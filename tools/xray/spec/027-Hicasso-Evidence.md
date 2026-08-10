# 027 — The Hicasso Evidence tab

**Status**: shipped (rf2-hic-023) · **Tab**: `:hicasso`, Dynamic L4, order 10, mnemonic `h`
**Producer**: `re-frame.hicasso.tool` (`implementation/hicasso/`)
**Consumer**: `day8.re-frame2-xray.panels.hicasso` + `…panels.hicasso-reads` + `…panels.hicasso-helpers`
**Normative upstream**: `docs/design/hicasso/product/specification.md` §10 · `docs/design/hicasso/product/lanes/testing-xray.md` §Evidence contract

---

## What this tab is for

Hicasso is a lean-React view substrate: a boundary is a real React function
component, the runtime owns which boundaries a commit must re-run, and
everything else is React's. The tab answers the four questions spec SN §10
says a developer actually asks of such a substrate, one sub-view each.

| Sub-view | Question | Envelope read |
|---|---|---|
| **Mounted** | Which boundaries are mounted, over which frames? | `:mounted-boundaries` |
| **Reads** | Which boundaries read each subscription, at what fan-out? | `:read-attribution` |
| **Intents** | What was dispatched, in order, in the retained window? | `:intents` |
| **Why** | Which reads changed, and what does that prove? | `:explain-render` |

The sub-view is panel-local app-db state (`:rf.xray.hicasso/set-view` /
`:rf.xray.hicasso/view`), normalised on write so a stale or hand-dispatched
id lands on a view that exists. All four envelopes are taken in ONE turn by
`:rf.xray.hicasso/data` — the rosters are projections of a single runtime
state, and reading them across two turns would let a mount land between the
census and the edges.

---

## The evidence contract

Every envelope carries seven axes. Three identify it; four state how far to
trust it.

```clojure
{:schema     :re-frame.hicasso.evidence/v1   ; validated FIRST
 :producer   :re-frame/hicasso               ; the schema is adapter-neutral
 :read       :mounted-boundaries             ; which question this answers
 :scope      :mounted-boundaries             ; what it covers
 :basis      :observation                    ; how it knows
 :complete?  true                            ; ONLY ever relative to :scope
 :loss       nil}                            ; or {:reason … :dropped …}
```

`:complete?` means nothing on its own — it is completeness FOR the stated
scope. `:loss` is the honest half: `nil` says nothing was dropped; a loss map
names a reason from the closed vocabulary and a `:dropped` that is a count or
the explicit `:unknown`.

### The five states, and the one rule that matters

| State | Where it lives | Means |
|---|---|---|
| `:unknown` | a field's VALUE, and a loss's `:dropped` | the fact is not held; not empty, not zero |
| `:opaque` | a basis AND a loss reason | the substrate keeps no such fact, deliberately and permanently |
| `:host-opaque` | a basis AND a loss reason | React owns it and does not publish it |
| `:cap` | a loss reason | a retention window bounded what could be carried |
| `:uncorrelated` | a loss reason | the fact is real but joins to nothing |

**Unknown is never encoded as an empty collection.** The producer's
`evidence/projection` door refuses a projection that answers `[]` under an
`:opaque` or `:host-opaque` basis and requires the roster to state `:unknown`
instead — the one refusal the sibling schemas do not have, and the reason
this tab can render an absence without a reader having to guess whether it
found nothing or looked at nothing.

### Xray and Pair consume the same bytes

There is ONE door. `re-frame.hicasso.tool` exposes exactly four reads, none
of which takes an argument — no audience, no profile, no verbosity — so the
AI pair calling `read-mounted-boundaries` on a running application receives
the identical value Xray does. Xray's read seam (`hicasso-reads.cljs`) passes
each envelope through UNCHANGED, and `hicasso_cljs_test/the-seam-reshapes-nothing`
asserts the whole Xray chain — seam and the subscription the view derefs —
is `pr-str` identical to the producer's answer. Row shaping happens one layer
further out, in the pure helpers, against an envelope a reader can still see
whole.

The producer's rosters are sorted through one total order, so two calls over
one runtime state print identically; that determinism is the precondition the
byte claim rests on and it has its own witness
(`tool_reads_cljs_test/every-read-is-deterministic`).

### The schema pin is consumer-owned

`hicasso-helpers/consumed-evidence-schema` and `…/consumed-producer` are
LITERALS, deliberately not the producer's vars. Deriving support from the
producer makes any bump silently "supported", so an evolved shape would be
mis-parsed as exact and the version boundary would be nominal. A projection
stamped a schema (or a producer) this build was not taught suppresses rows
and renders the mismatch banner.

---

## What the producer projects, and what it refuses to invent

Everything is projected from state the runtime ALREADY retains: the read-set
entry cache, the cell table and its reader lists, the frame-ops table, and
Spec 009's per-frame retained-event ring folded at READ time. **There is no
accumulator, no occurrence index, no history store and no second knob** — the
one retention mechanism is Spec 009's, under `:rf.trace/events-retained`.

### A boundary's identity is its READ SET

The runtime mints no boundary identity. A registration is
`#js {reads, notify, cells}` — the object the heap ladder prices — and it
carries no view name, no source coordinate and no id; `codec/mark-boundary!`
is *no registry, no map* by design. Two boundaries reading the same set are
not merely similar to this runtime, they are indistinguishable: they share one
read-set entry, one `subscribe` closure and one `getSnapshot`.

So a boundary is keyed by its edge set and `:instances` counts how many hold
it. That is the exact granularity the runtime retains, and it is what makes
the Mounted and Reads rosters join without a correlation step. `:view` and
`:source` are `:unknown` under an `:opaque` naming projection, because naming
every live boundary would need a registry or a field on the priced
registration — a standing memory cost this producer will not levy for a
panel's benefit.

### Causality is never inferred from adjacency

The Why view has a proven half and an uncorrelated half, and they are separate
fields on the row and separate lines on the screen.

- **Proven.** A cell's `epoch` is re-stamped by every commit that moved its
  value, so `:latest-reads` — the boundary's reads at its highest epoch — are
  the ones that moved most recently. `:snapshot` is the exact number React
  compares in `checkIfSnapshotChanged`.
- **Uncorrelated.** Hicasso's commit seam records no cascade id, so nothing in
  the retained window can be JOINED to a boundary's re-run. `:cause` is
  `:unknown` every time — structurally, not circumstantially: a bigger ring
  does not fix it. `:candidates` are the retained runs that recomputed a
  subscription the boundary reads, offered as LEADS.
- **Host-opaque.** Whether the boundary then ran, retried, was abandoned, was
  bailed out by its memo comparator, or committed and painted is React's to
  know. React DevTools and the browser performance tools are the authority.

An empty window flips the row's loss from `:uncorrelated` to `:cap` and its
`:candidates` from `[]` to `:unknown`, because no search happened — a
distinction the tab renders and the suite drives.

### No read carries application data

Queries pass `re-frame.elision/elide-wire-value` with the read's own frame and
query — the same off-box egress projector the Pair MCP direct reads and Xray
already use — and it FAILS CLOSED: a frameless or destroyed-frame read
redacts the whole query rather than walking it under no policy.

No read VALUE is carried anywhere. The intent stream carries an event ID and
an argument COUNT, never the vector: the classification model is fail-open, so
an event whose registration declared nothing ships its arguments raw through
the egress chokepoint, and a door that promised *no application data* while
routing an undeclared payload through would be making the promise falsely.
A developer who needs the arguments has the Trace surface, which is where
Spec 015 governs that egress. `tool_reads_cljs_test`'s seeded-value witness
proves the hazard is real (a cell's live reaction derefs to the seeded secret)
before asserting the secret reaches none of the four envelopes.

---

## Rendering contract

### Three empties, three sentences

A tab showing no rows can mean three unrelated things with three unrelated
remedies. Collapsing them would undo the schema one level up, so each renders
under its own testid with its own prose.

| Presence | testid | Means |
|---|---|---|
| `:absent` | `rf-xray-hicasso-absent` | the door answered `nil` — not running Hicasso, or a production build |
| `:mismatch` | `rf-xray-hicasso-mismatch` | Hicasso answered, stamping a schema/producer this build was not taught |
| `:idle` | `rf-xray-hicasso-idle` | Hicasso is running and nothing is mounted — the one empty that is a clean bill of health |

### Every absence is a chip, and the five chips differ

`hicasso-helpers/loss-chip` turns a loss (or an `:unknown` value) into
`{:kind :testid-suffix :short :says}`, and the panel renders it as
`…-loss-<kind>`. No two kinds share a word, a sentence, or a testid suffix —
asserted as a property, so a browser selector for `…-loss-cap` can never match
an `…-loss-uncorrelated` row.

Every view also renders a summary line stating the envelope's own claim, on
every render including the complete ones: a reader who only ever sees a
completeness claim when it is bad learns to read its absence as good news.

### Row cap

The 200-row panel budget applies (`common-helpers/cap-rows` +
`overflow-indicator/overflow-row`), per `007-UX-IA.md` §Performance budget.

---

## Read-only, dev-only, bundle-isolated

Every read is a pure projection: nothing is pinned, nothing is dispatched,
nothing is acquired. The Hicasso tier has no registry and no ownership plane,
so a consumer cannot claim it, cannot be locked out of it by another tool, and
cannot read a superseded span's data through it.

Every read is `nil` in a production build (the door nil-gates on
`re-frame.interop/debug-enabled?`), and Xray itself never reaches a production
bundle. `re-frame.hicasso` does not require the tool namespace, so a
production application never loads it at all; the sentinel-based erasure proof
is rf2-hic-024's.

The dependency points one way only: `tools/xray` → `implementation/hicasso`.
Nothing under `implementation/` may `:require` anything under `tools/`.

**Release note.** `implementation/hicasso/deps.edn` carries no `:clein/build`
yet (rf2-hic-008 owns release wiring), so `day8/re-frame2-hicasso` is the
second in-repo coordinate `.github/scripts/preflight-xray-package.sh` refuses
to find in a published pom, beside `day8/re-frame2-freehand`. That refusal is
correct and is not a mechanical fix.

---

## Registration and drift gates

`hicasso/install!` registers `:rf.xray.hicasso/set-view`,
`:rf.xray.hicasso/view`, `:rf.xray.hicasso/data` and the L4 tab entry. The tab
is **L4-only** — no standalone `mount-*!` facade, so it is deliberately absent
from `panel-enum`, following the Graph and Modules precedent.

Adding it moved six governance pins, each of which fails the build on drift:

| Gate | What it pins |
|---|---|
| `focus/valid-panels` + `focus_cljs_test` | the focusable-panel mirror equals the live Dynamic registry |
| `registry/schema-version` (4 → 5) + `migrate-schema!` | an already-registered process installs the delta without a reload |
| `registry_cljs_test` name snapshots | the `:rf.xray.hicasso/*` sub and event ids |
| `panel_gallery_inventory_smoke_cljs_test` | the tab is galleried OR documented-excluded, never neither |
| `resources_cljs_test` / `routing_cljs_test` palette counts | the command palette's Dynamic tab count |
| `feature_matrix/scenarios.cjs` `PANEL_HANDOFFS` | the browser sweep reaches a real panel root, never the unknown-tab stub |

## Test coverage

| Suite | Tier | Proves |
|---|---|---|
| `re-frame.hicasso.evidence-schema-cljs-test` | node | every shape in which a projection would claim more than it knows is refused, each with a positive control |
| `re-frame.hicasso.tool-reads-cljs-test` | node (reactive substrate) | the four reads over real committed boundaries; the seeded-value privacy witness; determinism; the production-nil arm |
| `…panels.hicasso-helpers-cljs-test` | node + JVM | the five absences and the three empties are pairwise distinct; the schema pin; row projections |
| `…panels.hicasso-cljs-test` | node (reactive substrate) | the four views answer on a running app; the loss states render under distinct testids, driven between two real window states; the seam reshapes nothing |
| `feature_matrix/scenarios.cjs` | browser | the tab reaches a real panel root in the shell sweep |
