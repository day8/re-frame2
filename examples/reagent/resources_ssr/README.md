# resources_ssr — Spec 016 §SSR worked example

A server+client app whose page server-state is a re-frame2 **resource**:
the server renders an article list with the resource preloaded; the client
hydrates the resource cache and avoids a duplicate immediate fetch for fresh
entries. The SSR/hydration counterpart to
[`examples/reagent/resources/`](../resources/), and the worked companion to
[Spec 016 §SSR and hydration](../../../spec/016-Resources.md) over [Spec 011
SSR](../../../spec/011-SSR.md).

`.cljc` so the same code runs server-side (JVM) and client-side (browser).

## What this demonstrates

The resource SSR contract (Spec 016 §SSR and hydration):

- **Request-local frame** — SSR uses a per-request frame; a process-global
  resource cache would leak data between users. `handle-request` (`:clj`)
  makes the frame, dispatches `:rf/server-init`, renders, and tears the
  frame down in a `finally` on every exit path.
- **Blocking SSR preload + drain** — `:rf/server-init` ensures the page
  resource under an `[:ssr request-id nav-token]` owner with cause
  `:ssr-preload`; `handle-request` then calls `ssr/drain-blocking-resources!`
  to settle that blocking ensure (or time it out into a structured first-load
  failure) **before** the render walk, so the render never sees a hung
  `:loading` skeleton.
- **Allowlist projection** — `handle-request` projects the runtime-db through
  `payload-policy/project-runtime-db`, so only the durable resource `:entries`
  ride the hydration payload's `:rf/runtime-db` — per-entry **redacted**
  (`:sensitive?`) / **omitted** (`:large?`), with the `:tag-index` /
  `:owner-index` **excluded** (they recompute from `:entries` on install) and
  host handles never serialized. The full runtime-db is **never** shipped; the
  `:rf/app-db` slice rides the explicit fail-closed allowlist (`apply-policy`)
  the real Ring host uses (empty here — the page state is the resource).
- **No-double-fetch hydration** — the framework `:rf/hydrate` installs the
  projection into the client frame's `:rf.runtime/resources` slice; the
  hydration reconcile orphans the SSR owner, recomputes the reverse indexes,
  and settles a wire-stripped in-flight entry to a stable status. A fresh
  hydrated entry renders immediately and does **not** immediately re-fetch; a
  stale / redacted / omitted entry refetches by the hydration refetch plan.
- **Scope isolation** — the resource declares an explicit
  `:scope :rf.scope/global` claim; hydration MUST NEVER cross scopes
  (request-local SSR frames and serialized scopes must agree).

The `index.html` next to this file carries a **pre-baked** hydration payload
illustrating the serialized resource projection (a `:loaded` `:articles/list`
entry under `[:rf.scope/global :articles/list {}]`) for the runnable
browser-side `run` — exactly as the sibling [`examples/reagent/ssr/`](../ssr/)
bakes a plain SSR payload, a stand-in for what `handle-request` emits when a
real Clojure server sits in front.

## Landed behaviour

The SSR-resource runtime is **real** (EP-0003), so `handle-request` drives the
actual server path rather than a skeleton stand-in: the blocking-drain,
the per-entry projection with redaction / omission / scoped-key
privacy / index omission, and the client hydration
reconcile + refetch plan all run end-to-end. The example tree is
test-free, but THIS example's own SSR preload → projection → client
hydration path is pinned by a direct headless JVM fixture,
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
  mutation example is built; see [`../resources/README.md`](../resources/README.md).
- **GraphQL** — a deferred later phase. `:rf.http/managed` is the single
  built-in transport. No GraphQL example is built.

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
and serve over HTTP. `npm run test:examples` does not build this example.
The JVM-runnable server flow (`handle-request`) is demonstrative code; the
example tree is test-free per [`examples/README.md`](../../README.md).

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec (§SSR and hydration).
- [`spec/011-SSR.md`](../../../spec/011-SSR.md) — the SSR substrate this rides.
- [`docs/guide/concepts/server-state.md`](../../../docs/guide/concepts/server-state.md#ssr-and-hydration) — the guide (§SSR and hydration).
- [`examples/reagent/resources/`](../resources/) — the client-side resource patterns.
- [`examples/reagent/ssr/`](../ssr/) — plain SSR (server-state into app-db, not a resource).
