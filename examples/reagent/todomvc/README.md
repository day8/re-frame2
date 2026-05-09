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
- Headless browser verification through the Playwright example harness.

### Why localStorage and not :rf.http/managed?

TodoMVC persists locally so the example stays small and dependency-free. The canonical demo of Spec 014 (`:rf.http/managed`) lives with the `realworld` example. If you're here to learn the request shape, that's where to look.

## Official assets

The example stages the official TodoMVC CSS packages at test/build time:

- `todomvc-common` `1.0.5`
- `todomvc-app-css` `2.4.3`

That keeps the rendered surface close to the current TodoMVC template without vendoring upstream CSS into this repo.

## Running it

From `implementation/`:

```bash
npm install
npm run test:examples
```

That compiles the example, stages its HTML and TodoMVC CSS assets into `out/examples/todomvc/`, serves the output, and runs the Playwright smoke spec.
