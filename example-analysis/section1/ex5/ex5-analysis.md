# Native memory analysis: Ex5 (PID 22000)

Run: `-Xmx256m -XX:NativeMemoryTracking=summary`, JDK 25.0.4, G1, ~1,000 messages/s, 64 KB each. NMT baseline at 3 s,
`summary.diff` at 15 s and 30 s, heap dump at 30 s.

## Evidence

**1. Process memory grows, heap does not explain it** (`ex5-process-memory-22000.txt`, app output)

|                        | 3 s    | 15 s   | 30 s       |
|------------------------|--------|--------|------------|
| Process private memory | 381 MB | 498 MB | 509 MB     |
| Process working set    | 187 MB | 339 MB | 351 MB     |
| Live heap (heap dump)  |        |        | **1.8 MB** |

"Heap used" printed by the app swings between 18 and 151 MB - that is short-lived payload garbage, not retained data.
The live heap at dump time is under 2 MB.

**2. NMT `summary.diff` - one category grows** (`ex5-nmt-22000-2-diff-30s.txt`)

```
Total: reserved=1890276KB +139485KB, committed=511624KB +139553KB
-     Java Heap (reserved=262144KB, committed=262144KB)              <- no change
-         Other (reserved=167872KB +140160KB, committed=167872KB +140160KB)
                (malloc=167872KB +140160KB #2623 +2190)
```

`Other` = +130 MB after 12 s, +140 MB after 27 s; every other category changes by < 100 KB.
`Other` is where `ByteBuffer.allocateDirect` / `Unsafe.allocateMemory` memory is accounted.

**3. Heap dump - who holds the native memory** (MAT via `ci/mat.sh`)

- `suspects` is **misleading** here: it reports JDK Strings and Classes (577 KB, 434 KB) because the heap is tiny. MAT
  measures heap bytes only; a `DirectByteBuffer` is 72 bytes on heap.
- `group_by_value java.nio.DirectByteBuffer -field capacity`:
  **1,339 buffers x 65,536 bytes = 84 MB native**, held by 96 KB of heap objects. (One more buffer of 145 MB belongs to
  `SystemModuleFinders$SystemImage` - the JDK's mapped module image, not a leak.)
- `merge_shortest_paths java.nio.DirectByteBuffer`: all 1,339 share one GC root.
- `path2gc` of one buffer:
  ```
  DirectByteBuffer @ 0xf0000090
  └ value of HashMap$Node
     └ HashMap$Node[2048]
        └ table of HashMap (size 1,339)
           └ static PENDING_ACKS of class hr.javacro.section1.Ex5
  ```
- 1,339 matches the expected leak: ~26,000 messages x 5% lost acks.

**Why NMT shows +140 MB but the dump only 84 MB:** the dump contains live objects only. The rest of `Other` belongs to
acknowledged buffers that are already garbage, but their native memory is released only when GC collects the small
`DirectByteBuffer` object and its `Cleaner`
runs. NMT shows the real native footprint, including that lag.

## Findings

| # | Problem                                                                                                                                                                          | Severity                                                                                                                  | Location                                              |
|---|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------|
| 1 | Messages whose ack never arrives stay in `PENDING_ACKS` forever - no retransmit timeout, no eviction. Each holds a 64 KB direct buffer                                           | **Critical** - native memory grows ~3 MB/s; container OOM-kill or `OutOfMemoryError: Cannot reserve direct buffer memory` | `Ex5.java:34` (`PENDING_ACKS`), `Ex5.java:39` (`put`) |
| 2 | A direct buffer is allocated per message - native memory is freed only after GC, so even the acked 95% inflate the footprint                                                     | High                                                                                                                      | `Ex5.java:37` (`allocateDirect`)                      |
| 3 | No `-XX:MaxDirectMemorySize` - the limit defaults to `-Xmx` (256 MB), so the process can use heap + 256 MB off-heap, which a container limit sized for the heap will not survive | Medium (config)                                                                                                           | JVM options                                           |
| 4 | Heap-based monitoring (heap used, heap dumps, MAT suspects) does not show the problem                                                                                            | Medium (observability)                                                                                                    | monitoring                                            |

## Action items

1. **Expire pending messages** - store a send timestamp; a scheduled task retransmits or drops entries older than the
   ack timeout (bounded retries), so `PENDING_ACKS` cannot grow unbounded. Also bound its size (backpressure when too
   many messages are in flight).
2. **Pool direct buffers** instead of allocating one per message - return the buffer to the pool on ack or expiry
   (Netty's `PooledByteBufAllocator` + reference counting does exactly this). Alternative on JDK 22+: allocate in an
   `Arena` and close it deterministically.
3. **Set limits explicitly** - `-XX:MaxDirectMemorySize=<n>` and size the container for heap + direct memory +
   metaspace + thread stacks + code cache (NMT `Total committed` is the number to plan for).
4. **Monitor off-heap** - export `BufferPoolMXBean` ("direct": count, memory used), alert on growth; in tests/staging
   run with `-XX:NativeMemoryTracking=summary` and diff against a baseline.
5. **Verify the fix** - rerun: NMT `Other` must plateau, and the `DirectByteBuffer` count in a heap dump must stay
   around the number of in-flight messages.

## Files

- `ex5-nmt-22000-0-baseline-summary.txt` - full NMT summary at the baseline (3 s)
- `ex5-nmt-22000-1-diff-15s.txt`, `ex5-nmt-22000-2-diff-30s.txt` - NMT `summary.diff` against the baseline
- `ex5-process-memory-22000.txt` - OS view of the process (working set, private bytes)
- `ex5-heapinfo-22000.txt` - G1 heap summary at dump time
- `../heap-dumps/ex5-22000.hprof` - heap dump (5 MB)
