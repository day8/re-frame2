# EP-0007: One Name Per Fact

Status: proposal
Type: process

> A process EP (the PEP-8 analogue, per EP-0009): on acceptance, the rules in
> §Specification graduate into `spec/Conventions.md` §Namespacing — which
> remains the authoritative home — and this EP stays as the rationale record.
> The sweep items are independent beads, each separately rulable; accepting the
> rules does not bulk-accept the sweep.

## Abstract

re-frame2 keeps re-growing parallel spellings for single facts. Recent and
current instances from this review cycle: the frame stamp rode event contexts
as both `:frame` and `:rf.frame/id`; the runtime partition is
`:rf.db/runtime` while its children are `:rf.runtime/*`; the work-ledger draft
carried `:work/id` and `:stale-key` as near-duplicate composite identities;
the SSR redirect target accepts `:location` / `:url` / `:to`; the
registration API splits into `reg-*` and `register-*` families; and "schema"
names four different validators across the `reg-*` surface. Each instance is
small. The class is permanent, because the project has naming *conventions*
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

1. **The frame stamp (rf2-1m6rf1, verified).** Spec 002's normative event
   context carries `:rf.frame/id` only; the implementation injects `:frame`
   beside it on every event, and ~40 internal sites consume the retired
   spelling. The rename was additive, not a rename — and EP-0002 R3's "one
   carrier, one name" is contradicted on the hot path it was written for.
2. **The redirect synonyms (rf2-vngir).** Three accepted keys for one value —
   deliberately widened, now permanent surface sprawl awaiting an API-freeze
   that has no normative home.
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

- Not the registration-arity/metadata-uniformity question (deferred under
  rf2-iczn3); this EP is vocabulary only.
- Not a rename-everything pass: inherited re-frame vocabulary (`:db`, `:event`,
  `:fx`, the public `:frame` opt) is explicitly sanctioned and untouched.
- Not blocking other work: each sweep item is independently landable.

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

### The sweep (current instances, each one bead-sized)

| # | Instance | Resolution |
|---|---|---|
| 1 | `:frame` coeffect beside `:rf.frame/id` | **Done** — `rf2-1m6rf1` merged 2026-06-10 (the `:frame` coeffect dropped, internal consumers migrated); retained here as the sweep's precedent row |
| 2 | `:rf.db/runtime` parent vs `:rf.runtime/*` children | **Keep, as a recorded rule**: `:rf.db/*` names partition *slots* of frame-state (`:rf.db/app`, `:rf.db/runtime`); `:rf.runtime/*` names *subsystem children* inside the runtime partition — globally greppable when detached from context. EP-0001 Appendix A asked for the split to be justified or aligned; this justifies it as a layer rule (rule 3) |
| 3 | Work-ledger `:work/id` vs `:stale-key` | One identity: stale suppression keys on the work id (or EP-0003 must justify the distinction as transport-facing); the denormalized fields are declared projections (rule 4). Folded into `rf2-uh3pbx`'s notes; resolved at EP-0003 acceptance |
| 4 | Redirect `:location`/`:url`/`:to` | Canonical key is `:location` per rule 3's vocabulary rule. **Timing is `rf2-vngir`'s standing disposition, unchanged by this EP**: a one-line "preferred spelling" docs steer may land any time; the breaking narrowing lands at API-freeze — or earlier only by explicit ruling on that bead |
| 5 | `reg-*` vs `register-*` families | Audit: `reg-*` = registrar-kind registrations; `register-*` = listener/side-table attachments (`register-listener!`, `register-marks!`, `register-error-listener!`). If the split is principled, record it as a rule; align any stragglers |
| 6 | The `:schema` family | Record the map: `reg-event-*` `:schema` validates *event args*; machine `:data-schema` validates machine `:data` (EP-0005's rename — the precedent: qualify where a visible sibling creates ambiguity); `reg-app-schema` validates app-db paths; runtime-db schemas are framework-owned. One Conventions table; no renames expected beyond what EP-0005 already did |

### Enforcement

- The synonym-rejection rule (rule 2) gets the no-floor-lint treatment where
  shapes allow: a retired spelling appearing in source is a CI failure, not a
  doc note.
- New-surface review checklist: "does this introduce a second spelling for an
  existing fact?" — one line in the EP template / implementor skill.

## Backwards Compatibility

Pre-alpha, in-repo only. Sweep item 1 is done (merged); item 4 is a breaking
narrowing whose temporary alias window stays with `rf2-vngir`; 2, 5, 6 are
documentation; 3 lands inside EP-0003's own pre-acceptance amendments.

## Bead Plan

1. Conventions bead: add the rules + the vocabulary tables (hot-zone;
   sequential). This is the graduation bead.
2. ~~`rf2-1m6rf1`: the frame-stamp completion~~ — **merged** (item 1 done).
3. `rf2-vngir` (existing, parked): the redirect narrowing, on its own standing
   timing (API-freeze, or earlier by explicit ruling); the optional
   preferred-spelling docs steer is dispatchable any time.
4. `reg-`/`register-` audit bead (doc-only unless stragglers found).
5. Lint bead: retired-spelling checks for items 1 and 4 (item-1 lint can land
   now that the rename is merged).

## Recommendation

Adopt. The sweep is finite and mostly filed already; the durable value is the
rules, which make the *class* unrepresentable in review — every future "second
spelling" is a named violation instead of a judgment call.
