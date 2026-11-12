package names;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import j2act.ComponentTag;
import j2act.View;
import j2act.internal.names.RetainedNames;

/** ADR 0031: the variable names a createRetained call's key is built from, helper chains included. */
public final class Notes extends ComponentTag {

  public static final List<String> KEYS = new ArrayList<>();

  public static final class Note {
  }

  /** Stands in for Stored. */
  public static final class Box<T> {
  }

  @Override public View setup() {
    var notes = createDraft();
    var summary = createDraft();
    var direct = Keys.createKey();
    KEYS.add(notes);
    KEYS.add(summary);
    KEYS.add(direct);
    Runnable inLambda = () -> {
      var lost = Keys.createKey();
      KEYS.add(lost);
    };
    inLambda.run();
    var one = Keys.createKey(); var two = Keys.createKey();
    KEYS.add(one);
    KEYS.add(two);
    var created = Keys.createTyped(new ArrayList<Note>());
    Box<List<Note>> declared = Keys.createTyped(new ArrayList<>());
    Box<Map<String, List<Note>>> nested = Keys.createTyped(new java.util.HashMap<>());
    var inferred = Keys.createTyped(new ArrayList<>());
    var plain = Keys.createTyped("text");
    return null;
  }

  private static String createDraft() {
    var draft = Keys.createKey();
    return draft;
  }

  /** Stands in for j2act.Reactive: what the chain skips. */
  static final class Keys {
    static String createKey() {
      return RetainedNames.chain(Keys.class);
    }

    @SuppressWarnings("unchecked")
    static <T> T createTyped(Object initial) {
      java.lang.reflect.Type type = RetainedNames.callSiteType(Keys.class);
      TYPED.add(type == null ? null : type.getTypeName());
      return null;
    }
  }

  /** The value types the table recorded, in call order (ADR 0035). */
  public static final List<String> TYPED = new ArrayList<>();
}
