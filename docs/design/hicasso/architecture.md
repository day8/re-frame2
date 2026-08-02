# Hicasso — architecture

The runtime architecture space, the one live arm, the front half it inherited
from the two-arm period, and the mechanism ladders. Decisions cited as HD-nnn are
normative in [decisions.md](decisions.md); the proof obligations live in
[validation.md](validation.md).

> **Status, 2026-07-31: Hicasso is a React adapter, and there is one arm.** Mike
> ruled that Hicasso is "an adaptor for React that is optimised for re-frame2,
> user ergonomics and performance", and dropped Arm 2 (PATCH). The tournament is
> over, decided on **product direction, not on measurement**: Arm 2 *met* its
> hard gate in real Chromium and its code was retired afterwards (`rf2-m6if4`),
> not because it lost. HD-007's two-equal-arms ruling is superseded accordingly;
> the Arm 2 material below is kept as the design record of a road not taken.

## The space

| | **Views re-run when dirty** | **Views run once; reactive holes** |
|---|---|---|
| **React owns the DOM** | **Hicasso lean-React** — the product line | dead — two-owner input clobber |
| **Own renderer, React at islands** | ~~**Hicasso/PATCH**~~ — withdrawn 2026-07-31 (HD-007, superseded) | dead — authoring pin (HD-002) |

`sub`-as-a-value in open Clojure forces the re-run column: without a compiler to
thunk expressions, run-once economics require hole-based authoring, which the
product rejects. The surviving arm owns the front half outright; while both arms
were live they shared it and diverged only at **who applies the patch** (React
elements vs own DOM).

## The shared front half (built once for both arms; now the adapter's alone)

1. **The Hiccup codec** — runtime interpretation of arbitrary hiccup to the arm's
   element representation, built by extracting reagent-slim's measured tag/prop/
   child plumbing (never its component protocol, ratoms, argv equality, or
   scheduler). Codec-work caching (parsed tags, prop-name conversion, cached
   stable component heads) is in scope (HD-004; it was in scope for both arms
   when both ran).
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

## Arm 1 — Hicasso lean-React (the product line since 2026-07-31)

- A boundary is a **real React function component** minted by `defview`
  (invocation semantics: HD-016). React owns identity, reconciliation, context,
  refs, errors, concurrency, and the controlled-input end-of-event restore —
  the last of these **only while the element is genuinely controlled**, which
  UIx does not guarantee
  ([the two implementations](studio/controlled-input-two-implementations.md)).
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

> **WITHDRAWN 2026-07-31, and the code is gone.** Mike ruled that Hicasso is an
> adapter for React (`rf2-2rtt6.10`), so this arm was dropped on **product
> direction, not on measurement** — it *met* its hard gate, in real Chromium.
> Its tree under `implementation/freehand/test/re_frame/bench/hicasso/arm2/` was
> retired by `rf2-m6if4`. The section stays as the design record. What survived
> the retirement is the controlled-restore witness, moved to
> `bench/hicasso/controlled_restore_dom_cljs_test.cljs` and re-taken on React —
> where the `:unchanged-model-rejection` row does **not** hold, which is open on
> `rf2-n3dxw`.

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
- **Why it stayed in the tournament while one ran**: it is the only family that
  can *beat* the UIx frontier on bulk and memory rather than approach it. That
  argument was never refuted — it was set aside by the product ruling.

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
`flushSync` is never the general default, and its single evidence-gated
exception is the controlled-text converge in the element path (amended by
`rf2-ncn5p`). Rejected/unchanged-model paths lean on React's own end-of-event
restore for the value but not the caret; resets are by explicit caller
revision, never value equality. The 100-cell grid witnesses prove the door.

**Measured 2026-08-01 (rf2-n3dxw), and the lean holds for the value only.**
React's restore does fire on a rejection, inside the discrete event and with
nothing re-rendered, so the refused character comes off the screen for free.
It does not put the caret back: assigning `value` moves the cursor to the end
of the control, and React restores a selection only around a commit in which
focus moved. So a mid-string refusal — and every keystroke a normalising model
rewrites — converges with the caret thrown to the end of the field.

**The caret half is the arm's, taken 2026-08-02 (rf2-fki5d), in the element
path.** `front.codec/native-element` calls `front.controlled/install!`, which
wraps the change handler the author wrote: at the end of it, inside the same
discrete event and ahead of React's restore, `flushSync`, write the value the
element renders if the field disagrees — which makes React's own later write a
no-op — and put the caret back by offset from the end of the string. The
authoring surface does not change (an ordinary `:value` / `:on-input` pair),
**no hook is spent**, and the value the element last rendered is read off the
node as `defaultValue`, which React maintains there itself. The matrix, the
two implementations UIx chooses between, and the price are on
[the studio page](studio/controlled-input-two-implementations.md).

## Memoization (HD-006)

No default `=`/argv memoization: React semantics stand (a child may render with
its parent); narrow updates come from boundary placement; `React.memo` remains an
opt-in escape. Revisit only if the keyed-row or broad witnesses demand it — do not
recreate Reagent's equality semantics from taste.

## Code residence (HD-017)

Instrument arms live on branches against the existing tracked bench trees, with
results published to their beads and the studio table. Runtime skeletons stay
disposable (spike branch or the local `ai/` tree) until the surviving arm
graduates into a tracked `implementation/hicasso/` artefact. (HD-017 wrote that
condition as "the P2 ruling graduates exactly one arm"; the 2026-07-31 product
ruling settled *which* arm ahead of P2, and the residence rule is otherwise
unchanged.) The spike-01 index model is library input to the front half, not a
product namespace.
