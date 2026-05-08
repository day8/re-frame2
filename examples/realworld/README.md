# RealWorld (Conduit) in re-frame2

The canonical re-frame2 demo for **Spec 014 — `:rf.http/managed`** (per rf2-kauy and rf2-o8t6). Built on the [RealWorld spec](https://github.com/gothinkster/realworld), the de-facto cross-framework benchmark for SPA frameworks.

The goal here is breadth: show how the current re-frame2 surface composes across auth, routing, remote data, forms, machines, optimistic updates, and SSR-related payload concerns — all on top of `:rf.http/managed` for HTTP.

**Status: worked sketch.** This is not presented as a polished production clone. It is a broad, current-API example set: every major RealWorld page now has a concrete namespace, and each namespace demonstrates the intended re-frame2 shape even where the implementation remains sketch-level.

## What this example demonstrates from Spec 014

The normative contract lives in [`spec/014-HTTPRequests.md`](../../spec/014-HTTPRequests.md). The realworld example specifically exercises:

- **Default reply addressing** — `realworld.comments/:article/load` issues `:rf.http/managed` with no explicit `:on-success` / `:on-failure` and branches its handler on `(:rf/reply msg)` for both the initial dispatch and the reply paths. One handler, two roles.
- **Explicit `:on-success` / `:on-failure`** — every other endpoint (auth, articles list, profile, comments, favourites, follow, settings, editor) uses the separate-handler shape: a small DB-only handler per success / failure that destructures `{:keys [value]}` / `{:keys [failure]}` from the appended reply payload.
- **Schema-driven decode** — every request passes a Malli schema (`schema/UserResponse`, `ArticlesResponse`, `ArticleResponse`, `CommentsResponse`, `CommentResponse`, `ProfileResponse`, `TagsResponse`) as `:decode`. Decode runs only on 2xx (Spec 014 §Classification order); a 4xx HTML page never produces a `:rf.http/decode-failure`.
- **Schema reflection** — every event handler that issues a managed request declares `:rf.http/decode-schemas` in its registration metadata. Tooling can introspect via `(rf/handler-meta :event :articles/load)` without invoking the handler (Spec 014 §Schema reflection).
- **Retry + backoff** — read-only data fetches (articles list, profile, article detail, comments, feed) carry the shared `data-fetch-retry` policy: 3 attempts on `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}` with exponential backoff + jitter. Login / register / settings / submit / delete deliberately do NOT retry — single user-initiated action per click.
- **Abort by `:request-id`** — `:articles/load` and `:feed/load` are tagged with stable `:request-id` keywords (and the per-slug requests with vector ids like `[:article/load slug]`); `(:articles/cancel)` and `(:feed/cancel)` issue `:rf.http/managed-abort` to cancel an in-flight load when the user navigates away or re-issues mid-fetch.
- **Frame awareness** — replies route back to the originating frame automatically (Spec 014 §Frame awareness); the test fixtures spin per-test frames via `make-frame` and assert against `(get-frame-db f)`.
- **Failure projection** — `realworld.http/failure->message` projects the closed-set `:rf.http/*` failure categories (`:rf.http/transport`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`) to human-readable messages, surfacing the Conduit `{:errors {:body [...]}}` shape when present.

## Other patterns this example exercises

- **Auth state machine** — login, register, session-restore, logout as one machine (Spec 005); each transition issues a managed-HTTP request and routes the reply through machine actions.
- **Pattern-RemoteData** — global feed, your feed, article detail, comments, profile banner, authored articles, favorited articles, and tags all use the same lifecycle slice (Pattern-RemoteData).
- **Pattern-Forms** — login, register, comment post, article editor, and settings reuse the same draft/submission shape.
- **Routing** — route table, path params, query params, auth gating, route-driven loads, and navigation blocking for the editor.
- **Optimistic updates** — favorite toggle, comment delete, and follow/unfollow all show rollback-friendly event shapes against the managed-HTTP failure path.
- **Schemas** — wire payloads and app-db slices are attached with `reg-app-schema`.
- **SSR boundary** — the app-specific hydration payload helper lives alongside the generic SSR worked example in `examples/ssr/`.

## Files

| File | Status | Notes |
|---|---|---|
| `core.cljs` | implemented | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` stub. |
| `schema.cljs` | implemented | Wire shapes (User/Profile/Article/...) and their per-endpoint response wrappers used as `:decode` schemas. |
| `http.cljs` | implemented | `request` builder + `data-fetch-retry` policy + `failure->message` projector for `:rf.http/managed`. |
| `routing.cljs` | implemented | Route table, auth guard, route-link helper, browser wiring. |
| `auth.cljs` | implemented | Auth machine plus login/register forms (managed-HTTP). |
| `articles.cljs` | implemented | Home page, global feed, popular tags, tag filter UI; managed-HTTP with retry + abort. |
| `favorites.cljs` | implemented | Favorite toggle and followed-authors feed; optimistic updates with managed-HTTP rollback. |
| `comments.cljs` | implemented | Article detail page, comments list, comment form, optimistic delete. **`:article/load` uses default reply addressing.** |
| `article_editor.cljs` | implemented | New/edit/delete article plus unsaved-change guard. |
| `profile.cljs` | implemented | Profile banner, authored/favorited tabs, follow/unfollow. |
| `settings.cljs` | implemented | User settings form and logout affordance. |
| `tags.cljs` | implemented | Home-page query helpers (`?tag=` and `?feed=your`). |
| `ssr.cljc` | implemented | RealWorld-specific hydration payload helper; pairs with `../ssr/core.cljc`. |

## Architecture references

- [`spec/014-HTTPRequests.md`](../../spec/014-HTTPRequests.md) — **`:rf.http/managed`** (this is the canonical demo).
- [`spec/Pattern-RemoteData.md`](../../spec/Pattern-RemoteData.md)
- [`spec/Pattern-Forms.md`](../../spec/Pattern-Forms.md)
- [`spec/012-Routing.md`](../../spec/012-Routing.md)
- [`spec/005-StateMachines.md`](../../spec/005-StateMachines.md)
- [`spec/011-SSR.md`](../../spec/011-SSR.md)

## How to run

The example assumes a shadow-cljs build aliased `examples/realworld` (typical
`:modules {:realworld {:entries [realworld.core] :init-fn realworld.core/run}}`).

In production point `realworld.http/api-base` at <https://api.realworld.io/api>; for local development, the spec ships a Node/Postgres reference backend that listens on `http://localhost:3000/api`. The demo entry installs an in-process `:rf.http/managed` override (`:rf.http/managed.realworld-demo`) that synthesises canned responses for the common reads (global feed, tags, profile) — Spec 014 §Testing — so the headless smoke and Playwright run without a network.

```bash
shadow-cljs watch examples/realworld
# then open the served HTML and click around
```

## Headless tests

The headless tests are browserless sketches. They live alongside the
sources at `test/realworld/<feature>_test.cljs`, mirroring the source
namespaces. Each test stubs `:rf.http/managed` via `:fx-overrides`,
delegating to the framework-shipped canned-stub fxs
(`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`)
through small wrappers in `test/realworld/test_helpers.cljs`.

| Source ns | Test ns |
|---|---|
| `realworld.auth` | `realworld.auth-test` |
| `realworld.articles` | `realworld.articles-test` |
| `realworld.article-editor` | `realworld.article-editor-test` |
| `realworld.comments` | `realworld.comments-test` |
| `realworld.favorites` | `realworld.favorites-test` |
| `realworld.profile` | `realworld.profile-test` |
| `realworld.settings` | `realworld.settings-test` |
| `realworld.tags` | `realworld.tags-test` |
| `realworld.routing` | `realworld.routing-test` |
| `realworld.ssr` | `realworld.ssr-test` |
| `realworld.core` | `realworld.core-test` |

Each test fn is a plain zero-arg `defn`; failures throw via `assert`. The
shadow-cljs `node-test` build picks them up via the integration wrapper
at `implementation/test/re_frame/realworld_cljs_test.cljs` (run with
`npm run test:cljs` from the `implementation/` directory).

The Playwright spec at `realworld.spec.cjs` exercises the user-visible
flow against the `:rf.http/managed.realworld-demo` override and runs as
part of `npm run test:examples`.

## Why RealWorld

- **Cross-framework comparability.** The same app exists in React, Vue, Svelte, Solid, Elm, and many more.
- **Larger than 7GUIs.** 7GUIs nails the primitives; RealWorld shows how they combine into a recognisable product shape.
- **Pattern coverage.** This is where the routing, machine, forms, remote-data, optimistic-update, and managed-HTTP stories meet each other.
- **AI-friendly breadth.** The code is intentionally direct, repetitive where useful, and split by feature so individual flows stay easy to follow.
