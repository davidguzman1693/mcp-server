package dev.dguzman.jvminspector.mcp;

import dev.dguzman.jvminspector.jvm.JvmDescriptor;
import dev.dguzman.jvminspector.jvm.JvmInspector;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpTool.McpAnnotations;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The MCP-facing layer: each {@code @McpTool} method becomes a tool the AI can call.
 * <p>
 * The {@code description} texts are what the model reads to decide WHEN and HOW to call a tool,
 * so they matter as much as the code. Return values are serialized to JSON automatically.
 */
@Component
public class JvmInspectorTools {

    private static final int DEFAULT_MAX_FRAMES = 20;
    private static final long DEFAULT_SAMPLE_MILLIS = 1000;
    private static final int DEFAULT_HOT_THREADS = 10;

    private final JvmInspector inspector;

    public JvmInspectorTools(JvmInspector inspector) {
        this.inspector = inspector;
    }

    @McpTool(name = "list_jvms",
            description = "Lists the Java Virtual Machines running on this machine that can be inspected "
                    + "(like the 'jps' command). Returns pid and main class/jar. Call this first to find the pid.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public List<JvmDescriptor> listJvms() {
        return inspector.listJvms();
    }

    @McpTool(name = "jvm_overview",
            description = "General information about a JVM: Java version, vendor, uptime, JVM arguments "
                    + "(e.g. -Xmx, GC flags), CPU count, system load, thread and class counts.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public JvmInspector.Overview jvmOverview(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid) {
        return inspector.overview(pid);
    }

    @McpTool(name = "heap_usage",
            description = "Memory usage of a JVM: heap and non-heap totals plus every memory pool "
                    + "(Eden, Survivor, Old Gen, Metaspace, Code Cache...). Use it to spot memory pressure or leaks.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public JvmInspector.MemoryReport heapUsage(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid) {
        return inspector.memory(pid);
    }

    @McpTool(name = "gc_stats",
            description = "Garbage collector statistics: collectors in use, number of collections, total pause time "
                    + "and the percentage of uptime spent in GC (above ~5% usually indicates memory pressure).",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public JvmInspector.GcReport gcStats(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid) {
        return inspector.gc(pid);
    }

    @McpTool(name = "thread_dump",
            description = "Takes a thread dump (like 'jstack'): every thread with its state, stack trace and held locks. "
                    + "Can be filtered by thread state to keep the output small.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public String threadDump(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid,
            @McpToolParam(required = false,
                    description = "Only include threads in this state: NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED")
            String state,
            @McpToolParam(required = false, description = "Maximum stack frames per thread (default 20)")
            Integer maxFrames) {
        return inspector.threadDump(pid, state, maxFrames == null || maxFrames <= 0 ? DEFAULT_MAX_FRAMES : maxFrames);
    }

    @McpTool(name = "find_deadlocks",
            description = "Detects threads that are deadlocked waiting on each other's monitors or locks, "
                    + "and returns their stack traces if any are found.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public JvmInspector.DeadlockReport findDeadlocks(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid) {
        return inspector.deadlocks(pid);
    }

    @McpTool(name = "hot_threads",
            description = "Finds the threads consuming the most CPU right now by sampling CPU time over a short window. "
                    + "cpuPercent is relative to one core (200% = two full cores). Combine with thread_dump to see what they execute.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public JvmInspector.HotThreadsReport hotThreads(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid,
            @McpToolParam(required = false, description = "Sampling window in milliseconds (default 1000, max 10000)")
            Long sampleMillis,
            @McpToolParam(required = false, description = "How many threads to return (default 10)")
            Integer limit) {
        long sample = sampleMillis == null || sampleMillis <= 0 ? DEFAULT_SAMPLE_MILLIS : Math.min(sampleMillis, 10_000);
        int top = limit == null || limit <= 0 ? DEFAULT_HOT_THREADS : limit;
        return inspector.hotThreads(pid, sample, top);
    }

    @McpTool(name = "system_properties",
            description = "System properties of a JVM (java.home, user.dir, file.encoding, custom -D flags...). "
                    + "Optionally filtered by a case-insensitive substring of the property name.",
            annotations = @McpAnnotations(readOnlyHint = true, openWorldHint = false))
    public Map<String, String> systemProperties(
            @McpToolParam(description = "Process id of the JVM (from list_jvms)") long pid,
            @McpToolParam(required = false, description = "Substring to filter property names, e.g. 'java.' or 'spring'")
            String filter) {
        return inspector.systemProperties(pid, filter);
    }

    // ------------------------------------------------------------------ prompts

    @McpPrompt(name = "diagnose_jvm",
            description = "Step-by-step health check of a JVM that produces a diagnosis with recommendations.")
    public GetPromptResult diagnoseJvm(
            @McpArg(name = "pid", description = "Process id of the JVM to diagnose", required = true) String pid) {
        String text = """
                Perform a health check of the JVM with pid %s using the jvm-inspector tools:
                1. jvm_overview - note the Java version, uptime and memory/GC flags.
                2. heap_usage - is the heap or any pool (Old Gen, Metaspace) close to its max?
                3. gc_stats - how much time is spent in GC?
                4. hot_threads - which threads are consuming CPU right now?
                5. find_deadlocks - are any threads deadlocked?
                6. If something looks wrong, use thread_dump (filtered by state if useful) to dig deeper.

                Finish with a short report: overall status (healthy / warning / critical),
                findings with evidence, and concrete recommendations (e.g. JVM flags or code areas to review).
                """.formatted(pid);
        // The prompt is sent as if the USER typed it, so the model acts on it.
        return new GetPromptResult("JVM health check for pid " + pid,
                List.of(new PromptMessage(Role.USER, new TextContent(text))));
    }
}
