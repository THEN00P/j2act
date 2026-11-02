// Just enough of vitest for Solid's test files: describe/it, expect, vi and timers on a virtual clock.
(function () {
  const root = { name: "", parent: null, befores: [], afters: [], beforeAlls: [], afterAlls: [], children: [] };
  let scope = root;
  let onlyMode = false;

  function addTest(name, fn, opts) {
    const test = { name, fn, parent: scope, ...opts };
    if (test.only) onlyMode = true;
    scope.children.push(test);
  }
  function describeImpl(name, fn, opts) {
    const s = { name, parent: scope, befores: [], afters: [], beforeAlls: [], afterAlls: [], children: [], suite: true, ...opts };
    if (s.only) onlyMode = true;
    scope.children.push(s);
    const prev = scope;
    scope = s;
    try {
      fn && fn();
    } finally {
      scope = prev;
    }
  }
  const fmt = (name, args) => {
    let i = 0;
    return String(name).replace(/%[sdifjoO%]/g, m => (m === "%%" ? "%" : String(args[i++])));
  };
  function each(register) {
    return cases => (name, fn) => {
      for (const c of cases) {
        const args = Array.isArray(c) ? c : [c];
        const title = typeof c === "object" && !Array.isArray(c) && c !== null
          ? String(name).replace(/\$(\w+)/g, (_, k) => String(c[k]))
          : fmt(name, args);
        register(title, () => fn(...args));
      }
    };
  }
  function makeIt() {
    const it = (name, fn) => addTest(name, fn, {});
    it.skip = (name, fn) => addTest(name, fn, { skip: true });
    it.only = (name, fn) => addTest(name, fn, { only: true });
    it.todo = name => addTest(name, null, { skip: true });
    it.fails = (name, fn) => addTest(name, fn, { fails: true });
    it.fails.each = each((n, f) => it.fails(n, f));
    it.skipIf = c => (c ? it.skip : it);
    it.runIf = c => (c ? it : it.skip);
    it.concurrent = it;
    it.each = each((n, f) => it(n, f));
    it.skip.each = each((n, f) => it.skip(n, f));
    return it;
  }
  const it = makeIt();
  const describe = (name, fn) => describeImpl(name, fn, {});
  describe.skip = (name, fn) => describeImpl(name, fn, { skip: true });
  describe.only = (name, fn) => describeImpl(name, fn, { only: true });
  describe.skipIf = c => (c ? describe.skip : describe);
  describe.runIf = c => (c ? describe : describe.skip);
  describe.concurrent = describe;
  describe.each = each((n, f) => describe(n, f));

  // ---- Timers. Real timers run on a virtual clock, whenever no microtask is left; fake ones wait for vi.
  const realSetTimeout = (fn, ms, repeat) => schedule(realTimers, fn, ms, repeat);
  let clock = 0;
  let seq = 1;
  const realTimers = [];
  const fakeTimers = [];
  let fake = false;
  let fakeNow = 0;
  function schedule(list, fn, ms, repeat) {
    const base = list === fakeTimers ? fakeNow : clock;
    const t = { id: seq++, at: base + Math.max(+ms || 0, 0), fn, repeat: repeat ? Math.max(+ms || 1, 1) : 0 };
    list.push(t);
    return t.id;
  }
  function pop(list) {
    if (!list.length) return null;
    let best = 0;
    for (let i = 1; i < list.length; i++) {
      if (list[i].at < list[best].at || (list[i].at === list[best].at && list[i].id < list[best].id)) best = i;
    }
    return list.splice(best, 1)[0];
  }
  function fire(t, list) {
    if (t.repeat) {
      t.at += t.repeat;
      list.push(t);
    }
    t.fn();
  }
  const clear = id => {
    for (const list of [realTimers, fakeTimers]) {
      const i = list.findIndex(t => t.id === id);
      if (i >= 0) list.splice(i, 1);
    }
  };
  globalThis.setTimeout = (fn, ms, ...a) => schedule(fake ? fakeTimers : realTimers, () => fn(...a), ms, false);
  globalThis.setInterval = (fn, ms, ...a) => schedule(fake ? fakeTimers : realTimers, () => fn(...a), ms, true);
  globalThis.setImmediate = (fn, ...a) => schedule(fake ? fakeTimers : realTimers, () => fn(...a), 0, false);
  globalThis.clearTimeout = clear;
  globalThis.clearInterval = clear;
  globalThis.clearImmediate = clear;
  const macrotask = () => new Promise(r => realSetTimeout(r, 0));
  globalThis.performance = { now: () => (globalThis.__bridge ? globalThis.__bridge.nanos() / 1e6 : clock) };
  function advanceSync(ms) {
    const end = fakeNow + ms;
    for (;;) {
      fakeTimers.sort((a, b) => a.at - b.at || a.id - b.id);
      if (!fakeTimers.length || fakeTimers[0].at > end) break;
      const t = fakeTimers.shift();
      fakeNow = t.at;
      fire(t, fakeTimers);
    }
    fakeNow = end;
  }
  async function advanceAsync(ms) {
    const end = fakeNow + ms;
    await macrotask();
    for (;;) {
      fakeTimers.sort((a, b) => a.at - b.at || a.id - b.id);
      if (!fakeTimers.length || fakeTimers[0].at > end) break;
      const t = fakeTimers.shift();
      fakeNow = t.at;
      fire(t, fakeTimers);
      await macrotask();
    }
    fakeNow = end;
    await macrotask();
  }

  // ---- Mocks
  const mocks = [];
  function fnImpl(impl) {
    let current = impl;
    const once = [];
    const mock = function (...args) {
      mock.mock.calls.push(args);
      mock.mock.lastCall = args;
      mock.mock.instances.push(this);
      try {
        const f = once.length ? once.shift() : current;
        const value = f ? f.apply(this, args) : undefined;
        mock.mock.results.push({ type: "return", value });
        return value;
      } catch (e) {
        mock.mock.results.push({ type: "throw", value: e });
        throw e;
      }
    };
    mock.mock = { calls: [], results: [], instances: [], lastCall: undefined };
    mock._isMock = true;
    mock.mockImplementation = f => ((current = f), mock);
    mock.mockImplementationOnce = f => (once.push(f), mock);
    mock.mockReturnValue = v => ((current = () => v), mock);
    mock.mockReturnValueOnce = v => (once.push(() => v), mock);
    mock.mockResolvedValue = v => ((current = () => Promise.resolve(v)), mock);
    mock.mockRejectedValue = v => ((current = () => Promise.reject(v)), mock);
    mock.mockClear = () => {
      mock.mock.calls = [];
      mock.mock.results = [];
      mock.mock.instances = [];
      mock.mock.lastCall = undefined;
      return mock;
    };
    mock.mockReset = () => (mock.mockClear(), (current = impl), mock);
    mock.mockRestore = () => {
      mock.mockReset();
      mock._restore && mock._restore();
      return mock;
    };
    mock.getMockName = () => "vi.fn()";
    return mock;
  }
  const stubs = [];
  const vi = {
    fn: fnImpl,
    spyOn(obj, key) {
      const original = obj[key];
      const spy = fnImpl(function (...a) {
        return original.apply(this, a);
      });
      spy._restore = () => {
        obj[key] = original;
      };
      obj[key] = spy;
      mocks.push(spy);
      return spy;
    },
    restoreAllMocks() {
      for (const m of mocks.splice(0)) m.mockRestore();
    },
    clearAllMocks() {
      for (const m of mocks) m.mockClear();
    },
    resetAllMocks() {
      for (const m of mocks) m.mockReset();
    },
    useFakeTimers() {
      fake = true;
      return vi;
    },
    useRealTimers() {
      fake = false;
      fakeTimers.length = 0;
      return vi;
    },
    advanceTimersByTime(ms) {
      advanceSync(ms);
      return vi;
    },
    advanceTimersByTimeAsync: advanceAsync,
    runAllTimers() {
      for (let i = 0; fakeTimers.length && i < 10000; i++) {
        const t = pop(fakeTimers);
        fakeNow = t.at;
        fire(t, fakeTimers);
      }
      return vi;
    },
    async runAllTimersAsync() {
      await macrotask();
      for (let i = 0; fakeTimers.length && i < 10000; i++) {
        const t = pop(fakeTimers);
        fakeNow = t.at;
        fire(t, fakeTimers);
        await macrotask();
      }
      return vi;
    },
    getTimerCount: () => fakeTimers.length,
    stubGlobal(name, value) {
      stubs.push([name, globalThis[name]]);
      globalThis[name] = value;
      return vi;
    },
    unstubAllGlobals() {
      for (const [n, v] of stubs.splice(0).reverse()) globalThis[n] = v;
      return vi;
    },
    mock() {},
    isMockFunction: f => !!(f && f._isMock)
  };

  // ---- expect
  const ANY = Symbol("asymmetric");
  const asym = test => ({ [ANY]: test });
  function isObj(v) {
    return v !== null && typeof v === "object";
  }
  function isHost(v) {
    return typeof Java !== "undefined" && Java.isJavaObject && Java.isJavaObject(v);
  }
  function equals(a, b, strict, seen = []) {
    if (isObj(b) && !isHost(b) && b[ANY]) return b[ANY](a);
    if (isObj(a) && !isHost(a) && a[ANY]) return a[ANY](b);
    if (Object.is(a, b)) return true;
    if (typeof a === "function" || typeof b === "function") return false;
    if (!isObj(a) || !isObj(b)) return false;
    if (isHost(a) || isHost(b)) return a === b;
    if (a instanceof Date && b instanceof Date) return a.getTime() === b.getTime();
    if (a instanceof RegExp && b instanceof RegExp) return String(a) === String(b);
    if (a instanceof Error && b instanceof Error) return a.message === b.message && a.name === b.name;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    if (strict && Object.getPrototypeOf(a) !== Object.getPrototypeOf(b)) return false;
    for (const [x, y] of seen) if (x === a && y === b) return true;
    seen = seen.concat([[a, b]]);
    if (a instanceof Map && b instanceof Map) {
      if (a.size !== b.size) return false;
      for (const [k, v] of a) if (!b.has(k) || !equals(v, b.get(k), strict, seen)) return false;
      return true;
    }
    if (a instanceof Set && b instanceof Set) {
      if (a.size !== b.size) return false;
      for (const v of a) if (![...b].some(w => equals(v, w, strict, seen))) return false;
      return true;
    }
    if (Array.isArray(a)) {
      if (a.length !== b.length) return false;
      for (let i = 0; i < a.length; i++) if (!equals(a[i], b[i], strict, seen)) return false;
      return true;
    }
    const keys = o => Reflect.ownKeys(o).filter(k => o[k] !== undefined || strict);
    const ka = keys(a);
    const kb = keys(b);
    if (ka.length !== kb.length) return false;
    for (const k of ka) if (!equals(a[k], b[k], strict, seen)) return false;
    return true;
  }
  function subset(actual, expected) {
    if (isObj(expected) && expected[ANY]) return expected[ANY](actual);
    if (Array.isArray(expected)) {
      return Array.isArray(actual) && actual.length === expected.length && expected.every((e, i) => subset(actual[i], e));
    }
    if (!isObj(expected) || expected instanceof Date || expected instanceof RegExp) return equals(actual, expected);
    if (!isObj(actual)) return false;
    return Reflect.ownKeys(expected).every(k => k in actual && subset(actual[k], expected[k]));
  }
  function show(v, depth = 0) {
    try {
      if (typeof v === "string") return JSON.stringify(v);
      if (typeof v === "function") return "[Function]";
      if (typeof v === "symbol") return v.toString();
      if (!isObj(v)) return String(v);
      if (isHost(v)) return "[Java " + v + "]";
      if (v instanceof Error) return `${v.name}: ${v.message}`;
      if (depth > 3) return "...";
      if (Array.isArray(v)) return "[" + v.map(x => show(x, depth + 1)).join(", ") + "]";
      return "{" + Object.keys(v).map(k => `${k}: ${show(v[k], depth + 1)}`).join(", ") + "}";
    } catch (e) {
      return "<unprintable>";
    }
  }
  function matchThrown(err, expected) {
    if (expected === undefined) return true;
    if (typeof expected === "string") return String(err && err.message !== undefined ? err.message : err).includes(expected);
    if (expected instanceof RegExp) return expected.test(String(err && err.message !== undefined ? err.message : err));
    if (typeof expected === "function") return err instanceof expected;
    if (isObj(expected) && expected[ANY]) return expected[ANY](err);
    if (expected instanceof Error) return err && err.message === expected.message;
    return subset(err, expected);
  }
  const matchers = {
    toBe: (a, b) => [Object.is(a, b), `expected ${show(a)} to be ${show(b)}`],
    toEqual: (a, b) => [equals(a, b, false), `expected ${show(a)} to equal ${show(b)}`],
    toStrictEqual: (a, b) => [equals(a, b, true), `expected ${show(a)} to strictly equal ${show(b)}`],
    toMatchObject: (a, b) => [subset(a, b), `expected ${show(a)} to match ${show(b)}`],
    toBeUndefined: a => [a === undefined, `expected ${show(a)} to be undefined`],
    toBeDefined: a => [a !== undefined, `expected ${show(a)} to be defined`],
    toBeNull: a => [a === null, `expected ${show(a)} to be null`],
    toBeTruthy: a => [!!a, `expected ${show(a)} to be truthy`],
    toBeFalsy: a => [!a, `expected ${show(a)} to be falsy`],
    toBeNaN: a => [a !== a, `expected ${show(a)} to be NaN`],
    toBeGreaterThan: (a, b) => [a > b, `expected ${show(a)} > ${show(b)}`],
    toBeGreaterThanOrEqual: (a, b) => [a >= b, `expected ${show(a)} >= ${show(b)}`],
    toBeLessThan: (a, b) => [a < b, `expected ${show(a)} < ${show(b)}`],
    toBeLessThanOrEqual: (a, b) => [a <= b, `expected ${show(a)} <= ${show(b)}`],
    toBeCloseTo: (a, b, d = 2) => [Math.abs(a - b) < 10 ** -d / 2, `expected ${show(a)} close to ${show(b)}`],
    toBeInstanceOf: (a, b) => [a instanceof b, `expected ${show(a)} to be an instance of ${b && b.name}`],
    toBeTypeOf: (a, b) => [typeof a === b, `expected typeof ${show(a)} to be ${b}`],
    toHaveLength: (a, b) => [a != null && a.length === b, `expected ${show(a)} to have length ${b}`],
    toContain: (a, b) => [
      typeof a === "string" ? a.includes(b) : [...a].some(x => Object.is(x, b) || x === b),
      `expected ${show(a)} to contain ${show(b)}`
    ],
    toContainEqual: (a, b) => [[...a].some(x => equals(x, b)), `expected ${show(a)} to contain equal ${show(b)}`],
    toMatch: (a, b) => [typeof b === "string" ? String(a).includes(b) : b.test(a), `expected ${show(a)} to match ${b}`],
    toHaveProperty: (a, k, v) => [
      a != null && k in Object(a) && (v === undefined || equals(a[k], v)),
      `expected ${show(a)} to have property ${String(k)}`
    ],
    toThrow(a, expected) {
      if (typeof a !== "function") throw new Error("toThrow needs a function");
      try {
        a();
      } catch (e) {
        return [matchThrown(e, expected), `expected thrown ${show(e)} to match ${show(expected)}`];
      }
      return [false, `expected function to throw ${show(expected)}`];
    },
    toHaveBeenCalled: a => [a.mock.calls.length > 0, "expected mock to have been called"],
    toHaveBeenCalledTimes: (a, n) => [a.mock.calls.length === n, `expected mock called ${n} times, got ${a.mock.calls.length}: ${show(a.mock.calls)}`],
    toHaveBeenCalledWith: (a, ...args) => [
      a.mock.calls.some(c => equals(c, args)),
      `expected mock called with ${show(args)}, got ${show(a.mock.calls)}`
    ],
    toHaveBeenLastCalledWith: (a, ...args) => [
      a.mock.calls.length > 0 && equals(a.mock.calls[a.mock.calls.length - 1], args),
      `expected last call ${show(args)}, got ${show(a.mock.calls)}`
    ],
    toHaveBeenNthCalledWith: (a, n, ...args) => [
      equals(a.mock.calls[n - 1], args),
      `expected call ${n} ${show(args)}, got ${show(a.mock.calls)}`
    ],
    toHaveReturnedWith: (a, v) => [a.mock.results.some(r => r.type === "return" && equals(r.value, v)), "expected return value"]
  };
  const squeeze = s => String(s).replace(/\s+/g, "").replace(/,([\]}])/g, "$1");
  matchers.toMatchInlineSnapshot = (a, snapshot) => [
    squeeze(show(a)) === squeeze(snapshot),
    `expected ${show(a)} to match snapshot ${snapshot}`
  ];
  matchers.toThrowError = matchers.toThrow;
  matchers.toBeCalled = matchers.toHaveBeenCalled;
  matchers.toBeCalledTimes = matchers.toHaveBeenCalledTimes;
  matchers.toBeCalledWith = matchers.toHaveBeenCalledWith;
  matchers.toHaveBeenCalledOnce = a => [a.mock.calls.length === 1, `expected one call, got ${a.mock.calls.length}`];
  matchers.toHaveBeenCalledExactlyOnceWith = (a, ...args) => [
    a.mock.calls.length === 1 && equals(a.mock.calls[0], args),
    `expected one call with ${show(args)}, got ${show(a.mock.calls)}`
  ];

  function chain(actual, negate, promise) {
    const out = {};
    for (const name of Object.keys(matchers)) {
      out[name] = (...args) => {
        const check = value => {
          const [pass, message] = matchers[name](value, ...args);
          if (pass === negate) throw new Error((negate ? "not: " : "") + message);
        };
        if (promise === "resolves") return Promise.resolve(actual).then(check);
        if (promise === "rejects") {
          return Promise.resolve(actual).then(
            () => {
              throw new Error("expected a rejection");
            },
            e => (name === "toThrow" || name === "toThrowError" ? check(() => { throw e; }) : check(e))
          );
        }
        check(actual);
      };
    }
    Object.defineProperty(out, "to", { get: () => out });
    Object.defineProperty(out, "not", { get: () => chain(actual, !negate, promise) });
    Object.defineProperty(out, "resolves", { get: () => chain(actual, negate, "resolves") });
    Object.defineProperty(out, "rejects", { get: () => chain(actual, negate, "rejects") });
    return out;
  }
  const expect = actual => chain(actual, false, null);
  expect.any = type =>
    asym(v => (type === String ? typeof v === "string" : type === Number ? typeof v === "number"
      : type === Function ? typeof v === "function" : type === Boolean ? typeof v === "boolean"
      : type === Object ? typeof v === "object" : v instanceof type));
  expect.anything = () => asym(v => v != null);
  expect.objectContaining = o => asym(v => subset(v, o));
  expect.arrayContaining = a => asym(v => a.every(e => [...v].some(x => equals(x, e))));
  expect.stringContaining = s => asym(v => typeof v === "string" && v.includes(s));
  expect.stringMatching = r => asym(v => typeof v === "string" && (typeof r === "string" ? v.includes(r) : r.test(v)));
  expect.getState = () => ({});
  expect.soft = expect;
  expect.assertions = () => {};
  expect.hasAssertions = () => {};

  // ---- Hooks and the run
  const hook = key => fn => void scope[key].push(fn);
  const results = [];
  let stuckReject = null;

  function chainOf(s) {
    const out = [];
    for (let p = s; p; p = p.parent) out.unshift(p);
    return out;
  }
  function hasOnly(n) {
    return n.only || (n.children && n.children.some(hasOnly));
  }
  async function runSuite(s, inheritedOnly, skipAll) {
    const selected = s.children.filter(c => !onlyMode || inheritedOnly || hasOnly(c));
    const skip = skipAll || s.skip;
    if (!selected.length) return;
    if (!skip) for (const f of s.beforeAlls) await f();
    for (const c of s.children) {
      if (c.suite) await runSuite(c, inheritedOnly || c.only, skip);
      else await runTest(c, !selected.includes(c) || skip);
    }
    if (!skip) for (const f of s.afterAlls) await f();
  }
  function fullName(t) {
    const names = [];
    for (let p = t; p && p.parent; p = p.parent) names.unshift(p.name);
    return names.join(" > ");
  }
  async function runTest(t, skip) {
    const name = fullName(t);
    if (skip || t.skip || !t.fn) {
      results.push({ name, status: "skipped" });
      return;
    }
    const chainS = chainOf(t.parent);
    let error = null;
    const stuck = new Promise((_, rej) => (stuckReject = rej));
    const body = (async () => {
      for (const s of chainS) for (const f of s.befores) await f();
      await t.fn();
    })();
    try {
      await Promise.race([body, stuck]);
    } catch (e) {
      error = e;
    }
    stuckReject = null;
    for (const s of chainS.slice().reverse()) {
      for (const f of s.afters) {
        try {
          await Promise.race([f(), new Promise((_, rej) => (stuckReject = rej))]);
        } catch (e) {
          error = error ?? e;
        }
        stuckReject = null;
      }
    }
    // A test that failed and left the engine halted would fail every test after it.
    if (error && globalThis.__solid) {
      try {
        globalThis.__solid.resetErrorHalt();
        globalThis.__solid.flush();
      } catch (e) {
        // what the failed test left behind
      }
    }
    if (t.fails) error = error ? null : new Error("expected to fail");
    results.push({
      name,
      status: error ? "failed" : "passed",
      message: error ? describeError(error) : undefined
    });
  }
  function describeError(e) {
    try {
      if (isHost(e)) return "[Java] " + e;
      if (e && e.__java) return `${e.name}: ${e.message} [Java ${e.__java}]`;
      if (e && e.message !== undefined) {
        const frames = String(e.stack || "").split("\n").filter(l => l.includes(".test.js:")).slice(0, 2);
        return `${e.name || "Error"}: ${e.message} ${frames.map(f => f.replace(/.*\.test\.js:/, "@")).join(" ")}`;
      }
      return String(e);
    } catch (x) {
      return "<unprintable error>";
    }
  }
  let finished = false;
  globalThis.__runAll = () => {
    (async () => {
      try {
        await runSuite(root, false, false);
      } catch (e) {
        results.push({ name: "<suite>", status: "failed", message: describeError(e) });
      }
      finished = true;
    })();
  };
  globalThis.__finished = () => finished;
  // Fires the next timer on the virtual clock. False means nothing is left, so a pending test can never end.
  let spent = 0;
  globalThis.__tick = () => {
    const t = pop(realTimers);
    if (t === null) {
      if (stuckReject) {
        stuckReject(new Error("hung: an await never resolved"));
        stuckReject = null;
        return true;
      }
      return false;
    }
    clock = Math.max(clock, t.at);
    if (++spent > 200000) {
      realTimers.length = 0;
      return true;
    }
    fire(t, realTimers);
    return true;
  };
  globalThis.__results = () => JSON.stringify(results);

  const beforeEach = hook("befores");
  const afterEach = hook("afters");
  const beforeAll = hook("beforeAlls");
  const afterAll = hook("afterAlls");
  const test = it;
  globalThis.it = it;
  globalThis.test = test;
  globalThis.describe = describe;
  globalThis.expect = expect;
  globalThis.vi = vi;
  globalThis.beforeEach = beforeEach;
  globalThis.afterEach = afterEach;
  globalThis.beforeAll = beforeAll;
  globalThis.afterAll = afterAll;
  globalThis.__vitest = { it, test, describe, expect, vi, beforeEach, afterEach, beforeAll, afterAll };
  globalThis.queueMicrotask ??= f => void Promise.resolve().then(f);
  globalThis.process ??= { env: {}, on() {}, off() {}, removeListener() {}, emit() {} };
  globalThis.AbortController ??= class {
    constructor() {
      const listeners = [];
      this.signal = {
        aborted: false,
        reason: undefined,
        addEventListener: (_, f) => listeners.push(f),
        removeEventListener: (_, f) => {
          const i = listeners.indexOf(f);
          if (i >= 0) listeners.splice(i, 1);
        }
      };
      this.abort = reason => {
        if (this.signal.aborted) return;
        this.signal.aborted = true;
        this.signal.reason = reason ?? new Error("This operation was aborted");
        for (const f of listeners.splice(0)) f();
      };
    }
  };
})();
