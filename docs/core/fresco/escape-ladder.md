# The escape ladder

Sometimes a screen needs something ordinary Fresco Hiccup cannot express, and
you step outside it. Every such escape is explicit in source, visible to the
tools, and reversible. There are two reasons to take one, and each has its own
rules.

## Two different reasons to leave

**Interoperability.** There is no Hiccup spelling for what you need: a date
picker with forty props, a virtualiser with its own scheduler, a mapping SDK
that owns a DOM node. Without the escape you have no implementation at all.

**Performance.** You have written the Hiccup version, and a measured
interaction misses a budget with Fresco's own rendering work as the cost owner.
The ordinary version exists and is too slow.

Most mistakes here treat one reason as the other: deleting a host because it
did not recover 20%, or building a native island because a screen felt heavy
and nobody measured.

## The performance ladder

Take each rung only after the one above it has failed. The code for rungs 3 to
5 is in [Islands](10-native-tier.md).

| Rung | What you write | Take it when |
| --- | --- | --- |
| 1 | Ordinary Fresco: Hiccup, `h/sub`, event vectors | always; every screen starts here |
| 2 | Tuned Fresco: view boundaries, keys, read shape, chunking, windowing | a measured interaction invalidates too much work |
| 3 | A `defview` body returns a React element | Hiccup conversion is the measured cost |
| 4 | A React island: raw React or UIx, mounted through `h/defhost` | hooks, vendor internals, reconciliation, or per-frame local work dominate |
| 5 | A native screen | the screen is React-shaped by design |

Most performance work ends at rung 2, which is still ordinary Fresco: moving a
read down, drawing a view boundary differently, or windowing a list.

## The interoperability ladder

Two rungs, taught in [Interop](09-interop.md). Their order has nothing to do
with speed.

| Rung | What you write | Take it when |
| --- | --- | --- |
| A | A declared host, `h/defhost` | the component is foreign and you need its behaviour |
| B | The raw escape, `[:> Component …]` | during migration, or for a one-off dynamic choice of component |

Prefer A. A declaration is validated once and has a name the tools can show;
the raw escape is validated at every use and has no name.
[Interop](09-interop.md#raw--escape) lists everything else the raw escape gives
up. Once a component appears in two raw `[:>]` escapes, declare it.

## What each rung costs

Each rung below ordinary Fresco mostly costs you what tests and tools can see
(L2 and L3 are the test levels in [Testing](15-testing.md)):

| At and past | Semantic tests | Tools | Server rendering | Frame carriage |
| --- | --- | --- | --- | --- |
| Performance rung 2 | unchanged | unchanged | unchanged | unchanged |
| Performance rungs 3–5 | assert React behaviour at L3 | a rung-3 view keeps its Xray name and reads; an island's `n/use-sub` reads show, its React subtree is opaque, and Xray times neither | the island's `h/defhost` declares `:server :render`, or stays Client-only | `(rf/capture-frame)` in a rung-3 body carries the frame; inside an island, `n/use-frame` does |
| A declared host | the crossing is opaque to L2; assert it at L3 | Xray names the crossing; it does not time it or see inside it | yours to declare: `:server :render`, or Client-only with an optional `:fallback` | an `h/event` or intent vector at an `on*` prop carries the frame; a plain function does not |
| The raw escape | opaque to L2; assert at L3 | the crossing has no authored name | Client-only, with no fallback of its own | contracts are inferred from the spelling as on a declared host; there is no override and no slot |

`ht/tree` throws at a host or a raw React element and names L3 as the level to
test it at ([Testing](15-testing.md#l2-refuses-react-only-behaviour)).

A controlled text field moved into an island loses Fresco's controlled-field
handling, and native construction does not make typing faster. Keep those
fields in ordinary Fresco ([Islands](10-native-tier.md#when-not-to-write-an-island)).

## Taking a performance escape

Do not take one without a reproducible interaction and an attributed owner. The
procedure is [Performance](19-performance.md#the-measurement-loop)'s measurement
loop, and its fourth and fifth steps are the performance rungs above.

Then apply the benefit rule. Keep the escape only if, on the interaction you
scripted and against the same screen written the ordinary way, it:

- recovers at least 20% of the measured interaction,
- saves at least 2 ms at p95, or
- turns a failed user-visible budget into a pass.

Otherwise remove it. Re-run the comparison when the surrounding code changes
materially, because an escape justified against the old code may not be
justified against the new.

Measure each escape on its own screen. Gains from direct React return, for
example, sit close to the 20% line, so the same technique can pass on one
screen and fail on another.

## The rule an interoperability escape is not judged by

The benefit rule compares against the same screen written the ordinary way.
An interoperability escape has nothing to compare against: there is no Hiccup
version of `react-datepicker`. A `defhost` that recovers 0% of an interaction
has not failed the benefit rule, because the rule does not apply to it.

Judge it on the questions that do apply:

- Is the crossing declared, so it is validated once and named to the tools?
- Does every value that drives the component arrive on its own props?
- Does each callback's inferred contract match the library? In particular, is
  any `on*`-named prop really a render prop, which needs a
  `{:callbacks {… :render}}` override?
- Does the declaration state the server policy you meant, rather than
  defaulting to Client-only?
- If the component acquires anything, does something release it?

[Interop](09-interop.md) covers each of these.

## What every escape must preserve

An escape changes how a subtree is written, but the application must behave
the same. After taking any rung, re-run the checks in
[Islands](10-native-tier.md#verify-every-crossing), which Fresco can no longer
perform inside the React subtree.

## Climbing back

Remove an escape when the pressure that justified it is gone: a subscription
made finer, a list windowed, a vendor component replaced by twenty lines of
Hiccup, a React release that fixed the slow path.

The usual signal is the benefit rule failing on a re-run. Delete the island,
restore the ordinary Fresco version, and re-measure.

## Things that look like escapes and are not

These are ordinary Fresco features, not escapes, and the benefit rule does not
apply to them:

| Doing this | Is |
| --- | --- |
| A callback ref that attaches an imperative SDK and returns its cleanup | the supported way to own a DOM-attached SDK ([Interop](09-interop.md)) |
| `h/portal` | a container mechanism; the subtree stays interpreted and in the same frame |
| Ephemeral state for open/closed, hover, or draft UI | [Ephemeral state](11-ephemeral-state.md), a Fresco feature |
| `h/as-component` or `h/as-element` | handing a Fresco view to a React parent; the view keeps its reads, memo and frame |
| A plain `defn` helper that returns Hiccup | a helper called in place, inside its caller's view |

## When you are not on either ladder

If the screen is React-shaped by design — a canvas editor, a diagramming
surface, a vendor grid at the centre of the product — you are not escaping
anything. Implement it natively under the same adapter, root and frames, and
keep one state owner ([Islands](10-native-tier.md#native-screens)).

If the whole application is React-shaped, the UIx adapter is a better fit than
a Fresco application made of islands. Decide that up front rather than drifting
into it one island at a time.
