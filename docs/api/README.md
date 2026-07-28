# The re-frame2 API

This is the **complete public API reference** for the ClojureScript implementation of
re-frame2. One page per public namespace. Entries use a consistent shape: **Kind**,
**Signature**, **Description** (contract, including error ids where they are part of
the surface), and an **Example** where a call is worth showing. The Example is
optional — many contract-only surfaces (compile-time template forms, symbol-resolution
vars) carry no runnable call — so its absence is not a gap.

For the mental model, start with the [Core guide](../core/introduction.md). This
corpus is deliberately terse: it states *what* you may call, not *why* the design
chose it.

**Not everything here is installable, and two different reasons say so.** Three
pages — [`re-frame.ui`](re-frame.ui.md), [`re-frame.ui.react`](re-frame.ui.react.md)
and [`re-frame.ui.test`](re-frame.ui.test.md) — document the **donor** compiled-view
substrate, which has no published Maven coordinate and never will
([release process](../release-process.md#policy)). They are the contract record for
code an app already carries. The four **Freehand** pages —
[`re-frame.freehand`](re-frame.freehand.md),
[`re-frame.freehand.form`](re-frame.freehand.form.md),
[`re-frame.freehand.controls`](re-frame.freehand.controls.md) and
[`re-frame.freehand.test`](re-frame.freehand.test.md) — are unpublished for the other
reason: `day8/re-frame2-freehand` is **pre-alpha**, ships inside the monorepo, and
has no date at which it will be published either. Freehand is not donor code — it is
where new view work starts — and you resolve it with `:local/root` from a checkout
([Install](../core/freehand/get-running/install.md)). The kit's pointer control,
[`re-frame.freehand.splitter`](re-frame.freehand.splitter.md), carries the same banner for
the same reason. Every other page on this list describes an artefact the release workflow
deploys.

## How to read these pages

| Audience need | Where |
|---|---|
| Day-to-day app API | [`re-frame.core`](re-frame.core.md) (the facade) |
| Freehand views (`defview`, callbacks, event intent) | [`re-frame.freehand`](re-frame.freehand.md) — **pre-alpha, not published** |
| Freehand forms and controls | [`re-frame.freehand.form`](re-frame.freehand.form.md), [`re-frame.freehand.controls`](re-frame.freehand.controls.md), [`re-frame.freehand.splitter`](re-frame.freehand.splitter.md) — **pre-alpha, not published** |
| Compiled views (`defview`, `mount`, `sub`) | [`re-frame.ui`](re-frame.ui.md) — **donor, not published** |
| Optional capabilities | machines, routing, resources, flows, schemas, HTTP, SSR |
| Substrate adapters | `re-frame.adapter.{reagent,uix}` — first-class and permanent |
| Tests | [`re-frame.test-support`](re-frame.test-support.md), [`re-frame.test-helpers`](re-frame.test-helpers.md), [`re-frame.freehand.test`](re-frame.freehand.test.md) (pre-alpha, not published — Freehand substrate), [`re-frame.ui.test`](re-frame.ui.test.md) (donor compiled-view substrate) |
| Production timing | [`re-frame.performance`](re-frame.performance.md) |

**Facade vs owning namespace.** Many optional features re-export registration verbs
through `re-frame.core` (for example `reg-machine`, `reg-flow`, `reg-resource`). The
core page carries a short entry and points at the owning namespace for the full
contract. Prefer requiring the feature namespace when you need depth; `rf/` remains
valid for the re-export.

**Keyword surfaces.** Events, fx, subs, and similar *keyword-addressed*
registrations (`:rf.http/managed`, `:rf/machine`, …) appear as tables or sections on
the owning page. They are not vars; the [api-manifest](../../spec/api-manifest.edn)
tracks vars.

**Completeness.** Public vars in the manifest with tiers `:front-porch`,
`:advanced`, `:adapter`, or `:testing` under `re-frame.*` are expected to appear on
these pages (or as an explicit facade pointer). Tooling and implementation tiers are
out of scope here. This is **enforced**: the api-manifest `doc-api-check` reconciles
every eligible manifest namespace against `docs/api/`, so an eligible namespace with
no page — or an eligible var with no member heading (`### \`var\``, or a
`#### \`var\`` facade-pointer entry on the owning/facade page) — turns the CI check
red. A member heading may be written bare (`### \`sub\``) or namespace-qualified
(`### \`re-frame.machines/machine-transition\``).

## Namespaces

### Facade and core dataflow

| Page | Role |
|---|---|
| [re-frame.core](re-frame.core.md) | Registration, dispatch, subscribe, views (`reg-view`), frames, boot, interceptors, feature re-exports |
| [re-frame.freehand](re-frame.freehand.md) | **Pre-alpha — `day8/re-frame2-freehand` is not published.** Freehand view substrate (EP-0036): `defview`, descriptor inspection, callback and event-intent forms |
| [re-frame.freehand.form](re-frame.freehand.form.md) | **Pre-alpha — `day8/re-frame2-freehand` is not published.** Pure form transitions over ordinary data: `init` / `edit` / `visit` / `seed` / `reset` / `rebase` / `set-errors` / `attempt-submit`, and the narrow per-leaf `field` read |
| [re-frame.freehand.controls](re-frame.freehand.controls.md) | **Pre-alpha — `day8/re-frame2-freehand` is not published.** The first-party control kit: `field`, `buffered-field`, the causal owner's `release`, and the composing-Enter law |
| [re-frame.freehand.splitter](re-frame.freehand.splitter.md) | **Pre-alpha — `day8/re-frame2-freehand` is not published.** The kit's pointer control: a resizable pane divider, the `settle` arithmetic both its clocks end at, the keyboard law, and five transitions over an ordinary value |
| [re-frame.ui](re-frame.ui.md) | **Donor, not published.** Compiled-view substrate: `defview`, `sub`, `mount`, `frame-root`, interop forms |

### Optional capabilities

| Page | Role |
|---|---|
| [re-frame.schemas](re-frame.schemas.md) | App / event / effect schemas |
| [re-frame.flows](re-frame.flows.md) | Materialised derivations into app-db |
| [re-frame.http](re-frame.http.md) | Managed HTTP fx and interceptors |
| [re-frame.machines](re-frame.machines.md) | State machines |
| [re-frame.routing](re-frame.routing.md) | Router, routes, route link |
| [re-frame.resources](re-frame.resources.md) | Resource cache, owners, mutations |
| [re-frame.ssr](re-frame.ssr.md) | Server render, head, payloads |
| [re-frame.ssr.ring](re-frame.ssr.ring.md) | Ring adapter for SSR |
| [re-frame.epoch](re-frame.epoch.md) | Epoch history / time-travel surface |

### Adapters, tests, tooling

| Page | Role |
|---|---|
| [re-frame.adapter.reagent](re-frame.adapter.reagent.md) | Stock / slim Reagent substrate |
| [re-frame.adapter.uix](re-frame.adapter.uix.md) | UIx substrate |
| [re-frame.test-support](re-frame.test-support.md) | Fixtures, registrar snapshot, poll, sequester |
| [re-frame.test-helpers](re-frame.test-helpers.md) | Hiccup walkers, testids |
| [re-frame.freehand.test](re-frame.freehand.test.md) | **Pre-alpha — `day8/re-frame2-freehand` is not published.** Freehand structural test surface: headless render + with-render/find/find-all/attrs/text, both hosts |
| [re-frame.ui.test](re-frame.ui.test.md) | **Donor, not published.** Compiled-view test surface: headless render + find/attrs/text, mounted DOM |
| [re-frame.performance](re-frame.performance.md) | Compile-time User-Timing flags |

## Require patterns

```clojure
;; Typical app — Freehand views
(:require [re-frame.core :as rf]
          [re-frame.freehand :as v])

(rf/init! v/adapter)

;; Or on a Reagent / UIx substrate (both first-class and permanent)
(:require [re-frame.core :as rf]
          [re-frame.adapter.reagent :as reagent-adapter])

(rf/init! reagent-adapter/adapter)

;; Tests
(:require [re-frame.core :as rf]
          [re-frame.test-support :as ts]
          [re-frame.test-helpers :as th])
```

## Related corpora

- [Core guide](../core/introduction.md) — progressive teaching
- [spec/API.md](../../spec/API.md) — normative var catalogue with tiers (projection of
  the api-manifest)
- Feature guides under Machines, Resources, Routing, SSR, Async tabs
