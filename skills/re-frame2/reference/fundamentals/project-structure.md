# Project structure

Where each kind of file goes in a re-frame2 CLJS project. The shapes
below are extracted from the canonical examples; load this leaf before
writing any non-trivial code so new files land in the slots their
peers expect.

> Assumes monorepo conventions, `src/`/`test/` split, and CLJS
> namespace ↔ path rules. Only the re-frame2-specific placements
> appear here.

## The shape — at a glance

```
my-app/
├── deps.edn / shadow-cljs.edn
├── src/
│   └── myapp/
│       ├── core.cljs            ; entry; mount + dispatch :app/initialise
│       ├── schema.cljs          ; reg-app-schema for every wire boundary
│       ├── http.cljs            ; request builder + retry policy
│       ├── routing.cljs         ; reg-route table + auth guards
│       ├── auth.cljs            ; one feature → one .cljs
│       ├── articles.cljs        ; one feature → one .cljs
│       ├── comments.cljs        ; ...
│       └── ssr.cljc             ; SSR-only: cljc + reader conditionals
└── test/
    └── myapp/
        ├── test_helpers.cljs    ; shared canned-stub helpers
        ├── auth_test.cljs       ; mirrors src filename + `_test` suffix
        ├── articles_test.cljs
        └── ssr_test.cljc        ; cljc test for the cljc source ns
```

Mirrors `examples/reagent/realworld/` (`examples/reagent/realworld/core.cljs:1-63`,
`README.md:43-59`). Replace `myapp` with the project's own segment.

## Source files — one feature, one .cljs

Each feature owns one `.cljs` file at the top of the project namespace.
The feature file registers everything it owns: events, subs, fxs, cofxs,
views, machines. Don't split a feature across `events.cljs` / `subs.cljs`
/ `views.cljs` files — that pre-fragments code that should be read
together. The single-feature shape is what `realworld/auth.cljs`,
`realworld/articles.cljs`, and `realworld/comments.cljs` show:
machine + supporting events + subs + views, all in one ns
(`examples/reagent/realworld/auth.cljs:1-23`).

Tiny apps that don't carve into features (counter, login) collapse to a
single `core.cljs` and skip the per-feature step
(`examples/reagent/counter/core.cljs`, `examples/reagent/login/core.cljs`).

When the app grows past a single file, promote each section into its
own feature file before the per-feature file grows past ~400 lines.

## Tests — sibling `test/` tree, mirroring source

Tests live in a sibling `test/` directory rooted at the same namespace.
Each source file `src/myapp/foo.cljs` has a peer test
`test/myapp/foo_test.cljs` (`examples/reagent/realworld/test/realworld/`).
The `_test` suffix is the convention; the test ns matches —
`(ns realworld.auth-test ...)` (`auth_test.cljs:1`).

A `test_helpers.cljs` at the root of the test tree owns shared fixture
helpers (canned-stub registration wrappers, frame builders); per-test
files require it as `[realworld.test-helpers :as th]`
(`examples/reagent/realworld/test/realworld/test_helpers.cljs:1-51`).

## Stories — co-located with the feature

Stories ship in the same `src/myapp/` tree as the code they exercise,
in a file named `<feature>_stories.cljs` (or, when many stories share a
feature, a `stories/` subdirectory rooted at that feature). The
story authoring spec puts the ns at `<app>.stories.<feature>` or
`<app>.<feature>.stories` (`tools/story/spec/001-Authoring.md:228-230`);
either works as long as the file's path tracks the ns.

In the canonical example the four counter variants live in one
`stories.cljs` next to `events.cljs` / `subs.cljs` / `views.cljs`
(`examples/reagent/counter_with_stories/stories.cljs:1-40`). The
stories file requires its feature's events / subs / views so the
registrations fire before the variant bodies are read.

Story integration tests live alongside the stories file, not under
`test/` — they need the same load order
(`examples/reagent/counter_with_stories/stories_cljs_test.cljs:1-25`).

## Schemas — one `schema.cljs` per feature tree

Boundary schemas live in one `schema.cljs` at the top of the feature
tree. Each `reg-app-schema` call attaches a Malli schema to a path the
feature owns (`examples/reagent/realworld/schema.cljs:1-23`).

A single project-wide `schema.cljs` is the default for small-to-medium
apps. Larger apps that vendor a sub-feature with its own boundary may
keep a feature-local `schema.cljs` inside the sub-feature's directory —
the entry namespace requires the top-level `schema.cljs`, which
in turn requires the sub-feature ones.

Do not co-locate schema registrations inside the feature file that uses
them — keeping them in one place makes "what does the app validate?"
answerable by reading one file (Cardinal Rule 4: schemas at boundaries,
not everywhere; `SKILL.md:32`).

## SSR — `.cljc` with reader conditionals

Namespaces that must run on both JVM (server render) and browser
(hydrate + interactive) get a `.cljc` extension and reader
conditionals (`#?(:clj ...)` / `#?(:cljs ...)`) to split platform-
specific code. The runtime SSR walkthrough shows the canonical layout —
schema and event handlers are shared, the server's `handle-request` is
`#?(:clj ...)`, the client's `run` is `#?(:cljs ...)`
(`examples/reagent/ssr/core.cljc:188-269`).

When SSR is one boundary of a larger app, the SSR-specific code goes in
its own `ssr.cljc` next to the other feature files
(`examples/reagent/realworld/ssr.cljc:1-50`). The entry ns
(`core.cljs`) requires it; the ssr.cljc holds the hydration-payload
helper, the slice-selector, and the client bootstrap that calls
`:rf/hydrate`.

Test files that exercise cljc source must themselves be cljc:
`ssr_test.cljc` mirrors `ssr.cljc`.

## Entry namespace — `core.cljs` (or `core.cljc`)

The entry ns is named `core`. It:

1. Requires every feature ns so each feature's `reg-*` macros fire at
   load time.
2. Requires the day8 artefacts the app needs (`re-frame.machines`,
   `re-frame.routing`, `re-frame.schemas`, `re-frame.http-managed`,
   `re-frame.ssr`) — the requires publish the late-bind hooks
   (`realworld/core.cljs:31-63`).
3. Defines `:app/initialise` (the `:on-create` event) and fans out to
   per-feature initialisers (`realworld/core.cljs:69-83`).
4. Defines the root view and the React root.
5. Exports a `run` fn that calls `rf/init!` with the substrate
   adapter, dispatch-syncs `:app/initialise`, and renders.

If the app server-renders, `core.cljc` (not `.cljs`) and the `run`
body sits inside `#?(:cljs ...)`; the JVM-side `handle-request` lives
in the same ns (`examples/reagent/ssr/core.cljc:188-269`).

## Routing — one `routing.cljs`

Route registrations belong in a single `routing.cljs`. It owns the
`reg-route` table, auth-gating helpers, and a small `route-link`
helper view (`examples/reagent/realworld/routing.cljs:1-49`). The
entry ns requires `routing` for side-effects and calls its
`install-router!` from `run` (`realworld/core.cljs:310-312`).

Don't sprinkle `reg-route` calls across feature files — the router is
one table; one file makes the table grep-able and lets the auth-guard
helpers stay private.

## Per-frame organisation (multi-frame apps)

Most apps use one frame (`:rf/default`). Apps with several frames
(server-render per request, stories shell, embedded widget) name each
frame and configure it in `core`. Per-frame configuration —
`:fx-overrides`, `:on-create`, request interceptors — goes through
`reg-frame` calls at the bottom of `core.cljs`
(`realworld/core.cljs:298-306`). Feature files do not configure
frames; they assume `:rf/default` and let the entry ns reroute via
overrides.

If a per-frame concern is large enough to warrant its own file (e.g. a
"per-request server frame" helper), put it next to `core` —
`server.cljc` for the server frame, `client.cljs` for the client
frame — and let `core` orchestrate the wiring.

## Smell checks

- A feature file that doesn't require its own schema, when it crosses
  a wire boundary → the schema isn't registered.
- An `events.cljs` / `subs.cljs` / `views.cljs` split per feature →
  fragments related code; collapse to one feature file.
- Tests under `src/` (not `test/`), unless they're story-integration
  tests that need the load order.
- A `.cljs` ns required from a `.cljc` ns → the `.cljc` won't load on
  JVM; widen the source to `.cljc`.
- `reg-route` in two files → split routing table; consolidate.
