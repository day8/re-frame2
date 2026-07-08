# RealWorld (Conduit) on resources + mutations

This is a complete, working blog — the RealWorld "Conduit" app, the medium.com clone many frameworks build to prove they can do something real. You can browse articles, open one to read it with its comments, favourite it, follow its author, write and edit your own posts, register, log in, and edit your profile. Nothing needs setting up: a fake backend runs right in the page, so you just start it and click around.

Underneath that ordinary-looking app is the point of the example. Every read is a **[resource](../../../docs/resources/concepts.md)**; every write is a **mutation**. There are no `:status` fields, no `:loading?` booleans, and no hand-written "which screens did this edit break?" bookkeeping. You declare what each read *is* and what each write *touches*, once, and the framework runs the cache.

## The one idea this example exists to show

Here is the whole pitch. A resource is a read; a mutation is a write. Wire one to the other and you get the loop that makes up most of a real CRUD app:

> **read → write → invalidate → refetch**, end to end, with nothing wired by hand.

Here is that loop made concrete. You favourite an article. That is a mutation. The mutation declares `:invalidates` — the set of cached reads it just made stale. One favourite makes `[:article slug]`, the global `[:article-list]`, **and** `[:feed]` (your private, personalised feed) stale. Every mounted read carrying one of those cache tags refetches itself: the detail page, every list showing that article, your feed. The favourite button never touches `app-db`. It never dispatches a manual "now go refresh these five reads." It does not even *know* which screens are showing that article. The mutation said what it changed, and the framework did the rest.

If you have used TanStack Query, RTK Query, or SWR, this shape is familiar — a keyed cache, staleness, dedupe, invalidation. This is that family, with three deliberate differences to watch for:

- **Views never fetch.** A route or an event causes the load. The view only reads.
- **Scope is part of a read's identity.** It is required, so one user's feed can never surface in another's cache.
- **Invalidation is causal.** A write declares its consequences as data. You never remember to call `invalidate()` at the right moment.

> **A sibling, not a rewrite.** [`examples/real-apps/realworld_http/`](../realworld_http/) is the *same app* built one level lower — directly on [`:rf.http/managed`](../../../spec/014-HTTPRequests.md), with each read's `{:status :data :error}` slice and the optimistic rollback wired by hand. That version is the canonical demo of managed HTTP, and it stays. This version builds the same Conduit on the declarative server-state surface that sits *on top* of managed HTTP. Read the two side by side: the difference between them is exactly what resources and mutations do for you.

The rest of this document walks the app one capability at a time, simplest first. The [`resources/`](../../capabilities/resources/resources/) example is a gentler introduction to the read side alone. This is the full app, with the write side and everything built on it.

## Reads are resources

Every read in Conduit is a resource. The article list, an article, its comments, a profile, the popular-tags sidebar, your feed — each is registered once with `reg-resource` (`resources.cljs`) and read **passively** through `[:rf.resource/*]` subscriptions.

None of these need a `:loading?` flag in `app-db`, because the cache does not live in `app-db` at all. It lives in the framework-owned half of the frame: [runtime-db](../../../docs/core/glossary.md#runtime-db), under `:rf.runtime/resources`. Keeping it there means an ordinary [event handler](../../../docs/core/glossary.md#event-handler) cannot reach in and clobber it by accident. You read the cache through subscriptions and change it through events — the same in-through-events, out-through-subscriptions rule as the rest of re-frame2, with the storage moved next door.

| Resource | Read |
|---|---|
| `:realworld/articles` | the global article list — `:tag`-filtered when the `/tag/:tag` route is active, and `:page`-paginated. Tag and page both ride in params, so a filtered list and each page are distinct cache entries. |
| `:realworld/article` | article detail by slug |
| `:realworld/comments` | an article's comments — a sub-resource, which is just an ordinary resource whose params happen to carry the parent slug |
| `:realworld/profile` | a user's public profile banner |
| `:realworld/author-articles` | the articles a profile wrote (paginated) — the profile's **My Articles** tab |
| `:realworld/favorited-articles` | the articles a profile **favorited** (`GET /articles?favorited=username`, paginated) — the profile's **Favorited Articles** tab |
| `:realworld/tags` | the popular-tags sidebar |
| `:realworld/feed` | your personalised feed — **session-scoped** (more on that below), paginated |

Three things are worth noticing. Each is work the framework does for you that you would otherwise write by hand.

- **The route causes the load — the view never does.** `routing.cljs` declares each page's reads as `:resources` route metadata. Enter the route and the runtime ensures those reads, owning each under a per-navigation token. Leave the route and it releases them. The views do not fetch; they read whatever the route already arranged. This is what lets the same view render on the server, in a test, or after a cache hit — with no network call hidden inside the render.
- **The resource status carries every loading state.** A view renders the canonical resource shape: `:loading?` shows a skeleton; `:error` with no data shows an error; otherwise it shows the data, plus a quiet refresh hint while `:fetching?` and a "showing last-known data" warning on `:refresh-error`. That last distinction matters. A *first* load that fails is an error, so you blank the page. A *background refresh* that fails keeps the data you already have and warns quietly. The framework owns stale-while-revalidate, so the view does not invent it.
- **Staleness, dedupe, and garbage collection are policy, not plumbing.** Reads go stale after a minute (`:stale-after-ms`). Re-`ensure` a stale entry and it refetches into `:fetching` while keeping the old data on screen. Re-`ensure` a fresh entry and it is a cache hit, with no fetch. Two identical reads in flight dedupe onto one request. An entry nobody owns becomes eligible for collection after its window (`:gc-after-ms`). Focus and reconnect revalidation is wired in `core.cljs` too: return to the tab or reconnect, and the active, stale reads refetch — as a *cause*, not as a subscription that secretly fetches.

## Writes are mutations

Every write is a `reg-mutation` — most in `mutations.cljs`, with the editor's save/delete pair registered beside its form in `article_editor.cljs`. A mutation sends its request through the *same* managed HTTP the resources use. On its registration — as data, never imperatively at the call site — it declares what success means for the cache: which reads it `:invalidates`, and where useful which entry it `:populates` from its own reply.

| Mutation | Write | What it does to the cache |
|---|---|---|
| `:realworld/favorite` / `:realworld/unfavorite` | POST/DELETE `/articles/:slug/favorite` | **Optimistic** (see below). Commits the detail from the reply, then invalidates the public article tags, the session feed, **and** the acting user's own Favorited-Articles cache (`[:favorited-articles username]`, threaded in via the mutation's `:params` — `:invalidates` has no `:db` of its own to source it from). |
| `:realworld/follow` / `:realworld/unfollow` | POST/DELETE `/profiles/:username/follow` | populates the profile banner from the reply; invalidates `[:profile username]` |
| `:realworld/post-comment` | POST `/articles/:slug/comments` | invalidates `[:comments slug]`, so the mounted page's comments refetch |
| `:realworld/delete-comment` | DELETE `/articles/:slug/comments/:id` | invalidates `[:comments slug]` |
| `:realworld/save-article` | POST `/articles` (create) / PUT `/articles/:slug` (edit) | invalidates the global lists (and the article's own detail, on edit), the session feed, **and** the author's own My Articles cache (`[:author-articles username]`, keyed off the reply, since a create has no prior slug to key against) |
| `:realworld/delete-article` | DELETE `/articles/:slug` | invalidates the article, the lists, **and** the session feed |
| `:realworld/update-settings` | PUT `/user` | invalidates `[:profile username]` so a later profile visit re-reads the new bio |

Two things make mutations feel like infrastructure rather than glue:

- **Each write is watched per *instance*, not by id.** Runtime state for a mutation is keyed by a per-call instance id. So two favourite toggles on two different articles can be in flight at once without stepping on each other. A view watches its own instance through `[:rf/mutation {:instance …}]` — `:pending?`, `:success?`, `:error?`, `:result`, `:error` — instead of keeping a submission-status slice in `app-db`.
- **Writes do not retry.** None of these set `:retry`, and that is on purpose. Re-sending a write because the *reply* was slow is how you double-charge a customer. Reads carry retry policy in the transport (a flaky GET should retry); writes opt in only deliberately, and Conduit's never need to.

### What happens after a write: the `:reply-to` event

Some writes have a follow-up step. Saving an article should navigate you to it. Updating settings should fold the saved user into your session and send you to your profile. Deleting should send you home.

A mutation deliberately gives up the request's `:on-success` hook, because the runtime owns reply addressing — that is how it suppresses stale and superseded replies for you. So how does the follow-up step run?

The call site passes a `:reply-to` event target. The runtime dispatches it **once**, when it accepts the reply as current — *after* `:invalidates` has reconciled the cache and the instance has settled — and appends the canonical reply map (`:status`, `:value`, `:error`, …). A stale or superseded reply never fires it, so you get that safety for free. This is why `settings.cljs`, `article_editor.cljs`, and the article-detail social controls in `views.cljs` stay plain functions of subscriptions that never dispatch out of band. The follow-up is a declarative, replayable event target, not a side effect smuggled into the render.

### Optimistic favourites that roll themselves back

Favouriting is the textbook optimistic update. It is a tiny, reversible change — one boolean and a count — that the user expects to land *instantly*, everywhere the article shows at once. So favourite and unfavourite declare `:optimistic-tags`: the heart flips and the count moves the moment you click, across the detail, every list, and your feed, **before** the request is even sent.

The change is *tag-addressed*. It patches every cached entry carrying `[:article slug]` — the same cache tag index `:invalidates` matches against. It has to work this way, because you cannot list by hand every cache key that shows an article: lists are paginated, scopes differ, entries come and go.

Here is the nice part. You write only the *forward* patch. The runtime records the inverse itself, by snapshotting each touched entry before it edits it. So the reply settles with no undo code from you:

- an **`:ok`** reply **commits** — `:populates` overwrites your optimistic guess with the server's authoritative Article (the entry then reads exactly like a freshly fetched one);
- an **`:error`** reply **rolls back** — every touched entry is restored as it was, the heart un-flips, the count returns;
- a write that lands while another concurrent write *moved* the same entry follows `:on-conflict` (default `:invalidate`) — refetch the authoritative value rather than restore a now-stale inverse.

The button reads a derived `:optimistic?` flag off the mutation state to show a subtle "not yet confirmed" cue. It pointedly does **not** disable itself while pending: the user is already looking at their change, so disabling would only make it feel slower. The managed-HTTP sibling wires the same rollback by hand against raw `:rf.http/managed`. Reading the two together is the clearest way to see what the mutation surface buys you.

## Scope: the leak boundary you can't forget

Every resource declares a **scope** — whose data the cached read belongs to. Conduit reads two kinds of server data, and they need different scopes.

Most reads are **public**: the article lists, an article, a profile, the tags. The same params produce the same bytes for everyone, so each declares `:scope :rf.scope/global`. There is no default. Forget the scope and registration fails *loud* with `:rf.error/resource-missing-scope-policy`. (Xray even lists every `:rf.scope/global` resource for you as a standing security-review list — "here is everything you have declared safe to share.")

Your feed is the other kind. What `/articles/feed` returns depends on *who is asking*, so it must never be shared across users. A logged-out user, or the *next* user, must not read the previous user's feed out of the cache. That is a **session scope**, `[:rf.scope/session {:username …}]`. A session scope that cannot resolve fails closed: it raises rather than quietly serve someone else's data.

"Who is the current viewer?" is one fact, and it shows up in many places. The feed resource needs it. The home route needs it to plan the feed. The favourite, save, and delete mutations need it to invalidate the feed. Logout needs it to clear the feed. So this app names it **once**, as a resource-scope resolver (`scope.cljs`):

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs {:username [:db [:auth :user :username]]}}
  (fn [{:keys [username]} _ctx]
    (when username
      [:rf.scope/session {:username username}])))
```

Every site then refers to it as `{:from-db :realworld/session}` — one way to resolve scope for the whole app. The `:inputs` declaration tells the runtime which app-db fact decides the scope. So a `{:from-db …}` subscription **re-keys** when you log in or out: a live feed subscription tracks the new user automatically, with no login listener to wire up.

`nil` is the fail-closed condition everywhere it appears. A logged-out feed subscription resolves to `nil` and becomes a loud "scope unresolved" diagnostic, not a silent shared read. A logged-out route entry or invalidation descriptor resolves to `nil` and does nothing. Logout resolves to `nil` and skips the clear.

### One mutation, two scopes

This is the subtle part, where scope and invalidation meet. A favourite changes two kinds of read in two different scopes: the public article and lists (global), and your private feed (session). A plain tag-set `:invalidates` resolves under one scope, so a global-scope mutation could never reach the session feed on its own. Invalidation is fail-closed by design: it will not guess that you meant to cross a scope boundary.

So `:invalidates` is a vector of **per-target descriptors**, each naming its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global               ; the public article + lists
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :realworld/session}  ; the session feed, via the named resolver
    :tags  #{[:feed]}}])
```

One mutation reaches both scopes. There is no app-level cross-scope patch and no home-page watcher keeping the feed in sync by hand. The session descriptor uses the same `{:from-db :realworld/session}` resolver the feed resource declares, resolved at settle time against the frame's db. Save-article and delete-article use the identical two-descriptor shape.

## Pagination, the cheapest kind

The Conduit list endpoints page with `limit`/`offset`. The UI is 1-indexed with a fixed page size and numbered controls driven off the server's `articlesCount`. A read-only resources demo cannot show this, and the nice thing is that it needs **no new machinery at all**. Pagination is just params identity plus one flag.

- **The page is just another `:params` key.** `:page` rides in params for every list — the global list, both profile tabs, the feed — and the one `page->limit-offset` helper maps it to `limit`/`offset`. So page N and page N+1 are **distinct cache entries**, under the same params-identity rule the `/tag/:tag` filter already uses. Every server-visible option is part of the cache key.
- **Page state lives in the URL.** `?page=N` flows into each list route's `:resources` params. Paging is a navigation that swaps only `?page=` and keeps the active feed or tag. There is no page-cache map in `app-db` and no `:status` field. Page 1 drops the param entirely, so the first-page URL stays clean.
- **Back-navigation is a cache hit.** Return to a page you have already loaded and it re-`ensure`s the same entry, with no fetch, as long as it is still within its stale window.
- **`:keep-previous? true` removes the flicker.** While a new page first loads, the resource state carries `:previous? true` plus `:previous-data` (the prior page's articles). The list renders that prior page, plus a quiet "Loading next page…" hint, instead of a skeleton. So the user never sees a flash of empty list on a page change. The previous data is *shown* but never *adopted* into the new entry — it does not contribute the new entry's cache tags — and the new entry becomes ordinary `:loaded` only once its own request succeeds.

## The article editor: a mutation, a flow, and a guard in one form

The create/edit page (`article_editor.cljs`) is the app's most form-heavy surface. It is a good tour because it composes three different contracts in one place:

- **The write is the `:realworld/save-article` mutation.** One mutation handles both create (POST) and edit (PUT), branching on whether the draft carries a `:slug`. Its `:invalidates` makes the lists and the feed stale (and, on edit, the article's own detail), so navigating to the saved article reads fresh data with nothing more to arrange. It also stales the author's own My Articles cache — keyed off `result`, the decoded reply, since a *create* has no prior `:slug` to key an invalidation descriptor against. Delete is the sibling `:realworld/delete-article`.
- **The can-submit gate is a [Spec 013 flow](../../../spec/013-Flows.md).** `:editor/can-submit?` materialises "the draft is valid **and** dirty" into `app-db` at `[:editor :can-submit?]`. Why a flow and not a subscription? Because the *submit handler* needs to read it as plain app-db data to gate the submit, and a subscription's value is only available to views, not to event handlers. The submit button reads the same materialised value through an ordinary subscription over the flow's output path. (Materialising a derived value so a *handler* can read it is exactly what flows are for.)
- **A `:can-leave` navigation guard.** The editor routes declare `:can-leave [:editor/can-leave?]`. So a dirty draft parks a navigate-away as *pending*, and the app shell renders a "discard changes?" dialog off the `:rf/pending-navigation` subscription. Saving re-seeds the baseline, so the just-saved navigate is not itself blocked.

The follow-up after save or delete — navigate to the article, or home on delete — is the mutation's `:reply-to [:editor/replied]` target, fired once after the cache reconciled. Save and delete share one instance, so they share one follow-up that branches on the reply value. One thing *does* stay a Reagent Form-3 reaction: seeding the draft when the article read first loads in edit mode. That is because it watches a *resource read* settle, which has no reply-side hook. Everything else is a pure function of subscriptions.

## Auth is a command and a machine, not a cached read

Login, register, session-restore, and logout are a [Spec 005 state machine](../../../spec/005-StateMachines.md) (`auth.cljs`). It issues managed HTTP from its actions and owns the `:idle → :submitting / :restoring → :authed | :error` lifecycle. Auth is a *command*: it does a thing and transitions. It is not a cached read, so it is deliberately not forced into a resource. (The one auth-adjacent *write* that *is* a mutation is the settings update, because it really does invalidate a cached read.) Four details are worth a closer look, because each fixes a bug you would otherwise hit:

- **One Bearer header for the whole API.** Resources, mutations, and the auth machine all run on `:rf.http/managed`. So a single frame-wide HTTP [interceptor](../../../docs/core/glossary.md#interceptor) (`:realworld/bearer-auth`, in `core.cljs`) adds `Authorization: Token <jwt>` to *every* outbound request — authenticated reads, every write, the restore `GET /user`. No `:request` fn threads the token per call. The auth slice is the single source of truth, and the interceptor is the single read site. It reads the token from the *carried* frame, so the header follows a renamed or multi-frame mount, and it passes the request through untouched when there is no token (login, register, and logged-out public reads are unaffected). The resource surface alone could not show this kind of cross-cutting decoration.
- **Logout must clear the session cache.** On logout the machine clears the session *and* drops the session-scoped resource cache via `:rf.resource/clear-scope` — the causal operation for exactly that — so the next user cannot read the previous user's feed. It resolves the old scope with the `rf/resolve-resource-scope` helper against the handler's *coeffect* db (the pre-transition value, which still carries the logging-out user) and the same named `:realworld/session` resolver every other site uses. Public global reads are left alone, since they are the same for everyone.
- **Restore keeps the route; only interactive login navigates.** Cold-booting with a saved JWT runs a restore transition that stores the session *without* navigating. Deep-link to `/article/x` with a valid token and you stay there. Only an *interactive* login or register sends you to the guard-stashed return route (or home).
- **A user switch with no route change has to re-ensure the feed.** This is the subtle trap. A `{:from-db :realworld/session}` feed subscription re-keys when the user changes — but the re-key is *passive*. It does not fetch. New-scope data loads only when a *cause* ensures it. Almost every user switch in this app comes with a route change that re-ensures the feed for free. The exception is cold-boot restore, which writes the user *after* the home route was already entered logged-out (where the feed resolved to `nil` and was not planned). So restore dispatches an explicit `:rf.resource/ensure` of the feed under a stable lease. Without it, the feed would re-key and then sit at `:idle` forever — the "feed stuck loading after login" mystery.

### The JWT is a classified secret, redacted at the boundary

The token is a real secret, so it is *classified* once rather than redacted by hand at each sink. The durable fact — the JWT at `[:auth :token]` — is marked `:sensitive`, and the decoded reply that introduces it marks the user schema's `:token` field as sensitive too. The result: anything that *leaves the box* — an Xray or observability capture, an off-box tool, an SSR hydration payload — sees the token redacted, while on-box rendering keeps the real value. Two points are easy to get subtly wrong:

- The outbound `Authorization` header is **not** declared separately. It is already on the framework's built-in HTTP carrier denylist, so it is redacted off-box with no app config.
- The session-scope *key* (`[:auth :user :username]`) that keys the cache is **deliberately not** classified. It is identity, not a secret. Redacting it would hide the very cache-leak boundary this whole example exists to show.

## Files

| File | What it holds |
|---|---|
| `core.cljs` | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` backend stub, the focus/reconnect revalidation listeners, the frame-wide `:realworld/bearer-auth` HTTP interceptor, and the JWT egress classification. |
| `resources.cljs` | Every RealWorld read as `reg-resource` (identity / scope / `:request` / `:tags` / stale + GC policy); the `page-size` + `page->limit-offset` pagination helpers. |
| `mutations.cljs` | The social, comment, and settings writes as `reg-mutation` — favourite / unfavourite are optimistic (`:optimistic-tags` + `:on-conflict :invalidate`); `:populates` seeds/commits, `:invalidates` as per-target `{:scope … :tags …}` descriptors (including the session-feed target, so one mutation reaches both scopes). The save / delete article pair lives in `article_editor.cljs`. |
| `scope.cljs` | The named `reg-resource-scope :realworld/session` resolver — the single scope-resolution currency every resource site references as `{:from-db :realworld/session}`. |
| `routing.cljs` | Routes with `:resources` metadata (the `?page=` query → resource params + `:keep-previous?`, the session feed as a `:scope {:from-db :realworld/session}` route resource, the `/tag/:tag` PATH tag route, and the favorites tab route) + the `auth-guard` interceptor. |
| `auth.cljs` | The `:auth/flow` auth machine (login / register / restore-without-navigating / logout) + the login/register forms; logout resolves the session scope and clears it; cold-boot restore re-ensures the feed under the new principal. |
| `settings.cljs` | The settings page as a mutation instance (`:rf/mutation`); the save-success continuation is the mutation's `:reply-to [:settings/replied]` target — a plain Form-1 view, no off-render reaction. |
| `article_editor.cljs` | Create / edit / delete an article: the `:realworld/save-article` + `:realworld/delete-article` mutations, the `:editor/can-submit?` flow, the `:editor/can-leave?` guard, the `:reply-to [:editor/replied]` save/delete continuation, and the one remaining Form-3 reaction (seed-on-load from the article read). |
| `views.cljs` | Passive pages (home / article / profile-with-two-tabs) + the numbered `pagination` control + the keep-previous list render + the small UI event glue (favourite / follow / comment / page navigation); the article-detail contextual controls (author follow + author Edit/Delete) whose follow/delete continuations are `:reply-to` targets. The article body renders through `realworld-shared.markdown/render` (sanitized CommonMark → hiccup). |
| `../realworld_shared/markdown.cljs` | Shared (both realworld apps) CommonMark → hiccup renderer for the article body, built on `io.github.nextjournal/markdown` in hiccup-emitting mode (full CommonMark: headings, emphasis, code, links, images, tables, nested lists, blockquotes). **Sanitized by construction**: emits hiccup (never raw HTML / `dangerouslySetInnerHTML`) so React escapes all text; raw inline/block HTML degrades to inert escaped text; and link/image URL schemes are allowlisted (http/https/mailto + relative) so `javascript:`/`data:`/`vbscript:` URLs are dropped. |
| `schema.cljs` | Malli wire shapes + the small app-db schemas (auth + form drafts only — the reads live in runtime-db). `User`'s `:token` slot carries the per-slot `:sensitive?` property on its `:decode` schema so the JWT is redacted out of off-box reply captures. |
| `http.cljs` | The demo backend stub (resources + mutations lower onto `:rf.http/managed`, so one stub serves the whole API) — synthesises a multi-page article set, honours `limit`/`offset`, serves a distinct favorited subset — plus the shared `data-fetch-retry` read policy and the `:rf.http/*` failure projection. |
| `../realworld_shared/avatar.cljs` | Shared (both realworld apps) default-avatar helper — `avatar-src` falls a nil/empty author/user image back to `default-avatar.svg` on every `.user-img` / `.user-pic` / `.comment-author-img` (RealWorld contract conformance). |
| `index.html` | Static host page. |
| `default-avatar.svg` | The fallback avatar asset, served from the app root. |

## How to run

Build it under shadow-cljs id `examples/realworld-resources` from `implementation/`:

```bash
shadow-cljs watch examples/realworld-resources
```

Then open the served page in a browser. **No backend ships.** The demo entry (`core.cljs`) installs an in-process `:rf.http/managed` stub that returns canned Conduit responses for both the reads (resources) and the writes (mutations), so it runs standalone with no network.

To run against a real backend instead: point `realworld-resources.http/api-base` at the official hosted Conduit API (<https://api.realworld.show/api>) or a local reference backend on `http://localhost:3000/api`, and remove the demo-stub `:fx-overrides` line in `core.cljs`. The frame-wide `:realworld/bearer-auth` interceptor then attaches the JWT to every authenticated call.

## RealWorld contract conformance

This example follows the official RealWorld "Conduit" contract: its route shapes, the `localStorage["jwtToken"]` session key, the form `name` attributes, the selectors, and the favorite/follow toggle conventions. It reads the contract the same way the `realworld_http/` sibling does. One caveat: the contract assumes **one app per origin**, but the repo's dev orchestrator serves both this app and the sibling from a single origin, so the two share — and clobber — each other's `jwtToken` there. Serve the app standalone (one app per origin) and the problem goes away.

## Architecture references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — **Resources + mutations** (this is the canonical full-app demo of the write→invalidate→refetch loop).
- [`docs/resources/concepts.md`](../../../docs/resources/concepts.md) — the resources/mutations guide.
- [`examples/real-apps/realworld_http/`](../realworld_http/) — the **Spec 014 `:rf.http/managed`** counterpart (kept intact).
- [`examples/capabilities/resources/resources/`](../../capabilities/resources/resources/) — the focused read-resource lifecycle demo (route/event/machine-owned + manual refresh, read-side only).
