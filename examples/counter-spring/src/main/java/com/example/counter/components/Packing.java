package com.example.counter.components;

import static j2act.Reactive.*;
import static j2act.html.TagCreator.*;

import java.util.ArrayList;
import java.util.List;

import j2act.ComponentTag;
import j2act.Prop;
import j2act.Stored;
import j2act.View;

/**
 * A store over plain classes (ADR 0035): the trip and its items are ordinary beans, a write is a change to a
 * draft of the same class, the destination field is bound to one property, and each item is a row that reads
 * only itself, so ticking one renders that row and nothing else.
 */
public final class Packing extends ComponentTag {

  public static Packing packing() {
    return new Packing();
  }

  @Override protected View setup() {
    var trip = createStore(new Trip("Wien", List.of(new Item("Passport"), new Item("Charger"))));
    var items = trip.at(Trip::getItems);
    return () -> div(
      new Destination().with(trip),
      // Only the number of items is read here, so this view runs again when one is added, not when one is ticked.
      div(each(java.util.stream.IntStream.range(0, items.get().size())
        .mapToObj(i -> new PackingRow().with(trip, i).withKey(i)))).withId("packing-rows"),
      button("Add socks").withId("packing-add")
        .onClick(e -> trip.update(d -> d.getItems().add(new Item("Socks " + d.getItems().size())))),
      button("Pack everything").withId("packing-all")
        .onClick(e -> trip.update(d -> d.getItems().forEach(item -> item.setPacked(true))))
    );
  }

  /** The destination, bound to one property of the store: typing here renders this and no row. */
  static final class Destination extends ComponentTag {
    private final Prop<Stored<Trip>> trip = prop();

    Destination with(Stored<Trip> store) {
      trip.set(store);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        var destination = trip.get().at(Trip::getDestination);
        return div(
          input()
            .withId("packing-destination")
            .withAutocomplete("off")
            .withValue(destination.get())
            .onInput(e -> destination.set(e.value())),
          p("Packing for " + destination.get()).withId("packing-title"));
      };
    }
  }

  /** One item, reading its own row of the store. */
  static final class PackingRow extends ComponentTag {
    private final Prop<Stored<Trip>> trip = prop();
    private final Prop<Integer> index = prop();
    private int renders;

    PackingRow with(Stored<Trip> store, int i) {
      trip.set(store);
      index.set(i);
      return this;
    }

    @Override protected View setup() {
      return () -> {
        int i = index.get();
        Item item = trip.get().at(Trip::getItems).get().get(i);
        renders++;
        return label(
          input().withType("checkbox").withCondChecked(item.isPacked())
            .onChange(e -> trip.get().update(d -> d.getItems().get(i).setPacked(!d.getItems().get(i).isPacked()))),
          span(item.getName() + (item.isPacked() ? " ✓" : "")),
          span(" (rendered " + renders + "×)")
        ).withClass("packing-row");
      };
    }
  }

  public static final class Trip {
    private String destination;
    private List<Item> items = new ArrayList<>();

    Trip() {
    }

    Trip(String destination, List<Item> items) {
      this.destination = destination;
      this.items = new ArrayList<>(items);
    }

    public String getDestination() {
      return destination;
    }

    public List<Item> getItems() {
      return items;
    }
  }

  public static final class Item {
    private String name;
    private boolean packed;

    Item() {
    }

    Item(String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    public boolean isPacked() {
      return packed;
    }

    public void setPacked(boolean packed) {
      this.packed = packed;
    }
  }
}
