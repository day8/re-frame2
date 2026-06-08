# EP-0005: Machine `:data` Schema (`defmachine` `:data-schema`)

Status: proposal

## Abstract

The bead (`rf2-cdvybr`) proposes adding an optional `:data-schema` (Malli) key
to `defmachine` for the machine's `:data` (its context), on the premise that
"the machine `:data` is the one re-frame2 state surface with NO declared
schema."

**That premise is no longer true.** A read of the current reference
implementation shows that machine `:data` validation already shipped, under
`rf2-jbbp7`, as a top-level **`:schema`** key on the machine spec. It validates
`:data` at the macrostep-commit boundary, at bootstrap, and at spawn time; it
emits `:rf.error/schema-validation-failure` with `:where :machine-data`; it
rolls back the cascade on failure; it is production-elidable; and its
value-bearing trace slots already route through the schema-aware redaction seam.
Spec 005 §Schema validation and Spec 010 §Per-step recovery row 7 document it as
normative fact.

So the design question this EP actually answers is narrower and sharper than the
bead's framing, and splits cleanly into one **naming decision** and three
**genuine gaps**:

1. **Naming (the bead's literal ask).** Should the existing key be **renamed**
   `:schema` → `:data-schema` to say what it validates, or should `:schema` be
   **kept** (consistent with every other `reg-*` kind, which all use `:schema`)?
   This is the one decision the bead's title raises, and the EP recommends a
   clear answer below.
2. **Redaction bridge (a real gap).** A machine `:schema` whose Malli properties
   mark a `:data` slot `:sensitive?` / `:large?` does **not** today cause that
   slot to be redacted in machine snapshot egress. Machine snapshot redaction
   reads a *separately, manually registered* mark table keyed by machine-id; it
   does not read the schema's properties. The bead's rationale 5 ("redaction
   markers") names a feature that is **not wired**.
3. **machines-viz declared-over-inferred (a real, already-filed gap).** The
   static Context-shape panel infers `{key -> type}` from one sample of the
   initial `:data` (`rf2-vcnvj`), badged "inferred" (`rf2-5tz9p`). A declared
   `:data` schema should make that panel **authoritative**. This is exactly the
   `wto1k`-option-A prerequisite the bead cites.
4. **XState-v5 parity documentation (a real gap).** re-frame2 has the
   *mechanism* (`:schema` validates `:data`) but does not *frame* it as the
   re-frame2-native analog of XState v5's typed context. The parity story is
   undocumented.

The recommendation, in one line: **keep the validation that exists, rename the
key to `:data-schema` for clarity, close the schema→marks redaction bridge so
the existing key earns its rationale-5 promise, switch machines-viz to
declared-over-inferred, and document the XState-v5 parity.** The bulk of the
"add `:data-schema`" work is already done; what remains is a rename, a redaction
wiring, a viz feeder switch, and parity prose.

## Motivation

### What already exists (ground truth)

The transition-table grammar already carries an optional `:data` schema slot:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schema  DrawerData                ;; validates :data
   :guards  {...}
   :actions {...}
   :states  {...}})
```

Source evidence:

- `spec/005-StateMachines.md` §"Transition table grammar" lists `:schema` as a
  top-level optional key: *"optional — validates the machine's `:data` slot at
  every macrostep boundary + at bootstrap; failures emit
  `:rf.error/schema-validation-failure :where :machine-data` and roll back the
  cascade."*
- `spec/005-StateMachines.md` §"Schema validation" specifies the full contract:
  macrostep-boundary timing, initial-data installation, spawn-time validation,
  the failure trace shape, full-cascade rollback recovery, and production
  elision.
- `implementation/machines/src/re_frame/machines/data_validation.cljc` is the
  boundary-validation namespace. `validate-machine-data!` walks every snapshot
  under `[:rf/runtime :machines :snapshots]` and validates `(:data snapshot)`
  against `(:schema spec)`; `validate-spawn-data!` is the pre-install spawn
  check; `validate-snapshot-data!` is the per-snapshot core.
- `implementation/machines/test/re_frame/machine_schema_test.clj` covers
  acceptance, macrostep rollback, bootstrap rollback, spawn-time skip-install,
  the no-schema control, and tag-payload completeness.
- `spec/010-Schemas.md` §"Validation timing — the steps" step 4a: *"Machine-
  `:data` schemas (from `reg-machine` `:schema`) — alongside step 4 the runtime
  walks `[:rf/runtime :machines :snapshots]` and validates each snapshot's
  `:data` against its registered machine's `:schema`."*
- `spec/Spec-Schemas.md` enumerates `:machine-data` in the
  `SchemaValidationTags` `:where` enum, and `:rf/machine-meta` documents that
  `machine-meta` returns the spec carrying `:schema`.

So the bead's acceptance criteria — *"`defmachine` / `reg-machine` accept an
optional schema; the initial `:data` is validated at registration (loud
`:rf.error/*`); dev-mode action-output validation, production-elided"* — are
**already met by `:schema`**. The bead's recommended validation scope ("at least
(a), with (b) as the dev-mode boundary check") is exactly what `:schema` does:
it validates both initial data (bootstrap) and action outputs (every macrostep
commit), production-elided.

### What is genuinely missing

Three things in the bead's rationale are real and not yet delivered:

**1. The redaction bridge (rationale 5).** The bead claims a `:data-schema` "can
carry `:sensitive?` / `:large?` Malli markers like app-db schemas, so machine
`:data` participates in the wire-elision + sensitive-redaction posture." Today
it does not. Two separate mechanisms exist and are **not connected**:

- *app-db schema → elision.* `reg-app-schema` runs the schemas walker
  (`implementation/schemas/src/re_frame/schemas/walker.cljc`), which extracts
  per-slot `:sensitive?` / `:large?` Malli properties into the frame's elision
  registry at `[:rf/runtime :elision :sensitive-declarations]` /
  `[:rf/runtime :elision :declarations]`. The wire walker honours them.
- *machine snapshot → redaction.* Machine snapshot trace egress
  (`re-frame.marks/project-machine-tags` and `project-machine-error-tags`) reads
  marks from `(marks/register-marks! :event machine-id ...)` — a *manually
  registered, machine-id-keyed* table (`machine-marks`). It does **not** read
  the machine `:schema`'s Malli properties.

The machine `:schema` already runs through the schema-aware redactor *for its
own failure trace's value slots* (`data_validation.cljc` routes `:value` /
`:received` / `:explain` through `:schemas/redact-validation-tags` — rf2-o69h5).
But the *snapshot* trace slots (`:before` / `:after` / `:snapshot` on every
`:rf.machine/transition`) are redacted only against the manually-registered
machine marks, not against the schema. A developer who declares
`[:auth-token {:sensitive? true} :string]` inside a machine `:schema` gets
validation, but the token still egresses raw in every transition trace and Xray
snapshot — because nothing bridges the schema's `:sensitive?` into
`machine-marks`. This is the gap the bead's rationale 5 promises and the EP must
close.

**2. machines-viz declared-over-inferred (rationale 3).** The static
Context-shape panel
(`tools/xray/src/day8/re_frame2_xray/panels/machines/topology_view.cljs`,
`static-context-shape`) derives `{key -> type-caption}` from one sample of the
definition's initial `:data` (`rf2-vcnvj`), and `rf2-5tz9p` added an "inferred
from :data" badge because that inference can mislead when the initial `:data` is
partial. A declared `:data` schema turns that panel from a one-sample inference
into an **authoritative declared key→type table** — exactly the `wto1k`-option-A
the closed bead noted "presupposes machines can declare a context schema." They
can; the feeder just doesn't consult the schema yet.

**3. XState-v5 parity framing (rationale 2).** XState v5 has typed context
(`setup({ types: { context } })`), and Stately renders the context shape in the
chart header. re-frame2's `:schema`-on-`:data` is the behavioural analog, but
the spec does not say so. The parity story — and its one deliberate divergence
(Malli runtime validation + elision vs TypeScript compile-time-only types) — is
worth documenting, because XState v5 is the project's gold standard for machines.

### Why naming is the load-bearing decision

The bead asks for `:data-schema`; the code ships `:schema`. The single most
consequential design call here is which name wins, because it touches a
HOT-ZONE spec (005), Spec-Schemas, the data-validation namespace, every test,
and the machines-viz feeder. The EP makes a recommendation (below), but flags it
as a Mike decision because it trades two of re-frame2's own values against each
other (CLARITY-of-name vs cross-registration-consistency).

## Goals

- Correct the bead's premise: machine `:data` validation already exists; this EP
  is a rename + three completions, not a from-scratch feature.
- Resolve the `:schema` vs `:data-schema` naming question with a recommendation
  and the trade named explicitly.
- Close the schema→marks redaction bridge so a `:sensitive?` /`:large?` slot in
  a machine `:data` schema is honoured in snapshot egress, not just validation.
- Make machines-viz render the **declared** Context shape when a schema is
  present (authoritative) and fall back to **inferred** (`rf2-5tz9p`) when
  absent.
- Document the XState-v5 typed-context parity and its one deliberate divergence.
- Sketch the spec/005 + spec/010 + Spec-Schemas edits the chosen option entails,
  as proposal text — not by editing those normative files.

## Non-Goals

- Re-implementing validation timing, rollback, or production elision — they
  exist and are correct.
- A second, parallel validation surface alongside `:schema`. There is exactly
  one machine `:data` schema slot; this EP renames or keeps it, it does not add
  a sibling.
- Schematizing the snapshot's reserved `:rf/*` slots or its `:state` keyword —
  `:state` is validated structurally at registration; the `:rf/*` slots are
  framework-owned. The schema's job is the user-domain `:data` only.
- Validating `:data` in production by default. The dev-only posture and the
  `:rf.schema/at-boundary` opt-in are inherited unchanged from Spec 010.
- The app/runtime partition rename of `[:rf/runtime :machines :snapshots]` →
  `[:rf.runtime/machines :snapshots]`. That is the App/Runtime Partition EP's
  concern; this EP uses today's path and sequences after it if both land.

## Relationships

This EP is largely independent but shares one path with the partition EP.

- **Sequences after the app/runtime partition rename.** This EP's redaction
  bridge targets the machine-snapshot path `[:rf/runtime :machines :snapshots]`,
  which the [App/Runtime Partition EP](app-db-runtime-partition.md) (listed under
  `Requires`) renames to `[:rf.runtime/machines :snapshots]`. If both land, the
  redaction-bridge work sequences *after* the partition rename so it targets the
  final path. The two are otherwise independent.
- **In-bead relationships.** For the relationship to the closed beads `rf2-wto1k`
  (deferred declared-context option A) and `rf2-5tz9p` (inferred-context badge),
  see [Relationship To `rf2-5tz9p` And `rf2-wto1k`](#relationship-to-rf2-5tz9p-and-rf2-wto1k)
  below.

## Benchmark Standard And Prior Art

### XState v5 typed context (gold standard)

XState v5 declares context shape up front:

```ts
const machine = setup({
  types: {
    context: {} as { openedCount: number; heldOpen: boolean },
  },
}).createMachine({
  context: { openedCount: 0, heldOpen: false },
  // ...
});
```

Stately's inspector renders this as a "Context: openedCount: number, heldOpen:
boolean" header on the chart. The context shape is a *declared contract*, not an
inference from a sample value.

re-frame2's behavioural analog already exists:

```clojure
(rf/reg-machine :door/main
  {:initial :locked
   :data    {:opened-count 0 :held-open? false}
   :schema  [:map [:opened-count :int] [:held-open? :boolean]]   ;; the declared context shape
   :states  {...}})
```

The parity is *behavioural* (both declare context shape; both can render it; both
validate it), expressed re-frame2-natively (Malli data schema vs TypeScript
type), per the project's "behavioural parity not API-mimicry" standard.

**The one deliberate divergence to document.** XState v5 context types are
*compile-time only* (TypeScript erases them; there is no runtime context
validation from the `types` block — runtime validation needs a separate input
schema). re-frame2's `:schema` is a *runtime Malli validator*: it actually
rejects out-of-shape `:data` at the macrostep boundary, rolls back, and can drive
elision. So re-frame2 **exceeds** the benchmark on runtime enforcement and on
privacy (elision markers) while matching it on declared-shape rendering. This is
a divergence worth blessing, not apologizing for — it is the loud-failure ethos
applied to machine context.

### re-frame2's own `:schema` convention

Every other registration kind already takes a `:schema`:
`reg-event-db` / `reg-event-fx` / `reg-cofx` / `reg-fx` / `reg-sub` carry
`:schema` in their metadata map, and `reg-app-schema` is the app-db variant.
Spec 005 §Schema validation explicitly motivates the machine key by analogy:
*"Mirrors how `:schema` rides on every other registration kind."* This is the
prior art that argues *against* renaming to `:data-schema` — see the naming
decision.

## Design Rationale

### The naming decision: `:schema` vs `:data-schema`

This is the crux. Two of re-frame2's own values point opposite ways.

**Argument for keeping `:schema` (cross-registration consistency).** Every
`reg-*` kind uses `:schema`. A reader who knows `reg-event-db`'s `:schema`
validates the event's contract transfers that knowledge directly: a machine's
`:schema` validates the machine's contract. The machine's contract *is* its
`:data` (its `:state` is validated structurally, its `:rf/*` slots are
framework-owned), so "`:schema` validates the user-domain value" is already the
uniform meaning across the framework. Renaming machines alone to `:data-schema`
*breaks* that uniformity and invites the question "why does only this one kind
spell it differently?"

**Argument for renaming to `:data-schema` (local CLARITY + the bead's ask).** On
a machine spec specifically, `:data` and `:schema` sit side by side, and
`:schema` does not *say* it validates `:data` — a reader could plausibly think it
validates the whole snapshot or the spec itself. `:data-schema` is
self-documenting at the exact site of greatest ambiguity, and pairs visually with
the `:data` key it governs (`:data` + `:data-schema`, like `:initial` +
`:initial-…`). The bead's own shape uses `:data-schema`, and Mike's ruling A
adopts "`:data-schema`."

**Recommendation: rename `:schema` → `:data-schema`, pre-alpha, no shim.** The
local-clarity win at the point of maximum ambiguity outranks the
cross-registration symmetry here, for three reasons: (a) the machine spec is the
*only* `reg-*` surface where the validated value has its own visible sibling key
(`:data`), so the ambiguity `:data-schema` resolves is unique to machines and
the symmetry argument is weaker than it looks; (b) Mike has already ruled A,
which uses `:data-schema`; (c) pre-alpha is the only free moment to make the
rename. The cost is a HOT-ZONE 005 edit, a Spec-Schemas edit, the
`data_validation.cljc` lookup (`(:schema spec)` → `(:data-schema spec)`), the
`machine-meta` round-trip docs, and the tests — all mechanical.

*This is the one place the EP defers to Mike to confirm,* because it inverts the
"mirrors every other `reg-*` kind" rationale that 005 currently uses to motivate
the key. If Mike prefers cross-registration symmetry, keeping `:schema` is
defensible and the rest of this EP (gaps 2–4) stands unchanged.

### Closing the redaction bridge (gap 2 — the real engineering)

This is where the bead's rationale 5 becomes actual work. The goal: a
`:sensitive?` / `:large?` property anywhere in a machine's `:data-schema` is
honoured in snapshot egress (Xray, pair-MCP, epoch wire), not only in the
validation-failure trace.

There are two coherent designs:

**Option R1 — bridge schema marks into the machine-marks table at
registration.** Mirror what `reg-event` does. `reg-event` passes its whole
`meta` (carrying `:schema` + `:sensitive` / `:large`) to
`marks/register-marks! :event id meta` after the registrar write. `reg-machine*`
does **not** call `register-marks!` at all today. Under R1, `reg-machine*`
extracts the `:sensitive?` / `:large?` per-slot paths from the `:data-schema`
(reusing the schemas walker that `reg-app-schema` already uses) **rooted under
`[:data …]`** (because machine snapshot marks are snapshot-rooted per Spec 015
§6 — e.g. `[:data :jwt]`), and registers them via
`marks/register-marks! :event machine-id {...}`. Then the existing
`project-machine-tags` walker redacts `:before` / `:after` / `:snapshot` against
them with zero change to the egress chokepoint.

- Pros: reuses two shipped mechanisms (the schemas walker for extraction,
  `project-machine-tags` for application); no new egress path; precise per-slot
  redaction; symmetric with how `reg-event`/`reg-app-schema` already work.
- Cons: requires `reg-machine*` to take a dependency on the schemas walker
  (late-bound, so an app shipping no schemas artefact pays nothing); the
  extraction must root paths under `[:data …]` to match snapshot shape.

**Option R2 — conservative whole-slot scrub when the schema declares any
sensitive slot.** Mirror `project-machine-error-tags` (rf2-zsm03), which scrubs
the *whole* `:exception-data` slot when the machine declares any `:sensitive`
mark. Under R2, if the `:data-schema` marks *any* slot `:sensitive?`, the
machine "handles secrets," so its snapshot `:data` is treated conservatively.

- Pros: trivially safe (fail-closed); no per-slot path extraction.
- Cons: coarse — redacts the whole `:data` even when only one slot is sensitive,
  losing the legible non-sensitive context Xray wants to show; inconsistent with
  the precise per-slot redaction app-db already does.

**Recommendation: R1 (precise per-slot bridge), with R2's conservative posture
reserved for the genuinely un-mappable slot.** R1 gives machines the same
precise redaction app-db has and makes rationale 5 literally true. R2's
whole-slot scrub is correct for `:exception-data` (which is not snapshot-shaped,
so per-slot paths cannot map) and should stay there, but the snapshot `:data` is
schema-shaped, so R1's path extraction works. Adopt R1 for snapshot slots; leave
the rf2-zsm03 `:exception-data` scrub as-is.

One subtlety to settle in implementation: R1 should compose with, not clobber,
any marks a developer registered manually via
`register-marks! :event machine-id`. Last-write-wins replaces wholesale today;
the schema-sourced + manually-sourced marks should *union* (the same
schema-sourced-vs-author-sourced composition `reg-app-schema` + `add-marks`
already define for app-db, where schema-sourced entries carry `:source :schema`).

### machines-viz: declared-over-inferred (gap 3)

Switch `static-context-shape` to a two-tier feed:

1. **Declared.** If the machine definition carries a `:data-schema`, derive the
   `{key -> type-caption}` table from the *schema* (its `[:map [k type] …]`
   entries), and render it as **authoritative** (drop the "inferred" badge for
   this machine).
2. **Inferred.** If there is no `:data-schema`, keep the current behaviour:
   derive `{key -> type}` from one sample of initial `:data`, badged "inferred
   from :data" (`rf2-5tz9p`).

This is the `wto1k`-option-A the closed bead deferred, now unblocked. Per the
standing "Xray specs kept current" rule, the same PR that touches
`topology_view.cljs` must update `tools/xray/spec/*` (the §Static context shape
section) to document the declared-vs-inferred tiering, and add a DOM test for the
declared path alongside the existing inferred-shape test
(`chart-renders-root-context-panel-from-static-shape`).

### XState-v5 parity documentation (gap 4)

Add a short §"XState v5 parity — typed context" subsection to Spec 005 §Schema
validation (sketched below) that: (a) names `:data-schema` as the re-frame2
analog of XState v5 typed context; (b) maps the rendered-context-header parity to
machines-viz's declared Context panel; (c) blesses the one divergence (runtime
Malli validation + elision vs compile-time-only TS types) as re-frame2 exceeding
the benchmark on enforcement and privacy.

## Specification (sketch of the normative edits — proposal text, not applied)

The following are *proposed* edits to the normative tree. They are written here
as the design Mike reviews; this EP does **not** modify `spec/` itself.

### Sketch A — `spec/005-StateMachines.md`

**A1. Grammar block (§Transition table grammar).** Rename the key in the
top-level shape and the key table:

```clojure
{:initial      <fsm-keyword>            ;; required — initial state
 :data         {<initial data>}         ;; optional — initial data (the "context")
 :data-schema  <validator-schema>       ;; optional — validates :data at the
                                        ;;   :where :machine-data boundary
 ...}
```

Key-table row:

> `:data-schema` | top-level | optional — validates the machine's `:data` slot
> (its context) at every macrostep boundary + at bootstrap + at spawn;
> failures emit `:rf.error/schema-validation-failure :where :machine-data` and
> roll back the cascade. Slots marked `:sensitive?` / `:large?` also drive
> snapshot wire-elision (§Schema validation). See [§Schema validation].

**A2. §Schema validation.** Update the prose and example to `:data-schema`; keep
all timing/rollback/elision text. Replace the "Mirrors how `:schema` rides on
every other registration kind" sentence with: *"The machine's context is named
`:data`; its schema is `:data-schema` — self-documenting at the site of the
`:data` key it governs. (Other `reg-*` kinds spell the analogous key `:schema`
because they validate a single unnamed value; a machine's validated value has a
visible sibling key, so it earns the qualified spelling.)"*

**A3. New subsection §Redaction — sensitive `:data` slots.** Specify that
`:sensitive?` / `:large?` properties in `:data-schema` are extracted at
registration (snapshot-rooted under `[:data …]`) and unioned into the machine's
mark table, so `project-machine-tags` redacts `:before` / `:after` / `:snapshot`
slots in transition traces and Xray/pair/epoch egress. Cross-reference Spec 015
§6 (State machines) and the rf2-zsm03 `:exception-data` scrub (which stays
whole-slot because it is not snapshot-shaped).

**A4. New subsection §XState v5 parity — typed context.** As §gap-4 above.

**A5. §What the Single Store gives us for free.** Update the Schema-validation
bullet's `:schema` → `:data-schema`.

### Sketch B — `spec/010-Schemas.md`

- §"Validation timing — the steps" step 4a: `reg-machine` `:schema` →
  `reg-machine` `:data-schema`.
- §`:sensitive?`: add machine `:data-schema` to the list of schema surfaces
  whose `:sensitive?` properties drive elision (currently `reg-app-schema` and
  per-slot event schemas), noting the machine path roots under `[:data …]` of
  the snapshot rather than at app-db root.

### Sketch C — `spec/Spec-Schemas.md`

- `:rf/machine-meta` round-trip note: `:schema` → `:data-schema`.
- The `SchemaValidationTags` `:where` enum keeps `:machine-data` unchanged (it
  names the *boundary*, not the key).
- If a `:rf/machine-spec` schema is added/extended, give it an optional
  `[:data-schema {:optional true} :any]` slot (validators are opaque to the
  spec-schema).

### Sketch D — Conventions

If a `:rf/machine-spec` grammar is enumerated in Conventions, note `:data-schema`
as the reserved optional context-schema slot. No new reserved namespace is
introduced — `:data-schema` is an unqualified spec-map key like `:data` /
`:guards` / `:actions`.

## Examples

### Declared context with a sensitive slot

```clojure
(rf/reg-machine :session/auth
  {:initial     :anon
   :data        {:retries 0 :token nil}
   :data-schema [:map
                 [:retries :int]
                 [:token   {:sensitive? true} [:maybe :string]]]
   :states      {:anon          {:on {:login :authenticating}}
                 :authenticating {...}
                 :authed        {...}}})
```

- The macrostep boundary rejects a `:data` where `:retries` is not an int or
  `:token` is not a string/nil, rolls back, and emits
  `:rf.error/schema-validation-failure :where :machine-data`.
- Every `:rf.machine/transition` trace's `:before` / `:after` carries
  `[:token …]` redacted to `:rf/redacted` at egress (the gap-2 bridge), so the
  token never reaches Xray, pair-MCP, the epoch wire, or a log sink raw.
- machines-viz renders an authoritative `Context: retries: int, token: string?`
  panel (declared, no "inferred" badge), and shows the `:token` row redacted in
  the live overlay.

### No schema (unchanged)

```clojure
(rf/reg-machine :ui/toggle
  {:initial :off
   :data    {:count 0}
   :states  {:off {:on {:flip :on}} :on {:on {:flip :off}}}})
```

- No validation; `:data` is free-form.
- machines-viz infers `Context: count: number` badged "inferred from :data"
  (`rf2-5tz9p`), exactly as today.

## Rejected Ideas

### A. Status quo — keep `:schema`, do nothing else

Leave the key spelled `:schema`, ship no redaction bridge, no viz change, no
parity docs.

- Pros: zero churn; validation already works.
- Cons: rationale 5 (redaction) stays an unkept promise — a documented capability
  that does not function; the bead's `:data-schema` ask is unmet; the
  `wto1k`-option-A viz upgrade stays blocked; the XState parity stays implicit.
  Fails the masterpiece bar (a half-wired privacy feature is worse than none).

### B. Rename to `:data-schema` + close all three gaps (recommended)

Rename `:schema` → `:data-schema`; bridge schema `:sensitive?` /`:large?` into
machine marks (R1); switch machines-viz to declared-over-inferred; document
XState parity.

- Pros: the bead's ask delivered; rationale 5 made true; viz authoritative;
  parity blessed; one coherent pre-alpha rename.
- Cons: HOT-ZONE 005 edit + Spec-Schemas + tests churn for the rename;
  `reg-machine*` gains a late-bound schemas-walker dependency.

Recommendation: **B**.

### C. Keep `:schema`, close gaps 2–4 only

Decline the rename (preserve cross-registration symmetry), but still bridge
redaction, upgrade viz, and document parity.

- Pros: keeps `:schema` uniform across all `reg-*` kinds; delivers the three real
  gaps; smaller spec churn (no rename).
- Cons: leaves the side-by-side `:data` / `:schema` ambiguity on the machine
  spec; contradicts Mike's ruling A wording (`:data-schema`).

C is the fallback if Mike prioritizes cross-registration symmetry over
local clarity. The redaction/viz/parity work (gaps 2–4) is identical under B and
C — only the key spelling differs.

## Backwards Compatibility

Pre-alpha; no shim. The `:schema` → `:data-schema` rename (option B) is a clean
break: machine specs using `:schema` would need a one-token edit. Because the
project ships no external alpha yet, the only consumers are in-repo testbeds,
examples, and tests, all updated in the same work. A short-lived registration
diagnostic — *"machine spec carries `:schema`; rename to `:data-schema`"* — can
ease the in-repo migration during implementation, then be removed.

## Migration

Migration is in-repo only and mechanical (option B):

- **Rename the key.** `(:schema spec)` → `(:data-schema spec)` in
  `data_validation.cljc`, the `machine-meta` round-trip, and every in-repo machine
  spec, testbed, example, and test that declares a machine `:data` schema. This is
  a one-token edit per machine spec.
- **Drive legacy usage loud.** Ship the short-lived `:schema`-present registration
  diagnostic during the rename so any missed call site surfaces, then remove it.
  (Whether to ship the diagnostic at all or do a silent atomic in-repo rename is
  [Open Issue 4](#open-issues).)
- **No app-side migration.** With no external alpha, there are no downstream
  consumers; the rename is contained to this repo and lands in the same work.

The redaction bridge and machines-viz changes (gaps 2–3) add new behavior rather
than migrating existing usage; their sequencing is in the
[Reference Implementation Plan](#reference-implementation-plan-if-adopted) below.

## Security And Privacy Considerations

The redaction bridge (gap 2) is the security-load-bearing part of this EP.
Without it, `spec/005` and the bead both *describe* a privacy capability
(`:sensitive?` markers on machine context) that does not function — a worse
posture than no claim at all, because a developer may trust the marker and ship a
machine that egresses a token in every transition trace. Closing the bridge
makes the documented capability real and fail-precise (R1) for snapshot-shaped
`:data`, while the conservative whole-slot scrub stays correct for the
non-snapshot-shaped `:exception-data` path (rf2-zsm03). The redaction, like all
of it, lives behind `interop/debug-enabled?` and is moot in production builds
where the trace surface itself is elided.

## Relationship To `rf2-5tz9p` And `rf2-wto1k`

- **`rf2-wto1k` (closed done)** asked machines-viz to derive a static
  Context-shape panel from the machine definition. It shipped *option B* — the
  pragmatic inference from initial `:data` (`rf2-vcnvj`) — and explicitly
  deferred *option A* (a declared context schema) as "a separate larger spec/005
  feature, not a prerequisite." **This EP is that option-A prerequisite.** The
  bead notes wto1k's cleanest option "presupposes machines can declare a context
  schema — which they cannot today"; the read of the code shows they *can*
  (`:schema`), and this EP makes the viz consume it.
- **`rf2-5tz9p` (closed)** added the "inferred from :data" badge to the inferred
  panel, gated by `:machine-data-inferred?` (default true). **This EP makes the
  badge conditional on schema-absence:** declared → authoritative (badge off);
  absent → inferred (badge on, exactly 5tz9p's behaviour). 5tz9p's plumbing (the
  `:machine-data-inferred?` prop) is the seam gap-3 toggles; nothing 5tz9p built
  is discarded.

This EP therefore *subsumes* the deferred wto1k-option-A and *extends* (does not
revert) 5tz9p.

## Reference Implementation Plan (if adopted)

Sequential, because spec/005 is a machines HOT-ZONE file.

1. **Decision bead (this EP).** Mike rules: rename to `:data-schema` (option B)
   vs keep `:schema` (option C); confirm R1 (precise per-slot redaction) for the
   bridge.
2. **Spec bead.** Apply Sketches A–D to spec/005 (HOT-ZONE — sequence with other
   005 work), spec/010, Spec-Schemas, Conventions. Add the §Redaction and
   §XState-parity subsections.
3. **Rename bead (option B only).** `(:schema spec)` → `(:data-schema spec)` in
   `data_validation.cljc`, the `machine-meta` round-trip, and all machine tests;
   add the short-lived `:schema`-present registration diagnostic.
4. **Redaction-bridge bead (R1).** `reg-machine*` extracts `:data-schema`
   `:sensitive?` / `:large?` per-slot paths (snapshot-rooted under `[:data …]`)
   via the schemas walker and unions them into
   `register-marks! :event machine-id`. Compose schema-sourced + author-sourced
   marks (don't clobber). Tests: a sensitive `:data-schema` slot is redacted in a
   `:rf.machine/transition` `:before`/`:after`; a manual `register-marks!` still
   composes; no schema → no redaction.
5. **machines-viz bead.** Switch `static-context-shape` to declared-over-inferred
   (gap 3); update `tools/xray/spec/*` (Xray-specs-kept-current rule); add the
   declared-path DOM test.
6. **Docs/examples bead.** Update any in-repo machine spec using `:schema` →
   `:data-schema`; refresh the machines guide if it teaches the key.

## Open Issues

1. **Naming (the load-bearing one).** Rename `:schema` → `:data-schema`
   (option B, recommended — local clarity + your ruling A) or keep `:schema`
   (option C — cross-registration symmetry)? This inverts the "mirrors every
   other `reg-*` kind" rationale 005 currently uses, so it is your call.
2. **Redaction precision.** R1 (precise per-slot bridge, recommended) vs R2
   (conservative whole-`:data` scrub when any slot is sensitive)? R1 matches
   app-db's precision; R2 is simpler but coarser.
3. **Schema-sourced + author-sourced mark composition.** Confirm union (matching
   `reg-app-schema` + `add-marks` for app-db), not last-write-wins, when a
   machine has both a `:data-schema` and a manual `register-marks!`.
4. **Migration diagnostic.** Ship the short-lived `:schema`-present registration
   warning during the rename, or do a silent atomic in-repo rename (no external
   consumers yet)?
5. **Scope of the spec edit.** Should the §XState-parity subsection live in
   spec/005 (recommended, beside §Schema validation) or in a machines guide doc?

## Recommendation

Adopt **Option B**: rename `defmachine`'s existing `:schema` key to
`:data-schema` for the machine's `:data` (its context, the re-frame2 analog of
XState v5 typed context), and close the remaining gaps.

The rename buys local clarity — the key now names what it validates — and the work
that follows is the real engineering: close the schema→marks **redaction** bridge
so `:sensitive?` slots are elided in snapshot egress (gap 2, precise per-slot R1),
switch machines-viz to **declared-over-inferred** Context shape (gap 3, the
deferred `rf2-wto1k` option A), and document the XState-v5 parity (gap 4). Validation
itself already shipped under `rf2-jbbp7`, so this EP corrects the premise that
machine `:data` is un-schema'd and finishes the documented-but-non-functional
privacy capability. The final naming call is Mike's (see [Open
Issues](#open-issues)), because it inverts the "mirrors every other `reg-*` kind"
symmetry rationale Spec 005 currently uses.

## Sources Consulted

- `spec/005-StateMachines.md` — §Transition table grammar, §Schema validation,
  §What the Single Store gives us for free
- `spec/010-Schemas.md` — §Validation timing step 4a, §Production builds,
  §`:sensitive?`
- `spec/Spec-Schemas.md` — `:rf/machine-meta`, `SchemaValidationTags` `:where`
  enum
- `implementation/machines/src/re_frame/machines/data_validation.cljc`
- `implementation/machines/src/re_frame/machines/lifecycle_fx/registration.cljc`
- `implementation/machines/src/re_frame/machines.cljc`
- `implementation/machines/test/re_frame/machine_schema_test.clj`
- `implementation/core/src/re_frame/marks.cljc` — `register-marks!`,
  `machine-marks`, `project-machine-tags`, `project-machine-error-tags`
- `implementation/core/src/re_frame/events.cljc` — the `reg-event`→`register-marks!`
  precedent
- `implementation/schemas/src/re_frame/schemas/walker.cljc` — the
  schema-`:sensitive?`→elision extraction `reg-app-schema` uses
- `tools/xray/src/day8/re_frame2_xray/panels/machines/topology_view.cljs` —
  `static-context-shape`
- Beads `rf2-cdvybr`, `rf2-5tz9p`, `rf2-wto1k`, and the referenced `rf2-jbbp7`,
  `rf2-vcnvj`, `rf2-zsm03`, `rf2-o69h5`
- XState v5 typed context and Stately context rendering (gold-standard benchmark)
