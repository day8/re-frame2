# The read path, measured on the host re-frame2 ships to — rf2-x0fe2

2026-07-28 · CLJS / node 24.13.0 · V8 13.6.233.17-node.37 · `:advanced` +
`goog.DEBUG false` · pointer compression **OFF**

Instrument:
`implementation/core/test/re_frame/bench/read_attribution_cljs.cljs` — the CLJS
counterpart of `read_attribution.clj`, arm for arm.

It exists because every allocation figure previously published on this surface —
`rf2-21pck`, `rf2-mvqwe` (PR #7151), `rf2-j8ls2` / `rf2-ncjyt` (PR #7154) — is a
**JVM** figure taken with `ThreadMXBean/getThreadAllocatedBytes`. That was always
stated. It came to matter because the JVM decomposition's largest single term is
one that **does not exist in ClojureScript at all**.

> **This document carries no Freehand-versus-Reagent verdict.** That question is
> held pending an operator ruling. Nothing here measures the observation port;
> the subject is `@(subscribe [:q])` and its parts, and nothing else.

---

## Headline

**The JVM's top term is worth nothing here, and the term it was ranked above is
now three-quarters of the whole.**

`subscribe` on a cache HIT, n = 300 live entries, app-db held still. Net of
`NOOP`, per READ, p50 across 42 samples in 6 rounds. Both figures below are from
runs the arm-order guard passed in both orders; they agree to **0.1 B/read** on
every term.

| term | JVM B/call | share | **CLJS B/read** | share | verdict |
|---|---:|---:|---:|---:|---|
| the **ref-count attach** | 732–772 | 41% | **1224.9–1225.0** | **72.1%** | **holds — and takes the top row** |
| `require-current-frame!` (shipped) | ~0 | 0% | **264.1** | **15.6%** | **CLJS-ONLY — appears from nothing** |
| `call-with-frame-resolution` | 720–800 | 41% | **112.3** | **6.6%** | collapses to a seventh |
| — of which the **dynamic `binding`** | **~760** | **41–46%** | **0.1 ≤ floor** | **0.0%** | **VANISHES** |
| — of which the generation read | 16–40 | ~2% | 48.0 | 2.8% | holds |
| — of which the harness's own thunk | — | — | 64.0–128.1 | 3.8% | instrument, not product — and **bimodal**, see below |
| `(frame/frame id)` + `(get @cache k)` | 0–8 | 0% | 96.7 | 5.7% | appears, small |
| `frame-target->id` | 0 | 0% | 0.0 | 0.0% | free in both |
| **= `subscribe`, cache hit, no deref** | **1220–1364** | | **1698.1** | | |
| + the deref of a cached node | *not comparable — see below* | | **0.0–0.1 ≤ floor** | 0.0% | **free** |
| **= `@(subscribe [:q])`** | | | **1698.1** | | |

### The binding, three ways

| how | B/read |
|---|---:|
| **`N-BINDONLY`** — `(binding [registrar/*generation* gen] …)` standalone, **no thunk to elide** | **0.1** |
| **budget** — `N-CWFRRAW − N-CALLTHUNK − N-GENREAD`, i.e. `cwfr`'s whole cost accounted without one | **0.1** |
| **symmetric pair** — `N-CWFRBIND − N-CWFRGEN`, symmetry check passed | **0.0** |
| instrument floor (`NOOP`, the bare inner loop) | 0.053 |

All three are at or under the floor, so all three are **upper bounds, not
measurements**. The prediction was stated before the run and the CLJS macro
expansion is the reason: `binding` on a `^:dynamic` var compiles to `let` +
`set!` + `try`/`finally` restore. No threads, no binding stack, no `TBox`, no
`Frame` — nothing to allocate.

**So the JVM ranking is not the ranking a browser app pays.**

| rank | JVM | CLJS |
|---|---|---|
| 1 | the dynamic `binding` (~46%) | **the ref-count attach (72.1%)** |
| 2 | the ref-count attach (~32%) | **the ambient frame reader (15.6%)** |
| 3 | the throwaway frame value (~12%) | the frame + cache lookup (5.7%) |
| 4 | the eager error payload (~8%) | the generation read (2.8%) |
| last | — | **the dynamic `binding` (0.0%)** |

---

## The instrument, and what it is worth

Same method and same file family as `write_attribution.cljs`, deliberately —
there is no fifteenth instrument here. If no collection runs between two readings
of V8's used-heap counter, the difference is the bytes allocated in between.
Every sample is bracketed by an explicit `global.gc()` and sized to fit one
nursery; a sample whose counter fell is discarded and counted (**0 dropped, every
arm, every run**).

### The controls landed

Predicted-vs-measured at **every size**, not merely as a slope — printing only
the slope is how an arm reading exactly twice its own prediction went unremarked
for as long as it did (`rf2-l3jv4`).

| control | measured | predicted | error |
|---|---:|---:|---:|
| `DBL D=100` — `.slice()` of a packed double array | 848.3 | 848 | **+0.03%** |
| `DBL D=200` | 1648.6 | 1648 | **+0.03%** |
| slope, small pair (100→200) | 8.0029 B/double | 8.0000 | **+0.04%** |
| `DBL D=1000` | 8178.1 | 8048 | +1.62% |
| `DBL D=10000` | 86738.8 | 80048 | +8.36% |
| slope, large pair (1000→10000) | 8.7290 B/double | 8.0000 | +9.11% |

The JVM set hit +0.000% in 18 rounds; the bead asked this one to match that
standard or say plainly that it could not. **It matches to +0.03–0.04% in the
regime it is used in.** The large pair's +9% is V8's page-tail filler — an 87 KB
object wastes ~7% of a 256 KB page and that filler is genuine used-heap. It is a
LARGE-object effect and every arm measured here allocates small ones. The finding
stays open, exactly as it does in `write_attribution`.

The SMI pair is **not** checked against an asserted slot width, because its job is
to *read* the pointer-compression regime and predicting it would be circular. It
is checked against the double control at the same size:

| control | measured | ratio to `DBL` at same D |
|---|---:|---:|
| `SMI D=100` | 848.3 | **×1.0000** |
| `SMI D=200` | 1648.6 | **×1.0000** |
| SMI slope | 8.0031 B/slot | +0.04% off 8 B |

×1.0000 and 8.003 B/slot ⇒ **pointer compression OFF**, the Node default, read
off the process rather than assumed.

### `NOOP`, and the floor

The bead requires a NOOP arm that reads exactly 0, so that no subtraction is
load-bearing. `NOOP` here is the bare `keep!` loop — the inner-loop skeleton every
arm shares, with the arm's own body removed. It reads **16.0 B/call**, flat from
reps=256 to reps=4000, so it is a per-CALL constant and not a per-window one, and
it is **0.053 B/read** over 300 inner iterations.

It is not zero, so it is subtracted rather than ignored: every decomposition
figure above is **net of `NOOP`**, which is legitimate precisely because `NOOP` is
the skeleton every arm carries and it cancels in any arm-to-arm difference.

Every arm at or under the floor is quoted as an **upper bound**, never as a
measurement. Four are: `DEREF`, `N-BINDONLY`, `N-FLUSH`, and the binding itself.

Running each arm with `n = 300` inner iterations is what makes a ~0 B/read term
separable at all: it divides the per-call floor down by 300.

### Read-back — "N unverified of M"

Something `write_attribution`'s main plan does not do, added here: **every window
is verified.** Each arm advances the shared counter exactly `per` times per call,
so after `reps` calls the counter must have moved by exactly `per × reps`. An arm
that was dead-code-eliminated, short-circuited, or took a branch it was not meant
to take cannot pass silently.

**0 UNVERIFIED of 1638 windows**, every run, every configuration.

### The three faults fixed on 2026-07-27 are designed out, not inherited

1. **A shared counter clobbered by a control** (`rf2-xu0ma`, PR #7229). `keep!`
   and `reset-sink!` are the only writers of `sink`; `reset-sink!` writes the
   literal Smi `0` and runs **outside** the measured window. No arm can charge
   another, and arithmetic cannot walk the counter out of Smi range mid-plan.
2. **A polymorphic control site** (`rf2-l3jv4`, PR #7230). The double and SMI
   `.slice()` factories are character-for-character identical *on purpose* and
   must not be merged.
3. **The arm-order guard** runs first and exits 2 on refusal. It refused. See
   below.

---

## Three subtractions that look right and are not

All three are on record because each one, published, would have been a *precise
wrong number* — the failure mode this harness family exists to catch. Two of them
were caught **by this harness disagreeing with itself**.

**(a) The JVM harness's own subtraction.** `read_attribution.clj` isolates the
binding as `S3-CWFR − S2-TGTID − N-CWFRNOG`, where `N-CWFRNOG` is `cwfr` on a
`nil` target. But a nil target **short-circuits** `frame-resolution-generation`,
so that difference also contains the generation read. On the JVM the binding was
~760 B and the generation read 16–40 B, so the conflation was 2–5% and invisible.
Run here it yields **48.2 B/read** — which is `N-GENREAD` (48.0 B/read) wearing
the binding's name. Published, the answer to this bead would have been "the CLJS
binding costs 48 B/read". It costs nothing.

**(b) The obvious repair.** Re-spell `cwfr`'s body inline and subtract. Measured
**64.1 B/read** against standalone `N-BINDONLY`'s 0.1 — because
`call-with-frame-resolution` takes a **thunk** and its caller allocates a fresh
closure per call that *escapes*, while an inline re-spelling allocates one that
does not, and V8 elides what it can prove never escapes. That subtraction prices
the closure. Same error as (a), opposite sign. Confirmed directly:
`N-CALLTHUNK` (one escaping thunk and nothing else) reads **64.0 B/read** in its
settled mode — and `N-NEWFN`, which is nothing *but* a closure, reads the same,
which is what pins that 64 B on the closure rather than on `cwfr`.

**(c) The symmetric pair itself, when V8 compiles the two halves differently.**
The repair for (b) was a symmetric pair: two sibling functions of identical arity
and shape, each handed a thunk the arm allocates fresh, differing in exactly one
token — `(binding [registrar/*generation* gen] (thunk))` against `(thunk)`. At
n=300 both halves allocate their thunk (112.1 B/read each) and the difference is
0.0, correct. **At n=30 V8 elides the no-bind half's thunk (48.0) while the
binding's `try`/`finally` blocks the same elision on the bind half (112.0), and
the difference reads 64.0 B/read — one whole closure — as if it were the
binding.**

A paired control is only symmetric while both halves are *compiled* alike, and
writing them alike does not guarantee that. So the harness now prints an explicit
**symmetry check**: the no-bind half's thunk must agree with `N-CALLTHUNK`, or the
pair is declared not quotable. It correctly passes at n=300 and refuses at n=30.

**And it is why the PRIMARY figure is `N-BINDONLY`** — the one arm that contains
no thunk, so there is nothing for escape analysis to move, and no subtraction at
all.

Fidelity of the re-spelling is **checked, not asserted**: `N-CWFRBIND` (calls the
harness's copy of `cwfr`'s body) must agree with `N-CWFRRAW` (calls the shipped
function). Measured delta **0.0 B/read**, both 112.1.

---

## The guard refused, and it was right every time

The arm-order guard partitions every arm's per-round figures by what ran before it
*and* by where in the run it sat, and refuses the run if either factor separates
an arm.

**Refusal 1 — a defective arm of mine.** The forward plan refused on
`N-CALLTHUNK` in three configurations (6 warm windows, 12 warm windows, 8 rounds)
with **bit-identical values every time**, while the reversed plan passed. Bit-
identical across configurations means it is not a settling curve and more warm-up
cannot touch it (contrast `rf2-tb345`). `call-thunk` was a private one-liner;
Closure inlines those, so the thunk never escaped, so V8 removed it and the arm
read the floor — measuring **nothing** — except in some windows, where it read
19,219 B/call: exactly 64 B per inner iteration, one closure each. The same
~19,300 B/call step appeared as the high end of `N-CWFRBIND`, `N-CWFRGEN` and
`N-CWFRRAW`'s ranges simultaneously.

The repair was to the **arm**, not the guard: the thunk is now stored where
nothing can prove it dead. It escapes for real and the arm reads 64.0 B/read.
**The guard's tolerance was never touched.**

**And that repair went as far as a repair can go (`rf2-ktrvw`).** It removed the
*elision*; it did not remove a residual ~64 B/read step, and nothing done to the
arm could have. `N-NEWFN` creates one closure per inner iteration and does
nothing else at all — no callee, no binding, not even a call — and it carries
the same step:

| arm | settled | high | step | = closures |
|---|---:|---:|---:|---:|
| `N-NEWFN` | 64.0 | 128.1 | 64.1 | 1.00 |
| `N-CALLTHUNK` | 64.0 | 128.1 | 64.1 | 1.00 |
| `N-CWFRNOG` | 64.0 | 128.6 | 64.6 | 1.01 |
| `N-CWFRBIND` | 112.0 | 176.4 | 64.3 | 1.01 |
| `N-CWFRGEN` | 112.0 | 176.6 | 64.6 | 1.01 |
| `N-CWFRRAW` | 112.0 | 176.5 | 64.5 | 1.01 |

B/read net of `NOOP`, reversed plan; the forward plan agrees at 64.1–77.4. The
step is the **same absolute size** on arms whose totals differ by 75%, so it is
additive and belongs to **closure creation**, not to any arm's subject.
`N-CALLTHUNK − N-NEWFN` is 0.0 B/read against a prediction of 0, so `call-thunk`
itself costs nothing beyond the closure.

So a closure on this runtime is **two numbers, 64 B and 128 B, chosen per
window with nothing in between** — and an arm whose subject is a closure cannot
be re-shaped out of it without ceasing to measure the thing. A thunk-dominated
arm is quotable as its **range**, and as an upper bound at the high mode, never
as a p50 alone.

It also explains why the forward plan now passes where it once refused: the step
appears in the forward plan and the reversed one **alike**, so it is not a
position effect, and the `phase` factor adjudicates it by luck — it fires when a
third lands wholly in one mode and passes when a third straddles both. That is
the house rule working correctly on a factor that does not describe the
phenomenon. Widening the tolerance would hide a real bimodality and narrowing it
would refuse at random, so the guard is **still** left alone. A later green run
is not evidence the effect went away.

**Refusal 2 — the instrument's upper working limit.** An `RA_N=1000` run was
refused, and correctly: at n=1000 the arms are large enough that calibration
bottoms out at reps = 1–2, and the controls stop landing (`SMI D=100` read 361.7
against a predicted 848). **n=1000 is outside this instrument's working regime and
no figure from it is quoted here.** The tail of `RC-ASSOC`'s scaling is the only
thing that run suggests, and it is left as a suggestion.

**What was NOT done:** the forward plan's refusals were not tuned away, and the
25% tolerance was not widened. Both refusing arms are non-headline —
`N-CALLTHUNK` is a control, and `N-CWFRNOG` is the arm this document explicitly
says *not* to subtract with. Every headline term passed the guard in both orders.
Filed as `rf2-ktrvw`, and **closed by the bimodality finding above**: the arms
were not defective, they were measuring something that has two values, and the
answer was to say so rather than to repair an arm or relax a guard.

None of the headline terms is exposed to it. The binding figure is `N-BINDONLY`,
which allocates no closure at all; the budget and symmetric-pair reconstructions
each difference two arms carrying exactly one, so the mode cancels.

**Added after this study — the floor-aware quote (`rf2-hydpy`).** The rf2-2ix22
levers later optimised six of these arms to the instrument's own floor, and the
guard then refused every one of them by phase — an identical ratio across arms
with nothing else in common, which is a per-**window** floor moving mid-run, not
any arm's subject. The harness now answers that refusal instead of only
inheriting it: each refused arm is re-measured across a reps ladder derived from
its own calibrated reps (the `rf2-tmzie` sweep — a real per-call cost is
rep-independent in B/call, a per-window floor falls as the window grows), and a
refusal every arm of which collapses by at least half the ladder span is
downgraded to **certified at the floor**: each arm quoted as its worst round's
upper bound, never as a p50, and the run exits 0. Anything the sweep cannot
attribute — flat, bimodal, unverified, or an `:unchecked` plan defect — keeps
exit 2. The guard itself, its 25% tolerance and its refusals are untouched; the
attribution can only ever weaken a claim (measurement → bound), never lift one.

### Runs

| run | n | order | guard | read-back |
|---|---:|---|---|---|
| `R1-n300-fwd` | 300 | forward | **reportable** | 0 unverified of 1638 |
| `R2-n300-rev` | 300 | reversed | **reportable** | 0 unverified of 1638 |
| `R3-n30-fwd` | 30 | forward | **reportable** (pair refused by the symmetry check) | 0 unverified of 1638 |
| `R4-n1000-fwd` | 1000 | forward | **REFUSED** — controls do not land at reps 1–2 | 0 unverified of 1638 |

---

## Which JVM terms vanish, which shrink, which hold

### VANISHES

**The dynamic `binding` of `registrar/*generation*` — JVM ~760 B/call (41–46%),
CLJS ≤ 0.1 B/read (0.0%), at the instrument's floor by three independent
routes.** JVM `push-thread-bindings` builds a hash-map for the pair, assocs a
fresh `TBox` into the thread's binding map (itself a path copy) and allocates a
`Frame`, then pops. CLJS has neither threads nor a binding stack.

**The deref of a cached node — 0.0–0.1 B/read, at the floor.** Not strictly a
vanishing JVM term, because the two are **not the same measurement**: the JVM
plain-atom adapter's derived value *recomputes on every deref*, while the CLJS
spine caches and recomputes only when a source moved. The JVM `DEREF` arm prices a
read whose value moved; this one prices a genuine cache hit, and a cache hit is
free. The cost of a read whose value moved is the *write* leg, and that is
`write_attribution.cljs`'s subject, not this one.

### SHRINKS

**`call-with-frame-resolution` as a whole — JVM 720–800 B/call (41%), CLJS
112.3 B/read (6.6%).** With the binding gone what remains is the generation read
(48.0 B/read), the harness's own escaping thunk (64.0 B/read — instrument, not
product), and a late-bind flush consult at the floor.

### HOLDS

**The ref-count attach — JVM 732–772 B/call (41%), CLJS 1224.9–1225.0 B/read
(72.1%).** It does not merely hold; with the binding gone it is nearly
three-quarters of `subscribe`'s cache-hit allocation.

| part | CLJS B/read |
|---|---:|
| `RC-SWAPID` — `swap-vals!` machinery alone | 152.1 |
| `RC-GUARD` — + the identity guard | 256.4 |
| `RC-ASSOC` — the outer HAMT path copy alone | 785.9 |
| `RC-EASSOC` — the entry-map copy alone | 168.1 |
| **irreducible persistent write** (`ASSOC` + `EASSOC`) | **954.0** |
| `RC-CAND` — the **shipped** attach, standalone | 1218.2 |
| `RC-ATTACH` — the retired `update-in` spelling | 2263.1–2264.4 |
| **what `rf2-j8ls2`'s rewrite saves** (`ATTACH − CAND`) | **1044.9–1046.3** |

`RC-CAND` standalone (1218.2) brackets the by-subtraction attach (1224.9) to
**0.5%**, so the ladder and the standalone arm agree.

**The same conclusion the JVM reached survives the change of host, with the shares
moved.** ~954 B/read of the attach is what a persistent one-key change *is* — copy
the path to the entry, copy the entry. A mutable ref-count would not be an
optimisation of that; it would be a different lifecycle model, and it is not
proposed here any more than it was there.

Scaling is logarithmic, as a HAMT must be: `RC-ASSOC` reads **632.6 B/read at
n=30 and 785.9 at n=300** — +24% for a 10× cache.

`rf2-j8ls2`'s rewrite is worth **1045 B/read** here against 224–248 B/call on the
JVM: the same fix, roughly four times the payoff on the target runtime.

### APPEARS — a CLJS-only term nothing in the JVM profile could see

**`require-current-frame!` — 264.1 B/read, 15.6%, the second-largest term.**

On the JVM `frame/resolve-current-frame` is a plain `*current-frame*` var read. On
CLJS it consults the `:adapter/current-frame` late-bind hook so the React-context
tier is live — a hook lookup plus a routed-hook chain bottoming out in
`frame/current-frame`. The paired arms price it:

| arm | B/read |
|---|---:|
| `S0-VAR` — `(frame/current-frame)`, the dynamic-var tier alone (all the JVM does) | 0.0 ≤ floor |
| `S0-SCOPE` — `(frame/resolve-current-frame)`, the shipped CLJS reader | 264.0 |
| `S1-CURFRM` — + the `or` and the untaken require | 264.1 |

**Every ambient `subscribe` pays 264 B/read for the React-context tier, and the
JVM decomposition is structurally incapable of showing it** — the `:clj` branch
does none of this. Filed as **`rf2-f70iq`** (P2), measurement only; where inside
the hook route the bytes go is not yet attributed and no change is proposed until
it is.

**`(frame/frame id)` + `(get @cache k)` — JVM 0–8 B/call, CLJS 96.7 B/read.**
Small, but it is 0% on the JVM and 5.7% here.

### The retired spellings, as paired controls

Both retirements were correct, and both are worth **more** here than the JVM said.

| retirement | JVM | CLJS | error |
|---|---:|---:|---:|
| `rf2-a8bw0` — the eager error payload (`S1-EAGER − S1-CURFRM`) | 136 B/call | **216.5 B/read** | |
| `rf2-8gb3t` — the throwaway frame value (`N-CWFRWRAP − N-CWFRRAW`) | 200.0 B/call | **519.9–520.0 B/read** | |
| … independently predicted by `N-RESTGT` | 200.0 | **520.0** | **0.0%** |

`rf2-8gb3t`'s paired control reproduces on CLJS exactly as it did on the JVM: the
wrapper's cost, measured two independent ways, agrees to **0.1 B/read**.

---

## Which beads were ranked off JVM numbers CLJS does not support

**`rf2-k9iu4` — the binding ruling. CLOSED. This measurement confirms the close;
it does not disturb it.** The bead was ruled "no change; the wrapper stays" on the
strength of the CLJS macro expansion plus a null-result Node *timing* benchmark.
This is the *allocation* measurement that ruling anticipated, and it agrees:
**≤ 0.1 B/read, three independent ways, at the instrument's floor.** Per its own
close reason a ~zero figure here does not reopen it. **It stays closed**, now on
measured rather than derived grounds.

**`rf2-8gb3t` — the throwaway frame VALUE. CLOSED (PR #7157). The CLJS figure
supports the retirement more strongly than the JVM one did** — 520 B/read against
200 B/call, predicted to 0.0% by an independent arm. Nothing to reopen; the seam
is already gone. Recorded because the bead quotes the JVM figure and the CLJS one
is 2.6× it.

**`rf2-ezwnl` — the registrar `[kind id]` key vector. CLOSED, with its reopen
condition keyed to this bead. Recommendation: DO NOT REOPEN — but the number is
much larger than the JVM said.**

| arm | JVM | CLJS |
|---|---:|---:|
| `registrar/lookup`, generation-routed (`N-LOOKGEN`) | 138.3–138.5 B/call | 488.5–524.7 B/read |
| `registrar/lookup`, registrar-atom (`N-LOOKATOM`) | 40.0 B/call | 80.3–80.4 B/read |
| **the key vector (difference)** | **~98 B/call** | **408.1–444.3 B/read** |

**4.5× the JVM figure.** But the reopen condition asks whether the lookup site is
"materially on the target-runtime profile", and for the **read path the answer is
no**: `subscribe`'s cache-HIT branch performs no `registrar/lookup` at all — it
goes ambient reader → `frame-target->id` → `cwfr` → `frame` → cache `get` →
attach. The lookup runs per dispatch, per fx, per cofx and per subscribe **miss**.
Those are real paths and 408–444 B/read is a real number on them, but **this
measurement is of the read path and does not price them.** Reopening on this would
be reopening on evidence the condition does not actually ask for. The figure is
recorded so a future dispatch- or miss-path measurement starts from it.

**The published JVM absolutes.** Every per-call byte count in `rf2-21pck`,
`rf2-mvqwe`, `rf2-j8ls2`, `rf2-ncjyt` and
`ai/findings/2026-07-27.subscribe-alloc-j8ls2.md` is a JVM figure. The correction
note that document already carries is **confirmed**: the ranking taken off that
profile is the JVM's cost structure, not the browser's. Its shares and paired
comparisons — arms measured the same way in one process — remain sound; the
**ordering of the terms does not transfer**, and neither do the absolutes.

---

## What a byte count here is, and is not

**Node ships V8 with pointer compression OFF and Chrome ships it ON** — read off
this process by the SMI control, not assumed. A tagged slot is 8 bytes here and 4
there, and CLJS persistent collections are almost nothing but tagged slots, so a
Chrome absolute for a structure of that kind is roughly **half** a Node one; the
`write_attribution` study measured that ratio directly at 1.87× on a
pointer-dominated structure.

**Shares, ratios and slopes transfer. Absolute byte counts do not.** Every figure
in this document is labelled CLJS / node V8. None is a JVM figure and none is a
Chrome figure.

---

## Reproduce

```bash
cd implementation
npx shadow-cljs release ui-bench --config-merge \
  '{:main re-frame.bench.read-attribution-cljs/-main
    :output-to "out/read-attribution-cljs.js"
    :compiler-options {:optimizations :advanced :infer-externs :auto
                       :closure-defines {goog.DEBUG false}}}'
RA_N=300 node --expose-gc out/read-attribution-cljs.js               # forward
RA_N=300 RA_ORDER=rev node --expose-gc out/read-attribution-cljs.js  # reversed
```

`shadow-cljs.edn` is unchanged — the same `--config-merge` route onto the existing
`:ui-bench` build id that `write_attribution.cljs` takes. Exit code 2 means the
arm-order guard refused and nothing in that run may be quoted.
