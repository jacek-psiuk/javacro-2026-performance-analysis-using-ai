package hr.javacro.section3;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/// All problems from section 1 and section 2, running together in one JVM - one process to attach
/// thread dumps, heap dumps, Native Memory Tracking, JFR and async-profiler to.
///
/// | id      | class                 | problem                                    | best seen with            |
/// |---------|-----------------------|--------------------------------------------|---------------------------|
/// | `s1ex1` | [BankTransfers]       | deadlock                                   | thread dump               |
/// | `s1ex2` | [PriceService]        | slow work under a lock                     | thread dump, JFR          |
/// | `s1ex3` | [ReportingJobs]       | virtual thread carriers starved            | thread dump (JSON)        |
/// | `s1ex4` | [ReportCache]         | heap leak - key without equals/hashCode    | class histogram, heap dump|
/// | `s1ex5` | [MessageSender]       | direct buffer leak                         | NMT, heap dump            |
/// | `s2ex1` | [OrderService]        | contention, regex, exceptions, allocation  | JFR                       |
/// | `s2ex2` | [ExportImportService] | kernel CPU in write(), malloc leak         | async-profiler            |
///
/// Run all of them (NMT must be enabled at startup):
/// `mvn -q compile exec:exec -Dmain=hr.javacro.section3.Workshop -Djvm="-Xmx1g -XX:NativeMemoryTracking=detail"`
///
/// Run only some: `-Dargs="s1ex1 s1ex4"`.
///
/// Leak rates are lowered compared with the original examples so the JVM survives a whole workshop
/// with `-Xmx1g`: heap ~1 MB/s, direct buffers ~0.3 MB/s, zlib ~0.8 MB/s.
/// The virtual thread scheduler is limited to 2 carriers unless `-Djdk.virtualThreadScheduler.parallelism`
/// is given, so [ReportingJobs] does not take every core.
public class Workshop {

    static final long STATUS_INTERVAL_MS = 10_000;

    static void main(String[] args) {
        // Must happen before the first virtual thread is created
        if (System.getProperty("jdk.virtualThreadScheduler.parallelism") == null) {
            System.setProperty("jdk.virtualThreadScheduler.parallelism", "2");
        }

        List<Scenario> all = List.of(
                new BankTransfers(),
                new PriceService(),
                new ReportingJobs(),
                new ReportCache(),
                new MessageSender(),
                new OrderService(),
                new ExportImportService());

        List<Scenario> scenarios = select(all, args);
        if (scenarios == null) {
            return;
        }

        long pid = ProcessHandle.current().pid();
        IO.println("PID: " + pid);
        for (Scenario scenario : scenarios) {
            scenario.start();
            IO.println("  started %s  %s".formatted(scenario.id(), scenario.description()));
        }
        printCheatSheet(pid);

        long started = System.currentTimeMillis();
        while (true) {
            Threads.sleep(STATUS_INTERVAL_MS);
            Runtime rt = Runtime.getRuntime();
            long heapMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            StringBuilder line = new StringBuilder("%4ds heap=%d MB, RSS=%s"
                    .formatted((System.currentTimeMillis() - started) / 1000, heapMb, rssMb()));
            for (Scenario scenario : scenarios) {
                line.append(" | ").append(scenario.status());
            }
            IO.println(line);
        }
    }

    static List<Scenario> select(List<Scenario> all, String[] args) {
        if (args.length == 0) {
            return all;
        }
        Set<String> ids = new LinkedHashSet<>(List.of(args));
        List<Scenario> selected = new ArrayList<>();
        for (Scenario scenario : all) {
            if (ids.remove(scenario.id())) {
                selected.add(scenario);
            }
        }
        if (!ids.isEmpty()) {
            IO.println("Unknown scenario(s): " + ids + ". Available:");
            all.forEach(scenario -> IO.println("  %s  %s".formatted(scenario.id(), scenario.description())));
            return null;
        }
        return selected;
    }

    static void printCheatSheet(long pid) {
        boolean nmt = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.startsWith("-XX:NativeMemoryTracking=") && !arg.endsWith("=off"));
        IO.println("""
                
                Thread dumps:
                  jstack -l %1$d
                  jcmd %1$d Thread.print -l
                  jcmd %1$d Thread.dump_to_file -format=json threads.json
                Heap:
                  jcmd %1$d GC.class_histogram
                  jcmd %1$d GC.heap_dump heap.hprof
                Native Memory Tracking%2$s:
                  jcmd %1$d VM.native_memory baseline
                  jcmd %1$d VM.native_memory summary.diff
                JFR:
                  jcmd %1$d JFR.start settings=profile duration=60s filename=workshop.jfr
                  jfr view contention-by-site|hot-methods|exception-count|allocation-by-site workshop.jfr
                  jfr print --events hr.javacro.OrderProcessed workshop.jfr
                async-profiler (Linux / WSL2):
                  asprof -e cpu -d 30 -f cpu.html %1$d
                  asprof -e lock -d 30 -f lock.html %1$d
                  asprof -e alloc -d 30 -f alloc.html %1$d
                  asprof -e nativemem -d 30 -f nativemem.jfr %1$d
                """.formatted(pid, nmt ? "" : " (disabled - restart with -XX:NativeMemoryTracking=detail)"));
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
}
