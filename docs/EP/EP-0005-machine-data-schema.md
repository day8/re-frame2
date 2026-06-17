# EP-0005: Machine `:data` Schema

Status: final

> **`final` means the decisions are settled.** The five deferred calls were ruled
> by Mike on 2026-06-08 (see [Resolved Decisions](#resolved-decisions)); the later
> 2026-06-09 `rf2-0k5ubx` errata ruling reaffirmed the same schema-first public
> surface for `:sensitive?` / `:large?`. The design is locked. The implementation
> has now also shipped in full: every tracked implementation erratum is closed —
> see [Implementation errata](#implementation-errata).
> The EP is implementation-complete. (Finalizing the *decisions* did not, on its own,
> assert the *implementation* was gap-free; the errata ledger below tracked that
> separately to its close.)

## Implementation errata

The EP decisions are final and the implementation has shipped in full: **every
tracked erratum below is closed.** This section is kept as a closed record of the
build-completion work that followed the decision-freeze; none of it reopens any
ruling. The EP is implementation-complete.

### Resolved errata

The rename and skill/spec-reconciliation errata below are **fixed**; they are kept
here as a closed record and no longer reopen any ruling:

- **`rf2-pjv7pz`** *(fixed — PR #3538, 2026-06-08)* — the `:schema` → `:data-schema`
  rename (decision 1) reached the core probe/integration surfaces that still carried
  the old `:schema` spelling (`late_bind/directory.cljc`, `elision_probe.cljs`). The
  rename is now complete across machines/spec/guide and core surfaces alike.
- **`rf2-0k5ubx`** *(fixed — PR #3560, ruled by Mike 2026-06-09)* — Spec 015 §6 (SS-6)
  plus three implementor-skill notes documented unimplemented top-level `:sensitive` /
  `:large` convenience keys on the machine spec. Mike ruled **option a** (reword,
  firming the schema-first surface): `reg-machine` stays `(machine-id machine-map)`;
  `:data` sensitivity is expressed **only** via per-slot `:data-schema` props
  (`:sensitive?` / `:large?`); there is no 3-arity metadata argument and no top-level
  `:sensitive` / `:large` keys for v1. The docs were reworded to match the shipped
  per-slot surface accordingly.

The redaction-bridge errata below are **fixed** (the marks-cluster work) and kept
here as a closed record; they no longer reopen any ruling:

- **`rf2-20d6k2`** *(fixed)* — `project-machine-tags` now redacts machine `:data`
  across EVERY trace slot that carries it: `:rf.machine/started`'s direct `:data`
  map, the `:input {:data …}` of `:rf.machine/guard-evaluated` /
  `:rf.machine/action-ran`, and the per-step `:data-delta`s of a
  `:rf.machine/transition`'s `:cascade` — re-rooting the snapshot-rooted `[:data …]`
  marks to each slot's shape — alongside the original `:before` / `:after` /
  `:snapshot` coverage.
- **`rf2-qpibk0`** *(fixed)* — schema-sourced machine marks now live in a table
  SEPARATE from the author-sourced `:event` marks entry; `marks-for :event <id>`
  unions the two at READ time, and `reg-event-fx` skips its bare-meta
  `register-marks!` clear for machine registrations. A `register-marks!` (or
  re-registration) can no longer drop schema-derived `[:data …]` marks, so the
  schema-vs-author union is order-independent regardless of whether the manual
  marks were registered before OR after `reg-machine` (decision 3).
- **`rf2-1zqh1z`** *(fixed)* — `union-marks!` now preserves an explicit `false`
  whole-output override across a union that adds only path marks (monotone-OR via
  `union-whole-output-flag`: `true` on either side wins, an explicit `false` is
  preserved, only both-absent vanishes), honouring the OR semantics the mark-union
  contract (decision 3) documents both ways.
- **`rf2-egvm4t`** *(fixed)* — a spawned actor's per-instance `:data-schema` marks
  are now lifecycle-managed: every destroy trigger (explicit destroy, final-state
  auto-destroy, frame teardown of spawned actors) CLEARS the per-instance entry, and
  the lazy actor-handler resolver REHYDRATES it from the restored snapshot's spec on
  the first dispatch after a `restore-epoch!` / replay — so the marks table tracks the
  revertible snapshot in lock-step and epoch restore stays safe.

(The declared-over-inferred context-shape gap for empty/closed/wrapped map schemas,
`rf2-2btfzr`, is **fixed** in PR #3523 and is no longer open.)

## Abstract

A state machine's `:data` slot — its context, in XState terms — is the value the
machine carries across transitions. The originating bead (`rf2-cdvybr`) proposed
adding an optional `:data-schema` key to `reg-machine` to validate that context,
on the premise that machine `:data` is "the one re-frame2 state surface with no
declared schema."

That premise is no longer true. Machine `:data` validation already shipped, under
`rf2-jbbp7`, as a top-level `:schema` key on the machine spec. It validates `:data`
at the macrostep-commit boundary, at bootstrap, and at spawn time; it emits
`:rf.error/schema-validation-failure` with `:where :machine-data`; it rolls back
the cascade on failure; and it is production-elidable. `spec/005-StateMachines.md`
and `spec/010-Schemas.md` document it as normative fact.

So the question this EP actually answers is narrower and sharper than the bead's
framing. It is one naming decision plus three unbuilt completions:

1. **Naming.** Rename the existing key `:schema` → `:data-schema` to say what it
   validates, or keep `:schema` for consistency with every other `reg-*` kind?
2. **Redaction bridge.** A `:sensitive?` / `:large?` marker on a machine `:data`
   slot drives validation but is *not* honoured in snapshot egress — the privacy
   capability the bead's rationale describes is documented but not wired.
3. **machines-viz declared-over-inferred.** The static Context-shape panel infers
   key→type from one sample of the initial `:data`; a declared schema should make
   that panel authoritative.
4. **XState v5 parity.** re-frame2 has the mechanism but does not frame it as the
   re-frame2-native analog of XState v5's typed context.

The bulk of "add a `:data-schema`" is therefore already done. What remains is a
rename, a redaction wiring, a viz feeder switch, and a paragraph of parity prose.

## Motivation

### Validation already exists

The transition-table grammar already carries an optional schema slot for `:data`:

```clojure
(rf/reg-machine :drawer/editor
  {:initial :idle
   :data    {:circles [] :undo [] :redo []}
   :schema  DrawerData                ;; validates :data
   :states  {...}})
```

`spec/005-StateMachines.md` lists `:schema` as a top-level optional key that
"validates the machine's `:data` slot at every macrostep boundary + at bootstrap";
failures emit `:rf.error/schema-validation-failure :where :machine-data` and roll
back the cascade. `spec/010-Schemas.md` step 4a now walks runtime-db at
`[:rf.runtime/machines :snapshots]` and validates each snapshot's `:data`. The
implementation is `re_frame/machines/data_validation.cljc`
(`validate-machine-data!`, `validate-spawn-data!`, `validate-snapshot-data!`),
with acceptance, rollback, bootstrap, and spawn coverage in `machine_schema_test.clj`.

The bead's acceptance criteria — accept an optional schema, validate initial `:data`
at registration, validate action outputs in dev, elide in production — are all met
by `:schema` today. This EP must not re-implement them.

### The lone unkept promise: redaction

The bead's rationale claims a machine schema "can carry `:sensitive?` / `:large?`
Malli markers like app-db schemas, so machine `:data` participates in the
wire-elision + sensitive-redaction posture." It does not. Two mechanisms exist and
are not connected:

- **app-db schema → elision.** `reg-app-schema` runs the schemas walker
  (`re_frame/schemas/walker.cljc`), which extracts per-slot `:sensitive?` /
  `:large?` Malli properties into the frame's elision registry. The wire walker
  honours them.
- **machine snapshot → redaction.** Machine snapshot egress
  (`re_frame.marks/project-machine-tags`) reads marks from a *manually registered,
  machine-id-keyed* table (`machine-marks`). It does not read the machine schema's
  properties.

The machine schema already routes its *own failure trace's* value slots through the
schema-aware redactor (`data_validation.cljc`, under `rf2-o69h5`). But the *snapshot*
slots — `:before` / `:after` / `:snapshot` on every `:rf.machine/transition` — are
redacted only against the manually-registered marks, never against the schema. A
developer who declares `[:auth-token {:sensitive? true} :string]` inside a machine
schema gets validation, but the token still egresses raw in every transition trace
and Xray snapshot, because nothing bridges the schema's `:sensitive?` into
`machine-marks`. This is the bead's rationale made real, and it is the only genuine
engineering in the EP.

### machines-viz infers what it could declare

The static Context-shape panel (`topology_view.cljs`, `static-context-shape`)
derives key→type from one sample of the definition's initial `:data` (`rf2-vcnvj`),
badged "inferred from :data" (`rf2-5tz9p`) because a partial initial `:data` can
mislead. A declared schema turns that one-sample inference into an authoritative
declared key→type table — exactly the option-A upgrade the closed bead `rf2-wto1k`
deferred as "presupposing machines can declare a context schema." They can; the
feeder just doesn't consult it.

### The XState parity is implicit

XState v5 declares context shape with `setup({ types: { context } })`, and Stately's
inspector renders it as a "Context: …" header on the chart. re-frame2's schema-on-
`:data` is the behavioural analog — both declare context shape, both render it, both
validate it — but the spec never says so. Since XState v5 is the project's gold
standard for machines, the parity (and its one deliberate divergence: runtime Malli
validation + elision vs TypeScript compile-time-only types) is worth recording.

## Goals

- Correct the bead's premise: machine `:data` validation already exists; this EP is
  a rename plus three completions, not a from-scratch feature.
- Settle the `:schema` vs `:data-schema` naming with a recommendation and the
  trade-off named explicitly.
- Bridge schema `:sensitive?` / `:large?` markers into snapshot egress, so a
  sensitive `:data` slot is redacted in traces, not only at validation.
- Make machines-viz render the declared Context shape (authoritative) when a schema
  is present, and fall back to inferred (`rf2-5tz9p`) when absent.
- Document the XState v5 typed-context parity and its one divergence.

## Non-Goals

- Re-implementing validation timing, rollback, or production elision — they exist
  and are correct.
- Adding a second validation surface alongside the existing key. There is exactly
  one machine `:data` schema slot; this EP renames it, it does not add a sibling.
- Schematizing the snapshot's reserved `:rf/*` slots or its `:state` keyword. The
  `:state` is validated structurally at registration; `:rf/*` is framework-owned.
  The schema governs the user-domain `:data` only.
- Validating `:data` in production by default. The dev-only posture and the
  `:rf.schema/at-boundary` opt-in are inherited unchanged from `spec/010-Schemas.md`.

## Relationships

This EP is largely independent but shares one path with another proposal.

- **Follows the App/Runtime Partition EP.** The redaction bridge targets the
  machine-snapshot path in runtime-db, `[:rf.runtime/machines :snapshots]`, as
  graduated by the [App/Runtime Partition EP](EP-0001-frame-partitions.md). The two
  decisions are otherwise independent, but EP-0005's implementation targets the
  partitioned path.
- **Subsumes the deferred `rf2-wto1k` option A.** `rf2-wto1k` shipped the pragmatic
  inference from initial `:data` (option B) and deferred the declared-context schema
  (option A) as a separate spec/005 feature. This EP is that feature.
- **Extends, does not revert, `rf2-5tz9p`.** `rf2-5tz9p` added the "inferred from
  :data" badge gated by `:machine-data-inferred?` (default true). This EP makes the
  badge conditional on schema-absence: declared → authoritative (badge off); absent →
  inferred (badge on, exactly today's behaviour). The `:machine-data-inferred?` prop
  is the seam this EP toggles; nothing 5tz9p built is discarded.
- **Catalogued by [EP-0007](EP-0007-one-name-per-fact.md) (One Name Per Fact).**
  This EP's `:schema` → `:data-schema` rename (the
  qualify-where-a-sibling-makes-`:schema`-ambiguous precedent) is recorded in
  EP-0007's schema-family table as the canonical example; EP-0007 credits this
  EP and adds no renames beyond it.

## Specification

The proposal has one decision and three pieces of work. The decision (the key
spelling) governs the others; the rest of this section assumes the recommended
rename to `:data-schema` and notes where keeping `:schema` would differ.

### The `:data-schema` key

A machine spec MAY carry an optional `:data-schema`, a Malli validator for the
machine's `:data`:

```clojure
(rf/reg-machine :session/auth
  {:initial     :anon
   :data        {:retries 0 :token nil}
   :data-schema [:map
                 [:retries :int]
                 [:token   {:sensitive? true} [:maybe :string]]]
   :states      {:anon           {:on {:login :authenticating}}
                 :authenticating {...}
                 :authed         {...}}})
```

The key is unqualified, like `:data` / `:guards` / `:actions`; no new reserved
namespace is introduced.

### Validation semantics (unchanged)

Validation behaviour is inherited verbatim from the shipped `:schema` key — the
rename does not alter it:

- The schema validates `:data` at every macrostep-commit boundary, at bootstrap,
  and at spawn time.
- A failure emits `:rf.error/schema-validation-failure` with `:where :machine-data`
  and rolls back the whole cascade.
- The validation, and its failure-trace value slots, are production-elidable and
  route through the schema-aware redactor.

### Redaction marking

A `:sensitive?` / `:large?` property anywhere in a `:data-schema` MUST be honoured in
snapshot egress — Xray, pair-MCP, and the epoch wire — not only in the validation-
failure trace. At registration, `reg-machine` extracts the marked per-slot paths from
the `:data-schema` (reusing the schemas walker `reg-app-schema` already uses), roots
them under `[:data …]` to match the snapshot shape, and unions them into the
machine's mark table. The existing `project-machine-tags` walker then redacts
`:before` / `:after` / `:snapshot` against those marks with no change to the egress
chokepoint.

Schema-sourced marks compose with — they do not clobber — marks a developer
registered manually via `register-marks! :event machine-id`. The two sets union, the
same schema-sourced-vs-author-sourced composition `reg-app-schema` + `add-marks`
already define for app-db.

### machines-viz: declared over inferred

`static-context-shape` becomes a two-tier feed:

1. **Declared.** If the definition carries a `:data-schema`, derive `{key → type}`
   from the schema's `[:map [k type] …]` entries and render it as authoritative —
   the "inferred" badge is dropped for that machine.
2. **Inferred.** If there is no `:data-schema`, keep `rf2-5tz9p`'s behaviour: derive
   `{key → type}` from one sample of initial `:data`, badged "inferred from :data".

Per the standing Xray-specs-kept-current rule, the PR that touches `topology_view.cljs`
also updates `tools/xray/spec/*` and adds a DOM test for the declared path.

### XState v5 parity

A short subsection added to `spec/005-StateMachines.md` names `:data-schema` as the
re-frame2 analog of XState v5 typed context, maps the rendered-context-header parity
to the machines-viz declared panel, and records the one divergence (below).

### Examples

A declared context with a sensitive slot (the `:session/auth` machine above):

- The macrostep boundary rejects a `:data` where `:retries` is not an int or `:token`
  is not a string/nil, rolls back, and emits the failure error.
- Every transition trace's `:before` / `:after` carries `[:token …]` redacted to
  `:rf/redacted` at egress, so the token never reaches Xray, pair-MCP, the epoch wire,
  or a log sink raw.
- machines-viz renders an authoritative `Context: retries: int, token: string?` panel
  with no "inferred" badge, and shows the `:token` row redacted in the live overlay.

A machine with no schema is unchanged: `:data` is free-form and unvalidated, and
machines-viz infers and badges its Context shape exactly as today.

## Rationale

### Why rename `:schema` → `:data-schema`

This is the load-bearing decision, because the two relevant re-frame2 values point
opposite ways.

**For keeping `:schema` (cross-registration consistency).** Every `reg-*` kind —
`reg-event-db`, `reg-cofx`, `reg-fx`, `reg-sub`, `reg-app-schema` — spells its
validator `:schema`. A reader who knows what `reg-event-db`'s `:schema` validates
transfers that knowledge directly. Renaming machines alone breaks the uniformity and
invites "why does only this one kind spell it differently?"

**For renaming to `:data-schema` (local clarity).** The machine spec is the *only*
`reg-*` surface where the validated value has its own visible sibling key: `:data`
and `:schema` sit side by side, and `:schema` does not *say* it validates `:data` — a
reader could plausibly think it validates the whole snapshot or the spec itself.
`:data-schema` is self-documenting at the exact site of greatest ambiguity, and pairs
visually with the `:data` it governs.

The recommendation is **rename to `:data-schema`**: the local-clarity win at the point
of maximum ambiguity outranks the cross-registration symmetry, because (a) machines
are the only surface where the validated value has a visible sibling key, so the
ambiguity is unique to them and the symmetry argument is weaker than it looks, and (b)
pre-alpha is the only free moment to make a clean-break rename. This was the one call
the EP deferred to Mike, who ruled the rename (see [Resolved Decisions](#resolved-decisions)),
because it inverts the "mirrors every other `reg-*` kind" rationale
`spec/005-StateMachines.md` previously used to motivate the key — a rationale that
section now updates accordingly.

### Why the redaction bridge, and why per-slot

Without the bridge, `spec/005-StateMachines.md` and the bead both *describe* a privacy
capability — `:sensitive?` markers on machine context — that does not function. That is
worse than no claim at all: a developer may trust the marker and ship a machine that
egresses a token in every transition trace. Closing the bridge makes the documented
capability real.

The bridge is per-slot rather than a conservative whole-`:data` scrub because the
snapshot `:data` is schema-shaped: the marked paths map cleanly onto the snapshot under
`[:data …]`, so precise per-slot redaction (matching app-db) works and preserves the
legible non-sensitive context Xray wants to show. The conservative whole-slot scrub
remains correct only for the non-snapshot-shaped `:exception-data` path (`rf2-zsm03`),
where per-slot paths cannot map, and stays there unchanged.

### Why the viz and parity completions

The viz switch unblocks the option-A upgrade `rf2-wto1k` deferred and turns a
sometimes-misleading inference into an authoritative table when the author has done
the work of declaring a schema. The parity prose records that re-frame2 *exceeds* its
XState v5 benchmark — runtime validation and elision vs TypeScript's compile-time-only,
erased types — rather than leaving the relationship implicit. Both are small, but each
finishes a story the codebase already half-tells.

## Backwards Compatibility

Pre-alpha; no shim. The `:schema` → `:data-schema` rename is a clean break: a machine
spec using `:schema` needs a one-token edit. With no external alpha shipped, the only
consumers are in-repo testbeds, examples, and tests, all updated in the same work. The
redaction bridge and the viz switch add new behaviour rather than changing existing
usage, so they raise no compatibility concern of their own.

## Migration

Migration is in-repo only and mechanical:

- **Rename the key.** `(:schema spec)` → `(:data-schema spec)` in `data_validation.cljc`,
  the `machine-meta` round-trip, and every in-repo machine spec, testbed, example, and
  test that declares a machine `:data` schema. One token per spec.
- **Silent atomic rename — no diagnostic, no shim.** Mike ruled a silent atomic in-repo
  rename (see [Resolved Decisions](#resolved-decisions), item 4): with no external
  consumers, no short-lived `:schema`-present registration warning is shipped. The rename
  is a single contained edit across this repo.
- **No app-side migration.** With no downstream consumers, the rename is contained to
  this repo and lands in the same work.

## Security And Privacy Considerations

The redaction bridge is the security-load-bearing part of this EP. A `:sensitive?`
marker that validates but does not redact is a trap: the developer believes the token
is protected, and it egresses raw in every transition trace and Xray snapshot. The
bridge makes the marker honoured and fail-precise for snapshot-shaped `:data` (the
per-slot path), while the conservative whole-slot scrub stays correct for the
non-snapshot-shaped `:exception-data` path (`rf2-zsm03`). All of it lives behind
`interop/debug-enabled?` and is moot in production builds, where the trace surface is
elided entirely. Note that *production-elidable is not elided-by-default on the JVM*:
the CLJS `:advanced` build DCEs the surface via `goog.DEBUG=false`, but on the JVM
`debug-enabled?` defaults **true** unless `-Dre-frame.debug=false` (or
`RE_FRAME_DEBUG=false`) is set, so a production JVM SSR process that does not set the
flag runs the dev trace surface — a sensitive `:data` slot is still redacted by the
bridge, but the trace surface itself is live and must be disabled explicitly.

## Rejected Ideas

### Status quo — keep `:schema`, build nothing else

Leave the key spelled `:schema`, ship no redaction bridge, no viz change, no parity
prose. Zero churn, but the privacy capability stays an unkept promise (a documented
feature that does not function), the `wto1k` viz upgrade stays blocked, and the parity
stays implicit. A half-wired privacy feature is worse than none; this fails the
masterpiece bar.

### Conservative whole-`:data` redaction scrub

When the schema marks any slot `:sensitive?`, scrub the whole `:data` slot in egress —
mirroring the `rf2-zsm03` `:exception-data` scrub. Trivially fail-closed and needs no
per-slot path extraction, but coarse: it loses the legible non-sensitive context Xray
wants to show even when only one slot is sensitive, and is inconsistent with app-db's
precise per-slot redaction. The snapshot `:data` is schema-shaped, so per-slot paths
map; precision is achievable and preferred. (The whole-slot scrub stays correct for
`:exception-data`, which is not snapshot-shaped — it is kept there.)

## Resolved Decisions

The five calls this EP deferred to the operator were ruled by Mike on
2026-06-08. All were taken as recommended above; the rulings below are the
final, implemented decisions. A follow-up implementation-errata ruling,
`rf2-0k5ubx` on 2026-06-09, confirmed the same public surface for
sensitivity/large-data metadata: machine `:data` marks come only from per-slot
`:data-schema` properties for v1, with no top-level `:sensitive` / `:large`
keys.

1. **Naming → `:data-schema`.** The key is renamed from `:schema` to `:data-schema`. The
   local-clarity win at the point of maximum ambiguity (a `:data` sibling sitting beside
   the validator) outranks the cross-registration symmetry argument, and pre-alpha is the
   only free moment for a clean-break rename. `spec/005-StateMachines.md`'s "mirrors every
   other `reg-*` kind" rationale is updated accordingly.
2. **Redaction precision → per-slot bridge.** A `:sensitive?` / `:large?` property
   anywhere in a `:data-schema` is bridged per-slot into snapshot egress, matching app-db's
   precise per-slot redaction rather than a coarse whole-`:data` scrub. The snapshot `:data`
   is schema-shaped, so the marked paths map cleanly under `[:data …]`; precision is
   achievable and preserves the legible non-sensitive context Xray shows. (The conservative
   whole-slot scrub stays where it is correct — the non-snapshot-shaped `:exception-data`
   path, `rf2-zsm03`.)
3. **Mark composition → union.** Schema-sourced and author-sourced marks **union** (the
   same schema-sourced-vs-author-sourced composition `reg-app-schema` + `add-marks` define
   for app-db), not last-write-wins, when a machine has both a `:data-schema` and a manual
   `register-marks!`.
4. **Migration → silent atomic in-repo rename.** No `:schema`-present registration warning
   and no shim. With no external consumers, the rename is a contained, atomic in-repo edit
   landed in the same work.
5. **Parity location → `spec/005-StateMachines.md`.** The XState-v5-typed-context parity
   subsection lives in `spec/005-StateMachines.md`, beside the schema-validation section,
   not in a separate machines guide doc.

## Recommendation

Rename `reg-machine`'s existing `:schema` key to `:data-schema` for the machine's
`:data` context — the re-frame2 analog of XState v5 typed context — and close the
remaining gaps: bridge schema `:sensitive?` / `:large?` markers into snapshot egress so
sensitive slots are redacted, not only validated; switch machines-viz to
declared-over-inferred Context shape; and document the XState v5 parity. Validation
itself already shipped under `rf2-jbbp7`, so this EP corrects the premise that machine
`:data` is un-schema'd and finishes a documented-but-non-functional privacy capability.
All five deferred calls were ruled by the operator on 2026-06-08, with the
`rf2-0k5ubx` follow-up ruling recorded on 2026-06-09 (see
[Resolved Decisions](#resolved-decisions)), and the design is settled, so this EP is
**final** — final in its *decisions*. The work has now shipped in full; every
[implementation erratum](#implementation-errata) is closed, so the EP is
implementation-complete as well.
