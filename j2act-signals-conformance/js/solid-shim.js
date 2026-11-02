// Solid's public surface, each function a call into the Java port through __bridge. Nothing here decides
// reactive behavior; a function that has no Java counterpart yet is left undefined, so its tests fail.
(function () {
  const b = globalThis.__bridge;
  const $REFRESH = Symbol("refresh");

  class NotReadyError extends Error {
    constructor(source) {
      super("Value not ready");
      this.name = "NotReadyError";
      const node = source && source.__node !== undefined ? source.__node : source;
      this.source = node;
      this.__java = b.newNotReady(node);
    }
  }
  class NoOwnerError extends Error {
    constructor() {
      super("Context can only be accessed under a reactive root.");
      this.name = "NoOwnerError";
    }
  }
  class ContextNotFoundError extends Error {
    constructor() {
      super("Context must either be created with a default value or a value must be provided before accessing it.");
      this.name = "ContextNotFoundError";
    }
  }
  class TimeoutError extends Error {
    constructor() {
      super("until() timed out before the condition was met.");
      this.name = "TimeoutError";
    }
  }

  // Java errors reach JS code as JS errors, and go back to Java as what they were.
  b.setErrorFactory((java, kind, message, cause, source) => {
    let e;
    if (kind === "notReady") {
      e = Object.create(NotReadyError.prototype);
      Object.defineProperty(e, "message", { value: message, writable: true, enumerable: false });
      e.name = "NotReadyError";
      e.source = source;
    } else if (kind === "timeout") {
      e = new TimeoutError();
    } else if (kind === "noOwner") {
      e = new NoOwnerError();
    } else if (kind === "contextNotFound") {
      e = new ContextNotFoundError();
    } else {
      e = new Error(message);
      if (cause !== undefined) e.cause = cause;
    }
    e.__java = java;
    return e;
  });
  const guard = fn =>
    function (...args) {
      try {
        return fn.apply(this, args);
      } catch (e) {
        throw Java.isJavaObject(e) ? b.jsError(e) : e;
      }
    };

  const owners = new Map();
  const wrapOwner = java => {
    if (java === null || java === undefined) return null;
    const key = b.identity(java);
    let w = owners.get(key);
    if (w === undefined) {
      w = {
        __owner: java,
        get _parent() {
          return wrapOwner(b.ownerParent(java));
        },
        get _firstChild() {
          return wrapOwner(b.ownerFirstChild(java));
        },
        get _nextSibling() {
          return wrapOwner(b.ownerNextSibling(java));
        },
        get id() {
          return undefined;
        },
        get _context() {
          return w.__context;
        },
        set _context(v) {
          w.__context = v;
        },
        dispose(self) {
          return guard(() => b.disposeOwner(java, self !== false))();
        }
      };
      owners.set(key, w);
    }
    return w;
  };
  const unwrapOwner = o => (o === null || o === undefined ? null : o.__owner !== undefined ? o.__owner : o);

  const accessor = node => {
    const fn = guard(() => b.read(node));
    fn[$REFRESH] = node;
    fn.__node = node;
    return fn;
  };
  const setter = (node, write) =>
    guard(function (v) {
      return typeof v === "function" ? write(node, undefined, v) : write(node, v, null);
    });

  function createSignal(first, second) {
    if (typeof first === "function") {
      const node = b.derived(first, second);
      return [accessor(node), setter(node, (n, v, f) => b.setMemo(n, v, f))];
    }
    const node = b.signal(first, second);
    return [accessor(node), setter(node, (n, v, f) => b.setSignal(n, v, f))];
  }
  function createOptimistic(first, second) {
    if (typeof first === "function") {
      const node = b.derived(first, second);
      return [accessor(node), setter(node, (n, v, f) => b.optimisticWrite(n, v, f))];
    }
    const node = b.signal(first, second);
    return [accessor(node), setter(node, (n, v, f) => b.optimisticWrite(n, v, f))];
  }
  const createMemo = guard((compute, options) => accessor(b.memo(compute, options)));
  const effectArgs = fn => [(fn && fn.effect) || fn, fn && fn.error];
  const createEffect = guard((compute, effectFn, options) => {
    if (effectFn === undefined) {
      throw new Error(
        "[MISSING_EFFECT_FN] createEffect requires both a compute function and an effect function. " +
          "Use `createEffect(() => signal(), value => doWork(value))`."
      );
    }
    const [fn, error] = effectArgs(effectFn);
    b.effect(compute, fn, error, true, options);
  });
  const createRenderEffect = guard((compute, effectFn, options) => {
    b.effect(compute, effectFn, undefined, false, options);
  });
  const createTrackedEffect = guard((compute, options) => b.trackedEffect(compute, options));
  const createRoot = guard((fn, options) =>
    b.createRoot(java => {
      const owner = wrapOwner(java);
      return fn(guard((self) => b.disposeOwner(java, self !== false)), owner);
    }, options)
  );
  const createOwner = guard(() => wrapOwner(b.createOwner()));
  const runWithOwner = guard((owner, fn) => b.runWithOwner(unwrapOwner(owner), fn));
  const getOwner = () => wrapOwner(b.getOwner());
  const getObserver = () => wrapOwner(b.getObserver());
  const isDisposed = o => b.isDisposed(unwrapOwner(o));
  const untrack = guard(fn => b.untrack(fn));
  const onCleanup = guard(fn => b.onCleanup(fn));
  const flush = guard(fn => (fn === undefined ? b.flush() : b.flushWith(fn)));
  const resetErrorHalt = guard(() => b.resetErrorHalt());
  b.setTimers((f, ms) => setTimeout(f, ms), id => clearTimeout(id));

  const isPending = guard(fn => b.isPending(fn));
  const latest = guard(fn => b.latest(fn));
  const resolve = guard(fn => b.resolve(fn));
  const refresh = guard(target => {
    const node = target && target[$REFRESH];
    if (!node) {
      throw new Error(
        "[INVALID_REFRESH_TARGET] refresh() expects a Solid source accessor or refreshable store. " +
          "Pass the original source target, not a wrapper function or derived property read."
      );
    }
    const promise = b.refresh(node);
    promise.catch(() => {});
    return promise;
  });
  const until = guard((fn, options) => b.until(fn, options && options.timeout, options && options.signal));
  const onSettled = guard(fn => b.onSettled(fn));
  const onFlush = guard(fn => b.onFlush(fn));
  const createReaction = guard((effectFn, options) => {
    const reaction = b.createReaction((effectFn && effectFn.effect) || effectFn, effectFn && effectFn.error, options);
    return guard(tracking => reaction(tracking));
  });
  const action = genFn =>
    guard(function (...args) {
      return b.action(() => genFn(...args));
    });

  const createContext = (defaultValue, description) => ({ id: b.newContextId(), defaultValue });
  const getContext = guard((context, owner = getOwner()) =>
    b.getContext(context.id, context.defaultValue, unwrapOwner(owner))
  );
  const setContext = guard((context, value, owner = getOwner()) =>
    b.setContext(context.id, value, context.defaultValue, unwrapOwner(owner))
  );

  function flattenArray(children, results = [], options) {
    let notReady = null;
    let needsUnwrap = false;
    for (let i = 0; i < children.length; i++) {
      try {
        let child = children[i];
        if (typeof child === "function" && !child.length) {
          if (options && options.doNotUnwrap) {
            results.push(child);
            needsUnwrap = true;
            continue;
          }
          do {
            child = child();
          } while (typeof child === "function" && !child.length);
        }
        if (Array.isArray(child)) {
          needsUnwrap = flattenArray(child, results, options) || needsUnwrap;
        } else if (options && options.skipNonRendered && (child == null || child === true || child === false || child === "")) {
        } else results.push(child);
      } catch (e) {
        if (!(e instanceof NotReadyError)) throw e;
        notReady = e;
      }
    }
    if (notReady) throw notReady;
    return needsUnwrap;
  }
  function flatten(children, options) {
    if (typeof children === "function" && !children.length) {
      if (options && options.doNotUnwrap) return children;
      do {
        children = children();
      } while (typeof children === "function" && !children.length);
    }
    if (options && options.skipNonRendered && (children == null || children === true || children === false || children === "")) return;
    if (Array.isArray(children)) {
      const results = [];
      if (flattenArray(children, results, options)) {
        return () => {
          const nested = [];
          flattenArray(results, nested, { ...options, doNotUnwrap: false });
          return nested;
        };
      }
      return results;
    }
    return children;
  }
  b.setFlatten(flatten);

  const createLoadingBoundary = guard((fn, fallback, options) =>
    accessor(b.createLoadingBoundary(fn, fallback, options && options.on))
  );
  const createErrorBoundary = guard((fn, fallback) =>
    accessor(b.createErrorBoundary(fn, (node, reset) => fallback(accessor(node), guard(() => reset()))))
  );
  const createRevealOrder = guard((fn, options) =>
    b.createRevealOrder(fn, options && options.order, options && options.collapsed)
  );
  const createDeferred = guard((fn, options) => accessor(b.createDeferred(fn, options)));
  const affects = guard(function (target, key) {
    if (arguments.length > 2) {
      throw new Error(
        "[INVALID_AFFECTS_TARGET] affects() takes a single optional key — extra keys are not a path. Mark each slot " +
          'with its own affects(record, key) call, or pass the nested record itself: affects(state.user, "name").'
      );
    }
    const node = target && target[$REFRESH];
    if (!node) {
      throw new Error(
        "[INVALID_AFFECTS_TARGET] affects() expects a Solid source accessor or a store. Pass the store proxy (optionally " +
          "with a property key) or the original accessor, not a wrapper function or an already-read value."
      );
    }
    if (key !== undefined) {
      throw new Error(
        "[INVALID_AFFECTS_TARGET] affects() keys are only valid on store targets. An accessor is a single slot — pass it " +
          "alone, or target the store record that owns the property."
      );
    }
    b.affects(node);
  });

  const iterableOf = java => ({
    __iterable: java,
    [Symbol.asyncIterator]() {
      const it = guard(() => b.iterate(java))();
      return {
        next: guard(() => b.iteratorNext(it)),
        return: guard(value => {
          b.iteratorClose(it);
          return Promise.resolve({ done: true, value });
        })
      };
    }
  });
  const from = guard(source => {
    let subscribe = null;
    if (typeof source === "function") subscribe = source;
    else {
      const $$observable = Symbol.observable;
      const interop = ($$observable && source && source[$$observable]) ?? (source && source["@@observable"]);
      const obs = typeof interop === "function" ? interop.call(source) : source;
      if (obs && typeof obs.subscribe === "function") {
        subscribe = (next, error, complete) => obs.subscribe({ next, error, complete });
      }
    }
    if (subscribe) {
      return iterableOf(
        b.fromSubscribe((next, error, complete) => {
          const t = subscribe(guard(v => next(v)), guard(e => error(e)), guard(() => complete()));
          return typeof t === "function" ? t : t && typeof t.unsubscribe === "function" ? () => t.unsubscribe() : undefined;
        })
      );
    }
    if (source && typeof source[Symbol.asyncIterator] === "function") return iterableOf(b.wrapIterable(source));
    throw new TypeError("from(): expected a subscribe function, an Observable or an AsyncIterable");
  });
  const configureClientErrors = config => {
    if (config && config.onError !== undefined && typeof config.onError !== "function") {
      throw new TypeError(`Invalid onError: expected a function, received ${typeof config.onError}.`);
    }
    b.configureClientErrors(config ? config.onError : undefined);
  };

  const solid = {
    $REFRESH,
    NotReadyError,
    NoOwnerError,
    ContextNotFoundError,
    TimeoutError,
    createSignal,
    createOptimistic,
    createMemo,
    createEffect,
    createRenderEffect,
    createTrackedEffect,
    createRoot,
    createOwner,
    runWithOwner,
    getOwner,
    getObserver,
    isDisposed,
    untrack,
    onCleanup,
    flush,
    resetErrorHalt,
    isEqual: (a, b2) => a === b2,
    isPending,
    latest,
    resolve,
    refresh,
    until,
    onSettled,
    onFlush,
    createReaction,
    action,
    createContext,
    getContext,
    setContext,
    flatten,
    createLoadingBoundary,
    createErrorBoundary,
    createRevealOrder,
    createDeferred,
    affects,
    from,
    configureClientErrors,
    SUPPORTS_PROXY: true,
    __guard: guard,
    __wrapOwner: wrapOwner,
    __unwrapOwner: unwrapOwner
  };
  globalThis.__solid = solid;
})();
