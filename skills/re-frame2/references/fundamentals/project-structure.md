# Project structure

Where each kind of file goes in a re-frame2 CLJS project. Load before writing any non-trivial code so new files land in the slots their peers expect.

> Assumes `src/`/`test/` split and CLJS namespace ↔ path rules; only re-frame2-specific placements appear here.
>
> **The structure is portable; the paths are not.** The `examples/...` / `tools/...` citations point at the re-frame2 *source repo's* worked examples — evidence for the shape, not directories your app will have. Substitute your own segment (`myapp/` below).

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

Mirrors `examples/real-apps/realworld_http/` (`examples/real-apps/realworld_http/core.cljs:1-63`,
`README.md:43-59`). Replace `myapp` with the project's own segment.

## Source files — one feature, one .cljs

Each feature owns one `.cljs` file at the top of the project namespace, registering everything it owns: events, subs, fxs, cofxs, views, machines. Don't split a feature across `events.cljs` / `subs.cljs` / `views.cljs` — that pre-fragments code that should be read together. The single-feature shape (machine + supporting events + subs + views in one ns) is what `realworld/auth.cljs`, `articles.cljs`, and `comments.cljs` show (`examples/real-apps/realworld_http/auth.cljs:1-23`).

Tiny apps that don't carve into features (counter, login) collapse to a single `core.cljs` (`examples/core/counter/core.cljs`, `examples/core/login/core.cljs`). When the app grows past a single file, promote each section into its own feature file before any one grows past ~400 lines.

## Tests — sibling `test/` tree, mirroring source

In a consumer app, tests live in a sibling `test/` directory rooted at the same namespace. Each `src/myapp/foo.cljs` has a peer `test/myapp/foo_test.cljs` (the `_test` suffix is the convention; the test ns matches — `(ns myapp.foo-test ...)`). A `test_helpers.cljs` at the root owns shared fixture helpers (canned-stub wrappers, frame builders); per-test files require it as `[myapp.test-helpers :as th]`.

(This dev repo's own `examples/` tree is deliberately *test-free* — the examples' headless fixtures live in the framework test tree. A repo-internal convention; a normal consumer app keeps the sibling `test/` tree above.)

## Stories — co-located with the feature

Stories ship in the same `src/myapp/` tree as the code they exercise, in `<feature>_stories.cljs` (or a `stories/` subdirectory when many share a feature). The ns is `<app>.stories.<feature>` or `<app>.<feature>.stories` (`tools/story/spec/001-Authoring.md:228-230`) — either works as long as the path tracks the ns. The stories file requires its feature's events / subs / views so registrations fire before the variant bodies are read (`tools/story/testbeds/counter_with_stories/stories.cljs:1-40`).

Story integration tests live alongside the stories file, not under `test/` — they need the same load order (`tools/story/testbeds/counter_with_stories/stories_cljs_test.cljs:1-25`).

## Schemas — one `schema.cljs` per feature tree

Boundary schemas live in one `schema.cljs` at the top of the feature tree; each `reg-app-schema` attaches a Malli schema to a path the feature owns (`examples/real-apps/realworld_http/schema.cljs:1-23`). A single project-wide `schema.cljs` is the default; a larger app that vendors a sub-feature with its own boundary may keep a feature-local `schema.cljs` the top-level one requires.

Do not co-locate schema registrations inside the feature file that uses them — one place makes "what does the app validate?" answerable by reading one file (Cardinal Rule 4: schemas at boundaries, not everywhere).

## SSR — `.cljc` with reader conditionals

Namespaces that must run on both JVM (server render) and browser
(hydrate + interactive) get a `.cljc` extension and reader
conditionals (`#?(:clj ...)` / `#?(:cljs ...)`) to split platform-
specific code. The runtime SSR walkthrough shows the canonical layout —
schema and event handlers are shared, the server's `handle-request` is
`#?(:clj ...)`, the client's `run` is `#?(:cljs ...)`
(`examples/capabilities/ssr/ssr/core.cljc:188-269`).

When SSR is one boundary of a larger app, the SSR-specific code goes in
its own `ssr.cljc` next to the other feature files
(`examples/real-apps/realworld_http/ssr.cljc:1-50`). The entry ns
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
   `re-frame.routing`, `re-frame.schemas`, `re-frame.http.managed`,
   `re-frame.ssr`) — the requires publish the late-bind hooks
   (`realworld/core.cljs:31-63`).
3. Defines `:app/initialise` (the boot event, run via the frame's `:initial-events`)
   and fans out to per-feature initialisers (`realworld/core.cljs:69-83`).
4. Defines the root view and the React root.
5. Exports a `run` fn that calls `rf/init!` with the substrate
   adapter, then renders a `frame-provider {:id …}` whose `:initial-events` seed the frame (including `:app/initialise`).

If the app server-renders, `core.cljc` (not `.cljs`) and the `run`
body sits inside `#?(:cljs ...)`; the JVM-side `handle-request` lives
in the same ns (`examples/capabilities/ssr/ssr/core.cljc:188-269`).

## Routing — one `routing.cljs`

Route registrations belong in a single `routing.cljs` — the `reg-route` table, auth-gating helpers, and a small `route-link` helper view (`examples/real-apps/realworld_http/routing.cljs:1-49`). The entry ns requires it for side-effects and calls its `install-router!` from `run` (`realworld/core.cljs:310-312`). Don't sprinkle `reg-route` across feature files — the router is one table; one file makes it grep-able and keeps the auth-guard helpers private.

## Per-frame organisation (multi-frame apps)

Most apps run a single frame — an **explicitly registered, descriptively-named** one (e.g. `:app/main`); there is no ambient default (EP-0002 — `:rf/default` is an ordinary id with no privilege, worth picking only for a tiny app or v1 migration). Apps with several frames (server-render per request, stories shell, embedded widget) name and configure each in `core`. Per-frame config — `:fx-overrides`, `:initial-events`, request interceptors — goes through the render-root `frame-provider {:id …}` config props (or `reg-frame` for a frame created outside React) (`realworld/core.cljs:575-586`). Feature files do not configure frames; they register events/subs against no particular frame and talk to a frame only through `dispatch` / `subscribe`.

A per-frame concern large enough for its own file (a "per-request server frame" helper) goes next to `core` — `server.cljc` / `client.cljs` — and `core` orchestrates the wiring.

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

---

*Derived from the canonical worked examples (`examples/real-apps/realworld_http/`, `examples/core/counter/`, `examples/core/login/`, `examples/capabilities/ssr/ssr/`, `tools/story/testbeds/counter_with_stories/`) @ main `89bd9c3`. The shape is example-driven; re-verify after substantial restructure of those examples.*
