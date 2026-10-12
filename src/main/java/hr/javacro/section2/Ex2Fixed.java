package hr.javacro.section2;

import java.io.BufferedOutputStream;
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
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/// Fixed version of [Ex2] - same exports and imports, both problems removed:
///
/// 1. Kernel CPU time - the exporters write through a 64 KB `BufferedOutputStream`, so the
///    six small `write` calls per record go into the buffer, and the kernel gets one `write`
///    system call per 64 KB instead of one per field.
/// 2. Native memory leak - each `GZIPInputStream` is closed by try-with-resources, which calls
///    `Inflater.end()` and frees the ~39 KB of zlib memory right away. The import history keeps
///    only the result (`id`, `lines`), not the stream, and is bounded to the last
///    `MAX_HISTORY` jobs.
///
/// How to see it with async-profiler: profile it exactly like [Ex2] -
/// `asprof -e cpu -d 30 -o flat=20 <pid>` shows the exporters' time in Java code (formatting the
/// records) instead of kernel `write` / ext4 frames, and export throughput goes up;
/// `asprof -e nativemem -d 30 -f nativemem.jfr <pid>` + `jfrconv --nativemem --leak` shows no
/// unfreed `Inflater` allocations, and RSS stays flat.
public class Ex2Fixed {

    record ImportJob(long id, int lines) {}

    static final int RECORDS_PER_FILE = 10_000;
    static final int BUFFER_SIZE = 64 * 1024;
    static final int MAX_HISTORY = 1_000;

    // The "recent imports" dashboard - only the last MAX_HISTORY results, no streams
    static final Deque<ImportJob> IMPORT_HISTORY = new ArrayDeque<>();

    static final AtomicLong exported = new AtomicLong();
    static final AtomicLong imported = new AtomicLong();

    static void export(Path dir, String name) {
        long fileNo = 0;
        while (true) {
            Path file = dir.resolve(name + "-" + (fileNo++ % 3) + ".csv");
            // Buffered: the kernel sees one write() per 64 KB, not one per field
            try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file.toFile()), BUFFER_SIZE)) {
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
            // Closed after use - Inflater.end() frees the zlib memory immediately
            try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressedBatch))) {
                int lines = countLines(in.readAllBytes());
                IMPORT_HISTORY.addLast(new ImportJob(++id, lines));
                if (IMPORT_HISTORY.size() > MAX_HISTORY) {
                    IMPORT_HISTORY.removeFirst();
                }
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
