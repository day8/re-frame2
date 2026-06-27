# counter — the smallest possible re-frame2 app

A number, a minus button, a plus button. That's the whole app.

The counter is the first example because it shows the shortest path a
click can take through re-frame2, with nothing else in the way. The
maths doesn't matter. The path does.

That path is a loop. A click **dispatches** an
[event](../../../docs/guide/glossary.md#event) — a plain data vector,
`[:counter/inc]`, that just says "something happened". The event does
nothing on its own. The runtime hands it to the registered
[event handler](../../../docs/guide/glossary.md#event-handler): a
*pure* function that reads the current
[app-db](../../../docs/guide/glossary.md#app-db) and returns a
*description* of the next one.

```clojure
(rf/reg-event :counter/inc
  (fn [{:keys [db]} _event] {:db (update db :counter/value inc)}))
```

Notice what the handler does *not* do. It doesn't touch the DOM. It
doesn't mutate anything. It doesn't even know a button exists. It
returns `{:db …}` — "replace app-db with this" — and the framework
[commits](../../../docs/guide/glossary.md#commit) the new value. After
that, the [subscription](../../../docs/guide/glossary.md#subscription)
re-derives the count and the
[view](../../../docs/guide/glossary.md#view) re-renders.

State flows **in** through events and **out** through subscriptions,
and never the other way. That one-directional shape is the whole idea.
Every richer example is the same loop with more parts.

## What this demonstrates

- **`reg-event`** — three handlers (`:counter/initialise`,
  `:counter/inc`, `:counter/dec`). Each is a pure function that returns
  a `{:db …}` [effect map](../../../docs/guide/glossary.md#effect-map).
  There's no `:fx`, because a counter has no side effects to perform —
  it only changes state.
- **`reg-sub`** — a `:counter/value` subscription that reads the count
  straight out of `app-db`. One hop from state to view: the simplest
  derivation there is.
- **`reg-view`** — a view-registration macro you write like a `defn`.
  It `def`s a Var *and* registers the view. It also hands you `dispatch`
  and `subscribe` as ready-to-call local bindings — no `rf/` prefix, no
  [frame](../../../docs/guide/glossary.md#frame) argument to thread
  through. They resolve at render time to whichever frame is in scope
  (here, the app's one frame).
- **Standing up the frame** — the part worth slowing down for, and the
  reason this is more than a generic Reagent demo. See below.

The same counter, on other substrates, lives at
[`examples/uix/counter_uix/`](../../uix/counter_uix/) and
[`examples/helix/counter_helix/`](../../helix/counter_helix/) — the same
dataflow, rendered through hooks instead of Reagent's reactive
[substrate](../../../docs/guide/glossary.md#substrate). Put the three
side by side and the seam is clear: the events, subscription, and
`app-db` are byte-for-byte identical, and only the rendering layer
changes. That seam is the whole point of the
[adapter](../../../docs/guide/glossary.md#adapter).

## Why this shape

re-frame2 never creates a frame for you. An app must stand its frame up
itself, and this counter is the smallest place to watch that happen end
to end. Two steps, all in `run`:

```clojure
(rf/init! reagent-adapter/adapter)              ;; install the adapter (NOT a frame)
(rdc/render @react-root
  [rf/frame-provider {:id app-frame             ;; stand the frame up: create + seed
                      :initial-events [[:counter/initialise]]}
   [counter-app]])
```

Each step does one job:

- [`init!`](../../../docs/guide/glossary.md#init) tells the runtime which
  [substrate](../../../docs/guide/glossary.md#substrate) to render
  through, and nothing else. It does not create a frame.
- [`frame-provider`](../../../docs/guide/glossary.md#frame-provider) wraps
  the view tree and stands the frame up. Given `:id`, it *ensures* a named
  frame — creating it on the first mount and reusing it untouched on a hot
  reload, never re-seeding — and runs `:initial-events` once on creation
  to seed `app-db` before the first render. The example names the frame
  `:rf/default`, but that name has no special meaning — you ask for it
  like any other. Wrapping the tree is also what lets the `dispatch` and
  `subscribe` calls inside the views find their frame instead of raising
  `:rf.error/no-frame-context`.

If you're coming from re-frame v1, this is the thing to notice: there's
no global, implicit app any more. The payoff is that frames are
independent instances. That's what lets you mount the same app twice on
one page, run throwaway frames in tests and stories, and let a sidecar
tool like Xray live in its own frame beside yours. The counter just
uses exactly one.

Everything else is left out on purpose: no schemas, no machines, no
HTTP, no routing. Just the dataflow loop and the frame it runs in.

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

Then open the served page. The minus and plus buttons drive the loop
described above.
