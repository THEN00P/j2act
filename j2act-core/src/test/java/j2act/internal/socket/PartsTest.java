package j2act.internal.socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PartsTest {

  @Test
  void aWholeMessageIsGivenBackAsItIs() {
    Parts parts = new Parts();
    String text = "{\"type\":\"event\"}";
    assertEquals(text, parts.add(text, true, 10));
  }

  @Test
  void partsAreJoined() {
    Parts parts = new Parts();
    assertNull(parts.add("ab", false, 10));
    assertNull(parts.add("cd", false, 10));
    assertEquals("abcde", parts.add("e", true, 10));
    assertEquals("next", parts.add("next", true, 10));
  }

  @Test
  void aMessageOverTheLimitIsGivenOnceJustOverItAndTheRestDropped() {
    Parts parts = new Parts();
    assertNull(parts.add("abcd", false, 5));
    assertEquals("abcdef", parts.add("efgh", false, 5));
    assertNull(parts.add("ijkl", false, 5));
    assertNull(parts.add("mn", true, 5));
    assertEquals("ok", parts.add("ok", true, 5));
  }
}
