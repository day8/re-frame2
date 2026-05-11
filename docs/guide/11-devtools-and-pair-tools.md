# 11 — Tooling

This is the third of the README's three pillars. The first was spec-first; the second was views-derivative. This one is the payoff.

Most frameworks bolt tooling on at the end. A devtools panel arrives a year after launch, a profiler a year after that, an AI integration somewhere off to the side; each tool reaches into the runtime through whatever crack it can find, and the cracks aren't the same. re-frame2 inverts the order. The runtime is **a predictable pipeline with a single, deeply integrated trace bus**, and every tool — devtools, story playgrounds, AI pair programmers, conformance harnesses, your own in-app debug panel — attaches to that one surface and gets the whole picture for free.

This chapter is the human-facing version of that pitch. What you actually get, what tools already exist or are in design, and how a tool attaches in five lines of code. The contract reference — destroyed-frame behaviour, the full failure-mode tables, the listener-silencing rules — is here too, in a clearly-marked Reference section at the bottom. Read top-down for the story; jump to the bottom when you need the exact shape.

You'll come away knowing:

- What re-frame2 commits to as a *tooling substrate*, in narrative form.
- Which tools already exist (`re-frame-pair2`) and which are in design (`re-frame-2-story`, `re-frame-10x` v2, machine visualisers).
- Why the **trace bus** and **per-cascade epoch records** are the architectural punchline — one observation surface, every tool consumes it.
- How **source-coord stamping** wires click-to-source from any panel.
- How to attach your own listener and build a working debug panel in twenty lines.
- Where to look when you need the contract reference (it's still here, just positioned at the end).

## What you get for free

A re-frame2 app, in dev mode, is **inspectable by default**. Without you writing a single instrumentation hook, the runtime produces:

- **A trace event for every meaningful runtime moment.** Dispatches, handler invocations, fx applications, sub computations, errors, machine transitions, registrations, hot-swaps — they all flow through one channel, the trace bus, as structured maps you can filter, route, or record.
- **An epoch for every drain-to-empty.** Each time the dispatch queue settles, the runtime emits a fully-shaped record with `:db-before`, `:db-after`, and structured projections of the sub-runs, renders, and effects that the cascade produced. Tools route diagnostics off these directly; you don't fold the raw stream yourself.
- **A ring buffer of the last N epochs per frame.** Scrub backwards. Restore to any prior `:db-after` with one call. Time-travel is not a feature bolted on by a devtools plugin — it's a runtime primitive.
- **Source coordinates on every registration.** Every event handler, every sub, every view, every fx knows the `{:ns :file :line :column}` of where it was registered. Every rendered DOM element carries `data-rf2-source-coord` pointing back at the view that produced it. Click anywhere on the screen, walk back to the line of code that put it there.
- **Static topology you can query.** The sub-graph is buildable from the registry without running the app. So is the registered-machine inventory. So is the fx index. Tools render dependency graphs, state-diagram visualisations, "what depends on this sub?" navigation — all off the same source-of-truth registries the runtime itself uses.

These surfaces exist for **tools** to consume — not for you to consume by hand, although you can. The framework's job is to keep the data shapes stable and well-named. The tools' job is to present them.

## The tools

Four tools, at different stages of life. They all share one substrate.

### `re-frame-pair2` — the AI pair-programming companion

[**`re-frame-pair2`**](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2) is a Claude Code skill that attaches an AI pair programmer to your running re-frame2 app. With it, the agent isn't just reading static source — it can dispatch events, hot-swap a handler, stub a fx, inspect `app-db`, walk epoch history, restore to a prior state, and read structured trace events as they fire. It's the v2 successor to the original [`re-frame-pair`](https://github.com/day8/re-frame-pair) — but where v1 reached into `re-frame-10x` internals to read the epoch buffer, v2 reads `(rf/epoch-history frame-id)` directly. Same vocabulary, no 10x dependency.

You'd use `re-frame-pair2` when you want an AI agent that can do experimentally what a human REPL session would: try a thing, see the trace, decide what's next. The skill's transcripts are kept structured precisely so [`re-frame-pair-improver2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair-improver2) — a sibling retrospective skill — can review them and propose improvements to the pair tool itself.

### `re-frame-2-story` — Storybook for frames *(in design)*

A Storybook-flavoured component playground built around re-frame2's frame primitive. Each story is a frame, with controls that dispatch events and surfaces that watch subscriptions. Machine-state visualisation, per-story time-travel, frame-aware fixture data — the same machinery the runtime uses for the live app, scoped to a single component.

[Spec 007 — Stories](../../spec/007-Stories.md) carries the design. You'd use this for catalogue-style visual development: drive a component through every state it can reach, snapshot the rendered tree, document the controls in the same file as the implementation.

### `re-frame-10x` v2 — interactive devtools *(in design)*

The next generation of the in-browser devtools panel. The v1 tool ([`re-frame-10x`](https://github.com/day8/re-frame-10x)) was built against re-frame v1 and ships its own epoch buffer; v2 is being rewritten as a *renderer* of re-frame2's own surfaces — a registered trace listener, a consumer of `epoch-history`, a query consumer of the registrar, a UI on top. Same panels (events, subs, renders, fxs, app-db diff, time-travel), but the substrate underneath is the framework's own, not 10x's bespoke recording machinery.

The architectural punchline is in this rewrite: **a future 10x v2 and `re-frame-pair2` will coexist as parallel listeners on the same trace bus**, neither depending on the other. The runtime's contract is the integration point.

### Machine visualisers *(in design)*

Per-machine visualisation tools — a SCXML-style state-diagram panel, a "what guards/actions fire when?" trace viewer, an interactive transition driver — sit naturally on the source-coord index that `reg-machine` stamps at expansion time. Click a guard on the diagram, jump to its definition. Click a transition arrow, jump to the line that registered it. The framework commits to the index shape; the visualiser ships its own UI affordance over the index. Multiple visualisers can coexist, all consuming the same `(rf/machine-meta machine-id)` query.

Where these go is open. The substrate is in place today.

## The architectural punchline: one observation surface

The thing to internalise: **every tool above consumes the same data**.

Trace stream. Epoch records. Registrar queries. Source-coord indices. Static topology. That's the surface. There is no privileged tool — `re-frame-10x` v2 and `re-frame-pair2` register as peer listeners on the trace bus, each with its own id, each filtering the stream as it likes. Your in-app debug panel attaches the same way, in the same call, with the same shape of data arriving. A future tool nobody's built yet — a story-tool panel, an AI-driven test-generator, a conformance recorder for fixture capture — does too.

This is what *first-class tooling* means in re-frame2: not "we shipped a devtools panel," but "the runtime is built around one observation surface and any tool can attach to it." The framework commits to **stable data shapes and query APIs**; tools own **presentation and orchestration**. Multiple tools coexist on the same contract without coordinating with each other or with the framework.

The integration is *deep*, not bolt-on. The trace events aren't a sidecar log file — they're emitted inline from the pipeline that the runtime is already walking. The epoch records aren't a recording made by a plugin — they're the same records the runtime uses internally to drive `restore-epoch`. There's no second substrate, no shadow runtime, no "make sure devtools is installed first." When the framework knows something happened, the trace bus knows. When the trace bus knows, every attached tool knows.

## Attaching a tool, concretely

Here's a working in-app debug panel that listens to the trace bus, tracks the last ten events, and renders the last five epochs with a restore button on each.

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

That's the whole shape. The trace listener is a function. The epoch list is a query. The restore is a single call. No framework extension, no plugin contract. The panel composes from the same surfaces `re-frame-pair2` and (a future) `re-frame-10x` v2 consume — the difference is that they're richer renderers of the same data.

A few things to notice:

- The id `:my-app/debug-panel` is the listener's handle; pass it to `remove-trace-cb!` to detach. Tools coexist on the bus by giving themselves a unique namespaced id.
- The filter `(= :event (:op-type ev))` keeps this listener cheap. Trace events have a load-bearing `:op-type` field that's the universal discriminator — `:event`, `:sub/run`, `:fx`, `:flow`, `:machine`, `:rf.epoch/restored`, and more. New op-types are additive; tools that don't recognise an op-type ignore it without breaking ([Spec 009 §Trace event](../../spec/009-Instrumentation.md)).
- `(rf/epoch-history :rf/default)` returns the frame's ring buffer, oldest-first. The default depth is 50; configure with `(rf/configure :epoch-history {:depth N})`.
- `restore-epoch` rewinds the frame's `app-db` to the named epoch's `:db-after`. **Effects already fired** (HTTP sent, navigation pushed) are not reversed — restore is a state operation, not a universe operation. Surface that caveat in your UI.

All of this is **dev-only**. The trace bus is gated on `re-frame.interop/debug-enabled?` (the CLJS mirror of `goog.DEBUG`); production builds elide every emit via Closure DCE. A shipped binary carries no trace bytes and no listener machinery.

## Click-to-source: the source-coord story

The second piece of the tooling pitch is **source-coord stamping**. A tool gestures at a button on screen — a click in `re-frame-10x` v2, a `dom/source-at` call from `re-frame-pair2`, a Playwright locator in an end-to-end test — and asks "where in the code did this come from?" The answer is on the DOM node itself.

```html
<button data-rf2-source-coord="counter.core:counter:48:5" ...>+</button>
```

Four colon-separated segments: `<ns>:<sym>:<line>:<col>`. Public, parseable, forward-compatible. Tools split on the colon and recover the four pieces directly. To get the file path too, follow the parsed handler-id back to the registration metadata:

```clojure
(:rf/source-coord-meta (rf/handler-meta :view :counter.core/counter))
;; → {:ns "counter.core", :file "counter/core.cljs", :line 48, :column 5}
```

The same idea generalises to state machines. `reg-machine` is a macro that walks its literal spec form at expansion time and attaches a flat coord index under `:rf.machine/source-coords`, keyed by spec-path tuples:

```clojure
(:rf.machine/source-coords (rf/machine-meta :auth/login))
;; {[:guards :form-valid?]                {:ns ... :line ... :column ... :file ...}
;;  [:actions :commit]                    {...}
;;  [:states :form :on :submit]           {...}}
```

A pair-tool or a state-diagram visualiser reads this index for two distinct gestures: **jump to definition** (a click on `:form-valid?` in the diagram reads `[:guards :form-valid?]`) and **jump to call site** (a click on a transition arrow reads `[:states :form :on :submit]`).

The framework commits to the attribute format and the index shape — both are parseable public contracts. The framework does **not** ship `dom-source-at` / `find-by-src` / `fire-click-at-src` helpers; those depend on host-specific DOM access that re-frame2 the framework doesn't assume. Pair tools and devtools panels ship their own helpers over the attribute. The attribute itself is forward-compatible; the helpers can evolve.

Like the trace bus, source-coord stamping is **dev-only**. Production builds elide via DCE; the rendered HTML in a production bundle carries no `data-rf2-source-coord` bytes.

## Scrubbing time

`epoch-history` plus `restore-epoch` is the time-travel surface. Per frame, the runtime keeps a ring buffer of the last N epoch records. Each record carries `:db-before`, `:db-after`, the event that triggered it, and structured projections of the cascade (sub-runs, renders, effects). A tool can:

- **Walk backwards through history.** Render a timeline. Show what each event changed.
- **Restore to any prior epoch.** One call: `(rf/restore-epoch frame-id epoch-id)`. The runtime rewinds the frame's `app-db` to the recorded `:db-after`, emits a `:rf.epoch/restored` trace event, and the views re-render naturally.
- **Inject a synthetic state.** Sometimes the state you want never existed: a bug repro arrives as serialised `app-db`, a hot-swapped handler needs an evolved shape, a story-tool wants to render the cart in a specific snapshot. `(rf/reset-frame-db! frame-id new-db)` is the supported write surface — it bypasses dispatch, replaces the container, and records a synthetic epoch so `restore-epoch` can rewind past the injection.

Here's the canonical pair-tool gesture — "rewind to before that event" — in fifteen lines:

```clojure
(defn epoch-before-event
  "Return the epoch-id of the most recent epoch in `frame-id`'s history
   that precedes `target-event-id`, or nil."
  [frame-id target-event-id]
  (let [history (rf/epoch-history frame-id)
        before  (take-while #(not= target-event-id (:event-id %)) history)]
    (when (seq before)
      (:epoch-id (last before)))))

(when-let [target (epoch-before-event :app/main :checkout/submit)]
  (rf/restore-epoch :app/main target))
```

That same gesture — under different UI — is what `re-frame-pair2`'s "rewind past this event" action does, what `re-frame-10x` v2's timeline scrub will do, what a story-tool's "back to the previous frame state" affordance will do. One surface, many tools.

The time-travel surface ships in `day8/re-frame-2-epoch`. Apps that want time-travel add it alongside core; apps that don't, omit it and the read-shaped surfaces (`epoch-history`, `register-epoch-cb`) degrade silently to empty / no-op. Mutating surfaces (`reset-frame-db!`, `restore-epoch`) raise structurally when the artefact is missing — a silent no-op on a mutation would lie.

## Performance: the prod-friendly channel

The trace bus is dev-only, but there's a second observation channel that's safe to enable in production: **User Timing API entries**, stable-named under the `rf:` prefix.

```
rf:event:<event-id>
rf:sub:<sub-id>
rf:fx:<fx-id>
rf:render:<view-id>
```

Any consumer with a `PerformanceObserver` reads them — no re-frame2 API call needed, just the browser primitive:

```javascript
new PerformanceObserver((list) => {
  for (const e of list.getEntriesByType('measure')) {
    if (e.name.startsWith('rf:')) {
      sendToAPM(e);
    }
  }
}).observe({ type: 'measure', buffered: true });
```

The channel is gated on `re-frame.performance/enabled?` — a `goog-define` boolean defaulting to `false`. Flip it on in your build when you want APM-style production telemetry; leave it off (the default) and DCE elides every bracket. Timing instrumentation has measurable cost on heavy hot paths, so this is opt-in for prod by design.

```edn
;; consumer's shadow-cljs.edn
{:builds {:app {:target           :browser
                :compiler-options {:closure-defines {re-frame.performance/enabled? true}}}}}
```

The Performance API surface is CLJS-only — JVM artefacts (SSR, headless tests) emit no perf entries; tools running there use the host's profilers.

## What re-frame2 does not ship

The contract above is sized to the framework's responsibility. Things that are **not** part of re-frame2 itself:

- **The Claude integration in `re-frame-pair2`** — prompts, retrieval, model selection. Lives in the skill.
- **The nREPL middleware** that bridges agent-to-runtime. Specific to the host stack.
- **The 10x v2 devtools UI.** It will be a separate artefact, consuming the same trace bus you've already met.
- **DOM-to-source helpers** (`source-at`, `find-by-src`, `fire-click-at-src`). Tool-side; depends on host-specific DOM access.
- **Skill-shaped retrospective analysis** of pair sessions — `re-frame-pair-improver2` is a separate Claude skill that reviews sessions and proposes improvements to the pair tool itself.

The split is deliberate: the framework commits to **stable data shapes and query APIs**; tools own **presentation, orchestration, and host integration**. Without that split, the framework would own every consumer's roadmap — and would lock out every consumer it didn't already know about.

---

## Reference

The rest of this chapter is the contract: surface shapes, error tables, behaviour against destroyed frames, the failure-mode enumeration for `restore-epoch` and `reset-frame-db!`. Read top-down if you're implementing a tool. Skip past if you're not — the pitch above is the part you need to internalise.

### Reference: the trace stream

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

### Reference: the retain-N trace ring buffer

For tools that want to read **recent history** without having been registered earlier, the runtime keeps a configurable ring buffer of the last N trace events:

```clojure
(rf/trace-buffer)                        ;; → vector of recent trace events, oldest-first
(rf/trace-buffer {:op-type :event})      ;; with optional filter map
(rf/configure :trace-buffer {:depth 1000})
```

Default depth is per-implementation. The buffer is dev-only and elides under production build flags.

### Reference: per-cascade epoch records

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

### Reference: epoch history failure modes

Six failure modes are enumerated and named for `restore-epoch`, each emitting a structured error trace event under the reserved `:rf.epoch/*` namespace:

| Failure | When |
|---|---|
| **Unknown frame** | The frame-id doesn't name a registered frame. |
| **Unknown epoch** | The epoch-id isn't in the frame's current history (aged out, or never recorded). |
| **Schema mismatch** | The recorded `:db-after` doesn't validate against the currently-registered schemas (someone added a stricter schema since the snapshot). |
| **Missing handler** | The recorded `app-db` references a registered id (a machine, a route) that's no longer in the registrar. |
| **Version mismatch** | The frame's recorded `:rf/snapshot-version` is incompatible with the currently-loaded machine definition. |
| **Concurrent-drain rejection** | Called while the frame's drain is still in flight; retry after settle. |

`restore-epoch` returns `true` on success, `false` on any failure. Pair tools display the `:operation` and `:tags` to the user and route from the failure to a remediation. The full table with `:tags` schemas is in [Tool-Pair §Time-travel](../../spec/Tool-Pair.md#time-travel-epoch-snapshots-and-undo).

Listening for restore outcomes:

```clojure
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
```

### Reference: `reset-frame-db!` failure modes and synthetic epoch

`reset-frame-db!` (rf2-zq55, shipped in PR #170) bypasses the dispatch loop, replaces the frame's `app-db` container directly, and **records a synthetic epoch** so `restore-epoch` can rewind past the injection. The synthetic epoch carries:

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

`reset-frame-db!` is **not** a substitute for `dispatch`. Use it only when bypass-the-cascade is required (pair-tool agent injection, story-tool snapshot rendering, bug repro loading, hot-swap reshape); for anything you want the data loop to see, dispatch a real event.

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

### Reference: the `data-rf2-source-coord` attribute format

Every `reg-view`-rendered DOM element receives:

```html
<button data-rf2-source-coord="counter.core:counter:48:5" ...>+</button>
```

The four colon-separated segments are `<ns>:<sym>:<line>:<col>`:

- `<ns>` — the registration's namespace (`counter.core`)
- `<sym>` — the **registered handler-id** (the symbol passed to `reg-view`, here `counter`). Not a file path.
- `<line>` — source line at `reg-view` macro-expansion time
- `<col>` — source column

The format is a **public, parseable contract** (rf2-q7r0, per [Spec-Schemas §`:rf/source-coord-attr`](../../spec/Spec-Schemas.md#rfsource-coord-attr)). Tools split on the colon and recover the four pieces directly.

To recover the file path too, follow the parsed handler-id back to the registration metadata via `:rf/source-coord-meta` (per [Spec-Schemas](../../spec/Spec-Schemas.md)), which carries all four keys including `:file`. The DOM attribute is the cheap-on-the-wire form; the registration metadata is the rich form.

The annotation is **dev-only** — gated on the universal `re-frame.interop/debug-enabled?`. Production builds elide via DCE; the rendered HTML in production carries no `data-rf2-source-coord` bytes.

Documented exemption: components whose outermost return is a React Fragment, a `:>` host-component head, or another non-DOM root are exempt. Pair tools fall back to `(rf/handler-meta :view id)` for those nodes.

### Reference: machine source-coord index

The framework commits to **the index shape and the keyword-reference rule** for `:rf.machine/source-coords`:

- **Definition-site stamping for keyword references.** A keyword reference (`{:guard :form-valid?}`) is stamped at its definition site (`[:guards :form-valid?]`), not at the call site — the call site is the keyword itself, which is identity-free.
- **Reference-site stamping for inline-fn literals.** An inline `(fn [...] ...)` is stamped where it appears.

Pair tools use this index for two distinct gestures (jump-to-definition, jump-to-call-site); the keyword-reference rule means call-site clicks on a keyword-named slot (`{:guard :form-valid?}`) fall back to the enclosing transition's coord, which IS stamped. Like `data-rf2-source-coord`, the stamping is gated on debug-enabled and elides under production build flags.

### Reference: the static sub-graph — `sub-topology`

Subscriptions chain — `:count-doubled` depends on `:count`. The framework knows the dependency graph at registration time (built from `:<-` declarations). For visualisation, dependency analysis, and "what depends on this sub?" navigation, the static graph is exposed:

```clojure
(rf/sub-topology)
;; → a static dependency graph
;;   {:nodes #{:count :count-doubled :auth/state ...}
;;    :edges #{[:count-doubled :count] ...}}
```

This is **static** — no runtime, no live cache, no Reagent. It reads off the registry. Use it to render a graph of "everything derived," find the leaves (subs nothing else depends on), find the roots (subs that read `app-db` directly), and spot dead subs (registered but no consumers). The shape lives in [Spec 006 §Subscription topology](../../spec/006-ReactiveSubstrate.md); the function shipped in PR #142 (rf2-8nzo).

### Reference: behaviour against destroyed frames

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

## Next

- [12 — Routing](12-routing.md) — the URL ↔ state contract.
- [Tool-Pair](../../spec/Tool-Pair.md) — the full normative contract for pair-shaped tools.
- [Spec 009 — Instrumentation](../../spec/009-Instrumentation.md) — the trace stream's full shape and the error contract.
- [`re-frame-pair2`](https://github.com/day8/re-frame2/tree/main/skills/re-frame-pair2) — the AI pair-programming skill, today.
