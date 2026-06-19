# Recipes

Named procedures the user may ask for. When the user asks a matching question, run the procedure below rather than improvising.

Each recipe is expressed in **MCP-tool form** — the only transport this skill exposes — using the flat `mcp__re-frame2-pair__*` tool vocabulary (`orient`, `snapshot`, `get-path`, `read-sub`, `dispatch`, `dispatch-dry-run`, `read-ui`, `read-dom`, `list-handlers`, `handler-meta`, …). Where a gesture has no dedicated tool, the recipe drops to `eval-cljs` over a `re-frame2-pair.runtime` helper — that is **first-class for the long tail**, not a last resort (see [§eval-cljs is the workhorse](#eval-cljs-is-the-workhorse)). **Named state-rewrite gestures route through the dedicated, gated tools** — `restore-epoch` and `replace-app-db` are the canonical, `--allow-writes`-gated path; the raw eval forms are the backstop for a gate-OFF server (see [ops.md §Time-travel](ops.md#time-travel-epoch-restore)).

## Contents

- [eval-cljs is the workhorse](#eval-cljs-is-the-workhorse)
- ["What is this app?" / First contact](#what-is-this-app--first-contact)
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
- ["What would this event do?" (dry-run)](#what-would-this-event-do-dry-run)
- [Stub an effect for an experiment](#stub-an-effect-for-an-experiment)
- ["Narrate the next N events"](#narrate-the-next-n-events)
- ["Alert me on slow events"](#alert-me-on-slow-events)
- ["Watch for X while I interact"](#watch-for-x-while-i-interact)
- ["Record while I interact" / "Wait until X happens"](#record-while-i-interact--wait-until-x-happens)
- ["Drive a Story variant from a re-frame2-pair session"](#drive-a-story-variant-from-a-re-frame2-pair-session)
- ["Diff two variants of the same component"](#diff-two-variants-of-the-same-component)
- ["Refine a variant interactively"](#refine-a-variant-interactively)

## eval-cljs is the workhorse

The recipes lead with **structured tools** (`orient`, `snapshot`, `get-path`, `read-sub`, `dispatch`, `dispatch-dry-run`, `read-ui`, `read-dom`, `list-handlers`, `handler-meta`) because each returns a validated, elided, single-round-trip answer for the gesture it owns. **Named state rewrites have structured tools too** — `restore-epoch` and `replace-app-db` are the canonical, audited path for time-travel undo and state injection (see the [Experiment loop](#experiment-loop) below and SKILL.md §Time-travel writes). But re-frame2-pair also exposes `eval-cljs` — arbitrary ClojureScript against the live runtime — and in a real session it carries the **long tail**: anything the dedicated tools don't have a shape for.

**The rule:** *prefer a structured op WHEN ONE FITS the gesture — including the dedicated write tools for named rewrites; for the long tail and for recovery, `eval-cljs` is first-class, not a last resort.*

`eval-cljs` is the right call — not a fallback — for:

- **Forensics over the epoch ring** — `find-where`, `find-all-where`, `cascade-of`, `epoch-diff`, `frame-diff`, `last-epoch`, `epoch-history`. There is no dedicated tool for "find the epoch where X went bad"; the `re-frame2-pair.runtime` helpers are the surface (see [ops.md §Trace](ops.md#trace)).
- **Reading arbitrary DOM by selector** when `read-ui`'s view-root resolution doesn't fit — a portal, a fragment leaf, a node outside any tagged view. `(re-frame2-pair.runtime/dom-describe "sel")` / `(... dom-source-at "sel")` reach it.
- **Joining or cross-referencing** projection data — diffing two frames, correlating a sub-value against an app-db path, walking a cascade tree — where the answer is a *computation* over several reads, not a single read.
- **Recovery — re-run the same query as `eval-cljs` when a structured read returns blank or errors.** A blank `read-dom` / `read-sub` / `read-ui` result usually means the OP (or its underlying eval form) is broken, NOT that the connection is stale. Re-issue the equivalent `eval-cljs` form to confirm the runtime is actually answering; if the eval returns the value, report the structured op as suspect (see [errors.md §A structured read came back blank](errors.md#a-structured-read-came-back-blank)).

Every `eval-cljs` form takes the same `frame: ":foo"` arg the dedicated tools do — pass it in a multi-frame app so `(rf/subscribe …)` / `(rf/dispatch …)` inside the form resolve against the right frame.

## "What is this app?" / First contact

**Your first move on an unfamiliar app — a cart, a dashboard, anything you didn't write.** After `discover-app` connects, run `orient` *before any other read*. It is one round-trip that maps the whole app: which frames are app vs reserved tool frames, each app frame's top-level app-db keys, the registry **counts** per kind, and the navigable event / sub / fx / machine **id lists** — compact by construction (counts + ids + top-keys, never the full app-db).

```
mcp__re-frame2-pair__orient {}
```

Returns `{:ok? true :liveness {…} :frames {:all [...] :app [...] :operating <id>} :app-db-top-keys {<app-frame> [<top-level key>…]} :registry {:counts {<kind> N…} :events [...] :subs [...] :fx [...]} :machines [...]}`. Reserved `:rf/*` tool frames (Xray's `:rf/xray`, an SSR slot, …) are excluded from `:app-db-top-keys` so the summary never overflows on a tool frame's working set.

Narrate it back to the user as the app's shape — *"this app runs one app frame `:rf/default`, with `:cart` / `:route` / `:user` at the top of its db, 14 events, 9 subs, 3 fx, and one machine `:checkout`."* Then **drill** into whatever the user's question is about:

- one sub's value → `read-sub {sub: "[:cart/total]"}`
- one app-db path → `get-path {path: "[:cart :items]"}`
- a bounded sub-tree → `snapshot {path: "[:cart]"}`
- a registration's source → `handler-meta {kind: "event", id: ":cart/checkout"}`

Never read a whole frame to orient — `orient` already handed you the map. `snapshot` / `get-path` are *drill-in* tools you hand a `path`, not the way you take in an app.

## "What's in `app-db`?" / "What did the last event do?"

- **Read a slice with the dedicated tools.** `snapshot {path: "[:user :profile]"}` for a bounded sub-tree, or `get-path {path: "[:user :profile :email]"}` for one targeted value (`{:exists?}` distinguishes a `nil` from a missing path). For "what did the last event do?", read the most recent epoch and diff its `:db-before` / `:db-after`:
  ```
  mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/epoch-diff (re-frame2-pair.runtime/last-epoch))"}
  ```
  `epoch-diff` returns a pre-computed `{:only-before :only-after :common}` map (it wraps `clojure.data/diff`). `last-epoch` / `last-pair-epoch` (the latter scoped to dispatches *this skill* fired, tagged `:origin :pair`) are `eval-cljs` runtime helpers — there is no dedicated epoch-read tool. `trace-window {ms: <N>}` is the dedicated tool for "epochs in the last N ms".
- From the MCP transport, `snapshot`'s `:app-db` slice defaults to **`:summary` mode** (top-level keys + count + size marker, not the full value); drill in with `get-path {path: "[:that :key]"}` or a follow-up `snapshot {path: "[:that :key]"}`. Root path `path: "[]"` opts back into the full `:app-db` slice when you really want it — a last resort, and **never** against a reserved `:rf/*` tool frame (the server refuses it). See [`mcp-transport.md` §:app-db slice modes](mcp-transport.md#when-to-use-snapshot-vs-the-per-op-reads).

## "Why didn't my view update?"

**The deterministic observe loop: `dispatch {settle: true}` → `read-ui` / `read-dom`.** "Did my UI update?" is now one gesture — settle synchronously flushes renders, then you read what actually rendered. No manual `requestAnimationFrame` dance.

1. Identify the sub the view reads (ask the user if it's not in the view file). Confirm its current value with `read-sub {sub: "[:the/sub]"}` (validated + elided — never a silent nil on a typo'd id).
2. Re-fire the event with the deterministic settle, so the cascade commits and renders flush before you read:
   ```
   mcp__re-frame2-pair__dispatch {event: "[:profile/save …]", settle: true}
   ```
   The result carries the full epoch including `:render-events`, plus a `:cascade-summary` with the `:renders` count.
3. Walk the epoch's `:sub-runs` projection. **A sub that re-ran appears in the vector; a sub that cache-hit does not** (Spec-Schemas §`:rf/epoch-record` — value-equal recompute suppression is enforced by the runtime, so cache-hit subs do not emit `:rf.sub/run`).
4. If the sub the view depends on isn't in `:sub-runs` for the cascade, the equality gate held; report the upstream sub whose return value was `=` to its previous value.
5. If the sub did re-run but the view didn't re-render, check `:renders` — the projection lists every render in the cascade with its `:render-key` and `:triggered-by`.
6. To confirm whether the DOM *actually* changed (vs. the projection saying it should have), read what rendered: `read-ui {view-id: ":my.app/header"}` (returns content **and** the producing entity + its `subs-read`) or `read-dom {selector: "<the view's node>"}` (content-only by explicit selector). The data plane (`:sub-runs` / `:renders`) says what *should* have rendered; `read-ui` / `read-dom` say what's actually on screen. If the read comes back blank, re-run it as `eval-cljs` to confirm the op (not the runtime) is the suspect — see [§eval-cljs is the workhorse](#eval-cljs-is-the-workhorse).

## "Explain this dispatch"

Fire the event and capture its full epoch in one call — `dispatch {event: "[:cart/apply-coupon \"SPRING25\"]", settle: true}` (the dedicated tool; `:settle` returns the assembled `:rf/epoch-record` incl. `:render-events`, `:trace: true` returns the epoch without the synchronous render flush). The eval equivalent is `(re-frame2-pair.runtime/dispatch-and-collect [:cart/apply-coupon "SPRING25"])` — reach for it only when you need the raw runtime return shape. Then narrate the six dominoes against the resulting `:rf/epoch-record`:

- Event vector + interceptor chain (from `handler-meta {kind: "event", id: ":cart/apply-coupon"}`)
- Coeffects injected (visible as `:event/run` tags in `:trace-events`)
- Effects map (visible as `:event/do-fx` plus per-fx warning/error traces; the `:effects` projection only carries warning/error outcomes — successful fx executions live in the raw `:trace-events` slot, see Spec-Schemas note)
- `app-db` diff between `:db-before` and `:db-after`
- Subs that re-ran (the `:sub-runs` projection); the absence of a sub from this list means it cache-hit
- Components that re-rendered (the `:renders` projection). Each row's `:render-key` is a **tuple** `[<view-id-or-:rf.view/anonymous> <instance-token>]` (Spec-Schemas `:rf/epoch-record` `:renders`), so resolve source coords from the **first** slot, not the whole tuple: `(first render-key)` is the registered view id you pass to `handler-meta {kind: "view", id: <view-id>}` to get `:ns` / `:line` / `:file`. When the first slot is `:rf.view/anonymous` (a plain Reagent fn, not `reg-view`-registered) `handler-meta` will return `:not-registered` — fall back to `read-ui`'s `:entity` `:view-id` / `:source-coord`, which resolves the producing entity directly. Passing the whole tuple as the `id` always yields `:not-registered`.

Keep it short. One compact paragraph per domino.

## "Post-mortem — how did we get here?"

**When the user is stuck in a broken state and can't describe how they got there.** Every cascade since page load is in the operating frame's `epoch-history` (subject to retention — default depth 50, configurable). You don't need the user to remember the sequence; you can walk it back.

Procedure:

1. Ask the user what's wrong in *observable* terms ("the save button is grey", "the dashboard is empty"). Resolve any UI references to source first — `read-ui {selector: "<the element>"}` (returns the producing view-id + source-coord) or, for just the coord, `eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save\")"}`.
2. Identify the **app-db key(s) or sub(s)** that govern the observation. If the user can't, trace the recent render for the offending component and walk its sub inputs.
3. Pinpoint the epoch where the governing key last changed to its current (bad) value with the `find-where` forensic helper (there is no dedicated tool for "when did X happen?" — `eval-cljs` over the runtime fn is the surface):
   ```
   mcp__re-frame2-pair__eval-cljs {
     form: "(re-frame2-pair.runtime/find-where
              (fn [e] (= :expired (get-in (:db-after e) [:auth-state]))))"
   }
   ```
4. Report that epoch as the culprit: its `:trigger-event`, the diff between `:db-before` and `:db-after`, and (crucially) the cascade tree. Often the root-cause dispatch is a child of another event — follow `:parent-dispatch-id` upstream via `eval-cljs {form: "(re-frame2-pair.runtime/cascade-of <dispatch-id>)"}`.
5. If no single epoch is responsible — the state drifted over many events — use `eval-cljs {form: "(re-frame2-pair.runtime/find-all-where <pred>)"}` to get the trajectory. Narrate the 3–5 most relevant transitions rather than all of them.
6. Propose a fix. Usually one of: a handler that shouldn't have fired, a handler that did fire but was wrong, or a missing guard.

**Retention caveat.** The epoch ring is bounded (default 50, configurable via `(rf/configure! {:epoch-history {:depth N}})`). Events that happened "a long time ago" may have aged out. If the user describes a state change you can't find in the ring, say so explicitly: *"I can see the last N events but the change you're describing happened before that."* Then propose reproducing the path from a known state — or `(rf/configure! {:epoch-history {:depth 500}})` and re-trigger.

## "What effects fired?"

Walk the epoch's `:effects` projection — but note its asymmetry (Spec-Schemas §`:rf/epoch-record`): the projection captures *visible outcomes* (skipped-on-platform, fx-handler-exception, no-such-fx). Successful fx execution is observable only in the raw `:trace-events` slot (look for `:event/do-fx` plus the absence of an error trace for that fx-id). If you need successful-fx attribution, walk `:trace-events` directly and group by `:fx-id` tag.

For cascaded dispatches: follow `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) into child epochs. `eval-cljs {form: "(re-frame2-pair.runtime/cascade-of <dispatch-id>)"}` returns the tree.

## "What caused this re-render?"

Given a component name or render key, find the latest epoch whose `:renders` includes it. Reverse from there: the sub inputs that invalidated its outputs (visible in `:sub-runs`), then the event that invalidated the sub inputs (the `:trigger-event` of that epoch). For machine-driven renders, also check `:rf/machine` reg-sub activity — machine state changes flow through the sub graph like any other.

## "Explain this error" / "What caused this error?"

1. Pull recent error traces: `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})`. Each entry is an `:rf.error/*` op with `:rf.error/data`. (Frame-id first; `:op-type` is a `:flat-only` filter so pass `:flat true`. Use the `re-frame.trace.tooling` ns — `rf/trace-buffer` is JVM-only and returns nil in the browser runtime this skill drives.)
2. Read `:rf.trace/trigger-handler` on the error event — `{:kind :event :id :user/save :source-coord {:ns ... :file ... :line ... :column ...}}`. This is the **handler that was executing when the error fired**, not the throw site inside the framework. Report it as `<kind> :<id> at <file>:<line>` so the user can jump straight to the source. (The field rides the trace surface — always present in the dev build this skill drives; it production-elides with the rest of the trace surface, so a production deployment would instead read the coord off the always-on error-emit record's `:source-coord`, not from a pair session.)
3. If the error sits inside a known epoch, cross-check `:trigger-event` and walk the cascade via `:parent-dispatch-id` — the upstream event that queued the offending handler is often the real culprit.
4. If `:rf.trace/trigger-handler` is **absent**, the error fired at dispatch-time before any handler ran (e.g. `:rf.error/no-such-event` because the registered id is misspelt). The `:rf.error/data` payload — the failing id, the lookup map — is then the only handle; offer to `list-handlers {kind: "event"}` (or the matching kind) to find a near match.

## "Where in the code does this come from?"

The one-call move is `read-ui {selector: "#save"}` (or `{point: {x, y}}` / `{view-id: "…"}`) — it returns the producing entity's `:source-coord {:ns :line :file}` directly. For *just* the coord (no content/entity), the `dom-source-at` runtime helper resolves a selector — or the most recently clicked element — against the source-coord registry:

```
mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save\")"}   ;; or (... :last-clicked)
```

Returns `{:ns :line :file}`. If `:src` is nil, report which prerequisite is missing: no registered view at this DOM position (anonymous Reagent fn, so no `data-rf2-source-coord` is stamped — re-frame2 annotates registered-view roots only), a non-DOM-capable adapter or production build, or no `:src (at)` on a re-com call site.

## "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), the one-call move is **`read-ui`** — it returns the rendered content AND the producing re-frame2 entity (view-id, source-coord, render-key, the frame's live `subs-read`) in a single round-trip, riding the `data-rf-view` map so it works with zero testids:

```
mcp__re-frame2-pair__read-ui {selector: "#save"}       ;; or {view-id: ":my.app/toolbar"} or {point: {x, y}}
```

Then chain:

1. `read-ui` (or, if you want only the source coord, `eval-cljs {form: "(re-frame2-pair.runtime/dom-source-at \"#save\")"}`) — resolve to `:entity {:view-id :source-coord {:ns :line :file} :subs-read [...]}` and `:content {:tag :text :attrs}`.
2. `Read` the source file at `:source-coord` `:line`, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and which subscriptions it reads (`:subs-read` already names them). Cross-check against `handler-meta {kind: "view", id: <id>}` for registered views.
4. If `read-ui` returns `:entity {:view-id nil :reason :no-tagged-view-root}` (a portal / fragment leaf), fall back to `eval-cljs` over `(re-frame2-pair.runtime/dom-source-at "sel")` then `(re-frame2-pair.runtime/dom-describe "sel")` to report tag/class/listeners, and ask the user to point at the source instead.

To read JUST the rendered content of a node you already have a selector for (no entity provenance needed), use `read-dom {selector: "..."}` — see [ops.md §read-dom](ops.md#read-dom--raw-dom-content-by-explicit-css-selector).

This is one of the most grounding moves you can make — it turns *"that button"* into *"`re-com/button` at `app/cart/view.cljs:84`, dispatching `[:cart/checkout]`"* in one step.

## "Fire the button at file:line"

Synthesise the click via the `dom-fire-click` runtime helper, then read the resulting epoch (`eval-cljs {form: "(re-frame2-pair.runtime/last-epoch)"}` or `trace-window {ms: 1000}`). Distinctive to re-frame2-pair — exercise a specific call site by its source location rather than picking a CSS path:

```
mcp__re-frame2-pair__eval-cljs {form: "(re-frame2-pair.runtime/dom-fire-click \"view.cljs\" 84)"}
```

## "Inspect this machine"

When the user mentions a state machine (Spec 005), chain:

1. `list-handlers {kind: "machine"}` — confirm it's registered (the `machine` kind lists every event handler flagged `:rf/machine? true`).
2. `handler-meta {kind: "machine", id: ":auth"}` — return the spec map (`:initial`, `:states`, `:guards`, `:actions`, source coords).
3. Current `:rf/machine-snapshot` — read it via the machine sub (snapshots live in the **runtime-db** partition, not app-db):
   ```
   mcp__re-frame2-pair__read-sub {sub: "[:rf/machine :auth]"}
   ```
   or eval `(get-in (rf/runtime-db-value frame-id) [:rf.runtime/machines :snapshots :auth])`. (Note: `get-path` reads app-db, so it will NOT find the snapshot — use the sub or `runtime-db-value`.) The snapshot shape is `{:state :data :tags? :meta?}` (`:rf/snapshot-version` lives under `:meta`, per Spec 005 §Snapshot shape). (The `machine-state` runtime helper is the eval equivalent.)
4. To watch transitions live: `subscribe {topic: "epoch", filter: {":event-id-prefix": ":auth/"}}` and inspect each emitted epoch's `:trace-events` for `:rf.machine/transition` entries — `(some #(= :rf.machine/transition (:operation %)) (:trace-events e))`. Arbitrary-predicate filtering at the subscribe layer isn't supported; combine `:event-id-prefix` (to narrow by trigger) with caller-side filtering of the streamed epochs.
5. The canonical machine sub is `[:rf/machine :auth]` — `read-sub {sub: "[:rf/machine :auth]"}` reads its current value (validated + elided).

## "Dead code scan"

`list-handlers {kind: "event"}`, `list-handlers {kind: "sub"}`, etc. (or `orient`, whose `:registry` already carries the counts + id lists). Then `trace-window {ms: 60000}` with a large window — or ask the user to exercise the app first. Report registered ids that never appeared in any epoch's `:trigger-event` or `:sub-runs`. *Caveat: trace coverage is bounded by epoch-history depth and trace-buffer depth.*

## Experiment loop

**Reach for `dispatch-dry-run` first — it is the safe primitive for hypothesis-testing.** When the user says *"test this handler"* / *"try a dispatch"* / *"what would this event do?"*, you don't need the full baseline → restore → modify → re-dispatch dance, and you should **not** reach for a live `dispatch` or a throwaway `eval-cljs` handler by default. `dispatch-dry-run {event: "[:foo …]"}` runs the whole cascade (reducer, interceptors, schema validation, machine transitions, sub-runs, renders) **without committing**: no fx execute and the framework auto-rolls-back the app-db via `restore-epoch`. It returns the same `:cascade-summary` shape as `dispatch` plus `:would-fire-effects` (every fx that *would* have fired, with args). See [§"What would this event do?"](#what-would-this-event-do-dry-run). Use the manual loop below only when you need to **commit a change and compare two REAL epochs** — i.e. you're iterating on a handler's code, not just reading one consequence.

**Probing a *throwaway* handler — register, then dry-run.** `dispatch-dry-run` targets a **registered** event, so to test a hypothesis handler you wrote on the spot: register it with `eval-cljs`, then dry-run it — never drive it with a live `dispatch`:

```
mcp__re-frame2-pair__eval-cljs {form: "(rf/reg-event :exp/probe (fn [{:keys [db]} _] {:db (assoc db :exp/x 1)}))"}
mcp__re-frame2-pair__dispatch-dry-run {event: "[:exp/probe]"}
```

The dry-run rolls back, so even a misbehaving probe leaves the live app-db untouched. Tear the registration down (or just leave it — it's ephemeral and gone on full page reload).

> **WARNING — a `reg-event` handler returning `{:db …}` REPLACES app-db wholesale; it does NOT merge.** `{:db <map>}` is the canonical "the new app-db is exactly this map" effect. A throwaway probe handler returning a bare literal — `(fn [_ _] {:db {:exp/x 1}})` — then driven by a **live** `dispatch` (or an `eval-cljs` that runs the real cascade) will nuke the *entire* frame's **app-db** (every boot-seeded app slice) — leaving only `{:exp/x 1}`, unrecoverable without `restore-epoch`. (Runtime-db — machine snapshots, routing, elision — is a *separate* partition a `:db` return cannot touch, so it survives; and a `:db` carrying a retired `:rf/runtime` key is a hard error, `:rf.error/legacy-runtime-root`.) This is a foot-gun an agent WILL hit. Two safe paths: **(1) dry-run the probe** (above) — the rollback means even a `{:db …}` handler can't damage the live db; **(2) if you must commit, preserve the existing db** — destructure the `db` cofx (it IS the live app-db) and return `{:db (assoc db :exp/x 1)}`, never a bare literal map. Never test a `{:db …}` handler with a live `dispatch` against a frame whose state you can't afford to lose.

**Why the manual loop works:** the same starting `app-db`, the same event, only the code changes — so any difference in the resulting epoch is attributable to *your edit*, nothing else. That makes it a controlled experiment rather than a fix-and-pray. re-frame2's first-class `restore-epoch` makes the loop fully closed — no adapter caveats.

Canonical procedure (commit-and-compare):

1. `dispatch {event: "[:foo …]", trace: true}` → observe baseline. Capture the `:epoch-id` from the resulting record. (The eval equivalent is `(re-frame2-pair.runtime/dispatch-and-collect [:foo …])`.)
2. **Tell the user** which side effects in the cascade can't be rewound. Walk `:trace-events` for `:event/do-fx` involving non-pure fx (`:http`, navigation, localStorage, `:dispatch-later` that already landed) and warn before restoring.
3. Rewind to the captured epoch with the **dedicated `restore-epoch` tool** — the canonical, audited undo. It reinstalls the whole **frame-state** (both partitions: app-db *and* runtime-db, so machine snapshots / routes / elision rewind too; side effects and transient host state do not):
   ```
   mcp__re-frame2-pair__restore-epoch {epoch-id: "<epoch-id>"}
   ```
   Returns `{:ok? true :restored? true :cascade-summary {…} :unreplayable-effects [...]}` on success. On any documented failure mode it returns `{:ok? false :reason :restore-rejected}` (one of the seven modes — Tool-Pair §Time-travel; check `(re-frame.trace.tooling/trace-buffer :rf/default {:flat true :op-type :error})` for the specific tag — frame-id first, `:op-type` is `:flat-only`). Against a server launched **without** `--allow-writes` (the published default) the tool refuses with `:reason :rf.error/writes-disabled` — the operator flips the gate at launch to enable pair-driven writes. **Backstop only** (gate-OFF server + operator says proceed via eval): `eval-cljs {form: "(rf/restore-epoch! :rf/default <epoch-id>)"}` returns bare `true`/`false` and rides outside the structured envelope + the audit gate — say so when you fall back to it.
4. **Modify the part of the system you're iterating on.**
   - *Handlers / subs / fx:* `eval-cljs {form: "(rf/reg-event :foo …)"}` / `(rf/reg-sub :bar …)` / `(rf/reg-fx :baz …)`. The registrar replaces; `:rf.registry/handler-replaced` fires.
   - *Machines:* `eval-cljs {form: "(rf/reg-machine :auth …)"}` — bumps the machine's `:version` if one is supplied. Old snapshots may now `:rf.epoch/restore-version-mismatch` against this machine.
   - *Views / helpers (plain `defn`s):* redefine the var via `eval-cljs`. Subsequent renders pick up the new fn.
   - *Permanent change:* `Edit` the source file, then `mcp__re-frame2-pair__tail-build {probe: "…"}` to wait for the reload to land.
5. **Verify the patch took before re-dispatching.** `handler-meta {kind: "event", id: ":foo"}` should now return a different `:line` / `:column` (or a different `:handler-fn` hash) than what you captured at step 1. If the patch didn't land, re-dispatching will silently test the old code.
6. `dispatch {event: "[:foo …]", trace: true}` → observe the new behaviour.
7. Compare the two epochs — `eval-cljs {form: "(re-frame2-pair.runtime/epoch-diff …)"}` between their `:db-after` values; cross-check `:sub-runs` and `:renders` projections. Repeat until satisfied.
8. If the change was REPL-only and the user wants to keep it, *commit via source edit* — REPL changes are lost on full page reload.

## "What would this event do?" (dry-run)

**When the user wants to know the consequence of an event WITHOUT paying for it** — before firing a checkout, a destructive delete, anything that hits the network or navigates. `dispatch-dry-run` runs the full cascade — reducer, interceptors, schema validation, machine transitions, sub-runs, renders — then rolls the frame back via `restore-epoch` (which reinstalls the whole frame-state — both partitions — so any machine/route mutation the simulated cascade made is rewound too, not just app-db). No fx execute; every fx that *would* have fired is enumerated with its args.

```
mcp__re-frame2-pair__dispatch-dry-run {event: "[:cart/checkout]"}
```

Returns the same `:cascade-summary` shape as `dispatch` (so you read one vocabulary for both) plus:

- `:rolled-back? true` — the frame is unchanged after the simulation (the `restore-epoch` rewind reinstated the whole frame-state, both partitions).
- `:would-fire-effects [{:fx-id :http :args {...}} {:fx-id :navigate :args [...]}]` — the real-world impact, enumerated. Narrate this: *"checkout would POST to `/orders` and navigate to `:order-confirmation` — nothing has actually happened yet."*
- `:db-state-after-simulation {...}` — the would-be app-db (what state the cascade *would* have committed).
- `:cascade-summary {:db-diff {...} :outcome :ok\|:error ...}` — a schema violation surfaces as `:outcome :error`; the rollback still fires.

**Privacy.** Dry-run commits nothing, but it IS an AI-facing read surface — `:db-state-after-simulation` and each `:would-fire-effects[*].args` slot are elided server-side under the same `--allow-sensitive-reads` posture as `snapshot` / `get-path` (gate OFF by default — see [`vocabulary.md` §Privacy posture](vocabulary.md#privacy-posture--sensitive-and-the-raw-eval-carve-out)). That makes dry-run the **safer** path than a raw `eval-cljs` "what would happen?" loop for sensitive events.

Compose with `:fx-overrides` to simulate realistic conditions (a canned http response) without losing the rollback guarantee — user overrides win on conflict. Use this in place of the *baseline → restore → modify → re-dispatch* experiment loop when you only need to **read** the consequence once, not iterate on a handler.

## Stub an effect for an experiment

Per Spec 002 §Per-frame and per-call overrides, dispatches can carry `:fx-overrides` to redirect a registered fx to a stub for one cascade. Used to run "what if the HTTP request returned X" experiments without hitting the network.

```
mcp__re-frame2-pair__dispatch {
  event: "[:cart/checkout]",
  fx-overrides: {":http": ":stub-http"}
}
```

The stub must already be registered via `(rf/reg-fx :stub-http (fn [_ v] ...))`. The override applies for this dispatch only; subsequent dispatches use the canonical `:http` again.

For `:rf.http/managed` failure-category experiments (Spec 014 §Failure categories), stub `:http` to fire one of the canonical failure traces directly.

## "Narrate the next N events"

Prefer the push-mode MCP path: call `mcp__re-frame2-pair__subscribe` with `{topic: "epoch", max-events: N}`. Each batch arrives as a `notifications/progress` tick; report each epoch as a short paragraph (event id, `:trigger-event`, key entries from `:effects` and `:sub-runs`, `app-db` diff summary) as it fires. The tool resolves with a summary once `max-events` is reached.

Fallback (host doesn't surface progress notifications): poll `mcp__re-frame2-pair__watch-epochs {}` (no filter) repeatedly, passing the previous response's `:head-id` back as `since-id`, narrating each pull's `:matches`; stop once you've narrated N events. (`watch-epochs` is poll-only — there is no `count` arg.)

See [streaming-subscriptions.md](streaming-subscriptions.md) for topic / filter / termination detail.

## "Alert me on slow events"

Prefer `mcp__re-frame2-pair__subscribe {topic: "epoch", filter: {":timing-ms": ">100"}}` — the `:timing-ms` predicate rides server-side so only slow cascades cross the wire. Accepts a number (sugar for `>= N`) or a comparison string (`">100"`, `"<=50"`, `">=100"`, `"<200"`, `"=42"`); see [streaming-subscriptions.md](streaming-subscriptions.md) §Filter shape. On each `notifications/progress` tick, narrate matches and pull per-interceptor timings from the raw trace if needed. Close with `unsubscribe` when the user moves on (or pass `max-ms` for a hard upper bound).

Fallback (pull-mode, no host progress notifications): poll `mcp__re-frame2-pair__watch-epochs {pred: {"timing-ms": ">100"}}` repeatedly, advancing `since-id` to the previous response's `:head-id` each time.

## "Watch for X while I interact"

Prefer `mcp__re-frame2-pair__subscribe {topic: "epoch", filter: {":event-id-prefix": ":checkout/"}}` (or other predicate from the `epoch-matches?` vocab — see [streaming-subscriptions.md](streaming-subscriptions.md) §Filter shape). Narrate each match as it arrives via `notifications/progress`; summarise when the stream goes idle. Close with `unsubscribe` (or `max-events` / `max-ms`) when the user moves on.

Fallback: poll `mcp__re-frame2-pair__watch-epochs {pred: {"event-id-prefix": ":checkout/"}}` in a loop, passing the previous response's `:head-id` as `since-id` so each call only returns new matches. (There is no `stream` arg — looping the poll is the pull-mode equivalent.)

### Inspect what's currently subscribed

For the **live reactive sub-cache** — "what subscriptions are active in this frame?" — call `mcp__re-frame2-pair__list-subscriptions {frame: ":rf/default"}`. It reads the same source as `snapshot :sub-cache` and returns the cached query-vectors (reflecting disposal); pass `include-values: true` for current values + ref-counts.

When a **streaming probe** seems to have gone quiet, call `mcp__re-frame2-pair__list-streams {}` to confirm it's still registered and check its `:queue-depth` before assuming the bus is dry. Full return shape and filters: see [streaming-subscriptions.md §Diagnostics](streaming-subscriptions.md#diagnostics--what-streams-are-currently-registered).

## "Record while I interact" / "Wait until X happens"

**When the bug only reproduces under real human input** — a focus race, a render-timing glitch, a value that only flips after a real mouse drag. `subscribe` / `watch-epochs` stream *epochs*; the `record` / `watch-until` family observes arbitrary **signals** (an app-db path, a sub value, a DOM node's text/attribute, the focus slot) across the interaction window. All read-only — never dispatch, never mutate. See [ops.md §Signal recording](ops.md#signal-recording--blocking-waits) for the full SIGNAL / PRED vocabulary.

> **Privacy:** the `{:app-db [...]}` / `{:sub [...]}` signals sampled below are app-db-derived values that `read-recording` / `watch-until` ship back to the model, so they ride the same `--allow-sensitive-reads` posture as `get-path` / `read-sub` / `snapshot`. With the gate OFF (the published default), each `:app-db` / `:sub` sample is walked through `re-frame.core/elide-wire-value` server-side — declared-sensitive slots land as `:rf/redacted`, declared-large slots as `:rf.size/large-elided` — so a sensitive path (`[:auth :token]`) is safe to record by default. `{:dom ...}` / `{:focus true}` signals are host reads, not app-db slots, and pass through. To see the unmasked sample, the operator launches with `--allow-sensitive-reads` and you pass `:include-sensitive true`.

**Capture across an interaction (record → read-recording).** When the user says *"watch what happens to focus and the count while I click around"*:

1. Start the recorder — it returns immediately with a `:recording-id`, then runs in the background:
   ```
   mcp__re-frame2-pair__record {
     signals: "[{:focus true} {:dom \"#count\"}]",
     stop: {:ms 15000}
   }
   ```
2. Tell the user to interact now. The runtime samples each signal once per animation frame, records each CHANGE (deduped — a steady signal yields one baseline entry), and tears down at the stop condition (`{:ms N}` / `{:changes N}` / `{:pred {...}}`).
3. Read the change-log:
   ```
   mcp__re-frame2-pair__read-recording {recording-id: "rec-abc"}
   ```
   Each entry is `{:i <signal-index> :signal {...} :value <v> :t <ms> :frame <rAF-counter>}` — signals that changed on the same paint share a `:frame`. For a long-running session, poll with `drain: true` (consume buffered entries, keep recording) or close with `stop: true`.

**Block until a condition lands (watch-until).** When the next op should only run *after* something happens — *"wait until the upload finishes, then snapshot"*:

```
mcp__re-frame2-pair__watch-until {
  signals: "[{:app-db [:upload :status]}]",
  pred: {:signal 0 :equals :done}
}
```

Blocks (server polls ~100ms cadence) until the predicate holds — `{:ok? true :held? true :elapsed-ms ... :sample {0 :done}}` — or `timeout-ms` (default 30000) elapses, in which case `{:ok? false :reason :watch-timeout :last-sample {...}}` shows how close it got. Then proceed to the next read. PRED shapes are the same data-predicate maps `record`'s `:stop {:pred ...}` accepts.

## "Drive a Story variant from a re-frame2-pair session"

**Why this works:** a Story variant *is* a re-frame2 frame — the variant id is also the frame id. Every re-frame2-pair op that takes a `frame:` arg works against a variant out of the box. See [variant-as-frame.md](variant-as-frame.md) for the full pattern.

**Setup.** A Story-enabled build is running (the user has `re-frame.story` loaded; some variants are registered). Either the variant is already mounted in the canvas, or you'll mount it via story-mcp / `run-variant`.

**Procedure:**

1. List candidate variants: filter the registered frame-ids to the `story` namespace (there's no dedicated frame-list tool — `get-operating-frame {}` returns `:frames`, or use the runtime helper):
   ```
   mcp__re-frame2-pair__eval-cljs {
     form: "(filter #(= \"story\" (namespace %)) (rf/frame-ids))"
   }
   ```
   For richer metadata (tags, modes, parent story), `list-stories` is the **authoring** surface — allow-listed by the `re-frame2` skill, not by re-frame2-pair — so reach it via the authoring skill if a session has it loaded; otherwise the `eval-cljs` form above stays within re-frame2-pair's own surface.
2. If the variant isn't mounted yet, mount it via story-mcp:
   ```
   mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
   ```
   This dispatches loaders + events + (optionally) play into the variant's frame.
3. Scope the re-frame2-pair session to that variant:
   ```
   set-operating-frame {frame: ":story.counter/loaded"}
   ```
   Subsequent reads/writes/watches inherit this frame.
4. Operate normally — `snapshot`, `read-sub`, `dispatch`, `eval-cljs {form: "(re-frame2-pair.runtime/last-epoch)"}`, etc. The variant's isolated state is what you see.

**Expected output shape.** Same as any re-frame2-pair op, scoped to the variant's frame. `snapshot` returns whatever the variant's loaders + events seeded; `last-epoch` returns the last dispatch (often the last `:play-script` step if the variant just mounted).

**Gotcha.** If you forget to pin the frame (`set-operating-frame`) or pass a per-call `frame:` arg, the op resolves the operating frame by the four-tier contract (per-call > session pin > sole app frame > refuse). With the variant frame plus the host app's frame both live, that is two-plus app frames, so the op **refuses** with `:reason :ambiguous-frame` rather than silently targeting another frame — you see a refusal, not the variant's history. Pin the variant id (or pass `frame:`). See [variant-as-frame.md §Common gotchas](variant-as-frame.md#common-gotchas--variant-as-frame-specific).

## "Diff two variants of the same component"

**Why this works:** per-variant frame isolation (Story spec 007) means each variant carries its own `app-db`. When the user asks *"why does state diverge in scenario A vs scenario B?"*, you compare the two frames' app-db values directly.

**Setup.** Both variants are mounted (canvas or `run-variant`). Both belong to the same parent story, so they share `:component`, `:args` defaults, decorators — only the variant body diverges.

**Procedure:**

1. Snapshot each variant's `app-db`. `snapshot` selects frames via the **plural `frames`** arg (an array of frame-id strings or `"all"`) — it has no singular `frame` arg, unlike `dispatch` / `get-path` / `read-sub`:
   ```
   mcp__re-frame2-pair__snapshot {frames: [":story.counter/empty"]}
   mcp__re-frame2-pair__snapshot {frames: [":story.counter/loaded"]}
   ```
   (Or snapshot both in one round-trip — `snapshot {frames: [":story.counter/empty", ":story.counter/loaded"]}` — and diff the two entries.) With the MCP `:summary` default, each result returns top-level keys + counts — drill into divergent keys with `get-path`.
2. Compute the diff. If both are small, return them inline and let the model narrate. If they're large, drive `clojure.data/diff` directly:
   ```
   mcp__re-frame2-pair__eval-cljs {
     form: "(let [a (rf/app-db-value :story.counter/empty)
                  b (rf/app-db-value :story.counter/loaded)]
              (clojure.data/diff a b))"
   }
   ```
   The runtime helper `(re-frame2-pair.runtime/frame-diff :a-id :b-id)` returns `{:only-in-a :only-in-b :common}` — semantics match `epoch-diff` but across frames instead of across one epoch's before/after.
3. Cross-check the cascade: `(rf/epoch-history :story.counter/empty)` and `(rf/epoch-history :story.counter/loaded)`. If the variants ran the same events but ended in different states, look at the loaders — they often seed divergent fixtures.
4. Narrate the divergence in terms the user can act on: *"variant `:loaded` carries `[:items]` with 7 entries from its `:counter/initialise 7` event; variant `:empty` has no `:items` key because its events list is empty."*

**Expected output shape.** A compact `{:only-in-a ... :only-in-b ... :common ...}` map (or the model's prose summary), keyed off paths that actually differ. Common subtree omitted unless the user asks for it.

**Gotcha.** A variant that hasn't been mounted yet returns `:rf.error/no-such-handler` (kind `:frame`) — the frame doesn't exist until `run-variant` or canvas-mount allocates it. Mount both before diffing.

## "Refine a variant interactively"

**Why this works:** the same loop that powers Story-MCP's self-healing pattern (`skills/re-frame2/references/tooling/story-mcp-loop.md`) is observable from re-frame2-pair — the variant body is edited via the **authoring** surface, then re-frame2-pair watches the trace events as it re-runs. re-frame2-pair sees every dispatch the play-runner makes, and you can intervene mid-loop without leaving the runtime.

**Skill-boundary handoff.** Editing a variant body — reading it (`get-variant`) and re-registering it (`register-variant`) — is the **Story authoring** surface, allow-listed by the `re-frame2` skill, **not** by re-frame2-pair (see `SKILL.md` frontmatter + `stories.md §The five tools`). re-frame2-pair's own Story allow-list is the five live-session tools (`run-variant`, `read-failures`, `snapshot-identity`, `run-a11y`, `record-as-variant`). So the body-editing steps below are a **handoff to the authoring skill**: drive them under `re-frame2` (its `register-variant`/`get-variant` are reachable there), and let re-frame2-pair watch + run + diagnose against the live runtime. The handoff is by design — re-frame2-pair drives the runtime; `re-frame2` owns the source-of-truth variant body.

**Setup.** Story-MCP write surface is enabled (`--allow-writes` / `RF_STORY_MCP_ALLOW_WRITES=true`). The variant exists; you want to iterate on its `:play-script` body to make an assertion pass.

**Procedure:**

1. **(authoring skill)** Read the current body — `get-variant` is the `re-frame2` skill's surface, so reach it from there: `register-variant`/`get-variant {variant-id ...}` returns the current body to refine.
2. **(re-frame2-pair)** Open a watch scoped to the variant before re-running, so you see every dispatch the play-runner makes:
   ```
   mcp__re-frame2-pair__subscribe {topic: "epoch", filter: {":frame": ":story.counter/loaded"}}
   ```
   Each `notifications/progress` tick carries one epoch record from the variant's cascade.
3. **(authoring skill)** Re-register with the refined body via the `re-frame2` skill's `register-variant` (e.g. extend `:story.counter`, set `:events [[:counter/initialise 7]]`, set `:play-script` to drive `[:counter/inc]` then assert `[:rf.assert/path-equals [:count] 8]`). `reg-variant*` calls `reset-frame!` on the variant's frame; `app-db` reverts to `{}`, loaders re-run, then events.
4. **(re-frame2-pair)** Run it — `run-variant` IS in re-frame2-pair's allow-list:
   ```
   mcp__re-frame2-story-mcp__run-variant {variant-id: ":story.counter/loaded"}
   ```
   As the play-runner drives each `:play-script` step, the re-frame2-pair subscription emits its epoch. Narrate them in order.
5. **(re-frame2-pair)** Read failures:
   ```
   mcp__re-frame2-story-mcp__read-failures {variant-id: ":story.counter/loaded"}
   ```
6. If `:status :fail` (`result-passed?` is false), hand back to the authoring skill and repeat from step 3 with a refined body. A `:status :cannot-run` is the distinct third verdict — the runner could not attempt the plan; fix the runner/environment rather than the body. re-frame2-pair's subscription stays open across iterations — close it with `unsubscribe` when the loop terminates.

**Expected output shape.** Stream of epoch records on the re-frame2-pair channel (one per play event), plus a `:status` verdict (`:pass`/`:fail`/`:cannot-run`/`:error`, read via `result-status`/`result-passed?`) + `:assertions` list from `read-failures`. Successful loop ends with `:status :pass`.

**Gotcha.** `:reset-frame!` on re-registration wipes any REPL-only state you injected (e.g. a `replace-app-db` you'd done in a prior iteration to set up a corner case). Bake the corner-case setup into `:events` or `:loaders` instead — the play-runner re-runs them each iteration, so the setup is durable across refinements.
