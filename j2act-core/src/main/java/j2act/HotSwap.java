package j2act;

import java.lang.management.ManagementFactory;
import java.util.List;

import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

/**
 * Dev mode's HotSwap hint. A JVM's standard HotSwap swaps method bodies only, and nearly every
 * edit to a component adds or removes a lambda (a handler, a computed, an effect), which the JVM
 * then refuses. JetBrains Runtime with -XX:+AllowEnhancedClassRedefinition takes those edits too.
 * Logged once in dev mode while a debugger is attached, since only a debugger swaps classes.
 */
final class HotSwap {

  static final String FLAG = "AllowEnhancedClassRedefinition";
  /** JetBrains Runtime 11 had enhanced redefinition only in its DCEVM build, and this is its last release. */
  static final String JBR_11 = "https://github.com/JetBrains/JetBrainsRuntime/releases/tag/jbr11_0_16b2043.64";

  private HotSwap() {
  }

  static void check(J2Act engine) {
    if (!debugging()) {
      return;
    }
    String hint = hint(System.getProperty("java.vm.vendor", ""), Runtime.version().feature(), enhanced());
    if (hint != null) {
      engine.log(System.Logger.Level.WARNING, hint, null);
    }
  }

  /**
   * The hint for a JVM, or null when it already swaps added methods and fields.
   *
   * @param enhanced the flag's value, or null when this JVM does not know it
   */
  static String hint(String vmVendor, int major, Boolean enhanced) {
    if (Boolean.TRUE.equals(enhanced)) {
      return null;
    }
    String options = "-XX:+" + FLAG + " in its JVM options (WildFly: JAVA_OPTS in bin/standalone.conf;"
      + " an IDE's server or run configuration: its VM arguments)";
    String refused = "edits that add a handler, lambda, method or field";
    if (vmVendor.contains("JetBrains")) {
      if (enhanced == null) {
        return "j2act dev: this JetBrains Runtime build lacks enhanced class redefinition, so HotSwap refuses "
          + refused + ". Its DCEVM build has it: " + JBR_11 + "; run it with " + options + ".";
      }
      return "j2act dev: HotSwap refuses " + refused + " until the server runs with " + options
        + ". This JetBrains Runtime supports it.";
    }
    String download = major == 11
      ? JBR_11 + " (the DCEVM build)"
      : "https://github.com/JetBrains/JetBrainsRuntime/releases?q=jbr-release-" + major + "&expanded=true";
    return "j2act dev: HotSwap on this JVM swaps method bodies only, so " + refused + " need a restart."
      + " JetBrains Runtime " + major + " swaps those too: run the server on it while developing, with "
      + options + ". Download: " + download;
  }

  /** A JDWP agent in the JVM's options: a debugger can attach and swap classes. */
  static boolean debugging() {
    try {
      List<String> arguments = ManagementFactory.getRuntimeMXBean().getInputArguments();
      for (String argument : arguments) {
        if (argument.contains("jdwp")) {
          return true;
        }
      }
    } catch (RuntimeException | LinkageError e) {
      // No management API: say nothing.
    }
    String toolOptions = System.getenv("JAVA_TOOL_OPTIONS");
    return toolOptions != null && toolOptions.contains("jdwp");
  }

  /** -XX:+AllowEnhancedClassRedefinition as the VM reads it, however it was set; null when unknown to this VM. */
  static Boolean enhanced() {
    try {
      CompositeData option = (CompositeData) ManagementFactory.getPlatformMBeanServer().invoke(
        new ObjectName("com.sun.management:type=HotSpotDiagnostic"), "getVMOption",
        new Object[] {FLAG}, new String[] {String.class.getName()});
      return Boolean.valueOf(String.valueOf(option.get("value")));
    } catch (Exception | LinkageError e) {
      return null;
    }
  }
}
