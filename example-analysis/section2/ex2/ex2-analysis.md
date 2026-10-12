# section2 Ex2 - async-profiler, 30 s

Target: running `hr.javacro.section2.Ex2` (PID 1182, JDK 25.0.4, WSL2 kernel 5.10, 8 CPUs), async-profiler 4.5. Threads:
`exporter-1`, `exporter-2` (CSV export), `importer` (gzip batch import).

```bash
# CPU (incl. kernel frames) + wall + heap alloc + every malloc/free, one session
asprof -e cpu -i 10ms --wall 20ms --alloc 512k --nativemem 0 -d 30 -f ex2-all.jfr <pid>
jfrconv --cpu ex2-all.jfr ex2-cpu.html
jfrconv --nativemem --leak ex2-all.jfr ex2-nativemem-leak.html   # only allocations not freed
jfrconv --nativemem --leak --total --threads -o collapsed ...     # leaked bytes per stack
# call counts (method tracing, separate 5 s session)
asprof -e 'java.io.FileOutputStream.write' -d 5 -o collapsed <pid>
```

NMT was not enabled in this JVM (`Native memory tracking is not enabled`).

## Process state

|                            | Before profiling | After 30 s                    | A few minutes later |
|----------------------------|------------------|-------------------------------|---------------------|
| RSS (`/proc/<pid>/status`) | 639 MB           | 880 MB (**+241 MB, ~8 MB/s**) | 1,126 MB            |
| Java heap used / committed |                  | 180 MB / 404 MB               |                     |

The process grows much faster than the heap - a native leak.

## Findings, ranked by impact

| # | Problem                                                                          | Evidence                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | Source              |
|---|----------------------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------|
| 1 | Native memory leak - `GZIPInputStream` never closed and kept in `IMPORT_HISTORY` | **nativemem --leak**: 190.4 MB allocated and not freed in 30 s, **100 % from the `importer` thread**, all in zlib: 155.8 MB from `Inflater.inflateBytesBytes` (4,986 x 32 KB inflate window) + 34.6 MB from `Inflater.init` (9,972 mallocs: 7 KB z_stream state + 112 B). That is ~39 KB of native memory per stream, ~166 streams/s - matches the RSS growth (~8 MB/s). **Heap**: `GC.class_histogram` shows 20,413 live `Inflater` / `GZIPInputStream` / `Ex2$ImportJob` objects - only ~3.4 MB of heap, but ~780 MB of native memory behind them (20,413 x 39 KB).                                                                                | `Ex2.java:85-87`    |
| 2 | Unbuffered `FileOutputStream` writes - one syscall per field                     | **CPU**: 6,154 samples = 61.5 s CPU in 30 s (~2 cores). `exporter-1` and `exporter-2` take 48.5 % each (both at ~100 % of a core); **81-82 % of their samples end in kernel frames**. Of exporter samples, 98.5 % are under `FileOutputStream.write`, 86 % in `__write` (libc), 78 % in `ksys_write`, 71 % in `ext4_buffered_write_iter`, 16.5 % in ext4 journalling (`jbd2`) and 12.9 % in `__crc32c_le` (inode checksum updated on every write). Record formatting (`Integer.toString`, string concat, `getBytes`) is < 1 %. **Method trace**: 1,724,770 `FileOutputStream.write` calls in 5 s (~345,000 syscalls/s, 6 per record, ~5 bytes each). | `Ex2.java:58,72-77` |

The hottest stack (both exporters) shows the whole path:
`Ex2.writeRecord -> FileOutputStream.write -> writeBytes -> __write -> ksys_write -> vfs_write
-> ext4_file_write_iter -> ext4_buffered_write_iter -> generic_perform_write -> ext4_da_write_end
-> __mark_inode_dirty -> ext4_mark_iloc_dirty -> ext4_inode_csum -> __crc32c_le`. The data itself is tiny; the cost is
the per-call kernel overhead (syscall entry, ext4 journal handle, inode checksum), paid ~345,000 times a second.

The `importer` uses only 2.7 % of CPU and the heap allocation profile is small. The leak is invisible to CPU and heap
profiles; only the native allocation profile shows it.

## Fix order

1. Close the import streams: `try (InputStream in = new GZIPInputStream(...)) { ... }` and keep only the result (`id`,
   `lines`) in `ImportJob`, not the stream. `close()` calls
   `Inflater.end()`, which frees the ~39 KB of zlib memory immediately. Also cap
   `IMPORT_HISTORY` (it grows forever - the Java objects are small, but they still leak).
2. Buffer the exporter output: `new BufferedOutputStream(new FileOutputStream(...), 64 * 1024)`
   (or `Files.newBufferedWriter`). This turns ~60 syscalls per 10 records into one per 64 KB and should remove most of
   the ~80 % kernel time.

## Files

| File                              | Content                                                                    |
|-----------------------------------|----------------------------------------------------------------------------|
| `ex2-all.jfr`                     | Raw recording (cpu + wall + alloc + nativemem), open with `jfrconv` or JMC |
| `ex2-cpu.html`                    | CPU flame graph with kernel frames (`_[k]`)                                |
| `ex2-nativemem-leak.html`         | Unfreed `malloc`s by Java stack - the leak                                 |
| `ex2-nativemem.html`              | All `malloc`s (incl. freed)                                                |
| `ex2-wall.html`, `ex2-alloc.html` | Wall-clock and heap allocation flame graphs                                |
| `ex2-*-threads.collapsed`         | Per-thread collapsed stacks (`*-bytes-*` weighted by bytes)                |
| `ex2-fos-write-5s.collapsed`      | `FileOutputStream.write` call counts, 5 s                                  |
