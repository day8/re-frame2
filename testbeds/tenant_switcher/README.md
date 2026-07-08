# `testbeds/tenant-switcher`

A single Reagent frame (`:rf/default`) that switches the
**active principal** — an admin impersonating tenant `acme`, then `globex`,
then back — while the scoped-cache keeps each tenant's loaded dashboard
structurally isolated and *simultaneously live*.

This is the multi-**scope** consumer the rest of the testbed tree lacks. Every
other multi-tenant-shaped surface (e.g. `multi_frame/`) is multi-**frame**:
one frame per tenant, each frame isolated. This one is multi-**scope**: one
frame, two tenant scopes coexisting in one resource cache, with the active
scope deciding which entry a read can address.

It is the live demonstrator of the guide's claim
([`docs/resources/concepts.md` §"The scoped key: a leak boundary that
fails closed"](../../docs/resources/concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed)): the resolved scope is *in* the
cache key, so two tenants' reads of the same params land on two structurally
distinct entries — neither reachable through the other's key. EP-0013/resources
Part-2 leak-boundary **scenario 5** (rf2-5e22yc, spun from rf2-wwhedk).

## The model

- **Named scope resolver** (EP-0016 D3): `reg-resource-scope :tenant/scope`
  derives `[:rf.scope/tenant {:tenant-id …}]` from `[:viewer :active-tenant]`.
  Pure, with declared `:inputs` so a tool can explain which app-db fact decides
  a resource's identity.
- **Scoped resource**: `:tenant/dashboard` declares
  `:scope {:from-db :tenant/scope}`, so its cache key carries the active
  tenant structurally.
- **Switching** is one ordinary event (`::switch-tenant`) rewriting
  `[:viewer :active-tenant]`; ensure and the active-scope sub re-resolve
  through the named resolver.
- **Transport** is a deterministic in-memory stub: the testbed overrides the
  `:rf.http/managed` fx to reply through the runtime's own `:on-success`
  target, so the entry loads exactly as a live read would — no network, fully
  deterministic. The success `:value` is derived from the resolved scope baked
  into the resource-key, so each tenant loads its OWN distinct payload (a real
  cross-tenant leak would surface as the wrong motto in the DOM).

## Controls

| Control | `data-testid` | What it does |
|---|---|---|
| Acme Corp | `switch-acme` | Impersonate tenant `acme` (active scope → acme). |
| Globex Inc | `switch-globex` | Impersonate tenant `globex` (active scope → globex). |
| Load active dashboard | `load` | Ensure `:tenant/dashboard {:page 1}` for the CURRENTLY-active tenant (scope derived from app-db). |

## Observables

- **Active dashboard panel** (`dashboard-panel`) — reads `:rf/resource` /
  `:rf.resource/data` with NO explicit scope; resolves the active tenant's
  scope from app-db. `active-tenant` / `status` / `motto` testids. This is the
  read seam where a leak would be visible — it structurally cannot read another
  tenant's entry.
- **Cache witnesses** (`witness-acme` / `witness-globex`) — one EXPLICIT-scope
  `:rf/resource` read per tenant, side by side, regardless of who is
  active. Once both dashboards are loaded, both witnesses read `:loaded` with
  their OWN distinct motto at the same time (`witness-status-<id>`,
  `witness-motto-<id>`): the load-bearing evidence that both entries are
  simultaneously live in one cache and each explicit scope addresses exactly
  its own data.

## Scenario 5 — simultaneously-live multi-scope isolation

The canonical sequence the colocated `spec.cjs` drives:

1. Active tenant boots as `acme`. Click **Load** → acme's dashboard loads
   (`motto = acme-only secret`).
2. Switch to **Globex Inc**. The active panel immediately reads globex's
   *idle* empty-state — **never** acme's cached motto. Click **Load** →
   globex's dashboard loads (`motto = globex-only secret`).
3. Both cache witnesses now read `:loaded` with their own distinct motto at
   once — both entries are live in the cache simultaneously.
4. Switch back to **Acme Corp**. The active panel reads acme's `motto`
   straight from cache (no refetch) — the entry was never evicted, just not
   addressable while globex was active.

A leak in any other server-state library (TanStack Query / RTK Query / SWR)
would show the previous tenant's data after a switch when a key segment is
forgotten; here scope is part of the key's *type*, so the active view can only
ever address the active tenant's entry.

## What's deliberately *missing*

- **No logout / `clear-scope`, wrong-scope, or invalidation paths.** Those
  leak-boundary guarantees are the EXECUTABLE regression vehicle and live as
  CLJS unit tests
  ([`resources_scope_leak_boundary_cljs_test.cljc`](../../implementation/resources/test/re_frame/resources_scope_leak_boundary_cljs_test.cljc)
  scenarios 1/2/3 +
  [`resources_scoped_lease_lifecycle_cljs_test.cljc`](../../implementation/resources/test/re_frame/resources_scoped_lease_lifecycle_cljs_test.cljc)).
  This surface is the live demonstrator of the simultaneous-multi-scope shape
  (scenario 5), not a duplicate regression vehicle.
- **No deliberate bugs / anti-pattern demos.** Idiomatic re-frame2 throughout
  (app-db + events + subs; named scope resolver; explicit-owner lease) — a
  clean test surface (feedback_testbeds_are_test_surfaces).

## Running

From `implementation/`:

```bash
npm run dev -- :testbeds/tenant-switcher
# then open http://localhost:8060/
```

The shadow-cljs build id is `testbeds/tenant-switcher`; output lands in
`implementation/out/testbeds/tenant-switcher/`. The browser smoke is the
colocated `spec.cjs`, run via `npm run test:testbed-tenant-switcher`.

## Cross-references

- [`docs/resources/concepts.md` §"The scoped key: a leak boundary that fails closed"](../../docs/resources/concepts.md#the-scoped-key-a-leak-boundary-that-fails-closed) — the positioning this surface demonstrates live.
- [`spec/016` resources §Named resource-scope resolvers / §Resource identity — the scoped key](../../spec/Spec-Schemas.md) — the `reg-resource-scope` + scoped-key contract.
- [`implementation/resources/test/re_frame/resources_scope_leak_boundary_cljs_test.cljc`](../../implementation/resources/test/re_frame/resources_scope_leak_boundary_cljs_test.cljc) — the executable leak-boundary guarantees (scenarios 1/2/3).
