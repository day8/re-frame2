# counter — the smallest possible re-frame2 app

This is the "hello, world" of re-frame2: a number, a minus button, a
plus button. The arithmetic is beneath comment. What earns the counter
its place as the *first* example is that it spells out, in full, the
shortest path a click can take through re-frame2 — and it does so with
nothing else in the frame to distract you.

That path is a loop. A click **dispatches** an [event](../../../docs/guide/glossary.md#event) — an
inert little vector, `[:counter/inc]`, that records "something
happened" and does nothing on its own. The runtime hands it to the
registered [event handler](../../../docs/guide/glossary.md#event-handler), a *pure* function that reads the
current [app-db](../../../docs/guide/glossary.md#app-db) and returns a *description* of the next one:

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
```

Note what the handler does *not* do: it doesn't reach into the DOM, it
doesn't mutate anything, it doesn't even know a button exists. It
returns `{:db …}` — "replace app-db with this" — and the framework
[commits](../../../docs/guide/glossary.md#commit) the new value. Downstream, the [subscription](../../../docs/guide/glossary.md#subscription)
re-derives the count and the [view](../../../docs/guide/glossary.md#view) re-renders. State flows **in**
through events and **out** through subscriptions, and never the other
way. Master that one-directional shape on a counter and every richer
example in the catalogue is the same dance with more dancers.

## What this demonstrates

- **`reg-event`** — three handlers (`:counter/initialise`,
  `:counter/inc`, `:counter/dec`), each a pure function returning a
  `{:db …}` [effect map](../../../docs/guide/glossary.md#effect-map). No `:fx`, because a counter has no side
  effects to perform — just state.
- **`reg-sub`** — a `:counter/value` subscription that reads the count
  straight out of `app-db`. The leaf of the simplest possible
  derivation graph: one hop from state to view.
- **`reg-view`** — the `defn`-shape view-registration macro. You write
  it like a `defn`, and it both `def`s a Var and registers the view —
  *and* it auto-injects `dispatch` and `subscribe` as lexical bindings
  you can just call, no `rf/` prefix and no frame argument threaded
  through. Those bindings resolve at render time to whichever
  [frame](../../../docs/guide/glossary.md#frame) is in scope (here, the app's one frame).
- **The frame ceremony** — the part worth slowing down for, and the
  reason this counter is more than a generic Reagent demo. See below.

Cross-substrate twins live at
[`examples/uix/counter_uix/`](../../uix/counter_uix/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) — the
same dataflow rendered through hooks instead of Reagent's reactive
[substrate](../../../docs/guide/glossary.md#substrate). Read the three side by side and the seam jumps out:
the events, subscription, and `app-db` are byte-for-byte identical, and
only the rendering layer moves. That seam is the whole point of the
[adapter](../../../docs/guide/glossary.md#adapter).

## Why this shape

re-frame2 never conjures a frame out of thin air — *identity is
carried, not found*. An app must stand its frame up explicitly, and
this counter is the smallest place to watch that happen end to end.
Four steps, all in `run`:

```clojure
(rf/init! reagent-adapter/adapter)              ;; install the adapter (NOT a frame)
(rf/reg-frame app-frame {})                     ;; register the app's frame
(rf/with-frame app-frame
  (rf/dispatch-sync [:counter/initialise]))     ;; boot it: seed app-db, synchronously
(rdc/render @react-root
  [rf/frame-provider-existing {:frame app-frame} ;; scope the frame to the view tree
   [counter-app]])
```

Each step does exactly one job. [`init!`](../../../docs/guide/glossary.md#init) teaches the runtime which
[substrate](../../../docs/guide/glossary.md#substrate) to render through and *nothing else* — pointedly, it
does not create a frame. `reg-frame` registers the app's frame
(the example names it `:rf/default`, but the runtime will not *infer*
that name — it has to be asked for). The boot dispatch runs inside
`with-frame` so it lands in the right frame, and uses
[`dispatch-sync`](../../../docs/guide/glossary.md#dispatch-sync) — the synchronous sibling of `dispatch` — so
`app-db` is seeded *before* the first render rather than a tick later.
Finally `frame-provider-existing` wraps the view tree, which is what
lets the `dispatch` and `subscribe` calls inside the views find their
frame instead of raising `:rf.error/no-frame-context`.

If you've come from re-frame v1, this explicitness is the thing to
notice: there's no global, implicit app any more. The payoff is that
frames are independent instances — the reason you can mount the same
app twice on one page, run throwaway frames in tests and stories, and
let a sidecar tool like Xray live in its own frame beside yours. The
counter just happens to use exactly one.

Everything else is deliberately absent: no schemas, no machines, no
HTTP, no routing. Just the dataflow loop and the frame it runs in, with
nothing in the way of seeing them clearly.

## Files

```
counter/
  core.cljs    — events, sub, view, mount
  index.html   — minimal host page
```

## How to run

```bash
shadow-cljs watch examples/counter
```

Then open the served page — the minus and plus buttons drive the loop
described above.
