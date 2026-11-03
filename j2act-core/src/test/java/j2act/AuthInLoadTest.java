package j2act;

import static j2act.Reactive.async;
import static j2act.Reactive.auth;
import static j2act.Reactive.createMemo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

/** The first auth() of a tab can come from a load, on a thread that is not the lane. */
class AuthInLoadTest {

  static final class Greets extends LiveComponent {
    @Override protected View setup() {
      var name = createMemo(() -> async(() -> auth().name()));
      return () -> T.page("t", T.span("hello " + name.get() + ";"));
    }
  }

  @Test
  void aLoadCanBeTheFirstToAskWhoThisIs() {
    try (Harness h = new Harness(Greets::new, b -> b.withIdentity(ex -> AuthCtx.of("ada", Set.of())))) {
      for (int i = 0; i < 200; i++) {
        ServeResult page = h.engine.serve("/", Exchange.empty());
        assertEquals(200, page.status());
        assertTrue(page.html().contains("hello ada;"), page.html());
      }
    }
  }
}
