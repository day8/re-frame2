# re-frame.performance

`re-frame.performance` is re-frame2's **production timing** instrumentation surface. It is deliberately separate from the trace channel: trace runs in dev and is too noisy to keep on in production, whereas the Performance-API path here is built to survive production and is **off by default** behind a single compile-time `goog-define`. When a consumer opts in at build time, re-frame2 brackets its four hot paths — event dispatch, subscription recompute, fx walk, and view render — in `performance.mark` / `performance.measure` calls (User-Timing entries named `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`) that any `PerformanceObserver` — including an APM's — can read. With the flag left at its default, Closure DCE elides every call site under `:advanced`, so a shipped binary carries zero User-Timing instrumentation. The whole surface is one var, `enabled?`; the JVM is a no-op (the Performance API is browser-only).

```clojure
(:require [re-frame.performance :as perf])
```

> **Note** — there is nothing to call from this namespace at runtime. You opt timing in by referencing `re-frame.performance/enabled?` fully-qualified in your build's `:closure-defines` (see below); the `:as perf` alias is shown only for consistency with the other API docs.

## Compile-time flags

### `enabled?`

- **Kind**: Var (`^boolean`)
- **Description**: `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure! ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op.

```clojure
;; Compile-time gate — the User-Timing bracket DCEs unless flipped on.
(when re-frame.performance/enabled?
  (js/performance.mark "rf:event:start"))
```

## See also

- [Find and fix a slow view](../core/how-to/fix-a-slow-view.md) — turning this channel on and reading the entries.
- [Configure dev and prod](../core/how-to/configure-dev-and-prod.md) — how the perf flag composes with `goog.DEBUG` across build profiles.
- [Observability](../core/concepts/observability.md) — where this production-survivable timing channel sits alongside the trace and error surfaces.
