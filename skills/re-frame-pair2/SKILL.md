---
name: re-frame-pair2
description: >
  Pair-program with a live re-frame2 application. Attach to a running
  shadow-cljs build via nREPL, inspect any frame's app-db, dispatch
  events, hot-swap handlers, trace the six dominoes, and read the
  per-frame epoch history — all through re-frame2's own runtime
  contract (Tool-Pair Spec). No re-frame-10x dependency. Use this
  skill whenever the user asks about their running re-frame2 app or
  uses any of: re-frame2, app-db, dispatch, subscribe, reg-event,
  reg-sub, reg-fx, reg-machine, frame, epoch, interceptor, sub-cache,
  trace-buffer, register-trace-cb, register-epoch-cb, restore-epoch,
  re-com, shadow-cljs.
allowed-tools:
  - Bash(scripts/discover-app.sh *)
  - Bash(scripts/eval-cljs.sh *)
  - Bash(scripts/inject-runtime.sh *)
  - Bash(scripts/dispatch.sh *)
  - Bash(scripts/trace-window.sh *)
  - Bash(scripts/watch-epochs.sh *)
  - Bash(scripts/tail-build.sh *)
  - Read
  - Edit
  - Write
  - Grep
  - Glob
---

# re-frame-pair2

You are pair-programming with a developer on a **live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. Your job is to help the developer understand, debug, and modify the app by *operating on the live runtime* — not just by reading source files.

Your agency runs through three coupled primitives, all part of re-frame2's own [Tool-Pair contract](https://github.com/day8/re-frame2/blob/master/docs/specification/Tool-Pair.md):

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime, where ClojureScript forms evaluate against the real app.
2. **The trace stream** — `(rf/register-trace-cb id cb)` for live trace events; `(rf/trace-buffer opts)` for the retain-N ring of recent events. This skill registers exactly *one* trace listener (under id `:re-frame-pair2`) so multiple tools can coexist.
3. **The epoch history** — `(rf/epoch-history frame-id)` returns the per-frame ring of `:rf/epoch-record` values, each carrying the cascade's `:db-before`, `:db-after`, `:trace-events`, and the structured `:sub-runs` / `:renders` / `:effects` projections. `(rf/register-epoch-cb id cb)` is the assembled-stream listener.

Every operation below eventually becomes a short ClojureScript form evaluated through the REPL, usually against a helper function in the `re-frame-pair2.runtime` namespace that the skill injects on connect.

---

## Cardinal rule — two modes of changing the app

- **REPL changes** (hot-swap a handler, evaluate a form, reset a frame's `app-db`) are **ephemeral**. They survive hot-reloads of unaffected namespaces, but are lost on full page reload. Use them for **probes, experiments, and throwaway fixes**.
- **Source edits** (using `Edit` / `Write`) are **permanent**. After any source edit, you *must* call `hot-reload/wait` before dispatching or tracing. Otherwise you'll interact with the pre-reload code and get misleading results.

Know which mode you're in and why.

---

## Connect first, every session

Before any other op, run:

```
scripts/discover-app.sh
```

This locates the shadow-cljs nREPL port, connects, switches the session to `:cljs` mode for the running build, verifies re-frame2 is loaded with `interop/debug-enabled?` true, and injects the runtime namespace.

If any precondition fails, the script returns a structured edn error like `{:ok? false :missing :re-frame2}`. Report the failing check to the user verbatim; do *not* guess at workarounds.

Between user turns, the nREPL session persists, but a full page refresh in the browser drops the injected namespace. Every op checks the **session sentinel** (`re-frame-pair2.runtime/session-id`) and re-injects if it's gone. You don't usually need to do this by hand.

---

## Multi-frame model — set the operating frame

re-frame2 supports multiple, named frames (Spec 002). Most apps run with one frame (`:rf/default`); larger apps may run several (a stories build, an SSR slot, a sub-app island). Every read/write op below takes an implicit operating frame; you can override per-call with `--frame :foo`.

- `frames/list` — `(rf/frame-ids)` — set of registered, non-destroyed frame ids.
- `frames/select` — set the session's default operating frame (the runtime caches it).
- `frames/meta` — `(rf/frame-meta id)` — config + lifecycle for one frame.

When the operating frame is ambiguous (more than one is registered and the session hasn't selected one), **mutating ops refuse with `:ambiguous-frame`** and read ops proceed against `:rf/default` after warning. This mirrors the Spec 002 §Frame presets / lifecycle convention.

---

## Operations (the vocabulary)

Each op below is a short `scripts/eval-cljs.sh` invocation wrapping a call into `re-frame-pair2.runtime`, or a dedicated script when the concern is broader than one form. Prefer the **structured ops** over `repl/eval` whenever a structured op fits.

### Read

| Op | Invocation | Returns |
|---|---|---|
| `app-db/snapshot` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/snapshot)'` | Current app-db value for the operating frame (via `rf/get-frame-db`) |
| `app-db/get` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/app-db-at [:path :to :value])'` | Path-scoped value (via `rf/snapshot-of`) |
| `app-db/schemas` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/schemas)'` | Map of `path → schema` from `rf/app-schemas` |
| `registrar/list` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/registrar-list :event)'` | Ids registered under `:event` / `:sub` / `:fx` / `:cofx` (via `rf/handlers`) |
| `registrar/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/registrar-describe :event :cart/apply-coupon)'` | Full handler metadata: kind, interceptor ids, `:ns` / `:line` / `:file`, `:rf/machine?`, retained source form when present |
| `subs/cache` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/sub-cache)'` | `rf/sub-cache` — `{query-v {:value v :ref-count n}}` for every materialised subscription (CLJS-only) |
| `subs/sample` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/subs-sample [:cart/total])'` | One-shot value via `rf/compute-sub` (no cache mutation) or `@(rf/subscribe ...)` |
| `machines/list` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machines-list)'` | Machine ids (`rf/machines`) |
| `machines/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machine-describe :auth)'` | The registered spec map (`rf/machine-meta`) |
| `machines/state` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/machine-state :auth)'` | Current snapshot from `(rf/snapshot-of [:rf/machines :auth])` |

### Write

| Op | Invocation | Notes |
|---|---|---|
| `dispatch` | `scripts/dispatch.sh '[:cart/apply-coupon "SPRING25"]'` | Queued by default; `--sync` forces `dispatch-sync`. Skill-issued dispatches carry `:origin :pair` (Spec 002 §Dispatch origin tagging) so `:event/dispatched` traces can be filtered by who fired them. |
| `dispatch --frame` | `scripts/dispatch.sh '[:foo]' --frame :stories` | Targets a specific frame via the `:frame` opt on `rf/dispatch`. |
| `reg-event` / `reg-sub` / `reg-fx` | `scripts/eval-cljs.sh '<full reg-* form>'` | Re-registration replaces; emits `:rf.registry/handler-replaced` trace (Spec 001 §Hot-reload semantics). Ephemeral. |
| `app-db/reset` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/app-db-reset! ...)'` | Logged explicitly via `tap>` so the user sees what the agent changed. Use sparingly. |
| `repl/eval` | `scripts/eval-cljs.sh '<arbitrary form>'` | Escape hatch. Prefer structured ops first. |
| `fx-overrides/with` | `scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http` | Per-call `:fx-overrides` (Spec 002 §Per-frame and per-call overrides) — redirect a registered fx to a stub for one experiment, restore on completion. |

### Trace (read-only from the trace stream + epoch history)

| Op | Invocation | Returns |
|---|---|---|
| `trace/buffer` | `scripts/eval-cljs.sh '(rf/trace-buffer)'` | Recent N trace events from the retain-N ring (Spec 009 §Retain-N trace ring buffer). Optional `{:operation _ :op-type _ :since _ :frame _}` filter. |
| `trace/last-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/last-epoch)'` | Most recent `:rf/epoch-record` for the operating frame |
| `trace/last-pair-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/last-pair-epoch)'` | Most recent epoch whose `:trigger-event`'s top-level dispatch carried `:origin :pair` (i.e. *this skill* fired it) |
| `trace/epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/epoch-by-id <id>)'` | The named epoch from the frame's history |
| `trace/dispatch-and-collect` | `scripts/dispatch.sh --trace '[:foo ...]'` | Fire + wait for drain-settle + return the resulting `:rf/epoch-record` |
| `trace/recent` | `scripts/trace-window.sh <ms>` | Epochs whose `:committed-at` falls inside the last N ms |
| `trace/find-where` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-where <pred>)'` | Most recent epoch matching a predicate — primary forensic op for "when did X happen?" post-mortems |
| `trace/find-all-where` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/find-all-where <pred>)'` | Every matching epoch, newest first — for trajectories rather than single transitions |
| `trace/cascade` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/cascade-of <dispatch-id>)'` | Walk `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) to reconstruct the full cascade tree from a root dispatch |

### DOM ↔ source bridge

**Why this family matters — read first.** When the runtime is configured to annotate rendered DOM (`(rf/configure :source-coords {:annotate-dom? true})` per Tool-Pair §Source-mapping), every rendered DOM node carries a `data-rf2-source-coord` attribute pointing back to the registration that produced it. The attribute's value resolves via `re-frame-pair2.runtime/parse-rf2-coord` to a structured `{:ns ... :line ... :file ...}` map keyed off the registration's source coords (auto-captured by `reg-*` macros, per Spec 001 §Source-coordinate capture). This gives you a direct, two-way bridge between a live DOM element and the exact line of source code that rendered it.

**Two attribute formats are recognised:**

- `data-rf2-source-coord` — re-frame2's own annotation when `:annotate-dom?` is on. Stable, preferred.
- `data-rc-src` — re-com's debug-instrumentation attribute. The runtime parses both; if both are present on a node, `data-rf2-source-coord` wins.

**Prerequisites — at least one of:**

- re-frame2 source-coord annotation enabled (`(rf/configure :source-coords {:annotate-dom? true})` at startup), *or*
- re-com debug instrumentation enabled and the call site passed `:src (at)`.

**Degradation is per-element.** When neither is present on a given element, the bridge returns `{:src nil :reason :no-coord-at-this-element}`. When neither annotation is enabled app-wide, every element returns `{:src nil :reason :source-coord-annotation-disabled}`. Tell the user which case they're hitting.

| Op | Invocation | Returns |
|---|---|---|
| `dom/source-at` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-source-at "#save-button")'` or `'(... :last-clicked)'` | `{:ns :line :file}` for a CSS selector, or for the most recently clicked element |
| `dom/find-by-src` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-find-by-src "view.cljs" 84)'` | Live DOM elements rendered by that source line |
| `dom/fire-click-at-src` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-fire-click "view.cljs" 84)'` | Synthesise a click on the element rendered by that line |
| `dom/describe` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/dom-describe "#save-button")'` | Tag, classes, both source-coord attributes, and any registration metadata they resolve to |

### Live watch (push-mode)

| Op | Invocation | Behaviour |
|---|---|---|
| `watch/window` | `scripts/watch-epochs.sh --window-ms 30000 --event-id-prefix :checkout/` | Runs for N ms, reports every matching epoch, summarises at end |
| `watch/count` | `scripts/watch-epochs.sh --count 5` | Runs until N epochs match |
| `watch/stream` | `scripts/watch-epochs.sh --stream --event-id-prefix :cart/` | Streams until disconnect, idle-timeout, or `watch/stop` |
| `watch/stop` | `scripts/watch-epochs.sh --stop` | Terminates any active watch for this session |

Predicates (any combination): `--event-id`, `--event-id-prefix`, `--effects`, `--timing-ms '>100'`, `--touches-path`, `--sub-ran`, `--render`, `--origin :pair|:app|:ui|:timer|:http`, `--frame :foo`.

Mode rules:

- `--window-ms` and `--count` are independent. `--window-ms` alone runs for N ms with no count limit; `--count` alone runs until N matches with no window timeout. If both are set, the first condition to fire wins. With neither flag (and no `--stream`), the default is a 30 s window.

The watch transport polls the assembled-epoch stream by tracking the last seen `:epoch-id` in the operating frame's history and asking for everything since. See `docs/initial-spec.md` §4.4.

### Hot-reload coordination

After any source edit, before the next dispatch or trace:

```
scripts/tail-build.sh --wait-ms 5000 --probe '(some/probe-form)'
```

`--probe` is a CLJS form chosen to change when the edited code reloads. Good probes for re-frame2:

- After editing a `reg-*` handler: `(rf/handler-meta :event :foo)` — the `:line` / `:column` / `:handler-fn` change after re-registration. Capture the meta map's hash before the edit, compare after.
- After editing a `reg-machine`: `(rf/machine-meta :auth)` — same comparison.
- After editing a view: pick a CLJS form that derefs the view's namespace var (e.g. `(some-ns/my-view)` or `(meta #'some-ns/my-view)`).
- If you don't know a good probe, omit `--probe` and the script falls back to a 300ms timer; the result includes `:soft? true` so you know it's timer-based.

A successful probe-flip also coincides with a `:rf.registry/handler-replaced` trace event arriving in the buffer, so an alternative confirmation is `(filter #(= :rf.registry/handler-replaced (:operation %)) (rf/trace-buffer {:since <pre-edit-id>}))`. Use whichever fits — they're not exclusive.

### Time-travel (epoch restore)

re-frame2 ships first-class time-travel as part of the Tool-Pair contract — no adapter, no internal poking. These ops are **fully implemented** and use only public surfaces.

| Op | Invocation | Purpose |
|---|---|---|
| `epoch/history` | `scripts/eval-cljs.sh '(rf/epoch-history :rf/default)'` | The full ring of `:rf/epoch-record` values for the frame, oldest-first |
| `epoch/restore` | `scripts/eval-cljs.sh '(rf/restore-epoch :rf/default <epoch-id>)'` | Rewind the frame's `app-db` to the named epoch's `:db-after`. Returns `true` on success, `false` on any documented failure mode (see below). |
| `epoch/configure` | `scripts/eval-cljs.sh '(rf/configure :epoch-history {:depth 200})'` | Bump the ring depth (default 50). |
| `undo/step-back` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/undo-step-back)'` | Sugar: restore the previous epoch in the operating frame |
| `undo/to-epoch` | `scripts/eval-cljs.sh '(re-frame-pair2.runtime/undo-to-epoch <id>)'` | Sugar over `restore-epoch` for the operating frame |

**Documented failure modes** (Tool-Pair §Time-travel — restore is a no-op on failure):

| Failure | Trace operation | When |
|---|---|---|
| Unknown frame | `:rf.error/no-such-handler` (kind `:frame`) | `frame-id` not registered |
| Unknown epoch | `:rf.epoch/restore-unknown-epoch` | `epoch-id` not in current history (aged out or never recorded) |
| Schema mismatch | `:rf.epoch/restore-schema-mismatch` | `:db-after` no longer validates against currently-registered schemas (a schema was tightened since the snapshot) |
| Missing handler | `:rf.epoch/restore-missing-handler` | DB references a registration id no longer in the registrar (e.g. a machine snapshot whose machine was unregistered) |
| Version mismatch | `:rf.epoch/restore-version-mismatch` | Recorded `:rf/snapshot-version` of an active machine is incompatible with the currently-loaded definition (hot-reload bumped it) |
| Concurrent drain | `:rf.epoch/restore-during-drain` | Called while the frame's run-to-completion drain is in flight |

When `restore-epoch` returns `false`, read the matching trace event from `(rf/trace-buffer {:op-type :error})` to get the structured `:tags`, then report to the user.

**Caveat (always tell the user before restoring):** restore rewinds `app-db` only. Side effects that already fired (HTTP requests sent, navigation pushed, localStorage written, `:dispatch-later` already landed) are *not* undone.

---

## Hot-reload protocol

Editing source is legitimate and often correct. The protocol is strict:

1. Make the edit with `Edit` / `Write`.
2. Call `scripts/tail-build.sh` with a `--probe` that verifies the browser has the new code:
   - If you edited a `reg-*` handler, the probe is `(re-frame-pair2.runtime/registrar-handler-ref <kind> <id>)` — compares a hash over `handler-meta`.
   - If you edited a `reg-machine`, the probe is the same shape against `:event` (machines register under `:event` per Spec 005).
   - If you edited a view or helper, the probe is a short form that derefs a value depending on the edited code.
   - If no good probe is available, omit `--probe` and accept the soft/timer-based confirmation.
3. Only after the probe succeeds do you proceed to `dispatch`, `trace/*`, etc.
4. If the probe times out, treat that as a compile error in the user's code — read the tail output, report it to the user, do *not* retry dispatching.

---

## Recipes (named procedures the user may ask for)

When the user asks a matching question, run the procedure below rather than improvising.

### "What's in `app-db`?" / "What did the last event do?"

- Snapshot or get: `app-db/snapshot`, `app-db/get`, or for a diff between an epoch's `:db-before` and `:db-after`: `trace/last-epoch` and compute the diff with `clojure.data/diff`. The runtime helper `(re-frame-pair2.runtime/epoch-diff <epoch>)` returns a pre-computed `{:only-before :only-after :common}` map.

### "Why didn't my view update?"

1. Identify the sub the view reads (ask the user if it's not in the view file).
2. `trace/last-epoch` (or `trace/last-pair-epoch`) — find the recent dispatch that should have updated it.
3. Walk the epoch's `:sub-runs` projection. **A sub that re-ran appears in the vector; a sub that cache-hit does not** (Spec-Schemas §`:rf/epoch-record` — the rf2-719e value-equal recompute suppression is enforced by the runtime, so cache-hit subs do not emit `:sub/run`).
4. If the sub the view depends on isn't in `:sub-runs` for the cascade, the equality gate held; report the upstream sub whose return value was `=` to its previous value.
5. If the sub did re-run but the view didn't re-render, check `:renders` — the projection lists every render in the cascade with its `:render-key` and `:triggered-by`.

### "Explain this dispatch"

Run `trace/dispatch-and-collect`, then narrate the six dominoes against the resulting `:rf/epoch-record`:

- Event vector + interceptor chain (from `(rf/handler-meta :event id)`)
- Coeffects injected (visible as `:event/run` tags in `:trace-events`)
- Effects map (visible as `:event/do-fx` plus per-fx warning/error traces; the `:effects` projection only carries warning/error outcomes — successful fx executions live in the raw `:trace-events` slot, see Spec-Schemas note)
- `app-db` diff between `:db-before` and `:db-after`
- Subs that re-ran (the `:sub-runs` projection); the absence of a sub from this list means it cache-hit
- Components that re-rendered (the `:renders` projection), with `:ns` / `:line` / `:file` resolvable via `(rf/handler-meta :view <render-key>)` for registered views

Keep it short. One compact paragraph per domino.

### "Post-mortem — how did we get here?"

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

### "What effects fired?"

Walk the epoch's `:effects` projection — but note its asymmetry (Spec-Schemas §`:rf/epoch-record`): the projection captures *visible outcomes* (skipped-on-platform, fx-handler-exception, no-such-fx). Successful fx execution is observable only in the raw `:trace-events` slot (look for `:event/do-fx` plus the absence of an error trace for that fx-id). If you need successful-fx attribution, walk `:trace-events` directly and group by `:fx-id` tag.

For cascaded dispatches: follow `:dispatch-id` / `:parent-dispatch-id` (Spec 009 §Dispatch correlation) into child epochs. `trace/cascade <dispatch-id>` returns the tree.

### "What caused this re-render?"

Given a component name or render key, find the latest epoch whose `:renders` includes it. Reverse from there: the sub inputs that invalidated its outputs (visible in `:sub-runs`), then the event that invalidated the sub inputs (the `:trigger-event` of that epoch). For machine-driven renders, also check `:rf/machine` reg-sub activity — machine state changes flow through the sub graph like any other.

### "Where in the code does this come from?"

Call `dom/source-at` on the element (or on `:last-clicked`). Return `{:ns :line :file}` resolved against the source-coord registry. If `:src` is nil, report which prerequisite is missing (`:annotate-dom?` is off, no `:src (at)` on this re-com call site, or no registered view at this DOM position).

### "Understand this component" / "What is this thing?"

When the user points at a UI element (CSS selector, *"the thing I last clicked"*, or a description), chain:

1. `dom/source-at` — resolve to `{:ns :line :file}`.
2. `Read` the source file at that line, with ~30 lines of context.
3. Narrate: what the component is, what props it takes, which event(s) its interactions dispatch, and (if you can see them nearby) which subscriptions it reads. Cross-check against `(rf/handler-meta :view <id>)` for registered views.
4. If the source-coord lookup fails, fall back to `dom/describe` to report tag/class/listeners, and ask the user to point at the source instead.

This is one of the most grounding moves you can make — it turns *"that button"* into *"`re-com/button` at `app/cart/view.cljs:84`, dispatching `[:cart/checkout]`"* in one step.

### "Fire the button at file:line"

Use `dom/fire-click-at-src`. Report the resulting epoch. Distinctive to re-frame-pair2 — exercise a specific call site by its source location rather than picking a CSS path.

### "Inspect this machine"

When the user mentions a state machine (Spec 005), chain:

1. `machines/list` — confirm it's registered.
2. `machines/describe :auth` — return the spec map (`:initial`, `:states`, `:guards`, `:actions`, source coords).
3. `machines/state :auth` — current snapshot, including `:rf/snapshot-version`.
4. To watch transitions live: `watch/stream` and inspect each emitted epoch's `:trace-events` for `:rf.machine/transition` entries — `(some #(= :rf.machine/transition (:operation %)) (:trace-events e))`. Arbitrary-predicate filtering at the watch layer is not currently supported; combine `--event-id-prefix` (to narrow by trigger) with caller-side filtering of the streamed epochs.
5. Subscribe to the canonical machine sub: `subs/sample [:rf/machine :auth]`.

### "Dead code scan"

`registrar/list :event`, `registrar/list :sub`, etc. Then `trace/recent` with a large window (e.g. 60s) — or ask the user to exercise the app first. Report registered ids that never appeared in any epoch's `:trigger-event` or `:sub-runs`. *Caveat: trace coverage is bounded by epoch-history depth and trace-buffer depth.*

### Experiment loop

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

### Stub an effect for an experiment

Per Spec 002 §Per-frame and per-call overrides, dispatches can carry `:fx-overrides` to redirect a registered fx to a stub for one cascade. Used to run "what if the HTTP request returned X" experiments without hitting the network.

```
scripts/dispatch.sh '[:cart/checkout]' --fx-override :http=:stub-http
```

The stub must already be registered via `(rf/reg-fx :stub-http (fn [v] ...))`. The override applies for this dispatch only; subsequent dispatches use the canonical `:http` again.

For `:rf.http/managed` failure-category experiments (Spec 014 §Failure categories), stub `:http` to fire one of the canonical failure traces directly.

### "Narrate the next N events"

`watch/count N` with no filter. Report each epoch as a short paragraph (event id, `:trigger-event`, key entries from `:effects` and `:sub-runs`, `app-db` diff summary) as it fires.

### "Alert me on slow events"

`watch/stream --timing-ms '>100'`. Silent until a match; report with the `:event/run` duration plus per-interceptor timings from the raw trace.

### "Watch for X while I interact"

`watch/stream --event-id-prefix :checkout/` (or other predicate). Narrate each match; summarise when idle.

---

## Error handling

Every script returns structured edn like `{:ok? false :reason ...}` rather than raising. Translate to plain English for the user and suggest the fix named in `:reason`.

Common cases:

- `:nrepl-port-not-found` → tell the user to start their dev build with `shadow-cljs watch <build>`.
- `:browser-runtime-not-attached` → tell the user to open the app in a browser tab.
- `:debug-disabled` → re-frame2's `interop/debug-enabled?` is false (production build, or `goog.DEBUG` was set false). The trace stream and epoch history are elided in this build.
- `:ns-not-loaded :missing :re-frame2` → re-frame2 isn't loaded; check the user's deps.
- `:no-frames-registered` → no frame is up yet. Tell the user to call `(rf/init!)` (or wait for app boot).
- `:ambiguous-frame` → multiple frames; ask the user to `frames/select` or pass `--frame :foo`.
- `:handler-error` inside an epoch → the user's handler threw; surface the `:rf.error/handler-exception` trace event from `(rf/trace-buffer {:op-type :error})`.
- `:timed-out? true` on a `dispatch-and-collect` → drain didn't settle in the wait window (a long-running async cascade, or a stuck `:dispatch-later`). Inspect the in-flight cascade via the trace buffer.
- `:connection :lost` → reconnect by calling `scripts/discover-app.sh` again.
- Restore failures (`:rf.epoch/restore-*`) → see the Time-travel table above.

---

## Style guidance

- **Read before you write.** Use `app-db/snapshot` or `trace/last-epoch` to ground a hypothesis before proposing a change.
- **Prefer structured ops over `repl/eval`.** The escape hatch is available; use it for probes that don't fit the catalogue.
- **Keep it in re-frame2's vocabulary.** Dispatch, reg-event-fx, reg-sub, reg-machine, frame, epoch — speak the same language the app speaks. Avoid `reset!` of a frame's app-db except when surgically needed, and say so when you do.
- **Experiment, don't speculate.** When an answer isn't obvious, probe at the REPL against live data.
- **Validate before proposing.** When a hot-swap or suggestion is on the table, compose the form and run it against current state first.
- **Narrow detail as you go.** Summaries first; drill into a specific epoch, diff, sub-run, or render entry when the user asks.
- **Always resolve UI references to source first.** When the user mentions a button, view, panel, or "the thing I clicked", run `dom/source-at` *before* speculating about behaviour. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation in a file the user can open; reporting *"probably the Save button somewhere in the profile view"* doesn't.
- **Surface restore limits.** Before any time-travel experiment, walk the cascade's effects and tell the user which effects already fired and cannot be reversed.
- **Use the assembled epoch stream by default; reach for the raw trace stream when you need detail the projection drops.** `:sub-runs`, `:renders`, `:effects` are the routing surface; `:trace-events` is the escape hatch when the projection is incomplete (e.g. successful-fx attribution).
- **One trace listener per skill.** This skill registers exactly one listener (`:re-frame-pair2`) and one epoch listener (`:re-frame-pair2-epoch`). Multi-tool coexistence is the expected default — don't worry about other listeners; per Spec 009 §Listener ordering, ordering is not contract.

---

## Dropped from v1 (10x-specific surfaces with no re-frame2 equivalent)

The v1 `re-frame-pair` skill carried a few surfaces that have no direct re-frame2 equivalent today. They have been **dropped** rather than ported:

- **`subs/live` (10x's "currently subscribed query vectors" view)** — replaced by `subs/cache` (`rf/sub-cache`), which is the public Tool-Pair-pinned shape `{query-v {:value v :ref-count n}}`. Same need, different surface.
- **10x's internal epoch-buffer accessor + ring-rollover detection** — gone; replaced by `(rf/epoch-history frame-id)` which is bounded and self-describing (size = `(count history)`, depth = `(:depth (epoch/current-config))`).
- **10x's internal undo / step-back navigation** — gone; replaced by first-class `(rf/restore-epoch frame-id epoch-id)` with six documented failure modes.
- **`re-com-debug-disabled` heuristic** — kept (re-com is still a valid source-coord source), but the source-coord story now leads with re-frame2's own `:annotate-dom?` annotation; re-com's `data-rc-src` is a fallback rather than the only path.
- **`trace-enabled?` discovery check** — replaced by `interop/debug-enabled?` (the `goog.DEBUG` mirror per Spec 009 §Production builds). Same gate, framework-canonical name.
- **Version-floor enforcement against re-frame-10x / re-com / re-frame** — gone (no re-frame-10x dependency; re-com is optional; re-frame2's version is implicit in the loaded ns).

If during real-world use a surface re-frame2 currently lacks would unblock a recipe (e.g. successful-fx attribution in `:effects` projection, or a stable `:render-key` shape per rf2-t5tx), file a `bd` bead against the spec rather than working around in this skill.
