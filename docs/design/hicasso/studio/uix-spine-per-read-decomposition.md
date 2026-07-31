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
> **Every figure below was re-measured on 2026-07-31 after the audit repairs,
> and nothing moved** — see [§ What the audit changed](#what-the-audit-changed).
> The repairs are refusals, not calculations, so there was nothing in them that
> could move a number, and the confirmation run says so rather than assuming it.
>
> A SHA does not survive a rebase, which is how the citation above went wrong in
> the first place — and this page's own repair branch was then rebased onto
> `main` between its confirmation run and its merge, moving every SHA again
> while the blobs did not move at all. **Content hashes are the identifier:**
> `spine_ablation.cljs` `c553b2bd25c57d753d41df1915e1ff565b94681f`,
> `spine_ablation_app.cljs` `a1ae49372b1b6d7d443408446ded9dd19f44ecfb`,
> `spine_ablation_run.cjs` `6567a4a47783b8a77fdfea340aff8987743f941f`. The
> confirmation run was authored as `fa09c5ad7a` on
> `worker/bench-audit-cluster`; if that does not resolve,
> `git log --oneline --all -- <path>` finds a commit carrying the blobs and
> `git rev-parse <candidate>:<path>` confirms it.

> **THE DEFECT THIS PAGE FOUND HAS BEEN FIXED (rf2-2rtt6.13, 2026-07-31).**
> `re-frame.substrate.spine`'s render-phase `use-memo` now derefs while the
> reaction is live and returns the **value**, so the disposed reaction is no
> longer retained. **The shipped UIx read is 3,501 B → 2,734 B and 125.8 → 102.8
> objects; UIx/Reagent 4.04× → 3.158×.** Every pre-fix figure on this page stands
> as measured and is now historical; each section says which side of the fix it
> is on, and [§ The fix, landed](#the-fix-landed) carries the after-run in full.
>
> The instrument's arms swapped polarity with it, because `xcript`'s contract is
> to transcribe the **shipped** hook: `xcript` is now the value-returning shape
> and the ablation is `retain`, the superseded one. Same two bodies, same single
> differing expression; the subtraction reads `retain − xcript`.
>
> Blobs for the after-run: `spine_ablation.cljs`
> `ee1ca916bfb02581c32c1a02e4266c7c6d309c76`, `spine_ablation_app.cljs`
> `a1ae49372b1b6d7d443408446ded9dd19f44ecfb` (unchanged), `spine_ablation_run.cjs`
> `10c49efbbe731ce02deda6e2ed432b0270d6beb6`, and the fixed spine
> `implementation/core/src/re_frame/substrate/spine.cljs`
> `56f7e5480c99330515d525a2bdacf5f86a0db7bd`. Authored as `4367e5d93f` on
> `worker/spine-2rtt6-13`.

> **SPINE STAMP — this page's after-run is POST-`rf2-2rtt6.13` and
> PRE-`rf2-2rtt6.25`.** The landed `.13` commit is **`9df5094816`**, whose
> `spine.cljs` blob is the `56f7e5480c99…` named above — so the `2,734 B/read`
> reading and everything under [§ The fix, landed](#the-fix-landed) sit on that
> tree exactly. **A third landing has happened since:** `rf2-2rtt6.25`
> (PR #7305, **`f784ab0adb`**, spine blob `086d08e94089…`) landed the
> hook-scoped provisional hand-off, so a cold read builds one reaction instead
> of two **under a forced synchronous commit** — on the shipped bare
> `createRoot().render` path the audit of PR #7305 measured 2N, which is open on
> `rf2-2rtt6.25`. That change is a **cold-read/mount-time** effect and this page's
> steady-state per-read slope is not re-measured against it; the figures below
> are therefore stamped `.13`, not "current tree". The live per-read gate lines
> are on [validation.md](../validation.md#the-per-read-gates), and the clock
> consequences of `.25` are on
> [the converged witness set](p0-converged-witness-set.md).

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

*As measured before rf2-2rtt6.13. The third term is the one that is now gone.*

The shipped UIx read cost **3,501 B** and **125.8 objects**. It decomposed into
three terms that **sum to the whole with no residual**:

| term | bytes/read | objects/read | share | still paid? |
|---|---:|---:|---:|---|
| **one live subscription**, as the spine represents it | 1,684 `[1,660–1,687]` | 56.7 | 48.1% | yes |
| **React's six-hook stack** | 1,037 `[1,035–1,039]` | 46.1 | 29.6% | yes |
| **a second, disposed, unreachable reaction** | 769 `[765–793]` | 23.0 | 22.0% | **no — rf2-2rtt6.13** |
| sum | 3,490 | 125.8 | 99.7% | |
| measured whole (pre-fix) | 3,489 `[3,488–3,492]` | 125.8 | | |

Reagent, measured in the same run on the same subs and the same page shape, pays
**866 B / 31.5 objects** for the subscription half. So:

* The hook stack is **not** a small minority. At 46.1 objects it is 37% of the
  read, and it is what a `useSyncExternalStore`-shaped substrate costs.
* The subscription half costs the spine **1,684 B / 56.7 obj** against Reagent's
  **866 B / 31.5 obj** — **1.94×** for the same job, because the two adapters
  build different objects (below).
* **22% of the read was waste**: a reaction that is built, disposed, and then
  retained for the lifetime of the component with no verb that can reach it.
  That term is now zero on the shipped hook — see
  [§ The fix, landed](#the-fix-landed).

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

The first one did not go away. `use-memo` returned it into the fiber's hook slot,
and `get-snap` closed over it as its pre-commit fallback. So a disposed reaction —
no source watches, no cache slot, no `-dispose` left to call — was retained for as
long as the component stayed mounted. **rf2-2rtt6.13 closed this**; the second
build is still there and is rf2-2rtt6.14's subject.

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

`xcript − hooks` (before the fix, `noretain − hooks` — the same body under its
old name). Both arms allocate the query vector, the stable key, both refs and all
six hooks, so this difference is the reaction, its cache entry, and the watch
wiring, and nothing else.

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

`retain − xcript` (before the fix, `xcript − noretain` — the same two bodies under
their old names), the single-variable ablation described above.

---

## The fix, landed

**Landed 2026-07-31 as rf2-2rtt6.13**, in `re-frame.substrate.spine`, as the
separate reviewed change this page asked for. It was a production edit to a
first-class shipped adapter and was deliberately not made under `rf2-2rtt6.12`.

**The change.** The render-phase `use-memo` returned the reaction handle; it now
derefs the handle **while the reaction is still live** and returns the **value**,
and `get-snap`'s pre-commit fallback reads that value. Six lines. Every
`subs/subscribe` / `subs/unsubscribe` call, every hook and every watch stayed
exactly where it was — so the render is still net-zero and rf2-es09qq's
no-retention-on-an-abandoned-render property is untouched.

**Measured effect**, one run on this branch, four rounds, exit 0 under all six
gates, the before and after arms measured **in the same run under the same order
guard**:

| arm | bytes/read | objects/read | vs Reagent |
|---|---:|---:|---:|
| `retain` — the superseded shape | 3,489 `[3,488–3,491]` | 125.8 | 4.030× |
| **`uix` — shipped, after the fix** | **2,734** `[2,731–2,735]` | **102.8** | **3.158×** |
| `xcript` — transcription of the shipped hook | 2,719 `[2,701–2,721]` | 102.8 | |
| `hooks` | 1,036 `[1,035–1,040]` | 46.1 | |
| `reagent` | 866 `[842–866]` | 31.5 | 1× |

−769 B `[767–789]` and −23.0 objects `[23.0–23.1]` per read, −22% — the term
reproduced to the byte against the 769 B `[765–793]` / 23.0 obj this page
published pre-fix.

**The term is gone from the shipped hook, and the fidelity control is how you
know.** `uix − xcript` is now 15 B (0.545%) and 0.05% on objects, both inside the
pre-declared 3% band; before the fix that same subtraction against the
value-returning body was the whole 769 B. The prediction this page made — 2,720 B
`[2,698–2,722]` / 102.8 obj / 3.14× — was the transcription's number, and the
transcription came back at 2,719 B, inside its own predicted range. The shipped
arm sits 15 B above it, which is the ~0.5% offset the shipped arm has always
carried against its copy (pre-fix: 3,501 vs 3,489, 0.33%).

**Blast radius.** `make-react-spine` is shared by three substrates —
`re-frame.adapter.uix` (first-class, shipped), `re-frame.freehand.substrate`, and
`re-frame.ui.substrate`. One change, three beneficiaries.

**Correctness.** The fallback stopped being a live re-read. That was measured to
be a real defect, and it has since been repaired; this section records both.

* React's `useSyncExternalStore` calls `getSnapshot` again after `subscribe`
  returns, and by then `subscribe-fn` has published the committed reaction, so
  that call reads the **live** one and any app-db write landing between render
  and commit is detected. It is React's own mount path that guarantees the
  order — `subscribeToStore` is pushed as a passive effect **before**
  `updateStoreInstance`, and passive effects run in push order — and
  `assert-use-subscribe-render-phase-reaction-not-retained` reds if a React
  upgrade ever reorders them.
* **This page originally claimed what was given up was "narrower than the live
  re-read" — a corrective re-render one commit later rather than before the
  commit. The merged-PR audit rejected that, and measurement agrees.** A value
  frozen at render time compares equal to itself, so React's **pre-commit**
  store-consistency check became a no-op *by construction*: on a concurrent lane
  the torn render was no longer discarded, it **committed**. Observed at the
  first commit, on a real browser host, cold, with an unmoved control beside it:
  first commit `{:dom "g=0", :db 1}` — the DOM carrying the render's value while
  app-db had already moved. That is a layout-visible, paint-eligible stale
  commit, not delayed bookkeeping, and it is the shape rf2-so3io / rf2-anmdr
  price (a panel mounting as a permission drops).
* **Repaired, still without retaining a handle** (rf2-2rtt6.13, after the audit).
  The pre-commit read prefers the reaction the hook's unspent **escrow token**
  is already holding — live by construction, since that +1 is what keeps the
  entry tenanted for the commit to adopt (rf2-2rtt6.25). Nothing new is
  retained: the token's hold predates the repair and ends at adoption or at the
  macrotask horizon, never at the component's lifetime, and the memo slot and
  `get-snap`'s closure still hold a value and no handle. The same row now reads
  `{:dom "g=1", :db 1}` — React sees the movement, discards the torn render, and
  the first commit is fresh. Pinned by
  `assert-use-subscribe-render-to-commit-window-first-commit`.
* On a **blocking** lane (`root.render` on the default lane — every shipped
  consumer's normal configuration) React pushes no pre-commit store-consistency
  check at all, so the render's value is the committed value whatever
  `getSnapshot` says. That row is unchanged by any of this and is pinned too, so
  a React release that starts checking blocking lanes shows up as a failure.
* Every source `get-snap` can return is `Object.is`-stable across back-to-back
  calls — a memoised reaction's value or a frozen one — which is what React's
  "the result of getSnapshot should be cached" rule requires.
* The key-change path (rf2-naz09e) is unchanged in shape: the memo is keyed on
  `stable-key`, so a key change recomputes the snapshot for the **new** target and
  the key guard still rejects the stale `committed-ref`. If anything it is now
  *closer* to the Reagent substrate it is defined against, whose render-phase
  value is likewise read once, in render.

**What it does not fix, and the witness says so unchanged.** Two reactions are
still *built* per read on a first mount — the after-run's witness is `commits`
1.00N, `rebuilt` 1.00N, `bodyRuns` 2.00N on UIx against 0/0/1.00N on Reagent,
twice over disjoint cells, exactly as before. Retention was never what made the
builds two. Removing the double build is rf2-2rtt6.14, **ruled ADOPT on
2026-07-31** as a hook-scoped provisional hand-off (escrow token + macrotask reap
+ commit-time adoption), implemented by rf2-2rtt6.25 on these same lines and
sequenced after this change.

**Rejected alternative.** Making the fallback live *without* retention by having
`get-snap` **re-subscribe** per call — `subs/subscribe-once`, or a balanced
`subscribe`/`unsubscribe` round trip — is unsafe. On a miss each call builds a
fresh reaction with a fresh memo cell, so a sub returning a collection yields a
fresh, non-`Object.is` value on every `getSnapshot`, which is React's documented
infinite-render-loop condition. The repair above is not that: it *reads a
reference something else already owns*, so it cannot build anything, and it
falls back to the frozen value precisely when there is nothing live to read.

**How the change is pinned.** `assert-use-subscribe-render-phase-reaction-not-retained`
(`implementation/core/test/re_frame/adapter/react_shared_suite.cljs`, run by the
UIx DOM suite) asserts the property retention itself cannot expose: **every deref
the spine performs, at any point in the component's lifetime, targets the reaction
tenanted in the sub-cache at that moment.** Retention had a cause, and the cause is
observable — the handle survived because `get-snap` closed over it, which is
exactly what made the spine deref an evicted reaction. Verified load-bearing
against the pre-fix spine, where it reds with the deref log
`[{:gen 1 :tenant? false} {:gen 1 :tenant? false} {:gen 2 :tenant? true}]`: two
pre-commit reads of the evicted handle, then React's post-subscribe `getSnapshot`
call landing on the committed one — the ordering the fix depends on, observed on
the code that predates it.

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
transcribed-but-unablated arm reproduces the shipped hook. Pre-fix: shipped `uix`
3,501 `[3,499–3,503]`; transcription `xcript` 3,489 `[3,488–3,492]` — **0.332%
apart**, inside the 3% band the driver declares before it measures. After the fix,
with `xcript` transcribing the new shape: `uix` 2,734 `[2,731–2,735]` against
`xcript` 2,719 `[2,701–2,721]` — **0.545% apart**, still well inside the band. The
band is an order of magnitude below the 22% term being resolved, so it cannot
manufacture the finding. **The object count is checked the same way** and lands
0.045% apart pre-fix, 0.050% after, which matters because the object count is this
page's headline and bytes are the cross-check.

This control is also what makes the after-run's central claim checkable rather
than asserted. `uix` reproducing the *value-returning* body to 0.545% is the
statement "the shipped hook no longer retains the reaction" in the instrument's
own terms — the same subtraction that carried 769 B before the fix.

Note which clause carries it: the two arms' *ranges do not overlap* — at four
rounds these ranges are a few bytes wide — and it is the pre-declared 3% band
that passes them. That is the clause's purpose, and it is why the band is
declared in the driver rather than chosen afterwards.

**Order guard.** `order_guard.schedule` rotates and reflects with the round; even
rounds forward, odd rounds reversed, both reported separately. Its self-test (**11
checks**, including the two repairs PR #7267 landed: the k=2 ordering degeneracy,
and refusal when samples are silently lost) runs before the bundle is built.
**Verdict: clean on both pages, no refusal**, before and after the fix.
Forward/reversed slopes, pre-fix: uix 3,501/3,501, xcript 3,489/3,490, noretain
2,710/2,721, hooks 1,036/1,038, reagent 866/866. After: uix 2,733/2,734, xcript
2,711/2,720, retain 3,488/3,490, hooks 1,036/1,038, reagent 866/854.

**Verification.** Every mount reads the DOM back against the boundary count the arm
should have produced: **0 unverified of 154 mounts** across both pages pre-fix,
**0 unverified of 154** after (119 on the uix page, 35 on the reagent page).

**Linearity.** r² ≥ 0.9999 on all four UIx variants for bytes and for object counts.
Reagent's is 0.9973 (bytes) / 0.9961 (objects) — visibly less linear, and its R=0
rung is the reason: a Reagent boundary shell costs 427 B / 12.1 obj against UIx's
~215 B / 5.2 obj. The after-run reproduces both: UIx arms 0.99996–0.99998, Reagent
0.99723 / 0.99606.

**Readers.** A = `Runtime.getHeapUsage().usedSize`; B = in-page
`performance.memory.usedJSHeapSize`; C = a full heap snapshot with every node's
`self_size` summed **and every node counted** by a streaming scan. A and B are two
doors onto one V8 counter and are not independent. C walks the object graph and is
the object-count reader — the signal this bead was filed about. A and C agree to
within 0.1% on every arm (e.g. uix 3,501 vs 3,503; reagent 866 vs 865).

---

## Cross-checks against the ladder

Different rung set (0/1/3/7 here, 0/1/3/7/20 there), different run, same box and
same collector design. **Both columns are pre-fix**, which is the only way the
comparison means anything — the ladder was measured against the retaining spine,
and so was this page's `retain` arm.

> **`3,552 B/read` IS NOT A LIVE GATE LINE, here or anywhere.** It is the
> ladder's reading of a spine that **no longer ships**, and it appears in this
> section only because a pre-fix figure is the correct partner for a pre-fix
> figure. Two production landings postdate both columns: `rf2-2rtt6.13`
> (PR #7304, `9df5094816`) stopped retaining the disposed render-phase reaction,
> worth **−769 B per unique query key** and so −769 B *per read* in the ladder's
> distinct-query regime; `rf2-2rtt6.25` (PR #7305, `f784ab0adb`) landed the
> hook-scoped provisional hand-off, so a cold read builds one reaction instead
> of two **under a forced synchronous commit** (2N on the shipped bare
> `createRoot().render` path — audit of PR #7305, open on `rf2-2rtt6.25`).
> **The live UIx per-read red-zone is `2,935 B/read` [2,852–3,055] on
> the P0 bench instrument** (Mike's option (a) ruling of 2026-07-31, executed by
> `rf2-e3flf`), stated in
> [validation.md](../validation.md#the-per-read-gates); the same tree reads
> **`2,783 B/read` on the ladder's instrument**, and the ~5% between the two
> harnesses is unexplained, so **a margin under 5% is instrument-limited rather
> than cleared**.

| | this page | `rf2-2rtt6.5` ladder | agreement |
|---|---:|---:|---|
| UIx bytes/read — **pre-fix spine, superseded as a gate** | 3,501 | 3,552 | 1.4% |
| UIx objects/read — pre-fix spine | 125.8 | 128.4 | 2.0% |
| Reagent bytes/read *(neither landing goes near the ratom path)* | 866 | 943 | 8.2% |
| Reagent objects/read | 31.5 | 35.8 | 12% |
| UIx/Reagent ratio — pre-fix spine | 4.04× | 3.77× | |
| UIx shell ÷ Reagent shell | 266 ÷ 589 = 0.45× | 0.49× | |

**The same cross-check on the shipping spine, and it holds.** This page's
post-fix `uix` arm measured **2,734 B/read** [2,731–2,735]; the ladder's
corresponding figure is its own slope less `.13`'s per-read term,
`3,552 − 769 = 2,783 B`. The two sit **49 B apart — 1.8%**, against the 1.4%
the pre-fix pair agreed to. **The agreement survives the landing**, which is
what a cross-check between two instruments is for:

| | this page *(measured post-fix)* | `rf2-2rtt6.5` ladder *(derived)* | agreement |
|---|---:|---:|---|
| UIx bytes/read, **shipping spine** | **2,734** | **2,783** | 1.8% |

The ladder column is its **corrected** publication: `rf2-2rtt6.5`'s fit had
included the sub-free R=0 anchor it promised to exclude, and refitting over
1/3/7/20 alone moved it from 3,550/942 to 3,552/943 and its object counts from
128.5/36.2 to 128.4/35.8 — **all four of those figures being readings of the
pre-`.13` spine.** Every agreement above holds to within 0.1 percentage point of
what it was, which is the useful thing to know: **this page never depended on
the defect in that one.**

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
argument, and this page prefers a measurement. Re-run on the repaired
instrument (blobs in the header; authored as `fa09c5ad7a`), four rounds and two
snapshot rounds per page, **exit 0 under all six new gates**:

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

* It does not claim the **3.158×** that remains after the fix is acceptable, or
  that it is not. That is the P0 bar's question.
* It does not claim the spine's derived-value representation should be flattened.
  It measures that the representation costs 1.94× Reagent's for the same job and
  stops there.
* It does not price the CPU of the double build, only its retention. The CPU
  is now priced by rf2-2rtt6.15 on
  [its own page](coldmount-double-build-priced.md), against the mount
  red-zone, in the same runs.
