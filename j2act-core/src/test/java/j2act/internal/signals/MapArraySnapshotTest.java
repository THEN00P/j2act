package j2act.internal.signals;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** map.ts's updateKeyedRows takes the pass's items before it maps any: what a mapper does to the list waits. */
class MapArraySnapshotTest {
  private final Runtime rt = new Runtime.Local().runtime;

  @Test void aMapperWritingTheListLeavesThisPassAlone() {
    List<Object> list = new ArrayList<>(List.of("a", "b"));
    Node mapped = rt.mapArray.mapArray(() -> list, (item, index) -> {
      if (item.equals("a")) {
        list.set(1, "c");
      }
      return item;
    }, false, null, false, null);
    assertEquals(List.of("a", "b"), rt.read(mapped));
  }

  @Test void theListIsCopiedUntracked() {
    Runtime.SignalRef<Integer> slot = rt.createSignal(1);
    AtomicInteger reads = new AtomicInteger();
    List<Object> list = new java.util.AbstractList<Object>() {
      @Override public Object get(int i) {
        reads.incrementAndGet();
        return slot.get();
      }

      @Override public int size() {
        return 1;
      }
    };
    Node mapped = rt.mapArray.mapArray(() -> list, (item, index) -> item, false, null, false, null);
    List<Object> seen = new ArrayList<>();
    rt.effectNode(prev -> rt.read(mapped), (v, p) -> {
      seen.add(((List<?>) v).get(0));
      return null;
    }, null, false, n -> { });
    rt.flush();
    slot.set(2);
    rt.flush();
    assertEquals(List.of(1), seen);
    assertEquals(1, reads.get());
  }
}
