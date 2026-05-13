# Recipes

Named procedures the user may ask for. When the user asks a matching question, run the procedure below rather than improvising.

Each recipe leads with the **MCP-tool form** (preferred — ~14× faster, single persistent nREPL connection). When the agent host hasn't configured the MCP server, fall back to the bash-shim equivalents — these are catalogued in [`ops.md` §Bash-shim back-compat appendix](ops.md#bash-shim-back-compat-appendix). Op semantics are identical between transports.

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
- ["Drive a Story variant from a pair2 session"](#drive-a-story-variant-from-a-pair2-session)
- ["Diff two variants of the same component"](#diff-two-variants-of-the-same-component)
- ["Refine a variant interactively"](#refine-a-variant-interactively)

## "What's in `app-db`?" / "What did the last event do?"

- Snapshot or get: `app-db/snapshot`, `app-db/get`, or for a diff between an epoch's `:db-before` and `:db-after`: `trace/last-epoch` and compute the diff with `clojure.data/diff`. The runtime helper `(re-frame-pair2.runtime/epoch-diff <epoch>)` returns a pre-computed `{:only-before :only-after :common}` map.
- From the MCP transport, `snapshot`'s `:app-db` slice now defaults to **`:summary` mode** (top-level keys + count + size marker, not the full value — rf2-tygdv); drill in with `mcp__re-frame-pair2__get-path {path: "[:that :key]"}` or with a follow-up `snapshot {path: "[:that :key]"}`. Root path `path: "[]"` opts back into the full `:app-db` slice when you really want it. See [`mcp-transport.md` §:app-db slice modes](mcp-transport.md#when-to-use-snapshot-vs-the-per-op-reads).

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
   mcp__re-frame-pair2__eval-cljs {
     form: "(re-frame-pair2.runtime/find-where
              (fn [e] (= :expired (get-in (:db-after e) [:auth-state]))))"
   }
   ```
   Legacy bash form: `scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-where (fn [e] (= :expired (get-in (:db-after e) [:auth-state]))))'`.
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
   - *Permanent change:* `Edit` the source file, then `mcp__re-frame-pair2__tail-build {probe: "..."}` to wait for the reload to land (legacy: `scripts/tail-build.sh --probe '...'`).
5. **Verify the patch took before re-dispatching.** `registrar/describe :event :foo` should now return different `:line` / `:column` (or a different `:handler-fn` hash) than what you captured at step 1. If the patch didn't land, re-dispatching will silently test the old code.
6. `trace/dispatch-and-collect [:foo ...]` → observe the new behaviour.
7. Compare the two epochs (`epoch-diff` between their `:db-after` values; cross-check `:sub-runs` and `:renders` projections). Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

## Stub an effect for an experiment

Per Spec 002 §Per-frame and per-call overrides, dispatches can carry `:fx-overrides` to redirect a registered fx to a stub for one cascade. Used to run "what if the HTTP request returned X" experiments without hitting the network.

```
mcp__re-frame-pair2__dispatch {
  event: "[:cart/checkout]",
  fx-overrides: {":http": ":stub-http"}
}
```

Legacy bash form: `scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http`.

The stub must already be registered via `(rf/reg-fx :stub-http (fn [_ v] ...))`. The override applies for this dispatch only; subsequent dispatches use the canonical `:http` again.

For `:rf.http/managed` failure-category experiments (Spec 014 §Failure categories), stub `:http` to fire one of the canonical failure traces directly.

## "Narrate the next N events"

Prefer the push-mode MCP path: call `mcp__re-frame-pair2__subscribe` with `{topic: "epoch", max-events: N}`. Each batch arrives as a `notifications/progress` tick; report each epoch as a short paragraph (event id, `:trigger-event`, key entries from `:effects` and `:sub-runs`, `app-db` diff summary) as it fires. The tool resolves with a summary once `max-events` is reached.

Fallback (host doesn't surface progress notifications): `mcp__re-frame-pair2__watch-epochs {count: N}` (pull-mode) with no filter, narrate on each pull.

See [streaming-subscriptions.md](streaming-subscriptions.md) for topic / filter / termination detail.

## "Alert me on slow events"

Prefer `mcp__re-frame-pair2__subscribe {topic: "epoch"}` with no server-side filter (the `epoch-matches?` filter vocab doesn't include a timing predicate — see [streaming-subscriptions.md](streaming-subscriptions.md) §Filter shape). On each `notifications/progress` tick, caller-side check the epoch's `:event/run` duration against the threshold; report matches with per-interceptor timings from the raw trace. Close with `unsubscribe` when the user moves on (or pass `max-ms` for a hard upper bound).

Fallback: `mcp__re-frame-pair2__watch-epochs {stream: true, pred: {"timing-ms": ">100"}}` (the timing predicate is applied caller-side under pull-mode; the wrapper hides that detail).

## "Watch for X while I interact"

Prefer `mcp__re-frame-pair2__subscribe {topic: "epoch", filter: {":event-id-prefix": ":checkout/"}}` (or other predicate from the `epoch-matches?` vocab — see [streaming-subscriptions.md](streaming-subscriptions.md) §Filter shape). Narrate each match as it arrives via `notifications/progress`; summarise when the stream goes idle. Close with `unsubscribe` (or `max-events` / `max-ms`) when the user moves on.

Fallback: `mcp__re-frame-pair2__watch-epochs {stream: true, pred: {"event-id-prefix": ":checkout/"}}`.

### Inspect what's currently subscribed

The `subscription-info` MCP tool reports every open subscription's `{:id :topic :filter :queue-depth :queue-bytes :dropped-events :dropped-bytes :overflow-reason :created-at}` without draining the queues. To list active streams:

```
mcp__re-frame-pair2__subscription-info {}
```

Optional filters: pass `topic` (`trace` / `epoch` / `fx` / `error`) to narrow, or `sub-id` to look up a specific stream. Use this when a streaming probe seems to have gone quiet — confirm it's still registered (and that its queue-depth isn't piling up against a dead consumer) before assuming the bus is dry.

## "Drive a Story variant from a pair2 session"

**Why this works:** a Story variant *is* a re-frame2 frame — the variant id is also the frame id. Every pair2 op that takes `--frame` works against a variant out of the box. See [variant-as-frame.md](variant-as-frame.md) for the full pattern.

**Setup.** A Story-enabled build is running (the user has `re-frame.story` loaded; some variants are registered). Either the variant is already mounted in the canvas, or you'll mount it via story-mcp / `run-variant`.

**Procedure:**

1. List candidate variants: `frames/list`, filter to the `story` namespace.
   ```
   mcp__re-frame-pair2__eval-cljs {
     form: "(filter #(= \"story\" (namespace %)) (rf/frame-ids))"
   }
   ```
   Legacy bash form: `scripts/eval-cljs.sh '(filter #(= "story" (namespace %)) (rf/frame-ids))'`.
   If the user has the story-mcp jar loaded, prefer `mcp__re-frame2-story-mcp__list-stories` — richer metadata (tags, modes, parent story).
2. If the variant isn't mounted yet, mount it via story-mcp:
   ```
   mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
   ```
   This dispatches loaders + events + (optionally) play into the variant's frame.
3. Scope the pair2 session to that variant:
   ```
   frames/select :story.counter/loaded
   ```
   Subsequent reads/writes/watches inherit this frame.
4. Operate normally — `app-db/snapshot`, `dispatch`, `trace/last-epoch`, etc. The variant's isolated state is what you see.

**Expected output shape.** Same as any pair2 op, scoped to the variant's frame. `app-db/snapshot` returns whatever the variant's loaders + events seeded; `trace/last-epoch` returns the last dispatch (often the last `:play` event if the variant just mounted).

**Gotcha.** If you forget the `frames/select` (or `--frame` per call), dispatches land in `:rf/default` and you'll see nothing in the variant's history. See [variant-as-frame.md §Common gotchas](variant-as-frame.md#common-gotchas--variant-as-frame-specific).

## "Diff two variants of the same component"

**Why this works:** per-variant frame isolation (Story spec 007) means each variant carries its own `app-db`. When the user asks *"why does state diverge in scenario A vs scenario B?"*, you compare the two frames' app-db values directly.

**Setup.** Both variants are mounted (canvas or `run-variant`). Both belong to the same parent story, so they share `:component`, `:args` defaults, decorators — only the variant body diverges.

**Procedure:**

1. Snapshot each variant's `app-db`:
   ```
   mcp__re-frame-pair2__snapshot {frame: ":story.counter/empty"}
   mcp__re-frame-pair2__snapshot {frame: ":story.counter/loaded"}
   ```
   With the MCP `:summary` default (rf2-tygdv), each result returns top-level keys + counts — drill into divergent keys with `get-path`.
2. Compute the diff. If both are small, return them inline and let the model narrate. If they're large, drive `clojure.data/diff` directly:
   ```
   mcp__re-frame-pair2__eval-cljs {
     form: "(let [a (rf/get-frame-db :story.counter/empty)
                  b (rf/get-frame-db :story.counter/loaded)]
              (clojure.data/diff a b))"
   }
   ```
   Legacy bash form: `scripts/eval-cljs.sh '(let [a (rf/get-frame-db :story.counter/empty) b (rf/get-frame-db :story.counter/loaded)] (clojure.data/diff a b))'`.
   The runtime helper `(re-frame-pair2.runtime/frame-diff :a-id :b-id)` returns `{:only-in-a :only-in-b :common}` — semantics match `epoch-diff` but across frames instead of across one epoch's before/after.
3. Cross-check the cascade: `(rf/epoch-history :story.counter/empty)` and `(rf/epoch-history :story.counter/loaded)`. If the variants ran the same events but ended in different states, look at the loaders — they often seed divergent fixtures.
4. Narrate the divergence in terms the user can act on: *"variant `:loaded` carries `[:items]` with 7 entries from its `:counter/initialise 7` event; variant `:empty` has no `:items` key because its events list is empty."*

**Expected output shape.** A compact `{:only-in-a ... :only-in-b ... :common ...}` map (or the model's prose summary), keyed off paths that actually differ. Common subtree omitted unless the user asks for it.

**Gotcha.** A variant that hasn't been mounted yet returns `:rf.error/no-such-handler` (kind `:frame`) — the frame doesn't exist until `run-variant` or canvas-mount allocates it. Mount both before diffing.

## "Refine a variant interactively"

**Why this works:** the same loop that powers Story-MCP's self-healing pattern (`skills/re-frame2/reference/tooling/story-mcp-loop.md`) is observable from pair2 — modify the variant body via story-mcp, then watch the trace events as it re-runs. Pair2 sees every dispatch the play-runner makes, and you can intervene mid-loop without leaving the runtime.

**Setup.** Story-MCP write surface is enabled (`--allow-writes` / `RF_STORY_MCP_ALLOW_WRITES=true`). The variant exists; you want to iterate on its `:play` body to make an assertion pass.

**Procedure:**

1. Read the current body:
   ```
   mcp__re-frame2-story-mcp__get-variant {variant-id: ":story.counter/loaded"}
   ```
2. Open a pair2 watch scoped to the variant before re-running, so you see every dispatch the play-runner makes:
   ```
   mcp__re-frame-pair2__subscribe {topic: "epoch", filter: {":frame": ":story.counter/loaded"}}
   ```
   Each `notifications/progress` tick carries one epoch record from the variant's cascade.
3. Re-register with the refined body:
   ```
   mcp__re-frame2-story-mcp__register-variant
     {variant-id: ":story.counter/loaded"
      body: {:extends :story.counter
             :events [[:counter/initialise 7]]
             :play   [[:counter/inc]
                      [:rf.assert/path-equals [:count] 8]]}}
   ```
   `reg-variant*` calls `reset-frame` on the variant's frame; `app-db` reverts to `{}`, loaders re-run, then events.
4. Run it:
   ```
   mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
   ```
   As the play-runner dispatches each `:play` event, the pair2 subscription emits its epoch. Narrate them in order.
5. Read failures:
   ```
   mcp__re-frame2-story-mcp__read-failures {variant-id: ":story.counter/loaded"}
   ```
6. If `:passing? false`, repeat from step 3 with a refined body. Pair2's subscription stays open across iterations — close it with `unsubscribe` when the loop terminates.

**Expected output shape.** Stream of epoch records on the pair2 channel (one per play event), plus a `:passing?` boolean + `:assertions` list from `read-failures`. Successful loop ends with `:passing? true`.

**Gotcha.** `:reset-frame` on re-registration wipes any REPL-only state you injected (e.g. an `app-db/reset` you'd done in a prior iteration to set up a corner case). Bake the corner-case setup into `:events` or `:loaders` instead — the play-runner re-runs them each iteration, so the setup is durable across refinements.
