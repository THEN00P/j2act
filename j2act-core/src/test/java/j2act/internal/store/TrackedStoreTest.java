package j2act.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.signals.Runtime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * ADR 0035: the read path the bytecode enhancer compiles to. The tracked classes here are written by hand as the
 * enhancer will write them: each read of a field calls StoreReads.field first, and the record a store built the
 * object from is kept in a j2act$ field. The same app over plain classes is the dev mode, which reads whole
 * objects: both must show the same values after every step, the tracked one running no more often.
 */
class TrackedStoreTest {

  /** Not tracked, as a class from a library is: reading it reads all of it. */
  public static class Tag {
    private String name;

    Tag() {
    }

    Tag(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  public static class Item {
    private String label;
    private int count;
    private Tag tag;
    private List<String> notes = new ArrayList<>();

    Item() {
    }

    Item(String label, int count, Tag tag) {
      this.label = label;
      this.count = count;
      this.tag = tag;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public int getCount() {
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public Tag getTag() {
      return tag;
    }

    public void setTag(Tag tag) {
      this.tag = tag;
    }

    public List<String> getNotes() {
      return notes;
    }
  }

  /** Item as the enhancer would leave it. */
  public static class TItem implements Tracked {
    private String label;
    private int count;
    private Tag tag;
    private List<String> notes = new ArrayList<>();
    private Object j2act$record;

    TItem() {
    }

    TItem(String label, int count, Tag tag) {
      this.label = label;
      this.count = count;
      this.tag = tag;
    }

    @Override public Object j2act$record() {
      return j2act$record;
    }

    @Override public void j2act$record(Object record) {
      j2act$record = record;
    }

    public String getLabel() {
      StoreReads.field(this, "label");
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public int getCount() {
      StoreReads.field(this, "count");
      return count;
    }

    public void setCount(int count) {
      this.count = count;
    }

    public Tag getTag() {
      StoreReads.field(this, "tag");
      return tag;
    }

    public void setTag(Tag tag) {
      this.tag = tag;
    }

    public List<String> getNotes() {
      StoreReads.field(this, "notes");
      return notes;
    }
  }

  /** A tracked class holding a tracked one. */
  public static class TBox implements Tracked {
    private String title;
    private TItem item;
    private Object j2act$record;

    TBox() {
    }

    TBox(String title, TItem item) {
      this.title = title;
      this.item = item;
    }

    @Override public Object j2act$record() {
      return j2act$record;
    }

    @Override public void j2act$record(Object record) {
      j2act$record = record;
    }

    public String getTitle() {
      StoreReads.field(this, "title");
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public TItem getItem() {
      StoreReads.field(this, "item");
      return item;
    }
  }

  private final java.util.ArrayDeque<Runnable> microtasks = new java.util.ArrayDeque<>();
  private final Runtime rt = new Runtime(Runnable::run, microtasks::add);
  private final JavaShape shape = new JavaShape(rt);

  private final class Reader<T> {
    int runs;
    T last;

    Reader(Supplier<T> read) {
      rt.createRoot(owner -> {
        rt.<T>createEffect(prev -> read.get(), (value, prev) -> {
          runs++;
          last = value;
          return null;
        }, (error, cleanup) -> {
          throw new AssertionError("a reader failed", error);
        });
        return null;
      });
      rt.flush();
    }
  }

  @Test
  void aTrackedObjectIsHandedOutStampedAndReadByField() {
    Object store = shape.create(new TBox("box", new TItem("one", 1, new Tag("red"))));
    Reader<String> title = new Reader<>(() -> ((TBox) shape.value(store)).getTitle());
    Reader<Integer> count = new Reader<>(() -> ((TBox) shape.value(store)).getItem().getCount());
    Reader<String> tag = new Reader<>(() -> ((TBox) shape.value(store)).getItem().getTag().getName());

    shape.<TBox>update(store, d -> d.getItem().setCount(2));
    rt.flush();
    assertEquals(2, count.last);
    assertEquals(2, count.runs);
    assertEquals(1, title.runs, "the title's reader read no count");
    assertEquals(1, tag.runs, "nor did the tag's");

    shape.<TBox>update(store, d -> d.getItem().getTag().setName("blue"));
    rt.flush();
    assertEquals("blue", tag.last);
    assertEquals(2, tag.runs);
    assertEquals(2, count.runs, "a tag is not a tracked class, so its reader reads all of it, and only it");

    shape.<TBox>update(store, d -> d.setTitle("crate"));
    rt.flush();
    assertEquals("crate", title.last);
    assertEquals(2, count.runs);
    assertEquals(2, tag.runs);
  }

  @Test
  void aTrackedObjectIsTheStoresOwnAndKeepsItsIdentity() {
    TItem given = new TItem("one", 1, new Tag("red"));
    Object store = shape.create(given);
    TItem first = (TItem) shape.value(store);
    assertNotSame(given, first, "an object stamped with its record is never the one the store was given");
    assertNull(given.j2act$record());
    assertSame(first, shape.value(store));
    shape.<TItem>update(store, d -> d.setLabel("uno"));
    rt.flush();
    TItem second = (TItem) shape.value(store);
    assertNotSame(first, second);
    assertSame(first.getTag(), second.getTag(), "what did not change below it is the object it was");
    assertEquals("uno", second.getLabel());
    assertEquals("one", first.getLabel(), "an object handed out is that version, never changed after");
  }

  @Test
  void aDraftOfATrackedClassReadsAndWrites() {
    Object store = shape.create(new TItem("one", 1, new Tag("red")));
    Reader<String> label = new Reader<>(() -> ((TItem) shape.value(store)).getLabel());
    shape.<TItem>update(store, d -> {
      d.setLabel(d.getLabel() + "!");
      d.getNotes().add("x");
    });
    rt.flush();
    assertEquals("one!", label.last);
    assertEquals(List.of("x"), ((TItem) shape.value(store)).getNotes());
  }

  @Test
  @SuppressWarnings("unchecked")
  void oneObjectInTwoStoresSaysWhichItCameFrom() {
    TItem shared = new TItem("one", 1, new Tag("red"));
    Object a = shape.create(new ArrayList<>(List.of(shared)));
    List<TItem>[] b = new List[1];
    rt.createRoot(owner -> {
      b[0] = (List<TItem>) shape.createProjection(draft -> List.of(shared), null);
      return null;
    });
    rt.flush();
    List<TItem> la = (List<TItem>) a;
    Reader<String> ra = new Reader<>(() -> la.get(0).getLabel());
    Reader<String> rb = new Reader<>(() -> b[0].get(0).getLabel());
    assertNotSame(la.get(0), b[0].get(0), "each store's object says which record it came from");
    assertSame(b[0].get(0), b[0].get(0));
    shape.<List<TItem>>update(a, d -> d.get(0).setLabel("a"));
    rt.flush();
    assertEquals("a", ra.last);
    assertEquals(2, ra.runs);
    assertEquals("one", rb.last, "the projection's record is its own");
    assertEquals(1, rb.runs);
  }

  // ---- the same app twice: plain classes (dev) and tracked ones (built)

  /** One app: a list of items, its readers, and writes, over one kind of item. */
  /** What dev mode's check of handed-out objects reported: a draft is always what changes, so nothing. */
  private final List<String> reports = new ArrayList<>();

  private final class App<I> {
    final Object store;
    final List<Reader<String>> readers = new ArrayList<>();

    @SuppressWarnings("unchecked")
    App(Function<Integer, I> make, int rows, List<Function<List<I>, String>> reads) {
      List<I> items = new ArrayList<>();
      for (int i = 0; i < rows; i++) {
        items.add(make.apply(i));
      }
      store = shape.create(items);
      for (Function<List<I>, String> read : reads) {
        readers.add(new Reader<>(() -> read.apply((List<I>) store)));
      }
    }
  }

  private static String show(Object item, Function<Object, String> part) {
    return item == null ? "-" : part.apply(item);
  }

  @Test
  void dev_andBuiltShowTheSameAndBuiltRunsNoMore() {
    int seeds = Integer.getInteger("tracked.seeds", 200);
    int from = Integer.getInteger("tracked.seed", 0);
    for (int seed = from; seed < from + seeds; seed++) {
      runSeed(seed);
    }
    assertTrue(trackedRuns < plainRuns * 3 / 4, "tracked readers ran " + trackedRuns + " times, plain ones " + plainRuns);
  }

  private long plainRuns;
  private long trackedRuns;

  private void runSeed(int seed) {
    shape.checkMutations(reports::add);
    Random r = new Random(seed);
    int rows = 1 + r.nextInt(4);
    // A reader reads one part of one row, or the size, or every row's label.
    List<Function<List<Item>, String>> plainReads = new ArrayList<>();
    List<Function<List<TItem>, String>> trackedReads = new ArrayList<>();
    for (int n = 0; n < 6; n++) {
      int row = r.nextInt(rows + 1);
      int part = r.nextInt(6);
      plainReads.add(l -> part == 5 ? String.valueOf(l.size())
        : row >= l.size() ? "-" : plainPart(l.get(row), part));
      trackedReads.add(l -> part == 5 ? String.valueOf(l.size())
        : row >= l.size() ? "-" : trackedPart(l.get(row), part));
    }
    plainReads.add(l -> {
      StringBuilder b = new StringBuilder();
      for (Item i : l) {
        b.append(i.getLabel()).append(',');
      }
      return b.toString();
    });
    trackedReads.add(l -> {
      StringBuilder b = new StringBuilder();
      for (TItem i : l) {
        b.append(i.getLabel()).append(',');
      }
      return b.toString();
    });
    App<Item> plain = new App<>(i -> new Item("l" + i, i, new Tag("t" + i)), rows, plainReads);
    App<TItem> tracked = new App<>(i -> new TItem("l" + i, i, new Tag("t" + i)), rows, trackedReads);
    String steps = "";
    for (int step = 0; step < 25; step++) {
      int op = r.nextInt(11);
      int at = r.nextInt(4);
      String value = r.nextInt(5) == 0 ? null : "v" + r.nextInt(3);
      // One app's update inside the other's, before or after its own change, or the two one after the other.
      int nest = r.nextInt(5);
      steps += op + "@" + at + "=" + value + (nest < 4 ? "/n" + nest : "") + " ";
      Runnable inPlain = () -> shape.<List<Item>>update(plain.store, d -> plainOp(d, op, at, value));
      Runnable inTracked = () -> shape.<List<TItem>>update(tracked.store, d -> trackedOp(d, op, at, value));
      switch (nest) {
        case 0:
          shape.<List<Item>>update(plain.store, d -> {
            plainOp(d, op, at, value);
            inTracked.run();
          });
          break;
        case 1:
          shape.<List<Item>>update(plain.store, d -> {
            inTracked.run();
            plainOp(d, op, at, value);
          });
          break;
        case 2:
          shape.<List<TItem>>update(tracked.store, d -> {
            trackedOp(d, op, at, value);
            inPlain.run();
          });
          break;
        case 3:
          shape.<List<TItem>>update(tracked.store, d -> {
            inPlain.run();
            trackedOp(d, op, at, value);
          });
          break;
        default:
          inPlain.run();
          inTracked.run();
          break;
      }
      rt.flush();
      if (Boolean.getBoolean("tracked.gc")) {
        System.gc();
      }
      for (int n = 0; n < plain.readers.size(); n++) {
        Reader<String> p = plain.readers.get(n);
        Reader<String> t = tracked.readers.get(n);
        String where = "seed " + seed + ", reader " + n + ", steps " + steps;
        assertEquals(p.last, t.last, where);
        assertTrue(t.runs <= p.runs, "tracked ran " + t.runs + " times, plain " + p.runs + ": " + where);
      }
      assertEquals(List.of(), reports, "seed " + seed + ", steps " + steps);
    }
    for (int n = 0; n < plain.readers.size(); n++) {
      plainRuns += plain.readers.get(n).runs;
      trackedRuns += tracked.readers.get(n).runs;
    }
  }

  private static String plainPart(Item i, int part) {
    switch (part) {
      case 0: return i.getLabel();
      case 1: return String.valueOf(i.getCount());
      case 2: return i.getTag() == null ? "null" : i.getTag().getName();
      case 3: return String.valueOf(i.getNotes());
      default: return i.getLabel() + i.getCount();
    }
  }

  private static String trackedPart(TItem i, int part) {
    switch (part) {
      case 0: return i.getLabel();
      case 1: return String.valueOf(i.getCount());
      case 2: return i.getTag() == null ? "null" : i.getTag().getName();
      case 3: return String.valueOf(i.getNotes());
      default: return i.getLabel() + i.getCount();
    }
  }

  private static void plainOp(List<Item> d, int op, int at, String value) {
    if (op == 6) {
      d.add(Math.min(at, d.size()), new Item(value, at, new Tag(value)));
      return;
    }
    if (d.isEmpty()) {
      return;
    }
    Item i = d.get(at % d.size());
    switch (op) {
      case 0: i.setLabel(value); break;
      case 1: i.setCount(i.getCount() + 1); break;
      case 2:
        if (i.getTag() != null) {
          i.getTag().setName(value);
        }
        break;
      case 8: i.setTag(null); break;
      case 9: i.setLabel(null); break;
      case 10: i.getNotes().add(null); break;
      case 3: i.getNotes().add(value); break;
      case 4: i.setTag(new Tag(value)); break;
      case 5: d.remove(at % d.size()); break;
      default: d.set(at % d.size(), new Item(value, 0, new Tag(value))); break;
    }
  }

  private static void trackedOp(List<TItem> d, int op, int at, String value) {
    if (op == 6) {
      d.add(Math.min(at, d.size()), new TItem(value, at, new Tag(value)));
      return;
    }
    if (d.isEmpty()) {
      return;
    }
    TItem i = d.get(at % d.size());
    switch (op) {
      case 0: i.setLabel(value); break;
      case 1: i.setCount(i.getCount() + 1); break;
      case 2:
        if (i.getTag() != null) {
          i.getTag().setName(value);
        }
        break;
      case 8: i.setTag(null); break;
      case 9: i.setLabel(null); break;
      case 10: i.getNotes().add(null); break;
      case 3: i.getNotes().add(value); break;
      case 4: i.setTag(new Tag(value)); break;
      case 5: d.remove(at % d.size()); break;
      default: d.set(at % d.size(), new TItem(value, 0, new Tag(value))); break;
    }
  }
}
