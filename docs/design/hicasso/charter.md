# Hicasso — charter

**Hicasso is re-frame2's native view layer: interpreted Hiccup on a modern React
function-component host, optimised for re-frame2.** This charter states the
identity, evidence base, goals, constraints, and scope. Decisions are normative in
[decisions.md](decisions.md); the runtime shape in
[architecture.md](architecture.md); the proof plan in [validation.md](validation.md);
the programmer-facing surface in [authoring.md](authoring.md).

## The name

| | |
|---|---|
| **Product** | **Hicasso** — Hiccup + Picasso, one letter apart |
| **Namespace** | `re-frame.hicasso` · artifact `io.github.day8/re-frame2-hicasso` |
| **Alias** | `h` (deliberately not Freehand's `v`, not `ui`) |
| **One-liner** | *Hicasso — Hiccup views for re-frame2.* |

```clojure
(:require [re-frame.hicasso :as h :refer [defview sub]])
(h/defview cart-badge [_] [:span.badge (sub [:cart/count])])
```

The wordplay is the product claim: Picasso's single-line freehand drawings — a
complete figure in one unbroken pass — are one-pass interpreted rendering; Cubism's
decomposition into primitive forms is hiccup's decomposition of UI into vectors and
maps. Verified unclaimed on Clojars, GitHub, and npm; single-c spelling is
canonical.

## Product identity

> A better, faster, interpreted, Hiccup-based view layer — a better UIx with hiccup
> interpretation, not a better Reagent. "Like Reagent" survives as authoring
> familiarity, not as runtime parent.

- **Interpreted Hiccup is the product delta, not the whole runtime.** Interpretation
  itself measures ~1.2× — never the deficit. What UIx users miss from Reagent —
  markup as pure data, plain `defn` helpers returning data, intent-as-data in
  attributes, structural tests on trees — is exactly what the Hiccup layer
  restores, without a reaction engine.
- **Accepting React's model deletes whole problem classes**: StrictMode and
  concurrent correctness, library interop, and the end-of-discrete-event
  controlled-input restore come from the host.
- **The specialization advantage**: in re-frame2, subscriptions are the only
  reactive source and the commit is the only write clock. Every comparable system
  (Solid, Svelte, Vapor, Preact signals) had to build a scheduler; Hicasso gets one
  free — a structural edge Reagent and UIx cannot copy without becoming re-frame2.
- **There is no compiler, no analyzer, no second mode, no ViewCell, no reaction
  graph, and no second app-facing state model.** The predecessors' post-mortems
  are design inputs, not baggage: the per-boundary shell and the per-read
  dependency ledger — the two measured killers — are omitted as *designed
  mechanisms* and their residual costs re-priced under explicit budgets, with
  tripwires rather than intentions (boundary-exclusive retention is inventoried
  and priced, never claimed absent; see [validation.md](validation.md)).

## The measured evidence base

- The predecessor's bulk-update deficit is **architectural**: ≈55% the per-read
  dependency ledger, ≈22–25% the per-boundary shell; a single-boundary ablation
  still reads ≈9×. Budgets are therefore read-shaped as well as boundary-shaped.
- Retained memory across substrates: Freehand ≈5.9×/4.2× Reagent and ≈9.7× UIx per
  boundary. React Compiler is memory-neutral, so memory is the native layer's most
  winnable axis.
- Boundary rungs (never sum across instruments): Freehand full boundary ≈2,430 B;
  Reagent sub-free ≈418 B, reactive ≈1,037 B; **UIx ≈251 B at 1.163× mount — the
  frontier**; an isolated `useSyncExternalStore` rung ≈516 B. There is **no
  complete like-for-like reactive UIx baseline** — the 516-vs-251 tension is a
  reason to measure the real arms (1/3/7/20 reads, directly), not to reject hooks
  in advance.
- The like-for-like arms (Reagent-on-subs, UIx-on-subs, the write+flush leg)
  have **no numbers yet — the ship bar is currently unbuilt**; their instrument
  specifications live on the *closed* beads rf2-mapni / rf2-m7xs7 / rf2-ssn1o
  (closed 2026-07-30 with the retired Freehand standard, marked do-not-refile;
  their close notes direct exactly this: a new standard against the new
  substrate). Wave 0 files **fresh beads** under EP-0038 using those closed
  beads as specification donors. Building the bar is phase P0.
- The W1 interpreted-mount discrepancy is **resolved** in the tracked record
  (`docs/design/freehand/studio/bulk-rerender-where-the-time-goes.md`,
  appendix): 2.987 is the operative figure; 1.904 was a different witness and
  mount door with no Reagent arm; a clean re-run reproduced 3.075. P0 carries
  ≈2.99–3.08× floor / ≈1.9–2.0× Reagent into the baseline table; only the
  dominance attribution remains open, as its own bead.
- React Compiler: stable and default-on for new JS apps, **no CLJS path**, and it
  never removes external-store re-renders — a standing watch item, not a baseline.
- The subscription→boundary index laws are proven in isolation (the spike-01 pure
  model), including conditional-read edge diffing.

## Goals

1. **The bar as entry gate** — ship ≤ 1.0× Reagent like-for-like on the clock
   (mount and bulk), with memory first-class through the kill rules (HD-012),
   CI-gated on the witnesses; UIx co-instrumented. The gate lives in an
   operator-owned standard bead with the kill bound and decider pre-registered:
   prose gates have historically been disposed of by ruling; beads survive.
2. **Same authoring model; outcome-keeps only.** The model was independently
   re-derived from requirements alone, and no correctness failure was ever
   recorded against it. No keep-lists as architecture. Candidates for
   micro-mechanic copying: the intent-marker roster and its one pure materializer,
   the controlled-door predicate, the `composing?`/keyCode-229 knowledge, the
   top-layer pair, the form and controls kits (runtime-free), the
   no-unmount-callback doctrine, route-link, the error-boundary shape. Salvaged
   conformance rows re-pin observables, never mechanisms.
3. **Budgets on paper before code** — boundary-shaped, read-shaped,
   keystroke-shaped, template-identity ([validation.md](validation.md)).
4. **Consumer code from day one, with an ergonomics kill-gate symmetric to the
   perf gate**: the dogfood screen and five-shape ports judged on their diff;
   public-door-only witness tests; more than ~8 public concepts or ~8 guide pages
   to ship CRUD is a kill signal.
   **Amended 2026-08-04 (operator ruling):** that last clause is the criterion
   [validation.md](validation.md) carried as **K5**, and K5 was removed. The
   sentence above stands as the record of what this charter pre-registered.
   Consumer code from day one, the diff judgement and the public-door-only
   witness tests are unchanged — only the ergonomics *kill* is gone, and nothing
   replaces it.
5. **Keep what's differentiating**: intent-as-data; the controlled door;
   browserless testing; demand-driven reads; zero-cost-when-absent tooling; the
   queryable registry; AI ergonomics throughout.

## Constraints

- **No analyzer.** Permitted: `defn`-class local macro sugar with function
  fallbacks (`defview`; a `for`-lowering that turns the binding into the `:key`)
  and runtime codec caching. Forbidden: any build-time pass that classifies,
  lowers, or refuses body forms; any grammar; any proof system; any second body
  emitter. If a compiler or dual mode becomes *required* to meet the bar, that is
  a kill, not a feature.
  **Amended 2026-08-04 (design + adversarial ruling, operator-overturnable):**
  the `for`-lowering named above is ruled out on the evidence to date and retired
  behind a door rather than deleted. The shelved shape is a scalar-refusing
  `h/for`, and its trigger is the dogfood preference test failing specifically on
  list ceremony; until then authors write `:key` themselves. The permission above
  stands as the record of what this charter pre-registered.
- **One mode.** No compiled tier, no promotion knob.
- **The anti-regression fence** — the programme has failed back into its
  predecessors if any of these appear: dual authoring modes or a public compiler
  identity; dual teaching names; a second app-facing state model; a semantic
  controller/behavior/command DSL as core product; a per-boundary shell that
  cannot meet ≈Reagent retained cost; success defined as donor absorption or
  conformance-green rather than dogfood preference plus speed; programme staging
  before a lovable slice. Banned vocabulary until a spike proves need: absorption
  (as programme), ViewCell (as public concept), "same semantic model" (as a
  continuity claim), "simplify Freehand in place" (as the fork).
- **Kill criteria** ([validation.md](validation.md)) — any tripping means stop or
  narrow; adapters-only is a *successful* outcome.
- **Tier-1 syntax only for the five census-proven shapes; rare shapes get escape
  hatches; no layout DSL.**
- **End state:** on a win, the public Freehand and re-frame.ui surfaces are
  deleted and **Hicasso is the one taught story**; on a loss, adapters plus
  status-quo donors — never three living stories.

## Use cases

The full roster is the product's definition of done — the existing witness corpus
re-pointed at Hicasso, green. **v0 is deliberately narrower**: the five tier-1
shapes beautiful; controlled input R-A1/R-A2; mount inside the mount gate and bulk
≤ Reagent on the witness shapes; one host hatch proven; a dogfood list+form screen
preferred over raw UIx by its authors; the event/sub loop not regressed; a short
guide. **SSR is explicitly out of v0** (HD-020: bodies stay `.cljc`-compatible by
construction; no JVM/SSR render path ships). Deferred past v0: the full
buffered/revision input ladder, overlay excellence, batteries/library platform,
SSR as identity, devtools glass, per-keystroke envelopes as a gate.

**Amended 2026-08-04 (operator ruling):** the out-of-v0 SSR posture above is
superseded — **SSR + hydration is required Hicasso scope** ("hicasso is useless
unless it does SSR"), through the framework's own Spec 011 story, recorded as
the HD-020 addendum in [decisions.md](decisions.md) and the same-date EP-0038
addendum. The sentences above stand as the record of what v0 pre-registered.
SSR *speed* stays off the bar, and "SSR as identity" stays in the deferred
list — SSR is a required capability, not the product's identity. Four pieces have
since landed on the now-closed beads `rf2-2rtt6.84`–`.87`: the hydration door,
the `defhost` `:ssr` policy, the Node render entry, and the X1–X5 spike witness.

**Amended 2026-08-05 (`rf2-hyd50`, operator-acked 2026-08-07):** the v0 bar
clause above is restated per-axis. It read, verbatim: `mount + bulk ≤ Reagent on
the witness shapes`. The mount gate it now names is **≤ 1.10× direct
UIx-on-subs** — canonical `M1`, floor-normalised, on the clock of record — with
Reagent-on-subs co-instrumented and **reported beside the mount row rather than
gating it**; bulk stays **≤ 1.0× Reagent-on-subs, like-for-like**, unchanged.
This is consistency propagation of a ruling already carried by
[validation.md](validation.md) and HD-012's addendum in
[decisions.md](decisions.md), not a new one. That ruling is delegated and
**operator-overturnable**: if it is overturned, this note reverts with it and the
frozen clause is the live bar again.

**A. The everyday SPA spine** (where the bar lives)
1. Ordinary views — the ~50-element form/list/layout shape.
2. Large templates — the ~1,200-element shape.
3. Bulk re-render — ~300 boundaries on one commit; the make-or-break row.
4. Narrow update — one cell moves in a large mounted page.

**B. Input — the hard correctness core** (never regress)
5. Controlled text through the synchronous door: zero dropped keystrokes,
   caret/IME honesty (the `composing?`/keyCode-229 laws). The runtime-owned
   controlled-restore obligation applies only to a back end that leaves React's
   discrete-event path — hard-gated in any PATCH work. **No such back end
   remains**: Arm 2 was withdrawn 2026-07-31, so the clause is dormant rather
   than live, and the adapter takes React's own end-of-event restore (with the
   caveat on [the two implementations](studio/controlled-input-two-implementations.md)).
6. Forms at scale: per-leaf narrow reads, buffered/revision fields, validation and
   submit lifecycle, the 100-cell editing grid. (Post-v0.)

**C. Component-library authorship** — through the public door only (post-v0 except
as the dogfood demands)
7. The three proven control classes buildable without internal requires: typeahead
   (anchored popover, async correlation/supersession), splitter (pointer capture,
   host-rate drag vs semantic commits), virtual collection (10K rows, keyed
   identity across scroll, focus continuity, aria honesty, a controlled
   scroll-offset that moves a live viewport). Rows as ordinary boundaries is the
   design intent the budget must make affordable.
8. Overlays on the native `<dialog>`/popover floor: measure-before-first-paint, no
   global listeners, nested toggles, honest dismissal.

**D. Living in the React ecosystem** — inherited from the React parent
9. Hosting React libraries via the one door (`defhost`): value/callback,
   hook/context/ref/compound owners, React-owned animation.
10. Hosting imperative SDKs at the host edge (ordinary React refs/effects per
    HD-003; a richer host-ownership pattern is product-phase): Maps-class root
    ownership, idempotent reclamation, deferred foreign handles.
11. Embeddable the other way in a React-primary app.

**E. The host boundary**
12. High-rate hosts: the two-clock envelope (30–240 Hz), host-local motion off
    app-db, no dropped input.
13. Imperative DOM with total cleanup — the zero-leak law.

**F. App-scale integration**
14. SSR + hydration surviving production posture. 15. Routing (route-link is
tier-1). 16. Multi-frame isolation. 17. Hot reload with clean remount semantics.
18. Time travel, including mounted host state.

**Amended 2026-08-04 (operator ruling):** item 14 is no longer waiting past
v0 — SSR + hydration is required Hicasso scope, per the same-date note under
the v0 scope paragraph above and the HD-020 addendum in
[decisions.md](decisions.md).

**G. Proof and production posture**
19. Testable without a browser through the public door; structural tests on data
    trees.
20. Tooling attached = rich (explain-render, manifests, presence); absent = zero
    cost.
21. Production build identity: `:advanced` changes cost, never semantics; the
    staged-stale case is a CI witness for any asynchronous-host variant.

**Named goal — demand-driven resource reads** (post-v0; decided at design time so
v0 doesn't preclude it): a mounted read as the causal owner of ensure-demand;
release on unmount/param-shift/conditional-false; debounce/supersession as
declaration policy; ensures post-commit; render pure; demand ≠ retention. Both a
differentiator no comparable system has and the only clean home for typeahead-class
async.

## What the fitness harness supplies

The requirements harness — tracked at
`docs/design/freehand/studio/fitness-harness.md` (the sanitized, content-identical
copy of the exploration original) — is a
**requirements mine, not a redesign brief**: v0 is judged on the five tier-1 shapes
plus core input; the full R-A/R-B/R-C cell-by-cell gauntlet and the buffered-input
pin list apply at product phase. The census names **the five tier-1 shapes** (the phrase every gate leans on): the
sub-read view body; intent-vector events with the value placeholder; the
controlled input; keyed lists; the route-link. Census facts that bind tier-1
design: 231
subscription reads across 85 idiomatic files; ~97% of the 183 handler sites are one
value-placeholder from pure data; 77 controlled controls; 48 `for`s / 35 keys; 106
route-links; **zero view-local reactive cells**; framework subs are **27% of read
traffic** (the index serves them first-class); refs appear once in 85 files and
portals/foreign components/stopPropagation/observers zero — escape hatches, never
tier-1 syntax; **no layout DSL**. Census-weighted policy defaults belong in the
acceptance instrument: `:on-submit` intents auto-prevent; a data key-map with the
composition-gated Enter/Escape law centralised in the runtime.

## Why Hicasso should fare better than its predecessors

1. **It starts from the autopsy they paid for.** The killers are measured — the
   per-boundary shell and the per-read ledger — and the design omits them, with
   tripwires rather than intentions.
2. **The identity bet sits on the measured frontier.** The runtime parent is the
   fastest arm in the repo (a UIx-class React FC), and the product delta is only
   what users demonstrably miss: hiccup-as-data and intent-as-data. The riskiest
   component of both prior designs — a second reactive machine inside React — is
   deleted, not improved.
3. **The bar exists before the code.** Instruments first; CI witnesses from the
   first mount-path commit; paper budgets that must pass before code. A deficit
   would surface in week one, not at 90% built.
4. **"Better" is falsifiable.** The null control rides every measurement; the
   kill-path is a success outcome; six weeks; red gates shrink scope; delete on
   win rather than absorb.
5. **The language is kept; only the economics change** — the smallest possible
   change to a system that was right about everything except cost.
6. **Two mechanisms nobody nearby has**: commit-clock reactivity (no scheduler to
   build) and arm-scoped interpreter accelerants.

None of this guarantees success: the inside-React bulk ceiling is real, and the
true bar is unmeasured. The difference is that failure is now cheap, early,
dignified, and informative.

## Known losses coming from Reagent

Deliberate trades, with compensations: boundary discipline replaces
split-a-component tuning (`r/track`, reactions, and cursors are gone — the subs
layer is the tool); `@`-anywhere is gone (`sub` is render-scoped; handlers use
cofx or `subscribe-once`); React's truths are visible (StrictMode, keys-as-props,
hooks at host edges); quick interop is declare-first
([authoring.md](authoring.md)). Maturity gaps that time closes or the kill gates
expose: the input long tail beyond R-A1/A2, and the unproven bulk/narrow story
where Reagent is proven. The three design debts this record resolves explicitly:
ephemeral-state ceremony (HD-009), per-keystroke economics (the named
instrumentation in [validation.md](validation.md)), and the reusable-widget
instance-key convention (post-v0, named in HD-009's sugar).
**Amended 2026-08-04 (operator ruling):** the third debt is no longer post-v0.
The instance-key convention and HD-009's sugar were ruled **into v0** — designed
as `h/reg-state` plus the explicit-key rules, recorded in HD-009's dated
addendum in [decisions.md](decisions.md). The sentence above stands as the
record of what this charter deferred; the deferral itself is over.
