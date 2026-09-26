# The escape ladder

Sooner or later a screen needs something ordinary Fresco Hiccup does not
express, and you step outside it. Every such escape is explicit in source,
visible to the tools, and reversible. Before taking one, know which of two
reasons you have, because each has its own rules.

## Two different reasons to leave

**Interoperability.** There is no Hiccup spelling for what you need: a date
picker with forty props, a virtualiser with its own scheduler, a mapping SDK
that owns a DOM node. Without the escape you have no implementation at all.

**Performance.** You have written the Hiccup version, and a measured
interaction misses a budget with Fresco's own rendering work as the cost owner.
The ordinary version exists and is too slow.

Most mistakes here treat one reason as the other: deleting a host because it
did not recover 20%, or building a native island because a screen felt heavy
and nobody measured. Each reason has its own ladder.

## The performance descent

Five rungs; the code for rungs 3 to 5 is in [Islands](10-native-tier.md). Take
each rung only after the one above it has failed.

| Rung | What you write | Take it when |
| --- | --- | --- |
| 1 | Ordinary Fresco — Hiccup, `h/sub`, event vectors | always; this is where every screen starts |
| 2 | Tuned Fresco — boundaries, keys, read shape, chunking, windowing | a measured interaction invalidates too much work |
| 3 | A `defview` body returns a React element | Hiccup lowering is the measured owner |
| 4 | A React island — raw React or UIx, mounted through `h/defhost` | hooks, vendor internals, reconciliation, or per-frame local work dominate |
| 5 | A native screen | the surface is React-shaped from its first useful design |

Most performance work ends at rung 2. Moving a read down, drawing a boundary
differently, or windowing a list all stay in ordinary Fresco; rungs 3 to 5 do
not.

## The interoperability descent

Two rungs, taught in [Interop](09-interop.md). The order between them has
nothing to do with speed.

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

Each rung below ordinary Fresco mostly costs inspectability:

| At and past | Semantic tests | Tools | Server rendering | Frame carriage |
| --- | --- | --- | --- | --- |
| Performance rung 2 | unchanged | unchanged | unchanged | unchanged |
| Performance rungs 3–5 | assert React behaviour at L3 | Xray names and times the native boundary and shows its supported hook reads; the inner tree is opaque | the island's `h/defhost` declares `:server :render`, or stays Client-only | `(rf/capture-frame)` in a rung-3 body carries the frame; inside an island, `n/use-frame` does |
| A declared host | the crossing is opaque to L2; assert it at L3 | Xray names and times the crossing, not its interior | yours to declare: `:server :render`, or Client-only with an optional `:fallback` | an `h/event` or intent vector at an `on*` prop carries the frame; a plain function does not |
| The raw escape | opaque to L2; assert at L3 | the crossing has no authored name | Client-only, with no fallback of its own | contracts are inferred from the spelling as on a declared host; there is no override and no slot |

The L2 limits are enforced: `ht/tree` raises
`:rf.error/fresco-test-host-is-opaque` at a host and
`:rf.error/fresco-test-react-is-opaque` at a raw React element, each pointing at
L3 ([Testing](15-testing.md#l2-refuses-react-only-behaviour)). Both ids are
indexed in [Troubleshooting](troubleshooting.md#the-complaint-index).

A controlled text field moved into an island loses Fresco's controlled-field
handling, and native construction does not make typing faster. Keep those
fields in ordinary Fresco ([Islands](10-native-tier.md#when-not-to-write-an-island)).

## Taking a performance escape

Do not take one without a reproducible interaction and an attributed owner. The
procedure is [Performance](19-performance.md#the-measurement-loop)'s measurement
loop, and its fourth and fifth steps are the performance rungs above.

Then apply the benefit rule: **keep the escape only if it recovers at least 20%
of the measured interaction, saves at least 2 ms at p95, or converts a failed
user-visible budget into a pass.** One of the three, on the interaction you
scripted, against the same screen written the ordinary way.

An escape that meets none of them is removed; the thresholds do not widen to
keep it. Re-run the comparison when the surrounding code changes materially,
because an escape justified against the old topology may not be justified
against the new one.

Judge each escape on its own measurement. A published figure for a mechanism
in general, such as direct React return, is a reference point and never a pass
or a veto for your site: measured gains for direct return sit close to the 20%
line, so the same mechanism can pass on one screen and fail on another.

## The rule an interoperability escape is not judged by

The benefit rule compares against the same screen written the ordinary way.
An interoperability escape has nothing to compare against: there is no Hiccup
version of `react-datepicker`. A `defhost` that recovers 0% of an interaction
has not failed the benefit rule, because the rule does not apply to it.

Judge it on the questions that do apply:

- Is the crossing **declared**, so it is validated once and named to the tools?
- Does every value that drives the component arrive on its **own props**?
- Does each callback's inferred **contract** match the library — in
  particular, is any on*-named prop really a render prop, which needs a
  `{:callbacks {… :render}}` override?
- Does the declaration state a **server policy** you meant, rather than
  inheriting Client-only by omission?
- If the component acquires anything, does something **release** it?

[Interop](09-interop.md) covers each of these. A host that answers all five is
finished.

Interoperability has one threshold of its own: once the same component appears
in two raw `[:>]` escapes, declare it with `h/defhost`.

## What every escape must preserve

An escape changes how a subtree is written, not what the application
promises. After taking any rung, re-check the behaviour Fresco can no longer
inspect for you ([Islands](10-native-tier.md#verify-every-crossing)):

- DOM and interaction parity;
- focus and selection;
- frame routing;
- SSR and hydration;
- cleanup and StrictMode behaviour;
- the performance script that sent you here in the first place.

An escape that speeds up construction while breaking teardown has moved cost
somewhere you were not measuring.

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
| Ephemeral state for open/closed, hover, or draft-local UI | [Ephemeral state](11-ephemeral-state.md), not local React state escaping |
| `h/as-component` or `h/as-element` | going outward — handing a Fresco view to a React parent, which keeps its reads, memo and frame |
| Writing a plain `defn` helper that returns Hiccup | an inline helper, called in place; it never became a boundary |

## When you are not on either ladder

If the screen is React-shaped by design — a canvas editor, a diagramming
surface, a vendor grid at the centre of the product — you are not escaping
anything. Implement it natively under the same adapter, root and frames, and
keep one state owner ([Islands](10-native-tier.md#native-screens)).

If the whole application is React-shaped, the UIx adapter is a better fit than
a Fresco application made of islands. Make that choice deliberately rather than
arriving at it one island at a time.
