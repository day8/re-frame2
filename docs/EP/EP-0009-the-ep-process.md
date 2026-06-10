# EP-0009: The EP Process

Status: proposal
Type: process

> The PEP-1 analogue: this EP defines what EPs are, when one is warranted, the
> type and status vocabulary, and the durability rules. On acceptance it
> becomes **active** (process EPs stay active rather than graduating to final)
> and `docs/EP/README.md` becomes a thin index pointing here.
>
> Normative home after acceptance: **this active EP**, specifically its
> §Specification.

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

EPs predating this one carry no `Type:` header and are standards-track.

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
  graduation a move, not a rewrite) but binds nothing; nothing may implement a
  proposal.
- **accepted** — ruled by the operator; graduation into the normative home +
  beads begins. Implementation may start.
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

1. README bead: reduce `docs/EP/README.md` to the per-type authority summary,
   lifecycle vocabulary, and index, pointing here for the full process.
2. Tooling bead: extend `check_ep_status_sync.py` so the documented status and
   type grammar is checked, not merely synchronized.
3. Template/update bead: update any EP authoring template or implementor skill
   that still treats `Status:` as free text or assumes every successful EP
   graduates into `spec/`.

## Recommendation

Adopt, marking this EP `active`. It codifies the model the corpus already
de-facto follows (stable numbers, graduation into spec, resolved-decision
records, the errata ledger) and adds the missing pieces: the worthiness bar,
the type split, the terminal statuses, and the never-delete rule.
