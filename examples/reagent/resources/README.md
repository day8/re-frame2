# resources — Spec 016 worked example

A small server-state app — an articles list and an article detail — built on
re-frame2 **resources**: declarative, cached reads where the framework owns the
fetching, the dedupe, the staleness, and the cache, so your views just read and
never reach for the network. It's the worked companion to [Spec
016](../../../spec/016-Resources.md) (the EP-0003 read-resource MVP) and the
guide at [docs/resources/concepts.md](../../../docs/resources/concepts.md).

The load-bearing idea here is one distinction that, once it clicks, makes the
whole resource surface fall into place: **owner versus cause**. An *owner* is a
lease — something that keeps a cached read alive for as long as it's around, and
GC-eligible the moment the last one lets go. A *cause* is just provenance — *why*
a fetch happened — recorded for the trace and affecting nothing's lifetime. Owner
= lifetime; cause = explanation. Every fetch in this example is one, the other, or
both, and the four patterns below are really four answers to the same question:
*who's holding this read alive, and why did it just fetch?*

Everything is composed as ordinary re-frame2 — app-db plus events plus
subscriptions, views passive, fetches caused — so nothing about resources asks you
to abandon the loop you already know.

## What this demonstrates

Four ways a fetch gets caused, all four wired into the articles page and running
live, arranged from "the framework decides" to "a workflow decides":

- **Route-driven page load — the route owns it.** A route declares what
  server-state its page needs (`reg-route` with `:resources` metadata), and on
  entry the runtime ensures each read under a `[:route route-id nav-token]` owner,
  with cause `[:route-entry …]`. Leave the route and it releases the owner by
  token. You never wrote a fetch; you wrote a data requirement next to a URL, and
  the page's lifetime *is* the read's lifetime.

- **Event-driven ensure — you mint the lease.** Sometimes a fetch isn't tied to a
  page. The per-row **Preview** button dispatches `:resources.app/preview-opened`,
  which ensures the detail under an app-minted `[:lease …]` owner; **Close
  preview** dispatches `:resources.app/preview-closed` to release it. App leases
  are app-authoritative — *you* opened it, so *you* are on the hook to close it.
  Forget the matching release and the entry pins forever (Xray will lint the
  orphan), which is exactly the trade you accept for holding the lease yourself.

- **Manual refresh — a cause with no owner.** The **Refresh** button dispatches
  `:rf.resource/refetch` with a `:cause` and deliberately **no** `:owner`. A
  refresh wants fresh data but isn't trying to keep anything alive — that's the
  route's job, not the button's. This is the cleanest demonstration of the split:
  same fetch, but it touches *why* without touching *lifetime*.

- **Machine-owned resource — a workflow decides.** When a read should live exactly
  as long as some workflow does, model the workflow as a [machine](../../../docs/machines/concepts.md)
  and let it hold the lease. The per-row **Open in reader** button starts the
  `:resources.app/reader` machine; its `:reading` entry action ensures the article
  under a `[:machine machine-id instance-id]` owner; **Stop reader** emits
  `[:rf.machine/destroy …]`, and destroying the actor releases the owner. The
  machine stays the semantic workflow — the resource runtime just handles the
  cached-read mechanics underneath it.

### No backend, real lifecycle

There's no server in the box, and yet every fetch is a *real* fetch. The example
overrides the `:rf.http/managed` effect with a per-URL canned stub that delegates
to the framework-shipped `:rf.http/managed-canned-success` (Spec 014 §Testing) —
the same reply shape a live server would produce. So each ensure genuinely
exercises in-flight tracking, dedupe, reply addressing, and the passive status
flow; a small 120 ms delay (deferred through framework `:dispatch-later`, not raw
`setTimeout`, so it stays tape-visible and time-travel-safe) gives the loading
skeleton a moment on screen before the reply lands. And re-ensure an entry that's
still inside its `:stale-after-ms` window and the runtime **fresh-skips** it — no
refetch, just a `:rf.resource/cache-hit` — while the manual Refresh forces one
regardless. That's the staleness model you'd get against a real backend, running
with no backend at all.

### The read side: one passive subscription, five distinctions

Views read everything through the **passive** `[:rf.resource/state …]` subscription
(and narrower projections layered over it), which is where resources earn their
keep on the rendering side. The view-model is deliberately rich enough to keep a
network hiccup from blanking the page: it distinguishes `:loading?` (first-load
skeleton, nothing usable yet) from `:has-data?`, and `:fetching?` (a refresh in
flight, old value still on screen) from `:refresh-error` (that background refresh
failed, but the prior data is kept). First-load failure is the only state that
gets to show an error page; a failed refresh quietly keeps what it had.

### Scope is the leak boundary

Every resource declares an explicit `:scope :rf.scope/global` — the auditable claim
that *this read is public and identical for every user*. There's no implicit
default: a user-, tenant-, or locale-scoped read would carry a scope resolver
instead, and a resource with no scope policy at all is a loud
`:rf.error/resource-missing-scope-policy` at registration time. The framework would
rather refuse to register than risk serving one principal's cached data to another.

## Status

Resources is a **post-v1 optional artefact** (`day8/re-frame2-resources`), and the
read-resource runtime has **landed** (EP-0003): `reg-resource`, the passive
`[:rf.resource/*]` subscriptions, route `:resources` metadata, and the causal
`:rf.resource/ensure` / `:rf.resource/refetch` / `:rf.resource/invalidate-tags` /
`:rf.resource/release-owner` event bodies are all real and operational. The four
patterns above run live: route entry, an event lease (Preview), a manual refresh,
and a machine-owned ensure (Open in reader) each cause a real fetch against the
canned stub, dedupe in flight, and flow through the passive status subscriptions.

## Scope of this example — read patterns only

This example covers the **read** side end to end. **Mutations** — causal writes
(`reg-mutation` / `[:rf.mutation/execute …]`) that invalidate, patch, or populate
cached entries on success — have also **landed**, but they're left out here on
purpose to keep the focus on the read lifecycle. The write surface is covered in
[docs/resources/concepts.md §Writes invalidate by tag](../../../docs/resources/concepts.md#writes-invalidate-by-tag--causally)
and the migration walkthrough at
[migration/from-re-frame-v1/re-frame-query-to-resources.md](../../../migration/from-re-frame-v1/re-frame-query-to-resources.md),
including the **scoped-invalidation** discipline — a write must invalidate under
the same scope its resources were ensured under.

## Deferred — not built here

- **GraphQL** — a deferred later phase, outside the read-resource contract.
  `:rf.http/managed` is the single built-in transport, but the lifecycle is
  transport-neutral, so a GraphQL transport can plug in later without disturbing
  any of the above. No GraphQL example is built.

## Files

```
resources/
  core.cljs    — resources, the per-URL canned :rf.http/managed stub, routes,
                 events, the reader machine, subs, views, mount.
  index.html   — minimal host page.
```

The example synthesises its server replies **in-app** via the canned
`:rf.http/managed` stub (it delegates to `:rf.http/managed-canned-success`),
so — unlike `managed_http_counter`, which fetches a real static
`api/inc.json` — there is **no `api/` asset to stage**. The detail route's
per-slug URL (`/api/articles/:slug`) is routed by the stub, not by a static
file tree.

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/resources
```

The watch build emits `main.js` into `out/examples/resources/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/resources/` over HTTP. (`npm run test:adapter-smokes` does not build
this example — it compiles and serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).)

## Coverage

The example tree is test-free, but this example's wiring is pinned by
a direct headless CLJS fixture, **`re-frame.resources-example-cljs-test`**
(`implementation/adapters/reagent/test/re_frame/`, run by `npm run test:cljs`).
It requires this example's production `resources.core` and drives the four causal
patterns directly: route-driven page load (the `:resources` route metadata
ensures under a `[:route …]` owner; the view reads passively and settles
`:loaded`), event-driven lease ensure/release (`:resources.app/preview-opened` /
`-closed` under a `[:lease …]` owner), manual refresh as a cause (no owner;
re-fetches a loaded list into `:fetching` keeping prior data), and the reader
machine's start/stop event glue. The machine-owned-resource ENSURE step itself
(the reader's `:reading` `:entry`) is left to the resources artefact runtime
suites — its `[:machine …]` owner is fail-closed bound to a live actor, so
pinning the entry deterministically in a shared headless bundle is brittle; the
generic ensure-under-owner + release-on-destroy mechanics it composes are pinned
in `implementation/resources/test/`. See the [coverage table](../README.md#coverage-level-per-reagent-example).

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec.
- [`docs/resources/concepts.md`](../../../docs/resources/concepts.md) — the guide.
- [`examples/reagent/resources_ssr/`](../resources_ssr/) — the SSR preload + hydration counterpart.
- [`examples/reagent/managed_http_counter/`](../managed_http_counter/) — the raw `:rf.http/managed` transport resources lower onto.
- [`examples/reagent/routing/`](../routing/) — the routing surface `:resources` metadata extends.
