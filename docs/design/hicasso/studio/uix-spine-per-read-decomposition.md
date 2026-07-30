# The UIx spine's per-read allocation, decomposed

**Bead** `rf2-2rtt6.12` · **epic** `rf2-2rtt6` (EP-0038) · **Wave 0**
**Landed instrument commit `24e8822d7f`** · **measured** 2026-07-31 AUSEST
**Runtime for every figure on this page: headless Chromium, `:advanced`, `goog.DEBUG false`.**

> This page first cited `0cf86fb580`, which is the **authored PR commit and is
> not on `main`** — a reader could not check it out and could not run the
> reproduction at it. The durable landed commit is `24e8822d7f`; the full
> authored-to-landed mapping for PR #7264 is `bb8fc38173=811f6cec42`,
> `0cf86fb580=24e8822d7f`, `fa30f51128=27fa21ac4e`, `1f381947ed=86c50a5d83`.
>
> **Every figure below was re-measured on 2026-07-31 at
> `e5ddd0f1c5581f92c3b11f4a18d57f4cbf770965` after the audit repairs, and
> nothing moved** — see [§ What the audit changed](#what-the-audit-changed).
> The repairs are refusals, not calculations, so there was nothing in them that
> could move a number, and the confirmation run says so rather than assuming it.
>
> A SHA does not survive a rebase, which is how the citation above went wrong in
> the first place. Content hashes do:
> `spine_ablation.cljs` `c553b2bd25c57d753d41df1915e1ff565b94681f`,
> `spine_ablation_app.cljs` `a1ae49372b1b6d7d443408446ded9dd19f44ecfb`,
> `spine_ablation_run.cjs` `6567a4a47783b8a77fdfea340aff8987743f941f`.

Reproduce:

```bash
cd implementation
ABL_ROUNDS=4 ABL_SNAP_ROUNDS=2 \
  node freehand/test/re_frame/freehand/bench/spine_ablation_run.cjs
```

Instrument: `implementation/freehand/test/re_frame/freehand/bench/spine_ablation.cljs`
(page), `…/spine_ablation_app.cljs` (`:advanced` entry),
`…/spine_ablation_run.cjs` (driver). It rides `freehand-release` through
`--config-merge` and adds no build id.

---

## The question

`rf2-2rtt6.5`'s reads-per-boundary ladder priced a re-frame2 subscription read
at **3,550 B** on the UIx spine against **942 B** on Reagent — 3.77× — and
attributed the whole difference to object **count**: 128.5 objects per read
against 36.2, at ~27 B per object on both arms.

One `useSyncExternalStore`, two `useRef`s, one `useMemo` and two `useCallback`s
do not come to 128 objects. So: what are the other objects, how many of them are
irreducible, and is any of it a defect?

---

## The answer, largest term first

The shipped UIx read costs **3,501 B** and **125.8 objects**. It decomposes into
three terms that **sum to the whole with no residual**:

| term | bytes/read | objects/read | share |
|---|---:|---:|---:|
| **one live subscription**, as the spine represents it | 1,684 `[1,660–1,687]` | 56.7 | 48.1% |
| **React's six-hook stack** | 1,037 `[1,035–1,039]` | 46.1 | 29.6% |
| **a second, disposed, unreachable reaction** | 769 `[765–793]` | 23.0 | 22.0% |
| sum | 3,490 | 125.8 | 99.7% |
| measured whole (`xcript`) | 3,489 `[3,488–3,492]` | 125.8 | |

Reagent, measured in the same run on the same subs and the same page shape, pays
**866 B / 31.5 objects** for the subscription half. So:

* The hook stack is **not** a small minority. At 46.1 objects it is 37% of the
  read, and it is what a `useSyncExternalStore`-shaped substrate costs.
* The subscription half costs the spine **1,684 B / 56.7 obj** against Reagent's
  **866 B / 31.5 obj** — **1.94×** for the same job, because the two adapters
  build different objects (below).
* **22% of the read is waste**: a reaction that is built, disposed, and then
  retained for the lifetime of the component with no verb that can reach it.

---

## The third term is a defect, and it was settled by counting

`re-frame.substrate.spine`'s `use-subscribe` takes a **balanced round trip** in
the render phase — `subs/subscribe` immediately followed by `subs/unsubscribe` —
so that a render which never commits retains no ref-count (rf2-es09qq). The
comment there anticipates the steady state correctly: with a durable +1 already
held, the round trip goes 1 → 2 → 1 and never crosses the disposal edge.

For a query with **no live cache entry** it goes 0 → 1 → 0, and 1 → 0 *is* the
disposal edge. `re-frame.subs.cache/unsubscribe!` disposes in-tick with no grace
period: the reaction the render phase just built is disposed and its cache slot
evicted. The commit-phase `subscribe-fn` then misses the cache again and builds a
**second** reaction.

The first one does not go away. `use-memo` returns it into the fiber's hook slot,
and `get-snap` closes over it as its pre-commit fallback. So a disposed reaction —
no source watches, no cache slot, no `-dispose` left to call — is retained for as
long as the component is mounted.

**That is not a claim about bytes, so it was not settled with a heap instrument.**
The page also mounts a small grid through a transcription carrying three counters.
The prediction was written into the driver before the run:

```
PREDICTED, for N reads:   uix  commits N, rebuilt N, bodyRuns 2N
                      reagent  commits 0, rebuilt 0, bodyRuns  N
```

Measured, at N = 600, twice per page over **disjoint** cells so no call could be
served by another's cache entries:

| page | commits | rebuilt | bodyRuns |
|---|---:|---:|---:|
| uix, offset 0 | 600 (1.00N) | **600 (1.00N)** | **1200 (2.00N)** |
| uix, offset 900 | 600 (1.00N) | **600 (1.00N)** | **1200 (2.00N)** |
| reagent, offset 0 | 0 | 0 | 600 (1.00N) |
| reagent, offset 900 | 0 | 0 | 600 (1.00N) |

`rebuilt = N` says every read's commit-phase acquire hands back a reaction that
is **not `identical?`** to the one the render phase built and the fiber is still
holding. `bodyRuns = 2N` is its shadow: the memoised body caches on
`(= @last-db db)`, so a second deref of the *same* reaction against an unchanged
app-db is a memo hit and does not re-run the body — two body runs therefore means
two distinct reactions, not two derefs of one.

Exact integers, no ranges, immune to every fault a heap instrument has.

`bodyRuns = 2N` proves a second thing that matters below: React **does** call
`getSnapshot` again after `subscribe-fn` has run, and that call reads the live
committed reaction.

### What the dead reaction actually holds

Its `-dispose` cleared `watchers` and `own-keys`, but the object graph survives:
the `reify`, three atoms, three volatiles, six closures, the recompute closure and
the memoised body — **23.0 objects, 769 B on this page**. That is a floor, not a
ceiling: the memoised body's `last-result` volatile pins the sub's last computed
value, and every cell here is the integer `0`. A sub returning a collection has
that collection retained twice.

---

## Term by term

### 1,684 B / 56.7 obj — one live subscription, as the spine represents it

`noretain − hooks`. Both arms allocate the query vector, the stable key, both
refs and all six hooks, so this difference is the reaction, its cache entry, and
the watch wiring, and nothing else.

Reagent's arm pays **866 B / 31.5 obj** for the same job. The two adapters build
different objects, and the gap is structural rather than accidental:

* `re-frame.adapter.reagent` passes `ratom/make-reaction` into
  `spine/make-ratom-spine` — one Reagent `Reaction` deftype instance with
  mutable fields.
* `re-frame.substrate.spine/make-derived-value-fn` has no reaction primitive to
  lean on, so it constructs one: a `reify`, three atoms (`watchers`,
  `on-dispose-fns`, `own-keys`), three volatiles (`disposed?`, `prev-state`,
  `dirty?`), six closures (`recompute`, `notify`, `deref-derived`, `flush!`,
  `mark-dirty!`, the on-dispose), and a dependent entry in the epoch scheduler's
  per-source fan-out coordinator.

Every one of those exists for a reason the spine's own comments give — glitch-free
recompute (rf2-i21f5), failure containment (rf2-qcmzc, rf2-2u4rw), a real terminal
for a raw-source fan-out (rf2-7ryt0), idempotent dispose (rf2-1bzlai). **This term
is the price of satisfying the Spec 006 invalidation contract explicitly instead of
inheriting it from a native reactive atom, and it is not a defect.** Whether the
representation could be flattened is a separate design question and is not
answered here.

### 1,037 B / 46.1 obj — React's six-hook stack

`hooks`, the floor-subtracted cost of two `useRef`s, the stable-key derivation
(including the query vector, so that allocation cancels in the difference above),
one `useMemo`, two `useCallback`s and one `useSyncExternalStore` — with no
re-frame anywhere. Per read.

For scale, `rf2-oob3g` priced an isolated `useSyncExternalStore` rung at 516 B.
Six hooks at 1,037 B is that number and change, which is the shape one would
expect.

**This term is irreducible for a hook-based substrate.** It is not re-frame's, it
does not shrink by writing better re-frame, and it is the reason a subscribing UIx
boundary loses to Reagent even after the defect above is fixed. It is also the
term Hicasso is in a position to avoid, since a substrate that does not route
reads through hooks does not pay it.

### 769 B / 23.0 obj — the retained render-phase reaction

`xcript − noretain`, the single-variable ablation described above.

---

## The fix, priced

**Recommendation: land it, in `re-frame.substrate.spine`, as a separate reviewed
change.** It is a production edit to a first-class shipped adapter and was
deliberately not made under this bead.

**The change.** The render-phase `use-memo` currently returns the reaction handle;
have it deref the handle and return the **value**, and have `get-snap`'s pre-commit
fallback read that value. Roughly six lines. Every `subs/subscribe` /
`subs/unsubscribe` call, every hook and every watch stays exactly where it is.

**Measured effect** (this is precisely what the `noretain` arm is):

| | bytes/read | objects/read | vs Reagent |
|---|---:|---:|---:|
| shipped | 3,501 `[3,499–3,503]` | 125.8 | 4.04× |
| with the fix | 2,720 `[2,698–2,722]` | 102.8 | **3.14×** |

−769 B and −23.0 objects per read, −22%.

**Blast radius.** `make-react-spine` is shared by three substrates —
`re-frame.adapter.uix` (first-class, shipped), `re-frame.freehand.substrate`, and
`re-frame.ui.substrate`. One change, three beneficiaries.

**Correctness.** The fallback stops being a live re-read. That window is covered:

* React's `useSyncExternalStore` calls `getSnapshot` again after `subscribe`
  returns, and by then `subscribe-fn` has published the committed reaction, so
  that call reads the **live** one and any app-db write landing between render
  and commit is detected. This is not inferred from React's source — `bodyRuns =
  2N` above measures it happening.
* A frozen value satisfies React's "the result of getSnapshot should be cached"
  rule at least as well as the current live deref does.
* The key-change path (rf2-naz09e) is unchanged in shape: the memo is keyed on
  `stable-key`, so a key change recomputes the snapshot for the **new** target and
  the key guard still rejects the stale `committed-ref`.

**What it does not fix.** Two reactions are still *built* per read on a first
mount (`bodyRuns` stays 2N); the fix stops the first being *retained*. Removing the
double build needs the cache to tolerate a ref-count-0 tenancy so the render-phase
materialisation survives to be adopted by the commit — which is a change to
`re-frame.subs` **and** to Spec 006 §Reference counting and disposal's
no-grace-period rule. That is a ruling, not a patch, and is filed separately.

**Rejected alternative.** Making the fallback live *without* retention — having
`get-snap` call `subs/subscribe-once` on the fallback path — is unsafe. Each call
builds a fresh reaction with a fresh memo cell, so a sub returning a collection
yields a fresh, non-`Object.is` value on every `getSnapshot`, which is React's
documented infinite-render-loop condition.

---

## Instrument discipline

**Retention, never allocation.** Mount an arm and keep it, collect three times
with a beat, read; release, collect, read again. V8's CDP *sampling* heap profiler
drops the samples of collected objects and has already produced one wrong table on
this surface.

**In-situ positive control, predicted before the run.** A dense JS array of
587,500 doubles, which V8 stores as unboxed 8-byte slots: **4,700,000 B predicted**.
Same shape and same size as `rf2-2rtt6.5`'s, so the errors are directly comparable.

| page | reader A | reader B | reader C |
|---|---|---|---|
| uix | 4,699,971 B, **−0.001%** | 4,699,971 B, −0.001% | 4,700,146 B, +0.003% |
| reagent | 4,700,796 B, +0.017% | 4,700,751 B, +0.016% | 4,700,014 B, **0.000%** |

**Fidelity control, on both readers.** An ablation is attributable only if the
transcribed-but-unablated arm reproduces the shipped hook. Shipped `uix` 3,501
`[3,499–3,503]`; transcription `xcript` 3,489 `[3,488–3,492]` — **0.332%
apart**, inside the 3% band the driver declares before it measures. The band is
an order of magnitude below the 22% term being resolved, so it cannot
manufacture the finding. **The object count is checked the same way** and lands
0.045% apart, which matters because the object count is this page's headline and
bytes are the cross-check.

Note which clause carries it: the two arms' *ranges do not overlap* — at four
rounds these ranges are a few bytes wide — and it is the pre-declared 3% band
that passes them. That is the clause's purpose, and it is why the band is
declared in the driver rather than chosen afterwards.

**Order guard.** `order_guard.schedule` rotates and reflects with the round; even
rounds forward, odd rounds reversed, both reported separately. Its self-test (**11
checks**, including the two repairs PR #7267 landed: the k=2 ordering degeneracy,
and refusal when samples are silently lost) runs before the bundle is built.
**Verdict: clean on both pages, no refusal.** Forward/reversed slopes: uix
3,501/3,501, xcript 3,489/3,490, noretain 2,710/2,721, hooks 1,036/1,038,
reagent 866/866.

**Verification.** Every mount reads the DOM back against the boundary count the arm
should have produced: **0 unverified of 154 mounts** across both pages.

**Linearity.** r² ≥ 0.9999 on all four UIx variants for bytes and for object counts.
Reagent's is 0.9973 (bytes) / 0.9961 (objects) — visibly less linear, and its R=0
rung is the reason: a Reagent boundary shell costs 427 B / 12.1 obj against UIx's
~215 B / 5.2 obj.

**Readers.** A = `Runtime.getHeapUsage().usedSize`; B = in-page
`performance.memory.usedJSHeapSize`; C = a full heap snapshot with every node's
`self_size` summed **and every node counted** by a streaming scan. A and B are two
doors onto one V8 counter and are not independent. C walks the object graph and is
the object-count reader — the signal this bead was filed about. A and C agree to
within 0.1% on every arm (e.g. uix 3,501 vs 3,503; reagent 866 vs 865).

---

## Cross-checks against the ladder

Different rung set (0/1/3/7 here, 0/1/3/7/20 there), different run, same box and
same collector design.

| | this page | `rf2-2rtt6.5` ladder | agreement |
|---|---:|---:|---|
| UIx bytes/read | 3,501 | 3,552 | 1.4% |
| UIx objects/read | 125.8 | 128.4 | 2.0% |
| Reagent bytes/read | 866 | 943 | 8.2% |
| Reagent objects/read | 31.5 | 35.8 | 12% |
| UIx/Reagent ratio | 4.04× | 3.77× | |
| UIx shell ÷ Reagent shell | 266 ÷ 589 = 0.45× | 0.49× | |

The ladder column is its **corrected** publication: `rf2-2rtt6.5`'s fit had
included the sub-free R=0 anchor it promised to exclude, and refitting over
1/3/7/20 alone moved it from 3,550/942 to 3,552/943 and its object counts from
128.5/36.2 to 128.4/35.8. Every agreement above holds to within 0.1 percentage
point of what it was, which is the useful thing to know: **this page never
depended on the defect in that one.**

The UIx arms agree closely; the Reagent arms sit ~8–12% below the ladder's, which
is the rung set — dropping R=20 removes the point with the most leverage from a
line whose r² is 0.997 rather than 0.9999. The ratio is quoted here from
same-run arms, which is the comparison that carries.

---

## What the audit changed

The audit of PR #7264 found that **an exit-0 rerun of this instrument was not
self-validating**. The decomposition it published was internally coherent and
the range-diff of the landed patches was exact; what was wrong was that nothing
above could have stopped a run from printing.

Five checks were fail-open, and all five are gates now:

| claim | before | now |
|---|---|---|
| the witness counts match the N/N/2N and 0/0/N predictions | printed beside the prediction, never compared | compared per offset per counter; a mismatch fails |
| the ablation is fidelitous | reader-A **bytes** only, and failed nothing | **bytes and the headline object count**; a failure fails the run |
| reader C answered | a snapshot failure was caught and stepped over | absent or incomplete reader C fails |
| the positive control hit its 8N prediction | recorded, adjudicated by nobody | must land within **1%**, declared in the driver |
| the rungs carry a line | r² recorded, adjudicated by nobody | **r² ≥ 0.99** on bytes and on objects |

A sixth was in the page rather than the driver: `release!*` caught every
unmount exception, removed the container and reported success. The container is
the DOM half; the half that matters is the committed subscription, its watch on
the frame and its cache entry, and an unmount that threw part-way can leave all
three live — after which later arms are no longer the fresh-cache-miss arms this
page prices, while DOM verification and the process verdict both still pass.
Cleanup stays best-effort so one bad arm cannot wedge a run; the error is now
recorded and fails it.

The report is still written to disk before any refusal, so the evidence for a
refusal survives it. Exit 4 is the new code.

### The confirmation run

Every repair is a refusal, so none of them can move a number — but that is an
argument, and this page prefers a measurement. Re-run at
`e5ddd0f1c5581f92c3b11f4a18d57f4cbf770965`, four rounds and two snapshot rounds
per page, **exit 0 under all six new gates**:

| | published | confirmation run |
|---|---:|---:|
| retained render-phase reaction | 769 B `[765–793]` / 23.0 obj | 768 B `[764–770]` / 23.0 obj |
| one live subscription | 1,684 B `[1,660–1,687]` / 56.7 obj | 1,685 B `[1,683–1,689]` / 56.7 obj |
| React's six-hook stack | 1,037 B `[1,035–1,039]` / 46.1 obj | 1,037 B `[1,034–1,038]` / 46.1 obj |
| measured whole (`xcript`) | 3,489 B / 125.8 obj | 3,489 B / 125.8 obj |
| Reagent, same run | 866 B / 31.5 obj | 866 B / 31.5 obj |
| the defect's share | 22.0% | 22.0% |

**No object count moved at all, and no byte figure by more than 0.1%.** The
witness came back exact for the second time — `commits` 1.00N, `rebuilt` 1.00N,
`bodyRuns` 2.00N on UIx against 0, 0 and 1.00N on Reagent, twice over disjoint
cells — which is now checked rather than admired. 0 unverified of 154 mounts,
order guard clean on both pages, controls at −0.003%/+0.003% (uix, readers A/C)
and +0.017%/0.000% (reagent).

The r² floor is worth one note. Reagent's fits sit at 0.9973 (bytes) and 0.9961
(objects), so a floor set at the UIx arms' 0.9999 would have refused a run that
is fine — the Reagent arm is less linear because its R=0 shell is twice UIx's,
which §*Cross-checks* already explains. 0.99 is the floor below which a slope is
not a slope, and it was chosen against that reasoning rather than against these
observations.

---

## What this page does not claim

* It does not claim the 3.14× that remains after the fix is acceptable, or that it
  is not. That is the P0 bar's question.
* It does not claim the spine's derived-value representation should be flattened.
  It measures that the representation costs 1.94× Reagent's for the same job and
  stops there.
* It does not price the CPU of the double build, only its retention.
