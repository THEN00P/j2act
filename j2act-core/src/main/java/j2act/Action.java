package j2act;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Stand-in until the actions slice: the thread-bound call of an action body (ADR 0031). */
final class Action {

  private Action() {
  }

  static final class Call {
    private static final ThreadLocal<Call> CURRENT = new ThreadLocal<>();
    final Tab tab;

    Call(Tab tab) {
      this.tab = tab;
    }

    static Call current() {
      return CURRENT.get();
    }

    void write(ValueCell cell, Object next) {
    }

    Object seen(ValueCell cell) {
      return cell.peekLatest();
    }

    void releaseAffected(AsyncCell cell) {
    }
  }
}
