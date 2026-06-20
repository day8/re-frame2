# RealWorld (Conduit) on resources + mutations

The RealWorld (Conduit) app ported onto **[Spec 016 — Resources](../../../spec/016-Resources.md)** (the EP-0003 read-resource MVP) for its reads and **mutations** (`reg-mutation`, the causal-write counterpart) for its writes — instead of the hand-rolled `:rf.http/managed` Pattern-RemoteData slices its sibling uses.

> **Sibling, not a rewrite.** [`examples/reagent/realworld/`](../realworld/) is the canonical re-frame2 demo for **Spec 014 — `:rf.http/managed`**: schema-driven decode, classification order, retry + abort, frame-awareness, optimistic-rollback against managed HTTP. That coverage is load-bearing, so this is a NEW sibling — `realworld/` stays intact as the managed-HTTP counterpart, and this example shows the *same app* expressed through the declarative server-state surface. Read them side by side to see what resources buy you.

EP-0003 graduated accepted→final on 2026-06-11, so the resources + mutations runtime is real and this example runs live.

## The one idea this example exists to show

A resource is **a sub you read and a cause you fire**; a mutation is **a cause you fire and an instance you watch**. Put them together and you get the loop the focused [`resources/`](../resources/) lifecycle demo can't show in a full app:

> **read → write → invalidate → refetch**, end to end, with no hand-wiring.

You favourite an article (a `reg-mutation`); its `:invalidates` stales `[:article slug]` + `[:article-list]` (global scope) **and** `[:feed]` (the session scope) in one per-target descriptor; every mounted, owned read of that article (the detail page, any list showing it, the personalised feed) refetches automatically. The form never touches `app-db`, never dispatches a manual invalidate, and never re-implements "which reads did this write break?" — the mutation declared that once.

## What this example demonstrates from EP-0016 (resource mutation completion)

This is the **dogfood** of [EP-0016](../../../docs/EP/EP-0016-resource-mutation-completion.md): the example was built on the EP-0003 read-resource + mutation MVP, and several seams the app hand-wired before EP-0016 landed are now expressed through the new capabilities. The headline shapes:

- **Named scope resolvers (`reg-resource-scope` + `{:from-db …}`).** The "who is the current viewer?" fact is named **once** — `reg-resource-scope :realworld/session` (`scope.cljs`) with declared db inputs — and every site references it as `{:from-db :realworld/session}`: the feed resource's spec `:scope`, the home route's feed resource entry, the favourite/save/delete mutations' session invalidation descriptor, and (via the `resolve-resource-scope` helper) logout's `clear-scope`. Before EP-0016 this app hand-rolled four separate seams for that one fact (a `:rf.scope/from-caller` policy, an `:on-match` event to ensure the feed, a `:session/scope` sub threaded onto every feed subscription, and an explicit cross-scope feed invalidation). A `{:from-db …}` subscription also **re-keys reactively** across login / logout, so a live feed sub tracks the new principal automatically.
- **Mutation-completion `:reply-to` continuations.** Create / update / delete and settings-save reconcile to the UI through call-site `:reply-to` event targets (`settings.cljs`, `article_editor.cljs`, the article-detail social controls in `views.cljs`). The runtime dispatches the target once when it **accepts** the reply — after the `:invalidates` reconciled the cache and the instance settled — appending the canonical reply map. This replaced the off-render Form-3 `reagent.ratom/run!` settle reactions the variant needed before a mutation had any reply-side hook. Stale / superseded replies never fire the continuation (the mandatory stale-suppression boundary, inherited for free).
- **Scoped invalidation descriptors (map-form, exact targets) + populate-as-authoritative-load.** A single mutation's `:invalidates` is a vector of `{:scope … :tags …}` descriptors, so one favourite/save/delete invalidates the **global** article tags **and** the **session** feed without an app-level cross-scope patch (the retired interim workaround). `:populates` seeds the article detail from the write's own reply as an **authoritative load** (the populated key reads identically to a fetched one and is exempt from the same mutation's refetch).
- **Optimistic mutation rollback (`:optimistic-tags` + `:on-conflict`, EP-0019).** Favourite / unfavourite are **optimistic**: the heart flips and the count moves the instant the button is clicked — across the detail, every list, and the session feed at once — *before* the request is sent, via a tag-addressed `:optimistic-tags` apply keyed on `[:article slug]` (the same tag index `:invalidates` matches). The runtime records the inverse, so an `:ok` reply **commits** (the `:populates` seed overwrites the optimistic value with the server's) and an `:error` reply **rolls back** (the heart flips back, verbatim) — no manual undo, no `app-db` flag. `:on-conflict :invalidate` (the default) refetches an entry a concurrent write moved rather than restoring a stale inverse. The favourite buttons read the derived `:optimistic?` flag off `[:rf.mutation/state …]` for an in-flight cue without disabling the control. This is the capability the example's prior forward-only `:populates` note caveated as deferred.

## What this example demonstrates from Spec 016

### Reads are resources (`resources.cljs`)

Every RealWorld read is `reg-resource`d once and read **passively** through `[:rf.resource/*]` subs — there are **no hand-rolled `:status` / `:loading?` app-db fields** for these reads, because the cache lives in the framework-owned runtime partition (`:rf.runtime/resources`), not in `app-db`.

| Resource | Read |
|---|---|
| `:realworld/articles` | the global article list, `:tag`-filtered when the `/tag/:tag` PATH route is active and `:page`-paginated (tag AND page are in params, so a filtered list and each page are distinct cache entries) |
| `:realworld/article` | article detail by slug |
| `:realworld/comments` | an article's comments (a sub-resource: an ordinary resource whose params carry the parent slug) |
| `:realworld/profile` | a user's public profile banner |
| `:realworld/author-articles` | the articles a profile authored (paginated) — the profile's **My Articles** tab |
| `:realworld/favorited-articles` | the articles a profile **favorited** (`GET /articles?favorited=username`, paginated) — the profile's **Favorited Articles** tab |
| `:realworld/tags` | the popular-tags sidebar |
| `:realworld/feed` | the authenticated user's personalised feed — **session-scoped** via the named `{:from-db :realworld/session}` resolver, paginated |

- **Route entry CAUSES the load.** `routing.cljs` declares each page's reads as `:resources` route metadata (`:blocking?` / `:keep-previous?` / `:when` / `:scope`). On entry the runtime ensures them under owner `[:route route-id nav-token]`; on leave it releases the owner by token and suppresses any stale reply by generation. The views never fetch. The session feed is **also** a declarative route resource now — it carries `:scope {:from-db :realworld/session}`, resolved against the navigation handler's app-db at entry (logged out it resolves nil and is simply not planned), so it no longer needs the prior `:home/on-match` event + app-minted lease.
- **`:loading` / `:fetching` / `:refresh-error` done right.** Views render the canonical resource shape: `:loading?` → skeleton; `:error` and not `:has-data?` → error; else render the data, plus a quiet refresh indicator while `:fetching?` and a "showing last-known data" warning on `:refresh-error` (a background refresh failed but prior data is kept). The framework owns stale-while-revalidate, not the view.
- **`:stale-after-ms` / `:gc-after-ms`, dedupe, fresh-skip.** Reads go stale after a minute (a re-`ensure` then refetches into `:fetching`, keeping prior data visible); a fresh re-`ensure` is a cache-hit (no fetch); concurrent identical reads dedupe onto one request; inactive entries are GC-eligible after their window.
- **Focus / reconnect revalidation** is wired (`install-revalidation-listeners!` in `core.cljs`): returning to the tab or reconnecting refetches the active-and-stale reads, expressed as causal events, not a subscription that fetches.

### Pagination is declarative — params identity + `:keep-previous?` (`resources.cljs` / `routing.cljs` / `views.cljs`)

The Conduit list endpoints page with `limit` / `offset`; the UI is 1-indexed with a fixed page size and renders numbered controls off the server's `articlesCount`. Resources make all of that **declarative** — and this is the missing Spec 016 dogfood: pagination is where canonical-params identity and `:keep-previous?` (the TanStack-parity surface) earn their keep, and it needs **no new spec surface**.

- **The page is just another `:params` key.** `:realworld/articles`, `:realworld/author-articles`, `:realworld/favorited-articles`, and the session feed all carry `:page` in params (mapped to `limit`/`offset` by the one `page->limit-offset` helper). So **page N and page N+1 are DISTINCT cache entries** under the same params-identity rule the `/tag/:tag` filter already uses (the active tag flows into params from the route). Every server-visible list option participates in the cache key.
- **Page state rides the route query.** `?page=N` flows into every list route's `:resources` `:params` fn — including the session feed's, which is now an ordinary route resource. Paging is a navigation that swaps only `?page=` and preserves the active feed/tag — no page-cache map, no `:status` field. Page 1 drops the param (the canonical first-page URL).
- **Back-navigation is a cache-hit.** Returning to a previously-loaded page re-`ensure`s the same params-identity entry — no fetch (`:rf.resource/cache-hit`), as long as it's still within its stale window.
- **`:keep-previous?` = no flicker.** The route `:resources` entries set `:keep-previous? true`, so while a NEW page key first-loads the public `:rf.resource/state` projection carries `:previous? true` + `:previous-data` (the prior page's articles). The list view renders that prior page (plus a quiet "Loading next page…" indicator) instead of a skeleton — the user never sees a flash of empty list on page change. The projection is **not** inserted into the new key and never provides its tags (Spec 016 §Paginated and previous data); the new entry becomes ordinary `:loaded` only after its own request succeeds.

### The profile's two official tabs — My Articles / Favorited Articles (`routing.cljs` / `views.cljs`)

The official Conduit profile has two tabs, and here they are **two routes**, each declaring its list read as route `:resources`:

- `:realworld.profile/show` → `/profile/:username` → `:realworld/author-articles` (**My Articles**)
- `:realworld.profile/favorites` → `/profile/:username/favorites` → `:realworld/favorited-articles` (**Favorited Articles**)

The active tab is **just the current route id** — there is no tab state in `app-db`; the view reads `:rf.route/id` to pick which list resource to subscribe to. The tab links are plain `route-link`s. Favoriting / unfavoriting from the Favorited tab fires the same `:realworld/favorite` / `:realworld/unfavorite` mutations, whose `:invalidates` stales `[:article slug]` — a tag the favorited list **carries** for every article it contains — so the list refetches and the article drops out on unfavorite with no extra wiring. (No dedicated `[:favorited-articles username]` invalidation is needed for the toggle; the per-article tag already reaches it.)

### Article-detail contextual controls (`views.cljs`)

Per the official Conduit article-page template, the detail page renders the author byline plus contextual controls: a non-author viewer sees **Follow / Unfollow** the author (`:ui/follow-author` — fires the `:realworld/follow` / `:realworld/unfollow` mutation with a `:reply-to [:ui/follow-author-replied slug]` continuation that stales `[:article slug]` on success so the detail's embedded `:author.following` refetches, since the follow mutation itself only invalidates `[:profile username]`); the author sees **Edit Article** (a `route-link` to `/editor/:slug`) and **Delete Article** (`:ui/delete-article` → the `:realworld/delete-article` mutation with a `:reply-to [:ui/article-deleted]` continuation that navigates home on success). Logged-out viewers see the byline only.

### Cross-scope feed invalidation — one mutation, two scopes (`mutations.cljs`)

A favourite affects two **kinds** of read in two **scopes**: the public article + lists (`:rf.scope/global`) and the authenticated user's personalised feed (the session scope). A bare tag-set `:invalidates` resolves under **one** scope, so a global-scope mutation could never reach the session feed — Spec 016 invalidation is scoped + fail-closed by default (the HYBRID ruling: execution scope is fail-open, invalidation scope fail-closed). The variant used to paper over this with an explicit app-level session-scoped invalidation fired from a home-page settle reaction (the interim patch).

**EP-0016 retired that.** `:invalidates` is now a vector of **per-target descriptors**, each naming its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global               ; the public article + lists
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :realworld/session}  ; the session feed, via the named resolver
    :tags  #{[:feed]}}])
```

One mutation invalidates both scopes; the session descriptor references the **same** `{:from-db :realworld/session}` resolver the feed resource declares (resolved at settle time against the frame db). No app-level cross-scope patch, no home-page watcher. The save-article and delete-article mutations carry the same two-descriptor shape.

### Scope is the fail-closed leak boundary — a named resolver (`scope.cljs`)

This example reads two **kinds** of server-state, so it shows **both** scope policies (Spec 016 §Scope resolution):

- **Public reads** (lists, detail, profile, tags, comments) declare the explicit, auditable **`:scope :rf.scope/global`** claim — "the same params produce the same data for every viewer." There is no implicit default; a missing scope policy is a loud `:rf.error/resource-missing-scope-policy` at registration. Xray enumerates every `:rf.scope/global` resource as the standing security-review list.
- **The personalised feed** depends on *who* is asking, so it carries a session scope `[:rf.scope/session {:username …}]`. A logged-out (or next) user must never see the previous user's feed from cache.

EP-0016 names that session scope **once**, as a `reg-resource-scope :realworld/session` resolver with declared db inputs:

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Every site references it as `{:from-db :realworld/session}` — the feed resource's spec `:scope`, the home route's feed resource entry, the favourite/save/delete invalidation descriptors, and (via the `resolve-resource-scope` helper) logout's `clear-scope`. **One scope-resolution currency**, replacing four hand-wired seams the variant used before EP-0016:

- the feed resource's `:rf.scope/from-caller` policy → `:scope {:from-db :realworld/session}`;
- the `:home/on-match` event that ensured the feed (a route `:scope` resolver couldn't see app-db) → a declarative route resource entry with `:scope {:from-db :realworld/session}`;
- the `:session/scope` sub threaded onto every feed subscription → the subscription resolves its own scope from the resource's `{:from-db …}` policy, and **re-keys reactively** when the resolver's app-db inputs change (login / logout — Spec 016 §A `{:from-db …}` subscription re-keys);
- the explicit cross-scope feed invalidation → the per-target invalidation descriptor above.

`nil` is the fail-closed unresolved condition everywhere: a logged-out feed subscription is the loud "scope unresolved" diagnostic, not a silent shared read; a logged-out route entry / invalidation descriptor resolves nil and does nothing; logout resolves nil and skips the clear.

### Writes are mutations (`mutations.cljs`)

Every RealWorld write is a `reg-mutation` whose `:invalidates` (and, where useful, `:populates`) drive the cached reads:

| Mutation | Write | Invalidates / populates |
|---|---|---|
| `:realworld/favorite` / `:realworld/unfavorite` | POST/DELETE `/articles/:slug/favorite` | **Optimistic** (EP-0019): `:optimistic-tags` flips the heart + count on every entry tagged `[:article slug]` (detail, lists, session feed) *before* the request; `:on-conflict :invalidate` governs a contested rollback. `:populates` then **commits** the detail from the reply as an **authoritative load** (exempt from this mutation's own refetch); `:invalidates` two **per-target descriptors** — `{:scope :rf.scope/global :tags #{[:article slug] [:article-list]}}` for the public reads and `{:scope {:from-db :realworld/session} :tags #{[:feed]}}` for the personalised feed. One mutation, both scopes (EP-0016 D2). |
| `:realworld/follow` / `:realworld/unfollow` | POST/DELETE `/profiles/:username/follow` | `:populates` the profile banner; `:invalidates` `[:profile username]`. From the article detail, the `:ui/follow-author` call site adds a `:reply-to` continuation that stales `[:article slug]` so the embedded author flag refetches. |
| `:realworld/post-comment` | POST `/articles/:slug/comments` | `:invalidates` `[:comments slug]` (the mounted page's comments refetch) |
| `:realworld/delete-comment` | DELETE `/articles/:slug/comments/:id` | `:invalidates` `[:comments slug]` |
| `:realworld/save-article` | POST `/articles` (create) / PUT `/articles/:slug` (edit) | `:invalidates` global `[:article-list]` (+ `[:article slug]` on edit) **and** the session `[:feed]` (the two-descriptor shape); a `:reply-to [:editor/replied]` continuation navigates to the saved article |
| `:realworld/delete-article` | DELETE `/articles/:slug` | `:invalidates` global `[:article slug] [:article-list]` **and** the session `[:feed]`; the `:reply-to` continuation navigates home |
| `:realworld/update-settings` | PUT `/user` | `:invalidates` `[:profile username]`; a `:reply-to [:settings/replied]` continuation folds the saved User (the reply `:value`) into the auth slice and navigates |

- **Instance-keyed lifecycle.** Runtime state is keyed by mutation **instance** id (not mutation id), so two concurrent favourite toggles on different articles never clobber each other. Each view watches its instance through `[:rf.mutation/state {:instance …}]` (`:pending?` / `:success?` / `:error?` / `:result` / `:error` / `:optimistic?`) — no `app-db` submission-status slice. The favourite buttons read `:optimistic?` (true while an unconfirmed optimistic value is showing) for an in-flight cue, and do **not** disable on `:pending?` — the user already sees their optimistic change.
- **Completion is a `:reply-to` continuation (EP-0016 D1).** A write whose success drives durable app state (navigate, fold the saved user, re-stale an embedded flag) passes a call-site `:reply-to` event target to `:rf.mutation/execute`. The runtime dispatches it **once** when it accepts the reply — after the `:invalidates` reconciled the cache and the instance settled — appending the canonical reply map (`:status` / `:value` / `:error` / `:instance` / `:affected-keys` / …). A stale / superseded reply never fires it. This replaced the off-render Form-3 settle reactions the variant needed before a mutation had any reply-side hook.
- **Same transport, runtime-owned reply addressing.** Mutations lower through the **same** managed HTTP as resources; the app `:request` never supplies `:request-id` / `:on-success` / `:on-failure`. Generation + work-id **stale suppression** is the correctness boundary, exactly as for reads — and `:reply-to` inherits it (it fires only on an accepted reply).
- **Writes don't retry.** None of these arm `:retry` — re-submitting a write because a reply was merely slow is the double-charge bug. (Reads carry retry policy in the transport; writes opt in only deliberately.)
- **Optimistic rollback is a mutation capability (EP-0019).** A write that must flip the UI optimistically *and* revert on failure is now a plain `reg-mutation` concern — no `:rf.http/managed` `:on-failure` hand-rollback. The favourite / unfavourite writes declare `:optimistic-tags` (the tag-addressed forward apply, the twin of `:invalidates`), and the runtime records the truthful inverse itself: an `:ok` reply **commits** (the `:populates` seed overwrites the optimistic value), an `:error` reply **rolls back** (the recorded `:before` is restored verbatim — the heart un-flips, the count returns), and a rollback contested by a concurrent write is governed by `:on-conflict` (default `:invalidate` — refetch the authoritative value rather than restore a stale inverse). The view reads the derived `:optimistic?` flag off `[:rf.mutation/state …]` to mark "showing my optimistic value, not yet confirmed." The managed-HTTP sibling ([`examples/reagent/realworld/`](../realworld/)) still carries the equivalent hand-rolled optimistic+rollback shapes against raw `:rf.http/managed` — read the two side by side to see what the mutation surface buys you. (See [docs/guide/concepts/server-state §When resources are the wrong tool](../../../docs/guide/concepts/server-state.md#when-resources-are-the-wrong-tool).)

### The article editor: a mutation + a Spec 013 flow + a `:can-leave` guard (`article_editor.cljs`)

The create/edit-article page is the variant's most form-heavy surface, and it composes three contracts:

- **The write is the `:realworld/save-article` mutation** — one mutation for both create (POST `/articles`) and edit (PUT `/articles/:slug`), switching on whether the draft carries a `:slug`. Its `:invalidates` stales the lists (global) + feed (session) (and, on edit, the article's own detail entry), so navigating to the saved article reads fresh data with no further wiring. Delete is the sibling `:realworld/delete-article` mutation.
- **The can-submit gate is a Spec 013 FLOW** — `:editor/can-submit?` materialises "the draft is valid **AND** dirty (differs from the loaded baseline)" into app-db at `[:editor :can-submit?]`. The `:editor/submit` handler reads it as **plain app-db data** to gate the submit (the "other event handlers read the value" criterion — Spec 013 §When to use a flow); the submit button reads the same value through a plain sub over the flow's `:path`. The flow is registered per-frame from `:editor/initialise` via `:rf.fx/reg-flow`, so it binds to whatever frame the app booted on.
- **A `:can-leave` navigation guard** — the editor routes declare `:can-leave [:editor/can-leave?]`; a dirty draft blocks a navigate-away and the app shell renders a confirm dialog off the `:rf/pending-navigation` sub (Spec 012 §Redirects and guards). Saving re-seeds the baseline so the just-saved navigate isn't blocked.

The save / delete success continuation (navigate to the saved article, or home on delete) is the mutation's call-site **`:reply-to [:editor/replied]` target** (EP-0016 D1) — dispatched once when the runtime accepts the reply, after the `:invalidates` reconciled the cache and the instance settled. Save and delete share one instance, so they share one continuation that branches on the reply value (a save's `:value` carries the saved Article; a delete returns no body). Only the **seed-on-load** reaction stays a Form-3 `reagent.ratom/run!` reaction — it watches a *resource read* settle, which has no reply-side continuation. The render bodies are pure functions of subs and never dispatch — the same `:reply-to` idiom `settings.cljs` uses.

### Auth is a command + machine, not a cached read (`auth.cljs`)

Login / register / session-restore / logout are a Spec 005 state machine issuing managed HTTP — auth is a *command*, not a cached read, and is deliberately **not** contorted into a read-resource (Spec 016 §Scope). The one auth-adjacent **write** that *is* a mutation is the settings update (it invalidates the profile read). On **logout** the machine clears the session **and** the session-scoped resource cache via `:rf.resource/clear-scope` — the causal operation for exactly that — so the next user never reads the prior user's feed. The concrete old scope is resolved with the **`rf/resolve-resource-scope`** resolver helper against the handler's **coeffect db** (the pre-transition causal input, still carrying the logging-out user) and the **same** named `:realworld/session` resolver every resource site references — one scope-resolution currency, teardown included (EP-0016 D3). The public `:rf.scope/global` reads are untouched.

- **One Bearer header for the whole API — a Spec 014 HTTP interceptor.** Resources, mutations, and the auth machine all lower onto `:rf.http/managed`, so a single frame-wide `:before` interceptor (`:realworld/bearer-auth`, registered in `core.cljs`) injects `Authorization: Token <jwt>` from the auth slice onto **every** outbound request — the authenticated reads (`/articles/feed`), the writes (favourite / follow / comment / settings / save-article), and the restore `GET /user`. No `:request` fn threads the token per-call; the auth slice is the single source of truth and the interceptor is the single read site. It reads the token from the **carried** frame (`(:frame ctx)`, EP-0002), so the header tracks a renamed / multi-frame mount, and returns the ctx unchanged when no token is present (login / register / logged-out public reads are unaffected). This is the cross-cutting-decoration story the resources surface otherwise leaves untold.
- **Session-restore preserves the route; only interactive login bounces.** Cold-booting with a saved JWT runs the `:restoring → :authed` transition through the `:restore-session` action, which stores the session **without** navigating — a logged-in user who deep-links to `/article/x` stays there once restore settles. Only an **interactive** login / register (`:store-session`) dispatches `:auth/post-login-redirect` to bounce to the guard-stashed `:return-to` (or home).
- **A principal switch with no route change must re-ensure the feed.** A `{:from-db :realworld/session}` feed subscription *re-keys* reactively when the principal changes (Spec 016 §A `{:from-db …}` subscription re-keys), but the re-key is **passive — it does not fetch**; the new scope's data loads only when a *cause* ensures it (route entry / an event-side `:rf.resource/ensure` / clear-scope). Cold-boot session-restore is the one principal switch in this app with **no** route change: the home route is entered logged-out (the feed resolves nil and is not planned), then the async `GET /user` writes the principal. So `:restore-session` dispatches **`:auth/ensure-session-feed`** — an explicit `:rf.resource/ensure` of the feed (page read from the live route slice, so it hits the same cache key the home route + sub use) under a stable `[:lease :auth/session-feed]` owner released on logout. Without it the feed would re-key but sit stuck at `:idle` ("feed stuck loading after restore"). The interactive login / logout paths re-enter the route, so their route plan re-ensures the feed for free.
- **JWT egress policy — declared once, projected at the boundary (EP-0015).** The token is a genuine secret, so it is classified, not hand-redacted. The durable frame fact — the JWT at `[:auth :token]` in app-db — is declared on the frame's `:sensitive` config (`core.cljs`); the decoded reply that introduces it carries the EP-0005 per-slot `:sensitive?` malli property on `schema/User`'s `:token`. Off-box egress (Xray / observability capture, an off-box tool, an SSR hydration payload) sees the token redacted; on-box use keeps the raw value. The outbound `Authorization` Bearer header (the interceptor above) is **not** declared — it is already on the framework's immutable built-in HTTP carrier denylist (Spec 014 §Privacy), redacted off-box with no frame config; the `:sensitive :http :headers` extension is for app-specific carriers, which this app has none of. The session-scope **key** (`[:auth :user :username]`) that keys the resource cache is identity, not a secret, so it is deliberately **not** classified — over-redacting it would obscure the very cache-leak boundary this example exists to show.

## Files

| File | What it holds |
|---|---|
| `core.cljs` | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` backend stub + the revalidation listeners + the frame-wide `:realworld/bearer-auth` HTTP interceptor + the JWT egress policy (EP-0015 `:sensitive` config: `[:auth :token]`). |
| `resources.cljs` | Every RealWorld read as `reg-resource` (identity / scope / `:request` / `:tags` / stale + GC policy); the `page-size` + `page->limit-offset` pagination helpers. |
| `mutations.cljs` | Every RealWorld write as `reg-mutation` — favourite / unfavourite are **optimistic** (`:optimistic-tags` forward apply across the `[:article slug]` tag index + `:on-conflict :invalidate`, EP-0019); `:populates` (authoritative-load seeds / commit), `:invalidates` as per-target `{:scope … :tags …}` descriptors (incl. the session-feed `{:from-db :realworld/session}` target so one mutation reaches both scopes — EP-0016 D2). |
| `scope.cljs` | The named `reg-resource-scope :realworld/session` resolver (`{:inputs … :resolve …}`) — the single scope-resolution currency every resource site references as `{:from-db :realworld/session}` (EP-0016 D3). |
| `routing.cljs` | Routes with `:resources` metadata (incl. the `?page=` query → resource params + `:keep-previous?`, the session feed as a `:scope {:from-db :realworld/session}` route resource, the `/tag/:tag` PATH tag route `:realworld/home-tag`, and the `:realworld.profile/favorites` tab route) + the `auth-guard` interceptor. |
| `auth.cljs` | The `:auth/flow` auth machine (login / register / restore-without-navigating / logout) + the login/register forms; logout resolves the session scope via `rf/resolve-resource-scope` and clears it; cold-boot restore re-ensures the feed under the new principal (`:auth/ensure-session-feed`) since the re-key alone does not fetch. |
| `settings.cljs` | The settings page as a mutation instance (`:rf.mutation/state`); the save-success continuation is the mutation's `:reply-to [:settings/replied]` target (EP-0016 D1) — a plain Form-1 view, no off-render reaction. |
| `article_editor.cljs` | Create / edit / delete an article: the `:realworld/save-article` + `:realworld/delete-article` mutations, the `:editor/can-submit?` Spec 013 flow, the `:editor/can-leave?` guard, the `:reply-to [:editor/replied]` save/delete continuation (EP-0016 D1), and the one remaining Form-3 reaction (seed-on-load from the article read). |
| `views.cljs` | Passive pages (home / article / profile-with-two-tabs) + the numbered `pagination` control + the keep-previous list render + the small UI event glue (favourite / follow / comment / page navigation); the article-detail contextual controls (author follow + author Edit/Delete) whose follow/delete continuations are `:reply-to` targets. The `home-page` + `article-page` are plain Form-1 views (the feed sub resolves its own `{:from-db :realworld/session}` scope; the mutation continuations are `:reply-to`, so no Form-3 wrappers remain). The article body is rendered through `realworld-shared.markdown/render` (sanitized CommonMark → hiccup). |
| `realworld_shared/markdown.cljs` | Shared (both realworld apps) CommonMark → hiccup renderer for the article body, built on `io.github.nextjournal/markdown` in hiccup-emitting mode (full CommonMark: headings, emphasis, code, links, images, tables, nested lists, blockquotes). **Sanitized by construction**: emits hiccup (never raw HTML / `dangerouslySetInnerHTML`) so React escapes all text; raw inline/block HTML degrades to inert escaped text; and link/image URL schemes are allowlisted (http/https/mailto + relative) so `javascript:`/`data:`/`vbscript:` URLs are dropped. Supersedes the hand-rolled per-app subset. |
| `schema.cljs` | Malli wire shapes + the small app-db schemas (auth + form drafts only — the reads live in runtime-db). `User`'s `:token` slot carries the EP-0005 `:sensitive?` property (EP-0015) so the JWT is redacted out of off-box reply captures. |
| `http.cljs` | The demo backend stub (resources + mutations lower onto `:rf.http/managed`, so one stub serves the whole API) — synthesises a multi-page article set + honours `limit`/`offset` + a distinct favorited subset — plus the shared `data-fetch-retry` read policy + the `:rf.http/*` failure projection. |
| `realworld_shared/avatar.cljs` | Shared (both realworld apps) default-avatar helper — `avatar-src` falls a nil/empty author/user image back to `default-avatar.svg` on every `.user-img` / `.user-pic` / `.comment-author-img` (RealWorld contract conformance). |
| `index.html` | Static host page. |
| `default-avatar.svg` | The fallback avatar asset, served from the app root. |

## How to run

The example tree is **test-free** — `npm run test:examples` drives only the three adapter smokes and never builds this example. To view it in a browser, build it under shadow-cljs id `examples/realworld-resources` from `implementation/`:

```bash
shadow-cljs watch examples/realworld-resources
# then stage this folder's index.html + _shared/ next to the built main.js and serve over HTTP.
```

### Running against a real backend

The app supports three backend modes; the default needs no network.

1. **Canned demo stub (default).** The demo entry (`core.cljs`) installs an in-process `:rf.http/managed` override (`:realworld-resources.demo/http-stub`) that synthesises canned Conduit responses for both the reads (resources) and the writes (mutations), so it runs standalone without a backend. `api-base` is **not contacted** in this mode — the stub matches on the URL path suffix.

2. **Official hosted API.** Point `realworld-resources.http/api-base` at the current official hosted API — <https://api.realworld.show/api> (the old `api.realworld.io` host is stale) — and remove the `:fx-overrides {:rf.http/managed :realworld-resources.demo/http-stub}` line from the demo frame in `core.cljs`. The frame-wide `:realworld/bearer-auth` HTTP interceptor (`core.cljs`) attaches the JWT to every outbound resource / mutation / restore request, so authenticated calls work against the real API; the read resources also carry the shared `data-fetch-retry` policy.

3. **Local reference backend.** The upstream spec ships a Node/Postgres reference backend on `http://localhost:3000/api`; set `api-base` to that and drop the stub override as in mode 2.

## RealWorld contract conformance

This example is **code-conformant** with the official RealWorld browser/E2E contract (the upstream Cypress E2E suite + the Newman/Postman API collection): its route shapes, session-storage key, debug accessor, form `name` attributes, selectors, and toggle conventions match the contract, so the external official suites **can** be run against it (see the [runbook](#validation-runbook-external-official-suite) below). That external validation is **NOT** automated in-repo — there is **no checked-in CI gate, test harness, or `*.spec.cjs`** that runs those suites (the examples tree is test-free), so "conformant" here means *code-conformant + a reproducible manual runbook*, not *suite-validated on every commit*. The in-repo coverage is the direct semantic fixture described under [Verification posture](#verification-posture). The contract interpretation is **identical to the `realworld/` sibling** (one worker conformed both apps); the differences below are only where the resources/mutations shape expresses it:

- **Route shapes.** The official frontend route surface — `/`, `/login`, `/register`, `/settings`, `/editor`, `/editor/:slug`, `/article/:slug`, `/profile/:username`, `/profile/:username/favorites`, the tag list at the PATH route `/tag/:tag` (`:realworld/home-tag`, with `/tag/:tag?page=N`), and the following feed at `/?feed=following`. The active tag is a route PARAM that flows into the articles resource's params (a distinct cache entry per tag); the following token is `following` (NOT `your`). Pagination rides `?page=N` (page 1 drops the param).
- **Session storage.** The JWT is persisted under `localStorage["jwtToken"]` — the exact contract key (`auth.cljs`). **Same-origin caveat:** the contract assumes **one app per origin**. The repo's dev orchestrator serves both this app and the `realworld/` sibling from a *single* origin (at `/realworld-resources/` and `/realworld/`), so the two conforming apps share — and clobber — each other's `jwtToken` there. A known dev-mode artifact, not a contract violation: conformance is validated against **standalone serving** (one app per origin), which the external suite does anyway (see the runbook).
- **Debug accessor.** `window.__conduit_debug__` exposes `getToken` / `getAuthState` / `getCurrentUser` (`core.cljs`). This is a **conformance-contract surface, NOT a re-frame2 pattern** — an unannotated global token accessor bypasses the frame/sub system and leaks the raw JWT; it is comment-marked as such and a production app would not ship it.
- **Form input `name` attributes.** `username` / `email` / `password` (login, register, settings), `image` / `bio` (settings), `title` / `description` / `body` / `tags` (editor).
- **Default avatar.** A nil/empty image falls back to `default-avatar.svg` on every `.user-img` / `.user-pic` / `.comment-author-img`; the navbar shows the authenticated user's `.user-pic`. Centralised in the shared `realworld-shared/avatar.cljs`.
- **Empty-list marker.** Empty list states carry the official `.empty-feed-message` selector.
- **Favorite / follow conventions.** The article-detail favorite control shows visible **Favorite** / **Unfavorite** text and toggles `.btn-outline-primary` ↔ `.btn-primary` on the favorited flag. The profile follow control shows **Follow** / **Unfollow** (`.btn-outline-secondary`). The compact heart-only card button stays `.btn-outline-primary`.

### Validation runbook (external official suite)

Conformance is claimed against an **actual external run**, not an in-repo assertion. To exercise it:

1. **Build the app standalone** (one app per origin — sidesteps the same-origin `jwtToken` caveat). From `implementation/`:
   ```bash
   npx shadow-cljs release examples/realworld-resources   # → out/examples/realworld-resources/
   ```
   Serve `examples/reagent/realworld_resources/index.html` + the built `main.js` from a single static origin (e.g. `npx http-server`), mounted at `/` (drop the `/realworld-resources` base-path — set `realworld-resources.routing/set-base-path!` to `""`, or serve at that sub-path and point the suite there).
2. **Point the app at a real backend** (modes 2/3 above): set `realworld-resources.http/api-base` to the hosted Conduit API or a local reference backend and remove the demo-stub `:fx-overrides` line in `core.cljs`. The real-backend modes are live (the bearer-auth interceptor + the JWT path are real); the canned stub cannot exercise the API-collection contract.
3. **Run the official suites externally** against the served origin:
   - **Cypress E2E:** the upstream `gothinkster/realworld` Cypress spec with `CYPRESS_baseUrl=<your-origin>`.
   - **Newman/Postman API:** the upstream `Conduit.postman_collection.json` with `newman run … --env-var APIURL=<api-base>`.

Record the run result in the PR. **Rows that need the hosted backend** (favorite/follow round-trips, comment post/delete, settings save) cannot be exercised against the canned stub; if a hosted backend is unavailable, note honestly which rows were and were not executed rather than claiming conformance from an in-repo test alone (the works-on-my-test failure mode).

## Verification posture

Following the examples policy and the `realworld/` sibling, this example carries **no Playwright / `*.spec.cjs`**. THIS example's own example-specific wiring is pinned by a direct headless CLJS fixture, **`re-frame.realworld-resources-cljs-test`** (`implementation/adapters/reagent/test/re_frame/`, run by `npm run test:cljs`): it requires the production `realworld-resources.core` and drives the named `:realworld/session` scope resolver (resolves from `[:auth :user :username]`, nil-when-logged-out fail-closed), the frame-wide bearer-auth interceptor (injects `Authorization: Token <jwt>`; no-op logged out), a favorite mutation's `:populates` (authoritative-load detail seed) + cross-scope `:invalidates` (global article tags AND the session feed in one descriptor set) + `:reply-to` continuation, the editor's `:editor/can-submit?` Spec 013 flow + `:reply-to [:editor/replied]` navigate + the `:can-leave?` guard, logout's `:rf.resource/clear-scope` + lease release, and the auth state machine login flow. Broader resource + mutation contract coverage lives as CLJS unit tests over per-test frames in `implementation/resources/test/` and the conformance fixtures; cross-artefact composition is exercised by `npm run test:cljs`, the Xray feature-matrix gate, bundle-isolation, and the perf-bundle gate. See the [coverage table](../README.md#coverage-level-per-reagent-example).

## Architecture references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — **Resources + mutations** (this is the canonical full-app demo of the write→invalidate→refetch loop).
- [`docs/guide/concepts/server-state.md`](../../../docs/guide/concepts/server-state.md) — the resources/mutations guide.
- [`examples/reagent/realworld/`](../realworld/) — the **Spec 014 `:rf.http/managed`** counterpart (kept intact).
- [`examples/reagent/resources/`](../resources/) — the focused read-resource lifecycle demo (route/event/machine-owned + manual refresh, read-side only).
- [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) — the managed-HTTP transport resources/mutations lower onto.
- [`spec/012-Routing.md`](../../../spec/012-Routing.md) — `:resources` route metadata + nav-token ownership.
- [`spec/005-StateMachines.md`](../../../spec/005-StateMachines.md) — the auth machine.
