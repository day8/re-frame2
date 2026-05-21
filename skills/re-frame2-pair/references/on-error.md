# `:on-error` per-frame policy — return-map contract

When the user wires (or asks you to wire) a frame's `:on-error` policy, the return value is a **closed shape**. The canonical normative source is [spec/009-Instrumentation.md §Error-handler policy (`:on-error` per frame)](../../../spec/009-Instrumentation.md). This file is the operational reminder agents need before they propose or inspect a policy at the REPL.

## The closed shape

```clojure
{:recovery     <keyword>   ;; REQUIRED — one of the closed recovery set
 :replacement  <value>     ;; OPTIONAL — honoured ONLY under :replaced-with-default
 :notes        <string>}   ;; OPTIONAL — free-form; surfaced on the augmented trace
```

Returning `nil` is the idiomatic "delegate to the category default" — that's what monitoring-library forwarders (Sentry, Honeybadger, etc.) do.

## The `:recovery` enum (closed set)

The runtime accepts exactly six values; anything else triggers `:rf.error/bad-on-error-return` and the category default is used.

| Recovery | Meaning |
|---|---|
| `:no-recovery` | The error propagates; the failing op is not retried or substituted. Cascade halts at the offending point. |
| `:replaced-with-default` | The runtime substitutes `:replacement` into the failing slot and resumes. Shape of `:replacement` is category-specific (see below). |
| `:skipped` | The runtime declines to act; cascade continues. |
| `:warned-and-replaced` | A `:warning` trace is emitted and a category-specific default is substituted. |
| `:logged-and-skipped` | The trace is emitted and the offending input is dropped; sibling inputs still apply. |
| `:ignored` | Advisory only; the snapshot/state is unchanged. |

## What `:retry-count` was — and isn't

The framework does NOT implement retry semantics. `:retry-count` is **not part of the contract** and never was. If you find a policy in the user's code returning `{:recovery :retried :retry-count 3}` (an old idiom from drafts), the runtime now rejects it: `:rf.error/bad-on-error-return` is emitted and the category default applies. The `:retried` keyword exists in the recovery enum but is **reserved for `:rf.http/retry-attempt`** traces emitted by managed-HTTP — that surface owns its own backoff. An `:on-error` policy that wants to fire the event again MUST dispatch a fresh event from the policy body.

## `:replacement` — only honoured under `:replaced-with-default`

For every other `:recovery` value the runtime ignores `:replacement` if present (and MAY emit `:rf.warning/replacement-ignored-on-recovery`).

The shape of `:replacement` matches what the failing operation would normally return:

- `:rf.error/handler-exception` — an effect-map (`{:db ...}`, `{:fx [[...] ...]}`, or both). The runtime applies it as if the handler had returned it. Non-map or malformed → `:rf.error/bad-on-error-return` and fall back to `:no-recovery`.
- `:rf.error/schema-validation-failure` — a value of the same position the validator was checking (event vector / sub return / app-db / fx-args / cofx-args / machine `on-create` payload — read the failing trace's `:where` tag).
- Categories whose default is `:no-recovery` with no substitutable value (registration-time failures, drain-depth-exceeded, `:rf.epoch/restore-*` rejections, `:rf.machine/*` registration-time rejections) — `:replacement` is NOT honoured and triggers `:rf.error/bad-on-error-return`.

## Two new error categories agents must recognise

These appear in the trace buffer when an `:on-error` policy violates the contract — surface them verbatim to the user; the structured `:tags` payload names exactly what was wrong.

| Operation | `:recovery` | When |
|---|---|---|
| `:rf.error/bad-on-error-return` | `:logged-and-skipped` | Policy returned a map with bad `:recovery`, malformed `:replacement`, or `:replacement` on a non-substitutable category. `:tags {:received <map> :reason <str>}`. Runtime falls back to the original error's category default. |
| `:rf.error/on-error-policy-exception` | `:no-recovery` | The `:on-error` policy fn threw. Runtime does NOT recursively invoke the policy on its own exception. `:tags {:original <input-error-event> :exception-message <str>}`. Cascade halts; the policy's exception does not propagate to user code. |

## Inspecting a policy at the REPL

```
;; Read the registered policy fn for the operating frame:
mcp__re-frame2-pair__eval-cljs {form: "(:on-error (rf/frame-meta :rf/default))"}

;; Pull recent policy-contract violations from the trace buffer:
mcp__re-frame2-pair__eval-cljs {
  form: "(filter #(#{:rf.error/bad-on-error-return
                     :rf.error/on-error-policy-exception} (:operation %))
                 (rf/trace-buffer {:op-type :error}))"
}

;; Hot-swap a policy (ephemeral; survives until full page reload):
mcp__re-frame2-pair__eval-cljs {
  form: "(rf/reg-frame :rf/default
           {:on-error (fn [error-event]
                        (case (:operation error-event)
                          :rf.error/handler-exception {:recovery :no-recovery}
                          nil))})"
}
```

Legacy bash form (if the MCP server isn't wired): replace each `mcp__re-frame2-pair__eval-cljs {form: "..."}` with `scripts/eval-cljs.sh '<form>'`.

## Quick checklist for proposing a policy

1. Return `nil` unless the user actually wants to override the category default.
2. If overriding, return a closed map with `:recovery` drawn from the enum above.
3. Only set `:replacement` when `:recovery` is `:replaced-with-default`, and match the failing op's normal return shape.
4. Never set `:retry-count`. To retry, dispatch a fresh event from the policy body.
5. Wrap host side effects (logging, monitoring fanout) in a `try` — the runtime catches policy throws, but cleaner to keep the policy total.

For the full normative text (the RFC 2119 clauses, the ordering rules inside the runtime, the style rubric for `:reason` strings), see [spec/009-Instrumentation.md §Error-handler policy](../../../spec/009-Instrumentation.md).
