import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * In-container benchmark harness (compiled and run inside the isolated runner; never on the host).
 *
 * usage: java -Djava.security.manager=allow Bench CLASSES_DIR MAIN_CLASS WARMUP MEASURED fresh|shared
 *             ITERATION_TIMEOUT_MS WORKLOAD_FILE...
 *
 * For each workload file (the program's stdin) it calls {@code MAIN_CLASS.main(String[])} in this JVM
 * WARMUP + MEASURED times. Every iteration gets a fresh stdin and a hashing output sink; time is taken with
 * {@link System#nanoTime()} (monotonic) around the call only, so JVM start, class path scanning and compilation
 * are outside the measurement. With "fresh" every iteration loads the program in a new class loader (static state
 * cannot leak between iterations; class loading and initialisation are inside the timing); with "shared" the
 * loaded classes are reused (JIT-warmed steady state; static state persists, which the harness reports).
 *
 * stdout: one JSON object per line - {"environment":...} then one {"workload":i,...} per workload.
 * {@code System.exit} inside the program is trapped and reported as the outcome.
 */
public final class Bench {
    private static final class ExitTrap extends SecurityException {
        final int status;

        ExitTrap(int status) {
            super("exit " + status);
            this.status = status;
        }
    }

    private static final class HashingSink extends OutputStream {
        final MessageDigest digest;
        long bytes;

        HashingSink() throws Exception {
            digest = MessageDigest.getInstance("SHA-256");
        }

        @Override
        public void write(int b) {
            digest.update((byte) b);
            bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) {
            digest.update(b, off, len);
            bytes += len;
        }

        String hex() {
            StringBuilder sb = new StringBuilder();
            for (byte b : digest.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        }
    }

    /** Set by the harness itself right before Runtime.halt, which also consults the security manager. */
    private static volatile boolean harnessExit;
    private static volatile long deadline = Long.MAX_VALUE;
    private static volatile String currentWorkload = "";

    public static void main(String[] args) throws Exception {
        PrintStream report = new PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true, StandardCharsets.UTF_8);
        Path classes = Path.of(args[0]);
        String mainClass = args[1];
        int warmup = Integer.parseInt(args[2]);
        int measured = Integer.parseInt(args[3]);
        boolean fresh = args[4].equals("fresh");
        long timeoutNanos = Long.parseLong(args[5]) * 1_000_000L;

        System.setSecurityManager(new SecurityManager() {
            @Override
            public void checkExit(int status) {
                if (!harnessExit) {
                    throw new ExitTrap(status);
                }
            }

            @Override
            public void checkPermission(java.security.Permission permission) {
            }
        });

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
                if (System.nanoTime() > deadline) {
                    report.println("{\"workload\":" + currentWorkload + ",\"status\":\"timed_out\"}");
                    report.flush();
                    harnessExit = true;
                    Runtime.getRuntime().halt(3);
                }
            }
        }, "bench-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        report.println("{\"environment\":" + environment() + "}");
        URL[] urls = {classes.toUri().toURL()};
        ClassLoader sharedLoader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        for (int w = 6; w < args.length; w++) {
            int index = w - 6;
            currentWorkload = Integer.toString(index);
            byte[] stdin = Files.readAllBytes(Path.of(args[w]));
            List<Long> warmupNanos = new ArrayList<>();
            List<Long> samples = new ArrayList<>();
            String firstHash = null;
            String firstOutcome = null;
            long firstBytes = 0;
            boolean stable = true;
            for (int i = 0; i < warmup + measured; i++) {
                ClassLoader loader = fresh ? new URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) : sharedLoader;
                HashingSink out = new HashingSink();
                HashingSink err = new HashingSink();
                PrintStream outStream = new PrintStream(out, false, StandardCharsets.UTF_8);
                PrintStream errStream = new PrintStream(err, false, StandardCharsets.UTF_8);
                System.setIn(new ByteArrayInputStream(stdin));
                System.setOut(outStream);
                System.setErr(errStream);
                System.gc();
                String outcome;
                long start;
                long end;
                deadline = System.nanoTime() + timeoutNanos;
                start = System.nanoTime();
                try {
                    Class<?> type = Class.forName(mainClass, true, loader);
                    Method main = type.getMethod("main", String[].class);
                    main.invoke(null, (Object) new String[0]);
                    outcome = "returned";
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    outcome = cause instanceof ExitTrap trap ? "exit:" + trap.status
                            : "exception:" + cause.getClass().getName() + ": " + cause.getMessage();
                } catch (ReflectiveOperationException e) {
                    outcome = "harness_error:" + e;
                }
                outStream.flush();
                end = System.nanoTime();
                deadline = Long.MAX_VALUE;
                String hash = out.hex();
                if (firstHash == null) {
                    firstHash = hash;
                    firstOutcome = outcome;
                    firstBytes = out.bytes;
                } else if (!firstHash.equals(hash) || !firstOutcome.equals(outcome)) {
                    stable = false;
                }
                (i < warmup ? warmupNanos : samples).add(end - start);
            }
            report.println("{\"workload\":" + index + ",\"status\":\"completed\",\"samples_ns\":" + samples
                    + ",\"warmup_ns\":" + warmupNanos + ",\"stdout_sha256\":\"" + firstHash + "\",\"stdout_bytes\":" + firstBytes
                    + ",\"outcome\":" + quote(firstOutcome) + ",\"stable_across_iterations\":" + stable + "}");
            report.flush();
        }
        report.flush();
        harnessExit = true;
        Runtime.getRuntime().halt(0);
    }

    private static String environment() {
        Runtime rt = Runtime.getRuntime();
        String collectors = ManagementFactory.getGarbageCollectorMXBeans().stream().map(b -> quote(b.getName()))
                .collect(Collectors.joining(","));
        String flags = ManagementFactory.getRuntimeMXBean().getInputArguments().stream().map(Bench::quote)
                .collect(Collectors.joining(","));
        return "{\"java_version\":" + quote(System.getProperty("java.version")) + ",\"vm\":" + quote(System.getProperty("java.vm.name"))
                + ",\"vm_version\":" + quote(System.getProperty("java.vm.version")) + ",\"os\":" + quote(System.getProperty("os.name"))
                + ",\"arch\":" + quote(System.getProperty("os.arch")) + ",\"available_processors\":" + rt.availableProcessors()
                + ",\"max_heap_bytes\":" + rt.maxMemory() + ",\"garbage_collectors\":[" + collectors + "],\"jvm_flags\":[" + flags + "]}";
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : (s == null ? "null" : s).toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }
}
