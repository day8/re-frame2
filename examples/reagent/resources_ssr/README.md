# A server-rendered page that arrives already filled in

This example renders a list of articles on the server and sends the page
to the browser with the list already in it. Open the page and the
articles are on screen at the first paint — no spinner, and the browser
doesn't go back to fetch them again. You don't need a running Clojure
server to see it: the [`index.html`](index.html) here ships with a
pre-baked payload that stands in for what a real server would send.

Here's the idea worth taking away:

> **The list isn't a value the server froze. It's a cache the client rebuilds.**

The sibling [`examples/reagent/ssr/`](../ssr/) does the frozen-value
version: the server fetches a list, bakes it into app-db, and hands that
snapshot across. This example is the harder case. The list's state is a
re-frame2 [resource](../../../docs/resources/glossary.md#resource) — a
live cache the framework owns, with its own identity, scope, and
staleness. So SSR can't just freeze a value; it has to ship *the cache
itself* — faithfully enough that the client renders the list on the first
paint **and** knows not to re-fetch something it was just handed fresh.

That's the load-bearing idea. A hydrated resource isn't a snapshot pasted
into app-db. It's the runtime's resource cache rebuilt on the client, so
the [view](../../../docs/guide/glossary.md#view) reads it passively — no
fetch, no flicker — just as if the client had fetched it a moment ago.
Everything below is the discipline that makes that handoff safe.

One artefact, two runtimes: the code is `.cljc`, so the same file runs on
the server (the `:clj` branches) and in the browser (the `:cljs`
branches), with reader macros picking the side. Read it as the worked
companion to [Spec 016 §SSR and hydration](../../../spec/016-Resources.md)
riding over [Spec 011 SSR](../../../spec/011-SSR.md). For the narrative
version, see
[docs/resources/concepts.md §SSR and hydration](../../../docs/resources/concepts.md#ssr-and-hydration).

## What this demonstrates

The whole resource SSR contract, read top to bottom as one request and
its client pickup. The recurring theme: a process-global cache would leak
one user's data into another's render, so SSR keeps everything
per-request, and the wire payload stays minimal.

- **A frame per request — because the cache is shared state.** SSR
  resources run in a per-request
  [frame](../../../docs/guide/glossary.md#frame), never a process-global
  one. The cache lives in that frame's
  [runtime-db](../../../docs/guide/glossary.md#runtime-db), so two
  concurrent renders are two isolated caches that can't see each other.
  It's the same frame isolation that powers tests and stories — here it
  buys the privacy you most need when rendering many users' pages in one
  JVM. `handle-request` (`:clj`) mints the frame, dispatches
  `:rf/server-init`, renders, and tears the frame down in a `finally` on
  **every** exit path — success or throw, no leak.

- **Block on the preload, so the render never sees a skeleton.** A
  resource is normally async: ask for it, get a `:loading` view-model,
  fill in the data when the reply lands. That's wrong for SSR, where
  there's no second render to fill anything in. So `:rf/server-init`
  [ensures](../../../docs/resources/glossary.md#owner--cause) the page
  resource under an `[:ssr request-id nav-token]`
  [owner](../../../docs/resources/glossary.md#owner--cause) with
  [cause](../../../docs/resources/glossary.md#owner--cause)
  `:ssr-preload`. Then `handle-request` calls
  `ssr/drain-blocking-resources!` to **wait** for that ensure to settle —
  reach `:loaded`, or time out into a structured first-load failure —
  before the render runs. The
  [view](../../../docs/guide/glossary.md#view) reads through the passive
  `[:rf.resource/state …]`
  [subscription](../../../docs/guide/glossary.md#subscription), and by the
  time it runs the data is simply *there*. No hung `:loading` skeleton
  reaches the HTML.

- **An allowlist on the way out — never the whole cache.** The resource
  cache is a rich runtime structure: entries, a tag-index, an
  owner-index, host fetch handles. Almost none of that should cross the
  wire. `handle-request` projects the runtime-db through
  `payload-policy/project-runtime-db`, which ships **only** the durable
  resource `:entries` onto the payload's `:rf/runtime-db`. Per entry it
  honours [data
  classification](../../../docs/guide/glossary.md#data-classification): a
  `:sensitive?` value is **redacted**, a `:large?` value is **omitted**.
  The reverse indexes (`:tag-index` / `:owner-index`) are **excluded** —
  the client recomputes them from `:entries` the moment it installs them,
  so shipping them would just be sending data you're about to rebuild.
  Host handles never serialize. The
  [app-db](../../../docs/guide/glossary.md#app-db) slice rides the same
  fail-closed allowlist (`apply-policy`) the real Ring host uses — empty
  here, because on this page *the resource is the state* and app-db
  carries nothing of its own.

- **No double-fetch on the client — the payoff.** On the browser side the
  framework's `:rf/hydrate` installs the projection into the client
  frame's `:rf.runtime/resources` slice. The
  [hydration](../../../docs/ssr/glossary.md#hydration) reconcile then does
  the cleanup the wire couldn't carry: it orphans the now-defunct SSR
  owner, recomputes the reverse indexes from `:entries`, and settles any
  wire-stripped in-flight entry to a stable
  [status](../../../docs/resources/glossary.md#resource-status). The
  result is the whole point: a **fresh** hydrated entry renders its data
  immediately and does **not** re-fetch it. (A stale, redacted, or
  omitted entry *does* refetch — because for those the client genuinely
  has no usable data in hand.)

- **Scope is a wall hydration may never cross.** The resource declares
  `:scope :rf.scope/global` — the auditable claim that this article list
  is identical for every user.
  [Scope](../../../docs/resources/glossary.md#scope) is part of a
  resource's cache identity, and hydration **must never cross scopes**: a
  request-local SSR frame and the serialized payload have to *agree* on
  scope before the client treats hydrated data as usable. A user-scoped
  page would carry a scope resolver instead; the global claim here is the
  explicit, checkable statement that this handoff is safe.

The SSR-resource runtime is **real**: `handle-request` drives the actual
server path, not a skeleton stand-in. The blocking drain, the per-entry
projection (redaction / omission / scoped-key privacy / index omission),
and the client hydration reconcile + refetch plan all run end-to-end.

The `index.html` next to this file carries a **pre-baked** hydration
payload — a `:loaded` `:articles/list` entry under
`[:rf.scope/global :articles/list {}]` — so the browser-side `run` runs
without a Clojure server in the box. It's an **illustrative** stand-in
for what `handle-request` emits behind a real server, not a byte-exact
capture. One visible difference is the frame-id. This hand-written
payload pins `:rf/frame-id :rf/default` — present *and equal* to the
client's fixed target, a valid no-conflict shape a static file can
hand-pick. `handle-request` instead *drops* `:rf/frame-id`, because its
per-request gensym frame would never equal that target (an absent id is
the other no-conflict shape). Both hydrate cleanly; both are correct.
This mirrors the sibling [`examples/reagent/ssr/`](../ssr/), which
likewise bakes a plain SSR payload into *its* `index.html`.

## Deferred — not built here

- **Mutation with invalidation** — the next phase. No
  [mutation](../../../docs/resources/glossary.md#mutation) example is
  built; see [`../resources/README.md`](../resources/README.md).
- **GraphQL** — a deferred later phase.
  [`:rf.http/managed`](../../../docs/resources/glossary.md#managed-http)
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
shadow-cljs watch examples/resources-ssr
```

Then serve this folder's [`index.html`](index.html) alongside the build
output and open it. Its pre-baked payload lets the page hydrate without a
Clojure server in the box.

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec (§SSR and hydration).
- [`spec/011-SSR.md`](../../../spec/011-SSR.md) — the SSR substrate this rides.
- [`docs/resources/concepts.md`](../../../docs/resources/concepts.md#ssr-and-hydration) — the guide (§SSR and hydration).
- [`examples/reagent/resources/`](../resources/) — the client-side resource patterns.
- [`examples/reagent/ssr/`](../ssr/) — plain SSR (server-state into app-db, not a resource).
