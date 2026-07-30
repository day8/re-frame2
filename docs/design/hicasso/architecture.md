# Hicasso — architecture

The runtime architecture space, the two spike arms, what they share, and the
mechanism ladders. Decisions cited as HD-nnn are normative in
[decisions.md](decisions.md); the proof obligations live in
[validation.md](validation.md).

## The space

| | **Views re-run when dirty** | **Views run once; reactive holes** |
|---|---|---|
| **React owns the DOM** | **Hicasso lean-React** (leading) | dead — two-owner input clobber |
| **Own renderer, React at islands** | **Hicasso/PATCH** (equal-class spike arm, HD-007) | dead — authoring pin (HD-002) |

`sub`-as-a-value in open Clojure forces the re-run column: without a compiler to
thunk expressions, run-once economics require hole-based authoring, which the
product rejects. The two live arms share one front half and diverge only at **who
applies the patch** (React elements vs own DOM).

## The shared front half (built once, serves both arms)

1. **The Hiccup codec** — runtime interpretation of arbitrary hiccup to the arm's
   element representation, built by extracting reagent-slim's measured tag/prop/
   child plumbing (never its component protocol, ratoms, argv equality, or
   scheduler). Codec-work caching (parsed tags, prop-name conversion, cached
   stable component heads) is in scope for both arms (HD-004).
2. **The subscription→boundary index** — sub-key `(query-id, args)` → boundary
   set; dirty set computed at commit; edge add/remove on mount/unmount/re-run;
   conditional reads as an edge-set diff. The pure model is proven (spike-01)
   under **six laws**, restated here so the tracked record is self-sufficient:
   (1) after mount+read, a commit of that sub dirties that boundary only;
   (2) two boundaries sharing a sub both dirty; (3) unmount removes edges;
   (4) a re-run with fewer reads drops edges (conditional read); (5) the broad
   dirty set is the union of all readers of any dirty sub; (6) an unknown dirty
   sub yields the empty set — no phantom boundaries. Index edges live in global
   maps — shared structure, not per-boundary object fan-out — and are not
   reactions: only the commit applies the dirty set. **The index serves the two
   product read tiers** (grouped declares its edges; the collector records
   them); the scalar comparator arm does not use it. The index carries a
   ~3-line nil-checked evidence sink seam (HD-005) so tooling can attach later;
   no evidence subsystem ships in v0.
3. **Ergonomics-as-data** — event vectors in attributes, the value placeholder,
   auto-prevent on submit, the composition-gated key-map, `route-link`
   ([authoring.md](authoring.md)).
4. **Sub-layer push-cheap/pull-lazy** — equality cutoff on subscription values at
   the existing sub layer; unchanged hot reads perform no new attach/release.

## Arm 1 — Hicasso lean-React (leading; HD-007)

- A boundary is a **real React function component** minted by `defview`
  (invocation semantics: HD-016). React owns identity, reconciliation, context,
  refs, errors, concurrency, and the controlled-input end-of-event restore.
- **Hook budget ≤ 2 per boundary** (paper rule), fully consumed by the
  subscription/epoch hook and the frame-context hook (HD-020); refs are callback
  refs, never `useRef` in the shell. A ViewCell-class per-boundary object graph
  appearing means the arm has failed.
- **Re-render path (grouped/collector topology)**: commit → dirty sub-keys →
  index → dirty boundary set → per-boundary epoch bump → React re-renders
  exactly those boundaries → bodies re-run against the committed snapshot →
  hiccup → codec → React reconciles. A generation fence keeps all reads within
  one render pass on one commit (invariant-5 preservation; the staged-stale CI
  witness guards it). The scalar *comparator* arm has a different topology —
  React notifies per uSES hook and the index is not in its notify path; it is
  priced by the 1/3/7/20 heap ladder, never by the shell-hook budget.
- **Boundary-exclusive retention is priced, not "absent"**: a boundary
  necessarily retains an identity token, index membership, its
  subscription/epoch cell, and (collector tier) a committed read set. Before an
  arm is admitted, every boundary-exclusive token, callback, hook cell, epoch,
  map entry, and edge membership is inventoried in the 1/3/7/20 heap ladder
  against the 0.4–0.5 KB target — honest accounting, not a claimed absence.
- **Inside-React feasibility constraints** (internalize before writing shells):
  React re-renders only via parent, local state, context, uSES, or root render; a
  root store cannot pay the consistency guarantee "once" (no root observer of
  leaf commits; registering watches during render violates the abandoned-render
  rules); per-boundary uSES is the only tear detector React offers. The honest
  ceiling on bulk may be parity-with-UIx rather than dominance — an open
  measurement, not doom.
- **Interpreter accelerant**: codec caching only. Template extraction with hole
  plans is out of scope for this arm (HD-004) — if an optimization starts wanting
  node references, subscription-addressed holes, or direct DOM writes, it is the
  PATCH strategy and must say so.

## Arm 2 — Hicasso/PATCH (equal-class spike arm; HD-007)

- **Own renderer**: dirty boundaries re-run *inside the commit* against the
  committed snapshot; an own keyed hiccup differ applies the patch; React exists
  only at `defhost` islands. Invariant 5 holds by construction (reads and patch
  happen in the commit's own extent).
- **Differ tiers, one grammar**: template extraction with data-encoded hole plans
  as the static-shape fast path (CSP-safe, no `new Function`; production-proven
  pattern), value-equality cutoff (bail when the new hiccup sexp `=` the old) as
  the fallback, full keyed diff as the floor.
- **Hard-gated obligations**: the **controlled-restore obligation** — off React's
  discrete-event path there is no free end-of-event value restore; the renderer
  must converge controlled value/checked against the live node after every
  controlled dispatch, caret preserved, composition fenced. Plus the islands
  bridge (context/error/event semantics at the seam) and an SSR/hydration story
  of its own. Any PATCH spike that cannot demonstrate the controlled-restore on
  the 100-cell grid witness has failed regardless of its clock numbers.
- **Why it stays in the tournament**: it is the only family that can *beat* the
  UIx frontier on bulk and memory rather than approach it.

## The sub-read mechanism (HD-002: grouped default, collector challenger, scalar comparator)

Three tiers, one product. **Tier 1 — scalar per-read hooks** (the raw UIx
spine): a comparator arm only, the measured floor, exempt from the product hook
budget because it is the control (N reads = N hooks can never satisfy the
product budget, and hook rules forbid conditional reads). **Tier 2 — grouped
`use-subs`, the product default**: one fixed hook receiving the complete query
collection before the body; query values may vary while hook count and order
stay fixed; conditional needs are met by conditional child boundaries and
conditionally-constructed query values at fixed sites; its canonical spelling
is pre-declared and dogfooded from P1 start. **Tier 3 — the ambient collector,
the challenger ridden hardest**: `(sub q)` as a plain tracked read anywhere in
the body, one fixed runtime hook, edge diff after the body; it replaces grouped
only by winning the per-read instrumentation under the HD-002 adjudication
clauses (the render/commit ownership state machine; the allowed edge-diff
operation vs the forbidden ledger; two pre-registered strategy hypotheses
counted by benchmarked commits; the warm 1/3/7/20 allocation-slope survival
metric) — and the standing tripwire **overrides the clock**: the first need for
a candidate ledger or generic post-render dependency reconciliation kills the
collector outright. It is the same mechanism class as the predecessor's
per-read ledger — the single largest measured killer — which is why it must win
its place rather than defend it, and why it is worth riding hard: it carries
the census's tier-1 authoring shape (conditional reads legal) and most of the
differentiation vs raw UIx. If both product tiers fail their gates, the outcome
is null — never a fourth read model.

**Frame plumbing and the hook ledger (HD-020)**: each boundary reads the frame
once via the substrate's single internal context, then binds it ambiently for
the render's dynamic extent — inlined helpers and generated callbacks resolve it
without hooks. The ≤2 hook budget is thereby fully consumed (subscription/epoch
hook + frame-context hook); shells use callback refs, never `useRef`.

**The synchronous controlled-input door (HD-019)**: controlled-element intent
lowering dispatches synchronously inside the discrete browser event; the store
notification runs synchronously so React commits the echo in the same turn;
`flushSync` is the evidence-gated last resort. Rejected/unchanged-model paths
lean on React's own end-of-event restore; resets are by explicit caller
revision, never value equality. The 100-cell grid witnesses prove the door.

## Memoization (HD-006)

No default `=`/argv memoization: React semantics stand (a child may render with
its parent); narrow updates come from boundary placement; `React.memo` remains an
opt-in escape. Revisit only if the keyed-row or broad witnesses demand it — do not
recreate Reagent's equality semantics from taste.

## Code residence (HD-017)

Instrument arms live on branches against the existing tracked bench trees, with
results published to their beads and the studio table. Runtime skeletons stay
disposable (spike branch or the local `ai/` tree) until the P2 ruling graduates
exactly one arm into a tracked `implementation/hicasso/` artefact. The spike-01
index model is library input to the front half, not a product namespace.
