package j2act.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The WebIDL reader against the vendored curated IDL. */
class WebIdlTest {

  private static final WebIdl.Model MODEL = WindowApi.readIdl();

  private static WebIdl.Member member(String iface, String name) {
    return MODEL.interfaces.get(iface).members.stream().filter(m -> m.name.equals(name)).findFirst()
      .orElseThrow(() -> new AssertionError(iface + "." + name + " missing"));
  }

  @Test
  void partialsFromOtherSpecsMergeIntoTheirInterface() {
    assertEquals("Clipboard", member("Navigator", "clipboard").type.name);
    assertEquals("Promise", member("Navigator", "share").type.name);
    assertEquals("Promise", member("Element", "requestFullscreen").type.name);
    assertTrue(MODEL.includes.get("Navigator").contains("NavigatorID"), MODEL.includes.get("Navigator").toString());
  }

  @Test
  void readsArgumentsTypesAndSpecialOperations() {
    WebIdl.Member current = member("Geolocation", "getCurrentPosition");
    assertEquals(List.of("successCallback", "errorCallback", "options"),
      current.args.stream().map(a -> a.name).collect(Collectors.toList()));
    assertFalse(current.args.get(0).optional);
    assertTrue(current.args.get(1).optional && current.args.get(1).type.nullable);
    assertEquals("DOMString", member("Storage", "getItem").type.name);
    assertTrue(member("Storage", "getItem").type.nullable);
    assertTrue(member("Storage", "length").readonly);
    assertTrue(member("Notification", "requestPermission").isStatic);
    WebIdl.Member scroll = member("Element", "scrollIntoView");
    assertTrue(scroll.args.get(0).type.isUnion(), scroll.args.get(0).type.toString());
  }

  @Test
  void readsDictionariesEnumsCallbacksAndTypedefs() {
    assertEquals(List.of("files", "title", "text", "url"),
      MODEL.dictionaries.get("ShareData").fields.stream().map(f -> f.name).collect(Collectors.toList()));
    assertTrue(MODEL.dictionaries.get("PermissionDescriptor").fields.get(0).required);
    assertEquals(List.of("granted", "denied", "prompt"), MODEL.enums.get("PermissionState"));
    assertEquals("GeolocationPosition", MODEL.callbacks.get("PositionCallback").args.get(0).type.name);
    assertEquals("unsigned long long", MODEL.resolve(new WebIdl.Type("EpochTimeStamp")).name);
  }
}
