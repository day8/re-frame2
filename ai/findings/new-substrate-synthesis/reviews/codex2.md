# Independent implementation review — `re-frame.ui` substrate synthesis

**Date:** 2026-07-12  
**Scope:** `ai/findings/new-substrate-synthesis/`, including docs 01–12, `guide/`,
`drafts/`, the five spike results represented by the two spike reports, the checked-in
Spec 004/006 anchor text, and the three preceding reviews. The decisions recorded in
08 §5 are treated as fixed; this review tests whether their finalized expression is
coherent and implementable, not whether different preferences would be nicer.

## Executive summary

- The architecture remains strong: compiled views, commit-owned observation, data handlers, dual-host output, early Xray consumption, and production erasure reinforce one another.
- The suite is not yet precise enough to dispatch Stage 1 and Stage 2 implementation beads without implementers making architecture on the fly.
- The principal blockers are the unreconciled observation-port ABI, the absent public structural-tree contract, the unbound root-manifest identity, and an undefined meaning of “Stage-1-conforming” for the full Spec-004 rewrite.
- Doc 12 is a useful epic sketch, but its API freeze and stage map contradict the suite in several places and do not yet form a leaf-bead plan.
- The stock-Reagent compatibility decision is consistently stated as the headline, but its normative home and two-way incremental co-mount path are not specified.
- The spike evidence supports the central design, but some “PASS” and “settled” wording exceeds the actual fixtures, especially S-4 root isolation, the real sub-cache graft, and real-browser input behavior.
- The interim Spec-004 amendment is mechanically merge-ready: all nine quoted old blocks still occur exactly once in checked-in Spec 004.
- The Spec-006 amendment’s anchors have not drifted, but the amendment itself is pre-spike and must be rewritten before merge.

## Findings

### 1. BLOCKER — The post-spike observation-port contract has three incompatible shapes

**Citations:** 03 §3 “The target” and “The port”; `spikes/s3-ownership-report.md` §5;
`drafts/spec-006-observation-port-amendment.md` “Observation targets,” “Candidate
signatures,” and its open-shape list; checked-in Spec 006 “Lifetime contract,”
`subscribe-once`, and “unknown sub.”

03 correctly adopts the spike’s most important discoveries—no captured node handle,
lease identity as the owner token, and slice-scoped memoization—but does not adopt the
spike’s actual ABI:

- 03’s example calls one map both a target and “evidence”; it puts `:value`, `:version`,
  and epochs on a subscription target. The spike separates a stable target
  `{:kind :subscription :frame-id ... :query ...}` from probe evidence containing the
  value, node key/version, liveness, and epochs.
- 03 exposes `(probe-sub frame-id query)` and `(acquire-sub! frame-id query on-change)`.
  The spike requires a single render-time `resolve-target`, then `(probe target
  ?slice-memo)` and `(acquire! target on-change)`. The distinction is load-bearing for
  Story overrides: commit must acquire the already-resolved override target rather than
  resolve context again.
- The spike needs `(current? lease target)` to retain an unchanged live lease and to
  reject a disposed HMR node. It is absent from 03 and the Spec-006 draft.
- The spike defines a uniform static lease for Story overrides. Neither displayed port
  in 03 nor the draft can express it cleanly.
- The Spec-006 draft still carries a node handle in the target, an independent `owner`
  argument, a render-pass token, “acquire verbatim” semantics, and a list of questions
  which the spike answered. Its heading still says the shapes are provisional pending
  S-3.
- The spike throws `:rf.error/no-sub` and `:rf.error/frame-destroyed` from internal
  acquire. Checked-in Spec 006 uses `:rf.error/no-such-sub` and public recovery-to-`nil`
  semantics. An internal fail-loud port can intentionally differ from the public API,
  but the distinction and the one-catalogue error id have not been specified.

There is also an unhandled transactional case in the spike recommendation. “Acquire
before release” preserves the old dependency set if the *first* new acquisition fails,
but not if acquisition 1 and 2 succeed and acquisition 3 throws. Without rollback, the
first two provisional leases leak. The commit contract must say that all new/retargeted
leases are staged, and on any failure every newly acquired lease is synchronously
released while the prior committed set remains installed.

**Required adjustment:** make the spike §5 target/evidence/lease model the sole shape
source; add `resolve-target` and `current?`; specify static-override leases, transactional
multi-acquire rollback, callback/reentrancy rules, and internal-vs-public error behavior;
then rewrite both 03 §3 and the Spec-006 amendment. Also name the actual namespace or
protocol by which the separate UI artifact consumes this “internal” core port. Stage 2
cannot be implemented safely before this reconciliation.

### 2. BLOCKER — Stage 1 has no normative structural-tree ABI, and the draft test ABI conflicts with the spike

**Citations:** 02 §2; 06 §1; 07 §2 and §4; `drafts/spec-004-rewrite-draft.md` “The
portability law,” “Template grammar,” and “The JVM structural subset”;
`drafts/ui-test-selector-grammar.md` “What `find` returns” and OPEN-1;
`spikes/s1-codegen-report.md` §S-4.

The private compiler AST may remain an implementation detail. The JVM structural tree
cannot: it is returned by `ui.test/render`, queried by users, carries event vectors,
feeds parity/fingerprint logic, and is consumed by the existing SSR artifact. No
document defines its node variants, field names, tag/namespace representation, child
normalization, event storage, trusted-HTML node, fragment/view-boundary representation,
or schema version.

The only concrete shape is the throwaway spike’s example:
`{:tag "li" :attrs {...} :events {:on-change ...} :children [...]}`. The selector draft
instead promises that a returned node is plain data where `(:on-click node)` directly
reads an attribute/handler and `ui.test/attrs` returns the combined map. A nested
`:events` map and direct keyword lookup cannot both be the public contract without a
wrapper or a different node shape. The selector grammar also needs a view-boundary slot,
but leaves fragment-rooted and nil-rooted views open. Stage 1 nevertheless includes
view-id selectors.

The same gap exists one level earlier. The suite repeatedly promises one “contextual,
total” DOM conversion table, but supplies examples rather than the table. The engineer
still has to invent the SVG/MathML namespace rules, custom-element property rules, the
complete boolean/overloaded-boolean set, style unitless/custom-property behavior,
`:class`/`:id` sugar precedence, attribute validation, child coalescing, and the exact
semantic normalization used for parity and fingerprints. The spike report explicitly
recommends promoting its discovered rows into the normative table; that promotion has
not happened.

Finally, “consumed by the existing `re-frame2-ssr` artifact” is not an integration
contract. Today’s SSR surface consumes the checked-in render-tree/hiccup contract. The
owner and signature of the new-tree-to-SSR boundary are absent.

**Required adjustment:** before a Stage-1 code bead, freeze (a) a versioned public JVM
tree schema and projection behavior, (b) the semantic normalization/fingerprint input,
(c) the DOM/SVG/MathML/custom-element conversion table, and (d) the SSR consumption
boundary. Resolve selector OPEN-1 and decide whether nodes are raw maps, opaque values
with projections, or maps whose attrs are directly addressable. Keep the optimizer AST
private if desired; do not accidentally freeze it just to fix the public tree.

### 3. BLOCKER — A required `root-id` and root manifest cannot be supplied by any documented mount form

**Citations:** 02 §6 “Roots”; 06 §2; 08 §2 Stage 1; 12 §2 and §3 S1/S5;
`drafts/spec-004-rewrite-draft.md` “Roots and mounting.”

06 makes root identity load-bearing: every root manifest needs a unique `:root-id`, an
element locator, view id, props, referenced frame-payload ids, two digests, an identifier
prefix, and phase; mount position is explicitly not identity. Yet the authoring surface
is only `(ui/mount root-form dom-node)`, while host root options are listed as React error
callbacks and id prefix. There is nowhere to provide a root id, and no derivation rule.

Consequently, Stage 1’s promised “root descriptor” is underdetermined:

- Is `root-id` a required literal mount option, compiler-derived from a Var/site, or
  host-supplied manifest data?
- Is `element-locator` derived from the runtime DOM node, authored for SSR, or generated?
- How are `view-id`, serialized props, and transitive frame-payload ids extracted from a
  root form?
- What detects duplicate root ids across compilation units or independently rendered
  page fragments?
- What happens when two roots reference the same payload id with different payload
  contents or frame configuration?
- What identity/defaults apply to a client-only non-hydrating mount?

12 postpones “root manifests” to S5 while 08 requires a root descriptor in S1, but does
not define the smaller S1 object or its compatibility with the S5 manifest. The compiler
cannot emit a stable descriptor or fingerprint without this boundary.

**Required adjustment:** specify literal `mount` syntax and the exact host-function
signatures, including root identity; define the S1 descriptor as a named subset/version
of the S5 manifest; pin locator generation, duplicate/conflict handling, and frame-plan
extraction. This can preserve the ratified roots-versus-frames decision exactly.

### 4. BLOCKER — “Full Spec 004 merges with the first conforming Stage-1 slice” has no implementable meaning yet

**Citations:** 08 §5 R-1; 11 W5; 12 §3 S1;
`drafts/spec-004-rewrite-draft.md` normative text, “Removed forms,” cross-spec ripple
inventory, and OPEN roster.

The staged R-1 decision is accepted. Its operational expression is not complete. The
full rewrite normatively specifies `sub`, the observation port, local state, effects,
leases, committed callbacks, error boundaries, presence, hydration behavior, HMR, and
tool evidence—features assigned to Stages 2–5. Stage 1 explicitly has no reactivity. On
a literal reading, a Stage-1 implementation cannot conform to the full rewritten spec;
on a looser reading, “conforming slice” has no defined profile or section matrix.

The draft is also not merge-final on its own terms: the custom-element declaration and
tree selector remain OPEN, the controlled-input predicate and R-2 shapes are still
described as provisional, and most 002/006/009/011/Conventions/Ownership changes are an
inventory rather than patches.

There is a second internal conflict with the ratified compatibility decision. The
rewrite removes `reg-view` from Spec 004 and instructs Conventions/API to remove its
exports, while saying the stock-Reagent tier and `reg-view` remain correct and supported
under a pre-rewrite revision preserved only in git history/tag. Git history is useful
provenance, not a live normative contract. A supported compatibility surface needs an
addressable current contract, including which API exports remain. This is not an
argument to keep UIx/Helix/slim; it is necessary to implement the ratified Reagent-only
exception coherently.

**Required adjustment:** define an explicit conformance matrix/profile for each stage,
or split the rewrite into a Stage-1 base amendment plus additive amendments that ride
later implementations. Give frozen stock Reagent a live compatibility appendix/spec
and preserve its required API/facade rows there. Then define exactly which portions and
cross-spec ripples constitute the atomic R-1 Stage-1 merge.

### 5. BLOCKER — Doc 12 is an epic outline, not yet a bead-ready implementation plan

**Citations:** 08 §2–3; 11 W5/W7/W9/W15; 12 §2–4; guide 02 and guide 07.

The API table has now been blessed, so its conflicts require the row-level re-ruling
protocol stated in 12 §4 rather than silent implementation choices:

- “Anything not in this table does not exist,” but 02 and the rewrite require an
  optional public `ui/custom-element` declaration. S4 promises custom elements without
  another declaration mechanism.
- The `.react/*` row claims guide 03/chart and interop as consumers, but the guide uses
  `ui/raw-fn`; it does not use the listed hooks. No document specifies the seven
  wrappers’ call shapes, server behavior, hook-order/HMR contribution, or why `lazy` is
  in the frozen surface while Suspense-as-loading is a non-goal.
- `->react`, `element`, and `spread` are wave 2 in the freeze, but guide 02 teaches all
  three as available and guide 07 teaches `spread`; the rewrite fails to mark `spread`
  WAVE-2. `->react` is also needed by the claimed per-subtree compatibility migration
  unless another outward bridge is specified.
- `ui/html` is Stage 1 in 08 but Stage 4 in 12.
- `ui.test` is part of S1 in 12 and is called one of the earliest critical-path items in
  11, but W9 labels it Stage 2.
- 08 puts foreign callbacks/components, portals, and `client-only` in Stage 3; 12’s S3
  omits them and does not assign them elsewhere with equivalent scope.
- Public v1 items including `lease`, `raw`, the host-root functions, `adapter`, and the
  `.react/*` tier have no unambiguous implementation stage. The S1 root descriptor versus
  S5 root manifest split is also unnamed (finding 3).

The S1/S2 bullets identify broad work packages, but not enough file/contract/fixture
boundaries to file safe leaf beads. For example, “AST + analyzer + emitters” combines
the language grammar, optimizer, two back ends, public tree ABI, SSR bridge, and parity
corpus, while “observation port + ViewCell” combines a core protocol change, real-cache
graft, React bridge, commit transaction, epoch integration, and HMR. Their acceptance
criteria and ownership boundaries are not enumerated.

**Required adjustment:** create one authoritative matrix with every frozen public name
and internal cross-artifact protocol mapped to owner, stage, source files/spec sections,
fixtures, gates, and dependencies. Reconcile the rows above through the table’s stated
Mike re-ruling process. Decompose S1/S2 into independently reviewable contract-first
beads only after findings 1–4 are settled. Epics can be filed now; implementation leaf
beads cannot responsibly be dispatched from this text.

### 6. MAJOR — The four tooling labels promise an observation the platform does not deterministically provide

**Citations:** 03 §4; 04 §1; 07 §3; 08 §1; README document map;
`spikes/s3-ownership-report.md` “Lifecycle”; rewrite “Reactive reads” and the 006/009
ripple rows.

The spike establishes three runtime states and identical cleanup-time behavior for
Activity hide and unmount. 03 reflects that, but then says tools apply
`:activity-disconnected` versus `:unmounted` retroactively at reconnect or cell
collection. A reconnect proves the *preceding interval* was a hide, but the cell is
connected at the moment that proof arrives. Collection can support an eventual unmount
inference only through weak/finalization machinery; it is nondeterministic, has no exact
unmount timestamp, and cannot fire while tooling accidentally retains the cell.

The surrounding suite still treats the labels as ordinary facts: 04 says all four
“connection transitions” update the instance record, 07 demands hide/reveal and unmount
fixtures with distinct end states, and 08/README/the rewrite still say “four-state
lifecycle.” The 009 ripple asks instrumentation to distinguish them. No event schema
states whether a prior interval is rewritten, whether inference is provisional, how
long tombstones live, or how loss accounting applies.

This is an explicit tension with the ratified four-state wording in 08, not a preference
argument against the ruling. The spike has shown that only three states are observable;
the fourth distinction can survive only as qualified tooling inference.

**Required adjustment:** make `:disconnected` with reason/evidence `:unknown` the
immediate emitted fact. Permit later annotation of the prior interval as Activity when
reconnect proves it, and `:unmounted` when an explicit host/root teardown proves it.
If GC inference is retained, specify it as eventual/best-effort with no precise time and
with a bounded tombstone token that does not retain the cell. Update 04, 07, 08, README,
the rewrite, and the 009 ripple to distinguish runtime state, current tool label, and
historical inference.

### 7. MAJOR — The frozen Reagent tier is stated consistently but is not operationally coherent yet

**Citations:** 08 §5 Adapters and §6; 10 headline, Tier R, and migration mechanics; 11
W9/W13; 12 S7; rewrite “Removed forms”; checked-in Spec 006 “Plain-fn footgun.”

The requested scope audit is positive at headline level: every named integration point
says UIx, Helix, and slim are removed while stock Reagent plus `reg-view` remain frozen.
No reviewed passage retains the old “delete every adapter including Reagent” policy.

The operational story nevertheless has gaps:

- 10 promises that step 1 moves dataflow and installs a `frame-root` while keeping
  *every* Reagent/re-com view unchanged. Checked-in Spec 006 says a plain Reagent fn
  cannot read provider context because it lacks the `contextType` attached by
  `reg-view`; ambient frame operations fail with `:rf.error/no-frame-context`. The
  promise is true for a narrower class of registered/explicit-frame views, not every
  stock Reagent tree, unless the compatibility implementation changes that contract.
- The per-subtree migration needs both boundary directions. `ui/raw` describes placing
  an existing React element inside a UI tree, but does not define Reagent-root creation,
  context/frame propagation, ownership, HMR, SSR fallback, or teardown. Placing a new
  `defview` subtree inside a remaining Reagent parent appears to require `ui/->react`,
  which the frozen API table defers to wave 2.
- W9 says the adapter matrix/shared suite collapses, while W13 requires the Reagent
  contract suite and one smoke to remain. These can coexist, but the plan must name a
  new-UI conformance suite and a distinct frozen-Reagent compatibility suite rather than
  implying all legacy coverage is retired.
- As finding 4 notes, the current-spec/API home for `reg-view` after the rewrite is
  missing.

**Required adjustment:** write one small compatibility-boundary contract covering both
nesting directions, frame propagation, supported plain-fn cases, root ownership and
teardown, SSR/HMR limits, and the exact retained CI suite. Either move the necessary
outward bridge into v1 through a row-level ruling or narrow “per subtree” to the root
boundaries actually supported. Qualify the no-view-change step according to the current
plain-fn constraint unless that constraint is deliberately fixed.

### 8. MAJOR — The normative `local` placement law rejects the guide’s canonical ergonomic example

**Citations:** rewrite “Local state — `local` — and the placement rule”; 02 §3 and §5;
guide 03 “Local state.”

The rewrite says a value is forbidden from `local` if *any handler* ever reads it. The
guide’s canonical search box stores text locally and puts that text in the button’s data
handler, `[:search/run text]`; the prose explicitly celebrates that seam. The callback
law likewise promises that handlers read committed slots, including local values. Both
cannot be conforming.

This is not academic wording: an implementer must decide whether to reject the guide at
compile time, prevent a local value from receiving a committed slot, or implement the
ergonomic behavior and violate the rewritten spec.

**Required adjustment:** decide the intended rule and make all three surfaces agree. A
coherent narrow rule would allow same-view committed handlers to consume local UI
ephemera while requiring app-db when the value needs cross-view observation, replay,
schema/tool inspection, durable navigation semantics, or subscription-derived use. If
the old strict rule is retained instead, replace the search-box example and state how a
local setter is useful without handler-visible local state.

### 9. MAJOR — The suite overstates what the five spike gates proved and only partially folded their results

**Citations:** 08 §1 and §5 Budget; 12 §3 S0 and §4; both spike reports; 02 §3;
07 G-1/G-8; 09 “Still open.”

The central feasibility evidence is good, but four readiness claims need narrowing:

1. 08 defines S-4 as dual-host output through *per-root hydration parity* with
   failed-root isolation. The S-1/S-4 report tested 11 structural-output fixtures; it did
   not build root manifests, hydrate multiple roots, or test failed-root isolation. By
   the suite’s own exit criterion, S-4 is not fully PASS.
2. S-3 used a purpose-built stand-in cache. Its report explicitly says the pure cold
   probe still has to be grafted onto the real memo/trace/dispose machinery. 03’s
   “pending only spec merge” wording erases that implementation risk.
3. S-5 proves the public-API mechanism in jsdom and explicitly leaves real Chromium/
   WebKit IME, caret restore, event ordering, and pre-paint behavior to G-8. Calling the
   feasibility spike PASS is reasonable; calling the browser correctness matrix passed
   is not. 02 and the rewrite, conversely, still call the trigger predicate provisional
   pending S-5 even though the report says the predicate is sufficient and should be
   kept.
4. The S-1 benchmark used best-round median/minimum p95. G-1 now specifies alternating
   rounds with median-of-rounds. That is a sensible gate refinement, but the revised
   estimator itself was not what produced the reported PASS.

**Required adjustment:** label these as feasibility passes and keep the unexecuted parts
as named gates: root hydration/failure isolation, real-cache graft conformance, and
real-browser G-8. Remove the obsolete “provisional pending S-5” text. Do not use the S0
checklist to imply those later gates already passed.

### 10. MINOR — Final-state/de-historicalized prose still contains stale state and one dangling section pointer

**Citations:** README document map; 09 adopted/open tables; 11 W7a/W7b; 12 S2/S5,
coordination rules, and checklist.

- W7a points to `04 §5b`; doc 04 has §5 but no §5b.
- 09 still records the four labels as four states and still calls probe-memo lifetime
  and the controlled-input predicate provisional, without marking the S-3/S-5 result as
  superseding those rows.
- README and 08 still advertise a four-state lifecycle rather than the new
  three-state/qualified-label result.
- 12 contains live operator state—bead chains, a worker who owns Spec 011 “today,”
  in-flight mayor work, and an anticipated “fable3” pass—inside a document presented as
  final handoff state. Those references will become false without any design change.
- 11 W7b similarly depends on an unexplained bead id (`j538f7.34`) for a semantic input.

I checked the suite’s numeric `0X §Y` references: apart from the more specific stale
`04 §5b` pointer, the numbered targets resolve. The issue is not general link rot.

**Required adjustment:** repair W7a; update 09/README/08 after the lifecycle and S-5
reconciliation; move volatile bead/worker coordination into the tracker or a generated
handoff appendix, leaving doc 12 with semantic dependencies (“after the frame split,”
“after the current Spec-011 owner merges”) that remain true for fresh readers.

## Questions an implementer would ask

These are the questions I would need answered before writing Stage 1 or Stage 2 product
code. Some answers may deliberately leave private representation choices open; they
still need a named owner and a conformance boundary.

### Stage 1 — compiler, emitters, roots, and Tier-1 tests

1. What are the exact accepted `defview` declaration arities—docstring, options map,
   zero-props form, and props binding—and how is each ambiguous header diagnosed?
2. Are undeclared props accepted when `:props` is absent or present; what does `:as`
   materialize; and how do defaults, namespaced `:keys`, and extra props interact?
3. What is the complete keyword-to-JS props-slot encoding, including namespaces,
   punctuation, reserved JS names, `key`/`ref`/`children`, and collision detection?
4. Are children legal for every internal view; how do they enter a declared props schema;
   and is absent children different from one `nil` child?
5. How are internal heads distinguished from foreign heads at macro-expansion time, and
   what are the rules for aliases, forward declarations, recursion, and mutual recursion?
6. What exact subgrammar of `letfn`, `case`, pure `do`, and `for` is supported? Do `for`
   modifiers (`:let`, `:when`, `:while`), destructuring, and nested loops exist?
7. What values are legal children besides the listed strings/numbers/nil/false—booleans,
   symbols, React elements, arrays, realized seqs—and how are nested sequences and text
   runs normalized?
8. What are the key rules for fragments, internal views, conditional branches, duplicate
   keys, and a keyed item whose root is nil or a fragment?
9. What is the closed AST node set, how are dynamic expressions represented, what part
   is serializable, and which schema/version is fingerprinted? Is this AST intentionally
   private or a build-tool contract?
10. What is the exact public JVM-tree node schema, including fragments, trusted HTML,
    events, keys, view boundaries, presence metadata, fallbacks, and text?
11. Does keyword lookup on a Tier-1 node expose attrs directly, or are nodes opaque and
    readable only through `ui.test/attrs`? Where do event vectors live?
12. How does a view-id selector match fragment-rooted and nil-rooted views, and what exact
    view-boundary annotation survives nested view expansion?
13. Are selector path vectors and `find!` in v1? If so, what are their frozen error ids
    and digest shape; if not, why are they in the Stage-1 surface?
14. What is the normative DOM conversion table for HTML, SVG, MathML, namespaces,
    boolean/overloaded-boolean attrs, data/ARIA attrs, property-only names, styles,
    custom CSS properties, and unitless numeric styles?
15. How do tag `.class#id` sugar and explicit `:class`/`:id` attrs combine, and in what
    deterministic order do class vector/map entries render?
16. What is the `ui/custom-element` declaration syntax, scope, duplicate behavior, and
    production representation—or is custom-property declaration removed?
17. Which invalid handler-option combinations are compile errors, especially
    `:passive true` with `:prevent-default true`; can `ui/handler` be used on native DOM
    sites when strict bare-handler lint is enabled?
18. During S1, do event forms compile to working client callbacks, to structural data
    only, or fail until S3? What conformance claim is made for that intermediate artifact?
19. Is `ui/html` implemented in S1 or S4, and what AST/JVM node represents dynamic
    trusted markup?
20. What are the static-hoisting rules around keys, refs, owner-sensitive foreign values,
    dev annotations, HMR generation, and reusable jsx props objects?
21. Does S1 include memoized stable component shells and the ruled `rf=` props comparator,
    or are those S2/HMR work? What is the intermediate public behavior?
22. How can the S1 JVM emitter render the documented pure subscription path if `sub` and
    frame ownership do not arrive until S2?
23. What exact function in `re-frame2-ssr` consumes the new tree, who owns the adapter
    from the old render-tree contract, and how is version incompatibility reported?
24. What exact forms may `ui.test/render` receive as `root-or-view`, and how are props,
    frames, registrations, and overrides combined or rejected?
25. Where is `root-id` authored or derived, and what is the complete `mount`/`create-root`/
    `render!`/`hydrate-root`/`unmount!` signature set?
26. What is the S1 root descriptor schema, and how does it evolve without churn into the
    S5 root manifest?
27. How are element locators generated for SSR and client mounts, and when are duplicate
    root ids detected across compilation units/page fragments?
28. How are unconditional `frame-root` plans extracted from the “top region”; what
    syntactic forms count as top-region wrappers; and how are duplicate/conflicting frame
    plans ordered and diagnosed?
29. What are the algorithms and version inputs for template fingerprint, render
    fingerprint, build digest, hook-signature hash, and normalized parity?
30. What is the stable compile-error roster and configuration surface, and which ids are
    compile diagnostics versus Spec-009 runtime catalogue entries?
31. Which exact fixture corpus and estimator make G-1/G-14 pass, and which S-4 root
    fixtures remain mandatory before Stage 1 is called conforming?
32. Which portions of the full Spec-004 rewrite are asserted by S1, and where is that
    stage conformance profile written?

### Stage 2 — observation ownership, frames, epochs, and HMR

33. What are the canonical `ObservationTarget`, probe-evidence, and Lease schemas, and
    which fields are stable identities versus diagnostic evidence?
34. What is the exact `resolve-target` input/output contract for ambient frame, explicit
    frame, Story override, pins, and future resolution sources, and may any source change
    between resolve and commit?
35. In what namespace/protocol does `day8/re-frame2-ui` access a port that is outside
    Spec 006’s public adapter map, and what compatibility/versioning rule governs that
    cross-artifact seam?
36. How does a cold ownership-free probe execute a real subscription graph without
    creating cache entries, watches, disposal obligations, or misleading trace events?
37. What happens when a cold graph contains an unknown input sub, a disposed derived
    node, a circular dependency, or an exception in a sub function?
38. What exactly keys the synchronous-slice memo, who creates it, and how are nested
    renders, nested roots, several frames, synchronous dispatch, and microtask cleanup
    handled?
39. Is `current?` part of the port; what precisely makes a lease current after HMR,
    frame swap, query `rf=` stabilization, override change, or node disposal?
40. What are static override-lease read/version/current/release semantics, and which
    Stage-2 fixture proves the unprototyped shape?
41. On a multi-target reconcile failure, which provisional leases are rolled back, in
    what order, and what cause/error is published while the old committed set remains?
42. Can `on-change` fire synchronously during acquire or release; what calls are forbidden
    during notification fan-out; and what queues notifications caused by HMR disposal?
43. Which internal failures throw and which public operations recover to nil, and is the
    catalogue id `:rf.error/no-sub` or `:rf.error/no-such-sub`?
44. What is the ViewCell’s exact committed/capture state, dirty-state machine, revision
    snapshot, and `useSyncExternalStore` subscribe/getSnapshot/getServerSnapshot contract?
45. Which layout-effect ordering makes publication, lease acquisition, DOM interaction,
    sibling effects, and pre-paint correction deterministic under StrictMode?
46. How does epoch finalization integrate with nested dispatch, several frames, restore,
    the controlled-input synchronous door, React batching, and `flushSync` rejection?
47. What immediate lifecycle fact is emitted on cleanup; what later evidence can relabel
    it; and how do Xray records survive without retaining the cell they await collecting?
48. What happens visibly and diagnostically when a frame/root/adapter dies under a still-
    mounted view, and which boundary owns recovery versus a fatal render?
49. What are ENSURE’s duplicate/config-conflict rules, initial-event ordering, retry
    behavior after failure, shared-frame hydration behavior, and HMR behavior?
50. What exact source-anchor/path algorithm produces site identity and hook signatures,
    and which edits preserve state versus force remount/release?
51. What does `flush!` flush (one frame, all frames, one root, all roots), and what are
    its nesting, reentrancy, async-settlement, and test `act` semantics?
52. At which stage does view-level resource `lease` land, and how does its owner token,
    Activity disconnect, rollback, and failure behavior relate to subscription leases?

### Program, compatibility, and specification landing

53. For every frozen public name, which stage, owning namespace/file, conformance fixture,
    production-erasure rule, and guide consumer justifies it?
54. What are the exact `.react/*` signatures and consumers, and how do those hooks affect
    hook-signature hashing, JVM use, SSR, and HMR?
55. Which wave-2 names must be removed from the v1 guide/rewrite now, and is an outward
    `defview`→React bridge actually required for the ratified incremental migration?
56. What are the two co-mount directions between frozen Reagent and `re-frame.ui`, and
    who owns frame context, React roots, teardown, HMR, and SSR at each boundary?
57. Which plain Reagent functions can complete migration step 1 unchanged under the
    current `frame-provider` context rule, and what is the prescribed rewrite for the
    rest?
58. Where does the live frozen-Reagent/`reg-view` normative contract reside after the
    Spec-004 rewrite, and which facade/API/Conventions rows remain because of it?
59. Which legacy tests are deleted, and which distinct Reagent compatibility contract
    suite and smoke remain in CI?
60. What exact later gates remain despite the feasibility PASS labels: real-cache graft,
    browser input matrix, root hydration/failure isolation, benchmark estimator, and
    production elision?
61. What atomic set of Spec 004/002/006/009/011/Conventions/Ownership edits lands with
    each implementation stage, and what prevents an intermediate checked-in spec from
    claiming unimplemented behavior?

## Draft merge-readiness audit

| Draft | Mechanical check | Semantic result |
|---|---|---|
| `spec-004-interim-amendment.md` | All **9/9** fenced “Old” blocks occur **exactly once** in current `spec/004-Views.md`. | **Ready to merge.** Its portability broadening admits both today’s runtime tree and the future compiler AST, and does not depend on the unresolved Stage-1/2 shapes. |
| `spec-004-rewrite-draft.md` | It is a wholesale replacement rather than an anchor patch; its ripple list was checked against the synthesis. | **Not merge-ready.** Findings 2–5, 7–9 must be reconciled, especially staged conformance and the live Reagent compatibility contract. |
| `spec-006-observation-port-amendment.md` | Every declared insertion/replacement anchor checked in `spec/006-ReactiveSubstrate.md` still occurs exactly once; no anchor drift found. | **Not merge-ready.** It encodes the pre-S-3 node/owner/pass-token model and leaves answered questions open; finding 1 requires a semantic rewrite. |
| `ui-test-selector-grammar.md` | Its 07/guide references resolve. | **Not promotion-ready.** OPEN-1/2/3 remain, and its direct node lookup contract must be reconciled with the public JVM-tree ABI. |

## Verdict

**(a) Ready to file the implementation beads? No.** Doc 12 is ready to seed a program
epic and contract-reconciliation beads, but not to dispatch Stage-1/Stage-2 product
beads. Resolve findings 1–5 first. In particular, freeze the R-2 port, public JVM tree,
root descriptor, staged Spec-004 conformance model, and authoritative API/stage matrix.
The remaining majors can then become explicit acceptance criteria rather than decisions
made inside implementation branches.

**(b) Ready to merge the interim Spec-004 amendment? Yes.** Its nine anchors are exact
and unique in the checked-in spec, and its broadened portability law is independently
sound. Merge that small amendment without waiting for the full rewrite. This verdict
does **not** extend to the full Spec-004 rewrite or the Spec-006 amendment.
