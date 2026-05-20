# error-events

The **single source of truth** for re-frame2's error / warning / advisory event vocabulary is [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). The catalogue enumerates ~95 categories with `:operation`, `:op-type`, trigger, default `:recovery`, and `:tags` columns. **Do not inline the catalogue here** — duplication will drift; cross-reference the spec instead.

When an agent migrating a v1 codebase needs to answer *"is this error name old or new?"* or *"what does the new `:on-error` policy actually intercept?"* — the answer is one click away in the spec, and this leaf points the way.

## Why this leaf exists

The migration touches three surfaces that hand-off to the error event stream:

- **M-13** — `reg-event-error-handler` is gone. The replacements (frame-level `:on-error`, `register-listener!`) consume events from the catalogue.
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

A frame's `:on-error` policy (M-13 replacement) receives any error event whose `:frame` matches the policy's frame. The policy fn dispatches on `:operation`:

```clojure
(rf/reg-frame
  :rf/default
  {:on-error
   (fn [{:keys [operation tags] :as evt}]
     (case operation
       :rf.error/handler-exception {:fx [[:dispatch [:app/log-error evt]]]}
       :rf.error/sub-exception     {:recovery :replaced-with-default :replacement nil}
       ;; ... etc — see the catalogue for every :operation the policy may receive
       nil))})                                       ; let the default recovery apply
```

The full list of `:operation` values the policy may see is exactly the catalogue. **Reference Spec 009 when writing the `case` arms** — don't guess from memory.

## How a trace listener filters

`register-listener!` (M-13's process-wide-observer replacement and M-17's audit-interceptor replacement) sees every emitted trace event. Filter on `:op-type` for severity branching, `:operation` for category routing:

```clojure
(rf/register-listener!
  :audit/sentry
  (fn [evt]
    (when (= :error (:op-type evt))                  ; severity branch
      (sentry/capture evt))))
```

`:op-type` values: `:error`, `:warning`, `:info`, `:fx`, `:cofx`, `:frame`, `:flow`. The mapping from `:operation` prefix to `:op-type` is in the catalogue's `:op-type` column.

## Production elision

Every recovery in the catalogue applies in **dev only** — trace emission is production-elided per [Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code). Registered `:on-error` callbacks **do not fire in CLJS prod**. Migration audits that surface "the new error-handler doesn't run" reports in production are hitting the elision, not a bug.

## Stale advice the migration agent will encounter

When auditing a v1 codebase, **assume any error-category name not present in the spec catalogue is stale**. Old prose, blog posts, Stack Overflow answers naming specific v1 categories may have invented names or used pre-rf2-wfbn3 spellings. The catalogue at Spec 009 wins; do not infer categories from project comments.

Specific drift to watch:

- v1 `:rf.warning/machine-unhandled-event` / `:rf.warning/machine-state-not-in-definition` / `:rf.warning/machine-snapshot-version-mismatch` were renamed to `:rf.error/*` form (per the catalogue's "Older drafts spelled this…" notes). User code matching the old `:rf.warning/*` spelling needs updating.
- v1 prose sometimes named ad-hoc keys like `:rf/error` or `:re-frame/error`. The contract surface is `:rf.error/<category>` — closed set only.

## When to point an author at this leaf

- They're writing the `:on-error` fn for M-13 and need to know what events arrive.
- They're writing a `register-listener!` listener and ask "which categories are errors vs warnings vs informational?"
- They have a `(case operation …)` shape and want a complete list of arms.
- A test asserts on an error event's `:operation` keyword and they need the canonical name.

In every case: **link to [Spec 009 §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue)**. Do not re-enumerate the categories.

---

*Authoritative catalogue: [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-event-catalogue). Per-category `:tags` schemas: [`spec/Spec-Schemas.md` §Per-category `:tags` schemas](../../../spec/Spec-Schemas.md#per-category-tags-schemas). Cross-references: M-13 in [`guided-handlers-state.md`](guided-handlers-state.md), M-17 / M-26 in [`guided-interceptors-subs.md`](guided-interceptors-subs.md).*
