package dev.dguzman.jvminspector.jvm;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import org.springframework.stereotype.Component;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Comparator;
import java.util.List;

/**
 * Low-level access to local JVMs.
 * <ul>
 *   <li>Discovery uses the JDK Attach API ({@code jdk.attach} module), the same mechanism behind {@code jps}.</li>
 *   <li>Inspection attaches to the target, starts its local JMX agent and reads the platform MXBeans
 *       (memory, threads, GC, runtime) through a {@link MBeanServerConnection}.</li>
 * </ul>
 * A JVM cannot attach to itself by default, so for our own pid we use the in-process MBean server.
 */
@Component
public class JvmConnector {

    private static final int MAX_DISPLAY_NAME = 150;

    private final long selfPid = ProcessHandle.current().pid();

    /** Something to do with a JMX connection to a target JVM. */
    @FunctionalInterface
    public interface JmxCallback<T> {
        T apply(MBeanServerConnection connection) throws IOException;
    }

    public long selfPid() {
        return selfPid;
    }

    public List<JvmDescriptor> listJvms() {
        return VirtualMachine.list().stream()
                .map(d -> {
                    long pid = Long.parseLong(d.id());
                    String name = d.displayName() == null || d.displayName().isBlank() ? "<unknown>" : d.displayName();
                    if (name.length() > MAX_DISPLAY_NAME) {
                        // Some JVMs (IDEs, language servers) have huge command lines; keep the answer readable.
                        name = name.substring(0, MAX_DISPLAY_NAME) + "...";
                    }
                    return new JvmDescriptor(pid, name, pid == selfPid);
                })
                .sorted(Comparator.comparingLong(JvmDescriptor::pid))
                .toList();
    }

    /**
     * Opens a short-lived JMX connection to the JVM with the given pid, runs the callback and closes everything.
     */
    public <T> T withConnection(long pid, JmxCallback<T> callback) {
        try {
            if (pid == selfPid) {
                return callback.apply(ManagementFactory.getPlatformMBeanServer());
            }
            VirtualMachine vm = attach(pid);
            try {
                String jmxUrl = vm.startLocalManagementAgent();
                try (JMXConnector connector = JMXConnectorFactory.connect(new JMXServiceURL(jmxUrl))) {
                    return callback.apply(connector.getMBeanServerConnection());
                }
            } finally {
                vm.detach();
            }
        } catch (IOException e) {
            throw new JvmInspectionException("I/O error while talking to JVM " + pid + ": " + e.getMessage(), e);
        }
    }

    private VirtualMachine attach(long pid) throws IOException {
        try {
            return VirtualMachine.attach(String.valueOf(pid));
        } catch (AttachNotSupportedException e) {
            throw new JvmInspectionException(
                    "Cannot attach to JVM " + pid + " (" + e.getMessage() + "). The process may have exited, "
                            + "belong to another OS user, or have been started with -XX:+DisableAttachMechanism. "
                            + "Use list_jvms to see attachable JVMs.", e);
        }
    }
}
