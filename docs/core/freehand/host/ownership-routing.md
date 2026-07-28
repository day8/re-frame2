# Ownership routing

There is one question to answer before you integrate anything, and it is not
which library you picked.

> **Who owns the node, and who owns the state that decides what is on it?**

Every route below falls out of that answer. Nothing here is a rule about Google
Maps or Radix or Framer Motion — those are *examples of ownership shapes*, and
two libraries with nothing else in common route identically when they own the
same things. The [JavaScript libraries](js-libraries.md) page is the recipe
companion: it shows the code. This page decides which recipe you are writing.

Routing by brand is how integrations go wrong. "Radix is a UI kit, so it goes in
a component" and "Maps is a map, so it goes in a `<div>`" are both true and both
useless — the first thing that matters about Radix is that it passes state
*between* its own parts through React context, and the first thing that matters
about Maps is that it hands you back a node *it* created.

## The three facts that decide the route

Answer these in order. The first one that is true settles it.

| # | Ownership fact | What it means in practice |
|---|---|---|
| 1 | **The library owns a DOM subtree.** | You hand it an element and it builds inside; you must never render into that element again. `new X(el)`, `chart.render(el)`, `map.setCenter(…)`. |
| 2 | **The library owns state that flows between its own parts.** | A root component publishes through React context, refs, or `asChild` cloning, and the parts are meaningless apart. Compound React libraries live here. |
| 3 | **The library owns a *clock*.** | Something continues after the commit that started it: an exit animation, a spring, a transition. The DOM disagrees with your last commit *on purpose*, for a while. |

If none of the three is true, the library owns nothing and you have the easy
case: it is a function, and it belongs in an event handler or a subscription
like any other pure code.

## The routes

| Ownership shape | Route | Crossings it costs | What stays yours |
|---|---|---|---|
| **Pure / headless core** — formatters, geometry, parsers, a date library, a diffing engine | No boundary at all. Call it from an event handler, a subscription, or a view body. | none | everything |
| **React component: values in, callbacks out** — date pickers, most charts, most inputs | **`v/defhost`**, registered once. | one door | the state, the props, the events |
| **React compound owner** — context between parts, `asChild` ref cloning, its own portal, its own focus management | **`v/defhost` on the *wrapper*** — the component that composes the parts — never on the parts individually. | one door | which parts render, and the data in them |
| **Imperative SDK owning a node** — Mapbox, Vega, a grid widget, GSAP on an element | **Registered behavior.** `:connect` establishes one mutable cell; `:update` mutates and its return value is discarded; `:disconnect` releases. | one behavior | the config, as data |
| **The library hands *you* a node** — an overlay pane, a popup container, a cell renderer target | **An explicit nested Freehand root** into the node you were given, torn down when it is taken back. | one behavior + one extra root | the view you mount there |
| **The library owns a clock** — exit animation, physics, transitions | Let the library own the retention. Do **not** put `v/presence` over the same keyed subtree. | one door | which children exist |
| **A React tree needs to render *your* view** — you are the guest, not the host | **`v/->react`**, which answers a component. Hoist it; repeated exports answer the identical one. | one export | the view |

### Pure and headless cores need no route

The largest category, and the one people over-engineer. A library with no DOM and
no lifecycle is not a host concern. Put it where the answer is needed and keep the
result in app-db if it is worth time-travelling and out of app-db if it is not.

### One door for a compound owner, and it goes around the whole thing

The temptation with a compound React library is to register each part so the call
site reads like the library's own docs. Do not. The parts talk to each other
through React context and refs, and a Freehand declaration between two of them is
a hole in that conversation.

Register the wrapper. Everything inside stays React-primary and keeps working:
the portal lands where React put it, focus moves on open and returns on close, and
the substrate neither moves it nor watches it move.

**The permanent limit here is a promise Freehand withholds:** a Freehand-authored
child does **not** participate in `asChild`, arbitrary `cloneElement`, or
ref-injection. It does not fail, either — React hands a `ref` prop to a function
component as an ordinary unused prop, so the ref simply never arrives and nothing
says so. **The recovery is the route itself**: if a region needs those protocols,
that region is React-owned, and its wrapper is what you register.

### An imperative SDK gets one reclamation path, entered from both ends

Two of the three things an SDK owns are ordinary. The one that decides whether the
integration leaks is that **reclamation can be initiated from either side**: your
`:disconnect` runs when the view goes away, and the library's own removal callback
runs when the library, a control, or the user takes the thing away. It can run
first, or second, or twice — and destroying the parent often *causes* the callback,
so the library re-enters your release from inside your release.

So write one path, fence it on its own terminal phase, and enter it from both
sides. Set the phase *before* releasing anything, not after.

**The cost is stated rather than recovered:** an imperative subtree is opaque. It
has no structural render, no SSR projection, and nothing in it is visible to
Freehand's own bookkeeping. That is the trade for letting the library do what it
is good at.

### A host-created pane is an island, and islands are explicit

When the library hands you a node it made, there is no portal to reach for —
Freehand does not have one and is not going to. Mount an explicit second root into
that node, and unmount it when the node is taken back.

Be clear-eyed about what an island is not: **it does not inherit the outer tree's
React context, its Suspense boundaries, or its event bubbling.** Nothing pretends
it does. Pass what it needs as props or read it from app-db, which the island
shares because a frame is not a tree.

**One measured consequence worth knowing before you write a leak check.** A nested
root's release is deferred by one task. At the moment the outer view unmounts, the
substrate's own books already read empty while the door's registry still holds the
island; one task later they agree. Both readings are correct and they are about
different clocks — but a leak check written against the wrong one reports a leak
that is not there.

### One clock, one retention owner

Freehand has a retention primitive ([presence](presence.md)) and so does every
animation library. Two owners over one keyed subtree is two clocks deciding when a
child is gone, and the answer is not to arbitrate between them — it is to pick one.

Where the animation is the library's, the retention is the library's and the
substrate holds nothing for it: the library keeps a departed child mounted after
your last commit stopped naming it, and reports completion through a declared
callback position. Where the exit is a CSS class and a duration, `v/presence` is
the smaller design and there is no library.

**The same rule one level down, and this one is silent when you break it:** the
property the library animates must have exactly **one writer**. If your call does
not author `transform`, the library's imperative write stands through an ordinary
commit. If your call *also* authors it, the commit wins, the library's own state
and the DOM disagree, and nothing errors or warns. **The recovery is not a
mechanism** — it is to stop authoring the property the library owns and drive it
through the library's own prop instead.

## Permanent limits, and what each one buys

Some of these are recoverable and some are the price of the route. The difference
matters more than the list.

| Limit | Recoverable? | The recovery, or the reason there is none |
|---|---|---|
| A Freehand child cannot take an injected `ref` / `asChild` | No — a withheld promise, deliberate | Register the wrapper; keep the region React-owned |
| An imperative subtree has no structural render and no SSR | No — it is what "the library owns the node" means | Render a server-side placeholder and let the library take over on the client |
| A nested root does not inherit outer context, Suspense, or bubbling | No — an island is a second tree | Pass props; share app-db; keep the island small |
| A nested root's release lands one task late | No — two clocks, both correct | Read the door's registry, or wait a task; do not average them |
| A host crossing cannot be inside a `{:compiled true}` parent | Yes | Keep the crossing's declaration interpreted — promotion is per declaration |
| Two writers on one animated property lose silently | Yes | One writer: the library's own prop, never `:style` |

## What is actually proven

Each route above is a conformance law with an executable fixture, not a
recommendation. In `spec/conformance/freehand/conformance-index.md`:

- **`FH-BEHAVIOR-010`** — the imperative-SDK route, the host-created pane, and the
  explicit nested root. One reclamation path entered from a host-removal callback
  and from `:disconnect`, in either order and any number of times, each run ending
  at exact zeros — zero maps, zero listeners, zero overlays, zero nested roots —
  plus the deferred release measured across the task boundary.
- **`FH-REACT-009`** — the compound React owner behind one `v/defhost` door. The
  portal lands in `document.body` and never in the Freehand container; focus moves
  on open and returns on close; and the withheld `asChild` promise is an A/B over
  one `cloneElement(child, {ref})` — a React-authored child takes the ref, a
  Freehand-authored one takes none and does not fail.
- **`FH-REACT-010`** — the clock owner. One writer on the animated property, in
  both directions, and one owner for exit retention.

Each of the three drives a **deterministic surrogate** reproducing its library's
ownership shape rather than the library itself, and each fixture says so in a
machine-readable `:evidence :limits`. What is proven is where ownership falls at
the boundary — which is the thing the route depends on — and not any vendor's
implementation of it. Maps cannot run in CI at all (network, API key, a billed
account); a real animation is driven against a wall clock, so a gate on it would
be measuring frame timing rather than ownership.

## Where to go next

- [Host boundaries](host-boundaries.md) — the contracts: `v/defhost`, registered
  behaviors, commands, `v/->react`, error boundaries.
- [JavaScript libraries](js-libraries.md) — the recipes, one per route, with code.
- [Presence: enter and exit](presence.md) — the retention primitive, and when it
  is the smaller design.
- [Reactivity and ownership](../authoring/reactivity-and-ownership.md) — the same
  question asked about your own views rather than a library's.
