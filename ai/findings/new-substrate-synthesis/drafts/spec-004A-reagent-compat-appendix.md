# Spec 004A — The stock-Reagent compatibility tier (live appendix to Spec 004)

> **Status: DRAFT — 2026-07-12. Promotes to `spec/004A-Reagent-Compat.md` at S7, with
> the adapter deletion wave** (per the ratified Adapters decision's proof / default /
> soak gates and the binding codex2 disposition, rows 4 and 7 — a **live** compatibility
> appendix, not git-history provenance). **Until the wave lands, this page has no
> normative force: the pre-rewrite [Spec 004](004-Views.md) and
> [Spec 006](006-ReactiveSubstrate.md) govern the shipping tier**, via the rewrite
> draft's [TRANSITION] markers. At promotion this page becomes the tier's owning spec
> ([Ownership.md](Ownership.md)'s re-scoped Reagent row names it), the `⟨source⟩`
> provenance tags below are stripped, and the carried-forward sections named in
> [§The carried-forward sections](#the-carried-forward-sections-promotion-roster) land
> here as live text. ⟨09 codex2 rows 4, 7; reagent-compat-boundary §8;
> spec-004-rewrite-draft §Removed forms + [TRANSITION]⟩

This appendix is the **live normative home of the frozen stock-Reagent tier**: stock
Reagent (Form-1 / Form-2 / Form-3), the `reg-view` family, the ratom substrate, and the
Reagent adapter — correct but frozen. It sorts beside the rewritten
[Spec 004](004-Views.md), which points here from its §Removed forms; the contract is
addressable, current, and versioned with the repo — a git tag of the pre-rewrite
revisions is kept as **provenance only, never a normative home**.

## Scope

### What the compatibility tier IS

- **Stock Reagent as a supported rendering tier.** Form-1 / Form-2 / Form-3 components,
  ratoms and Reagent's reaction machinery, hiccup render trees, and the `reg-view`
  registration family, exactly as specified by the carried-forward sections
  ([roster below](#the-carried-forward-sections-promotion-roster)). Artefact:
  `day8/re-frame2-reagent`, namespace `re-frame.adapter.reagent`. Semantics are
  **frozen at the S7 wave**: the contracts on this page describe behaviour that MUST
  NOT change. ⟨08 §5 Adapters; rewrite [TRANSITION]⟩
- **Supported for existing apps, with no sunset promised.** Apps on this tier keep
  running, keep their CI surface ([§Frozen-tier guarantees](#frozen-tier-guarantees)),
  and keep receiving correctness fixes. No removal is scheduled; any future sunset is a
  demand-reviewed post-1.0 decision, not a v1 promise. ⟨reagent-compat-boundary §8⟩
- **A rendering boundary, never a state boundary.** The dataflow layer — frames,
  events, subs, fx, machines, schemas, routes, resources — is tier-independent: one
  process-global registrar, one frame registry, one epoch stream. Frozen-tier views and
  `ui/defview` trees dispatch into and subscribe against the same frames. Nothing in
  this appendix moves state across anything; only *rendering* crosses.
  ⟨reagent-compat-boundary §1⟩
- **The step-1 migration landing zone.** A Reagent app moves its dataflow onto
  re-frame2 while its views stay on this tier (one enumerated adjustment class —
  [§The boundary contract](#the-boundary-contract) point 3), and gains Xray, epochs and
  time-travel, Story, schemas, machines, and resources immediately. Step 2 — the view
  rewrite — is per-subtree, on the app's own schedule
  ([§Leaving the tier](#leaving-the-tier-migration)). ⟨10 headline⟩

### What the compatibility tier is NOT

- **No new capabilities.** No new exports, options, hooks, or semantics are ever added
  to a retained surface. Any diff that would touch a compat-tier export still passes
  the standing diff-time facade-classification rule — and the classification for a new
  capability here is "rejected: freeze rule". ⟨reagent-compat-boundary §8;
  Conventions §Facade policy⟩
- **No parity with `ui` features.** The compiled substrate's guarantees — memo by
  default, the epoch/commit contract (one notification per cell per epoch,
  correct-before-paint), compiled HMR (stable shells, hook-signature analysis),
  Activity/presence semantics, the JVM structural subset — apply to `ui` cells only,
  never to frozen-tier components. This is stated honestly, not patched.
  ⟨08 §5 Adapters "no parity"; reagent-compat-boundary §2⟩
- **Not the taught surface.** `ui/defview` is the only *taught* component form; the
  frozen tier is taught on exactly one migration page. This appendix is a contract
  reference, not a tutorial. ⟨rewrite [TRANSITION]⟩
- **Not UIx, Helix, or reagent-slim.** Those adapters (and their API/Conventions rows,
  their smokes, and the ×3 shared-suite parameterisation) are **deleted** at the S7
  wave, not frozen. `day8/reagent-slim` deletes with them; the retained
  `with-resource-lease` row below loses its slim shipping note at promotion. A git tag
  preserves the deleted adapters as provenance. ⟨08 §5 Adapters; rewrite [TRANSITION]⟩
- **No artifact coupling with `re-frame.ui`.** `day8/re-frame2-ui` never depends on the
  compat adapter and vice versa (G-12); boundary-crossing code lives in application
  namespaces and traffics in plain React values. ⟨reagent-compat-boundary §1⟩

## The retained surface

The rows below moved here from [API.md](API.md) and [Conventions.md](Conventions.md)
under a **`v1 (frozen — compat tier)`** status — **relocated, not removed** (this
supersedes any earlier instruction to delete them). Each row gives the one-line
contract; the full semantics live in the carried-forward section named in the last
column (all of which are this appendix's live text from promotion onward).
⟨reagent-compat-boundary §8; rewrite §Cross-spec ripple inventory⟩

### Facade and adapter exports

| Surface | Kind · tier | Contract (one line) | Full semantics |
|---|---|---|---|
| `re-frame.core/reg-view` | M · front-porch | Defn-shape macro: auto-defs the symbol, auto-derives the id `(keyword (str *ns*) (str sym))` (override `^{:rf/id …}`), auto-injects lexical frame-bound `dispatch` / `subscribe` at render, rejects non-defn-shape bodies at macroexpand with an error naming `reg-view*`. Returns the registered id. | §`reg-view` is the multi-frame contract ⟨pre-rewrite 004⟩ |
| `re-frame.core/reg-view*` | Fn · advanced | Plain-fn registration under a supplied id — no auto-def, no auto-inject, no compile check; the lane for computed ids, library-generated views, Form-3 (`create-class`), and registration without a Var. | §`reg-view*` — the plain-fn escape hatch ⟨pre-rewrite 004⟩ |
| `re-frame.core/view` | Fn · advanced | `(view id)` → the **wrapped (frame-aware) render-fn** (never hiccup); re-resolved on every call so hot-reload re-registration is picked up; `nil` on a lookup miss. Distinct from the wave-2 `ui/view` (which does not exist in v1). | §`view` — the canonical post-registration lookup ⟨pre-rewrite 004⟩ |
| `re-frame.core/frame-provider` | Component (Reagent) · front-porch | SCOPE-only: provides an **already-created** frame id through React context (`:frame` accepts a keyword or a live frame value); creates / re-seeds / destroys nothing; fails loud when the frame is absent (`:rf.error/frame-provider-frame-absent`). Scopes the frozen tier AND both boundary directions. | Frame-side mechanics stay owned by [002-Frames.md](002-Frames.md); the Reagent-component realisation freezes here ⟨checked-in API §View ergonomics⟩ |
| `reagent-adapter/adapter` | Var (map) · adapter | The 11-key adapter spec map (`:kind` + the ten contract fns), ratom-backed, passed to `(rf/init! …)`. | §The Reagent adapter contract ⟨pre-rewrite 006⟩ |
| `reagent-adapter/flush-views!` | Fn · adapter | `(flush-views!)` / `(flush-views! f)` — flush pending Reagent renders synchronously (wraps React's `act()`); the frozen tier's canonical test flush. | §The Reagent adapter contract ⟨pre-rewrite 006, 008⟩ |
| `reagent-adapter/set-hiccup-emitter!` | Fn · adapter | Install the render-tree → HTML fn for `render-to-string` — the frozen tier's SSR late-bind seam. | §The Reagent adapter contract ⟨pre-rewrite 006, 011⟩ |
| `reagent-adapter/with-resource-lease` | Component (Reagent) · adapter | Form-3 component holding a resource-liveness lease for its mounted lifetime (`:rf.resource/ensure` on mount, `:rf.resource/release-owner` on unmount; re-leases on descriptor / frame / cause change). | §The Reagent adapter contract ⟨pre-rewrite 006, EP-0020⟩ |
| `expand-reg-view` / `parse-reg-view-args` | `^:no-doc` carve-outs | Unrowed internals of the `reg-view` macro (canonical homes in `re-frame.core-reg-view-macro`); live exactly as long as the macro lives. | Not rowed — carve-outs, as today ⟨checked-in API §Not-rowed internal carve-outs⟩ |

### Retained Conventions rows (re-scoped, not removed)

- **The facade export lists.** `reg-view` / `reg-view*` remain in
  [Conventions.md](Conventions.md)'s per-kind registration-macro list and the no-bang
  registration bucket, re-statused `v1 (frozen — compat tier)` with this appendix as
  the pointer target. The *taught* view surface is the `re-frame.ui` namespace.
- **The `*`-suffix pair table.** The `reg-view` / `reg-view*` row survives — the pair
  still exists, in this appendix — and the asymmetry footnote is re-worded accordingly
  (the family remains deliberately asymmetric; only macros with a genuine reason carry
  a facade-level `*` partner). [Cross-Spec-Interactions §21](Cross-Spec-Interactions.md#21-family-asymmetry--only-reg-view-keeps-a--suffixed-fn-partner)
  re-scopes to match.
- **The auto-id derivation rule.** `id = (keyword (str *ns*) (str sym))`, overridden
  only by `^{:rf/id :explicit/id}` symbol metadata; computed ids drop to `reg-view*`.
  The identical derivation is also the `defview` id rule — one rule, stated once on
  each side; neither statement is the other's owner.

⟨reagent-compat-boundary §8; checked-in Conventions §§registration buckets,
`*`-suffix table, auto-id rule⟩

### The carried-forward sections (promotion roster)

At S7 promotion the following pre-rewrite sections are carried into this appendix as
**live text** (verbatim apart from link re-pointing; the pre-rewrite files' revisions
are then superseded by the rewrite and this page):

1. **From pre-rewrite [Spec 004](004-Views.md):** §`reg-view` is the multi-frame
   contract (shape, compile-time error contract, `reg-view*`); §Calling a registered
   view; §How registered views are used in hiccup (canonical Var form, `view` lookup,
   the alternative function-position form, the bare-keyword-head rejection —
   rf2-n82bbu); §Plain Reagent fns: no frame injection (including
   `:rf.error/no-frame-context` and the `capture-frame` affordance); §Form-1, Form-2,
   Form-3 components; §View registry — tooling surface (source-coord capture);
   §Composing registered views; §Hot-reload behaviour for re-registered views.
2. **From pre-rewrite [Spec 006](006-ReactiveSubstrate.md):** the Reagent realisation
   of the adapter contract (§CLJS reference: Reagent as default adapter, per-contract-fn
   pseudocode, sub-cache wiring); §Frame-provider via React context, including the
   `:adapter/current-frame` late-bind hook, the `:r>` prop-conversion bypass, and the
   **plain-fn footgun** paragraph; §The sub-override subscribe seam (Story's
   `:sub-overrides` rung keeps working on this tier); §Source-coord annotation and
   §View tagging contract (the Reagent render-time wrapper machinery — the compiled
   substrate stamps coords at compile time instead); §Lazy-seq deref tracking.
3. **From [Spec 009](009-Instrumentation.md):** the error-catalogue rows thrown from
   `re-frame.core-reg-view-macro` and the `:>`-head SSR error row freeze into this
   appendix (the one-catalogue rule still holds — the rows keep their catalogue ids;
   this appendix becomes their owning-spec column). The frozen tier continues to emit
   the `[view-id instance-token]` `:render-key` wire shape and the
   `[:rf.view/anonymous nil]` fallback for unregistered render fns; the compiled
   substrate's versioned evidence schema is a different, coexisting shape.
4. **The boundary contract** ([drafts/reagent-compat-boundary.md](reagent-compat-boundary.md)
   §§1–7 and 9) is promoted into this appendix in full — see the next section.

⟨rewrite §Cross-spec ripple inventory rows for 004/006/009;
reagent-compat-boundary §8⟩

## The boundary contract

The full two-direction co-mount contract is
[reagent-compat-boundary.md](reagent-compat-boundary.md) — the owning contract, promoted
into this appendix at S7. Its normative content, summarised (the summary defers to the
contract on every point):

1. **Three granularities, coarsest first.** Sibling roots (share frames, nothing else —
   the recommended first cut); inward nesting via `ui/raw`; outward nesting via
   `ui/->react`. One React root owns any mixed tree; nested React roots are not part of
   the contract; frames are owned by neither tier and never by a boundary.
   ⟨reagent-compat-boundary §§1, 5⟩
2. **Inward — `ui/raw`.** A frozen-tier subtree embeds inside a `ui` tree via
   `(ui/raw (reagent.core/as-element [legacy-view …]))` — same-root embedding, no
   second React root, no second `createRoot`. Reagent's reactive machinery runs
   untouched inside the subtree; the `ui` epoch/commit guarantees stop at the boundary.
   Registered legacy views read the ambient frame through React context under the
   shared-context-object rule; until that rule is confirmed (**[S6-CONFIRM]**), the
   conservative authoring rule stands — an explicit `[rf/frame-provider {:frame …}]` at
   the top of the embedded hiccup. Callbacks bridge via `(ui/dispatch-fn)` passed as a
   prop. Teardown is total on each side's own terms; do not place embedded legacy
   subtrees under `ui` Activity-hidden or presence-retained regions pending the named
   fixture. ⟨reagent-compat-boundary §2⟩
3. **The plain-fn contract.** The checked-in frame-resolution contract carries: a plain
   (unregistered) Reagent fn lacks `reg-view`'s `:contextType` wiring, cannot read the
   enclosing `frame-provider`, and its **ambient** `(rf/subscribe …)` /
   `(rf/dispatch …)` raises `:rf.error/no-frame-context` — no silent `:rf/default`
   fall-through. Three classes complete migration step 1 unchanged: registered views;
   pure presentational plain fns; explicit-frame plain fns (the `{:frame f}` opts
   forms, a passed `(rf/capture-frame)` bundle, `with-frame` on non-deferred paths).
   The one class needing the step-1 adjustment is plain fns making ambient frame-scoped
   calls; the prescribed rewrite, in preference order: register the fn → hoist the
   ambient op to the nearest registered ancestor → make the frame explicit.
   ⟨reagent-compat-boundary §4; checked-in 006 §Plain-fn footgun⟩
4. **Outward — `ui/->react` (v1, lands S6).** `(ui/->react view)` exports a `defview`
   as a React component for a remaining Reagent (or JS) parent: memoised per view id
   (the identical stable-shell object across calls and HMR generations), no new React
   root / root manifest / host preflight; the exported view **scopes and resolves**
   frames by the ordinary chain (explicit pin → dynamic binding → React context → loud
   `:rf.error/no-frame-context`) and never creates them. Props follow the declared
   props ABI; from Reagent hiccup, `[:> Exported {…}]` camelises keys — the blessed
   spellings (single-segment unqualified names, the `[:r> … #js{…}]` bypass, or
   boundary renames) are the contract's §3. ⟨reagent-compat-boundary §3⟩
5. **Frame propagation across the boundary — both directions** rides one requirement:
   the compiled substrate's React-context frame tier and this tier's `frame-provider`
   MUST bind to the **same context object** (`re-frame.adapter.context`), per the
   checked-in shared-context precedent. **[S6-CONFIRM]** at implementation — resolved
   by the S6 migration-wave bead that lands `ui/->react` (the boundary contract's
   implementing bead), together with provider retention under production erasure. The
   contract's §9 roster enumerates every open confirmation and that same bead owns the
   roster; none changes this tier's own frozen semantics — they confirm the
   *crossing*, not the tier. ⟨reagent-compat-boundary §§2, 9⟩

## Frozen-tier guarantees

### What CI keeps green

Two suites exist after the wave; this tier owns one of them, plus exactly one browser
smoke. ⟨reagent-compat-boundary §7; 11 W9/W13⟩

- **`reagent-compat` — the frozen-tier contract suite.** Pinned once at the freeze,
  then maintenance-only. It carries: the Spec 006 adapter-contract fixtures against
  `reagent-adapter/adapter` (state container, subscribe-container, derived values,
  flush, dispose); `reg-view` / `reg-view*` registration and frame-context resolution
  fixtures, **including the plain-fn footgun fixture** (`:rf.error/no-frame-context`
  raised, no silent default); `frame-provider` SCOPE fixtures; `with-resource-lease`;
  the sub-override subscribe seam; and the boundary fixtures in both directions
  (inward embedding with frame scoping + teardown/leak check; outward export with
  frame acquisition, teardown/leak check, and the
  stable-component-object-across-HMR assertion; the Activity/class-component
  lifecycle probe).
- **Exactly one browser smoke.** The Reagent adapter smoke
  (`implementation/adapters/reagent/testbed/spec.cjs` — mount + dispatch + assert)
  survives; the UIx and Helix smokes delete with their adapters. The legacy adapter
  matrix collapses ×3 → ×1, and that ×1 is this tier's smoke.
- **`ui-conformance` is the other suite** — the `re-frame.ui` contract surface. It
  replaces the deleted `react_shared_suite` and is NOT part of this tier; no reading of
  "all legacy coverage is retired" is correct, and none of this tier's coverage rides
  it.

### What "frozen" means for the bug policy

- **Correctness fixes: yes.** A behaviour that violates a contract on this page (or a
  carried-forward section) is a bug and MAY be fixed at any time; fixes that unblock
  migration are prioritised. A fix restores the frozen contract; it never redefines it.
- **Semantics changes: no.** No behaviour change that any conforming consumer could
  observe as a contract change — no new exports, no new options, no relaxed errors, no
  `ui`-feature parity, no performance work that alters observable ordering. Where a
  frozen behaviour is unfortunate, the remedy is migration
  ([§Leaving the tier](#leaving-the-tier-migration)), not evolution.
- **The catalogue stays honest.** The frozen tier's error/warning ids keep their
  [Spec 009](009-Instrumentation.md) catalogue rows (owning spec: this appendix); a
  correctness fix that surfaces a new diagnostic still needs its catalogue row (the
  one-catalogue rule) — that is a fix's paperwork, not a new capability.

### HMR / SSR / Activity limits

The honest table — what each crossing supports for SSR, HMR, and Activity/presence —
is the boundary contract's §6, promoted into this appendix; it is normative by
reference and not restated here. Its headlines: sibling roots get full SSR and HMR,
each on its own tier's story; inward embeddings require the `client-only` sibling
fallback on SSR paths and get frozen-tier reload behaviour inside the boundary;
outward exports are **SSR-unsupported in v1** (placeholder container server-side,
client render after mount) while getting full `ui` HMR inside the export. Within the
tier itself, HMR remains the frozen tier's existing (weaker) story — no `ui`-grade
guarantees, stated honestly, never patched. ⟨reagent-compat-boundary §6⟩

## Leaving the tier (migration)

Migration off this tier is **step 2** of the two-step story
([the migration guide](../migration/from-re-frame-v1/README.md); derived from synthesis
doc 10 — at promotion this pointer targets the shipped migration page): the dataflow
layer moved in step 1 and never changes again; the view tier converts per-subtree, on
the app's schedule.

- **~80–90% is mechanical** (scriptable rules: Form-1 → `defview` with map props, deref
  drops, dispatch-closure → event-vector lifting, hiccup unchanged, key metadata →
  props); ~10–15% is a local transformation with a decision (Form-2/`with-let` state →
  `local` or app-db; lifecycle → `effect`); ~1–5% is genuine redesign (reaction/cursor
  side-channels, render-phase effects — latent bugs this library exists to refuse).
  The migrator tool applies the mechanical tier and flags the rest.
- **Choose the coarsest granularity that fits**: convert whole roots first (sibling
  roots — no React bridging); embed remaining legacy widgets inward via `ui/raw`;
  export migrated subtrees into a remaining Reagent shell via `ui/->react` — the
  outward bridge is **v1 and lands S6** with the migration wave.
- **Each converted subtree immediately gains** memo-by-default, the Xray causes
  timeline, a static interaction surface, Tier-1 headless tests, `ui`-grade HMR, and
  the compiled render path. The payoff arrives per-subtree, not at the end.
- **re-com and third-party Reagent wrapper libraries are the last movers** — embedded
  inward under `ui/raw` until a `ui`-native equivalent exists; a separate, deferrable
  decision per library.

⟨10 §§headline, Tiers M/D/R, mechanics; reagent-compat-boundary §1⟩
