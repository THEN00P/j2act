package j2act;

import java.util.Objects;

/** Raw markup emitted as-is. The one loud escape hatch (ADR 0008). */
public final class UnsafeHtml implements DomContent {

  final String html;

  public UnsafeHtml(String html) {
    this.html = Objects.toString(html, "");
  }
}
