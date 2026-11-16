package j2act.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import j2act.internal.signals.Runtime;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * ADR 0035: records in a store. Core's tests compile at release 11, which has no records, so these compile
 * theirs at run time with the JDK's compiler and use them through reflection.
 */
class RecordStoreTest {

  static Class<?> address;
  static Class<?> person;
  static Class<?> holder;

  @BeforeAll
  static void compile() throws Exception {
    Path dir = Files.createTempDirectory("j2act-records");
    Files.writeString(dir.resolve("Address.java"), "package rec; public record Address(String city, String zip) {}");
    Files.writeString(dir.resolve("Person.java"),
      "package rec; public record Person(long id, String name, Address address, java.util.List<String> tags) {}");
    Files.writeString(dir.resolve("Holder.java"), "package rec; public class Holder { private Person person;"
      + " public Holder() {} public Holder(Person p) { person = p; } public Person getPerson() { return person; }"
      + " public void setPerson(Person p) { person = p; } }");
    JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
    int status = javac.run(null, null, null, "--release", "17", "-d", dir.toString(),
      dir.resolve("Address.java").toString(), dir.resolve("Person.java").toString(), dir.resolve("Holder.java").toString());
    assertEquals(0, status, "the records compile");
    URLClassLoader loader = new URLClassLoader(new java.net.URL[] {dir.toUri().toURL()}, RecordStoreTest.class.getClassLoader());
    address = loader.loadClass("rec.Address");
    person = loader.loadClass("rec.Person");
    holder = loader.loadClass("rec.Holder");
  }

  static Object address(String city, String zip) throws Exception {
    return address.getConstructor(String.class, String.class).newInstance(city, zip);
  }

  static Object person(long id, String name, Object address, List<String> tags) throws Exception {
    Constructor<?> c = person.getConstructor(long.class, String.class, RecordStoreTest.address, List.class);
    return c.newInstance(id, name, address, tags);
  }

  static Object call(Object target, String method, Object... args) {
    try {
      for (Method m : target.getClass().getMethods()) {
        if (m.getName().equals(method) && m.getParameterCount() == args.length) {
          return m.invoke(target, args);
        }
      }
      throw new IllegalArgumentException(method);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e.getCause() != null ? e.getCause() : e);
    }
  }

  private final ArrayDeque<Runnable> microtasks = new ArrayDeque<>();
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
        });
        return null;
      });
      rt.flush();
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
  void aRecordIsReadPerComponentAndWrittenByOne() throws Exception {
    Object ada = person(1, "Ada", address("London", "N1"), List.of("math"));
    Object store = shape.create(ada);
    Reader<Object> city = new Reader<>(() -> shape.readPath(store, new String[] {"address", "city"}));
    Reader<Object> name = new Reader<>(() -> shape.readPath(store, new String[] {"name"}));

    shape.writePath(store, new String[] {"address", "city"}, "Wien");
    rt.flush();

    assertEquals("Wien", city.last);
    assertEquals(1, name.runs, "a component nobody wrote is not read again");
    Object now = shape.value(store);
    assertEquals(person, now.getClass(), "what a reader gets is the record itself");
    assertEquals("Wien", call(call(now, "address"), "city"));
    assertEquals("N1", call(call(now, "address"), "zip"));
    assertEquals("London", call(call(ada, "address"), "city"), "the record the store was given is unchanged");
    assertNotSame(ada, now);
  }

  @Test
  void aListInsideARecordIsWrittenThroughTheDraft() throws Exception {
    Object h = holder.getConstructor(person).newInstance(person(1, "Ada", address("London", "N1"), new ArrayList<>(List.of("math"))));
    Object store = shape.create(h);
    Reader<Object> tags = new Reader<>(() -> new ArrayList<>((List<?>) call(call(shape.value(store), "getPerson"), "tags")));
    Reader<Object> name = new Reader<>(() -> shape.readPath(store, new String[] {"person", "name"}));

    shape.<Object>update(store, d -> {
      @SuppressWarnings("unchecked")
      List<String> list = (List<String>) call(call(d, "getPerson"), "tags");
      list.add("engines");
    });
    rt.flush();

    assertEquals(List.of("math", "engines"), tags.last);
    assertEquals(1, name.runs);
    assertEquals(List.of("math"), call(call(h, "getPerson"), "tags"));
  }

  @Test
  void aRecordIsReplacedByAssigningANewOne() throws Exception {
    Object h = holder.getConstructor(person).newInstance(person(1, "Ada", address("London", "N1"), List.of()));
    Object store = shape.create(h);
    Reader<Object> name = new Reader<>(() -> shape.readPath(store, new String[] {"person", "name"}));

    Object grace = person(2, "Grace", address("New York", "10001"), List.of());
    shape.<Object>update(store, d -> call(d, "setPerson", grace));
    rt.flush();

    assertEquals("Grace", name.last);
  }

  /** A key as Reactive passes one on: typed for the row's class, so it throws on any other. */
  @SuppressWarnings("unchecked")
  static java.util.function.Function<Object, Object> typedKey() {
    java.util.function.Function<Object, Long> ofPerson = p -> (Long) call(person.cast(p), "id");
    return row -> ofPerson.apply(row);
  }

  @Test
  @SuppressWarnings("unchecked")
  void reloadedRecordsAreMergedByKeyComponentByComponent() throws Exception {
    List<CompletableFuture<Object>> loads = new ArrayList<>();
    List<Object>[] view = new List[1];
    rt.createRoot(owner -> {
      view[0] = (List<Object>) shape.createProjection(draft -> {
        CompletableFuture<Object> f = new CompletableFuture<>();
        loads.add(f);
        return f;
      }, typedKey());
      return null;
    });
    drain();
    loads.get(0).complete(List.of(person(1, "Ada", address("London", "N1"), List.of()),
      person(2, "Grace", address("New York", "10001"), List.of())));
    drain();
    // Per property, through the rows' views: a whole row from get(i) is Solid's deep(row), which a reload of
    // new nested objects wakes even when they are equal, as it does in Solid.
    Reader<Object> adaCity = new Reader<>(() -> rt.storeGet(shape.targetOf(rt.storeGet(
      shape.targetOf(rt.storeGet(shape.targetOf(view[0]), 0)), "address")), "city"));
    // One component of one row, read through the row's own view, as at(...) reads below a list.
    Reader<Object> graceName = new Reader<>(() -> rt.storeGet(shape.targetOf(rt.storeGet(shape.targetOf(view[0]), 1)), "name"));
    Reader<Object> graceCity = new Reader<>(() -> rt.storeGet(shape.targetOf(rt.storeGet(
      shape.targetOf(rt.storeGet(shape.targetOf(view[0]), 1)), "address")), "city"));

    rt.refresh(shape.derive(view[0]));
    drain();
    loads.get(1).complete(List.of(person(1, "Ada", address("London", "N1"), List.of()),
      person(2, "Grace", address("Arlington", "22201"), List.of())));
    drain();

    assertEquals("Arlington", graceCity.last);
    assertEquals(1, adaCity.runs, "a row that came back the same is not read again");
    assertTrue(graceName.runs == 1, "nor is a component that came back the same: " + graceName.runs);
  }
}
