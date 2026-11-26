// A load test of a shared store (ADR 0037): one large room of the demo's /chat/{room}, read by many pages over
// real sockets, some of which may delete every message (uneven permissions), some of which stop reading their
// socket (slow readers), while posters post at a steady rate. It prints how many messages went through, how long
// each took to reach each kind of reader, and checks that the slow readers held nobody else up.
//
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089
//   node tools/chat-load.mjs --url http://localhost:8089 [--readers 300] [--admins 20] [--slow 20] [--posters 10]
//     [--rate 50] [--seconds 30] [--keep 0] [--p99 1000] [--workers 8]
//
// --url takes several servers of one cluster, comma-separated: pages spread over them. --keep N has each poster
// delete what it posted beyond its last N, by the delete button its page shows it, so the room stays at a steady
// size; 0 lets it grow. Pages speak the runtime's protocol (hello, seen, event) over a WebSocket client of their own
// on a raw TCP socket, so that a slow reader really stops reading: the server's writes to it fill the socket and
// wait, as with a phone gone quiet. Readers run on --workers threads, so the client keeps up with the server.
import net from "node:net";
import crypto from "node:crypto";
import os from "node:os";
import { Worker, isMainThread, parentPort, workerData } from "node:worker_threads";
import { fileURLToPath } from "node:url";

const sleep = ms => new Promise(r => setTimeout(r, ms));
const find = (html, re) => (html.match(re) || [])[1];

/** A minimal WebSocket client (RFC 6455) over a TCP socket it controls. */
function socket(url, onText, onClose) {
  const { hostname, port } = new URL(url);
  const key = crypto.randomBytes(16).toString("base64");
  const tcp = net.connect(+port, hostname);
  let buffer = Buffer.alloc(0);
  let open = false;
  let parts = [];
  return new Promise((resolve, reject) => {
    tcp.on("connect", () => tcp.write(`GET /_j2act/ws HTTP/1.1\r\nHost: ${hostname}:${port}\r\n`
      + `Upgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: ${key}\r\nSec-WebSocket-Version: 13\r\n`
      + `Origin: ${new URL(url).origin}\r\n\r\n`));
    tcp.on("error", e => (open ? onClose(e.message) : reject(e)));
    tcp.on("close", () => onClose("closed"));
    tcp.on("data", chunk => {
      buffer = buffer.length ? Buffer.concat([buffer, chunk]) : chunk;
      if (!open) {
        const end = buffer.indexOf("\r\n\r\n");
        if (end < 0) return;
        const head = buffer.subarray(0, end).toString();
        if (!head.startsWith("HTTP/1.1 101")) {
          reject(new Error(head.split("\r\n")[0]));
          tcp.destroy();
          return;
        }
        buffer = buffer.subarray(end + 4);
        open = true;
        resolve(client);
      }
      while (buffer.length >= 2) {
        const fin = buffer[0] & 0x80, op = buffer[0] & 0x0f;
        let len = buffer[1] & 0x7f, at = 2;
        if (len === 126) { if (buffer.length < 4) return; len = buffer.readUInt16BE(2); at = 4; }
        else if (len === 127) { if (buffer.length < 10) return; len = Number(buffer.readBigUInt64BE(2)); at = 10; }
        if (buffer.length < at + len) return;
        const payload = buffer.subarray(at, at + len);
        buffer = buffer.subarray(at + len);
        if (op === 0x9) frame(0xA, payload);
        else if (op === 0x8) tcp.end();
        else if (op === 0x1 || op === 0x0) {
          parts.push(payload);
          if (fin) { onText(parts.length === 1 ? parts[0].toString() : Buffer.concat(parts).toString()); parts = []; }
        }
      }
    });
    function frame(op, data) {
      const body = Buffer.from(data);
      const mask = crypto.randomBytes(4);
      const head = body.length < 126 ? Buffer.from([0x80 | op, 0x80 | body.length])
        : Buffer.from([0x80 | op, 0x80 | 126, body.length >> 8, body.length & 0xff]);
      for (let i = 0; i < body.length; i++) body[i] ^= mask[i % 4];
      if (!tcp.destroyed) tcp.write(Buffer.concat([head, mask, body]));
    }
    const client = { send: text => frame(0x1, text), tcp, close: () => tcp.destroy() };
  });
}

/**
 * A page of the room: loaded over HTTP, then live over its socket, acknowledging what it applied as the runtime does.
 * What it shows of each message is timed by the token the poster wrote into it: m.<number>.<sent at>.
 */
async function page(url, room, name, kind) {
  const res = await fetch(`${url}/chat/${room}?name=${encodeURIComponent(name)}`);
  const html = await res.text();
  const p = { name, kind, seen: new Map(), deletes: new Map(), applied: 0, closed: null, bytes: 0,
    say: find(html, /id="say"[^>]*data-j2-change="([^"]+)"/) };
  p.ws = await socket(url, text => {
    p.bytes += text.length;
    const now = Date.now();
    for (const m of text.matchAll(/m\.(\d+)\.(\d+)\./g)) {
      if (!p.seen.has(m[1])) p.seen.set(m[1], now - +m[2]);
    }
    // A delete button is the permission a page was rendered with: an admin's for every message, a poster's for its own.
    for (const b of text.matchAll(/data-j2-click=\\*"([^"\\]+)\\*"[^>]*>delete (\d+)</g)) {
      p.deletes.set(b[2], b[1]);
    }
    const n = text.match(/"n":(\d+)/);
    if (n) p.applied = Math.max(p.applied, +n[1]);
  }, why => { p.closed ??= why; });
  p.ws.send(JSON.stringify({ type: "hello", tab: find(html, /name="j2-tab" content="([^"]+)"/),
    token: find(html, /name="j2-token" content="([^"]+)"/), seen: 0 }));
  if (kind === "slow") {
    // Reads its hello's answer, then nothing more: what the server sends it piles up in the socket.
    setTimeout(() => p.ws.tcp.pause(), 1500);
  } else {
    p.acks = setInterval(() => p.ws.send(JSON.stringify({ type: "seen", n: p.applied })), 100);
  }
  return p;
}

async function openAll(list, room) {
  const pages = [];
  for (let batch = 0; batch < list.length; batch += 25) {
    pages.push(...await Promise.all(list.slice(batch, batch + 25).map(d => page(d.url, room, d.name, d.kind))));
  }
  return pages;
}

/** What a page tells the coordinator at the end. */
function result(p) {
  return { kind: p.kind, seen: p.seen.size, latencies: Int32Array.from(p.seen.values()), deletes: p.deletes.size,
    bytes: p.bytes, closed: p.closed };
}

if (!isMainThread) {
  // A worker: its share of the readers, until the coordinator asks what they saw.
  const pages = await openAll(workerData.pages, workerData.room);
  parentPort.postMessage({ type: "open" });
  parentPort.once("message", () => {
    const results = pages.map(result);
    parentPort.postMessage({ type: "done", results }, results.map(r => r.latencies.buffer));
    for (const p of pages) {
      clearInterval(p.acks);
      p.ws.close();
    }
  });
} else {
  const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, a, i, all) =>
    a.startsWith("--") ? [...pairs, [a.slice(2), all[i + 1]]] : pairs, []));
  const urls = (args.url || "http://localhost:8089").split(",").map(u => u.replace(/\/$/, ""));
  const READERS = +(args.readers ?? 300);
  const ADMINS = +(args.admins ?? 20);
  const SLOW = +(args.slow ?? 20);
  const POSTERS = +(args.posters ?? 10);
  const RATE = +(args.rate ?? 50);
  const SECONDS = +(args.seconds ?? 30);
  const KEEP = +(args.keep ?? 0);
  const P99 = +(args.p99 ?? 1000);
  const WORKERS = +(args.workers ?? Math.max(1, Math.min(8, Math.floor(os.cpus().length / 2))));
  const ROOM = "load" + Date.now();

  console.log(`room ${ROOM} on ${urls.join(", ")}: ${READERS} readers, ${ADMINS} admins, ${SLOW} slow, ${POSTERS}`
    + ` posters, ${RATE} messages/s for ${SECONDS} s${KEEP ? `, keeping ${KEEP} per poster` : ""}, ${WORKERS} workers`);
  const opening = Date.now();
  const kinds = [...Array(READERS).fill("reader"), ...Array(ADMINS).fill("admin"), ...Array(SLOW).fill("slow")];
  const shares = Array.from({ length: WORKERS }, () => []);
  kinds.forEach((kind, n) => shares[n % WORKERS].push({ url: urls[n % urls.length], kind,
    name: kind === "admin" ? "admin" : `${kind}${n}` }));
  const workers = shares.map(pages => new Worker(fileURLToPath(import.meta.url), { workerData: { pages, room: ROOM } }));
  const opened = workers.map(w => new Promise((resolve, reject) => {
    w.once("message", resolve);
    w.once("error", reject);
  }));
  // The posters here, so that the rate is this thread's own.
  const posters = await openAll(Array.from({ length: POSTERS }, (_, n) => ({ url: urls[n % urls.length],
    kind: "poster", name: `poster${n}` })), ROOM);
  await Promise.all(opened);
  console.log(`${kinds.length + POSTERS} pages open in ${Date.now() - opening} ms`);
  await sleep(2000);

  let seq = 0, events = 0;
  const started = Date.now();
  const every = 1000 / RATE;
  while (Date.now() - started < SECONDS * 1000) {
    // Paced by the clock, not by the last wait, so a slow tick does not lower the rate.
    const due = started + seq * every;
    const wait = due - Date.now();
    if (wait > 0) await sleep(wait);
    const poster = posters[seq % posters.length];
    const id = ++seq;
    poster.ws.send(JSON.stringify({ type: "event", id: "e" + (++events), handler: poster.say,
      value: `m.${id}.${Date.now()}.` }));
    if (KEEP > 0 && poster.deletes.size > KEEP) {
      // Its oldest message goes, by the button its page shows it: the room stays at a steady size.
      const oldest = [...poster.deletes.keys()].map(Number).sort((a, b) => a - b)[0];
      poster.ws.send(JSON.stringify({ type: "event", id: "e" + (++events), handler: poster.deletes.get(String(oldest)),
        value: "" }));
      poster.deletes.delete(String(oldest));
    }
  }
  const posting = (Date.now() - started) / 1000;
  await sleep(3000);

  const results = posters.map(result);
  for (const w of workers) {
    const done = new Promise(resolve => w.once("message", m => resolve(m.results)));
    w.postMessage("done");
    results.push(...await done);
  }
  for (const p of posters) {
    clearInterval(p.acks);
    p.ws.close();
  }

  const stats = values => {
    if (values.length === 0) return { n: 0 };
    const s = Int32Array.from(values).sort();
    const at = q => s[Math.min(s.length - 1, Math.floor(q * s.length))];
    return { n: s.length, p50: at(0.5), p95: at(0.95), p99: at(0.99), max: s[s.length - 1] };
  };
  const fmt = st => st.n === 0 ? "none" : `${st.n} deliveries, p50 ${st.p50} ms, p95 ${st.p95} ms, p99 ${st.p99} ms,`
    + ` max ${st.max} ms`;
  const byKind = kind => results.filter(r => r.kind === kind);
  const latencies = kind => {
    const out = [];
    for (const r of byKind(kind)) for (const v of r.latencies) out.push(v);
    return out;
  };
  const expected = seq;
  let failed = 0;
  const check = (what, ok, detail) => {
    console.log(`${ok ? "PASS" : "FAIL"}  ${what}${detail ? "  — " + detail : ""}`);
    if (!ok) failed++;
  };
  console.log(`posted ${seq} messages in ${posting.toFixed(1)} s: ${(seq / posting).toFixed(1)} messages/s`);
  for (const kind of ["reader", "admin", "poster", "slow"]) {
    const group = byKind(kind);
    const got = group.reduce((sum, r) => sum + r.seen, 0);
    const mb = group.reduce((sum, r) => sum + r.bytes, 0) / 1e6;
    const dropped = group.filter(r => r.closed).length;
    console.log(`${kind.padEnd(6)} ${group.length} pages: ${fmt(stats(latencies(kind)))}; ${got}/${expected * group.length}`
      + ` seen, ${mb.toFixed(1)} MB, ${dropped} sockets closed`);
  }
  for (const kind of ["reader", "admin", "poster"]) {
    const group = byKind(kind);
    if (group.length === 0) continue;
    const missing = group.filter(r => r.seen < expected).length;
    check(`every ${kind} saw every message`, missing === 0, missing ? `${missing} pages missed some` : "");
    const st = stats(latencies(kind));
    check(`${kind}s' p99 latency under ${P99} ms${SLOW ? ", with slow readers in the room" : ""}`, st.p99 < P99, fmt(st));
    check(`no ${kind}'s socket closed`, group.every(r => !r.closed));
  }
  const readersWithButtons = byKind("reader").filter(r => r.deletes > 0).length;
  check("no reader's page got a delete button", readersWithButtons === 0,
    readersWithButtons ? `${readersWithButtons} did` : "");
  if (ADMINS > 0) {
    const short = byKind("admin").filter(r => r.deletes < expected).length;
    check("every admin's page got a delete button for every message", short === 0, short ? `${short} did not` : "");
  }
  console.log(`\n${failed === 0 ? "all checks passed" : failed + " checks failed"}`);
  process.exit(failed === 0 ? 0 : 1);
}
