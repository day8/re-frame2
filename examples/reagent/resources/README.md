# resources — Spec 016 worked example

A small server-state app — an articles list and an article detail — that
demonstrates re-frame2 **resources** (declarative, cached server-state
reads) composed as proper re-frame2: app-db + events + subs, views
passive, fetches caused. The worked companion to [Spec
016](../../../spec/016-Resources.md) (the EP-0003 read-resource MVP) and
the tutorial in [docs/guide/27-resources.md](../../../docs/guide/27-resources.md).

## What this demonstrates

The four **landed** causal patterns, in one cohesive app:

- **Route-driven page load** — `reg-route` with `:resources` metadata
  (`:resources.app/articles`, `:resources.app/article-detail`). Route entry
  marks each resource active under a `[:route route-id nav-token]` owner and
  ensures it with cause `[:route-entry …]`; route leave releases by token.
- **Event-driven ensure** — `:resources.app/preview-opened` dispatches
  `:rf.resource/ensure` under an app-minted `[:lease …]` owner, with a
  matching `:resources.app/preview-closed` → `:rf.resource/release-owner`
  release path (app leases are app-authoritative).
- **Manual refresh** — the "Refresh" button dispatches
  `:rf.resource/refetch` with a `:cause` and **no** `:owner` (a refresh
  wants fresh data but keeps nothing alive — owner vs cause).
- **Machine-owned resource** — `:resources.app/reader` ensures the article
  under a `[:machine machine-id instance-id]` owner (released on actor
  destroy); the machine stays the semantic workflow.

Plus the read side: views read everything through the **passive**
`[:rf.resource/state …]` sub (and its narrower projections), distinguishing
`:loading?` (first-load skeleton) from `:error` + `:has-data?` from
`:fetching?` (refresh-in-flight) from `:refresh-error` (background-refresh
failure with prior data kept).

**Scope is the fail-closed leak boundary.** Each resource declares the
explicit, auditable `:scope :rf.scope/global` claim — these reads are public
and the same for every user. A user/tenant/locale-scoped read would carry a
scope resolver instead; a missing scope policy is a loud
`:rf.error/resource-missing-scope-policy` at registration.

## Slice status

Resources is a **post-v1 optional artefact** (`day8/re-frame2-resources`)
and ships at its **skeleton slice** (rf2-p10npe): `reg-resource`, the
passive `[:rf.resource/*]` subs, and route `:resources` metadata are real
and load cleanly, so this example **compiles** and registers exactly as
shown. The causal event bodies (`:rf.resource/ensure` / `:rf.resource/refetch`)
are registered but raise `:rf.error/resource-not-implemented` until the
runtime slices land (rf2-afpdkn / rf2-pbxj48 / …) — so the **shape** here is
the canonical one a finished app uses, and the live fetch lights up when
those slices ship. This is the same "worked-scaffold on the current API
surface" maturity tier as `examples/reagent/realworld/`.

## Deferred — not built here

Two surfaces are deliberately **not** demonstrated, because they have not
landed:

- **Mutation with invalidation** — a write (`reg-mutation` /
  `:rf.mutation/execute`) that invalidates/patches/refetches resources is
  the **next** slice (EP-0003 slice 11). A mutation example will ship with
  that slice. Until then, a write is an ordinary `:rf.http/managed` event
  whose success dispatches `[:rf.resource/invalidate-tags …]` (tag-based,
  scoped by default) — see the guide chapter.
- **GraphQL** — a deferred later phase, out of the read-resource contract.
  `:rf.http/managed` is the single built-in transport; the lifecycle is
  transport-neutral so a GraphQL transport can plug in later. No GraphQL
  example is built.

## Files

```
resources/
  core.cljs    — resources, routes, events, the reader machine, subs, views, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/resources
```

The watch build emits `main.js` into `out/examples/resources/`; copy this
folder's hand-written [`index.html`](index.html) (and the shared assets it
references under [`../../_shared/`](../../_shared/)) alongside it, then serve
`out/examples/resources/` over HTTP. (`npm run test:examples` does not build
this example — it compiles and serves only the three adapter testbeds; see
[`examples/reagent/README.md`](../README.md).) Examples are test-free per
[`examples/README.md`](../../README.md); resource contract testing lives in
`implementation/resources/test/` and the conformance fixtures.

## Cross-references

- [`spec/016-Resources.md`](../../../spec/016-Resources.md) — the normative spec.
- [`docs/guide/27-resources.md`](../../../docs/guide/27-resources.md) — the tutorial.
- [`examples/reagent/resources_ssr/`](../resources_ssr/) — the SSR preload + hydration counterpart.
- [`examples/reagent/managed_http_counter/`](../managed_http_counter/) — the raw `:rf.http/managed` transport resources lower onto.
- [`examples/reagent/routing/`](../routing/) — the routing surface `:resources` metadata extends.
