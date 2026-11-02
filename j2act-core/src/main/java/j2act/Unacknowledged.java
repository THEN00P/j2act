package j2act;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What the server sent a tab and the client has not acknowledged (ADR 0032). A numbered message never
 * changes and leaves only by an acknowledgement, so a resend has no gaps. Lane-only.
 */
final class Unacknowledged {

  private static final class Sent {
    final long number;
    final String wire;

    Sent(long number, String wire) {
      this.number = number;
      this.wire = wire;
    }
  }

  private final long maxChars;
  private final Deque<Sent> sent = new ArrayDeque<>();
  private long lastNumber;
  private long chars;

  Unacknowledged(long maxChars) {
    this.maxChars = maxChars;
  }

  /** Takes the next number, and returns the message as it goes on the wire. */
  String add(String message) {
    long number = ++lastNumber;
    // Every message is a JSON object, so the number goes first.
    String wire = "{\"n\":" + number + "," + message.substring(1);
    sent.addLast(new Sent(number, wire));
    chars += wire.length();
    return wire;
  }

  /** The client applied everything up to this number. */
  void acknowledge(long upTo) {
    while (!sent.isEmpty() && sent.peekFirst().number <= upTo) {
      chars -= sent.removeFirst().wire.length();
    }
  }

  /** What is still owed, in order. */
  List<String> owed() {
    List<String> wires = new ArrayList<>(sent.size());
    for (Sent one : sent) {
      wires.add(one.wire);
    }
    return wires;
  }

  /** At the cap, the tab stops producing patches: what changes stays marked dirty instead. */
  boolean full() {
    return chars >= maxChars;
  }

  int size() {
    return sent.size();
  }
}
