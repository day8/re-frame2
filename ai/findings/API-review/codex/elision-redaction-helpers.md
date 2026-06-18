# Elision / Redaction Helper Family

Status: draft finding — **new in the 2026-06-18 fresh consolidation pass**.
This was the most-converged *new* area: four independent lenses (minimalist
facade, technical taste, model coherence, empirical call-site) flagged it
without prior corpus coverage.

## Crowding Signal

The public egress/redaction boundary is two primitives — `project-egress`
(record-level boundary) and `elide-wire-value` (size-elision walk). Beneath them,
the facade also exports a **9-member low-level toolkit** of collect/match/derived
helpers, doubled across a sensitive-axis and a large-axis. They are the internal
decomposition of `project-egress` leaked onto `re-frame.core`, exposed because
exactly one in-repo consumer wanted to share a single collection pass across N
output slots. SMALL-CORE-plus-COMPOSITION lost to a disassembled-gears surface.

The facade members (`core.cljc:2871-2958`):

- `redact-derived-values`, `redact-derived-large-values`
- `redact-matching-values`, `redact-matching-large-values`
- `elision-sensitive-value-set`, `elision-large-value-marker-map`
- `elision-collect-sensitive-values`
- `elision-declarations`, `elision-sensitive-declarations`

## Implementation / call-site evidence (verified)

- All 9 are exported at `core.cljc:2871-2958`, tier `:tooling`, `:facade? true`
  in `spec/api-manifest.edn:112-165`.
- **Spec drift:** none of the 9 are rowed in `spec/API.md` (only the higher-level
  `elide-wire-value` and `project-egress` are documented). API.md's own
  projection-maintenance rule (`API.md:37`) says every shipped facade surface
  must be rowed — 9 `:facade? true` vars are unrowed.
- **One assembling consumer.** Empirical counts across `examples/**` +
  `tools/**/src/**`: examples = 0 for all 9. `tools/story-mcp/.../egress.cljc`
  is the single tool that wires them — `scrub-rendered` (≈line 264) uses the two
  high-level `redact-derived-*` forms, while `scrub-explain-values` (≈line 334)
  re-implements the same thing from the four low-level primitives
  (`elision-sensitive-value-set` + `redact-matching-values`,
  `elision-large-value-marker-map` + `redact-matching-large-values`) purely to
  collect once and substitute over N slots. `elision-declarations` = 0 tools / 7
  tests; `elision-sensitive-declarations` = 0 tools / 1 test.
- Contrast the real boundary primitives: `elide-wire-value` ≈ 19 tools-src / 73
  tests; `project-egress` ≈ 4 tools-src / 28 tests. Those earn their facade slot;
  the 9 do not.
- The pair-mcp egress driver (`tools/re-frame2-pair-mcp/...`) is the same shape
  of consumer.

The pattern is textbook "every call site re-wraps the same way" → a **missing
higher-level primitive**, not nine public ones.

## Observed use cases

1. Redacting a derived tree (rendered hiccup, snapshot body, plan-resolved
   slots) against a frame's declared sensitive/large values, where the value
   re-surfaces at a non-app-db position the path walker can't reach.
2. Driving multi-slot redaction from **one** collection pass (the only reason
   the four low-level gears are public).
3. Tools/tests reach `project-egress` + `elide-wire-value` for the boundary;
   nothing in app or example code touches the nine.

## Proposed smaller API

Keep the two boundary primitives on the facade: `project-egress`,
`elide-wire-value` (plus `sensitive?`). Add **one** composed multi-slot helper —
shape roughly `(redact-derived-slots m slot-keys db frame opts)` — that does the
collect-once / substitute-many pass internally (sensitive then large, since
sensitive always wins). Move the seven `redact-*` / `elision-*-value-set` /
`*-marker-map` step helpers and the two `*-declarations` readers off the facade
into `re-frame.elision` (their actual home — they are already `elision/…`
aliases). story-mcp / pair-mcp call the one composed helper, or require
`re-frame.elision` directly per the project's "tools reach canonical homes
directly" rule (`API.md:83`).

Net: 9 facade exports → ~1 new composed helper (or 0, if the tools require the
ns), and the API.md projection-drift closes either by removing the rows or by
documenting one family block in §Data classification.

## Related: marks imperative residue (fold-in from the technical-taste pass)

The same EP-0015 redaction surface carries a second, smaller smell. The public
path is now frame-owned `:sensitive` / `:large` declaration + `project-egress`,
and `elision.cljc:16` asserts "There are no imperative large-path APIs" — yet:

- `re-frame.marks/add-marks` + `set-marks` survive as internal twins of the
  removed public marks surface (`core.cljc:62-71` documents the facade removal
  while keeping `re-frame.marks` as a side-effect require), and
- `reg-fx` still pokes the imperative marks registry on every registration:
  `(late-bind/get-fn :marks/register-marks!)` at `fx.cljc:96-101`.

The *need* — registration-owned event-arg marks for
`:marks/redact-event-by-registration` (`projection.cljc:221-232`) — is
legitimate. The smell is that it is a live registry **mutated imperatively**
rather than **derived from the registration metadata** the framework already
holds. The project already chose "derive from the registration spec, no
side-table" for machine guards (`core-machines.cljc:90-129`, "read it back rather
than duplicating it into a second registrar entry"). Apply the same here: derive
event/fx marks from the descriptor's `:sensitive` / `:large` at projection time,
and delete the imperative `add-marks` / `set-marks` registry plus the `reg-fx`
poke (DATA over FUNCTIONS).

## Classification

Beads after the boundary decision. The core move (9 → 1 composed helper +
re-frame.elision home) is an ordinary facade-pruning bead once the single tool
consumer is repointed; it also fixes the API.md projection drift. The marks
residue is a separate small data-over-functions cleanup. Neither is EP-level.

## Why this is better

The public redaction language should be "name the egress boundary"
(`project-egress`) and "elide a wire value" (`elide-wire-value`). When the only
reason a primitive is public is "a consumer needs to share an intermediate
result," the framework should expose the composed operation, not the
disassembled gears — and it should derive marks from the registration data it
already owns rather than mutating a parallel imperative registry.

## Implementation

- **Vehicle: ordinary beads.** No EP.
- Beads: (1) add one composed multi-slot egress helper, move the 9 granular helpers
  to `re-frame.elision`, repoint story-mcp/pair-mcp, and close the `spec/API.md`
  projection drift; (2) derive event/fx marks from registration metadata and delete
  the imperative `add-marks` / `set-marks` registry + the `reg-fx` poke.
- Pre-alpha disposition: the granular facade exports are removed, not demoted.
- Hot-zone: `core.cljc`, `api-manifest*.edn`, `spec/API.md`.
