// Checks shared stores end to end (ADR 0037) in headless Chrome: the demo's @SharedScoped ChatRoom at
// /chat/{room}, read by three users at once, each the top page or a same-origin iframe with its own name.
//   java -jar examples/counter-spring/target/counter-spring-*.jar --server.port=8089
//   node tools/chat-check.mjs http://localhost:8089/
// For a cluster, start a second demo with --server.port=8090 and --j2act.cluster.self on both, sharing one
// database, and pass its URL as the second argument: bob and the admin then use that server.
import { openBrowser, sleep } from "./cdp.mjs";

const URL = (process.argv[2] || "http://localhost:8089/").replace(/\/$/, "");
const OTHER = (process.argv[3] || URL).replace(/\/$/, "");
const ROOM = "check" + Date.now();
const b = await openBrowser();
const { js, until, open, check } = b;

const doc = (id) => id === "alice" ? "document" : `document.getElementById('${id}').contentDocument`;
const messages = (id) => `[...${doc(id)}.querySelectorAll('#messages > div')].map(d => d.textContent)`;
const say = (id, text) => js(`const i = ${doc(id)}.getElementById('say'); i.value = ${JSON.stringify(text)};
  i.dispatchEvent(new Event('change', { bubbles: true })); true`);
const buttons = (id) => `[...${doc(id)}.querySelectorAll('#messages button')].map(b => b.textContent)`;

b.run(async () => {
  await open(`${URL}/chat/${ROOM}?name=alice`);
  for (const [id, name] of [["bob", "bob"], ["admin", "admin"]]) {
    await js(`const f = document.createElement('iframe'); f.id = '${id}';
      f.src = ${JSON.stringify(`${OTHER}/chat/${ROOM}?name=`)} + '${name}';
      f.style.width = '600px'; f.style.height = '400px'; document.body.append(f); true`);
    check(`${name}'s page opens`, await until(`${doc(id)} && ${doc(id)}.getElementById('say') !== null`, 10000));
  }
  await sleep(600); // their sockets' hello

  await say("alice", "hello from alice");
  check("a message alice posts shows for bob", await until(`${messages("bob")}.some(m => m.includes('alice: hello from alice'))`),
    await js(`${messages("bob")}.join(' | ')`));
  check("and for the admin", await until(`${messages("admin")}.some(m => m.includes('alice: hello from alice'))`));
  check("alice may delete her own message", await until(`${buttons("alice")}.length === 1`), await js(`${buttons("alice")}.join(',')`));
  check("bob may not: no button on his page", (await js(`${buttons("bob")}.length`)) === 0, await js(`${buttons("bob")}.join(',')`));
  check("the admin may", (await js(`${buttons("admin")}.length`)) === 1);

  await say("bob", "hi from bob");
  check("bob's message shows for alice", await until(`${messages("alice")}.some(m => m.includes('bob: hi from bob'))`));
  check("with no button for alice on bob's message", await until(`${buttons("alice")}.length === 1`));

  await js(`[...${doc("admin")}.querySelectorAll('#messages button')].find(b => b.textContent.startsWith('delete 1')).click(); true`);
  check("the admin's delete reaches everyone", await until(`!${messages("alice")}.some(m => m.includes('hello from alice'))
    && !${messages("bob")}.some(m => m.includes('hello from alice'))`), await js(`${messages("bob")}.join(' | ')`));
});
