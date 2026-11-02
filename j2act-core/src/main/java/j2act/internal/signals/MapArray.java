package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.CONFIG_AUTO_DISPOSE;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * Port of @solidjs/signals map.ts at 9449845: mapArray and repeat, which keep a row's owner across re-runs
 * and dispose only rows that left. Solid's `===` on items is equals here, as it is for signals.
 */
final class MapArray {
  private static final Core.NodeOptions PURE = pureOptions();

  private static Core.NodeOptions pureOptions() {
    Core.NodeOptions o = new Core.NodeOptions();
    o.ownedWrite = true;
    return o;
  }

  private final Runtime rt;
  int smallMoveHits;

  MapArray(Runtime rt) {
    this.rt = rt;
  }

  /** Solid's MapData. `map` gets the item or its row node, and its index node or index, as the mode says. */
  private final class MapData {
    Owner owner;
    int len;
    Supplier<List<Object>> list;
    List<Object> items = new ArrayList<>();
    Object[] mappings = new Object[0];
    Owner[] nodes = new Owner[0];
    BiFunction<Object, Object, Object> map;
    Function<Object, Object> key;
    Node[] rows;
    Node[] indexes;
    boolean byIndex;
    Supplier<Object> fallback;

    /** Solid's `_mappings.length`, which is 1 while a fallback shows. */
    int mappingsLength;

    int i;
    int j;
    int slot;
    Node[] tempRows;
    Node[] tempIndexes;
    List<Object> newItems;

    Object mapper() {
      if (rows != null) {
        if (byIndex) {
          tempRows[slot] = rt.core.signal(newItems.get(j), PURE);
          return map.apply(tempRows[slot], j);
        }
        tempRows[slot] = rt.core.signal(newItems.get(j), PURE);
        if (tempIndexes != null) {
          tempIndexes[slot] = rt.core.signal(j, PURE);
        }
        return map.apply(tempRows[slot], tempIndexes != null ? tempIndexes[slot] : null);
      }
      if (indexes != null) {
        Object item = newItems.get(j);
        tempIndexes[slot] = rt.core.signal(j, PURE);
        return map.apply(item, tempIndexes[slot]);
      }
      return map.apply(newItems.get(j), null);
    }

    boolean sameAt(int oldIndex, int newIndex) {
      return Objects.equals(items.get(oldIndex), newItems.get(newIndex));
    }

    boolean compare(Object a, Object b) {
      return key == null || Objects.equals(key.apply(a), key.apply(b));
    }

    Object keyOf(Object item) {
      return key != null ? key.apply(item) : item;
    }
  }

  private boolean trySmallMove(MapData data, List<Object> newItems, int newLen, int start) {
    List<Object> oldItems = data.items;
    int oldEnd = data.len - 1;
    List<Integer> srcPos = new ArrayList<>();
    List<Integer> dstPos = new ArrayList<>();
    List<Integer> runs = new ArrayList<>();
    int budget = 256;
    int i = start;
    int j = start;
    boolean inRun = false;
    while (i <= oldEnd && j <= newLen - 1) {
      Object oldItem = oldItems.get(i);
      Object newItem = newItems.get(j);
      if (Objects.equals(oldItem, newItem)) {
        if (!inRun) {
          runs.add(i);
          runs.add(j);
          runs.add(0);
          inRun = true;
        }
        runs.set(runs.size() - 1, runs.get(runs.size() - 1) + 1);
        i++;
        j++;
        continue;
      }
      inRun = false;
      int del = -1;
      int lim = Math.min(Math.min(32 - srcPos.size(), oldEnd - i), budget);
      for (int a = 1; a <= lim; a++) {
        if (Objects.equals(oldItems.get(i + a), newItem)) {
          del = a;
          break;
        }
      }
      budget -= del == -1 ? lim : del;
      int ins = -1;
      lim = Math.min(Math.min(32 - dstPos.size(), newLen - 1 - j), budget);
      for (int a = 1; a <= lim; a++) {
        if (Objects.equals(newItems.get(j + a), oldItem)) {
          ins = a;
          break;
        }
      }
      budget -= ins == -1 ? lim : ins;
      if (del != -1 && (ins == -1 || del <= ins)) {
        while (del-- > 0) {
          srcPos.add(i++);
        }
        continue;
      }
      if (ins != -1) {
        while (ins-- > 0) {
          dstPos.add(j++);
        }
        continue;
      }
      if (budget <= 0 || srcPos.size() == 32 || dstPos.size() == 32) {
        return false;
      }
      srcPos.add(i++);
      dstPos.add(j++);
    }
    for (; i <= oldEnd; i++) {
      if (srcPos.size() == 32) {
        return false;
      }
      srcPos.add(i);
    }
    for (; j <= newLen - 1; j++) {
      if (dstPos.size() == 32) {
        return false;
      }
      dstPos.add(j);
    }
    return commitSmallMove(data, newItems, newLen, srcPos, dstPos, runs);
  }

  private boolean commitSmallMove(MapData data, List<Object> newItems, int newLen, List<Integer> srcPos,
    List<Integer> dstPos, List<Integer> runs) {
    List<Object> oldItems = data.items;
    boolean[] consumed = null;
    if (!dstPos.isEmpty()) {
      consumed = new boolean[srcPos.size()];
      for (int j = 0; j < dstPos.size(); j++) {
        int found = -1;
        for (int i = 0; i < srcPos.size(); i++) {
          if (!consumed[i] && Objects.equals(oldItems.get(srcPos.get(i)), newItems.get(dstPos.get(j)))) {
            found = i;
            break;
          }
        }
        if (found == -1) {
          return false;
        }
        consumed[found] = true;
        dstPos.set(j, (dstPos.get(j) << 6) | found);
      }
    }
    if (!srcPos.isEmpty() || !dstPos.isEmpty()) {
      Set<Object> displaced = new HashSet<>();
      for (int i = 0; i < srcPos.size(); i++) {
        displaced.add(oldItems.get(srcPos.get(i)));
      }
      for (int j = 0; j < dstPos.size(); j++) {
        displaced.add(newItems.get(dstPos.get(j) >> 6));
      }
      for (int r = 0; r < runs.size(); r += 3) {
        int ro = runs.get(r);
        for (int a = 0, n = runs.get(r + 2); a < n; a++) {
          if (displaced.contains(oldItems.get(ro + a))) {
            return false;
          }
        }
      }
    }
    smallMoveHits++;
    Object[] oldMappings = data.mappings;
    Owner[] oldNodes = data.nodes;
    Object[] mappings = Arrays.copyOf(oldMappings, newLen);
    Owner[] nodes = Arrays.copyOf(oldNodes, newLen);
    for (int r = 0; r < runs.size(); r += 3) {
      int ro = runs.get(r);
      int rn = runs.get(r + 1);
      if (ro != rn) {
        for (int a = 0; a < runs.get(r + 2); a++) {
          mappings[rn + a] = oldMappings[ro + a];
          nodes[rn + a] = oldNodes[ro + a];
        }
      }
    }
    for (int j = 0; j < dstPos.size(); j++) {
      int p = dstPos.get(j) >> 6;
      int q = srcPos.get(dstPos.get(j) & 63);
      mappings[p] = oldMappings[q];
      nodes[p] = oldNodes[q];
    }
    data.mappings = mappings;
    data.mappingsLength = newLen;
    data.nodes = nodes;
    data.len = newLen;
    data.items = new ArrayList<>(newItems);
    for (int i = 0; i < srcPos.size(); i++) {
      if (consumed == null || !consumed[i]) {
        oldNodes[srcPos.get(i)].dispose();
      }
    }
    return true;
  }

  private List<Owner> stageRemoval(MapData data, List<Object> newItems, int start, int end, int newEnd, Object[] temp,
    Owner[] tempNodes, Node[] rows, Node[] indexes) {
    List<Object> oldItems = data.items;
    Owner[] nodes = data.nodes;
    List<Owner> removed = new ArrayList<>();
    int i = start;
    for (int j = start; j <= newEnd; i++, j++) {
      Object nk = data.keyOf(newItems.get(j));
      while (i <= end && !Objects.equals(data.keyOf(oldItems.get(i)), nk)) {
        removed.add(nodes[i++]);
      }
      if (i > end) {
        Arrays.fill(tempNodes, null);
        return null;
      }
      temp[j] = data.mappings[i];
      tempNodes[j - start] = nodes[i];
      if (rows != null) {
        rows[j - start] = data.rows[i];
      }
      if (indexes != null) {
        indexes[j - start] = data.indexes[i];
      }
    }
    for (; i <= end; i++) {
      removed.add(nodes[i]);
    }
    return removed;
  }

  private void commitWindow(MapData data, List<Object> newItems, int newLen, int start, int end, int newEnd,
    Owner[] tempNodes, Node[] rows, Node[] indexes) {
    int oldLen = data.len;
    int dif = newLen - oldLen;
    if (dif > 0) {
      data.nodes = Arrays.copyOf(data.nodes, newLen);
      if (data.rows != null) {
        data.rows = Arrays.copyOf(data.rows, newLen);
      }
      if (data.indexes != null) {
        data.indexes = Arrays.copyOf(data.indexes, newLen);
      }
      for (int i = oldLen - 1, j = newLen - 1; i > end; i--, j--) {
        data.nodes[j] = data.nodes[i];
        if (data.rows != null) {
          data.rows[j] = data.rows[i];
        }
        if (data.indexes != null) {
          data.indexes[j] = data.indexes[i];
        }
      }
    } else if (dif < 0) {
      for (int i = end + 1, j = newEnd + 1; i < oldLen; i++, j++) {
        data.nodes[j] = data.nodes[i];
        if (data.rows != null) {
          data.rows[j] = data.rows[i];
        }
        if (data.indexes != null) {
          data.indexes[j] = data.indexes[i];
        }
      }
      data.nodes = Arrays.copyOf(data.nodes, newLen);
      if (data.rows != null) {
        data.rows = Arrays.copyOf(data.rows, newLen);
      }
      if (data.indexes != null) {
        data.indexes = Arrays.copyOf(data.indexes, newLen);
      }
    }
    for (int j = start; j <= newEnd; j++) {
      data.nodes[j] = tempNodes[j - start];
      if (data.rows != null) {
        data.rows[j] = rows[j - start];
        rt.core.setSignal(data.rows[j], newItems.get(j));
      }
      if (data.indexes != null) {
        data.indexes[j] = indexes[j - start];
        rt.core.setSignal(data.indexes[j], j);
      }
    }
    for (int j = newEnd + 1; j < newLen; j++) {
      if (data.rows != null) {
        rt.core.setSignal(data.rows[j], newItems.get(j));
      }
      if (data.indexes != null && dif != 0) {
        rt.core.setSignal(data.indexes[j], j);
      }
    }
  }

  private Object inOwner(Owner owner, Supplier<Object> fn) {
    return rt.core.runWithOwner(owner, fn);
  }

  private List<Object> updateKeyedMap(MapData d) {
    d.owner.parentComputed = rt.core.context instanceof Node ? (Node) rt.core.context : null;
    List<Object> listed = d.list.get();
    List<Object> newItems = listed != null ? listed : new ArrayList<>();
    int newLen = newItems.size();
    d.newItems = newItems;
    rt.core.runWithOwner(d.owner, () -> {
      updateInOwner(d, newItems, newLen);
      return null;
    });
    return toList(d.mappings, d.mappingsLength);
  }

  private List<Object> toList(Object[] mappings, int length) {
    return new ArrayList<>(Arrays.asList(mappings).subList(0, length));
  }

  private void updateInOwner(MapData d, List<Object> newItems, int newLen) {
    if (newLen == 0) {
      if (d.len != 0) {
        d.owner.dispose(false);
        d.nodes = new Owner[0];
        d.items = new ArrayList<>();
        d.mappings = new Object[0];
        d.mappingsLength = 0;
        d.len = 0;
        if (d.rows != null) {
          d.rows = new Node[0];
        }
        if (d.indexes != null) {
          d.indexes = new Node[0];
        }
      }
      if (d.fallback != null && d.mappingsLength == 0) {
        if (d.nodes.length > 0 && d.nodes[0] != null) {
          d.nodes[0].dispose();
        }
        Owner fb = rt.owners.createOwner();
        d.nodes = new Owner[] {fb};
        Object value = inOwner(fb, d.fallback);
        d.mappings = new Object[] {value};
        d.mappingsLength = 1;
      }
    } else if (d.len == 0) {
      Object[] mappings = new Object[newLen];
      Owner[] nodes = new Owner[newLen];
      d.tempRows = d.rows != null ? new Node[newLen] : null;
      d.tempIndexes = d.indexes != null ? new Node[newLen] : null;
      try {
        for (d.j = 0; d.j < newLen; d.j++) {
          d.slot = d.j;
          nodes[d.j] = rt.owners.createOwner();
          mappings[d.j] = inOwner(nodes[d.j], d::mapper);
        }
      } catch (RuntimeException | Error err) {
        for (int i = 0; i <= d.j && i < newLen; i++) {
          if (nodes[i] != null) {
            nodes[i].dispose();
          }
        }
        throw err;
      }
      if (d.nodes.length > 0 && d.nodes[0] != null) {
        d.nodes[0].dispose();
      }
      d.mappings = mappings;
      d.mappingsLength = newLen;
      d.nodes = nodes;
      if (d.tempRows != null) {
        d.rows = d.tempRows;
      }
      if (d.tempIndexes != null) {
        d.indexes = d.tempIndexes;
      }
      d.items = new ArrayList<>(newItems);
      d.len = newLen;
    } else {
      updateWindow(d, newItems, newLen);
    }
  }

  private void updateWindow(MapData d, List<Object> newItems, int newLen) {
    int start = 0;
    int end = Math.min(d.len, newLen);
    for (; start < end && (d.sameAt(start, start)
      || (d.rows != null && d.compare(d.items.get(start), newItems.get(start)))); start++) {
      if (d.rows != null) {
        rt.core.setSignal(d.rows[start], newItems.get(start));
      }
    }
    end = d.len - 1;
    int newEnd = newLen - 1;
    for (; end >= start && newEnd >= start && (d.sameAt(end, newEnd)
      || (d.rows != null && d.compare(d.items.get(end), newItems.get(newEnd)))); end--, newEnd--) {
      // only moving the cursors
    }
    if (start == newLen && d.len == newLen) {
      d.items = new ArrayList<>(newItems);
      return;
    }
    if (newLen <= d.len && end - start > 64 && d.rows == null && d.indexes == null) {
      int m = start + ((newEnd - start) >> 1);
      Object probe = newItems.get(m);
      int hi = Math.min(end, m + 32);
      int k = Math.max(start, m - 32);
      while (k <= hi && !Objects.equals(d.items.get(k), probe)) {
        k++;
      }
      if (k <= hi && trySmallMove(d, newItems, newLen, start)) {
        return;
      }
    }
    int dif = newLen - d.len;
    int wLen = newEnd - start + 1;
    Object[] temp = new Object[newLen];
    Owner[] tempNodes = new Owner[wLen];
    d.tempRows = d.rows != null ? new Node[wLen] : null;
    d.tempIndexes = d.indexes != null ? new Node[wLen] : null;
    List<Owner> pure = dif < 0
      ? stageRemoval(d, newItems, start, end, newEnd, temp, tempNodes, d.tempRows, d.tempIndexes) : null;
    List<Owner> removed = null;
    List<Owner> created = null;
    if (pure != null) {
      if (!pure.isEmpty()) {
        removed = pure;
      }
    } else {
      Map<Object, Integer> newIndices = new HashMap<>();
      int[] newIndicesNext = new int[Math.max(wLen, 0)];
      for (int j = newEnd; j >= start; j--) {
        Object key = d.keyOf(newItems.get(j));
        Integer i = newIndices.get(key);
        newIndicesNext[j - start] = i == null ? -1 : i;
        newIndices.put(key, j);
      }
      for (int i = start; i <= end; i++) {
        Object key = d.keyOf(d.items.get(i));
        Integer j = newIndices.get(key);
        if (j != null && j != -1) {
          temp[j] = d.mappings[i];
          tempNodes[j - start] = d.nodes[i];
          if (d.tempRows != null) {
            d.tempRows[j - start] = d.rows[i];
          }
          if (d.tempIndexes != null) {
            d.tempIndexes[j - start] = d.indexes[i];
          }
          newIndices.put(key, newIndicesNext[j - start]);
        } else {
          if (removed == null) {
            removed = new ArrayList<>();
          }
          removed.add(d.nodes[i]);
        }
      }
      try {
        for (d.j = start; d.j <= newEnd; d.j++) {
          if (tempNodes[d.j - start] != null) {
            continue;
          }
          if (created == null) {
            created = new ArrayList<>();
          }
          tempNodes[d.j - start] = rt.owners.createOwner();
          created.add(tempNodes[d.j - start]);
          d.slot = d.j - start;
          temp[d.j] = inOwner(tempNodes[d.j - start], d::mapper);
        }
      } catch (RuntimeException | Error err) {
        if (created != null) {
          for (Owner o : created) {
            o.dispose();
          }
        }
        throw err;
      }
    }
    for (int i = 0; i < start; i++) {
      temp[i] = d.mappings[i];
    }
    for (int j = newEnd + 1; j < newLen; j++) {
      temp[j] = d.mappings[j - dif];
    }
    commitWindow(d, newItems, newLen, start, end, newEnd, tempNodes, d.tempRows, d.tempIndexes);
    d.mappings = temp;
    d.mappingsLength = newLen;
    d.len = newLen;
    d.items = new ArrayList<>(newItems);
    if (removed != null) {
      for (Owner o : removed) {
        o.dispose();
      }
    }
  }

  /**
   * `keyed` is Solid's option: a key function, `true` (the item is its own key, the default) or `false`
   * (rows by position). `indexes` is Solid's `map.length > 1`. The accessor's value is a List.
   */
  Node mapArray(Supplier<List<Object>> list, BiFunction<Object, Object, Object> map, boolean indexes,
    Function<Object, Object> keyFn, boolean byIndex, Supplier<Object> fallback) {
    MapData data = new MapData();
    data.owner = rt.owners.createOwner();
    data.list = list;
    data.map = map;
    data.key = keyFn;
    data.rows = keyFn != null || byIndex ? new Node[0] : null;
    data.indexes = indexes && !byIndex ? new Node[0] : null;
    data.byIndex = byIndex;
    data.fallback = fallback;
    Node node = rt.core.computed(prev -> updateKeyedMap(data), null);
    node.config &= ~CONFIG_AUTO_DISPOSE;
    return node;
  }

  /** Solid's RepeatData. */
  private static final class RepeatData {
    Owner owner;
    int len;
    int offset;
    Supplier<Integer> count;
    IntFunction<Object> map;
    Owner[] nodes = new Owner[0];
    Object[] mappings = new Object[0];
    int mappingsLength;
    Supplier<Integer> from;
    Supplier<Object> fallback;
  }

  Node repeat(Supplier<Integer> count, IntFunction<Object> map, Supplier<Integer> from, Supplier<Object> fallback) {
    RepeatData data = new RepeatData();
    data.owner = rt.owners.createOwner();
    data.count = count;
    data.map = map;
    data.from = from;
    data.fallback = fallback;
    Node node = rt.core.computed(prev -> updateRepeat(data), null);
    node.config &= ~CONFIG_AUTO_DISPOSE;
    return node;
  }

  private List<Object> updateRepeat(RepeatData d) {
    d.owner.parentComputed = rt.core.context instanceof Node ? (Node) rt.core.context : null;
    int newLen = d.count.get();
    Integer fromValue = d.from != null ? d.from.get() : null;
    int from = fromValue != null ? fromValue : 0;
    rt.core.runWithOwner(d.owner, () -> {
      repeatInOwner(d, newLen, from);
      return null;
    });
    return toList(d.mappings, d.mappingsLength);
  }

  private void repeatInOwner(RepeatData d, int newLen, int from) {
    if (newLen == 0) {
      if (d.len != 0) {
        d.owner.dispose(false);
        d.nodes = new Owner[0];
        d.mappings = new Object[0];
        d.mappingsLength = 0;
        d.len = 0;
        d.offset = 0;
      }
      if (d.fallback != null && d.mappingsLength == 0) {
        if (d.nodes.length > 0 && d.nodes[0] != null) {
          d.nodes[0].dispose();
        }
        Owner fb = rt.owners.createOwner();
        d.nodes = new Owner[] {fb};
        d.mappings = new Object[] {inOwner(fb, d.fallback)};
        d.mappingsLength = 1;
      }
      return;
    }
    int to = from + newLen;
    int prevTo = d.offset + d.len;
    int keepStart = Math.max(from, d.offset);
    int keepEnd = Math.min(to, prevTo);
    Object[] mappings = new Object[newLen];
    Owner[] nodes = new Owner[newLen];
    for (int i = keepStart; i < keepEnd; i++) {
      nodes[i - from] = d.nodes[i - d.offset];
      mappings[i - from] = d.mappings[i - d.offset];
    }
    try {
      for (int i = from; i < to; i++) {
        if (i >= keepStart && i < keepEnd) {
          continue;
        }
        int index = i;
        nodes[i - from] = rt.owners.createOwner();
        mappings[i - from] = inOwner(nodes[i - from], () -> d.map.apply(index));
      }
    } catch (RuntimeException | Error err) {
      for (int i = from; i < to; i++) {
        if ((i < keepStart || i >= keepEnd) && nodes[i - from] != null) {
          nodes[i - from].dispose();
        }
      }
      throw err;
    }
    if (d.len == 0) {
      if (d.nodes.length > 0 && d.nodes[0] != null) {
        d.nodes[0].dispose();
      }
    } else {
      for (int i = d.offset; i < prevTo; i++) {
        if (i < from || i >= to) {
          d.nodes[i - d.offset].dispose();
        }
      }
    }
    d.mappings = mappings;
    d.mappingsLength = newLen;
    d.nodes = nodes;
    d.offset = from;
    d.len = newLen;
  }
}
