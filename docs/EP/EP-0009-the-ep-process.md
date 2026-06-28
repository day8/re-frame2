# EP-0009: The EP Process

Status: active
Type: process

> The PEP-1 analogue: this active process EP defines what EPs are, when one is
> warranted, the type and status vocabulary, and the durability rules.
> `docs/EP/README.md` is the index; this EP is the process rule.
>
> Normative home: **this active EP**, specifically its §Specification.

> **Ruling recorded 2026-06-11.** The corpus already follows this
> process de facto, and the first coherence remediation made the process
> dependency explicit by marking this EP `active`. This note records that
> activation in the EP itself so the process document follows its own
> recorded-ruling rule.

## Abstract

re-frame2 EPs follow the Python Enhancement Proposal model: **durable,
numbered design records** that carry a decision from proposal through ruling
into a normative home, and then remain forever as the record of *why* — including
when the answer was no. The authority rule is **per type**: a standards-track
EP graduates into `spec/`, which is then the authoritative contract — the EP is
the design document behind it, never a second normative home. A **process** EP
graduates into its *named normative home*, which MAY be the active EP itself
(the PEP-8 precedent: the style guide *is* the PEP). Every active process EP
names its normative home explicitly so there is exactly one.

## Relationships

- **[README](README.md)** is the index and summary page only. It mirrors
  statuses and points readers at this EP for process rules; it is not a second
  normative home.
- **`scripts/check_ep_status_sync.py`** enforces the mechanical part of this
  process: README/header status sync, status grammar, and the post-EP-0005
  `Type:` header rule.
- **EP-0005 and EP-0006** are the pattern for final EPs that outlive their
  implementation waves through a closed errata ledger. This EP generalizes
  that pattern for future standards-track EPs.
- **EP-0007 (one name per fact)** is the sibling process EP that graduates into
  `spec/Conventions.md`. This EP remains active as its own process rulebook.

## Specification

### When an EP is warranted

An EP is for:

- **feature-level or public-contract decisions with unresolved alternatives**
  (new artefacts, breaking contract changes, cross-cutting invariants —
  EP-0001..0006, EP-0008); or
- **process and convention rules worth a durable rationale record** (the PEP-8
  analogue — EP-0007, this EP).

Settled, mechanical material — a rename with no alternatives, a doc fix, a
bug — goes straight to beads and `spec/` edits. The test: *if the design
conversation fits in a bead description, it is a bead.* When in doubt, the
operator (Mike) decides; EPs are cheap to reject and rejection is itself a
valuable record.

### Types

| Type | Meaning | Terminal success status |
|---|---|---|
| **standards-track** | changes the framework's public contracts or runtime behavior; graduates into `spec/` + implementation beads | `final` |
| **process** | rules about how the project itself works (conventions, lifecycle, review posture); on acceptance it **names its normative home** — either an existing spec doc (EP-0007 names `Conventions.md` §Namespacing) or the active EP itself (this EP's home is this EP) — and that one home governs | `active` |

EP-0001 through EP-0005 may omit the `Type:` header and are treated as
standards-track. EPs numbered EP-0006 and later carry an explicit `Type:`
header.

### Statuses

```text
proposal ──► accepted ──► final            (standards-track)
    │             └─────► active           (process)
    ├──► rejected     (ruled no — kept as the record of why not)
    ├──► withdrawn    (author retracted — kept)
    └──► deferred     (parked with a stated revisit trigger)

any non-terminal EP may become: superseded-by EP-NNNN (kept)
```

The `Status:` line is machine-readable. Its value MUST be one of:
`proposal`, `accepted`, `final`, `active`, `rejected`, `withdrawn`,
`deferred`, or `superseded-by EP-NNNN`.

- **proposal** — under discussion. Normative-voiced text is permitted (it makes
  graduation a move, not a rewrite) but binds nothing; the proposal itself is
  not implementation authority. Independently settled work may still land by
  bead/spec/operator ruling and be recorded in the proposal as evidence or an
  already-resolved dependency, but any decision that relies on the EP waits for
  acceptance.
- **accepted** — ruled by the operator; graduation into the normative home +
  beads begins. EP-authorized implementation may start.
- **final / active** — graduated. The named normative home is now
  authoritative: for standards-track that is `spec/`, and **where the EP body
  and the spec differ, the spec governs** (the EP-0002 precedent); for process
  EPs it is the home the EP named — possibly the EP itself, in which case the
  EP's §Specification *is* the rule and the rest of the document remains
  rationale. A final EP MAY carry an **implementation-errata ledger** (the
  EP-0005 pattern): *final means the decisions are settled; it does not assert
  the build is gap-free.* Ledger rows cite live bead ids and are struck as they
  close.
- **rejected / withdrawn / deferred / superseded** — terminal or parked, and
  **always kept**: recording why something was not done is half an EP corpus's
  value.

### Durability rules

1. **EPs are never deleted.** Numbers are stable and never reused.
2. **Resolved Decisions live in the EP.** Operator rulings on an EP's open
   issues are recorded in a `Resolved Decisions` section (the EP-0001/0002
   pattern); an EP must not go `final`/`active` with open issues silently
   unresolved (the EP-0004 lesson — dispositions are recorded even when the
   answer emerged from the implementation).
3. **Amendment is allowed only before implementation.** Once an EP is accepted
   and beads are in flight, substantive changes go through a new EP or a
   recorded ruling — not silent edits. (Mechanical corrections and errata-ledger
   updates are always fine.)
4. **One EP, one decision surface.** Bundling independent decisions invites
   all-or-nothing rulings; prefer narrow EPs and cross-references.
5. **Graduation includes a guide-impact assessment:** the graduating EP names
   which `docs/guide` chapters change and which payoffs become newly teachable;
   the docs bead of the EP's wave carries those edits (e.g. EP-0010 upgrades
   guide ch.07's clock section from the cofx idiom to the envelope stamp;
   EP-0011 rewrites ch.10's no-await box around the reply envelope; EP-0008
   extends ch.16's production section with the always-on error axis; EP-0013
   eventually warrants a realms chapter). An EP may conclude "no human-facing
   guide change yet" and record why.

### Document conventions

- Filename `EP-NNNN-slug.md`; preamble lines `Status:` and `Type:`; standard
  sections (Abstract, Motivation, Goals/Non-Goals, Relationships,
  Specification, Rationale, Backwards Compatibility, Bead Plan /
  Reference Implementation, Open Issues, Recommendation) as applicable.
- New EPs carry `Type: standards-track` or `Type: process`. EPs predating this
  process may omit `Type:` and are treated as standards-track.
- State the positive principle first; subtractive framing is migration
  mechanics, not the rule (the EP-0002 lesson).
- Dependencies live in `Relationships`; the index row in `docs/EP/README.md`
  carries status + one-line summary. `check_ep_status_sync.py` enforces README
  status sync, the status grammar, and the `Type:` header rule for post-EP-0005
  EPs.

## Backwards Compatibility

This codifies the process the EP corpus is already converging on. Existing
EP-0001..EP-0005 files may continue without `Type:` headers; they are treated
as standards-track. The README remains an index, not a second normative home.

## Bead Plan

1. **Done.** README bead: `docs/EP/README.md` is the per-type authority summary,
   lifecycle vocabulary, and index, pointing here for the full process.
2. **Done.** Tooling bead: `check_ep_status_sync.py` enforces README/header
   status sync, status grammar, and the post-EP-0005 `Type:` header rule.
3. **Open.** Template/update bead: update any EP authoring
   template or implementor skill that still treats `Status:` as free text or
   assumes every successful EP graduates into `spec/`.

## Recommendation

Keep this EP `active`. It codifies the model the corpus already de-facto
follows (stable numbers, graduation into spec, resolved-decision records, the
errata ledger) and adds the missing pieces: the worthiness bar, the type split,
the terminal statuses, and the never-delete rule.
