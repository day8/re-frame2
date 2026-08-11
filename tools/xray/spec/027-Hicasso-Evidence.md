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
id lands on a view that exists. The strip dispatches through the
`reg-view`-injected frame-bound `dispatch`, threaded down from the `Panel`
body: the click fires after render unwinds, when the ambient frame is gone,
so a bare global `rf/dispatch` would leak to `:rf/default` and switch some
other shell's sub-view (rf2-1w07r; `frame_singleton_guard_test` holds it).
All four envelopes are taken in ONE turn by
`:rf.xray.hicasso/data` — the rosters are projections of a single runtime
state, and reading them across two turns would let a mount land between the
census and the edges.

---

## The evidence contract

Every envelope carries seven axes. Three identify it; four state how far to
trust it.

```clojure
{:schema     :re-frame.hicasso.evidence/v2   ; validated FIRST
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

### v1 → v2: the version tells the truth or it is worse than nothing

The #7789 audit repair changed the wire shape and left the stamp at `v1`, so
for one increment the pin accepted — as exact — a shape it had never been
taught. The merged-PR audit of #7802 called that the one defect a version
exists to prevent, and producer and consumer now stamp `v2` in lockstep.

| Field | v1 | v2 |
|---|---|---|
| a boundary key element | `[frame-id query]` | `[frame-id sub-id projected-query]` |
| an intent row's frame | `:frame-id`, singular | `:frames`, a vector |
| `:latest-reads` | bare sub-ids | `{:sub-id :query :frame-id}` maps |
| the `:host` projection | commit / paint / attempt-outcome | `:visibility` and `:hidden-retained` besides |

Each row is a silent misread waiting to happen: a v1 parser takes the sub-id
in a key element for the query, iterates a map where it expected a keyword,
and reads an absent `:frame-id` as a frameless intent. That is why a version
that lies is worse than no version — it converts a loud failure into a quiet
wrong answer.

**There is no v1 acceptance path and no compatibility adapter.** This is
pre-alpha; a shim would restore exactly the mis-parse the pin refuses. A
v1-stamped envelope is a mismatch at the data layer
(`hicasso_helpers_cljs_test/the-superseded-v1-shape-is-refused-rather-than-mis-parsed`,
with a non-vacuity row proving the same envelope parses under the current
stamp) and on the page
(`hicasso_cljs_test/an-unparseable-schema-is-MISMATCH-and-suppresses-rows`,
which drives both the superseded `v1` and an unknown future `v99`, because the
pin is exact rather than a floor).

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

**The EXPORTED key is projected, element by element.** Each element is
`[frame-id sub-id projected-query]` — never a raw sub-key. A key built from
raw sub-keys carries every query ARGUMENT past the projector that had just
been applied to the `:query` field, and carries it further than any field
does: a key is what joins two rosters, what a panel prints in a boundary
label, and what a DOM testid is derived from. It shipped that way in the
first increment and the merged-PR audit of #7789 caught it.

Two properties survive the change and both are load-bearing. The **join** is
untouched, because both rosters derive keys through the one function and
identical read sets still project to identical keys. The **registration id**
rides beside the query because it is the one spelling redaction cannot take:
where a frame's policy elides a query whole, `[:cart/item …]` and
`[:user/token …]` project to the same sentinel and only the sub-id keeps them
apart.

The identity is therefore as fine as the egress policy allows and no finer.
Where an application has declared the arguments that told two boundaries
apart sensitive, those boundaries are ONE row here and `:read-orders` counts
what folded in. That is the correct place for the collapse: the alternatives
are a census keyed on data the door has promised not to carry, or two rows
sharing one exported identity — which gives a panel duplicate DOM ids and a
consumer an ambiguous join.

**This is why the Views panel's Mounted Views section does not move here.**
`021-Dynamic-Panel-Designs.md` §3.4.1/§3.4.2 read Freehand's five-read tool
door for a roster keyed by view id and occurrence, plus a compiler manifest.
Neither has a counterpart on this door, and neither is a gap awaiting closure —
the read set is the only identity this runtime retains, and no evidence
subsystem ships. Those sections therefore retire with the Freehand tree rather
than being re-pointed at `re-frame.hicasso.tool`; the question-by-question
mapping and the disposition are recorded once, in
[`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §3.4.3.

### Causality is never inferred from adjacency

The Why view has a proven half and an uncorrelated half, and they are separate
fields on the row and separate lines on the screen.

- **Proven.** A cell's `epoch` is re-stamped by every commit that moved its
  value, so `:latest-reads` — the boundary's reads at its highest epoch — are
  the ones that moved most recently. `:snapshot` is the exact number React
  compares in `checkIfSnapshotChanged`. Each entry names the READ
  (`{:sub-id :query :frame-id}`, the query projected), not a bare sub-id:
  `[:row 1]` and `[:row 2]` are one registration and two different reads, and
  a Why view that answered ":row moved" to a developer looking at eight rows
  would be collapsing an identity the door already held (audit #7789).
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

**Both halves are scoped to the boundary's own frames.** The window a row
searches is the rings of the frames that row's reads actually name, and a
candidate must match a read on `[frame-id sub-id]`. Counting retained runs
globally lets activity in frame B report that frame A's window was searched —
converting A's honest `:cap` into a false `:uncorrelated` — and matching on
the sub-id alone then offers B's runs as A's leads for no better reason than
that two frames registered the same sub id, which is a lead fabricated from a
coincidence. Two mounted apps is the ordinary case, not the exotic one.
`tool_reads_cljs_test/explain-render-scopes-its-window-and-its-leads-to-the-boundarys-own-frames`
drives two frames sharing a sub id with asymmetric windows.

### The intent stream is ordered by dispatch, not by frame

Spec 009's rings are per frame; the stream is one. Rows are ordered by the
process-monotonic `:dispatch-id` the router allocates at queue time, which IS
the dispatch order across every frame — so the read can promise order and mean
it. Concatenating whole per-frame rings in frame-id order asserts a sequence
that never happened, alphabetically, the moment a second frame is live.

A dispatch that touched two frames is captured in both rings, so fragments
sharing a `:dispatch-id` are merged into the one row they describe: a row
names `:frames` (plural) and the union of what it recomputed. Two rows would
print one user action as two events; keeping the first would drop half of what
it did.

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

That witness covers a secret RETURN VALUE. A second pair covers a secret
QUERY ARGUMENT, which is a different path and was the one that leaked:
`a-sensitive-query-argument-never-reaches-a-key-a-reader-or-an-explanation`
spans the mounted, attribution and Why envelopes, and
`hicasso_cljs_test/a-sensitive-query-argument-reaches-neither-the-page-nor-a-testid`
spans the rendered tree — text AND `data-testid`, because the helpers printed
the raw query in a label and hashed it into a DOM id, so an envelope-only
control cannot see the escape. Both use frame destruction as the forcing
function (there the door promises to fail closed) and both carry a
non-vacuity row proving the argument really was reachable first.

---

## Rendering contract

### Every empty is its own sentence

A tab showing no rows can mean unrelated things with unrelated remedies.
Collapsing them would undo the schema one level up, so each renders under its
own testid with its own prose.

| Presence | testid | Means |
|---|---|---|
| `:absent` | `rf-xray-hicasso-absent` | the door answered `nil` — not running Hicasso, or a production build |
| `:mismatch` | `rf-xray-hicasso-mismatch` | Hicasso answered, stamping a schema/producer this build was not taught |
| `:idle` | per view, below | Hicasso answered with an EMPTY roster — which is a different fact in each view |

**The empty roster is four facts, not one.** The original single sentence —
*nothing is mounted, the one empty that is a clean bill of health* — was
written for the mounted census and then shown under all four views, where it
told a reader that a capped intent window proved nothing had been dispatched
(audit #7789). A confident wrong answer is worse than a visible gap, so each
view answers for its own scope:

| View | testid | What an empty roster means there |
|---|---|---|
| Mounted | `rf-xray-hicasso-empty-mounted` | no boundary holds a live read edge — a survey result, about SUBSCRIPTION rather than the screen |
| Reads | `rf-xray-hicasso-empty-attribution` | no cell is held; compatible with mounted boundaries that read nothing |
| Intents | `rf-xray-hicasso-empty-intents` | the retained window is empty — a CAP, which cannot say whether anything was dispatched |
| Why | `rf-xray-hicasso-empty-explain` | there is no mounted boundary to explain; it follows the census and inherits its qualifications |

Each view's loss and remedy render whether or not there are rows. A view that
showed its qualifications only when it had something to qualify would drop
them exactly where the reader has least else to go on.

### Mounted means subscribed, and the tab says so

The real-React lifecycle witness (audit #7792) established two facts this
census cannot see, and the tab states both rather than letting a reader
supply them:

- an **Activity-hidden** subtree that has released its reads leaves the same
  census as an **unmounted** one, and only a later 0-to-1 re-subscribe
  distinguishes them, retrospectively;
- a **Suspense-fallback-hidden** subtree stays SUBSCRIBED, so it is listed
  here while absent from the screen.

The producer names `:visibility` and `:hidden-retained` on the `:host`
projection as `:unknown` on a `:host-opaque` basis, and the Mounted view
renders the distinction beside the rows under
`rf-xray-hicasso-mounted-visibility`. No observable is invented for it: the
governing promise is amended instead, which is the honest half of the choice
the audit offered. Hidden-retained is never inferred from an empty census, and
a subscribed row is never labelled visible.

### A row's key and testid carry the WHOLE projected identity

The producer exports each key element as `[frame-id sub-id projected-query]`
and names the frame on every edge and explanation row. The panel must spend
all three, and for one increment it did not: `boundary-slug` ignored the
frame, `attribution-rows` slugged every edge by sub-id alone, and the Reads
view printed neither the projected query nor the frame. Rows that are
genuinely different facts therefore shared a React key and a DOM testid, and
read identically on screen (audit #7802):

```clojure
;; on merge 7c45f3ca9
(map boundary-slug [{:key [[:frame/a :row [:row 1]]]}
                    {:key [[:frame/b :row [:row 1]]]}])   ;=> ["row-row-1" "row-row-1"]
(map :slug (attribution-rows …))                          ;=> ["row" "row"]
;;   for [:row 1] in frame A and [:row 2] in frame B
```

**Frames are isolated contexts**, so the first pair is two applications'
boundaries, not one boundary counted twice — collapsing them is the same
class of error as collapsing two empties. `hicasso-helpers/read-key-str` is
now the one place a projected read identity becomes a string, and
`boundary-slug`, `read-slug` and the Reads and Why rows all go through it.

| Surface | Carries |
|---|---|
| a boundary testid / React key | every key element's frame, sub-id and projected query |
| a Reads row testid / React key | the edge's frame, sub-id and projected query |
| the Reads row on screen | the projected query as its label, and the frame beside the fan-out |
| the Why row on screen | the boundary label, and the frame beside it |

**Projected fields only.** The query arrives already projected and is printed
as found; recovering the raw query to make a slug more readable would undo
the producer's redaction at the last step, which is the escape the #7789
audit caught. A frame whose value is `:unknown` renders the `unknown` chip
rather than the word, exactly as the Mounted view does.

The identity remains as fine as the egress policy allows and no finer. Where
a policy elides two queries whole, two edges sharing a frame and a
registration id project to one identity — the producer's documented ceiling,
which the mounted census resolves by grouping and the Reads roster inherits
because it prints the cell table as it stands.

### The key is INJECTIVE, and the label is where readability lives

Carrying all three parts was necessary and not sufficient. For one further
increment `read-key-str` joined the parts' `pr-str` forms with `-`, and
`read-slug` and `boundary-slug` spent the result through `id-slug`, which
replaces every run of non-alphanumerics with `-` and folds case. Namespace
separators, in-name hyphens, component boundaries and collection punctuation
therefore all collapsed onto one character, and the encoding reintroduced the
very collision it had been written to close (audit #7820):

```clojure
;; on merge dbbcf05584
(map read-slug [[:a/b :c :d] [:a-b :c :d] [:a :b/c :d]])  ;=> ["a-b-c-d" "a-b-c-d" "a-b-c-d"]
(map #(boundary-slug {:key [%]}) …)                       ;=> ["a-b-c-d" "a-b-c-d" "a-b-c-d"]
```

Namespaced and hyphenated ids are ordinary programmer input, not an
adversarial edge.

A React key and a DOM testid do two jobs that are **not the same job**, so
the string has two halves and neither compromises for the other:

| Half | Its job | Built by |
|---|---|---|
| the stem | readable — a human reading a failing selector can tell which row it named | `id-slug`, lossy on purpose |
| the tail | unique — the reconciler must never treat two rows as one | `escaped`, reversible |

`escaped` rewrites the identity string into `[A-Za-z0-9%u]`: an ASCII
alphanumeric survives as itself, and every other character becomes `%` plus
its UTF-16 code unit in hex — two digits below 256, `%u` plus four digits
above it. `%` is itself escaped and `u` is not a hex digit, so the encoding
parses back exactly one way. It emits no `-`, so the LAST `-` in a slug is
always the join and the tail behind it decodes to exactly one input. That is
the injectivity argument, and it is an argument about the function rather
than an observation about three fixtures.

Neither half is the row's LABEL. The projected query and the frame are
printed on the row, which is where a reader actually looks — which is what
frees the key to be ugly.

`read-key-str` remains the one projected-identity door and spends one
`pr-str` over the whole triple rather than three joined by a separator: a
separator between printed components can be forged by a component that
contains it, while `pr-str` quotes what it prints. It also normalises the
triple, so an identity arriving as a seq does not mint a second key for one
fact, and it is the same canonical string the producer sorts its own rows by.
Its domain is stated rather than assumed — injective over the EDN a projected
identity is made of, being keywords, strings, numbers, booleans, nil and
sequential collections of those. It does not claim to separate what `pr-str`
cannot print apart: a deliberately reader-hostile symbol, or two `=`-equal
maps built in two insertion orders, which print in iteration order because
that is the producer's canonical form and not a second one invented here.

Intent rows are keyed by the same encoding, for the same reason: `id-slug`
alone gave `:a/b-c` and `:a-b/c` one testid.

**The guard is a property, not three examples.** The audited increment
asserted that its own three fixtures came out distinct — true, and equally
true of a constant function on three points, which is how the collision
shipped green. The suite now generates 21 legal component shapes across the
three projected positions (9261 identities), 10162 boundary keys and 441
intent rows, and asserts that distinct identities yield distinct strings over
the whole space. A non-vacuity assertion pins the pool to its job: the
three-way control must still collide under `id-slug` alone, or the space has
stopped testing anything and needs re-choosing.

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
| `re-frame.hicasso.tool-reads-cljs-test` | node (reactive substrate) | the four reads over real committed boundaries; the seeded-value privacy witness for a return value AND for a query argument; two frames sharing a sub id with asymmetric windows; the dispatch-ordered, fragment-merged intent stream; determinism; the production-nil arm |
| `…panels.hicasso-helpers-cljs-test` | node + JVM | the five absences and the empties are pairwise distinct — including the four per-view empties; labels and testids are built from the projected key; a row key carries the WHOLE projected identity, so two frames' boundaries over one query do not collide; two query variants do not collapse; the key is INJECTIVE as a property over a generated space of 9261 identities, 10162 boundary keys and 441 intent rows, with a non-vacuity control that the space still defeats a lossy slug; the superseded v1 stamp is refused rather than mis-parsed; the schema pin; row projections |
| `…panels.hicasso-cljs-test` | node (reactive substrate) | the four views answer on a running app; `:rf.xray.hicasso/data` INVALIDATES and RECOMPUTES on a real `:rf.xray/trace-buffer` tick with no cache clear, against a held reaction proved stale first — the SUBSCRIPTION's half of liveness, the panel's half being the browser row below; the loss states render under distinct testids, driven between two real window states; a sensitive query argument reaches neither the page nor a testid; the Reads and Why rows carry the frame on the page and in the testid; each view renders its own empty; both the superseded and an unknown stamp render the mismatch; the seam reshapes nothing |
| `…panels.hicasso-live-panel-dom-cljs-test` | browser (real React DOM) | the RUNNING panel is live: one `Panel` mounted into a real `reagent.dom.client` root, inside the shell's own `[frame-provider {:frame :rf/xray}]`, picks up a newly-mounted boundary on a trace tick and commits the row to the DOM. Nothing calls `Panel` a second time; a drained render queue proves the panel stale across the mount first; the `<section>` node afterwards is the one it started with, so the roster arrived by reconciliation and not by a remount |
| `frame_singleton_guard_test` | JVM (source text) | the sub-strip dispatches through the `reg-view`-injected frame-bound `dispatch`, never a bare global one |
| `feature_matrix/scenarios.cjs` | browser | the tab reaches a real panel root in the shell sweep |

### The populated arms in the browser: one liveness row, and no deck

The tab has no populated arm on a STAGED SURFACE. The shell sweep clicks
`:hicasso` on the counter surface, which is not a Hicasso application, so the
root it asserts is holding the `absent` note. That is the whole of the tab's
staged-surface browser coverage, and it is a decision rather than an unfilled
gap. It is not the whole of the tab's browser coverage, though: liveness has a
browser row of its own, three paragraphs down.

**The tab is one of four on the same footing.** Resources, Graph, Modules and
Hicasso are documented exclusions from the panel gallery, with their coverage
ruled to be the feature-matrix shipped-surface sweep plus their own per-panel
CLJS unit tests (`panel_gallery/core.cljs` §Intentional gallery exclusions).
`panel_gallery_inventory_smoke_cljs_test` fails the build if that partition
drifts. Of the four, Hicasso has much the strongest unit lane.

**Nothing about the populated RENDERING is out of that lane's reach.**
`hicasso_cljs_test` mounts real boundaries through the real commit seam under a
reactive substrate and stubs nothing between the runtime and the hiccup — its
two `with-redefs` synthesise the loss arms and touch no populated path. Narrow
the populated path and it reds: the roster's two-instance fold, both edge
labels, the fan-out counts, the frame on the row and in its testid, the
redaction of a sensitive query argument from both the text and the testids, and
the two loss chips driven between two genuinely different window states.

**LIVENESS is the one property that lane cannot carry, and it has a
real-React row.** That `Panel` mounts and routes under real React in the real
chrome is the shell-sweep row above; that the sub-strip dispatches through the
frame-bound `dispatch` is held by `frame_singleton_guard_test`, a source-text
guard over every panel — broader than a per-panel click and cheaper. Liveness is
two claims, and the Node lane can only reach the first.
`:rf.xray.hicasso/data` composes off `:rf.xray/trace-buffer`, an ordinary app-db
slot written by an ordinary dispatch, so
`hicasso_cljs_test/the-populated-roster-arrives-on-the-TRACE-TICK-and-not-on-a-cache-clear`
drives the tick in Node against a held reaction it proves stale first, and
proves the SUBSCRIPTION invalidates and recomputes. It does not prove the
RUNNING PANEL re-renders: it calls `Panel` a second time itself, with no React
root mounted, nothing committed and no DOM read — a claim about the sub, cited
for a claim about the tab (merged-PR audit of #7881). The panel's half is
`hicasso_live_panel_dom_cljs_test/the-mounted-panel-picks-up-a-new-boundary-on-the-trace-tick`:
one `Panel` mounted into a real `reagent.dom.client` root inside the shell's own
`[frame-provider {:frame :rf/xray}]`, a real boundary mounted, the render queue
DRAINED with the panel still showing its empty note — the control, because a
mount moves nothing in Xray's app-db and a tab wired to no tick would sit on an
empty roster forever — then one tick, after which the committed DOM carries the
boundary row and the same `<section>` node it started with.

**That row needed no deck, no build id and no port**, and the claim that a
browser proof would is a cost belonging to a different lane. It is an ordinary
`*_dom_cljs_test` namespace in the EXISTING `:browser-test` build, which already
carries `tools/xray/test` on `:source-paths`; `_browser-dom-lane-partition.test.cjs`
picks it up automatically. What DOES need a deck is a STAGED-SURFACE row: no
surface in `feature_matrix/scenarios.cjs` is a Hicasso host, so a populated arm
in the shell sweep needs a new deck, a new `implementation/shadow-cljs.edn`
build id and a new `:dev-http` port — the shape rf2-6pohj built for the Views
panel. That cost is why there is no staged Hicasso host, and it is not a reason
against the DOM row above. If that deck is ever wanted its moment is
rf2-hic-062, because `testbeds/freehand-views` exists solely to give the Views
panel a populated roster, that panel retires with the Freehand tree
(`021-Dynamic-Panel-Designs.md` §3.4.3), and its build id, port and scenario
slot free up together. This tab is the survivor of that disposition, not a
casualty of it.
