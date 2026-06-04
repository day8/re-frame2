# error-events

The **single source of truth** for re-frame2's error / warning / advisory event vocabulary is [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). The catalogue enumerates ~95 categories with `:operation`, `:op-type`, trigger, default `:recovery`, and `:tags` columns. **Do not inline the catalogue here** — duplication will drift; cross-reference the spec instead.

When an agent migrating a v1 codebase needs to answer *"is this error name old or new?"* or *"what does the new `:on-error` policy actually intercept?"* — the answer is one click away in the spec, and this leaf points the way.

## Why this leaf exists

The migration touches three surfaces that hand-off to the error event stream:

- **M-13** — `reg-event-error-handler` is gone. The replacements (frame-level `:on-error` for recovery policy, `register-listener!` for dev-loop observation, `register-error-listener!` for always-on production egress) consume events from the catalogue.
- **M-17 / M-26** — observer-shaped interceptors / post-event callbacks become trace listeners; they filter on `:operation` / `:op-type` from the catalogue.
- **M-23** — `re-frame.alpha` lifecycle annotations dropped; some user error-recognition code referenced old category names.

Anywhere a migration prompt or post-migration audit mentions "error category", "error event", "trace listener filter", or `:op-type` — point at the spec catalogue. Don't re-list the categories here.

## Namespace prefixes (the closed set)

The catalogue uses **six stable prefixes** (per [Spec 009 §Error namespace convention](../../../spec/009-Instrumentation.md#error-namespace-convention--five-prefix-shapes)):

| Prefix | Meaning |
|---|---|
| `:rf.error/*` | A genuine runtime error: a contract was violated. |
| `:rf.warning/*` | A misuse the runtime recovers from but wants surfaced. |
| `:rf.fx/*` | An fx-substrate event riding the error envelope (success-path or warning). |
| `:rf.cofx/*` | A cofx-substrate event riding the error envelope. |
| `:rf.ssr/*` | An SSR-substrate diagnostic (hydration mismatches, head-divergence). |
| `:rf.epoch/*` | Time-axis tooling diagnostics (epoch restore failures). |

Plus two narrower families surfaced by the per-feature artefacts:

| Prefix | Meaning |
|---|---|
| `:rf.http/*` / `:rf.http.interceptor/*` | Managed-HTTP request lifecycle (retry, abort, interceptor failure). |
| `:rf.route.nav-token/*` | Navigation-token suppression on stale async results. |
| `:rf.frame/*` | Frame lifecycle (drain interruption). |

The closed catalogue at [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue) enumerates every category. **The prefix list is stable**; new categories adopt an existing prefix. New ad-hoc prefixes are not part of the contract.

## How an `:on-error` policy uses the catalogue

A frame's `:on-error` policy (M-13 replacement) receives any error event whose `:frame` matches the policy's frame. The policy fn dispatches on `:operation` and **returns a closed-shape recovery map (or `nil`)** — never a raw effect-map. The return-map contract is pinned in [Spec 009 §Return-map contract](../../../spec/009-Instrumentation.md#return-map-contract): the recognised keys are `:recovery` (REQUIRED — one of the closed recovery keywords), `:replacement` (only honoured when `:recovery` is `:replaced-with-default`), and `:notes`. A bare `{:fx [...]}` map has **no `:recovery` key and is rejected** by the runtime (`:rf.error/bad-on-error-return`); the effects never run. To run effects, side-effect in the policy body and return `nil`, or dispatch a fresh event — the runtime never re-runs the failing handler.

```clojure
(rf/reg-frame
  :rf/default
  {:on-error
   (fn [{:keys [operation tags] :as evt}]
     (case operation
       ;; Observe-only: side-effect in the body, return nil to let the
       ;; runtime apply its documented per-category default recovery.
       :rf.error/handler-exception      (do (log-to-monitoring evt) nil)
       ;; Substitute a value: :replaced-with-default + a :replacement of the
       ;; failing slot's normal return type (for handler-exception, an effect-map).
       :rf.error/schema-validation-failure
       {:recovery :replaced-with-default
        :replacement (:default-value tags)}
       ;; ... etc — see the catalogue for every :operation the policy may receive
       nil))})                                       ; let the default recovery apply
```

The full list of `:operation` values the policy may see is exactly the catalogue; the recovery keywords (`:no-recovery` / `:replaced-with-default` / `:skipped` / `:warned-and-replaced` / `:logged-and-skipped` / `:ignored`) are the closed set in [Spec 009 §Recovery contract](../../../spec/009-Instrumentation.md#recovery-contract). **Reference Spec 009 when writing the `case` arms** — don't guess from memory, and don't return a raw effect-map.

## Trace listener vs. error-emit listener (dev-only vs. always-on)

Two distinct listener surfaces exist; picking the wrong one for production monitoring is the single most common error-handling mistake in a migration.

- **`register-listener!`** — the **dev-only** raw trace listener (M-13's process-wide-observer replacement and M-17's audit-interceptor replacement). Sees every emitted trace event with full dev-side enrichment, but is **production-elided**: under `:advanced` + `goog.DEBUG=false` the `emit!` gate is constant-folded out, registration is a no-op, and the listener never fires (per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)). Use it for dev-loop observability, never for production error egress.
- **`register-error-listener!`** — the **always-on** error-emit listener. Survives `:advanced` + `goog.DEBUG=false`; the router fans out one tight record (`{:error :event :event-id :frame :time :exception :elapsed-ms}`, post-elision) per `:rf.error/*` event. This is the correct surface for production Sentry / Honeybadger / Datadog forwarding (per [API.md §Error-emit](../../../spec/API.md#error-emit-always-on-production-survivable)).

```clojure
;; Production error egress — always-on. (register-error-listener!, NOT register-listener!)
(rf/register-error-listener!
  :audit/sentry
  (fn [{:keys [error event-id frame exception] :as record}]
    (sentry/capture record)))                        ; fires under goog.DEBUG=false

;; Dev-loop observability only — production-elided.
(rf/register-listener!
  :dev/trace
  (fn [evt]
    (when (= :error (:op-type evt))                  ; severity branch
      (js/console.warn evt))))
```

`:op-type` values on the trace stream: `:error`, `:warning`, `:info`, `:fx`, `:cofx`, `:frame`, `:flow`. The mapping from `:operation` prefix to `:op-type` is in the catalogue's `:op-type` column. (The corpus's M-13/M-26 entries recommend `register-listener!` for the cross-frame *observer* role; for a listener that must keep firing in production, prefer `register-error-listener!` per the dev/prod split above.)

## Production elision — what elides and what stays always-on

The error-handling surface is **split** across the dev/prod gate. Getting this backwards leads to wiring production monitoring on a surface that silently goes dark.

**Always-on (survives `:advanced` + `goog.DEBUG=false`):**

- **The per-frame `:on-error` policy slot.** It is NOT gated by `re-frame.interop/debug-enabled?` — it rides a small always-on error-emit substrate (`re-frame.error-emit`) that survives production builds. Registered policy fns **fire on production handler exceptions** (`:rf.error/handler-exception`, the primary production-monitoring case) per [Spec 009 §What is available in production](../../../spec/009-Instrumentation.md#what-is-available-in-production). A migration audit that reports "the new `:on-error` doesn't run in production" is hitting a **real bug**, not the elision — the slot is meant to fire.
- **`register-error-listener!`** (the error-emit listener) — same always-on substrate, independent fan-out path.

**Dev-only (production-elided per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code)):**

- The raw **trace stream** and `register-listener!` listeners — no events delivered in prod.
- Dev-side enrichments on the always-on path: `:rf.trace/dispatch-id` correlation, `:rf.trace/trigger-handler` source-coord, the `:rf.error/bad-on-error-return` / `:rf.error/on-error-policy-exception` validation traces, and the retain-N ring buffer.

So: route **production** error monitoring through the `:on-error` slot and/or `register-error-listener!` (always-on); use `register-listener!` only for the **dev** loop.

## Stale advice the migration agent will encounter

When auditing a v1 codebase, **assume any error-category name not present in the spec catalogue is stale**. Old prose, blog posts, Stack Overflow answers naming specific v1 categories may have invented names or used pre-rename spellings. The catalogue at Spec 009 wins; do not infer categories from project comments.

Specific drift to watch:

- v1 `:rf.warning/machine-state-not-in-definition` / `:rf.warning/machine-snapshot-version-mismatch` were renamed to `:rf.error/*` form (per the catalogue's "Older drafts spelled this…" notes). User code matching the old `:rf.warning/*` spelling needs updating.
- v1 `:rf.warning/machine-unhandled-event` is a **different** case — it was **retired entirely**, not re-keyed. Per rf2-ugdas there is no `:rf.error/machine-unhandled-event` and no warning either: an event a machine declines is now a **benign no-op**, surfaced as the `:rf.machine.event/unhandled-no-op` trace (op-type `:rf.machine`, the machine-activity family — NOT `:error`, NOT `:warning`; xstate-v5 parity, see [`spec/005-StateMachines.md` §Transition resolution](../../../spec/005-StateMachines.md#transition-resolution--deepest-wins-with-parent-fallthrough) + the [Spec 009 §`:op-type` vocabulary](../../../spec/009-Instrumentation.md#op-type-vocabulary) machine-activity entries). User code matching the old `:rf.warning/machine-unhandled-event` (or `:rf.error/machine-unhandled-event`) spelling should **drop** the handling — there is no replacement category to re-key to. To fail loudly on an unknown event, declare a `:*` wildcard whose action throws (a real `:rf.error/machine-action-exception`).
- v1 prose sometimes named ad-hoc keys like `:rf/error` or `:re-frame/error`. The contract surface is `:rf.error/<category>` — closed set only.

## When to point an author at this leaf

- They're writing the `:on-error` fn for M-13 and need to know what events arrive.
- They're writing a `register-listener!` listener and ask "which categories are errors vs warnings vs informational?"
- They have a `(case operation …)` shape and want a complete list of arms.
- A test asserts on an error event's `:operation` keyword and they need the canonical name.

In every case: **link to [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)**. Do not re-enumerate the categories.

---

*Authoritative catalogue: [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). Per-category `:tags` schemas: [`spec/Spec-Schemas.md` §Per-category `:tags` schemas](../../../spec/Spec-Schemas.md#per-category-tags-schemas). Cross-references: M-13 in [`guided-handlers-state.md`](guided-handlers-state.md), M-17 / M-26 in [`guided-interceptors-subs.md`](guided-interceptors-subs.md).*
