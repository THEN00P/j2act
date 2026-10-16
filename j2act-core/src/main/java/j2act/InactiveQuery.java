package j2act;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A query result kept after its component unmounted. It is reused on remount at the
 * same slot when the values at the addresses it read still match (ADR 0020).
 */
final class InactiveQuery {

  final Object data;
  final List<String> addresses;
  final List<Object> values;
  final long fetchedAt;
  long storedAt;

  InactiveQuery(Object data, List<String> addresses, List<Object> values, long fetchedAt) {
    this.data = data;
    this.addresses = addresses;
    this.values = values;
    this.fetchedAt = fetchedAt;
  }

  boolean matchesCurrent(Tab tab) {
    for (int i = 0; i < addresses.size(); i++) {
      Cell cell = tab.cells.get(addresses.get(i));
      if (cell == null || !Objects.equals(cell.peek(), values.get(i))) {
        return false;
      }
    }
    return true;
  }

  List<Cell> resolveCells(Tab tab) {
    List<Cell> cells = new ArrayList<>();
    for (String address : addresses) {
      cells.add(tab.cells.get(address));
    }
    return cells;
  }
}
