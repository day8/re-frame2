# Recipes

Named procedures the user may ask for. When the user asks a matching question, run the procedure below rather than improvising.

## Contents

- ["What's in `app-db`?" / "What did the last event do?"](#whats-in-app-db--what-did-the-last-event-do)
- ["Why didn't my view update?"](#why-didnt-my-view-update)
- ["Explain this dispatch"](#explain-this-dispatch)
- ["Post-mortem — how did we get here?"](#post-mortem--how-did-we-get-here)
- ["What effects fired?"](#what-effects-fired)
- ["What caused this re-render?"](#what-caused-this-re-render)
- ["Explain this error" / "What caused this error?"](#explain-this-error--what-caused-this-error)
- ["Where in the code does this come from?"](#where-in-the-code-does-this-come-from)
- ["Understand this component" / "What is this thing?"](#understand-this-component--what-is-this-thing)
- ["Fire the button at file:line"](#fire-the-button-at-fileline)
- ["Inspect this machine"](#inspect-this-machine)
- ["Dead code scan"](#dead-code-scan)
- [Experiment loop](#experiment-loop)
- [Stub an effect for an experiment](#stub-an-effect-for-an-experiment)
- ["Narrate the next N events"](#narrate-the-next-n-events)
- ["Alert me on slow events"](#alert-me-on-slow-events)
- ["Watch for X while I interact"](#watch-for-x-while-i-interact)

## "What's in `app-db`?" / "What did the last event do?"

- Snapshot or get: `app-db/snapshot`, `app-db/get`, or for a diff between an epoch's `:db-before` and `:db-after`: `trace/last-epoch` and compute the diff with `clojure.data/diff`. The runtime helper `(re-frame-pair2.runtime/epoch-diff <epoch>)` returns a pre-computed `{:only-before :only-after :common}` map.

## "Why didn't my view update?"

1. Identify the sub the view reads (ask the user if it's not in the view file).
2. `trace/last-epoch` (or `trace/last-pair-epoch`) — find the recent dispatch that should have updated it.
3. Walk the epoch's `:sub-runs` projection. **A sub that re-ran appears in the vector; a sub that cache-hit does not** (Spec-Schemas §`:rf/epoch-record` — the rf2-719e value-equal recompute suppression is enforced by the runtime, so cache-hit subs do not emit `:sub/run`).
4. If the sub the view depends on isn't in `:sub-runs` for the cascade, the equality gate held; report the upstream sub whose return value was `=` to its previous value.
5. If the sub did re-run but the view didn't re-render, check `:renders` — the projection lists every render in the cascade with its `:render-key` and `:triggered-by`.

## "Explain this dispatch"

Run `trace/dispatch-and-collect`, then narrate the six dominoes against the resulting `:rf/epoch-record`:

- Event vector + interceptor chain (from `(rf/handler-meta :event id)`)
- Coeffects injected (visible as `:event/run` tags in `:trace-events`)
- Effects map (visible as `:event/do-fx` plus per-fx warning/error traces; the `:effects` projection only carries warning/error outcomes — successful fx executions live in the raw `:trace-events` slot, see Spec-Schemas note)
- `app-db` diff between `:db-before` and `:db-after`
- Subs that re-ran (the `:sub-runs` projection); the absence of a sub from this list means it cache-hit
- Components that re-rendered (the `:renders` projection), with `:ns` / `:line` / `:file` resolvable via `(rf/handler-meta :view <render-key>)` for registered views

Keep it short. One compact paragraph per domino.

## "Post-mortem — how did we get here?"

**When the user is stuck in a broken state and can't describe how they got there.** Every cascade since page load is in the operating frame's `epoch-history` (subject to retention — default depth 50, configurable). You don't need the user to remember the sequence; you can walk it back.

Procedure:

1. Ask the user what's wrong in *observable* terms ("the save button is grey", "the dashboard is empty"). Resolve any UI references to source via `dom/source-at` if possible.
2. Identify the **app-db key(s) or sub(s)** that govern the observation. If the user can't, trace the recent render for the offending component and walk its sub inputs.
3. Use `trace/find-where` to pinpoint the epoch where the governing key last changed to its current (bad) value. Example:
   ```
   scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-where
                           (fn [e] (= :expired (get-in (:db-after e)
                                                        [:auth-state]))))'
   ```
4. Report that epoch as the culprit: its `:trigger-event`, the diff between `:db-before` and `:db-after`, and (crucially) the cascade tree. Often the root-cause dispatch is a child of another event — follow `:parent-dispatch-id` upstream via `trace/cascade <dispatch-id>`.
5. If no single epoch is responsible — the state drifted over many events — use `find-all-where` to get the trajectory. Narrate the 3–5 most relevant transitions rather than all of them.
6. Propose a fix. Usually one of: a handler that shouldn't have fired, a handler that did fire but was wrong, or a missing guard.

**Retention caveat.** The epoch ring is bounded (default 50, configurable via `(rf/configure :epoch-history {:depth N})`). Events that happened "a long time ago" may have aged out. If the user describes a state change you can't find in the ring, say so explicitly: *"I can see the last N events but the change you're describing happened before that."* Then propose reproducing the path from a known state — or `(rf/configure :epoch-history {:depth 500})` and re-trigger.

## "What effects fired?"

Walk the epoch's `:effects` projection — but note its asymmetry (Spec-Schemas §`:rf/epoch-record`): the projection captures *visible outcomes* (skipped-on-platform, fx-handler-exception, no-such-fx). Successful fx execution is observable only in the raw `:trace-events` slot (look for `:event/do-fx` plus the absence of an error trace for that fx-id). If you need successful-fx attribution, walk `:trace-events` directly and group by `:fx-id` tag.

For cascaded dispatches: follow `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) into child epochs. `trace/cascade <dispatch-id>` returns the tree.

## "What caused this re-render?"

Given a component name or render key, find the latest epoch whose `:renders` includes it. Reverse from there: the sub inputs that invalidated its outputs (visible in `:sub-runs`), then the event that invalidated the sub inputs (the `:trigger-event` of that epoch). For machine-driven renders, also check `:rf/machine` reg-sub activity — machine state changes flow through the sub graph like any other.

## "Explain this error" / "What caused this error?"

1. Pull recent error traces: `(rf/trace-buffer {:op-type :error})`. Each entry is an `:rf.error/*` op with `:rf.error/data`.
2. Read `:rf.trace/trigger-handler` on the error event — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}`. This is the **handler that was executing when the error fired**, not the throw site inside the framework. Report it as `<kind> :<id> at <file>:<line>` so the user can jump straight to the source — works in production builds (the field is not elided like `:rf.assert/*`).
3. If the error sits inside a known epoch, cross-check `:trigger-event` and walk the cascade via `:parent-dispatch-id` — the upstream event that queued the offending handler is often the real culprit.
4. If `:rf.trace/trigger-handler` is **absent**, the error fired at dispatch-time before any handler ran (e.g. `:rf.error/no-such-event` because the registered id is misspelt). The `:rf.error/data` payload — the failing id, the lookup map — is then the only handle; offer to `registrar/list` the matching kind to find a near match.

## "Where in the code does this come from?"

Call `dom/source-at` on the element (or on `:last-clicked`). Return `{:ns :line :file}` resolved against the source-coord registry. If `:src` is nil, report which prerequisite is missing (`:annotate-dom?` is off, no `:src (at)` on this re-com call site, or no registered view at this DOM position).

## "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), chain:

1. `dom/source-at` — resolve to `{:ns :line :file}`.
2. `Read` the source file at that line, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and (if you can see them nearby) which subscriptions it reads. Cross-check against `(rf/handler-meta :view <id>)` for registered views.
4. If the source-coord lookup fails, fall back to `dom/describe` to report tag/class/listeners, and ask the user to point at the source instead.

This is one of the most grounding moves you can make — it turns *"that button"* into *"`re-com/button` at `app/cart/view.cljs:84`, dispatching `[:cart/checkout]`"* in one step.

## "Fire the button at file:line"

Use `dom/fire-click-at-src`. Report the resulting epoch. Distinctive to re-frame-pair2 — exercise a specific call site by its source location rather than picking a CSS path.

## "Inspect this machine"

When the user mentions a state machine (Spec 005), chain:

1. `machines/list` — confirm it's registered.
2. `machines/describe :auth` — return the spec map (`:initial`, `:states`, `:guards`, `:actions`, source coords).
3. `machines/state :auth` — current snapshot, including `:rf/snapshot-version`.
4. To watch transitions live: `watch/stream` and inspect each emitted epoch's `:trace-events` for `:rf.machine/transition` entries — `(some #(= :rf.machine/transition (:operation %)) (:trace-events e))`. Arbitrary-predicate filtering at the watch layer is not currently supported; combine `--event-id-prefix` (to narrow by trigger) with caller-side filtering of the streamed epochs.
5. Subscribe to the canonical machine sub: `subs/sample [:rf/machine :auth]`.

## "Dead code scan"

`registrar/list :event`, `registrar/list :sub`, etc. Then `trace/recent` with a large window (e.g. 60s) — or ask the user to exercise the app first. Report registered ids that never appeared in any epoch's `:trigger-event` or `:sub-runs`. *Caveat: trace coverage is bounded by epoch-history depth and trace-buffer depth.*

## Experiment loop

**Why this works:** the same starting `app-db`, the same event, only the code changes — so any difference in the resulting epoch is attributable to *your edit*, nothing else. That makes it a controlled experiment rather than a fix-and-pray. Reach for this loop whenever you're unsure whether a change has the intended effect.

re-frame2's first-class `restore-epoch` makes this loop fully closed — no adapter caveats.

Canonical procedure:

1. `trace/dispatch-and-collect [:foo ...]` → observe baseline. Capture the `:epoch-id` from the resulting record.
2. **Tell the user** which side effects in the cascade can't be rewound. Walk `:trace-events` for `:event/do-fx` involving non-pure fx (`:http`, navigation, localStorage, `:dispatch-later` that already landed) and warn before restoring.
3. `epoch/restore <epoch-id>` → rewind `app-db`. Watch for `false` return + check `(rf/trace-buffer {:op-type :error})` for the failure reason.
4. **Modify the part of the system you're iterating on.**
   - *Handlers / subs / fx:* `(rf/reg-event-fx :foo ...)` / `(rf/reg-sub :bar ...)` / `(rf/reg-fx :baz ...)` via `repl/eval`. The registrar replaces; `:rf.registry/handler-replaced` fires.
   - *Machines:* `(rf/reg-machine :auth ...)` — bumps the machine's `:version` if one is supplied. Old snapshots may now `:rf.epoch/restore-version-mismatch` against this machine.
   - *Views / helpers (plain `defn`s):* redefine the var via `repl/eval`. Subsequent renders pick up the new fn.
   - *Permanent change:* `Edit` the source file, then `scripts/tail-build.sh --probe '...'` to wait for the reload to land.
5. **Verify the patch took before re-dispatching.** `registrar/describe :event :foo` should now return different `:line` / `:column` (or a different `:handler-fn` hash) than what you captured at step 1. If the patch didn't land, re-dispatching will silently test the old code.
6. `trace/dispatch-and-collect [:foo ...]` → observe the new behaviour.
7. Compare the two epochs (`epoch-diff` between their `:db-after` values; cross-check `:sub-runs` and `:renders` projections). Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

## Stub an effect for an experiment

Per Spec 002 §Per-frame and per-call overrides, dispatches can carry `:fx-overrides` to redirect a registered fx to a stub for one cascade. Used to run "what if the HTTP request returned X" experiments without hitting the network.

```
scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http
```

The stub must already be registered via `(rf/reg-fx :stub-http (fn [_ v] ...))`. The override applies for this dispatch only; subsequent dispatches use the canonical `:http` again.

For `:rf.http/managed` failure-category experiments (Spec 014 §Failure categories), stub `:http` to fire one of the canonical failure traces directly.

## "Narrate the next N events"

`watch/count N` with no filter. Report each epoch as a short paragraph (event id, `:trigger-event`, key entries from `:effects` and `:sub-runs`, `app-db` diff summary) as it fires.

## "Alert me on slow events"

`watch/stream --timing-ms '>100'`. Silent until a match; report with the `:event/run` duration plus per-interceptor timings from the raw trace.

## "Watch for X while I interact"

`watch/stream --event-id-prefix :checkout/` (or other predicate). Narrate each match; summarise when idle.
