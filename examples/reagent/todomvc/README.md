# TodoMVC in re-frame2

Everyone knows TodoMVC. That's the point of it — it's the "hello world" that every framework re-implements, so you can read one and instantly map its idioms onto the next. This is that familiar app (the current [TodoMVC app spec](https://github.com/tastejs/todomvc/blob/master/app-spec.md)) built on re-frame2, with one eye kept on the original [day8/re-frame TodoMVC example](https://github.com/day8/re-frame/tree/master/examples/todomvc) so a re-frame v1 reader feels at home.

So if it's just todos, what's actually worth reading here? Two design decisions that look small and turn out to be the whole re-frame2 lesson:

1. **The filter is a *route*, not a flag.** The all/active/completed switch lives in the URL, and the rest of the app derives the filtered list from it. There's no `:showing` key in app-db at all.
2. **Persistence stays *replayable*.** The localStorage read at boot isn't done inside a handler — it rides in as a recordable **coeffect**, so the boot **event** replays to the exact same state under time-travel. More on why that distinction matters below.

Everything else is the boring, correct re-frame2 spine you'd expect: pure **event handlers**, a layered **subscription** graph, a registered **effect** for the write side, and a **view** tree that holds no business logic.

The file layout deliberately echoes the v1 example's teaching split — the same six concerns in the same six places, so the diff between v1 and v2 is the *API*, not the architecture:

- `core.cljs` — entry point, frame setup, and the hash → route adapter
- `db.cljs` — the default app-db and the localStorage coeffect
- `events.cljs` — the routes, the state transitions, and the persistence effect
- `subs.cljs` — the derived views over todos and counts
- `views.cljs` — the TodoMVC markup and interactions

## What it demonstrates

- **The canonical TodoMVC behaviour** — add, edit (double-click a todo), toggle one, toggle all, delete, clear completed, the live "items left" count, and hash-based filtering.
- **Filtering as routing, not state.** `reg-route` registers `/`, `/active`, and `/completed` (plus the reserved `:rf.route/not-found` fallback, so a stray hash never breaks the app). The `:todo/showing` subscription reads `:rf.route/id` and maps it to `:all` / `:active` / `:completed`; `:todo/visible-todos` filters off *that*. The active route **is** the filter — which is exactly the re-frame2 posture that the URL is an input and the route is just derived state you read through a sub. Because TodoMVC's URLs are hash-based (`#/active`) rather than path-based, `core.cljs` carries a tiny host-adapter that strips the `#` and dispatches `:rf.route/handle-url-change` into the URL-owning **frame** — the same contract the framework's own popstate listener implements, done by hand because this app speaks `hashchange`.
- **A layered subscription graph.** `:todo/sorted-todos` reads app-db; `:todo/todos`, `:todo/visible-todos`, `:todo/all-complete?`, `:todo/completed-count`, and `:todo/footer-counts` stack on top of it. Each layer recomputes only when its input actually moves, so the footer count doesn't re-derive when you're busy editing a title.
- **Replayable browser persistence.** A registered effect (`:todo.storage/save`) writes every change back to localStorage; the initial load comes in through a recordable coeffect (`:todo.storage/todos`) — see the next section for why that's not just "a load function in disguise."

### Why the load is a coeffect, not a `localStorage` call in a handler

This is the one genuinely subtle thing in the example, and it's worth a minute.

You *could* just read `localStorage` inside the boot handler. It would work — right up until you try to replay or time-travel that boot, at which point the handler re-reads whatever localStorage holds *now*, not what it held *then*, and the replay diverges. A durable write has to be a pure function of prior state plus the facts that arrived with the event — never of an ambient read smuggled in at the write site.

So the example does the host read **once**, at the boundary, in `core.cljs`'s `run`, and stamps the value onto the boot dispatch as a **recordable** coeffect:

```clojure
(rf/dispatch-sync [:todo/initialise]
                  {:rf.cofx {:todo.storage/todos (db/read-todos-from-storage)}})
```

The handler then just declares it needs that fact and folds it into app-db — pure, no IO:

```clojure
(rf/reg-event :todo/initialise
  {:rf.cofx/requires [:todo.storage/todos]}
  (fn [{:todo.storage/keys [todos]} _]
    {:db (assoc db/default-db :todos todos)}))
```

Because the value was *recorded* with the dispatch, a replay or epoch-restore re-presents the captured snapshot verbatim instead of re-reading the world. (If you want the full theory, this is the "recordable vs ambient coeffects" distinction from the glossary, and the persistence write itself is an ordinary registered effect — effects are data, performed by the runtime, kept out of the pure handler.) It's a lot of ceremony for a todo list, granted — but TodoMVC is exactly the right size to show the *shape* of replayable persistence without burying it under a real domain.

### Why localStorage?

Persisting locally keeps the example small and dependency-free — no backend to stand up, no request lifecycle to follow. TodoMVC is exactly the right size to show the *shape* of replayable persistence on its own, and localStorage is the simplest thing that does the job.

## Official assets

The example uses the official TodoMVC CSS packages, pinned in
`implementation/package.json` (so `npm install` fetches them into
`node_modules/`) rather than vendored into this repo:

- `todomvc-common` `1.0.5` — ships `base.css`
- `todomvc-app-css` `2.4.3` — ships `index.css`

`index.html` links both files flat (`base.css`, `index.css`), so stage
them next to `main.js` from `node_modules/` (see **Running it**). The
`index.css` href intentionally matches the `todomvc-app-css` package
file name. That keeps the rendered surface close to the current TodoMVC
template without vendoring upstream CSS into this repo.

## Running it

From `implementation/`, iterate against a live browser:

```bash
npm install
shadow-cljs watch examples/todomvc
```

Stage `index.html` next to the build's `main.js` under
`out/examples/todomvc/`, copy the two TodoMVC CSS files there from
`node_modules/` so the flat `<link>` hrefs resolve, then serve that
directory over HTTP:

```bash
cp examples/reagent/todomvc/index.html out/examples/todomvc/
cp node_modules/todomvc-common/base.css out/examples/todomvc/base.css
cp node_modules/todomvc-app-css/index.css out/examples/todomvc/index.css
```

Per the test-free examples policy this example carries no per-example
spec; real-regression coverage of the primitives it exercises lives in
the substrate contract tests (`npm run test:cljs`) and the framework
gates (see [`examples/README.md`](../../README.md)).

## Cross-references

- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — the routing surface the filter rides on.
- [`examples/reagent/routing/`](../routing/) — routing on its own, as the focused worked example.
- [`examples/reagent/realworld/`](../realworld/) — the full Conduit app on the same primitives, if you want to see them at scale.
