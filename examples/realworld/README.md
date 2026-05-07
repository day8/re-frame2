# RealWorld (Conduit) in re-frame2

A worked example based on the [RealWorld spec](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark for SPA frameworks. The reference implementations on the [RealWorld page](https://codebase.show/projects/realworld) cover dozens of frontend frameworks; reading any two side-by-side shows the differences in shape concretely.

**Status: partially implemented.** This is a scaffolded worked example, not a finished Conduit clone. The auth flow, the article-list page, and the route table are wired end-to-end and headless-tested; the remaining pages (article detail, editor, profile, favorites, tag filter, SSR) are TODO stubs with docstrings describing what they should do. The "What's implemented" table below is the source of truth.

The implemented subset exercises re-frame2's locked surface like so:

- **Auth state machine** *(implemented)* — login, register, session-restore, logout as one machine.
- **Pattern-RemoteData** *(implemented for `articles` and `tags`; rest TODO)* — async resources use the standard 5-key slice + four standard events.
- **Routing** *(routes registered; subset exercised)* — every Conduit route is in the route table. The currently-exercised features are `reg-route` with the canonical path-pattern grammar, deterministic route ranking, `:on-match` data loading for the home page, the `:can-leave` registration shape (full editor-blocking flow lands with `article_editor.cljs`), and nav-token allocation. Fragment-aware scrolling, query-string round-tripping, and the full pending-nav UI exercise land with later TODO files.
- **Pattern-Forms** *(implemented for login + register; rest TODO)* — login form and register form follow the standard form slice; comment form, editor, and settings forms are TODO.
- **Schemas** *(implemented for the implemented slices)* — wire payloads and app-db slices used by the implemented files have Malli schemas attached via `reg-app-schema`.
- **SSR** *(TODO)* — `ssr.cljs` is a stub. See `examples/ssr/core.cljc` for the minimal SSR worked example.

## What's implemented

| File | Status | Notes |
|---|---|---|
| `core.cljs` | implemented | Entry point, mount, app-shell, header/footer/root view. |
| `schema.cljs` | implemented | Malli schemas for User, Profile, Article, Comment, Tag; the standard `RequestSlice`; per-slice schema registrations. |
| `http.cljs` | implemented | `:http` fx with auto-injected Bearer token; canned-success test stub. |
| `routing.cljs` | implemented (subset) | Every Conduit route registered. `:on-match` data loading wired for the home page; `:can-leave` editor guard registered (the full unsaved-changes flow lands with `article_editor.cljs`); pending-nav protocol scaffolding; route-link view. Several handlers are local re-statements of framework-shipped defaults — see the file's ownership-boundary docstring. |
| `auth.cljs` | implemented | Auth state machine (`:auth/flow`); login + register form events; session restore via cofx + machine; views for login/register pages. |
| `articles.cljs` | implemented | Article-list page with the canonical Pattern-RemoteData lifecycle; tags-list sidebar; home-page view. |
| `article_editor.cljs` | TODO stub | Editor (create / edit / delete). See file's docstring for what's pending. |
| `comments.cljs` | TODO stub | Comment list + post + delete. |
| `profile.cljs` | TODO stub | Profile pages: own profile, view another user, follow/unfollow. |
| `favorites.cljs` | TODO stub | Article favorite toggle (optimistic), 'Your Feed' tab. |
| `tags.cljs` | TODO stub | Tag-as-filter on the home page (the tag *list* is loaded by `articles.cljs`). |
| `ssr.cljs` | TODO stub | Server-side render + hydrate. |

Each TODO file is a valid namespace with a docstring describing the scope of that feature, the API endpoints it consumes, and which Patterns / Specs it instantiates.

## Architecture references

- [`docs/specification/Pattern-RemoteData.md`](../../docs/specification/Pattern-RemoteData.md) — RealWorld's `articles`, `tags`, `profile`, `comments` slices each instantiate this pattern.
- [`docs/specification/Pattern-Forms.md`](../../docs/specification/Pattern-Forms.md) — RealWorld's login, register, and (when implemented) comment, editor, and settings forms each instantiate this pattern.
- [`docs/specification/012-Routing.md`](../../docs/specification/012-Routing.md) — every routing locked feature is exercised here.
- [`docs/specification/005-StateMachines.md`](../../docs/specification/005-StateMachines.md) — auth flow.
- [`docs/specification/011-SSR.md`](../../docs/specification/011-SSR.md) — to be exercised by `ssr.cljs`.

## How to run

The example assumes a shadow-cljs build aliased `realworld` (typical
`:modules {:realworld {:entries [example.realworld.core] :init-fn example.realworld.core/run}}`).

In production point `example.realworld.http/api-base` at <https://api.realworld.io/api>; for local development, the spec ships a Node/Postgres reference backend that listens on `http://localhost:3000/api`.

```bash
shadow-cljs watch realworld
# then open the served HTML and click around
```

The included headless tests are browserless (no DOM, no React) and assert the implemented behaviour. Because the example files are `.cljs`, the tests run in a CLJS host (Node, shadow-cljs `node-test` target, etc.). To run on the JVM, port the testable parts of each file to `.cljc`:

```clojure
(example.realworld.auth/login-happy-path-test)
(example.realworld.auth/login-failure-test)
(example.realworld.articles/articles-load-test)
(example.realworld.articles/articles-load-failure-test)
(example.realworld.routing/routing-tests)
(example.realworld.core/app-smoke-test)
```

## Why RealWorld

- **Cross-framework comparability.** The same app exists in React, Vue, Svelte, Solid, Elm, etc. Diff with the [re-frame v1 RealWorld](https://github.com/jacekschae/conduit) to see what changes (or doesn't) in re-frame2.
- **Larger than 7GUIs.** 7GUIs nails the primitives; RealWorld is the "real app" benchmark.
- **End-to-end pattern exercise.** Auth flow (machine), routing (every locked feature), forms (every form-shaped UI), remote data (every async resource), schemas (every wire payload), SSR (server-rendered article pages).
- **AI-amenable.** The spec is unambiguous; the scaffolded shape here makes it cheap for an AI to fill in the TODO stubs one feature at a time.

## Next steps (if filling in stubs)

The TODO files are intentionally independent — each can be implemented and tested without touching the others. Suggested order:

1. `comments.cljs` — smallest; reuses Pattern-RemoteData and Pattern-Forms.
2. `profile.cljs` — exercises multiple slices for one page.
3. `favorites.cljs` — exercises optimistic-update with rollback.
4. `article_editor.cljs` — exercises `:can-leave` (the navigation-blocking flow is half-wired by `routing.cljs` already).
5. `tags.cljs` — exercises `:query` and `:query-retain` on a route.
6. `ssr.cljs` — convert to `.cljc`, add the JVM `:http` implementation, exercise `:rf/server-init` + `:rf/hydrate`.
