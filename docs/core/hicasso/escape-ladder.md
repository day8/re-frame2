# The escape ladder

Sooner or later a screen asks for something the interpreted model does not
express, and you go outside it. Hicasso expects that. Every escape is explicit
in source, visible to the tools, and reversible.

What it does not expect is that you take a rung without knowing which of two
questions you are answering, because the two have different rules and the wrong
rule gives the wrong answer confidently.

## Two different reasons to leave

**Interoperability.** There is no Hiccup spelling for what you need. A date
picker with forty props, a virtualiser with its own scheduler, a mapping SDK
that owns a DOM node — these are not slow Hiccup. They are absent Hiccup. The
alternative to the escape is not a slower version, it is nothing.

**Performance.** There is a Hiccup spelling, you have written it, and a measured
interaction misses a budget with the interpreted work named as the owner. The
alternative to the escape exists, runs, and is too slow.

Almost every mistake in this area is one of them treated as the other: a host
deleted because it did not recover 20%, or a native island built because a
screen felt heavy and nobody measured.

Because the reasons differ, so do the ladders. There are two, and you are on one
of them at a time.

## The performance descent

Five rungs; the code for rungs 3 to 5 is in [Islands](10-native-tier.md). They
are a descent: you take rung 4 having failed at 3, and rung 3 having failed at
2.

| Rung | What you write | Take it when |
| --- | --- | --- |
| 1 | Ordinary Hicasso — Hiccup, `h/sub`, event vectors | always; this is where every screen starts |
| 2 | Tuned Hicasso — boundaries, keys, read shape, chunking, windowing | a measured interaction invalidates too much work |
| 3 | A `defview` body returns a React element | Hiccup lowering is the measured owner |
| 4 | A React island — raw React or UIx, mounted through `h/defhost` | hooks, vendor internals, reconciliation, or per-frame local work dominate |
| 5 | A native screen | the surface is React-shaped from its first useful design |

Rung 2 is where most of this ends. A read moved down, a boundary drawn
differently, a list windowed — none of that leaves the interpreted language, and
the rungs below it all do.

## The interoperability descent

Two rungs, taught in [Interop](09-interop.md), and the order between them is not
about speed at all.

| Rung | What you write | Take it when |
| --- | --- | --- |
| A | A declared host — `h/defhost` | the component is foreign and you need its behaviour |
| B | The raw escape — `[:> Component …]` | migration, and genuinely one-off dynamic component selection |

Prefer A. A declaration is validated once and named everywhere; the raw escape
is validated at every crossing and named nowhere.
[Interop](09-interop.md#raw--escape) has the full table of what the second gives
up against the first. A component that appears more than once has already earned
its declaration.

## What each rung costs

Every rung buys something and spends something, and the spend is usually
inspectability rather than speed. The chapters teach the mechanisms; this is the
bill.

| At and past | Semantic tests | Tools | Server rendering | Frame carriage |
| --- | --- | --- | --- | --- |
| Performance rung 2 | unchanged | unchanged | unchanged | unchanged |
| Performance rungs 3–5 | assert React behaviour at L3 | Xray names and times the native boundary and shows its supported hook reads; the inner tree is opaque | the island's `h/defhost` declares `:server :render`, or stays Client-only | `(rf/capture-frame)` in a rung-3 body carries the frame; inside an island, `n/use-frame` does |
| A declared host | the crossing is opaque to L2; assert it at L3 | Xray names and times the crossing, not its interior | yours to declare: `:server :render`, or Client-only with an optional `:fallback` | an `h/event` or intent vector at an `on*` prop carries the frame; a plain function does not |
| The raw escape | opaque to L2; assert at L3 | the crossing has no authored name | Client-only, with no fallback of its own | contracts are inferred from the spelling as on a declared host; there is no override and no slot |

Two of those rows are refusals rather than conventions. The test kit raises
`:rf.error/hicasso-test-host-is-opaque` and
`:rf.error/hicasso-test-react-is-opaque` at L2, each pointing at L3 in its
`:reason`, so the boundary is enforced rather than documented
([Testing](15-testing.md#l2-refuses-react-only-behaviour)). Every id on this
page is indexed in [Troubleshooting](troubleshooting.md#the-complaint-index).

One cost is not on the table because it is not recoverable by care. A controlled
text field moved behind the native fence loses the Hicasso controlled-field
contract, and native construction does not make typing faster anyway. Keep those
fields interpreted ([Islands](10-native-tier.md#when-not-to-write-an-island)).

## Taking a performance escape

Do not take one without a reproducible interaction and an attributed owner. The
procedure is [Performance](19-performance.md#the-measurement-loop)'s measurement
loop, and its fourth and fifth steps are the performance rungs above.

Then apply the benefit rule: **keep the escape only if it recovers at least 20%
of the measured interaction, saves at least 2 ms at p95, or converts a failed
user-visible budget into a pass.** One of the three, on the interaction you
scripted, against the same screen written the ordinary way.

An escape that clears none of them is removed. “It may matter later” is not a
fourth condition, and thresholds do not widen to keep an island that misses
them. The comparison is re-run when the surrounding path changes materially,
because the rung you justified against last quarter's topology is not
automatically justified against this one.

!!! note "Where those numbers come from"
    They are not this page's invention. Hicasso's performance contract states
    the escape-benefit rule in exactly the three disjuncts above, and pairs it
    with the sentence that supplies their teeth: an island missing its
    threshold is simplified or removed, and thresholds do not widen to keep it.
    The contract registers the rule for reconciliation against measured
    evidence, so it is adjudicated rather than merely asserted.

    Three properties of it are worth carrying into a review.

    - **It is adjudicated per landed escape, not corpus-wide.** Every escape
      that ships carries its own measured benefit and is judged on that. A
      figure published for a mechanism in general is a reference price your own
      adjudication may cite; it is never a pass, and never a veto, for your
      site. The project's own reading for direct return sits close enough to
      the 20% line that its range straddles it, which is exactly why the
      mechanism cannot stand in for the site.
    - **An interoperability escape is outside the population**, by the rule's
      own wording rather than as an exception granted to it. That is the next
      section, and it is the reason the rule is written over escapes *taken
      for a benefit*.
    - **The thresholds are on a *ratio*,** so they do not go stale the way a
      measured constant does. The measurement you compare against them does,
      which is why the rule says re-run rather than remember.

    The contract that states the rule and the budget record that tracks it
    are working design records rather than published pages, so this note is
    the whole of the rule as it bears on a decision about one escape. What
    they hold beyond it is the project's own reconciliation bookkeeping — which
    rows are green, which are still waiting on evidence — and nothing there
    moves the three numbers above.

## The rule an interoperability escape is not judged by

The benefit rule has a denominator: the same screen written the ordinary way,
measured. An interoperability escape does not have one. There is no slower
spelling of `react-datepicker` to divide by, so a `defhost` that “only” recovers
0% of an interaction has not failed a test — it was never in that population.

Judge it on the questions that do apply:

- Is the crossing **declared**, so it is validated once and named to the tools?
- Does every value that drives the component arrive on its **own props**?
- Does each callback's inferred **contract** match the library — in
  particular, is any on*-named prop really a render prop, which needs a
  `{:callbacks {… :render}}` override?
- Does the declaration state a **server policy** you meant, rather than
  inheriting Client-only by omission?
- If the component acquires anything, does something **release** it?

Those are [Interop](09-interop.md)'s subject, and a host that answers all five
is finished work rather than a debt.

The one interoperability rule that does look like the benefit rule: a repeated
raw escape graduates to a declaration. Two crossings of the same component is
the threshold, and it is a threshold on count rather than on time.

## What every escape must preserve

Crossing changes how a subtree is written, never what the application promises.
After taking any rung, re-run the contracts Hicasso can no longer inspect for
you ([Islands](10-native-tier.md#verify-every-crossing)):

- DOM and interaction parity;
- focus and selection;
- frame routing;
- SSR and hydration;
- cleanup and StrictMode behaviour;
- the performance script that sent you here in the first place.

The last one is not a formality. An escape that improves construction while
breaking teardown has moved cost from a place you were measuring to a place you
were not.

## Climbing back

A rung is a position, not a destination. Remove an escape when the pressure that
justified it is gone: a subscription made finer, a list windowed, a vendor
component replaced by twenty lines of Hiccup, a React release that made the
thing you worked around fast.

The signal is usually the benefit rule failing on a re-run rather than anything
dramatic. Delete the island, restore the interpreted spelling, and re-measure.
A smaller diff is easier to maintain than a second authoring style kept for a
benefit nobody can still demonstrate.

## Things that look like escapes and are not

Not every crossing is a descent. These are ordinary parts of the model and carry
no benefit-rule obligation:

| Doing this | Is |
| --- | --- |
| A callback ref that attaches an imperative SDK and returns its cleanup | the supported way to own a DOM-attached SDK ([Interop](09-interop.md)) |
| `h/portal` | a container mechanism; the subtree stays interpreted and in the same frame |
| Ephemeral state for open/closed, hover, or draft-local UI | [Ephemeral state](11-ephemeral-state.md), not local React state escaping |
| `h/as-component` or `h/as-element` | going outward — handing a Hicasso view to a React parent, which keeps its reads, memo and frame |
| Writing a plain `defn` helper that returns Hiccup | an inline helper, called in place; it never became a boundary |

## When you are not on either ladder

If the screen is React-shaped by design — a canvas editor, a diagramming
surface, a vendor grid at the centre of the product — you are not escaping
anything. Implement it natively under the same adapter, root and frames, and
keep one state owner ([Islands](10-native-tier.md#native-screens)).

And if the answer is “the whole application is React-shaped”, the UIx adapter is
a better fit than a Hicasso application made of islands. A few named escapes are
a boundary. Islands throughout is a change of view-layer strategy, and it is
cheaper to make that choice deliberately than to arrive at it one rung at a
time.
