package j2act.internal.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** The open-addressed weak identity map against IdentityHashMap, with keys that go. */
class WeakIdentityMapTest {

  @Test
  void answersAsAnIdentityMapDoes() {
    Random random = new Random(7);
    WeakIdentityMap<Integer> weak = new WeakIdentityMap<>();
    Map<Object, Integer> plain = new IdentityHashMap<>();
    List<Object> keys = new ArrayList<>();
    for (int i = 0; i < 300; i++) {
      // Equal strings that are different objects: only identity tells them apart.
      keys.add(new String("k" + (i % 50)));
    }
    for (int step = 0; step < 200_000; step++) {
      Object key = keys.get(random.nextInt(keys.size()));
      switch (random.nextInt(4)) {
        case 0:
        case 1:
          assertEquals(plain.put(key, step), weak.put(key, step));
          break;
        case 2:
          assertEquals(plain.remove(key), weak.remove(key));
          break;
        default:
          assertEquals(plain.get(key), weak.get(key));
          assertEquals(plain.containsKey(key), weak.containsKey(key));
      }
      if (step % 1000 == 0) {
        assertEquals(plain.size(), weak.size());
      }
    }
  }

  @Test
  void doesNotKeepItsKeys() throws Exception {
    WeakIdentityMap<String> weak = new WeakIdentityMap<>();
    Object kept = new Object();
    weak.put(kept, "kept");
    for (int i = 0; i < 10_000; i++) {
      weak.put(new Object(), "gone");
    }
    for (int i = 0; i < 50 && weak.size() > 1; i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertEquals(1, weak.size());
    assertEquals("kept", weak.get(kept));
    for (int i = 0; i < 1000; i++) {
      weak.put(new Object(), "again");
    }
    assertTrue(weak.size() >= 1);
    assertEquals("kept", weak.get(kept));
  }

  /** Keys that go while the map is in use: those still held answer as they were put, whatever went around them. */
  @Test
  void keysThatGoLeaveTheOthersAsTheyWere() throws Exception {
    Random random = new Random(11);
    WeakIdentityMap<Integer> weak = new WeakIdentityMap<>();
    Map<Object, Integer> held = new IdentityHashMap<>();
    List<Object> alive = new ArrayList<>();
    for (int round = 0; round < 40; round++) {
      for (int step = 0; step < 5000; step++) {
        int op = random.nextInt(10);
        if (op < 4 || alive.isEmpty()) {
          Object key = new Object();
          alive.add(key);
          held.put(key, step);
          assertEquals(null, weak.put(key, step));
        } else if (op < 6) {
          // Let one go: nothing holds it any more.
          Object key = alive.remove(random.nextInt(alive.size()));
          held.remove(key);
        } else if (op < 7) {
          Object key = alive.get(random.nextInt(alive.size()));
          assertEquals(held.remove(key), weak.remove(key));
          alive.remove(key);
        } else {
          Object key = alive.get(random.nextInt(alive.size()));
          assertEquals(held.get(key), weak.get(key), "round " + round + " step " + step);
        }
      }
      if (round % 4 == 0) {
        System.gc();
        Thread.sleep(5);
      }
    }
    for (Object key : alive) {
      assertEquals(held.get(key), weak.get(key));
    }
  }
}
