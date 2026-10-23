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

  static final class Shower extends ComponentTag {
    private final java.util.function.Supplier<String> text;

    Shower(java.util.function.Supplier<String> text) {
      this.text = text;
    }

    @Override protected Tag<?> render() {
      return T.span("shows " + text.get());
    }
  }

  static final class Showing extends LiveComponent {
    private final java.util.List<String> items = new java.util.ArrayList<>(java.util.List.of("a"));
    private final java.util.function.Supplier<String> text = () -> String.join(",", items);
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Shower(text),
        T.button("more").onClick(e -> {
          items.add("b");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aChildGivenTheSameLambdaAgainRenders() {
    try (Harness h = new Harness(Showing::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "more")));
      assertTrue(sent.contains("shows a,b"), sent);
    }
  }

  static final class Tally extends ComponentTag {
    private final State<Integer> count = state(0);

    @Override protected Tag<?> render() {
      return T.button("tally " + count.get()).onClick(e -> count.set(count.get() + 1));
    }
  }

  static final class Panel extends ComponentTag {
    @Override protected Tag<?> render() {
      return T.div(new Tally());
    }
  }

  static final class Panels extends LiveComponent {
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Panel(),
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void aKeptChildShowsWhatItsOwnChildRenderedSince() {
    try (Harness h = new Harness(Panels::new)) {
      h.load();
      h.connect();
      h.click(Harness.clickOn(h.html, "tally 0"));
      java.util.List<java.util.Map<String, String>> patches = h.click(Harness.clickOn(h.html, "bump"));
      assertTrue(sent(patches).contains("data-j2-unchanged"), sent(patches));
      assertTrue(patches.get(0).get("html").contains("tally 1"), patches.get(0).get("html"));
    }
  }

  static final class Label extends ComponentTag {
    private String text = "a";

    Label withText(String text) {
      this.text = text;
      return this;
    }

    @Override protected Tag<?> render() {
      return T.span("label " + text);
    }
  }

  static final class Frame extends ComponentTag {
    private final Label label;

    Frame(Label label) {
      this.label = label;
    }

    @Override protected Tag<?> render() {
      return T.div(label);
    }
  }

  static final class Framing extends LiveComponent {
    private final Label label = new Label();
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Frame(label),
        T.button("relabel").onClick(e -> {
          label.withText("b");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aChildHandedAComponentChangedInPlaceRendersIt() {
    try (Harness h = new Harness(Framing::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "relabel")));
      assertTrue(sent.contains("label b"), sent);
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

  static final class InnerParam extends ComponentTag {
    private final QueryState<String> value;

    InnerParam(String name) {
      value = queryState(name, "-");
    }

    @Override protected Tag<?> render() {
      return T.div(new ComponentTag() {
        @Override protected Tag<?> render() {
          return T.span("inner param " + value.get());
        }
      });
    }
  }

  static final class InnerParams extends LiveComponent {
    private final State<String> name = state("a");

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        new InnerParam(name.get()),
        T.button("switch").onClick(e -> name.set("b"))));
    }
  }

  @Test
  void anInnerChildRendersWhenTheEnclosingQueryStateNamesAnotherParameter() {
    try (Harness h = new Harness(InnerParams::new)) {
      h.load("/?a=1&b=2", Exchange.empty());
      assertTrue(h.html.contains("inner param 1"), h.html);
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "switch")));
      assertTrue(sent.contains("inner param 2"), sent);
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

  static final class Greeting extends LiveComponent {
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      String greeting = "hello";
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new ComponentTag() {
          @Override protected Tag<?> render() {
            return T.span(greeting);
          }
        },
        T.button("bump").onClick(e -> bump.set(bump.get() + 1))));
    }
  }

  @Test
  void anAnonymousChildWhoseCapturesDidNotChangeIsKept() {
    try (Harness h = new Harness(Greeting::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("data-j2-unchanged"), sent);
    }
  }

  static final class Captioned extends LiveComponent {
    private String caption = "A";
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new ComponentTag() {
          @Override protected Tag<?> render() {
            return T.span("caption " + caption);
          }
        },
        T.button("recaption").onClick(e -> {
          caption = "B";
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void anAnonymousChildRendersWhenAFieldOfTheComponentAroundItChanged() {
    try (Harness h = new Harness(Captioned::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "recaption")));
      assertTrue(sent.contains("caption B"), sent);
    }
  }

  static final class Tagged extends ComponentTag {
    private final Prop<java.util.List<String>> tags = prop(java.util.List.of());
    private final State<Integer> bump = state(0);

    Tagged withTags(java.util.List<String> value) {
      tags.set(value);
      return this;
    }

    @Override protected Tag<?> render() {
      return T.div(
        T.span("bump " + bump.get()),
        new ComponentTag() {
          @Override protected Tag<?> render() {
            return T.span("tags " + tags.get());
          }
        },
        T.button("tag").onClick(e -> {
          tags.get().add("b");
          bump.set(bump.get() + 1);
        }));
    }
  }

  static final class Tagging extends LiveComponent {
    private final java.util.List<String> tags = new java.util.ArrayList<>(java.util.List.of("a"));

    @Override public Tag<?> render() {
      return T.page("t", new Tagged().withTags(tags));
    }
  }

  @Test
  void anInnerChildRendersWhenTheEnclosingPropChangedInPlace() {
    try (Harness h = new Harness(Tagging::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "tag")));
      assertTrue(sent.contains("tags [a, b]"), sent);
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

  static final class Filter extends ComponentTag {
    private final QueryState<java.util.List<String>> tags;

    Filter(java.util.List<String> initial) {
      tags = queryState("tags", initial, s -> java.util.Arrays.asList(s.split(",")), v -> String.join(",", v));
    }

    @Override protected Tag<?> render() {
      return T.span("tags " + tags.get());
    }
  }

  static final class Filters extends LiveComponent {
    private final java.util.List<String> defaults = new java.util.ArrayList<>(java.util.List.of("a"));
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Filter(defaults),
        T.button("more").onClick(e -> {
          defaults.add("b");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aQueryStateWithAMutableInitialValueRendersTheChild() {
    try (Harness h = new Harness(Filters::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "more")));
      assertTrue(sent.contains("tags [a, b]"), sent);
    }
  }

  static final class HeldFilters extends LiveComponent {
    private final java.util.List<String> defaults = new java.util.ArrayList<>(java.util.List.of("a"));
    private final Filter filter = new Filter(defaults);
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        filter,
        T.button("more").onClick(e -> {
          defaults.add("b");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aHeldChildsQueryStateWithAMutableInitialValueRendersIt() {
    try (Harness h = new Harness(HeldFilters::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "more")));
      assertTrue(sent.contains("tags [a, b]"), sent);
    }
  }

  static final class Mapped extends ComponentTag {
    private final QueryState<String> word;

    Mapped(java.util.function.Function<String, String> parse) {
      word = queryState("w", "none", parse, v -> v);
    }

    @Override protected Tag<?> render() {
      return T.span("word " + word.get());
    }
  }

  static final class Mapping extends LiveComponent {
    private final java.util.Map<String, String> words = new java.util.HashMap<>(java.util.Map.of("x", "one"));
    private final java.util.function.Function<String, String> parse = words::get;
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Mapped(parse),
        T.button("remap").onClick(e -> {
          words.put("x", "two");
          bump.set(bump.get() + 1);
        })));
    }
  }

  @Test
  void aChildReusingAParserThatCapturedAChangedValueRenders() {
    try (Harness h = new Harness(Mapping::new)) {
      h.load("/?w=x", Exchange.empty());
      assertTrue(h.html.contains("word one"), h.html);
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "remap")));
      assertTrue(sent.contains("word two"), sent);
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
  void aResyncRendersThePatchInFullOnceEvenWhenRateLimited() {
    try (Harness h = new Harness(Parent::new, b -> b.withRateLimit(auth -> RateLimit.perSecond(1)))) {
      h.load();
      h.connect();
      String root = Harness.find(h.html, "<html data-j2-scope=\"([^\"]+)\"");
      int from = h.conn.size();
      h.send("type", "resync", "anchor", root);
      String sent = sent(h.click(Harness.clickOn(h.html, "bump")));
      assertTrue(sent.contains("data-j2-unchanged"), sent);
      assertEquals(0, h.engine.stats.resyncs.get(), "a resync before any patch kept a child is ignored");

      from = h.conn.size();
      h.send("type", "resync", "anchor", root);
      sent = h.awaitPatch(from, html -> true).get("sent");
      assertTrue(sent.contains("A n0 0:0"), sent);
      assertFalse(sent.contains("data-j2-unchanged"), sent);

      h.send("type", "resync", "anchor", root);
      Harness.eventually(() -> h.engine.stats.resyncs.get() == 1, "one resync");
      assertEquals(1, h.engine.stats.resyncs.get());
    }
  }

  @Test
  void aResyncWithoutAKeptPatchNeverReachesTheLane() throws Exception {
    try (Harness h = new Harness(Parent::new)) {
      h.load();
      h.connect();
      Tab tab = h.engine.tab(h.sid);
      java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      tab.post(() -> {
        busy.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      try {
        busy.await();
        for (int i = 0; i < 100; i++) {
          h.send("type", "resync", "anchor", "s" + i);
        }
        assertEquals(0, tab.lane.backlog(), "no resync was queued");
      } finally {
        release.countDown();
      }
    }
  }

  @Test
  void resyncsTakeOneQueueSlotAndAreNeverDropped() throws Exception {
    try (Harness h = new Harness(Parent::new, b -> b.withMaxQueuedEvents(2))) {
      h.load();
      h.connect();
      Tab tab = h.engine.tab(h.sid);
      java.util.concurrent.CountDownLatch busy = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      tab.post(() -> {
        busy.countDown();
        try {
          release.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      try {
        busy.await();
        for (int i = 0; i < 5; i++) {
          tab.keptPatches.add("k" + i);
        }
        tab.post(() -> { });
        tab.post(() -> { });
        for (int i = 0; i < 5; i++) {
          h.send("type", "resync", "anchor", "k" + i);
        }
        assertEquals(3, tab.lane.backlog(), "one task for all five, though the queue was full");
        assertEquals(5, tab.pendingResyncs.size());
        assertTrue(tab.keptPatches.isEmpty());
      } finally {
        release.countDown();
      }
      Harness.eventually(() -> tab.pendingResyncs.isEmpty(), "the resyncs ran");
    }
  }

  static final class Dated extends ComponentTag {
    private java.time.format.DateTimeFormatterBuilder builder;

    Dated withBuilder(java.time.format.DateTimeFormatterBuilder b) {
      builder = b;
      return this;
    }

    @Override protected Tag<?> render() {
      return T.span("format " + builder.toFormatter());
    }
  }

  static final class Dates extends LiveComponent {
    private final java.time.format.DateTimeFormatterBuilder builder = new java.time.format.DateTimeFormatterBuilder();
    private final State<Integer> bump = state(0);

    @Override public Tag<?> render() {
      return T.page("t", T.div(
        T.span("bump " + bump.get()),
        new Dated().withBuilder(builder),
        T.button("year").onClick(e -> {
          builder.appendPattern("yyyy");
          bump.set(bump.get() + 1);
        })));
    }
  }

  enum Plain { A }

  enum Labelled {
    A("a");

    final String label;

    Labelled(String label) {
      this.label = label;
    }
  }

  enum Relabelled {
    A;

    String label = "a";
  }

  enum Listed {
    A;

    final java.util.List<String> labels = new java.util.ArrayList<>();
  }

  enum Looped {
    A;

    final Looped peer = this;
  }

  @Test
  void enumsAreImmutableOnlyWhenTheirOwnFieldsAre() {
    assertTrue(Unchanged.immutable(Plain.A));
    assertTrue(Unchanged.immutable(Labelled.A));
    assertFalse(Unchanged.immutable(Relabelled.A));
    assertFalse(Unchanged.immutable(Listed.A));
    assertTrue(Unchanged.immutable(java.util.List.of(java.util.List.of(java.util.List.of(java.util.List.of(Plain.A))))));
    assertTrue(Unchanged.immutable(java.util.concurrent.TimeUnit.SECONDS));
    assertTrue(Unchanged.immutable(java.math.RoundingMode.HALF_UP));
    assertFalse(Unchanged.immutable(Looped.A), "an enum leading back to itself ends at the depth limit");
  }

  @Test
  void javaTimeValuesAreImmutableButClocksAndBuildersAreNot() {
    assertTrue(Unchanged.immutable(java.time.LocalDate.of(2026, 10, 21)));
    assertTrue(Unchanged.immutable(java.time.Duration.ofSeconds(3)));
    assertTrue(Unchanged.immutable(java.time.ZoneId.of("Europe/Berlin")));
    assertTrue(Unchanged.immutable(java.time.ZoneOffset.UTC));
    assertTrue(Unchanged.immutable(java.time.DayOfWeek.MONDAY));
    assertFalse(Unchanged.immutable(java.time.Clock.systemUTC()));
    assertFalse(Unchanged.immutable(new java.time.DateTimeException("bad date")));
  }

  @Test
  void iteratorsOfImmutableCollectionsAreNotCollections() {
    assertFalse(Unchanged.immutable(java.util.List.of("a").iterator()));
    assertFalse(Unchanged.immutable(java.util.Collections.emptyIterator()));
    assertTrue(Unchanged.immutable(java.util.Collections.emptyList()));
    assertFalse(Unchanged.immutable(new java.time.format.DateTimeFormatterBuilder()));
  }

  @Test
  void aJavaTimeBuilderChangedInPlaceRendersTheChild() {
    try (Harness h = new Harness(Dates::new)) {
      h.load();
      h.connect();
      String sent = sent(h.click(Harness.clickOn(h.html, "year")));
      assertTrue(sent.contains("Value(YearOfEra"), sent);
    }
  }
}
