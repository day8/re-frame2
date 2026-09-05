# A server-rendered page that arrives already filled in

This example renders a list of articles on the server and sends the page
to the browser with the list already in it. Open the page and the
articles are on screen at the first paint — no spinner, and the browser
doesn't go back to fetch them again. You don't need a running Clojure
server to see it: the [`index.html`](index.html) here ships with a
pre-baked payload that stands in for what a real server would send.

Here's the idea worth taking away:

> The list isn't a value the server froze. It's a cache the client rebuilds.

The sibling [`examples/capabilities/ssr/ssr/`](../ssr/) does the frozen-value
version: the server fetches a list, bakes it into app-db, and hands that
snapshot across. This example is the harder case. The list's state is a
re-frame2 [resource](../../../../docs/resources/glossary.md#resource) — a
live cache the framework owns, with its own identity, scope, and
staleness. So SSR can't just freeze a value; it has to ship the cache
itself — faithfully enough that the client renders the list on the first
paint and knows not to re-fetch something it was just handed fresh.

That's the load-bearing idea. A hydrated resource isn't a snapshot pasted
into app-db. It's the runtime's resource cache rebuilt on the client, so
the [view](../../../../docs/core/glossary.md#view) reads it passively — no
fetch, no flicker — just as if the client had fetched it a moment ago.
Everything below is the discipline that makes that handoff safe.

One artefact, 2 runtimes: the code is `.cljc`, so the same file runs on
the server (the `:clj` branches) and in the browser (the `:cljs`
branches), with reader macros picking the side. Read it as the worked
companion to [Spec 016 §SSR and hydration](../../../../spec/016-Resources.md)
riding over [Spec 011 SSR](../../../../spec/011-SSR.md). For the narrative
version, see
[docs/resources/concepts.md §SSR and hydration](../../../../docs/resources/concepts.md#ssr-and-hydration).

## What this demonstrates

The whole resource SSR contract, read top to bottom as one request and
its client pickup. The recurring theme: a process-global cache would leak
one user's data into another's render, so SSR keeps everything
per-request, and the wire payload stays minimal.

- A frame per request — because the cache is shared state. SSR
  resources run in a per-request
  [frame](../../../../docs/core/glossary.md#frame), never a process-global
  one. The cache lives in that frame's
  [runtime-db](../../../../docs/core/glossary.md#runtime-db), so 2
  concurrent renders are 2 isolated caches that can't see each other.
  It's the same frame isolation that powers tests and stories — here it
  buys the privacy you most need when rendering many users' pages in one
  JVM. `handle-request` (`:clj`) mints the frame — its `:initial-events`
  fire `:rf/server-init` — renders, and tears the frame down in a
  `finally` on every exit path — success or throw, no leak.

- Block on the preload, so the render never sees a skeleton. A
  resource is normally async: ask for it, get a `:loading` view-model,
  fill in the data when the reply lands. That's wrong for SSR, where
  there's no second render to fill anything in. So `:rf/server-init`
  [ensures](../../../../docs/resources/glossary.md#owner--cause) the page
  resource under an `[:ssr request-id nav-token]`
  [owner](../../../../docs/resources/glossary.md#owner--cause) with
  [cause](../../../../docs/resources/glossary.md#owner--cause)
  `:ssr-preload`. This page has no route table at all — the simplest
  possible SSR shape — so `handle-request` can't reach for the framework's
  `ssr/drain-blocking-resources!`: that drain is ROUTE-blocking-keyed, it
  waits only on resources a `reg-route` on-route-entry plan enqueued for the
  current nav-token, and a route-free `[:ssr …]`-owned ensure never
  registers there. Instead `handle-request` calls `await-resource-loaded!`
  (defined alongside it), which polls the resource's own runtime entry
  directly via the public `rf/resource-state` introspection read until it
  reaches `:loaded` / `:error`, or a render-deadline budget elapses. The
  [view](../../../../docs/core/glossary.md#view) reads through the passive
  `[:rf/resource …]`
  [subscription](../../../../docs/core/glossary.md#subscription), and by the
  time it runs the data is simply there. No hung `:loading` skeleton
  reaches the HTML.

- An allowlist on the way out — never the whole cache. The resource
  cache is a rich runtime structure: entries, a tag-index, an
  owner-index, host fetch handles. Almost none of that should cross the
  wire. `handle-request` projects the runtime-db through
  `payload-policy/project-runtime-db`, which ships only the durable
  resource `:entries` onto the payload's `:rf/runtime-db`. Per entry it
  honours [data
  classification](../../../../docs/core/glossary.md#data-classification): a
  `:sensitive?` value is redacted, a `:large?` value is omitted.
  The reverse indexes (`:tag-index` / `:owner-index`) are excluded —
  the client recomputes them from `:entries` the moment it installs them,
  so shipping them would just be sending data you're about to rebuild.
  Host handles never serialise. The
  [app-db](../../../../docs/core/glossary.md#app-db) slice rides the same
  fail-closed policy (`apply-policy`) the real Ring host uses. It ships
  `{}` here — on this page the resource is the state and app-db carries
  nothing of its own — but fail-closed means even "send nothing" must be
  said out loud, so the code opts in with `:rf.ssr.payload/whole-app-db`
  rather than passing an empty allowlist (which would throw).

- No double-fetch on the client — the payoff. On the browser side the
  framework's `:rf/hydrate` installs the projection into the client
  frame's `:rf.runtime/resources` slice. The
  [hydration](../../../../docs/ssr/glossary.md#hydration) reconcile then does
  the cleanup the wire couldn't carry: it orphans the now-defunct SSR
  owner, recomputes the reverse indexes from `:entries`, and settles any
  wire-stripped in-flight entry to a stable
  [status](../../../../docs/resources/glossary.md#resource-status).
  Reconciling is all it does. It classifies what arrived; it never causes
  work — and neither does a passive subscription. So the client issues one
  command of its own: `run` dispatches `:resources-ssr.app/page-opened`,
  which `ensure`s the same resource under the app's own
  [owner](../../../../docs/resources/glossary.md#owner--cause) before the
  first render. That is where the payoff actually lands. For the fresh
  hydrated entry the `ensure` is a cache hit — it takes the hold, arms the
  entry's timers, and issues nothing — so the server's data renders on the
  first paint and no second request goes out. A cold or stale entry gets
  exactly the one load it needs, and no more.

- Acquisition is the app's job here, because there is no route. A routed
  page would not write that event at all: `reg-route`'s `:resources` plan
  `ensure`s under a `[:route route-id nav-token]` owner on route entry and
  releases it on route leave, so the framework mints and retires the hold
  for you. This page is deliberately route-free — the simplest SSR shape
  there is — so it uses the framework's other answer, the app-minted event
  owner ([Spec 016 §The scoped-cache owner
  lifecycle](../../../../spec/016-Resources.md)). Taking the hold is what
  makes the hydrated cache a live cache rather than a picture of one: an
  entry nobody owns is renderable but inert — tag invalidation and
  focus/reconnect revalidation pass it by, and no GC clock runs.

  The matching `:resources-ssr.app/page-closed` release is registered here
  too, and it is worth reading, but nothing in this demo dispatches it —
  the page is the whole app, so its lifetime is its frame's, and the entry
  it would release lives in that frame's runtime-db and goes when the frame
  does. Copy it, though, and the pairing stops being optional the moment
  the page can be unmounted while the frame lives on: an app-minted owner
  is never auto-released, so an unreleased one pins its entry for as long
  as the cache exists. A routed app never writes either half — route leave
  drops the route owner for you.

- Scope is a wall hydration may never cross. The resource declares
  `:scope :rf.scope/global` — the auditable claim that this article list
  is identical for every user.
  [Scope](../../../../docs/resources/glossary.md#scope) is part of a
  resource's cache identity, and hydration must never cross scopes: a
  request-local SSR frame and the serialised payload have to agree on
  scope before the client treats hydrated data as usable. A user-scoped
  page would carry a scope resolver instead; the global claim here is the
  explicit, checkable statement that this handoff is safe.

The SSR-resource runtime is real: `handle-request` drives the actual
server path, not a skeleton stand-in. The blocking poll, the per-entry
payload projection, the client hydration reconcile, and the client
acquisition all run end-to-end. Two limits on that worth stating plainly.
`:articles/list` declares neither `:sensitive?` nor `:large?`, so the
projection's redaction and omission branches are available to it but are
never taken on this page. And hydration's refetch plan *classifies* what
arrived without issuing anything — the request this page does or doesn't
make is decided by the client `ensure` above, not by the plan. The generic
contract for both is [Spec 016 §SSR and
hydration](../../../../spec/016-Resources.md).

The `index.html` next to this file carries a pre-baked hydration
payload — a `:loaded` `:articles/list` entry — so the browser-side `run`
runs without a Clojure server in the box. Like the live runtime, the entry
is map-keyed by its CEDN-1 byte `key-id` (the storage identity public
reads look up under) and carries the canonical scoped key
`[:rf.scope/global :articles/list {}]` as its own `:resource/key` (the
scoped resource fact the reverse indexes and refetch plan re-key from) —
not under the scoped-key vector as a map key. It's an illustrative
stand-in for what `handle-request` emits behind a real server, not a
byte-exact capture. Because `:articles/list` declares no time-based
staleness policy, the baked entry carries `:stale-at nil` and stays
deterministically fresh — no rotting absolute deadline. So the client's
`:resources-ssr.app/page-opened` `ensure` takes the fresh-skip path: it
attaches the page owner and arms the entry's timers without issuing a
request, which is what lets this fixture run with no server behind it.
One visible difference is the frame-id. This hand-written
payload pins `:rf/frame-id :rf/default` — present and equal to the
client's fixed target, a valid no-conflict shape a static file can
hand-pick. `handle-request` instead drops `:rf/frame-id`, because its
per-request gensym frame would never equal that target (an absent id is
the other no-conflict shape). Both hydrate cleanly; both are correct.
This mirrors the sibling [`examples/capabilities/ssr/ssr/`](../ssr/), which
likewise bakes a plain SSR payload into its own `index.html`.

## Deferred — not built here

- Mutation with invalidation — the next phase. No
  [mutation](../../../../docs/resources/glossary.md#mutation) example is
  built; see
  [`examples/capabilities/resources/resources/`](../../resources/resources/README.md).
- GraphQL — a deferred later phase.
  [`:rf.http/managed`](../../../../docs/resources/glossary.md#managed-http)
  is the single built-in transport. No GraphQL example is built.

## Files

```
resources_ssr/
  core.cljc    — resource, server-init preload, views, server + client entry points.
  index.html   — host page with a pre-baked hydration payload (the resource projection).
```

## How to run

```bash
# From implementation/:
npm run dev:example -- examples/resources-ssr
```

Then open the URL it prints. The runner stages this folder's
[`index.html`](index.html) alongside the build output for you; its pre-baked
payload lets the page hydrate without a Clojure server in the box.

## Cross-references

- [`spec/016-Resources.md`](../../../../spec/016-Resources.md) — the normative spec (§SSR and hydration).
- [`spec/011-SSR.md`](../../../../spec/011-SSR.md) — the SSR substrate this rides.
- [`docs/resources/concepts.md`](../../../../docs/resources/concepts.md#ssr-and-hydration) — the guide (§SSR and hydration).
- [`examples/capabilities/resources/resources/`](../../resources/resources/) — the client-side resource patterns.
- [`examples/capabilities/ssr/ssr/`](../ssr/) — plain SSR (server-state into app-db, not a resource).
