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
- **Blocking SSR preload** — `:rf/server-init` ensures the page resource
  under an `[:ssr request-id nav-token]` owner with cause `:ssr-preload`;
  the render waits for it to settle.
- **Allowlist projection** — only the durable resource `:entries` are
  serialized into the hydration payload's `:rf/runtime-db`. The
  `:tag-index` / `:owner-index` recompute from `:entries` on install, and
  host handles never serialize.
- **No-double-fetch hydration** — the framework `:rf/hydrate` installs the
  projection into the client frame's `:rf.runtime/resources` slice; a fresh
  hydrated entry renders immediately and does **not** immediately re-fetch
  (a stale entry would background-refetch by policy).
- **Scope isolation** — the resource declares an explicit
  `:scope :rf.scope/global` claim; hydration MUST NEVER cross scopes
  (request-local SSR frames and serialized scopes must agree).

The `index.html` next to this file carries a **pre-baked** hydration payload
illustrating the serialized resource projection (a `:loaded` `:articles/list`
entry under `[:rf.scope/global :articles/list {}]`), exactly as the sibling
[`examples/reagent/ssr/`](../ssr/) bakes a plain SSR payload — a stand-in for
what a real Clojure server emits.

## Slice status

Resources ships at its **skeleton slice** (rf2-p10npe): `reg-resource`, the
passive subs, and the late-bound SSR projection hook are real, so this
example **compiles** and the SSR shape is canonical. The blocking-drain +
hydration-install **runtime** lands in later slices (rf2-ctk2av /
rf2-pbxj48); until then the live preload would raise
`:rf.error/resource-not-implemented`, and the static `index.html` payload is
the illustrative stand-in. The example tree is test-free (rf2-8cevm);
SSR-resource contract coverage lives in `implementation/resources/test/` and
the conformance fixtures.

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
- [`docs/guide/27-resources.md`](../../../docs/guide/27-resources.md) — the tutorial (§SSR and hydration).
- [`examples/reagent/resources/`](../resources/) — the client-side resource patterns.
- [`examples/reagent/ssr/`](../ssr/) — plain SSR (server-state into app-db, not a resource).
