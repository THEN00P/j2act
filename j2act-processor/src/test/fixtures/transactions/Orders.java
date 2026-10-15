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

  @TabScoped
  @org.springframework.transaction.annotation.Transactional
  public static class Ledger extends Store {
    public void book() {
    }

    public void bookAll() {
      book(); // expect: book() is transactional
    }
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
