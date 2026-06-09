# EP-0007: One Name Per Fact

Status: proposal

## Abstract

re-frame2 keeps re-growing parallel spellings for single facts. Current
instances: the frame stamp rides event contexts as both `:frame` and
`:rf.frame/id`; the runtime partition is `:rf.db/runtime` while its children
are `:rf.runtime/*`; the work-ledger draft carries `:work/id` and `:stale-key`
as near-duplicate composite identities; the SSR redirect target accepts
`:location` / `:url` / `:to`; the registration API splits into `reg-*` and
`register-*` families; and "schema" names four different validators across the
`reg-*` surface. Each instance is small. The class is permanent, because the
project has naming *conventions* (reserved namespaces, attribute-shaped keys)
but no naming *rules* for synonyms, layers, and carriers.

This EP states the rule once:

> **Every fact has one canonical name per layer. Synonyms are never accepted.
> Where two layers legitimately use different words for related concepts, the
> distinction is recorded as a named vocabulary rule, not left as accident.**

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
2. **No accepted synonyms.** APIs accept exactly one spelling; a retired or
   alternative spelling is a hard error naming the canonical key, never a
   silently-normalized alias.
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
   (Shared with EP-0006 clause 4/5; stated here because it is a *naming*
   discipline too — the projection should not mint a new key for the same
   fact.)

### The sweep (current instances, each one bead-sized)

| # | Instance | Resolution |
|---|---|---|
| 1 | `:frame` coeffect beside `:rf.frame/id` | Drop the `:frame` coeffect; migrate internal consumers (already filed: `rf2-1m6rf1`; this EP is its normative home) |
| 2 | `:rf.db/runtime` parent vs `:rf.runtime/*` children | **Keep, as a recorded rule**: `:rf.db/*` names partition *slots* of frame-state (`:rf.db/app`, `:rf.db/runtime`); `:rf.runtime/*` names *subsystem children* inside the runtime partition — globally greppable when detached from context. EP-0001 Appendix A asked for the split to be justified or aligned; this justifies it as a layer rule (rule 3) |
| 3 | Work-ledger `:work/id` vs `:stale-key` | One identity: stale suppression keys on the work id (or EP-0003 must justify the distinction as transport-facing); the denormalized fields are declared projections (rule 4). Folded into `rf2-uh3pbx`'s notes; resolved at EP-0003 acceptance |
| 4 | Redirect `:location`/`:url`/`:to` | Prune to `:location` per rule 3's vocabulary rule (subsumes `rf2-vngir`); land with this EP rather than waiting for a separate API-freeze |
| 5 | `reg-*` vs `register-*` families | Audit: `reg-*` = registrar-kind registrations; `register-*` = listener/side-table attachments (`register-listener!`, `register-marks!`, `register-error-listener!`). If the split is principled, record it as a rule; align any stragglers |
| 6 | The `:schema` family | Record the map: `reg-event-*` `:schema` validates *event args*; machine `:data-schema` validates machine `:data` (EP-0005's rename — the precedent: qualify where a visible sibling creates ambiguity); `reg-app-schema` validates app-db paths; runtime-db schemas are framework-owned. One Conventions table; no renames expected beyond what EP-0005 already did |

### Enforcement

- The synonym-rejection rule (rule 2) gets the no-floor-lint treatment where
  shapes allow: a retired spelling appearing in source is a CI failure, not a
  doc note.
- New-surface review checklist: "does this introduce a second spelling for an
  existing fact?" — one line in the EP template / implementor skill.

## Backwards Compatibility

Pre-alpha, in-repo only. Sweep items 1 and 4 are breaking narrowings with
mechanical migrations; 2, 5, 6 are documentation; 3 lands inside EP-0003's own
pre-acceptance amendments.

## Bead Plan

1. Conventions bead: add the rules + the vocabulary tables (hot-zone;
   sequential).
2. `rf2-1m6rf1` (filed): the frame-stamp completion — becomes this EP's item 1.
3. Redirect-narrowing bead: schema + impl + Spec 011 + tests to `:location`
   (subsumes `rf2-vngir`).
4. `reg-`/`register-` audit bead (doc-only unless stragglers found).
5. Lint bead: retired-spelling checks for items 1 and 4.

## Recommendation

Adopt. The sweep is finite and mostly filed already; the durable value is the
rules, which make the *class* unrepresentable in review — every future "second
spelling" is a named violation instead of a judgment call.
