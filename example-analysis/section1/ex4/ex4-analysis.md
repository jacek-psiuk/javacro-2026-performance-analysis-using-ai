# Heap analysis: Ex4 (PID 13148)

Run: `-Xmx256m`, JDK 25.0.4, G1. Heap dump taken after ~12 s (137 MB `.hprof`, 129 MB used of 256 MB). Tools:
`jcmd GC.class_histogram` (x2, 5 s apart), `jcmd GC.heap_dump`, Eclipse MAT headless (`suspects`, `overview`,
`top_components`, query `group_by_value`).

## Evidence

**1. Growth between histograms (5 s apart)**

| Class           | Histogram 1      | Histogram 2       |
|-----------------|------------------|-------------------|
| `byte[]`        | 20,002 / 50.8 MB | 30,402 / 104.3 MB |
| `Ex4$ReportKey` | 4,900            | 10,100            |
| `Ex4$Report`    | 4,900            | 10,100            |
| `HashMap$Node`  | 7,387            | 12,587            |

`ReportKey` and `Report` grow 1:1 at ~1,000/s and never drop - objects are added and never released.

**2. MAT leak suspect**

> The class `hr.javacro.section1.Ex4` occupies 129,832,872 (98.97%) bytes.
> The memory is accumulated in one instance of `java.util.HashMap$Node[]`.

Path to GC root (shortest path to accumulation point):

```
HashMap$Node[32768]          retained 129,831,040
 └ table of HashMap          retained 129,831,088
    └ static CACHE of class Ex4   (GC root: class loaded by AppClassLoader)
```

Accumulated objects: 12,500 `ReportKey`, 12,500 `Report`, 24,999 `byte[]` (128.5 MB, ~10 KB report content each).

**3. MAT `group_by_value Ex4$ReportKey -field userId`** (`ex4-reportkey-by-userid.csv`)

Only **100 distinct userIds** across 12,500 keys - 104 to 152 keys per user. With 3 report types the cache should hold
at most 300 entries; it holds 12,500 and counting.

## Findings

| # | Problem                                                                                                                                                | Severity                                                                     | Location                                                       |
|---|--------------------------------------------------------------------------------------------------------------------------------------------------------|------------------------------------------------------------------------------|----------------------------------------------------------------|
| 1 | `ReportKey` has no `equals`/`hashCode`: identity semantics, every lookup misses, every request inserts a new entry                                     | **Critical** - unbounded growth, OOM in ~25 s at 256 MB                      | `Ex4.java:25` (`ReportKey`), `Ex4.java:47` (`computeIfAbsent`) |
| 2 | Cache is a static, unbounded `HashMap` with no eviction - even with correct keys it is only safe because the key space is small                        | **High** - latent leak if key space grows (more users, report params, dates) | `Ex4.java:43` (`CACHE`)                                        |
| 3 | Cache is a plain `HashMap` without synchronization - fine in this single-threaded demo, a data race if `getReport` is ever called from request threads | Medium (latent)                                                              | `Ex4.java:43`                                                  |
| 4 | Each key holds its own copy of `"user-N"` String (~12,400 duplicates)                                                                                  | Low - MAT does not flag it (<1 MB); disappears with fix #1                   | `Ex4.java:62`                                                  |

## Action items

1. **Fix key equality** - make `ReportKey` a `record ReportKey(String userId, String reportType)`
   (or implement `equals` + `hashCode` over both fields). Add a unit test: two keys with equal fields must hit the same
   cache entry.
2. **Bound the cache** - replace the static `HashMap` with a size/TTL-bounded cache (Caffeine: `maximumSize`,
   `expireAfterWrite`), or at minimum a `LinkedHashMap` with
   `removeEldestEntry`. Expose cache size as a metric.
3. **Make it thread-safe** - Caffeine covers this; otherwise `ConcurrentHashMap`
   (keeping `Ex2Fixed`'s lesson: no slow rendering inside `computeIfAbsent`).
4. **Operational safety nets**
    - Run with `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=<dir>` so the next OOM leaves evidence.
    - Alert on old-gen usage after GC trending up (a leak), not on raw heap usage.
    - For slow leaks in production, enable JFR (`jdk.OldObjectSample` with `path-to-gc-roots=true`)
      instead of taking full dumps.
5. **Verify the fix** - rerun the same scenario: cache size must plateau at 300 and two histograms taken minutes apart
   must show stable `ReportKey`/`Report` counts.

## Files

- `ex4-histogram-13148-1.txt`, `ex4-histogram-13148-2.txt` - class histograms 5 s apart
- `ex4-heapinfo-13148.txt` - G1 heap summary at dump time
- `ex4-13148_Leak_Suspects.zip`, `ex4-13148_System_Overview.zip`, `ex4-13148_Top_Components.zip` - MAT HTML reports
- `ex4-reportkey-by-userid.csv` - MAT query: ReportKey instances grouped by userId
- `ex4-13148.hprof` - the heap dump itself (137 MB). MAT recreates its index files next to it on first parse.
