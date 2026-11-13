package dev.dguzman.jvminspector.jvm;

/**
 * A JVM discovered on the local machine.
 *
 * @param pid         operating system process id
 * @param displayName main class or jar the JVM was started with
 * @param self        true if this is the JVM Inspector server itself
 */
public record JvmDescriptor(long pid, String displayName, boolean self) {
}

