# JVM flags — Generational ZGC

Can't meaningfully *demonstrate* GC pause-time behavior in a toy demo
app (you need real allocation pressure and a real heap size for the
tradeoff to show up at all) — but the real, current flags, and why each
one, are worth having correct:

```
-XX:+UseZGC
-XX:+ZGenerational
-Xlog:gc*:file=gc.log:time,uptime,level,tags
```

- `-XX:+UseZGC` — confirmed via Netflix's own engineering blog: they
  switched by default from G1 to Generational ZGC on JDK 21+, citing
  concurrent garbage collection's benefits at their scale.
- `-XX:+ZGenerational` — ZGC runs generational by default as of JDK 21
  (this flag is mostly there for explicitness, or if running an earlier
  JDK where it was opt-in). Generational ZGC specifically separates
  young/old generations the way G1 always did, which is *why* it ended
  up beating plain (non-generational) ZGC for Netflix across the board,
  per the video — most objects still die young, and treating all
  generations identically (original ZGC's model) wastes concurrent-GC
  effort on objects that would've been reclaimed cheaply anyway.
- The real tradeoff, stated plainly in the video: **more CPU spent on
  GC, in exchange for dramatically lower pause times.** Worth being able
  to say why that's the right trade for Netflix specifically — a paused
  JVM during a GC stop-the-world event is a paused response to whatever
  request that thread was serving, and at Netflix's request volume, tail
  latency matters more than raw CPU efficiency for most of their
  services. That's a real, statable tradeoff, not a universal "ZGC is
  just better" claim — a batch job that's CPU-bound and doesn't care
  about individual request latency might reasonably still prefer G1's
  lower CPU overhead.

## Comparing GCs for real, if you build this yourself

```bash
java -XX:+UseG1GC -Xlog:gc:file=g1.log -jar app.jar
java -XX:+UseZGC -XX:+ZGenerational -Xlog:gc:file:zgc.log -jar app.jar
```
Run the same load against both, compare `gc.log`'s pause-time
distribution, not just throughput — the whole point of this tradeoff
only shows up in the tail (p99/p999), not the average.
