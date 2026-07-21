# EP-0031: The re-frame.ui Programming Model

Status: final
Type: standards-track
Created: 2026-07-16
Resolution: final 2026-07-19

## Abstract

`re-frame.ui` introduces, for the new compiled substrate, one authoring
contract: **`defview` is the one component form** (one props map, memoized by
default on a generated `rf=` comparator); **templates are hiccup with the
ambiguities removed**, compiled — never interpreted — under a closed macro
grammar in expression positions; **event handlers are data** (event vectors with
a closed placeholder vocabulary, plus a small explicit-form decision table for
what genuinely is not data); view-side state and lifecycle are exactly three body
forms (`local`, `effect`, `frame`); every escape to the host world is a
**named interop boundary** with a stated cost. Everything else — Form-1/2/3,
`reg-view`, positional args, ratoms, `:on-mount` — is a normative absence.

The normative home is [`spec/004-Views.md`](../../spec/004-Views.md), with
companions [004B](../../spec/004B-UI-Tree-and-Conversion.md) and
[004C](../../spec/004C-Roots-and-Mount.md); per EP-0009, where this EP and the
spec differ, **the spec governs**. This EP is the durable design record behind
that contract: what was decided, why, and what was rejected. `final` means what
EP-0009 says — the decisions are settled and the spec is authoritative; it does
not assert the build is gap-free.

## Motivation

The Reagent-family authoring surface cannot be compiled, analyzed, or taught
honestly: three component forms with different closure semantics; handlers as
anonymous closures that capture stale renders and defeat memoization; hiccup
interpreted at runtime by a walker that guesses at tags, components, and seqs;
ratoms silently creating a second reactivity model. Each is an unresolved
alternative this design had to close — which components exist, what a handler
*is*, what the compiler may see through, where ephemeral state may live, how the
foreign-React world is admitted. Those are feature-level public-contract
decisions with real rejected alternatives: an EP, not a bead.

## Specification

**Scope.** This EP owns the authoring contract of the compiled substrate: the
one component form and its props/memo/registration semantics; the template
grammar, including the closed macro grammar in expression positions;
handlers-as-data (the vector form, placeholders, the decision table, and the
controlled-input synchrony door); the three body forms and the `local` placement
law; and the interop boundary rows. It does not own reactivity, ownership, or
the observation port (EP-0032); evidence, manifests, and instrumentation
(EP-0033); the component-library readiness deltas' own design records (EP-0035 —
summarized here only where they amend this surface); SSR/roots/hydration
mechanics beyond the authoring seam (Specs 004C/011); or Reagent migration
mechanics (the S6/S7 migration wave — the migration skill, whose rules stay in
their committed homes; no `spec/004A` appendix lands).

### 1. `defview` — the one component form

- **Zero or one argument, semantically a props map.** Header destructuring
  lowers to direct property reads on the host props object; `:as` opts into
  materialization at a documented dev cost. **No positional args.**
- **Props ABI:** deterministic quoted JS property names preserving namespace +
  name; `:key` is reserved (a literal `:key` prop is a compile error);
  `:children` compares as one slot; the manifest maps production slot indexes
  back to keywords.
- **Closed options map:** `:props` (Malli — compile-time on literal call sites,
  dev runtime on dynamic values, elided in production), `:id`, `:display-name`.
  Nothing else — `:memo false`, `:on-mount`/`:on-unmount`, and
  `:catch`/`:fallback` were considered and rejected (Resolved Decisions).
- **Registration:** `defview` defs a Var and registers under the registrar's
  `:view` kind (source metadata, template fingerprint, hook signature,
  capability bits) — Story mounts by view id, Xray lists by registry query, the
  Pair hot-swaps a view like an event handler.
- **Memo-by-default, no opt-out:** every internal view is memoized on a
  generated straight-line `rf=` comparator over its declared prop slots — per
  slot, `Object.is` OR CLJS value equality; host/foreign values fall through to
  identity. Teach as "React.memo, except CLJS data compares by value".

### 2. The template grammar

Reagent-familiar hiccup with the ambiguities removed. Control forms (`let`/
`letfn`/`if`/`if-not`/`when`/`when-not`/`cond`/`case`/statically-pure `do`/
`for`) normalize **into the AST**; both emitters (direct React code; the JVM
structural tree owned by 004B) see through branches. Rejected at compile time
with didactic messages: dynamic tag heads, markup-returning `map`, keywords in
child position, raw lazy seqs, unkeyed list items, `sub` in loops.

**Expression positions carry a closed macro grammar** (owning text: Spec 004
§Template grammar): expressions are value-opaque with respect to markup but
lexically audited for finite reactive ownership. Accepted: the binder-aware
structural tier (`quote`, `fn`, `let`, `loop`, `letfn`, `try`), the audited
transparent core macro set (`or` `and` `when` `when-not` `cond` `->` `->>`
`some->` `some->>` `cond->` `cond->>`), and ordinary function calls plus
evaluated-only host specials. **Every other macro — core or user — is rejected**
(`:rf.ui.compile/unsupported-form`): an unaudited expansion could hide a
reactive call from site indexing, falsify the manifest, and let production elide
a ViewCell that was not actually sub-free. The set grows only by ruling.

> **Addendum — 2026-07-21 (ratified UIx-review programme, rf2-u53yy.4).** The two
> clauses above enumerated the grammar as first ratified. Both tiers now admit the
> conditional-binder family `if-let` / `when-let` / `if-some` / `when-some`: they
> join the template control forms (after `when-not`) and the binder-aware structural
> expression tier (after `try`). Each desugars into the analyzer's own `let` +
> `if`/`when` over a reserved temp — the position-aware binder machinery the analyzer
> already fully controls — so the init lowers a finite reactive site, the pattern's
> reactive-escape is rejected, and the closed-grammar / manifest / elision invariants
> are unchanged. The admission is exactly those four forms; `case` in expression
> position, `condp`, `doto`, and other conveniences stay **deferred** under the
> "demonstrated demand at a real call site" trigger. The set still grows only by
> ruling, each addition carrying its lexical-scope + hidden-reactive-injection
> counterfixtures. Normative home: [spec/004-Views.md §Template grammar](../../spec/004-Views.md).
>
> **Erratum — 2026-07-21 (rf2-u53yy.4 chronological audit repair).** Two internal
> corrections to the addendum above; the admitted set and every invariant are
> unchanged. (1) The desugar target is the analyzer's own `let` + **`if`** — never a
> generated `when`: the single-body when-* shape lowers to `(if test body nil)` (the
> special form `if` cannot be captured by a user local named `when`), and the
> `some?`-variants' nil test is a **host-qualified core nil check written with a plain
> core function** (un-shadowable, and — unlike the `cljs.core` `some?`/`nil?` macros —
> accepted by the expression grammar on re-analysis). (2) Admission is
> **resolver-confirmed**, not raw-spelling: a `:refer`d look-alike user macro named
> `if-let` resolves to its own var and is rejected as an unaudited macro, and a
> qualified `clojure.core/if-let` is admitted.

DOM prop spelling is pinned (`:on-click`, never `:on-keydown`); prop conversion
is compile-time, contextual, and total — one rule table (004B) serves static
props, `ui/spread`, and both emitters; custom elements get the bounded
`ui/custom-element` classification rule (API-freeze delta #1).

### 3. Handlers are data

**Canonical: the event vector.** A vector in an `:on-*` position is the intent,
dispatched to the committed frame. The **placeholder vocabulary is closed (v1,
scalars only): `:rf.ui/value`, `:rf.ui/checked`, `:rf.ui/key`** —
`:rf.ui/form-data` and `:rf.ui/event` deliberately do not exist (both cases
belong to `ui/event`). Literal vectors are data: value-comparable, JVM-testable,
retained as data in the manifest and JVM tree.

**The decision table** (full table: Spec 004 §Handlers) covers the rest:
`ui/event` (committed phase + the live event; `nil` ⇒ no dispatch), `ui/handler`
(imperative, stable identity), `ui/render-fn` (render phase, pure — and also the
value an internal `ui/slot` accepts), the **narrow bare-fn law (R-4)** — bare
`#(…)` legal only in known native event properties (invoker and phase known),
the language permissive rather than flipped, with deliberately no strict lint
(style, not safety — see Resolved Decisions) — a compile
error at foreign-component boundaries,
and `ui/raw-fn` for identity-as-protocol APIs and callback refs. Dynamic handler
expressions classify at runtime by type; placeholders are recognized in literal
vectors only (dev warns on a placeholder riding a runtime vector); loop handlers
that capture the binding are compile errors with the extract-a-keyed-child-view
fix.

**The controlled-input synchrony door.** Dispatches from
`:on-input`/`:on-change`/`:on-before-input` on **compiler-proven controlled**
elements (the S-5 predicate: literal `:value`/`:checked` co-present with the
handler site) drain synchronously within the DOM event — the one sanctioned
synchronous door, protecting caret and IME. Two handler forms ride it at a
proven-controlled site: a literal event vector, and a synchronous `ui/event`
body whose result is an event vector (`nil` means no dispatch; any other
synchronous result is a diagnostic). The second is the reusable-input arm: a
library control appends its payload to an event prefix received through props
and keeps the IME/caret guarantee. Everything else at such sites batches with a
diagnostic, and a compiled event-template projection form stays rejected (a
second handler language). The real-browser matrix that certifies the door —
including a reusable event-prefix component arm — is gate G-8 (EP-0034).

**Internal fn props + the library event convention:** ordinary function props
between internal views are legal opaque values, identity-compared, with no
implicit invocation phase; component libraries accept event vectors/prefixes and
dispatch `(conj prefix payload…)`.

### 4. Body forms — `local`, `effect`, `frame`

- **`local`:** host component-local state, deliberately outside re-frame2
  epochs — no frame-resident variant exists or is reserved. `(local init)`
  returns a **three-tuple `[value set! update!]`**: `set!` stores its argument
  exactly (a stored fn is a value, never an updater); `update!` applies
  `(f current & args)` to the **latest host state**, so several same-turn host
  writers compose instead of losing updates. Render-phase use is a dev error;
  the JVM raises the typed host-op error. Reset-key/derived local stays out
  (a trigger-gated spike, EP-0035). **The placement law (F8) is spec-live in
  004:** a `local` value MAY be read by same-view committed handlers —
  committed slots include local ephemera (the guide's search-box seam is
  canonical); `local` is FORBIDDEN when the value needs cross-view observation,
  replay/persistence, schema/tool inspection, durable navigation semantics, or
  subscription-derived computation.
- **`effect`:** `rf=` value deps, cleanup on dep change/disconnect/unmount,
  StrictMode-replay-safe; `(effect :connect …)` is named for what it does —
  **there is no "once"** in a lifecycle React can replay.
- **`frame`:** the committed-frame ops bundle
  (`{:frame :dispatch :dispatch-sync :subscribe}`), incarnation-fenced, tiered
  advanced alongside `dispatch-fn`.

### 5. Interop boundary rows

| Row | Ruling | Stage |
|---|---|---|
| `ui/raw` | embed a React element; SSR needs a `client-only` sibling | shipped (S1) |
| `ui/html` | trusted markup, low-friction — the visible call *is* the contract; manifests record the site | shipped (S1) |
| `ui/spread` | the one generic runtime prop-map conversion, driven by the 004B rule table | shipped (S1) |
| `ui/spread-safe` | the literal safe-policy **sibling** of `ui/spread` (a distinct name, not a second `spread` arity) — compiler-visible allow/deny: denied structural/controlled/identity keys (`:key` `:ref` `:value` `:checked` owned `:on-*`) fail in **every build**; allowed `:on-*` classify through the decision table; `aria-*`/`data-*` pass; preserves the controlled proof under passthrough | shipped (S3; API-freeze delta #5) |
| `ui/slot` | compiler-owned invocation of `ui/render-fn` values at **internal** render seams (row/cell/part renderers): only `render-fn` or `nil`; body compiled under the closed grammar, pure render phase; keys/fingerprints/`ui.test` structure preserved | shipped (S3–S4; delta #4) |
| `ui/client-only` | mandatory capability-free fallback; one root phase-flip | shipped (S3); the phase flip completes S5 |
| `ui/error-boundary` | the explicit error component — catches render/lifecycle throws; `:on-error` dispatches after the failing commit; `:reset-key` clears | shipped (S3) |
| `ui/->react` | the outward migration bridge (export a view as a React component) — memoised per view identity; scopes a supplied frame without owning it; no root/manifest/preflight; one shallow props rule (children + ref preserved) | shipped (S6 delta #2; rf2-u53yy.2 — ahead of the migration wave) |
| `ui/element` / `ui/view` / `ui/portal` / `re-frame.ui.data` | wave-2, demand-gated — no v1 existence. General `ui/element` is rejected outright; `ui/view` stays gated on an open-set-identity trigger; `ui/portal` is covered in part by the inline + top-layer overlay plan (EP-0035 records the rulings) | — |

The foreign-React hook tier (`re-frame.ui.react`, seven wrappers) belongs to
this surface; its contract detail is Spec 004 §The React interop tier.

### 6. Removed forms — normative absences

Form-1/2/3 and the `reg-view` family (not `re-frame.ui` forms — they belong to
the coexisting Reagent compatibility tier, whose live contract stays in its
committed homes — no `spec/004A` appendix lands); positional view args; plain render fns as
frame-aware views; ratoms/cursors/reactions (a second state model);
`:on-mount`/`:on-unmount`; Suspense-as-loading; the `h` macro and bare-keyword
view heads. Absences are compile errors + export checks from the first slice.

### Guide impact

The teaching seams are guide 03 (state) and guide 04 (events), and both are
written to this contract: 03 teaches the four-inputs table, the `local`
three-tuple, and the search-box seam; 04 teaches vectors-first, the three
placeholders, the decision table, the synchrony door ("keep controlled fields
literal"), the component-library event-prefix convention (EP-0035, C-13b), and
"lifecycle is not an event". Newly teachable payoffs: headless handler
assertions, Xray-before-any-click, and library controls that keep the IME/caret
guarantee.

## Rationale

**Data over closures.** An event vector is readable in source, inspectable in
Xray before any click, assertable on the JVM without a DOM, and comparable — so
memo keeps working. Closures are none of these; they are admitted only where the
thing expressed genuinely is not data, and each admission names its invoker,
phase, identity, and cost (the decision table).

**Closed grammars are what make the compiler honest.** Site indexing (every
`sub` a compile-indexed site; sub-free views eliding their ViewCell) is
only sound if the compiler can see every reactive call; one unaudited macro
breaks it. The same logic closes the options map, the placeholder vocabulary,
and the AST node set: each closure converts "anything might happen" into a
surface tools and the JVM emitter can rely on, and each grows only by ruling.

**One component form, memo with no opt-out.** A `:memo false` valve or a
"simple vs stateful" form split reintroduces exactly the taxonomy Reagent users
misuse; mutable foreign values belong at an explicit boundary, not a memo
exemption.

**The door is narrow on purpose.** The substrate sanctions exactly one
synchronous drain, where a proof exists (the S-5 predicate) and correctness
(caret/IME) demands it. The vector-outcome widening extends the *handler form*,
never the proof — the site stays statically proven controlled — because
otherwise precisely the reusable inputs the guarantee exists for would forfeit
it.

## Backwards Compatibility

Pre-alpha: clean breaks, no shims. The Reagent, UIx, and reagent-slim adapters
live on as first-class shipping surfaces under Spec 004's [TRANSITION] markers;
the Reagent-tier forms are retained (not deleted), their live contract staying
in its committed homes; no `spec/004A` appendix lands. Only Helix is removed, at the S7
removal wave. Migration rides `ui/->react` per-subtree co-mounting (S6;
mechanics land with the S6 migration wave — the migration skill, whose rules
stay in their committed homes; no `spec/004A` appendix lands).

## Resolved Decisions

- **F8 — the narrow `local` law and committed-slots ruling (2026-07-12).**
  Same-view committed handlers MAY read `local` values (committed slots include
  local ephemera); the forbidden tier is defined by what the *value* needs, not
  by whether any handler ever reads it.
- **R-4 — the narrow bare-fn law (2026-07-12).** Bare fns only in known native
  event properties; the language permissive, not flipped. (The proposed strict
  lint was withdrawn pre-alpha — style, not safety; rf2-b6pua ruling,
  2026-07-19.)
- **Placeholder vocabulary closed (2026-07-12).** Three scalars;
  `:rf.ui/form-data` and `:rf.ui/event` rejected — both cases belong to
  `ui/event`.
- **`:on-mount`/`:on-unmount` rejected (2026-07-12).** Mechanical React
  lifecycle cannot carry domain "once" semantics; `effect :connect` is named
  for what it does.
- **The S-5 door predicate confirmed (2026-07-12); the vector-outcome widening
  (2026-07-16).** The controlled proof (literal `:value`/`:checked`
  co-presence) stands; the handler-form clause admits the
  synchronous-`ui/event`-vector arm; the event-template projection form stays
  rejected.
- **Blessed API-table deltas (ruled under the freeze's delta protocol).**
  #1 `ui/custom-element` (2026-07-12); #2 `ui/->react`, lands S6 (2026-07-12);
  #3 `ui/spread` (2026-07-12); #4 `ui/slot` plus the internal `render-fn`
  widening (2026-07-16); #5 `ui/spread-safe` (2026-07-16); #6 `render-static`
  fn→macro (2026-07-20).
- **Internal fn-props and the library event-prefix convention — C-13a/C-13b
  (2026-07-16).** Fn props between internal views are opaque identity-compared
  values with no implicit phase; libraries take event prefixes and `conj`
  payloads; no `dispatch-conj` helper enters core.
- **Spellings settled at spec landing.** The render-slot invocation is
  `ui/slot`; the safe-spread form is the sibling name `ui/spread-safe` (the
  second-arity option was not taken).

## Open Issues

None on the decision surface.

## References

- Normative homes: [`spec/004-Views.md`](../../spec/004-Views.md) with
  [004B](../../spec/004B-UI-Tree-and-Conversion.md) (tree/conversion),
  [004C](../../spec/004C-Roots-and-Mount.md) (roots/mount).
- [EP-0030](EP-0030-the-compiled-view-substrate-program.md) — the program
  umbrella; this EP is its programming-model pillar.
- [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md) owns `sub`'s
  semantics; this EP owns only the call-site grammar (`(sub [:query …])` as a
  compile-indexed site).
- [EP-0033](EP-0033-re-frame-ui-view-evidence.md) owns the compiler manifest and
  committed instance records; this EP's grammars are what make those surfaces
  statically derivable.
- [EP-0035](EP-0035-component-library-readiness.md) owns the component-library
  readiness package; its amendments to this surface are folded in above.
- [EP-0018](EP-0018-one-event-registration.md) supplies the registered-event
  world handlers-as-data dispatches into (the dev unregistered-id warning is
  the seam); [EP-0025](EP-0025-data-classification.md) governs the posture —
  `:props` schemas and production elision respect.
- Design provenance: the programming-model study in the tombstoned synthesis
  tree (`ai/findings/new-substrate-synthesis/`).
