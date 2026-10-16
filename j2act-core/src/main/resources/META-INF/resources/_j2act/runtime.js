/* j2act client runtime (ADR 0003, 0010, 0013, 0022, 0026). Built from j2act-core/src/main/ts by npm run build; do not edit.
* sources: sha256-3817f5ef490819b98a9529391bdaa5b40343ccf00124a614a2de76d55923a9e2 */
"use strict";
(function() {
	//#region src/main/ts/util.ts
	function named(name, message) {
		const e = new Error(message);
		e.name = name;
		return e;
	}
	function errorText(e) {
		if (e && typeof e === "object" && "message" in e && e.message !== void 0) return (e.name || "Error") + ": " + e.message;
		return "Error: " + e;
	}
	function meta(doc, name) {
		const m = doc.querySelector("meta[name=\"" + name + "\"]");
		return m ? m.content : null;
	}
	/**
	* Exponential backoff, capped at 5 s, with half of it random: after a server restart, every
	* open page reconnects, and without jitter they would all arrive in the same instant.
	*/
	function backoff(attempt, random = Math.random) {
		const ceiling = Math.min(5e3, 250 * Math.pow(2, attempt));
		return Math.round(ceiling / 2 + random() * ceiling / 2);
	}
	//#endregion
	//#region src/main/ts/clients.ts
	function createClients({ send, uploads, morphSlot }) {
		const clients = /* @__PURE__ */ new Map();
		const liveAnchors = /* @__PURE__ */ new Set();
		const lostSlots = /* @__PURE__ */ new Set();
		const serverAttrs = /* @__PURE__ */ new WeakMap();
		const stylesheets = /* @__PURE__ */ new Map();
		/** Mounts new client elements, updates changed props and unmounts removed ones. */
		function scan() {
			document.querySelectorAll("[data-j2-client]").forEach((el) => mount(el));
			clients.forEach((c) => {
				if (!c.el.isConnected || c.el.getAttribute("data-j2-client") !== c.id) unmount(c);
			});
		}
		/** Values from Java: callbacks become functions, slots elements, Upload targets objects with send(). */
		function revive(json) {
			return JSON.parse(json, (_key, value) => {
				if (value && typeof value === "object" && !Array.isArray(value)) {
					if (typeof value.$fn === "string") return callback(value.$fn);
					if (typeof value.$slot === "string") return document.querySelector("[data-j2-slot=\"" + CSS.escape(value.$slot) + "\"]");
					if (typeof value.$upload === "string") return uploads.target(value.$upload);
					if (typeof value.$html === "string") {
						const snapshot = document.createElement("div");
						snapshot.style.display = "contents";
						snapshot.innerHTML = value.$html;
						return snapshot;
					}
					if (typeof value.$live === "string") {
						const template = document.createElement("template");
						template.innerHTML = value.$live;
						const root = template.content.firstElementChild;
						liveAnchors.add(root.getAttribute("data-j2-scope"));
						return root;
					}
				}
				return value;
			});
		}
		/**
		* A patch found no element for its anchor. If it was live content given to an action, the
		* client dropped it, and the server stops patching it.
		*/
		function anchorGone(anchor) {
			if (liveAnchors.delete(anchor)) {
				console.warn("j2act: live content " + anchor + " passed to a client action left the document (ADR 0022)");
				send({
					type: "liveGone",
					anchor
				});
			}
		}
		/** A call from Java, sent after the patch: queued until its client has mounted. */
		function call(m) {
			const c = clients.get(m.client);
			if (!c) reply(m.id, false, named("AbortError", "the client is not mounted"));
			else if (c.failure) reply(m.id, false, c.failure.error);
			else if (!c.ready) c.waiting.push(m);
			else run(c, m);
		}
		/** An action bound to an event, run inside it: the client must have mounted already. */
		function callNow(id, name, args) {
			const c = clients.get(id);
			if (!c || !c.ready) throw c && c.failure ? c.failure.error : named("InvalidStateError", "the client has not mounted yet");
			return invoke(c, name, revive(JSON.stringify(args)));
		}
		/** Answers a call from Java, an action's or a window() call's. */
		function reply(id, ok, value) {
			if (ok) try {
				send({
					type: "callResult",
					id,
					ok: true,
					value: JSON.stringify(value === void 0 ? null : value)
				});
				return;
			} catch (e) {
				value = e;
			}
			console.warn("j2act: client action failed", value);
			send({
				type: "callResult",
				id,
				ok: false,
				error: errorText(value)
			});
		}
		/** Server attributes follow the render; attributes the client added stay; slots morph where they live. */
		function sync(el, fresh) {
			const names = attributeNames(fresh);
			for (const name of serverAttrs.get(el) || []) if (names.indexOf(name) < 0) el.removeAttribute(name);
			for (const name of names) {
				const value = fresh.getAttribute(name);
				if (el.getAttribute(name) !== value) el.setAttribute(name, value);
			}
			serverAttrs.set(el, names);
			fresh.querySelectorAll(":scope > [data-j2-slot]").forEach((slot) => {
				const id = slot.getAttribute("data-j2-slot");
				const live = document.querySelector("[data-j2-slot=\"" + CSS.escape(id) + "\"]");
				if (live) morphSlot(live, slot.innerHTML);
				else if (!lostSlots.has(id)) {
					lostSlots.add(id);
					console.warn("j2act: slot " + id + " left the document, so it stopped updating (ADR 0022)");
					send({
						type: "clientError",
						client: el.getAttribute("data-j2-client"),
						error: "slot " + id + " left the document, so it stopped updating"
					});
				}
			});
		}
		/**
		* Dev mode (ADR 0024): a new build moved these files. Stylesheets swap in place without a
		* flash, clients whose module moved mount again with their current props; Java state is untouched.
		*/
		function reimport(moved) {
			if (moved["*"]) {
				location.reload();
				return;
			}
			document.querySelectorAll("link[rel=\"stylesheet\"]").forEach((link) => {
				const href = link.getAttribute("href");
				const next = moved[href];
				if (!next) return;
				const fresh = link.cloneNode();
				fresh.href = next;
				fresh.onload = () => link.remove();
				link.after(fresh);
				const loaded = stylesheets.get(href);
				if (loaded) {
					stylesheets.set(next, loaded);
					stylesheets.delete(href);
				}
			});
			document.querySelectorAll("[data-j2-client]").forEach((el) => {
				const url = el.getAttribute("data-j2-module");
				if (url && moved[url]) el.setAttribute("data-j2-module", moved[url]);
				const css = el.getAttribute("data-j2-css");
				if (css) el.setAttribute("data-j2-css", css.split(" ").map((u) => moved[u] || u).join(" "));
			});
			scan();
		}
		function mount(el) {
			const id = el.getAttribute("data-j2-client");
			const url = el.getAttribute("data-j2-module");
			let c = clients.get(id);
			if (c && c.el === el && c.url === url) {
				propsChanged(c);
				return;
			}
			if (c) unmount(c);
			const client = {
				id,
				el,
				url,
				props: el.getAttribute("data-j2-props"),
				api: null,
				decoded: null,
				cleanup: null,
				ready: false,
				failure: null,
				waiting: []
			};
			c = client;
			clients.set(id, client);
			if (!serverAttrs.has(el)) serverAttrs.set(el, attributeNames(el));
			const name = el.getAttribute("data-j2-export");
			const styles = (el.getAttribute("data-j2-css") || "").split(" ").filter(Boolean);
			Promise.all([import(
				/* @vite-ignore */
				url
), ...styles.map((s) => stylesheet(s))]).then(([module]) => {
				if (clients.get(id) !== client) return;
				if (!module[name] || typeof module[name] !== "object") throw named("ReferenceError", url + " has no export " + name);
				client.api = module[name];
				start(client);
			}).catch((e) => {
				client.failure = { error: e };
				error(client, "import", e);
				failWaiting(client, e);
			});
		}
		function stylesheet(url) {
			let loaded = stylesheets.get(url);
			if (!loaded) {
				loaded = new Promise((resolve, reject) => {
					const link = document.createElement("link");
					link.rel = "stylesheet";
					link.href = url;
					link.setAttribute("data-j2-keep", "");
					link.onload = () => resolve();
					link.onerror = () => reject(named("NetworkError", "stylesheet " + url + " did not load"));
					document.head.append(link);
				});
				stylesheets.set(url, loaded);
			}
			return loaded;
		}
		function start(c) {
			const api = c.api;
			try {
				c.decoded = c.props == null ? null : revive(c.props);
				if (typeof api.mount === "function") {
					const cleanup = c.props == null ? api.mount(c.el) : api.mount(c.el, c.decoded);
					c.cleanup = typeof cleanup === "function" ? cleanup : null;
				}
			} catch (e) {
				c.failure = { error: e };
				error(c, "mount", e);
				failWaiting(c, e);
				return;
			}
			c.ready = true;
			const waiting = c.waiting;
			c.waiting = [];
			waiting.forEach((m) => run(c, m));
		}
		/** update runs only when props really changed; without it the client mounts again. */
		function propsChanged(c) {
			const props = c.el.getAttribute("data-j2-props");
			if (props === c.props) return;
			c.props = props;
			if (!c.ready) return;
			const api = c.api;
			if (typeof api.update === "function") {
				const previous = c.decoded;
				try {
					c.decoded = revive(props);
					api.update(c.el, c.decoded, previous);
				} catch (e) {
					error(c, "update", e);
				}
			} else {
				stop(c);
				c.ready = false;
				start(c);
			}
		}
		function stop(c) {
			if (c.cleanup) {
				try {
					c.cleanup();
				} catch (e) {
					error(c, "cleanup", e);
				}
				c.cleanup = null;
			}
		}
		function unmount(c) {
			clients.delete(c.id);
			stop(c);
			failWaiting(c, named("AbortError", "the client unmounted"));
		}
		function failWaiting(c, e) {
			const waiting = c.waiting;
			c.waiting = [];
			waiting.forEach((m) => reply(m.id, false, e));
		}
		function invoke(c, name, args) {
			const fn = c.api[name];
			if (typeof fn !== "function") throw named("TypeError", c.el.getAttribute("data-j2-export") + " has no action " + name);
			return fn.apply(c.api, [c.el, ...args]);
		}
		function run(c, m) {
			let result;
			try {
				result = invoke(c, m.action, revive(m.args));
			} catch (e) {
				reply(m.id, false, e);
				return;
			}
			Promise.resolve(result).then((v) => reply(m.id, true, v), (e) => reply(m.id, false, e));
		}
		function callback(id) {
			return (...args) => {
				send({
					type: "callback",
					handler: id,
					args: JSON.stringify(args)
				});
			};
		}
		/** Mount, update and cleanup failures and missing exports: the console here, the log on the server. */
		function error(c, what, e) {
			console.error("j2act: client " + c.el.getAttribute("data-j2-export") + " failed in " + what, e);
			send({
				type: "clientError",
				client: c.id,
				error: what + ": " + errorText(e)
			});
		}
		return {
			scan,
			revive,
			anchorGone,
			call,
			callNow,
			reply,
			sync,
			reimport
		};
	}
	function attributeNames(el) {
		return Array.from(el.attributes, (a) => a.name);
	}
	//#endregion
	//#region src/main/ts/connection.ts
	/**
	* The page's socket (ADR 0010): says hello with the tab id and token, queues messages until
	* the server answers ready, and reconnects with backoff unless the page is leaving or paused.
	*/
	function createConnection(url, tab, token, onMessage, reconnects) {
		let ready = false;
		let ws = null;
		let queue = [];
		let retry = 0;
		function connect() {
			const socket = new WebSocket(url);
			ws = socket;
			socket.onopen = () => {
				socket.send(JSON.stringify({
					type: "hello",
					tab,
					token
				}));
			};
			socket.onmessage = (e) => {
				onMessage(JSON.parse(e.data));
			};
			socket.onclose = () => {
				if (ws !== socket) return;
				ready = false;
				if (reconnects()) setTimeout(connect, backoff(retry++));
			};
		}
		return {
			connect,
			/** The tab's token, which the page's HTTP requests carry too. */
			get token() {
				return token;
			},
			get ready() {
				return ready;
			},
			/** The server accepted the hello: send what queued meanwhile. */
			opened() {
				ready = true;
				retry = 0;
				while (queue.length) ws.send(queue.shift());
			},
			/** Sends now, or once the socket is up. */
			send(message) {
				const text = JSON.stringify(message);
				if (ready) ws.send(text);
				else queue.push(text);
			},
			/** Sends only if the socket is up: worth nothing once the moment passed. */
			sendIfReady(message) {
				if (ready) ws.send(JSON.stringify(message));
			},
			/** The socket is about to close: queue from now on. */
			hold() {
				ready = false;
			},
			close() {
				ready = false;
				try {
					ws?.close();
				} catch {}
			},
			/** A new server-side tab: what queued for the old one is meaningless to it. */
			restart(newTab, newToken) {
				tab = newTab;
				token = newToken;
				retry = 0;
				queue = [];
				connect();
			}
		};
	}
	//#endregion
	//#region src/main/ts/web.ts
	/**
	* Follows the path (get a property or call a method at each step), then gets, sets, calls or
	* constructs. Everything up to the last call runs synchronously, so a call bound to a gesture
	* keeps the gesture's user activation.
	*/
	function runWeb(call, root = window) {
		let target = root;
		for (const [kind, name, args] of call.path) {
			target = kind === "get" ? target[name] : target[name](...args || []);
			if (target == null) throw named("TypeError", name + " is " + target);
		}
		let value;
		if (call.kind === "get") value = target[call.name];
		else if (call.kind === "set") target[call.name] = call.args[0];
		else if (call.kind === "new") new target(...call.args);
		else if (call.callbacks) {
			const callbacks = call.callbacks;
			value = new Promise((resolve, reject) => {
				const args = call.args.slice();
				args[callbacks[0]] = resolve;
				if (callbacks.length > 1) args[callbacks[1]] = (error) => reject(error instanceof Error ? error : named(errorName(error), error && error.message));
				target[call.name](...args);
			});
		} else value = target[call.name](...call.args);
		const shape = call.shape;
		return Promise.resolve(value).then((v) => shape ? project(v, shape) : v);
	}
	/** Copies what the snapshot's shape names; live objects never cross. */
	function project(value, shape) {
		if (value == null) return null;
		if (shape === true) return value;
		const out = {};
		for (const key of Object.keys(shape)) out[key] = project(value[key], shape[key]);
		return out;
	}
	var ERROR_CODES = {
		1: "NotAllowedError",
		2: "NotFoundError",
		3: "TimeoutError"
	};
	/** GeolocationPositionError is not a DOMException: name it from its code. */
	function errorName(error) {
		return error && error.code !== void 0 && ERROR_CODES[error.code] || "Error";
	}
	//#endregion
	//#region src/main/ts/events.ts
	/** Listens on doc for every event a render can bind. */
	function listenForEvents(doc, { send, pending, clients, uploads }) {
		let ackSeq = 0;
		const timers = /* @__PURE__ */ new WeakMap();
		const throttles = /* @__PURE__ */ new WeakMap();
		/**
		* Sends the event to the handler the render bound. A click or submit blocks its element until
		* the ack, with pending UI on pendingOn, which for a submit is the submitter button.
		*/
		function dispatch(el, type, value, extra, pendingOn) {
			const blocking = type === "click" || type === "submit";
			if (blocking && pending.has(el)) return;
			const handlerId = el.getAttribute("data-j2-" + type);
			if (!handlerId) return;
			const id = String(++ackSeq);
			if (blocking) pending.start(el, id, pendingOn || el);
			send({
				type: "event",
				id,
				handler: handlerId,
				value: value == null ? "" : value,
				...extra
			});
		}
		function schedule(el, type) {
			const throttleMs = parseInt(el.getAttribute("data-j2-throttle") || "-1", 10);
			if (throttleMs >= 0) {
				throttle(el, type, throttleMs);
				return;
			}
			const ms = parseInt(el.getAttribute("data-j2-debounce") || "-1", 10);
			const read = () => type === "click" ? null : valueOf(el);
			if (ms < 0) {
				dispatch(el, type, read());
				return;
			}
			clearTimeout(timers.get(el));
			timers.set(el, setTimeout(() => {
				timers.delete(el);
				dispatch(el, type, read());
			}, ms));
		}
		/**
		* The leading event at once, then at most one per interval: input and change send the latest
		* value when the interval ends, clicks inside it are dropped (ADR 0013).
		*/
		function throttle(el, type, ms) {
			const state = throttles.get(el);
			if (state) {
				if (type !== "click") state.trailing = type;
				return;
			}
			const running = { trailing: null };
			throttles.set(el, running);
			dispatch(el, type, type === "click" ? null : valueOf(el));
			const tick = () => {
				if (!running.trailing) {
					throttles.delete(el);
					return;
				}
				const next = running.trailing;
				running.trailing = null;
				dispatch(el, next, valueOf(el));
				setTimeout(tick, ms);
			};
			setTimeout(tick, ms);
		}
		/**
		* onClick/onSubmit/onKeyDown/onPointerDown/onPointerUp(action, then): the action runs
		* synchronously inside the event, which grants user activation, so gesture-gated APIs work.
		* Its result, or its error, then goes to the handler like an event's value.
		*/
		function boundAction(el, type, extra) {
			if (pending.has(el)) return;
			const call = JSON.parse(el.getAttribute("data-j2-call-" + type));
			let result;
			try {
				result = call.window ? runWeb(call.window) : clients.callNow(call.client, call.action, call.args);
			} catch (e) {
				console.warn("j2act: client action failed", e);
				dispatch(el, type, "", {
					...extra,
					error: errorText(e)
				});
				return;
			}
			Promise.resolve(result).then((v) => {
				dispatch(el, type, JSON.stringify(v === void 0 ? null : v), extra);
			}, (e) => {
				console.warn("j2act: client action failed", e);
				dispatch(el, type, "", {
					...extra,
					error: errorText(e)
				});
			});
		}
		doc.addEventListener("click", (e) => {
			const el = closest(e.target, "[data-j2-click]");
			if (!el) return;
			if (el.tagName === "A" || el.type === "submit") e.preventDefault();
			if (el.hasAttribute("data-j2-call-click")) {
				boundAction(el, "click");
				return;
			}
			schedule(el, "click");
		});
		for (const type of ["pointerdown", "pointerup"]) doc.addEventListener(type, (e) => {
			const el = closest(e.target, "[data-j2-" + type + "]");
			if (!el) return;
			const extra = {
				pointerType: e.pointerType,
				clientX: e.clientX,
				clientY: e.clientY
			};
			if (el.hasAttribute("data-j2-call-" + type)) boundAction(el, type, extra);
			else dispatch(el, type, "", extra);
		});
		for (const type of ["input", "change"]) doc.addEventListener(type, (e) => {
			const el = bound(e.target, type);
			if (!el) return;
			if (el.type === "file") dispatch(el, type, "", uploads.picked(el));
			else schedule(el, type);
		});
		for (const [listen, type] of [["focusin", "focus"], ["focusout", "blur"]]) doc.addEventListener(listen, (e) => {
			const el = bound(e.target, type);
			if (el) dispatch(el, type, valueOf(el));
		});
		doc.addEventListener("keydown", (e) => {
			const el = bound(e.target, "keydown");
			if (!el) return;
			const filter = el.getAttribute("data-j2-keys");
			if (filter && filter.split(" ").indexOf(e.key) < 0) return;
			const key = {
				key: e.key,
				ctrlKey: e.ctrlKey,
				shiftKey: e.shiftKey,
				altKey: e.altKey,
				metaKey: e.metaKey
			};
			if (el.hasAttribute("data-j2-call-keydown")) {
				boundAction(el, "keydown", key);
				return;
			}
			dispatch(el, "keydown", valueOf(el), key);
		});
		doc.addEventListener("submit", (e) => {
			const form = bound(e.target, "submit");
			if (!form) return;
			e.preventDefault();
			if (form.hasAttribute("data-j2-call-submit")) {
				boundAction(form, "submit");
				return;
			}
			const submitter = e.submitter;
			const fields = new FormData(form);
			if (submitter && submitter.name) fields.append(submitter.name, submitter.value);
			const encoded = [];
			fields.forEach((value, name) => {
				if (typeof value === "string") encoded.push(encodeURIComponent(name) + "=" + encodeURIComponent(value));
			});
			dispatch(form, "submit", encoded.join("&"), void 0, submitter || form);
		});
	}
	function closest(target, selector) {
		return target instanceof Element ? target.closest(selector) : null;
	}
	/** The event target itself, if the render bound this type on it. */
	function bound(target, type) {
		return target instanceof Element && target.hasAttribute("data-j2-" + type) ? target : null;
	}
	function valueOf(el) {
		return el.value;
	}
	//#endregion
	//#region node_modules/idiomorph/dist/idiomorph.esm.js
	/**
	* @typedef {object} ConfigHead
	*
	* @property {'merge' | 'append' | 'morph' | 'none'} [style]
	* @property {boolean} [block]
	* @property {function(Element): boolean} [shouldPreserve]
	* @property {function(Element): boolean} [shouldReAppend]
	* @property {function(Element): boolean} [shouldRemove]
	* @property {function(Element, {added: Node[], kept: Element[], removed: Element[]}): void} [afterHeadMorphed]
	*/
	/**
	* @typedef {object} ConfigCallbacks
	*
	* @property {function(Node): boolean} [beforeNodeAdded]
	* @property {function(Node): void} [afterNodeAdded]
	* @property {function(Element, Node): boolean} [beforeNodeMorphed]
	* @property {function(Element, Node): void} [afterNodeMorphed]
	* @property {function(Element): boolean} [beforeNodeRemoved]
	* @property {function(Element): void} [afterNodeRemoved]
	* @property {function(string, Element, "update" | "remove"): boolean} [beforeAttributeUpdated]
	*/
	/**
	* @typedef {object} Config
	*
	* @property {'outerHTML' | 'innerHTML'} [morphStyle]
	* @property {boolean} [ignoreActive]
	* @property {boolean} [ignoreActiveValue]
	* @property {boolean} [restoreFocus]
	* @property {ConfigCallbacks} [callbacks]
	* @property {ConfigHead} [head]
	*/
	/**
	* @callback NoOp
	*
	* @returns {void}
	*/
	/**
	* @typedef {object} ConfigHeadInternal
	*
	* @property {'merge' | 'append' | 'morph' | 'none'} style
	* @property {boolean} [block]
	* @property {(function(Element): boolean) | NoOp} shouldPreserve
	* @property {(function(Element): boolean) | NoOp} shouldReAppend
	* @property {(function(Element): boolean) | NoOp} shouldRemove
	* @property {(function(Element, {added: Node[], kept: Element[], removed: Element[]}): void) | NoOp} afterHeadMorphed
	*/
	/**
	* @typedef {object} ConfigCallbacksInternal
	*
	* @property {(function(Node): boolean) | NoOp} beforeNodeAdded
	* @property {(function(Node): void) | NoOp} afterNodeAdded
	* @property {(function(Node, Node): boolean) | NoOp} beforeNodeMorphed
	* @property {(function(Node, Node): void) | NoOp} afterNodeMorphed
	* @property {(function(Node): boolean) | NoOp} beforeNodeRemoved
	* @property {(function(Node): void) | NoOp} afterNodeRemoved
	* @property {(function(string, Element, "update" | "remove"): boolean) | NoOp} beforeAttributeUpdated
	*/
	/**
	* @typedef {object} ConfigInternal
	*
	* @property {'outerHTML' | 'innerHTML'} morphStyle
	* @property {boolean} [ignoreActive]
	* @property {boolean} [ignoreActiveValue]
	* @property {boolean} [restoreFocus]
	* @property {ConfigCallbacksInternal} callbacks
	* @property {ConfigHeadInternal} head
	*/
	/**
	* @typedef {Object} IdElement
	* @property {Element} elt
	* @property {string} id
	*/
	/**
	* @typedef {Object} IdSets
	* @property {Set<string>} persistentIds
	* @property {Map<Node, Set<string>>} idMap
	*/
	/**
	* @callback Morph
	*
	* @param {Node} oldNode
	* @param {Node | HTMLCollection | Node[] | string | null} newContent
	* @param {Config} [config]
	* @returns {Promise<Node[]> | Node[]}
	*/
	/**
	*
	* @type {{defaults: ConfigInternal, morph: Morph}}
	*/
	var Idiomorph = (function() {
		"use strict";
		/**
		* @typedef {object} MorphContext
		*
		* @property {Element} target
		* @property {Element} newContent
		* @property {Document} doc
		* @property {ConfigInternal} config
		* @property {ConfigInternal['morphStyle']} morphStyle
		* @property {ConfigInternal['ignoreActive']} ignoreActive
		* @property {ConfigInternal['ignoreActiveValue']} ignoreActiveValue
		* @property {ConfigInternal['restoreFocus']} restoreFocus
		* @property {Map<Node, Set<string>>} idMap
		* @property {Set<string>} persistentIds
		* @property {ConfigInternal['callbacks']} callbacks
		* @property {ConfigInternal['head']} head
		* @property {HTMLDivElement} pantry
		* @property {Element[]} activeElementAndParents
		*/
		const noOp = () => {};
		/**
		* Default configuration values, updatable by users now
		* @type {ConfigInternal}
		*/
		const defaults = {
			morphStyle: "outerHTML",
			callbacks: {
				beforeNodeAdded: noOp,
				afterNodeAdded: noOp,
				beforeNodeMorphed: noOp,
				afterNodeMorphed: noOp,
				beforeNodeRemoved: noOp,
				afterNodeRemoved: noOp,
				beforeAttributeUpdated: noOp
			},
			head: {
				style: "merge",
				shouldPreserve: (elt) => elt.getAttribute("im-preserve") === "true",
				shouldReAppend: (elt) => elt.getAttribute("im-re-append") === "true",
				shouldRemove: noOp,
				afterHeadMorphed: noOp
			},
			restoreFocus: true
		};
		/**
		* Core idiomorph function for morphing one DOM tree to another
		*
		* @param {Node} oldNode
		* @param {Node | HTMLCollection | Node[] | string | null} newContent
		* @param {Config} [config]
		* @returns {Promise<Node[]> | Node[]}
		*/
		function morph(oldNode, newContent, config = {}) {
			const oldElt = normalizeElement(oldNode);
			const newNode = normalizeParent(newContent);
			return withHeadBlocking(
				createMorphContext(oldElt, newNode, config),
				oldElt,
				newNode,
				/** @param {MorphContext} ctx */
				(ctx) => {
					const morphedNodes = saveAndRestoreFocus(ctx, () => {
						if (ctx.morphStyle === "innerHTML") {
							morphChildren(ctx, oldElt, newNode);
							return Array.from(oldElt.childNodes);
						} else return morphOuterHTML(ctx, oldElt, newNode);
					});
					ctx.pantry.remove();
					return morphedNodes;
				}
			);
		}
		/**
		* Morph just the outerHTML of the oldNode to the newContent
		* We have to be careful because the oldNode could have siblings which need to be untouched
		* @param {MorphContext} ctx
		* @param {Element} oldNode
		* @param {Element} newNode
		* @returns {Node[]}
		*/
		function morphOuterHTML(ctx, oldNode, newNode) {
			const oldParent = normalizeParent(oldNode);
			morphChildren(ctx, oldParent, newNode, oldNode, oldNode.nextSibling);
			return Array.from(oldParent.childNodes);
		}
		/**
		* @param {MorphContext} ctx
		* @param {Function} fn
		* @returns {Node[]}
		*/
		function saveAndRestoreFocus(ctx, fn) {
			if (!ctx.config.restoreFocus) return fn();
			let activeElement = ctx.doc.activeElement;
			if (!(is.inputElement(activeElement) || is.textAreaElement(activeElement))) return fn();
			const { id: activeElementId, selectionStart, selectionEnd } = activeElement;
			const results = fn();
			if (activeElementId && activeElementId !== ctx.doc.activeElement?.getAttribute("id")) {
				activeElement = ctx.target.querySelector(`[id="${CSS.escape(activeElementId)}"]`);
				activeElement?.focus();
			}
			if (activeElement && !activeElement.selectionEnd && selectionEnd) try {
				activeElement.setSelectionRange(selectionStart, selectionEnd);
			} catch {}
			return results;
		}
		const morphChildren = (function() {
			/**
			* This is the core algorithm for matching up children.  The idea is to use id sets to try to match up
			* nodes as faithfully as possible.  We greedily match, which allows us to keep the algorithm fast, but
			* by using id sets, we are able to better match up with content deeper in the DOM.
			*
			* Basic algorithm:
			* - for each node in the new content:
			*   - search self and siblings for an id set match, falling back to a soft match
			*   - if match found
			*     - remove any nodes up to the match:
			*       - pantry persistent nodes
			*       - delete the rest
			*     - morph the match
			*   - elsif no match found, and node is persistent
			*     - find its match by querying the old root (future) and pantry (past)
			*     - move it and its children here
			*     - morph it
			*   - else
			*     - create a new node from scratch as a last result
			*
			* @param {MorphContext} ctx the merge context
			* @param {Element} oldParent the old content that we are merging the new content into
			* @param {Element} newParent the parent element of the new content
			* @param {Node|null} [insertionPoint] the point in the DOM we start morphing at (defaults to first child)
			* @param {Node|null} [endPoint] the point in the DOM we stop morphing at (defaults to after last child)
			*/
			function morphChildren(ctx, oldParent, newParent, insertionPoint = null, endPoint = null) {
				if (is.templateElement(oldParent) && is.templateElement(newParent)) {
					oldParent = oldParent.content;
					newParent = newParent.content;
				}
				insertionPoint ||= oldParent.firstChild;
				for (const newChild of newParent.childNodes) {
					if (insertionPoint && insertionPoint != endPoint) {
						const bestMatch = findBestMatch(ctx, newChild, insertionPoint, endPoint);
						if (bestMatch) {
							if (bestMatch !== insertionPoint) removeNodesBetween(ctx, insertionPoint, bestMatch);
							morphNode(bestMatch, newChild, ctx);
							insertionPoint = bestMatch.nextSibling;
							continue;
						}
					}
					if (is.element(newChild)) {
						const newChildId = newChild.getAttribute("id");
						if (ctx.persistentIds.has(newChildId)) {
							const movedChild = moveBeforeById(oldParent, newChildId, insertionPoint, ctx);
							morphNode(movedChild, newChild, ctx);
							insertionPoint = movedChild.nextSibling;
							continue;
						}
					}
					const insertedNode = createNode(oldParent, newChild, insertionPoint, ctx);
					if (insertedNode) insertionPoint = insertedNode.nextSibling;
				}
				while (insertionPoint && insertionPoint != endPoint) {
					const tempNode = insertionPoint;
					insertionPoint = insertionPoint.nextSibling;
					removeNode(ctx, tempNode);
				}
			}
			/**
			* This performs the action of inserting a new node while handling situations where the node contains
			* elements with persistent ids and possible state info we can still preserve by moving in and then morphing
			*
			* @param {Element} oldParent
			* @param {Node} newChild
			* @param {Node|null} insertionPoint
			* @param {MorphContext} ctx
			* @returns {Node|null}
			*/
			function createNode(oldParent, newChild, insertionPoint, ctx) {
				if (ctx.callbacks.beforeNodeAdded(newChild) === false) return null;
				if (ctx.idMap.has(newChild)) {
					const newEmptyChild = ctx.doc.createElementNS(
						/** @type {Element} */
						newChild.namespaceURI,
						/** @type {Element} */
						newChild.localName
					);
					oldParent.insertBefore(newEmptyChild, insertionPoint);
					morphNode(newEmptyChild, newChild, ctx);
					ctx.callbacks.afterNodeAdded(newEmptyChild);
					return newEmptyChild;
				} else {
					const newClonedChild = ctx.doc.importNode(newChild, true);
					oldParent.insertBefore(newClonedChild, insertionPoint);
					ctx.callbacks.afterNodeAdded(newClonedChild);
					return newClonedChild;
				}
			}
			const findBestMatch = (function() {
				/**
				* Scans forward from the startPoint to the endPoint looking for a match
				* for the node. It looks for an id set match first, then a soft match.
				* We abort softmatching if we find two future soft matches, to reduce churn.
				* @param {Node} node
				* @param {MorphContext} ctx
				* @param {Node | null} startPoint
				* @param {Node | null} endPoint
				* @returns {Node | null}
				*/
				function findBestMatch(ctx, node, startPoint, endPoint) {
					let softMatch = null;
					let nextSibling = node.nextSibling;
					let siblingSoftMatchCount = 0;
					let cursor = startPoint;
					while (cursor && cursor != endPoint) {
						if (isSoftMatch(cursor, node)) {
							if (isIdSetMatch(ctx, cursor, node)) return cursor;
							if (softMatch === null) {
								if (!ctx.idMap.has(cursor)) softMatch = cursor;
							}
						}
						if (softMatch === null && nextSibling && isSoftMatch(cursor, nextSibling)) {
							siblingSoftMatchCount++;
							nextSibling = nextSibling.nextSibling;
							if (siblingSoftMatchCount >= 2) softMatch = void 0;
						}
						if (ctx.activeElementAndParents.includes(cursor)) break;
						cursor = cursor.nextSibling;
					}
					return softMatch || null;
				}
				/**
				*
				* @param {MorphContext} ctx
				* @param {Node} oldNode
				* @param {Node} newNode
				* @returns {boolean}
				*/
				function isIdSetMatch(ctx, oldNode, newNode) {
					let oldSet = ctx.idMap.get(oldNode);
					let newSet = ctx.idMap.get(newNode);
					if (!newSet || !oldSet) return false;
					for (const id of oldSet) if (newSet.has(id)) return true;
					return false;
				}
				/**
				*
				* @param {Node} oldNode
				* @param {Node} newNode
				* @returns {boolean}
				*/
				function isSoftMatch(oldNode, newNode) {
					const oldElt = oldNode;
					const newElt = newNode;
					return oldElt.nodeType === newElt.nodeType && oldElt.tagName === newElt.tagName && (!oldElt.getAttribute?.("id") || oldElt.getAttribute?.("id") === newElt.getAttribute?.("id"));
				}
				return findBestMatch;
			})();
			/**
			* Gets rid of an unwanted DOM node; strategy depends on nature of its reuse:
			* - Persistent nodes will be moved to the pantry for later reuse
			* - Other nodes will have their hooks called, and then are removed
			* @param {MorphContext} ctx
			* @param {Node} node
			*/
			function removeNode(ctx, node) {
				if (node === ctx.pantry) return;
				if (ctx.idMap.has(node)) moveBefore(ctx.pantry, node, null);
				else {
					if (ctx.callbacks.beforeNodeRemoved(node) === false) return;
					node.parentNode?.removeChild(node);
					ctx.callbacks.afterNodeRemoved(node);
				}
			}
			/**
			* Remove nodes between the start and end nodes
			* @param {MorphContext} ctx
			* @param {Node} startInclusive
			* @param {Node} endExclusive
			* @returns {Node|null}
			*/
			function removeNodesBetween(ctx, startInclusive, endExclusive) {
				/** @type {Node | null} */
				let cursor = startInclusive;
				while (cursor && cursor !== endExclusive) {
					let tempNode = cursor;
					cursor = cursor.nextSibling;
					removeNode(ctx, tempNode);
				}
				return cursor;
			}
			/**
			* Search for an element by id within the document and pantry, and move it using moveBefore.
			*
			* @param {Element} parentNode - The parent node to which the element will be moved.
			* @param {string} id - The ID of the element to be moved.
			* @param {Node | null} after - The reference node to insert the element before.
			*                              If `null`, the element is appended as the last child.
			* @param {MorphContext} ctx
			* @returns {Element} The found element
			*/
			function moveBeforeById(parentNode, id, after, ctx) {
				const selector = `[id="${CSS.escape(id)}"]`;
				const target = ctx.target.getAttribute?.("id") === id && ctx.target || ctx.target.querySelector(selector) || ctx.pantry.querySelector(selector);
				removeElementFromAncestorsIdMaps(target, ctx);
				moveBefore(parentNode, target, after);
				return target;
			}
			/**
			* Removes an element from its ancestors' id maps. This is needed when an element is moved from the
			* "future" via `moveBeforeId`. Otherwise, its erstwhile ancestors could be mistakenly moved to the
			* pantry rather than being deleted, preventing their removal hooks from being called.
			*
			* @param {Element} element - element to remove from its ancestors' id maps
			* @param {MorphContext} ctx
			*/
			function removeElementFromAncestorsIdMaps(element, ctx) {
				const id = element.getAttribute("id");
				/** @ts-ignore - safe to loop in this way **/
				while (element = element.parentNode) {
					let idSet = ctx.idMap.get(element);
					if (idSet) {
						idSet.delete(id);
						if (!idSet.size) ctx.idMap.delete(element);
					}
				}
			}
			/**
			* Moves an element before another element within the same parent.
			* Uses the `moveBefore` API if available (and working), otherwise falls back to `insertBefore`.
			* This is essentialy a forward-compat wrapper.
			*
			* @param {Element} parentNode - The parent node containing the after element.
			* @param {Node} element - The element to be moved.
			* @param {Node | null} after - The reference node to insert `element` before.
			*                              If `null`, `element` is appended as the last child.
			*/
			function moveBefore(parentNode, element, after) {
				if (parentNode.moveBefore) try {
					parentNode.moveBefore(element, after);
				} catch (e) {
					parentNode.insertBefore(element, after);
				}
				else parentNode.insertBefore(element, after);
			}
			return morphChildren;
		})();
		const morphNode = (function() {
			/**
			* @param {Node} oldNode root node to merge content into
			* @param {Node} newContent new content to merge
			* @param {MorphContext} ctx the merge context
			* @returns {Node | null} the element that ended up in the DOM
			*/
			function morphNode(oldNode, newContent, ctx) {
				if (ctx.ignoreActive && oldNode === ctx.doc.activeElement) return null;
				if (ctx.callbacks.beforeNodeMorphed(oldNode, newContent) === false) return oldNode;
				if (is.headElement(oldNode) && ctx.head.style === "none") {} else if (is.headElement(oldNode) && ctx.head.style !== "morph") handleHeadElement(oldNode, newContent, ctx);
				else {
					morphAttributes(oldNode, newContent, ctx);
					if (!ignoreValueOfActiveElement(oldNode, ctx)) morphChildren(ctx, oldNode, newContent);
				}
				ctx.callbacks.afterNodeMorphed(oldNode, newContent);
				return oldNode;
			}
			/**
			* syncs the oldNode to the newNode, copying over all attributes and
			* inner element state from the newNode to the oldNode
			*
			* @param {Node} oldNode the node to copy attributes & state to
			* @param {Node} newNode the node to copy attributes & state from
			* @param {MorphContext} ctx the merge context
			*/
			function morphAttributes(oldNode, newNode, ctx) {
				let type = newNode.nodeType;
				if (type === 1) {
					const oldElt = oldNode;
					const newElt = newNode;
					const oldAttributes = oldElt.attributes;
					const newAttributes = newElt.attributes;
					for (const newAttribute of newAttributes) {
						if (oldElt.getAttribute(newAttribute.name) === newAttribute.value) continue;
						if (ignoreAttribute(newAttribute.name, oldElt, "update", ctx)) continue;
						oldElt.setAttributeNode(newAttribute.cloneNode());
					}
					for (let i = oldAttributes.length - 1; 0 <= i; i--) {
						const oldAttribute = oldAttributes[i];
						if (!oldAttribute) continue;
						if (!newElt.hasAttribute(oldAttribute.name)) {
							if (ignoreAttribute(oldAttribute.name, oldElt, "remove", ctx)) continue;
							oldElt.removeAttribute(oldAttribute.name);
						}
					}
					if (!ignoreValueOfActiveElement(oldElt, ctx)) syncInputValue(oldElt, newElt, ctx);
				}
				if (type === 8 || type === 3) {
					if (oldNode.nodeValue !== newNode.nodeValue) oldNode.nodeValue = newNode.nodeValue;
				}
			}
			/**
			* NB: many bothans died to bring us information:
			*
			*  https://github.com/patrick-steele-idem/morphdom/blob/master/src/specialElHandlers.js
			*  https://github.com/choojs/nanomorph/blob/master/lib/morph.jsL113
			*
			* @param {Element} oldElement the element to sync the input value to
			* @param {Element} newElement the element to sync the input value from
			* @param {MorphContext} ctx the merge context
			*/
			function syncInputValue(oldElement, newElement, ctx) {
				if (is.inputElement(oldElement) && is.inputElement(newElement) && newElement.type !== "file") {
					let newValue = newElement.value;
					let oldValue = oldElement.value;
					syncBooleanAttribute(oldElement, newElement, "checked", ctx);
					syncBooleanAttribute(oldElement, newElement, "disabled", ctx);
					if (!newElement.hasAttribute("value")) {
						if (!ignoreAttribute("value", oldElement, "remove", ctx)) {
							oldElement.value = "";
							oldElement.removeAttribute("value");
						}
					} else if (oldValue !== newValue) {
						if (!ignoreAttribute("value", oldElement, "update", ctx)) {
							oldElement.setAttribute("value", newValue);
							oldElement.value = newValue;
						}
					}
				} else if (is.optionElement(oldElement) && is.optionElement(newElement)) syncBooleanAttribute(oldElement, newElement, "selected", ctx);
				else if (is.textAreaElement(oldElement) && is.textAreaElement(newElement)) {
					let newValue = newElement.value;
					let oldValue = oldElement.value;
					if (ignoreAttribute("value", oldElement, "update", ctx)) return;
					if (newValue !== oldValue) oldElement.value = newValue;
					if (oldElement.firstChild && oldElement.firstChild.nodeValue !== newValue) oldElement.firstChild.nodeValue = newValue;
				}
			}
			/**
			* @param {Element} oldElement element to write the value to
			* @param {Element} newElement element to read the value from
			* @param {string} attributeName the attribute name
			* @param {MorphContext} ctx the merge context
			*/
			function syncBooleanAttribute(oldElement, newElement, attributeName, ctx) {
				const newLiveValue = newElement[attributeName];
				if (newLiveValue !== oldElement[attributeName]) {
					const ignoreUpdate = ignoreAttribute(attributeName, oldElement, "update", ctx);
					if (!ignoreUpdate) oldElement[attributeName] = newElement[attributeName];
					if (newLiveValue) {
						if (!ignoreUpdate) oldElement.setAttribute(attributeName, "");
					} else if (!ignoreAttribute(attributeName, oldElement, "remove", ctx)) oldElement.removeAttribute(attributeName);
				}
			}
			/**
			* @param {string} attr the attribute to be mutated
			* @param {Element} element the element that is going to be updated
			* @param {"update" | "remove"} updateType
			* @param {MorphContext} ctx the merge context
			* @returns {boolean} true if the attribute should be ignored, false otherwise
			*/
			function ignoreAttribute(attr, element, updateType, ctx) {
				if (attr === "value" && ctx.ignoreActiveValue && element === ctx.doc.activeElement) return true;
				return ctx.callbacks.beforeAttributeUpdated(attr, element, updateType) === false;
			}
			/**
			* @param {Node} possibleActiveElement
			* @param {MorphContext} ctx
			* @returns {boolean}
			*/
			function ignoreValueOfActiveElement(possibleActiveElement, ctx) {
				return !!ctx.ignoreActiveValue && possibleActiveElement === ctx.doc.activeElement && possibleActiveElement !== ctx.doc.body;
			}
			return morphNode;
		})();
		/**
		* @param {MorphContext} ctx
		* @param {Element} oldNode
		* @param {Element} newNode
		* @param {function} callback
		* @returns {Node[] | Promise<Node[]>}
		*/
		function withHeadBlocking(ctx, oldNode, newNode, callback) {
			if (ctx.head.block && ctx.head.style !== "none") {
				const oldHead = oldNode.querySelector("head");
				const newHead = newNode.querySelector("head");
				if (oldHead && newHead) {
					const promises = handleHeadElement(oldHead, newHead, ctx);
					return Promise.all(promises).then(() => {
						return callback(Object.assign(ctx, { head: {
							block: false,
							style: "none"
						} }));
					});
				}
			}
			return callback(ctx);
		}
		/**
		*  The HEAD tag can be handled specially, either w/ a 'merge' or 'append' style
		*
		* @param {Element} oldHead
		* @param {Element} newHead
		* @param {MorphContext} ctx
		* @returns {Promise<void>[]}
		*/
		function handleHeadElement(oldHead, newHead, ctx) {
			let added = [];
			let removed = [];
			let preserved = [];
			let nodesToAppend = [];
			let srcToNewHeadNodes = /* @__PURE__ */ new Map();
			for (const newHeadChild of newHead.children) srcToNewHeadNodes.set(newHeadChild.outerHTML, newHeadChild);
			for (const currentHeadElt of oldHead.children) {
				let inNewContent = srcToNewHeadNodes.has(currentHeadElt.outerHTML);
				let isReAppended = ctx.head.shouldReAppend(currentHeadElt);
				let isPreserved = ctx.head.shouldPreserve(currentHeadElt);
				if (inNewContent || isPreserved) {
					if (isReAppended) removed.push(currentHeadElt);
					else {
						srcToNewHeadNodes.delete(currentHeadElt.outerHTML);
						preserved.push(currentHeadElt);
					}
				} else if (ctx.head.style === "append") {
					if (isReAppended) {
						removed.push(currentHeadElt);
						nodesToAppend.push(currentHeadElt);
					}
				} else if (ctx.head.shouldRemove(currentHeadElt) !== false) removed.push(currentHeadElt);
			}
			nodesToAppend.push(...srcToNewHeadNodes.values());
			let promises = [];
			for (const newNode of nodesToAppend) {
				let newElt = ctx.doc.createRange().createContextualFragment(newNode.outerHTML).firstChild;
				if (ctx.callbacks.beforeNodeAdded(newElt) !== false) {
					if ("href" in newElt && newElt.href || "src" in newElt && newElt.src) {
						/** @type {(result?: any) => void} */ let resolve;
						let promise = new Promise(function(_resolve) {
							resolve = _resolve;
						});
						newElt.addEventListener("load", function() {
							resolve();
						});
						promises.push(promise);
					}
					oldHead.appendChild(newElt);
					ctx.callbacks.afterNodeAdded(newElt);
					added.push(newElt);
				}
			}
			for (const removedElement of removed) if (ctx.callbacks.beforeNodeRemoved(removedElement) !== false) {
				oldHead.removeChild(removedElement);
				ctx.callbacks.afterNodeRemoved(removedElement);
			}
			ctx.head.afterHeadMorphed(oldHead, {
				added,
				kept: preserved,
				removed
			});
			return promises;
		}
		const createMorphContext = (function() {
			/**
			*
			* @param {Element} oldNode
			* @param {Element} newContent
			* @param {Config} config
			* @returns {MorphContext}
			*/
			function createMorphContext(oldNode, newContent, config) {
				const { persistentIds, idMap } = createIdMaps(oldNode, newContent);
				const mergedConfig = mergeDefaults(config);
				const morphStyle = mergedConfig.morphStyle || "outerHTML";
				if (!["innerHTML", "outerHTML"].includes(morphStyle)) throw `Do not understand how to morph style ${morphStyle}`;
				if (morphStyle === "innerHTML" && !oldNode.append) throw `Cannot morph the innerHTML of a ${oldNode.nodeName} node, as it cannot have children`;
				const headStyle = mergedConfig.head.style || "merge";
				if (![
					"merge",
					"append",
					"morph",
					"none"
				].includes(headStyle)) throw `Do not understand how to morph head style ${headStyle}`;
				const doc = oldNode.ownerDocument;
				return {
					target: oldNode,
					newContent,
					doc,
					config: mergedConfig,
					morphStyle,
					ignoreActive: mergedConfig.ignoreActive,
					ignoreActiveValue: mergedConfig.ignoreActiveValue,
					restoreFocus: mergedConfig.restoreFocus,
					idMap,
					persistentIds,
					pantry: createPantry(doc),
					activeElementAndParents: createActiveElementAndParents(oldNode, doc),
					callbacks: mergedConfig.callbacks,
					head: mergedConfig.head
				};
			}
			/**
			* Deep merges the config object and the Idiomorph.defaults object to
			* produce a final configuration object
			* @param {Config} config
			* @returns {ConfigInternal}
			*/
			function mergeDefaults(config) {
				let finalConfig = Object.assign({}, defaults);
				Object.assign(finalConfig, config);
				finalConfig.callbacks = Object.assign({}, defaults.callbacks, config.callbacks);
				finalConfig.head = Object.assign({}, defaults.head, config.head);
				return finalConfig;
			}
			/**
			* @param {Document} doc
			* @returns {HTMLDivElement}
			*/
			function createPantry(doc) {
				const pantry = doc.createElement("div");
				pantry.hidden = true;
				doc.documentElement.append(pantry);
				return pantry;
			}
			/**
			* @param {Element} oldNode
			* @param {Document} doc
			* @returns {Element[]}
			*/
			function createActiveElementAndParents(oldNode, doc) {
				/** @type {Element[]} */
				let activeElementAndParents = [];
				let elt = doc.activeElement;
				if (elt?.tagName !== "BODY" && oldNode.contains(elt)) while (elt) {
					activeElementAndParents.push(elt);
					if (elt === oldNode) break;
					elt = elt.parentElement;
				}
				return activeElementAndParents;
			}
			/**
			* Returns all elements with a non-empty ID contained within the root node and its
			* descendants, each paired with its id so that it only has to be read once.
			*
			* @param {Node} root
			* @returns {IdElement[]}
			*/
			function findIdElements(root) {
				/** @type {IdElement[]} */
				let elements = [];
				const rootElt = root;
				for (const elt of rootElt.querySelectorAll?.("[id]") ?? []) {
					const id = elt.getAttribute("id");
					if (id) elements.push({
						elt,
						id
					});
				}
				const rootId = rootElt.getAttribute?.("id");
				if (rootId) elements.push({
					elt: root,
					id: rootId
				});
				return elements;
			}
			/**
			* A bottom-up algorithm that populates a map of Element -> IdSet.
			* The idSet for a given element is the set of all IDs contained within its subtree.
			* As an optimzation, we filter these IDs through the given list of persistent IDs,
			* because we don't need to bother considering IDed elements that won't be in the new content.
			*
			* @param {Map<Node, Set<string>>} idMap
			* @param {Set<string>} persistentIds
			* @param {Element} root
			* @param {IdElement[]} elements
			*/
			function populateIdMapWithTree(idMap, persistentIds, root, elements) {
				for (const { elt, id } of elements) if (persistentIds.has(id)) {
					/** @type {Element|null} */
					let current = elt;
					while (current) {
						let idSet = idMap.get(current);
						if (idSet == null) {
							idSet = /* @__PURE__ */ new Set();
							idMap.set(current, idSet);
						}
						idSet.add(id);
						if (current === root) break;
						current = current.parentElement;
					}
				}
			}
			/**
			* This function computes a map of nodes to all ids contained within that node (inclusive of the
			* node).  This map can be used to ask if two nodes have intersecting sets of ids, which allows
			* for a looser definition of "matching" than tradition id matching, and allows child nodes
			* to contribute to a parent nodes matching.
			*
			* @param {Element} oldContent  the old content that will be morphed
			* @param {Element} newContent  the new content to morph to
			* @returns {IdSets}
			*/
			function createIdMaps(oldContent, newContent) {
				const oldIdElements = findIdElements(oldContent);
				const newIdElements = findIdElements(newContent);
				const persistentIds = createPersistentIds(oldIdElements, newIdElements);
				/** @type {Map<Node, Set<string>>} */
				let idMap = /* @__PURE__ */ new Map();
				populateIdMapWithTree(idMap, persistentIds, oldContent, oldIdElements);
				populateIdMapWithTree(idMap, persistentIds, newContent.__idiomorphRoot || newContent, newIdElements);
				return {
					persistentIds,
					idMap
				};
			}
			/**
			* This function computes the set of ids that persist between the two contents excluding duplicates
			*
			* @param {IdElement[]} oldIdElements
			* @param {IdElement[]} newIdElements
			* @returns {Set<string>}
			*/
			function createPersistentIds(oldIdElements, newIdElements) {
				let duplicateIds = /* @__PURE__ */ new Set();
				/** @type {Map<string, string>} */
				let oldIdTagNameMap = /* @__PURE__ */ new Map();
				for (const { elt, id } of oldIdElements) if (oldIdTagNameMap.has(id)) duplicateIds.add(id);
				else oldIdTagNameMap.set(id, elt.tagName);
				let persistentIds = /* @__PURE__ */ new Set();
				for (const { elt, id } of newIdElements) if (persistentIds.has(id)) duplicateIds.add(id);
				else if (oldIdTagNameMap.get(id) === elt.tagName) persistentIds.add(id);
				for (const id of duplicateIds) persistentIds.delete(id);
				if (duplicateIds.size) console.warn("[Warning] duplicate ids found during morph, state loss within these elements is possible:", Array.from(duplicateIds));
				return persistentIds;
			}
			return createMorphContext;
		})();
		const { normalizeElement, normalizeParent } = (function() {
			/** @type {WeakSet<Node>} */
			const generatedByIdiomorph = /* @__PURE__ */ new WeakSet();
			/**
			*
			* @param {Node} content
			* @returns {Element}
			*/
			function normalizeElement(content) {
				if (is.document(content)) return content.documentElement;
				else return content;
			}
			/**
			*
			* @param {null | string | Node | HTMLCollection | Node[]} newContent
			* @returns {Element}
			*/
			function normalizeParent(newContent) {
				if (newContent == null) return document.createElement("div");
				else if (typeof newContent === "string") return normalizeParent(parseContent(newContent));
				else if (generatedByIdiomorph.has(newContent)) return newContent;
				else if (is.node(newContent)) {
					if (newContent.parentNode)
 /** @type {Element} */
					return new SlicedParentNode(newContent);
					else {
						const dummyParent = document.createElement("div");
						dummyParent.append(newContent);
						return dummyParent;
					}
				} else {
					const dummyParent = document.createElement("div");
					for (const elt of [...newContent]) dummyParent.append(elt);
					return dummyParent;
				}
			}
			/**
			* A fake duck-typed parent element to wrap a single node, without actually reparenting it.
			* This is useful because the node may have siblings that we don't want in the morph, and it may also be moved
			* or replaced with one or more elements during the morph. This class effectively allows us a window into
			* a slice of a node's children.
			* "If it walks like a duck, and quacks like a duck, then it must be a duck!" -- James Whitcomb Riley (1849–1916)
			*/
			class SlicedParentNode {
				/** @param {Node} node */
				constructor(node) {
					this.originalNode = node;
					this.realParentNode = node.parentNode;
					this.previousSibling = node.previousSibling;
					this.nextSibling = node.nextSibling;
				}
				/** @returns {Node[]} */
				get childNodes() {
					const nodes = [];
					let cursor = this.previousSibling ? this.previousSibling.nextSibling : this.realParentNode.firstChild;
					while (cursor && cursor != this.nextSibling) {
						nodes.push(cursor);
						cursor = cursor.nextSibling;
					}
					return nodes;
				}
				/**
				* @param {string} selector
				* @returns {Element[]}
				*/
				querySelectorAll(selector) {
					return this.childNodes.reduce((results, node) => {
						if (is.element(node)) {
							if (node.matches(selector)) results.push(node);
							const nodeList = node.querySelectorAll(selector);
							for (let i = 0; i < nodeList.length; i++) results.push(nodeList[i]);
						}
						return results;
					}, []);
				}
				/**
				* @param {Node} node
				* @param {Node} referenceNode
				* @returns {Node}
				*/
				insertBefore(node, referenceNode) {
					return this.realParentNode.insertBefore(node, referenceNode);
				}
				/**
				* @param {Node} node
				* @param {Node} referenceNode
				* @returns {Node}
				*/
				moveBefore(node, referenceNode) {
					return this.realParentNode.moveBefore(node, referenceNode);
				}
				/**
				* for later use with populateIdMapWithTree to halt upwards iteration
				* @returns {Node}
				*/
				get __idiomorphRoot() {
					return this.originalNode;
				}
			}
			/**
			*
			* @param {string} newContent
			* @returns {Node | null | DocumentFragment}
			*/
			function parseContent(newContent) {
				let parser = new DOMParser();
				let contentWithSvgsRemoved = newContent.replace(/<svg(\s[^>]*>|>)([\s\S]*?)<\/svg>/gim, "");
				if (contentWithSvgsRemoved.match(/<\/html>/) || contentWithSvgsRemoved.match(/<\/head>/) || contentWithSvgsRemoved.match(/<\/body>/)) {
					let content = parser.parseFromString(newContent, "text/html");
					content.doctype?.remove();
					if (contentWithSvgsRemoved.match(/<\/html>/)) {
						generatedByIdiomorph.add(content);
						return content;
					} else {
						let htmlElement = content.firstChild;
						if (htmlElement) generatedByIdiomorph.add(htmlElement);
						return htmlElement;
					}
				} else {
					let content = parser.parseFromString("<body><template>" + newContent + "</template></body>", "text/html").body.querySelector("template").content;
					generatedByIdiomorph.add(content);
					return content;
				}
			}
			return {
				normalizeElement,
				normalizeParent
			};
		})();
		const is = (function() {
			/** @param {Node | null | undefined} value @returns {value is Element} */
			const element = (value) => value instanceof Element || value?.nodeType === Node.ELEMENT_NODE;
			/**
			* @param {Node | null | undefined} value
			* @param {string} localName
			* @returns {value is Element}
			*/
			const htmlElement = (value, localName) => element(value) && value.localName === localName && value.namespaceURI === "http://www.w3.org/1999/xhtml";
			return {
				element,
				/** @param {unknown} value @returns {value is Node} */
				node: (value) => value instanceof Node || typeof value?.nodeType === "number",
				/** @param {Node | null | undefined} value @returns {value is Document} */
				document: (value) => value instanceof Document || value?.nodeType === Node.DOCUMENT_NODE,
				/** @param {Node | null | undefined} value @returns {value is HTMLTemplateElement} */
				templateElement: (value) => htmlElement(value, "template"),
				/** @param {Node | null | undefined} value @returns {value is HTMLHeadElement} */
				headElement: (value) => htmlElement(value, "head"),
				/** @param {Node | null | undefined} value @returns {value is HTMLInputElement} */
				inputElement: (value) => htmlElement(value, "input"),
				/** @param {Node | null | undefined} value @returns {value is HTMLOptionElement} */
				optionElement: (value) => htmlElement(value, "option"),
				/** @param {Node | null | undefined} value @returns {value is HTMLTextAreaElement} */
				textAreaElement: (value) => htmlElement(value, "textarea")
			};
		})();
		return {
			morph,
			defaults
		};
	})();
	//#endregion
	//#region src/main/ts/morph.ts
	/**
	* Applies the server's patches with Idiomorph (ADR 0003), keeping what is the browser's: the
	* pending state of a blocked element, form state the render did not set (ADR 0013), a client
	* element's node and children (ADR 0022), and what the runtime added to the head.
	*/
	function createMorpher(pending, syncClient) {
		const callbacks = {
			beforeAttributeUpdated: (name, el) => {
				if (pending.has(el) && (name === "data-pending" || name === "aria-busy")) return false;
				if ((name === "value" || name === "checked" || name === "selected") && el.hasAttribute("data-j2-controlled")) return el.getAttribute("data-j2-controlled").split(" ").indexOf(name) >= 0;
				return true;
			},
			beforeNodeMorphed: (oldNode, newNode) => {
				if (oldNode.nodeType === 1 && newNode.nodeType === 1 && oldNode.hasAttribute("data-j2-client") && oldNode.getAttribute("data-j2-client") === newNode.getAttribute("data-j2-client")) {
					syncClient(oldNode, newNode);
					return false;
				}
				return true;
			}
		};
		const config = {
			morphStyle: "outerHTML",
			ignoreActiveValue: true,
			callbacks
		};
		const slotConfig = {
			morphStyle: "innerHTML",
			ignoreActiveValue: true,
			callbacks
		};
		const headConfig = {
			morphStyle: "innerHTML",
			callbacks: { beforeNodeRemoved: (node) => !(node.nodeType === 1 && node.hasAttribute("data-j2-keep")) }
		};
		function head(html) {
			Idiomorph.morph(document.head, html, headConfig);
		}
		function body(fresh) {
			Idiomorph.morph(document.body, fresh, config);
		}
		return {
			head,
			body,
			/**
			* Morphs the component at anchor; false when it is not in the document. The tab root may
			* be a new component after a navigation, with a new anchor, so it replaces head and body.
			*/
			patch(anchor, html, isRoot) {
				if (isRoot) {
					const doc = new DOMParser().parseFromString(html, "text/html");
					document.documentElement.setAttribute("data-j2-scope", anchor);
					head(doc.head.innerHTML);
					body(doc.body);
					return true;
				}
				const el = document.querySelector("[data-j2-scope=\"" + anchor + "\"]");
				if (!el) return false;
				Idiomorph.morph(el, html, config);
				return true;
			},
			slot(live, html) {
				Idiomorph.morph(live, html, slotConfig);
			}
		};
	}
	//#endregion
	//#region src/main/ts/navigation.ts
	/**
	* Soft navigation (ADR 0011): links and back/forward go over the socket, and the server answers
	* with patches, then the final URL. Intent on a link (hover, focus, touch) asks the server to
	* mount its target early.
	*/
	function createNavigation(send, base, preloadByDefault) {
		let pendingHash = "";
		const preloadedAt = /* @__PURE__ */ new Map();
		let hoverTimer;
		function go(url, mode) {
			history.replaceState({
				j2: 1,
				y: window.scrollY
			}, "");
			pendingHash = url.hash;
			send({
				type: "navigate",
				url: url.pathname + url.search,
				mode
			});
		}
		function inApp(url) {
			return url.origin === location.origin && (base === "" || url.pathname === base || url.pathname.indexOf(base + "/") === 0);
		}
		function preloadUrl(target) {
			const a = link(target);
			if (!a || a.hasAttribute("download") || a.hasAttribute("data-j2-reload") || a.target && a.target !== "_self") return null;
			const mode = a.getAttribute("data-j2-preload");
			if (mode === "none" || mode !== "intent" && !preloadByDefault) return null;
			const url = new URL(a.href, location.href);
			if (!inApp(url) || url.pathname === location.pathname && url.search === location.search) return null;
			return url.pathname + url.search;
		}
		function preload(u) {
			const at = preloadedAt.get(u);
			if (at && Date.now() - at < 5e3) return;
			preloadedAt.set(u, Date.now());
			send({
				type: "preload",
				url: u
			});
		}
		return {
			listen(doc) {
				if ("scrollRestoration" in history) history.scrollRestoration = "manual";
				history.replaceState({
					j2: 1,
					y: window.scrollY
				}, "");
				doc.addEventListener("mouseover", (e) => {
					const u = preloadUrl(e.target);
					clearTimeout(hoverTimer);
					if (u) hoverTimer = setTimeout(() => preload(u), 50);
				});
				for (const type of ["focusin", "touchstart"]) doc.addEventListener(type, (e) => {
					const u = preloadUrl(e.target);
					if (u) preload(u);
				}, { passive: true });
				doc.addEventListener("click", (e) => {
					if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
					const a = link(e.target);
					if (!a || a.hasAttribute("data-j2-click") || a.hasAttribute("download") || a.hasAttribute("data-j2-reload") || a.target && a.target !== "_self") return;
					const url = new URL(a.href, location.href);
					if (!inApp(url)) return;
					if (url.pathname === location.pathname && url.search === location.search && url.hash) return;
					e.preventDefault();
					go(url, "push");
				});
				window.addEventListener("popstate", () => {
					pendingHash = location.hash;
					send({
						type: "navigate",
						url: location.pathname + location.search,
						mode: "pop"
					});
				});
			},
			/** The server's answer to a navigation, after its patches: the final URL, after redirects. */
			landed(u, mode) {
				const target = u + pendingHash;
				if (mode === "push") history.pushState({
					j2: 1,
					y: 0
				}, "", target);
				else if (mode === "replace") history.replaceState({
					j2: 1,
					y: 0
				}, "", target);
				if (mode === "pop") {
					const y = history.state && typeof history.state.y === "number" ? history.state.y : 0;
					window.scrollTo(0, y);
				} else if (pendingHash) {
					const anchor = document.getElementById(decodeURIComponent(pendingHash.slice(1)));
					if (anchor) anchor.scrollIntoView();
				} else window.scrollTo(0, 0);
				pendingHash = "";
			}
		};
	}
	function link(target) {
		return target instanceof Element ? target.closest("a[href]") : null;
	}
	//#endregion
	//#region src/main/ts/pending.ts
	/**
	* Pending UI (ADR 0013): instant on click or submit, reverted on the ack unless a morph already
	* replaced it. The element is blocked meanwhile; swap is where withPending markup goes, which for
	* a submit is the submitter button.
	*/
	function createPending() {
		const inFlight = /* @__PURE__ */ new Map();
		const blocked = /* @__PURE__ */ new Set();
		const saved = /* @__PURE__ */ new WeakMap();
		return {
			has(el) {
				return blocked.has(el);
			},
			get size() {
				return inFlight.size;
			},
			start(el, ack, swap) {
				blocked.add(el);
				inFlight.set(ack, {
					el,
					swap
				});
				for (const target of [el, swap]) {
					target.setAttribute("data-pending", "");
					target.setAttribute("aria-busy", "true");
				}
				const tpl = swap.querySelector(":scope > template[data-j2-pending]");
				if (tpl) {
					saved.set(swap, swap.innerHTML);
					swap.innerHTML = tpl.outerHTML + "<span data-j2-pending-live>" + tpl.innerHTML + "</span>";
				}
			},
			ack(ack) {
				const entry = inFlight.get(ack);
				inFlight.delete(ack);
				if (!entry) return;
				blocked.delete(entry.el);
				for (const target of [entry.el, entry.swap]) {
					target.removeAttribute("data-pending");
					target.removeAttribute("aria-busy");
				}
				const swap = entry.swap;
				const before = saved.get(swap);
				saved.delete(swap);
				if (before !== void 0 && swap.querySelector(":scope > [data-j2-pending-live]")) swap.innerHTML = before;
			},
			/** A new server-side tab will never ack these. */
			clear() {
				inFlight.clear();
				blocked.clear();
			}
		};
	}
	//#endregion
	//#region src/main/ts/reopen.ts
	var KEY = "j2act:tabs";
	/** URLs remembered per browser tab, oldest dropped first. */
	var MAX = 20;
	function read() {
		try {
			const tokens = JSON.parse(sessionStorage.getItem(KEY) || "{}");
			return tokens && typeof tokens === "object" ? tokens : {};
		} catch {
			return {};
		}
	}
	function write(tokens) {
		try {
			sessionStorage.setItem(KEY, JSON.stringify(tokens));
		} catch {}
	}
	/** How this page load came about, as the browser reports it. */
	function navigationType() {
		return (performance.getEntriesByType?.("navigation")[0])?.type ?? "navigate";
	}
	/** The token a closed page left for this URL: to restore when going back or forward, to forget on a reload. */
	function takePrevious(url, current, type) {
		const tokens = read();
		const previous = tokens[url];
		if (!previous || previous === current) return {};
		delete tokens[url];
		write(tokens);
		return type === "back_forward" ? { restore: previous } : type === "reload" ? { forget: previous } : {};
	}
	/** On pagehide: this URL was last shown by the server-side tab with this token. */
	function remember(url, token) {
		const tokens = read();
		for (const key of Object.keys(tokens)) if (tokens[key] === token) delete tokens[key];
		tokens[url] = token;
		const keys = Object.keys(tokens);
		for (let i = 0; i < keys.length - MAX; i++) delete tokens[keys[i]];
		write(tokens);
	}
	//#endregion
	//#region src/main/ts/retained.ts
	/** How long a hidden tab waits before trying again, after the server or the page was busy. */
	var RETRY_MS = 1e4;
	function createRetained(options) {
		let current = "running";
		/** The pause started because the tab was hidden: it gives way when busy, and the tab showing again resumes. */
		let byHiding = false;
		/** The server had Retained State to keep, so resume() reports whether it came back. */
		let withState = false;
		let deferral = null;
		let handlers = [];
		const pauseWaiters = [];
		const resumeWaiters = [];
		let hiddenTimer;
		/** As .NET's onCircuitPausing: runs before every pause and delays it until its promise settles. */
		function onPausing(handler) {
			handlers.push(handler);
			return () => {
				handlers = handlers.filter((h) => h !== handler);
			};
		}
		/** Resolves true once the server saved the page's Retained State and let its tab go. */
		function pause() {
			if (current === "paused") return Promise.resolve(true);
			return new Promise((resolve) => {
				pauseWaiters.push(resolve);
				if (current === "running") begin(false);
				else byHiding = false;
			});
		}
		/** Resolves once the page runs again on a new server-side tab: true when its Retained State came back, or it had none. */
		function resume() {
			if (current === "deferring") {
				cancel("resumed");
				return Promise.resolve(true);
			}
			if (current === "pausing") return new Promise((resolve) => pauseWaiters.push(() => resume().then(resolve)));
			if (current !== "paused") return Promise.resolve(false);
			current = "running";
			byHiding = false;
			document.documentElement.removeAttribute("data-j2-paused");
			return new Promise((resolve) => {
				resumeWaiters.push(resolve);
				options.remount();
			});
		}
		/** The server let the tab go; saved: it had Retained State to keep. */
		function paused(saved) {
			withState = saved;
			current = "paused";
			options.stopSending();
			document.documentElement.setAttribute("data-j2-paused", "");
			document.dispatchEvent(new CustomEvent("j2act:paused"));
			settle(true);
			if (byHiding && !document.hidden) resume();
		}
		/** Runs the onPausing handlers, then asks the server; a handler that fails calls the pause off. */
		function begin(automatic) {
			current = "deferring";
			byHiding = automatic;
			const controller = new AbortController();
			deferral = controller;
			Promise.all(handlers.map((h) => Promise.resolve().then(() => h(controller.signal)))).then(() => {
				if (deferral === controller) {
					deferral = null;
					current = "pausing";
					send();
				}
			}, (e) => {
				if (deferral === controller) {
					console.warn("j2act: an onPausing handler failed, so the page did not pause", e);
					cancel("an onPausing handler failed");
				}
			});
		}
		function send() {
			options.sendIfReady(byHiding ? {
				type: "pause",
				automatic: true
			} : { type: "pause" });
		}
		function cancel(reason) {
			stopDeferral(reason);
			current = "running";
			byHiding = false;
			settle(false);
		}
		function stopDeferral(reason) {
			if (deferral) {
				deferral.abort(reason);
				deferral = null;
			}
		}
		function settle(ok) {
			pauseWaiters.splice(0).forEach((resolve) => resolve(ok));
		}
		function autoPause() {
			hiddenTimer = void 0;
			if (!document.hidden || current !== "running" || options.remounting()) return;
			if (options.busy()) {
				hiddenTimer = setTimeout(autoPause, RETRY_MS);
				return;
			}
			begin(true);
		}
		return {
			get phase() {
				return current;
			},
			onPausing,
			pause,
			resume,
			paused,
			/** window.j2act; see j2act.d.ts. */
			api() {
				return Object.freeze({
					pause,
					resume,
					onPausing
				});
			},
			/** Hidden tabs pause after autoPauseAfter, and resume when shown, if the pause was theirs. */
			listen(doc) {
				if (options.autoPauseAfter <= 0) return;
				doc.addEventListener("visibilitychange", () => {
					clearTimeout(hiddenTimer);
					hiddenTimer = void 0;
					if (doc.hidden) hiddenTimer = setTimeout(autoPause, options.autoPauseAfter);
					else if (current === "deferring" && byHiding) cancel("the tab is visible again");
					else if (current === "paused" && byHiding) resume();
				});
			},
			/** The socket is up again: a pause that waited for it goes out now. */
			connected() {
				if (current === "pausing") send();
			},
			/** The server asked for a pause, as RequestCircuitPauseAsync does: handlers may delay it, not refuse it. */
			requested() {
				if (current === "running") begin(false);
			},
			/**
			* The server-side tab is gone. Evicted while a pause was on its way, a snapshot may be saved all the
			* same, so the page counts as paused. Returns whether the page should remount instead.
			*/
			expired() {
				if (current === "pausing" || current === "deferring") {
					stopDeferral("the server-side tab ended");
					paused(true);
					return false;
				}
				return current === "running";
			},
			/** The server was busy for an automatic pause. */
			refused() {
				if (!byHiding) {
					send();
					return;
				}
				current = "running";
				byHiding = false;
				if (document.hidden) hiddenTimer = setTimeout(autoPause, RETRY_MS);
			},
			/**
			* The page runs on its new server-side tab. As .NET's resumeCircuit(): false when the page had state to
			* keep and got none back, as when its snapshot expired, was used already or belongs to someone
			* else. The page runs either way.
			*/
			resumed(restored) {
				if (resumeWaiters.length) {
					const ok = restored || !withState;
					document.dispatchEvent(new CustomEvent("j2act:resumed", { detail: { restored: ok } }));
					resumeWaiters.splice(0).forEach((resolve) => resolve(ok));
				}
			}
		};
	}
	//#endregion
	//#region src/main/ts/uploads.ts
	var CHUNK = 524288;
	/**
	* Uploads (ADR 0006): the event names the file, the server answers with where to send it, and
	* the chunks go over HTTP.
	*/
	function createUploads(send, token) {
		const files = /* @__PURE__ */ new Map();
		const waiters = /* @__PURE__ */ new Map();
		let seq = 0;
		function settle(id, error) {
			const waiter = waiters.get(id);
			waiters.delete(id);
			if (waiter && error) waiter.reject(error);
			else if (waiter) waiter.resolve();
		}
		return {
			/** Files still being sent. */
			get size() {
				return files.size;
			},
			/** A file input changed: its event names the file, which waits here for the server's go. */
			picked(input) {
				const file = input.files && input.files[0];
				if (!file) return {};
				const id = String(++seq);
				files.set(id, file);
				return {
					fileId: id,
					fileName: file.name,
					fileSize: file.size,
					fileType: file.type || ""
				};
			},
			target(handler) {
				return { send: (blob, name) => {
					return new Promise((resolve, reject) => {
						const fileId = String(++seq);
						files.set(fileId, blob);
						waiters.set(fileId, {
							resolve,
							reject
						});
						send({
							type: "callback",
							handler,
							args: "[]",
							fileId,
							fileName: name || blob.name || "blob",
							fileSize: blob.size,
							fileType: blob.type || ""
						});
					});
				} };
			},
			/** Refused before the first chunk: drop the file, reject an UploadTarget.send. */
			refused(id, message) {
				files.delete(id);
				settle(id, named("NotAllowedError", message));
			},
			/**
			* Sends the file in order. The server's offset decides where each chunk starts, so a failed
			* or repeated chunk resumes where the bytes really stopped.
			*/
			upload(id, url) {
				const file = files.get(id);
				if (!file) return;
				let offset = 0;
				let failures = 0;
				const again = () => {
					if (++failures > 8) {
						files.delete(id);
						settle(id, named("NetworkError", "the upload kept failing"));
						return;
					}
					setTimeout(next, backoff(failures));
				};
				const next = () => {
					fetch(url + "?o=" + offset, {
						method: "POST",
						credentials: "same-origin",
						headers: {
							"X-J2-Token": token() || "",
							"Content-Type": "application/octet-stream"
						},
						body: file.slice(offset, offset + CHUNK)
					}).then((r) => {
						return r.json().catch(() => ({})).then((j) => ({
							status: r.status,
							body: j
						}));
					}).then((res) => {
						if ((res.status === 200 || res.status === 409) && typeof res.body.o === "number") {
							failures = 0;
							offset = res.body.o;
							if (res.body.done) {
								files.delete(id);
								settle(id, null);
							} else next();
						} else if (res.status === 429) again();
						else {
							files.delete(id);
							settle(id, named("NotAllowedError", "the server refused the upload"));
						}
					}, again);
				};
				next();
			}
		};
	}
	//#endregion
	//#region src/main/ts/main.ts
	function start() {
		const sid = meta(document, "j2-tab");
		const wsPath = meta(document, "j2-ws");
		const base = meta(document, "j2-base") || "";
		if (!sid || !wsPath) return;
		let leaving = false;
		let remounting = false;
		const here = () => location.pathname + location.search;
		const previous = takePrevious(here(), meta(document, "j2-token"), navigationType());
		const connection = createConnection((location.protocol === "https:" ? "wss://" : "ws://") + location.host + wsPath, sid, meta(document, "j2-token"), onMessage, () => !leaving && !remounting && retained.phase !== "paused");
		const send = (message) => connection.send(message);
		const pending = createPending();
		const uploads = createUploads(send, () => connection.token);
		const morpher = createMorpher(pending, (el, fresh) => clients.sync(el, fresh));
		const clients = createClients({
			send,
			uploads,
			morphSlot: (live, html) => morpher.slot(live, html)
		});
		const navigation = createNavigation(send, base, meta(document, "j2-preload") === "intent");
		const retained = createRetained({
			sendIfReady: (message) => connection.sendIfReady(message),
			stopSending: () => connection.hold(),
			remount,
			remounting: () => remounting,
			busy,
			autoPauseAfter: Number(meta(document, "j2-autopause") || 0)
		});
		const handlers = {
			ready: () => {
				connection.opened();
				retained.connected();
			},
			patch: (m) => {
				if (!morpher.patch(m.anchor, m.html, m.root === true)) clients.anchorGone(m.anchor);
				clients.scan();
			},
			head: (m) => morpher.head(m.html),
			url: (m) => navigation.landed(m.url, m.mode),
			load: (m) => location.assign(m.url),
			download: (m) => download(m.url),
			upload: (m) => uploads.upload(m.file, m.url),
			uploadRefused: (m) => uploads.refused(m.file, m.error),
			windowCall: (m) => {
				let result;
				try {
					result = runWeb(JSON.parse(m.call));
				} catch (e) {
					clients.reply(m.id, false, e);
					return;
				}
				result.then((v) => clients.reply(m.id, true, v), (e) => clients.reply(m.id, false, e));
			},
			clientCall: (m) => clients.call(m),
			ack: (m) => pending.ack(m.id),
			expired: () => {
				if (retained.expired()) remount();
			},
			paused: (m) => retained.paused(m.saved === true),
			pauseRefused: () => retained.refused(),
			pauseRequested: () => retained.requested(),
			modules: (m) => clients.reimport(m.modules),
			browserCookie: (m) => claimBrowserCookie(m.url)
		};
		function onMessage(m) {
			handlers[m.type]?.(m);
		}
		/** Starts over on a new server-side tab; restoring names a closed page's token instead of this one's (ADR 0030). */
		function remount(restoring) {
			if (remounting) return;
			remounting = true;
			connection.close();
			const tok = restoring ?? connection.token;
			fetch(location.href, {
				credentials: "same-origin",
				headers: tok ? { "X-J2-Restore": tok } : {}
			}).then((r) => r.text()).then((html) => {
				const doc = new DOMParser().parseFromString(html, "text/html");
				const sid = meta(doc, "j2-tab");
				const tok = meta(doc, "j2-token");
				document.querySelector("meta[name=\"j2-tab\"]").content = sid;
				document.querySelector("meta[name=\"j2-token\"]").content = tok;
				document.documentElement.setAttribute("data-j2-scope", doc.documentElement.getAttribute("data-j2-scope"));
				document.title = doc.title;
				pending.clear();
				morpher.body(doc.body);
				clients.scan();
				remounting = false;
				connection.restart(sid, tok);
				retained.resumed(meta(doc, "j2-restored") === "1");
			}).catch(() => {
				remounting = false;
				setTimeout(() => remount(restoring), 2e3);
			});
		}
		/** Failed claims of the browser cookie so far; the server offers a fresh URL after each, a few times. */
		let cookieClaimFailures = 0;
		/** Claims run one after another, so the latest offer's cookie is the one that lands last. */
		let claims = Promise.resolve();
		function claimBrowserCookie(url) {
			claims = claims.then(() => fetch(url, {
				credentials: "same-origin",
				cache: "no-store"
			}).then((r) => {
				if (!r.ok) throw new Error("browser cookie claim answered " + r.status);
				cookieClaimFailures = 0;
			}).catch(() => {
				if (cookieClaimFailures < 5) setTimeout(() => send({ type: "browserCookieRetry" }), 1e3 * 2 ** cookieClaimFailures++);
			}));
		}
		function busy() {
			const a = document.activeElement;
			return pending.size > 0 || uploads.size > 0 || !!a && a !== document.body && (a.matches("input, textarea, select") || a.isContentEditable) || Array.prototype.some.call(document.querySelectorAll("audio, video"), (m) => !m.paused && !m.ended);
		}
		window.addEventListener("pagehide", () => {
			if (connection.token) remember(here(), connection.token);
			if (connection.ready) {
				leaving = true;
				connection.sendIfReady({ type: "bye" });
			}
		});
		window.addEventListener("pageshow", (e) => {
			if (e.persisted && retained.phase !== "paused") {
				leaving = false;
				remount();
			}
		});
		navigation.listen(document);
		listenForEvents(document, {
			send,
			pending,
			clients,
			uploads
		});
		retained.listen(document);
		window.j2act = retained.api();
		clients.scan();
		if (previous.restore) remount(previous.restore);
		else {
			connection.connect();
			if (previous.forget) send({
				type: "forget",
				token: previous.forget
			});
		}
	}
	/** A download link: the browser saves the response and the page stays (ADR 0012). */
	function download(url) {
		const a = document.createElement("a");
		a.href = url;
		a.download = "";
		a.style.display = "none";
		document.body.appendChild(a);
		a.click();
		a.remove();
	}
	start();
	//#endregion
})();
