# re-frame.performance

`re-frame.performance` is re-frame2's production timing instrumentation surface, separate from the dev-only trace channel. It brackets four hot paths — event dispatch, subscription recompute, fx walk, and view render — in options-bag `performance.measure` calls that any `PerformanceObserver` (including an APM's) can read.

- Emits User-Timing measure entries named `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`. No `performance.mark` entries are allocated.
- Delivery is observer-first: each measure is cleared by name immediately after emit, so the buffer read by `performance.getEntriesByType("measure")` stays empty unless `retain-entries?` is on.
- The whole surface is two compile-time flags, `enabled?` and `retain-entries?`, both **off by default**. Under `:advanced` with the defaults, Closure DCE elides every call site, so a shipped binary carries zero User-Timing instrumentation.
- CLJS-only: the JVM is a no-op (the Performance API is browser-only).

```clojure
(:require [re-frame.performance :as perf])
```

> **Note** — there is nothing to call from this namespace at runtime. You opt timing in by referencing the flags fully-qualified in your build's `:closure-defines` (see below); the `:as perf` alias is shown only for consistency with the other API docs.

See [Find and fix a slow view](../core/how-to/fix-a-slow-view.md) for turning this channel on and reading the entries.

## Compile-time flags

### `enabled?`

- **Kind**: Var (`^boolean`)
- **Signature**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}`.
- **Description**: Gates the four `performance.measure` brackets (event dispatch / sub recompute / fx walk / view render).
    - Emits `performance.measure(name, {start, end})` with numeric `performance.now()` timestamps; User-Timing measure entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`. No `performance.mark` entries are allocated.
    - Each measure is emitted inside a `try/finally`, so the entry lands even when the bracketed body throws (the exception still propagates).
    - The entry is cleared by name (`performance.clearMeasures`) immediately after emit unless `retain-entries?` is on. A live `PerformanceObserver` still receives it — observer callbacks fire at `measure()` time, before the clear.
    - **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` with the default, every bracket DCEs. CLJS-only; the JVM is a no-op.

```clojure
;; shadow-cljs.edn — flip the compile-time gate. Default off:
;; with the default, every bracket site DCEs under :advanced.
{:builds {:app {:compiler-options
                {:closure-defines {re-frame.performance/enabled? true}}}}}
```

### `retain-entries?`

- **Kind**: Var (`^boolean`)
- **Signature**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/retain-entries? true}`.
- **Description**: Skips the per-emit `performance.clearMeasures(name)` so measure entries persist in the host's retained User-Timing buffer.
    - Enables one-shot `performance.getEntriesByType("measure")` readers (DevTools / console workflows).
    - Default `false`: each entry is delivered to any live `PerformanceObserver` at `measure()` time, then cleared, so the buffer does not grow across a long-running (RUM) session — with the default, `getEntriesByType` returns no `rf:*` entries.
    - No effect unless `enabled?` is also on.
    - **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. CLJS-only; the JVM is a no-op.

```clojure
;; shadow-cljs.edn — retain entries for one-shot DevTools / console reads.
;; Leave off for long-running sessions; read via a PerformanceObserver.
{:builds {:app {:compiler-options
                {:closure-defines {re-frame.performance/enabled?        true
                                   re-frame.performance/retain-entries? true}}}}}
```

## See also

- [Find and fix a slow view](../core/how-to/fix-a-slow-view.md) — turning this channel on and reading the entries.
- [Configure dev and prod](../core/how-to/configure-dev-and-prod.md) — how the perf flag composes with `goog.DEBUG` across build profiles.
- [Observability](../core/observability.md) — where this production-survivable timing channel sits alongside the trace and error surfaces.
