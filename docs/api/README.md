# The re-frame2 API

This is the complete public API reference for the manifest-tracked namespaces of
the ClojureScript implementation of re-frame2 — one page per public namespace, with
the boundary of "manifest-tracked" set out under Completeness below. Entries use a
consistent shape: Kind, Signature, Description (contract, including error
ids where they are part of the surface), and an Example where a call is worth
showing. The Example is optional — many contract-only surfaces (compile-time template
forms, symbol-resolution vars) carry no runnable call — so its absence is not a gap.

For the mental model, start with the [Core guide](../core/introduction.md). This
corpus is deliberately terse: it states what you may call, not why the design
chose it.

## How to read these pages

| Audience need | Where |
|---|---|
| Day-to-day app API | [`re-frame.core`](re-frame.core.md) (the facade) |
| Optional capabilities | machines, routing, resources, flows, schemas, HTTP, SSR |
| Substrate adapters | `re-frame.adapter.{reagent,uix}` — first-class and permanent |
| The Hicasso view layer | [`re-frame.hicasso`](re-frame.hicasso.md) for the door's vars; [Hicasso API reference](../core/hicasso/api-reference.md) for the full contract and the optional modules |
| Tests | [`re-frame.test-support`](re-frame.test-support.md), [`re-frame.test-helpers`](re-frame.test-helpers.md) |
| Production timing | [`re-frame.performance`](re-frame.performance.md) |

Facade vs owning namespace. Many optional features re-export registration verbs
through `re-frame.core` (for example `reg-machine`, `reg-flow`, `reg-resource`). The
core page carries a short entry and points at the owning namespace for the full
contract. Prefer requiring the feature namespace when you need depth; `rf/` remains
valid for the re-export.

Keyword surfaces. Events, fx, subs, and similar keyword-addressed
registrations (`:rf.http/managed`, `:rf/machine`, …) appear as tables or sections on
the owning page. They are not vars; the [api-manifest](../../spec/api-manifest.edn)
tracks vars.

Completeness. Public vars in the manifest with tiers `:front-porch`,
`:advanced`, `:adapter`, or `:testing` under `re-frame.*` are expected to appear on
these pages (or as an explicit facade pointer). Tooling and implementation tiers are
out of scope here. This is enforced: the api-manifest `doc-api-check` reconciles
every eligible manifest namespace against `docs/api/`, so an eligible namespace with
no page — or an eligible var with no member heading (`### \`var\``, or a
`#### \`var\`` facade-pointer entry on the owning/facade page) — turns the CI check
red. A member heading may be written bare (`### \`sub\``) or namespace-qualified
(`### \`re-frame.machines/machine-transition\``).

Where Hicasso sits. The **door** — `re-frame.hicasso` — is manifest-tracked like
any other published namespace, and has been since rf2-phm7g. It is a split-host
`.cljc`: its three authoring macros are the `:clj` arm the JVM generator
introspects, and its eleven runtime vars are the `:cljs` arm the analyzer probe
reconciles, so a public added, removed or renamed on either host turns the
api-manifest gate red. Since rf2-3ne8 the whole tree is scanned rather than the
door alone: `implementation/hicasso/src` is a roster-covered root, so every
namespace under it is classified, and its **optional authoring modules**
(`.forms`, `.overlay`, `.motion`, `.native`, `.substrate`) carry rows and pages
here on the same footing as any other published surface. The `.server` SSR module
and the `.tool` / `.evidence` reader door are rowed at the `:implementation` and
`:tooling` tiers, which the completeness clause above puts out of scope for this
corpus; everything else under the tree is `re-frame.hicasso.impl.*` and is not a
consumer surface.

The split follows the depth, not the coverage. Read
[`re-frame.hicasso`](re-frame.hicasso.md) here for the door's var index, the five
module pages for their public vars, and the
[Hicasso API reference](../core/hicasso/api-reference.md) for the full authoring
contract and every optional module. Read this corpus for the pipeline — events,
app-db, subscriptions, effects, the optional capabilities and the substrate
adapters. A Hicasso application uses both, because Hicasso replaces the view
notation and nothing else.

## Namespaces

### Facade and core dataflow

| Page | Role |
|---|---|
| [re-frame.core](re-frame.core.md) | Registration, dispatch, subscribe, views (`reg-view`), frames, boot, interceptors, feature re-exports |

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

### Hicasso's optional authoring modules

Each is opt-in: nothing under the artefact's `src/` requires it, so a build that
never asks for one carries none of it.

| Page | Role |
|---|---|
| [re-frame.hicasso.forms](re-frame.hicasso.forms.md) | Buffered field, and the draft concern behind it |
| [re-frame.hicasso.overlay](re-frame.hicasso.overlay.md) | `popover` / `modal` on the browser's top layer |
| [re-frame.hicasso.motion](re-frame.hicasso.motion.md) | `presence` — retention for exiting keyed children |
| [re-frame.hicasso.native](re-frame.hicasso.native.md) | The two React-island hooks, `use-sub` and `use-frame` |

### Adapters, tests, tooling

| Page | Role |
|---|---|
| [re-frame.adapter.reagent](re-frame.adapter.reagent.md) | Stock / slim Reagent substrate |
| [re-frame.adapter.uix](re-frame.adapter.uix.md) | UIx substrate |
| [re-frame.hicasso](re-frame.hicasso.md) | The Hicasso view layer's door — authoring macros, reads, roots, markup |
| [re-frame.hicasso.substrate](re-frame.hicasso.substrate.md) | Hicasso's own substrate adapter — the value `init!` takes |
| [re-frame.test-support](re-frame.test-support.md) | Fixtures, registrar snapshot, poll |
| [re-frame.test-helpers](re-frame.test-helpers.md) | Hiccup walkers, testids |
| [re-frame.performance](re-frame.performance.md) | Compile-time User-Timing flags |

## Require patterns

```clojure
;; Typical app — on a Reagent / UIx substrate (both first-class and permanent)
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
- [Hicasso API reference](../core/hicasso/api-reference.md) — the view layer's own
  corpus: the door's full contract, and the optional modules, which carry no
  api-manifest rows
- [spec/API.md](../../spec/API.md) — normative var catalogue with tiers (projection of
  the api-manifest)
- Feature guides under Machines, Resources, Routing, SSR, Async tabs
