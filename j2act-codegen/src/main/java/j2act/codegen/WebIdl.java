package j2act.codegen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A WebIDL reader for the window() facade (ADR 0022): enough of the grammar for webref's
 * curated IDL. Partials merge into their definition and mixins stay separate for the
 * generator to include. Constants, iterables, namespaces and callback interfaces are read
 * past and dropped.
 */
final class WebIdl {

  static final class Type {
    /** "DOMString", "unsigned long long", "Promise", "sequence", a definition name; null for a union. */
    final String name;
    final List<Type> params = new ArrayList<>();
    final List<Type> union = new ArrayList<>();
    boolean nullable;

    Type(String name) {
      this.name = name;
    }

    boolean isUnion() {
      return name == null;
    }

    @Override public String toString() {
      String base = isUnion() ? union.toString() : params.isEmpty() ? name : name + params;
      return nullable ? base + "?" : base;
    }
  }

  static final class Arg {
    final String name;
    final Type type;
    final boolean optional;
    final boolean variadic;

    Arg(String name, Type type, boolean optional, boolean variadic) {
      this.name = name;
      this.type = type;
      this.optional = optional;
      this.variadic = variadic;
    }
  }

  enum Kind { ATTRIBUTE, OPERATION, CONSTRUCTOR }

  static final class Member {
    final Kind kind;
    final String name;
    /** Attribute type or operation return type; null for a constructor. */
    final Type type;
    final List<Arg> args;
    final boolean readonly;
    final boolean isStatic;

    Member(Kind kind, String name, Type type, List<Arg> args, boolean readonly, boolean isStatic) {
      this.kind = kind;
      this.name = name;
      this.type = type;
      this.args = args;
      this.readonly = readonly;
      this.isStatic = isStatic;
    }
  }

  static final class Interface {
    final String name;
    String parent;
    final boolean mixin;
    final List<Member> members = new ArrayList<>();

    Interface(String name, boolean mixin) {
      this.name = name;
      this.mixin = mixin;
    }
  }

  static final class Field {
    final String name;
    final Type type;
    final boolean required;

    Field(String name, Type type, boolean required) {
      this.name = name;
      this.type = type;
      this.required = required;
    }
  }

  static final class Dictionary {
    final String name;
    String parent;
    final List<Field> fields = new ArrayList<>();

    Dictionary(String name) {
      this.name = name;
    }
  }

  static final class Callback {
    final String name;
    final Type returns;
    final List<Arg> args;

    Callback(String name, Type returns, List<Arg> args) {
      this.name = name;
      this.returns = returns;
      this.args = args;
    }
  }

  /** Every definition read so far, partials merged. */
  static final class Model {
    final Map<String, Interface> interfaces = new LinkedHashMap<>();
    final Map<String, Interface> mixins = new LinkedHashMap<>();
    final Map<String, Dictionary> dictionaries = new LinkedHashMap<>();
    final Map<String, List<String>> enums = new LinkedHashMap<>();
    final Map<String, Callback> callbacks = new LinkedHashMap<>();
    final Map<String, Type> typedefs = new LinkedHashMap<>();
    /** Interface to the mixins it includes. */
    final Map<String, List<String>> includes = new LinkedHashMap<>();

    /** Follows typedefs, keeping nullability. */
    Type resolve(Type type) {
      Type seen = type;
      for (int i = 0; i < 16 && seen.name != null && typedefs.containsKey(seen.name); i++) {
        boolean nullable = seen.nullable;
        seen = typedefs.get(seen.name);
        if (nullable && !seen.nullable) {
          Type copy = copy(seen);
          copy.nullable = true;
          seen = copy;
        }
      }
      return seen;
    }

    private static Type copy(Type type) {
      Type out = new Type(type.name);
      out.params.addAll(type.params);
      out.union.addAll(type.union);
      out.nullable = type.nullable;
      return out;
    }
  }

  // ---- reading

  private final List<String> tokens;
  private int pos;
  private final Model model;

  private WebIdl(String source, Model model) {
    this.tokens = tokenize(source);
    this.model = model;
  }

  /** Reads one IDL file into the model. */
  static void read(String source, Model model) {
    new WebIdl(source, model).definitions();
  }

  private void definitions() {
    while (pos < tokens.size()) {
      skipExtendedAttributes();
      accept("partial");
      if (accept("callback")) {
        if (accept("interface")) {
          skipBlock();
          continue;
        }
        String name = next();
        expect("=");
        Type returns = type();
        expect("(");
        List<Arg> args = args();
        expect(";");
        model.callbacks.put(name, new Callback(name, returns, args));
      } else if (accept("interface")) {
        boolean mixin = accept("mixin");
        String name = next();
        Map<String, Interface> target = mixin ? model.mixins : model.interfaces;
        Interface definition = target.computeIfAbsent(name, n -> new Interface(n, mixin));
        if (accept(":")) {
          definition.parent = next();
        }
        members(definition);
      } else if (accept("dictionary")) {
        String name = next();
        Dictionary definition = model.dictionaries.computeIfAbsent(name, Dictionary::new);
        if (accept(":")) {
          definition.parent = next();
        }
        fields(definition);
      } else if (accept("namespace")) {
        next();
        skipBlock();
      } else if (accept("enum")) {
        String name = next();
        expect("{");
        List<String> values = new ArrayList<>();
        while (!accept("}")) {
          String value = next();
          if (value.startsWith("\"")) {
            values.add(value.substring(1, value.length() - 1));
          }
        }
        expect(";");
        model.enums.put(name, values);
      } else if (accept("typedef")) {
        Type type = type();
        model.typedefs.put(next(), type);
        expect(";");
      } else {
        String name = next();
        expect("includes");
        model.includes.computeIfAbsent(name, n -> new ArrayList<>()).add(next());
        expect(";");
      }
    }
  }

  private void members(Interface definition) {
    expect("{");
    while (!accept("}")) {
      skipExtendedAttributes();
      if (peekAny("const", "iterable", "async", "maplike", "setlike", "stringifier")
          || peek("readonly") && peekAt(1, "maplike", "setlike")) {
        skipStatement();
        continue;
      }
      if (accept("constructor")) {
        expect("(");
        definition.members.add(new Member(Kind.CONSTRUCTOR, "constructor", null, args(), false, false));
        expect(";");
        continue;
      }
      boolean isStatic = accept("static");
      accept("inherit");
      boolean readonly = accept("readonly");
      if (accept("attribute")) {
        Type type = type();
        String name = next();
        expect(";");
        definition.members.add(new Member(Kind.ATTRIBUTE, name, type, new ArrayList<>(), readonly, isStatic));
        continue;
      }
      while (peekAny("getter", "setter", "deleter")) {
        next();
      }
      Type returns = type();
      if (peek("(")) {
        // An unnamed special operation: only reachable through indexing, so dropped.
        skipStatement();
        continue;
      }
      String name = next();
      expect("(");
      definition.members.add(new Member(Kind.OPERATION, name, returns, args(), false, isStatic));
      expect(";");
    }
    expect(";");
  }

  private void fields(Dictionary definition) {
    expect("{");
    while (!accept("}")) {
      skipExtendedAttributes();
      boolean required = accept("required");
      Type type = type();
      String name = next();
      if (accept("=")) {
        skipValue();
      }
      expect(";");
      definition.fields.add(new Field(name, type, required));
    }
    expect(";");
  }

  /** After "(": the arguments and the closing ")". */
  private List<Arg> args() {
    List<Arg> out = new ArrayList<>();
    while (!accept(")")) {
      skipExtendedAttributes();
      boolean optional = accept("optional");
      Type type = type();
      boolean variadic = accept("...");
      String name = next();
      if (accept("=")) {
        skipValue();
      }
      out.add(new Arg(name, type, optional, variadic));
      accept(",");
    }
    return out;
  }

  private Type type() {
    skipExtendedAttributes();
    Type type;
    if (accept("(")) {
      type = new Type(null);
      do {
        type.union.add(type());
      } while (accept("or"));
      expect(")");
    } else {
      StringBuilder name = new StringBuilder(next());
      while (isPrimitivePart(name.toString()) && peekAny("long", "short", "double", "float")) {
        name.append(' ').append(next());
      }
      type = new Type(name.toString());
      if (accept("<")) {
        do {
          type.params.add(type());
        } while (accept(","));
        expect(">");
      }
    }
    type.nullable = accept("?");
    return type;
  }

  private static boolean isPrimitivePart(String name) {
    return name.equals("unsigned") || name.equals("unrestricted") || name.endsWith("long") || name.endsWith("unsigned long");
  }

  // ---- tokens

  private boolean peek(String token) {
    return pos < tokens.size() && tokens.get(pos).equals(token);
  }

  private boolean peekAt(int offset, String... any) {
    if (pos + offset >= tokens.size()) {
      return false;
    }
    for (String token : any) {
      if (tokens.get(pos + offset).equals(token)) {
        return true;
      }
    }
    return false;
  }

  private boolean peekAny(String... any) {
    return peekAt(0, any);
  }

  private boolean accept(String token) {
    if (peek(token)) {
      pos++;
      return true;
    }
    return false;
  }

  private String next() {
    if (pos >= tokens.size()) {
      throw new IllegalStateException("IDL ended early");
    }
    return tokens.get(pos++);
  }

  private void expect(String token) {
    String got = next();
    if (!got.equals(token)) {
      throw new IllegalStateException("IDL: expected " + token + " but found " + got + " near " + context());
    }
  }

  private String context() {
    return String.join(" ", tokens.subList(Math.max(0, pos - 8), Math.min(tokens.size(), pos + 4)));
  }

  private void skipExtendedAttributes() {
    if (!peek("[")) {
      return;
    }
    int depth = 0;
    do {
      String token = next();
      depth += token.equals("[") ? 1 : token.equals("]") ? -1 : 0;
    } while (depth > 0);
  }

  /** Past a default value, up to the "," ")" or ";" that ends it. */
  private void skipValue() {
    int depth = 0;
    while (depth > 0 || !peekAny(",", ")", ";")) {
      String token = next();
      depth += token.equals("{") || token.equals("[") ? 1 : token.equals("}") || token.equals("]") ? -1 : 0;
    }
  }

  private void skipStatement() {
    int depth = 0;
    while (true) {
      String token = next();
      depth += token.equals("(") || token.equals("<") || token.equals("{") ? 1
        : token.equals(")") || token.equals(">") || token.equals("}") ? -1 : 0;
      if (depth == 0 && token.equals(";")) {
        return;
      }
    }
  }

  /** A { ... }; block, name already read. */
  private void skipBlock() {
    while (!peek("{")) {
      next();
    }
    int depth = 0;
    do {
      String token = next();
      depth += token.equals("{") ? 1 : token.equals("}") ? -1 : 0;
    } while (depth > 0);
    expect(";");
  }

  private static List<String> tokenize(String source) {
    List<String> out = new ArrayList<>();
    int i = 0;
    int n = source.length();
    while (i < n) {
      char c = source.charAt(i);
      if (Character.isWhitespace(c)) {
        i++;
      } else if (source.startsWith("//", i)) {
        while (i < n && source.charAt(i) != '\n') {
          i++;
        }
      } else if (source.startsWith("/*", i)) {
        int end = source.indexOf("*/", i + 2);
        i = end < 0 ? n : end + 2;
      } else if (c == '"') {
        int end = source.indexOf('"', i + 1);
        out.add(source.substring(i, end + 1));
        i = end + 1;
      } else if (source.startsWith("...", i)) {
        out.add("...");
        i += 3;
      } else if (Character.isDigit(c) || c == '-' && i + 1 < n && (Character.isDigit(source.charAt(i + 1))
          || source.charAt(i + 1) == 'I')) {
        // A number: 0xFFFFFFFF, 1.5e3, -Infinity.
        int start = i++;
        while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '.'
            || (source.charAt(i) == '-' || source.charAt(i) == '+') && "eE".indexOf(source.charAt(i - 1)) >= 0)) {
          i++;
        }
        out.add(source.substring(start, i));
      } else if (Character.isLetter(c) || c == '_') {
        int start = i;
        while (i < n && (Character.isLetterOrDigit(source.charAt(i)) || source.charAt(i) == '_')) {
          i++;
        }
        out.add(source.substring(start, i));
      } else {
        out.add(String.valueOf(c));
        i++;
      }
    }
    return out;
  }
}
