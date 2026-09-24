package j2act;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code that knowingly emits raw HTML or a script URL (ADR 0008). The build-time
 * processor stays quiet inside it; the value says why the input is safe.
 *
 * <pre>{@code
 * @AllowUnsafe("markdown is rendered by the OWASP sanitizer")
 * private DomContent body(String markdown) {
 *   return unsafeHtml(sanitizer.render(markdown));
 * }
 * }</pre>
 */
@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.FIELD})
public @interface AllowUnsafe {

  /** Why the raw content is safe here. */
  String value();
}
