# What does dropping the concurrent-store contract give back?

Seat: ABLATION SPIKE. Bead `rf2-so3io`, operator-authorised, answering the
one remaining measurement that could **change** the answer on `rf2-lbs3y`
rather than refine it.

Measured 2026-07-27 against `main` at `109dd53002`, on branch
`worker/uses-ablation-so3io` at the commit that carries this page — the
instrument ships in the same PR. Reagent **2.0.1**, UIx **1.4.4**, React
**19.2.0**. Host: Windows 11, headless Chromium 147 via Playwright, single
developer workstation. Every figure is an `:advanced` ClojureScript bundle
with `goog.DEBUG false` — the artefact a consumer ships.

**This is an ablation, not a proposal.** Nothing here changes the shipped
path; `implementation/freehand/src/` is byte-identical to `origin/main`.
The ablated boundary lives entirely in `implementation/freehand/test/…/bench/`
as an extra arm on B6's clock window and B7's heap door.

---

## The answer, first

**The two worst rows are not one row, and neither of them moves enough.**

The hypothesis this bead was filed on was that `useSyncExternalStore` and
the commit-side invariant-5 re-read are two faces of one contract, so
dropping it might recover a large slice of retained heap **and** a large
slice of clock at once. Both halves were ablated, together and separately,
and measured on both axes in both arm orders.

1. **Clock: the broad update gets ≈14% cheaper. Nothing else moves.**
   A write that repaints 300 boundaries costs the reference boundary
   3.1–4.1 ms and the ablated one 2.7–3.6 ms — **cheaper in 12 of 12
   paired rounds**, by 0.3–0.7 ms, mean **13.6%–14.8%**. It takes the
   Freehand-shaped boundary from **6.3–6.5× Reagent to 5.4–5.5×**. The
   narrow update is a **null result** (the direction flips between arm
   orders) and mount moves by **≤0.1 ms**, which is Chrome's clock
   quantum.
2. **Heap: 363–376 bytes a boundary come back, and every one of them is
   the hook, not the commit.** Above the React floor, the sub-free
   boundary goes from **2,416–2,431 B to 2,041–2,062 B** and the reactive
   one from **4,281–4,294 B to 3,918 B**. Ranges disjoint in both orders,
   the independent snapshot reader agreeing to within 1.5%. Against
   Reagent that is **5.88–5.94× → 4.97–5.04×** on the sub-free witness
   and **4.14–4.18× → 3.79–3.81×** on the reactive one.
3. **The hypothesis is falsified in a specific and useful way.** An
   isolating rung — React's six hooks over a two-field JS object, no
   ViewCell, no CLJS data structure — gives back **355–357 B**, which is
   **95%–97% of the whole heap return**. The commit-side re-read retains
   **8–20 B a boundary**, i.e. nothing: it publishes the same five-key
   observation record either way. So the heap return comes from the hook
   half and the clock return comes from the commit half; they are two
   levers on two axes, not one lever on both.
4. **And the cheap path is not free even where it wins.** Without
   `useSyncExternalStore` a boundary **cannot repaint inside
   `react-dom/flushSync`** — measured, not reasoned: `{:ref true,
   :nc false}` on the same probe in both runs. `useSyncExternalStore`'s
   listener schedules at React's **sync lane**; nothing else available to
   a function component does, and an empty `flushSync` flushes only that
   lane. `rf2-w2m25`'s synchronous commit door is built on it.

**What the guarantee buys, measured.** A source moved in the render→commit
gap, on a **watchable browser host** — where the standing rebuttal is that
the change watch would catch it anyway:

| site | commit | revision advanced? | cell left dirty? | value published |
|---|---|---|---|---|
| **retained** | shipped | **yes** | no | **fresh** |
| **retained** | ablated | no | yes | stale |
| **staged** | shipped | **yes** | no | **fresh** |
| **staged** | ablated | no | **no** | **stale** |

The retained row self-heals one window later, because its watch was
installed by an earlier commit and did fire. **The staged row does not
heal at all.** `obs/acquire!` installs the watch *during this commit* —
after the move has already happened — so there is no channel left, and the
boundary paints the stale value and keeps it until something else moves
that source. That is the first render of a dependency: a panel mounting
while a permission drops, a user switches, a record is redacted. It is not
a hypothetical class; it is the row above.

---

## Method

**No sixth instrument.** The clock is B6's `timed-write!` window called
unchanged — same state install, same single microtask yield, same
per-write read-back of the written cell out of the DOM, same interleaving
with the arm order rotating on the sample index, same in-run floor
normalisation. The heap is B7's door, B7's collector, B7's three readers,
B7's forced collections and B7's positive control, over a merged arm
table. The two drivers gained purely additive `B6_INIT_FN` / `B7_INIT_FN`
/ `B7_ARMS` environment seams; with them unset both files are
byte-for-byte the published instruments.

**The ladder has a reference rung, and that is what makes it an
ablation.** Comparing a hand-built ablated boundary against the *published*
Freehand arm would measure the ablation plus every other difference
between a hand-built body and the interpreted emitter walk. So each pair
is:

| rung | body | shell | commit |
|---|---|---|---|
| `ref` | hand-built | real `shell/render` | real `cell/commit!` |
| `nc` | **identical** | no `useSyncExternalStore` | no commit-side re-read |

and the contract's price is `ref − nc`, with everything else held
identical. The published arms ride alongside in the same run on the same
box, so `published − ref` is visible as the emitter walk this pair does
not contain (≈0.7–1.3 ms on the broad write, ≈20 B on standing heap).

**What `nc` is, exactly.**

- `useSyncExternalStore` → `useReducer` force-update, with the
  subscription folded into the lifecycle layout effect. **Five hooks
  against the shell's six** — `useContext`, `useRef`, `useReducer`, and
  the same two `useLayoutEffect`s.
- `cell/commit-readings`'s per-handle `obs/read`, and the `moved?`
  comparison it feeds, deleted. The bundle's observations are published
  from the value the **render** read. `obs/owned?` stays — a total,
  no-throw predicate that is not part of the contract being priced.
- Everything else in the commit is copied unchanged: staging, the
  acquire-before-release order, the rollback guard, the currency
  re-check, superseded-handle release, the event-table publication.

**The repaint channel the ablation is forced into, and why the window is
still B6's.** Because the sync lane is lost (finding 4), the `nc` arm
takes Reagent's shape instead: a notification *enqueues* the boundary's
force-update and a drain runs the enqueued bumps inside the caller's
`flushSync`, where they take the sync lane. That is `reagent.core/flush`
in Freehand spelling, and it is what the Reagent arm this whole studio
compares against has always done. Both B9 rungs run `cell/flush!` inside
their `force!` so the two windows are identical and the pair's difference
is the contract rather than the drain. The consequence is visible in the
leg split and is bookkeeping, not a saving: on the broad write the
reference rung reads `gap 2.8–2.9 ms / force 0.0` and the ablated one
`gap 0.2–0.3 ms / force 2.0–2.3`.

**Gates green before any figure was read.**

- **Canonical-DOM parity across all seven mount arms** — floor, Freehand
  interpreted, Freehand compiled, Reagent, UIx, `b9-ref`, `b9-nc` — with
  the written element count (301) on every one, in both arm orders.
- **Canonical-DOM parity across all six update arms**, plus the check
  that every arm's DOM actually *moved*, in both orders.
- **Per-write DOM read-back: 0 unverified writes** in every published
  clock row, both orders.
- **Per-mount DOM read-back on the heap door: 0 unverified of 195
  mounts** across the two heap runs.
- **The positive control**, a dense JS array of 587,500 doubles that V8
  stores as unboxed 8-byte slots, so its retained size is **4,700,000
  bytes predicted before anything is measured**:

  | run | reader | predicted | measured | error |
  |---|---|---|---:|---:|
  | forward | A | 4,700,000 | 4,697,146 [4,681,918–4,700,942] | −0.061% |
  | forward | **C** | 4,700,000 | **4,700,024** | **+0.0005%** |
  | reversed | A | 4,700,000 | 4,697,236 [4,691,344–4,701,266] | −0.059% |

  The control did not miss. It has missed on this surface before — a flat
  one-byte string of 4,700,000 characters read as six kilobytes, because
  V8 does not materialise `'x'.repeat(n)` — which is why it is a
  predicted figure rather than an observed one.

**Both arm orders, always.** Every row below was taken twice, once with
the arm list forward and once reversed. B6 already rotates the order on
the sample index and B7 rotates it on the round, but a large-object arm
has been observed to inflate its successor 2× reproducibly on this surface
(`rf2-88pie`), and only running both orders can see that. It did not
happen here: every conclusion holds in both.

---

## 1. The clock

### 1a. Broad update — one write, 300 boundaries repaint

p50 milliseconds per write, six rounds, range across rounds in brackets.

| arm | forward | reversed |
|---|---:|---:|
| floor — top-down React, no substrate | 0.1–0.2 | 0.1–0.2 |
| **Reagent** | **0.5–0.7** | **0.5–0.6** |
| Freehand interpreted (published arm) | 4.3–5.4 | 3.6–4.1 |
| Freehand compiled | 3.7–4.7 | 3.2–3.8 |
| **`b9-ref`** — hand-built, contract intact | **3.4–4.1** | **3.1–3.9** |
| **`b9-nc`** — hand-built, contract ablated | **3.0–3.6** | **2.7–3.2** |

**The `ref` and `nc` ranges overlap, so on ranges alone they are
indistinguishable** — which is what this studio's method requires be said
first. The arms are interleaved *within* each round, so the paired
comparison is available and is far stronger:

| paired, per round | forward | reversed |
|---|---|---|
| `nc` ÷ `ref` | **×0.864**, cheaper in **6 of 6** | **×0.852**, cheaper in **6 of 6** |
| `ref − nc`, ms | 0.4–0.7, mean 0.52 | 0.3–0.7, mean 0.50 |

**Twelve of twelve rounds in one direction is a real effect**, and it is
worth **≈14%** of the broad-write window.

Against Reagent, computed per round so the box's drift cancels:

| ÷ Reagent, broad write | forward | reversed |
|---|---:|---:|
| Freehand interpreted, published arm | 8.00 [7.5–8.6] | 7.27 [6.83–7.8] |
| `b9-ref` | 6.30 [5.86–6.83] | 6.48 [6.2–6.8] |
| **`b9-nc`** | **5.44** [5.14–6.0] | **5.52** [5.33–5.8] |

So the ablation moves the boundary from ≈6.4× Reagent to ≈5.5×. Applying
the same measured ×0.86 to the published interpreted arm puts it at
≈6.3–6.9× rather than ≈7.3–8.0×.

> **These are not the published row's absolutes.** Six arms interleave
> here where the published row interleaves four, on a differently loaded
> box and a different day, and the two B9 arms carry a `cell/flush!` in
> their `force!` that the published arm does not. The published broad row
> reads **9.9× Reagent**; this run reads the same arm at 7.3–8.0×. **The
> ratio inside this run is what transfers, and the pair is what this page
> claims** — not the absolute.

### 1b. Narrow update — one write, one boundary repaints: a null result

p50 milliseconds per **sample of 20 writes**.

| arm | forward | reversed |
|---|---:|---:|
| Reagent | 1.0–1.2 | 1.1–1.3 |
| Freehand interpreted | 10.2–12.4 | 9.9–12.6 |
| `b9-ref` | 9.9–12.3 | 9.6–12.6 |
| `b9-nc` | 10.1–12.3 | 9.4–11.8 |

Paired: `nc ÷ ref` is **×1.027** forward and **×0.970** reversed — the
direction **flips between arm orders**, which is what a null result looks
like when it is honest. Nothing here is distinguishable.

It should not be. B6's own leg split puts ≈90% of a narrow write in
`frame/replace-app-db!` and the signal graph, before React is involved,
and the commit-side re-read the ablation removes is **one** handle read
rather than three hundred. There is nothing for the ablation to take.

### 1c. Mount — 300 sub-free boundaries: ≤0.1 ms

p50 milliseconds, B6's W2 storm shape, all seven arms gated on canonical
DOM first.

| arm | forward | reversed |
|---|---:|---:|
| floor | 0.2–0.3 | 0.2 |
| UIx | 0.3 | 0.3–0.4 |
| Freehand compiled | 0.5 | 0.4–0.5 |
| **Reagent** | **0.5–0.6** | **0.5–0.6** |
| **`b9-ref`** | **0.9–1.1** | **0.8–1.1** |
| **`b9-nc`** | **0.9–1.0** | **0.8–1.0** |
| Freehand interpreted (published arm) | 1.7–2.2 | 1.6–2.0 |

`nc` is never *more* expensive than `ref` in any of the twelve rounds, and
never cheaper by more than **0.1 ms — Chrome's `performance.now` quantum**
across three hundred boundaries. Read it as a bound rather than a
measurement: **the hook swap is worth at most 0.1 ms of a 0.9 ms mount.**

That bound is useful for the broad-update row, because the storm witness
has no dependencies at all — its commit loops over zero sites — so this
pair isolates the **hook** half of the ablation exactly. At most 0.1 ms of
the broad write's 0.5 ms saving can be the hooks; **at least 0.4 ms of it
is the commit-side re-read.**

And note where a contract-free Freehand boundary still stands: **0.8–1.1
ms against Reagent's 0.5–0.6**, on the shape with no reactivity on either
side.

---

## 2. The heap

Retained bytes per boundary, `arm − floor`, 10 roots × 300 boundaries =
3,000 boundaries held per reading, six rounds, one un-read warm-up pass
first. Reader **A** is CDP `Runtime.getHeapUsage` after three forced
collections; reader **C** is a full heap snapshot with every node's
`self_size` summed by a streaming scan, run once as its own pass.

Each arm's floor is subtracted **within its own round**, so the box's
drift cancels; the bracket is the range across the six rounds.

| arm | A, forward | A, reversed | C |
|---|---:|---:|---:|
| `storm` UIx | 251 [244–257] | 252 [250–254] | 251 |
| `storm` **Reagent** | **411** [405–417] | **409** [407–413] | **403** |
| `storm` Freehand (published arm) | 2,436 [2,431–2,444] | 2,434 [2,426–2,441] | 2,428 |
| `storm` **`b9-ref`** | **2,416** [2,397–2,428] | **2,431** [2,401–2,439] | 2,396 |
| `storm` **`b9-nc`** | **2,041** [2,024–2,053] | **2,062** [2,053–2,072] | 2,042 |
| `storm` hooks-only `ref` — six hooks, no ViewCell | 1,199 [1,191–1,203] | 1,200 [1,194–1,207] | 1,193 |
| `storm` hooks-only `nc` — five hooks, no ViewCell | 842 [837–847] | 845 [840–849] | 839 |
| `reactive` **Reagent** | **1,027** [1,024–1,034] | **1,034** [1,030–1,039] | 944 |
| `reactive` Freehand (published arm) | 4,395 [4,374–4,413] | 4,390 [4,370–4,408] | 4,402 |
| `reactive` **`b9-ref`** | **4,294** [4,284–4,300] | **4,281** [4,270–4,305] | 4,305 |
| `reactive` **`b9-nc`** | **3,918** [3,913–3,924] | **3,918** [3,912–3,926] | 3,930 |

**The published rows reproduce.** UIx 251 against the published 251,
Reagent 411/409 against 410, Freehand 2,436/2,434 against 2,430, reactive
Reagent 1,027/1,034 against 1,037, reactive Freehand 4,395/4,390 against
4,346. That is a different day, a different bundle and a thirteen-arm
table landing on the published table to better than 1.2%, which is what
makes the new rows beside them readable. Reader C's `reactive` Reagent is
the one row that does not (944 against the published 1,034), and it is not
reconciled away here — the snapshot pass is a single reading per arm and
the two readers' constant offset is known to be arm-dependent.

**What the ablation returns**, paired within each round:

| pair | forward | reversed | reader C |
|---|---:|---:|---:|
| `storm` `ref − nc` | **375 B** [352–402], lower in **6/6** | **369 B** [348–383], **6/6** | 354 B |
| `reactive` `ref − nc` | **376 B** [366–387], **6/6** | **363 B** [352–386], **6/6** | 375 B |
| hooks-only `ref − nc` | **357 B** [352–363], **6/6** | **355 B** [351–361], **6/6** | 354 B |

The `ref` and `nc` ranges are **disjoint on every witness in both orders**
— 2,416 [2,397–2,428] against 2,041 [2,024–2,053], 4,294 [4,284–4,300]
against 3,918 [3,913–3,924], and the same in reverse — so this is a real
effect by this studio's own rule, not a paired inference.

**And the hooks-only rung accounts for essentially all of it.** 355–357 B
of a 363–376 B return, which is **95%–97%**. The remaining 8–20 B is the
difference between publishing a five-key observation record built from a
commit-time reading and one built from the render's — which is to say,
nothing. **The commit-side re-read costs clock, not heap.**

Where that leaves the ratio, computed per round and then summarised:

| | Freehand ÷ Reagent | Freehand ÷ UIx |
|---|---:|---:|
| `storm`, contract intact | **5.884** [5.755–5.982] / **5.941** [5.820–5.988] | 9.627 / 9.648 |
| `storm`, contract ablated | **4.969** [4.911–5.064] / **5.039** [4.977–5.092] | 8.131 / 8.184 |
| `reactive`, contract intact | **4.180** [4.144–4.196] / **4.139** [4.112–4.178] | — |
| `reactive`, contract ablated | **3.814** [3.788–3.827] / **3.788** [3.765–3.807] | — |

(forward / reversed; every pair of ranges disjoint.)

### 2a. Read against `rf2-oob3g`'s ceiling

`rf2-oob3g` decomposed the 2,430 B sub-free boundary and put React's six
hooks at 1,171 B, of which `useSyncExternalStore` alone was **516 B** —
more than Reagent's whole boundary. This ladder's hooks rung reads
**1,199–1,200 B**, within **2.4%** of that figure from a differently
built instrument, which is the cross-check that lets the two pages be read
together.

But 516 B is what `useSyncExternalStore` *costs*, not what removing it
*returns*, and the difference is the whole point of measuring rather than
subtracting. The ablated rung still needs a repaint channel: a
`useReducer` and a subscription registration folded into the lifecycle
effect. Those cost **≈304 B** (the 516 B store plus the 145 B
`useCallback` that fed it, less the 357 B actually returned). **The
headline number a subtraction would have produced — 661 B — is 1.85× the
truth.**

`rf2-oob3g`'s ceiling therefore stands and tightens. It found that a
Freehand boundary with the ViewCell driven **to zero** would still retain
1,171 B against Reagent's 418 — **2.80×**. With the concurrent-store
contract *also* gone, that floor moves to 842–845 B, **2.05–2.07×
Reagent**. Both levers, taken together and taken to their limits, do not
reach parity.

---

## 3. What the contract is load-bearing for

Two of these are measured on this page; the third is read out of the
source and is stated as such.

**3a. The synchronous commit door — measured, `{:ref true, :nc false}`.**
`useSyncExternalStore`'s listener calls React's `forceStoreRerender`,
which schedules at the **sync lane**. A `useReducer` dispatch issued from
a plain microtask takes the **default** lane, and an empty
`react-dom/flushSync` flushes only the sync lane — the fault B6 already
records against its own floor arm. So the ablated boundary put through
B6's published window — write, one microtask, empty `flushSync`, read the
DOM — **still holds the old value when the flush returns**, in both runs.

`rf2-w2m25` is exactly that guarantee, and it is pinned in both
directions by `a-freehand-write-commits-inside-flushsync`. A cheap path
would have to rebuild it: the substrate keeps its own queue of pending
force-updates and drains it inside a synchronous commit boundary, which is
what this page's `nc` arm does and what `reagent.core/flush` is. That is
recoverable — 14,000 verified writes here say so — but it means
re-implementing the piece of Reagent's batching that
`useSyncExternalStore` exists to make unnecessary.

**3b. What invariant 5 catches that a watch does not — measured.** The
table in *The answer, first*. On a **watchable** host, a **retained**
site's mid-gap move is caught by both channels and the ablation is one
window late; a **staged** site's is caught by **neither**, because
`obs/acquire!` installs the watch during the same commit that needed it.
The published bundle carries the stale value, the cell is not dirty, and
nothing will correct it.

Spec 006's own statement of invariant 5 rests on the headless case — *"on
a non-watchable headless host a retained site has no value-movement
watch, so this comparison is its only correction"*. This measurement adds
the browser case, which is stronger than the spec claims and is the case
a product decision is actually about.

**3c. In the adapters, `useSyncExternalStore` is load-bearing for
ownership, not tearing.** Not measured here; read out of
`implementation/core/src/re_frame/substrate/spine.cljs`. The spine's
`use-subscribe` puts the durable sub-cache acquire/release **inside**
`useSyncExternalStore`'s subscribe callback precisely because React never
runs that callback for a render it does not commit — *"an abandoned render
acquires NOTHING; the leak is gone BY CONSTRUCTION"* (`rf2-es09qq`, after
`rf2-879fe`). Freehand does not depend on that: its ownership is acquired
in a layout effect, which React also only runs for committed renders. But
any ablation framed as a re-frame-wide policy would hit the adapters, and
there it reintroduces a ref-count leak class rather than a tear class.

---

## 4. Is the cheap path separable?

**The commit half is separable cheaply. The hook half is separable
expensively. The *decision* is not separable at all, and that is the one
that settles it.**

**The commit half — one branch.** The ablated `commit-readings` differs
from the shipped one by which value the observation record carries and
whether a comparison runs. Inside `cell.cljc` that is a branch in one
function plus a flag on the cell, on the order of a dozen lines. This
page's copy is ~115 lines of duplicated staging, currency, rollback and
publication, but that duplication is an artefact of those functions being
**private** to `cell.cljc` — it is the price of measuring from outside,
not the price of the design. It should not be quoted as the cost of the
feature.

**The hook half — two component types, and a fork in the checkpoint.**
Hooks cannot be called conditionally, so a per-boundary choice between
`useSyncExternalStore` and `useReducer` is two `shell/render`s and two
component types, chosen at component-construction time and keyed into
`shell-signature` so that flipping the choice **remounts** rather than
running a different hook order against the old Fiber's hook state. That
machinery already exists and is already exercised by the lowering axis, so
the shape is available — but it is a second shell, permanently, and it
drags `checkpoint.cljs` with it: the pending window's host-visible closer
would have to drain the cheap boundaries' force-updates from inside the
sentinel's layout effect (§3a), so the substrate acquires a render queue
of its own that today it does not have.

**The decision cannot be automated, and its failure mode is silent.** The
switch is supposed to be "cheap by default, guarantee on when a consumer
uses concurrent features". **Nothing in re-frame2 can observe that.**
`startTransition`, `useDeferredValue` and `<Suspense>` are React APIs the
consumer calls in their own tree; the substrate mounts through
`createRoot` and sees no signal from any of them. The only detector for
"this render was interrupted" is `useSyncExternalStore` itself, which is
the thing being switched off — the detection is circular. So the switch
must be **declared**, and a wrong declaration produces exactly the class
of bug the guarantee deletes: a silent wrong render, in a build that was
told it did not need checking.

**And it cannot be per-boundary even if it were declarable.** A tear is
not local. The ugly instance is a stale panel *beside* a fresh one, so a
tree where some boundaries are cheap and some are guaranteed is torn
across the boundary between them. Any honest switch is at least
per-root — which makes it a build-or-mount-level mode, not an authoring
convenience.

**The permanent clarity cost, stated plainly.** Two shells, two hook
skeletons, a flag threaded through commit, a fork in the window closer,
and — the part that does not shrink — **every ViewCell law verified in
both modes, or the cheap mode is unverified**. Spec 006 freezes six
invariants; invariant 5 is one of them and invariant 6's window-closing
contract is entangled with §3a. This is a spec change, not a patch, and
`spec/` is not this bead's to edit.

**The arithmetic that should be read beside all of it.** A boundary with
the whole contract removed is still **5.0× Reagent** on standing heap,
**3.8×** on the reactive witness, **≈5.5× Reagent** on a broad write, and
**1.6× Reagent** on mount. The contract is not what puts Freehand behind
Reagent; it is 14% of one row and 15% of another.

---

## 5. What this page does not cover

- **No compiled tier.** Ruled out; not measured, not quoted.
- **The `nc` pair's bodies are hand-built**, so neither rung contains the
  interpreted emitter walk. The walk is visible beside them as
  `published − ref` — ≈0.7–1.3 ms on the broad write, ≈20 B of standing
  heap — and it is absent from **both** rungs, so it cannot affect the
  pair's difference. It does mean the pair's *ratios* to Reagent are for a
  hand-built Freehand boundary, not for `v/defview`.
- **The `nc` arm's window puts its React work in a different leg.** Total
  window is what the ratios use and it is the same window on both rungs,
  but a reader comparing leg splits across rungs is comparing bookkeeping.
- **Heap under update.** Standing retention only, exactly as B7 measures
  it. `freehand-vs-reagent-allocation.md` owns the other question.
- **SSR and hydration.** `useSyncExternalStore`'s `getServerSnapshot` has
  no counterpart in the ablated shell. Nothing here measures what that
  would do to hydration; it is named as unmeasured rather than assumed
  harmless.
- **A real application.** Two purpose-built witnesses on a loaded
  developer workstation.
- **A failing-test demonstration inside the shipped suite.** §3b's tear is
  measured through a probe over the real port, not as a `deftest`. The
  shipped invariant-5 rows (`invariant-5-every-axis-is-still-compared-on-a-retained-handle`,
  `invariant-5-a-real-node-moving-in-the-gap-corrects-a-retained-site`)
  cover the retained case and stayed green throughout; **the staged case
  has no shipped row**, and on this page's evidence it is the worse one.

## Provenance

- Fixtures: `storm` = 300 sub-free leaf boundaries, 301 elements (B6's
  W2); `reactive` / update grid = 300 cells each reading their own
  `[:b6/cell i]`, 301 elements.
- Clock sampling: mount 6 rounds × (5 warm-up + 20 samples) per arm;
  update 6 rounds × (4 warm-up + 12 samples), broad = 1 write a sample,
  narrow = 20. Arms interleaved at the sample level with the order
  rotating on the sample index. Every published row taken twice, forward
  and reversed.
- Heap sampling: 10 roots × 300 boundaries held per reading, 6 rounds,
  arm order rotating with the round, one un-read warm-up pass first;
  snapshot pass separate, once per arm, on the forward run only.
- Verification: 0 unverified writes in every clock row; 0 unverified of
  195 heap mounts; canonical-DOM parity across 7 mount arms and 6 update
  arms in both orders; positive control within 0.061% on reader A and
  0.0005% on reader C.
- Build: `:advanced`, `goog.DEBUG false`, via `--config-merge` on the
  existing `:freehand-release` build id.
  `implementation/shadow-cljs.edn` is unchanged.
- Source, shipped rather than deleted:
  `implementation/freehand/test/re_frame/freehand/bench/b9_nc.cljs` (the
  ablated commit, the ablated shell, both rungs of each pair, the heap
  arms, the clock arms, and the two probes) and `b9_app.cljs` (the
  `:advanced` entry). The drivers are B6's `b6_prod_run.cjs` and B7's
  `b7_run.cjs`, unchanged except for additive environment seams.
- Reproduce:

  ```sh
  cd implementation
  B6_INIT_FN=re-frame.freehand.bench.b9-app/-main B6_OUT_DIR=out/b9-clock \
    node freehand/test/re_frame/freehand/bench/b6_prod_run.cjs
  B6_INIT_FN=… B6_QUERY='?reverse=1' node …/b6_prod_run.cjs
  B7_INIT_FN=re-frame.freehand.bench.b9-app/-main B7_OUT_DIR=out/b9-heap \
    B7_ARMS='storm/floor,storm/uix,storm/reagent,storm/freehand,b9/storm-hooks-ref,b9/storm-hooks-nc,b9/storm-ref,b9/storm-nc,reactive/floor,reactive/reagent,reactive/freehand,b9/reactive-ref,b9/reactive-nc' \
    node freehand/test/re_frame/freehand/bench/b7_run.cjs --only heap
  B6_INIT_FN=… B6_QUERY='?mode=probe' node …/b6_prod_run.cjs
  ```
