package dev.dguzman.jvminspector.jvm;

import org.springframework.stereotype.Service;

import javax.management.MBeanServerConnection;
import java.io.IOException;
import java.lang.management.ClassLoadingMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.MonitorInfo;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reads diagnostic information from a JVM through its platform MXBeans.
 * This class knows nothing about MCP: it returns plain Java records, which makes it easy to unit-test.
 */
@Service
public class JvmInspector {

    private final JvmConnector connector;

    public JvmInspector(JvmConnector connector) {
        this.connector = connector;
    }

    // ------------------------------------------------------------------ report types

    public record Overview(long pid, String vmName, String vmVendor, String javaVersion, String uptime,
                           Instant startTime, List<String> jvmArguments, int availableProcessors,
                           double systemLoadAverage, int liveThreads, int daemonThreads, int peakThreads,
                           int loadedClasses, long totalLoadedClasses, long unloadedClasses) {
    }

    public record Usage(String used, String committed, String max, Double percentOfMax) {
        static Usage of(MemoryUsage u) {
            Double pct = u.getMax() > 0 ? round(100.0 * u.getUsed() / u.getMax()) : null;
            return new Usage(bytes(u.getUsed()), bytes(u.getCommitted()), u.getMax() < 0 ? "undefined" : bytes(u.getMax()), pct);
        }
    }

    public record Pool(String name, String type, Usage usage) {
    }

    public record MemoryReport(long pid, Usage heap, Usage nonHeap, List<Pool> pools) {
    }

    public record Collector(String name, long collections, long totalTimeMs, List<String> memoryPools) {
    }

    public record GcReport(long pid, String uptime, List<Collector> collectors, long totalGcTimeMs,
                           double percentTimeInGc) {
    }

    public record DeadlockReport(long pid, boolean deadlockDetected, int deadlockedThreads, String details) {
    }

    public record HotThread(long threadId, String name, String state, double cpuPercent, long cpuTimeMs) {
    }

    public record HotThreadsReport(long pid, long sampleMillis, int availableProcessors, List<HotThread> threads) {
    }

    // ------------------------------------------------------------------ inspections

    public List<JvmDescriptor> listJvms() {
        return connector.listJvms();
    }

    public Overview overview(long pid) {
        return connector.withConnection(pid, c -> {
            RuntimeMXBean rt = mx(c, RuntimeMXBean.class);
            OperatingSystemMXBean os = mx(c, OperatingSystemMXBean.class);
            ThreadMXBean th = mx(c, ThreadMXBean.class);
            ClassLoadingMXBean cl = mx(c, ClassLoadingMXBean.class);
            return new Overview(pid, rt.getVmName(), rt.getVmVendor(), rt.getSpecVersion() + " (" + rt.getVmVersion() + ")",
                    duration(rt.getUptime()), Instant.ofEpochMilli(rt.getStartTime()), rt.getInputArguments(),
                    os.getAvailableProcessors(), round(os.getSystemLoadAverage()),
                    th.getThreadCount(), th.getDaemonThreadCount(), th.getPeakThreadCount(),
                    cl.getLoadedClassCount(), cl.getTotalLoadedClassCount(), cl.getUnloadedClassCount());
        });
    }

    public MemoryReport memory(long pid) {
        return connector.withConnection(pid, c -> {
            MemoryMXBean mem = mx(c, MemoryMXBean.class);
            List<Pool> pools = ManagementFactory.getPlatformMXBeans(c, MemoryPoolMXBean.class).stream()
                    .map(p -> new Pool(p.getName(), p.getType().toString(), Usage.of(p.getUsage())))
                    .toList();
            return new MemoryReport(pid, Usage.of(mem.getHeapMemoryUsage()), Usage.of(mem.getNonHeapMemoryUsage()), pools);
        });
    }

    public GcReport gc(long pid) {
        return connector.withConnection(pid, c -> {
            long uptime = mx(c, RuntimeMXBean.class).getUptime();
            List<Collector> collectors = ManagementFactory.getPlatformMXBeans(c, GarbageCollectorMXBean.class).stream()
                    .map(g -> new Collector(g.getName(), g.getCollectionCount(), g.getCollectionTime(),
                            Arrays.asList(g.getMemoryPoolNames())))
                    .toList();
            long total = collectors.stream().mapToLong(g -> Math.max(0, g.totalTimeMs())).sum();
            double pct = uptime > 0 ? round(100.0 * total / uptime) : 0;
            return new GcReport(pid, duration(uptime), collectors, total, pct);
        });
    }

    public Map<String, String> systemProperties(long pid, String filter) {
        return connector.withConnection(pid, c -> {
            Map<String, String> all = mx(c, RuntimeMXBean.class).getSystemProperties();
            Map<String, String> result = new TreeMap<>();
            String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT);
            all.forEach((k, v) -> {
                if (f.isEmpty() || k.toLowerCase(Locale.ROOT).contains(f)) {
                    result.put(k, v);
                }
            });
            return result;
        });
    }

    public String threadDump(long pid, String stateFilter, int maxFrames) {
        return connector.withConnection(pid, c -> {
            ThreadMXBean th = mx(c, ThreadMXBean.class);
            ThreadInfo[] infos = th.dumpAllThreads(th.isObjectMonitorUsageSupported(), th.isSynchronizerUsageSupported());
            Map<Thread.State, Integer> counts = new TreeMap<>();
            StringBuilder body = new StringBuilder();
            int shown = 0;
            for (ThreadInfo info : infos) {
                if (info == null) continue;
                counts.merge(info.getThreadState(), 1, Integer::sum);
                if (stateFilter != null && !stateFilter.isBlank()
                        && !info.getThreadState().name().equalsIgnoreCase(stateFilter.trim())) {
                    continue;
                }
                body.append(format(info, maxFrames)).append('\n');
                shown++;
            }
            return "Thread dump of JVM " + pid + " at " + Instant.now() + "\n"
                    + "Total threads: " + infos.length + " " + counts + "\n"
                    + "Shown: " + shown + (stateFilter == null || stateFilter.isBlank() ? "" : " (state=" + stateFilter.trim().toUpperCase(Locale.ROOT) + ")")
                    + ", max " + maxFrames + " frames per thread\n\n" + body;
        });
    }

    public DeadlockReport deadlocks(long pid) {
        return connector.withConnection(pid, c -> {
            ThreadMXBean th = mx(c, ThreadMXBean.class);
            long[] ids = th.isSynchronizerUsageSupported() ? th.findDeadlockedThreads() : th.findMonitorDeadlockedThreads();
            if (ids == null || ids.length == 0) {
                return new DeadlockReport(pid, false, 0, "No deadlocks found.");
            }
            StringBuilder sb = new StringBuilder();
            for (ThreadInfo info : th.getThreadInfo(ids, true, true)) {
                if (info != null) sb.append(format(info, 30)).append('\n');
            }
            return new DeadlockReport(pid, true, ids.length, sb.toString());
        });
    }

    /**
     * Samples thread CPU time twice and reports the threads that consumed the most CPU in between.
     * This answers "what is my app busy doing RIGHT NOW?" better than cumulative CPU times.
     */
    public HotThreadsReport hotThreads(long pid, long sampleMillis, int limit) {
        return connector.withConnection(pid, c -> {
            ThreadMXBean th = mx(c, ThreadMXBean.class);
            if (!th.isThreadCpuTimeSupported()) {
                throw new JvmInspectionException("Thread CPU time measurement is not supported by JVM " + pid);
            }
            if (!th.isThreadCpuTimeEnabled()) {
                throw new JvmInspectionException("Thread CPU time measurement is disabled in JVM " + pid);
            }
            int cpus = mx(c, OperatingSystemMXBean.class).getAvailableProcessors();

            Map<Long, Long> before = cpuTimes(th);
            long start = System.nanoTime();
            sleep(sampleMillis);
            Map<Long, Long> after = cpuTimes(th);
            long elapsedNanos = System.nanoTime() - start;

            Map<Long, Long> deltas = new HashMap<>();
            after.forEach((id, t) -> deltas.put(id, t - before.getOrDefault(id, 0L)));
            List<Long> top = deltas.entrySet().stream()
                    .sorted(Map.Entry.<Long, Long>comparingByValue().reversed())
                    .limit(limit)
                    .map(Map.Entry::getKey)
                    .toList();

            List<HotThread> result = Arrays.stream(th.getThreadInfo(top.stream().mapToLong(Long::longValue).toArray()))
                    .filter(i -> i != null)
                    .map(i -> new HotThread(i.getThreadId(), i.getThreadName(), i.getThreadState().name(),
                            round(100.0 * deltas.get(i.getThreadId()) / elapsedNanos),
                            after.get(i.getThreadId()) / 1_000_000))
                    .sorted(Comparator.comparingDouble(HotThread::cpuPercent).reversed())
                    .toList();
            return new HotThreadsReport(pid, sampleMillis, cpus, result);
        });
    }

    // ------------------------------------------------------------------ helpers

    private static <T extends java.lang.management.PlatformManagedObject> T mx(MBeanServerConnection c, Class<T> type)
            throws IOException {
        return ManagementFactory.getPlatformMXBean(c, type);
    }

    private static Map<Long, Long> cpuTimes(ThreadMXBean th) {
        Map<Long, Long> times = new HashMap<>();
        for (long id : th.getAllThreadIds()) {
            long t = th.getThreadCpuTime(id);
            if (t >= 0) times.put(id, t);
        }
        return times;
    }

    /** Like {@link ThreadInfo#toString()} but without its hard-coded 8-frame limit. */
    static String format(ThreadInfo info, int maxFrames) {
        StringBuilder sb = new StringBuilder();
        sb.append('"').append(info.getThreadName()).append('"')
                .append(info.isDaemon() ? " daemon" : "")
                .append(" #").append(info.getThreadId())
                .append(" ").append(info.getThreadState());
        if (info.getLockName() != null) sb.append(" on ").append(info.getLockName());
        if (info.getLockOwnerName() != null) {
            sb.append(" owned by \"").append(info.getLockOwnerName()).append("\" #").append(info.getLockOwnerId());
        }
        sb.append('\n');

        StackTraceElement[] stack = info.getStackTrace();
        MonitorInfo[] monitors = info.getLockedMonitors();
        for (int i = 0; i < stack.length && i < maxFrames; i++) {
            sb.append("\tat ").append(stack[i]).append('\n');
            if (i == 0 && info.getLockInfo() != null) {
                String verb = switch (info.getThreadState()) {
                    case BLOCKED -> "waiting to lock";
                    case WAITING, TIMED_WAITING -> "waiting on";
                    default -> "parked on";
                };
                sb.append("\t- ").append(verb).append(' ').append(info.getLockInfo()).append('\n');
            }
            for (MonitorInfo m : monitors) {
                if (m.getLockedStackDepth() == i) sb.append("\t- locked ").append(m).append('\n');
            }
        }
        if (stack.length > maxFrames) {
            sb.append("\t... ").append(stack.length - maxFrames).append(" more frames\n");
        }
        LockInfo[] syncs = info.getLockedSynchronizers();
        if (syncs.length > 0) {
            sb.append("\tLocked ownable synchronizers:\n");
            for (LockInfo s : syncs) sb.append("\t- ").append(s).append('\n');
        }
        return sb.toString();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JvmInspectionException("Interrupted while sampling");
        }
    }

    static String bytes(long b) {
        if (b < 1024) return b + " B";
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = b;
        int u = -1;
        do {
            v /= 1024;
            u++;
        } while (v >= 1024 && u < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", v, units[u]);
    }

    static String duration(long millis) {
        Duration d = Duration.ofMillis(millis);
        return String.format(Locale.ROOT, "%dd %02dh %02dm %02ds", d.toDays(), d.toHoursPart(), d.toMinutesPart(), d.toSecondsPart());
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

