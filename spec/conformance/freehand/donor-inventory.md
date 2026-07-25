# Freehand donor inventory

`re-frame.ui` is in donor mode. Its useful machinery becomes the compiled tier of
Freehand, and the standalone `day8/re-frame2-ui` artifact is deleted once every
row below has been disposed. "Absorption completeness" is one of the release
gates, and this ledger is the artifact that gate reads.

The ledger enumerates every donor row — source file, test family, fixture,
build-wiring hook, spec obligation, and downstream consumer — and gives each one
an explicit disposition. It does not perform any move: each programme slice
disposes its own rows and flips their status in the same change that does the
work.

## How to read a row

Every row carries a disposition from a closed set of three:

| Disposition | Meaning |
|---|---|
| **MOVE** | the code, test, or obligation is absorbed under Freehand ownership — by rename, by relocation, or by generalization from donor names to both execution modes |
| **REPLACE** | the job is real but Freehand does it differently on purpose; the donor realization is not carried across |
| **DELETE** | the row does not cross at all; nothing in Freehand takes its place |

Every row also names the **owning slice** — the programme slice (F0–F6) whose
work disposes it — and a **status**:

| Status | Meaning |
|---|---|
| `pending` | the row is still undisposed; it counts toward the absorption-completeness baseline |
| `done` | the disposition has been carried out; the row stays as the audit record |

Reuse of donor code confers no API status. A `MOVE` row means the
implementation is worth keeping, never that the donor's public spelling
survives.

For a donor **source** file, `done` records that Freehand owns the code — not
that the donor's file has vanished. Absorbed code diverges the moment it
lands: the moved analyzer recognizes Freehand vars and the moved emitters
lower to Freehand runtime namespaces, so the donor cannot run on its own
absorbed code, and the donor artifact has to keep building for the whole of
coexistence. Its copy therefore stays where it is — vestigial and frozen —
until the artifact is deleted whole. What `done` asserts is precisely this:
nothing further has to be extracted from that file, and deleting the donor
costs Freehand nothing. Donor **tests** are different: a suite proves a
contract, and the contract has one owner, so a moved suite leaves the donor
tree in the change that moves it.

## Granularity rules

Three rules fix what gets a row, so coverage can be checked mechanically rather
than argued:

1. **One row per source file.** Every tracked file under
   `implementation/ui/src/` gets its own row. These are the rows whose
   disposition is most consequential, so none of them hides inside a group.
2. **One row per test family.** The donor test tree is flat and large, so tests
   are grouped by filename-prefix family — the same convention the tree already
   uses to name its subjects (`reactive_*`, `custom_element_*`, `presence_*`).
   A family is a glob; every tracked test file must fall inside exactly one.
   This is finer than directory granularity, which would collapse the entire
   suite into a single row.
3. **One row per fixture, probe, or wiring hook.** Benches, evidence fixtures,
   proof packs, probes, testbeds, and the artifact's own build wiring each get
   a row at the directory or file level, whichever is the unit that moves.

Rows outside the donor tree — spec obligations, `tools/` consumers, examples,
docs — are an open roster rather than a closed partition. The ledger claims to
name every donor *file*; for the consuming trees it claims to name every
*obligation*, and the paths it lists must continue to exist while the row is
pending. The open roster is not curated by hand alone: the subset of it that is
a **live consumer** is derived mechanically, and the next section says how.

## What counts as a live consumer

Outside the donor tree the ledger's job is to name every place that would still
break if `day8/re-frame2-ui` were deleted today. The gate derives that set from
two signals over git-tracked files, so the ledger cannot silently fall behind
the code:

| Signal | What it matches |
|---|---|
| **require** | a Clojure or EDN libspec naming a `re-frame.ui…` namespace — `[re-frame.ui :as ui]`, `[re-frame.ui.tree :as tree]`, a build's `:entries` vector |
| **coordinate** | the donor artifact coordinate in dependency position — `day8/re-frame2-ui {…}` in a `deps.edn`, in a generated scaffold, or in an install instruction |

Every file either signal finds must be claimed by a row, and while the signal is
still there the row must still be `pending`. A row covering a live consumer
cannot be marked `done`: the require or the coordinate goes first, and the row
flips in the same change that removes it. That ordering is what keeps `done`
from becoming a claim the code contradicts.

Historical mentions are **evidence, not consumers**, and are excluded by name:
`docs/EP/`, `docs/design/`, `CHANGELOG.md`, and `.beads/` record what was
decided and when, and deleting the donor does not break any of them. This ledger
and the gate's own script are excluded for the same reason — they quote the
donor in order to retire it. Nothing else is excluded. Incidental prose
elsewhere is simply not a signal, because neither detector fires on it, and the
gate deliberately does not police it.

## How coverage is enforced

```
python scripts/check_donor_inventory.py              # the gate
python scripts/check_donor_inventory.py --report     # undisposed-row report
python scripts/check_donor_inventory.py --self-test  # the gate's own fixtures
```

The gate fails when:

* a tracked file under `implementation/ui/` is claimed by no row, or by more
  than one — the donor tree is a partition;
* a live consumer outside the donor tree is claimed by no row;
* a row covering a still-live consumer is marked `done`;
* a still-pending row points at a path that no longer exists, or at a path that
  no longer carries any donor material at all — the shape a row takes when its
  subject was migrated out from under it and the path was reused for something
  else;
* an **established row identity has been deleted** from the ledger. Rows are
  never removed. Disposing a row means flipping its status to `done`; the row
  stays as the audit record. The roster of established identities in
  `scripts/check_donor_inventory.py` is what makes a deletion loud instead of
  looking like progress;
* a **row is not in that roster**. The roster covers this ledger exactly, in
  both directions: adding a row is two edits in one change — the row here and
  its identity there — and the failure prints the line to paste. Without that,
  the retention rule above would hold only for the rows that existed when the
  roster was written, and every row added afterwards could be added, disposed,
  and then quietly deleted again. Renaming a row's path edits the row and its
  roster identity together, which stays the one deliberate reason to change an
  existing entry;
* any row is missing a disposition, an owning slice, or a status.

So a new donor file cannot appear undisposed, a donor file cannot quietly vanish
without its row being settled first, a new consumer cannot appear unclassified,
and the pending count cannot fall by deletion — only by disposition.

`--report` prints the count of rows not yet disposed, broken down by slice,
disposition, and section. That count is the number the programme drives to zero.

## Ruled contract dispositions

These are settled product decisions, not open questions. They are recorded here
because several of them cut across many files and would otherwise be invisible
in a per-file table. The file rows below inherit them.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `local` and its placement machinery | component-local state and the anchors that place it; the buffered/reset-key use case that justified it is replaced by the generation-fenced buffered controller, not by a smaller `local` | DELETE | F4a | done |
| instance state and generic storage verbs | `local`-adjacent generic component storage and derived writable anchors | DELETE | F4a | done |
| refs, effects, and the React hook tier | the neutral imperative forms; one-node behaviors own bounded DOM lifecycle and wrappers own React protocols | DELETE | F4 | done |
| callable JVM view values | a declared view that is invocable as a function | REPLACE | F1 | done |
| placeholder provenance | donor event-payload placeholder spellings and general dispatch payload arity | REPLACE | F2 | done |
| compiled parent to interpreted child crossing | the one emitted descriptor boundary that lets a single declaration be promoted | MOVE | F3 | done |
| controlled scheduling | the final-normalized native input predicate and one frame-scoped synchronous scheduler shared by both modes | MOVE | F2 | done |
| key-condition event maps | the closed exact-key `:on-key-down`/`:on-key-up` form with existing event values and pre-dispatch mechanics. **Gate DISCHARGED, outcome DELETE (2026-07-25, D007, rf2-drpa3.178):** all four F5 pilots (rf2-drpa3.44) used the form zero times, so it was removed from the substrate rather than kept for symmetry — source, tests, fixture and the FH-EVENT-005 row all gone. The row does not cross: a map at an event position is the listener-options map and nothing else, and keyboard branching is an ordinary registered event carrying `::v/key`. | DELETE | F2 | done |
| `spread-safe`/`spread` and `render-fn`/`slot` | forwarding and parameterized-content grammar, absorbed without the donor hook tier | MOVE | F5 | done |
| presence runtime | keyed enter/exit retention, made common to both modes | MOVE | F4 | done |
| `route-link` | the ordinary routing-aware view over the late-bound routing seam, crossing by rename | MOVE | F5 | done |
| analyzer, both emitters, ViewCell reactor, manifest/elision, diagnostic taxonomy, structural test surface | the useful donor machinery named by the programme as absorbed | MOVE | F3 | pending |

## Donor sources

Every tracked file under `implementation/ui/src/`.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `implementation/ui/src/re_frame/ui.cljc` | the donor public facade — `defview`, `custom-element`, `sub`, interop forms, `local`, `effect`, `ref`, `presence`, `route-link`, mount. The trusted-markup verb (`ui.cljc:519-530`) is disposed: `re-frame.freehand/html` is published (rf2-rrosy), and it is a REPLACE rather than a move — the donor's var answers the tree node on the JVM and throws in the browser, where Freehand's answers a private nominal value that BOTH interpreted walks lower. The row stays `pending` for the rest of the facade | REPLACE | F1 | pending |
| `implementation/ui/src/re_frame/ui/client.cljs` | Root handle, live-root claim registry, mount / render! / hydrate-root / unmount! — REPLACED by `re-frame.freehand.root`, which owns the whole client lifecycle behind the ratified runtime-fn mount door (no `create-root` / `render!`); the donor file stays as the re-frame.ui substrate's own | REPLACE | F1 | done |
| `implementation/ui/src/re_frame/ui/compiler.cljc` | the declaration expansion pipeline: arity and options parsing, header analysis, manifest and fingerprint assembly, per-host emission | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/a11y.cljc` | compile-tier accessibility diagnostics minted from literal template facts | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/analyze.cljc` | the template-grammar analyzer and its closed normalized AST | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/binding_plan.cljc` | the one host-faithful associative-destructuring binding plan | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/build.cljc` | build-scoped compiler registries and the acceptance transaction | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/build_hook.clj` | the shadow-cljs build-lifecycle adapter that harvests whole-build registries | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/emit_cljs.cljc` | the React emitter — direct jsx-runtime lowering, static hoisting, per-slot comparators. The move landed in F3 but was only made TRUTHFUL for TRUSTED MARKUP by `v/html`'s publication (rf2-rrosy): the donor's `dangerouslySetInnerHTML` arm (`emit_cljs.cljc:273-276,432`) had no Freehand counterpart, so a `(v/html s)` the analyzer recognised, validated and recorded on the manifest reached the DOM as an element with the author's markup silently gone. Freehand's own arm is a `compiled-react/html!` write through the interpreted walk's shared writer, not a port of the donor's props-list entry | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/emit_jvm.cljc` | the JVM emitter — the versioned structural tree, event vectors retained as data. Made truthful for TRUSTED MARKUP by the same slice (rf2-rrosy): the donor's `{:html s}` child arm (`emit_jvm.cljc:178-179`) crossed as a dead `:html nil` case that emitted nothing. Freehand's own arm fills the canonicaliser's element `:html` slot — the slot the interpreted structural walk also fills — so the string check and the host refusals are one implementation rather than a per-emitter pair | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/env.cljc` | compile-time environment and internal-versus-foreign head resolution | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/harvest.clj` | deterministic pre-seed of compile-time custom-element declarations. The move landed in F3 but was only made TRUTHFUL by `v/custom-element`'s publication (rf2-sv2oq): the harvester scans each source for the head symbols that name `re-frame.freehand/custom-element`, and until that var existed the scan resolved aliases to a name nothing published, so it could pre-seed nothing and its reason to exist — declaration order not being an authoring decision — was unprovable | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/header.cljc` | props-binding analysis: destructuring lowering, `:as` materialization, `:or` defaults | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/compiler/root.cljc` | root-id grammar, deterministic slug, and the static top-region scan | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/eq.cljc` | `rf=`, the ruled per-slot equality behind memo-by-default | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/events.cljs` | commit-owned native event callbacks; the candidate table an abandoned render cannot retarget | MOVE | F2 | pending |
| `implementation/ui/src/re_frame/ui/fingerprint.cljc` | template fingerprint and hook-signature digests; the hook-signature arm goes with the hook tier | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/frames.cljc` | preflight ENSURE executor, plan-install registry, frame-scope elements, ambient frame resolution | MOVE | F2 | pending |
| `implementation/ui/src/re_frame/ui/hooks.cljc` | the host-hook lowering targets `local` / `effect` / `dispatch-fn` / `use-ref` lower to | DELETE | F1 | pending |
| `implementation/ui/src/re_frame/ui/presence_runtime.cljc` | the three-phase keyed retention machine and its terminal timeout bound | MOVE | F4 | done |
| `implementation/ui/src/re_frame/ui/react.cljc` | the six-wrapper neutral React tier: effect, layout-effect, effect-event, context, id, lazy | DELETE | F5 | pending |
| `implementation/ui/src/re_frame/ui/reactive.cljc` | the ViewCell, the render-side probe/record protocol, the layout-commit reconciler, the lifecycle | MOVE | F2 | pending |
| `implementation/ui/src/re_frame/ui/route_link_seam.cljc` | runtime helpers consuming the late-bound routing link-model and activate hooks | MOVE | F5 | pending |
| `implementation/ui/src/re_frame/ui/rules.cljc` | the one DOM prop-conversion rule table shared by analyzers, emitters, and the JVM tree | MOVE | F3 | done |
| `implementation/ui/src/re_frame/ui/runtime.cljs` | the small client vocabulary emitted code calls, including the one sanctioned runtime conversion — REPLACED by the shipped Freehand runtime; the donor file stays as the re-frame.ui substrate's own | REPLACE | F3 | done |
| `implementation/ui/src/re_frame/ui/semantic.cljc` | semantic normalization — the parity and fingerprint input | MOVE | F1 | pending |
| `implementation/ui/src/re_frame/ui/sub_overrides.cljs` | the React carriage for story-supplied subscription overrides | MOVE | F2 | pending |
| `implementation/ui/src/re_frame/ui/substrate.cljs` | the first-party React adapter machinery, frame-context reader, and root-scoped adapter disposal — the GENERIC half crosses as `re-frame.freehand.substrate`, published on the door as `v/adapter` (`:kind :rf.adapter/freehand`, rf2-vo8fb): spine-fns over React's own hooks, the plain-React `:register-context-provider` component, and `make-react-adapter`. FIDELITY DELTA: the donor's ViewCell reactor flush and its root-registry disposal COMPOSITION do not cross, and there is nothing left over to settle — Freehand has no donor reactor (its `reactive.cljc` is a sub-read plus an event-site and carries no flush machinery; the pending window lives on `re-frame.freehand.cell`), and no client-kernel admission fence to inject, so the two lifecycle jobs are re-realized against Freehand's own registry rather than ported: disposal is `root/drain-live-roots!` THEN the spine, and `flush-render!` closes the cell window inside React's commit boundary and converges through `cell/converge-flush!`. The frame-context READER does not cross either and is not a lost capability: `freehand.shell/frame-context-frame` reads the shared context directly, so the donor's `:adapter/current-frame` publication has no Freehand counterpart to reproduce. The donor file stays as the re-frame.ui substrate's own | MOVE | F2 | done |
| `implementation/ui/src/re_frame/ui/test.cljc` | the structural and mounted test surface across the JVM and browser hosts | MOVE | F1 | done |
| `implementation/ui/src/re_frame/ui/tool.cljc` | the dev-only read-only projections a debugging consumer reads — REPLACED by `re-frame.freehand.evidence` published through the `re-frame.freehand.cell` commit seam (`cell.cljc:799` `set-evidence-sink!`, `:848` `commit-evidence-record`, `:901` `emit-commit-evidence!`), and later by the `re-frame.freehand.tool` reader, which is built from Freehand's own compiler manifest and its own commit records; the donor projections are a shape reference, not code to port. FIDELITY DELTA: Freehand publishes a validated per-commit occurrence record through one door; it does not accrete per-occurrence render history, so the donor's `mounted-views` / `explain-render` reading of a cumulative accumulator does not cross. The donor file stays as the re-frame.ui substrate's own | REPLACE | F4 | done |
| `implementation/ui/src/re_frame/ui/tool/evidence.cljc` | the bounded per-cell accumulator over the invalidation-evidence plane — REPLACED by `re-frame.freehand.evidence`, one versioned occurrence-keyed schema stating scope, basis, completeness and loss, published through the `re-frame.freehand.cell` commit seam (`cell.cljc:799` `set-evidence-sink!`, `:848` `commit-evidence-record`, `:901` `emit-commit-evidence!`). FIDELITY DELTA: Freehand publishes a validated per-commit occurrence record through that one door; it does not accrete per-occurrence render history, so the donor's cumulative accumulator — the per-cell accretion of folded batch records across a cell's whole observed life, and the weak identity-keyed registry that retains it — does not cross. The donor file stays as the re-frame.ui substrate's own | REPLACE | F4 | done |
| `implementation/ui/src/re_frame/ui/tree.cljc` | builders for the versioned public structural tree in canonical form. The trusted-markup constructor (`tree.cljc:349-357`) is disposed: rf2-rrosy re-realized it as the canonicaliser's element `:html` slot rather than a standalone `tree/html` builder, so trusted markup is a slot on the element both front ends fill and not a node a caller can construct — which is what lets the sole-child, `<textarea>` and void refusals live in one place. The row stays `pending` for the builders still to cross | MOVE | F1 | pending |
| `implementation/ui/src/re_frame/ui/viewcell.cljs` | the React glue driving the reactor: ref, external-store subscription, layout effect | MOVE | F2 | pending |

## Donor tests

Grouped by filename-prefix family under `implementation/ui/test/`, per granularity
rule 2. A test family's disposition follows its subject: a family whose subject
is DELETEd is deleted with it, and a family whose subject is REPLACEd is
re-proved against the Freehand contract rather than ported line by line.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `implementation/ui/test/re_frame/ui/a11y_*` | compile-tier accessibility diagnostics | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/adapter_*` | substrate adapter installation, conformance, generation fence, public root disposal | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/analyze_*` | analyzer accept and reject corpora | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/authored_collision_*` | authored view-id collision detection | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/binding_plan_*` | host-faithful destructuring plan, including advanced-build elision | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/build_*` | build probe and REPL/HMR build convergence | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/callbacks_*` | callback boundary ownership across hosts | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/committed_events_*` | the committed-events publication law | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/compiler_*` | build hook, build state, harvest, and macro resolution | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/conditional_root_annotation_*` | root annotation under conditional markup | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/conditional_sub_*` | subscriptions inside conditional branches | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/custom_element_*` | custom-element classification, ordering, conflict, spread parity, warm staleness, elision. **Crossed CONSOLIDATED (rf2-sv2oq)**, nine suites into five, because seven of them re-derived their own views to drive one path each: `custom_element_cljs_test.cljc` (the classification matrix, written by lowering PATH — compiled literal map, interpreted walk, `v/spread` in each mode, `v/spread-safe` caller — plus order independence and the value grammar's positive and negative halves), `custom_element_ssr_jvm_test.clj` (property omission from server markup, pinned as whole strings because the claim is about absence), `custom_element_conflict_jvm_test.clj` (one tag one manifest, at the macro barrier and the Shadow harvest barrier, with the permutation rows two retired behaviours each passed), `custom_element_warm_staleness_jvm_test.clj` (a warm declaration edit re-bakes to the clean value, and an edit with no manifest delta invalidates nothing), and `custom_element_dom_cljs_test.cljs` (the mounted property write — the only place the renderable-but-inert failure is observable). All five read the one `FH-STRUCT-011` fixture. The donor's two `*_elision_prod_test.cljs` arms did NOT cross as suites: they are advanced-BUNDLE gates rather than classification laws, their oracle is the donor-named bundle scanner `scripts/check-ui-mounted-prod-elision.cjs`, and re-homing that scanner is one F6 unit rather than one law — the reload ledger's development-only residence is instead verified by the reachability check on the Freehand release bundle (rf2-sv2oq item 6) | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/defview_grammar_*` | the declaration grammar itself | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/digest_probe/*` | the warm-watch digest probe project used by the recompile gate | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/emit_cljs_*` | React-emitter lowering and view-evidence annotation | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/eq_*` | the per-slot equality law | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/error_roster_*` | the diagnostic-id roster | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/event_*` | event warning scope and event-wrapper shapes | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/exact_render_capture_*` | exact render capture in the browser | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/fast_refresh_shell_*` | fast-refresh shell identity | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/fingerprint_*` | identity digests | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/frame_*` | frame ops, plans, scope resolution, preflight races, publication linearization, context | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/g13/*` | the mass-mount evidence fixture's own measurement test | MOVE | F6 | pending |
| `implementation/ui/test/re_frame/ui/g14_*` | the compile-budget gate | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/hidden_sub_macros.clj` | test-only macro fixture for hidden subscription sites | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/hooks_*` | local-state cause and commit behaviour of the hook tier | DELETE | F1 | pending |
| `implementation/ui/test/re_frame/ui/local_effect_*` | `local` / `effect` / `dispatch-fn` behaviour | DELETE | F1 | pending |
| `implementation/ui/test/re_frame/ui/mounted_*` | mounted browser gates: cardinality, stage gates, story override schema | MOVE | F4 | pending |
| `implementation/ui/test/re_frame/ui/parity_*` | the cross-emitter parity corpus, its fixtures, HTML projection, and embedding | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/passive_events_*` | passive event registration | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/preflight_*` | preflight authority, frame wiring, generation fence, supersession | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/presence_*` | presence reconciliation, retention, and hosts | MOVE | F4 | pending |
| `implementation/ui/test/re_frame/ui/raw_foreign_boundary_*` | the qualified foreign-host boundary | MOVE | F5 | pending |
| `implementation/ui/test/re_frame/ui/react_export_bridge_*` | the outward React bridge | MOVE | F5 | pending |
| `implementation/ui/test/re_frame/ui/react_interop_*` | the neutral React interop tier; only the outward-bridge arms are re-proved | REPLACE | F5 | pending |
| `implementation/ui/test/re_frame/ui/react_render_*` | React render behaviour of a declared view | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/reactive_*` | the reactor: commit causes and records, epochs, HMR matrix, incarnation, teardown, races, slice memoization | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/render_batch_*` | render-batch host checkpointing | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/render_capture_*` | render-capture thread ownership | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/render_key_dom_stamp_*` | key stamping in the DOM and its production elision | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/render_static_strip_*` | static-subtree stripping | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/reserved_head_reject_*` | rejection of reserved template heads | MOVE | F3 | done |
| `implementation/ui/test/re_frame/ui/root_*` | root analysis, registry, incarnation, mount, teardown, wiring | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/route_link_*` | anchor semantics and the click law over the routing seam | MOVE | F5 | pending |
| `implementation/ui/test/re_frame/ui/rules_*` | the DOM conversion table and its custom-element conflict rule | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/s3_*` | the donor stage-3 conformance profile and ergonomic proof | DELETE | F6 | pending |
| `implementation/ui/test/re_frame/ui/s4_*` | the donor stage-4 conformance profile | DELETE | F6 | pending |
| `implementation/ui/test/re_frame/ui/s5_*` | the donor stage-5 conformance profile | DELETE | F6 | pending |
| `implementation/ui/test/re_frame/ui/semantic_normalize_*` | semantic normalization | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/serialiser_rules_*` | serialization rules for the structural tree | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/shadow_config_*` | the build-tool configuration contract | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/skeleton_*` | the adapter-isolation skeleton that rides the node test build | MOVE | F3 | pending |
| `implementation/ui/test/re_frame/ui/slice_memo_*` | slice-memo lifetime census | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/slot_*` | slot render-fn arity and keyed reorder | MOVE | F5 | done |
| `implementation/ui/test/re_frame/ui/spread_*` | spread and spread-safe grammar, rejected props, elision | MOVE | F5 | done |
| `implementation/ui/test/re_frame/ui/ssr_reinit_*` | server-render reinitialization lifecycle | MOVE | F5 | pending |
| `implementation/ui/test/re_frame/ui/sub_overrides_*` | production elision of the subscription-override carriage | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/substrate_flush_*` | flush-to-render convergence | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/ui/teardown_falsy_*` | teardown under a falsy render failure | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/test_*` | the test surface itself: render, projections, outcomes, sub-override schema, guide fixtures | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/tool_*` | the tool projections: view manifests, evidence, generation, elision — REPLACE, by the granularity rule that a family's disposition follows its subject: both subjects (`tool.cljc`, `tool/evidence.cljc`) are REPLACE, a suite proves a contract, the contract has one owner, and a family whose subject never crosses does not cross either. Re-proved against the Freehand contract rather than ported line by line — `re-frame.freehand.evidence` carries its own suite over the `re-frame.freehand.cell` commit seam (`evidence_cljs_test.cljc`, `evidence_seam_cljs_test.cljc`, `evidence_boundary_jvm_test.clj`), and the later `re-frame.freehand.tool` reader brings its own against Freehand's compiler manifest. FIDELITY DELTA: no Freehand test asserts a cumulative per-cell accumulation, because Freehand publishes one validated per-commit occurrence record through one door and accretes no per-occurrence render history; the donor's accumulator-lifetime, owner-generation and retention cases have no Freehand counterpart to prove. The donor family stays where it is, proving the donor's own vestigial tier for the rest of coexistence | REPLACE | F4 | done |
| `implementation/ui/test/re_frame/ui/tree_*` | structural-tree builders | MOVE | F1 | pending |
| `implementation/ui/test/re_frame/ui/viewcell_*` | ambient frame binding and server frame context | MOVE | F2 | pending |
| `implementation/ui/test/re_frame/realworld_*` | the donor stage-3 ergonomic proof mounted over the realworld example | DELETE | F6 | pending |

## Donor fixtures, probes, and testbeds

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `implementation/ui/bench/*` | the direct-render parity bench and its hand-written comparison arm | MOVE | F3 | pending |
| `implementation/ui/cache-carrier-probe/*` | the standalone probe pinning build-cache carrier behaviour for the build hook | MOVE | F3 | pending |
| `implementation/ui/dev/*` | the React behaviour probe that version-pins the DOM conversion table | MOVE | F1 | pending |
| `implementation/ui/g8/*` | the input-latency evidence fixture | MOVE | F6 | pending |
| `implementation/ui/g13/*` | the mass-mount evidence fixture, dev and production arms | MOVE | F6 | pending |
| `implementation/ui/proof-pack/*` | the elision proof pack: a library, a single-view consumer, and the all-views positive control | MOVE | F3 | pending |
| `implementation/ui/scaffold-smoke/*` | the compile-and-omission smoke over the minimal scaffold example | MOVE | F6 | pending |
| `implementation/ui/testbed/*` | the browser smoke testbed: host page, spec, and counter app | MOVE | F1 | pending |

## Donor artifact and build wiring

The donor artifact's own descriptor is DELETEd rather than moved: Freehand ships
from its own artifact with its own coordinate, created by its first slice, and
never depends on the donor. The wiring that names the donor in shared build files
is REPLACEd — the hook still has to exist, but it points at Freehand.

Publication wiring is the exception. `day8/re-frame2-ui` is never published: it
is internal donor code for the whole of its life, so its deploy leaf, its
publishability assertion, and the guard that made it a required artifact of the
release train have no Freehand successor and are DELETEd outright.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `implementation/ui/deps.edn` | the donor artifact descriptor: paths, core dependency, test alias, and publication coordinate | DELETE | F6 | pending |
| `implementation/deps.edn` | the cross-artifact classpath: the donor local-root dependency and its test path | REPLACE | F6 | pending |
| `implementation/shadow-cljs.edn` | donor source paths, the default build hook, and the node-test, bench, evidence, proof-pack, and testbed build ids | REPLACE | F6 | pending |
| `implementation/package.json` | the donor npm entry points: node suite, adapter isolation, warm watch, benches, evidence, facade isolation | REPLACE | F6 | pending |
| `implementation/scripts/check-ui-adapter-isolation.cjs` | proof that the donor node test build pulls in no adapter | MOVE | F6 | pending |
| `implementation/scripts/check-ui-facade-isolation.cjs` | proof that a single-view consumer elides the rest of a view library | MOVE | F6 | pending |
| `implementation/scripts/check-ui-warm-watch.cjs` | the warm-watch recompile probe runner | MOVE | F6 | pending |
| `implementation/scripts/check-ui-mounted-prod-elision.cjs` | proof that mounted-view debug machinery elides in production | MOVE | F6 | pending |
| `implementation/scripts/run-ui-bench.cjs` | the parity-bench runner | MOVE | F6 | pending |
| `implementation/scripts/run-ui-g13.cjs` | the mass-mount evidence runner | MOVE | F6 | pending |
| `implementation/scripts/lib/g13-timing-evidence.cjs` | the mass-mount timing-evidence library | MOVE | F6 | pending |
| `implementation/scripts/lib/g8-latency-evidence.cjs` | the input-latency evidence library | MOVE | F6 | pending |
| `implementation/scripts/bundle-isolation-positive-control/*` | the positive control proving the donor client sentinel is emitted | MOVE | F6 | pending |
| `implementation/scripts/_release-ui-required-gate.test.cjs` | the guard asserting the donor is a required artifact of the release train; the donor is never published, so nothing succeeds it | DELETE | F6 | done |
| `implementation/scripts/_ui-deps-edn-boundary.test.cjs` | the guard scoping optional artifacts out of the donor's production dependencies | REPLACE | F6 | pending |
| `.github/scripts/verify-version-lockstep.sh` | the release inventory's assertion that the donor artifact is publishable; the surrounding inventory serves other artifacts and stays | DELETE | F6 | done |
| `.github/scripts/report-changed-surfaces.sh` | the changed-surface router that maps donor paths to gates | REPLACE | F6 | pending |
| `.github/workflows/test.yml` | the donor-suite jobs on the pull-request train | REPLACE | F6 | pending |
| `.github/workflows/release.yml` | the donor deploy leaf, pre-deploy JVM test, and release-body row | DELETE | F6 | done |
| `.github/workflows/lint.yml` | donor paths in the lint surface | REPLACE | F6 | pending |
| `.github/workflows/portability.yml` | donor paths in the portability matrix | REPLACE | F6 | pending |
| `TESTING.md` | the donor rows of the canonical test matrix | REPLACE | F6 | pending |

## Donor obligations in the spec tree

The programme migrates existing canonical owners rather than creating a parallel
spec family, so these rows are obligations on documents that stay, not documents
that move wholesale. Each row is disposed by the slice that migrates the
surface it names.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `spec/004D-Freehand-Compiled-Grammar.md` | the donor-era compiled view language, moved intact out of 004 by rename so 004 could become the common contract; the moved file is where the donor spelling now lives and evolves into the v1 compiled grammar | MOVE | F0 | done |
| `spec/004B-UI-Tree-and-Conversion.md` | the semantic tree and conversion tables, generalized from donor names to both modes | MOVE | F1 | done |
| `spec/004C-Roots-and-Mount.md` | Root Descriptor, identity, mount, hydration, and teardown, re-spelled for the paved path | MOVE | F1 | pending |
| `spec/006-ReactiveSubstrate.md` | the observation-port contract plus its packaging and sole-consumer text, which names the donor today | MOVE | F2 | pending |
| `spec/008-Testing.md` | structural and mounted testing, the host and mode matrix, and the cross-mode conformance contract | MOVE | F1 | pending |
| `spec/009-Instrumentation.md` | diagnostic ids, evidence and retention fields, lifecycle facts, and error egress | MOVE | F4 | pending |
| `spec/011-SSR.md` | server rendering, hydration, fallback, and server-error projection over the donor tree today | MOVE | F5 | pending |
| `spec/012-Routing.md` | route-link href and click semantics; the donor view is replaced by the Freehand descriptor over the same seam | MOVE | F5 | pending |
| `spec/API.md` | the public-name inventory carrying the donor's exported surface | REPLACE | F6 | pending |
| `spec/Ownership.md` | the contract-surface ownership map naming the donor as an owner | REPLACE | F6 | pending |
| `spec/Conventions.md` | reserved namespaces and packaging conventions minted under donor names | REPLACE | F6 | pending |
| `spec/conformance/S3-view-conformance-profile.md` | the donor stage-3 conformance profile — evidence during migration, never a second Freehand authority | DELETE | F6 | pending |
| `spec/conformance/S4-view-conformance-profile.md` | the donor stage-4 conformance profile | DELETE | F6 | pending |
| `spec/conformance/S5-view-conformance-profile.md` | the donor stage-5 conformance profile | DELETE | F6 | pending |
| `spec/Pattern-StatefulComponents.md` | the stateful-component pattern, written around donor component-local state | REPLACE | F4 | pending |
| `spec/api-manifest.edn` | the generated public-API manifest, which inventories donor namespaces | REPLACE | F6 | pending |
| `spec/api-manifest-metadata.edn` | the manifest's hand-maintained metadata for donor namespaces | REPLACE | F6 | pending |

## Donor consumers in the implementation tree

The donor is optional, but it is not isolated: sibling artifacts pin its
coordinate and test against it. Every row here is a live consumer the census
finds today — a require or a coordinate that would break the moment the donor
artifact is deleted.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `implementation/ssr/deps.edn` | the donor artifact coordinate pinned on the SSR artifact's build and test aliases | REPLACE | F6 | pending |
| `implementation/ssr/src/re_frame/ssr/ui_tree.cljc` | SSR's deliberate copy of the donor conversion and semantic-normalization rules, plus the tree-version gate it reads from them | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/emit_ui_tree_cljs_test.cljc` | the SSR emitter proved over donor-produced structural trees | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/render_static_jvm_test.clj` | static server rendering of donor views on the JVM | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/root_manifest_cljs_test.cljc` | Root Manifest discovery across the donor hydrate-root seam | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/hydrate_root_seam_dom_cljs_test.cljs` | the hydrate-root seam mounted over a donor root in the browser | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/*_hydration_dom_cljs_test.cljs` | phase-flip and presence hydration in the browser over donor roots | MOVE | F5 | pending |
| `implementation/ssr/test/re_frame/ssr/client_only_adoption_verification_dom_cljs_test.cljs` | client-only adoption verification over a donor root | MOVE | F5 | pending |
| `implementation/adapters/reagent/test/re_frame/observation_port_watchable_host_*` | the observation-port cross-host tests, which pair a Reagent host against the donor | MOVE | F2 | pending |
| `implementation/core/test/re_frame/elision_probe.cljs` | core's production-elision probe, which compiles donor views to prove the debug machinery is stripped | MOVE | F3 | pending |
| `implementation/scripts/api-manifest/deps.edn` | the donor artifact coordinate on the API-manifest generator's build-only classpath | REPLACE | F6 | pending |
| `implementation/scripts/api-manifest/src/re_frame/api_manifest/gen.clj` | the generator's namespace roster, which names the donor test surface | REPLACE | F6 | pending |
| `implementation/scripts/api-manifest/src/re_frame/api_manifest/ui_context.clj` | the donor AI context-sheet generator and its classification drift check | MOVE | F6 | pending |
| `implementation/scripts/api-manifest/test/re_frame/api_manifest/ui_context_test.clj` | the context-sheet generator's own regression tests | MOVE | F6 | pending |
| `implementation/scripts/api-manifest/probe/test/re_frame/api_manifest/cljs_manifest_probe_cljs_test.cljs` | the CLJS-publics probe test over donor namespaces | MOVE | F6 | pending |

## Donor consumers in tools

Nothing under `tools/` may keep a donor dependency at the gate. These rows are
disposed when the consumer moves to Freehand names.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `tools/story/deps.edn` | the donor artifact coordinate on the story classpath | REPLACE | F6 | pending |
| `tools/story/src/re_frame/story/late_bind.cljc` | the late-bound seam story publishes for donor views | MOVE | F6 | done |
| `tools/story/src/re_frame/story/sub_overrides.cljc` | the author surface behind the donor subscription-override carriage | MOVE | F6 | done |
| `tools/story/src/re_frame/story/play/*` | presence, presence host, runner, and runner events reaching into the donor presence runtime | MOVE | F6 | done |
| `tools/story/test/re_frame/story/play/presence_*` | story's play-presence tests, mounted over the donor presence runtime | MOVE | F6 | done |
| `tools/story/test/re_frame/story/view_tool*` | story's view-tool tests over donor projections | MOVE | F6 | pending |
| `tools/story/test/re_frame/story/realworld_ui_consumer_cljs_test.cljs` | story's end-to-end consumer test over a donor example | MOVE | F6 | pending |
| `tools/story/spec/017-Testing-Story.md` | story's testing contract where it names the donor | REPLACE | F6 | done |
| `tools/xray/deps.edn` | the donor artifact coordinate on the xray classpath | REPLACE | F6 | done |
| `tools/xray/src/day8/re_frame2_xray/viewcell_evidence.cljs` | xray's reader over the reactor evidence plane | MOVE | F6 | done |
| `tools/xray/src/day8/re_frame2_xray/panels/reactive_panel_*` | the xray panel projecting donor view records | MOVE | F6 | done |
| `tools/xray/test/day8/re_frame2_xray/viewcell_evidence_cljs_test.cljs` | xray's evidence-reader tests over donor view records | MOVE | F6 | done |
| `tools/xray/test/day8/re_frame2_xray/realworld_ui_evidence_cljs_test.cljs` | xray's end-to-end evidence test over a donor-authored example | MOVE | F6 | done |
| `tools/xray/spec/*` | xray's own spec pages naming the donor substrate | REPLACE | F6 | done |
| `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/view_tool.cljs` | the pair server's view tool over donor projections | MOVE | F6 | done |
| `tools/re-frame2-pair-mcp/src/re_frame2_pair_mcp/tools/descriptors_data.cljs` | tool descriptors naming donor view vocabulary | REPLACE | F6 | done |
| `tools/re-frame2-pair-mcp/spec/003-Tool-Catalogue.md` | the pair tool catalogue where it names the donor | REPLACE | F6 | done |
| `tools/template/resources/day8/re_frame2_template/_ui/*` | the generated donor scaffold variant: deps, build config, entry namespace, views, readme | DELETE | F6 | done |
| `tools/template/resources/day8/re_frame2_template/template.edn` | the variant menu entry that offers the donor scaffold | REPLACE | F6 | done |
| `tools/template/spec/001-Substrate-Variants.md` | the template's substrate-variant contract | REPLACE | F6 | done |
| `tools/mcp-conformance/test/end-to-end-re-frame2-pair.cjs` | the cross-server conformance run that mounts a donor app | MOVE | F6 | done |

## Donor consumers in examples and testbeds

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `examples/ui/minimal-counter/*` | the minimal runnable donor scaffold example | MOVE | F6 | done |
| `examples/real-apps/realworld_resources/ui_*` | the donor-authored views of the realworld example, including its compiled editor and counter | MOVE | F6 | pending |
| `tools/xray/testbeds/feature_matrix/scenarios.cjs` | the xray feature-matrix scenarios that mount donor views | MOVE | F6 | done |

## Donor material in docs and skills

Migration completeness covers guides and authoring aids, so they carry rows too.
They are grouped at directory level: none of them is load-bearing for a semantic
decision, and each moves as one editorial pass.

| Donor row | What it is | Disposition | Slice | Status |
|---|---|---|---|---|
| `docs/core/re-frame.ui/*` | the donor guide chapters: mental model, building a view, state, events, presence, reactivity, interop, SSR, testing, custom elements | MOVE | F6 | done |
| `docs/core/how-to/install-re-frame-ui.md` | the donor install how-to | MOVE | F6 | done |
| `docs/core/how-to/measure-before-paint.md` | the before-paint measurement how-to, written against donor forms | MOVE | F6 | done |
| `docs/core/views.md` | the view-layer overview that routes readers to the donor | REPLACE | F6 | done |
| `docs/api/re-frame.ui*.md` | the generated donor API pages | REPLACE | F6 | pending |
| `skills/re-frame2-ui/*` | the donor authoring skill, its references, and its packaging | MOVE | F6 | pending |
| `skills/reagent-migration/*` | the migration skill's donor target vocabulary | REPLACE | F6 | done |
| `mkdocs.yml` | navigation entries for the donor guide and API pages | REPLACE | F6 | pending |
