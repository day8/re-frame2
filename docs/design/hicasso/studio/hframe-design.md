# `h/frame` — the author-facing frame read, and what `rf2-2rtt6.122` did to its premise

**Bead**: `rf2-2rtt6.118`. **Status**: designed, adversarially reviewed,
synthesized, and **ruled BUILD** by the operator. This page is the tracked
record of that ruling; the design pass and the attack that corrected it were
working documents.

**Why this page exists.** The ruling lived only in a bead comment and in two
local-only working files. The `[:>]` escape programme's designs are tracked here
in the studio tree, and the reasons are the same: the *rejections* are the part
that has to survive, because a rejected candidate that leaves no record gets
re-proposed. Read this with `raw-escape-spec.md`, which owns the `[:>]` ruling
this one hangs off.

---

## 1. The verdict

**BUILD `(h/frame)`** — a plain function, legal during a boundary's render, that
returns the current frame **id keyword**. Loud error outside a render extent. Not
a tracked read.

**The carry spelling is composition with the platform keystone**, not a new
primitive:

```clojure
(rf/capture-frame (h/frame))
```

Hicasso contributes the deterministic frame **read**; core keeps `capture-frame`
as what Spec 002 calls *"the ONE public carry primitive"*. The two-step spelling,
against the adapters' one-step `(rf/capture-frame)`, is the taught asymmetry, and
it has a one-sentence reason: **ambient frame lookup is what Hicasso's stricter
body discipline withdraws.**

The motivating author is precise, and the design is scoped to them: **someone at
a foreign edge who must hand a dispatching closure to a caller they do not
control** — an SDK attach ref, a `[:>]` value-first callback, a `defhost`
callback slot. Hand-written async *work* is not that person's problem and is not
this primitive's job; see §5.

---

## 2. What `rf2-2rtt6.122` changed, and why the verdict survives it

**This is the one part of the ruling that is now out of date on the bead, and it
matters.** The design pass ran against a tree in which `rf2-2rtt6.122` had not
landed. The attack's FATAL finding — the one that corrected the design's premise
and was recorded as *strengthening* the verdict — was that `rf/capture-frame`
0-arity **did not throw** in a Hicasso body. It *accidentally worked* on the
dominant configurations, through a raw React-context read published by the UIx
and Freehand adapters, and the same one spelling produced **six** behaviours
across adapter, renderer and timing: correct, throwing, throwing on SSR, and
silently wrong-frame inside a render callback.

**`rf2-2rtt6.122` has since deleted that accident.** Core gained a refusal tier,
and the Hicasso arm fused it into `intent/with-frame` — the one binding every
Hicasso render extent already performs. The refusal detail the arm supplies says
so in its own words: *"It used to succeed silently under some adapters and throw
under others; now it refuses under all of them."*

The consequences for this design, in order of how much they matter:

1. **The K5 argument that carried the verdict is now spent.** The synthesis
   earned `h/frame`'s slot largely on *"it replaces a six-behaviour accident on
   the platform's own keystone with one deterministic spelling."* `rf2-2rtt6.122`
   already bought that. Anyone re-reading the ruling should not re-spend it.
2. **The residual argument is unchanged and is now sharper.** After the refusal,
   a Hicasso body has **no ambient route to its own frame at all** — the 0-arity
   keystone refuses deterministically rather than working by luck. The case the
   design was built for, a **reusable view mounted under N frames**, could
   previously capture by accident on a UIx- or Freehand-hosted page. It now
   cannot, by construction. `rf2-2rtt6.122` widened the gap `h/frame` closes.
3. **The seam is confirmed, and it is not the one a casual reading suggests.**
   The refusal withdraws the ambient **find**, never the **carrying** — so
   `with-frame` and `{:frame id}` both still work inside a body. But neither is
   the author-facing answer here, because both *presuppose knowing the frame id*,
   which is exactly what the motivating case cannot know. The load-bearing fact
   is narrower: **`capture-frame`'s 1-arity never consults the resolver at all**,
   so `(rf/capture-frame (h/frame))` is refusal-immune by construction.
   `(h/frame)` supplies the id that the carrying spellings presuppose.
4. **`(h/frame)` itself is untouched by the refusal.** It reads the arm's own
   `intent/*frame*` binding directly and never enters core's resolution chain.

**Verdict after re-grounding: BUILD stands**, on (2) rather than on (1).

---

## 3. Why the read is `intent/*frame*`

Not `rstate.frame`, and the difference is load-bearing. The `rf2-2rtt6.74`
render-position work already rebinds `intent/*frame*` to the **supplying**
boundary when a foreign component invokes a render callback, while `rstate.frame`
is nil there — the foreign render runs outside the arm's own render pass. So
reading the binding gives the *owner's* frame inside a render callback for free,
and gives it immune to tree position, adapter, renderer and timing.

**There is already an internal consumer doing exactly this.** `route-link` reads
`intent/*frame*` to capture the render frame into its navigate vector, for the
stated reason that *"a browser click fires long after the render's dynamic extent
has unwound, so the frame must travel as data."* That is the `h/frame` use case,
written internally, in the same file family. The design exposes a door the arm
already walks through.

---

## 4. Not a tracked read — and it must not be

Reads become collector edges because they are **sub-keys**. The frame is not one:
it is render-constant per boundary, resolved once and bound ambiently. `(h/frame)`
is one dynamic-var read. It appends nothing to the collector scratch, registers no
edge, and takes no hook — so the ≤2-hook shell ledger is untouched, and the
frame-prop shell variant behaves identically because the same ambient is bound
either way.

Reactivity needs no edge: a frame change is a context change or a remount, and
React propagates context to consumers ahead of the memo comparator.

---

## 5. The rejections, with their reasons

These are the part that must survive.

**(b) `(h/bound f)` — a frame-capturing wrapper. REJECTED, in both readings.**

- *Returns a plain fn that dispatches `f`'s returned vector:* this is `h/fn`'s
  event contract **stripped of its position**, and HD-024's whole architecture is
  that **position selects the contract**. A `h/bound` output is an unmarked plain
  fn, so it crosses every boundary by identity — including into a declared
  `:render` slot, where it would then genuinely dispatch during a foreign
  component's render, walking past the `rf2-2rtt6.74` arming gate and its
  refusal. A positionless self-dispatching form launders a dispatch past every
  contract the grammar enforces.
- *Invokes `f` with a frame-bound dispatch argument:* this is a resurrection of
  `frame-bound-fn*`, which API-shrink #1 deleted from the facade precisely so
  `capture-frame` would be the one carry primitive. Re-opening a closed API
  decision to save one `let` binding.

**(d) Bind core's ambient (`frame/*current-frame*`) inside the body, or publish a
Hicasso `:adapter/current-frame` hook. REJECTED — and `rf2-2rtt6.122` has since
settled it in the opposite direction.** The ambient scope is **one door with three
consumers** — `capture-frame`, ambient `rf/dispatch`, and ambient `rf/subscribe`
all resolve through it, and no per-consumer discrimination exists. Widening it for
the capture would bless ambient `rf/subscribe` in a body: a render-phase sub-cache
mutation contributing zero collector edges, leaving a boundary that never
re-renders — HD-002 clause (a)'s forbidden class. `rf2-2rtt6.122` went the other
way and **refused** that door. This is the argument that `(h/frame)` is not
gratuitous API: it is the largest *safe* subset of the ambient contract.

**(e) Publish or bless the `:adapter/current-frame` hook. REJECTED.** Same
three-consumer door, and it is de facto the shipped accident — it *was* the
nondeterminism.

**(c) Docs-only — teach the existing recovery honestly. REJECTED as a complete
answer, but half of it ships regardless.** The honest-teaching half is not
optional: the guide's rows get rewritten fx-first under any verdict. What
docs-only cannot do is serve the reusable-multi-frame foreign edge, which has no
spelling without `h/frame`; taking it would mean Hicasso ships weaker than the
adapters on the spec's own keystone affordance, and the `[:>]` dev-warning's
promised "plain closure" door stays spellable only with app knowledge.

---

## 6. Layering — the honest asymmetry

**Hand-written async work belongs in the event layer, and the framework has
already fully built that answer**: an fx handler receives the frame id in its ctx,
`:dispatch-later` expresses delay as data with the frame threaded by the run, and
the async-effect pattern is canon. A view-layer `setTimeout` that dispatches is
mis-layered, and no primitive should be designed *for* it.

What the view layer does have is a distinct and legitimate case that is **not
async work**: handing a dispatch door to a foreign caller wired during render.
There, the thing captured is not time — it is **frame identity at the one moment
it is knowable**.

**One softening, stated rather than argued away.** Today mis-layered view async
fails loudly. Under `h/frame` it would *work* — the dispatch lands in the right
frame, merely from the wrong layer. That is parity with the adapters, and style is
the guide's jurisdiction, but it is real and it has no mechanical teeth. The
compensation is that every guide row putting `h/frame` on the page puts `:fx`
first. A reviewer who weighs this above the residual case lands on (c); the
operator weighed it and did not.

---

## 7. SSR

Server frames are per-request gensyms destroyed in the render's `finally`. The
body runs on Node through the same shell path, so `(h/frame)` answers the
per-request frame id and per-request isolation holds by construction.

**No leak into output.** Closures and event props do not serialize into the
rendered markup, and the payload deliberately omits the per-request id.

**One authorable hazard, and it is already instrumented**: rendering the id *into*
markup. Two same-process renders take two gensyms, so the existing
render-twice byte-comparison determinism witness catches it. The docstring should
state it — the value is process-local identity, carried in closures, never placed
in markup.

A capture that outlives its request is pinned to a destroyed incarnation and
refuses loudly; it cannot reach a same-id successor or another request.

---

## 8. Witnesses

Nine rows, re-grounded on the post-`rf2-2rtt6.122` tree.

| # | Row | Note |
|---|---|---|
| W1 | `(h/frame)` in a body returns the root's frame — under **both** shell variants (context shell and frame-prop shell) | |
| W2 | Two roots, two frames, side by side: a closure built with `(rf/capture-frame (h/frame))` in each fires from a timeout after render and lands in **its own** frame | the documented trap made green, plus the isolation law |
| W3 | Outside any render extent → the loud error, naming the layer-routed recovery | |
| W4 | Inside a `defhost` `:render` callback invocation → answers the **supplying** boundary's frame, not the invoker's | the `rf2-2rtt6.74` owner rule |
| W5 | Not a tracked read: a body reading `(h/frame)` alone registers zero edges; adding it to a reading body changes no read set; the hook ledger still counts 2 (and 1 on the frame-prop shell) | |
| W6 | StrictMode double-invoke: same value both runs, no additive effect | |
| W7 | The `[:>]` value-first door dispatches through a plain closure over the capture | **BUILT** once `rf2-2rtt6.103` shipped the escape — `rf2-zllp8`; see §8a |
| W8 | SSR: the server body answers the per-request id; render-twice stays byte-identical while the id stays out of markup, and goes red when a witness deliberately renders it | |
| W9 | An event-time read is not silently wrong: `(h/frame)` inside a `reg-event` handler body raises | the router binds core scope, not the arm's |

**W10 as synthesized is now obsolete and must be inverted before it is built.**
It read *"pin the accidental door: `(rf/capture-frame)` 0-arity on the live UIx
page currently succeeds"*, so that a future change to the accident would be seen
rather than silent. `rf2-2rtt6.122` **is** that change. The row must now pin the
opposite: 0-arity `capture-frame` in a body **refuses, by name**, under every
adapter. Building it as written would assert a behaviour the tree no longer has.

**W11 (SSR ambient) needs the same re-grounding.** It contrasted `(h/frame)`
answering against the ambient chain throwing server-side for a renderer-specific
reason. Post-refusal the ambient chain refuses on *both* sides, for one reason.
The contrast still holds; its explanation changed.

---

## 8a. What was actually built (`rf2-841vn`)

`(h/frame)` is `intent/hframe` in `front/intent.cljs` — the var's own namespace,
the first home §10 offers. Spelled `hframe` for the reason `lang/hfn` is spelled
`hfn`: the product name is qualified, and a bare `frame` would shadow the
`re-frame.frame` alias every namespace in this arm carries. No core edits, no
codec/shell/collector changes, no new React hook.

**W10 was inverted, and here is what the row now asserts.** `(rf/capture-frame)`
0-arity inside a body refuses, with `:operation :capture-frame`,
`:substrate :hicasso` and `:extent 'hicasso/boundary-render`. The *"under every
adapter"* half is settled **structurally rather than by trying adapters** — Spec
006 allows one substrate per process, so a row that tried three would still not
be the claim. Instead the `:adapter/current-frame` hook is wrapped with a counter:
inside a refused body it is reached **zero** times, and a control one call outside
the body proves it is reached there. The only part of the resolution chain that
differs between adapters is the tier that is withdrawn, so no adapter can change
the answer. The rows live with their siblings in `arm1/ambient_refusal_cljs_test`,
because a carry refusing is a fact about the refusal (`rf2-hnrww`) rather than
about this primitive.

**The error id is `:rf.error/hicasso-frame-outside-boundary`** — §9's
recommendation, matching the arm's `:rf.error/hicasso-intent-outside-boundary`.
Confirming it against Spec 009 was the instruction, and the answer is that **no
Hicasso arm error id is catalogued there**: `git grep 'rf.error/hicasso' -- spec/`
is empty, which is what HD-017's bench-lane residence implies. So no catalogue
row is owed, and the question reopens if and when the arm graduates.

**The provisional id did not leak.** `:rf.error/ambient-frame-refused` is written
in exactly one place in the new work — nowhere. The carry rows pin *"the same id
an ambient read gets"*, taken live from a sibling row, so `rf2-k0rbk`'s rename
touches the one pre-existing anchor rather than five new sites.

**W7 could not be built when this record was first written, and that was not a
shortfall.** The row needs the `[:>]` value-first door, and `front/codec.cljs`
said at the site that the raw escape *"stays unbuilt here, deliberately"* — so a
witness would have had to mint its own `[:>]`-shaped thing and would then be
witnessing the test's construction. It was filed as `rf2-zllp8` against whichever
bead shipped the escape.

**`rf2-2rtt6.103` shipped it, and W7 is now built** — the last row of
`arm1/hframe_dom_cljs_test`, `the-escapes-value-first-door-dispatches-through-a-plain-closure-over-the-capture`
(`rf2-zllp8`). ONE reusable view is mounted under two frames and writes a `[:>]`
crossing whose callback prop is a plain closure over `(rf/capture-frame
(h/frame))`; a foreign component keeps that closure and calls it from a DOM
handler of its own, from a macrotask, after every render extent has unwound. Both
crossings are clicked, deliberately: a capture that resolved one frame for both
would toggle that one frame twice and leave the pair reading `false`/`false`,
which one click alone would not catch.

**One correction to the row's own note.** It said W7 *"pairs with the
`rf2-2rtt6.103` dev-warning row"*, and what shipped is not a dev warning — it is
a pair of hard refusals. `[:>]` carries no declaration, so `raw-crossing`'s
roster is empty by construction, and an intent vector at an event-spelled prop is
`:rf.error/hicasso-host-undeclared-callback` while a marked `h/fn` at any prop is
`:rf.error/hicasso-host-unclaimed-callback`. That strengthens W7 rather than
changing it: the plain closure is not the recommended spelling among two, it is
the only one the door admits. Both refusals are asserted live at the top of the
row, so the premise is measured rather than cited — if the escape ever grows a
contract at a callback slot, W7 goes red before it mounts anything.

**One new finding, pinned but not endorsed.** The refusal withdraws the ambient
find and never the carrying — so an enclosing `rf/with-frame` still answers
inside a body, and `(rf/capture-frame)` there captures the **outer scope's**
frame while the boundary renders a different one. Core is correct per EP-0002:
that stamp *was* carried. But the same body's collector reads and lowered intents
all target the boundary's frame, so one body ends up with two frames selected by
which spelling the author reached for. `(rf/capture-frame (h/frame))` is immune,
which is the sharpest available statement of what §2(3) buys. The design question
is `rf2-nqj22`. It is also how the SSR rows were grounded: the shared test fixture
root-binds `*current-frame*` to `:rf/default`, so the SSR witnesses opt out with
`:ambient-frame nil` rather than measure the fixture.

**Spec 009's catalogue row for the refusal is behind the code** — it enumerates
`:operation` as `(:dispatch / :subscribe)`, and at least `:capture-frame` and
`:current-frame-id` also reach `require-current-frame!`. Filed as `rf2-e28wl`,
to sequence with `rf2-k0rbk`.

**The guide half is still owed.** §10's guide bullet was fenced out of the
implementation PR — a rename was landing in `draft-guide/` at the time — so the
fx-first troubleshooting row, the contract-force sentence, and the `[:>]`
"plain closure" spelling have not been written. The last of those is no longer
blocked — the escape shipped with `rf2-2rtt6.103` and W7 pins the mechanism — but
it was fenced out of `rf2-zllp8` for the same reason it was fenced out of
`rf2-841vn`: live work in `draft-guide/`. It stays owed.

---

## 9. What is NOT settled here — flagged for a ruling

*Written before the implementation. §8a resolves (1) and (4); (2) and (3) stand.*

1. **The error id.** The design pass proposed
   `:rf.error/hicasso-frame-outside-boundary`; the synthesis comment wrote
   `:rf.error/hicasso-frame-outside-render`. They are not the same string. The
   arm's existing sibling is `:rf.error/hicasso-intent-outside-boundary`, so
   **`-outside-boundary` is the spelling that matches the tree** and is the
   recommendation — but the implementer should have it confirmed rather than pick
   silently, because error ids are catalogued in Spec 009.
2. **`:rf.error/ambient-frame-refused` is PROVISIONAL.** `rf2-k0rbk` may rename
   it. Nothing in this design may be built on that spelling; `h/frame` does not
   depend on it, and the guide text that names it must be written after
   `rf2-k0rbk` settles.
3. **The name `h/frame` is unfrozen**, as `h/root!` is.
4. **A diagnostic gap `rf2-2rtt6.122` opened, which is arguably a small bug of its
   own.** The arm's refusal detail is written for the two doors it was aimed at —
   it advises reading through the collector and dispatching through an intent at a
   handler position. An author who trips the refusal via **`rf/capture-frame`** —
   the spec's keystone *carry* primitive, not a read and not a dispatch — gets
   advice that does not name their recovery. And no witness pins `capture-frame`
   under refusal at all: the landed suite covers ambient subscribe, ambient
   dispatch, carried `with-frame`, and the `{:frame …}` opt. `h/frame`'s landing
   is the natural moment to close both, since `(rf/capture-frame (h/frame))` is
   the sentence that advice should contain. Filed separately.

**`rf2-2rtt6.116` does not block this.** It concerns an `h/fn` at an *unclaimed*
`defhost` prop slot — a marked-callback question. `h/frame` returns a frame id
keyword and touches neither `h/fn` marking nor slot claiming; the candidate that
*would* have entangled with HD-024 position semantics, `(h/bound f)`, is rejected
in §5. The two can land in either order.

---

## 10. Implementer brief

- **Build** `h/frame` in the bench arm: reads `intent/*frame*`, returns the frame
  id keyword, raises on nil with the layer-routed recovery text. Roughly ten lines
  plus a docstring, in the var's own namespace or the collector-grammar home the
  implementer judges — the same file family as the planned `h/ikey` door.
- **No core edits.** The hook, `resolve-current-frame` and the hook router stay
  untouched. The ambient-door question belongs to the sibling bead.
- **No codec, shell or collector changes.** If a seam choice appears to need one,
  that is the signal it is the wrong seam.
- **Witnesses** W1–W9 above, plus the **inverted** W10 and re-grounded W11.
- **Guide** rides the same PR: the troubleshooting row rewritten fx-first, with
  `(h/frame)` taught as the *foreign-edge* affordance; the sentence with contract
  force that **ambient `rf/*` forms are non-contractual in Hicasso bodies**; and
  the `[:>]` dev-warning's promised "plain closure" door given its spelling.
- **Reject on sight**: any `h/bound` wrapper, any second carry primitive, any
  binding of core's ambient inside the render extent, any tracked-read or
  hook-consuming implementation.
