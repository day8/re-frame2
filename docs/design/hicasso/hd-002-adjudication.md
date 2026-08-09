# HD-002 — the collector adjudication

> **Delegated advisory, operator-overturnable.** The operator-owned standard bead
> `rf2-2rtt6.1` required HD-002's four clauses pinned before P1 code; it was
> superseded and closed on 2026-08-10, and HD-002 adjudication now runs through
> the kernel witnesses `rf2-hic-010`/`rf2-hic-011`, the design-laws lane, and the
> substrate adjudication `rf2-hic-018`. A delegated
> ruling recorded on that bead (mayor, 2026-07-30) makes this a written advisory
> rather than an operator gate, on the pattern HD-013 already uses for the donor
> gate: produced by a worker, adversarially reviewed, recorded on the standard
> bead. **The operator may overturn any part of it.** Normative source:
> [decisions.md](decisions.md) (HD-001…HD-025).

HD-002 is a measurement and the measurement has not run. This page does not argue
about whether the ambient collector should be the product mechanism. It makes the
question decidable: what kills the collector on the spot, what the collector is
allowed to do at commit time, how a candidate read survives a render React may
throw away, and which two swings the collector gets.

The four clauses in the order the standard bead lists them are (a) the ownership
state machine, (b) the allowed edge-diff operation versus the forbidden ledger
class, (c) two pre-registered strategy hypotheses, (d) the tripwire. They are
adjudicated here in the reverse order, because **the tripwire overrides the
clock** and a worker needs it before they write anything else.

## How to read this page

Four markers, and they are the most useful thing here. A worker building Arm 1
must be able to tell what the record decided from what this page concluded.

| Marker | Means |
|---|---|
| **[RULED]** | The normative record says this. Citation follows. |
| **[DERIVED]** | Follows by direct reading of quoted normative text. Overturn the reading and the conclusion goes. |
| **[INFERRED]** | My conclusion from React or re-frame2 semantics that the record does not state. Weakest class — check it before you rely on it. |
| **[OPEN]** | Cannot be adjudicated from the current record. What would settle it is named. |
| **[SETTLED]** | Was **[OPEN]**; a later bead settled it with a witness. The bead, the witness and the verdict are named — including when the verdict went against this page. |

---

## 1. The tripwire

### The one question

You are about to introduce a data structure. Ask:

> **Does anything I am about to write need to survive from one render attempt to a
> different render attempt of the same boundary — or need to be told apart from
> another render attempt's version of itself?**

If yes, that is the candidate ledger, and **the collector has lost.** Not "is
slow". Lost. Stop, record, hand the tier to grouped.

**[RULED]** The tripwire and its precedence are in the record twice.
[decisions.md](decisions.md) HD-002 clause (a): "if correctness requires the
forbidden candidate ledger, the tripwire fires immediately".
[architecture.md](architecture.md): "the standing tripwire **overrides the
clock**: the first need for a candidate ledger or generic post-render dependency
reconciliation kills the collector outright."

### The mechanical checklist

The one question is the test. These are the ways it comes up in practice, phrased
so you do not have to re-derive it at 11pm. **Any yes is a firing.**

1. **Am I allocating a second slot?** One candidate slot per boundary is allowed.
   The moment you need "pending" *and* "previous pending" — so that two in-flight
   renders can be distinguished — you are keeping candidates. Fired.
2. **Am I keying anything by a render id, an attempt id, a lane, or a
   generation?** A generation counter *compared* once per render is fine; a
   generation counter *used as a map key over read sets* is the ledger. Fired.
3. **Am I mutating anything shared from render phase?** The index, a global
   registry, a ref-count, an evidence sink. If a render that gets abandoned would
   leave that mutation behind, you now need a record of what to undo. Fired.
4. **Am I re-reading subscription values at commit?** That is generic post-render
   dependency reconciliation by name. Fired.
5. **Am I allocating an object per read to make the commit correct?** Not to make
   it fast — to make it *correct*. A per-read record carrying handle, version,
   order, and frame is the predecessor's `:observations` map by another name.
   Fired.
6. **Do I need to know which renders are currently in flight?** A root-level
   observer of live renders is the ledger's other half. Fired.

### Tripwire versus budget failure

This distinction is the whole point of "overrides the clock", and getting it
wrong in either direction wastes the arm.

- **A tripwire is about necessity.** The structure is needed for the collector to
  be *correct*. It is fatal on sight, however good the numbers look. You can be
  fast and still dead.
- **A budget failure is about cost.** The structure is not needed for correctness;
  it is there and it is expensive. That is measured against the survival metric
  and the heap ladder in [validation.md](validation.md), and it is survivable —
  you delete it and re-measure.

Per-read allocation is the case that sits on the join. If you allocate per read
because it was convenient, that is a budget problem: delete it. If you allocate
per read because the commit cannot be correct without it, the tripwire has fired.

### What happens when it fires

**[DERIVED]** from HD-002's own outcome grammar ("A collector loss promotes a
*known, already-scored* surface") and the standard bead's red-row rule ("silence
is not a pass"):

1. **Stop collector work on that arm.** Do not redesign around it quietly. The
   firing is the result.
2. **Record it** on the arm bead (`rf2-2rtt6.9`) and on the substrate
   adjudication `rf2-hic-018`, which carries the tripwire clause now that
   `rf2-2rtt6.1` is superseded and closed (2026-08-10): the construct you
   reached for, the correctness obligation that demanded it, and the commit SHA
   where the need became visible.
3. **The arm continues on grouped.** A fired tripwire kills the *collector tier*,
   not the lean-React arm and not the programme. Grouped is the product default
   and has been dogfooded since P1 start precisely so this is a promotion, not a
   rewrite (HD-002).
4. **The operator may waive it.** This page is overturnable and HD-002 reopens
   "via its own adjudication". But the default is loss, and silence is not a pass.

**[DERIVED]** A tripwire firing is not one of the two hypotheses being consumed
(§4). It ends the tier regardless of how many swings are left.

---

## 2. The boundary — the allowed edge-diff operation

### The operation

**[DERIVED]** from HD-002 clause (b), architecture.md's index description
("edge add/remove on mount/unmount/re-run; conditional reads as an edge-set
diff"; "Index edges live in global maps — shared structure, not per-boundary
object fan-out"), and the six index laws restated in architecture.md.

Exactly one operation is allowed, and it runs exactly once per committed render,
in the commit phase:

```
commit-edges! : boundary × committed-seq × candidate-seq → index mutations

  Let P = the boundary's committed read sequence (what is on screen).
  Let N = the candidate read sequence from the render being committed.

  For each sub-key in N and not in P  → add    edge (key → boundary)
  For each sub-key in P and not in N  → remove edge (key → boundary)
  Then P := N.
```

That is the whole operation. A symmetric difference over sub-keys, applied to the
shared index. Nothing else is permitted in the commit path.

### The three constraints that keep it out of the ledger class

**1. The commit does not deref.** Zero subscription values are read at commit
time. Values were read during the render; the commit consults only sub-keys.

**[RULED]** This is the sharpest divergence from the predecessor, and it is
measured. [The bulk-rerender profile](../freehand/studio/bulk-rerender-where-the-time-goes.md)
§4: the predecessor's `cell/commit!` called `obs/read` on every handle — "a
**second deref of every subscription**, after render already read it" — as the
invariant-5 tear check, costing 1.19 ms of a 4.0 ms write for 300 reads, against
Reagent's entire layout-effect phase of 1.4 microseconds. Hicasso replaces the
commit-side re-read with the **generation fence** (architecture.md: "A generation
fence keeps all reads within one render pass on one commit (invariant-5
preservation; the staged-stale CI witness guards it)") — one comparison per
boundary, O(1), instead of one deref per read, O(reads). That substitution is
what makes the allowed operation affordable, and it is why the predecessor could
not take the retained-set fast path it identified as its own largest lever: its
commit-side re-read *was* the tear check.

**2. Allocation is proportional to the change, not to the read count.** The
unchanged case allocates nothing. The changed case allocates O(d) where d is the
size of the symmetric difference — never O(k) for k reads.

This is the single sentence to check any implementation against, and it is the
survival metric seen from the other side. [validation.md](validation.md) prices
the tier-3 per-read budget as "steady-state allocation slope across warm 1/3/7/20
reads, zero retained per-occurrence objects after commit/teardown". If allocation
is proportional to change, and steady state has no change, the slope is flat at
zero. **The cost law and the survival metric are the same statement.** A
collector that satisfies one and fails the other has a bug in its instrument.

**3. The unchanged case must be detectable without building anything.** On the
make-or-break bulk row — 300 boundaries re-rendering on one commit — every
boundary re-reads the same keys in the same order. If detecting "nothing changed"
requires materialising a set, the collector pays 300 allocations to discover that
nothing happened. The two hypotheses in §4 are two ways to make that detection
free.

### Two details a worker gets wrong

**[INFERRED]** Neither is stated in the record; both follow from the operation.

- **The index edge is set-valued; the candidate buffer is a sequence.** A body may
  read the same key twice (a loop, a helper). Two reads of one key are one edge.
  The buffer holds them both, in order, because it holds one render's reads and
  nothing more; the diff collapses them.
- **An ordinal fast path is a false-negative device, never a wrong answer.** If
  the fast path compares read *order* and the order changed while the *set* did
  not, you fall into the diff, compute an empty symmetric difference, and mutate
  nothing. Slower, still correct. Do not "fix" this by making the fast path
  order-insensitive with a hash — see the forbidden list below.

### Nearest the line, allowed

These are legal and they sit close enough to the fence that a reviewer will
question them. The answers:

| Construct | Why it is allowed |
|---|---|
| **A per-boundary reused scratch buffer** for the current render's keys — one array, grown to the high-water read count, overwritten unconditionally at the top of every render | It holds exactly one render's reads, is never consulted to decide *between* renders, and is destroyed by overwrite rather than by bookkeeping. Its retained size is the high-water read count, which is exactly what the 1/3/7/20 heap ladder prices. **This is the closest allowed construct to the fence.** |
| **The committed read sequence P**, retained per boundary | It describes what is on screen. Exactly one of it, and you cannot compute a diff without it. Explicitly acknowledged: architecture.md prices "(collector tier) a committed read set" as boundary-exclusive retention. |
| **A per-boundary generation counter**, compared once per render | O(1), not keyed over read sets, and it is the mechanism that replaces the forbidden commit-side deref. |
| **A single candidate slot** holding the pending sequence between render and commit | One slot. Overwritten by the next render, read once by the commit. §3 is the proof that one is enough. |
| **A high-water capacity that does not shrink** | Retention, not candidacy — a budget question for the heap ladder, not a tripwire. |

### Nearest the line, forbidden

| Construct | Why it fires |
|---|---|
| **A second candidate slot** — "pending" plus "previous pending" | **The nearest forbidden case**, and one line away from the allowed scratch buffer. Needing two means you must tell two render attempts apart. That is the definition. |
| **Any map keyed by render / attempt / lane / generation id** holding read sets | The ledger, spelled literally. |
| **A per-read record** — a map or object per read carrying handle, version, order, frame — instead of a bare sub-key in a flat sequence | The predecessor's five-key `:observations` maps, 300 per commit. Measured as the largest single term in its deficit. |
| **Any commit-phase re-read of subscription values** | Named in architecture.md as "generic post-render dependency reconciliation". |
| **A registry of in-flight renders** at the root | architecture.md's inside-React constraints already rule out the class: "no root observer of leaf commits". |
| **A content hash / fingerprint of the read set used to decide "unchanged"** | Two distinct read sets can collide, and a collision is a silently missing edge — a stale boundary, not a slow one. A device whose failure mode is wrongness is not an optimisation. **[INFERRED]** — the record does not name hashing; the exclusion is mine and rests on the failure mode. |
| **Any render-phase mutation of the index or of subscription ref-counts** | §3. An abandoned render would leave it behind, and undoing it needs a record of what to undo — which is the ledger arriving through the back door. |

---

## 3. The ownership state machine

React can abandon a render. That is the whole difficulty, and React offers no
callback for it: there is no `onAbandon`. The only signals a component gets are
its next render (which re-runs the body), its commit-phase effect (which runs only
for renders that win), and its unmount cleanup.

### The invariant

Everything below reduces to one sentence, and it is worth memorising instead of
the state table:

> **No render-phase code mutates anything outside the boundary's own single
> candidate slot. The index is mutated only in the commit phase.**

**[DERIVED]** This is the positive form of the tripwire, and it is what
architecture.md's inside-React constraints already require: "registering watches
during render violates the abandoned-render rules."

If that invariant holds, an abandoned render needs no cleanup — because it never
did anything that needs cleaning. **The state machine does not detect abandoned
renders. It makes them costless.** That is the adjudication: the record asks how
candidate reads "vanish on abandon", and the answer is that they were never
anywhere they would have to vanish from.

### States and transitions

Per boundary:

| State | Meaning |
|---|---|
| `UNMOUNTED` | No index membership, no committed set. |
| `RENDERING` | The body is executing; the collector is bound to this boundary; the scratch buffer is being filled. |
| `PENDING` | The body returned; the candidate slot holds this render's read sequence; **the index is untouched**. |
| `COMMITTED` | The commit-phase effect ran; the index reflects the candidate; `P := N`; the scratch is released or reused. |

| Transition | Trigger | What happens |
|---|---|---|
| `UNMOUNTED → RENDERING` | First render | Reset the scratch **unconditionally**. |
| `RENDERING → PENDING` | Body returns | Snapshot the scratch into the single candidate slot. No global mutation. |
| `PENDING → COMMITTED` | Commit-phase effect | `commit-edges!` (§2). **The only global mutation in the machine.** |
| `PENDING → RENDERING` | React re-renders this fiber before committing | **This is the abandoned render.** The scratch is reset at the top; the previous candidate is destroyed by overwrite. Nothing to undo. |
| `RENDERING → RENDERING` | StrictMode double-invoke | Same as above. The unconditional reset is why this is correct — a reset guarded by "if empty" would concatenate two renders' reads. |
| `PENDING → ∅` | The tree is discarded; the fiber neither commits nor re-renders | The index still describes the last *committed* render, which is what is on screen. If the boundary never committed at all, it has no index membership. **Correct in both cases, with no cleanup.** |
| `RENDERING → ∅` | The body throws | The scratch is dirty and will be reset by the next render or never read. No global mutation happened. An error boundary either re-renders the fiber (reset) or unmounts it (below). |
| `COMMITTED → COMMITTED` | Commit effect re-runs (StrictMode mount/unmount/mount) | Cleanup removes every edge in P, then the effect re-adds them. Idempotent because the index is set-valued — but **prove it with a witness, do not assume it**. |
| `COMMITTED → UNMOUNTED` | Unmount | Cleanup removes every edge in P and clears it. This is the standing "zero leaked subscription ref-counts after teardown" assertion (validation.md). |

### The three cases the record names, answered

- **Candidate reads survive a winning render.** The candidate slot is read by the
  commit-phase effect, which runs only for the render that won. `P := N`.
- **They vanish on abandon.** They never left the slot, and the next render
  overwrites it. There is nothing to vanish.
- **They vanish on replay.** Identical to abandon: React re-running a component
  after a suspended sibling resolves is `PENDING → RENDERING`.
- **They vanish on teardown.** Unmount cleanup removes P's edges. Nothing else
  exists to remove.

### The consequence the state machine forces

**[INFERRED]** The invariant forbids render-phase ref-count mutation, so
`(sub q)` during a render must not acquire. Checking re-frame2's actual
subscription layer:

- **A warm read is a pure deref.** If the boundary already holds an edge for that
  key from a prior commit, the cache slot is alive on someone else's ref-count and
  the read is `@reaction` with no acquisition. This is the overwhelming majority
  case and it is what validation.md's "an unchanged hot read performs no new
  attach/release" already requires.
- **A cold read must compute without retaining.** `subscribe-once`
  (`implementation/core/src/re_frame/subs.cljc`) subscribes, derefs, and
  unsubscribes within the calling tick, retaining nothing. Both halves complete
  inside the render, so an abandoned render leaves the world exactly as it found
  it. **Abandonment-safe, and therefore inside the fence** — but it pays
  create-compute-dispose at render and create-compute again at commit when the
  edge is added for real.
- **Acquisition happens at commit**, as part of `add edge`. Acquire without
  deref: the value is already known from the render.

**[INFERRED]** This is *not* a collector-specific problem and must not be scored
against the collector. `useSyncExternalStore` has the same shape — React calls
`getSnapshot` during render and `subscribe` in an effect after commit — so the
grouped tier and the scalar comparator inherit the identical constraint. It is a
shared front-half concern. Instrument it once, charge it to nobody.

---

## 4. The two pre-registered hypotheses

Pre-registration is the point. Naming a strategy after seeing its numbers is how a
measurement becomes a story. These two are named now, before any collector code
exists, with their predictions and their falsification conditions.

Both live inside §2's allowed operation. They differ on **where the unchanged-case
detection happens**, which is the only strategic axis the operation leaves open.

### H1 — commit-side ordered compare

**Mechanism.** The render appends each sub-key to the reused scratch buffer in
read order. At commit, compare lengths, then compare pairwise under `=`. On a
match, return without allocating or mutating anything. On a mismatch, fall through
to the symmetric difference.

**The bet.** A boundary's read sequence is ordinally identical across renders in
the overwhelming majority of renders, so the steady-state commit is k comparisons
and zero allocations.

**Pre-registered prediction.** On the 300-boundary bulk witness, steady-state
commit-phase allocation attributable to edge maintenance is **zero bytes**, and
the allocation slope across warm 1/3/7/20 reads is **flat at zero**. The clock
prediction is directional only — not worse than the grouped tier on the same
witness — because no P0 baseline exists yet to state a number against.

**Falsified by.** Non-zero steady-state allocation; a non-flat allocation slope;
or a commit leg that grows with k fast enough to lose the bulk row to grouped.

### H2 — render-side positional verification

**Mechanism.** Each `(sub q)` compares itself positionally against the k-th entry
of the committed sequence P as it reads, setting a divergence flag on any
mismatch. The scratch buffer is filled only from the point of divergence onward.
At commit: if the flag is clear and the count matches, return — **O(1), no pass
over the reads at all**. Otherwise, the symmetric difference.

**The bet.** Doing the comparison at the read is cheaper than doing it at the
commit, because the render already has the key in hand and the cache line is warm
— and it removes the commit-phase pass entirely. In the unchanged case it also
produces **allocation-free renders**, not merely allocation-free commits, because
no new sequence is ever built.

**Pre-registered prediction.** Steady-state commit-phase edge-maintenance work is
O(1) per boundary and independent of k, at the cost of one array index and one `=`
per read during the render.

**Falsified by.** The render leg rising by more than the commit leg falls, on the
k=7 and k=20 rungs. That is a directional test and needs no P0 number.

**Note the risk asymmetry.** H2 puts work on the hot render path that the
predecessor already measured at 0.77–0.86 ms for 300 reads. If the reads are the
expensive part, H2 makes the wrong trade — which is exactly why it is a
*hypothesis* and not a refinement of H1.

### The counting rule

**[DERIVED]** from HD-002 clause (c) ("each counted only by a benchmarked commit,
never by tuning passes") and validation.md's evidence rule ("every P1 evidence row
cites its producing commit SHA and reproduction command").

- A hypothesis is **consumed** when a commit exists implementing it **and** a
  bench row on the standard witness set cites that commit SHA and its reproduction
  command. Both halves, or it is not consumed.
- **Tuning passes over an already-consumed hypothesis consume nothing** and
  un-consume nothing. Tune freely; the count does not move.
- **An abandoned implementation that never produced a bench row leaves the
  hypothesis unconsumed** — but record the attempt and why it was abandoned, on the
  arm bead. An unrecorded abandonment is how two swings quietly become five.
- When **both are consumed and neither meets the survival metric**, the collector
  loses. Grouped stays the product default. No third hypothesis and no extension
  without an explicit operator ruling — the same grammar HD-014 uses for the
  clock: never silently.
- **A tripwire firing consumes nothing and ends the tier**, however many swings
  remain. It overrides the count as it overrides the clock.

---

## 5. The grouped-surface helper-read question

The draft guide's author found a real gap and labelled it honestly: HD-016 calls
helper-donated reads "collector-contingent" and never states the grouped answer.
The guide inferred "no" from React's hook rules and marked it an inference. That
inference reaches the right verdict on weaker grounds than the record supports.

**Verdict: no. An inlined helper cannot read under the grouped surface.**

**[DERIVED]**, and the derivation is from HD-002's own words rather than from
React. HD-002 defines grouped as "one fixed hook receiving **the complete query
collection before the body**". A helper's queries are not knowable before the body
— the body has to run to reach the helper. A reading helper is therefore
structurally incompatible with the grouped tier as defined, independently of what
React permits.

Two supporting grounds, both weaker than the above and neither needed:

- **[RULED]** HD-020's budget is already fully consumed: "the ≤2 budget is fully
  consumed by the subscription/epoch hook and the frame-context hook". A helper
  calling `use-subs` is a third hook in the boundary. Budget breach.
- **[INFERRED]** React's hook rules are the *weakest* ground, and the guide's use
  of them slightly overstates the case. A helper called unconditionally and
  exactly once per render, itself calling `use-subs` unconditionally, satisfies
  React's rules — it is a custom hook by React's own definition. React would
  permit it. HD-002's "before the body" clause is what forbids it.

### What grouped does instead — and why it matters to the verdict

**[INFERRED].** "Helpers take values as arguments and read nothing" is the guide's
answer and it is incomplete. There is a second move that costs no new API, because
it is only data:

```clojure
;; A helper contributes QUERIES, not reads. Still one hook, still before the body.
(defn- badge-queries [id]
  {:badge-count [:cart/count id]})

(defview cart-header [{:keys [id]}]
  (let [{:keys [title badge-count]}
        (use-subs (merge {:title [:cart/title id]}
                         (badge-queries id)))]
    [:header title (badge id badge-count)]))
```

The helper composes into the single `use-subs` call. Hook count fixed, order
fixed, complete collection still assembled before the body. Nothing is added to
the surface — `use-subs` takes a map, and maps merge.

**This changes what the dogfood screen's grouped rendering should look like.**
validation.md makes the three renderings the ergonomics half of the verdict. If
the grouped rendering is written as "helpers read nothing, pass everything down as
arguments" while the collector rendering gets free helper reads, the ergonomic
comparison is being run with one hand tied and the collector wins a point it did
not earn. **Recommendation:** the grouped rendering exercises query-contributing
helpers explicitly, and the diff comparison notes where they were used.

**How to overturn this section.** An operator ruling that grouped's hook may be
called from custom hooks would overturn the verdict — but it must also re-open
HD-020's budget arithmetic, since the third hook is a stated breach. The two move
together.

---

## 6. What this page does not settle

Three holes. Each names what would close it. The first is now **[SETTLED]**, and
the answer went against the page.

### 6.1 Whether one generation fence covers what three compared fields covered

**[SETTLED — NO] (rf2-2rtt6.33, 2026-07-31).** §2 rested on replacing the
predecessor's commit-side deref with the generation fence. The predecessor's
`obs/read` compared **three** things — node-key, version, and frame/registry
epoch — between the render that produced an element and the commit about to
publish it (`implementation/freehand/src/re_frame/freehand/cell.cljc`, `moved?`;
each clause independently load-bearing, and pinned axis-by-axis by
`shell_cljs_test.cljc`'s `invariant-5-every-axis-is-still-compared-on-a-retained-handle`).

It does not cover them, and not by an arithmetic margin. **The two do not guard
the same window**, so "which of the three does the fence cover" has one answer for
all three:

```
predecessor   render probes a site … COMMIT re-reads that site   the render→commit gap
fence         capture gen … BODY RUNS … compare gen … RETURN     one body run
```

`render-body` captures the generation, runs the body, compares, and returns. The
commit that follows — React calling the read-set entry's `subscribe`, which is
where `acquire-cell!` installs the edge and the watch — compares nothing, by
design. So the fence covers a hazard the three fields never addressed (a commit
landing **between two reads of one body**, which the predecessor got for free by
probing per site and re-reading per site) and does not reach the window they were
about. One-for-one on a different axis, not one-for-three.

What *does* reach the gap is the epoch-sum `getSnapshot` handed to
`useSyncExternalStore` plus React's own commit-time snapshot re-check — real, and
enough for a **retained** key. It is driven by the same counter the fence is, and
that counter has exactly one writer: `flush!`, reached only from `mark-dirty!`,
whose only caller is the value-change watch `acquire-cell!` installs **at commit**.
So the version axis is covered for a retained key and not for a staged one, and
the node-key and frame/registry-epoch axes have no counterpart at all.

**The adversarial witness §6.1 asked for exists, and it fails.** A boundary reads a
key nothing holds yet; the value moves before React commits; the generation cannot
move (there is no watch to mark anything), so the change really is *within one
generation*; the commit acquires, takes the new value as its baseline, and arms the
watch one move too late to have reported it; `getSnapshot` answers the same number
before and after, so nothing re-renders. The boundary paints stale and **nothing
ever corrects it** — Spec 006 invariant 5's own words for its stronger half. Filed
as **rf2-2rtt6.42** (P0), with three candidate repairs costed against the tripwire
and none of them implemented here.

**Witness.**
`implementation/freehand/test/re_frame/bench/hicasso/generation_fence_coverage_cljs_test.cljs`
— three rows over the real sub layer, real frames and real watches: a retained key
moving in the gap *does* move the counter (host honesty, without which the staged
row's still counter would be trivially still), a staged key moving in the gap moves
nothing, and a registry-epoch move reaches no counter at all.

**Consequence for §2 and for the tripwire.** The allowed edge-diff operation is
**under-specified rather than merely fast**, exactly as this section warned. It does
not by itself fire tripwire item 4 ("Am I re-reading subscription values at
commit?") — the smallest candidate repair is one comparison per *newly acquired*
key per commit, which is O(new edges) and zero on a steady-state re-render, not a
per-read commit deref. What it does remove is the record's stated reason for
believing item 4 can stay unfired on correctness grounds.

### 6.2 The cost of the first render after a conditional read turns on

**[OPEN], measurement not adjudication.** §3 forces cold render-time reads onto a
compute-and-discard path, so a newly-conditional read pays compute twice: once
uncached at render, once at commit when the edge is acquired. StrictMode's
double-invoke makes it three times in development. Whether that matters depends
entirely on how often conditional reads flip in the witness corpus, which no
instrument has measured.

**What would settle it.** A counter on the collector's cold-read path, reported on
the 100-cell grid and the keyed insert/delete/reorder witnesses. If flips are rare
the cost is noise; if they are common, that is a genuine collector-specific charge
and it belongs in the tier comparison rather than the shared front half.

### 6.3 The clock prediction for both hypotheses

**[OPEN] by construction, and it stays open until P0 publishes.** §4 pre-registers
allocation numbers because they are absolute (zero is zero), and states the clock
predictions only directionally. No number can honestly be pre-registered against
the bar before the Reagent-on-subs and UIx-on-subs arms exist — inventing one now
would be a number nobody could defend and exactly the failure mode
pre-registration exists to prevent.

**What would settle it.** P0's baseline table on the standard bead. When it lands,
the directional predictions in §4 can be restated as ratios against the published
UIx red-zones without reopening the hypotheses themselves. That restatement is a
mechanical step and does not consume a swing.

---

## Cross-references

[decisions.md](decisions.md) HD-002 (the subject), HD-016 (invocation and the
helper-read gap), HD-020 (the hook ledger and the frame plumbing), HD-013 (the
delegated-advisory pattern this page follows) ·
[architecture.md](architecture.md) (the index, the six laws, the inside-React
constraints, the tripwire's "overrides the clock" wording) ·
[validation.md](validation.md) (the survival metric, the witness set, the
evidence rule) · [charter.md](charter.md) §Constraints (the anti-regression fence)
· [EP-0038](../../EP/EP-0038-the-hicasso-view-layer-programme.md) ·
[the bulk-rerender profile](../freehand/studio/bulk-rerender-where-the-time-goes.md)
(the measured predecessor ledger this page's forbidden class is drawn from).

Arms gated by this page: `rf2-2rtt6.9` (lean-React) and `rf2-2rtt6.10` (PATCH).
Recorded on `rf2-2rtt6.1`.
