package j2act.html;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import j2act.CustomEmptyTag;
import j2act.CustomTag;
import j2act.DomContent;
import j2act.Fragment;
import j2act.Text;
import j2act.UnsafeHtml;
import j2act.html.tags.HtmlTag;
import j2act.html.tags.ScriptTag;
import j2act.html.tags.StyleTag;

/**
 * The non-element half of TagCreator, handwritten. Covers j2html's helpers except
 * rawHtml (see {@link #unsafeHtml}), attrs("#id.class") (params stay children only,
 * ADR 0005) and the *_min file variants.
 */
public abstract class TagHelpers {

  protected TagHelpers() {
  }

  // ---- text

  public static Text text(String text) {
    return new Text(text);
  }

  /** Raw markup, emitted unescaped. Loud on purpose (ADR 0008); j2html calls this rawHtml. */
  public static UnsafeHtml unsafeHtml(String html) {
    return new UnsafeHtml(html);
  }

  /**
   * Joins strings and tags with single spaces into one sentence, without a space
   * before a leading period or comma: join("Hello", b("world"), "!") .
   */
  public static Fragment join(Object... parts) {
    List<DomContent> out = new ArrayList<>();
    for (Object part : parts) {
      if (part == null) {
        continue;
      }
      boolean punctuation = part instanceof String && (((String) part).startsWith(".") || ((String) part).startsWith(","));
      if (!out.isEmpty() && !punctuation) {
        out.add(new Text(" "));
      }
      out.add(part instanceof DomContent ? (DomContent) part : new Text(String.valueOf(part)));
    }
    return new Fragment(out);
  }

  // ---- each

  /** Renders one child per item; key stateful rows with withKey(...) (ADR 0019). */
  public static <T> Fragment each(Collection<T> items, Function<? super T, ? extends DomContent> row) {
    List<DomContent> out = new ArrayList<>();
    if (items != null) {
      for (T item : items) {
        out.add(row.apply(item));
      }
    }
    return new Fragment(out);
  }

  /** Like each, with the zero-based index first. */
  public static <T> Fragment each(Collection<T> items, BiFunction<Integer, ? super T, ? extends DomContent> row) {
    List<DomContent> out = new ArrayList<>();
    if (items != null) {
      int i = 0;
      for (T item : items) {
        out.add(row.apply(i++, item));
      }
    }
    return new Fragment(out);
  }

  public static <K, V> Fragment each(Map<K, V> items, Function<Map.Entry<K, V>, ? extends DomContent> row) {
    return each(items == null ? null : items.entrySet(), row);
  }

  public static <K, V> Fragment each(Map<K, V> items, BiFunction<K, V, ? extends DomContent> row) {
    List<DomContent> out = new ArrayList<>();
    if (items != null) {
      for (Map.Entry<K, V> entry : items.entrySet()) {
        out.add(row.apply(entry.getKey(), entry.getValue()));
      }
    }
    return new Fragment(out);
  }

  public static Fragment each(Stream<? extends DomContent> children) {
    return new Fragment(children.collect(Collectors.toList()));
  }

  public static Fragment each(DomContent... children) {
    return new Fragment(Arrays.asList(children));
  }

  public static <T> List<T> filter(Collection<T> items, Predicate<? super T> keep) {
    return items.stream().filter(keep).collect(Collectors.toList());
  }

  // ---- conditionals (absence is null, ADR 0005)

  public static <T> T iff(boolean condition, T content) {
    return condition ? content : null;
  }

  public static <T, U> T iff(Optional<U> optional, Function<U, T> content) {
    return optional.map(content).orElse(null);
  }

  public static <T> T iffElse(boolean condition, T ifTrue, T ifFalse) {
    return condition ? ifTrue : ifFalse;
  }

  // ---- documents and custom elements

  public static UnsafeHtml document() {
    return new UnsafeHtml("<!DOCTYPE html>");
  }

  /** A standalone document string; inside j2act pages the transport prepends the doctype itself. */
  public static String document(HtmlTag html) {
    return "<!DOCTYPE html>" + html.render();
  }

  /** Any element by name, e.g. a web component. */
  public static CustomTag tag(String name) {
    return new CustomTag(name);
  }

  public static CustomEmptyTag emptyTag(String name) {
    return new CustomEmptyTag(name);
  }

  // ---- classpath files

  public static String fileAsString(String classpathResource) {
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    String path = classpathResource.startsWith("/") ? classpathResource.substring(1) : classpathResource;
    try (InputStream in = loader.getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalArgumentException("no classpath resource " + classpathResource);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static Text fileAsEscapedString(String classpathResource) {
    return new Text(fileAsString(classpathResource));
  }

  /** Inlines a classpath script. The file is trusted app code, emitted as script text. */
  public static ScriptTag scriptWithInlineFile(String classpathResource) {
    return new ScriptTag().withText(fileAsString(classpathResource));
  }

  public static StyleTag styleWithInlineFile(String classpathResource) {
    return new StyleTag().withText(fileAsString(classpathResource));
  }
}
