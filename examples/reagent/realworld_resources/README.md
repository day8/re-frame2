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
| `:realworld/articles` | the global article list, `:tag`-filtered when `?tag=` is set (the tag is in params, so a filtered list is a distinct cache entry) |
| `:realworld/article` | article detail by slug |
| `:realworld/comments` | an article's comments (a sub-resource: an ordinary resource whose params carry the parent slug) |
| `:realworld/profile` | a user's public profile banner |
| `:realworld/author-articles` | the articles a profile authored |
| `:realworld/tags` | the popular-tags sidebar |
| `:realworld/feed` | the authenticated user's personalised feed — **session-scoped** |

- **Route entry CAUSES the load.** `routing.cljs` declares each page's reads as `:resources` route metadata (`:blocking?` / `:keep-previous?` / `:when`). On entry the runtime ensures them under owner `[:route route-id nav-token]`; on leave it releases the owner by token and suppresses any stale reply by generation. The views never fetch.
- **`:loading` / `:fetching` / `:refresh-error` done right.** Views render the canonical resource shape: `:loading?` → skeleton; `:error` and not `:has-data?` → error; else render the data, plus a quiet refresh indicator while `:fetching?` and a "showing last-known data" warning on `:refresh-error` (a background refresh failed but prior data is kept). The framework owns stale-while-revalidate, not the view.
- **`:stale-after-ms` / `:gc-after-ms`, dedupe, fresh-skip.** Reads go stale after a minute (a re-`ensure` then refetches into `:fetching`, keeping prior data visible); a fresh re-`ensure` is a cache-hit (no fetch); concurrent identical reads dedupe onto one request; inactive entries are GC-eligible after their window.
- **Focus / reconnect revalidation** is wired (`install-revalidation-listeners!` in `core.cljs`): returning to the tab or reconnecting refetches the active-and-stale reads, expressed as causal events, not a subscription that fetches.

### Scope is the fail-closed leak boundary (`scope.cljs`)

This example reads two **kinds** of server-state, so it shows **both** scope policies (Spec 016 §Scope resolution):

- **Public reads** (lists, detail, profile, tags, comments) declare the explicit, auditable **`:scope :rf.scope/global`** claim — "the same params produce the same data for every viewer." There is no implicit default; a missing scope policy is a loud `:rf.error/resource-missing-scope-policy` at registration. Xray enumerates every `:rf.scope/global` resource as the standing security-review list.
- **The personalised feed** depends on *who* is asking, so it carries an explicit session scope `[:rf.scope/session {:username …}]`. A logged-out (or next) user must never see the previous user's feed from cache. Its spec policy is `:rf.scope/from-caller`: the scope must come from the use site.

The load-bearing seam is the **read side**: a subscription is pure, so it can't run a `(route, ctx)` resolver. If a route ensured the feed under a session scope but a view subscribed *without* one, the sub would resolve a different scope and read `:idle` forever — a permanent skeleton with no error. The view closes that the re-frame2 way: it derives the **same** session scope from `app-db` via the `:session/scope` sub and passes it on the subscription payload, so the entry the route ensured and the entry the view reads are the same scoped key.

### Writes are mutations (`mutations.cljs`)

Every RealWorld write is a `reg-mutation` whose `:invalidates` (and, where useful, `:populates`) drive the cached reads:

| Mutation | Write | Invalidates / populates |
|---|---|---|
| `:realworld/favorite` / `:realworld/unfavorite` | POST/DELETE `/articles/:slug/favorite` | `:populates` the detail entry from the reply (heart flips immediately); `:invalidates` `[:article slug] [:article-list] [:feed]` |
| `:realworld/follow` / `:realworld/unfollow` | POST/DELETE `/profiles/:username/follow` | `:populates` the profile banner; `:invalidates` `[:profile username]` |
| `:realworld/post-comment` | POST `/articles/:slug/comments` | `:invalidates` `[:comments slug]` (the mounted page's comments refetch) |
| `:realworld/delete-comment` | DELETE `/articles/:slug/comments/:id` | `:invalidates` `[:comments slug]` |
| `:realworld/update-settings` | PUT `/user` | `:invalidates` `[:profile username]`; the saved User rides the instance result into the auth slice |

- **Instance-keyed lifecycle.** Runtime state is keyed by mutation **instance** id (not mutation id), so two concurrent favourite toggles on different articles never clobber each other. Each view watches its instance through `[:rf.mutation/state {:instance …}]` (`:pending?` / `:success?` / `:error?` / `:result` / `:error`) — no `app-db` submission-status slice.
- **Same transport, runtime-owned reply addressing.** Mutations lower through the **same** managed HTTP as resources; the app `:request` never supplies `:request-id` / `:on-success` / `:on-failure`. Generation + work-id **stale suppression** is the correctness boundary, exactly as for reads.
- **Writes don't retry.** None of these arm `:retry` — re-submitting a write because a reply was merely slow is the double-charge bug. (Reads carry retry policy in the transport; writes opt in only deliberately.)
- **Optimistic rollback is deferred** (Spec 016 §Deferred slices) — the `:populates` here are forward-only seeds, not optimistic-then-rollback. The managed-HTTP sibling's hand-rolled optimistic+rollback paths are the counterpart to compare.

### Auth is a command + machine, not a cached read (`auth.cljs`)

Login / register / session-restore / logout are a Spec 005 state machine issuing managed HTTP — auth is a *command*, not a cached read, and is deliberately **not** contorted into a read-resource (Spec 016 §Scope). The one auth-adjacent **write** that *is* a mutation is the settings update (it invalidates the profile read). On **logout** the machine clears the session **and** the session-scoped resource cache via `:rf.resource/clear-scope` — the causal operation for exactly that — so the next user never reads the prior user's feed. The public `:rf.scope/global` reads are untouched.

## Files

| File | What it holds |
|---|---|
| `core.cljs` | Entry point, app shell, route switch, mount; installs the demo `:rf.http/managed` backend stub + the revalidation listeners. |
| `resources.cljs` | Every RealWorld read as `reg-resource` (identity / scope / `:request` / `:tags` / stale + GC policy). |
| `mutations.cljs` | Every RealWorld write as `reg-mutation` (`:invalidates` / `:populates`). |
| `scope.cljs` | The fail-closed session cache scope + the `:session/scope` sub the view passes on session-scoped reads. |
| `routing.cljs` | Routes with `:resources` metadata + the `auth-guard` interceptor; the home `:on-match` ensures the session feed. |
| `auth.cljs` | The `:auth/flow` auth machine (login / register / restore / logout-with-clear-scope) + the login/register forms. |
| `settings.cljs` | The settings page as a mutation instance (`:rf.mutation/state`). |
| `views.cljs` | Passive pages (home / article / profile) + the small UI event glue (favourite / follow / comment). |
| `schema.cljs` | Malli wire shapes + the small app-db schemas (auth + form drafts only — the reads live in runtime-db). |
| `http.cljs` | The demo backend stub (resources + mutations lower onto `:rf.http/managed`, so one stub serves the whole API) + the `:rf.http/*` failure projection. |
| `index.html` | Static host page. |

## How to run

The example tree is **test-free** (rf2-8cevm) — `npm run test:examples` drives only the three adapter smokes and never builds this example. To view it in a browser, build it under shadow-cljs id `examples/realworld-resources` from `implementation/`:

```bash
shadow-cljs watch examples/realworld-resources
# then stage this folder's index.html + _shared/ next to the built main.js and serve over HTTP.
```

In production point `realworld-resources.http/api-base` at <https://api.realworld.io/api>; the demo entry installs an in-process `:rf.http/managed` override (`:realworld-resources.demo/http-stub`) that synthesises canned Conduit responses for both the reads (resources) and the writes (mutations), so it runs standalone without a backend.

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
