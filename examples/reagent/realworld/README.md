# RealWorld (Conduit) in re-frame2

> **Canonical multi-artefact integration test.** This example is the canonical multi-artefact integration test for re-frame2. It exercises `day8/re-frame2` (core) + `-schemas` + `-machines` + `-routing` + `-flows` + `-http` together in a single app. CI runs its CLJS fixtures on every PR via `npm run test:cljs` from `implementation/` (the example tree is test-free — `npm run test:adapter-smokes` drives only the three adapter smokes and never builds this example). When a per-artefact change accidentally breaks cross-artefact composition, this is the test that catches it. See [docs/release-process.md](../../../docs/release-process.md) for how this slots into the multi-artefact deploy pipeline.

The canonical re-frame2 demo for **Spec 014 — `:rf.http/managed`**. Built on the [RealWorld spec](https://github.com/gothinkster/realworld), the de-facto cross-framework benchmark for SPA frameworks.

The goal here is breadth: show how the current re-frame2 surface composes across auth, routing, remote data, forms, machines, optimistic updates, and SSR-related payload concerns — all on top of `:rf.http/managed` for HTTP.

**Status: worked sketch.** This is not presented as a polished production clone. It is a broad, current-API example set: every major RealWorld page now has a concrete namespace, and each namespace demonstrates the intended re-frame2 shape even where the implementation remains sketch-level.

## What this example demonstrates from Spec 014

The normative contract lives in [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md). The realworld example specifically exercises:

- **Default reply addressing** — `realworld.comments/:article/load` issues `:rf.http/managed` with no explicit `:on-success` / `:on-failure` and branches its handler on `(:rf/reply msg)` for both the initial dispatch and the reply paths. One handler, two roles.
- **Explicit `:on-success` / `:on-failure`** — every other endpoint (auth, articles list, profile, comments, favourites, follow, settings, editor) uses the separate-handler shape: a small DB-only handler per success / failure that destructures `{:keys [value]}` / `{:keys [failure]}` from the appended reply payload.
- **Schema-driven decode** — every request passes a Malli schema (`schema/UserResponse`, `ArticlesResponse`, `ArticleResponse`, `CommentsResponse`, `CommentResponse`, `ProfileResponse`, `TagsResponse`) as `:decode`. Decode runs only on 2xx (Spec 014 §Classification order); a 4xx HTML page never produces a `:rf.http/decode-failure`.
- **Schema reflection** — every event handler that issues a managed request declares `:rf.http/decode-schemas` in its registration metadata. Tooling can introspect via `(rf/handler-meta :event :articles/load)` without invoking the handler (Spec 014 §Schema reflection).
- **Retry + backoff** — read-only data fetches (articles list, profile, article detail, comments, feed) carry the shared `data-fetch-retry` policy: 3 attempts on `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}` with exponential backoff + jitter. Login / register / settings / submit / delete deliberately do NOT retry — single user-initiated action per click.
- **Abort by `:request-id`** — `:articles/load` and `:feed/load` are tagged with stable `:request-id` keywords (and the per-slug requests with vector ids like `[:article/load slug]`); `(:articles/cancel)` and `(:feed/cancel)` issue `:rf.http/managed-abort` to cancel an in-flight load when the user navigates away or re-issues mid-fetch.
- **Frame awareness** — replies route back to the originating frame automatically (Spec 014 §Frame awareness); the test fixtures spin per-test frames via `make-frame` and assert against `(app-db-value f)`.
- **Failure projection** — `realworld.http/failure->message` projects the closed-set `:rf.http/*` failure categories (`:rf.http/transport`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`) to human-readable messages, surfacing the Conduit `{:errors {:body [...]}}` shape when present.

## Other patterns this example exercises

- **Auth state machine** — login, register, session-restore, logout as one machine (Spec 005); each transition issues a managed-HTTP request and routes the reply through machine actions.
- **Pattern-NineStates** — the home page (`articles.cljs`) uses a parallel-region state machine (`:realworld/articles-home`) with three orthogonal axes (`:feed` × `:filter` × `:data`); a render-priority table + a selector sub (`:articles.home/render`) collapse the tag union to a single render keyword so the root view is a `case`, not a priority `cond`. The profile pages (`profile.cljs`) apply the same shape at smaller scale: a `:ui/profile` parallel machine with two regions (`:tab` × `:data`), its own render-priority table, and a `:profile/render` selector sub — the cross-axis "which slice's articles to render" math lives in the machine instead of a sub that branches on the route id. The canonical worked example is in `examples/reagent/nine_states/`; this is the production-shaped variant. See [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md).
- **Pattern-RemoteData — two shapes side-by-side.** This example exercises **both** Pattern-RemoteData shapes so a reader can compare:
  - **Slice form** (seven resources: your feed, article detail, comments, profile banner, authored articles, favorited articles, and the global articles list). Each carries the standard 5-key `{:status :data :error :loaded-at :attempt}` slice in app-db; `:status` is an explicit field; the derived `:loading?` / `:fetching?` boolean subs drive view-level branches. The home page's `:articles` slice keeps its slice shape for the optimistic-update paths (`favorites.cljs/find-article` scans across `[:articles :feed :profile.articles :profile.favorites]`) while the home-page render decision is driven by the home parallel machine's tags.
  - **`:data-region` machine form** (one resource: popular tags, in `tags.cljs`). The Pattern-RemoteData status enum (`:idle :loading :fetching :loaded :error`) maps **one-to-one** onto states of a single-region `:realworld/tags` machine; the slice's `:status` field disappears because the region's state-keyword IS the status. Items + error + loaded-at + attempt live in the machine's shared `:data`. The `:loading?` / `:fetching?` derived boolean subs collapse into `:tags/loading` / `:tags/in-flight` tags queried with `rf/machine-has-tag?` — the view doesn't need to know which state-keyword carries the "in-flight" intent. (The canonical worked example of the same shape, scaled to nine states, is in `examples/reagent/nine_states/`.)

  **When to choose each.** Pick the **slice form** when the resource interacts with optimistic-update code that scans across multiple slices (favorites toggle, comment delete), when the data is read from many sites at the same path (existing schemas already attach to `[:resource]` / `[:resource :data]`), or when the resource has no per-state UI distinctions worth naming. Pick the **machine form** when the lifecycle is itself a workflow you want to enumerate and test in isolation, when you'd otherwise be authoring derived boolean subs (`:loading?`, `:fetching?`, `:has-data?`) and a priority `cond` over them, or when the resource composes with other axes (forms, modes, tabs) — at that point the per-region machine is what Pattern-NineStates' parallel-region shape is built from.
- **Pattern-Forms — two shapes side-by-side.** This example exercises **both** Pattern-Forms shapes so a reader can compare:
  - **Slice form** (four forms: `:auth :login-form`, `:auth :register-form`, `:editor`, `:comment-form`). Each carries the standard `{:draft :submitted :status :errors :touched :submit-error}` slice in app-db; `:status` is an explicit field (`:idle | :submitting`); the derived `:submitting?` boolean sub drives view-level disabled-attribute toggles. The article-editor slice adds `:mode :baseline` axes for the unsaved-changes guard.
  - **`:form-region` machine form** (one form: settings, in `settings.cljs`). The Pattern-Forms lifecycle (`:neutral` / `:incorrect` / `:correct` / `:submitting`) maps **one-to-one** onto states of a single-region `:settings/form` machine; the slice's `:status` field disappears because the region's state-keyword IS the status. Draft + errors + touched + submit-error + submitted + loaded-at live in the machine's shared `:data`. The `:submitting?` derived boolean sub collapses into a `:settings/in-flight` tag queried with `rf/machine-has-tag?` — the view doesn't need to know which state-keyword carries the "in-flight" intent. (The canonical worked example of the same shape, scaled to nine states, is in `examples/reagent/nine_states/`.)

  **When to choose each.** Pick the **slice form** when the form's `:status` is the only lifecycle axis worth modelling, when the form is one of many in the same feature (and consistent shape across them buys you copy-paste fluency), or when the form is intentionally small enough that a state machine is over-engineering. Pick the **machine form** when the lifecycle is itself a workflow you want to enumerate and test in isolation, when you'd otherwise be authoring derived boolean subs (`:submitting?`, `:has-errors?`, `:can-submit?`) and a priority `cond` over them, or when the form composes with other axes (modes, tabs, optimistic-update windows) — at that point the per-region machine is what Pattern-NineStates' parallel-region shape is built from.
- **Flows (Spec 013)** — the article editor registers a `:editor/can-submit?` flow (`article_editor.cljs`) that materialises a derived boolean — "the draft is valid AND differs from the loaded baseline" — into app-db at `[:editor :can-submit?]`. The `:editor/submit` handler reads it as plain app-db data to gate the submit (Spec 013 §Sub integration (a) — no subscribe ceremony inside the handler); the submit button reads the same path through a plain sub. The flow is registered per-frame via `:rf.fx/reg-flow` from `:editor/initialise` so it is frame-correct under the per-test `make-frame` fixtures. This is the `-flows` artefact in the composition claim above. The dedicated, standalone Flows exemplar (a shopping cart with a flow-reads-flow cascade and a runtime-toggleable discount) lives at [`examples/reagent/flows/`](../flows/).
- **Routing** — route table, path params, query params, auth gating via the `auth-guard` interceptor (`routing.cljs`, wired into the demo frame's `:interceptors` in `core.cljs` — `:requires-auth`-tagged routes redirect unauthenticated users to login per Spec 012 §Redirects and guards), route-driven loads, and navigation blocking for the editor.
- **Pagination** — every article list (the home global feed, the tag list, the authenticated "Your Feed", and the profile authored / favorited lists) paginates with the official RealWorld `limit` / `offset` query params and consumes the response's grand `articlesCount` to size a 1-indexed page-number control. The page rides the **route query** (`?page=N`) so back/forward and bookmarking restore it; `:int` coercion turns the URL's `"2"` into `2` and `:query-defaults {:page 1}` fills page 1 when absent (`routing.cljs`). The page-number control renders the canonical Conduit `.pagination` / `.page-item.active` / `.page-link` markup (the shared `articles/pagination` view, reused by the home page and the profile pages). The 1-indexed page → 0-based `offset` math and the `(ceil articlesCount / page-size)` page count live in `http.cljs` (`page-size`, `page->offset`, `page-count`, `paginate-path`). Switching feed or tag resets to page 1; a page click re-fires the active route's `:on-match` with the new window (Spec 012 — same route, changed query). The demo stub (`core.cljs`) serves a 25-article corpus sliced by the request's `limit` / `offset` so all three pages are exercisable offline.
- **Optimistic updates** — favorite toggle, comment delete, and follow/unfollow all show rollback-friendly event shapes against the managed-HTTP failure path.
- **Article-detail contextual controls** — per the official Conduit article-page template, the detail page (`comments.cljs`) renders the author byline plus, for a non-author viewer, Follow/Unfollow the author (`:article/toggle-follow-author`, optimistic + rollback against the article's embedded `:author`), or, for the author, Edit Article (→ `/editor/:slug`) + Delete Article (`:article/delete`, navigate home on success). Logged-out viewers see the byline only. The editor's own Delete (`article_editor.cljs`) remains reachable too.
- **Schemas** — wire payloads and app-db slices are attached with `reg-app-schemas` (the bulk plural form) — one `{path -> schema}` map registers all 22 slices in `schema.cljs`.
- **Egress classification (EP-0025)** — this app carries a genuine secret (the JWT), so it classifies the *path* it lives at and lets the framework project it at the trust boundary. The durable app-db fact — the token at `[:auth :token]` — is classified by the `:sensitive` **commit-plane classification effect** the `:auth/classify-token` init event returns alongside `:db` (`core.cljs`); the transient decoded reply that introduces it (`schema/User`'s `:token` slot) carries the per-slot `:sensitive?` malli property on its `:decode` schema (`schema.cljs`). Off-box egress (Xray / observability capture, an off-box tool, an SSR hydration payload) sees the token redacted; on-box use (the live header the request actually sends, the navbar) keeps the raw value. Those are two *separate* path declarations on two surfaces — classification is fail-open and does not propagate, so each surface a secret crosses is classified on its own. The outbound `Authorization` Bearer header is **not** declared — it is already on the framework's immutable built-in HTTP carrier denylist (Spec 014 §Privacy), redacted off-box with no app config; the frame's `:sensitive {:http :headers}` extension is for app-specific carriers, which this app has none of. The password form drafts under `[:auth :*-form]` are deliberately *not* classified — transient form state owned by its registration, never sent off-box from app-db. Egress classification appears here because the app actually has sensitive data, not as a sprinkled showcase.
- **SSR boundary** — the app-specific hydration payload helper lives alongside the generic SSR worked example in `examples/reagent/ssr/`.

## Files

| File | Status | Notes |
|---|---|---|
| `core.cljs` | implemented | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` stub. The stub serves a 25-article corpus sliced by the request's `limit` / `offset` (with the grand `articlesCount`) so pagination works offline. The `:auth/classify-token` init event classifies the durable JWT path (EP-0025 commit-plane `:sensitive` effect: `[[:auth :token]]`). |
| `schema.cljs` | implemented | Wire shapes (User/Profile/Article/...) and their per-endpoint response wrappers used as `:decode` schemas. The shared `RequestSlice` carries an optional `:articles-count` (the pagination grand total). `User`'s `:token` slot carries the per-slot `:sensitive?` property on its `:decode` schema (the transient-body route) so the JWT is redacted out of off-box reply captures. |
| `http.cljs` | implemented | `request` builder + `data-fetch-retry` policy + `failure->message` projector for `:rf.http/managed`, plus the pagination helpers (`page-size`, `page->offset`, `page-count`, `paginate-path`) shared by every paginated list. |
| `routing.cljs` | implemented | Route table + the `auth-guard` interceptor (Spec 012 §Redirects and guards), wired into the demo frame in `core.cljs`. Browser wiring. Anchors render via `rf/route-link` (framework-shipped, registered at `:route/link`). The tag filter is the official `/tag/:tag` PATH route (`:realworld/home-tag`); the home + tag + profile routes carry a `?page=N` query (`:int`-coerced, `:query-defaults {:page 1}`) for pagination. |
| `auth.cljs` | implemented | Auth machine plus login/register forms (managed-HTTP). |
| `articles.cljs` | implemented | Home page, global feed, tag filter UI; managed-HTTP with retry + abort. Home page uses Pattern-NineStates — a `:realworld/articles-home` parallel machine with three regions (`:feed` × `:filter` × `:data`) + a render-priority table; the root view is a `case` over `:articles.home/render`. Popular-tags loading moved to `tags.cljs`. Hosts the shared `pagination` view (`.pagination` / `.page-item` / `.page-link`) reused by the profile pages, and the home pagination subs. |
| `favorites.cljs` | implemented | Favorite toggle and followed-authors feed; optimistic updates with managed-HTTP rollback. The authenticated feed paginates via `?page=` like the global feed. |
| `comments.cljs` | implemented | Article detail page, comments list, comment form, optimistic delete, plus the article-detail contextual controls (author Follow/Unfollow for non-author viewers; Edit / Delete for the author). **`:article/load` uses default reply addressing.** The article body is rendered through `realworld-shared.markdown/render` (sanitized CommonMark → hiccup). |
| `realworld_shared/avatar.cljs` | implemented | Shared (both realworld apps) default-avatar helper — `avatar-src` falls a nil/empty author/user image back to `default-avatar.svg` on every `.user-img` / `.user-pic` / `.comment-author-img` (RealWorld contract conformance). |
| `default-avatar.svg` | asset | The fallback avatar asset, served from the app root. |
| `realworld_shared/markdown.cljs` | implemented | Shared (both realworld apps) CommonMark → hiccup renderer for the article body, built on `io.github.nextjournal/markdown` in hiccup-emitting mode (full CommonMark: headings, emphasis, code, links, images, tables, nested lists, blockquotes). **Sanitized by construction**: emits hiccup (never raw HTML / `dangerouslySetInnerHTML`) so React escapes all text; raw inline/block HTML degrades to inert escaped text; and link/image URL schemes are allowlisted (http/https/mailto + relative) so `javascript:`/`data:`/`vbscript:` URLs are dropped. Supersedes the hand-rolled per-app subset. |
| `article_editor.cljs` | implemented | New/edit/delete article plus unsaved-change guard. Hosts the `:editor/can-submit?` **flow** (Spec 013) — a materialised valid-AND-dirty boolean read by the submit handler and the submit button. |
| `profile.cljs` | implemented | Profile banner, authored/favorited tabs, follow/unfollow. Uses Pattern-NineStates — a `:ui/profile` parallel machine with two regions (`:tab` × `:data`) + a render-priority table; the root view is a `case` over `:profile/render`. Each tab's article list paginates via `?page=` (reusing `articles/pagination`). |
| `settings.cljs` | implemented | User settings form and logout affordance. Form lifecycle as the **`:form-region` machine** variant of Pattern-Forms — `:settings/form` is a single-region state machine whose state-keyword IS the Pattern-Forms lifecycle. |
| `tags.cljs` | implemented | Popular-tags lifecycle as the **`:data-region` machine** variant of Pattern-RemoteData — `:realworld/tags` is a single-region state machine whose state-keyword IS the Pattern-RemoteData status. Also home-page navigation helpers: the `/tag/:tag` PATH route, the `?feed=following` toggle, and `?page=` via `:home/show-page`. |
| `ssr.cljc` | implemented | RealWorld-specific hydration payload helper; pairs with `../ssr/core.cljc`. |

## Architecture references

- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — **`:rf.http/managed`** (this is the canonical demo).
- [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md)
- [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md)
- [`spec/Pattern-Forms.md`](../../../spec/Pattern-Forms.md)
- [`spec/012-Routing.md`](../../../spec/012-Routing.md)
- [`spec/013-Flows.md`](../../../spec/013-Flows.md)
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md)
- [`spec/011-SSR.md`](../../../spec/011-SSR.md)

## How to run

The example's behaviour is verified by its CLJS test fixtures (see
**Headless tests** below). From `implementation/`:

```bash
npm run test:cljs
```

To view the app in a browser, build it under shadow-cljs id `examples/realworld` from `implementation/`:

```bash
shadow-cljs watch examples/realworld
# then open the example's index.html (served however you prefer).
```

### Running against a real backend

The app supports three backend modes; the default needs no network.

1. **Canned demo stub (default).** The demo entry (`core.cljs`) installs an in-process `:rf.http/managed` override (`:realworld.demo/http-stub`) that synthesises canned Conduit-shaped responses (a 25-article corpus sliced by `limit`/`offset`, tags, profile, auth) — Spec 014 §Testing. `api-base` is **not contacted** in this mode; the stub matches on the URL path suffix. This is what the browser build and the CLJS fixtures use, so everything runs offline.

2. **Official hosted API.** Point `realworld.http/api-base` at the current official hosted API — <https://api.realworld.show/api> (the old `api.realworld.io` host is stale) — and remove the `:fx-overrides {:rf.http/managed :realworld.demo/http-stub}` line from the demo frame in `core.cljs`. The Bearer token is injected by the frame-wide `:realworld/bearer-auth` HTTP interceptor (`core.cljs`), so authenticated calls (feed, favourite, follow, comment, settings, save) carry the header automatically.

3. **Local reference backend.** The upstream spec ships a Node/Postgres reference backend that listens on `http://localhost:3000/api`; set `api-base` to that and drop the stub override as in mode 2.

## RealWorld contract conformance

This example is **code-conformant** with the official RealWorld browser/E2E contract (the upstream Cypress E2E suite + the Newman/Postman API collection) — the external official suites validate against it; there is **no in-repo test harness and no `*.spec.cjs`** (the examples tree is test-free). What "conformance" means concretely:

- **Route shapes.** The official frontend route surface — `/`, `/login`, `/register`, `/settings`, `/editor`, `/editor/:slug`, `/article/:slug`, `/profile/:username`, `/profile/:username/favorites`, the tag list at the PATH route `/tag/:tag` (with `/tag/:tag?page=N`), and the following feed at `/?feed=following`. Pagination rides `?page=N` (1-indexed) on every list. The tag filter is a path param (NOT `?tag=`) and the following token is `following` (NOT `your`).
- **Session storage.** The JWT is persisted under `localStorage["jwtToken"]` — the exact contract key (`auth.cljs`). **Same-origin caveat:** the contract assumes **one app per origin**. The repo's dev orchestrator serves both this app and the resources variant from a *single* origin (at `/realworld/` and `/realworld-resources/`), so the two conforming apps share — and clobber — each other's `jwtToken` there. That is a known dev-mode artifact, not a contract violation: conformance is validated against **standalone serving** (one app per origin), which the external suite does anyway (see the runbook below).
- **Debug accessor.** `window.__conduit_debug__` exposes `getToken` / `getAuthState` / `getCurrentUser` for the external harness to introspect (`core.cljs`). This is a **conformance-contract surface, NOT a re-frame2 pattern** — an unannotated global token accessor bypasses the frame/sub system and leaks the raw JWT; it is comment-marked as such and a production RealWorld app would not ship it.
- **Form input `name` attributes.** `username` / `email` / `password` (login, register, settings), `image` / `bio` (settings), `title` / `description` / `body` / `tags` (editor) — so the suite can target inputs by `name`.
- **Default avatar.** A nil/empty author or user image falls back to `default-avatar.svg` (served from the app asset root) on every `.user-img` / `.user-pic` / `.comment-author-img`; the navbar shows the authenticated user's `.user-pic`. Centralised in `realworld-shared/avatar.cljs`.
- **Empty-list marker.** Empty list states carry the official `.empty-feed-message` selector.
- **Favorite / follow conventions.** The article-detail favorite control shows visible **Favorite** / **Unfavorite** text and toggles `.btn-outline-primary` (not favorited) ↔ `.btn-primary` (favorited). The profile follow control shows **Follow** / **Unfollow** (`.btn-outline-secondary`, per the official template). The compact heart-only card button stays `.btn-outline-primary` (also per the official client).

### Validation runbook (external official suite)

Conformance is claimed against an **actual external run**, not an in-repo assertion. To exercise it:

1. **Build the app standalone** (one app per origin — this sidesteps the same-origin `jwtToken` caveat above). From `implementation/`:
   ```bash
   npx shadow-cljs release examples/realworld   # → out/examples/realworld/
   ```
   Serve `examples/reagent/realworld/index.html` + the built `out/examples/realworld/main.js` from a single static origin (e.g. `npx http-server`), with the app mounted at `/` (drop the `/realworld` base-path the orchestrator uses — set `realworld.routing/set-base-path!` to `""`, or serve at that sub-path and point the suite there).
2. **Point the app at a real backend** (modes 2/3 above): set `realworld.http/api-base` to the hosted Conduit API (`https://api.realworld.show/api`) or a local reference backend (`http://localhost:3000/api`) and remove the demo-stub `:fx-overrides` line in `core.cljs`. The real-backend modes are live here (the auth/session work makes the JWT path real); the canned stub cannot exercise the API-collection contract.
3. **Run the official suites externally** against the served origin:
   - **Cypress E2E:** clone the upstream `gothinkster/realworld` Cypress spec and run it with `CYPRESS_baseUrl=<your-origin>`.
   - **Newman/Postman API:** run the upstream `Conduit.postman_collection.json` against the backend with `newman run … --env-var APIURL=<api-base>`.

Record the run result in the PR. **Rows that need the hosted backend** (favorite/follow round-trips, comment post/delete, settings save) cannot be exercised against the canned stub; if a hosted backend is unavailable, note honestly which rows were and were not executed rather than claiming conformance from an in-repo test alone (the works-on-my-test failure mode).

## Headless tests

The headless tests are browserless sketches. The example tree is
test-free, so the per-feature fixtures + the canned-stub
helpers live in the integration test at
[`implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs`](../../../implementation/adapters/reagent/test/re_frame/realworld_cljs_test.cljs)
— folded inline. Each helper stubs `:rf.http/managed` via
`:fx-overrides`, delegating to the framework-shipped canned-stub fxs
(`:rf.http/managed-canned-success` / `:rf.http/managed-canned-failure`)
through small `reg-canned-*` wrappers in that ns.

Each example source ns has a matching `deftest`:

| Source ns | deftest |
|---|---|
| `realworld.auth` | `realworld-auth-flow` |
| `realworld.articles` | `realworld-articles-feed` |
| `realworld.article-editor` | `realworld-article-editor` |
| `realworld.comments` | `realworld-comments` |
| `realworld.favorites` | `realworld-favorites` |
| `realworld.profile` | `realworld-profile` |
| `realworld.settings` | `realworld-settings` |
| `realworld.tags` | `realworld-tags` |
| `realworld.routing` | `realworld-routing` |
| `realworld.ssr` | `realworld-ssr` |
| `realworld.core` | `realworld-core-smoke` |

The shadow-cljs `node-test` build picks the integration test up
(`re-frame.realworld-cljs-test`); run with `npm run test:cljs` from the
`implementation/` directory.

Together the fixtures exercise the user-visible flow against the
`:realworld.demo/http-stub` override: the initial-load shell
(navbar, global feed, sidebar tags), article-detail navigation, the
auth machine end-to-end (login → authed navbar), and an optimistic
comment-submission round-trip through the comment form.

## Why RealWorld

- **Cross-framework comparability.** The same app exists in React, Vue, Svelte, Solid, Elm, and many more.
- **Larger than 7GUIs.** 7GUIs nails the primitives; RealWorld shows how they combine into a recognisable product shape.
- **Pattern coverage.** This is where the routing, machine, forms, remote-data, optimistic-update, and managed-HTTP stories meet each other.
- **AI-friendly breadth.** The code is intentionally direct, repetitive where useful, and split by feature so individual flows stay easy to follow.
