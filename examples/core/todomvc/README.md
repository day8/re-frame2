# TodoMVC in re-frame2

This is TodoMVC, built on re-frame2. It's the todo app everyone knows — add, edit, complete, filter — so once you've read one version you can map its ideas onto the next. This one follows the current [TodoMVC app spec](https://github.com/tastejs/todomvc/blob/master/app-spec.md), and tracks the original [day8/re-frame TodoMVC example](https://github.com/day8/re-frame/tree/master/examples/todomvc) closely, so a re-frame v1 reader lands somewhere familiar.

It's just a todo list, so what's worth your time? Two design decisions. Each looks like a throwaway detail, and each carries the real re-frame2 lesson:

1. **The filter is a *route*, not a flag.** The all/active/completed switch lives in the URL. The rest of the app derives the filtered list from it. There's no `:showing` key in app-db — the thing you'd reflexively store turns out to be something you read.
2. **Persistence stays *replayable*.** The localStorage read at boot doesn't happen inside a handler. It arrives as a recordable **coeffect** instead. So the boot **event** replays to the exact same state under time-travel, rather than re-reading whatever the browser holds now. There's a section on why that matters below.

Everything else is the plain, correct re-frame2 spine: pure **event handlers**, a layered **subscription** graph, a registered **effect** for the write side, and a **view** tree that holds no business logic.

The file layout echoes the v1 example's teaching split — the same concerns, split the same way — so diffing v1 against v2 shows what changed in the *API*, never in the architecture:

- `core.cljs` — entry point, frame setup (with the hash `:url-strategy`), and the URL-change listener
- `db.cljs` — the default app-db and the localStorage coeffect
- `events.cljs` — the routes, the state transitions, and the persistence effect
- `subs.cljs` — the derived views over todos and counts
- `views.cljs` — the TodoMVC markup and interactions

## What it demonstrates

- **The canonical TodoMVC behaviour** — add, edit (double-click a todo), toggle one, toggle all, delete, clear completed, the live "items left" count, and hash-based filtering.
- **Filtering as routing, not state.** `reg-route` registers `/`, `/active`, and `/completed`, plus the reserved `:rf.route/not-found` fallback so a stray hash never breaks the app. The `:todo/showing` subscription reads `:rf.route/id` and maps it to `:all` / `:active` / `:completed`; `:todo/visible-todos` filters off *that*. The active route **is** the filter. That's the re-frame2 posture: the URL is an input, and the route is just derived state you read through a sub. TodoMVC's URLs are hash-based (`#/active`), not path-based — so the app declares `:url-strategy routing/hash-url-strategy` (from `re-frame.routing`) on its URL-owning **frame** (`core.cljs`). That one line tells the router to speak hash: `route-url`/`match-url` stay path-form, and the strategy encodes the `#` at the four edges (link hrefs, `pushState`/`replaceState`, and the URL-change listener). So the filter links are ordinary `route-link`s and navigation goes through `:rf.route/navigate` / `:rf/url-requested`, exactly like every other example — no hand-rolled `hashchange` adapter, no hand-built `#/active` strings.
- **A layered subscription graph.** `:todo/sorted-todos` reads app-db. `:todo/todos`, `:todo/visible-todos`, `:todo/all-complete?`, `:todo/completed-count`, and `:todo/footer-counts` stack on top of it. Each layer recomputes only when its own input changes, so the footer count doesn't re-derive while you're editing a title.
- **Controlled inputs and editing state in app-db (Pattern-Forms).** The new-todo and edit-in-place inputs are *controlled*: their `:value` reads a draft sub, and their `:on-change` dispatches an edit event into a `:ui` slice. The view holds no `reagent/atom` for the text. "Which row is being edited" is application state too, so it lives at `[:ui :editing-id]` and is read through `:todo.ui/editing?`. No view-local atoms, no DOM-node refs to read `.value` off, no blur-suppression flag — just drafts and an editing id, read through subs and changed through events. The edit input still auto-focuses when a row enters edit mode, via a focus-only `:ref` that calls `.focus()` and never touches the value. See [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md).
- **Replayable browser persistence.** A registered effect (`:todo.storage/save`) writes every change back to localStorage. The initial load comes in through a recordable coeffect (`:todo.storage/todos`) — see the next section for why that's not just "a load function in disguise."

### Why the load is a coeffect, not a `localStorage` call in a handler

This is the one genuinely subtle thing in the example, and it's worth a minute.

You *could* read `localStorage` inside the boot handler. It would work — until you replay or time-travel that boot. Then the handler re-reads whatever localStorage holds *now*, not what it held *then*, and the replay diverges. A durable write has to be a pure function of prior state plus the facts that arrived with the event. Never of an ambient read smuggled in at the write site.

So the example *registers* the host read as a **recordable** coeffect: a `reg-cofx` supplier whose generator reads localStorage (`db.cljs`). The boot read decides a durable write, so the app supplies it the canonical way — a **registered recordable generator**, not a value stamped at the dispatch site. (That `:rf.cofx` dispatch option is a seam for tests and framework boundaries — not how app code supplies its own reads.)

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

The boot dispatch stays **plain** — `core.cljs` seeds the frame with `:initial-events [[:todo/initialise]]` and carries no cofx; the registered generator is the supplier. The generator runs once at processing-start, its value is recorded onto the causal token, and a replay or epoch-restore re-presents the captured snapshot verbatim instead of re-reading the world. (For the full theory, this is the "recordable vs ambient coeffects" distinction from the glossary. The persistence write itself is an ordinary registered effect — effects are data, performed by the runtime, kept out of the pure handler.)

Yes, it's a lot of ceremony to load a todo list, and on a todo list you'd never actually feel the bug it prevents. That's the point. A todo list is the place to learn the *shape*: small enough that the mechanism is the only thing in view. Once you've seen it here, you'll recognise it the day it's a 200-row spreadsheet whose replay quietly lies to you.

### Why localStorage?

Because the persistence is the lesson, not the wiring around it. A real backend drags in a server to stand up, a request lifecycle to follow, and auth to wave away — none of which teaches you anything about *replayable* writes. localStorage is the smallest thing that's a genuine durable side effect: it survives a reload, it can hold stale data, and it's read at the boundary. That's all the example needs to make the recordable-coeffect point land, with nothing else competing for your attention.

## Official assets

The example wears the real TodoMVC skin — the official upstream CSS
rather than a hand-rolled lookalike — so the rendered page matches
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
- [`examples/capabilities/routing/routing/`](../../capabilities/routing/routing/) — routing on its own, as the focused worked example.
- [`examples/real-apps/realworld_http/`](../../real-apps/realworld_http/) — the full Conduit app on the same primitives, if you want to see them at scale.
