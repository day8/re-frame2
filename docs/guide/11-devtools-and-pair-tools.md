# 11 — Devtools and pair tools

A re-frame2 app is **inspectable**. Every event the runtime processes, every fx that fires, every render, every error — they all emit structured trace events. Every drain-to-empty-queue settles into an epoch record with a `:db-before`, a `:db-after`, and the cascade's structured `:sub-runs`/`:renders`/`:effects` projections. Every registration carries source coordinates. Every rendered DOM node carries a back-pointer to the registration that produced it. Every frame's last N epochs are kept in a ring buffer, queryable and restorable.

These surfaces exist so **tools** — not the framework — can build inspection, debugging, and AI-pairing experiences on top.

This chapter is about that contract: what re-frame2 commits to, what tools consume it, and what you do at the keyboard to attach.

You'll know how to:

- Subscribe to the trace stream from a REPL or tool.
- Walk a frame's epoch history and restore an earlier state.
- Inject a synthetic `app-db` value (state injection) for repros and experiments.
- Parse the source-coord DOM attribute back to a code location.
- Read the static sub-graph topology.
- Understand what tools see when a frame is destroyed mid-observation.
- Recognise where the contract ends and where the consuming tool begins.

## What "pair tools" are

A pair tool is an AI/REPL companion that attaches to a running re-frame2 app. The canonical examples:

- **[day8/re-frame-pair](https://github.com/day8/re-frame-pair)** — an nREPL middleware + Claude integration that lets an agent inspect, dispatch, hot-swap handlers, time-travel, and stub fxs against a live app.
- **A future re-frame-10x v2** — a renderer of the same surfaces; trace listener, epoch-history consumer, registry inspector, all wrapped in a UI.
- **Custom debug panels** built into your app's dev build.

Each consumes the same runtime contract — the trace stream, the registrar query API, the epoch ring buffer, source coordinates. Multiple tools can attach simultaneously; listener ordering isn't contract.

re-frame2's commitment is the **contract**, not the tool. Two tools can disagree on UX, prompt design, or visualisation without disagreeing on the API.

## The trace stream

Every event the runtime emits — dispatch boundaries, handler invocations, fx applications, sub computations, errors, machine transitions, registrations, hot-swaps — flows through one channel: the trace stream.

Subscribe to it with `register-trace-cb!`:

```clojure
(rf/register-trace-cb!
  :my-tool/watcher
  (fn [ev]
    ;; ev is a structured trace map with :op-type, :operation, :tags, :timestamp, ...
    (println (:operation ev) (:tags ev))))
```

The callback fires once per emitted event — fine-grained, raw. The shape of the event is documented in [Spec 009 §Trace event](../../spec/009-Instrumentation.md). The load-bearing field is `:op-type` — the universal discriminator. Tools that only care about a single subsystem filter inside the callback:

```clojure
(rf/register-trace-cb!
  :my-tool/flow-panel
  (fn [ev]
    (when (= :flow (:op-type ev))
      (case (:operation ev)
        :rf.flow/registered  (track-flow-registration! ev)
        :rf.flow/computed    (record-flow-computation! ev)
        :rf.flow/skip        (note-skip! ev)
        :rf.flow/cleared     (drop-flow-state! ev)
        :rf.flow/failed      (surface-flow-error! ev)
        nil))))
```

The same pattern works for `:machine`, `:event`, `:sub/run`, `:fx`, etc. New op-types are additive (per [Spec 009](../../spec/009-Instrumentation.md)); a tool that doesn't recognise an op-type ignores it without breaking.

To unsubscribe:

```clojure
(rf/remove-trace-cb! :my-tool/watcher)
```

The trace surface is **dev-only** — gated on `re-frame.interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`). Production builds (`:advanced` + `goog.DEBUG=false`) elide every emit via Closure DCE. A shipped binary carries no trace bytes and no listener machinery.

### The retain-N trace ring buffer

For tools that want to read **recent history** without having been registered earlier, the runtime keeps a configurable ring buffer of the last N trace events:

```clojure
(rf/trace-buffer)                        ;; → vector of recent trace events, oldest-first
(rf/trace-buffer {:op-type :event})      ;; with optional filter map
(rf/configure :trace-buffer {:depth 1000})
```

Default depth is per-implementation. The buffer is dev-only and elides under production build flags.

## Per-cascade epoch records

`register-trace-cb!` is the **raw** stream. For tools that route diagnostics off "what just happened in this drain?", a complementary listener fires once per **drain-settle**, with the cascade's structured projections already computed:

```clojure
(rf/register-epoch-cb
  :my-tool/dashboard
  (fn [{:keys [frame event-id epoch-id sub-runs renders effects] :as record}]
    (record-recomputes! frame event-id (count sub-runs))
    (doseq [{:keys [render-key elapsed-ms]} renders]
      (record-render! frame (first render-key) elapsed-ms))
    (doseq [{:keys [fx-id outcome error-trace]} effects]
      (when (= :error outcome)
        (surface-fx-error! frame epoch-id fx-id error-trace)))
    nil))
```

The record (per [Spec-Schemas §`:rf/epoch-record`](../../spec/Spec-Schemas.md#rfepoch-record)) carries:

- `:epoch-id`, `:frame`, `:committed-at`, `:event-id`, `:trigger-event`
- `:db-before`, `:db-after`
- `:sub-runs` — every sub recomputation in this cascade (cache-hit subs are absent)
- `:renders` — every view that re-rendered, keyed `[<view-id> <instance-token>]`
- `:effects` — every fx that fired, with `:outcome` ∈ `{:ok :error :skipped-on-platform}`
- `:trace-events` — the raw trace slice, optionally

Two listener shapes coexist by design:

- **`register-trace-cb!`** is the raw stream — used by tools that need per-emit detail (custom recorders, error-monitor forwarders, timing aggregators).
- **`register-epoch-cb`** is the assembled stream — one fully-shaped record per drain-settle, used by tools that route diagnostics off "what happened in this cascade" rather than re-folding the raw stream each time.

Most pair-shaped tools prefer the assembled stream and reach for the raw stream only when they need detail the projection drops.

A throw inside an epoch-cb does not propagate to the framework or other listeners — the framework catches and continues. The throwing listener is **not** auto-evicted; eviction is the consumer's call.

## Epoch history and time-travel

Per frame, the runtime keeps a ring buffer of the last N epoch records. The default is 50; configure with `(rf/configure :epoch-history {:depth N})`.

```clojure
(rf/epoch-history :app/main)
;; → vector of epoch records, oldest-first
;;   each carries :epoch-id, :event-id, :db-before, :db-after, :sub-runs, ...

(rf/restore-epoch :app/main some-epoch-id)
;; rewinds the frame's app-db to the named epoch's :db-after
;; emits :rf.epoch/restored on success
;; returns true on success, false on any failure
```

`restore-epoch` rewinds the frame's `app-db` only — **effects already fired** (HTTP requests sent, navigation pushed, localStorage written) are not reversed. Pair tools surface this caveat in their UI before applying a restore.

Six failure modes are enumerated and named, each emitting a structured error trace event under the reserved `:rf.epoch/*` namespace:

| Failure | When |
|---|---|
| **Unknown frame** | The frame-id doesn't name a registered frame. |
| **Unknown epoch** | The epoch-id isn't in the frame's current history (aged out, or never recorded). |
| **Schema mismatch** | The recorded `:db-after` doesn't validate against the currently-registered schemas (someone added a stricter schema since the snapshot). |
| **Missing handler** | The recorded `app-db` references a registered id (a machine, a route) that's no longer in the registrar. |
| **Version mismatch** | The frame's recorded `:rf/snapshot-version` is incompatible with the currently-loaded machine definition. |
| **Concurrent-drain rejection** | Called while the frame's drain is still in flight; retry after settle. |

Pair tools display the `:operation` and `:tags` to the user and route from the failure to a remediation. The full table with `:tags` schemas is in [Tool-Pair §Time-travel](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo).

### Walking history and restoring — worked example

A pair tool's "rewind to before that event" affordance:

```clojure
(defn epoch-before-event
  "Return the epoch-id of the most recent epoch in `frame-id`'s history
   that precedes `target-event-id`, or nil."
  [frame-id target-event-id]
  (let [history (rf/epoch-history frame-id)
        before  (take-while #(not= target-event-id (:event-id %)) history)]
    (when (seq before)
      (:epoch-id (last before)))))

;; Listen for restore success / failure traces.
(rf/register-trace-cb!
  :my-tool/restore-watcher
  (fn [ev]
    (case (:operation ev)
      :rf.epoch/restored                    (notify-ui :restored ev)
      :rf.epoch/restore-unknown-epoch       (notify-ui :error ev)
      :rf.epoch/restore-schema-mismatch     (notify-ui :error ev)
      :rf.epoch/restore-missing-handler     (notify-ui :error ev)
      :rf.epoch/restore-version-mismatch    (notify-ui :error ev)
      :rf.epoch/restore-during-drain        (notify-ui :error ev)
      nil)))

;; Trigger.
(when-let [target (epoch-before-event :app/main :checkout/submit)]
  (rf/restore-epoch :app/main target))
```

The walk-then-restore pattern is the canonical pair-tool gesture; render-tree visualisers, "what did this event do?" probes, and conformance harnesses all build on the same primitives.

### Where the time-travel surface lives

Per Strategy B, the time-travel surface ships in the artefact `day8/re-frame-2-epoch`. Apps that want time-travel add it alongside core:

```clojure
{:deps {day8/re-frame-2        {...}
        day8/re-frame-2-epoch  {...}}}
```

When the artefact is on the classpath and `re-frame.epoch` is required at boot, the late-bind hooks publish so the public re-exports in `re-frame.core` (`epoch-history`, `restore-epoch`, `register-epoch-cb`, `reset-frame-db!`) reach into the hook table at call time.

When the artefact is **not** on the classpath, the read-shaped surfaces (`epoch-history`, `register-epoch-cb`) degrade silently — empty vector / no-op. A production build that omits the artefact does not raise. Mutating surfaces (`reset-frame-db!`) raise `:rf.error/epoch-artefact-missing` — the caller's invariant is "this changes state" and a silent no-op would lie.

## Direct state injection: `reset-frame-db!`

Sometimes you need to put a frame in a state the runtime never recorded. A pair-tool agent has just hot-swapped a handler that operates on an evolved `app-db` shape; running a real cascade would re-trigger broken handlers. A story tool wants to render the cart in a specific snapshot without authoring a setup event for every variant. A bug repro arrives as a serialised `app-db` from a user; the agent needs to load it.

`reset-frame-db!` is the supported write surface ([rf2-zq55](#), shipped in PR #170):

```clojure
(rf/reset-frame-db! :app/main {:cart {:items [{:sku "abc" :qty 2}]}
                                :checkout/state :ready})
;; Returns true on success, false on failure.
```

It bypasses the dispatch loop, replaces the frame's `app-db` container directly, and **records a synthetic epoch** so `restore-epoch` can rewind past the injection. The synthetic epoch carries:

- `:event-id :rf.epoch/db-replaced`
- `:trigger-event [:rf.epoch/db-replaced]`
- `:db-before` (the pre-reset value)
- `:db-after` (`new-db`)
- empty `:sub-runs` / `:renders` / `:effects` projections — no cascade ran

It also fires a `:rf.epoch/db-replaced` trace event and delivers the assembled record to every registered epoch listener — same shape as a cascade-settle delivery. Pair-tool dashboards filter on the operation to route pair-tool injections distinctly from cascade-driven epochs.

The empty projections are the **visible signal** that no cascade ran. If your tool routes off `:sub-runs`, `:renders`, or `:effects`, an empty entry tells you the epoch came from a direct write rather than dispatch.

Three failure modes:

| Failure | When |
|---|---|
| **Unknown frame** | `frame-id` doesn't name a registered frame. |
| **Drain in flight** | Called while the frame's drain is in progress; retry after settle. |
| **Schema mismatch** | `new-db` fails the frame's currently-registered `app-schema` set. With no schemas registered, every `new-db` is accepted. |

`reset-frame-db!` is **not** a substitute for `dispatch`. Use it only when bypass-the-cascade is required (the four use cases above); for anything you want the data loop to see, dispatch a real event.

```clojure
;; A pair-tool agent has hot-swapped a handler that needs a new app-db shape.
;; Inject the shape directly; then drive a single dispatch to verify.
(when (rf/reset-frame-db! :app/main {:cart {:items [{:sku "abc" :qty 2}]}
                                      :checkout/state :ready})
  (rf/dispatch [:checkout/submit] {:frame :app/main}))

;; Rewind PAST the injection: pick the epoch BEFORE the synthetic one.
(let [history (rf/epoch-history :app/main)
      pre     (last (filter #(not= :rf.epoch/db-replaced (:event-id %)) history))]
  (when pre
    (rf/restore-epoch :app/main (:epoch-id pre))))
```

## Source coordinates: clicking a button back to its source line

A pair tool gestures at a button on screen and asks "where in the code did this come from?" That requires every rendered DOM node to carry a back-pointer to the registration that produced it.

re-frame2's CLJS reference does this two ways, layered:

### 1. The `data-rf2-source-coord` DOM attribute

Every `reg-view`-rendered DOM element receives:

```html
<button data-rf2-source-coord="counter.core:counter:48:5" ...>+</button>
```

The four colon-separated segments are `<ns>:<sym>:<line>:<col>`:

- `<ns>` — the registration's namespace (`counter.core`)
- `<sym>` — the **registered handler-id** (the symbol passed to `reg-view`, here `counter`). Not a file path.
- `<line>` — source line at `reg-view` macro-expansion time
- `<col>` — source column

The format is a **public, parseable contract** ([rf2-q7r0](#), per [Spec-Schemas §`:rf/source-coord-attr`](../../spec/Spec-Schemas.md#rfsource-coord-attr)). Tools split on the colon and recover the four pieces directly.

To recover the file path too, follow the parsed handler-id back to the registration metadata:

```clojure
(:rf/source-coord-meta (rf/handler-meta :view :counter.core/counter))
;; → {:ns "counter.core", :file "counter/core.cljs", :line 48, :column 5}
```

`:rf/source-coord-meta` (per [Spec-Schemas](../../spec/Spec-Schemas.md)) carries all four keys including `:file`. The DOM attribute is the cheap-on-the-wire form; the registration metadata is the rich form.

The annotation is **dev-only** — gated on the universal `re-frame.interop/debug-enabled?`. Production builds elide via DCE; the rendered HTML in production carries no `data-rf2-source-coord` bytes.

Documented exemption: components whose outermost return is a React Fragment, a `:>` host-component head, or another non-DOM root are exempt. Pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

### 2. State-machine source-coord stamping

A complementary surface for state-machines: `reg-machine` is a macro that walks its literal spec form at expansion time and attaches a flat coord index under `:rf.machine/source-coords`, keyed by spec-path tuples:

```clojure
(:rf.machine/source-coords (rf/machine-meta :auth/login))
;; {[:guards :form-valid?]                {:ns ... :line ... :column ... :file ...}
;;  [:actions :commit]                    {...}
;;  [:states :form :on :submit]           {...}
;;  [:states :form :on :submit :action]   {...}}
```

Pair tools use this index for two distinct gestures:

- **Jump to definition.** A click on a guard or action name in a state-diagram visualisation reads `[:guards <id>]` / `[:actions <id>]` to find where the fn is implemented.
- **Jump to call site.** A click on a transition arrow or state node reads the deepest stamped path-tuple matching the node (e.g. `[:states :idle :on :submit]`); for keyword-named slots (`{:guard :form-valid?}`) the slot itself isn't stamped — the tool falls back to the enclosing transition's coord, which IS stamped.

The framework commits to **the index shape and the keyword-reference rule** (definition-site only for keyword refs, reference-site for inline-fn literals). Pair tools ship their own UI affordance over the index. Like `data-rf2-source-coord`, the stamping is gated on debug-enabled and elides under production build flags.

### Where the helpers live

The audit found upstream pair tools ship `dom/source-at`, `dom/find-by-src`, and `dom/fire-click-at-src` helpers. **re-frame2's commitment is the attribute, not the helpers.**

The runtime emits the attribute; the attribute's value format is a committed public contract. Consumers read it via their own host's DOM access (`document.querySelector` in CLJS, `page.locator` in Playwright-driven flows) and parse the four segments locally. The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` helpers — those depend on host-specific DOM access that re-frame2 the framework doesn't assume. A future minor version may introduce framework-side helpers if the ecosystem converges; the attribute contract is forward-compatible.

## The static sub-graph: `sub-topology`

Subscriptions chain — `:count-doubled` depends on `:count`. The framework knows the dependency graph at registration time (built from `:<-` declarations). For visualisation, dependency analysis, and "what depends on this sub?" navigation, the static graph is exposed:

```clojure
(rf/sub-topology)
;; → a static dependency graph
;;   {:nodes #{:count :count-doubled :auth/state ...}
;;    :edges #{[:count-doubled :count] ...}}
```

This is **static** — no runtime, no live cache, no Reagent. It reads off the registry. Use it to:

- Render a graph of "everything in the app that's derived."
- Find the leaves (subs nothing else depends on) — the stuff views actually read.
- Find the roots (subs that read `app-db` directly) — the ground truth.
- Spot dead subs (registered but no consumers).

The shape lives in [Spec 006 §Subscription topology](../../spec/006-ReactiveSubstrate.md). The function shipped in PR #142 ([rf2-8nzo](#)).

## What happens when a frame is destroyed mid-observation

Multi-frame architectures make frame churn ordinary — story tools mount and unmount frames freely; pair tools probe ephemeral test frames. A pair tool watching a frame's epoch history is guaranteed to encounter destroyed-frame races.

The runtime commits to a **closed contract** for these races so a tool can route them deterministically:

| Surface | Shape | Behaviour against destroyed frame |
|---|---|---|
| `(rf/epoch-history frame-id)` | read | Returns `[]`. Identical to "no epochs yet recorded"; consumers that want to distinguish a destroyed frame from a fresh one consult `(rf/frame-meta frame-id)` separately. |
| `(rf/get-frame-db frame-id)` | read | Returns `nil`. Consumers consult `(rf/frame-meta frame-id)` for destroyed-vs-unknown. |
| `(rf/restore-epoch frame-id epoch-id)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false`. |
| `(rf/reset-frame-db! frame-id new-db)` | mutate | Emits `:rf.error/no-such-handler` (kind `:frame`) and returns `false`. |
| Pre-registered `register-epoch-cb` callback whose observed frame is later destroyed | listener silencing | Runtime emits `:rf.epoch.cb/silenced-on-frame-destroy` once per `(frame-id, cb-id)` pair, with `:tags {:frame-id <id>, :cb-id <id>}`. The callback registration stays in place — eviction is the consumer's call. |

The pattern: **read-shaped surfaces return an empty shape** (so a defensive `(when ...)` is sufficient); **mutating-shaped surfaces raise structurally** (so a tool that intended a write learns the write did not happen); **listener fan-out emits a one-shot trace** when a previously-registered callback is silenced because its observed frame was destroyed.

Why "silencing" is a trace and not a return value: the `register-epoch-cb` callback never sees a record from a destroyed frame — the runtime stops producing records the moment the destroy walks. A tool that didn't know its observed frame was destroyed would see a callback that simply *stopped firing*, with no signal to route off. The silencing trace closes that gap.

The full contract is in [Tool-Pair §Surface behaviour against destroyed frames](../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames). Pattern source: rf2-d656.

## What you don't get from re-frame2

The contract above is sized to the framework's responsibility. Things explicitly **not** part of re-frame2:

- **The Claude integration itself** — prompts, retrieval, model selection. Lives in the pair tool.
- **The nREPL middleware** that bridges agent-to-runtime. Specific to the host.
- **The conversational interface** ("Tell me about every `:checkout/*` event"). The pair tool's job to prompt-engineer; re-frame2 ships data.
- **Skill-shaped retrospective analysis** of pair sessions. That's a separate, post-v1 artefact (`re-frame-pair-improver`) — a Claude skill that reviews sessions and proposes improvements to the pair tool itself.
- **DOM-to-source helpers** (`source-at`, `find-by-src`). Tool-side; depends on host-specific DOM access.

The split is deliberate: the framework commits to **stable data shapes and query APIs**; tools own **presentation and orchestration**. Multiple tools can coexist on the same contract without coordinating with each other or with the framework.

## Performance API consumption — the prod-friendly channel

A separate channel from the dev-only trace stream: the runtime emits **User Timing API** entries that any consumer with a `PerformanceObserver` can read. No re-frame2 API call needed.

Names are stable and namespaced under `rf:`:

```
rf:event:<event-id>
rf:sub:<sub-id>
rf:fx:<fx-id>
rf:render:<view-id>
```

```javascript
// Pull every re-frame entry from the recent run
performance.getEntriesByType('measure')
  .filter(e => e.name.startsWith('rf:'))
  .forEach(e => {
    const [_rf, bucket, ...idParts] = e.name.split(':');
    const id = idParts.join(':');
    sendToAPM(e);
  });

// Live: PerformanceObserver fires per emitted entry
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);
    }
  }
}).observe({ type: 'measure', buffered: true });
```

The channel is gated on `re-frame.performance/enabled?` — a `goog-define` boolean defaulting to `false`. When the flag is off (default), Closure DCE elides every bracket; `getEntriesByType` returns no `rf:`-prefixed entries because none were emitted. This is **opt-in for prod**; timing instrumentation has measurable cost on heavy hot paths and consumers should choose to pay it.

Flip the flag in your build:

```edn
;; consumer's shadow-cljs.edn
{:builds {:app {:target           :browser
                :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

The Performance API surface is **CLJS-only**. JVM artefacts (SSR, headless tests) emit no perf entries; tools running there use the host's profilers (clj-async-profiler, JFR).

## Putting it together — a minimal "trace + epoch" debug panel

A small in-app panel that prints the last 10 events and the current frame's last 5 epochs:

```clojure
(ns my-app.debug-panel
  (:require [re-frame.core :as rf]
            [reagent.core :as r]))

(defonce recent-events (r/atom []))

(rf/register-trace-cb!
  :my-app/debug-panel
  (fn [ev]
    (when (= :event (:op-type ev))
      (swap! recent-events (fn [evs] (->> (cons ev evs) (take 10) vec))))))

(rf/reg-view debug-panel []
  (let [events @recent-events
        epochs (take-last 5 (rf/epoch-history :rf/default))]
    [:aside.debug-panel
     [:h3 "Recent events"]
     [:ul (for [e events] ^{:key (:dispatch-id e)} [:li (str (:operation e))])]
     [:h3 "Recent epochs"]
     [:ul (for [ep epochs] ^{:key (:epoch-id ep)}
            [:li
             [:button {:on-click #(rf/restore-epoch :rf/default (:epoch-id ep))}
              (str (:event-id ep))]])]]))
```

That's the whole shape. The trace listener is a fn; the epoch list is a query; the restore is a single call. No framework extension, no plugin contract. The panel composes from the same surfaces the upstream pair tool consumes — the tool is just a richer renderer of the same data.

## Next

- [12 — Routing](12-routing.md) — the URL ↔ state contract.
- [Tool-Pair](../../spec/Tool-Pair.md) — the full normative contract for pair-shaped tools.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace stream's full shape and the error contract.
