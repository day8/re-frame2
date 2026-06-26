# TodoMVC in re-frame2

Everyone knows TodoMVC. That's rather the point of it — it's the "hello world" that every framework re-implements, so once you've read one you can map its idioms straight onto the next. This is that familiar app (built to the current [TodoMVC app spec](https://github.com/tastejs/todomvc/blob/master/app-spec.md)) on re-frame2, with one eye on the original [day8/re-frame TodoMVC example](https://github.com/day8/re-frame/tree/master/examples/todomvc) so a re-frame v1 reader lands somewhere familiar.

So if it's *just* a todo list — and we've all written a hundred of those — what's actually worth your time here? Two design decisions that look like throwaway details and turn out to carry the whole re-frame2 lesson:

1. **The filter is a *route*, not a flag.** The all/active/completed switch lives in the URL, and the rest of the app derives the filtered list straight off it. There's no `:showing` key in app-db at all — the thing you'd reflexively store turns out to be something you read.
2. **Persistence stays *replayable*.** The localStorage read at boot doesn't happen inside a handler. It rides in as a recordable **coeffect**, so the boot **event** replays to the exact same state under time-travel instead of re-reading whatever the browser holds now. That sounds like a fussy distinction; it's the difference between persistence you can debug and persistence you can only pray over, and there's a whole section on it below.

Everything else is the boring, correct re-frame2 spine you'd expect, and that's a compliment: pure **event handlers**, a layered **subscription** graph, a registered **effect** for the write side, and a **view** tree that holds no business logic of its own.

The file layout deliberately echoes the v1 example's teaching split — the same six concerns parcelled out the same way — so when you diff v1 against v2 what changes is the *API*, never the architecture:

- `core.cljs` — entry point, frame setup, and the hash → route adapter
- `db.cljs` — the default app-db and the localStorage coeffect
- `events.cljs` — the routes, the state transitions, and the persistence effect
- `subs.cljs` — the derived views over todos and counts
- `views.cljs` — the TodoMVC markup and interactions

## What it demonstrates

- **The canonical TodoMVC behaviour** — add, edit (double-click a todo), toggle one, toggle all, delete, clear completed, the live "items left" count, and hash-based filtering.
- **Filtering as routing, not state.** `reg-route` registers `/`, `/active`, and `/completed` (plus the reserved `:rf.route/not-found` fallback, so a stray hash never breaks the app). The `:todo/showing` subscription reads `:rf.route/id` and maps it to `:all` / `:active` / `:completed`; `:todo/visible-todos` filters off *that*. The active route **is** the filter — which is exactly the re-frame2 posture that the URL is an input and the route is just derived state you read through a sub. Because TodoMVC's URLs are hash-based (`#/active`) rather than path-based, `core.cljs` carries a tiny host-adapter that strips the `#` and dispatches `:rf.route/handle-url-change` into the URL-owning **frame** — the same contract the framework's own popstate listener implements, done by hand because this app speaks `hashchange`.
- **A layered subscription graph.** `:todo/sorted-todos` reads app-db; `:todo/todos`, `:todo/visible-todos`, `:todo/all-complete?`, `:todo/completed-count`, and `:todo/footer-counts` stack on top of it. Each layer recomputes only when its input actually moves, so the footer count doesn't re-derive when you're busy editing a title.
- **Controlled inputs and editing state in app-db (Pattern-Forms).** The new-todo and edit-in-place inputs are *controlled*: their `:value` reads a draft sub and their `:on-change` dispatches an edit event into a `:ui` slice — the view holds no `reagent/atom` for the text. "Which row is being edited" is application state too, so it lives at `[:ui :editing-id]` and is read through `:todo.ui/editing?`. No view-local atoms, no DOM-node refs to read `.value` off, no blur-suppression flag — just drafts and an editing id projected via subs and mutated via events. The edit input still auto-focuses when a row enters edit mode, via a focus-only `:ref` that calls `.focus()` and never touches the value. See [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md).
- **Replayable browser persistence.** A registered effect (`:todo.storage/save`) writes every change back to localStorage; the initial load comes in through a recordable coeffect (`:todo.storage/todos`) — see the next section for why that's not just "a load function in disguise."

### Why the load is a coeffect, not a `localStorage` call in a handler

This is the one genuinely subtle thing in the example, and it's worth a minute.

You *could* just read `localStorage` inside the boot handler. It would work — right up until you try to replay or time-travel that boot, at which point the handler re-reads whatever localStorage holds *now*, not what it held *then*, and the replay diverges. A durable write has to be a pure function of prior state plus the facts that arrived with the event — never of an ambient read smuggled in at the write site.

So the example *registers* the host read as a **recordable** coeffect — a `reg-cofx` supplier whose generator reads localStorage (`db.cljs`). Because the boot read decides a durable write, the app supplies it the canonical way: a **registered recordable generator**, not a value stamped at the dispatch site (that `:rf.cofx` dispatch opt is a unit-test seam, never a production shape).

```clojure
(rf/reg-cofx :todo.storage/todos
  {:recordable? true :doc "…"}
  (fn [] (read-todos-from-storage)))   ;; the generator: reads the host once
```

The handler then just declares it needs that fact and folds it into app-db — pure, no IO:

```clojure
(rf/reg-event :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc db/default-db :todos todos)}))
```

The boot dispatch stays **plain** — `core.cljs` seeds the frame with `:initial-events [[:todo/initialise] …]` and carries no cofx; the registered generator is the supplier. The generator runs once at processing-start, its value is recorded onto the causal token, and a replay or epoch-restore re-presents the captured snapshot verbatim instead of re-reading the world. (If you want the full theory, this is the "recordable vs ambient coeffects" distinction from the glossary, and the persistence write itself is an ordinary registered effect — effects are data, performed by the runtime, kept out of the pure handler.) Yes, it's a conspicuous amount of ceremony to load a todo list, and on a todo list you'd never actually feel the bug it prevents. But that's exactly why a todo list is the place to learn the *shape*: small enough that the mechanism is the only thing in view, and once you've seen it here you'll recognise it the day it's a 200-row spreadsheet whose replay quietly lies to you.

### Why localStorage?

Because the persistence is the lesson here, not the wiring around it. A real backend would drag in a server to stand up, a request lifecycle to follow, and auth to wave away — none of which teaches you anything about *replayable* writes. localStorage is the smallest thing that's a genuine durable side effect: it survives a reload, it can hold stale data, it's read at the boundary. That's all the example needs to make the recordable-coeffect point land, with nothing else in the frame competing for your attention.

## Official assets

The example wears the real TodoMVC skin — the official upstream CSS
rather than a hand-rolled lookalike — so the rendered surface matches
the template you already know:

- `todomvc-common` `1.0.5` — ships `base.css`
- `todomvc-app-css` `2.4.3` — ships `index.css`

## Running it

```bash
shadow-cljs watch examples/todomvc
```

Then open the served page in a browser. No backend ships — persistence
runs entirely against the browser's own localStorage.

## Cross-references

- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the routing surface the filter rides on.
- [`examples/reagent/routing/`](../routing/) — routing on its own, as the focused worked example.
- [`examples/reagent/realworld/`](../realworld/) — the full Conduit app on the same primitives, if you want to see them at scale.
