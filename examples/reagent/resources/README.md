# An articles list that fetches its own data

This example is an articles list and an article's detail. Click **Preview** on a
row to peek at an article, **Open in reader** to read it, or **Refresh** to reload
the list. Each fetch flashes a brief loading skeleton, then the data lands.
There's no server to set up — a stub answers every request right in the page, so
you just start it and click.

The point is what your views *don't* do: they never touch the network. They just
read the data — the framework owns the fetching, the deduping, the staleness, and
the cache. That's what re-frame2 **resources** are. Underneath it's all ordinary
re-frame2: app-db, events, subscriptions. Resources don't ask you to leave the
loop you already know.

This is the worked companion to [Spec
016](../../../spec/016-Resources.md) and the guide at
[docs/resources/concepts.md](../../../docs/resources/concepts.md).

## The one idea: owner versus cause

One distinction makes the whole resource surface click: **owner versus cause**.

- An **owner** is a *lease*. It keeps a cached read alive for as long as it
  exists. The moment the last owner lets go, the entry becomes GC-eligible.
- A **cause** is just provenance — *why* a fetch happened. It is recorded for the
  trace and changes nothing's lifetime.

> **Owner = lifetime; cause = explanation.**

Every fetch here is one, the other, or both. The four patterns below are four
answers to the same question: *who is holding this read alive, and why did it just
fetch?*

## What this demonstrates

Four ways a fetch gets caused. All four are wired into the articles page and run
live. They go from "the framework decides" to "a workflow decides":

- **Route-driven page load — the route owns it.** A route declares the
  server-state its page needs (`reg-route` with `:resources` metadata). On entry,
  the runtime ensures each read under a `[:route route-id nav-token]` owner, with
  cause `[:route-entry …]`. Leave the route and it releases that owner by token.
  You never wrote a fetch. You wrote a data requirement next to a URL, and the
  page's lifetime *is* the read's lifetime.

- **Event-driven ensure — you mint the lease.** Some fetches aren't tied to a
  page. The per-row **Preview** button dispatches `:resources.app/preview-opened`,
  which ensures the detail under an app-minted `[:lease …]` owner. **Close
  preview** dispatches `:resources.app/preview-closed` to release it. *You* opened
  the lease, so *you* must close it. Forget the release and the entry pins forever
  (Xray lints the orphan). That is the trade for holding the lease yourself.

- **Manual refresh — a cause with no owner.** The **Refresh** button dispatches
  `:rf.resource/refetch` with a `:cause` and deliberately **no** `:owner`. A
  refresh wants fresh data, but it isn't trying to keep anything alive — that's
  the route's job, not the button's. This is the cleanest view of the split: the
  same fetch touches *why* without touching *lifetime*.

- **Machine-owned resource — a workflow decides.** Sometimes a read should live
  exactly as long as some workflow. Model that workflow as a
  [machine](../../../docs/machines/concepts.md) and let it hold the lease. The
  per-row **Open in reader** button starts the `:resources.app/reader` machine.
  Its `:reading` entry action ensures the article under a
  `[:machine machine-id instance-id]` owner. **Stop reader** emits
  `[:rf.machine/destroy …]`; destroying the actor releases the owner. The machine
  stays the workflow; the resource runtime just handles the cached-read mechanics
  underneath it.

### No backend, real lifecycle

There is no server in the box, yet every fetch is a *real* fetch. The example
overrides the `:rf.http/managed` effect with a per-URL canned stub. The stub
delegates to the framework-shipped `:rf.http/managed-canned-success` (Spec 014
§Testing), which returns the same reply shape a live server would. So each ensure
genuinely exercises in-flight tracking, deduping, reply addressing, and the
passive status flow.

A small 120 ms delay gives the loading skeleton a moment on screen before the
reply lands. (It is deferred through the framework's `:dispatch-later`, not raw
`setTimeout`, so it stays visible to the trace and safe to time-travel.)

Re-ensure an entry while it is still inside its `:stale-after-ms` window and the
runtime **fresh-skips** it: no refetch, just a `:rf.resource/cache-hit`. The
manual Refresh forces one anyway. That is the real backend's staleness model,
running with no backend at all.

### The read side: one passive subscription, five distinctions

Views read everything through one **passive** subscription, `[:rf.resource/state …]`
(plus narrower projections over it). The view-model is rich enough that a network
hiccup never blanks the page. It draws these distinctions:

- `:loading?` — the first-load skeleton; nothing usable yet.
- `:has-data?` — a value is on screen.
- `:fetching?` — a refresh is in flight; the old value is still shown.
- `:refresh-error` — that background refresh failed, but the prior data is kept.

Only a first-load failure shows an error page. A failed refresh quietly keeps
what it had.

### Scope is the leak boundary

Every resource declares an explicit `:scope :rf.scope/global`. That is the
auditable claim that *this read is public and identical for every user*. There is
no implicit default. A user-, tenant-, or locale-scoped read would carry a scope
resolver instead. A resource with no scope policy at all is a loud
`:rf.error/resource-missing-scope-policy` at registration time — the framework
refuses to register rather than risk serving one user's cached data to another.

## Scope of this example — read patterns only

This example covers the **read** side end to end. It leaves out **mutations** on
purpose, to keep the focus on the read lifecycle. (Mutations are causal writes —
`reg-mutation` / `[:rf.mutation/execute …]` — that invalidate, patch, or populate
cached entries on success.) The write surface is covered in
[docs/resources/concepts.md §Writes invalidate by tag](../../../docs/resources/concepts.md#writes-invalidate-by-tag--causally),
including the **scoped-invalidation** rule: a write must invalidate under the same
scope its resources were ensured under.

## Deferred — not built here

- **GraphQL** — a later phase, outside the read-resource contract.
  `:rf.http/managed` is the one built-in transport, but the lifecycle is
  transport-neutral. A GraphQL transport can plug in later without disturbing any
  of the above. No GraphQL example is built here.

## Files

```
resources/
  core.cljs    — resources, the per-URL canned :rf.http/managed stub, routes,
                 events, the reader machine, subs, views, mount.
  index.html   — minimal host page.
```

There is no server, so there is **no `api/` asset**. (Contrast
`managed_http_counter`, which fetches a real static `api/inc.json`.) The detail
route's per-slug URL (`/api/articles/:slug`) is served by the stub, not by a
static file tree.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/resources
```

Then serve `out/examples/resources/` over HTTP and open it. Click **Preview**,
**Refresh**, or **Open in reader** and watch the skeleton flash before each reply
lands — all served by the in-page stub.
