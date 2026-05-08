# RealWorld (Conduit) in re-frame2

A worked example based on the [RealWorld spec](https://github.com/gothinkster/realworld) — the de-facto cross-framework benchmark for SPA frameworks. The goal here is breadth: show how the current re-frame2 surface composes across auth, routing, remote data, forms, machines, optimistic updates, and SSR-related payload concerns.

**Status: worked sketch.** This is not presented as a polished production clone. It is a broad, current-API example set: every major RealWorld page now has a concrete namespace, and each namespace demonstrates the intended re-frame2 shape even where the implementation remains sketch-level.

## What this example exercises

- **Auth state machine** — login, register, session-restore, logout as one machine.
- **Pattern-RemoteData** — global feed, your feed, article detail, comments, profile banner, authored articles, favorited articles, and tags all use the same lifecycle slice.
- **Pattern-Forms** — login, register, comment post, article editor, and settings reuse the same draft/submission shape.
- **Routing** — route table, path params, query params, auth gating, route-driven loads, and navigation blocking for the editor.
- **Optimistic updates** — favorite toggle, comment delete, and follow/unfollow all show rollback-friendly event shapes.
- **Schemas** — wire payloads and app-db slices are attached with `reg-app-schema`.
- **SSR boundary** — the app-specific hydration payload helper lives alongside the generic SSR worked example in `examples/ssr/`.

## Files

| File | Status | Notes |
|---|---|---|
| `core.cljs` | implemented | Entry point, app shell, route switch, mount. |
| `schema.cljs` | implemented | Wire shapes plus app-db slice schemas used by the sketch. |
| `http.cljs` | implemented | `:http` fx with auth token injection and canned test override. |
| `routing.cljs` | implemented | Route table, auth guard, route-link helper, browser wiring. |
| `auth.cljs` | implemented | Auth machine plus login/register forms. |
| `articles.cljs` | implemented | Home page, global feed, popular tags, tag filter UI. |
| `favorites.cljs` | implemented | Favorite toggle and followed-authors feed. |
| `comments.cljs` | implemented | Article detail page, comments list, comment form, optimistic delete. |
| `article_editor.cljs` | implemented | New/edit/delete article plus unsaved-change guard. |
| `profile.cljs` | implemented | Profile banner, authored/favorited tabs, follow/unfollow. |
| `settings.cljs` | implemented | User settings form and logout affordance. |
| `tags.cljs` | implemented | Home-page query helpers (`?tag=` and `?feed=your`). |
| `ssr.cljc` | implemented | RealWorld-specific hydration payload helper; pairs with `../ssr/core.cljc`. |

## Architecture references

- [`docs/specification/Pattern-RemoteData.md`](../../docs/specification/Pattern-RemoteData.md)
- [`docs/specification/Pattern-Forms.md`](../../docs/specification/Pattern-Forms.md)
- [`docs/specification/012-Routing.md`](../../docs/specification/012-Routing.md)
- [`docs/specification/005-StateMachines.md`](../../docs/specification/005-StateMachines.md)
- [`docs/specification/011-SSR.md`](../../docs/specification/011-SSR.md)

## How to run

The example assumes a shadow-cljs build aliased `realworld` (typical
`:modules {:realworld {:entries [realworld.core] :init-fn realworld.core/run}}`).

In production point `realworld.http/api-base` at <https://api.realworld.io/api>; for local development, the spec ships a Node/Postgres reference backend that listens on `http://localhost:3000/api`.

```bash
shadow-cljs watch realworld
# then open the served HTML and click around
```

The included headless tests are browserless sketches. Because most files are `.cljs`, run them in a CLJS host (Node, a `node-test` target, or a browser build without mounting the DOM-facing entrypoint):

```clojure
(realworld.auth/login-happy-path-test)
(realworld.articles/articles-load-test)
(realworld.comments/comments-load-test)
(realworld.article-editor/editor-create-test)
(realworld.profile/profile-load-test)
(realworld.favorites/favorite-toggle-test)
(realworld.tags/tag-query-test)
(realworld.settings/settings-test)
(realworld.routing/routing-tests)
(realworld.core/app-smoke-test)
```

## Why RealWorld

- **Cross-framework comparability.** The same app exists in React, Vue, Svelte, Solid, Elm, and many more.
- **Larger than 7GUIs.** 7GUIs nails the primitives; RealWorld shows how they combine into a recognisable product shape.
- **Pattern coverage.** This is where the routing, machine, forms, remote-data, and optimistic-update stories meet each other.
- **AI-friendly breadth.** The code is intentionally direct, repetitive where useful, and split by feature so individual flows stay easy to follow.
