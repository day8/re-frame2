# re-frame.performance

`re-frame.performance` is re-frame2's **production timing** instrumentation surface. It is deliberately separate from the trace channel: trace runs in dev and is too noisy to keep on in production, whereas the Performance-API path here is built to survive production and is **off by default** behind a compile-time `goog-define`. When a consumer opts in at build time, re-frame2 brackets its four hot paths — event dispatch, subscription recompute, fx walk, and view render — in options-bag `performance.measure` calls (User-Timing measure entries named `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`; no `performance.mark` entries are allocated) that any `PerformanceObserver` — including an APM's — can read. Delivery is observer-first: each measure is cleared by name immediately after emit, so the retained buffer that `performance.getEntriesByType("measure")` reads stays empty unless the second flag, `retain-entries?`, is flipped on. With the flags left at their defaults, Closure DCE elides every call site under `:advanced`, so a shipped binary carries zero User-Timing instrumentation. The whole surface is two compile-time flags, `enabled?` and `retain-entries?`; the JVM is a no-op (the Performance API is browser-only).

```clojure
(:require [re-frame.performance :as perf])
```

> **Note** — there is nothing to call from this namespace at runtime. You opt timing in by referencing the flags fully-qualified in your build's `:closure-defines` (see below); the `:as perf` alias is shown only for consistency with the other API docs.

## Compile-time flags

### `enabled?`

- **Kind**: Var (`^boolean`)
- **Description**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in options-bag `performance.measure` calls — `performance.measure(name, {start, end})` with numeric `performance.now()` timestamps; no `performance.mark` entries are allocated (User-Timing measure entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). The measure is emitted inside a `try/finally`, so the entry lands even when the bracketed body throws (the exception still propagates), and is cleared by name (`performance.clearMeasures`) immediately after emit unless `retain-entries?` is on — a live `PerformanceObserver` still receives it, since observer callbacks fire at `measure()` time, before the clear. **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op.

```clojure
;; shadow-cljs.edn — flip the compile-time gate. Default off:
;; with the default, every bracket site DCEs under :advanced.
{:builds {:app {:compiler-options
                {:closure-defines {re-frame.performance/enabled? true}}}}}
```

### `retain-entries?`

- **Kind**: Var (`^boolean`)
- **Description**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/retain-entries? true}` to skip the per-emit `performance.clearMeasures(name)` so measure entries persist in the host's retained User-Timing buffer for one-shot `performance.getEntriesByType("measure")` readers (DevTools / console workflows). Default `false`: each entry is delivered to any live `PerformanceObserver` at `measure()` time, then cleared, so the buffer does not grow across a long-running (RUM) session — with the default, `getEntriesByType` returns no `rf:*` entries. No effect unless `enabled?` is also on. **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. CLJS-only — JVM is a no-op.

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
