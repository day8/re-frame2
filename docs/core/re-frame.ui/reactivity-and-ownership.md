# Reactivity and ownership

The [build-a-view walkthrough](build-a-view.md) leaned on one sentence — "when
`[:count]` changes, the view re-renders" — without saying how. This page opens
that up to the level a view author needs: what re-computes when, and why a
compiled view's subscriptions never leak. You don't have to manage any of it, but
knowing the shape makes the framework's behaviour predictable.

## What re-computes when

Start with the question every view author eventually asks: I changed one value in
app-db — what runs again?

**One reactive bridge per view.** However many `(sub …)` sites a view has, they
share a *single* subscription to the store. The compiler indexes every site at
compile time and wires them to one bridge, so a view with five subscriptions
still holds one connection, not five. When any of its subscribed values moves,
the view is marked dirty.

**Only what actually changed.** A dirty view doesn't necessarily re-render.
Subscriptions are stabilised by value: if a sub's new value is equal (`rf=`) to
its last, the view sees the *same* value and nothing downstream churns. So
changing `[:count]` from `1` to `2` re-renders the views that read `[:count]`;
changing some *other* key, or writing `2` where the value was already `2`, does
not.

**Children skip on equal props.** Because every view is memoized on its props by
value, a parent re-render doesn't cascade into a child whose props are `rf=`. The
re-render stops where the data stops changing.

**Once per batch.** When an event settles, each dirty view flushes at most once —
several subscription changes from one event coalesce into a single re-render, not
one per sub. You never see a view render three times because three of its inputs
moved together.

Put together: *changing a value re-runs exactly the views that read it, once,
and only if the value really changed.* That's the whole performance story, and
it's automatic — there is no manual memo, no deps array, no subscription
bookkeeping.

## Why subscriptions never leak

Worth understanding even though you never touch the machinery yourself.

Under concurrent React a *render* is speculative: React may run it, restart it,
or throw it away without ever showing it. Any design that grabs a resource —
opens a subscription, takes a lock — *during* render is wrong by construction:
the render that grabbed it might be abandoned, and the grab would leak.

`re-frame.ui` sidesteps this with a strict rule: **render only reads; commit
owns.** When a view renders, it *resolves and reads* its subscriptions but takes
no ownership. Only when React *commits* that render — actually puts it on screen —
does the view acquire its subscriptions, and it releases them when the view
unmounts or is hidden. Because ownership is keyed to the commit, an abandoned
render owns nothing to leak, and a StrictMode double-invoke balances out. That is
why you can use subscriptions freely in a compiled view without thinking about
cleanup — the leak classes that hit render-time ownership never open.

## Frames survive hot reload

Frames are created at **host preflight** — the moment `frame-root` runs, before
React renders — never from inside a render. That timing is deliberate: it's what
makes hot reload a designed workflow rather than a hope. When you save a file and
Shadow reloads it, preflight runs again, finds the frame already live, and
reuses it without re-seeding. Your app-db, your count, your form drafts — all
survive the edit. The view code swaps; the state stays.

## Where state lives: the `local` boundary

The [walkthrough](build-a-view.md#step-4--local-state) put the count in app-db and
the help toggle in `local`, and the reason is exactly the ownership model above.
State in app-db is *observed*: subscriptions derive from it, the trace records
every change, tools inspect it, and [time-travel](../observability.md) can rewind
it. `local` deliberately sits **outside** all of that — it isn't seen by subs,
isn't in the epoch history, and re-renders only its own view.

That gives a clean rule for where a value belongs:

- **app-db, behind events** — anything with product meaning: something another
  view reads, that should replay or persist, that a schema or a tool should see,
  or that a subscription computes from.
- **`local`** — keystroke-latency ephemera with no product meaning: a disclosure
  toggle, a hover flag, a transient bit that only this view cares about.

When in doubt, prefer app-db: it's the observable, replayable home, and the
[Where should this value live?](../where-state-lives.md) page walks the fuller
decision.

## The vocabulary tools use

Because public React gives no signal that distinguishes "this view was hidden"
from "this view was unmounted", `re-frame.ui` models a view as being in one of
three observable states, and its tools speak in these terms:

- **connected** — mounted and visible; owns its subscriptions.
- **disconnected** — ownership released (hidden or unmounted); the runtime
  doesn't yet claim which.
- **dead** — its frame, adapter, or root was destroyed; reconnection fails
  loudly rather than silently.

You won't write against these directly, but they're the words [Xray](../observability.md)
and the reactivity tooling use, so it helps to recognise them.

## Resources: reading server data

A view never owns a [resource](../../resources/concepts.md)'s liveness. That is
owned **causally**, by whatever caused the load — a route entry, a machine, or an
app event — each with a named release. A view only ever **reads** a resource,
passively, with an ordinary `(sub [:rf/resource …])`: status and data, never a
fetch during render. The same "commit owns, release on teardown" discipline that
governs subscriptions above governs resource entries too, but the owner is the
cause, not the view.
