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
}
