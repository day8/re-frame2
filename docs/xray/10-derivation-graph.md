# 10. Derivation graph

The other Xray tabs answer "what just happened?" The derivation graph answers a quieter, structural question: **"where does this value come from?"** A page reads a dozen subscriptions, a couple of resources, a route param, and a machine selector, and you cannot see how they relate. Which fact feeds which? Is this value durable app-db state, a server-owned cache entry, or an ephemeral reaction? When does it re-evaluate? Which owner keeps it alive?

The derivation graph draws all of that as **one picture**. Every subscription, flow, resource, route fact, and machine selector is a node in a single dependency graph rooted at your state — the same unified view [One graph: derivations and algebra views](../guide/derivations-and-algebra-views.md) describes in the guide, here rendered for your running app. ([`spec/Derivations.md`](https://github.com/day8/re-frame2/blob/main/spec/Derivations.md) is the normative model.)

## The two superkinds

Every node is one of exactly two kinds, and reading that distinction is the first thing the panel buys you:

- A **derivation** (○) is a pure computation over inputs — it stores nothing of its own and recomputes on demand. A subscription is the archetype.
- A **process** (◆) is stateful — it has a snapshot or a cache entry that advances over time. A resource entry, a route fact, and a machine are processes.

The panel classifies and groups every node by this superkind. Finer labels — `resource-process`, `route-fact`, `machine-process`, `machine-selector` — ride a separate *refinement* axis and only tint the row's family colour; they never change the basic derivation-vs-process read. A node whose family Xray doesn't recognise still classifies and renders by its superkind rather than breaking the view.

## The five families

Nodes are grouped into five family sections, in editorial order:

- **Subscriptions** — derivations over app-db and over other subscriptions.
- **Flows** — derivations that *materialise* into app-db at a declared path.
- **Resources** — processes whose authority is a remote server; their local storage is a runtime-db cache entry.
- **Routes** — the match fact, params, and transition state of the active route.
- **Machines** — process snapshots plus the selector subscriptions layered over them.

A family with no registrations in your app simply contributes no nodes — an app that never registered a machine just shows no machine section, and the panel names that empty state rather than implying the tool is broken.

## What each node row tells you

Beyond its id and family, each row carries the classification axes that make the graph readable without opening source:

- **Storage** — *where the value lives locally*: `:app-db`, the sub-cache (`:reaction`), `:runtime-db`, or nothing (a pure on-demand derivation). This is the axis that tells a flow (app-db) apart from a plain subscription (reaction) apart from a resource entry (runtime-db).
- **Evaluation** — *when it recomputes*: on demand, after every event, on cause-and-staleness, on transition.
- **Owner** — *who keeps it alive*: a mounted view, a registered flow, a resource owner-token, a machine instance, a nav-token. When the owner releases, the node's value can be reclaimed.
- **Authority chip** — present only on nodes whose source of truth lives *outside* the frame. A resource shows an authority chip (`:remote`, with its transport) alongside its local `:runtime-db` storage — the chip answers "whose fact is this, really?" while storage still answers "where is the local copy?" Most nodes have no authority chip; their value is local-authoritative.
- **Parametric marker** — a parametric subscription (one taking a query argument) shows this marker. In static mode it contributes no edges, because its concrete inputs aren't known until it runs; its realized edges appear in live mode.

Below the family sections, the **edges** list the dependency records — `:input`, `:param`, and `:selector` edges, each as a `from → to` pair. A machine-selector edge points at exactly the machine(s) the selector reads, never the cross product of every selector against every machine.

## Static vs live

A toggle in the panel header switches two modes:

- **Static** is the registration-derived graph: every registered fact and process plus the edges known from registration alone. It is process-global and frame-agnostic — the map of what *can* exist. A parametric subscription appears with its marker but contributes no static edges.
- **Live** is the graph realized in the *observed frame* at this moment: concrete subscription query vectors with their realized input edges, the active resource cache entries keyed by scoped key, live machine instances and spawned actors, and the materialized route slice with its nav-token owner. Live mode is dev-only — it is empty for a missing or destroyed frame, and its machinery is compiled out of production builds.

Use static when you want the structural map; use live when you want to see what your running frame actually built.

## Reading off-box is redacted

On your own box, in your own browser, the panel shows **raw** value summaries — that is the in-process truth, and read-only inspection of your own app is fine (the summaries are bounded only so a multi-megabyte value can't wreck the panel). 

The boundary matters the moment a value-bearing graph leaves your machine — a capture streamed to a remote agent, serialized to disk, or posted to a service. At that egress the graph is projected through `project-egress` under the observed frame's **classification registry**, and value-bearing leaf fields (a node's `:value`, `:params`, `:query`, `:state`) are redacted **path-by-path**: the runtime substitutes `:rf/redacted` at each path the frame declared sensitive (and a size marker at each large path). Those declarations are the frame's own classification — durable `app-db` paths classified by the EP-0025 commit-plane `:sensitive` / `:large` effects (a `reg-event` returning them alongside `:db`), plus the per-slot `:sensitive?` / `:large?` props that own machine `:data` and resource data. (Schemas describe *shape*, not durable-`app-db` egress policy — there is no schema-driven classification of an app-db path.) Projection is **path-based, never value-match**: a secret re-keyed onto an *unclassified* path is not chased by value — it ships raw, the intended fail-open, until you classify *that* path too. The projector itself **fails closed on the frame**: if the named frame isn't live, the whole value redacts to a `:rf/redacted` sentinel rather than ship raw under no registry. Crucially, **redaction never loses structure** — a redacted param is still an edge. Node ids, the edge topology, the storage/evaluation/lifecycle classifications, and the source forms all ride through untouched; only the value leaves are elided. You keep the shape of the graph; you just don't leak its data.

## Read-only, like every Xray tab

Drawing the graph dispatches nothing, pins nothing, and mutates no host state. It is a projection over registration facts and the observed frame's state — the same read-only contract as the rest of the tool.
