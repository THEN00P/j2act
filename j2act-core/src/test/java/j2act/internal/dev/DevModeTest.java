package j2act.internal.dev;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Dev mode turns on only for an app on disk, and must not outlive its application: a redeploy
 * drops the application's class loader, and anything the JVM still references from dev mode
 * would keep that whole deployment alive.
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
        Class<?> type = app.loadClass("j2act.internal.dev.DevMode");
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

  @Test void onlyATokenOnDiskTurnsDevModeOn(@TempDir Path dir) throws IOException {
    Path token = dir.resolve("dev.json");
    Files.writeString(token, "{}");
    assertTrue(DevMode.onDisk(token.toUri().toURL()), "a class folder");
    assertTrue(DevMode.onDisk(vfs(token.toUri().getPath())), "an exploded deployment");
    assertFalse(DevMode.onDisk(new URL("jar:" + dir.resolve("app.jar").toUri() + "!/META-INF/j2act/dev.json")), "a jar");
    assertFalse(DevMode.onDisk(vfs("/content/app.war/WEB-INF/classes/META-INF/j2act/dev.json")), "a WAR file");
  }

  /** WildFly's vfs: URLs, which plain Java has no handler for. */
  private static URL vfs(String path) throws IOException {
    return new URL("vfs", "", -1, path, new java.net.URLStreamHandler() {
      @Override protected java.net.URLConnection openConnection(URL url) {
        throw new UnsupportedOperationException();
      }
    });
  }
}
