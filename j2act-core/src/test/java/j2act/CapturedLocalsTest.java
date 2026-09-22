package j2act;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * ADR 0020 open question: can the runtime read a loader lambda's captured values, to
 * re-run when a captured local changes? This checks it on whatever JDK runs the test.
 */
class CapturedLocalsTest {

  @Test
  void capturedValuesOfALambdaAreReadableReflectively() throws Exception {
    long id = 42L;
    String name = "x";
    Object self = this;
    Loader<String> loader = () -> name + id + self.hashCode();

    List<Object> captured = new ArrayList<>();
    for (Field field : loader.getClass().getDeclaredFields()) {
      field.setAccessible(true);
      captured.add(field.get(loader));
    }
    assertTrue(captured.contains(42L), captured::toString);
    assertTrue(captured.contains("x"), captured::toString);
    assertTrue(captured.contains(self), captured::toString);
  }
}
