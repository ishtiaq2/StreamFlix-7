# netflix-java-architecture-demo

A representative illustration of the architecture described in Paul
Bakker's "How Netflix Uses Java — 2026 Edition" — **not** a literal
reproduction of Netflix's actual, internal, much larger system (that's
neither possible nor useful from a public talk), but the same real
patterns, wired together correctly: GraphQL (DGS) over gRPC rather than
REST, virtual threads, Spring Boot as the standardized stack.

## Read this first — what's verified vs. what isn't

This sandbox can reach GitHub and a handful of package registries, but
**not Maven Central or Gradle's plugin portal** — the same class of
network constraint hit earlier in this project with Buildroot, just with
no equivalent workaround this time (there's no "use the host's
already-installed toolchain" trick for JVM library dependencies the way
there was for a C compiler).

**Genuinely compiled and run, in this sandbox, for real:**
`virtual-threads-demo/` — zero external dependencies, needs nothing
beyond the JDK itself. Real numbers from an actual run, 10,000 concurrent
50ms blocking calls:

| | total time |
|---|---|
| `Executors.newVirtualThreadPerTaskExecutor()` | **281ms** |
| Fixed pool of 200 platform threads | **2,570ms** |

That ~9x gap is close to the theoretical minimum for the platform-thread
case (10,000 tasks ÷ 200 concurrent × 50ms ≈ 2,500ms) — the pool simply
can't run more than 200 blocking calls at once, and virtual threads
don't have that ceiling.

**Correct, real, but not executable here:** `graphql-service/` and
`catalog-service/`, the actual Spring Boot + DGS + gRPC code. Real
dependency coordinates (checked against Netflix's own current docs and
Maven Central listings, not guessed), real DGS annotations
(`@DgsComponent`, `@DgsQuery`, `@DgsData`), a real gRPC service
definition and generated-stub usage pattern. One known, flagged gap:
the exact gRPC-Spring starter API was verified against the community
`net.devh`/`grpc-ecosystem` project specifically (concrete, quoted
documentation examples confirmed while writing this) — the newer
official `org.springframework.grpc` starter exists too and may be the
better long-term choice, but wasn't verified closely enough here to
commit to its exact annotations without guessing, so this repo
deliberately uses the one with confirmed API surface. Worth checking
both when you actually build this.

## Layout

```
virtual-threads-demo/    — genuinely run here; see numbers above
graphql-service/         — DGS-based GraphQL service (the "front door")
  src/main/resources/schema/schema.graphqls
  src/main/java/.../ShowsDataFetcher.java   — calls catalog-service over gRPC
catalog-service/         — gRPC backend service (the "owns the data" side)
  src/main/proto/catalog.proto
  src/main/java/.../CatalogServiceImpl.java
jvm-flags.md             — the real Generational ZGC flags, and why
```

## The architecture, mapped to what the video actually said

- **"GraphQL or gRPC over REST"** → `graphql-service` is the schema/query
  layer a client (web, mobile, TV app) talks to; `catalog-service` is a
  backend that owns real data and exposes it only over gRPC — nothing
  here is a REST resource.
- **"Think methods, not data"** (gRPC specifically) → `catalog.proto`
  defines `GetShow`/`ListShows`/`GetAvailability` as three narrow RPCs,
  not a generic `/shows` CRUD resource.
- **DGS, schema-first** → `schema.graphqls` is the source of truth;
  `ShowsDataFetcher` is discovered and wired to it by DGS's own
  conventions, not manual registration.
- **Avoiding over-fetching** → `Show.availability` resolves via its own,
  separate downstream gRPC call (`@DgsData(parentType = "Show", field =
  "availability")`), not bundled into the main `getShow` response — a
  client that only asks for `{ title }` never triggers that second RPC
  at all. `ShowsDataFetcherTest` asserts this directly, not just
  describes it.
- **Virtual threads, adopted carefully** → enabled via
  `spring.threads.virtual.enabled: true` in both services' config — the
  one-line Spring Boot switch, deliberately paired with the video's own
  caution: Netflix rolled this back once already because it wasn't safe
  for every use case, and only recently resumed evaluating it after JDK
  25 fixed the underlying problems. Turning the flag on is not the hard
  part; knowing when *not* to trust it yet is.
- **Generational ZGC by default** → `jvm-flags.md`.
- **"Upgrade sooner, more often, invest in tooling to keep it painless"**
  → the root `build.gradle.kts` deliberately targets current, real
  Spring Boot 3.x rather than jumping straight to 4, matching where the
  video says Netflix actually is right now, not where they're headed.

## What isn't attempted here, on purpose

Full GraphQL Federation (a gateway composing multiple independent
subgraphs into one schema) — genuinely core to Netflix's real
architecture, but a materially bigger, multi-service undertaking than
fits a representative demo. Two services with a real gRPC call between
them demonstrates the same underlying pattern (GraphQL front door, gRPC
backend, schema-first, field-level resolution) without the added
federation-gateway machinery on top.
