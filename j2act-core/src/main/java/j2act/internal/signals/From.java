package j2act.internal.signals;
// Port of @solidjs/signals at 9449845. Copyright (c) 2023-2025 Ryan Carniato, MIT License; see NOTICE.

import static j2act.internal.signals.Constants.CONFIG_CHILDREN_FORBIDDEN;

import j2act.internal.signals.Async.AsyncIterable;
import j2act.internal.signals.Async.AsyncIterator;
import j2act.internal.signals.Async.Step;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Port of @solidjs/signals from.ts at 9449845: a subscription as an async iterable that keeps the latest value. */
public final class From {
  /** Solid's subscribe function; its Observable and `{unsubscribe}` forms are the caller's to adapt. */
  @FunctionalInterface
  public interface Subscribe<T> {
    Runnable subscribe(Consumer<T> next, Consumer<Throwable> error, Runnable complete);
  }

  private final Runtime rt;

  From(Runtime rt) {
    this.rt = rt;
  }

  private void bindToOwner(Runnable finish) {
    Owner owner = rt.core.context;
    if (owner == null) {
      return;
    }
    rt.async.onSupersede(finish);
    if ((owner.config & CONFIG_CHILDREN_FORBIDDEN) == 0) {
      rt.owners.cleanup(finish, owner);
    }
  }

  private static <T> CompletionStage<Step<T>> resolved(Step<T> step) {
    return CompletableFuture.completedFuture(step);
  }

  <T> AsyncIterable<T> wrap(AsyncIterable<T> source) {
    return () -> {
      AsyncIterator<T> it = source.iterator();
      boolean[] closed = new boolean[1];
      Set<CompletableFuture<Step<T>>> pending = new LinkedHashSet<>();
      Runnable finish = () -> {
        if (closed[0]) {
          return;
        }
        closed[0] = true;
        for (CompletableFuture<Step<T>> p : new java.util.ArrayList<>(pending)) {
          p.complete(Step.end());
        }
        pending.clear();
        try {
          it.close();
        } catch (RuntimeException e) {
          // closing what nobody reads any more
        }
      };
      bindToOwner(finish);
      return new AsyncIterator<>() {
        @Override public CompletionStage<Step<T>> next() {
          if (closed[0]) {
            return resolved(Step.end());
          }
          CompletionStage<Step<T>> step = it.next();
          CompletableFuture<Step<T>> out = new CompletableFuture<>();
          pending.add(out);
          step.whenComplete((r, e) -> {
            if (pending.remove(out)) {
              if (e != null) {
                out.completeExceptionally(e);
              } else {
                out.complete(r);
              }
            }
          });
          return out;
        }

        @Override public void close() {
          finish.run();
        }
      };
    };
  }

  <T> AsyncIterable<T> from(Subscribe<T> subscribe) {
    return () -> new Latest<>(subscribe);
  }

  /** The iterator from() hands out. */
  private final class Latest<T> implements AsyncIterator<T> {
    T latest;
    boolean has;
    boolean done;
    boolean failed;
    Throwable failure;
    CompletableFuture<Step<T>> waiting;
    Runnable unsubscribe;
    boolean closed;

    Latest(Subscribe<T> subscribe) {
      Runnable teardown = subscribe.subscribe(value -> {
        if (done) {
          return;
        }
        latest = value;
        has = true;
        settle();
      }, err -> {
        if (done) {
          return;
        }
        failed = true;
        failure = err;
        done = true;
        settle();
        closeSource();
      }, () -> {
        if (done) {
          return;
        }
        done = true;
        settle();
        closeSource();
      });
      unsubscribe = teardown != null ? teardown : () -> { };
      if (closed) {
        Runnable c = unsubscribe;
        unsubscribe = null;
        runQuietly(c);
      }
      bindToOwner(this::finish);
    }

    private void runQuietly(Runnable c) {
      if (c == null) {
        return;
      }
      try {
        c.run();
      } catch (RuntimeException e) {
        // a teardown failing has nowhere to go
      }
    }

    private void closeSource() {
      if (closed) {
        return;
      }
      closed = true;
      done = true;
      Runnable c = unsubscribe;
      unsubscribe = null;
      runQuietly(c);
    }

    private void settle() {
      if (waiting == null) {
        return;
      }
      CompletableFuture<Step<T>> w = waiting;
      if (has) {
        waiting = null;
        has = false;
        w.complete(Step.of(latest));
      } else if (failed) {
        waiting = null;
        failed = false;
        w.completeExceptionally(failure);
      } else if (done) {
        waiting = null;
        w.complete(Step.end());
      }
    }

    private void finish() {
      closeSource();
      has = false;
      if (waiting != null) {
        CompletableFuture<Step<T>> w = waiting;
        waiting = null;
        w.complete(Step.end());
      }
    }

    @Override public CompletionStage<Step<T>> next() {
      if (has) {
        has = false;
        return resolved(Step.of(latest));
      }
      if (failed) {
        failed = false;
        CompletableFuture<Step<T>> f = new CompletableFuture<>();
        f.completeExceptionally(failure);
        return f;
      }
      if (done) {
        return resolved(Step.end());
      }
      waiting = new CompletableFuture<>();
      return waiting;
    }

    @Override public void close() {
      finish();
    }
  }
}
