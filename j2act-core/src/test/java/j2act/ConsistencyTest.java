package j2act;

import static j2act.Reactive.action;
import static j2act.Reactive.async;
import static j2act.Reactive.createMemo;
import static j2act.Reactive.createOptimistic;
import static j2act.Reactive.createSignal;
import static j2act.Reactive.refresh;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import j2act.DomContent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Random graphs of signals, memos, loads and optimistic values, driven by random writes, refreshes and
 * actions, with loads landing in random order. Every derived value is a pure function of what it
 * reads, so one thing must hold of every update a browser ever gets: each value on the page is that
 * function of the values on the same page. And at the end everything has finished: no action future
 * is left open, no transition stays held, and every guess has gone.
 */
class ConsistencyTest {

  private static final Pattern NODE = Pattern.compile("\\[(n\\d+)=([^\\]]*)\\]");
  private static final int SIGNALS = 3;
  private static final int OPTIMISTIC = 2;

  /** What one run of an action does. */
  static final class Run {
    final int optimistic;
    final String guess;
    final boolean fails;
    final int refreshes;
    final CompletableFuture<Void> gate = new CompletableFuture<>();
    final CountDownLatch guessed = new CountDownLatch(1);
    CompletableFuture<String> result;

    Run(int optimistic, String guess, boolean fails, int refreshes) {
      this.optimistic = optimistic;
      this.guess = guess;
      this.fails = fails;
      this.refreshes = refreshes;
    }
  }

  /** The graph's shape, fixed by the seed, and what the driver and the component share. */
  static final class Graph {
    final List<int[]> parents = new ArrayList<>();
    final List<Integer> kinds = new ArrayList<>();
    final List<Integer> loads = new ArrayList<>();
    final List<CompletableFuture<String>> pending = new CopyOnWriteArrayList<>();
    final List<Run> runs = new CopyOnWriteArrayList<>();
    volatile boolean loaded;
    volatile Run next;
    volatile String written;
    volatile int writeTo;
    volatile int refreshing;
    volatile int openTransitions;
    volatile String waiting;

    Graph(Random random, int derived) {
      for (int i = 0; i < derived; i++) {
        int reachable = SIGNALS + OPTIMISTIC + i;
        int first = random.nextInt(reachable);
        int kind = random.nextInt(4);
        parents.add(random.nextBoolean() ? new int[] {first} : new int[] {first, random.nextInt(reachable)});
        kinds.add(kind);
        if (kind != 0) {
          loads.add(SIGNALS + OPTIMISTIC + i);
        }
      }
    }
  }

  static String f(int node, String... inputs) {
    return Integer.toHexString((node + "|" + String.join(",", inputs)).hashCode());
  }

  static final class Page extends LiveComponent {
    private final Graph graph;

    Page(Graph graph) {
      this.graph = graph;
    }

    @Override protected View setup() {
      List<Signal<String>> signals = new ArrayList<>();
      List<Supplier<String>> nodes = new ArrayList<>();
      List<Optimistic<String>> optimistic = new ArrayList<>();
      for (int i = 0; i < SIGNALS; i++) {
        Signal<String> signal = createSignal("s" + i);
        signals.add(signal);
        nodes.add(signal);
      }
      for (int i = 0; i < OPTIMISTIC; i++) {
        Signal<String> signal = signals.get(i);
        Supplier<String> over = signal::get;
        Optimistic<String> guess = createOptimistic(over);
        optimistic.add(guess);
        nodes.add(guess);
      }
      for (int i = 0; i < graph.parents.size(); i++) {
        int id = SIGNALS + OPTIMISTIC + i;
        int[] from = graph.parents.get(i);
        List<Supplier<String>> inputs = new ArrayList<>();
        for (int parent : from) {
          inputs.add(nodes.get(parent));
        }
        nodes.add(derived(id, graph.kinds.get(i), inputs));
      }
      Action<Run, String> save = action((Run run) -> {
        optimistic.get(run.optimistic).set(run.guess);
        run.guessed.countDown();
        run.gate.get();
        if (run.fails) {
          throw new IllegalStateException("this action fails on purpose");
        }
        signals.get(run.optimistic).set(run.guess);
        if (run.refreshes >= 0) {
          refreshNode(nodes.get(run.refreshes));
        }
        return run.guess;
      });
      Tab tab = Tab.current();
      return () -> {
        List<DomContent> shown = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
          shown.add(T.span("[n" + i + "=" + nodes.get(i).get() + "]"));
        }
        shown.add(T.button("write").onClick(e -> signals.get(graph.writeTo).set(graph.written)));
        shown.add(T.button("act").onClick(e -> {
          Run run = graph.next;
          run.result = save.run(run);
        }));
        shown.add(T.button("refresh").onClick(e -> refreshNode(nodes.get(graph.refreshing))));
        shown.add(T.button("sync").onClick(e -> {
          graph.openTransitions = tab.transitions.size();
          graph.waiting = tab.transitions.stream().map(Transition::waitingFor).collect(java.util.stream.Collectors.joining(" / "));
        }));
        return T.page("t", T.div(shown.toArray(new DomContent[0])));
      };
    }

    private static void refreshNode(Supplier<String> node) {
      refresh(node);
    }

    private Supplier<String> derived(int id, int kind, List<Supplier<String>> inputs) {
      switch (kind) {
        case 0: {
          Supplier<String> body = () -> f(id, read(inputs));
          return createMemo(body);
        }
        case 1: {
          // Reads on the lane, lands when the test says.
          AsyncSupplier<String> body = () -> {
            String value = f(id, read(inputs));
            if (!graph.loaded) {
              return CompletableFuture.completedFuture(value);
            }
            CompletableFuture<String> landing = new CompletableFuture<>();
            graph.pending.add(landing);
            return landing.thenApply(ignored -> value);
          };
          return createMemo(body);
        }
        case 2: {
          // Reads on the lane, then a run off it that reads more.
          AsyncSupplier<String> body = () -> {
            read(inputs.subList(0, 1));
            return async(() -> f(id, read(inputs)));
          };
          return createMemo(body);
        }
        default: {
          // Everything it reads, it reads off the lane.
          AsyncSupplier<String> body = () -> async(() -> f(id, read(inputs)));
          return createMemo(body);
        }
      }
    }

    private static String[] read(List<Supplier<String>> inputs) {
      String[] values = new String[inputs.size()];
      for (int i = 0; i < values.length; i++) {
        values[i] = inputs.get(i).get();
      }
      return values;
    }
  }

  /** Each value on a page is what it derives from, on that page. */
  private static void checkPage(String html, Graph graph, String context) {
    Map<String, String> values = new HashMap<>();
    Matcher matcher = NODE.matcher(html);
    while (matcher.find()) {
      values.put(matcher.group(1), matcher.group(2));
    }
    for (int i = 0; i < graph.parents.size(); i++) {
      int id = SIGNALS + OPTIMISTIC + i;
      String shown = values.get("n" + id);
      if (shown == null) {
        continue;
      }
      int[] from = graph.parents.get(i);
      String[] inputs = new String[from.length];
      for (int j = 0; j < from.length; j++) {
        inputs[j] = values.get("n" + from[j]);
      }
      if (!f(id, inputs).equals(shown)) {
        fail("n" + id + "=" + shown + " does not follow from n" + java.util.Arrays.toString(from)
          + " on the same page\n" + values + "\n" + context);
      }
    }
  }

  private static Map<String, String> shownOn(String html) {
    Map<String, String> values = new LinkedHashMap<>();
    Matcher matcher = NODE.matcher(html);
    while (matcher.find()) {
      values.put(matcher.group(1), matcher.group(2));
    }
    return values;
  }

  private static void run(long seed, int derived, int steps) throws Exception {
    Random random = new Random(seed);
    Graph graph = new Graph(random, derived);
    StringBuilder log = new StringBuilder("seed " + seed + " parents");
    for (int[] from : graph.parents) {
      log.append(' ').append(java.util.Arrays.toString(from));
    }
    log.append(" kinds ").append(graph.kinds).append('\n');
    try (Harness h = new Harness(() -> new Page(graph), b -> b.withRateLimit(auth -> RateLimit.perMinute(10_000_000)))) {
      String html = h.load();
      graph.loaded = true;
      h.connect();
      checkPage(html, graph, log.toString());
      String write = Harness.clickOn(html, "write");
      String act = Harness.clickOn(html, "act");
      String refresh = Harness.clickOn(html, "refresh");
      String sync = Harness.clickOn(html, "sync");
      int checked = h.conn.size();
      int unique = 0;
      List<Run> open = new ArrayList<>();
      for (int step = 0; step < steps; step++) {
        int op = random.nextInt(10);
        if (op < 3) {
          graph.writeTo = random.nextInt(SIGNALS);
          graph.written = "w" + unique++;
          log.append("write s").append(graph.writeTo).append(" = ").append(graph.written).append('\n');
          h.click(write);
        } else if (op < 4 && !graph.loads.isEmpty()) {
          graph.refreshing = graph.loads.get(random.nextInt(graph.loads.size()));
          log.append("refresh n").append(graph.refreshing).append('\n');
          h.click(refresh);
        } else if (op < 6 && open.size() < 3) {
          int target = random.nextInt(OPTIMISTIC);
          int refreshes = random.nextInt(3) == 0 && !graph.loads.isEmpty()
            ? graph.loads.get(random.nextInt(graph.loads.size())) : -1;
          Run run = new Run(target, "g" + unique++, random.nextInt(4) == 0, refreshes);
          graph.next = run;
          graph.runs.add(run);
          open.add(run);
          log.append("act on o").append(target).append(" guess ").append(run.guess)
            .append(run.fails ? " (fails)" : "").append(" refreshing ").append(refreshes).append('\n');
          h.click(act);
          assertTrue(run.guessed.await(5, java.util.concurrent.TimeUnit.SECONDS), "the action starts\n" + log);
        } else if (op < 8 && !open.isEmpty()) {
          Run run = open.remove(random.nextInt(open.size()));
          log.append("let go of ").append(run.guess).append('\n');
          run.gate.complete(null);
        } else if (!graph.pending.isEmpty()) {
          int index = random.nextInt(graph.pending.size());
          CompletableFuture<String> landing = graph.pending.remove(index);
          log.append("land #").append(index).append('\n');
          landing.complete("");
        }
        h.click(sync);
        checked = checkPatches(h, graph, checked, log);
      }
      for (Run run : open) {
        run.gate.complete(null);
      }
      long deadline = System.currentTimeMillis() + 5000;
      while (true) {
        for (CompletableFuture<String> landing : new ArrayList<>(graph.pending)) {
          graph.pending.remove(landing);
          landing.complete("");
        }
        h.click(sync);
        boolean done = graph.pending.isEmpty() && graph.openTransitions == 0;
        for (Run run : graph.runs) {
          done &= run.result != null && run.result.isDone();
        }
        if (done) {
          break;
        }
        if (System.currentTimeMillis() > deadline) {
          StringBuilder stuck = new StringBuilder();
          for (Run run : graph.runs) {
            stuck.append(run.guess).append(run.result != null && run.result.isDone() ? " done" : " OPEN").append(' ');
          }
          fail("did not finish: waiting for " + graph.waiting + ", runs " + stuck + "\n" + log);
        }
        Thread.sleep(2);
      }
      checked = checkPatches(h, graph, checked, log);
      // Nothing to check at the end but one last render: every guess went, so the page is the truth.
      List<Map<String, String>> messages = h.conn.since(0, m -> m.get("html") != null);
      if (!messages.isEmpty()) {
        Map<String, String> last = shownOn(messages.get(messages.size() - 1).get("html"));
        for (int i = 0; i < OPTIMISTIC; i++) {
          assertEquals(last.get("n" + i), last.get("n" + (SIGNALS + i)), "guess " + i + " has gone\n" + last + "\n" + log);
        }
      }
    }
  }

  private static int checkPatches(Harness h, Graph graph, int from, StringBuilder log) {
    int size = h.conn.size();
    for (Map<String, String> message : h.conn.since(from, m -> m.get("html") != null)) {
      checkPage(message.get("html"), graph, log.toString());
    }
    return size;
  }

  @Test
  void everyUpdateIsConsistentAndEverythingFinishes() throws Exception {
    long start = Long.getLong("consistency.seed", 1L);
    int seeds = Integer.getInteger("consistency.seeds", 150);
    for (long seed = start; seed < start + seeds; seed++) {
      run(seed, 2 + (int) (seed % 7), 30);
    }
  }
}
