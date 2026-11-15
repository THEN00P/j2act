package j2act.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import j2act.internal.signals.Runtime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** ADR 0035: a store over Java objects, driven with a bare runtime. */
class JavaStoreTest {

  public static class Address {
    private String city;
    private String zip;

    Address() {
    }

    Address(String city, String zip) {
      this.city = city;
      this.zip = zip;
    }

    public String getCity() {
      return city;
    }

    public void setCity(String city) {
      this.city = city;
    }
  }

  public static class User {
    private String name;
    private Address address;
    private List<String> tags = new ArrayList<>();

    User() {
    }

    User(String name, Address address) {
      this.name = name;
      this.address = address;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public Address getAddress() {
      return address;
    }

    public List<String> getTags() {
      return tags;
    }
  }

  /** A scheduled flush waits for the test to flush, as a microtask waits for the code that scheduled it. */
  private final java.util.ArrayDeque<Runnable> microtasks = new java.util.ArrayDeque<>();
  private final Runtime rt = new Runtime(Runnable::run, microtasks::add);
  private final JavaShape shape = new JavaShape(rt);

  /** Counts the runs of an effect over read, and keeps what it last read. */
  private final class Reader<T> {
    int runs;
    T last;

    Reader(Supplier<T> read) {
      rt.createRoot(owner -> {
        rt.<T>createEffect(prev -> read.get(), (value, prev) -> {
          runs++;
          last = value;
          return null;
        });
        return null;
      });
      rt.flush();
    }
  }

  @Test
  void aDraftIsTheRealClassAndTheGivenObjectIsNeverChanged() {
    User ada = new User("Ada", new Address("London", "N1"));
    Object store = shape.create(ada);
    Reader<String> city = new Reader<>(() -> ((User) shape.value(store)).getAddress().getCity());
    assertEquals("London", city.last);

    shape.<User>update(store, d -> d.getAddress().setCity("Wien"));
    rt.flush();

    assertEquals("Wien", city.last);
    assertEquals(2, city.runs);
    assertEquals("London", ada.getAddress().getCity(), "the object the store was given is the application's");
    User now = (User) shape.value(store);
    assertNotSame(ada, now);
    assertEquals("Ada", now.getName());
  }

  @Test
  void onePropertyIsReadAndWrittenByItsGettersNames() {
    Object store = shape.create(new User("Ada", new Address("London", "N1")));
    String[] city = {Getters.property((java.util.function.Function<User, Address> & java.io.Serializable) User::getAddress),
      Getters.property((java.util.function.Function<Address, String> & java.io.Serializable) Address::getCity)};
    assertEquals(List.of("address", "city"), List.of(city));
    Reader<Object> cityReader = new Reader<>(() -> shape.readPath(store, city));
    Reader<Object> name = new Reader<>(() -> shape.readPath(store, new String[] {"name"}));

    shape.writePath(store, city, "Wien");
    rt.flush();
    assertEquals("Wien", cityReader.last);
    assertEquals(1, name.runs, "a property nobody wrote is not read again");

    shape.<User>update(store, d -> d.setName("Grace"));
    rt.flush();
    assertEquals("Grace", name.last);
    assertEquals(2, cityReader.runs);

    assertThrows(IllegalArgumentException.class,
      () -> Getters.property((java.util.function.Function<User, String> & java.io.Serializable) u -> u.getName()));
  }

  public static class Row {
    private long id;
    private String label;

    Row() {
    }

    Row(long id, String label) {
      this.id = id;
      this.label = label;
    }

    public long getId() {
      return id;
    }

    public String getLabel() {
      return label;
    }

    /** As a JPA entity usually has it: two rows are equal when their ids are. */
    @Override public boolean equals(Object o) {
      return o instanceof Row && ((Row) o).id == id;
    }

    @Override public int hashCode() {
      return Long.hashCode(id);
    }
  }

  private void drain() {
    for (int i = 0; i < 20; i++) {
      Runnable task;
      while ((task = microtasks.poll()) != null) {
        task.run();
      }
      rt.flush();
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRefreshedProjectionShowsARowsNewValue() {
    java.util.List<java.util.concurrent.CompletableFuture<Object>> loads = new ArrayList<>();
    List<Row>[] view = new List[1];
    rt.createRoot(owner -> {
      view[0] = (List<Row>) shape.createProjection(draft -> {
        java.util.concurrent.CompletableFuture<Object> f = new java.util.concurrent.CompletableFuture<>();
        loads.add(f);
        return f;
      }, row -> ((Row) row).getId());
      return null;
    });
    drain();
    loads.get(0).complete(List.of(new Row(1, "one"), new Row(2, "two v1")));
    drain();
    Reader<String> second = new Reader<>(() -> view[0].get(1).getLabel());
    Reader<String> first = new Reader<>(() -> view[0].get(0).getLabel());
    assertEquals("two v1", second.last);

    rt.refresh(shape.derive(view[0]));
    drain();
    loads.get(1).complete(List.of(new Row(1, "one"), new Row(2, "two v2")));
    drain();

    assertEquals("two v2", second.last);
    assertEquals(1, first.runs, "a row that came back the same is not read again");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aReloadHeldByItsReadersShowsARowsNewValue() {
    java.util.List<java.util.concurrent.CompletableFuture<Object>> loads = new ArrayList<>();
    List<Row>[] view = new List[1];
    Runtime.SignalRef<Integer> x = rt.createSignal(0);
    rt.createRoot(owner -> {
      view[0] = (List<Row>) shape.createProjection(draft -> {
        x.get();
        java.util.concurrent.CompletableFuture<Object> f = new java.util.concurrent.CompletableFuture<>();
        loads.add(f);
        return f;
      }, row -> ((Row) row).getId());
      return null;
    });
    drain();
    loads.get(0).complete(List.of(new Row(1, "one"), new Row(2, "two v1")));
    drain();
    Reader<String> second = new Reader<>(() -> view[0].get(1).getLabel());
    Reader<String> first = new Reader<>(() -> view[0].get(0).getLabel());
    assertEquals("two v1", second.last);

    x.set(1);
    drain();
    assertEquals("two v1", second.last, "the old row stays while the new one loads");
    loads.get(1).complete(List.of(new Row(1, "one"), new Row(2, "two v2")));
    drain();

    assertEquals("two v2", second.last);
    assertEquals(1, first.runs, "a row that came back the same is not read again");
  }

  @Test
  void devModeReportsABeanChangedOutsideUpdate() {
    List<String> reports = new ArrayList<>();
    shape.checkMutations(reports::add);
    Object store = shape.create(new User("Ada", new Address("London", "N1")));

    User given = (User) shape.value(store);
    shape.<User>update(store, d -> d.setName("Grace"));
    assertEquals(List.of(), reports, "a draft is what to change");

    ((User) shape.value(store)).getAddress().setCity("Wien");
    shape.value(store);
    assertEquals(1, reports.size(), reports.toString());
    assertEquals(true, reports.get(0).contains("Address.city"), reports.get(0));
    assertEquals("Ada", given.getName());
  }

  @Test
  void aDraftReadsItsOwnWrites() {
    Object store = shape.create(new User("Ada", new Address("London", "N1")));
    String[] seen = new String[1];
    shape.<User>update(store, d -> {
      d.setName("Grace");
      d.getAddress().setCity("Wien");
      seen[0] = d.getName() + " in " + d.getAddress().getCity();
    });
    assertEquals("Grace in Wien", seen[0]);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRowChangesAlone() {
    List<User> users = new ArrayList<>(List.of(new User("Ada", new Address("London", "N1")),
      new User("Grace", new Address("New York", "10001"))));
    List<User> store = (List<User>) shape.create(users);
    Reader<String> first = new Reader<>(() -> store.get(0).getName());
    Reader<String> second = new Reader<>(() -> store.get(1).getName());
    Reader<Integer> size = new Reader<>(store::size);

    shape.<List<User>>update(store, d -> d.get(0).setName("Ada L."));
    rt.flush();

    assertEquals("Ada L.", first.last);
    assertEquals(2, first.runs);
    assertEquals(1, second.runs, "a row nobody wrote is not read again");
    assertEquals(1, size.runs, "nor is the size");
    assertEquals("Ada", users.get(0).getName());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aListDraftIsAList() {
    List<String> store = (List<String>) shape.create(new ArrayList<>(List.of("a", "b", "c")));
    Reader<String> joined = new Reader<>(() -> String.join(",", store));

    shape.<List<String>>update(store, d -> {
      d.add("d");
      d.removeIf("b"::equals);
      d.add(0, "z");
    });
    rt.flush();

    assertEquals("z,a,c,d", joined.last);
    assertEquals(2, joined.runs, "one update is one change");
  }

  @Test
  @SuppressWarnings("unchecked")
  void anObjectTwoRowsShareIsOneObject() {
    Address shared = new Address("London", "N1");
    List<User> store = (List<User>) shape.create(new ArrayList<>(List.of(new User("Ada", shared), new User("Grace", shared))));
    Reader<String> second = new Reader<>(() -> store.get(1).getAddress().getCity());

    shape.<List<User>>update(store, d -> {
      assertSame(d.get(0).getAddress(), d.get(1).getAddress());
      d.get(0).getAddress().setCity("Wien");
    });
    rt.flush();

    assertEquals("Wien", second.last);
    assertEquals("London", shared.getCity());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMapTracksAKeyAndItsPresenceApart() {
    Map<String, Integer> store = (Map<String, Integer>) shape.create(new LinkedHashMap<>(Map.of("a", 1)));
    Reader<Integer> a = new Reader<>(() -> store.get("a"));
    Reader<Boolean> hasB = new Reader<>(() -> store.containsKey("b"));

    shape.<Map<String, Integer>>update(store, d -> d.put("b", 2));
    rt.flush();
    assertEquals(true, hasB.last);
    assertEquals(1, a.runs, "a key nobody wrote is not read again");

    shape.<Map<String, Integer>>update(store, d -> d.put("a", 5));
    rt.flush();
    assertEquals(5, a.last);
    assertEquals(2, hasB.runs);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aStoreIsWrittenOnlyInUpdate() {
    List<String> store = (List<String>) shape.create(new ArrayList<>(List.of("a")));
    assertThrows(UnsupportedOperationException.class, () -> store.add("b"));
    assertEquals(1, store.size());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aListInsideABeanIsWrittenThroughTheDraft() {
    User ada = new User("Ada", new Address("London", "N1"));
    ada.getTags().add("math");
    Object store = shape.create(ada);
    Reader<String> tags = new Reader<>(() -> String.join(",", ((User) shape.value(store)).getTags()));

    shape.<User>update(store, d -> d.getTags().add("engines"));
    rt.flush();

    assertEquals("math,engines", tags.last);
    assertEquals(List.of("math"), ada.getTags());
  }

  @Test
  @SuppressWarnings("unchecked")
  void replacingAValueReachesOnlyWhatChanged() {
    List<User> store = (List<User>) shape.create(new ArrayList<>(List.of(new User("Ada", null), new User("Grace", null))));
    Reader<String> first = new Reader<>(() -> store.get(0).getName());
    Reader<String> second = new Reader<>(() -> store.get(1).getName());

    User grace = store.get(1);
    shape.<List<User>>update(store, d -> d.set(0, new User("Ada L.", null)));
    rt.flush();

    assertEquals("Ada L.", first.last);
    assertEquals(1, second.runs);
    assertEquals("Grace", grace.getName());
  }

  @Test
  @SuppressWarnings("unchecked")
  void aRecordThatDidNotChangeIsTheObjectItWas() {
    Object store = shape.create(new ArrayList<>(List.of(new User("Ada", new Address("London", "N1")),
      new User("Grace", new Address("Arlington", "22201")))));
    List<User> users = (List<User>) store;
    shape.<List<User>>update(store, d -> {
      d.get(0).setName("Ada L.");
      d.get(1).getAddress().setCity("New York");
    });
    rt.flush();
    User ada = users.get(0);
    User grace = users.get(1);
    assertSame(ada, users.get(0), "a written row read twice is one object");
    assertSame(grace.getAddress(), users.get(1).getAddress());

    shape.<List<User>>update(store, d -> d.get(1).setName("Grace H."));
    rt.flush();
    assertSame(ada, users.get(0), "a row nobody wrote since keeps its object");
    assertNotSame(grace, users.get(1), "a written row is a new object");
    assertSame(grace.getAddress(), users.get(1).getAddress(), "and what below it did not change stays");
    assertEquals("Grace H.", users.get(1).getName());

    shape.<List<User>>update(store, d -> d.get(0).getAddress().setCity("Paris"));
    rt.flush();
    assertNotSame(ada, users.get(0), "a row whose address changed is a new object");
    assertEquals("Paris", users.get(0).getAddress().getCity());
  }

  public static class Node {
    private String label;
    private Node next;

    Node() {
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }

    public Node getNext() {
      return next;
    }

    public void setNext(Node next) {
      this.next = next;
    }
  }

  @Test
  void aCycleIsBuiltAnewAndPointsAtItself() {
    Node a = new Node();
    Node b = new Node();
    a.setLabel("a");
    b.setLabel("b");
    a.setNext(b);
    b.setNext(a);
    Object store = shape.create(a);
    shape.<Node>update(store, d -> d.getNext().setLabel("b2"));
    rt.flush();
    Node now = (Node) shape.value(store);
    assertSame(now, now.getNext().getNext(), "the cycle closes on the object handed out");
    assertEquals("b2", now.getNext().getLabel());
    shape.<Node>update(store, d -> d.setLabel("a2"));
    rt.flush();
    Node later = (Node) shape.value(store);
    assertEquals("a2", later.getLabel());
    assertSame(later, later.getNext().getNext(), "a kept snapshot never points at an older version of the cycle");
  }

  @Test
  void aCopyWhoseRecordWasLetGoIsStillBuilt() {
    Object store = shape.create(new User("Ada", new Address("London", "N1")));
    shape.<User>update(store, d -> d.getAddress().setCity("Wien"));
    rt.flush();
    // Nobody reads the address, so its record goes, and the store's copy of it stays in the user's.
    java.lang.ref.WeakReference<Object> record = new java.lang.ref.WeakReference<>(rt.storeGet(shape.targetOf(store), "address"));
    for (int i = 0; i < 50 && record.get() != null; i++) {
      System.gc();
    }
    org.junit.jupiter.api.Assumptions.assumeTrue(record.get() == null, "the collector let it go");
    User now = (User) shape.value(store);
    assertEquals("Wien", now.getAddress().getCity());
  }

  public static class Card {
    private String title;
    private java.util.Set<String> labels = new java.util.LinkedHashSet<>();

    Card() {
    }

    Card(String title) {
      this.title = title;
    }

    public String getTitle() {
      return title;
    }

    public java.util.Set<String> getLabels() {
      return labels;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aSetTracksEachElementsPresence() {
    Object store = shape.create(new java.util.LinkedHashSet<>(List.of("a", "b")));
    java.util.Set<String> set = (java.util.Set<String>) store;
    Reader<Boolean> hasA = new Reader<>(() -> set.contains("a"));
    Reader<Boolean> hasC = new Reader<>(() -> set.contains("c"));
    Reader<Integer> size = new Reader<>(set::size);
    Reader<String> all = new Reader<>(() -> String.join(",", set));

    shape.<java.util.Set<String>>update(store, d -> d.add("c"));
    rt.flush();
    assertEquals(true, hasC.last);
    assertEquals(2, hasC.runs);
    assertEquals(1, hasA.runs, "an element nobody added or removed is not read again");
    assertEquals(3, size.last);
    assertEquals("a,b,c", all.last);

    shape.<java.util.Set<String>>update(store, d -> d.add("a"));
    rt.flush();
    assertEquals(2, size.runs, "adding what is there changes nothing");
    assertEquals(2, all.runs);

    shape.<java.util.Set<String>>update(store, d -> d.removeIf("a"::equals));
    rt.flush();
    assertEquals(false, hasA.last);
    assertEquals(2, hasC.runs);
    assertEquals("b,c", all.last);
    assertThrows(UnsupportedOperationException.class, () -> set.add("d"), "a set is written inside update(...)");
  }

  @Test
  void aSetInABeanIsTheStoresOwn() {
    Card given = new Card("todo");
    given.getLabels().add("red");
    Object store = shape.create(given);
    Reader<Boolean> red = new Reader<>(() -> ((Card) shape.value(store)).getLabels().contains("red"));
    shape.<Card>update(store, d -> {
      d.getLabels().add("blue");
      d.getLabels().remove("red");
    });
    rt.flush();
    assertEquals(false, red.last);
    assertEquals(java.util.Set.of("red"), given.getLabels(), "the object the store was given is the application's");
    assertEquals(List.of("blue"), new ArrayList<>(((Card) shape.value(store)).getLabels()));
  }

  @Test
  @SuppressWarnings("unchecked")
  void aReloadedSetIsMergedByElement() {
    List<java.util.Set<String>> answers = new ArrayList<>(List.of(java.util.Set.of("a", "b")));
    List<Card>[] view = new List[1];
    rt.createRoot(owner -> {
      view[0] = (List<Card>) shape.createProjection(draft -> {
        Card c = new Card("one");
        c.getLabels().addAll(answers.get(answers.size() - 1));
        return List.of(c);
      }, row -> ((Card) row).getTitle());
      return null;
    });
    drain();
    // The row's labels as the store holds them: what a tracked Card's getter reads.
    Supplier<java.util.Set<String>> labels = () -> (java.util.Set<String>) rt.storeGet(
      ((StoreView) rt.storeGet(shape.targetOf(view[0]), 0)).target(), "labels");
    Reader<Boolean> hasA = new Reader<>(() -> labels.get().contains("a"));
    Reader<Boolean> hasB = new Reader<>(() -> labels.get().contains("b"));
    Reader<Boolean> hasC = new Reader<>(() -> labels.get().contains("c"));
    answers.add(java.util.Set.of("a", "c"));
    rt.refresh(shape.derive(view[0]));
    drain();
    assertEquals(new java.util.TreeSet<>(List.of("a", "c")), new java.util.TreeSet<>(view[0].get(0).getLabels()));
    assertEquals(1, hasA.runs, "an element in both answers is not read again");
    assertEquals(false, hasB.last);
    assertEquals(true, hasC.last);
  }

  public static class Totals {
    private int open;
    private String label = "totals";

    Totals() {
    }

    public int getOpen() {
      return open;
    }

    public void setOpen(int open) {
      this.open = open;
    }

    public String getLabel() {
      return label;
    }

    public void setLabel(String label) {
      this.label = label;
    }
  }

  @Test
  @SuppressWarnings("unchecked")
  void aDerivedStoreWritesADraftOfItsSeed() {
    Object source = shape.create(new ArrayList<>(List.of("a", "b")));
    List<String> names = (List<String>) source;
    int[] derives = {0};
    Object[] totals = new Object[1];
    rt.createRoot(owner -> {
      totals[0] = shape.createDerived(d -> {
        derives[0]++;
        ((Totals) d).setOpen(names.size());
      }, new Totals());
      return null;
    });
    drain();
    Reader<Object> open = new Reader<>(() -> shape.readPath(totals[0], new String[] {"open"}));
    Reader<Object> label = new Reader<>(() -> shape.readPath(totals[0], new String[] {"label"}));
    assertEquals(2, open.last);

    shape.<List<String>>update(source, d -> d.add("c"));
    drain();
    assertEquals(3, open.last);
    assertEquals(1, label.runs, "the derive wrote one property, so only its readers run");

    shape.<Totals>update(totals[0], d -> d.setLabel("mine"));
    drain();
    assertEquals("mine", label.last, "a write from outside stands");
    int before = derives[0];
    shape.<Totals>update(totals[0], d -> d.setOpen(99));
    drain();
    assertEquals(99, open.last);
    assertEquals(before, derives[0], "a write to the store does not run its derive");

    shape.<List<String>>update(source, d -> d.remove(0));
    drain();
    assertEquals(2, open.last, "until the derive runs again");
    assertEquals("mine", label.last);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aShallowStoreServesItsRowsByReference() {
    Row one = new Row(1, "one");
    Row two = new Row(2, "two");
    Object store = shape.create(new ArrayList<>(List.of(one, two)), true);
    List<Row> rows = (List<Row>) store;
    Reader<String> first = new Reader<>(() -> rows.get(0).getLabel());
    Reader<String> second = new Reader<>(() -> rows.get(1).getLabel());
    Reader<Integer> size = new Reader<>(rows::size);
    assertSame(one, rows.get(0), "a row is the object it was given");

    Row twoAgain = new Row(2, "two v2");
    shape.<List<Row>>update(store, d -> d.set(1, twoAgain));
    rt.flush();
    assertEquals("two v2", second.last, "a row whose equals compares ids is still another row");
    assertEquals(1, first.runs);
    assertEquals(1, size.runs);
    assertSame(twoAgain, rows.get(1));
    assertSame(one, rows.get(0));

    shape.<List<Row>>update(store, d -> d.add(new Row(3, "three")));
    rt.flush();
    assertEquals(3, size.last);
    assertEquals(1, first.runs);
    assertEquals("two", two.getLabel(), "what was given is never written");
  }
}
