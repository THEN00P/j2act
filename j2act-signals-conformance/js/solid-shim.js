// Solid's public surface, each function a call into the Java port through __bridge. Nothing here decides
// reactive behavior; a function that has no Java counterpart yet is left undefined, so its tests fail.
(function () {
  const b = globalThis.__bridge;
  if (typeof globalThis.structuredClone !== "function") {
    const cloneOf = v =>
      Array.isArray(v) ? v.map(cloneOf)
        : v !== null && typeof v === "object" ? Object.fromEntries(Object.entries(v).map(([k, x]) => [k, cloneOf(x)]))
          : v;
    globalThis.structuredClone = cloneOf;
  }
  const $REFRESH = Symbol("refresh");
  const ROOT_ERROR_HOOK = Symbol("root error hook");

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
      e.message = message;
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

  // What a test sees of an engine object it looks inside: `_subs` is the Java field `subs`.
  const mirrors = new Map();
  const NOT_PENDING = b.constantOf("NOT_PENDING");
  const mirror = java => {
    if (java === null || java === undefined || !Java.isJavaObject(java)) return java;
    if (b.isCollection(java) || b.isMap(java)) {
      const entries = b.entriesOf(java);
      return b.isMap(java) ? new Map(entries.map(([k, v]) => [mirror(k), mirror(v)])) : new Set(entries.map(mirror));
    }
    if (!b.isEngineObject(java)) return java;
    const key = b.identity(java);
    let m = mirrors.get(key);
    if (m === undefined) {
      const extras = {};
      const isThrowable = false;
      m = new Proxy(extras, {
        get(t, prop) {
          if (prop === "__node") return java;
          if (typeof prop !== "string" || prop === "then") return undefined;
          if (prop in t) return t[prop];
          if (prop === "id") return undefined;
          if (prop === "dispose") return guard(self => b.disposeOwner(java, self !== false));
          if (prop === "_context") {
            const out = {};
            for (const [k, v] of b.entriesOf(b.fieldOf(java, "ctx"))) out[Symbol("context")] = mirror(v);
            return out;
          }
          return mirror(b.fieldOf(java, prop));
        },
        set(t, prop, value) {
          if (prop === ROOT_ERROR_HOOK) b.setRootErrorHook(java, value);
          if (prop === "_name" && typeof value === "string") b.setName(java, value);
          t[prop] = value;
          return true;
        }
      });
      mirrors.set(key, m);
    }
    return m;
  };
  const unmirror = o => (o !== null && o !== undefined && o.__node !== undefined ? o.__node : o);
  const wrapOwner = java => (java === undefined ? null : mirror(java));
  const unwrapOwner = o => (o === null || o === undefined ? null : unmirror(o));

  const accessor = node => {
    const fn = guard(() => b.read(node));
    Object.defineProperty(fn, $REFRESH, { get: () => mirror(node) });
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
  b.setConsole((...args) => console.error(...args));
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
    const landed = b.refresh(unmirror(node));
    // A store resolves with itself, as it has no one value.
    const promise = typeof target === "function" ? landed : landed.then(() => target);
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

  const createContext = function (defaultValue, description) {
    return {
      id: Symbol(description),
      java: b.newContext(defaultValue, description, arguments.length > 0 && defaultValue === undefined),
      defaultValue
    };
  };
  const getContext = guard((context, owner = getOwner()) => b.getContext(context.java, unwrapOwner(owner)));
  const setContext = guard((context, value, owner = getOwner()) => b.setContext(context.java, value, unwrapOwner(owner)));

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
    b.affects(unmirror(node));
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
  const mapArray = guard((list, map, options) => {
    const keyed = options && options.keyed;
    const keyFn = typeof keyed === "function" ? keyed : undefined;
    const byIndex = keyed === false;
    const indexes = map.length > 1;
    const rowsMode = keyFn !== undefined || byIndex;
    const wrapped = (item, index) => {
      if (byIndex) return map(accessor(item), index);
      if (rowsMode) return map(accessor(item), indexes ? accessor(index) : undefined);
      return indexes ? map(item, accessor(index)) : map(item);
    };
    // A store's array is read as Solid's map.ts reads it: its container, then its length and each row.
    const rows = () => {
      const items = list();
      if (!items || typeof items !== "object" || items[$JT] === undefined) return items;
      items[$TRACK];
      const out = [];
      for (let i = 0, n = items.length; i < n; i++) out.push(items[i]);
      return out;
    };
    return accessor(b.mapArray(rows, wrapped, indexes, keyFn, byIndex, options && options.fallback));
  });
  const repeat = guard((count, map, options) =>
    accessor(b.repeat(count, map, options && options.from, options && options.fallback))
  );
  const configureClientErrors = config => {
    if (config && config.onError !== undefined && typeof config.onError !== "function") {
      throw new TypeError(`Invalid onError: expected a function, received ${typeof config.onError}.`);
    }
    b.configureClientErrors(config ? config.onError : undefined);
  };

  // ---- stores: the proxy is JavaScript's, and each trap is a call into the port's store
  const $TRACK = Symbol("STORE_TRACK"), $TARGET = Symbol("STORE_TARGET"), $PROXY = Symbol("STORE_PROXY"),
    $RECORD = Symbol("VIEW_RECORD");
  /** The port's target behind a view. $TARGET gives what a test may look inside, with Solid's field names. */
  const $JT = Symbol("java target");
  const targetMirrors = new Map();
  const slotMap = map => {
    if (map === null || map === undefined) return null;
    const out = {};
    for (const [k, v] of b.entriesOf(map)) out[typeof k === "number" || Java.isJavaObject(k) ? String(k) : k] = mirror(v);
    return out;
  };
  const targetMirror = jt => {
    if (jt === null || jt === undefined) return jt;
    const id = b.identity(jt);
    let m = targetMirrors.get(id);
    if (m === undefined) {
      m = new Proxy({}, {
        get(t, prop) {
          if (prop === "__node") return jt;
          if (typeof prop !== "string") return undefined;
          if (prop === "n" || prop === "h") return slotMap(b.fieldOf(jt, prop));
          if (prop === "k" || prop === "dk") return mirror(b.fieldOf(jt, prop)) ?? null;
          if (prop === "u") return targetMirror(b.fieldOf(jt, "u")) ?? null;
          if (prop === "_fam") {
            const fam = b.fieldOf(jt, "fam");
            if (fam === null || fam === undefined) return null;
            return {
              get live() {
                return new Set(b.entriesOf(b.fieldOf(fam, "live")).map(targetMirror));
              },
              get node() {
                return mirror(b.fieldOf(fam, "node")) ?? null;
              },
              get opt() {
                return b.fieldOf(fam, "opt");
              }
            };
          }
          const v = b.fieldOf(jt, prop);
          return v === undefined ? null : v;
        }
      });
      targetMirrors.set(id, m);
    }
    return m;
  };
  const hasOwn = Object.prototype.hasOwnProperty;
  const targets = new WeakMap();
  const wrappableProtos = new WeakMap();
  function isWrappable(obj) {
    if (obj == null || typeof obj !== "object" || Object.isFrozen(obj)) return false;
    const proto = Object.getPrototypeOf(obj);
    if (proto === Object.prototype || proto === null) return true;
    if (Array.isArray(obj)) return true;
    let wrappable = wrappableProtos.get(proto);
    if (wrappable === undefined) {
      wrappable = Object.prototype.toString.call(obj) === "[object Object]";
      wrappableProtos.set(proto, wrappable);
    }
    return wrappable;
  }
  const guardTraps = traps => Object.fromEntries(Object.entries(traps).map(([name, trap]) => [name, guard(trap)]));
  const storeTraps = guardTraps({
    get(t, key, receiver) {
      const jt = targets.get(t);
      const arr = Array.isArray(t);
      if (typeof key === "symbol") {
        if (key === $JT) return jt;
        if (key === $TARGET) return targetMirror(jt);
        if (key === $PROXY) return receiver;
        if (key === $RECORD) return undefined;
        if (key === $REFRESH) return mirror(b.storeDerive(jt));
        if (key === $TRACK) {
          b.storeTrack(jt);
          return undefined;
        }
      }
      // A method of the prototype is served untracked, as a key the object does not have itself.
      const proto = arr ? Array.prototype : Object.prototype;
      if (key in proto && !b.storeOwn(jt, key, arr)) {
        const pv = Reflect.get(proto, key, receiver);
        if (typeof pv === "function" || key === "constructor" || key === "__proto__") return typeof pv === "function" ? pv : undefined;
      }
      return b.storeGet(jt, key, arr);
    },
    has(t, key) {
      if (key === $TARGET || key === $PROXY || key === $TRACK || key === $JT) return true;
      if (key === $RECORD) return false;
      return b.storeHas(targets.get(t), key, Array.isArray(t));
    },
    ownKeys(t) {
      const keys = b.storeKeys(targets.get(t));
      const out = [];
      for (let i = 0; i < keys.length; i++) out.push(keys[i]);
      if (Array.isArray(t)) out.push("length");
      return out;
    },
    getOwnPropertyDescriptor(t, key) {
      const jt = targets.get(t);
      const arr = Array.isArray(t);
      if (arr && key === "length")
        return { value: b.storeGet(jt, key, true), writable: true, enumerable: false, configurable: false };
      const d = b.storeDescriptor(jt, key, arr);
      return d === undefined ? undefined : { value: d[0], writable: true, enumerable: true, configurable: true };
    },
    set(t, key, value) {
      if (key === "__proto__") return true;
      b.storeSet(targets.get(t), key, value, Array.isArray(t));
      return true;
    },
    defineProperty(t, key, desc) {
      if (key === "__proto__") return true;
      if ("value" in desc) b.storeSet(targets.get(t), key, desc.value, Array.isArray(t));
      return true;
    },
    deleteProperty(t, key) {
      b.storeDelete(targets.get(t), key, Array.isArray(t));
      return true;
    }
  });
  b.installStoreOps({
    isWrappable,
    isObject: v => typeof v === "object" && v !== null,
    same: (x, y) => x === y,
    sameKind: (x, y) => Object.getPrototypeOf(x) === Object.getPrototypeOf(y),
    has: (o, k) => hasOwn.call(o, k),
    get: (o, k) => (hasOwn.call(o, k) ? o[k] : undefined),
    keys: o => {
      if (!Array.isArray(o)) return Reflect.ownKeys(o);
      const out = [];
      for (let i = 0; i < o.length; i++) if (i in o) out.push(i);
      return out;
    },
    clone: o => (Array.isArray(o) ? o.slice() : Object.create(Object.getPrototypeOf(o), Object.getOwnPropertyDescriptors(o))),
    set: (o, k, v) => {
      o[k] = v;
    },
    delete: (o, k) => {
      delete o[k];
    },
    view: (jt, array) => {
      const t = array ? [] : {};
      targets.set(t, jt);
      return new Proxy(t, storeTraps);
    },
    targetOf: v => v[$JT],
    // Solid's wrapDraft: a proxy over a view whose reads and writes go through the derive's control.
    draft: function draft(inner, read, mutate, alias) {
      const px = new Proxy(Array.isArray(inner) ? [] : {}, {
        get(_, prop) {
          const value = read(() => inner[prop]);
          return typeof value === "object" && value !== null && prop !== $TARGET && prop !== $JT && !Java.isJavaObject(value)
            ? draft(value, read, mutate, alias)
            : value;
        },
        has: (_, prop) => read(() => prop in inner),
        set: (_, prop, value) =>
          mutate(() => {
            inner[prop] = value;
          }),
        deleteProperty: (_, prop) =>
          mutate(() => {
            delete inner[prop];
          }),
        ownKeys: () => read(() => Reflect.ownKeys(inner)),
        getOwnPropertyDescriptor(_, prop) {
          const d = read(() => Reflect.getOwnPropertyDescriptor(inner, prop));
          if (d && !(prop === "length" && Array.isArray(inner))) d.configurable = true;
          return d;
        },
        defineProperty: (_, prop, desc) =>
          mutate(() => {
            Reflect.defineProperty(inner, prop, desc);
          })
      });
      if (inner[$JT] !== undefined) alias(px, inner);
      return px;
    }
  });
  const keyOf = options => {
    const key = options && options.key !== undefined ? options.key : "id";
    return typeof key === "function" ? key : key === null ? null : row => (isWrappable(row) ? row[key] : undefined);
  };
  const projectionOptions = options => {
    if (options && options.shallow) throw new Error("[left out] shallowStore");
    if (options && options.seedLoadingValue) throw new Error("[left out] seedLoadingValue");
  };
  const createProjection = guard((fn, seed, options) => {
    projectionOptions(options);
    return b.createProjection(fn, seed || {}, keyOf(options));
  });
  const createStore = guard((first, second, third) => {
    if (typeof first === "function") {
      projectionOptions(third);
      const store = b.createProjection(first, second || {}, keyOf(third));
      return [store, guard(fn => b.setStore(store, fn))];
    }
    if (second && second.shallow) throw new Error("[left out] shallowStore");
    const proxy = b.createStore(first);
    return [proxy, guard(fn => b.setStore(proxy, fn))];
  });
  const reconcile = (value, key = "id") => {
    const keyFn = key === null ? null : typeof key === "string" ? item => (item == null ? undefined : item[key]) : key;
    return guard(state => b.reconcile(value, state, keyFn));
  };
  const snapshot = guard(value => b.snapshot(value));
  const deep = guard(value => b.deep(value));

  const solid = {
    createStore,
    createProjection,
    reconcile,
    snapshot,
    deep,
    isWrappable,
    $TRACK,
    $TARGET,
    $PROXY,
    $RECORD,
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
    ROOT_ERROR_HOOK,
    mapArray,
    repeat,
    __smallMoveHits: () => b.smallMoveHits(),
    ...Object.fromEntries(
      [
        "NOT_PENDING", "REACTIVE_NONE", "REACTIVE_CHECK", "REACTIVE_DIRTY", "REACTIVE_RECOMPUTING_DEPS", "REACTIVE_IN_HEAP",
        "REACTIVE_IN_HEAP_HEIGHT", "REACTIVE_ZOMBIE", "REACTIVE_DISPOSED", "REACTIVE_JOINED", "REACTIVE_LAZY",
        "REACTIVE_FRAME_READ", "REACTIVE_REASK", "REACTIVE_STAGED_READ", "REACTIVE_PROBED", "REACTIVE_LANE_DIRTY",
        "REACTIVE_LANE_READ", "REACTIVE_SCREEN_READ", "REACTIVE_PROBE_UNANSWERED", "REACTIVE_WINDOW", "CONFIG_OWNED_WRITE",
        "CONFIG_CHILDREN_FORBIDDEN", "CONFIG_AUTO_DISPOSE", "CONFIG_SYNC", "CONFIG_STAGED", "CONFIG_HELD", "CONFIG_HELD_FRAME",
        "CONFIG_INPUTS_PUBLISHED", "CONFIG_OVERRIDE", "CONFIG_STAGED_ERROR", "CONFIG_GUESS", "CONFIG_VERDICT", "CONFIG_REDERIVE",
        "CONFIG_LAGS", "CONFIG_AUTHORITATIVE", "CONFIG_FRESH_READ", "CONFIG_PROMOTED", "CONFIG_FRAME", "CONFIG_ASYNC",
        "STATUS_NONE", "STATUS_PENDING", "STATUS_ERROR", "STATUS_UNINITIALIZED", "EFFECT_RENDER", "EFFECT_USER", "EFFECT_TRACKED"
      ].map(n => [n, b.constantOf(n)])
    ),
    SUPPORTS_PROXY: true,
    __guard: guard,
    __wrapOwner: wrapOwner,
    __unwrapOwner: unwrapOwner
  };
  // Something the port leaves out names itself when a test reaches it, so a test that needs it is told
  // apart from one that fails for a real reason.
  const leftOut = name =>
    new Proxy(function () {}, {
      apply() {
        throw new Error(`[left out] ${name}`);
      },
      construct() {
        throw new Error(`[left out] ${name}`);
      },
      get(t, prop) {
        if (typeof prop === "symbol" || prop === "then" || prop === "__esModule") return undefined;
        return leftOut(`${name}.${prop}`);
      }
    });
  // esbuild copies a module's own names into an object with the module's prototype, so a missing name
  // reaches this prototype.
  Object.setPrototypeOf(
    solid,
    new Proxy(Object.create(null), {
      get(t, prop) {
        if (typeof prop === "symbol" || prop === "then" || prop === "__esModule" || prop === "default") return undefined;
        return leftOut(prop);
      }
    })
  );
  globalThis.__solid = solid;
})();
