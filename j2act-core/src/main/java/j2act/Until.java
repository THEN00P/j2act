package j2act;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * until(fn) and resolve(fn), as in Solid 2 (ADR 0031): evaluates fn against the authoritative view,
 * following what it read, until it settles. A guess can never satisfy the wait for its own
 * confirmation, and a read that is not loaded yet means "not yet". Evaluated on the lane.
 */
final class Until<T> implements Observer {

  private final Tab tab;
  private final Supplier<T> fn;
  /** until waits for a value that says yes; resolve takes the first value there is. */
  private final boolean needsTruthy;
  final CompletableFuture<T> result = new CompletableFuture<>();
  private final Set<Cell> deps = new HashSet<>();
  private boolean queued;

  Until(Tab tab, Supplier<T> fn, boolean needsTruthy, Duration timeout) {
    this.tab = tab;
    this.fn = fn;
    this.needsTruthy = needsTruthy;
    result.whenComplete((value, failure) -> tab.post(this::stop));
    tab.post(() -> {
      if (tab.disposed) {
        result.cancel(false);
        return;
      }
      tab.untils.add(this);
      evaluate();
    });
    if (timeout != null) {
      tab.engine.later(tab, timeout.toMillis(), () -> result.completeExceptionally(
        new TimeoutException("until(...) was not satisfied within " + timeout)));
    }
  }

  private void evaluate() {
    queued = false;
    if (result.isDone()) {
      return;
    }
    unsubscribe();
    Observer previous = Tracking.swap(this);
    Pass previousPass = Tracking.swapPass(Pass.AUTHORITATIVE);
    try {
      T value = fn.get();
      if (!needsTruthy || value != null && !Boolean.FALSE.equals(value)) {
        result.complete(value);
      }
    } catch (NotReady e) {
      // Not yet: what it read tells this when it lands.
      Cell local = tab.local(e.cell);
      local.observers.add(this);
      deps.add(local);
    } catch (LoadFailed e) {
      result.completeExceptionally(e.getCause() != null ? e.getCause() : e);
    } catch (RuntimeException e) {
      result.completeExceptionally(e);
    } finally {
      Tracking.swap(previous);
      Tracking.swapPass(previousPass);
    }
  }

  @Override public void invalidate() {
    if (!queued) {
      queued = true;
      // Later on the lane, so it never evaluates in the middle of a commit.
      tab.post(this::evaluate);
    }
  }

  @Override public void dependsOn(Cell cell) {
    deps.add(cell);
  }

  private void unsubscribe() {
    for (Cell cell : deps) {
      cell.unsubscribe(this);
    }
    deps.clear();
  }

  private void stop() {
    unsubscribe();
    tab.untils.remove(this);
  }

  /** The tab ended, so nothing will ever satisfy it, and whoever waits must not wait for good. */
  void cancel() {
    deps.clear();
    result.cancel(false);
  }
}
