package j2act;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import org.junit.jupiter.api.Test;

/** Dev mode's HotSwap hint: which JVM gets which advice. */
class HotSwapTest {

  @Test void aJvmWithEnhancedRedefinitionOnGetsNoHint() {
    assertNull(HotSwap.hint("JetBrains s.r.o.", 21, true));
  }

  @Test void anyOtherJvmGetsJetBrainsRuntimeForItsOwnJavaVersion() {
    String hint = HotSwap.hint("Eclipse Adoptium", 21, null);
    assertTrue(hint.contains("JetBrains Runtime 21 swaps those too") && hint.contains("with -XX:+AllowEnhancedClassRedefinition"), hint);
    assertTrue(hint.contains("https://github.com/JetBrains/JetBrainsRuntime/releases?q=jbr-release-21&expanded=true"), hint);
    assertTrue(HotSwap.hint("Oracle Corporation", 25, null).contains("q=jbr-release-25&"));
  }

  @Test void java11GetsTheLastDcevmBuild() {
    String hint = HotSwap.hint("Eclipse Adoptium", 11, null);
    assertTrue(hint.contains(HotSwap.JBR_11 + " (the DCEVM build"), hint);
  }

  @Test void jetBrainsRuntimeWithTheFlagOffIsToldToTurnItOn() {
    String hint = HotSwap.hint("JetBrains s.r.o.", 17, false);
    assertTrue(hint.contains("until the server runs with -XX:+AllowEnhancedClassRedefinition"), hint);
    assertTrue(hint.contains("JAVA_OPTS in bin/standalone.conf"), hint);
  }

  @Test void aJetBrainsRuntimeBuildWithoutTheFlagIsSentToTheDcevmBuild() {
    String hint = HotSwap.hint("JetBrains s.r.o.", 11, null);
    assertTrue(hint.contains("lacks enhanced class redefinition"), hint);
    assertTrue(hint.contains(HotSwap.JBR_11), hint);
  }

  @Test void aStockJdkDoesNotKnowTheFlag() {
    assumeFalse(System.getProperty("java.vm.vendor").contains("JetBrains"));
    assertNull(HotSwap.enhanced());
  }

  @Test void anotherHotSpotOptionReadsThroughTheSameBean() throws Exception {
    // The same MBean call enhanced() makes, on an option every HotSpot has.
    Object value = ((javax.management.openmbean.CompositeData) java.lang.management.ManagementFactory
      .getPlatformMBeanServer().invoke(new javax.management.ObjectName("com.sun.management:type=HotSpotDiagnostic"),
        "getVMOption", new Object[] {"UseCompressedOops"}, new String[] {String.class.getName()})).get("value");
    assertTrue("true".equals(value) || "false".equals(value), String.valueOf(value));
  }
}
