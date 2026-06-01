# TodoMVC in re-frame2

A re-frame2 implementation of the current [TodoMVC app spec](https://github.com/tastejs/todomvc/blob/master/app-spec.md), with the original [day8/re-frame TodoMVC example](https://github.com/day8/re-frame/tree/master/examples/todomvc) kept in mind.

The shape deliberately echoes the v1 example's teaching split:

- `core.cljs` — entry point and hash-router wiring
- `db.cljs` — default app-db and localStorage cofx
- `events.cljs` — state transitions and persistence fx
- `subs.cljs` — derived views over todos and counts
- `views.cljs` — TodoMVC markup and interactions

## What it demonstrates

- The canonical TodoMVC behavior: add, edit, toggle, clear completed, remaining count, and hash-filter routing.
- Browser-only persistence via a registered fx and a cofx-backed initial load.
- A v1-style separation of data/events/subs/views, but on the current re-frame2 API surface.

### Why localStorage and not :rf.http/managed?

TodoMVC persists locally so the example stays small and dependency-free. The canonical demo of Spec 014 (`:rf.http/managed`) lives with the `realworld` example. If you're here to learn the request shape, that's where to look.

## Official assets

The example uses the official TodoMVC CSS packages, pinned in
`implementation/package.json` (so `npm install` fetches them into
`node_modules/`) rather than vendored into this repo:

- `todomvc-common` `1.0.5`
- `todomvc-app-css` `2.4.3`

That keeps the rendered surface close to the current TodoMVC template without vendoring upstream CSS into this repo.

## Running it

From `implementation/`, iterate against a live browser:

```bash
npm install
shadow-cljs watch examples/todomvc
```

Stage the `index.html` (and the TodoMVC CSS from `node_modules/`) next
to the build's `main.js` under `out/examples/todomvc/` and serve that
directory over HTTP.

Per the test-free examples policy this example carries no per-example
spec; real-regression coverage of the primitives it exercises lives in
the substrate contract tests (`npm run test:cljs`) and the framework
gates (see [`examples/README.md`](../../README.md)).
