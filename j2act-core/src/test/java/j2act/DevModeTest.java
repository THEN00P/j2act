package j2act;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dev mode must not outlive its application: a redeploy drops the application's class loader,
 * and anything the JVM still references from dev mode would keep that whole deployment alive.
 */
class DevModeTest {

  @TempDir File project;

  @Test void closingRemovesTheShutdownHook() {
    DevMode dev = new DevMode(project);
    dev.registerStopHook();
    Thread hook = dev.stopHook;
    assertNull(hook.getContextClassLoader(), "the hook must not hold the application's class loader");
    dev.close();
    assertNull(dev.stopHook);
    assertFalse(Runtime.getRuntime().removeShutdownHook(hook), "close() already removed the hook");
  }

  @Test void aRedeployedApplicationsClassLoaderCanBeCollected() throws Exception {
    URL classes = DevMode.class.getProtectionDomain().getCodeSource().getLocation();
    List<WeakReference<ClassLoader>> deployments = new ArrayList<>();
    ClassLoader previous = Thread.currentThread().getContextClassLoader();
    for (int i = 0; i < 3; i++) {
      // One deployment: j2act loaded by its own class loader, dev mode started and stopped in it.
      URLClassLoader app = new URLClassLoader(new URL[] {classes}, ClassLoader.getPlatformClassLoader());
      Thread.currentThread().setContextClassLoader(app);
      try {
        Class<?> type = app.loadClass("j2act.DevMode");
        assertNotSame(DevMode.class, type);
        Constructor<?> create = type.getDeclaredConstructor(File.class);
        create.setAccessible(true);
        Object dev = create.newInstance(project);
        Method register = type.getDeclaredMethod("registerStopHook");
        register.setAccessible(true);
        register.invoke(dev);
        ((AutoCloseable) dev).close();
      } finally {
        Thread.currentThread().setContextClassLoader(previous);
      }
      deployments.add(new WeakReference<>(app));
      app.close();
    }
    for (int i = 0; i < 100 && deployments.stream().anyMatch(ref -> ref.get() != null); i++) {
      System.gc();
      Thread.sleep(20);
    }
    assertTrue(deployments.stream().allMatch(ref -> ref.get() == null),
      "an undeployed application's class loader stayed reachable");
  }
}
