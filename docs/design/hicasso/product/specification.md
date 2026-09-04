# Hicasso: native re-frame2 adapter

## Product analysis, design review, and action specification

Hicasso is the selected native re-frame2 view adapter. Its default is interpreted Hiccup with ambient re-frame2 reads, data-first events, React-correct controlled fields, and ordinary Clojure composition. When a measured part of an application needs native React performance or semantics, that part drops through an explicit React boundary while retaining the same root, frame context, and state owner. Hicasso supplies a small optional native namespace for this purpose; UIx and raw React/JavaScript components remain supported alternative authoring routes rather than required dependencies.

This living product specification defines what Hicasso should become, how it is judged, and the work required to ship it. The companion [decision brief](decision-brief.md) owns the forensic scoreboard, the selected-direction record, the scoped amendment, the K3 disposition, and the kill rules; this document assumes that direction and turns it into a product contract.

> **Decision:** Hicasso is the selected product direction, and the measured capability price Phase 0 prepared has been explicitly ratified — by operator ruling of 2026-08-13, not by a sitting — without the registered gate being treated as passed or adapter selection silently reopened.  
> **Current state:** the implementation is a benchmark prototype, not an installable product.  
> **Authority of this document:** normative product target, proposed budgets, and action specification; the decision brief owns selected-direction and ruling-level decisions, while the evidence baseline owns adjudicated measurements. API spellings remain provisional until their named witnesses pass.

The intended outcome is not “the fastest adapter on every synthetic row.” It is the best overall re-frame2 adapter:

- the least ceremony for ordinary application code;
- correct under modern React and browser behavior;
- complete across real SPA use cases;
- unusually easy to test and diagnose;
- fast enough by declared user-visible and comparative budgets;
- able to recover direct React performance in the exceptional hot regions;
- installable, documented, supportable, and safe to adopt.

The expected native-React share may be 0%, 1%, or 2% of source in a typical application. That is an observed outcome, never a quota. A small amount of source may perform most of the work.

## 1. Product shape

Hicasso has five cooperating surfaces. Only the interpreted core is unavoidable. The React bridge and SSR/hydration contracts are core product capabilities, but their runtime cost is paid only when an application crosses or exercises them.

| Surface | Purpose | Cost rule |
|---|---|---|
| Interpreted core | Views, reads, Hiccup lowering, event intents, controlled fields, roots, SSR/hydration semantics | Small fixed boundary shell; variable cost follows actual reads and markup |
| React bridge | Declared hosts, raw elements, providers, refs, render props, outward embedding, server policies | Paid only at the crossing |
| Native hot path | Direct React output or a named native island, authored through Hicasso's optional native namespace, UIx, or raw React/JavaScript | Paid only by the selected region; absent when the namespace is unused |
| Optional libraries | Forms, overlays, presence, routing integration, deployable Node/React SSR service | Zero reachable production code when absent |
| Developer products | Testing, Xray, lint, migration, AI-readable evidence | Development-only; erased from production |

The core is not a general React wrapper language, component library, local-state framework, compiler, or renderer. React-specific work stays React-specific, application state remains in re-frame2, and the adapter owns only the semantics that remove repeated defects or ceremony. The exact native-tier semantic contract has one owner: the [native-boundary design law](lanes/design-laws.md#native-boundary).

React-library interop and SSR/hydration correctness are core product goals. Every public view, host, root, resource boundary, and native escape needs defined server and hydration behavior or a source-located refusal. “Optional SSR” refers only to the deployable Node service and its operational machinery; a client-only application does not ship that service.

## 2. What “better” means

The priorities are ordered. A lower item cannot buy a failure in a higher one.

1. **Correctness.** No stale reads, cross-frame dispatch, leaked ownership, hydration corruption, lost input, or speculative-render side effects.
2. **Ergonomics.** The ordinary screen is Hiccup, local reads, and event data. Helpers are ordinary functions. Special forms are rare and stable.
3. **Coverage.** Every motivating use case has a clean default, a supported optional module, a named React escape, or a didactic refusal with recovery.
4. **Testing and diagnosis.** Most application logic is cheap to test as data; React and browser laws use real React and real browsers; Xray explains work without pretending to know unavailable facts.
5. **Performance.** Ordinary interactions meet user-visible budgets. Comparative regressions are bounded. A local native island can recover the exceptional case.
6. **Product readiness.** A clean consumer can install, compile, hot-reload, production-build, test, diagnose, upgrade, and remove the adapter without benchmark-tree knowledge.

The current [evidence baseline](lanes/evidence-baseline.md) supports the direction but does not yet constitute a product release:

- The canonical representative cold-mount row decisively misses the registered `1.10x` K1 gate. The [pinned evidence](lanes/evidence-baseline.md#pinned-economic-evidence) owns the point estimates, intervals, sample counts, witness, and estimator context. The product implication is a priced capability decision, not a retrospective pass; much of the difference is the witness's richer read topology rather than Hiccup walking alone.
- On the proved-same dogfood screen, Hicasso expressed the same DOM and intent behavior in 47 lines versus UIx's 72, with eight event positions as data rather than eight closures and no threaded subscription values. The centralized IME law prevents a defect present in naïve direct-React handling.
- Hicasso's per-read retained cost is below its UIx parent on the measured row, but the read-free boundary shell is red against its registered paper-fail line and is a Phase 1 remediation gate; the three K3 scoreboards remain non-substitutable.
- Interpreted lowering is not the dominant general defect; read topology, boundary count, React work, and host behavior often matter more.
- Controlled input behavior is strong in Chromium, but Firefox, WebKit, multi-root, abandonment, reincarnation, HMR, and React lifecycle matrices remain release work.
- The collector can currently sit over materially different underlying subscription substrates; that choice changes retained cost enough that it must be adjudicated before the runtime ABI freezes.
- The shared re-frame2 frame context already makes mixed Hicasso/native-React subtrees feasible without a second state system; UIx is one demonstrated producer of those native components.

Performance evidence always names the witness, comparator, hardware, browser, commit, instrument, and confidence. Numbers from different instruments do not silently pool.

### 2.1 Use-case compass

Maintain a living requirements mine: application job, observed frequency, failure modes, intended home, and executable witness. Frequent ordinary jobs justify syntax; rare but critical jobs justify an excellent host escape or optional module. Absence from a census is evidence against universal syntax and standing cost, not evidence that the job is unimportant.

A feature enters core only when it removes repeated ceremony or a centralized defect class, has at least one paying witness, adds no unexplained standing boundary cost, and beats the smallest equivalent direct-React, Hicasso-native, or established-library alternative. Optional modules need a named consumer and zero reachability when absent. Recipes graduate to APIs only after repetition demonstrates that prose is inadequate.

## 3. Architecture laws

### 3.1 One language and one state owner

There is one ordinary Hicasso semantics: interpreted Hiccup. There is no `:fast` flag, compiled body, alternate renderer, automatic specialization, or profile-dependent meaning. The explicit native language is governed by the [native-boundary design law](lanes/design-laws.md#native-boundary); it never changes the meaning of Hiccup.

Application state has one owner: re-frame2. A host may use local React state for host-private motion, focus, transient editing mechanics, or vendor integration, but not as an invisible duplicate of application state.

The pressure valves are named. Durable or application-visible state—including drafts that affect validation, navigation, replay, or collaboration—uses an explicit re-frame2 address, normally through an optional forms/application pattern. High-frequency state that exists only to operate a native widget may remain inside that host and is diagnostic-opaque by contract. DOM-owned uncontrolled state is an explicit interop choice, never a hidden substitute for application state. No ratom-like second model is introduced.

### 3.2 React owns React facts

React owns component identity, hooks, refs, effects, errors, context, concurrency, Suspense, Activity, hydration, and commit. Hicasso uses React's public contracts rather than simulating them.

Render is speculative. It may retry or disappear. Render probes reads but acquires no durable ownership and publishes no committed evidence. Commit reconciles the selected read set. Abandoned renders leave no subscriptions or diagnostic records. Teardown is exact and testable.

### 3.3 Dynamic composition is a feature

Reads may occur in branches, loops, and ordinary synchronous helpers. Runtime-selected Hiccup and Clojure data transformations remain legal. This is a deliberate ergonomic advantage, not a temporary lack of compiler analysis.

The boundary is equally clear: a read deferred through a callback, promise, timer, lazy sequence, or other escaped extent refuses with source and recovery. Hicasso must never guess which render owns a deferred read.

### 3.4 Capability pays rent

A facility used by few boundaries must not add standing machinery to all boundaries. Optional libraries and the native namespace are separately reachable and absent from a production bundle that does not use them. Diagnostics project facts already retained by the runtime before they ask for more retention. Stable callback machinery, read ledgers, static manifests, and lifecycle histories require a measured caller before they exist.

The ordinary boundary's context plus external-store hooks consume the current two-hook budget. Optional capabilities may not add hooks to every boundary. Freeing or replacing one requires its own correctness and whole-shell measurement; a feature cannot quietly spend a third hook.

Measure the do-nothing and read-free boundary whenever the shell changes. Standing cost is the first budget; Hiccup lowering is the second.

The native tier must satisfy the shared-substrate and zero-rent clauses of the [native-boundary design law](lanes/design-laws.md#native-boundary). The [canonical native-tier checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) decides whether those clauses are proved; no paraphrased local checklist can waive them.

### 3.5 Data by default, functions when the contract is executable

Markup and ordinary event intent are data. An explicit handler form covers value-first or calculated events. Ordinary functions cross host boundaries unchanged when a JavaScript API genuinely expects executable behavior. What does **not** vary by position is ownership and prevention: a callback owns its own event everywhere and is never auto-prevented. What **does** vary is the contract the `h/event` carrier is read under — HD-024 tabulates event, as-declared and render — and it never varies *silently*: an intent dispatched at a render position refuses with `:rf.error/hicasso-intent-at-a-non-event-contract`, named at the position. **[Amended 2026-08-29, PR #8755 (`rf2-6c12m.24`).]** That refusal is retired: HD-024 now tabulates **event** and **render**, inferred from the position at a native tag and at a host alike, with a `defhost` `:callbacks` override (`:event` / `:render`) for an `on*`-named render prop; `:handler` and `:rf.error/hicasso-intent-at-a-non-event-contract` are gone, and an intent at a render position crosses as data.

*This section closed* "Position must not silently change the meaning of the same function form" *until 2026-08-16 (`rf2-0fd3b`).* That sentence collapsed the two questions into one and contradicted [§4.1](#41-events), which already says the position selects the contract; [`invariants.md`](invariants.md) I10 transcribes this section and had carried the correction since 2026-08-15, so the row was right and its owner was the defect.

### 3.6 Loud, stable failure

Every refusal has a stable error id, source coordinate, view, frame where relevant, tree path or host-prop position, offending value, expected shape, and actionable recovery. Unknown diagnostic information is labelled unknown, opaque, capped, or uncorrelated; it is never rendered as an authoritative empty result.

## 4. Target programming model

These names are a provisional facade. Phase 0 freezes the laws and classifications; ordinary names freeze after the Phase 2 application witness, and host/hot-path names after Phase 3. The public model should be no larger than this without a witnessed need.

| Surface | Contract |
|---|---|
| `h/defview` | Define a React/re-frame boundary; use it as a Hiccup head; direct Clojure invocation refuses |
| `h/sub` | Return a subscription value during the direct synchronous body; legal in branches, loops, and ordinary helpers |
| `h/event` | Capture the current frame; run later; dispatch a returned event vector; ignore `nil` — **at an event position**; the position selects the contract (`rf2-0fd3b`) |
| `h/defhost` | Declare a foreign React ABI, ReactNode-valued positions, and server policy once |
| `h/as-element` | Explicitly lower Hiccup returned through a render prop or foreign callback |
| attribute merge | Pure owned-wins helper or recipe for forwarded attributes; public only if a witness needs it |
| root lifecycle | `mount!`, `hydrate!`, `render!`, and `unmount!` with idempotent handles |
| server/hydration contract | A Render or Client-only policy for every inventory id under the canonical matrix; root-scoped identity, errors, adoption, and cleanup |
| error region | A minimal `h/error-boundary` surface |

The separately imported `re-frame.hicasso.native` namespace, conventionally aliased `n`, is the self-contained hot-path surface. It is two React hooks and nothing else; the island they serve lives in the [public-language specification](lanes/ergonomics-api.md#optional-native-surface):

| Surface | Contract |
|---|---|
| `n/use-sub` | Read re-frame2 state through a native React hook under the current Hicasso frame |
| `n/use-frame` | Obtain frame-locked operations from that same context |

**[Amended 2026-09-04, `rf2-aunp`, under the `rf2-6c12m.3` ruling of 2026-08-29:** this table published four further names as shipped surface — `n/$`, `n/props`, `n/defcomponent`, and a *native ABI helpers* row carrying the component contract through memoization, lazy loading, refs and outward/inward embedding. Option A deleted all four, and none of them appears anywhere in `implementation/` today. They are **removed** here rather than marked in place, because this table states what the public model *is*; their text stands in this document's history before that date, and [`lanes/ergonomics-api.md`](lanes/ergonomics-api.md#optional-native-surface) — the lane that owns this surface — carries the retirement record in full.**]**

React hooks remain React's surface and are called directly inside the island, which owns its own source and therefore its own hook order; Hicasso adds wrappers only after repeated island code proves material value. Phase 3 freezes the two hooks' contract only when every live row of the canonical native-tier checklist passes.

The interpreted grammar needs a fragment, raw React element escape, one props map, and a small reserved-data vocabulary: event value, checked value, explicit prevention, and controlled-value revision. Existing `rf/current-frame-id` and `rf/capture-frame` remain the frame doors; Hicasso should not duplicate them. Applications get one obvious `h` facade, with optional capabilities in clearly named namespaces and no alias sprawl; the facade and each optional namespace have bundle-reachability proofs.

A typical view stays unsurprising:

```clojure
(h/defview article-row [{:keys [article-id]}]
  (let [title (h/sub [:article/title article-id])
        saving? (h/sub [:article/saving? article-id])]
    [:article
     [:h2 title]
     [:button {:disabled saving?
               :on-click [:article/delete article-id]}
      "Delete"]]))
```

Ordinary `defn` helpers inline into their caller and donate any synchronous reads to the active boundary. A `defview` always creates identity and reactivity; it never changes into an inline function because it was called differently. Keys live in props, bodies are pure and re-runnable, and default prop equality follows the project's value-equality doctrine without mode flags.

### 4.1 Events

Literal vectors are the common path. Prevention is explicit everywhere except `:on-submit`, whose data spelling auto-prevents by deliberate law: a prevented submit forecloses no browser affordance the way a prevented click forecloses a modifier-click, and the rare real submission opts out through the function escape, which owns its event. `h/event` is the one callback form, and the **position** selects the contract it carries — HD-024 tabulates three, so "one meaning everywhere" is not what shipped: a native `:on-*` prop is **event**, a `defhost` `:callbacks` entry is **as declared** (`:event`, `:handler` or `:render`), and any other walked prop is **render**. **[Amended 2026-08-29, PR #8755 (`rf2-6c12m.24`): the roster is two contracts, not three — a `defhost` prop is inferred exactly as a native tag's is, `on*` event and otherwise render, with `:callbacks` reduced to an optional `:event` / `:render` override; `:handler` is deleted.]** Ordinary `fn` is reserved for real callbacks, render props, and imperative APIs. Generated intent callbacks may be fresh per render. A narrow stable-event primitive is admitted only if a realistic retaining host demonstrates a material problem and the solution is safe across abandonment, frame reincarnation, and teardown.

*This paragraph read* "`rf2-0fd3b` owns making that table travel with the name" *until 2026-08-16 (`rf2-0fd3b`).* The forward reference is discharged rather than deleted: the three positions are now named here, [`invariants.md`](invariants.md)'s `h/event` row carries each contract in full, and the published guide teaches the table directly under the heading that introduces the name — [`core/hicasso/03-events-as-data.md`](../../../core/hicasso/03-events-as-data.md#one-callback-form-hevent). `re-frame.hicasso.impl.intent` remains the authority all three transcribe.

Keyboard maps apply only to keyboard props and must make IME composition behavior explicit. The framework should not hide the DOM event when a host API genuinely requires it; that is the function escape.

### 4.2 Controlled fields

Controlled inputs are a framework law because the defect class is too subtle and common to repeat in applications. The contract includes same-turn convergence, committed echo, rejection/normalization, caret and selection preservation, composition safety, and identity-preserving reset through an explicit revision.

Forwarded attributes cannot replace owned value, checked, handler, key, or revision slots. Text, textarea, checkbox, radio, select, file input, contenteditable, blur-after-unmount, and async normalization each receive an explicit support or refusal policy.

Buffered drafts, touched/submit-attempt validation, and mutation status belong in an optional forms layer or recipe, not the boundary shell.

### 4.3 Host interop

`defhost` is the primary seam for foreign components, providers, compound components, refs, callbacks retained by vendors, render props, and server policy. A host declaration identifies ReactNode-valued props such as a Suspense fallback or named content slot so Hiccup is lowered under the captured frame without deep-converting arbitrary data maps. Dev schemas and lint validate declared slots and prop positions; runtime conversion remains honest: normalize documented HTML-like slots, pass values by identity, and require explicit `clj->js` for JavaScript option objects.

An ordinary render callback returns Hiccup only through `h/as-element`; this makes the conversion visible without a general callback taxonomy. Component and host definitions are minted at top level, never during render. Literal owned props win collisions by presence. Imperative integrations receive an idempotent acquire/release recipe and adversarial StrictMode/remount tests. A tiny optional portal helper lowers Hiccup into `createPortal`, preserves frame/context, documents React-tree event bubbling and target-change identity, and has an explicit client/server policy.

Add a thin outward bridge so a native React parent—whether authored in raw React, UIx, or JavaScript—can render a minted Hicasso view under the existing frame provider without another root or exposure of the internal codec/`rfProps` ABI.

## 5. Native React hot path

The hot path is a gradient of explicit choices, all visible to tests and Xray.

### Rung 1 — ordinary Hicasso

Start here. Use interpreted Hiccup, local reads, event data, controlled semantics, structural tests, and full diagnostics.

### Rung 2 — tune Hicasso topology

Move boundaries around independently changing or expensive regions, inline helpers that always travel with their parent, stabilize keys and props, and select a read topology that fits the workload:

- fine row reads for sparse independent updates;
- a coarse view-model read for cheap mount and bulk replacement;
- chunked or visible-window reads for large mixed workloads;
- virtualization for collections whose DOM should not exist in full.

Large oscillating read sets are suspect because whole-set reconciliation can become proportional to the current read count. Xray must show boundary count, reads per boundary, fan-out, and read-set churn.

### Rung 3 — direct React output from a Hicasso boundary

A `defview` may return an existing React element, constructed with `react/createElement` (or a `.jsx` file) or by UIx. The boundary keeps its frame, Hicasso reads, props, memo wrapper, and lifecycle but skips Hiccup lowering for that result. This is the narrowest escape when measurement says the codec/markup walk is material.

Native React semantics begin inside the returned element. Hicasso event lowering, controlled-field normalization, structural assertions, and key diagnostics do not inspect it. Use native React props and callbacks. Hooks do not belong in the dynamically composed `defview` body; hook-intensive behavior belongs in a separately defined native component so hook order cannot depend on Hicasso data paths.

### Rung 4 — named native React island

A virtualizer, editor, drag/animation surface, canvas/WebGL coordinator, retained vendor widget, or hot hook-intensive collection becomes a named native component — an **island**. It is written in React, raw or UIx, and mounted through `h/defhost` (or `[:>]` for a one-off); when it needs Hicasso state it reaches it through `n/use-sub` and `n/use-frame`, and an island that reads nothing needs neither. A JavaScript/TypeScript component enters the same way, through the host bridge. Every route stays inside the same React root and shared re-frame2 frame context.

The crossing has an explicit prop, child, callback, error, cleanup, diagnostics, and SSR contract. The native boundary is visible to Xray; its internal React subtree is opaque unless its re-frame reads pass through the supported native hooks. Repeated raw escapes graduate to a named host or island. A one-off raw element remains useful for migration and truly one-off interop, but it is not advertised as the performance tier.

### Rung 5 — native screen

An intrinsically React-first screen may implement its view tree with UIx or JavaScript/TypeScript React under the single installed Hicasso adapter and shared frame provider, reading Hicasso state through `n/use-sub` and `n/use-frame` where it needs any. No second adapter, root, or state owner is installed for speed. A substantial React-first application may reasonably choose UIx rather than writing the tree by hand. Phase 3 must prove subscription behavior, frame isolation, and cleanup parity across the mixed tree; an independent root remains an isolation choice only. (*This rung named the Hicasso-native namespace as a third way to implement the view tree, and warned against "asking Hicasso's deliberately narrow native surface to grow into a second component framework", until 2026-09-04, `rf2-aunp`. `rf2-6c12m.3` retired that route on 2026-08-29 and the surface constructs nothing, so the warning has no subject; the rung's obligations are unchanged.*)

### The working loop

1. Reproduce a named slow interaction with a stable script.
2. Use Xray to correlate event, changed reads, invalidated boundaries, body work, commit, and paint.
3. Tune read and boundary topology.
4. Compare direct React output if lowering dominates.
5. Isolate a named native component if hooks, vendor behavior, reconciliation, or high-rate local work dominates; choose UIx or raw React according to the component's actual needs. (*This step offered Hicasso-native as a third choice until 2026-09-04, `rf2-aunp`; `rf2-6c12m.3` retired it, and the component reads Hicasso state through the two hooks whichever route writes it.*)
6. Re-run DOM/intent parity, focus/selection, frame routing, SSR/hydration, cleanup, and performance checks.
7. Keep the escape only if it clears its declared benefit threshold.

Diagnostics may recommend a boundary and scaffold a comparison. They never rewrite code or switch execution semantics at runtime.

## 6. Performance contract

“Good enough” is a product contract, not a euphemism. Unless already registered, the initial budgets below are proposals until Mike Thompson, acting as re-frame2 product operator, ratifies them against every registered *physical* distributional profile in Phase 0 — today exactly `P-DEV-1`, under the single-profile limitation [budgets.md §1](budgets.md#1-the-named-reference-profiles) accepts explicitly and records the cost of; a second physical profile, when one registers, is owed the same run. The K1 ceiling has the additional ratification rule below, and it is the one budget here that has already been through it.

The registered `1.10x` K1 gate is a decisive miss, not a pass; the exact record is fixed in the [evidence baseline](lanes/evidence-baseline.md#pinned-economic-evidence) and decision brief. **The miss stands as published and nothing below recolours it.** The `1.25x` cold-mount ceiling is **ratified and operative from 2026-08-13** — the scoped price-acceptance record Phase 0 prepared was ratified by the re-frame2 product operator, Mike Thompson, ruling the P2 fork *graduate, as a success* in chat on that date, which pre-empted the 2026-08-27 sitting it had been held for. Acceptance of a price is not a pass: **the registered criterion remains the only adjudicated K1 line, K1 stays recorded MISSED, and no evidence row may cite the accepted ceiling to mark K1 green.** The operative status, the fields and the frozen comparison rule that reads a future row against `1.25x` live in [`k1-price-acceptance.md`](k1-price-acceptance.md); the ruling is [`decisions.md` HD-029](../decisions.md#hd-029--the-p2-fork-hicasso-graduates-as-a-success).

That record names the frozen registered criterion, purchased use cases, accepted ceiling, native escape, effective revision, evidence owner, reopen conditions, and revert condition. It lapses if the purchased ambient-read capability or the native escape fails its named witness, if the canonical K1 row exceeds the accepted ceiling, or if the witness/estimator changes materially without re-ratification. On lapse the registered gate and its consequences resume immediately as the only adjudicated K1 line; ratification by ruling did not put the record beyond lapse, and its reopen conditions are unchanged and live.

The registered read-free boundary-shell paper-fail budget remains `1 KB` retained per boundary on each canonical segment. Phase 0 must freeze its byte-exact interpretation before the next product run; the current pinned row is red under either 1,000 B or 1,024 B, and the [baseline owns its values and evidence](lanes/evidence-baseline.md#pinned-economic-evidence). Phase 1 first attempts remediation through the collector-substrate adjudication.

If Phase 1 cannot meet the frozen line without breaking a higher-order law, a separate prospective re-registration or scoped acceptance—decided by the same product operator before the runtime ABI freezes—must name the reason, ceiling, effective revision, reopen conditions, and revert trigger. The K1 ratification does not pre-authorize that later decision, and a relative regression allowance cannot recolour the red shell row.

Deterministic correctness, residue, scaling-shape, and production-erasure gates block ordinary changes. Noisy clock/heap distributions are adjudicated in pinned, interleaved evidence runs rather than converted into flaky PR thresholds. Every instrument states its estimand and exercised population, includes a positive control or sabotage that can make it fail, and refuses to publish when its own quality checks fail.

### Absolute correctness and user-visible budgets

- Controlled updates are correct in the same turn and visibly echoed within one 60 Hz frame at p95.
- Ordinary discrete interactions reach next paint within 50 ms p95 and 100 ms p99.
- Broad application operations complete within 100 ms p95 unless explicitly classified as background work.
- Dragging and animation remain inside their frame budget, normally by keeping high-rate mechanics local to a native host.
- Narrow-update body work scales with changed rows rather than all mounted rows.
- Teardown residue is zero after quiescence.

For the four-field editor and controlled grid, publish the mechanical per-keystroke path: state writes, subscription recomputations, boundary runs, write amplification, commit, and visible echo. This is explanatory product documentation as well as a performance witness.

### Comparative and regression budgets

- The pinned ordinary-Hicasso benchmark does not regress more than 5% on the same witness and instrument.
- Under the ratification recorded above, cold mount has an initial ceiling of 1.25x equivalent direct UIx on the agreed representative witness, read against a future canonical row only by the record's own frozen comparison rule. It does not displace the registered `1.10x` line or recolour the published miss. Read topology and capability differences remain reported alongside the ratio.
- After topology tuning, representative broad updates target no worse than 1.25x the best relevant supported adapter. A sustained result beyond 1.5x triggers local-island analysis rather than a global redesign.
- The read-free boundary shell meets the frozen byte-exact `1 KB` line or carries the separately ratified disposition described above. It is not governed by a “baseline plus 10%” rule.
- Per-read retained cost is governed by the three-scoreboard K3 disposition and also may not regress more than 10% on the same pinned witness. Retained cost stays linear in boundaries/reads and teardown leaves no residue.
- A native island should be within 5% or 1 ms of the same component mounted directly through its chosen React route, excluding the single explicit crossing. The UIx route is co-instrumented against handwritten React construction so the convenience layer cannot define its own floor. (*This bullet read* "**The Hicasso-native surface is co-instrumented against both handwritten React construction and UIx so the convenience layer cannot define its own floor.**" *until 2026-09-04, `rf2-aunp`. `rf2-6c12m.3` retired the native authoring surface on 2026-08-29, leaving two arms; `implementation/hicasso/test/re_frame/hicasso/three_way_parity_cljs_test.cljs` quotes the sentence above and states the succession — handwritten `react/createElement` is the arm with no convenience in it, so it stays the floor and UIx is measured against it. The `5%`/`1 ms` line is unchanged and no result is re-scored.*)
- An escape earns its added complexity by recovering at least 20%, saving at least 2 ms p95, or converting a failed user-visible budget into a pass.

Once the comparative budgets are ratified, disposition is explicit. A same-instrument baseline regression blocks the change until the benchmark owner validates the instrument and the adapter owner fixes or reverts it. A 1.25–1.5x comparative result is a warning band: attribute the cause, make one bounded topology pass, and test a local island where appropriate. Above 1.5x, or on any user-visible miss, that scenario cannot graduate as ordinary Hicasso until it is fixed or deliberately classified as a native-host use case. An island that misses its parity or benefit threshold is simplified or removed; thresholds do not widen to keep it.

Representative bulk behavior is a release gate. The topology tournament must publish qualified, behaviorally matched sparse, broad-replace, reorder, and controlled-edit rows at 100, 300, and 1,000 items, meet the ratified user-visible and comparative budgets, and preserve the declared scaling shape. The current instrument-limited broad row is therefore an open obligation, not a waiver. Warm-allocation evidence is not a release gate: no allocation claim publishes until a fitted series clears the registered quality floor. Retained heap, boundary shell, teardown, and bulk behavior remain required independently.

The native-code percentage is reported as a source/component census after implementation; it is never enforced. Diagnostic and profiling sentinels must be absent from default production bundles.

The bounded experiment set is the [canonical native-tier checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist), direct-return delta, fine/coarse/chunked/native topology tournament, retaining-host callback identity, one mutation-proved causal Xray slice, and outward-bridge performance/lifecycle. Exact protocols live in the [hot-path lane](lanes/hot-path-architecture.md); no open-ended benchmark programme follows them.

## 7. Complete use-case coverage

Completeness does not mean every feature belongs in core. It means every recurring application job has a maintained answer and a clear ownership layer.

| Use case | Default answer | Additional surface or escape | Required proof |
|---|---|---|---|
| Ordinary pages, conditional UI, dynamic lists | Hiccup, helpers, ambient reads, keyed boundaries | None | Todo and RealWorld-class flows |
| Forms and controlled fields | Core controlled law and revision | Forms recipes; **optional `re-frame.hicasso.forms` module (`forms/buffered-field`), V0 by operator ruling** — Mike, 2026-08-12: the module is V0 scope and `docs/core/hicasso/05-forms.md` stands as its draft spec, which for this feature only overrides the second-caller extraction gate below. The gate stands everywhere else (rf2-sh56) | Four-field editor, 100-cell grid, Chromium/Firefox/WebKit IME |
| Validation and async normalization | Derived subscriptions and event data | Validation-gating and settle-merge recipes | Late result cannot clobber newer edits |
| Routing and navigation | Routing integration namespace | Dirty-leave, scroll restoration, focus-on-route recipes | Deep link, back/forward, pending mutation |
| Async resources and mutations | re-frame2 resource/event model: a subscription is a passive read that never fetches, and an event, route or machine cause owns acquisition and release | Mutation status/settle-merge recipes; `rf2-hic-054`'s explicit async-resource recipes. The *committed-read resource demand* experiment this cell called flagship is **STOPPED** — the typeahead witness ran and `rf2-hic-050` returned STOP on `rf2-hic-039`'s frozen criteria (record page [`resource-demand-verdict.md`](resource-demand-verdict.md)); amended 2026-08-16 under `rf2-h3tke` | Typeahead, cancellation, supersession, rollback |
| Errors | Minimal error region | Expected failures stay data | Thrown render, retry, nested region |
| Foreign React ecosystem and native hot work | Core React bridge: `defhost`, raw element, `h/as-element`; optional Hicasso-native namespace | Library-specific wrappers, optional UIx route and outward bridge | Compound library, native hot row, render prop, provider, portal |
| Large collections | Suitable read topology and keys | Blessed foreign virtualizer recipe | 10K-row behavior, focus and accessibility |
| Imperative SDKs | Declared host ownership | Acquire/release recipe | StrictMode, remount, throw, cleanup |
| Overlays and focus | Native HTML where possible | Optional popover/modal module on top-layer primitives | Nesting, dismissal, focus restore, zero idle listeners |
| Motion and high-rate input | Presence posture | Native host-local animation/drag state | Interrupted transition and frame budget |
| Code splitting | Route/module boundary | Small `React.lazy` bridge for Hicasso's boundary ABI; Hiccup-aware Suspense/error host | Load, fallback, error, retry, HMR |
| Multiple frames and roots | Shared frame context with isolated ownership | Explicit independent root only when needed | Same public id reincarnation, two roots, teardown |
| Suspense and Activity | React-owned lifecycle | Declared host boundary when necessary | Suspend/retry; hide/reveal releases and reacquires reads |
| SSR and hydration | Every public-surface inventory id follows the canonical Render or Client-only policy | Optional Node service, built in v0 — the *"for a named caller"* gate this cell carried is **REMOVED** (amended 2026-08-12 by operator ruling, Mike in session 17:36 AUSEST, `rf2-xpq9`; *optional* now describes packaging only). Its operational contract remains separate from surface semantics | Every inventory id is green in the [canonical SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) |
| Accessibility | Semantic Hiccup and native controls | Structural a11y assertions plus browser focus tests | Names, roles, keyboard, virtualized/overlay focus |
| i18n and theming | Ordinary data, classes, CSS variables, context through hosts | Recipes; no adapter subsystem | Runtime locale/theme change |
| Testing | Pure data and supported semantic harness | Mounted DOM and browser tiers | Positive and sabotage controls at every tier |
| Diagnostics | Versioned current-state evidence | Xray/Pair causal and heat views | Loss states, privacy, production erasure |
| Migration | Reporter and explicit refusal classes | Shadow DOM/intent comparison; cautious codemod later | Three representative repositories |

The async-resource answer is a passive read plus an explicit fetch cause: a subscription reads resource state and never fetches, an event, route or machine cause owns acquisition and release, and debounce, supersession, refresh-with-data, and cancellation remain explicit policies. `rf2-hic-054`'s async-resource recipes are the standing answer.

> *Amended 2026-08-16 under `rf2-h3tke`.* The paragraph this replaces named **demand-driven resource ownership** the strategic differentiator — *"a committed `sub` that reads a resource may also declare demand; unmount or parameter change releases it"* — fenced it hard (reuse committed read membership, execute nothing for abandoned renders, no second per-read ledger), and left *"a typeahead witness decides whether it graduates."* **The witness ran and the verdict is STOP** (`rf2-hic-050`, 2026-08-12; record page [`resource-demand-verdict.md`](resource-demand-verdict.md)), applying `rf2-hic-039`'s frozen criteria to `rf2-hic-044`'s report: any ambiguous criterion gives STOP, and C3 was ambiguous on two independent triggers. Committed-read resource demand is **not adopted**, nothing under `implementation/` implements it, and `implementation/resources` states in the runtime's own words that subscriptions stay passive. The earlier disposition is recorded here because it is why the idea was once wanted — not because it is current.

The initial optional-library priorities are forms and overlays because they remove repeated high-risk application code. Layout primitives, a generic component-library platform, generic state helpers, and a lifecycle/effect DSL stay out until repeated external demand demonstrates that recipes and hosts are inadequate.

## 8. Modern React compatibility

React compatibility is a release matrix, not a one-time claim. Keep `useSyncExternalStore` behind an internal seam because React makes external-store updates synchronous and limits their Transition/Suspense behavior ([reference](https://react.dev/reference/react/useSyncExternalStore)).

React 19.2 Activity must release active subscriptions while hidden and restore them safely on reveal ([reference](https://react.dev/reference/react/Activity)). Hydration uses React server bytes, recoverable-error reporting, matching `identifierPrefix`, and root isolation ([reference](https://react.dev/reference/react-dom/client/hydrateRoot)). Streaming stays behind the server abstraction ([server APIs](https://react.dev/reference/react-dom/server)).

The supported-version matrix covers StrictMode, retry/abandonment, Suspense, Activity, errors, HMR, multiple roots, controls, and production erasure. The separate [public-surface SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) owns server policy and hydration states. Lazy components are declared outside render and use a small boundary-ABI adapter. Tests use pure data, mounted DOM, and browsers—not deprecated test-renderer/shallow foundations ([warning](https://react.dev/warnings/react-test-renderer)). Experimental React APIs remain at native host edges.

## 9. Testing as a product surface

Ship a supported `re-frame.hicasso.test` namespace with a deliberately layered contract.

| Tier | What it proves | Mechanism |
|---|---|---|
| L0 | subscriptions, handlers, state transitions | Pure CLJ/CLJS functions |
| L1 | codecs, intents, control/revision, optional-module laws, native-form expansion and boundary-ABI helpers | Pure data, property, and macro-expansion tests |
| L2 | One hook-free Hicasso body | Restricted semantic-tree assertion harness with injected read fixtures |
| L3 | React lifecycle, context, hooks, refs, errors, hosts | Mounted React DOM and Testing Library/user-event |
| L4 | IME, caret, focus, layout, hydration, performance | Chromium, Firefox, and WebKit |

L2 is an assertion model, not a renderer or React-parity oracle. It invokes one hook-free body under a discardable resolver and expands no children of its own: a nested Hicasso boundary is recorded as the CALL it is — its view id, the props the call site passed and the children the call site wrote — and its body does not run, so the node claims nothing about what that child would render. A test may name the body function or write the minted `defview` head: the mint attaches the body to the head under one dev-only own property — no registry and no map — so a harness that runs no hook can reach it, and an `:advanced` build with `goog.DEBUG` false carries no such property and refuses a minted head there. Hosts, raw React, hooks, identity, lifecycle, Suspense, and errors are opaque/L3; missing fixtures and escaped reads refuse.

The mounted facade provides isolated-frame mount, hydrate, rerender, dispatch-and-settle, settle, advance-clock, unmount, and assert-clean. Cleanup waits for quiescence and compares residue with the pre-mount baseline before reset.

Every important gate has a negative or sabotage control so an accidentally empty population cannot pass. Apply the canonical native-tier checklist around native extraction, add migration-shadow tests for Reagent conversions, and run a bounded schema-driven generative spike for state/intent sequences.

Every differential witness names the equality it proves: authored data, semantic assertion tree, canonical DOM, intent stream, React server bytes, or hydrated browser behavior. Normalized structure cannot stand in for React's hydration wire format, and a semantic snapshot cannot claim lifecycle parity.

## 10. Xray and runtime evidence

Xray should answer questions developers actually ask:

1. What can current evidence prove about mounted, hidden, suspended, or recently committed work?
2. Why did this boundary run or commit, which reads changed, and what is their fan-out?
3. Did props, context, reads, retries, abandonment, errors, or a host cause the work?
4. Is pressure in computation, read topology, Hiccup lowering, React, or layout/paint, and what remedy is credible?
5. What information is unavailable, capped, host-opaque, or uncorrelated?

The causal lens follows:

`event -> subscriptions recomputed -> values changed -> boundaries notified -> bodies run -> React commit -> paint`

Each link needs an explicit evidence seam. Timing proximity never proves commit, paint, hidden, or suspended state; a render measure may be retry, throw, StrictMode duplicate, or abandoned work. Use existing trace history, pure current-state projections, separate commit evidence, and `unknown`/`opaque`/`uncorrelated` for unsupported links. Xray owns bounded retention.

Every envelope carries schema, producer, operation, scope, basis, completeness, and loss. Xray and Pair consume the same privacy-projected schema; sink failures are contained and production contains no evidence/source sentinels.

Emit the standard adapter-neutral fields that can be proved: view, frame, source, current reads, known cause, attempt outcome, commit/rendered event, and unmount. Stable occurrence identity requires a commit-owned identifier. User Timing is complementary.

The differentiating feature is a hot-view advisor that ranks time/frequency/read churn/fan-out, then classifies computation, topology, lowering, React, or layout pressure. It recommends native extraction only when it addresses the measured owner, selects the smallest credible route—direct React output from the boundary, a named island in raw React or UIx, or a foreign React host—and otherwise points to computation, topology, boundary, or virtualization work.

## 11. Innovation portfolio

Novelty enters only through a small deciding experiment. The detailed designs and privacy/kill rules are in [the left-field lane](lanes/left-field-ideas.md).

| Idea | Status | Deciding rule |
|---|---|---|
| Complaint catalogue and cause-aware hot advisor | Adopt | Stable recovery guidance and cause-directed optimization are part of the core developer workflow; no automatic promotion |
| Counterfactual topology advice | Spike | Retain only if blinded calibration predicts useful coarse/fine/chunked choices |
| Capability receipts | Adopt (counts only) | Attempt facts derived post hoc from retained state, so the receipt costs one tap per attempt at the body-run site and no per-read or per-node tap; an attempt is counted from the moment its body is entered, so a throw, a Suspense promise and a failed lowering are recorded attempts whose codec-node count reads `unknown` rather than zero; self time refused, because the clock grain is coarser than the quantity and the overhead scales with attempt count rather than cost |
| Replayable view capsules | Spike after L2 | One-shot, commit-owned, redacted; stop if representative views are mostly opaque |
| Pull-shaped reads | Spike | Must beat hand-coarse ergonomics/cost without a per-leaf ledger or independent-churn regression |
| Schema-driven generators | Spike | Graduate only if they find real defects/refusal gaps and shrink usefully |
| AI-pair queries and migration shadowing | Extend existing tools | Reuse Xray/Pair and canonical DOM/intent evidence; create no parallel graph/history system |
| Shared read-set notification groups | Census, then spike | Proceed only for material identical-set fan-out with no singleton/cleanup regression |
| Codec shape planning | Watch | Profile-trigger only when classification/lowering exceeds 10% of hot-boundary self time; require whole-boundary gain and bounded cache growth |
| Read-free shell, intent replay, keyed-list maintenance, future React store seam | Watch | Each waits for its named population, owner, or failing budget |
| Compiler/JIT mode, second renderer, worker view runtime, signals replacement, universal callback cells, hydration-free inference | Reject | Second semantics/state, standing cost, or unsupported inference |

## 12. Action programme

The programme proceeds in vertical slices. Testing and Xray land with the behavior they explain; they are not deferred to a polish phase.

### Phase 0 — freeze the invariants and establish the product spine

Deliver:

- a one-page invariant/capability ledger and provisional facade;
- the [K1 price-acceptance record specified in section 6](#6-performance-contract), drafted for a sitting and made operative when its named decider ratified it by ruling on 2026-08-13;
- the section 6 read-free-shell disposition, including the frozen byte-exact `1 KB` line and Phase 1 remediation gate;
- an explicit K3 disposition that preserves the governed Reagent viability row, the UIx-parent architecture row, and author preference as three non-substitutable scoreboards;
- the living requirements mine, with every surface mapped to a job, home, owner, and witness;
- the core/optional/host/recipe/refusal classification for every use case in section 7;
- a unique inventory entry and server/hydration policy for every proposed public surface under the [canonical SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix), independent of whether the Node service is activated;
- ratified performance budgets, estimands/control standards, named reference hardware, and an instrument-eligibility rule that must pass before a product row can publish;
- an installable `implementation/hicasso` package, public namespaces, metadata, tiny app, production build, and HMR path;
- one public facade, optional-namespace reachability checks, and no benchmark-only aliases;
- stable error shape and source-coordinate capture in `defview`/`defhost`.

Exit when a clean consumer can compile and run a minimal view without benchmark-tree imports, no provisional option selects an execution mode, the named decider has ratified or rejected the K1 record with consequences recorded — **ratified by ruling on 2026-08-13**, so this clause is satisfied — and the shell breach has an operative remediation gate rather than a relative-baseline waiver. Exact names are not frozen ahead of the application witnesses.

### Phase 1 — make the reactive kernel trustworthy

Deliver and sabotage-test:

- render-probes/commit-owns behavior;
- conditional and changing reads;
- abandonment, retry, unmount, and rollback;
- multiple roots and frame isolation;
- same-public-id frame reincarnation and delayed callbacks;
- Activity hide/reveal and Suspense behavior;
- HMR generation and cleanup;
- controlled input across Chromium, Firefox, and WebKit;
- an explicit choice of the under-collector subscription substrate, using correctness, retained cost, clock, teardown, and migration evidence rather than an inherited default;
- read-free shell evidence at or below the operative byte-exact `1 KB` line on both canonical segments, or a separate prospective operator disposition completed before runtime ABI freeze;
- exact residue after quiescence;
- root-scoping or explicit justification for every mutable global.

Exit at zero stale reads, cross-frame operations, tears, or residual ownership and only when the shell meets its frozen line or carries its separate prospective disposition. Correctness failures are blockers, not performance trade-offs.

### Phase 2 — ship one lovable vertical slice

Build a small but complete application flow using only the proposed API: routing, keyed list, article edit, async mutation, controlled fields, errors, and reset. In the same slice, ship:

- the L0–L3 testing facade;
- the first versioned evidence projection;
- Xray mounted/read/intent/explain-render views;
- the complaint catalogue;
- the first bounded clj-kondo checks;
- production-erasure proof.

Exit when the application code contains no artificial boundaries or internal imports and its core behavior is readable without browser-test ceremony. Freeze the ordinary authoring facade from this evidence.

### Phase 3 — make the native hot path excellent

Deliver:

- direct React output contract and delta benchmark;
- the optional `re-frame.hicasso.native` namespace — `n/use-sub` and `n/use-frame` — passing every **live** row of the [canonical native-tier acceptance checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist); (*this deliverable also required the namespace to implement the [provisional `n/$` grammar](lanes/ergonomics-api.md#provisional-n-grammar) until 2026-09-04, `rf2-aunp`. `rf2-6c12m.3` deleted that grammar on 2026-08-29 and the checklist rows that scored it are marked RETIRED there; the linked heading is kept as the retirement record, which is why it is still cited.*)
- completed `defhost` callback, ReactNode prop, children, provider, render-prop, ref, error, and SSR contracts, plus the optional portal helper;
- outward Hicasso-under-native-React bridge, exercised from UIx and raw React parents; (*this line read* "*exercised from Hicasso-native, UIx, and raw React parents*" *until 2026-09-04, `rf2-aunp`; `rf2-6c12m.3` retired the first of the three and an island is now written in one of the other two. The bridge itself is untouched — `h/as-component` sits on the facade rather than the tier, deliberately, so a UIx or JavaScript parent need not require the native namespace.*)
- topology tournament and host-identity experiment;
- one causal Xray interaction and hot-view advisor;
- one virtualizer and one imperative SDK witness.

Exit when the canonical native-tier checklist is green and a measured hot boundary can move to native React, meet the island budget, and remain diagnosable from the same Xray surface. Freeze the host, outward-bridge, and hot-path facade from those witnesses.

### Phase 4 — close the application coverage matrix

Extend the product application and witness suite to cover pagination, runtime content, code splitting through the explicit lazy-boundary bridge, lazy/error fallbacks, navigation prefetch across hover/focus/touch, navigation focus, multiple frames, retained callbacks, provider/compound components, accessibility, SVG/custom-element attributes, autofill/form reset/FormData, all named control types, the complete countable SSR/hydration matrix, and a typeahead resource-demand witness. Publish the editor/grid per-keystroke mechanics and the qualified bulk topology tournament. Add a second screen with a serious vendor component.

> *Amended 2026-08-16 under `rf2-h3tke`.* The **typeahead resource-demand witness** this list asks for **has run**, and the list keeps its text as the programme that was planned rather than as work still owed. `rf2-hic-044` reported the witness and `rf2-hic-050` returned **STOP** on `rf2-hic-039`'s frozen criteria (2026-08-12; record page [`resource-demand-verdict.md`](resource-demand-verdict.md)). Committed-read resource demand is not adopted, and the §7 row this witness was to prove now reads as a passive read with an explicit event, route or machine fetch cause.

Exit when every row in section 7 points to running evidence, an installable optional module, a tested recipe, or an explicit non-goal with a React escape, and when the canonical SSR/hydration and bulk/economic suites are green.

### Phase 5 — decide differentiators and add high-value optional products

> **Amended 2026-08-12 by operator ruling (Mike, in session, 17:36 AUSEST; `rf2-xpq9`): every item in this phase is a V0 DELIVERABLE, not an optional product held for a later release. V0 is not done until they are completed.** Completion has two shapes. **Feature-shaped** items — 3, 4, 5, 6 and 7 below — are built, witnessed and shipped in v0; item 2's buffered-draft helper was ruled into v0 the same day by `rf2-sh56` and `re-frame.hicasso.forms` has shipped. **Decision-shaped** items — 1 and 8 — are complete when the spike runs and its pre-registered verdict is made, and **a ruled STOP completes the item: completion is the verdict, never forced adoption.** Two clauses in the ladder move and one does not. Item 7's *"when a named caller activates it"* condition is **REMOVED** — the bounded service is built in v0 to the spec item 7 already states (`rf2-hic-056`, whose `[DORMANT]` marker is lifted). Item 6's *"and only then"* sequencing is **RETAINED**, now running inside v0 rather than after it. The two floors closing this section stand unchanged, so *optional* now describes only how a namespace is packaged and reached — never whether it ships.

In priority order:

1. decide the committed-read resource-demand spike from the typeahead witness — **decided, and this decision-shaped item is COMPLETE: the witness ran and the verdict is STOP** (`rf2-hic-050`, 2026-08-12; record page [`resource-demand-verdict.md`](resource-demand-verdict.md)), which the amendment above settles as completion — *a ruled STOP completes the item*. Committed-read resource demand is not adopted and nothing implements it; `rf2-hic-054`'s explicit async-resource recipes are the standing answer (amended 2026-08-16 under `rf2-h3tke`);
2. forms recipes and a buffered-draft helper after its second consumer — **the second-consumer condition is REMOVED and the item is delivered: operator ruling (Mike, in session, 2026-08-12; `rf2-sh56`) put the optional `re-frame.hicasso.forms` module in V0 scope with [`core/hicasso/05-forms.md`](../../../core/hicasso/05-forms.md) standing as its draft spec, and `forms/buffered-field` has shipped. For this feature only that overrides the §7 second-caller extraction gate; the gate stands everywhere else**;
3. anchored popover and modal on native top-layer primitives;
4. presence/motion posture and focus intent;
5. routing and async-resource recipes;
6. migration reporter, shadow comparison, and only then safe codemod transforms;
7. deliver the bounded Node/React SSR service contract when a named caller activates it (**that condition is removed — see the amendment above**), using immutable request snapshots, allowlisted state, build identity, bounded isolate concurrency, hard termination, and a pre-registered caller latency envelope;
8. selected left-field spikes that meet their decision criteria.

Each optional namespace proves zero reachable production code when absent. No optional feature changes the boundary shell.

### Phase 6 — adoption and release

Publish versioned artifacts, compatibility matrix, upgrade policy, API reference, cookbook, troubleshooting guide, performance method, and escape criteria.

Make Hicasso the one taught interpreted-Hiccup native story and archive or remove the obsolete re-frame.ui/Freehand public APIs and documentation so users do not face overlapping data-view models. UIx remains the supported React-first adapter and optional native authoring route.

Move every live Xray/Story/Pair consumer onto the adapter-neutral Hicasso evidence provider before disposing of the experimental donor tool surfaces. Fixtures may retain explicit compatibility coverage, but production and primary tooling may not retain a hidden dependency.

Recruit two real pilot applications and migrate at least one substantial screen in each.

Exit when both pilots can install, develop, test, diagnose, production-build, and upgrade without framework-author intervention, and no critical public API changes across one release candidate.

## 13. Definition of done

Hicasso is ready to be the native re-frame2 adapter when all of the following are true:

- The package is independent of the benchmark tree and has a documented compatibility/release policy.
- The language is small, internally coherent, source-located, and has no execution-mode switch.
- The collector substrate and two-hook shell are explicit, measured choices; optional capabilities add no universal hook or ownership graph.
- The full correctness matrix passes with sabotage controls and exact cleanup.
- The representative app, controlled grid, compound host, virtualizer, and imperative SDK use only public surfaces.
- React-library interop is a core contract: providers, compound components, ReactNode slots, render props, refs, errors, lazy boundaries, outward embedding, and same-root native islands pass their ownership tests.
- Every inventoried public surface passes its **live** policy row in the [canonical SSR/hydration matrix](lanes/react-compatibility-notes.md#public-surface-ssrhydration-matrix) even when the optional Node service is not deployed. The two native-tier rows that matrix marks retired describe a surface that no longer ships and are not scored; the surviving tier's obligation is the two hooks' server behaviour, stated on the row that replaces them.
- Ordinary performance meets the ratified user-visible, regression, mount, update, and heap budgets.
- The read-free boundary shell meets the operative byte-exact `1 KB` line or has a separately ratified, prospective operator disposition; a relative regression budget cannot substitute for it.
- Qualified bulk evidence covers the named sparse, broad, reorder, and controlled-edit populations and meets the ratified budgets. Warm allocation is not a release blocker and carries no product claim unless its instrument qualifies.
- Every **live** row of the [canonical native-tier acceptance checklist](lanes/hot-path-architecture.md#canonical-native-tier-acceptance-checklist) passes; no partial parity result substitutes for the rows that remain. The rows that checklist marks retired — the `n/$` form grammar and the component ABI — describe a surface deleted by `rf2-6c12m.3` and are not scored, and no row is waived by success on another.
- The testing ladder is supported, with honest opacity boundaries and browser coverage for browser laws.
- Xray explains current reads and causal work, accounts for loss, guides hot extraction, respects privacy, and erases from production.
- Every motivating use case is assigned to core, optional module, recipe, host escape, or explicit non-goal.
- The requirements mine and per-keystroke mechanics are current, and the demand-driven resource spike has an explicit adopt/stop verdict.
- Two pilot applications ship substantial screens without bespoke support.
- No primary documentation, production path, or live developer-tool consumer depends on the experimental re-frame.ui or Freehand public surfaces; any retained compatibility fixture is named and isolated.

Client v0 does not wait for an unused production SSR deployment, but the hydration contract and React-server compatibility tests are mandatory. **The bounded Node service is itself a v0 deliverable, built and witnessed to the spec Phase 5 item 7 states.**

> *Amended 2026-08-12 by operator ruling (Mike, in session, 17:36 AUSEST; `rf2-xpq9`).* The sentence this replaces read **"The bounded Node service ships when a named caller exists"**, and the ruling **REMOVES** that condition — every Phase 5 item is v0 scope, `rf2-hic-056` is awake and its `[DORMANT]` marker is lifted. This is the definition of done, so the change is load-bearing rather than editorial: v0 is not complete until the service is built. **What does not move**: building it is the obligation, deploying it for a live consumer is not, which is why the first clause of this paragraph stands unchanged and why the surface row above still requires every inventoried surface to pass its policy with the service absent.

> *Amended 2026-09-04 (`rf2-aunp`), restating two bullets over the surface that survived `rf2-6c12m.3`.* **The native tier is two hooks.** That ruling took Option A on 2026-08-29: `re-frame.hicasso.native` is `n/use-sub` and `n/use-frame`, 82 lines and two public names, and `n/$`, `n/props`, `n/defcomponent`, `n/memo` and `n/lazy` are deleted — they occur nowhere in `implementation/`. Bullets 7 and 11 were written over that deleted grammar and could not be scored as they stood: they sent a reader to a checklist whose first two rows and a matrix whose two native rows describe names the package does not have. Both bullets are now **restated over the live rows**, and both canonical tables mark their retired rows in place rather than deleting them, because those rows are the record of what was once owed. **No verdict is re-scored and no threshold moves by this amendment.** **Bullet 6 needed no restatement and is unchanged**: its *same-root native islands* names a subject that survives the ruling intact — an island is a React component, raw or UIx, mounted through `h/defhost` under the same root, frame and app-db as the Hiccup around it, which is `native.cljc`'s own definition and the [guide's islands chapter](../../../core/hicasso/10-native-tier.md). Only the authoring route changed; the ownership tests the bullet demands are owed exactly as before.

> *Amended 2026-09-04 (`rf2-aunp`, second pass after a merged-PR audit of the first).* **The tables were reconciled; the surrounding prose still sold a third authoring route, and that is what this pass fixes.** The audit found that §5's rung 5, §5's working-loop step 5, §6's island-parity bullet and §12's Phase 3 deliverables — with the matching passages in [`lanes/hot-path-architecture.md`](lanes/hot-path-architecture.md), [`lanes/react-compatibility-notes.md`](lanes/react-compatibility-notes.md), [`lanes/corpus-insights.md`](lanes/corpus-insights.md), [`lanes/use-cases.md`](lanes/use-cases.md), [`lanes/left-field-ideas.md`](lanes/left-field-ideas.md), [`lanes/testing-xray.md`](lanes/testing-xray.md), [`decision-brief.md`](decision-brief.md) and [`requirements-mine.md`](requirements-mine.md) — still listed "Hicasso-native" beside UIx and raw React as a way to WRITE a component, view tree, host or parent. It is not one and has not been since 2026-08-29: `re-frame.hicasso.native` supplies two read hooks, so raw React or UIx authors the island and the namespace is what the island reads Hicasso state with. Every such passage now names the two authoring routes and carries an italic note giving its former wording, the ruling and the date, following the retirement convention [`lanes/design-laws.md`](lanes/design-laws.md#native-boundary) law 1 already set. **Bullet 6 is again left unchanged, on the reasoning above, which this pass re-checked and agrees with.** **Nothing here re-scores a verdict, moves a threshold or changes what any row requires** — only which routes are named. The dated checkpoint, ledger and verdict documents are deliberately untouched: they record what was measured on the day, and correcting them would time-travel the record.

> *Amended 2026-09-04 (`rf2-60jv`), recording the currency of the public-surface-only bullet.* **The claim stands; its positive witness is gone and is deliberately not re-asserted.** The per-package `*surface-cljs-test*` suites that pinned each application's exact roster of permitted doors were deleted by `rf2-6c12m.10`, and no file matching `*surface*` survives under `implementation/hicasso/`. Their successor, `re-frame.hicasso.examples.fence-cljs-test`, derives its population from the `examples/` directory on **every run** and carries a sabotage control and a planted-breach control — but it is a four-family **blocklist** (`re-frame.hicasso.impl.*`, `re-frame.bench.*`, the `tools/` namespaces, the test kit), not a permitted-door roster: a require of anything outside those four families passes. What the blocklist does not carry — each package's exact door roster, the absent-routing-edge assertions, and the ledger vendor's *names nothing of ours* claim — is now **reviewed rather than enforced**, true as at the date each reporting page records, and those pages say so. **Where the five named witnesses stand**: the representative app, the controlled grid and the virtualizer are inside that fence. **The compound host and the imperative SDK are outside every fence by construction** — both live outside `examples/`, and both are `*-cljs-test` files, which the fence's `suite?` predicate drops — and each requires `re-frame.hicasso.impl.codec`, `.impl.collector` and `.impl.mount`, the SDK also `re-frame.hicasso.test.runtime`. Those requires are the **harness's**, for mounting and for observation; the subjects they exercise are built on public surfaces, so this is not a breach of the bullet. The honest reading of the bullet today: mechanically held for three of the five, held by review for all five.

## Supporting specifications

The complete supporting specification set and its concern-ownership map are maintained in [`lanes/README.md`](lanes/README.md). This document does not maintain a second index.
