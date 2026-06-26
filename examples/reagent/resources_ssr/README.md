# resources_ssr — Spec 016 §SSR worked example

The sibling [`examples/reagent/ssr/`](../ssr/) ships an articles page where the
server *bakes the fetched list into app-db* and hands that frozen value to the
client. This example asks the harder question: what if the page's server-state
isn't a value you froze, but a re-frame2 **resource** — a live cache the
framework owns, with its own identity, scope, and staleness? Now SSR has to
ship more than data. It has to ship *the cache*, faithfully enough that the
client picks up where the server left off — renders the list on the first paint
**and** knows not to re-fetch something it was just handed fresh.

That's the load-bearing idea: a hydrated resource isn't a snapshot pasted into
app-db, it's the runtime's resource cache reconstituted on the client, so the
view reads it passively — no fetch, no flicker — exactly as if the client had
fetched it itself a moment ago. Everything below is the discipline that makes
that handoff safe.

One artefact, two runtimes: it's `.cljc`, so the very same code runs server-side
on the JVM (the `:clj` branches) and client-side in the browser (the `:cljs`
branches), with the reader macros picking the side. Read it as the worked
companion to [Spec 016 §SSR and hydration](../../../spec/016-Resources.md)
riding over [Spec 011 SSR](../../../spec/011-SSR.md); the narrative version,
if you'd rather read the story than the code, lives at
[docs/resources/concepts.md §SSR and hydration](../../../docs/resources/concepts.md#ssr-and-hydration).

## What this demonstrates

The whole resource SSR contract, read top to bottom as one request and its
client pickup. The recurring theme: a process-global cache would leak one
user's data into another's render, so SSR is *aggressively* per-request, and the
wire payload is *aggressively* minimal.

- **A frame per request — because the cache is shared state.** SSR resources run
  in a per-request [frame](../../../docs/guide/glossary.md#frame), never a
  process-global one. The cache lives in the frame's
  [runtime-db](../../../docs/guide/glossary.md#runtime-db), so two concurrent
  renders are two isolated caches that can't see each other — the same frame
  isolation that powers tests and stories, now buying you the privacy guarantee
  that matters most when you're rendering N users' pages in one JVM.
  `handle-request` (`:clj`) mints the frame, dispatches
  `:rf/server-init`, renders, and tears it down in a `finally` on **every** exit
  path — success or throw, no leaked frame per request.

- **Blocking preload, then drain — so the render never sees a skeleton.** A
  resource is normally async: ask for it, get a `:loading` view-model, fill in
  the data when the reply lands. That's wrong for SSR, where there's no second
  render to fill anything in. So `:rf/server-init`
  [ensures](../../../docs/resources/glossary.md#owner--cause) the page resource
  under an `[:ssr request-id nav-token]`
  [owner](../../../docs/resources/glossary.md#owner--cause) with
  [cause](../../../docs/resources/glossary.md#owner--cause) `:ssr-preload`, and
  then `handle-request` calls `ssr/drain-blocking-resources!` to *wait* for that
  ensure to settle — reach `:loaded`, or time out into a structured first-load
  failure — **before** the render walk runs. The
  [view](../../../docs/guide/glossary.md#view) reads through the passive
  `[:rf.resource/state …]` [subscription](../../../docs/guide/glossary.md#subscription)
  and, by the time it runs, the data is simply *there*. No hung `:loading`
  skeleton makes it into the HTML.

- **A ruthless allowlist on the way out — never the whole cache.** The resource
  cache is a rich runtime structure: entries, a tag-index, an owner-index, host
  fetch handles. Almost none of that should cross the wire. `handle-request`
  projects the runtime-db through `payload-policy/project-runtime-db`, which
  ships **only** the durable resource `:entries` onto the payload's
  `:rf/runtime-db`. Per entry it honours
  [data classification](../../../docs/guide/glossary.md#data-classification) —
  a `:sensitive?` value is **redacted**, a `:large?` value is **omitted**. The
  reverse indexes (`:tag-index` / `:owner-index`) are **excluded** entirely
  because they *recompute from `:entries`* the moment the client installs them —
  shipping them would be sending derived data you're about to rebuild anyway.
  Host handles never serialize. The
  [app-db](../../../docs/guide/glossary.md#app-db) slice rides the explicit,
  fail-closed allowlist (`apply-policy`) the real Ring host uses — empty here,
  because on this page *the resource is the state*, and app-db carries nothing
  of its own.

- **No double-fetch on the client — the payoff.** On the browser side the
  framework's `:rf/hydrate` installs the projection into the client frame's
  `:rf.runtime/resources` slice. The
  [hydration](../../../docs/ssr/glossary.md#hydration) reconcile does the
  cleanup the wire couldn't carry: it orphans the now-defunct SSR owner,
  recomputes the reverse indexes from `:entries`, and settles any
  wire-stripped in-flight entry to a stable
  [status](../../../docs/resources/glossary.md#resource-status). The result is
  the whole point of the exercise — a **fresh** hydrated entry renders its data
  immediately and does **not** turn around and re-fetch it. (A stale, redacted,
  or omitted entry *does* refetch — by the hydration refetch plan — because for
  those the client genuinely doesn't have usable data in hand.)

- **Scope is a wall hydration may never climb.** The resource declares
  `:scope :rf.scope/global` — the auditable claim that this article list is
  identical for every user. [Scope](../../../docs/resources/glossary.md#scope)
  is part of a resource's cache identity, and hydration **must never cross
  scopes**: a request-local SSR frame and the serialized payload have to *agree*
  on scope before the client treats hydrated data as usable. A user-scoped page
  would carry a scope resolver instead; the global claim here is the explicit,
  checkable statement that this particular handoff is safe.

The `index.html` next to this file carries a **pre-baked** hydration payload — a
`:loaded` `:articles/list` entry under `[:rf.scope/global :articles/list {}]` —
so the browser-side `run` is runnable without a Clojure server in the box. It's
an **illustrative** stand-in for the projection `handle-request` emits when a
real server sits in front — not a byte-exact capture. One visible difference is
the frame-id: this hand-written payload pins `:rf/frame-id :rf/default` (present
*and equal* to the client's fixed target — a valid no-conflict shape a static
file can hand-pick), whereas `handle-request` *drops* `:rf/frame-id` because its
per-request gensym frame would never equal that target (an absent id is the
other no-conflict shape). Both hydrate cleanly; both are correct. This mirrors
the sibling [`examples/reagent/ssr/`](../ssr/), which likewise bakes a plain SSR
payload into *its* `index.html`.

## Landed behaviour

The SSR-resource runtime is **real** (EP-0003), so `handle-request` drives the
actual server path rather than a skeleton stand-in: the blocking drain, the
per-entry projection (redaction / omission / scoped-key privacy / index
omission), and the client hydration reconcile + refetch plan all run
end-to-end. The example tree is test-free, but THIS example's own SSR preload →
projection → client hydration path is pinned by a direct headless JVM fixture,
**`resources-ssr-example-dynamic-payload-hydrates-without-frame-id-mismatch`** in
`implementation/core/test/re_frame/examples_test.clj` (run by `clojure -M:test`):
it drives the example's `handle-request`, asserts the SSR-preloaded resource
settled `:loaded` and rode `:rf/runtime-db` as the `:entries` projection (not the
indexes), then hydrates the example's own `:rf/default` client frame and asserts
the entry installed `:loaded` (renders immediately, no double-fetch). Broader
SSR-resource contract coverage (redaction / scoped-key privacy / restore) lives
in `implementation/resources/test/` (the SSR + restore CLJS suites) and the
EP-0003 §9 conformance fixtures. See the [coverage table](../README.md#coverage-level-per-reagent-example).

## Deferred — not built here

- **Mutation with invalidation** — the next slice (EP-0003 slice 11). No
  [mutation](../../../docs/resources/glossary.md#mutation) example is built; see
  [`../resources/README.md`](../resources/README.md).
- **GraphQL** — a deferred later phase.
  [`:rf.http/managed`](../../../docs/resources/glossary.md#managed-http) is the
  single built-in transport. No GraphQL example is built.

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

The watch build emits `main.js` into `out/examples/resources-ssr/`; copy
this folder's [`index.html`](index.html) (and `../../_shared/`) alongside it
and serve over HTTP. `npm run test:adapter-smokes` does not build this example.
The JVM-runnable server flow (`handle-request`) is demonstrative code; the
example tree is test-free per [`examples/README.md`](../../README.md).

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec (§SSR and hydration).
- [`spec/011-SSR.md`](../../../spec/011-SSR.md) — the SSR substrate this rides.
- [`docs/resources/concepts.md`](../../../docs/resources/concepts.md#ssr-and-hydration) — the guide (§SSR and hydration).
- [`examples/reagent/resources/`](../resources/) — the client-side resource patterns.
- [`examples/reagent/ssr/`](../ssr/) — plain SSR (server-state into app-db, not a resource).
