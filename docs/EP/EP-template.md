# EP-NNNN: <Title>

Status: proposal
Type: standards-track

<!--
AUTHORING TEMPLATE — copy this file to `EP-NNNN-<slug>.md`, fill it in, and
delete every HTML comment (including this one) before opening the PR.

This template is the skeleton for a new Enhancement Proposal. The process it
encodes is normative in [EP-0009](EP-0009-the-ep-process.md) (an `active`
process EP); this file is only the convenience scaffold. Where this template
and EP-0009 ever differ, EP-0009 governs.

The template is NOT itself an EP: it has no number, carries no decision, and is
deliberately named `EP-template.md` so the status-sync guard
(`scripts/check_ep_status_sync.py`) — which only matches `EP-NNNN-<slug>.md` —
skips it. Do not assign it a number or add it to the README index.

==== Status: a controlled lifecycle, NOT free text ====

`Status:` is machine-readable and its value MUST be exactly one of:

    proposal · accepted · final · active
    rejected · withdrawn · deferred · superseded-by EP-NNNN

A new EP opens at `proposal`. The lifecycle (EP-0009 §Statuses) is:

    proposal ──► accepted ──► final            (standards-track)
        │             └─────► active           (process)
        ├──► rejected     (ruled no — kept as the record of why not)
        ├──► withdrawn    (author retracted — kept)
        └──► deferred     (parked with a stated revisit trigger)

    any non-terminal EP may become: superseded-by EP-NNNN (kept)

EPs are never deleted and numbers are never reused; the terminal states are
kept because recording why something was NOT done is half the corpus's value.

==== Type: standards-track OR process ====

`Type:` MUST be exactly `standards-track` or `process` (EP-0006 and later
require the header; the gate enforces it).

  * standards-track — changes the framework's public contracts or runtime
    behaviour. Terminal success status `final`. On graduation it moves into
    `spec/` + implementation beads; `spec/` is then the authoritative contract
    and **where the final EP and the spec differ, the spec governs**. The EP
    is the design record behind the spec, never a second normative home.

  * process — rules about how the project itself works (conventions,
    lifecycle, review posture). Terminal success status `active`. On
    acceptance it NAMES its normative home — either an existing spec doc
    (e.g. EP-0007 names `Conventions.md` §Namespacing) or the active EP
    itself (the PEP-8 precedent — EP-0009's home is EP-0009). Name exactly one.

==== Not every EP graduates into spec/ ====

Graduation is per-type, and graduation is not the only outcome. A process EP
may graduate into the active EP itself rather than `spec/`; any EP may end at
`rejected` / `withdrawn` / `deferred` / `superseded` and is kept as the record.
`final`/`active` asserts the DECISIONS are settled — it does not, on its own,
assert the implementation is build-complete (a `final` EP may carry an
implementation-errata ledger; the EP-0005 pattern).

==== Is an EP even warranted? ====

An EP is for feature-level / public-contract decisions with unresolved
alternatives, or process/convention rules worth a durable rationale record.
Settled mechanical material — a rename with no alternatives, a doc fix, a bug —
goes straight to beads + `spec/` edits. The test: *if the design conversation
fits in a bead description, it is a bead.* When in doubt, the operator decides;
EPs are cheap to reject and rejection is itself a valuable record.

==== Sections ====

Keep the sections below that apply and delete the rest; the set is the
EP-0009 §Document conventions list. State the positive principle first —
subtractive framing is migration mechanics, not the rule.
-->

> One-paragraph orientation: what this EP decides and what its normative home is.
> A `process` EP states its named normative home here explicitly.

## Abstract

<!-- Two or three sentences: the decision, in plain language. -->

## Motivation

<!-- The problem. Why a bead is not enough — the unresolved alternatives or the
durable rationale that warrants an EP. -->

## Goals / Non-Goals

<!-- What is in scope for this decision surface, and what is explicitly out.
One EP, one decision surface — bundling independent decisions invites
all-or-nothing rulings. -->

## Relationships

<!-- Dependencies and cross-references live HERE (not scattered in prose):
the EPs and specs this builds on, supersedes, or is constrained by. The
README index row carries only status + a one-line summary. -->

## Specification

<!-- The normative content. For a standards-track EP this is the design that
will graduate into `spec/`; for a process EP it is the rule (and, if the home
is this EP, this section IS the rule and the rest of the document is
rationale). Normative-voiced text is permitted at `proposal` — it makes
graduation a move, not a rewrite — but it binds nothing until accepted. -->

## Rationale

<!-- Why this shape over the alternatives considered. -->

## Backwards Compatibility

<!-- Pre-alpha: there is usually no compatibility surface to preserve. State
the migration mechanics, if any, here — not as the headline rule. -->

## Bead Plan / Reference Implementation

<!-- The waves that carry this EP into its normative home + implementation.
Each graduation wave includes a guide-impact assessment: name which
`docs/core` chapters change and which payoffs become newly teachable, or
record "no human-facing guide change yet" and why. Ledger rows cite live
bead ids and are struck as they close. -->

## Open Issues

<!-- Questions awaiting an operator ruling. An EP MUST NOT go `final`/`active`
with open issues silently unresolved — record the disposition of each in a
`Resolved Decisions` section as rulings land (the EP-0001/0002 pattern). -->

## Recommendation

<!-- The author's recommended disposition for the operator to rule on. -->
