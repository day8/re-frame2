# error-events

The **single source of truth** for re-frame2's error / warning / advisory event vocabulary is [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). The catalogue enumerates ~95 categories with `:operation`, `:op-type`, trigger, default `:recovery`, and `:tags` columns. **Do not inline the catalogue here** — duplication will drift; cross-reference the spec instead.

When an agent migrating a v1 codebase needs to answer *"is this error name old or new?"* or *"which listener surface do I wire production error monitoring on?"* — the answer is one click away in the spec, and this leaf points the way. (There is **no** app-steering frame-level `:on-error` recovery policy to "intercept" anything — recovery is framework-owned; see below.)

## Why this leaf exists

The migration touches three surfaces that hand-off to the error event stream:

- **M-13** — `reg-event-error-handler` is gone, and there is **no** app-steering frame-level `:on-error` recovery policy that replaces it (recovery is framework-owned — the typed per-category default). The observability replacement is the one stream-parameterized `register-listener!` verb (the `:trace` stream for dev-loop observation, the `:errors` stream for always-on production egress), consuming events from the catalogue.
- **M-17 / M-26** — observer-shaped interceptors / post-event callbacks become trace listeners; they filter on `:operation` / `:op-type` from the catalogue.
- **M-23** — `re-frame.alpha` lifecycle annotations dropped; some user error-recognition code referenced old category names.

Anywhere a migration prompt or post-migration audit mentions "error category", "error event", "trace listener filter", or `:op-type` — point at the spec catalogue. Don't re-list the categories here.

## Namespace prefixes (the closed set)

The catalogue uses **six stable prefixes** (per [Spec 009 §Error namespace convention](../../../spec/009-Instrumentation.md#error-namespace-convention--five-prefix-shapes) — the spec's heading and prose say "five", but its table enumerates the six below):

| Prefix | Meaning |
|---|---|
| `:rf.error/*` | A genuine runtime error: a contract was violated. |
| `:rf.warning/*` | A misuse the runtime recovers from but wants surfaced. |
| `:rf.fx/*` | An fx-substrate event riding the error envelope (success-path or warning). |
| `:rf.cofx/*` | A cofx-substrate event riding the error envelope. |
| `:rf.ssr/*` | An SSR-substrate diagnostic (hydration mismatches, head-divergence). |
| `:rf.epoch/*` | Time-axis tooling diagnostics (epoch restore failures). |

Plus three narrower families surfaced by the per-feature artefacts:

| Prefix | Meaning |
|---|---|
| `:rf.http/*` / `:rf.http.interceptor/*` | Managed-HTTP request lifecycle (retry, abort, interceptor failure). |
| `:rf.route.nav-token/*` | Navigation-token suppression on stale async results. |
| `:rf.frame/*` | Frame lifecycle (drain interruption). |

The closed catalogue at [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) enumerates every category. **The prefix list is stable**; new categories adopt an existing prefix. New ad-hoc prefixes are not part of the contract.

## Where v1's `reg-event-error-handler` went (M-13 / M-26)

v1's process-wide `reg-event-error-handler` is **dropped**. There is **no app-steering error-recovery policy** in v2 — recovery is framework-owned (the **typed per-category default**: frame-destroyed recovers + emits, sub-exception returns `nil`, handler-exception fails loud without crashing the app). Earlier v2 drafts documented a per-frame `:on-error` recovery policy as the replacement; that policy was **REMOVED** (its return value was never read or applied, and errors are not generically recoverable by an app policy). When migrating v1 code that registered a process-wide error handler:

- **Observability** → register a corpus-wide error-emit listener — `(register-listener! :errors <id> f)` (always-on; survives production builds). It receives the tight record per `:rf.error/*` event; forward it to your monitor.
- **Genuine recovery** for *expected* failures → handle at the source (managed-HTTP `:retry`, optional-read fallback). To re-run a failed event, dispatch a fresh one; the runtime never re-runs the failing handler.

A v1 error-handler that returned a substitute value or swallowed an error has **no v2 equivalent** — drop the steering and rely on the framework's typed default, moving any genuine recovery to the source.

## Trace listener vs. error-emit listener (dev-only vs. always-on)

Listener registration is **one stream-parameterized verb** — `(register-listener! stream id f)`, with `stream` a leading **required** keyword (`:trace` / `:events` / `:errors` / `:epoch`; an unknown stream throws `:rf.error/unknown-listener-stream` — no bare-trace default, no compatibility aliases, per [API.md §`register-listener!`](../../../spec/API.md#error-emit-always-on-production-survivable)). The former per-channel `register-(trace|event|error)-listener!` facade pairs were collapsed into this one verb. Two of its streams differ on the dev/prod axis, and picking the wrong one for production monitoring is the single most common error-handling mistake in a migration:

- **`(register-listener! :trace <id> f)`** — the **dev-only** raw trace listener (M-13's process-wide-observer replacement and M-17's audit-interceptor replacement). Sees every emitted trace event with full dev-side enrichment, but is **production-elided**: under `:advanced` + `goog.DEBUG=false` the `emit!` gate is constant-folded out, registration is a no-op, and the listener never fires (per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)). Use it for dev-loop observability, never for production error egress.
- **`(register-listener! :errors <id> f)`** — the **always-on** error-emit listener. Survives `:advanced` + `goog.DEBUG=false` (and JVM `-Dre-frame.debug=false`). Its payload is an **error-keyed union of several record shapes**: the per-event error record (`{:error :event :event-id :frame :time :exception :elapsed-ms}`, post-elision, one per production-reachable `:rf.error/*`); the frame-teardown report (`{:error :rf.error/frame-teardown-failed :frame :hook-failures :reason :recovery :time}`, one bounded record per destroy whose cleanup hooks threw — EP-0008); and the EP-0008-promoted **non-event SSR records** (`:rf.error/ssr-render-failed`, `:rf.error/ssr-streaming-writer-failed`, `:rf.error/malformed-hydration-payload`, `:rf.error/ssr-head-resolution-failed`, `:rf.error/sanitised-on-projection`, `:rf.error/ssr-ring-error-view-failed` — each carrying `:frame` + category-specific slots, some with `:frame nil` and none with `:event`). The teardown report and the SSR records carry no `:event` / `:event-id` / `:exception`, so a listener **must branch on `(:error record)`** rather than assuming the per-event shape (a listener that destructures `:event-id` / `:exception` off every record NPEs on a non-event one). This is the correct surface for production Sentry / Honeybadger / Datadog forwarding (per [API.md §Error-emit](../../../spec/API.md#error-emit-always-on-production-survivable)).

```clojure
;; Production error egress — always-on. (the :errors stream, NOT :trace)
;; Branch on (:error record): a teardown report carries no :exception.
(rf/register-listener! :errors
  :audit/sentry
  (fn [{:keys [error] :as record}]
    (sentry/capture record)))                        ; fires under goog.DEBUG=false

;; Dev-loop observability only — production-elided.
(rf/register-listener! :trace
  :dev/trace
  (fn [evt]
    (when (= :error (:op-type evt))                  ; severity branch
      (js/console.warn evt))))
```

`:op-type` values on the trace stream: the bare severity/flow discriminators `:error`, `:warning`, `:info`, `:flow`, plus the `:rf.<family>` domino discriminators (`:rf.event`, `:rf.sub`, `:rf.fx`, `:rf.cofx`, `:rf.view`, `:rf.frame`, `:rf.machine`, …). Note the family discriminators carry the `:rf.` prefix — a listener filtering effects matches `:rf.fx`, not bare `:fx`. The mapping from `:operation` prefix to `:op-type` is in the catalogue's `:op-type` column. (The corpus's M-13/M-26 entries recommend the `:trace` stream for the cross-frame *observer* role; for a listener that must keep firing in production, prefer the `:errors` stream per the dev/prod split above.)

## Production elision — what elides and what stays always-on

The error-handling surface is **split** across the dev/prod gate. Getting this backwards leads to wiring production monitoring on a surface that silently goes dark.

**Always-on (survives `:advanced` + `goog.DEBUG=false`):**

- **The `:errors` stream of `register-listener!`** (the error-emit listener) — the single error-observability surface. It is NOT gated by `re-frame.interop/debug-enabled?` — it rides a small always-on error-emit substrate (`re-frame.error-emit`) that survives production builds (CLJS `goog.DEBUG=false` AND JVM `-Dre-frame.debug=false`), fanning out one tight record per catalogued production-reachable `:rf.error/*` event, the bounded `:rf.error/frame-teardown-failed` report on a frame destroy whose cleanup hooks threw, and the EP-0008-promoted non-event SSR records (the error-keyed union above), per [Spec 009 §What is available in production](../../../spec/009-Instrumentation.md#what-is-available-in-production). On a JVM SSR host the dev trace surface elides under `-Dre-frame.debug=false` (default-on; production deployments set it), but this always-on axis keeps delivering — so a migrated SSR app's production error egress survives the JVM gate.

**Dev-only (production-elided per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)):**

- The raw **trace stream** — `(register-listener! :trace …)` listeners — no events delivered in prod.
- Dev-side enrichments on the always-on path: `:rf.trace/dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, and the retain-N ring buffer.

So: route **production** error monitoring through the `:errors` stream (always-on); use the `:trace` stream only for the **dev** loop.

## Stale advice the migration agent will encounter

When auditing a v1 codebase, **assume any error-category name not present in the spec catalogue is stale**. Old prose, blog posts, Stack Overflow answers naming specific v1 categories may have invented names or used pre-rename spellings. The catalogue at Spec 009 wins; do not infer categories from project comments.

Specific drift to watch:

- v2-pre-rename `:rf.warning/machine-state-not-in-definition` / `:rf.warning/machine-snapshot-version-mismatch` were renamed to `:rf.error/*` form (per the catalogue's "Older drafts spelled this…" notes). Code matching the old `:rf.warning/*` spelling needs updating. (These are pre-release-v2 spellings, not v1 — v1 had no machine substrate.)
- v2-pre-rename `:rf.warning/machine-unhandled-event` is a **different** case — it was **retired entirely**, not re-keyed. There is no `:rf.error/machine-unhandled-event` and no warning either: an event a machine declines is now a **benign no-op**, surfaced as the `:rf.machine.event/unhandled-no-op` trace (op-type `:rf.machine`, the machine-activity family — NOT `:error`, NOT `:warning`; xstate-v5 parity, see [`spec/005-StateMachines.md` §Transition resolution](../../../spec/005-StateMachines.md#transition-resolution--deepest-wins-with-parent-fallthrough) + the [Spec 009 §`:op-type` vocabulary](../../../spec/009-Instrumentation.md#op-type-vocabulary) machine-activity entries). User code matching the old `:rf.warning/machine-unhandled-event` (or `:rf.error/machine-unhandled-event`) spelling should **drop** the handling — there is no replacement category to re-key to. To fail loudly on an unknown event, declare a `:*` wildcard whose action throws (a real `:rf.error/machine-action-exception`).
- v1 prose sometimes named ad-hoc keys like `:rf/error` or `:re-frame/error`. The contract surface is `:rf.error/<category>` — closed set only.

## When to point an author at this leaf

- They're wiring an error-observability listener for M-13 (`register-listener!` — the `:errors` or `:trace` stream) and need to know what events arrive.
- They're writing a `register-listener!` listener and ask "which categories are errors vs warnings vs informational?"
- They have a `(case operation …)` shape and want a complete list of arms.
- A test asserts on an error event's `:operation` keyword and they need the canonical name.

In every case: **link to [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)**. Do not re-enumerate the categories.

---

*Authoritative catalogue: [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). Per-category `:tags` schemas: [`spec/Spec-Schemas.md` §Per-category `:tags` schemas](../../../spec/Spec-Schemas.md#per-category-tags-schemas). Cross-references: M-13 in [`guided-handlers-state.md`](guided-handlers-state.md), M-17 / M-26 in [`guided-interceptors-subs.md`](guided-interceptors-subs.md).*
