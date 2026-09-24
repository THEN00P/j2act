package fixtures;

import static j2act.html.TagCreator.*;

import java.util.List;

import j2act.ComponentTag;
import j2act.Tag;

public class Keys extends ComponentTag {

  private final List<String> names = List.of("Ada", "Grace");

  @Override protected Tag<?> render() {
    return ul(
      each(names, name -> Row.row().withName(name)), // expect: component in each() has no withKey
      each(names, name -> Row.row().withName(name).withKey(name)),
      each(names, name -> new Row().withName(name)), // expect: component in each() has no withKey
      each(names, name -> li(name)),
      each(names, name -> {
        return row(); // expect: component in each() has no withKey
      }),
      each(names.stream().map(name -> Row.row().withName(name))), // expect: component in Stream.map() has no withKey
      each(names.stream().map(name -> Row.row().withKey(name)))
    );
  }

  private static Row row() {
    return new Row();
  }

  static final class Row extends ComponentTag {
    private String name;

    static Row row() {
      return new Row();
    }

    Row withName(String name) {
      this.name = name;
      return this;
    }

    @Override protected Tag<?> render() {
      return li(name);
    }
  }
}
