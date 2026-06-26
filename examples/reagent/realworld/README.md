# RealWorld (Conduit) in re-frame2

This is the example where everything has to work *at once*. Most of the other examples isolate one idea and show it cleanly — a flow here, a state machine there. RealWorld is the opposite move: it's [Conduit](https://github.com/gothinkster/realworld), a Medium-style blogging app (sign in, write articles, follow authors, favourite posts, comment, paginate) that exists in dozens of frameworks precisely so you can compare how each one holds together under a *recognisable product shape*. Re-frame2's core, schemas, machines, routing, flows, and managed HTTP all have to compose here, in one running app, without sprouting glue between them. When they do, you've learned something the single-idea examples can't teach: how the pieces fit.

The thread that ties it together is **managed HTTP** — the [`:rf.http/managed`](#architecture-references) [effect](../../../docs/guide/glossary.md#effect). Conduit is mostly a thin client over a remote API, so nearly everything interesting in it is *some* shape of "fire a request, wait, do something with the reply." Re-frame2's answer to that is a single seam: you describe a request as data, the framework owns its whole lifecycle (encode, send, decode, classify the failure, retry, abort), and the result comes back as an ordinary [event](../../../docs/guide/glossary.md#event) — [the uniform reply](../../../docs/guide/glossary.md#the-uniform-reply). The payoff is that the *async* surface and everything else compose for free: an HTTP reply can land straight inside a state machine, gate a flow, trigger an optimistic rollback, or redraw a paginated list, all through the same [event cascade](../../../docs/guide/glossary.md#event-cascade) the rest of your app already runs on. No promise plumbing, no second messaging system. This example is the canonical demonstration of that effect, which is why so much of what follows is really about HTTP wearing different hats.

> **It's also the integration test.** Because RealWorld exercises every artefact together — `day8/re-frame2` (core) + `-schemas` + `-machines` + `-routing` + `-flows` + `-http` — it's the canonical multi-artefact integration test for re-frame2. CI runs its CLJS fixtures on every PR via `npm run test:cljs` from `implementation/` (the example tree is test-free — `npm run test:adapter-smokes` drives only the three adapter smokes and never builds this example). When a per-artefact change quietly breaks *composition* — the seams between the pieces, which no single-artefact test watches — this is the test that catches it. See [docs/release-process.md](../../../docs/release-process.md) for how it slots into the multi-artefact deploy pipeline.

**Status: worked sketch.** This is not a polished production clone, and it doesn't pretend to be. It's a broad tour of the current API: every major Conduit page has a concrete namespace, and each one shows the *intended* re-frame2 shape — sometimes at sketch-level fidelity, but always the shape you'd actually reach for. Read it for the patterns, not for pixel-perfect Medium.

## What this example demonstrates about managed HTTP

The normative contract lives in [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md); here's the part that matters for a reader. Once you accept that an HTTP request is just data you hand to the runtime, a surprising number of everyday concerns stop being *your* problem and become *configuration*. Each bullet below is one of those concerns — a thing you'd normally hand-roll (retries, cancellation, "did the JSON come back the right shape?") expressed instead as a key on the request map. RealWorld touches nearly all of them, because a real app eventually needs all of them.

- **Default reply addressing** — `realworld.comments/:article/load` issues `:rf.http/managed` with no explicit `:on-success` / `:on-failure` and branches its handler on `(:rf/reply msg)` for both the initial dispatch and the reply paths. One [event handler](../../../docs/guide/glossary.md#event-handler), two roles: it kicks the request off, and the reply comes back to the *same* id. Tidy when the load and its result naturally belong together.
- **Explicit `:on-success` / `:on-failure`** — every other endpoint (auth, articles list, profile, comments, favourites, follow, settings, editor) uses the separate-handler shape: a small db-only handler per success / failure that destructures `{:keys [value]}` / `{:keys [failure]}` from the appended reply payload. The opposite trade: more handlers, but each one does exactly one thing and reads like a sentence.
- **Schema-driven decode** — every request hands the runtime a [Malli](../../../docs/guide/glossary.md#schema) schema (`schema/UserResponse`, `ArticlesResponse`, `ArticleResponse`, `CommentsResponse`, `CommentResponse`, `ProfileResponse`, `TagsResponse`) as `:decode`, so the body is validated *as it arrives* instead of blowing up three layers deeper when a field is missing. The decode runs only on a 2xx — so when the server hands you a 4xx HTML error page, you get a clean failure, not a spurious `:rf.http/decode-failure` from trying to parse an apology as JSON.
- **Schema reflection** — because those `:decode` schemas are declared in each handler's registration metadata (`:rf.http/decode-schemas`), a tool can ask "what shape does `:articles/load` expect back?" via `(rf/handler-meta :event :articles/load)` *without running the handler*. The schema is data, so it's introspectable — which is the whole reason re-frame2 prefers data over code wherever it can get away with it.
- **Retry + backoff** — read-only data fetches (articles list, profile, article detail, comments, feed) carry the shared `data-fetch-retry` policy: 3 attempts on `#{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}` with exponential backoff + jitter. Login / register / settings / submit / delete deliberately do **not** retry — one user-initiated action per click. The principle worth stealing: retry *reads* (a flaky network shouldn't make you reload), but never silently re-fire a *write* (the user didn't ask to post their comment twice).
- **Abort by `:request-id`** — `:articles/load` and `:feed/load` carry stable `:request-id` keywords (and the per-slug requests get vector ids like `[:article/load slug]`); `(:articles/cancel)` and `(:feed/cancel)` issue `:rf.http/managed-abort` to cancel an in-flight load when the user navigates away or re-issues mid-fetch — so a slow first request can't land *after* a fast second one and overwrite it with stale data, the classic race that haunts hand-rolled fetch code.
- **Frame awareness** — a reply finds its way back to the [frame](../../../docs/guide/glossary.md#frame) that issued it, automatically. You never thread a frame reference through the request; the runtime remembers. That's what lets the test fixtures spin up a fresh per-test frame via `make-frame`, fire a request into it, and assert against `(app-db-value f)` — every test gets its own isolated app instance with no shared mutable state to leak between them.
- **Failure projection** — `realworld.http/failure->message` turns the closed set of `:rf.http/*` failure categories (`:rf.http/transport`, `:rf.http/timeout`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/accept-failure`, `:rf.http/aborted`) into human-readable strings, surfacing the Conduit `{:errors {:body [...]}}` shape when the API supplies it. The categories are a *closed set*, so you can write an exhaustive `case` over them and the compiler has your back — far better than `(re-find #"timeout" err-string)` and praying the wording never changes.

## Other patterns this example exercises

With managed HTTP doing the heavy lifting underneath, the rest of the app gets to be about *shape*: where does each kind of state want to live, and what's the cleanest way to model it? RealWorld is large enough that the same question comes up several times with different answers, so a couple of these patterns are shown in **two forms side by side** — the lightweight version and the state-machine version — precisely so you can hold them up against each other and develop a feel for when each one earns its keep.

- **Auth state machine** — login, register, session-restore, and logout aren't four independent toggles; they're stages of *one* lifecycle, with rules about which can follow which (you can't log out of a session you never started). So they're modelled as a single [machine](../../../docs/machines/glossary.md#machine) (Spec 005) rather than a scatter of boolean flags. Each [transition](../../../docs/machines/glossary.md#transition) issues a managed-HTTP request, and the reply lands back inside the machine as just another event that an [action](../../../docs/machines/glossary.md#action) handles — the async-meets-machine composition from the intro, in the flesh. The [Machines guide](../../../docs/machines/concepts.md) builds this exact login flow up from first principles if you want the long version.
- **Pattern-NineStates** — the home page (`articles.cljs`) uses a parallel-region state machine (`:realworld/articles-home`) with three orthogonal axes (`:feed` × `:filter` × `:data`); a render-priority table + a selector sub (`:articles.home/render`) collapse the tag union to a single render keyword so the root view is a `case`, not a priority `cond`. The profile pages (`profile.cljs`) apply the same shape at smaller scale: a `:ui/profile` parallel machine with two regions (`:tab` × `:data`), its own render-priority table, and a `:profile/render` selector sub — the cross-axis "which slice's articles to render" math lives in the machine instead of a sub that branches on the route id. The canonical worked example is in `examples/reagent/nine_states/`; this is the production-shaped variant. See [`spec/Pattern-NineStates.md`](../../../spec/Pattern-NineStates.md).
- **Pattern-RemoteData — two shapes side-by-side.** Every screen that loads server data faces the same little question: how do you represent "I asked, I'm waiting, here's the answer (or the error)"? This example answers it **two ways at once** so you can compare them directly — the everyday slice, and the same lifecycle promoted to a [machine](../../../docs/machines/glossary.md#machine).
  - **Slice form** (seven resources: your feed, article detail, comments, profile banner, authored articles, favorited articles, and the global articles list). Each carries the standard 5-key `{:status :data :error :loaded-at :attempt}` slice in [app-db](../../../docs/guide/glossary.md#app-db); `:status` is an explicit field; the derived `:loading?` / `:fetching?` boolean [subscriptions](../../../docs/guide/glossary.md#subscription) drive view-level branches. The home page's `:articles` slice keeps its slice shape for the optimistic-update paths (`favorites.cljs/find-article` scans across `[:articles :feed :profile.articles :profile.favorites]`) while the home-page render decision is driven by the home parallel machine's tags.
  - **`:data-region` machine form** (one resource: popular tags, in `tags.cljs`). Here's the neat part: the Pattern-RemoteData status enum (`:idle :loading :fetching :loaded :error`) maps **one-to-one** onto the states of a single-region `:realworld/tags` machine — so the slice's `:status` *field* simply vanishes, because the region's state-keyword **is** the status. Items + error + loaded-at + attempt ride in the machine's shared `:data`. And the `:loading?` / `:fetching?` boolean subs collapse into `:tags/loading` / `:tags/in-flight` [tags](../../../docs/machines/glossary.md#state-tag) queried with `rf/machine-has-tag?` — the view asks "is it busy?" without needing to know which exact state-keyword carries the "in-flight" intent (*ask, don't tell*). (The canonical worked example of the same shape, scaled to nine states, is in `examples/reagent/nine_states/`.)

  **When to choose each.** Pick the **slice form** when the resource interacts with optimistic-update code that scans across multiple slices (favorites toggle, comment delete), when the data is read from many sites at the same path (existing schemas already attach to `[:resource]` / `[:resource :data]`), or when the resource has no per-state UI distinctions worth naming. Pick the **machine form** when the lifecycle is itself a workflow you want to enumerate and test in isolation, when you'd otherwise be authoring derived boolean subs (`:loading?`, `:fetching?`, `:has-data?`) and a priority `cond` over them, or when the resource composes with other axes (forms, modes, tabs) — at that point the per-region machine is what Pattern-NineStates' parallel-region shape is built from.
- **Pattern-Forms — two shapes side-by-side.** Same move, different domain: a form is also a tiny lifecycle (empty → editing → submitting → done-or-errored), and this example models it **both ways** so the comparison sits right next to the RemoteData one above.
  - **Slice form** (three forms: `:auth :login-form`, `:auth :register-form`, `:comment-form`). Each carries the standard `{:draft :submitted :status :errors :touched :submit-error}` slice in app-db; `:status` is an explicit field (`:idle | :submitting`); the derived `:submitting?` boolean sub drives view-level disabled-attribute toggles. The article editor (`:editor`) is a fourth slice-backed form but a *hybrid*: its `:mode` (create/edit) and lifecycle status live in the `:ui/article-editor` parallel machine (Pattern-NineStates), so the slice carries only the data — `:draft` plus a `:baseline` for the unsaved-changes guard, never a `:status` field.
  - **`:form-region` machine form** (one form: settings, in `settings.cljs`). The Pattern-Forms lifecycle (`:neutral` / `:incorrect` / `:correct` / `:submitting`) maps **one-to-one** onto states of a single-region `:settings/form` machine; the slice's `:status` field disappears because the region's state-keyword IS the status. Draft + errors + touched + submit-error + submitted + loaded-at live in the machine's shared `:data`. The `:submitting?` derived boolean sub collapses into a `:settings/in-flight` tag queried with `rf/machine-has-tag?` — the view doesn't need to know which state-keyword carries the "in-flight" intent. (The canonical worked example of the same shape, scaled to nine states, is in `examples/reagent/nine_states/`.)

  **When to choose each.** Pick the **slice form** when the form's `:status` is the only lifecycle axis worth modelling, when the form is one of many in the same feature (and consistent shape across them buys you copy-paste fluency), or when the form is intentionally small enough that a state machine is over-engineering. Pick the **machine form** when the lifecycle is itself a workflow you want to enumerate and test in isolation, when you'd otherwise be authoring derived boolean subs (`:submitting?`, `:has-errors?`, `:can-submit?`) and a priority `cond` over them, or when the form composes with other axes (modes, tabs, optimistic-update windows) — at that point the per-region machine is what Pattern-NineStates' parallel-region shape is built from.
- **Flows (Spec 013)** — the article editor needs one derived fact in two places: *can the user submit right now?* ("the draft is valid **and** differs from the loaded baseline"). A [subscription](../../../docs/guide/glossary.md#subscription) would serve the button fine — but the `:editor/submit` *event handler* also wants to check it before firing, and a handler can't read subscriptions (they're for [views](../../../docs/guide/glossary.md#view)). That's exactly the gap a [flow](../../../docs/guide/glossary.md#flow) fills: the editor registers a `:editor/can-submit?` flow (`article_editor.cljs`) that *materialises* the boolean into app-db at `[:editor :can-submit?]`, so the handler reads it as plain state — no subscribe ceremony — and the button reads the same path through an ordinary sub. The flow is registered per-frame via `:rf.fx/reg-flow` from `:editor/initialise`, so it's frame-correct under the per-test `make-frame` fixtures. This is the `-flows` artefact pulling its weight in the composition claim above. The dedicated, standalone Flows exemplar (a shopping cart with a flow-reads-flow cascade and a runtime-toggleable discount) lives at [`examples/reagent/flows/`](../flows/).
- **Routing** — route table, path params, query params, auth gating via the `auth-guard` interceptor (`routing.cljs`, wired into the demo frame's `:interceptors` in `core.cljs` — `:requires-auth`-tagged routes redirect unauthenticated users to login per Spec 012 §Redirects and guards), route-driven loads, and navigation blocking for the editor.
- **Pagination** — every article list (the home global feed, the tag list, the authenticated "Your Feed", and the profile authored / favorited lists) paginates with the official RealWorld `limit` / `offset` query params and consumes the response's grand `articlesCount` to size a 1-indexed page-number control. The page rides the **route query** (`?page=N`) so back/forward and bookmarking restore it; `:int` coercion turns the URL's `"2"` into `2` and `:query-defaults {:page 1}` fills page 1 when absent (`routing.cljs`). The page-number control renders the canonical Conduit `.pagination` / `.page-item.active` / `.page-link` markup (the shared `articles/pagination` view, reused by the home page and the profile pages). The 1-indexed page → 0-based `offset` math and the `(ceil articlesCount / page-size)` page count live in `http.cljs` (`page-size`, `page->offset`, `page-count`, `paginate-path`). Switching feed or tag resets to page 1; a page click re-fires the active route's `:on-match` with the new window (Spec 012 — same route, changed query). The demo stub (`core.cljs`) serves a 25-article corpus sliced by the request's `limit` / `offset` so all three pages are exercisable offline.
- **Optimistic updates** — favorite toggle, comment delete, and follow/unfollow all show rollback-friendly event shapes against the managed-HTTP failure path.
- **Article-detail contextual controls** — per the official Conduit article-page template, the detail page (`comments.cljs`) renders the author byline plus, for a non-author viewer, Follow/Unfollow the author (`:article/toggle-follow-author`, optimistic + rollback against the article's embedded `:author`), or, for the author, Edit Article (→ `/editor/:slug`) + Delete Article (`:article/delete`, navigate home on success). Logged-out viewers see the byline only. The editor's own Delete (`article_editor.cljs`) remains reachable too.
- **Schemas** — wire payloads and app-db slices are attached with `reg-app-schemas` (the bulk plural form) — one `{path -> schema}` map registers all 19 app-db paths in `schema.cljs`.
- **Egress classification** — this pattern shows up here for an honest reason: RealWorld actually *has* a secret to protect (the JWT), which is more than most demos can say. The idea is [data classification](../../../docs/guide/glossary.md#data-classification): rather than scrubbing the token at every place it might leak, you mark the *path* it lives at as `:sensitive` once, and the framework redacts it wherever that value would cross a boundary off the box. So the durable token at `[:auth :token]` is classified by a `:sensitive` effect the `:auth/classify-token` init event returns alongside `:db` (`core.cljs`), and the transient decoded reply that introduces it (`schema/User`'s `:token` slot) carries the per-slot `:sensitive?` Malli property on its `:decode` schema (`schema.cljs`). The result: anything heading off-box — an [Xray](../../../docs/guide/glossary.md#xray) capture, an observability log, an SSR hydration payload — sees the token redacted, while on-box use (the live header the request actually sends, the navbar) keeps the real value. Those are two *separate* declarations on two surfaces, because classification deliberately doesn't propagate — each surface a secret crosses gets classified on its own, fail-open. Note what's **not** declared: the outbound `Authorization` Bearer header, because it's already on the framework's built-in HTTP carrier denylist (Spec 014 §Privacy) and redacted off-box with no app config — over-declaring a built-in would only teach a redundant ritual. The password drafts under `[:auth :*-form]` aren't classified either: transient form state owned by its registration, never sent off-box from app-db. The lesson is the discipline — classify where the data is genuinely sensitive, not as a reflexive sprinkle.
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
