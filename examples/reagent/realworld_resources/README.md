# RealWorld (Conduit) on resources + mutations

The RealWorld "Conduit" app — the medium-dot-com clone every framework ports to prove it can do a real thing — built on **[resources](../../../docs/resources/concepts.md)** for its reads and **mutations** for its writes. No `:status` fields, no `:loading?` booleans, no hand-rolled "which screens did this edit break?" bookkeeping. You declare what each read *is* and what each write *touches*, once, and the framework runs the cache.

> **Sibling, not a rewrite.** [`examples/reagent/realworld/`](../realworld/) is the *same app* built the lower-level way — directly on [`:rf.http/managed`](../../../spec/014-HTTPRequests.md), with the per-read `{:status :data :error}` slices and the optimistic-rollback wiring done by hand. That version is the canonical demo of managed HTTP, and it stays. This one expresses the identical Conduit through the declarative server-state surface that sits *on top* of managed HTTP. Read them side by side and the diff *is* the lesson: it's everything resources and mutations do for you.

## The one idea this example exists to show

Here is the whole pitch in one line. A resource is **a subscription you read and a cause you fire**; a mutation is **a cause you fire and an instance you watch**. Wire one to the other and you get the loop a focused lifecycle demo can't show you — the loop that makes up most of the work in a real CRUD app:

> **read → write → invalidate → refetch**, end to end, with nobody holding the wires.

Concretely: you favourite an article. That's a mutation. The mutation declares `:invalidates` — the set of things it just made stale — and one favourite stales `[:article slug]`, the global `[:article-list]`, **and** `[:feed]` (your personalised, private feed). Every mounted read that carries one of those tags — the detail page, every list showing that article, your feed — refetches itself. The favourite button never touches `app-db`. It never dispatches a manual "and now go refresh these five queries." It doesn't *know* which screens are showing that article. The mutation said what it changed; the framework knew the rest.

If you've used TanStack Query, RTK Query, or SWR, that shape is already in your bones — a keyed cache, staleness, dedupe, invalidation. This is that family, with three deliberate differences worth watching for as you read: **views never fetch** (a route or an event causes the load; the view only reads), **scope is a required part of a read's identity** (so one user's feed can never surface in another's cache), and **invalidation is causal** (a write declares its consequences as data, rather than you remembering to call `invalidate()` at the right moment).

The rest of this document walks the app one capability at a time, smallest idea first. The [`resources/`](../resources/) example is the gentler introduction to the read side alone; this is the full app, with the write side and everything that hangs off it.

## Reads are resources

Every read in Conduit — the article list, an article, its comments, a profile, the popular-tags sidebar, your feed — is registered once with `reg-resource` (`resources.cljs`) and read **passively** through `[:rf.resource/*]` subscriptions. There is no `:loading?` flag anywhere in `app-db` for any of these, because the cache doesn't live in `app-db` at all. It lives in the framework-owned half of the frame — [runtime-db](../../../docs/guide/glossary.md#runtime-db), under `:rf.runtime/resources` — exactly so that an ordinary [event handler](../../../docs/guide/glossary.md#event-handler) can't reach in and clobber it by accident. You read it through subs; you change it through events; same in/out discipline as the rest of re-frame2, with the storage moved next door.

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

Three things to notice, because each is a place where the framework is quietly doing work you'd otherwise hand-write:

- **The route causes the load — the view never does.** `routing.cljs` declares each page's reads as `:resources` route metadata. Enter the route and the runtime ensures those reads, owning each under a per-navigation token; leave the route and it releases them. The views don't fetch; they read whatever the route already arranged. This separation is precisely what lets the same view render on the server, in a test, or after a cache hit — with no network call hiding inside the render.
- **The five statuses, done right.** A view renders the canonical resource shape: `:loading?` → skeleton; `:error` with no data → an error; otherwise the data, plus a quiet refresh hint while `:fetching?` and a "showing last-known data" warning on `:refresh-error`. That last distinction matters — a *first* load that fails is an error you blank the page for; a *background refresh* that fails keeps the data you've got and grumbles quietly. The framework owns stale-while-revalidate so the view doesn't have to invent it.
- **Staleness, dedupe, and GC are policy, not plumbing.** Reads go stale after a minute (`:stale-after-ms`); a re-`ensure` of a stale entry refetches into `:fetching` while keeping the old data on screen; a fresh re-`ensure` is a cache-hit with no fetch; two identical reads in flight dedupe onto one request; and an entry nobody owns becomes GC-eligible after its window (`:gc-after-ms`). Focus/reconnect revalidation is wired in `core.cljs` too — return to the tab or reconnect and the active-and-stale reads refetch, as a *cause*, not as a subscription that secretly fetches.

## Writes are mutations

Every write is a `reg-mutation` (`mutations.cljs`). A mutation lowers its request through the *same* managed HTTP the resources use, and declares — as data, on the registration, never imperatively at the call site — what its success means for the cache: which reads it `:invalidates`, and where useful which entry it `:populates` from its own reply.

| Mutation | Write | What it does to the cache |
|---|---|---|
| `:realworld/favorite` / `:realworld/unfavorite` | POST/DELETE `/articles/:slug/favorite` | **Optimistic** (see below). Commits the detail from the reply, then invalidates the public article tags **and** the session feed. |
| `:realworld/follow` / `:realworld/unfollow` | POST/DELETE `/profiles/:username/follow` | populates the profile banner from the reply; invalidates `[:profile username]` |
| `:realworld/post-comment` | POST `/articles/:slug/comments` | invalidates `[:comments slug]`, so the mounted page's comments refetch |
| `:realworld/delete-comment` | DELETE `/articles/:slug/comments/:id` | invalidates `[:comments slug]` |
| `:realworld/save-article` | POST `/articles` (create) / PUT `/articles/:slug` (edit) | invalidates the global lists (and the article's own detail, on edit) **and** the session feed |
| `:realworld/delete-article` | DELETE `/articles/:slug` | invalidates the article, the lists, **and** the session feed |
| `:realworld/update-settings` | PUT `/user` | invalidates `[:profile username]` so a later profile visit re-reads the new bio |

The thing that makes mutations feel like infrastructure rather than glue:

- **Each write is watched by *instance*, not by id.** Runtime state for a mutation is keyed by a per-call instance id, so two favourite toggles on two different articles can be in flight at once without stepping on each other. A view watches its instance through `[:rf.mutation/state {:instance …}]` — `:pending?`, `:success?`, `:error?`, `:result`, `:error` — instead of maintaining a submission-status slice in `app-db`.
- **Writes don't retry.** None of these arm `:retry`, and that's on purpose: re-sending a write because the *reply* was slow is the double-charge bug. Reads carry retry policy in the transport (a flaky GET should retry); writes opt in only deliberately, and Conduit's never need to.

### Completion is a `:reply-to` continuation

Some writes have a sequel. Saving an article should navigate you to it; updating settings should fold the saved user into your session and bounce you to your profile; deleting should send you home. A mutation deliberately gives up the request's `:on-success` hook — the runtime owns reply addressing so it can suppress stale and superseded replies for you — so how does the sequel get to run?

The call site passes a `:reply-to` event target. The runtime dispatches it **once**, when it accepts the reply as current — *after* the `:invalidates` reconciled the cache and the instance settled — appending the canonical reply map (`:status`, `:value`, `:error`, …). A stale or superseded reply simply never fires it; you inherit that safety for free. So `settings.cljs`, `article_editor.cljs`, and the article-detail social controls in `views.cljs` all keep their views as plain functions of subscriptions that never dispatch out of band — the continuation is a declarative, replayable event target, not a side-effecting reaction smuggled into the render.

### Optimistic favourites that roll themselves back

Favouriting is the textbook optimistic write: a tiny, reversible change — one boolean and a count of one — that the user expects to land *instantly*, in every place the article shows at once. So favourite and unfavourite declare `:optimistic-tags`: the heart flips and the count moves the moment you click, across the detail, every list, and your feed, **before** the request is even sent. The apply is *tag-addressed* — it patches every cached entry carrying `[:article slug]`, which is exactly the same tag index `:invalidates` matches against — because the author can't enumerate by hand every cache key showing an article (lists are paginated, scopes differ, entries come and go).

Here's the part that's genuinely nice. You write only the *forward* patch. The runtime records the inverse itself, by snapshotting each touched entry before it edits it. So the reply settles deterministically with no undo code from you:

- an **`:ok`** reply **commits** — `:populates` overwrites your optimistic guess with the server's authoritative Article (the populated entry then reads identically to a freshly fetched one);
- an **`:error`** reply **rolls back** — every touched entry is restored verbatim, the heart un-flips, the count returns;
- a write that lands while another concurrent write *moved* the same entry is governed by `:on-conflict` (default `:invalidate`) — refetch the authoritative value rather than restore a now-stale inverse.

The button reads a derived `:optimistic?` flag off the mutation state to show a subtle "not yet confirmed" cue, and pointedly does **not** disable itself while pending — the user is already looking at their change; disabling would only make it feel slower. The managed-HTTP sibling carries the equivalent rollback shapes hand-wired against raw `:rf.http/managed`; reading the two together is the cleanest way to see what the mutation surface buys.

## Scope: the leak boundary you can't forget

Conduit reads two *kinds* of server data, and they live in different security worlds. The article lists, an article, a profile, the tags — those are **public**: the same params produce the same bytes for everyone, so each resource declares `:scope :rf.scope/global`. There is no implicit default; forget the scope and registration fails *loud* with `:rf.error/resource-missing-scope-policy`. (Xray will even enumerate every `:rf.scope/global` resource for you as a standing security-review list — "here is everything you've asserted is safe to share.")

Your feed is the other kind. What `/articles/feed` returns depends on *who is asking*, so it must never be shared across users — a logged-out user, or the *next* user, must not read the previous user's feed out of cache. That's a **session scope**, `[:rf.scope/session {:username …}]`, and a session scope that can't resolve fails closed: it raises rather than quietly serving someone else's data.

Now, "who is the current viewer?" is one fact, and it shows up in a lot of places — the feed resource needs it, the home route needs it to plan the feed, the favourite/save/delete mutations need it to invalidate the feed, and logout needs it to clear the feed. So this app names it **once**, as a resource-scope resolver (`scope.cljs`):

```clojure
(rf/reg-resource-scope :realworld/session
  {:inputs  {:username [:db [:auth :user :username]]}
   :resolve (fn [{:keys [username]} _ctx]
              (when username [:rf.scope/session {:username username}]))})
```

Every site then refers to it as `{:from-db :realworld/session}` — one scope-resolution currency for the whole app. The `:inputs` declaration tells the runtime which app-db fact decides the scope, so a `{:from-db …}` subscription **re-keys reactively** when you log in or out: a live feed subscription tracks the new principal automatically, without anyone wiring up a login listener. And `nil` is the fail-closed condition everywhere it appears — a logged-out feed sub is the loud "scope unresolved" diagnostic rather than a silent shared read; a logged-out route entry or invalidation descriptor resolves nil and does nothing; logout resolves nil and skips the clear.

### One mutation, two scopes

This is where scope and invalidation meet, and it's the subtle bit. A favourite changes two *kinds* of read in two *different* scopes: the public article and lists (global), and your private feed (session). A plain tag-set `:invalidates` resolves under one scope — so a global-scope mutation, left to itself, could never reach the session feed. Invalidation is fail-closed by design; it will not guess that you meant to cross a scope boundary.

So `:invalidates` is a vector of **per-target descriptors**, each naming its own scope:

```clojure
:invalidates
(fn [{:keys [slug]} _result]
  [{:scope :rf.scope/global               ; the public article + lists
    :tags  #{[:article slug] [:article-list]}}
   {:scope {:from-db :realworld/session}  ; the session feed, via the named resolver
    :tags  #{[:feed]}}])
```

One mutation reaches both scopes, with no app-level cross-scope patch and no home-page watcher keeping the feed in sync by hand. The session descriptor references the very same `{:from-db :realworld/session}` resolver the feed resource declares — resolved at settle time against the frame's db. Save-article and delete-article wear the identical two-descriptor shape.

## Pagination, the cheapest kind

The Conduit list endpoints page with `limit`/`offset`; the UI is 1-indexed with a fixed page size and numbered controls driven off the server's `articlesCount`. This is the missing piece a read-only resources demo can't show you, and the lovely thing is that it needs **no new machinery at all** — pagination is just params identity plus one flag.

- **The page is just another `:params` key.** `:page` rides in params for every list — the global list, both profile tabs, the feed — mapped to `limit`/`offset` by the one `page->limit-offset` helper. So page N and page N+1 are **distinct cache entries** under the exact same params-identity rule the `/tag/:tag` filter already uses. Every server-visible option participates in the cache key.
- **Page state lives in the URL.** `?page=N` flows into each list route's `:resources` params. Paging is a navigation that swaps only `?page=` and preserves the active feed or tag — no page-cache map in `app-db`, no `:status` field. Page 1 drops the param entirely, so the canonical first-page URL is clean.
- **Back-navigation is a cache-hit.** Return to a page you've already loaded and it re-`ensure`s the same entry — no fetch — as long as it's still within its stale window.
- **`:keep-previous? true` kills the flicker.** While a new page key first-loads, the resource state carries `:previous? true` plus `:previous-data` (the prior page's articles). The list renders that prior page — plus a quiet "Loading next page…" hint — instead of a skeleton, so the user never sees a flash of empty list on a page change. The previous data is *shown* but never *adopted* into the new key (it doesn't contribute the new entry's tags); the new entry becomes ordinary `:loaded` only once its own request succeeds.

## The article editor: a mutation, a flow, and a guard walk into a form

The create/edit page (`article_editor.cljs`) is the app's most form-heavy surface, and it's a nice tour because it composes three different contracts in one place:

- **The write is the `:realworld/save-article` mutation** — one mutation for both create (POST) and edit (PUT), branching on whether the draft carries a `:slug`. Its `:invalidates` stales the lists and the feed (and, on edit, the article's own detail), so navigating to the saved article reads fresh data with nothing further to arrange. Delete is the sibling `:realworld/delete-article`.
- **The can-submit gate is a [Spec 013 flow](../../../spec/013-Flows.md).** `:editor/can-submit?` materialises "the draft is valid **and** dirty" into `app-db` at `[:editor :can-submit?]`. Why a flow and not a subscription? Because the *submit handler* needs to read it as plain app-db data to gate the submit — and a subscription's value is only available to views, not to event handlers. The submit button reads the same materialised value through an ordinary sub over the flow's output path. (Materialising a derived value so a *handler* can read it is exactly what flows are for.)
- **A `:can-leave` navigation guard** — the editor routes declare `:can-leave [:editor/can-leave?]`, so a dirty draft parks a navigate-away as *pending* and the app shell renders a "discard changes?" dialog off the `:rf/pending-navigation` subscription. Saving re-seeds the baseline, so the just-saved navigate isn't itself blocked.

The save/delete sequel — navigate to the article, or home on delete — is the mutation's `:reply-to [:editor/replied]` target, fired once after the cache reconciled. Save and delete share one instance, so they share one continuation that branches on the reply value. The one thing that *does* stay a Reagent Form-3 reaction is seeding the draft when the article read first loads in edit mode — and that's because it watches a *resource read* settle, which has no reply-side hook. Everything else is a pure function of subscriptions.

## Auth is a command and a machine, not a cached read

Login, register, session-restore, and logout are a [Spec 005 state machine](../../../spec/005-StateMachines.md) (`auth.cljs`) that issues managed HTTP from its actions and owns the `:idle → :submitting → :authed | :error` lifecycle. Auth is a *command* — it does a thing and transitions — not a cached read, and it's deliberately not contorted into a resource. (The one auth-adjacent *write* that *is* a mutation is the settings update, because it really does invalidate a cached read.) A few details here are worth lingering on because each fixes a bug you'd otherwise hit:

- **One Bearer header for the whole API.** Resources, mutations, and the auth machine all lower onto `:rf.http/managed`, so a single frame-wide HTTP [interceptor](../../../docs/guide/glossary.md#interceptor) (`:realworld/bearer-auth`, in `core.cljs`) injects `Authorization: Token <jwt>` onto *every* outbound request — authenticated reads, every write, the restore `GET /user`. No `:request` fn threads the token per call; the auth slice is the single source of truth and the interceptor is the single read site. It reads the token from the *carried* frame, so the header tracks a renamed or multi-frame mount, and it passes the request through untouched when there's no token (login, register, and logged-out public reads are unaffected). This cross-cutting decoration is a story the resource surface alone leaves untold.
- **Logout must clear the session cache.** On logout the machine clears the session *and* drops the session-scoped resource cache via `:rf.resource/clear-scope` — the causal operation for exactly that — so the next user can't read the previous user's feed. The old scope is resolved with the `rf/resolve-resource-scope` helper against the handler's *coeffect* db (the pre-transition value, which still carries the logging-out user) and the same named `:realworld/session` resolver every other site uses. Public global reads are left alone — they're the same for everyone.
- **Restore preserves the route; only interactive login bounces.** Cold-booting with a saved JWT runs a restore transition that stores the session *without* navigating — deep-link to `/article/x` with a valid token and you stay there. Only an *interactive* login or register bounces you to the guard-stashed return route (or home).
- **A principal switch with no route change has to re-ensure the feed.** This is the subtle footgun. A `{:from-db :realworld/session}` feed subscription re-keys when the principal changes — but the re-key is *passive*; it does not fetch. New-scope data loads only when a *cause* ensures it. Almost every principal switch in this app comes with a route change that re-ensures the feed for free — except cold-boot restore, which writes the principal *after* the home route was already entered logged-out (where the feed resolved nil and wasn't planned). So restore dispatches an explicit `:rf.resource/ensure` of the feed under a stable lease; without it, the feed would re-key and then sit forever at `:idle`, the "feed stuck loading after login" mystery.

### The JWT is a classified secret, redacted at the boundary

The token is a real secret, so it's *classified* rather than hand-redacted at each sink. The durable fact — the JWT at `[:auth :token]` — is marked `:sensitive`, and the decoded reply that introduces it carries a per-slot sensitivity marker on the user schema's `:token` field. The result: anything that *leaves the box* — an Xray or observability capture, an off-box tool, an SSR hydration payload — sees the token redacted, while on-box rendering keeps the real value. Two facts deserve their own footnote here, because both are easy to get subtly wrong:

- The outbound `Authorization` header is **not** separately declared, because it's already on the framework's built-in HTTP carrier denylist — redacted off-box with no app config.
- The session-scope *key* (`[:auth :user :username]`) that keys the cache is **deliberately not** classified. It's identity, not a secret, and over-redacting it would obscure the very cache-leak boundary this whole example exists to demonstrate.

## Files

| File | What it holds |
|---|---|
| `core.cljs` | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` backend stub, the focus/reconnect revalidation listeners, the frame-wide `:realworld/bearer-auth` HTTP interceptor, and the JWT egress classification. |
| `resources.cljs` | Every RealWorld read as `reg-resource` (identity / scope / `:request` / `:tags` / stale + GC policy); the `page-size` + `page->limit-offset` pagination helpers. |
| `mutations.cljs` | Every RealWorld write as `reg-mutation` — favourite / unfavourite are optimistic (`:optimistic-tags` + `:on-conflict :invalidate`); `:populates` seeds/commits, `:invalidates` as per-target `{:scope … :tags …}` descriptors (including the session-feed target, so one mutation reaches both scopes). |
| `scope.cljs` | The named `reg-resource-scope :realworld/session` resolver — the single scope-resolution currency every resource site references as `{:from-db :realworld/session}`. |
| `routing.cljs` | Routes with `:resources` metadata (the `?page=` query → resource params + `:keep-previous?`, the session feed as a `:scope {:from-db :realworld/session}` route resource, the `/tag/:tag` PATH tag route, and the favorites tab route) + the `auth-guard` interceptor. |
| `auth.cljs` | The `:auth/flow` auth machine (login / register / restore-without-navigating / logout) + the login/register forms; logout resolves the session scope and clears it; cold-boot restore re-ensures the feed under the new principal. |
| `settings.cljs` | The settings page as a mutation instance (`:rf.mutation/state`); the save-success continuation is the mutation's `:reply-to [:settings/replied]` target — a plain Form-1 view, no off-render reaction. |
| `article_editor.cljs` | Create / edit / delete an article: the `:realworld/save-article` + `:realworld/delete-article` mutations, the `:editor/can-submit?` flow, the `:editor/can-leave?` guard, the `:reply-to [:editor/replied]` save/delete continuation, and the one remaining Form-3 reaction (seed-on-load from the article read). |
| `views.cljs` | Passive pages (home / article / profile-with-two-tabs) + the numbered `pagination` control + the keep-previous list render + the small UI event glue (favourite / follow / comment / page navigation); the article-detail contextual controls (author follow + author Edit/Delete) whose follow/delete continuations are `:reply-to` targets. The article body renders through `realworld-shared.markdown/render` (sanitized CommonMark → hiccup). |
| `realworld_shared/markdown.cljs` | Shared (both realworld apps) CommonMark → hiccup renderer for the article body, built on `io.github.nextjournal/markdown` in hiccup-emitting mode (full CommonMark: headings, emphasis, code, links, images, tables, nested lists, blockquotes). **Sanitized by construction**: emits hiccup (never raw HTML / `dangerouslySetInnerHTML`) so React escapes all text; raw inline/block HTML degrades to inert escaped text; and link/image URL schemes are allowlisted (http/https/mailto + relative) so `javascript:`/`data:`/`vbscript:` URLs are dropped. |
| `schema.cljs` | Malli wire shapes + the small app-db schemas (auth + form drafts only — the reads live in runtime-db). `User`'s `:token` slot carries the per-slot `:sensitive?` property on its `:decode` schema so the JWT is redacted out of off-box reply captures. |
| `http.cljs` | The demo backend stub (resources + mutations lower onto `:rf.http/managed`, so one stub serves the whole API) — synthesises a multi-page article set, honours `limit`/`offset`, serves a distinct favorited subset — plus the shared `data-fetch-retry` read policy and the `:rf.http/*` failure projection. |
| `realworld_shared/avatar.cljs` | Shared (both realworld apps) default-avatar helper — `avatar-src` falls a nil/empty author/user image back to `default-avatar.svg` on every `.user-img` / `.user-pic` / `.comment-author-img` (RealWorld contract conformance). |
| `index.html` | Static host page. |
| `default-avatar.svg` | The fallback avatar asset, served from the app root. |

## How to run

Build it under shadow-cljs id `examples/realworld-resources` from `implementation/`:

```bash
shadow-cljs watch examples/realworld-resources
```

then open the served page in a browser. **No backend ships:** the demo entry (`core.cljs`) installs an in-process `:rf.http/managed` stub that synthesises canned Conduit responses for both the reads (resources) and the writes (mutations), so it runs standalone with no network. To run against a real backend instead, point `realworld-resources.http/api-base` at the official hosted Conduit API (<https://api.realworld.show/api>) or a local reference backend on `http://localhost:3000/api`, and drop the demo-stub `:fx-overrides` line in `core.cljs`; the frame-wide `:realworld/bearer-auth` interceptor then attaches the JWT to every authenticated call.

## RealWorld contract conformance

This example follows the official RealWorld "Conduit" contract — its route shapes, the `localStorage["jwtToken"]` session key, the form `name` attributes, the selectors, and the favorite/follow toggle conventions — interpreted identically to the `realworld/` sibling. One caveat worth knowing: the contract assumes **one app per origin**, but the repo's dev orchestrator serves both this app and the sibling from a single origin, so the two share — and clobber — each other's `jwtToken` there. Serve the app standalone (one app per origin) and that goes away.

## Architecture references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — **Resources + mutations** (this is the canonical full-app demo of the write→invalidate→refetch loop).
- [`docs/resources/concepts.md`](../../../docs/resources/concepts.md) — the resources/mutations guide.
- [`examples/reagent/realworld/`](../realworld/) — the **Spec 014 `:rf.http/managed`** counterpart (kept intact).
- [`examples/reagent/resources/`](../resources/) — the focused read-resource lifecycle demo (route/event/machine-owned + manual refresh, read-side only).
