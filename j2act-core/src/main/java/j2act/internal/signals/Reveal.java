package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import j2act.internal.signals.Boundaries.Boundary;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Port of @solidjs/signals reveal.ts at 9449845: an order in which sibling boundaries show their content. */
final class Reveal {
  private final Runtime rt;

  Reveal(Runtime rt) {
    this.rt = rt;
  }

  private static final Supplier<String> SEQUENTIAL = () -> "sequential";
  private static final Supplier<Boolean> FALSE = () -> false;

  private boolean slotReady(Object slot) {
    return slot instanceof Controller ? ((Controller) slot).isReady() : rt.boundaries.ready((Boundary) slot);
  }

  private boolean slotMinReady(Object slot) {
    return slot instanceof Controller ? ((Controller) slot).isMinReady() : rt.boundaries.ready((Boundary) slot);
  }

  private void setSlotState(Object slot, Controller controller, boolean gated, boolean collapsed) {
    if (slot instanceof Controller) {
      Controller c = (Controller) slot;
      c.gated = gated;
      c.collapsed = collapsed;
      c.evaluate();
      if (!gated && c.ready && c.parent == controller) {
        c.parent = null;
      }
      return;
    }
    Boundary b = (Boundary) slot;
    if (b.gated != gated || b.collapsed != collapsed) {
      b.gated = gated;
      b.collapsed = collapsed;
      rt.boundaries.redraw(b);
    }
  }

  /** Solid's RevealController. */
  final class Controller {
    final List<Object> slots = new ArrayList<>();
    Controller parent;
    boolean gated;
    boolean collapsed;
    boolean ready = true;
    boolean minReady = true;
    boolean evaluating;
    final Supplier<String> order;
    final Supplier<Boolean> collapse;
    /** Its owner, by which the caller of createRevealOwner names it. */
    Owner owner;

    Controller(Supplier<String> order, Supplier<Boolean> collapse) {
      this.order = order;
      this.collapse = collapse;
    }

    private boolean done(Object slot) {
      return (slot instanceof Controller ? ((Controller) slot).parent : ((Boundary) slot).reveal) != this;
    }

    private boolean owned(Predicate<Object> fn) {
      for (int i = 0; i < slots.size(); i++) {
        Object slot = slots.get(i);
        if (!done(slot) && !fn.test(slot)) {
          return false;
        }
      }
      return true;
    }

    boolean isReady() {
      for (int i = 0; i < slots.size(); i++) {
        Object slot = slots.get(i);
        if (!done(slot) && !slotReady(slot)) {
          return false;
        }
      }
      return true;
    }

    boolean isMinReady() {
      String o = rt.core.untrack(order);
      if (slots.isEmpty()) {
        return true;
      }
      if ("sequential".equals(o)) {
        return done(slots.get(0)) || slotMinReady(slots.get(0));
      }
      boolean natural = "natural".equals(o);
      for (int i = 0; i < slots.size(); i++) {
        boolean min = done(slots.get(i)) || slotMinReady(slots.get(i));
        if (natural ? min : !min) {
          return natural;
        }
      }
      return !natural;
    }

    void register(Object slot) {
      if (slots.contains(slot)) {
        return;
      }
      slots.add(slot);
      boolean collapsedTail = "sequential".equals(rt.core.untrack(order)) && truthy(rt.core.untrack(collapse));
      if (slot instanceof Controller) {
        Controller c = (Controller) slot;
        c.parent = this;
        c.gated = true;
        c.collapsed = collapsedTail;
        c.evaluate();
      } else {
        Boundary b = (Boundary) slot;
        b.reveal = this;
        b.gated = true;
        b.collapsed = collapsedTail;
      }
      rt.core.untrack(() -> {
        evaluate();
        return null;
      });
    }

    void unregister(Object slot) {
      slots.remove(slot);
      rt.core.untrack(() -> {
        evaluate();
        return null;
      });
    }

    void evaluate() {
      if (evaluating) {
        return;
      }
      evaluating = true;
      boolean wasReady = ready;
      boolean wasMinReady = minReady;
      try {
        String o = rt.core.untrack(order);
        boolean collapseTail = "sequential".equals(o) && truthy(rt.core.untrack(collapse));
        if (gated) {
          owned(slot -> {
            setSlotState(slot, this, true, collapsed);
            return true;
          });
        } else if ("natural".equals(o)) {
          owned(slot -> {
            if (slot instanceof Controller) {
              setSlotState(slot, this, false, false);
            } else {
              setSlotState(slot, this, !rt.boundaries.ready((Boundary) slot), false);
            }
            return true;
          });
        } else if ("together".equals(o)) {
          boolean min = owned(Reveal.this::slotMinReady);
          owned(slot -> {
            setSlotState(slot, this, !min, false);
            return true;
          });
        } else {
          boolean[] pendingSeen = new boolean[1];
          owned(slot -> {
            if (pendingSeen[0]) {
              setSlotState(slot, this, true, collapseTail);
            } else if (slotReady(slot)) {
              setSlotState(slot, this, false, false);
            } else {
              pendingSeen[0] = true;
              setSlotState(slot, this, !(slot instanceof Controller), false);
            }
            return true;
          });
        }
      } finally {
        ready = isReady();
        minReady = isMinReady();
        evaluating = false;
      }
      if (parent != null && (wasReady != ready || wasMinReady != minReady)) {
        parent.evaluate();
      }
    }
  }

  private static boolean truthy(Boolean b) {
    return b != null && b;
  }

  /** The owner of createRevealOrder, for a caller that renders under it again and again. */
  Owner createRevealOwner(Supplier<String> order, Supplier<Boolean> collapsed) {
    Owner owner = rt.owners.createOwner();
    Controller parent = (Controller) owner.ctx.get(Boundaries.REVEAL);
    Controller controller = new Controller(order != null ? order : SEQUENTIAL, collapsed != null ? collapsed : FALSE);
    Map<Object, Object> context = new HashMap<>(owner.ctx);
    context.put(Boundaries.REVEAL, controller);
    owner.ctx = context;
    controller.owner = owner;
    if (parent != null) {
      parent.register(controller);
      rt.owners.cleanup(() -> parent.unregister(controller), owner);
    }
    return owner;
  }

  /**
   * Not in Solid's file. Its order and collapsed are reactive and a computed evaluates when they change; a view's
   * are fields of the description it rendered, so a render evaluates instead. Its slots stay in the order they
   * registered, even when keyed siblings move; here the order is where they stand now (ADR 0033).
   */
  void rendered(Owner owner, List<Object> standing) {
    Controller controller = (Controller) owner.ctx.get(Boundaries.REVEAL);
    Map<Object, Integer> places = new HashMap<>();
    for (int i = 0; i < standing.size(); i++) {
      places.put(standing.get(i), i);
    }
    controller.slots.sort(java.util.Comparator.comparingInt(slot -> places.getOrDefault(
      slot instanceof Controller ? ((Controller) slot).owner : ((Boundary) slot).output, Integer.MAX_VALUE)));
    rt.core.untrack(() -> {
      controller.evaluate();
      return null;
    });
  }

  <T> T createRevealOrder(Function<Void, T> fn, Supplier<String> order, Supplier<Boolean> collapsed) {
    Owner owner = rt.owners.createOwner();
    Controller parent = (Controller) owner.ctx.get(Boundaries.REVEAL);
    Controller controller = new Controller(order != null ? order : SEQUENTIAL, collapsed != null ? collapsed : FALSE);
    Map<Object, Object> context = new HashMap<>(owner.ctx);
    context.put(Boundaries.REVEAL, controller);
    owner.ctx = context;
    return rt.core.runWithOwner(owner, () -> {
      T value = fn.apply(null);
      rt.core.computed(prev -> {
        controller.order.get();
        controller.collapse.get();
        controller.evaluate();
        return null;
      }, null);
      if (parent != null) {
        parent.register(controller);
        rt.owners.cleanup(() -> parent.unregister(controller), rt.core.context);
      }
      return value;
    });
  }
}
