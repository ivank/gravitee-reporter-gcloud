# Refactor Plan — Modern Java 21

## Overview

Five independent changes, ordered by impact. None break the public API or test surface.

---

## 1. Replace hand-crafted JSON with Jackson (`GCloudEntrySerializer`)

**Problem.** `GCloudEntrySerializer` is 240 lines of manual string concatenation: a hand-rolled
`jsonString()` escaper, a recursive `toJson()` dispatcher, a fragile `boolean first` comma-tracking
pattern in `jsonHttpRequest`, and a full `jsonObject` / `jsonStringMap` / `jsonHttpRequest` suite.
The comment admits the reason: "avoid pulling a JSON library into compile scope." But `jackson-databind`
is already present on every Gravitee gateway's runtime classpath (it is a transitive dependency of
`gravitee-common`), so adding it at `provided` scope bundles nothing extra into the plugin ZIP.

**Change.**

- In `pom.xml`: promote `jackson-databind` from `test` to `provided` scope (remove the version pin;
  it is managed by the APIM BOM).
- Delete all helper methods in `GCloudEntrySerializer` (`jsonString`, `jsonObject`, `jsonStringMap`,
  `jsonHttpRequest`, `toJson`, `encode`).
- Replace `serialize(List<GCloudLogEntry>)` with an `ObjectMapper`-based implementation:
  build an `ObjectNode` for the request body, an `ArrayNode` for `entries`, and call
  `objectMapper.writeValueAsString(root)`. Each entry becomes an `ObjectNode` assembled with
  `node.put(...)` and `node.set(...)` — no escaping, no comma tracking, no string format calls.
- The only logic that must survive verbatim is the `static toProtoDuration(long ms)` method — it is
  domain logic (protobuf Duration format), not serialization.
- `GCloudEntrySerializer` shrinks from ~240 lines to ~60 lines.
- Tests: `GCloudEntrySerializerTest` can drop all the escape-sequence and comma-placement assertions;
  keep only the structural / field-presence tests.

---

## 2. Remove boilerplate from `GCloudReporterConfiguration` with Lombok

**Problem.** `GCloudReporterConfiguration` is 183 lines: 12 `@Value` fields each paired with a
getter and setter. The Spring `@Value`-injection contract only requires that the bean is a plain class
with a no-arg constructor and setters — it does not require hand-written methods.

**Change.**

- Add `lombok` as an annotation-processor-only dependency (compile-time only, zero runtime footprint,
  no change to the plugin ZIP).
- Annotate the class with `@Getter @Setter`. Delete all 24 getter/setter methods.
- The class shrinks from 183 lines to ~30 lines (field declarations + `@Value` annotations + the
  `resourceLabels` field with its inline initializer).
- No changes to callers; Lombok generates byte-compatible bytecode.

---

## 3. Move mapper instantiation to Spring; use constructor injection in `GCloudReporter`

**Problem — two sub-issues:**

a. `GCloudReporter.doStart()` manually `new`s up four mapper objects, mixing lifecycle and
   construction concerns. If `cfg` were ever changed after construction this would silently use
   stale config.

b. `GCloudReporter` uses field-level `@Autowired` (two fields). Field injection hides dependencies,
   makes the class harder to test without a Spring context, and is considered an anti-pattern in
   modern Spring.

**Change.**

- In `GCloudReporterSpringConfiguration`: add four `@Bean` methods — one for each mapper, each
  accepting `GCloudReporterConfiguration cfg` as a parameter.
- In `GCloudReporter`: replace the two `@Autowired` fields with a single `@Autowired` constructor
  accepting `GCloudReporterConfiguration`, `GCloudLogWriter`, and all four mappers. Store them as
  `final` fields.
- `doStart()` shrinks to `super.doStart()` + one log line. The `if (!cfg.isEnabled()) return` guard
  stays, but mapper instantiation disappears entirely.
- Bonus: tests can construct `GCloudReporter` directly without needing Spring `@MockBean`.

---

## 4. Simplify mapper null-coalescing verbosity

**Problem.** Every mapper repeats the pattern:

```java
payload.put("api_id", metrics.getApiId() != null ? metrics.getApiId() : "");
```

This appears ~20 times across the four mapper classes. Similarly, `GCloudLabels.ifPresent` is called
3–5 times per mapper as a series of identical-looking statements.

**Changes.**

### 4a. Replace ternary null-to-empty with `Objects.toString(value, "")`

```java
// before
payload.put("api_id", metrics.getApiId() != null ? metrics.getApiId() : "");
// after
payload.put("api_id", Objects.toString(metrics.getApiId(), ""));
```

Apply to all four mappers.

### 4b. Replace `GCloudLabels.ifPresent` call series with a varargs factory

Replace the `GCloudLabels` utility class with a package-private static helper (or a method on
a shared `Mappers` util) that accepts alternating key/value pairs and returns a `Map<String,String>`:

```java
static Map<String, String> labelsOf(String... kvPairs) {
    // iterate pairs, skip if value is null/blank
}
```

Usage becomes one call per mapper instead of 3–5 sequential `ifPresent` calls:

```java
var labels = labelsOf(
    "gravitee.api_id",      metrics.getApiId(),
    "gravitee.api_name",    metrics.getApiName(),
    "gravitee.application", metrics.getApplicationId()
);
```

`GCloudLabels` class is deleted; the single `ifPresent` method is no longer needed as a standalone
utility once callers use the factory.

---

## 5. Clean up `GCloudLogWriter`

**Problem — two small issues:**

a. `AtomicBoolean running` is set to `false` in `close()` but never read anywhere. Dead code.

b. `sendWithRetry(batch, attempt)` is recursive. While safe on virtual threads (no stack overflow
   risk), a loop is simpler to read. Also: the current `InterruptedException` catch sets the
   interrupt flag but then falls through to the outer `catch (Exception e)` — that outer handler
   re-logs the interruption as an "unexpected exception", which is misleading.

**Changes.**

- Delete the `AtomicBoolean running` field and its `running.set(false)` call in `close()`.
- Convert `sendWithRetry` to an iterative `for (int attempt = 0; attempt <= MAX_RETRIES; attempt++)`
  loop. The retry condition (`status == 429 || 500 || 503`) becomes a `continue`; permanent failure
  becomes a `break` with a log. Eliminates the recursive call, makes the flow linear.
- Move `InterruptedException` handling outside the generic `Exception` catch so the log message
  accurately reflects what happened.

---

## File change summary

| File | Action |
|---|---|
| `pom.xml` | `jackson-databind` → `provided` scope; add Lombok annotation processor |
| `GCloudEntrySerializer.java` | Rewrite with Jackson; ~240 → ~60 lines |
| `GCloudReporterConfiguration.java` | Add `@Getter @Setter`; delete 24 methods; ~183 → ~30 lines |
| `GCloudReporter.java` | Constructor injection; remove mapper instantiation from `doStart()` |
| `GCloudReporterSpringConfiguration.java` | Add four mapper `@Bean` methods |
| `GCloudLabels.java` | Delete |
| `MetricsToLogEntryMapper.java` | `Objects.toString`; `labelsOf(...)` |
| `LogToLogEntryMapper.java` | `Objects.toString`; `labelsOf(...)` |
| `EndpointStatusToLogEntryMapper.java` | `Objects.toString`; `labelsOf(...)` |
| `MessageMetricsToLogEntryMapper.java` | `Objects.toString`; `labelsOf(...)` |
| `GCloudLogWriter.java` | Delete `running`; iterative retry loop |

No new files. No changes to `GCloudLogEntry`, `GCloudHttpRequest`, or `GCloudSeverity` (records /
enum are already idiomatic). No changes to tests except dropping obsolete escape-sequence assertions
from `GCloudEntrySerializerTest`.
