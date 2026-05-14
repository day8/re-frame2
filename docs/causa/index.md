# Causa

**The cascade you can see.**

Causa is the in-app devtools panel for re-frame2. It opens on `Ctrl+Shift+C`, mounts inside your dev build, and renders sixteen panels over a single observation surface — the framework's own trace bus and epoch buffer. No bespoke recorder, no shadow runtime, no second substrate. The runtime knows what happened; Causa is what knows knows.

Where the v1-era [`re-frame-10x`](https://github.com/day8/re-frame-10x) was a sidecar with its own recorder, Causa is a *renderer* of an already-structured surface. Same panels — events, subs, renders, fxs, app-db diff, time-travel — different substrate. The framework moved the observation contract into the runtime; Causa moved with it.

This is the welcome page. The chapters that follow are a walkthrough — install, panel tour, the hero scenarios — designed to read top-to-bottom. If you want a single sentence to take away first: **the runtime emits trace events, Causa renders them; everything else is composition.**

---

## A scenario, before the tour

You're paged at 3pm. A tester drops a screenshot on your desk: *the wrong number is showing in the cart total*. There's no reproduction recipe; the only clue is the screenshot.

The legacy debugging loop is the one you know: open browser DevTools, set a breakpoint on whatever you guess produced the number, refresh, try to reproduce, watch the call stack scroll past. Maybe you log. Maybe you `console.dir(state)`. Maybe you give up and ask the tester to re-run with the React profiler open.

The Causa loop is different. You haven't reproduced anything yet. You haven't even *opened* Causa. The tester just sent you the screenshot.

1. You ask the tester to right-click the wrong number and *copy element*. The HTML they paste back is:
   ```html
   <span data-rf2-source-coord="cart-total.core:cart-total-line:286:4">$0.00</span>
   ```
2. The `data-rf2-source-coord` attribute is on **every** rendered DOM element in dev mode. Four segments, colon-separated: `<ns>:<sym>:<line>:<col>`. You're already at the line in your editor.
3. You read the function. It subscribes to `:cart/total`. You open the running app on your machine, press `Ctrl+Shift+C`, click *Subscriptions*, type `cart/total`. The sub is recomputing on every line-item edit. Good — that's expected.
4. The sub reads `:cart/items`. You click the dependency edge. The upstream sub is reading `:checkout/items`. Wrong slot. The tester had the cart open during a checkout transition; the projection drifted.
5. You write a one-line fix, hot-reload, and Causa's Issues ribbon clears.

This is the loop the rest of the tutorial unpacks. Source coords on the wire. Sub-graph navigation in the panel. Trace bus carrying every fx and sub-run. Epoch history you can scrub. Hot-reload with the diff preserved. None of it is novel by itself; what's novel is that they're **on one substrate** and the tool just paints.

!!! tip "Run the scenario yourself"

    The five-step walk-through above is a runnable testbed at [`tools/causa/testbeds/cart_total/`](https://github.com/day8/re-frame2/tree/main/tools/causa/testbeds/cart_total). Clone the repo, run `npm run test:examples` from `implementation/`, then open `http://127.0.0.1:8030/cart-total/` and reproduce the bug in three clicks: *Checkout* → *+ Apple* → watch the total stay locked to the snapshot's value while the live cart visibly carries different items. Open Causa (`Ctrl+Shift+C`), find `:cart/total` in the Subscriptions panel, and click the dependency edge — the wrong upstream slot is one click away. [Chapter 5 (click-to-source)](05-click-to-source.md) walks the fix end-to-end on the same testbed.

The chapters:

- [1. Installation](01-installation.md) — get Causa running against your app in five minutes.
- [2. Panel tour](02-panel-tour.md) — the sixteen panels, what each is for, when you'd open it.
- [3. Time-travel scrubbing](03-time-travel.md) — walk the epoch buffer; rewind; replay.
- [4. Trace stream](04-trace-stream.md) — every fx, every sub, every render, filtered.
- [5. Click-to-source](05-click-to-source.md) — the hero feature: any DOM element back to its line.
- [6. Schema-violation timeline](06-schema-timeline.md) — Malli failures over time.
- [7. Hydration debugger](07-hydration.md) — server vs client render diff.
- [8. Machine inspector](08-machine-inspector.md) — Stately-grade state-chart per machine.
- [9. App-DB diff](09-app-db-diff.md) — slice-centric diff per epoch.
- [10. AI co-pilot rail](10-ai-copilot.md) — pull-only Q&A and slash commands.
- [11. MCP-server panel](11-mcp-server.md) — Causa as an agent surface.

---

## The architectural punchline: one observation surface

The thing to internalise before opening Causa: **the framework commits to a single observation surface, and every tool consumes it**.

Trace stream. Epoch records. Registrar queries. Source-coord indices. Static topology. That's the surface. There is no privileged tool — every consumer registers as a peer listener on the trace bus, each with its own id, each filtering the stream as it likes. Your in-app debug panel attaches the same way Causa does, in the same call, with the same shape of data arriving. A future tool nobody's built yet — a story-tool panel, an AI-driven test-generator, a conformance recorder for fixture capture — does too.

This is what *first-class tooling* means in re-frame2: not "we shipped a devtools panel," but "the runtime is built around one observation surface and any tool can attach to it." The framework commits to **stable data shapes and query APIs**; tools own **presentation and orchestration**. Multiple tools coexist on the same contract without coordinating with each other or with the framework.

The integration is *deep*, not bolt-on. The trace events aren't a sidecar log file — they're emitted inline from the pipeline that the runtime is already walking. The epoch records aren't a recording made by a plugin — they're the same records the runtime uses internally to drive `restore-epoch`. There's no second substrate, no shadow runtime, no "make sure devtools is installed first." When the framework knows something happened, the trace bus knows. When the trace bus knows, every attached tool knows.

Causa is just the most *complete* listener — sixteen panels deep, lazily mounted, AI-co-pilot-rail-equipped. Pair tools and the Story playground consume the same surfaces with different presentations. Your project's bespoke debug panel can too, in fifteen lines (we'll show it in [chapter 4](04-trace-stream.md)).

## What you get for free

A re-frame2 app, in dev mode, is **inspectable by default**. Without you writing a single instrumentation hook, the runtime produces:

- **A trace event for every meaningful runtime moment.** Dispatches, handler invocations, fx applications, sub computations, errors, machine transitions, registrations, hot-swaps — they all flow through one channel, the trace bus, as structured maps you can filter, route, or record.
- **An epoch for every drain-to-empty.** Each time the dispatch queue settles, the runtime emits a fully-shaped record with `:db-before`, `:db-after`, and structured projections of the sub-runs, renders, and effects that the cascade produced. Tools route diagnostics off these directly; you don't fold the raw stream yourself.
- **A ring buffer of the last N epochs per frame.** Scrub backwards. Restore to any prior `:db-after` with one call. Time-travel is not a feature bolted on by a devtools plugin — it's a runtime primitive.
- **Source coordinates on every registration.** Every event handler, every sub, every view, every fx knows the `{:ns :file :line :column}` of where it was registered. Every rendered DOM element carries `data-rf2-source-coord` pointing back at the view that produced it. Click anywhere on the screen, walk back to the line of code that put it there.
- **Static topology you can query.** The sub-graph is buildable from the registry without running the app. So is the registered-machine inventory. So is the fx index. Tools render dependency graphs, state-diagram visualisations, "what depends on this sub?" navigation — all off the same source-of-truth registries the runtime itself uses.

These surfaces exist for **tools** to consume — not for you to consume by hand, although you can. The framework's job is to keep the data shapes stable and well-named. The tools' job is to present them.

The same surfaces carry beyond the developer's browser too. For an off-box, production-shaped consumer — forwarding the trace bus through an interceptor and `rf/elide-wire-value` to Datadog, Honeycomb, Sentry, or any APM that takes structured events — see [Guide 22 — Trace to Datadog](../guide/22-trace-to-datadog.md). Same listener pattern; just a different endpoint at the other end.

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

For *when* to reach for this channel — and the four shapes of slowness the cures address — see the companion deep-dive [Guide 16 — Performance](../guide/16-performance.md).

## Reference: the static sub-graph

Subscriptions chain — `:count-doubled` depends on `:count`. The framework knows the dependency graph at registration time (built from `:<-` declarations). For visualisation, dependency analysis, and "what depends on this sub?" navigation, the static graph is exposed:

```clojure
(rf/sub-topology)
;; → a static dependency graph
;;   {:nodes #{:count :count-doubled :auth/state ...}
;;    :edges #{[:count-doubled :count] ...}}
```

This is **static** — no runtime, no live cache, no Reagent. It reads off the registry. Use it to render a graph of "everything derived," find the leaves (subs nothing else depends on), find the roots (subs that read `app-db` directly), and spot dead subs (registered but no consumers). Causa's Subscriptions panel uses this directly.

## What re-frame2 does not ship

The framework commits to stable data shapes and query APIs; tools own presentation, orchestration, and host integration. Outside the framework, in separate artefacts:

- Causa itself — the in-app devtools panel, this tutorial's subject.
- [`re-frame-pair2`](../skills/re-frame-pair2.md) — the AI pair-programming skill that attaches over nREPL.
- [Story](../story/index.md) — the Storybook-class component playground.
- The `story-mcp` JVM server (and the planned `causa-mcp`) packaging the surface as MCP tools for AI agents.
- APM-shipper wiring — see [Guide 22 — Trace to Datadog](../guide/22-trace-to-datadog.md).

Causa is the first of three Tool-Pair tools that share this substrate. Story sits alongside, with frame-per-variant isolation; the pair skill sits across, driving the running app through an editor's nREPL bridge. They never coordinate. They never need to.

Ready? Start at [1. Installation](01-installation.md).
