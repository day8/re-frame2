# reagent-slim — design rationale

> Bead **rf2-kez3**. Audience: adopters evaluating `day8/reagent-slim` against `day8/reagent-classic`.
>
> Sister docs:
> - `IMPL-SPEC.md` (this directory) — Stage 3 engineering spec, written for implementers.
> - `findings/re-frame-2-reagent-stage1-api-surface.md` — Stage 1 surface analysis, eight RESOLVED decisions.
> - `findings/reagent-slim-stage2-efficiency.md` — Stage 2 bundle and runtime estimates.
> - `findings/recom-react19-readiness-audit.md` (rf2-cgcv) — re-com + 10x lifecycle inventory.
> - `findings/dash8-rf8-react19-readiness-audit.md` (rf2-kfpf) — Dash8 + rf8 lifecycle and SSR inventory.
> - `FORM-3.md` (rf2-pe4u, future) — Form-3 cap specifics with worked examples.

This document explains, decision by decision, **why** reagent-slim is shaped the way it is. If you want to know **how** it was built, read `IMPL-SPEC.md`. If you want to know what changed in your code, read `MIGRATION.md`. If you want to know whether to adopt it, read this.

---

## §1 Why this exists

Stock Reagent has accumulated about a decade of API surface. Some of it earned its place; some of it predates patterns that Reagent itself now recommends against; some of it covers React APIs that React no longer ships. Re-frame2's ecosystem — re-com, re-frame-10x, Dash8, rf8 — uses a small, specific subset. The legacy bits are bundle cost we don't pay for.

reagent-slim is a Reagent rewrite tuned to that subset. It is not a generic Reagent alternative. It is the Reagent that re-frame2's ecosystem actually needs, sized for that ecosystem, integrated with that ecosystem.

Three drivers shape every decision in this document:

1. **Slim** — measurable bundle-size reduction, validated against the surface re-com and re-frame2 actually use. Stage 2 puts the headline at ~25-33% smaller for typical re-com apps, ~70% for SSR-using apps. The savings are guaranteed by absence: we do not ship the namespaces, so the bytes do not enter the build.

2. **React-19-native** — modern React semantics throughout. No legacy mount paths. No `findDOMNode`. No string refs. No legacy context. The rewrite emits React-19-clean elements by default, schedules through microtasks, integrates with `react/act` for tests.

3. **Re-frame2-fit** — the surface is bounded by re-com plus re-frame2's own internals. Native trace-bus integration replaces 10x v1's monkey-patches. Source-coord stamping is native to the renderer. Frame-context wiring uses the modern React context API. Defensive workarounds in re-frame2's own code retire because the bug classes they defend against are structurally removed.

Every section below frames its decision against these three drivers and ends with a migration note for adopters.

The third driver matters most. reagent-slim is not "stock Reagent minus what we feel like dropping." It is a Reagent rewrite that actually knows about re-frame2. Stock Reagent does not — it cannot — because re-frame2 did not exist when most of stock Reagent's surface was designed.

---

## §2 The re-com-scoping directive

This is the biggest single design decision. Surface area equals what re-com uses, plus what re-frame2 itself uses, plus a small audit-driven extension for Dash8 and rf8.

### Decision

The reagent-slim API surface is rigorously bounded. A symbol ships if and only if a real consumer in the audited surface uses it. Surfaces with zero consumers do not ship — not even as warning stubs.

### Why

Two empirical audits (rf2-cgcv: re-com + re-frame-10x; rf2-kfpf: Dash8 + rf8) inventoried four production codebases and the symbols they actually call. The list of zero-usage surfaces is striking:

`with-let`, `cursor`, `track`, `track!`, `wrap`, `rswap!`, `partial`, `merge-props`, `unsafe-html`, `adapt-react-class`, `reactify-component`, `create-element`, `after-render` (user-facing), `next-tick`, `flush`, `force-update`, `dom-node`, `class-names`, `is-client`, `reagent.ratom/run!`.

None of those appear in any of the four audited codebases. They also do not appear in re-frame2's own source or in any of the seventeen re-frame2 example apps.

The traditional argument for shipping zero-usage surfaces is "back-compat goodwill" — keep the symbol, emit a deprecation warning, give users a window to migrate. That argument carries weight when there is no alternative. It does not carry weight when `reagent-classic` exists. Stock-Reagent users who genuinely need `with-let` or `cursor` keep their full surface by depending on `day8/reagent-classic` instead. The two artefacts coexist on the classpath because they live in separate namespace trees.

### Slim impact

Massive. Every dropped surface drops source code, transitively-required modules, and Closure-DCE blockers. Aggregate Stage 2 estimate: ~7-10 KB gzipped saved on a typical re-com app, ~22-27 KB on an SSR-using app. The slim claim is not a marketing position — it is an arithmetic consequence of the directive.

### Re-frame2-fit impact

Several of the dropped surfaces have direct re-frame2 idiomatic replacements that are usually better. `cursor` collapses into a layer-2 sub. `with-let`'s setup-and-teardown maps onto `:on-create`/`:on-destroy` events on a registered view. `track` is what subs are. `next-tick` overlaps re-frame2's own scheduling primitives. Apps using these surfaces are not stranded — they migrate to re-frame2 idiom and usually end up with cleaner code.

### Migration note

Apps using any dropped surface have two paths. (a) Migrate the call site to re-frame2 idiom. (b) Stay on `day8/reagent-classic`. Both are first-class. Path (a) is the long-term direction; path (b) is a pragmatic option for stock-Reagent codebases not ready to refactor.

---

## §3 React 19 floor

### Decision

reagent-slim targets React 19 as its floor. The legacy `reagent.dom` namespace does not ship. Surfaces that depended on React-17-era APIs are absent or throw on call.

### Why

React 19 removed `ReactDOM.render`, `ReactDOM.hydrate`, `ReactDOM.unmountComponentAtNode`, and `findDOMNode`. It removed string refs and legacy context. These are not deprecations — the functions are gone. Any Reagent surface that depended on them cannot be back-compat shimmed at any cost. A shim would not function.

The rewrite does not pretend otherwise. `reagent.dom/render` and `reagent.dom/unmount-component-at-node` are not shipped. `reagent.core/render` (which forwarded to `reagent.dom/render`) is not shipped. `reagent.core/dom-node` (which proxied `findDOMNode`) is not shipped.

What the rewrite does ship is a thin `reagent2.dom` namespace whose only purpose is throwing on first call with a migration message. Static-analysis-friendly callers delete the import and never trigger the throw. The throw exists for runtime callers who need a clear signal: *this API is gone, here is what replaces it.*

### Slim impact

The legacy `reagent.dom` namespace was ~62 LoC plus its `react-dom`-legacy import. The throw-on-call shim is ~10 LoC. Stage 2 estimate: ~0.7-1 KB gzipped saved. Modest in isolation, structural in aggregate — committing to React 19 is what unlocks the larger simplifications elsewhere (no `requestAnimationFrame` fallback dance, no mount-order render sort, no React-17-vs-18 path branching).

### Re-frame2-fit impact

re-frame2 was already React-18-or-better in practice — every example mounts via `reagent.dom.client/create-root`. The floor commitment formalises what the example suite was already doing. It also lets the rewrite emit React-19-clean elements without paying for back-compat with element shapes React 17 expected.

### Migration note

Adopting reagent-slim means bumping `react` and `react-dom` to 19.x in your `package.json`. Replace `reagent.dom/render` calls with `reagent2.dom.client/{create-root, render}`. Replace `reagent.dom/unmount-component-at-node` with `reagent2.dom.client/unmount`. If you have a `r/dom-node` call, it must move to a `:ref` callback — `findDOMNode` is gone. Stock-Reagent users on React 17 or 18 stay on `reagent-classic`.

---

## §4 The 7-key Form-3 cap

### Decision

`reagent2.core/create-class` accepts exactly seven lifecycle keys:

```
:component-did-mount
:component-will-unmount
:component-did-update
:reagent-render
:display-name
:get-snapshot-before-update
:component-did-catch
```

Any other key throws `:rf.error/create-class-key-unsupported` with the offending key in `ex-data`.

### Why

The cap is the empirical key universe across the four audited codebases. Two pre-flight audits established this:

- re-com (10 `create-class` sites) uses five keys: `:display-name`, `:component-did-mount`, `:component-did-update`, `:component-will-unmount`, `:reagent-render`.
- re-frame-10x (3 userland sites) adds `:get-snapshot-before-update` (one site, the `code` component's scroll-restoration path).
- Dash8 + rf8 (13 sites combined) add `:component-did-catch` (logging-only error boundaries via the shared `reagent-error-boundary` component).

Across all four codebases combined: zero UNSAFE_ keys, zero `:component-will-receive-props`, zero `:should-component-update`, zero `:get-initial-state`, zero `:component-will-mount`, zero `:component-will-update`, zero `:get-derived-state-from-props`. Every legacy lifecycle hook React 16.3 deprecated is unused.

The cap is what those audits actually found, plus zero — not an aspirational limit, not a stylistic preference, just what the ecosystem uses.

The throw is loud on purpose. A user who registers `:should-component-update` is not getting a silent shrug; they get an `ex-info` at first call with the unsupported key and the supported set. Migration is mechanical: rewrite into the supported keys, or use `reagent-classic` for that component.

### Slim impact

`reagent.impl.component`'s `custom-wrapper` handles every React lifecycle key Reagent has ever supported, plus several variants. Each lifecycle wrapper carries its own props-camelify shim and method-name conversion path — roughly five to six lines per key. Dropping the unused keys removes ~35-40 LoC. Stage 2 estimate: ~1-1.5 KB gzipped saved across the lifecycle dispatch table.

### Re-frame2-fit impact

This is the part of the decision that needs the most direct framing. The cap is not "we are deciding what re-frame2 users are allowed to do." It is "we are looking at what re-frame2 users actually do, and that is the cap." A future codebase that needs a banned key files a bead and the cap extends — the discipline is empirical, not ideological.

The cap also lets `create-class`'s validation be simple. The supported set is small and static; the throw can name every supported key in its message. Adopters get a clear, citable contract for what Form-3 means in the rewrite.

### Migration note

For the four audited codebases: zero changes. Their existing `create-class` calls work as-is. For codebases outside the audit using a banned key: rewrite the lifecycle into the supported set, or stay on `reagent-classic`. The throw fires at first invocation with a clear message — no silent breakage.

The companion document `FORM-3.md` (rf2-pe4u, future) covers Form-3 worked examples in detail.

---

## §5 The narrowed `convert-prop-value`

### Decision

`convert-prop-value` stringifies named values (keywords, symbols) only when they appear under documented HTML-attribute prop names: `:class`, `:id`, `:role`, `:data-*`, `:aria-*`. Keywords passed as values to any other prop name pass through unchanged.

In dev mode, a one-shot `console.warn` fires when a keyword value reaches a non-listed prop name — the safety-net for incidental users who relied on the silent stringification.

### Why

This decision closes the rf2-d4sf bug class structurally.

In stock Reagent, `convert-prop-value` stringifies any keyword passed as a prop value, regardless of which prop it is. This was a convenience for HTML attributes — `:class :primary` becoming `class="primary"` — but it bit React-context Provider values: when a Provider received `:value :some-keyword`, Reagent rewrote it to `"some-keyword"` and the React context lost its identity. re-frame2's `re-frame.adapter.context/coerce-context-value` defensively un-stringified the value at the consumer site. The defensive workaround papered over the symptom; the root cause sat upstream in Reagent.

The narrowed shape removes the symptom by removing the cause. `:value` is not in the HTML-attribute set, so a keyword passed as `:value` survives. The Provider receives the keyword. The defensive coerce becomes vestigial — and Stage 4 deletes it.

The audit cross-check confirmed the breakage surface is bounded: across re-com, re-frame-10x, Dash8, rf8, none of the four codebases visibly relies on stringification of non-HTML props. The dev-mode warning catches any incidental users whose code we did not audit.

### Slim impact

Modest at the byte level — the implementation is roughly the same size, with the universal "stringify any keyword" branch traded for a small set-membership check. The runtime cost drops noticeably: per-prop work falls from ~30 ns (named?-branch + name-call-or-passthrough) to ~10 ns (set-membership-or-early-return). Per-render savings are real but small in absolute terms.

### Re-frame2-fit impact

Largest single correctness win in the rewrite. The rf2-d4sf defensive workaround retires entirely. The bug class — keyword-as-context-Provider-value silently stringified — cannot recur because the path that would do it no longer fires for that prop name. re-frame2's adapter layer becomes ~30 LoC simpler.

### Migration note

If your code today writes `[:div {:class :primary}]` or `[:span {:id :greeting}]`, behaviour is identical — those prop names are in the HTML-attribute set. If your code writes a keyword value to a non-HTML prop name and depended on Reagent stringifying it, the dev-mode warning will fire and you will see a `console.warn` in the browser. Fix the call site (usually by using `name` explicitly).

The four audited codebases trigger zero such warnings. Most apps will trigger zero. The warning exists for the case we did not audit.

---

## §6 The SSR split

### Decision

`render-to-string` is **not** shipped by reagent-slim. It is owned by `day8/re-frame-2-ssr`, the canonical re-frame2 SSR seam.

`render-to-static-markup` **is** shipped, under `reagent2.dom.server`. It is implemented as a pure-CLJS hiccup-to-HTML serializer, ~150-200 LoC, with no `react-dom/server` dependency.

### Why

The two surfaces serve different purposes and the rewrite separates them.

`render-to-string` is hydrate-able SSR — it emits React-id attributes so a client-side `hydrate-root` can adopt the markup. re-frame2 already has `day8/re-frame-2-ssr` for this; the seam is wired through the adapter's late-bind hook (`:reagent/set-hiccup-emitter!`). Shipping a duplicate `render-to-string` in reagent-slim would compete with the seam, force users to choose, and pull in `react-dom/server` (a ~50 KB module) for a path the seam already handles.

`render-to-static-markup` is offline HTML export — pure markup, no React-id attributes, no hydration intent. The audit (rf2-kfpf) found this in production use at Dash8 and rf8: clipboard exports, HTML report exports, both call `render-to-static-markup` to turn a hiccup tree into a static HTML string for distribution outside the React lifecycle. There is no seam for this in re-frame2 because there does not need to be — the operation is a tree walk, not a React feature.

A pure-CLJS serializer covers `render-to-static-markup` without any React DOM dependency. The same prop-conversion rules from the renderer apply (so the narrowed `convert-prop-value` is reused). It is a hiccup walker emitting attribute-stringified HTML.

### Slim impact

Largest single bundle win in absolute terms — for apps that import the namespace. `render-to-string` in stock Reagent pulls in `react-dom/server`, a ~50 KB module. The rewrite's pure-CLJS serializer is ~3-4 KB. Apps that import `reagent2.dom.server` for HTML export save ~15-18 KB gzipped (~50 KB minified). Apps that do not import it pay zero in either case (Closure DCE).

For the small audience that does import it — Dash8, rf8, anyone with similar HTML export needs — the savings are dramatic. For everyone else, neutral.

### Re-frame2-fit impact

The split aligns with re-frame2's separation of concerns. SSR-with-hydration is a substrate operation owned by `day8/re-frame-2-ssr`, which knows how to coordinate the server's frame state with the client's mount. Static HTML export is a hiccup operation that does not need the substrate at all. Both responsibilities land in the right artefact.

### Migration note

If your app imports `reagent.dom.server/render-to-string` for hydrate-able SSR: migrate to the `day8/re-frame-2-ssr` seam. The seam's API is documented in the SSR adapter's spec.

If your app imports `reagent.dom.server/render-to-static-markup` for HTML export: change the require to `reagent2.dom.server` and the rest works. Behavioural parity with React's `renderToStaticMarkup` is covered by a parity test suite (R-004 in Stage 2's risk register) — any intentional differences (attribute ordering, void-tag handling) are documented in `MIGRATION.md`.

---

## §7 The namespace tree

### Decision

The Maven coord `day8/reagent-slim` is decoupled from import paths. Adopters require `reagent2.*` namespaces:

| Concern | Namespace |
|---|---|
| Adapter Var (`(rf/init! ...)` consumes) | `re-frame.adapter.reagent` (UNCHANGED) |
| User-facing compat surface | `reagent2.core` |
| Reactive primitives | `reagent2.ratom` |
| React 19 mount entry | `reagent2.dom.client` |
| Pure-CLJS static markup | `reagent2.dom.server` |
| Throw-on-call shim for legacy mount path | `reagent2.dom` |

### Why

The decoupling is intentional. Three reasons:

1. **No collision with stock Reagent.** `reagent2.*` and `reagent.*` are separate namespace trees. They coexist on the classpath without interfering. An app can depend on both `reagent-slim` and `reagent-classic` simultaneously if migration is incremental.

2. **No marketing claim baked into the import path.** The `day8/reagent-slim` Maven coord carries the brand. The `reagent2.*` import path is neutral — it does not promise anything in the source code. If a future rewrite supersedes reagent-slim, the import path stays valid.

3. **Type-friendly.** `reagent2.core` is shorter than `reagent-slim.core` in `(:require ...)` lines. The `2` is a version marker, not a brand statement.

The adapter Var stays at `re-frame.adapter.reagent`. That is the public surface `(rf/init! ...)` consumes. Apps that already wired their init call against the bridge keep working without source changes when they swap from `reagent-classic` to `reagent-slim`. The adapter Var path is the ABI; the namespace tree underneath is the implementation.

### Slim impact

Indirect but real. A clean namespace split lets Closure DCE prove what is reachable. There are no shadowed namespaces, no surprising classpath shadowing, no fallthrough imports. Each `reagent2.*` namespace is a self-contained unit; users pay for what they import.

### Re-frame2-fit impact

The adapter Var's stability is what makes the swap palatable. `(rf/init! reagent/adapter)` continues to work across the bridge → rewrite swap because the adapter Var is the contract. Apps already on re-frame2 v2 pre-release stay on the same init line.

### Migration note

Find-and-replace your requires:

```clojure
;; before
(:require [reagent.core :as r]
          [reagent.dom.client :as rdc])

;; after
(:require [reagent2.core :as r]
          [reagent2.dom.client :as rdc])
```

Idiomatic aliases are `r` for `reagent2.core`, `rdc` for `reagent2.dom.client`, mirroring stock-Reagent convention. The adapter init line — `(rf/init! reagent/adapter)` — does not change.

---

## §8 No Class A warning stubs

### Decision

Surfaces with zero ecosystem usage simply do not ship. Not as warning stubs. Not as deprecation shims. They are absent. Calls fail at compile time.

### Why

This decision splits the dropped surfaces into two classes by why they were dropped:

**Class A — pure-CLJS deprecates with no React-internal dependency.** `cursor`, `wrap`, `next-tick`, `class-names`, `is-client`, `IRunnable`. These could have shipped as warning stubs that emit a `console.warn` and forward to a re-frame2-idiom replacement. The audits found zero usage across re-com, re-frame-10x, Dash8, rf8.

**Class B — React-internal removes.** `reagent.core/render`, `reagent.dom/render`, `reagent.dom/unmount-component-at-node`, `reagent.dom/force-update-all`, `r/dom-node`. These cannot warn-and-continue because the React APIs they depended on are gone in React 19. They ship as throw-on-call shims (per §3).

For Class A, the question was whether to ship warning stubs or nothing. The "back-compat goodwill" argument for warning stubs assumes there are users to be courteous to. The audits established there are no such users. And `reagent-classic` exists for any genuine stock-Reagent user.

So Class A surfaces ship as nothing. A user calling `reagent2.core/cursor` fails at compile time with an unresolved-symbol error, not at runtime with a deprecation warning. This is the right pedagogy: the surface does not exist, the compiler says so, the user updates the call site.

### Slim impact

Cumulative. Each Class A surface drops its source code, its transitively-required modules, and its DCE-reachable footprint. Stage 2 estimate: ~1-1.5 KB gzipped saved across the Class A surfaces — modest individually, structural in aggregate when combined with the other slimming directives.

### Re-frame2-fit impact

The discipline is the load-bearing thing. "Warning stub" is a soft posture that defers the decision. "Does not exist" is a hard posture that commits. The audits made the commit defensible.

### Migration note

If your code calls a Class A surface, the compiler tells you. Migration to re-frame2 idiom: `cursor` → layer-2 sub. `next-tick` → re-frame2's scheduling primitives or `goog.async.nextTick` directly. `class-names` → a one-line userland helper or one of several CLJS string-join libraries. `is-client` → re-frame2's platform marker in `re-frame.interop`. If migration is not feasible, stay on `reagent-classic`.

Class B surfaces, per §3, throw on call with a migration message rather than failing at compile time — the React-API-gone framing means the throw is the right pedagogy for callers who try them at runtime.

---

## §9 Native re-frame2 integration

This section frames the third driver — re-frame2-fit — for adopters. It is the part of the rewrite stock Reagent cannot match because it requires knowledge of re-frame2's primitives.

### Source-coord stamping is native to the renderer

Stage 2 §3.6 identifies this as the biggest single dev-mode runtime win. re-frame2's adapter today wraps user views via `views.cljs:332-357` to inject a `data-rf2-source-coord` attribute onto the rendered hiccup tree. The wrapper walks the tree post-render. The walk is dev-only (gated on `goog.DEBUG`) but it costs ~5-15 µs per registered-view render. For a re-com page with ~50 registered views, that is 200-600 µs per render — 1-4% of the 16ms frame budget.

reagent-slim moves the stamping into the renderer itself — a single `assoc` on the root attrs map at the registered-view's root element. No tree walk. The win is dev-mode hot-reload feedback latency: tighter render-cycle visibility for the AI-companion's render-cascade work (re-frame2 Goal 12). In production the wrapper layer DCEs out anyway, so the production cost is unchanged. The win is where developers feel it.

### Trace-bus integration replaces 10x v1's monkey-patches

re-frame-10x v1 monkey-patches `reagent.impl.batching/{next-tick, render-queue, mark-rendered, queue-render}` and `reagent.impl.component/{wrap-funs, custom-wrapper}` to capture per-render-frame trace data and stamp component IDs. The patches are documented in 10x v1's preload comments. They are also fragile: any change to Reagent's batching internals risks breaking 10x.

reagent-slim emits the trace surface natively. The renderer publishes `:view/render` events to re-frame2's trace bus directly, with the `:render-key` and component identity 10x v2 needs. No monkey-patches. 10x v2's preload reads the trace bus; it does not patch the renderer.

This is one of the surfaces stock Reagent cannot deliver because it requires the renderer to know about re-frame2's trace bus. The bridge artefact does its best with a wrapper layer; the rewrite does it natively.

### Frame-context primitive integrates with the component lifecycle

re-frame2's frame-context — the per-component frame the view renders against — is wired through the modern `React.createContext` API. The rewrite's renderer threads frame-context through the React tree without monkey-patching `getChildContext` or any of the legacy-context machinery React 19 removed. The `re-frame.adapter.context/coerce-context-value` defensive workaround retires (per §5) because the rewrite's narrowed `convert-prop-value` does not stringify the keyword values frame-context uses.

### `flush-views!` replaces the brittle `r/flush`

Stage 2 §3.5: Reagent's `r/flush` does not compose cleanly with React 18+ concurrent rendering. The flush drains Reagent's `next-tick` queue but does not necessarily flush React's pending work. Tests that combine `(rf/dispatch-sync ...)` with `r/flush` race against React's commit phase.

reagent-slim ships `reagent2.dom.client/flush-views!`. It owns its own scheduler — microtask + dirty-set — and integrates with `react/act` natively. A test that calls `flush-views!` after `dispatch-sync` is deterministic: all pending Reactions have recomputed, React has committed, the DOM reflects state.

The win is test reliability. Production-runtime impact is zero — `flush-views!` is a test-only API. But the test-determinism story matters for CI flake budgets, for hot-reload feedback, and for any harness that wants reproducible state assertions.

### What this adds up to for adopters

Stock Reagent cannot offer these integrations because stock Reagent does not know about re-frame2's trace bus, frame-context primitive, source-coord injector, or test-flush primitive. reagent-classic carries the same blindness — it is stock Reagent under a thin bridge.

reagent-slim is the Reagent that actually knows about re-frame2. Adopters get the integration without the monkey-patches, without the wrapper-layer overhead, without the tests-race-each-other surprise.

---

## §10 Quantified expectations

This section reproduces Stage 2's numbers honestly. Stage 2's executive summary is explicit: the "fast" claim was qualified, then dropped from the artefact name. The doc you are reading replaces it with a more accurate framing.

### Bundle

| App profile | gzipped Δ vs Reagent 1.3 | % reduction |
|---|---|---|
| Typical re-com app (no SSR) | -7 to -10 KB | 25-33% smaller |
| SSR-using app (Dash8 / rf8 HTML export) | -22 to -27 KB | ~70% smaller |

The "slim" claim is **defensible**. The savings come from (a) namespaces that do not ship, where the saving is guaranteed by absence; (b) narrowed implementations of the surfaces that do ship; and (c) compile-time decisions that replace runtime detection.

The numbers are analytical estimates, not Closure-built measurements. Stage 4's S3-008 build-comparison harness will validate them against a real `:advanced`-compiled bundle. If realised reduction is <15%, the slim framing is unsupported and Stage 4 investigates where the predicted savings went. Stage 2's risk register flags this explicitly (R-007).

### Runtime — production

Per-render savings: roughly 80 ns per element from the narrowed `convert-prop-value`, roughly 0-50 µs across the reactive-graph slim. For a 200-element re-com page rendered at 60 fps, that is ~16-30 µs per render — about 0.2-0.4% of the 16 ms frame budget. **Real but not visibly faster.**

A re-com page that took 12 ms to render in stock Reagent will take ~11.7-11.8 ms in reagent-slim. That is a real win. It is not the kind a user notices.

### Runtime — dev mode

Wrapper-layer collapse (per §9 above): ~4-12 µs per registered-view render, savings come from moving source-coord stamping into the renderer. For a re-com page with ~50 registered views, ~200-600 µs per render — 1-4% of the 16 ms frame budget. **Meaningful for hot-reload feedback latency.**

Production wrapper layers DCE out; the dev-mode savings do not show up in production. But hot-reload feedback is where the dev experience is felt.

### Runtime — test

`flush-views!` deterministic flush. Not a speedup — a reliability story. Tests that today retry on flush-flake recover their retry budget. CI flake counts drop.

### Honest framing

The "fast" claim was dropped from the artefact name on 2026-05-11 because the production-runtime story is marginal. The slim claim is defensible; the dev-DX claim is defensible; the test-determinism claim is defensible. Production speed is not the headline.

This honesty matters because the doc you are reading is for adopters making a real decision, not a marketing pitch. If you are evaluating reagent-slim for "will my app feel faster in production," the answer is "marginally, at best." If you are evaluating it for "will my bundle be smaller, my hot-reload feedback tighter, my tests more deterministic, my re-frame2 integration cleaner," the answer is "yes, measurably."

---

## §11 Side-by-side comparison

| Dimension | reagent-classic | reagent-slim |
|---|---|---|
| Maven coord | `day8/reagent-classic` | `day8/reagent-slim` |
| React floor | React 17/18 (stock-Reagent constrained) | React 19 |
| Surface | full Reagent surface (~30+ symbols) | ~20 symbols, re-com/re-frame2 scoped |
| Bundle (gzip) vs stock Reagent 1.3 | baseline | -25% to -33% (typical), -70% (SSR-using) |
| Form-3 keys | all React lifecycle keys | 7-key cap |
| `convert-prop-value` | stringifies all keywords | HTML-attribute names only |
| `r/dom-node` / `findDOMNode` | works | throws (React 19 — API gone) |
| `reagent.dom/render` | works | throws (React 19 — API gone) |
| Trace integration | requires 10x v1 monkey-patches | native to renderer |
| Source-coord stamping | post-render tree walk | native to renderer |
| `r/flush` | brittle under React 18+ concurrent | `flush-views!` deterministic |
| `render-to-string` | shipped (pulls `react-dom/server`) | not shipped (use `day8/re-frame-2-ssr`) |
| `render-to-static-markup` | shipped (pulls `react-dom/server`) | shipped (pure-CLJS, no `react-dom/server`) |
| `with-let`, `cursor`, `track`, `wrap`, ... | shipped | not shipped (compile-time error) |
| Adapter Var path | `re-frame.adapter.reagent` | `re-frame.adapter.reagent` (unchanged) |

Adoption shape:

- **Stock Reagent codebase, full surface usage, on React 17/18:** stay on `reagent-classic`. No reason to migrate.
- **Stock Reagent codebase, re-com surface only, on React 19:** migrate to `reagent-slim`. The migration is a require-rewrite plus mount-API swap; everything else is unchanged.
- **re-frame2-native codebase, on React 19:** migrate to `reagent-slim`. You get the full integration (trace bus, source-coord, frame-context, deterministic flush) plus the bundle savings.
- **Codebase using a banned surface (`with-let`, `cursor`, banned Form-3 key, etc.):** either rewrite into the supported surface, or stay on `reagent-classic`. Both are first-class.

---

## §12 What this doc is not

This doc is for adopters. It is not the implementation spec — see `IMPL-SPEC.md` for that. It is not the Form-3 worked-examples doc — see `FORM-3.md` (rf2-pe4u, future) for that. It is not a benchmark report — Stage 4's build-comparison harness produces that. And it is not a marketing pitch — the "fast" claim was dropped because the runtime story did not support it, and the doc you just read flags every claim that depends on Stage 4 validation.

Read the audits if you want the empirical underpinning. Read Stage 1 if you want the surface enumeration. Read Stage 2 if you want the size and runtime estimates. Read this doc to decide whether the rewrite fits your codebase.

---

*Cross-references: Stage 1 (`findings/re-frame-2-reagent-stage1-api-surface.md`), Stage 2 (`findings/reagent-slim-stage2-efficiency.md`), rf2-cgcv audit, rf2-kfpf audit, IMPL-SPEC.md (this directory), FORM-3.md (rf2-pe4u, future).*
