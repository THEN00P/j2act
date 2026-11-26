package fixtures;

import static j2act.Reactive.*;

import j2act.Reactive;
import j2act.SharedScoped;
import j2act.Signal;
import j2act.Store;
import j2act.TabScoped;

/** ADR 0037: a shared store's code runs once for every reader, so it cannot read what differs between them. */
public class SharedStores {

  @TabScoped
  public static class Sidebar extends Store {
    final Signal<Boolean> open = createSignal(false);
  }

  @SharedScoped
  public static class Room extends Store {
    final Signal<String> topic = createSignal("");
    final Signal<String> who = createSignal("");

    public Room() {
      createEffect(() -> topic.get(), value -> {
        who.set(auth().name()); // expect: auth() differs between the readers of a @SharedScoped store
        String id = Reactive.pathParam("id"); // expect: pathParam() differs between the readers
        useTabStore(Sidebar.class); // expect: useTabStore() differs between the readers
        useSharedStore(Lobby.class);
        return null;
      });
    }
  }

  /** Inherited: a subclass of a shared store is one too. */
  public static class Annex extends Room {
    public Annex() {
      createEffect(() -> 1, value -> {
        useTab(); // expect: useTab() differs between the readers
        return null;
      });
    }
  }

  @SharedScoped
  public static class Lobby extends Store {
    final Signal<Integer> count = createSignal(0);
  }

  /** A tab store may read all of it. */
  @TabScoped
  public static class Mine extends Store {
    public Mine() {
      createEffect(() -> 1, value -> {
        auth();
        pathParam("id");
        return null;
      });
    }
  }
}
