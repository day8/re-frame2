# EP-0031: The re-frame.ui Programming Model

Status: accepted
Type: standards-track

> **Graduation banner.** The S1/S2 surfaces of this EP are already spec-live:
> [`spec/004-Views.md`](../../spec/004-Views.md) (with companions
> [004B](../../spec/004B-UI-Tree-and-Conversion.md),
> [004C](../../spec/004C-Roots-and-Mount.md),
> [004D](../../spec/004D-UI-Test-Selectors.md)) is the normative home, and per
> EP-0009 **where this EP and the spec differ, the spec governs**. The spec's
> §Stage conformance profiles device applies throughout: rows tagged S3+ are
> *declared, not yet asserted* — text final, enforcement riding the S3+ slices.
> This EP is the durable design record behind that contract: what was decided, why,
> what was rejected, and which accepted pieces are still landing (each marked below
> with its live bead).

## Abstract

`re-frame.ui` introduces, for the new compiled substrate, one authoring contract:
**`defview` is the one component form** (one props map, memoized by default on a
generated `rf=` comparator); **templates are hiccup with the ambiguities removed**,
compiled — never interpreted — under a closed macro grammar in expression positions;
**event handlers are data** (event vectors with a closed placeholder vocabulary,
plus a small explicit-form decision table for what genuinely is not data);
view-side state and lifecycle are exactly four body forms (`local`, `effect`,
`lease`, `frame`); every escape to the host world is a **named interop boundary**
with a stated cost. Everything else — Form-1/2/3, `reg-view`, positional args,
ratoms, `:on-mount` — is a normative absence.

## Motivation

The Reagent-family authoring surface cannot be compiled, analyzed, or taught
honestly: three component forms with different closure semantics; handlers as
anonymous closures that capture stale renders and defeat memoization; hiccup
interpreted at runtime by a walker that guesses at tags, components, and seqs;
ratoms silently creating a second reactivity model. Each is an unresolved
alternative the synthesis had to close — which components exist, what a handler
*is*, what the compiler may see through, where ephemeral state may live, how the
foreign-React world is admitted. Those are feature-level public-contract decisions
with real rejected alternatives: an EP, not a bead. The full design study is
`ai/findings/new-substrate-synthesis/02-programming-model.md` (final 2026-07-11,
codex2 fold-in 2026-07-12, component-library amendments 2026-07-16); this EP records
its decision surface durably.

## Goals / Non-Goals

Goals — the **authoring contract** of the compiled substrate:

- the one component form and its props/memo/registration semantics;
- the template grammar, including the closed macro grammar in expression positions;
- handlers-as-data: the vector form, placeholders, the decision table, the
  controlled-input synchrony door and its 2026-07-16 widening;
- the four body forms (`local` / `effect` / `lease` / `frame`) and the `local`
  placement law;
- the interop boundary rows and their staging.

Non-goals (owned elsewhere; one EP, one decision surface): reactivity, ownership,
and the observation port (**EP-0032**); evidence, manifests, and instrumentation
(**EP-0033**); the readiness deltas' own design records (**EP-0035** — summarized
here only where they amend this surface); SSR/roots/hydration mechanics beyond the
authoring seam (Specs 004C/011); Reagent migration mechanics (doc 10; S6/S7).

## Relationships

- **EP-0030** — the program umbrella for the compiled-substrate rewrite; this EP is
  its programming-model pillar.
- **EP-0032 (reactivity)** owns `sub`'s semantics; this EP owns only the call-site
  grammar (`(sub [:query …])` as a compile-indexed site).
- **EP-0033 (evidence)** owns the compiler manifest and committed instance records;
  this EP's grammars are what make those surfaces statically derivable.
- **EP-0035 (readiness deltas)** owns the component-library readiness package
  (`drafts/component-library-readiness.md`); its amendments to this surface are
  folded in below with stage-honesty markers.
- **EP-0018 (one event registration)** supplies the registered-event world
  handlers-as-data dispatches into (the dev unregistered-id warning is the seam);
  **EP-0025 (classification)** governs the posture `:props` schemas and production
  elision respect.
- Normative homes: `spec/004-Views.md` + 004B (tree/conversion), 004C (roots/mount),
  004D (test selectors).

## Specification

Stage-honesty legend, used per item: **[spec-live]** = asserted by the landed S1/S2
conformance slices; **[accepted → S3+]** = ruled and in spec text as declared rows,
enforcement landing with the cited bead.

### 1. `defview` — the one component form [spec-live, S1]

- **Zero or one argument, semantically a props map.** Header destructuring lowers to
  direct property reads on the host props object; `:as` opts into materialization at
  a documented dev cost. **No positional args.**
- **Props ABI:** deterministic quoted JS property names preserving namespace + name;
  `:key` is reserved (a literal `:key` prop is a compile error); `:children` compares
  as one slot; the manifest maps production slot indexes back to keywords.
- **Closed options map:** `:props` (Malli — compile-time on literal call sites, dev
  runtime on dynamic values, elided in production), `:id`, `:display-name`. Nothing
  else — `:memo false`, `:on-mount`/`:on-unmount`, and `:catch`/`:fallback` were
  considered and rejected (Resolved Decisions).
- **Registration:** `defview` defs a Var and registers under the registrar's `:view`
  kind (source metadata, template fingerprint, hook signature, capability bits) —
  Story mounts by view id, Xray lists by registry query, the Pair hot-swaps a view
  like an event handler.
- **Memo-by-default, no opt-out:** every internal view is memoized on a generated
  straight-line `rf=` comparator over its declared prop slots — per slot,
  `Object.is` OR CLJS value equality; host/foreign values fall through to identity.
  Teach as "React.memo, except CLJS data compares by value".

### 2. The template grammar [forms spec-live S1; expression grammar spec-live S2]

Reagent-familiar hiccup with the ambiguities removed. Control forms (`let`/`letfn`/
`if`/`if-not`/`when`/`when-not`/`cond`/`case`/statically-pure `do`/`for`) normalize
**into the AST**; both emitters (direct React code; the JVM structural tree owned by
004B) see through branches. Rejected at compile time with didactic messages: dynamic
tag heads, markup-returning `map`, keywords in child position, raw lazy seqs,
unkeyed list items, `sub`/`lease` in loops.

**Expression positions carry a closed macro grammar** (rf2-vxgfnd.100 lineage — the site-identity bead the spec cites — with the grammar
reconciled by rf2-vxgfnd.112/.224; owning text Spec 004 §Template grammar): expressions are value-opaque with respect to markup but
lexically audited for finite reactive ownership. Accepted: the binder-aware
structural tier (`quote`, `fn`, `let`, `loop`, `letfn`, `try`), the audited
transparent core macro set (`or` `and` `when` `when-not` `cond` `->` `->>` `some->`
`some->>` `cond->` `cond->>`), and ordinary function calls plus evaluated-only host
specials. **Every other macro — core or user — is rejected**
(`:rf.ui.compile/unsupported-form`): an unaudited expansion could hide a reactive
call from site indexing, falsify the manifest, and let production elide a ViewCell
that was not actually sub-free. The set grows only by ruling.

DOM prop spelling is pinned (`:on-click`, never `:on-keydown`); prop conversion is
compile-time, contextual, and total — one rule table (004B) serves static props,
`ui/spread`, and both emitters; custom elements get the bounded `ui/custom-element`
classification rule (name exported S1, behaviour asserts S4 — delta #1, 2026-07-12).

### 3. Handlers are data [vectors-as-data spec-live S1; committed behaviour → S3]

**Canonical: the event vector.** A vector in an `:on-*` position is the intent,
dispatched to the committed frame. The **placeholder vocabulary is closed (v1,
scalars only): `:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key`** — `:rf.ui/form-data`
and `:rf.ui/event` deliberately do not exist (both cases belong to `ui/event`).
Literal vectors are data: value-comparable, JVM-testable, retained as data in the
manifest and JVM tree.

**The decision table** (full table: Spec 004 §Handlers) covers the rest: `ui/event`
(committed phase + the live event; `nil` ⇒ no dispatch), `ui/handler` (imperative,
stable identity), `ui/render-fn` (render phase, pure — and, per the 2026-07-16
amendment, also the value an internal `ui/slot` accepts), the **narrow bare-fn law
(R-4)** — bare `#(…)` legal only in known native event properties, with a day-one
strict lint (`{:re-frame.ui/bare-handlers :warn|:error}`) instead of a language
flip — a compile error at foreign-component boundaries, and `ui/raw-fn` for
identity-as-protocol APIs and callback refs. Dynamic handler expressions classify at
runtime by type; placeholders are recognized in literal vectors only (dev warns on a
placeholder riding a runtime vector); loop handlers that capture the binding are
compile errors with the extract-a-keyed-child-view fix.

**The controlled-input synchrony door.** Dispatches from
`:on-input`/`:on-change`/`:on-before-input` on **compiler-proven controlled**
elements (the S-5 predicate: literal `:value`/`:checked` co-present with the handler
site) drain synchronously within the DOM event — the one sanctioned synchronous
door, protecting caret and IME. **[Amended 2026-07-16, accepted → S3, bead
rf2-8k14ia]:** the door widens by one arm — at a proven-controlled site, a
synchronous `ui/event` body whose result is an event vector rides the same drain
(`nil` = no dispatch; any other synchronous result is a diagnostic). This is the
reusable-input arm: a library control appends its payload to an event prefix
received through props. Everything else at such sites still batches with a
diagnostic; a compiled event-template projection form stays rejected (a second
handler language). The G-8 real-browser matrix (now including a reusable
event-prefix component arm) is the residual named gate.

**Internal fn props + the library event convention [ruled 2026-07-16]:** ordinary
function props between internal views are legal opaque values, identity-compared,
with no implicit invocation phase; component libraries accept event
vectors/prefixes and dispatch `(conj prefix payload…)`.

### 4. Body forms — `local`, `effect`, `lease`, `frame`

- **`local` [accepted → S3, bead rf2-vxgfnd.95.2]:** host component-local state,
  deliberately outside re-frame2 epochs — no frame-resident variant exists or is
  reserved. **[Amended 2026-07-16]** `(local init)` returns a **three-tuple
  `[value set! update!]`**: `set!` stores its argument exactly (a stored fn is a
  value, never an updater); `update!` applies `(f current & args)` to the **latest
  host state**, so several same-turn host writers compose instead of losing updates.
  Render-phase use stays a dev error; JVM raises the typed host-op error.
  Reset-key/derived local stays out (trigger-gated spike, EP-0035).
  **The placement law (F8, ruled 2026-07-12) is spec-live in 004:** a `local` value
  MAY be read by same-view committed handlers — committed slots include local
  ephemera (the guide's search-box seam is canonical); `local` is FORBIDDEN when the
  value needs cross-view observation, replay/persistence, schema/tool inspection,
  durable navigation semantics, or subscription-derived computation.
- **`effect` [accepted → S3, same bead]:** `rf=` value deps, cleanup on dep
  change/disconnect/unmount, StrictMode-replay-safe; `(effect :connect …)` is named
  for what it does — **there is no "once"** in a lifecycle React can replay.
- **`lease` [spec-live S2; view-level confirmation → S3]:** declares resource
  liveness, reconciled by one aggregated passive effect after commit; reads stay
  `(sub [:rf/resource …])`; never fetches during render.
- **`frame` [spec-live S2]:** the committed-frame ops bundle
  (`{:frame :dispatch :dispatch-sync :subscribe}`), incarnation-fenced, tiered
  advanced alongside `dispatch-fn`.

### 5. Interop boundary rows

| Row | Ruling | Stage honesty |
|---|---|---|
| `ui/raw` | embed a React element; SSR needs a `client-only` sibling | spec-live S1 (boundary corpus completes S4) |
| `ui/html` | trusted markup, low-friction — the visible call *is* the contract; manifests record the site | spec-live S1 |
| `ui/spread` | the one generic runtime prop-map conversion, driven by the 004B rule table | spec-live S1 |
| `ui/spread` safe-policy form | compiler-visible allow/deny: denied structural/controlled/identity keys (`:key` `:ref` `:value` `:checked` owned `:on-*`) fail in **every build**; allowed `:on-*` classify through the decision table; `aria-*`/`data-*` pass; preserves the controlled proof under passthrough | **accepted → S3, bead rf2-isdqjv** (delta #5, 2026-07-16; spelling at spec landing) |
| `ui/slot` | compiler-owned invocation of `ui/render-fn` values at **internal** render seams (row/cell/part renderers): only `render-fn` or `nil`; body compiled under the closed grammar, pure render phase; keys/fingerprints/`ui.test` structure preserved | **accepted → S3→S4, bead rf2-ri0k6n** (delta #4, 2026-07-16) |
| `ui/client-only` | mandatory capability-free fallback; one root phase-flip | accepted → S3 (flip completes S5) |
| `ui/error-boundary` | the explicit error component — catches render/lifecycle throws; `:on-error` dispatches after the failing commit; `:reset-key` clears | accepted → S3 |
| `ui/->react` | the outward migration bridge (export a view as a React component) | v1, **lands S6** (delta #2, ruled 2026-07-12) |
| `ui/element` / `ui/view` / `ui/portal` / `re-frame.ui.data` | wave-2, demand-gated — no v1 existence. Subsequent rulings (2026-07-16, EP-0035): general `ui/element` **rejected outright** (`rf2-a62fje`); `ui/view` stays gated on an open-set-identity trigger; `ui/portal` superseded-in-part by the inline+top-layer plan (`rf2-efxb1h`) | — |

The foreign-React hook tier (`re-frame.ui.react`, seven wrappers) belongs to this
surface; its contract detail is Spec 004 §The React interop tier (declared, asserts
S3).

### 6. Removed forms — normative absences [spec-live S1]

Form-1/2/3 and the `reg-view` family (not `re-frame.ui` forms — they belong to the
coexisting Reagent compatibility tier, live home `spec/004A-Reagent-Compat.md` at S7);
positional view args; plain render fns as
frame-aware views; ratoms/cursors/reactions (a second state model);
`:on-mount`/`:on-unmount`; Suspense-as-loading; the `h` macro and bare-keyword
view heads. Absences are compile errors + export checks from the first slice.

## Rationale

**Data over closures.** An event vector is readable in source, inspectable in Xray
before any click, assertable on the JVM without a DOM, and comparable — so memo
keeps working. Closures are none of these; they are admitted only where the thing
expressed genuinely is not data, and each admission names its invoker, phase,
identity, and cost (the decision table).

**Closed grammars are what make the compiler honest.** Site indexing (every
`sub`/`lease` a compile-indexed site; sub-free views eliding their ViewCell) is only
sound if the compiler can see every reactive call; one unaudited macro breaks it.
The same logic closes the options map, the placeholder vocabulary, and the AST node
set: each closure converts "anything might happen" into a surface tools and the JVM
emitter can rely on, and each grows only by ruling.

**One component form, memo with no opt-out.** A `:memo false` valve or a
"simple vs stateful" form split reintroduces exactly the taxonomy Reagent users
misuse; mutable foreign values belong at an explicit boundary, not a memo exemption.

**The door is narrow on purpose.** The substrate sanctions exactly one synchronous
drain, where a proof exists (the S-5 predicate) and correctness (caret/IME) demands
it. The 2026-07-16 widening extends the *handler form*, never the proof — the site
stays statically proven controlled — because otherwise precisely the reusable
inputs the guarantee exists for would forfeit it.

## Backwards Compatibility

Pre-alpha: clean breaks, no shims. The Reagent, UIx, and reagent-slim adapters live on
as first-class shipping surfaces under Spec 004's [TRANSITION] markers; the Reagent-tier
forms are retained (not deleted), with their live home `spec/004A-Reagent-Compat.md`
landing at S7. Only Helix is removed, at the S7 removal wave. Migration rides
`ui/->react` per-subtree co-mounting (S6; mechanics in doc 10).

## Bead Plan / Reference Implementation

**Graduated.** S1 and S2 landed complete (the Spec 004 rewrite merged atomically
with the first conforming Stage-1 slice per its R-1; S2 core verified S3-ready under
the rf2-vxgfnd.22 boundary review).

**Landing (the S3 epic, `rf2-vxgfnd.95`):** `.95.1` committed event spine —
**merged**; `.95.2` local three-tuple / effect / `dispatch-fn` / lease confirmation
— open; `rf2-8k14ia` sync-door widening — in progress (must land before `.95.1`'s
literal-only door is declared conforming); `rf2-ri0k6n` `ui/slot` — open (S3→S4
compiler surface); `rf2-isdqjv` safe-spread policy — open (blocks final S3
conformance); `.95.10` closes the stage. Later: custom-element assertion (S4),
`client-only` phase flip (S5), `->react` (S6), 004A landing + Helix removal (S7).

**Guide-impact assessment (EP-0009 rule 5).** The teaching seams are guide 03
(state) and guide 04 (events), and both are already written to this contract:
03 teaches the four-inputs table, the `local` three-tuple (P0-1 text landed
2026-07-16) and the search-box seam; 04 teaches vectors-first, the three
placeholders, the decision table, the widened door ("keep controlled fields
literal"), the component-library event-prefix convention (C-13), and
"lifecycle is not an event". Newly teachable payoffs: headless handler assertions,
Xray-before-any-click, and library controls that keep the IME/caret guarantee.

## Open Issues

None open on the decision surface. Named residuals — gates or bounded spec-landing
choices, not design questions: the G-8 real-browser matrix (including the reusable
event-prefix arm); the exact `ui/slot` name and safe-spread spelling (second arity
vs sibling), both delegated to spec landing by the 2026-07-16 direction.

## Resolved Decisions

- **F8 — the narrow `local` law + committed-slots ruling (2026-07-12).** Same-view
  committed handlers MAY read `local` values (committed slots include local
  ephemera); the earlier "forbidden if any handler ever reads it" strictness is
  superseded; the forbidden tier is defined by what the *value* needs.
- **R-4 — the narrow bare-fn law (2026-07-12, with the rewrite ruling set).** Bare
  fns only in known native event properties; strict lint over language flip.
- **Placeholder vocabulary closed (2026-07-12).** Three scalars;
  `:rf.ui/form-data` / `:rf.ui/event` rejected — those cases are `ui/event`'s.
- **`:on-mount`/`:on-unmount` rejected (2026-07-12).** Mechanical React lifecycle
  cannot carry domain "once" semantics; `effect :connect` is named for what it does.
- **S-5 door predicate confirmed (2026-07-12) + vector-outcome widening
  (2026-07-16).** The controlled proof (literal `:value`/`:checked` co-presence)
  stands; the handler-form clause widens by the synchronous-`ui/event`-vector arm;
  the event-template projection form stays rejected.
- **Blessed API-table deltas (12 §2 delta protocol):** #2 `->react` v1-lands-S6
  (2026-07-12, delegated authority); #3 `spread` v1 at S1 (2026-07-12); #4 `ui/slot`
  + internal `render-fn` widening (directed 2026-07-16); #5 `spread` safe-policy
  form (directed 2026-07-16). (#1, `custom-element`, ruled 2026-07-12, is recorded
  in §2 above.)
- **Internal fn-props + library event-prefix convention (2026-07-16, C-13a/C-13b).**
  Fn props between internal views are opaque identity-compared values with no
  implicit phase; libraries take event prefixes and `conj` payloads; no
  `dispatch-conj` helper enters core.

## Recommendation

Keep `accepted`. Graduate to `final` when the S3 conformance slice
(`rf2-vxgfnd.95.10`, including the widened G-8 and the four cited landing beads)
closes — the decisions are settled now; `final` should also assert the enforcement.
Where any wording here drifts from Spec 004 and its companions, the spec governs.
