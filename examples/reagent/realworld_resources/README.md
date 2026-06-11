# RealWorld (Conduit) on resources + mutations

The RealWorld (Conduit) app ported onto **[Spec 016 — Resources](../../../spec/016-Resources.md)** (the EP-0003 read-resource MVP) for its reads and **mutations** (`reg-mutation`, the causal-write counterpart) for its writes — instead of the hand-rolled `:rf.http/managed` Pattern-RemoteData slices its sibling uses.

> **Sibling, not a rewrite.** [`examples/reagent/realworld/`](../realworld/) is the canonical re-frame2 demo for **Spec 014 — `:rf.http/managed`**: schema-driven decode, classification order, retry + abort, frame-awareness, optimistic-rollback against managed HTTP. That coverage is load-bearing, so this is a NEW sibling — `realworld/` stays intact as the managed-HTTP counterpart, and this example shows the *same app* expressed through the declarative server-state surface. Read them side by side to see what resources buy you.

EP-0003 graduated accepted→final on 2026-06-11 (rf2-9l9xs2), so the resources + mutations runtime is real and this example runs live.

## The one idea this example exists to show

A resource is **a sub you read and a cause you fire**; a mutation is **a cause you fire and an instance you watch**. Put them together and you get the loop the focused [`resources/`](../resources/) lifecycle demo can't show in a full app:

> **read → write → invalidate → refetch**, end to end, with no hand-wiring.

You favourite an article (a `reg-mutation`); its `:invalidates` stales `[:article slug]` + `[:article-list]`; every mounted, owned read of that article (the detail page, any list showing it) refetches automatically. The form never touches `app-db`, never dispatches a manual invalidate, and never re-implements "which reads did this write break?" — the mutation declared that once.

## What this example demonstrates from Spec 016

### Reads are resources (`resources.cljs`)

Every RealWorld read is `reg-resource`d once and read **passively** through `[:rf.resource/*]` subs — there are **no hand-rolled `:status` / `:loading?` app-db fields** for these reads, because the cache lives in the framework-owned runtime partition (`:rf.runtime/resources`), not in `app-db`.

| Resource | Read |
|---|---|
| `:realworld/articles` | the global article list, `:tag`-filtered when `?tag=` is set and `:page`-paginated (tag AND page are in params, so a filtered list and each page are distinct cache entries) |
| `:realworld/article` | article detail by slug |
| `:realworld/comments` | an article's comments (a sub-resource: an ordinary resource whose params carry the parent slug) |
| `:realworld/profile` | a user's public profile banner |
| `:realworld/author-articles` | the articles a profile authored (paginated) — the profile's **My Articles** tab |
| `:realworld/favorited-articles` | the articles a profile **favorited** (`GET /articles?favorited=username`, paginated) — the profile's **Favorited Articles** tab |
| `:realworld/tags` | the popular-tags sidebar |
| `:realworld/feed` | the authenticated user's personalised feed — **session-scoped**, paginated |

- **Route entry CAUSES the load.** `routing.cljs` declares each page's reads as `:resources` route metadata (`:blocking?` / `:keep-previous?` / `:when`). On entry the runtime ensures them under owner `[:route route-id nav-token]`; on leave it releases the owner by token and suppresses any stale reply by generation. The views never fetch.
- **`:loading` / `:fetching` / `:refresh-error` done right.** Views render the canonical resource shape: `:loading?` → skeleton; `:error` and not `:has-data?` → error; else render the data, plus a quiet refresh indicator while `:fetching?` and a "showing last-known data" warning on `:refresh-error` (a background refresh failed but prior data is kept). The framework owns stale-while-revalidate, not the view.
- **`:stale-after-ms` / `:gc-after-ms`, dedupe, fresh-skip.** Reads go stale after a minute (a re-`ensure` then refetches into `:fetching`, keeping prior data visible); a fresh re-`ensure` is a cache-hit (no fetch); concurrent identical reads dedupe onto one request; inactive entries are GC-eligible after their window.
- **Focus / reconnect revalidation** is wired (`install-revalidation-listeners!` in `core.cljs`): returning to the tab or reconnecting refetches the active-and-stale reads, expressed as causal events, not a subscription that fetches.

### Pagination is declarative — params identity + `:keep-previous?` (`resources.cljs` / `routing.cljs` / `views.cljs`)

The Conduit list endpoints page with `limit` / `offset`; the UI is 1-indexed with a fixed page size and renders numbered controls off the server's `articlesCount`. Resources make all of that **declarative** — and this is the missing Spec 016 dogfood: pagination is where canonical-params identity and `:keep-previous?` (the TanStack-parity surface) earn their keep, and it needs **no new spec surface**.

- **The page is just another `:params` key.** `:realworld/articles`, `:realworld/author-articles`, `:realworld/favorited-articles`, and the session feed all carry `:page` in params (mapped to `limit`/`offset` by the one `page->limit-offset` helper). So **page N and page N+1 are DISTINCT cache entries** under the same params-identity rule a `?tag=` filter already used. Every server-visible list option participates in the cache key.
- **Page state rides the route query.** `?page=N` flows into the route's `:resources` `:params` fn (and, for the session feed, into the `:home/on-match` ensure). Paging is a navigation that swaps only `?page=` and preserves the active feed/tag — no page-cache map, no `:status` field. Page 1 drops the param (the canonical first-page URL).
- **Back-navigation is a cache-hit.** Returning to a previously-loaded page re-`ensure`s the same params-identity entry — no fetch (`:rf.resource/cache-hit`), as long as it's still within its stale window.
- **`:keep-previous?` = no flicker.** The route `:resources` entries set `:keep-previous? true`, so while a NEW page key first-loads the public `:rf.resource/state` projection carries `:previous? true` + `:previous-data` (the prior page's articles). The list view renders that prior page (plus a quiet "Loading next page…" indicator) instead of a skeleton — the user never sees a flash of empty list on page change. The projection is **not** inserted into the new key and never provides its tags (Spec 016 §Paginated and previous data); the new entry becomes ordinary `:loaded` only after its own request succeeds.

### The profile's two official tabs — My Articles / Favorited Articles (`routing.cljs` / `views.cljs`)

The official Conduit profile has two tabs, and here they are **two routes**, each declaring its list read as route `:resources`:

- `:realworld.profile/show` → `/profile/:username` → `:realworld/author-articles` (**My Articles**)
- `:realworld.profile/favorites` → `/profile/:username/favorites` → `:realworld/favorited-articles` (**Favorited Articles**)

The active tab is **just the current route id** — there is no tab state in `app-db`; the view reads `:rf.route/id` to pick which list resource to subscribe to. The tab links are plain `route-link`s. Favoriting / unfavoriting from the Favorited tab fires the same `:realworld/favorite` / `:realworld/unfavorite` mutations, whose `:invalidates` stales `[:article slug]` — a tag the favorited list **carries** for every article it contains — so the list refetches and the article drops out on unfavorite with no extra wiring. (No dedicated `[:favorited-articles username]` invalidation is needed for the toggle; the per-article tag already reaches it.)

### Article-detail contextual controls (`views.cljs`)

Per the official Conduit article-page template, the detail page renders the author byline plus contextual controls: a non-author viewer sees **Follow / Unfollow** the author (`:ui/follow-author` — fires the `:realworld/follow` / `:realworld/unfollow` mutation, then stales `[:article slug]` so the detail's embedded `:author.following` refetches, since the follow mutation itself only invalidates `[:profile username]`); the author sees **Edit Article** (a `route-link` to `/editor/:slug`) and **Delete Article** (`:ui/delete-article` → the `:realworld/delete-article` mutation, with a Form-3 settle reaction navigating home on success — the same off-render idiom the editor uses, since a mutation has no reply-side `:on-success`). Logged-out viewers see the byline only (rf2-2xi8sr).

### Cross-scope feed invalidation — an interim seam (`views.cljs`)

The favourite / unfavourite mutations are `:rf.scope/global` (matching the public reads they invalidate), so their `[:feed]` tag resolves in the **global** scope — but the personalised feed entry lives under a **session** scope, so the global invalidation is structurally unreachable from it (Spec 016 invalidation is scoped + fail-closed by default; the nx8ip6 HYBRID ruling makes invalidation scope fail-closed). The home page therefore fires an **explicit session-scoped** `[:feed]` invalidation (`:ui/invalidate-session-feed`, derived from the auth slice) when a favourite toggle settles, so Your Feed refreshes. This is the **interim** patch (rf2-em5ab8); the durable fix is EP-0016 per-target scoped invalidation descriptors (`{:scope … :tags #{[:feed]}}`), of which this bug is the minimal failing example.

### Scope is the fail-closed leak boundary (`scope.cljs`)

This example reads two **kinds** of server-state, so it shows **both** scope policies (Spec 016 §Scope resolution):

- **Public reads** (lists, detail, profile, tags, comments) declare the explicit, auditable **`:scope :rf.scope/global`** claim — "the same params produce the same data for every viewer." There is no implicit default; a missing scope policy is a loud `:rf.error/resource-missing-scope-policy` at registration. Xray enumerates every `:rf.scope/global` resource as the standing security-review list.
- **The personalised feed** depends on *who* is asking, so it carries an explicit session scope `[:rf.scope/session {:username …}]`. A logged-out (or next) user must never see the previous user's feed from cache. Its spec policy is `:rf.scope/from-caller`: the scope must come from the use site.

The load-bearing seam is the **read side**: a subscription is pure, so it can't run a `(route, ctx)` resolver. If a route ensured the feed under a session scope but a view subscribed *without* one, the sub would resolve a different scope and read `:idle` forever — a permanent skeleton with no error. The view closes that the re-frame2 way: it derives the **same** session scope from `app-db` via the `:session/scope` sub and passes it on the subscription payload, so the entry the route ensured and the entry the view reads are the same scoped key.

### Writes are mutations (`mutations.cljs`)

Every RealWorld write is a `reg-mutation` whose `:invalidates` (and, where useful, `:populates`) drive the cached reads:

| Mutation | Write | Invalidates / populates |
|---|---|---|
| `:realworld/favorite` / `:realworld/unfavorite` | POST/DELETE `/articles/:slug/favorite` | `:populates` the detail entry from the reply (heart flips immediately); `:invalidates` `[:article slug] [:article-list] [:feed]` (global scope). The personalised feed is **session-scoped**, so a global-scope invalidation can't reach it — `views.cljs` fires an explicit session-scoped `[:feed]` invalidation when a favourite settles (the rf2-em5ab8 interim patch; the durable per-target fix is EP-0016). |
| `:realworld/follow` / `:realworld/unfollow` | POST/DELETE `/profiles/:username/follow` | `:populates` the profile banner; `:invalidates` `[:profile username]` |
| `:realworld/post-comment` | POST `/articles/:slug/comments` | `:invalidates` `[:comments slug]` (the mounted page's comments refetch) |
| `:realworld/delete-comment` | DELETE `/articles/:slug/comments/:id` | `:invalidates` `[:comments slug]` |
| `:realworld/save-article` | POST `/articles` (create) / PUT `/articles/:slug` (edit) | `:invalidates` `[:article-list] [:feed]` (and `[:article slug]` on edit) |
| `:realworld/delete-article` | DELETE `/articles/:slug` | `:invalidates` `[:article slug] [:article-list] [:feed]` |
| `:realworld/update-settings` | PUT `/user` | `:invalidates` `[:profile username]`; the saved User rides the instance result into the auth slice |

- **Instance-keyed lifecycle.** Runtime state is keyed by mutation **instance** id (not mutation id), so two concurrent favourite toggles on different articles never clobber each other. Each view watches its instance through `[:rf.mutation/state {:instance …}]` (`:pending?` / `:success?` / `:error?` / `:result` / `:error`) — no `app-db` submission-status slice.
- **Same transport, runtime-owned reply addressing.** Mutations lower through the **same** managed HTTP as resources; the app `:request` never supplies `:request-id` / `:on-success` / `:on-failure`. Generation + work-id **stale suppression** is the correctness boundary, exactly as for reads.
- **Writes don't retry.** None of these arm `:retry` — re-submitting a write because a reply was merely slow is the double-charge bug. (Reads carry retry policy in the transport; writes opt in only deliberately.)
- **Optimistic rollback is deferred — managed HTTP still owns it** (Spec 016 §Deferred slices). A mutation's `:populates` / `:patches` are **forward-only** seeds: they make the change appear immediately and let the success-time `:invalidates` reconcile, but there is **no automatic revert on failure**. So when a write must flip the UI optimistically *and* roll the change back if the server rejects it — a favourite that un-flips on a 500, a follow that reverts, a deleted comment that re-inserts at its old position — that write stays a plain `:rf.http/managed` event whose `:on-failure` handler restores the prior `app-db` value; a mutation cannot express it today. The managed-HTTP sibling ([`examples/reagent/realworld/`](../realworld/)) carries three deliberately-different optimistic+rollback shapes (favourite cross-slice patch, follow single-boolean, comment-delete positional re-insert) as the worked counterpart. Here the same toggles use forward-only `:populates` and accept the brief refetch round-trip instead. (See [docs/guide/27-resources §When managed HTTP is still the right tool](../../../docs/guide/27-resources.md#mutations--the-causal-write).)

### The article editor: a mutation + a Spec 013 flow + a `:can-leave` guard (`article_editor.cljs`)

The create/edit-article page is the variant's most form-heavy surface, and it composes three contracts:

- **The write is the `:realworld/save-article` mutation** — one mutation for both create (POST `/articles`) and edit (PUT `/articles/:slug`), switching on whether the draft carries a `:slug`. Its `:invalidates` stales the lists + feed (and, on edit, the article's own detail entry), so navigating to the saved article reads fresh data with no further wiring. Delete is the sibling `:realworld/delete-article` mutation.
- **The can-submit gate is a Spec 013 FLOW** — `:editor/can-submit?` materialises "the draft is valid **AND** dirty (differs from the loaded baseline)" into app-db at `[:editor :can-submit?]`. The `:editor/submit` handler reads it as **plain app-db data** to gate the submit (the "other event handlers read the value" criterion — Spec 013 §When to use a flow); the submit button reads the same value through a plain sub over the flow's `:path`. The flow is registered per-frame from `:editor/initialise` via `:rf.fx/reg-flow`, so it binds to whatever frame the app booted on.
- **A `:can-leave` navigation guard** — the editor routes declare `:can-leave [:editor/can-leave?]`; a dirty draft blocks a navigate-away and the app shell renders a confirm dialog off the `:rf/pending-navigation` sub (Spec 012 §Redirects and guards). Saving re-seeds the baseline so the just-saved navigate isn't blocked.

The save-success continuation (navigate to the saved article) is **off the render path**: a mutation has no reply-side `:on-success` hook, so a Form-3 mount-time `reagent.ratom/run!` reaction watches the save instance and dispatches `:editor/saved` once it first settles success. The render bodies are pure functions of subs and never dispatch — the same idiom `settings.cljs` uses.

### Auth is a command + machine, not a cached read (`auth.cljs`)

Login / register / session-restore / logout are a Spec 005 state machine issuing managed HTTP — auth is a *command*, not a cached read, and is deliberately **not** contorted into a read-resource (Spec 016 §Scope). The one auth-adjacent **write** that *is* a mutation is the settings update (it invalidates the profile read). On **logout** the machine clears the session **and** the session-scoped resource cache via `:rf.resource/clear-scope` — the causal operation for exactly that — so the next user never reads the prior user's feed. The public `:rf.scope/global` reads are untouched.

- **One Bearer header for the whole API — a Spec 014 HTTP interceptor.** Resources, mutations, and the auth machine all lower onto `:rf.http/managed`, so a single frame-wide `:before` interceptor (`:realworld/bearer-auth`, registered in `core.cljs`) injects `Authorization: Token <jwt>` from the auth slice onto **every** outbound request — the authenticated reads (`/articles/feed`), the writes (favourite / follow / comment / settings / save-article), and the restore `GET /user`. No `:request` fn threads the token per-call; the auth slice is the single source of truth and the interceptor is the single read site. It reads the token from the **carried** frame (`(:frame ctx)`, EP-0002), so the header tracks a renamed / multi-frame mount, and returns the ctx unchanged when no token is present (login / register / logged-out public reads are unaffected). This is the cross-cutting-decoration story the resources surface otherwise leaves untold.
- **Session-restore preserves the route; only interactive login bounces.** Cold-booting with a saved JWT runs the `:restoring → :authed` transition through the `:restore-session` action, which stores the session **without** navigating — a logged-in user who deep-links to `/article/x` stays there once restore settles. Only an **interactive** login / register (`:store-session`) dispatches `:auth/post-login-redirect` to bounce to the guard-stashed `:return-to` (or home).

## Files

| File | What it holds |
|---|---|
| `core.cljs` | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` backend stub + the revalidation listeners + the frame-wide `:realworld/bearer-auth` HTTP interceptor. |
| `resources.cljs` | Every RealWorld read as `reg-resource` (identity / scope / `:request` / `:tags` / stale + GC policy); the `page-size` + `page->limit-offset` pagination helpers. |
| `mutations.cljs` | Every RealWorld write as `reg-mutation` (`:invalidates` / `:populates`). |
| `scope.cljs` | The fail-closed session cache scope + the `:session/scope` sub the view passes on session-scoped reads. |
| `routing.cljs` | Routes with `:resources` metadata (incl. the `?page=` query → resource params + `:keep-previous?`, and the `:realworld.profile/favorites` tab route) + the `auth-guard` interceptor; the home `:on-match` ensures the session feed. |
| `auth.cljs` | The `:auth/flow` auth machine (login / register / restore-without-navigating / logout-with-clear-scope) + the login/register forms. |
| `settings.cljs` | The settings page as a mutation instance (`:rf.mutation/state`); the save-success continuation is off the render path (Form-3 settle reaction). |
| `article_editor.cljs` | Create / edit / delete an article: the `:realworld/save-article` + `:realworld/delete-article` mutations, the `:editor/can-submit?` Spec 013 flow, the `:editor/can-leave?` guard, and the Form-3 settle reactions (seed-on-load + save/delete continuation). |
| `views.cljs` | Passive pages (home / article / profile-with-two-tabs) + the numbered `pagination` control + the keep-previous list render + the small UI event glue (favourite / follow / comment / page navigation); the article-detail contextual controls (author follow + author Edit/Delete — rf2-2xi8sr); and the explicit session-scoped feed invalidation on favourite-settle (rf2-em5ab8 interim patch). The `home-page` + `article-page` are Form-3 wrappers holding the off-render settle reactions. |
| `schema.cljs` | Malli wire shapes + the small app-db schemas (auth + form drafts only — the reads live in runtime-db). |
| `http.cljs` | The demo backend stub (resources + mutations lower onto `:rf.http/managed`, so one stub serves the whole API) — synthesises a multi-page article set + honours `limit`/`offset` + a distinct favorited subset — plus the shared `data-fetch-retry` read policy + the `:rf.http/*` failure projection. |
| `index.html` | Static host page. |

## How to run

The example tree is **test-free** (rf2-8cevm) — `npm run test:examples` drives only the three adapter smokes and never builds this example. To view it in a browser, build it under shadow-cljs id `examples/realworld-resources` from `implementation/`:

```bash
shadow-cljs watch examples/realworld-resources
# then stage this folder's index.html + _shared/ next to the built main.js and serve over HTTP.
```

### Running against a real backend

The app supports three backend modes; the default needs no network.

1. **Canned demo stub (default).** The demo entry (`core.cljs`) installs an in-process `:rf.http/managed` override (`:realworld-resources.demo/http-stub`) that synthesises canned Conduit responses for both the reads (resources) and the writes (mutations), so it runs standalone without a backend. `api-base` is **not contacted** in this mode — the stub matches on the URL path suffix.

2. **Official hosted API.** Point `realworld-resources.http/api-base` at the current official hosted API — <https://api.realworld.show/api> (the old `api.realworld.io` host is stale) — and remove the `:fx-overrides {:rf.http/managed :realworld-resources.demo/http-stub}` line from the demo frame in `core.cljs`. The frame-wide `:realworld/bearer-auth` HTTP interceptor (`core.cljs`) attaches the JWT to every outbound resource / mutation / restore request, so authenticated calls work against the real API (rf2-j4kbro); the read resources also carry the shared `data-fetch-retry` policy.

3. **Local reference backend.** The upstream spec ships a Node/Postgres reference backend on `http://localhost:3000/api`; set `api-base` to that and drop the stub override as in mode 2.

## Verification posture

Following the examples policy and the `realworld/` sibling, this example carries **no Playwright / `*.spec.cjs`**. Resource + mutation contract coverage lives as CLJS unit tests over per-test frames in `implementation/resources/test/` and the conformance fixtures; cross-artefact composition is exercised by `npm run test:cljs`, the Xray feature-matrix gate, bundle-isolation, and the perf-bundle gate.

## Architecture references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — **Resources + mutations** (this is the canonical full-app demo of the write→invalidate→refetch loop).
- [`docs/guide/27-resources.md`](../../../docs/guide/27-resources.md) — the resources/mutations tutorial.
- [`examples/reagent/realworld/`](../realworld/) — the **Spec 014 `:rf.http/managed`** counterpart (kept intact).
- [`examples/reagent/resources/`](../resources/) — the focused read-resource lifecycle demo (route/event/machine-owned + manual refresh, read-side only).
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the managed-HTTP transport resources/mutations lower onto.
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — `:resources` route metadata + nav-token ownership.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the auth machine.
