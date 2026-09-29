import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

/**
 * Hot code replace as an IDE's debugger sends it: attaches over JDWP and redefines every loaded
 * class of that name (a redeployed application may leave an older one loaded) with the class file.
 * Used by tools/hotswap-check.mjs; run as a single source file:
 *   java tools/Redefine.java localhost 8787 com.example.Page Page.class
 */
public class Redefine {
  public static void main(String[] args) throws Exception {
    AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors().stream()
      .filter(c -> c.name().equals("com.sun.jdi.SocketAttach")).findFirst().orElseThrow();
    Map<String, Connector.Argument> arguments = socket.defaultArguments();
    arguments.get("hostname").setValue(args[0]);
    arguments.get("port").setValue(args[1]);
    VirtualMachine vm = socket.attach(arguments);
    try {
      List<ReferenceType> classes = vm.classesByName(args[2]);
      byte[] bytes = Files.readAllBytes(Paths.get(args[3]));
      Map<ReferenceType, byte[]> redefinitions = new HashMap<>();
      for (ReferenceType type : classes) {
        redefinitions.put(type, bytes);
      }
      vm.redefineClasses(redefinitions);
      System.out.println("redefined " + classes.size() + " loaded class(es) " + args[2]);
    } catch (RuntimeException | LinkageError e) {
      System.out.println("refused: " + e);
    } finally {
      vm.dispose();
    }
  }
}
