package j2act;

import java.util.Objects;

/** Escaped text. There is no way to turn escaping off here; raw markup goes through UnsafeHtml (ADR 0008). */
public final class Text implements DomContent {

  final String text;

  public Text(String text) {
    this.text = Objects.toString(text, "");
  }
}
