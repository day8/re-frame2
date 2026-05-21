# 11 — Instrumentation

The instrumentation surface is two surfaces stacked. The first is **dev-only**: a trace bus that emits one richly-tagged record per noteworthy event (dispatch, sub recompute, fx walk, render, machine transition, schema validation, error), buffered into a ring, fanned out to registered listeners synchronously, and elided entirely under `:advanced` + `goog.DEBUG=false`. The second is **always-on**: a pair of tight, production-survivable substrates (event-emit, error-emit) that deliver one record per processed event and one record per `:rf.error/*` event. Together they let the same app feed Causa in dev and Sentry / Datadog / Honeybadger in production from the same registration.

This is the load-bearing surface for the pair-shape architecture — every tool that watches a running re-frame2 app composes against one of these surfaces. Causa subscribes to the dev trace bus. The MCP servers do the same. The event-emit substrate is what hosted observability shippers consume. The error-emit substrate is what hosted error monitors consume. Same registrations, three audiences.

This chapter covers the event-emit listener surface, the error-emit listener surface, the dev-only tracing surface, the epoch buffer (time-travel), the performance instrumentation gate, the source-coord annotation contract, the wire-boundary elision walker, and the error contract.

## Event-emit (always-on, production-survivable)

A minimal always-on listener surface that survives `:advanced` + `goog.DEBUG=false` and delivers one tight record per processed event. The intended consumers are hosted observability back-ends (Datadog, Honeycomb, Sentry, …). **Parallel to (not a fallback for) the dev-only trace surface; per-event only — no per-sub, per-fx, or per-`:event/db-changed` records.**

Record shape: `{:event :event-id :frame :time :outcome :elapsed-ms}`. The `:event` slot is passed through `elide-wire-value` (see below) once before fan-out, so schema-marked `:sensitive?` paths land as `:rf/redacted` and `:large?` paths land as `:rf.size/large-elided`.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `register-event-listener!` | Fn | `(register-event-listener! id listener-fn)` | v1 | Receive one event-record per processed event. Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. |
| `unregister-event-listener!` | Fn | `(unregister-event-listener! id)` → nil | v1 | The inverse. |

## Error-emit (always-on, production-survivable)

Sibling of the event-emit surface above. Runs through the SAME always-on error-emit substrate as the per-frame `:on-error` slot ([002-Frames.md](../../spec/002-Frames.md)) but along an INDEPENDENT corpus-wide fan-out path. Survives `:advanced` + `goog.DEBUG=false`. Intended consumers are hosted error monitors (Sentry, Honeybadger, Rollbar).

Record shape: `{:error :event :event-id :frame :time :exception :elapsed-ms}`. The `:event` slot is passed through `elide-wire-value` once before fan-out (same redaction posture as event-emit). The two paths from the substrate (corpus-wide listeners AND the per-frame `:on-error` policy fn) are mutually isolated; either may throw without affecting the other.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `register-error-listener!` | Fn | `(register-error-listener! id listener-fn)` | v1 | Receive one error-record per `:rf.error/*` event. Re-registering the same `id` replaces. Returns `id`. **Always-on**: survives CLJS `:advanced` + `goog.DEBUG=false`. |
| `unregister-error-listener!` | Fn | `(unregister-error-listener! id)` → nil | v1 | The inverse. |

## Tracing (dev-only)

The rich-detail trace surface. **Dev-only — elided in production via Closure DCE under `:advanced` + `goog.DEBUG=false`.** See [009 §Tracing](../../spec/009-Instrumentation.md) for emit semantics and synchronous listener delivery.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `register-listener!` | Fn | `(register-listener! key callback-fn)` | v1 (dev-only) | "Receive every trace event the runtime emits." Synchronous delivery; the callback returns before the next trace event is processed. |
| `unregister-listener!` | Fn | `(unregister-listener! key)` → nil | v1 (dev-only) | The inverse. |
| `emit-trace-event!` | Fn | `(emit-trace-event! op-type operation tags)` → nil | v1 (dev-only) | "Emit a custom trace event." Use sparingly — the framework emits the load-bearing events; custom emission is for app-specific cross-cutting concerns the framework can't know about. |
| `re-frame.interop/debug-enabled?` | Var | `^boolean` | v1 | **CLJS:** alias of `goog.DEBUG` — constant-folded by Closure under `:advanced`, so `:advanced` + `goog.DEBUG=false` builds DCE every `(when interop/debug-enabled? ...)` branch. **JVM:** a `def` read ONCE at ns-load from the Java system property `-Dre-frame.debug` (winning on conflict) or the environment variable `RE_FRAME_DEBUG`; defaults `true` (dev parity). Accepts the conventional false-y vocabulary case-insensitively (`false`, `0`, `no`, `off`, empty string) with whitespace trimmed; anything else leaves the flag at `true`. SSR / webhook receivers / long-running JVMs facing untrusted input MUST set the gate `false` explicitly. |
| `re-frame.performance/enabled?` | Var | `^boolean` | v1 | `goog-define`d (CLJS) / `^:const false` (JVM). Set via `:closure-defines {re-frame.performance/enabled? true}` to bracket event dispatch / sub recompute / fx walk / view render in `performance.mark` + `performance.measure` calls (User-Timing entries `rf:event:*`, `rf:sub:*`, `rf:fx:*`, `rf:render:*`). **Compile-time only** — not a `(rf/configure ...)` knob; runtime mutation has no effect. Default `false`; under `:advanced` + default the bracket DCEs and shipped binaries carry zero User-Timing instrumentation. CLJS-only — JVM is a no-op. |
| `trace-buffer` | Fn | `(trace-buffer)` / `(trace-buffer opts)` → vector of trace events, oldest-first | v1 (dev-only) | "What's in the ring right now?" Reads the buffer non-destructively. Pair tools and Causa use this for post-mortem inspection. |
| `clear-trace-buffer!` | Fn | `(clear-trace-buffer!)` → nil | v1 (dev-only) | Empty the ring. |
| `(rf/configure :trace-buffer {:depth N})` | — | | v1 (dev-only) | Buffer depth knob. See [01 — Core §Configure keys](01-core.md#runtime-configuration-configure). |
| `group-cascades` | Fn | `(group-cascades events)` → vector of cascade records | v1 (dev-only) | Pure data projection of a list of trace events into per-cascade records `{:dispatch-id :event :handler :fx :effects :subs :renders :other}`, sorted by emission order. JVM-runnable. Re-exported from `re-frame.trace.projection`. |
| `domino-bucket` | Fn | `(domino-bucket trace-event)` → `#{:event :handler :fx :effect :sub :render :other}` | v1 (dev-only) | Classify a raw trace event into the six-domino slot used by `group-cascades`. Pure. |

### Trace-emission opt-out

Event-handler registration accepts a `:rf.trace/no-emit? true` metadata flag. When set, the runtime suppresses **every** trace emission and event-emit record within the handler's scope — the handler runs invisibly to the trace surface, the event-emit substrate, and (transitively) the epoch buffer.

| Metadata key | Where | Value | Default | Effect |
|---|---|---|---|---|
| `:rf.trace/no-emit?` | `reg-event-db` / `reg-event-fx` / `reg-event-ctx` metadata map | boolean | `false` | When `true`, suppresses all trace + event-emit emissions inside the handler's scope. |

Used by framework-internal bookkeeping handlers (Causa, Story, re-frame2-pair-mcp, story-mcp) that would otherwise saturate the trace stream. The `:rf.trace/*` namespace is framework-owned (per [Conventions §Reserved namespaces](../../spec/Conventions.md#reserved-namespaces-framework-owned)).

## Epoch history (Tool-Pair)

Per-frame epoch snapshots, recorded on each drain-completion in dev builds. Used by pair-shaped tools for time-travel and post-mortem analysis. **Production builds elide entirely.**

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `epoch-history` | Fn | `(epoch-history frame-id)` → vector of epoch records | v1 (dev-only) | Returns `[]` for an unknown / destroyed frame. |
| `restore-epoch` | Fn | `(restore-epoch frame-id epoch-id)` → boolean | v1 (dev-only) | Restore the frame's `app-db` to the named epoch. Returns `true` on success; `false` for an unknown / destroyed frame (and emits `:rf.error/no-such-handler` of kind `:frame`). |
| `reset-frame-db!` | Fn | `(reset-frame-db! frame-id new-db)` → boolean | v1 (dev-only) | Pair-tool write surface (state injection). Direct write to `app-db` — bypasses the cascade. Returns `true` on success. |
| `register-epoch-listener!` | Fn | `(register-epoch-listener! key callback-fn)` | v1 (dev-only) | Process-global assembled-epoch listener. A callback whose previously-observed frame is destroyed receives a one-shot `:rf.epoch.cb/silenced-on-frame-destroy` trace. |
| `unregister-epoch-listener!` | Fn | `(unregister-epoch-listener! key)` | v1 (dev-only) | The inverse. |
| `(rf/configure :epoch-history {:depth N :trace-events-keep N :redact-fn fn})` | — | | v1 (dev-only) | Buffer-depth and redactor knobs. See [01 — Core §Configure keys](01-core.md#runtime-configuration-configure). |

### Trace events emitted by epoch-history machinery

| `:operation` | Tags |
|---|---|
| `:rf.epoch/snapshotted` | `:frame`, `:epoch-id`, `:event-id` |
| `:rf.epoch/restored` | `:frame`, `:epoch-id` |
| `:rf.epoch/db-replaced` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-unknown-epoch` | `:frame`, `:epoch-id`, `:history-size` |
| `:rf.epoch/restore-schema-mismatch` | `:frame`, `:epoch-id`, `:schema-digest-recorded`, `:schema-digest-current`, `:failing-paths` |
| `:rf.epoch/restore-missing-handler` | `:frame`, `:epoch-id`, `:missing` |
| `:rf.epoch/restore-version-mismatch` | `:frame`, `:epoch-id`, `:machine-id`, `:version-recorded`, `:version-current` |
| `:rf.epoch/restore-during-drain` | `:frame`, `:epoch-id` |
| `:rf.epoch/restore-non-ok-record` | `:frame`, `:epoch-id`, `:outcome`, `:halt-reason` |
| `:rf.epoch/reset-frame-db-during-drain` | `:frame` |
| `:rf.epoch/reset-frame-db-schema-mismatch` | `:frame`, `:failing-paths` |
| `:rf.epoch.cb/silenced-on-frame-destroy` | `:frame`, `:cb-id` |

## The wire-boundary walker

`elide-wire-value` is the framework primitive that walks tree-shaped values at the wire boundary and substitutes elision markers for sensitive or large slots. **This walker is the single normative emission site for the `:rf/redacted` sensitive sentinel and the `:rf.size/large-elided` size marker.** Per-tool reimplementation is prohibited.

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `elide-wire-value` | Fn | `(elide-wire-value v opts)` → `v` or an elision-marker substitution | v1 | Walk `v` consulting `[:rf/elision :declarations]` and `[:rf/elision :sensitive-declarations]` of the named frame's `app-db`. Substitute `:rf/redacted` for sensitive slots and `:rf.size/large-elided` markers for large slots. |
| `elision-declarations` | Fn | `(elision-declarations)` / `(elision-declarations frame-id)` | v1 | Read the current `[:rf/elision :declarations]` map for the frame (or `{}`). Pair-tool / introspection reader. |
| `populate-elision-from-schemas!` | Fn | `(populate-elision-from-schemas!)` / `(populate-elision-from-schemas! frame-id)` → vector of paths populated | v1 | Boot-time hydrator that walks the frame's registered app-schemas and writes `{:large? true :source :schema}` declarations for every path whose Malli schema carries `:large? true`. Idempotent. |

Composition rule: when both predicates match (sensitive AND large for the same path), **sensitive drop wins** — the size marker is suppressed because it would leak `:path` / `:bytes` / `:digest` from a sensitive slot.

See [08 — Schemas](08-schemas.md) for the registration side (`add-marks`, `set-marks`, `reg-app-schema` with `:sensitive?` / `:large?` flags).

## Privacy predicate

| API | M/Fn | Signature | Status | Intuition |
|---|---|---|---|---|
| `sensitive?` | Fn | `(sensitive? trace-event)` → `boolean` | v1 | True iff `trace-event` is a map carrying `:sensitive? true` at the top level (not under `:tags`). The framework-published predicate every consumer composes against — replaces per-consumer reimplementations of the same five-token check. |
| `redact-interceptor` | Fn | `(redact-interceptor paths)` → interceptor | post-v1 (planned) | Positional interceptor that overwrites the named keys in the event vector's payload map with the `:rf/redacted` sentinel before the handler chain runs. The handler body itself sees the UNREDACTED payload via the regular `:event` coeffect slot; the redaction is for the trace surface only. |

## DOM source-coord annotations

Every adapter whose host has a DOM-attribute concept (Reagent / UIx / Helix on the browser) injects `data-rf2-source-coord="<ns>:<sym>:<line>:<col>"` on the rendered root DOM element of each registered view. Format and exemptions live in [Spec 006 §Source-coord annotation](../../spec/006-ReactiveSubstrate.md#source-coord-annotation-mandatory-rf2-z7f7--rf2-z9n1).

The annotation is gated on `interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production `:advanced` builds elide the attribute via dead-code elimination — there is no DOM-bytes cost in shipped bundles. The JVM SSR emitter mirrors the contract per [Spec 011 §Source-coord annotation under SSR](../../spec/011-SSR.md#source-coord-annotation-under-ssr).

## The error contract

Errors are emitted as structured trace events with `:op-type :error` (or `:warning` / `:info` / `:fx` / `:flow` / `:frame`) and a per-category `:operation` keyword. The complete normative catalogue — every `:rf.error/*`, `:rf.warning/*`, `:rf.fx/*`, `:rf.cofx/*`, `:rf.ssr/*`, `:rf.epoch/*`, `:rf.flow/*`, `:rf.http/*`, `:rf.http.interceptor/*`, `:rf.frame/*`, and `:rf.route.nav-token/*` event the runtime emits — lives at [009 §Error event catalogue](../../spec/009-Instrumentation.md#error-event-catalogue) (single source of truth for category names, `:op-type` discriminator, trigger conditions, default `:recovery`, and `:tags` payload keys).

Per-category Malli `:tags` schemas are canonicalised at [Spec-Schemas §Per-category `:tags` schemas](../../spec/Spec-Schemas.md#per-category-tags-schemas) — one schema per catalogue row.

## See also

- [01 — Core](01-core.md) — the `:trace-buffer`, `:epoch-history`, `:elision` configure keys.
- [08 — Schemas](08-schemas.md) — the registration side of the elision posture.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the normative source.
- [Tool-Pair](../../spec/Tool-Pair.md) — how the epoch buffer, trace bus, and source-coord annotations compose into the pair-shaped tools.
