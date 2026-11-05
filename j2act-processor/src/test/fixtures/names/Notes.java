package names;

import java.util.ArrayList;
import java.util.List;

import j2act.ComponentTag;
import j2act.View;
import j2act.internal.names.RetainedNames;

/** ADR 0031: the variable names a createRetained call's key is built from, helper chains included. */
public final class Notes extends ComponentTag {

  public static final List<String> KEYS = new ArrayList<>();

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
  }
}
