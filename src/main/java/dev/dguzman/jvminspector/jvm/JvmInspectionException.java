package dev.dguzman.jvminspector.jvm;

/**
 * Thrown when a JVM cannot be inspected. The message is returned to the AI client as a tool error,
 * so it should explain what went wrong and what to try next.
 */
public class JvmInspectionException extends RuntimeException {

    public JvmInspectionException(String message) {
        super(message);
    }

    public JvmInspectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

