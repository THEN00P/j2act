package transactions;

import static j2act.html.TagCreator.*;

import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import j2act.ComponentTag;
import j2act.Mutation;
import j2act.State;
import j2act.Store;
import j2act.Tag;
import j2act.TabScoped;

public class Orders {

  @TabScoped
  public static class Cart extends Store {
    @Inject Cart self;
    final State<Integer> count = state(0);
    final Mutation<String, String> viaLambda = mutation((String item) -> save(item)); // expect: save() is transactional
    final Mutation<String, String> viaReference = mutation(this::save); // expect: save() is transactional
    final Mutation<String, String> viaSelf = mutation((String item) -> self.save(item));

    @Transactional
    public String save(String item) {
      return item;
    }

    public String saveTwice(String item) {
      this.save(item); // expect: save() is transactional
      return plain(item);
    }

    private String plain(String item) {
      return item;
    }
  }

  /** Overloads: only the one a call selects counts. */
  @TabScoped
  public static class Drafts extends Store {
    @Transactional
    public void keep() {
    }

    public void keep(String note) {
    }

    @Transactional
    public void tag(int priority) {
    }

    public void tag(String label) {
    }

    public void edit(String note) {
      keep(note);
      keep(); // expect: keep() is transactional
      tag("urgent");
      tag(note);
      tag(1); // expect: tag() is transactional
      int priority = 2;
      tag(priority); // expect: tag() is transactional
      tag(level); // expect: tag() is transactional
    }

    private final int level = 3;

    @Transactional
    public void file(String name) {
    }

    public void file(Object anything) {
    }

    public void sort(String name, Object anything) {
      file("x"); // expect: file() is transactional
      file(anything);
    }

    @Transactional
    public void stash(String... names) {
    }

    public void stash(Object anything) {
    }

    public void tidy() {
      stash("x");
      stash("x", "y"); // expect: stash() is transactional
    }

    @Transactional
    public void count(long n) {
    }

    public void count(Integer n) {
    }

    public void tally() {
      count(1); // expect: count() is transactional
    }

    @Transactional
    public void label(String first, String... rest) {
    }

    public void label(Object... any) {
    }

    public void relabel() {
      label("a", "b"); // expect: label() is transactional
    }

    final Mutation<String, String> viaReference = mutation((String note) -> {
      java.util.function.Consumer<String> later = this::keep;
      later.accept(note);
      return note;
    });
  }

  @TabScoped
  @org.springframework.transaction.annotation.Transactional
  public static class Ledger extends Store {
    public void book() {
    }

    public void bookAll() {
      book(); // expect: book() is transactional
    }
  }

  /** A subclass reaching its parent's transactional method through super skips the proxy too. */
  @TabScoped
  public static class Archive extends Cart {
    @Override
    public String save(String item) {
      return super.save(item); // expect: save() is transactional
    }

    final Mutation<String, String> viaSuperReference = mutation(super::save); // expect: save() is transactional
  }

  /** A component is never proxied, so its own transactional methods never start one either. */
  public static class Checkout extends ComponentTag {
    @Transactional
    void pay() {
    }

    @Override protected Tag<?> render() {
      return button("pay").onClick(e -> pay()); // expect: pay() is transactional
    }
  }

  /** Not a store or a component: the container's own rules apply, so nothing is reported. */
  public static class Service {
    @Transactional
    public void a() {
    }

    public void b() {
      a();
    }
  }
}
