package j2act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** ADR 0031: a parent's re-render keeps an unchanged child's element instead of sending it again. */
class KeepTest {

  static final AtomicInteger renders = new AtomicInteger();

  static final class Child extends ComponentTag {
    private final Prop<String> label = prop("?");
    private String note = "";
    private State<Integer> shared;
    private Runnable onPick;

    Child withLabel(String l) {
      label.set(l);
      return this;
    }

    Child withNote(String n) {
      note = n;
      return this;
    }

    Child withShared(State<Integer> s) {
      shared = s;
      return this;
    }

    Child onPick(Runnable r) {
      onPick = r;
      return this;
    }

    @Override protected Tag<?> render() {
      renders.incrementAndGet();
      State<Integer> count = state(0);
      return T.button(label.get() + " " + note + " " + (shared == null ? "" : shared.get()) + ":" + count.get())
        .onClick(e -> count.set(count.get() + 1));
    }
  }

  static final class Parent extends LiveComponent {
    private final State<Integer> bump = state(0);
    private final State<String> label = state("A");
    private final State<Integer> note = state(0);
    private final State<Integer> shared = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Child().withLabel(label.get()).withNote("n" + note.get()).withShared(shared),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1)),
        T.button("relabel").onClick(e -> label.set("B")),
        T.button("note").onClick(e -> note.set(note.get() + 1)),
        T.button("shared").onClick(e -> {
          bump.set(bump.get() + 1);
          shared.set(shared.get() + 1);
        })));
    }
  }

  private static String sent(java.util.List<java.util.Map<String, String>> patches) {
    assertEquals(1, patches.size(), String.valueOf(patches));
    return patches.get(0).get("sent");
  }

  @Test
  void anUnchangedChildIsKeptAndKeepsWorking() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      int before = renders.get();
      java.util.List<java.util.Map<String, String>> patches = h.click(Harness.clickOn(h.html, "bump"));
      String sent = sent(patches);
      assertTrue(sent.contains("bump 1"), sent);
      assertTrue(sent.contains("<button data-j2-unchanged=\"s2\"></button>"), sent);
      assertFalse(sent.contains("A n0"), sent);
      assertEquals(before, renders.get());

      String page = patches.get(0).get("html");
      assertTrue(page.contains("A n0 0:0"), page);
      String child = h.click(Harness.clickOn(page, "A n0 0:0")).get(0).get("html");
      assertTrue(child.contains("A n0 0:1"), child);

      sent = sent(h.click(Harness.clickOn(page, "bump")));
      assertTrue(sent.contains("data-j2-unchanged=\"s2\""), sent);
    }
  }

  @Test
  void aChangedPropRendersTheChild() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "relabel")));
      assertTrue(sent.contains("B n0 0:0"), sent);
      assertFalse(sent.contains("data-j2-unchanged"), sent);
    }
  }

  @Test
  void aChangedPlainFieldRendersTheChild() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "note")));
      assertTrue(sent.contains("A n1 0:0"), sent);
    }
  }

  @Test
  void aChildWhoseHandedDownStateChangedRenders() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "shared")));
      assertTrue(sent.contains("bump 1"), sent);
      assertTrue(sent.contains("A n0 1:0"), sent);
    }
  }

  static final class Picking extends LiveComponent {
    private final State<Integer> bump = state(0);
    private final State<String> picked = state("");

    @Override public Tag<?> render() {
      int n = bump.get();
      return T.page("t", T.div(
        T.span("bump " + n + picked.get()),
        new Child().withLabel("P").onPick(() -> picked.set("picked " + n)),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void aChildHoldingALambdaAlwaysRenders() {
    try (Harness h = new Harness(Picking::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("P  :0"), sent);
      assertFalse(sent.contains("data-j2-unchanged"), sent);
    }
  }

  static final class Dial extends ComponentTag {
    private final ClientTest.Meter meter = client(ClientTest.Meter.class);
    private final Upload export = upload();

    @Override protected Tag<?> render() {
      return T.div().withClient(meter.mount("dial", v -> { }, T.span("legend"), export));
    }
  }

  static final class Dials extends LiveComponent {
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Dial(),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void aChildWithAClientModuleIsKept() {
    try (Harness h = new Harness(Dials::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("<div data-j2-unchanged="), sent);
    }
  }

  static final class Editor extends ComponentTag {
    private final Mutation<Integer, String> save;

    Editor(int id) {
      save = mutation((Integer v) -> "saved " + id);
    }

    @Override protected Tag<?> render() {
      return T.button(save.data() == null ? "save" : save.data()).onClick(e -> save.mutate(0));
    }
  }

  static final class Editors extends LiveComponent {
    private final State<Integer> id = state(1);
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Editor(id.get()),
        T.button("next").onClick(e -> id.set(2)),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void aChildWhosePrimitiveCapturedAnotherValueIsBoundAgain() {
    try (Harness h = new Harness(Editors::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("<button data-j2-unchanged="), sent);
      String page = h.click(Harness.clickOn(h.html, "next")).get(0).get("html");
      int from = h.conn.size();
      h.click(Harness.clickOn(page, "save"));
      String saved = h.awaitPatch(from, html -> html.contains("saved")).get("html");
      assertTrue(saved.contains("saved 2"), saved);
    }
  }

  static final class Param extends ComponentTag {
    private final QueryState<String> value;

    Param(String name) {
      value = queryState(name, "-");
    }

    @Override protected Tag<?> render() {
      return T.span("param " + value.get());
    }
  }

  static final class Params extends LiveComponent {
    private final State<String> name = state("a");

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        new Param(name.get()),
        T.button("switch").onClick(e -> name.set("b"))));
    }
  }

  @Test
  void aChildWhoseQueryStateNamesAnotherParameterRenders() {
    try (Harness h = new Harness(Params::new)) {
      h.load("/?a=1&b=2", Exchange.empty());
      assertTrue(h.html.contains("param 1"), h.html);
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "switch")));
      assertTrue(sent.contains("param 2"), sent);
    }
  }

  static final class Labels extends LiveComponent {
    private final State<Integer> n = state(1);

    @Override public Tag<?> render() {
      String label = "label " + n.get();
      return T.page("t", T.div(
        new ComponentTag() {
          @Override protected Tag<?> render() {
            return T.span(label);
          }
        },
        T.button("next").onClick(e -> n.set(n.get() + 1))));
    }
  }

  @Test
  void anAnonymousChildWhoseCapturesChangedRenders() {
    try (Harness h = new Harness(Labels::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "next")));
      assertTrue(sent.contains("label 2"), sent);
    }
  }

  static final class Picker extends ComponentTag {
    private final State<String> a = state("first");
    private final State<String> b = state("second");
    private final State<String> selected;

    Picker(boolean second) {
      selected = second ? b : a;
    }

    @Override protected Tag<?> render() {
      return T.span("picked " + selected.get());
    }
  }

  static final class Pickers extends LiveComponent {
    private final State<Boolean> second = state(false);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        new Picker(second.get()),
        T.button("pick").onClick(e -> second.set(true))));
    }
  }

  @Test
  void aChildFieldPointingAtAnotherOwnPrimitiveRenders() {
    try (Harness h = new Harness(Pickers::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "pick")));
      assertTrue(sent.contains("picked second"), sent);
    }
  }

  static final class Service {
  }

  static final Service SERVICE = new Service();

  static final class Listing extends ComponentTag {
    private final Prop<java.util.List<String>> fixed = prop();
    private java.util.List<String> items = java.util.Collections.emptyList();
    Service service;

    Listing withFixed(java.util.List<String> f) {
      fixed.set(f);
      return this;
    }

    Listing withItems(java.util.List<String> i) {
      items = i;
      return this;
    }

    @Override protected Tag<?> render() {
      return T.span(fixed.get() + " " + items + (service == null ? "" : " served"));
    }
  }

  static final class Listings extends LiveComponent {
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Listing().withFixed(java.util.List.of("a", "b")),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void anInjectedServiceAndAnImmutableListKeepTheChild() {
    try (Harness h = new Harness(Listings::new, b -> b.withMembersInjector(c -> {
      if (c instanceof Listing) {
        ((Listing) c).service = SERVICE;
      }
    }))) {
      h.load();
      assertTrue(h.html.contains("[a, b] [] served"), h.html);
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("<span data-j2-unchanged="), sent);
    }
  }

  static final class Sharing extends LiveComponent {
    private final java.util.List<String> shared = new java.util.ArrayList<>();
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Listing().withFixed(java.util.List.of("a", "b")).withItems(shared),
        T.button("add").onClick(e -> {
          shared.add("x");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aMutableObjectChangedInPlaceRendersTheChild() {
    try (Harness h = new Harness(Sharing::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "add")));
      assertTrue(sent.contains("[a, b] [x]"), sent);
    }
  }

  static final class Holder extends ComponentTag {
    private final Prop<java.util.List<String>> items = prop();

    Holder withItems(java.util.List<String> i) {
      items.set(i);
      return this;
    }

    @Override protected Tag<?> render() {
      return T.span("held " + items.get());
    }
  }

  static final class Holders extends LiveComponent {
    private final java.util.List<String> shared = new java.util.ArrayList<>();
    private final Holder holder = new Holder();
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        holder.withItems(shared),
        T.button("add").onClick(e -> {
          shared.add("x");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aHeldChildWhosePropChangedInPlaceRenders() {
    try (Harness h = new Harness(Holders::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "add")));
      assertTrue(sent.contains("held [x]"), sent);
    }
  }

  static java.util.concurrent.CountDownLatch gate;

  static final class Leaf extends ComponentTag {
    private final Query<String> data = query(() -> {
      gate.await();
      return "data";
    }).withDefer();

    @Override protected Tag<?> render() {
      return T.span(data.isPending() ? "leaf pending" : data.get());
    }
  }

  static final class InnerBoundary extends ComponentTag {
    @Override protected Tag<?> loading() {
      return T.span("inner loading");
    }

    @Override protected Tag<?> render() {
      return T.div(new Leaf());
    }
  }

  static final class OuterBoundary extends ComponentTag {
    private final State<Integer> bump = state(0);

    @Override protected Tag<?> loading() {
      return T.span("outer loading");
    }

    @Override protected Tag<?> render() {
      return T.div(
        T.span("bump " + bump.get()),
        new InnerBoundary(),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1)));
    }
  }

  static final class Boundaries extends LiveComponent {
    @Override public Tag<?> render() {
      return T.page("t", new OuterBoundary());
    }
  }

  @Test
  void aKeptBoundaryKeepsTheQueriesUnderIt() {
    gate = new java.util.concurrent.CountDownLatch(1);
    try (Harness h = new Harness(Boundaries::new)) {
      h.load();
      assertTrue(h.html.contains("inner loading"), h.html);
      h.connect();
      java.util.List<java.util.Map<String, String>> patches = h.click(Harness.clickOn(h.html, "bump"));
      String sent = sent(patches);
      assertTrue(sent.contains("bump 1"), sent);
      assertFalse(sent.contains("outer loading"), sent);
      assertTrue(patches.get(0).get("html").contains("inner loading"), patches.get(0).get("html"));
      int from = h.conn.size();
      gate.countDown();
      h.awaitPatch(from, html -> html.contains("data"));
    } finally {
      gate.countDown();
    }
  }

  @Test
  void aReconnectRendersInFullOnce() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "bump"));
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("A n0 0:0"), sent);
      sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("data-j2-unchanged"), sent);
    }
  }

  @Test
  void aResyncRendersThePatchInFull() {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      String root = Harness.find(h.html, "<html data-j2-scope=\"([^\"]+)\"");
      int from = h.conn.size();
      h.send("type", "resync", "anchor", root);
      String sent = h.awaitPatch(from, html -> true).get("sent");
      assertTrue(sent.contains("A n0 0:0"), sent);
      assertFalse(sent.contains("data-j2-unchanged"), sent);
      assertEquals(1, h.engine.stats.resyncs.get());
    }
  }
}
