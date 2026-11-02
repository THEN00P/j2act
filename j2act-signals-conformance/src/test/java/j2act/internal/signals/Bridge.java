package j2act.internal.signals;

import j2act.internal.signals.Errors.NotReadyError;
import j2act.internal.signals.Errors.StatusError;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyExecutable;

/**
 * What js/solid-shim.js calls: the port's functions, with JS values on one side and Java values on the
 * other. JS `undefined` is Java null, as a port's "no value" is; a JS string, number or boolean is its Java
 * value, and anything else stays a Value, whose equals is identity, as `===` is on objects.
 */
public final class Bridge {
  private final Context context;
  private final Value undefined;
  private final Value queueMicrotask;
  private Value errorFactory;
  private final Map<Throwable, Value> jsErrors = new IdentityHashMap<>();
  private final Map<Object, Integer> identities = new IdentityHashMap<>();
  final Runtime rt;

  /** A value a JS `throw` left, kept as it was thrown: anything can be thrown in JS. */
  static final class JsThrown extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final transient Value thrown;
    final PolyglotException origin;

    JsThrown(PolyglotException origin, Value thrown) {
      super(messageOf(thrown), null, false, false);
      this.origin = origin;
      this.thrown = thrown;
    }

    private static String messageOf(Value thrown) {
      if (thrown != null && thrown.hasMember("message")) {
        Value m = thrown.getMember("message");
        return m == null || m.isNull() ? null : m.toString();
      }
      return thrown == null ? null : thrown.toString();
    }
  }

  Bridge(Context context) {
    this.context = context;
    this.undefined = context.eval("js", "undefined");
    this.queueMicrotask = context.eval("js", "(f) => queueMicrotask(f)");
    Runnable[] box = new Runnable[1];
    this.rt = new Runtime(task -> task.run(), task -> queueMicrotask.execute((ProxyExecutable) args -> {
      task.run();
      return undefined;
    }));
  }

  public void setErrorFactory(Value factory) {
    this.errorFactory = factory;
  }

  public int identity(Object o) {
    return identities.computeIfAbsent(o, k -> identities.size() + 1);
  }

  public Object newContextId() {
    return new Object();
  }

  public Object newNotReady(Object source) {
    return new NotReadyError(source instanceof Node ? (Node) source : null);
  }

  // ---- Conversions

  Object toJava(Value v) {
    if (v == null) {
      return null;
    }
    if (v.isNull()) {
      return "undefined".equals(v.toString()) ? null : v;
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    if (v.isString()) {
      return v.asString();
    }
    if (v.isNumber()) {
      if (v.fitsInInt()) {
        return v.asInt();
      }
      if (v.fitsInDouble()) {
        return v.asDouble();
      }
      return v;
    }
    if (v.isHostObject()) {
      return v.asHostObject();
    }
    return v;
  }

  /** A compute's result: a thenable becomes a future and an async iterable an AsyncIterable, as handleAsync reads them. */
  Object toJavaResult(Value v) {
    if (v == null || v.isNull() || !v.hasMembers() || v.isHostObject()) {
      return toJava(v);
    }
    Value java0 = v.hasMember("__iterable") ? v.getMember("__iterable") : null;
    if (java0 != null && java0.isHostObject()) {
      return java0.asHostObject();
    }
    Value iteratorFn = asyncIteratorOf(v);
    if (iteratorFn != null) {
      return new JsIterable(v, iteratorFn);
    }
    Value java = v.hasMember("__iterable") ? v.getMember("__iterable") : null;
    if (java != null && java.isHostObject()) {
      return java.asHostObject();
    }
    Value then = v.getMember("then");
    if (then != null && then.canExecute() && !v.canExecute()) {
      return futureOf(v);
    }
    return v;
  }

  private Value asyncIteratorOf(Value v) {
    Value get = context.eval("js", "(o) => o[Symbol.asyncIterator]").execute(v);
    return get != null && get.canExecute() ? get : null;
  }

  CompletableFuture<Object> futureOf(Value thenable) {
    CompletableFuture<Object> future = new CompletableFuture<>();
    ProxyExecutable resolve = args -> {
      future.complete(args.length == 0 ? null : toJavaResultNested(args[0]));
      return undefined;
    };
    ProxyExecutable reject = args -> {
      future.completeExceptionally(args.length == 0 ? new JsThrown(null, undefined) : thrown(args[0]));
      return undefined;
    };
    try {
      thenable.invokeMember("then", resolve, reject);
    } catch (PolyglotException e) {
      future.completeExceptionally(unwrap(e));
    }
    return future;
  }

  private Object toJavaResultNested(Value v) {
    return v != null && v.hasMembers() && !v.isHostObject() && asyncIteratorOf(v) != null
      ? new JsIterable(v, asyncIteratorOf(v)) : toJava(v);
  }

  Throwable thrown(Value v) {
    if (v != null && v.hasMember("__java")) {
      Value java = v.getMember("__java");
      if (java != null && java.isHostObject() && java.asHostObject() instanceof Throwable) {
        return java.asHostObject();
      }
    }
    return new JsThrown(null, v);
  }

  Object[] toJs(Object... args) {
    Object[] out = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      out[i] = js(args[i]);
    }
    return out;
  }

  Object js(Object o) {
    return o == null ? undefined : o instanceof Throwable ? jsError(o) : o;
  }

  Value call(Value fn, Object... args) {
    try {
      return fn.execute(toJs(args));
    } catch (PolyglotException e) {
      throw Errors.<RuntimeException>sneak(unwrap(e));
    }
  }

  Throwable unwrap(PolyglotException e) {
    if (e.isHostException()) {
      return e.asHostException();
    }
    if (e.isCancelled() || e.isInterrupted() || e.isInternalError()) {
      return e;
    }
    Value thrown = e.getGuestObject();
    Throwable t = thrown(thrown);
    return t;
  }

  /** The JS error a Java error reads as in JS code. */
  public Value jsError(Object e) {
    Throwable t = (Throwable) e;
    if (t instanceof JsThrown) {
      return ((JsThrown) t).thrown;
    }
    Value known = jsErrors.get(t);
    if (known != null) {
      return known;
    }
    if (t instanceof PolyglotException) {
      PolyglotException p = (PolyglotException) t;
      if (p.isGuestException() && p.getGuestObject() != null) {
        return p.getGuestObject();
      }
    }
    Object cause = t instanceof StatusError && t.getCause() != null ? jsError(t.getCause()) : undefined;
    String kind = t instanceof NotReadyError ? "notReady" : t instanceof Errors.TimeoutError ? "timeout"
      : t instanceof Errors.NoOwnerError ? "noOwner" : t instanceof Errors.ContextNotFoundError ? "contextNotFound" : "error";
    Object source = t instanceof NotReadyError && ((NotReadyError) t).source != null ? ((NotReadyError) t).source : undefined;
    Value wrapped = errorFactory.execute(t, kind, String.valueOf(t.getMessage()), cause, source);
    jsErrors.put(t, wrapped);
    return wrapped;
  }

  /** An error as user code gets it. */
  Object userError(Throwable t) {
    return t == null ? undefined : jsError(t);
  }

  // ---- Options

  private static Value member(Value options, String name) {
    if (options == null || options.isNull() || !options.hasMember(name)) {
      return null;
    }
    Value v = options.getMember(name);
    return v == null || (v.isNull() && "undefined".equals(v.toString())) ? null : v;
  }

  Core.NodeOptions nodeOptions(Value o) {
    if (o == null || o.isNull()) {
      return null;
    }
    Core.NodeOptions n = new Core.NodeOptions();
    Value equals = member(o, "equals");
    if (equals != null) {
      if (equals.canExecute()) {
        n.equals = (a, b) -> truthy(call(equals, a, b));
      } else if (equals.isBoolean() && !equals.asBoolean()) {
        n.equals = null;
      }
    }
    n.ownedWrite = isTrue(member(o, "ownedWrite"));
    n.lazy = isTrue(member(o, "lazy"));
    n.sync = isTrue(member(o, "sync"));
    Value unobserved = member(o, "unobserved");
    if (unobserved != null && unobserved.canExecute()) {
      n.unobserved = () -> call(unobserved);
    }
    if (o.hasMember("loadingValue")) {
      Value loading = member(o, "loadingValue");
      n.hasLoadingValue = loading != null || isOwn(o, "loadingValue");
      n.loadingValue = toJava(loading);
    }
    return n;
  }

  private boolean isOwn(Value o, String key) {
    return context.eval("js", "(o, k) => k in o && o[k] !== undefined").execute(o, key).asBoolean();
  }

  private static boolean isTrue(Value v) {
    return v != null && v.isBoolean() && v.asBoolean();
  }

  boolean truthy(Value v) {
    if (v == null || v.isNull()) {
      return false;
    }
    if (v.isBoolean()) {
      return v.asBoolean();
    }
    if (v.isNumber()) {
      return v.fitsInDouble() && v.asDouble() != 0 && !Double.isNaN(v.asDouble());
    }
    if (v.isString()) {
      return !v.asString().isEmpty();
    }
    return true;
  }

  // ---- signals.ts

  private Node.Compute compute(Value fn) {
    return prev -> toJavaResult(call(fn, prev));
  }

  private UnaryOperator<Object> updater(Value fn) {
    return prev -> toJava(call(fn, prev));
  }

  public Object signal(Value value, Value options) {
    return rt.core.signal(toJava(value), nodeOptions(options));
  }

  public Object derived(Value fn, Value options) {
    Node node = rt.core.computed(compute(fn), nodeOptions(options));
    node.config &= ~Constants.CONFIG_AUTO_DISPOSE;
    return node;
  }

  public Object memo(Value fn, Value options) {
    return rt.core.computed(compute(fn), nodeOptions(options));
  }

  public Object read(Object node) {
    return js(rt.core.read((Node) node));
  }

  public Object setSignal(Object node, Value value, Value fn) {
    return js(fn != null && fn.canExecute() ? rt.core.updateSignal((Node) node, updater(fn))
      : rt.core.setSignal((Node) node, toJava(value)));
  }

  public Object setMemo(Object node, Value value, Value fn) {
    return js(fn != null && fn.canExecute() ? rt.core.setMemo((Node) node, null, updater(fn))
      : rt.core.setMemo((Node) node, toJava(value), null));
  }

  private Runnable cleanupOf(Value result) {
    if (result == null || !result.canExecute()) {
      return null;
    }
    return () -> call(result);
  }

  public void effect(Value compute, Value effectFn, Value errorFn, boolean user, Value options) {
    Effects.EffectOptions o = new Effects.EffectOptions();
    o.user = user;
    o.defer = isTrue(member(options, "defer"));
    o.schedule = isTrue(member(options, "schedule"));
    o.node = nodeOptions(options);
    Node.EffectFn fn = (value, prev) -> {
      Value r = call(effectFn, value, prev);
      if (r != null && !r.isNull() && !r.canExecute()) {
        throw new IllegalStateException("[INVALID_EFFECT_CLEANUP] effect callback returned an invalid cleanup value."
          + " Return a cleanup function or undefined.");
      }
      return cleanupOf(r);
    };
    Node.ErrorFn error = errorFn == null || !errorFn.canExecute() ? null : (err, cleanup) ->
      call(errorFn, userError(err), (ProxyExecutable) args -> {
        cleanup.run();
        return undefined;
      });
    rt.effects.effect(compute(compute), fn, error, o);
  }

  public void trackedEffect(Value compute, Value options) {
    rt.effects.trackedEffect(() -> {
      Value r = call(compute);
      if (r != null && !r.isNull() && !r.canExecute()) {
        throw new IllegalStateException("Tracked effect returned an invalid cleanup value.");
      }
      return cleanupOf(r);
    });
  }

  public Object onCleanup(Value fn) {
    if (fn != null && fn.canExecute()) {
      rt.owners.cleanup(() -> call(fn), rt.core.context);
    }
    return fn;
  }

  public Object createRoot(Value fn, Value options) {
    Function<Owner, Object> init = owner -> js(call(fn, owner));
    return rt.owners.createRoot(init);
  }

  public Object createOwner() {
    return rt.owners.createOwner();
  }

  public void disposeOwner(Object owner, boolean self) {
    ((Owner) owner).dispose(self);
  }

  public Object runWithOwner(Object owner, Value fn) {
    return rt.core.runWithOwner((Owner) owner, () -> js(call(fn)));
  }

  public Object getOwner() {
    return rt.owners.getOwner();
  }

  public Object getObserver() {
    return rt.owners.getObserver();
  }

  public boolean isDisposed(Object owner) {
    return rt.owners.isDisposed((Owner) owner);
  }

  public Object ownerParent(Object owner) {
    return ((Owner) owner).parent;
  }

  public Object ownerFirstChild(Object owner) {
    return ((Owner) owner).firstChild;
  }

  public Object ownerNextSibling(Object owner) {
    return ((Owner) owner).nextSibling;
  }

  public Object untrack(Value fn) {
    return rt.core.untrack(() -> js(call(fn)));
  }

  public void flush() {
    rt.scheduler.flush();
  }

  public Object flushWith(Value fn) {
    return rt.scheduler.flush(() -> js(call(fn)));
  }

  public void resetErrorHalt() {
    rt.scheduler.resetErrorHalt();
  }

  // ---- The rest of the surface

  private Supplier<Object> supplier(Value fn) {
    return () -> toJava(call(fn));
  }

  public Value promiseOf(CompletionStage<Object> stage) {
    ProxyExecutable executor = args -> {
      Value resolve = args[0];
      Value reject = args[1];
      stage.whenComplete((v, e) -> {
        if (e != null) {
          Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
          reject.execute(jsError(cause));
        } else {
          resolve.execute(js(v));
        }
      });
      return undefined;
    };
    return context.eval("js", "(f) => new Promise(f)").execute(executor);
  }

  public Object optimisticWrite(Object node, Value value, Value fn) {
    return js(fn != null && fn.canExecute() ? rt.lanes.optimisticWrite((Node) node, null, updater(fn))
      : rt.lanes.optimisticWrite((Node) node, toJava(value), null));
  }

  public Object latest(Value fn) {
    return js(rt.verdict.latest(() -> toJava(call(fn))));
  }

  public boolean isPending(Value fn) {
    return rt.verdict.isPending(() -> toJava(call(fn)));
  }

  public Value resolve(Value fn) {
    return promiseOf(rt.signals.resolve(supplier(fn)));
  }

  public Value refresh(Object node) {
    return promiseOf(rt.signals.refresh((Node) node));
  }

  public Value until(Value fn, Value timeout, Value signal) {
    Long ms = timeout == null || timeout.isNull() || !timeout.isNumber() ? null : Long.valueOf(timeout.asLong());
    Signals.Abort abort = signal == null || signal.isNull() ? null : new Signals.Abort() {
      private final Map<Runnable, Value> listeners = new IdentityHashMap<>();

      @Override public boolean aborted() {
        return truthy(signal.getMember("aborted"));
      }

      @Override public Throwable reason() {
        return thrown(signal.getMember("reason"));
      }

      @Override public void addListener(Runnable listener) {
        ProxyExecutable f = args -> {
          listener.run();
          return undefined;
        };
        Value wrapped = context.eval("js", "(f) => () => f()").execute(f);
        listeners.put(listener, wrapped);
        signal.invokeMember("addEventListener", "abort", wrapped);
      }

      @Override public void removeListener(Runnable listener) {
        Value wrapped = listeners.remove(listener);
        if (wrapped != null) {
          signal.invokeMember("removeEventListener", "abort", wrapped);
        }
      }
    };
    return promiseOf(rt.signals.until(supplier(fn), ms, abort));
  }

  public void setTimers(Value setTimeout, Value clearTimeout) {
    rt.timer = (task, millis) -> {
      ProxyExecutable f = args -> {
        task.run();
        return undefined;
      };
      Value id = setTimeout.execute(f, millis);
      return () -> clearTimeout.execute(id);
    };
  }

  public void onSettled(Value fn) {
    rt.signals.onSettled(() -> {
      Value r = call(fn);
      if (r != null && !r.isNull() && !r.canExecute()) {
        throw new IllegalStateException("onSettled returned an invalid cleanup value.");
      }
      return cleanupOf(r);
    });
  }

  public void onFlush(Value fn) {
    rt.signals.onFlush(() -> call(fn));
  }

  public Object createReaction(Value effectFn, Value errorFn, Value options) {
    Node.ErrorFn error = errorFn == null || !errorFn.canExecute() ? null : (err, cleanup) ->
      call(errorFn, userError(err), (ProxyExecutable) args -> {
        cleanup.run();
        return undefined;
      });
    java.util.function.Consumer<Runnable> reaction = rt.signals.createReaction(() -> {
      Value r = effectFn == null || !effectFn.canExecute() ? null : call(effectFn);
      if (r != null && !r.isNull() && !r.canExecute()) {
        throw new IllegalStateException(
          "Reaction callback returned an invalid cleanup value. Return a cleanup function or undefined.");
      }
      return cleanupOf(r);
    }, error);
    return (ProxyExecutable) args -> {
      Value tracking = args[0];
      reaction.accept(() -> call(tracking));
      return undefined;
    };
  }

  public Object getContext(Object id, Value defaultValue, Object owner) {
    return js(rt.owners.getContext(id, toJava(defaultValue), (Owner) owner));
  }

  public void setContext(Object id, Value value, Value defaultValue, Object owner) {
    rt.owners.setContext(id, toJava(value), toJava(defaultValue), (Owner) owner);
  }

  public Object createLoadingBoundary(Value fn, Value fallback, Value on) {
    return rt.boundaries.createLoadingBoundary(compute(fn), () -> toJavaResult(call(fallback)),
      on == null || !on.canExecute() ? null : () -> toJava(call(on)));
  }

  public Object createErrorBoundary(Value fn, Value fallback) {
    return rt.boundaries.createErrorBoundary(compute(fn), (node, reset) -> toJavaResult(call(fallback, node,
      (ProxyExecutable) args -> {
        reset.run();
        return undefined;
      })));
  }

  public Object createRevealOrder(Value fn, Value order, Value collapsed) {
    return js(rt.reveal.createRevealOrder(v -> toJava(call(fn)),
      order == null || !order.canExecute() ? null : () -> {
        Value o = call(order);
        return o.isString() ? o.asString() : String.valueOf(o);
      },
      collapsed == null || !collapsed.canExecute() ? null : () -> truthy(call(collapsed))));
  }

  public Object createDeferred(Value fn, Value options) {
    return rt.deferred.createDeferred(compute(fn), nodeOptions(options));
  }

  public void affects(Object node) {
    rt.marks.affects((Node) node);
  }

  public void setFlatten(Value fn) {
    rt.flatten = v -> toJavaResult(call(fn, v));
  }

  public void configureClientErrors(Value fn) {
    rt.clientErrorHook = fn == null || !fn.canExecute() ? null : (e, o) -> call(fn, userError(e), o);
  }

  public void setRootErrorHook(Object owner, Value fn) {
    ((Owner) owner).errorHook = fn == null || !fn.canExecute() ? null : (e, o) -> call(fn, userError(e), o);
  }

  public Object fromSubscribe(Value subscribe) {
    return rt.from.from((next, error, complete) -> {
      Value teardown = call(subscribe, (ProxyExecutable) a -> {
        next.accept(a.length == 0 ? null : toJava(a[0]));
        return undefined;
      }, (ProxyExecutable) a -> {
        error.accept(a.length == 0 ? new JsThrown(null, undefined) : thrown(a[0]));
        return undefined;
      }, (ProxyExecutable) a -> {
        complete.run();
        return undefined;
      });
      return cleanupOf(teardown);
    });
  }

  public Object wrapIterable(Value source) {
    return rt.from.wrap(new JsIterable(source, asyncIteratorOf(source)));
  }

  /** A Java AsyncIterable's iterator, as JS code steps it. */
  public Object iterate(Object iterable) {
    @SuppressWarnings("unchecked")
    Async.AsyncIterator<Object> it = ((Async.AsyncIterable<Object>) iterable).iterator();
    return it;
  }

  public Value iteratorNext(Object iterator) {
    @SuppressWarnings("unchecked")
    Async.AsyncIterator<Object> it = (Async.AsyncIterator<Object>) iterator;
    return promiseOf(it.next().thenApply(step -> context.eval("js", "(done, value) => ({done, value})")
      .execute(step.done, js(step.value))));
  }

  public void iteratorClose(Object iterator) {
    ((Async.AsyncIterator<?>) iterator).close();
  }

  public Value action(Value makeIterator) {
    return promiseOf(rt.action.run(() -> {
      Value it = call(makeIterator);
      return new Action.Generator() {
        private Object step(Value r) {
          if (r.hasMember("then") && r.getMember("then").canExecute()) {
            return futureOf(r).thenApply(v -> yieldOf(v instanceof Value ? (Value) v : undefined));
          }
          return yieldOf(r);
        }

        private Action.Yield yieldOf(Value r) {
          boolean done = truthy(r.getMember("done"));
          Value value = r.getMember("value");
          return done ? Action.Yield.end(toJava(value)) : Action.Yield.of(toJavaResult(value));
        }

        @Override public Object next(Object value) {
          try {
            return step(it.invokeMember("next", js(value)));
          } catch (PolyglotException e) {
            throw Errors.<RuntimeException>sneak(unwrap(e));
          }
        }

        @Override public Object raise(Throwable error) {
          try {
            return step(it.invokeMember("throw", jsError(error)));
          } catch (PolyglotException e) {
            throw Errors.<RuntimeException>sneak(unwrap(e));
          }
        }
      };
    }));
  }

  // ---- Async iterables

  private final class JsIterable implements Async.AsyncIterable<Object> {
    final Value source;
    final Value iteratorFn;

    JsIterable(Value source, Value iteratorFn) {
      this.source = source;
      this.iteratorFn = iteratorFn;
    }

    @Override public Async.AsyncIterator<Object> iterator() {
      Value it = context.eval("js", "(o, f) => f.call(o)").execute(source, iteratorFn);
      return new Async.AsyncIterator<>() {
        @Override public CompletionStage<Async.Step<Object>> next() {
          Value step = it.invokeMember("next");
          if (step.hasMember("then") && step.getMember("then").canExecute()) {
            return futureOf(step).thenApply(v -> stepOf(v));
          }
          return CompletableFuture.completedFuture(stepOf(step));
        }

        private Async.Step<Object> stepOf(Object v) {
          Value r = v instanceof Value ? (Value) v : null;
          boolean done = r != null && r.hasMember("done") && r.getMember("done").isBoolean()
            && r.getMember("done").asBoolean();
          Object value = r != null && r.hasMember("value") ? toJava(r.getMember("value")) : null;
          return done ? Async.Step.end() : Async.Step.of(value);
        }

        @Override public void close() {
          if (it.hasMember("return") && it.getMember("return").canExecute()) {
            Value returned = it.invokeMember("return");
            if (returned != null && returned.hasMember("then") && returned.getMember("then").canExecute()) {
              ProxyExecutable ignore = args -> undefined;
              returned.invokeMember("then", undefined, ignore);
            }
          }
        }
      };
    }
  }

  @SuppressWarnings("unused")
  private static List<Object> unused() {
    return new ArrayList<>();
  }
}
