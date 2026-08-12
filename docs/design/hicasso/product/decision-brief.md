# Hicasso: the native adaptor — analysis, review, and plan

**The goal**: **Hicasso is the base** — an interpreted-hiccup view layer with clean ways to drop down to very efficient React for the hot parts of an app (1–2% of the code; often 0%). Good-enough performance. Excellent programming ergonomics: minimal, clean code; excellent testing; excellent Xray diagnostics. Complete coverage of the use-cases. The end state is an excellent **native re-frame2 adaptor that is better than the other adaptors** — more ergonomic, more complete.

This document is the decision-grade analysis, review, and plan. It owns the forensic scoreboard, the selected-direction record, the scoped price amendment, the K3 disposition, the sitting agenda, and the kill rules—the same split `lanes/README.md` states.

Its sibling `specification.md` is the detailed **product specification**: facade, budgets, coverage matrix, phase exits. It is maintained with the `lanes/` documents as a living normative spec set, indexed by `lanes/README.md`. This page deliberately does not restate API spellings, budget tables, or coverage rows. The two are designed to be read together. History and evidence live in companion files; neither page retells them.

---

## Part I — Analysis: what everything rests on

**1. Capability pays rent — standing per-boundary machinery is the design's mortal enemy.** A facility used by few boundaries must never add standing cost to all boundaries; optional modules are zero reachable production code when absent; the do-nothing boundary is measured whenever the shell changes. The rule is unconditional, and its evidence is mortal: two prior runtimes died of fixed per-boundary cost (a 2,430 B do-nothing wrapper; an O(reads) observation ledger) — different runtimes on different instruments, so a *mechanism-class hypothesis* rather than one pooled result, but the strongest failure signal in the record.

**2. The hot path is authored, not compiled** — a visible gradient of explicit choices, never a `:fast` flag or a second execution mode. Compilation does not rescue runtime costs: a compiled tier moved a 27.78x deficit only to 24.83x, a perfect markup compiler lands *on* the UIx twin, and the registered bounded conclusion stands — on the named large-template mount evidence, no identified admissible lever clears the 45.7–49.4% threshold of the measured deficit (reopen conditions are named in the baseline).

**3. Where the semantics live is the real architecture axis.** Four coherent placements exist, each with a price owner:

| Placement | Buys | Price owner |
|---|---|---|
| Data IR + analyzer + two emitters | Static knowledge, portable structure | Grammar, compiler, parity corpus, boundary machinery — the re-frame.ui/Freehand bill |
| **Interpreted data as real React components** | Ordinary Clojure composition, one client semantics, the React ecosystem | Runtime read collection, a small shell, codec work, Node for full SSR |
| Own renderer / whole-state renderer | A chance to change bulk/heap economics at the root | DOM semantics, invalidation topology, React as islands |
| Thin adapter over UIx | Minimal machinery, mature hooks | No ambient reads in loops/helpers; a weaker data language |

Hicasso is the second row, chosen with eyes open; the fourth row stays alive as the permanent comparator and fallback.

**4. The mount premium is priced, and the amendment is its record.** The canonical K1 record—one estimator producing point and interval together; rf2-diaud / PR #7704—stands at **1.1718x [1.1263–1.2190]** and **1.1976x [1.1504–1.2468]** versus direct UIx, both intervals above the original 1.10x gate: **K1 is missed, decisively**, and remains a miss.

About 70% of that premium is read-count *capability*: ambient `sub` in loops and helpers declares fine reads a hook twin cannot spell; per read, Hicasso is cheaper; read-matched pages run near parity. The dogfood verdict prefers the capability, and the selection is made. The **prospective, scoped amendment** is the selection's honest record — the accepted price, the use-cases it buys, the escape route, and the reconsideration trigger — drafted in Phase 0 and ratified at the sitting; the original result is never recoloured and no threshold widens.

Bulk is gated on the Reagent pair only, unresolved and instrument-limited. Heap keeps **three scoreboards, no denominator substitution**: viability against the best shipped path (Reagent, the governed K3 row); architecture progress against the UIx parent (~29% better, reported beside); and author preference (the verdict). None replaces another.

**5. Semantics live in React, so the server renders with React.** The server/hydration contract is **core, per public surface**: every view, host, root, and escape either renders deterministically with React server bytes or refuses at its source with recovery — only the deployable Node *service* is optional and caller-gated. The Node sidecar is ruled; one emitter; no JVM twin ever. And **numbers never pool across instruments** — every claim carries its witness, commit, substrate, and instrument.

**The scoreboard** (records cited in Sources):

| Axis | Where it stands |
|---|---|
| Mount (K1) | **Missed decisively**: 1.1718x / 1.1976x vs direct UIx, canonical, both intervals above 1.10x — the priced capability premium |
| Bulk (K2) | Gated on the Reagent pair only (ruled); all gated intervals straddle 1.0 — unresolved, instrument-limited |
| Per-read heap (K3) | Misses the governed Reagent row (~1.4x); beats the UIx parent by ~29% — both reported, disposition owed explicitly |
| Boundary shell (R=0) | **1,103 B / 1,097 B on the pinned segments — above the registered 1 KB paper-fail line**; a live pressure, owned by the Phase 1 substrate adjudication |
| Warm allocation | No fitted series clears the instrument's quality floor — no claim publishable yet |
| Teardown | Zero retained bytes and objects on the pinned heap arms — the proven invariant |
| Interpreter walk | Faster than stock Reagent — interpretation itself is solved |
| Controlled input | Strong in Chromium; WebKit matrix open (K4) |
| SSR | Core per-surface server/hydration contract owed; the Node spike is proven on its fixtures; the deployable service waits for its named caller |
| Correctness isolation | Eleven-risk register open — eight kernel rows plus three native-tier rows; the blocker class |

---

## Part II — Review: Hicasso against the goal

**"Better" is ordered, and a lower priority cannot buy a failure in a higher one**: correctness → ergonomics → coverage → testing & diagnosis → performance → product readiness. The product has five surfaces, of which only the interpreted core is unavoidable: the interpreted core (small fixed shell; cost follows actual reads and markup), the React bridge (a core capability, paid only at the crossing), the native hot path (paid by the selected region; absent when unused), optional libraries (zero production code when absent), and developer products (erased from production).

**The use-case compass steers everything.** Hicasso exists for ordinary data-heavy forms, lists, routes, and conditional UI; dynamic reads whose natural spelling is helpers, branches, and loops; event-data-first authoring; controlled fields under the centralized law; React libraries through the declared door; and incremental Reagent migration. These jobs are the census-grounded majority: 231 reads across 85 files, ~97% pure-data handler sites, 77 controlled fields, 106 route links, and zero view-local cells. They justify the premium.

It must still prove bulk collections, multi-root isolation, WebKit input, a complete application, and a serious vendor integration; Phases 1 and 4 exist for those jobs. Generic local state, controller runtimes, overlay managers, layout primitives, and form frameworks stay out of core until demand repeats. That is the anti-cathedral fence: universalizing rare jobs killed the predecessor. Rarity in one repository is not proof that a job does not matter, so the fitness harness remains the standing acceptance corpus.

**The interpreted base: strong and settled.** Hiccup as data, intents as data, ambient reads at point of use; the interpreter outruns stock Reagent; the dogfood screen reads 47 lines against UIx's 72 with the IME law centralized where hand-rolled code gets it wrong. Nothing in the plan touches the language. State keeps **named pressure valves** rather than one brittle rule: domain and workflow state in re-frame; drafts and control state in concern-named or optional modules; host-private geometry, focus, and imperative handles inside ordinary React hosts; no generic second store, ever.

**The drop-down: the trap door is native React, not any particular library.** UIx is the proven, ergonomic CLJS route to native React and the standing comparator, but it is not the contract and not necessarily the absolute floor. The ladder reads: **native React, with Hicasso-native and UIx as supported authoring routes**.

Raw escapes, `defhost`, and a shared frame context already make mixed subtrees feasible without a second state owner. What is missing is the **gradient** in Part III. Its most valuable unbuilt rung is direct React output from a Hicasso boundary: keep the frame, reads, memo, and lifecycle, but skip Hiccup lowering for the returned React element. The advisor must name the hot 1–2%, and the product must teach the routes between “ordinary” and “island.”

**The native tier ships in the same artifact.** An application should not acquire UIx merely to optimize one hot region. Hicasso therefore carries a small surface in a separate namespace, provisionally `re-frame.hicasso.native` as `n`. The namespace makes the semantic boundary visible and contains only:

- `n/$`, compiling one explicit element expression directly to React construction;
- `n/defcomponent`, defining a stable native component with display name, source, HMR, and one props/children ABI;
- `n/use-sub` and `n/use-frame`, joining the installed frame through shared native hooks;
- same-root bridges in both directions; and
- ABI-preserving helpers for memo, lazy loading, and refs.

Ordinary hooks come directly from React. Clojure-friendly wrappers appear only when real island code proves repeated ceremony; Hicasso does not pre-emptively clone UIx's utility library. UIx remains supported for applications already using it or whose native region grows into substantial React-first work.

The boundary is **two explicit languages, absolute and visible**: `[...]` is always interpreted Hiccup and `n/$` is always compiled native React. This does not revive macro optimization of later-interpreted Hiccup. Native props take native callbacks with no intent lowering. Controlled-input repair, structural testing, and tree diagnostics stop at the boundary. Xray still names and times it and observes reads through `n/use-sub`, while the inner React tree remains honestly opaque. Foreign libraries still normally enter through `defhost`.

The tier is real only after parity against **both UIx and handwritten React**: equivalent DOM, keys, refs, hydration, SVG, custom elements, dynamic props, and children; stable HMR and component identity; zero native runtime in bundles that never require the namespace; and performance inside the native-island parity budget.

**Ergonomics: a real win, with the facade needing curation, not growth.** The honest losses versus UIx (compile-time shape checking; event-in-hand) are recoverable as lint and the one explicit handler form. The prototype-to-product disposition (spec §3.7) prunes rather than adds: `h/fn` becomes `h/handler` with one invariant meaning; `:&` leaves the grammar for a pure merge helper; `h/reg-state` leaves the adaptor core; `subscribe-once` goes internal; presence and route-link move to their optional homes; `h/as-element` and the outward bridge (a native React parent rendering a Hicasso view under the shared frame) are the two genuinely missing conversions. Names stay provisional until their witnesses pass.

**Testing: the unshipped superpower, now with a shape.** The layered ladder (spec §9): L0 pure functions → L1 pure data/property tests → L2 a *restricted semantic-tree assertion harness* for one hook-free body (an assertion model with honest opacity — hosts, hooks, and raw React are opaque by design, missing fixtures refuse) → L3 mounted React DOM → L4 real browsers. The bench-private machinery (canonical-DOM compare, intent-script driver) becomes the supported kit; schema-driven generative testing runs as a bounded spike; every important gate carries a sabotage control.

**Diagnostics: measured but dark, with the laws already learned.** The causal lens is *event → subscriptions → values → boundaries → bodies → commit → paint*. Every link has an explicit evidence seam; unavailable facts are `unknown`, `opaque`, or `uncorrelated`, never authoritative empties. Schemas are versioned, loss is accounted for, and the machinery erases from production.

The differentiator is the **cause-aware hot-view advisor**. It ranks views by time, frequency, read churn, and fan-out; classifies pressure as computation, topology, lowering, React, or layout; and recommends native extraction only when that addresses the measured owner. It chooses the smallest route among direct `n/$`, a named Hicasso-native component, UIx, and a foreign host. The complaint catalogue—stable diagnostic ids with recovery ladders—is the cheapest proven asset to adopt. Current Xray consumes the Freehand tooling tier, while `re-frame.ui.tool` survives only in fixtures, so the glass builds on the live tier.

**Completeness: named gaps under one coverage rule.** Every recurring job gets a maintained answer at a named layer: core default, optional module, recipe, host escape, or didactic refusal with recovery. The full matrix is spec §7.

The biggest gaps sit on high-traffic jobs: forms, overlays on the native top layer, the testing kit, the glass, async resource demand, code splitting, and modern-React conduct. The forms answer begins with a buffered-draft field that removes five hand-rolled trap classes. For code splitting, current Hicasso mints React functions and has **no** late-bound view-id registry; a small `React.lazy` boundary-ABI bridge is the default over existing fact, while a registry would be new work needing its own justification. The release matrix covers Activity hide/reveal, the internal `useSyncExternalStore` seam, and hydration from React server bytes.

The strategic differentiator among the gaps is **demand-driven resource ownership**: a committed `sub` that reads a resource may also declare demand — unmount or parameter change releases it; debounce, supersession, refresh-with-data, and cancellation remain explicit policies. Its fences are hard (reuse committed read membership; abandoned renders acquire nothing; no second per-read ledger — the failed-ledger mechanism class must not reappear here) and a typeahead witness decides whether it graduates.

**Trust: the one blocker class.** The trust register in `lanes/adversarial-risks.md` holds eleven risks. Eight belong to the kernel: module-global ownership including the shared hydration adoption window; same-id reincarnation dispatch; speculative-render leakage and false abandonment tests; the unstated ambient-read extent; controlled-input portability; HMR identity; callback retirement; and hydration isolation.

Three belong to the native tier: native-language leakage (`n/$` never rewrites interpreted Hiccup), boundary-ABI drift across the three authoring routes, and native-tier rent (zero native runtime in interpreted-only bundles). “Better than the other adaptors” begins at “as trustworthy as them.”

---

## Part III — The plan

Phases with exits (delivery in vertical slices — testing and Xray land *with* the behavior they explain, never in a polish phase). This is a **map of future work, not a case for a verdict**: sequencing follows dependency, proof obligation, and named callers. Two standing rules govern scope: most of the programme's value — the kit, the lint, the glass laws, the recipes, the requirements corpus — holds even if the amendment's reconsideration trigger ever fires; and speculative surfaces wait for their named caller.

**Phase 0 — freeze and product spine**. Freeze semantic expansion. One-page invariant/capability ledger and the provisional facade; the core/optional/host/recipe/refusal classification for every coverage row; ratified budgets on named reference hardware; an installable `implementation/hicasso` package a clean consumer can compile, hot-reload, and production-build; a server/hydration disposition for every proposed public surface (owed regardless of whether the Node service is ever activated); stable error shape with source coordinates. Exit: a minimal app runs on public namespaces; no option selects an execution mode.

**Phase 1 — the trustworthy kernel** *(blocking)*. Close the eight kernel rows of the eleven-risk trust register; the three native-tier rows close with Phase 3's adoption gates. Apply the gate rule—published populations, sabotage mutations, residue before reset—to render-probes/commit-owns; abandonment and retry under concurrent roots; multi-root and frame isolation; same-id reincarnation dispatch; Activity and Suspense; HMR; controlled input across Chromium, Firefox, and WebKit; and every mutable global.

The **substrate adjudication** explicitly chooses the under-collector subscription substrate using correctness, retained cost, clock, teardown, and migration evidence rather than an inherited default. It also freezes the two-hook boundary ceiling before the runtime ABI stabilizes. Exit: zero stale reads, cross-frame operations, tears, or residue.

**Phase 2 — one lovable vertical slice**. A small complete flow (routing, keyed list, edit, async mutation, controlled fields, errors) on the proposed API only — shipped together with the L0–L3 testing facade, the first versioned evidence projection, the Xray mounted/read/intent/explain-render views, the complaint catalogue, the first lint checks, and production-erasure proof. Exit: the app has no artificial boundaries; the ordinary facade freezes from this evidence.

**Phase 3 — the hot path made excellent**. The five-rung gradient, delivered and taught:

1. *Ordinary Hicasso* — the default.
2. *Tune topology* — boundary placement, keys, and read shape (fine row reads for sparse updates; a coarse view-model for cheap mount and bulk; chunked/windowed reads; virtualization when the DOM shouldn't exist in full). Xray shows boundary count, reads, fan-out, churn.
3. *Direct React output* — a `defview` returns a React element, authored with `n/$`; frame, reads, memo, and lifecycle stay; hiccup lowering is skipped for that result. The narrowest escape, for when lowering is the measured owner.
4. *Named native island* — hooks, vendors, drag/animation, hot collections as a native component (`n/defcomponent` with `react` hooks, or UIx); same root, same frame, `n/use-frame`/`n/use-sub`; explicit crossing contract.
5. *Native screen* — an intrinsically React-first screen authored natively (Hicasso-native or UIx) under the single installed adaptor; an independent root remains an isolation choice only.

Plus the working loop (reproduce → attribute with Xray → tune topology → try direct output → isolate an island → re-verify parity and budget; the escape stays only if it clears its declared threshold), the cause-aware advisor, the completed `defhost`/portal/outward-bridge contracts, the native tier delivered against its Part II adoption gates, and the bounded experiment set (direct-return delta; topology tournament; retaining-host callback identity; one causal Xray slice; outward-bridge parity; the native-tier three-way parity — `n/$` vs UIx vs handwritten React) — with **no open-ended benchmark programme after it**. Exit: a measured hot boundary moves to native React without a second root or state owner and is diagnosed from the same Xray surface.

**Phase 4 — close the coverage matrix**. Every spec §7 row points to running evidence, an installable module, a tested recipe, or an explicit non-goal with a React escape — including pagination, code splitting through the lazy boundary bridge, navigation focus/scroll and prefetch across hover/focus/touch, accessibility assertions, autofill/reset/FormData, all named control types, the typeahead resource-demand witness, the published per-keystroke mechanics for the editor and grid (state writes → recomputations → boundary runs → commit → visible echo; a witness that doubles as teaching documentation), React server bytes and hydration proven across every public surface, and a second screen with a serious vendor component.

**Phase 5 — optional products, in priority order**. Decide the committed-read resource-demand spike from the Phase 4 typeahead witness — an explicit adopt or stop, first, because it gates the shape of the async-resource recipes below; forms (recipes, then the buffered-draft helper — **which is no longer Phase 5: the operator ruled the `re-frame.hicasso.forms` module into V0 scope on 2026-08-12, with `draft-guide/05-forms.md` standing as its draft spec, and `rf2-sh56` shipped it. That overrides the second-caller gate for this feature only; the gate stands everywhere else, and every other item in this phase is unaffected**); popover + modal on native top-layer primitives; presence/motion posture and the focus intent; routing and async-resource recipes (settle-merge, mutation status, dirty-nav guard); migration reporter → shadow comparison (the dual-render canonical-DOM/intent diff as a consumer verifier) → only then cautious codemod transforms; the Node SSR service when a named caller activates it (**that condition is removed — see the amendment below**); the left-field spikes that meet their deciding rules.

**Amended 2026-08-12 by operator ruling (Mike, in session, 17:36 AUSEST; `rf2-xpq9`): every item in this phase is V0 SCOPE — v0 is not done until they are completed.** The priority order above and the deciding rules stand; what changes is that these products are no longer held past v0. **Feature-shaped** items — popover + modal, presence/motion posture and focus intent, routing and async-resource recipes, migration reporter → shadow comparison, and the Node SSR service — are built, witnessed and shipped in v0; forms was ruled in the same day by `rf2-sh56` and has shipped. **Decision-shaped** items — the committed-read resource-demand spike and the left-field spikes — are complete when the spike runs and its pre-registered verdict is made, and **a ruled STOP completes the item: completion is the verdict, never forced adoption**, so nothing here converts a spike into an obligation to adopt. One clause is superseded and one is retained: the Node SSR service's *"when a named caller activates it"* is **REMOVED**, and `rf2-hic-056` builds it in v0 to its own spec — immutable request snapshots, allowlisted state, build identity, bounded isolate concurrency, hard termination, and a pre-registered caller latency envelope — while migration's *"only then cautious codemod transforms"* sequencing is **RETAINED**, now running inside v0. The fences hold: every optional namespace still proves zero reachable production code when absent, and no optional feature changes the boundary shell. Part III's standing rule that *speculative surfaces wait for their named caller* is not amended here and still governs surfaces outside this phase; the ruling lifts it from this one service only.

**Phase 6 — adoption and release**. Versioned artifacts, compatibility matrix, upgrade policy, cookbook, performance method, escape criteria; **one taught native story** — the experimental donor surfaces and their docs explicitly dispositioned so users never face three overlapping view models; two pilot applications shipping substantial screens without framework-author intervention. The definition of done is spec §13.

**The performance contract** *(detail in spec §6)* starts with user-visible budgets: controlled echo within one frame; discrete interactions to paint in 50 ms p95; broad operations in 100 ms p95; narrow work scaling with changed rows; and zero teardown residue.

Comparative bands follow: an initial mount ceiling of 1.25x direct UIx; tuned broad updates at ≤1.25x the best relevant adapter, with 1.25–1.5x a warning and >1.5x forcing island analysis or reclassification; and native islands within 5% or 1 ms of the same component mounted directly through its chosen React route. Hicasso-native is co-instrumented against handwritten React and UIx. The same-instrument regression gate is 5%; an escape earns its keep at ≥20%, ≥2 ms, or by flipping a user-visible budget. Native-code percentage is **an observed census, never a quota**. Budgets ratify on named hardware before optimization; thresholds never widen to turn a row green.

**The ideas ledger** (full designs in `lanes/left-field-ideas.md`, and in its operator-local source `fable/left-field-ideas.md`, which is not published in this tree):

- *Adopt*: complaint catalogue, cause-aware hot advisor, capability receipts (counts only; self time refused), direct React output with the Hicasso-owned native tier (`n/$` and companions; gates in Part II).
- *Spike with deciding rules*: committed-read resource demand; pull-shaped reads; schema-driven generators; counterfactual topology advice; replayable view capsules after L2; MCP-queryable runtime and migration shadowing through existing tools; shared read-set notification groups after a census.
- *Watch*: a read-free shell as a read-nothing optimization, not a heap fix; codec shape planning only when classification/lowering exceeds 10% of hot-boundary self time; intent replay through Story; keyed-list maintenance only after a red bulk verdict; a future React store seam.
- *Reject*: compiler/JIT modes, second renderers, worker view runtimes, signals replacement, universal callback cells, and hydration-free inference.

Every spike must retain the detailed fence and deciding witness in `lanes/left-field-ideas.md`; this ledger is not permission to build an open-ended experiment.

**First moves now**: the reincarnation-dispatch witness; the `hicasso.test` extraction; the installable package spine; the hot-view advisor; the forms field recipe; the first lint checks; and at the sitting — the K3 disposition and the amendment that names the accepted mount price.

**Kill rules**: no compiler or dual mode for the hiccup language (`n/$` is a visibly distinct second language, never a second mode of `[...]`), no ViewCell-class graph, no second emitter, for any gate. Bulk above its kill line after the allowed iterations stops or narrows. Standing cost without a paying use-case is refused. Tools without daily consumers build no retained machinery. Thresholds never widen.

---

## The sitting (2026-08-27) and this document

The direction is selected, so the sitting is a **recording and dispositioning session, not a go/no-go**. Its business is to ratify the price-acceptance amendment as the formal record of the decisive K1 miss: frozen original criterion, purchased use-cases, escape route, and reconsideration trigger. The held instrument is exactly this. It also takes the K3 disposition and assigns the open proof obligations—correctness, bulk, WebKit, and application witnesses—as execution risks inside the selected direction, each with an owner and a phase.

The residual governance is the amendment's reconsideration trigger. The plan is robust under it: the kit, lint, glass laws, recipes, and requirements corpus retain their value regardless.

## Sources

`specification.md` (the product specification; §6 budgets, §7 coverage, §9 testing, §13 done) and the `lanes/` specification set. Product evidence and rationale: `lanes/evidence-baseline.md` and `lanes/corpus-insights.md`. Source corpus — operator-local, deliberately not published here, and nothing in this tree substitutes for it: the `fable/` scout corpus, its three `lessons-*.md` files, and `fable/dispositions.md`, which carries the current status of every scout idea (a different ledger from this directory's `dispositions.md`). Normative implementation record: `docs/design/hicasso/` and the governance beads (K1: rf2-diaud / PR #7704; bulk gating: rf2-vp0j7; SSR: rf2-2rtt6.88).
