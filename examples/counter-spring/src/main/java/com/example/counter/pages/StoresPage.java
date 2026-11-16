package com.example.counter.pages;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import j2act.ComponentTag;
import j2act.LiveComponent;
import j2act.Projection;
import j2act.Prop;
import j2act.Stored;
import j2act.View;

/**
 * Deep stores (ADR 0035), one section per thing they do. Every row shows how often it rendered, so what a write
 * reaches is visible: a change to one row renders that row and nothing else. A view renders the components it
 * places whenever it runs, so each section is a component of its own, and a list's view reads only its size.
 */
public class StoresPage extends LiveComponent {

  @Override protected View setup() {
    var board = createStore(new Board("Launch", new Person("Ada"), List.of(
      new Task("Write the docs"), new Task("Cut the release"), new Task("Tell everyone"))));
    // A writable derived store: worked out from the board whenever what it read changes, and writable meanwhile.
    var totals = createStore(d -> {
      d.setOpen((int) board.get().getTasks().stream().filter(t -> !t.isDone()).count());
      d.setDone((int) board.get().getTasks().stream().filter(Task::isDone).count());
    }, new Totals());
    return () -> html(
      head(title("Stores · j2act")),
      body(
        h1("Deep stores"),
        p("Plain Java classes, lists, maps and sets in a store: a write goes through update(d -> ...) on a draft of"
          + " the same classes, and only what it changed renders again. Each row counts its renders.").withClass("muted"),

        section(
          h2("1 · Beans, rows and one property"),
          p("Tick or rename one task: only its row renders. The owner field is bound to board.at(Board::getOwner,"
            + " Person::getName), one property deep in the store.").withClass("muted"),
          new OwnerField().with(board),
          new TaskList().with(board),
          button("Add a task").withId("stores-add")
            .onClick(e -> board.update(d -> d.getTasks().add(new Task("Task " + (d.getTasks().size() + 1))))),
          button("Tick all").onClick(e -> board.update(d -> d.getTasks().forEach(t -> t.setDone(true)))),
          button("Undo all").onClick(e -> board.update(d -> d.getTasks().forEach(t -> t.setDone(false))))
        ),

        section(
          h2("2 · A writable derived store"),
          p("createStore(d -> ..., new Totals()) works the totals out from the board. Overwrite them by hand: that"
            + " stands until the board changes, and the derive runs again.").withClass("muted"),
          new TotalsLine().with(totals),
          button("Pretend 99 are open").onClick(e -> totals.update(d -> d.setOpen(99)))
        ),

        section(h2("3 · Sets and maps"), new SetsAndMaps()),
        section(h2("4 · A projection: rows from a load, merged by key"), new Quotes()),
        section(h2("5 · An optimistic store"), new Comments()),
        section(h2("6 · A shallow store of records"), new Ticker()),
        section(h2("7 · Loading values"), new Forecast()),
        section(h2("8 · A retained store"), new Profile())
      )
    );
  }

  // ---- 1: beans

  static final class OwnerField extends ComponentTag {
    private final Prop<Stored<Board>> board = prop();

    OwnerField with(Stored<Board> store) {
      board.set(store);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        var owner = board.get().at(Board::getOwner, Person::getName);
        return p(
          span("Owner: "),
          input().withId("stores-owner").withAutocomplete("off").withValue(owner.get())
            .onInput(e -> owner.set(e.value())),
          span(" · " + owner.get() + " owns " + board.get().get().getTitle()));
      };
    }
  }

  /** Reads only how many tasks there are: adding one renders the list, ticking one does not. */
  static final class TaskList extends ComponentTag {
    private final Prop<Stored<Board>> board = prop();

    TaskList with(Stored<Board> store) {
      board.set(store);
      return this;
    }

    @Override protected View setup() {
      return () -> div(each(IntStream.range(0, board.get().at(Board::getTasks).get().size())
        .mapToObj(i -> new TaskRow().with(board.get(), i).withKey(i)))).withId("stores-tasks");
    }
  }

  static final class TotalsLine extends ComponentTag {
    private final Prop<Stored<Totals>> totals = prop();

    TotalsLine with(Stored<Totals> store) {
      totals.set(store);
      return this;
    }

    @Override protected View setup() {
      return () -> p("open " + totals.get().get().getOpen() + " · done " + totals.get().get().getDone())
        .withId("stores-totals");
    }
  }

  static final class TaskRow extends ComponentTag {
    private final Prop<Stored<Board>> board = prop();
    private final Prop<Integer> index = prop();
    private int renders;

    TaskRow with(Stored<Board> store, int i) {
      board.set(store);
      index.set(i);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        int i = index.get();
        Task task = board.get().at(Board::getTasks).get().get(i);
        renders++;
        return div(
          input().withType("checkbox").withCondChecked(task.isDone())
            .onChange(e -> board.get().update(d -> d.getTasks().get(i).setDone(!d.getTasks().get(i).isDone()))),
          span(" " + task.getTitle() + (task.isDone() ? " ✓" : "")),
          button("rename").onClick(e -> board.get().update(d -> d.getTasks().get(i).setTitle(task.getTitle() + "!"))),
          span(" rendered " + renders + "×").withClass("muted"));
      };
    }
  }

  // ---- 3: sets and maps

  static final class SetsAndMaps extends ComponentTag {
    private static final List<String> LABELS = List.of("urgent", "backend", "frontend", "docs");

    @Override protected View setup() {
      Stored<Set<String>> picked = createStore(new LinkedHashSet<String>());
      Stored<Map<String, Integer>> votes = createStore(new LinkedHashMap<String, Integer>());
      return () -> div(
        p("A Set: each chip reads contains(label), the subscription to that one element.").withClass("muted"),
        div(each(LABELS.stream().map(label -> new Chip().with(picked, label).withKey(label)))),
        new Line<>(() -> "picked: " + String.join(", ", picked.get())),
        p("A Map: the vote buttons write one key; the table walks the entries.").withClass("muted"),
        div(each(List.of("tea", "coffee", "water").stream().map(drink -> button("+1 " + drink)
          .withKey(drink)
          .onClick(e -> votes.update(d -> d.merge(drink, 1, Integer::sum)))))),
        new Line<>(() -> votes.get().isEmpty() ? "no votes yet" : votes.get().entrySet().stream()
          .map(en -> en.getKey() + ": " + en.getValue()).collect(Collectors.joining(" · "))),
        button("Clear votes").onClick(e -> votes.update(Map::clear)));
    }
  }

  /** A line of text worked out from a store, as a component of its own, so only it renders when that changes. */
  static final class Line<T> extends ComponentTag {
    private final java.util.function.Supplier<String> text;

    Line(java.util.function.Supplier<String> text) {
      this.text = text;
    }

    @Override protected View setup() {
      return () -> p(text.get());
    }
  }

  static final class Chip extends ComponentTag {
    private final Prop<Stored<Set<String>>> picked = prop();
    private final Prop<String> label = prop();
    private int renders;

    Chip with(Stored<Set<String>> store, String l) {
      picked.set(store);
      label.set(l);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        String l = label.get();
        boolean on = picked.get().get().contains(l);
        renders++;
        return button((on ? "● " : "○ ") + l + " (" + renders + "×)")
          .onClick(e -> picked.get().update(d -> {
            if (!d.remove(l)) {
              d.add(l);
            }
          }));
      };
    }
  }

  // ---- 4: a projection

  /** Stands in for a database: each reload moves one price, and the rows come back as new objects. */
  static final List<Quote> MARKET = new CopyOnWriteArrayList<>(List.of(
    new Quote("ACME", 100), new Quote("GLOBEX", 42), new Quote("INITECH", 7), new Quote("UMBRELLA", 13)));

  static List<Quote> load() throws InterruptedException {
    Thread.sleep(1200);
    int i = ThreadLocalRandom.current().nextInt(MARKET.size());
    Quote q = MARKET.get(i);
    MARKET.set(i, new Quote(q.getSymbol(), q.getPrice() + ThreadLocalRandom.current().nextInt(-5, 6)));
    List<Quote> fresh = new ArrayList<>();
    for (Quote m : MARKET) {
      fresh.add(new Quote(m.getSymbol(), m.getPrice()));
    }
    return fresh;
  }

  static final class Quotes extends ComponentTag {
    @Override protected View setup() {
      var quotes = createProjection(() -> async(StoresPage::load), Quote::getSymbol,
        List.of(new Quote("loading…", 0), new Quote("loading…", 0)));
      var reload = action(() -> refresh(quotes).join());
      return () -> div(
        p("createProjection(load, Quote::getSymbol, skeletonRows): the skeleton rows show while the first load is out,"
          + " with no loading fallback. Each reload brings every row back as a new object, one price moved: the merge"
          + " by key keeps the others, which do not render again.").withClass("muted"),
        new QuoteList().with(quotes),
        new Line<>(() -> isPending(quotes) ? "Reloading…" : ""),
        button("Reload").onClick(e -> reload.run()));
    }
  }

  static final class QuoteList extends ComponentTag {
    private final Prop<Projection<Quote>> quotes = prop();

    QuoteList with(Projection<Quote> p) {
      quotes.set(p);
      return this;
    }

    @Override protected View setup() {
      return () -> div(each(IntStream.range(0, quotes.get().get().size())
        .mapToObj(i -> new QuoteRow().with(quotes.get(), i).withKey(i))));
    }
  }

  static final class QuoteRow extends ComponentTag {
    private final Prop<Projection<Quote>> quotes = prop();
    private final Prop<Integer> index = prop();
    private int renders;

    QuoteRow with(Projection<Quote> p, int i) {
      quotes.set(p);
      index.set(i);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        Quote q = quotes.get().get().get(index.get());
        renders++;
        return div(span(q.getSymbol() + " " + q.getPrice()), span(" rendered " + renders + "×").withClass("muted"));
      };
    }
  }

  // ---- 5: an optimistic store

  static final List<Comment> SAVED = new CopyOnWriteArrayList<>(List.of(new Comment(1, "First!")));

  static final class Comments extends ComponentTag {
    @Override protected View setup() {
      var comments = createOptimisticStore(() -> async(() -> {
        Thread.sleep(300);
        return new ArrayList<>(SAVED);
      }), Comment::getId, List.of());
      var failNext = createSignal(false);
      var error = createSignal("");
      var post = action((String text) -> {
        comments.update(d -> d.add(new Comment(-1, text + " (saving…)")));
        Thread.sleep(1500);
        if (failNext.get()) {
          throw new IllegalStateException("the server said no");
        }
        SAVED.add(new Comment(SAVED.size() + 1, text));
        refresh(comments).join();
        return null;
      });
      var count = createSignal(1);
      return () -> div(
        p("createOptimisticStore(load, Comment::getId, List.of()): the comment shows at once while the save is out, and is"
          + " replaced by the saved one when the reload lands. Make the save fail and the guess goes away.")
          .withClass("muted"),
        div(each(comments.get().stream().map(c -> p(c.getText()).withKey(c.getId())))),
        button("Post a comment").onClick(e -> {
          error.set("");
          int n = count.get() + 1;
          count.set(n);
          post.run("Comment " + n).exceptionally(t -> {
            error.set("Save failed: " + (t.getCause() != null ? t.getCause().getMessage() : t.getMessage()));
            return null;
          });
        }),
        label(input().withType("checkbox").withCondChecked(failNext.get())
          .onChange(e -> failNext.set(!failNext.get())), span(" make the next save fail")),
        p(error.get()).withClass("muted"));
    }
  }

  // ---- 6: a shallow store

  public record Price(String symbol, int cents) {
  }

  static final class Ticker extends ComponentTag {
    @Override protected View setup() {
      List<Price> given = List.of(new Price("EUR", 108), new Price("GBP", 127), new Price("JPY", 1));
      var prices = createShallowStore(new ArrayList<>(given));
      return () -> div(
        p("createShallowStore(list): each slot is reactive and a row is served as it is, by reference: these are"
          + " immutable records, replaced with d.set(i, row), never copied or looked into.").withClass("muted"),
        new PriceList().with(prices, given),
        button("Tick one").onClick(e -> prices.update(d -> {
          int i = ThreadLocalRandom.current().nextInt(d.size());
          d.set(i, new Price(d.get(i).symbol(), d.get(i).cents() + 1));
        })));
    }
  }

  static final class PriceList extends ComponentTag {
    private final Prop<Stored<List<Price>>> prices = prop();
    private final Prop<List<Price>> given = prop();

    PriceList with(Stored<List<Price>> p, List<Price> g) {
      prices.set(p);
      given.set(g);
      return this;
    }

    @Override protected View setup() {
      return () -> div(each(IntStream.range(0, prices.get().get().size())
        .mapToObj(i -> new PriceRow().with(prices.get(), given.get(), i).withKey(i))));
    }
  }

  static final class PriceRow extends ComponentTag {
    private final Prop<Stored<List<Price>>> prices = prop();
    private final Prop<List<Price>> given = prop();
    private final Prop<Integer> index = prop();
    private int renders;

    PriceRow with(Stored<List<Price>> p, List<Price> g, int i) {
      prices.set(p);
      given.set(g);
      index.set(i);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        Price p = prices.get().get().get(index.get());
        renders++;
        return div(span(p.symbol() + " " + p.cents() + "¢"),
          span(" · the record given: " + (p == given.get().get(index.get()))).withClass("muted"),
          span(" · rendered " + renders + "×").withClass("muted"));
      };
    }
  }

  // ---- 7: loading values

  static final class Forecast extends ComponentTag {
    @Override protected View setup() {
      var sky = createMemo(() -> async(() -> {
        Thread.sleep(2500);
        return "sunny, 21°";
      }), "checking the sky…");
      var plain = createMemo(() -> async(() -> {
        Thread.sleep(2500);
        return "sunny, 21°";
      }));
      // seedLoadingValue: the seed shows while the derive waits on the sky.
      var banner = createStore(d -> d.setLine("Today: " + sky.get()), new Banner(), true);
      return () -> div(
        p("createMemo(load, \"checking the sky…\") shows its loading value until the first answer lands, with nothing"
          + " stopping at loading(...). The banner is a derived store with seedLoadingValue. The last line has no"
          + " loading value, so it shows its fallback. Reload the page to see it again.").withClass("muted"),
        p("Sky: " + sky.get()),
        p(banner.get().getLine()),
        loading(() -> p("Without a loading value: " + plain.get())).withFallback(p("(fallback while loading)")));
    }
  }

  // ---- 8: a retained store

  static final class Profile extends ComponentTag {
    @Override protected View setup() {
      Stored<Card> card = createRetainedStore(new Card());
      return () -> {
        var name = card.at(Card::getName);
        return div(
          p("createRetainedStore(new Card()): the store goes into the tab's snapshot, so what you type here is still"
            + " here after the server evicts the tab or restarts. restored() says whether it came back.")
            .withClass("muted"),
          p(card.restored() ? "Restored from a snapshot." : "Fresh: nothing was restored.").withId("stores-restored"),
          input().withId("stores-name").withAutocomplete("off").withValue(name.get()).withPlaceholder("Your name")
            .onInput(e -> name.set(e.value())),
          div(each(List.of("hiking", "chess", "cooking").stream().map(hobby -> button(
            (card.get().getHobbies().contains(hobby) ? "● " : "○ ") + hobby).withKey(hobby)
            .onClick(e -> card.update(d -> {
              if (!d.getHobbies().remove(hobby)) {
                d.getHobbies().add(hobby);
              }
            }))))));
      };
    }
  }

  // ---- the classes the stores hold: plain beans, with a no-argument constructor

  public static final class Board {
    private String title;
    private Person owner;
    private List<Task> tasks = new ArrayList<>();

    Board() {
    }

    Board(String title, Person owner, List<Task> tasks) {
      this.title = title;
      this.owner = owner;
      this.tasks = new ArrayList<>(tasks);
    }

    public String getTitle() {
      return title;
    }

    public Person getOwner() {
      return owner;
    }

    public List<Task> getTasks() {
      return tasks;
    }
  }

  public static final class Person {
    private String name;

    Person() {
    }

    Person(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }
  }

  public static final class Task {
    private String title;
    private boolean done;

    Task() {
    }

    Task(String title) {
      this.title = title;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public boolean isDone() {
      return done;
    }

    public void setDone(boolean done) {
      this.done = done;
    }
  }

  public static final class Totals {
    private int open;
    private int done;

    public int getOpen() {
      return open;
    }

    public void setOpen(int open) {
      this.open = open;
    }

    public int getDone() {
      return done;
    }

    public void setDone(int done) {
      this.done = done;
    }
  }

  public static final class Quote {
    private String symbol;
    private int price;

    Quote() {
    }

    Quote(String symbol, int price) {
      this.symbol = symbol;
      this.price = price;
    }

    public String getSymbol() {
      return symbol;
    }

    public int getPrice() {
      return price;
    }
  }

  public static final class Comment {
    private long id;
    private String text;

    Comment() {
    }

    Comment(long id, String text) {
      this.id = id;
      this.text = text;
    }

    public long getId() {
      return id;
    }

    public String getText() {
      return text;
    }
  }

  public static final class Banner {
    private String line = "Today: …";

    public String getLine() {
      return line;
    }

    public void setLine(String line) {
      this.line = line;
    }
  }

  public static final class Card {
    private String name = "";
    private List<String> hobbies = new ArrayList<>();

    public String getName() {
      return name;
    }

    public List<String> getHobbies() {
      return hobbies;
    }
  }
}
