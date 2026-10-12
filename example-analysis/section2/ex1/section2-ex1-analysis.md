# JFR analysis: section2 Ex1 (order processing) vs Ex1Fixed

Both recorded for 30 s with `-XX:StartFlightRecording=duration=30s,settings=profile`, `-Xmx512m`, 8 worker threads, JDK
25.0.4, same order generator (200 items/order, 10% invalid quantities). Analysed only with `jfr summary`,
`jfr view <view>` and `jfr print` (+ awk for percentiles).

## Ex1 - findings, ranked by impact

| # | Problem                                                                                  | JFR evidence                                                                                                                                                                                                      | Impact                                                                                                                                     | Location                                                           |
|---|------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------|
| 1 | **Lock contention** on the audit log: the slow audit write happens inside `synchronized` | `contention-by-site`: `Ex1.audit` 9,657 times (≈ every order), avg **23.7 ms**, max 62.6 ms. Sum of `jdk.JavaMonitorEnter` = **229 s blocked** of 240 s worker time                                               | **95%** of worker time; 86% of each order's latency (23.7 of 27.5 ms). JVM CPU ≈ 0% (`cpu-load-samples`) - the app is waiting, not working | `Ex1.java:99` (`synchronized`), `Ex1.java:101` (`sleep` inside it) |
| 2 | **Exceptions as control flow** for invalid quantities                                    | `exception-count`: **177,819** thrown in 30 s (~5,900/s, ~18 per order); `exception-by-type`/`-by-site`: `NumberFormatException.forInputString`; `allocation-by-site`: `Throwable.fillInStackTrace` 3%            | Hidden behind #1 today; stack-trace capture becomes real CPU cost once #1 is fixed                                                         | `Ex1.java:76`                                                      |
| 3 | **Regex compiled per call** (`String.matches`)                                           | `allocation-by-site`: `Pattern.compile` 14.6% + `Pattern$BitClass.<init>` 11.0% + more `Pattern` frames ≈ **37%** of allocation pressure; `hot-methods`: `Pattern.compile`, `Pattern.clazz`, `CharProperty.study` | Biggest allocation source; CPU share small only because CPU is idle                                                                        | `Ex1.java:70`                                                      |
| 4 | **`String.format` + boxing** per invoice line                                            | `hot-methods`: `Formatter.format`, `FormatSpecifierParser`, `DecimalDigits` ≈ **25%** of the (few) CPU samples; `allocation-by-site`: `Formatter.parse` 7.3%                                                      | Second-biggest CPU user                                                                                                                    | `Ex1.java:82-87`                                                   |

Supporting data: only **215** `jdk.ExecutionSample` events in 30 s with 8 threads - the strongest hint that threads are
blocked, not computing. GC: 14 young pauses, 54.7 ms total - not a problem.

Custom event `hr.javacro.OrderProcessed` (9,683 events): latency **avg 27.5 ms, p50 27.3, p90 29.7, p99 32.2, max 164
ms**. Avg latency ≈ 8 workers / 320 orders/s - consistent with a single serialized section (Little's law), and the lock
wait explains almost all of it.

## Action items

1. Move the slow audit write out of the lock: hand records to a queue and let one writer persist them in batches (or use
   an async/batched client). Never do I/O inside `synchronized`.
2. Validate quantities before parsing (`isQuantity` check), so invalid input is a branch, not an exception.
3. Compile the SKU regex once: `static final Pattern SKU = Pattern.compile(...)`.
4. Replace `String.format` in the hot loop with `StringBuilder` appends; use `double[]` / primitive
   `double` instead of `Map<Long, Double>` / `Double`.
5. Keep the custom `OrderProcessed` event, but in production add `@Threshold("10 ms")` so only slow orders are recorded
   (Ex1Fixed emits 1.24 M events / 25 MB in 30 s without it).
6. Guard it in CI: record a fixed load test and fail the build if contention, exceptions per order or allocation per
   order exceed a baseline.

## Verification: Ex1Fixed, same load, same JFR settings

| Metric                                    | Ex1                         | Ex1Fixed                          | Change                            |
|-------------------------------------------|-----------------------------|-----------------------------------|-----------------------------------|
| Throughput                                | ~320 orders/s               | ~43,000 orders/s                  | **~130x**                         |
| Orders in the 30 s recording              | 9,683                       | 1,242,626                         |                                   |
| Order latency avg / p50 / p99             | 27.5 / 27.3 / 32.2 ms       | 0.18 / 0.14 / 0.43 ms             | **~150x**                         |
| Monitor contention (`contention-by-site`) | `Ex1.audit` 9,657 x 23.7 ms | none in app code (1 JFR-internal) | gone                              |
| Exceptions thrown                         | 177,819                     | 4                                 | gone                              |
| JVM CPU (user)                            | ~0%                         | ~80% (machine 100%)               | now CPU-bound - it does real work |
| CPU samples                               | 215                         | 8,454                             |                                   |
| GC pauses                                 | 14 (1 per ~690 orders)      | 264 (1 per ~4,700 orders)         | **~7x less GC per order**         |
| Avg GC pause                              | 3.9 ms                      | 1.5 ms                            |                                   |

Ex1Fixed's profile now shows the actual work: `hot-methods` = `StringBuilder.append`, regex *matching* (not compiling),
digit formatting; `allocation-by-site` = `Pattern.matcher`/`Matcher.<init>`
and the order generator (`Integer.toString`). The next optimisation, if needed, would be reusing a `Matcher` per thread
or a hand-written SKU check - but the service is no longer the bottleneck.

Lesson for comparisons: normalise per unit of work (per order), not per recording - Ex1Fixed has 19x more GC pauses but
does 128x more work.

## Files

- `section2-ex1.jfr`, `section2-ex1-output.txt` - Ex1 recording and app output
- `section2-ex1fixed.jfr`, `section2-ex1fixed-output.txt` - Ex1Fixed recording and app output
