package hr.javacro.section2;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// A report export/import service with two problems that live below the Java level -
/// made for async-profiler, which sees native and kernel frames that JFR does not.
///
/// 1. Kernel CPU time - the exporters write every field of every record straight to a
///    `FileOutputStream`, without a buffer. Each `write` is a system call, so most of the
///    CPU is spent in the kernel. JFR's CPU samples end at the native method
///    `FileOutputStream.writeBytes`; async-profiler shows the `write` syscall and the kernel
///    frames underneath it.
/// 2. Native memory leak that NMT cannot see - the importer decompresses each uploaded batch with a
///    `GZIPInputStream`, never closes it, and keeps it in the import history. Every stream holds an
///    `Inflater`, whose zlib state (~40 KB) is `malloc`-ed by the JDK's zip library, outside the JVM's
///    Native Memory Tracking. The heap stays small and NMT stays flat, but the process RSS keeps growing.
///    async-profiler's `nativemem` mode records `malloc` calls with their Java call stacks.
///
/// How to see it with async-profiler (Linux / WSL2 - async-profiler does not support Windows):
/// - CPU incl. kernel frames: `asprof -e cpu -d 30 -o flat=20 <pid>`, `-o traces=10`, or
///   `-f cpu.html` for a flame graph (kernel frames need `kernel.perf_event_paranoid<=1`
///   and `kernel.kptr_restrict=0`).
/// - Native memory: `asprof -e nativemem -d 30 -f nativemem.jfr <pid>`, then
///   `jfrconv --nativemem --leak nativemem.jfr leak.html` (only allocations not freed).
/// For comparison: `-XX:NativeMemoryTracking=summary` + `jcmd <pid> VM.native_memory summary.diff`
/// stays flat, and a 30 s JFR recording shows CPU only in `FileOutputStream.writeBytes`.
public class Ex2 {

    record ImportJob(long id, int lines, InputStream source) {}

    static final int RECORDS_PER_FILE = 10_000;

    // Kept for the "recent imports" dashboard - grows forever and holds the unclosed streams
    static final List<ImportJob> IMPORT_HISTORY = new ArrayList<>();

    static final AtomicLong exported = new AtomicLong();
    static final AtomicLong imported = new AtomicLong();

    static void export(Path dir, String name) {
        long fileNo = 0;
        while (true) {
            Path file = dir.resolve(name + "-" + (fileNo++ % 3) + ".csv");
            try (OutputStream out = new FileOutputStream(file.toFile())) {
                for (int i = 0; i < RECORDS_PER_FILE; i++) {
                    writeRecord(out, i);
                    exported.incrementAndGet();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static void writeRecord(OutputStream out, int i) throws IOException {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Unbuffered: every field is a separate write() system call - this is bug 1
        out.write(Integer.toString(i).getBytes(StandardCharsets.US_ASCII));
        out.write(';');
        out.write(("customer-" + random.nextInt(10_000)).getBytes(StandardCharsets.US_ASCII));
        out.write(';');
        out.write(Long.toString(random.nextLong(1_000_000)).getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
    }

    static void importBatches(byte[] compressedBatch) {
        long id = 0;
        while (true) {
            try {
                // Never closed, and kept in the history - this is bug 2
                InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressedBatch));
                int lines = countLines(in.readAllBytes());
                IMPORT_HISTORY.add(new ImportJob(++id, lines, in));
                imported.incrementAndGet();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            sleep(5);
        }
    }

    static int countLines(byte[] data) {
        int lines = 0;
        for (byte b : data) {
            if (b == '\n') {
                lines++;
            }
        }
        return lines;
    }

    static byte[] createCompressedBatch() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream gzip = new GZIPOutputStream(bytes)) {
            for (int i = 0; i < 2_000; i++) {
                gzip.write(("order-" + i + ";customer-" + (i % 97) + ";" + (i * 31 % 1000) + "\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
        }
        return bytes.toByteArray();
    }

    static String rssMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return Long.parseLong(line.replaceAll("\\D", "")) / 1024 + " MB";
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not Linux
        }
        return "n/a";
    }

    static void main() throws IOException {
        Path dir = Files.createTempDirectory("ex2-export");
        byte[] batch = createCompressedBatch();

        for (int i = 1; i <= 2; i++) {
            String name = "exporter-" + i;
            Thread exporter = new Thread(() -> export(dir, name), name);
            exporter.setDaemon(true);
            exporter.start();
        }
        Thread importer = new Thread(() -> importBatches(batch), "importer");
        importer.setDaemon(true);
        importer.start();

        IO.println("PID: " + ProcessHandle.current().pid() + ", exporting to " + dir);
        IO.println("Profile with: asprof -e cpu -d 30 -o flat=20 " + ProcessHandle.current().pid());
        IO.println("         and: asprof -e nativemem -d 30 -f nativemem.jfr " + ProcessHandle.current().pid());

        long lastExported = 0;
        long started = System.currentTimeMillis();
        while (true) {
            sleep(5000);
            long now = exported.get();
            Runtime rt = Runtime.getRuntime();
            long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            IO.println("%3ds: exported %d records/s, imports=%d, heap used=%d MB, RSS=%s"
                    .formatted((System.currentTimeMillis() - started) / 1000, (now - lastExported) / 5,
                            imported.get(), heapMb, rssMb()));
            lastExported = now;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
