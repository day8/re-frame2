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
says a developer actually asks of such a substrate, one sub-view each — and
then adds two more views that DERIVE from the same take rather than reading
anything new. **Four envelopes, six views**: Advisor and Causal are
derivations over the four reads already in hand (rf2-hic-037), which is why
they are sub-views of this tab and not a tab of their own — a second tab
would take a second turn, and a mount landing between the two would give a
ranking about a census the slice no longer agrees with.

| Sub-view | Question | Envelope read |
|---|---|---|
| **Mounted** | Which boundaries are mounted, over which frames? | `:mounted-boundaries` |
| **Reads** | Which boundaries read each subscription, at what fan-out? | `:read-attribution` |
| **Intents** | What was dispatched, in order, in the retained window? | `:intents` |
| **Why** | Which reads changed, and what does that prove? | `:explain-render` |
| **Advisor** | Which boundary is hot, what owns the pressure, and what is the smallest route that addresses it? | derived — all four, no read of its own ([`028-Hicasso-Advisor.md`](028-Hicasso-Advisor.md)) |
| **Causal** | One dispatch, walked link by link from event to paint — with every missing link named. | derived — all four, no read of its own ([`028-Hicasso-Advisor.md`](028-Hicasso-Advisor.md)) |

The live view list is `hicasso-helpers/sub-modes`, and the per-view copy and
testid suites count against THAT rather than a literal, so a seventh view
cannot ship carrying a sixth view's sentence.

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

Every envelope carries five fields. Three identify it; two state how far to
trust it.

```clojure
{:schema     :re-frame.hicasso.evidence/v3   ; validated FIRST
 :producer   :re-frame/hicasso               ; the schema is adapter-neutral
 :read       :mounted-boundaries             ; which question this answers
 :complete?  true                            ; a claim about under-reporting
 :loss       nil}                            ; or {:reason … :dropped …}
```

`:loss` is the honest half: `nil` says nothing was dropped; a loss map names a
reason from the closed vocabulary and a `:dropped` that is a count or the
explicit `:unknown`. The producer's one door, `evidence/envelope`, refuses a
loss beside a completeness claim, a foreign reason, and an absent `:dropped`.

### The five states, and the one rule that matters

| State | Where it lives | Means |
|---|---|---|
| `:unknown` | a field's VALUE, and a loss's `:dropped` | the fact is not held; not empty, not zero |
| `:opaque` | a loss reason | the substrate keeps no such fact, deliberately and permanently |
| `:host-opaque` | a loss reason | React owns it and does not publish it |
| `:cap` | a loss reason | a retention window bounded what could be carried |
| `:uncorrelated` | a loss reason | the fact is real but joins to nothing |

**Unknown is never encoded as an empty collection.** A roster the producer
did not survey states `:unknown` where the vector would be — `:candidates`
under an empty window, `:views` on a body minted without a name — and the
Advisor and Causal derivations state `:opaque` and `:host-opaque` losses of
their own for what this tab does not measure. That is the reason this tab can
render an absence without a reader having to guess whether it found nothing
or looked at nothing.

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

### v2 → v3: the version tells the truth or it is worse than nothing

The wire shape has moved under a stale stamp before — the #7789 repair left
`v1` on a `v2` shape, and the merged-PR audit of #7802 called that the one
defect a version exists to prevent. v3 (rf2-6c12m.21) moved the shape again,
and producer and consumer stamp it in lockstep.

| Field | v2 | v3 |
|---|---|---|
| the envelope's claim axes | `:scope`, `:basis`, `:complete?`, `:loss` | `:complete?`, `:loss` |
| the `:naming`, `:host` and `:origin` sub-projections | present, every field `:unknown` | gone — what React owns is stated by the panel, not shipped as fields |
| a mounted row, reader or explanation | `:view :unknown :source :unknown` | `:views [{:view "<ns>/<sym>" :source {:ns :file :line :column}} …]`, or `:unknown` |
| an explanation's `:cause` | `:unknown`, constant | gone — the row's `:loss` says the join is missing |
| the intents envelope's frames | `:scope {:frames …}` | `:frames` |

Each row is a silent misread waiting to happen: a v2 parser reads an absent
`:scope` as a missing axis and an absent `:view` as a boundary the producer
did not name. That is why a version that lies is worse than no version — it
converts a loud failure into a quiet wrong answer.

**There is no acceptance path for a superseded version and no compatibility
adapter.** This is pre-alpha; a shim would restore exactly the mis-parse the
pin refuses. A v2-stamped envelope is a mismatch at the data layer
(`hicasso_helpers_cljs_test/the-superseded-v2-shape-is-refused-rather-than-mis-parsed`,
with a non-vacuity row proving the same envelope parses under the current
stamp) and on the page
(`hicasso_cljs_test/an-unparseable-schema-is-MISMATCH-and-suppresses-rows`,
which drives both the superseded `v2` and an unknown future `v99`, because the
pin is exact rather than a floor).

---

## What the producer projects, and what it refuses to invent

Everything is projected from state the runtime ALREADY retains: the read-set
entry cache, the cell table and its reader lists, the frame-ops table, and
Spec 009's per-frame retained-event ring folded at READ time. **There is no
accumulator, no occurrence index, no history store and no second knob** — the
one retention mechanism is Spec 009's, under `:rf.trace/events-retained`.

### A boundary's identity is its READ SET, and its name rides beside it

The runtime mints no boundary identity. A registration is
`#js {reads, notify, cells}` — the object the heap ladder prices — and it
carries no id; `codec/mark-boundary!` is *no registry, no map* by design. Two
boundaries reading the same set are not merely similar to this runtime, they
are indistinguishable: they share one read-set entry, one `subscribe` closure
and one `getSnapshot`.

So a boundary is keyed by its edge set and `:instances` counts how many hold
it. That is the exact granularity the runtime retains, and it is what makes
the Mounted and Reads rosters join without a correlation step.

**In a dev build the entry also names the views that HOLD it.** `defview`
stamps `"<ns>/<sym>"` on the body as `displayName` and hands its source
coordinate to `impl.error`'s dev-only ledger. The name rides on the reference,
not on the render: in a dev build the shell hands React a per-(entry, view)
`subscribe` that counts the name where React commits the reference and
uncounts it where React's cleanup releases it, kept on the read-set entry
under the `hicassoViews` own property inside `goog.DEBUG`. So a row's
`:views` is exactly the roster `refs > 0` claims — a view that unmounts
leaves the row its twin still holds, and a render React discards (a suspended
attempt, an aborted transition, StrictMode's first invoke) names nothing,
because no `subscribe` ever followed it (the merged-PR audit of #8758). The
producer resolves each name back to its coordinate, so every mounted row,
every attribution reader and every explanation carries `:views` — one
`{:view :source}` per declared view holding the edge set, sorted by name — or
`:unknown` for a body minted without a name (a harness fn; a name minted
outside the macro carries `:source :unknown`). Two declared views over one
edge set are still one row, naming both. React re-subscribes on no render it
did not already: the named `subscribe` is cached per (entry, view), so its
identity moves exactly when the entry's does. The stamp costs a production
build nothing: the slot is written only under `goog.DEBUG`, and
`check_production_erasure.cjs` scans the release bundle for the slot name
(rf2-6c12m.21).

The tab spends the name where a reader looks: a Mounted, Reads, Why or
Advisor row leads with the view (testid `…-views`, hover text the
`file:line`), keeps the edge set beside it as the identity the runtime keys
on, and renders the `unknown` chip in the view position for an unnamed body.

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

**This is why the Views panel's Mounted Views section did not move here.**
The two sections read Freehand's five-read tool
door for a roster keyed by view id and occurrence, plus a compiler manifest.
Neither had a counterpart on this door, and neither was a gap awaiting closure —
the read set is the only identity this runtime retains, and no evidence
subsystem ships. Those sections therefore RETIRED rather than being re-pointed
at `re-frame.hicasso.tool` (rf2-l86mm); the question-by-question mapping and
the disposition are recorded once, in
[`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §3.4.1. This
tab is now the only rendering of any view substrate's live evidence in Xray.

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
  the retained window can be JOINED to a boundary's re-run — structurally,
  not circumstantially: a bigger ring does not fix it, and the row's own
  `:loss` says so. `:candidates` are the retained runs that recomputed a
  subscription the boundary reads, offered as LEADS.
- **Host-opaque.** Whether the boundary then ran, retried, was abandoned, was
  bailed out by its memo comparator, or committed and painted is React's to
  know. React DevTools and the browser performance tools are the authority;
  the producer ships no field for it and the panel states it beside the rows.

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

**The empty roster is six facts, not one — one per view.** The original single
sentence — *nothing is mounted, the one empty that is a clean bill of health* —
was written for the mounted census and then shown under all four views the tab
had at the time, where it told a reader that a capped intent window proved
nothing had been dispatched (audit #7789). A confident wrong answer is worse
than a visible gap, so each view answers for its own scope:

| View | testid | What an empty roster means there |
|---|---|---|
| Mounted | `rf-xray-hicasso-empty-mounted` | no boundary holds a live read edge — a survey result, about SUBSCRIPTION rather than the screen |
| Reads | `rf-xray-hicasso-empty-attribution` | no cell is held; compatible with mounted boundaries that read nothing |
| Intents | `rf-xray-hicasso-empty-intents` | the retained window is empty — a CAP, which cannot say whether anything was dispatched |
| Why | `rf-xray-hicasso-empty-explain` | there is no mounted boundary to explain; it follows the census and inherits its qualifications |
| Advisor | `rf-xray-hicasso-empty-advisor` | there is no boundary to rank; it follows the census, and is not a verdict that nothing is hot — it says nothing about lowering, React or layout, which this tab never measures |
| Causal | `rf-xray-hicasso-empty-causal` | there is no slice to draw: a slice needs a mounted boundary AND a retained dispatch, and this is the first of the two missing |

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

The producer ships no field for either — they are facts about what the census
cannot see, not values it holds — and the Mounted view states the distinction
beside the rows under `rf-xray-hicasso-mounted-visibility`, rows or none. No
observable is invented for it: the governing promise is amended instead, which
is the honest half of the choice the audit offered. Hidden-retained is never
inferred from an empty census, and a subscribed row is never labelled visible.
The Intents view likewise states the window's cap beneath its rows under
`rf-xray-hicasso-intents-cap`.

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

Every read is `nil` in a production build: the door nil-gates on
`re-frame.interop/debug-enabled?`. `re-frame.hicasso` does not require the
tool namespace, so a production application never loads *it* at all; the
sentinel-based erasure proof for the door is rf2-hic-024's. Xray itself
stays out of a release build by build placement — the host doesn't load it
(see [`Principles.md`](./Principles.md) §Production posture is build
placement) — and no gate in this repo proves that.

The dependency points one way only: `tools/xray` → `implementation/hicasso`.
Nothing under `implementation/` may `:require` anything under `tools/`.

**Release note — CLOSED.** This edge was once Xray's unpublishable coordinate:
`implementation/hicasso/deps.edn` carried no `:clein/build`, so
`.github/scripts/preflight-xray-package.sh` refused to find
`day8/re-frame2-hicasso` in a published pom. rf2-gra70 answered it by
publishing the artefact — `deps.edn` now carries the `:clein/build` alias — and
the refusal is gone. What replaces it is an ordering obligation rather than
nothing; see [`docs/release-process.md`](../../../docs/release-process.md).

---

## Registration and drift gates

`hicasso/install!` registers `:rf.xray.hicasso/set-view`,
`:rf.xray.hicasso/view`, `:rf.xray.hicasso/data` and the L4 tab entry. The tab
is **L4-only** — no standalone `mount-*!` facade, so it is deliberately absent
from `panel-enum`, following the Graph and Frames precedent.

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
| `re-frame.hicasso.evidence-schema-cljs-test` | node | the envelope door stamps a coherent read and refuses a foreign read, a foreign or unsized loss and a loss beside a completeness claim, each refusal asserting the problem it named, with a positive control |
| `re-frame.hicasso.tool-reads-cljs-test` | node (reactive substrate) | the four reads over real committed boundaries; a declared view is named on the mounted row, the attribution reader and the explanation with the coordinate `defview` captured, two declared views over one edge set are one row naming both, an unnamed body states `:unknown`, and a name minted outside the macro carries no source; the seeded-value privacy witness for a return value AND for a query argument; two frames sharing a sub id with asymmetric windows; the dispatch-ordered, fragment-merged intent stream; determinism; the production-nil arm |
| `re-frame.hicasso.erasure-sentinels-cljs-test` | node | the live half of the erasure proof — a dev render mints the `hicassoViews` slot the release scan requires absent, and only the commit names a view in it |
| `…panels.hicasso-helpers-cljs-test` | node + JVM | the five absences and the empties are pairwise distinct — including the per-view empties, counted against the LIVE `sub-modes` list (six today) rather than a literal, so a seventh view cannot be added carrying a sixth view's sentence; labels and testids are built from the projected key; a named row leads with its view and an unnamed one carries the `unknown` chip; a row key carries the WHOLE projected identity, so two frames' boundaries over one query do not collide; two query variants do not collapse; the key is INJECTIVE as a property over a generated space of 9261 identities, 10162 boundary keys and 441 intent rows, with a non-vacuity control that the space still defeats a lossy slug; the superseded v2 stamp is refused rather than mis-parsed; the schema pin; row projections |
| `…panels.hicasso-cljs-test` | node (reactive substrate) | the four EVIDENCE views answer on a running app (Advisor and Causal are the derived pair, and have their own suites per [`028-Hicasso-Advisor.md`](028-Hicasso-Advisor.md)); a declared view is named on the Mounted, Reads and Why pages with a `…-views` testid, and a harness body renders the `unknown` chip instead; `:rf.xray.hicasso/data` INVALIDATES and RECOMPUTES on a real `:rf.xray/trace-buffer` tick with no cache clear, against a held reaction proved stale first — the SUBSCRIPTION's half of liveness, the panel's half being the browser row below; the loss states render under distinct testids, driven between two real window states; a sensitive query argument reaches neither the page nor a testid; the Reads and Why rows carry the frame on the page and in the testid; each view renders its own empty; both the superseded and an unknown stamp render the mismatch; the seam reshapes nothing |
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

**The tab is one of four on the same footing.** Resources, Graph, Frames and
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
against the DOM row above. If that deck is ever wanted, its moment has arrived:
`testbeds/freehand-views` existed solely to give the Views panel a populated
roster, those panel sections retired
([`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §3.4.1,
rf2-l86mm), and the deck's build id, port and scenario slot freed up with the
Freehand tree (rf2-0yp7w). This tab is the survivor of that disposition, not a
casualty of it.
