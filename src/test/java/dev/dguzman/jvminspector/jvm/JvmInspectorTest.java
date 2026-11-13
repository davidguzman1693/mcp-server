package dev.dguzman.jvminspector.jvm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests run against the test JVM itself (self-inspection goes through the in-process MBean server),
 * so no external process is needed.
 */
class JvmInspectorTest {

    private final JvmConnector connector = new JvmConnector();
    private final JvmInspector inspector = new JvmInspector(connector);
    private final long self = connector.selfPid();
    private Thread[] deadlocked;

    @AfterEach
    void cleanup() {
        if (deadlocked != null) {
            for (Thread t : deadlocked) t.interrupt(); // daemon threads; they die with the JVM anyway
        }
    }

    @Test
    void listsOwnJvm() {
        assertThat(inspector.listJvms()).anyMatch(j -> j.pid() == self && j.self());
    }

    @Test
    void overview() {
        var o = inspector.overview(self);
        assertThat(o.javaVersion()).startsWith(System.getProperty("java.specification.version"));
        assertThat(o.liveThreads()).isPositive();
    }

    @Test
    void memoryAndGc() {
        var mem = inspector.memory(self);
        assertThat(mem.heap().used()).isNotBlank();
        assertThat(mem.pools()).isNotEmpty();
        assertThat(inspector.gc(self).collectors()).isNotEmpty();
    }

    @Test
    void threadDumpContainsCurrentThread() {
        String dump = inspector.threadDump(self, null, 5);
        assertThat(dump).contains("\"" + Thread.currentThread().getName() + "\"");
        assertThat(inspector.threadDump(self, "runnable", 5)).contains("RUNNABLE");
    }

    @Test
    void systemPropertiesFilter() {
        assertThat(inspector.systemProperties(self, "JAVA.VERSION")).containsKey("java.version");
    }

    @Test
    void hotThreadsFindsBusyThread() throws Exception {
        Thread busy = Thread.ofPlatform().daemon().name("busy-spinner").start(() -> {
            long x = 0;
            while (!Thread.currentThread().isInterrupted()) x++;
        });
        try {
            var report = inspector.hotThreads(self, 500, 3);
            assertThat(report.threads()).extracting(JvmInspector.HotThread::name).contains("busy-spinner");
        } finally {
            busy.interrupt();
        }
    }

    @Test
    void detectsDeadlock() throws Exception {
        Object a = new Object(), b = new Object();
        CountDownLatch bothLocked = new CountDownLatch(2);
        deadlocked = new Thread[]{
                lockInOrder("deadlock-1", a, b, bothLocked),
                lockInOrder("deadlock-2", b, a, bothLocked)};
        bothLocked.await(5, TimeUnit.SECONDS);
        Thread.sleep(200);

        var report = inspector.deadlocks(self);
        assertThat(report.deadlockDetected()).isTrue();
        assertThat(report.details()).contains("deadlock-1", "deadlock-2");
    }

    private static Thread lockInOrder(String name, Object first, Object second, CountDownLatch latch) {
        return Thread.ofPlatform().daemon().name(name).start(() -> {
            synchronized (first) {
                latch.countDown();
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (second) {
                    // never reached
                }
            }
        });
    }

    @Test
    void formatsBytesAndDurations() {
        assertThat(JvmInspector.bytes(512)).isEqualTo("512 B");
        assertThat(JvmInspector.bytes(1536)).isEqualTo("1.5 KB");
        assertThat(JvmInspector.duration(90_061_000)).isEqualTo("1d 01h 01m 01s");
    }
}

