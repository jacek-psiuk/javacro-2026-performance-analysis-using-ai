package hr.javacro.section3;

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

/// Section 2, example 2 ([hr.javacro.section2.Ex2]) - problems below the Java level, made for async-profiler.
///
/// 1. Kernel CPU time - the `exporter-N` threads write every field straight to an unbuffered
///    `FileOutputStream`, one `write` system call each. JFR stops at `FileOutputStream.writeBytes`;
///    async-profiler shows the syscall and kernel frames below it.
/// 2. Native memory leak NMT cannot see - the `importer` never closes its `GZIPInputStream`s and keeps
///    them in `IMPORT_HISTORY`. Each holds an `Inflater` whose zlib state (~40 KB) is `malloc`-ed by the
///    JDK's zip library, outside NMT (~0.8 MB/s). RSS grows, heap and NMT stay flat.
///
/// async-profiler (Linux / WSL2): `asprof -e cpu -d 30 -f cpu.html <pid>`,
/// `asprof -e nativemem -d 30 -f nativemem.jfr <pid>` + `jfrconv --nativemem --leak nativemem.jfr leak.html`.
final class ExportImportService implements Scenario {

    record ImportJob(long id, int lines, InputStream source) {}

    static final int RECORDS_PER_FILE = 10_000;

    // Kept for the "recent imports" dashboard - grows forever and holds the unclosed streams
    static final List<ImportJob> IMPORT_HISTORY = new ArrayList<>();

    private final AtomicLong exported = new AtomicLong();
    private final AtomicLong imported = new AtomicLong();
    private final Threads.Rate rate = new Threads.Rate();

    @Override
    public String id() {
        return "s2ex2";
    }

    @Override
    public String description() {
        return "unbuffered writes in exporters, unclosed GZIPInputStreams in importer (async-profiler)";
    }

    @Override
    public void start() {
        Path dir;
        byte[] batch;
        try {
            dir = Files.createTempDirectory("workshop-export");
            batch = createCompressedBatch();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (int i = 1; i <= 2; i++) {
            String name = "exporter-" + i;
            Threads.startDaemon(name, () -> export(dir, name));
        }
        Threads.startDaemon("importer", () -> importBatches(batch));
    }

    void export(Path dir, String name) {
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

    void importBatches(byte[] compressedBatch) {
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
            Threads.sleep(50);
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

    @Override
    public String status() {
        return "exported=" + rate.perSecond(exported.get()) + " rec/s, imports=" + imported.get();
    }
}
