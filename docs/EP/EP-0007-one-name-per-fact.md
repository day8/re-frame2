# EP-0007: One Name Per Fact

Status: active
Type: process

> **Ruling recorded 2026-06-11.** This is a process EP; its
> normative home — [`spec/Conventions.md` §Reserved namespaces → The naming
> rules (one name per fact)](../../spec/Conventions.md#the-naming-rules-one-name-per-fact) —
> exists and carries the authoritative rule text, so the EP graduates to
> `active` (the EP-0009 precedent: a process EP whose named home
> exists goes `active`, even with sweep items still in flight). The two
> documentation sweep items remaining at acceptance (rows 5 and 6 of §The
> sweep) are completed in the same pass: the `reg-*`/`register-*` audit found
> no stragglers, and the `:schema`-family vocabulary table is now recorded in
> Conventions §The naming rules.

> A process EP (the PEP-8 analogue, per EP-0009). **Accepted 2026-06-11.** The
> rules in §Specification have graduated into
> [`spec/Conventions.md` §Reserved namespaces → The naming rules (one name per
> fact)](../../spec/Conventions.md#the-naming-rules-one-name-per-fact) — which is
> now the authoritative home. This EP remains the **rationale record**: read it
> for *why* the rules exist (the review-cycle defect class they close) and read
> Conventions for *what the rules are*. Where this EP and Conventions differ on
> the rule text, Conventions governs.
>
> Acceptance graduated the **rulebook only**. The sweep items below are
> independent beads, each separately rulable; accepting the rules did **not**
> bulk-accept the sweep. Sweep state (done / kept-as-rule / deferred) is tracked
> in §The sweep, unchanged by this acceptance.

## Abstract

re-frame2 keeps re-growing parallel spellings for single facts. Recent and
current instances from this review cycle: the frame stamp rode event contexts
as both `:frame` and `:rf.frame/id`; the runtime partition is
`:rf.db/runtime` while its children are `:rf.runtime/*`; the work-ledger draft
carried `:work/id` and `:stale-key` as near-duplicate composite identities;
the SSR redirect target accepted `:location` / `:url` / `:to` before
the retired spellings were pruned; the registration API splits into
`reg-*` and `register-*` families; and "schema" names four different
validators across the `reg-*` surface. Each instance is small. The class is
permanent, because the project has naming *conventions*
(reserved namespaces, attribute-shaped keys) but no naming *rules* for
synonyms, layers, and carriers.

This EP states the rule once:

> **Every fact has one canonical name per layer. Stable APIs accept one
> spelling. Where two layers legitimately use different words for related
> concepts, the distinction is recorded as a named vocabulary rule, not left
> as accident.**

…and runs the finite sweep that brings the current surface into compliance.

## Motivation

Three of this review cycle's findings were instances of one defect:

1. **The frame stamp (verified).** Spec 002's normative event
   context carries `:rf.frame/id` only; the implementation injects `:frame`
   beside it on every event, and ~40 internal sites consume the retired
   spelling. The rename was additive, not a rename — and EP-0002 R3's "one
   carrier, one name" is contradicted on the hot path it was written for.
2. **The redirect synonyms (now pruned).** Three accepted keys for
   one value were deliberately widened and then had to be removed by a
   targeted follow-up once this rule named the defect class.
3. **The work-ledger near-duplicates (PR #3703 review).** `:work/id` and
   `:stale-key` encode the same `[kind resource-key generation]` facts under
   two heads, plus the same facts denormalized as fields — three spellings of
   one identity in one record, in a *brand-new* design. New surfaces re-grow
   the smell because no rule forbids it.

The cost is concrete for an AI-first project: an agent generating code must
*choose* between spellings (so codebases diverge), tools and validators must
check all spellings, and every parallel spelling is latent drift of exactly the
class that dominated this review cycle.

## Goals

- State the naming rules normatively (in `spec/Conventions.md` §Namespacing,
  which already carries the reserved-namespace and attribute-shaped-name
  rules — this completes that section).
- Enumerate and resolve the current synonym instances (the sweep is finite).
- Record the deliberate cross-layer vocabulary distinctions so they read as
  rules, not inconsistencies.

## Non-Goals

- Not the registration-arity/metadata-uniformity question (deferred
  separately); this EP is vocabulary only.
- Not a rename-everything pass: inherited re-frame vocabulary (`:db`, `:event`,
  `:fx`, the public `:frame` opt) is explicitly sanctioned and untouched.
- Not blocking other work: each sweep item is independently landable.

## Relationships

- **EP-0002 (frame target resolution)** supplies the frame-stamp precedent:
  the public opt remains `:frame`, while runtime context uses `:rf.frame/id`.
  This EP records that as the first named cross-layer vocabulary rule.
- **EP-0003 / Spec 016 (resource queries)** is the main work-ledger consumer:
  `:work/id`, scoped resource keys, stale suppression, and resource
  params/scope canonicalization are the surfaces most likely to regress into
  parallel names.
- **EP-0005 (machine `:data` schema)** is the schema-family precedent. It
  deliberately chose `:data-schema` where a visible sibling made the generic
  `:schema` name ambiguous.
- **EP-0008 (production observability channels)** depends on this rule for
  channel vocabulary: causal, diagnostic, and always-on error are distinct
  facts, not synonyms for "things we log."
- **EP-0009 (EP process)** defines this document's process status and
  graduation path. On acceptance, the rules in this EP move to
  `spec/Conventions.md`; this EP remains the rationale record.
- **EP-0011, EP-0012, and EP-0015** are proposal-tier consumers: the uniform
  reply envelope, canonical-form rules, and frame-owned egress policy all use
  this EP to avoid duplicate carriers for the same fact.

## Specification

### The rules

1. **One canonical spelling per fact per layer.** A fact appearing in multiple
   places carries the same key everywhere within its layer (the frame id in
   *runtime context* is `:rf.frame/id`, everywhere).
2. **No stable accepted synonyms.** APIs accept exactly one stable spelling.
   A retired or alternative spelling is a hard error naming the canonical key,
   never a silently-normalized alias. Temporary migration aliases are allowed
   only when an explicit bead/EP ruling names the alias, canonical spelling,
   diagnostic, and sunset trigger; they are migration mechanics, not part of
   the stable contract.
3. **Cross-layer distinctions are named rules.** Where layers use different
   words for related concepts, Conventions records the rule. Initial rules:
   - *Public-opt vs runtime-context*: `:frame` is the public dispatch/subscribe
     opt and trace tag; `:rf.frame/id` is the same stamp's runtime-context
     spelling. (Already ruled by EP-0002 R3; recorded here as the pattern's
     first instance.)
   - *HTTP-response vocabulary vs navigation vocabulary*: server response
     surfaces use header vocabulary (`:location` for redirects); client
     navigation surfaces use `:url`. Different concepts, deliberately different
     words.
4. **One authoritative home per fact; mirrors are projections.** Denormalized
   copies (indexes, dual-homed owners, derived fields) are declared
   recomputable projections of the authoritative home, never co-equal sources.
   (The state-ownership half of this rule is [`spec/Runtime-Subsystems.md`
   §Derived rule 2 — one authoritative home per fact; mirrors are recomputable
   projections](../../spec/Runtime-Subsystems.md#derived-rule-2--one-authoritative-home-per-fact-mirrors-are-recomputable-projections);
   stated here because it is a *naming* discipline too — the projection should
   not mint a new key for the same fact.)

### The sweep (review-cycle instances, each one bead-sized)

| # | Instance | Resolution |
|---|---|---|
| 1 | `:frame` coeffect beside `:rf.frame/id` | **Done** — merged 2026-06-10 (the `:frame` coeffect dropped, internal consumers migrated); retained here as the sweep's precedent row |
| 2 | `:rf.db/runtime` parent vs `:rf.runtime/*` children | **Keep, as a recorded rule**: `:rf.db/*` names partition *slots* of frame-state (`:rf.db/app`, `:rf.db/runtime`); `:rf.runtime/*` names *subsystem children* inside the runtime partition — globally greppable when detached from context. EP-0001 Appendix A asked for the split to be justified or aligned; this justifies it as a layer rule (rule 3) |
| 3 | Work-ledger `:work/id` vs `:stale-key` | **Done** — EP-0003 acceptance / Spec 016 resolved one identity: stale suppression keys on `:work/id`; the separate `:stale-key` synonym is dropped, and denormalized fields are projections (rule 4) |
| 4 | Redirect `:location`/`:url`/`:to` | **Done** — merged 2026-06-10. Canonical key is `:location` per rule 3's vocabulary rule; `:url` / `:to` are retired spellings rejected with `:rf.error/redirect-retired-target-key`, not compatibility aliases |
| 5 | `reg-*` vs `register-*` families | **Done** — no stragglers; rule recorded in [Conventions §Naming: when does a surface carry `!`?](../../spec/Conventions.md#naming-when-does-a-surface-carry-) (bucket 1 `reg-*` registrars vs bucket 2 `register-*!` listeners) + the lifecycle-verb law roster. A verification pass swept the API surface (`spec/API.md` + the `re-frame.core` facade exports): every `reg-*` is a registrar entry and every `register-*!` a listener/side-table attachment (`register-event-listener!`, `register-error-listener!`, `register-listener!`, `register-epoch-listener!`, `register-marks!`). Route removal was the lone inverse verb `unregister-route!`; it was renamed to **`clear-route`** so declarative-registration removal uses `clear-*` (like `clear-event` / `clear-sub` / `clear-flow`) and `unregister-*` is reserved for listener/sink callbacks — closing the last `reg`/`clear` vs `register`/`unregister` mismatch |
| 6 | The `:schema` family | **Done** — schema-family table recorded in [Conventions §The naming rules](../../spec/Conventions.md#the-naming-rules-one-name-per-fact): `reg-event-*` `:schema` validates *event args*; machine `:data-schema` validates machine `:data` (the EP-0005 qualify-where-a-sibling-makes-`:schema`-ambiguous precedent); `reg-app-schema` validates app-db paths; runtime-db schemas are framework-owned. No renames beyond EP-0005's |

### Enforcement

- The synonym-rejection rule (rule 2) gets the no-floor-lint treatment where
  shapes allow: a retired spelling appearing in source is a CI failure, not a
  doc note.
- New-surface review checklist: "does this introduce a second spelling for an
  existing fact?" — one line in the EP template / implementor skill.

## Backwards Compatibility

Pre-alpha, in-repo only. Sweep items 1, 3, and 4 are done: item 1 removed the
bare `:frame` coeffect; item 3 graduated through EP-0003 / Spec 016; item 4
pruned the redirect aliases immediately, with no compatibility window. Items
2, 5, and 6 are documentation / convention-recording work.

## Bead Plan

1. Conventions bead: add the rules + the vocabulary tables (hot-zone;
   sequential). This is the graduation bead.
2. ~~The frame-stamp completion~~ — **merged** (item 1 done).
3. ~~The redirect narrowing~~ — **merged** (item 4 done; retired
   spellings fail loudly and name `:location`).
4. `reg-`/`register-` audit bead (doc-only unless stragglers found).
5. ~~Lint bead: retired-spelling checks for items 1 and 4~~ — **done**.
   `scripts/check_retired_spellings.py` fails on the retired
   bare `:frame` event-context coeffect read (item 1) and the `:url` / `:to`
   redirect-target key on an SSR redirect fx (item 4), scoped to the retired
   *shapes* so the sanctioned public `:frame` opt / trace tag / fx-handler ctx
   and client-navigation `:url` stay green. Wired into the always-on PR-spine
   python job (`.github/workflows/test.yml`) and the local pre-checkin spine
   (`scripts/test-fast-pr.sh`). The two shapes too ambiguous to lint without
   noise (a `let`-bound redirect map far from the fx id; an exotic coeffect
   accessor) are documented in the script header as deliberate non-targets —
   the runtime guards (`reject-retired-redirect-keys!`, the
   `event_context_coeffect_keys_test` conformance pin) are the backstop there.

## Recommendation

Adopt. The sweep is finite and mostly filed already; the durable value is the
rules, which make the *class* unrepresentable in review — every future "second
spelling" is a named violation instead of a judgment call.
