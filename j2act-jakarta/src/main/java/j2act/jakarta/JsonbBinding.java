package j2act.jakarta;

import java.lang.reflect.Type;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;

import j2act.JsonBinding;

/** JSON-B (Yasson on WildFly) for client module values, as the app's REST endpoints use it (ADR 0022). */
final class JsonbBinding implements JsonBinding {

  private final Jsonb jsonb = JsonbBuilder.create();

  @Override public String write(Object value) {
    return jsonb.toJson(value);
  }

  @Override public Object read(String json, Type type) {
    return jsonb.fromJson(json, type);
  }
}
