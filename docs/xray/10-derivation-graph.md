# 10. Derivation graph

The other Xray tabs answer "what just happened?" The derivation graph answers a quieter, structural question: **"where does this value come from?"** A page reads a dozen subscriptions, a couple of resources, a route param, and a machine selector, and you cannot see how they relate. Which fact feeds which? Is this value durable app-db state, a server-owned cache entry, or an ephemeral reaction? When does it re-evaluate? Which owner keeps it alive?

The Graph tab draws all of that as one picture. Every subscription, flow, resource, route fact, and machine selector is a node in a single dependency graph rooted at your state: the graph [One graph: derivations and algebra views](../core/derivations-and-algebra-views.md) describes, drawn for your running app. [The five questions every node answers](../core/derivations-and-algebra-views.md#the-five-questions-every-node-answers) in that guide explains the classifications each row below carries.

Open **Graph** from the Dynamic tab strip. It does not follow the event you pick. Its header, **Derivation / process graph**, carries a **static** / **live** toggle, which starts on static, and a count of what the graph holds; the node rows follow, grouped by family.

## The two superkinds

Every node is one of two kinds:

- A **derivation** (○) is a pure computation over inputs — it stores nothing of its own and recomputes on demand. A subscription is the archetype.
- A **process** (◆) is stateful — it has a snapshot or a cache entry that advances over time. A resource entry, a route fact, and a machine are processes.

The panel marks every node with its superkind, and the header counts both. Finer labels — `resource-process`, `route-fact`, `machine-process`, `machine-selector` — ride a separate *refinement* axis, shown as muted text after the node's id; they never change the basic derivation-vs-process read. A node whose family Xray doesn't recognise still classifies and renders by its superkind rather than breaking the view.

## The five families

Nodes are grouped into five family sections, in editorial order:

- **Subscriptions** — derivations over app-db and over other subscriptions.
- **Flows** — derivations that *materialise* into app-db at a declared path.
- **Resources** — processes whose authority is a remote server; their local storage is a runtime-db cache entry.
- **Routes** — the match fact, params, and transition state of the active route.
- **Machines** — process snapshots plus the selector subscriptions layered over them.

A family with no nodes has no section at all, so an app that never registered a machine shows no machine section. When every family is empty, the panel says so in words rather than implying the tool is broken.

## What each node row tells you

Beyond its id and family, each row carries the classification axes that make the graph readable without opening source, each printed as a short label:

- **`store`** — *where the value lives locally*: `:ephemeral` (a cached value derived from frame state, such as a subscription's), `:app-db` (your app-db, where a flow writes its output), `:runtime-db` (the framework's own frame state, such as a resource's cache entry or a machine snapshot), or `:host-transient` (host handles, such as an in-flight request). This is the axis that tells a flow apart from a plain subscription apart from a resource entry.
- **`eval`** — *when it recomputes*: `:on-demand`, `:after-event`, `:on-reply`, `:on-route`, `:on-transition`, `:scheduled` or `:manual`, or a set of them for a process with more than one trigger.
- **`owner`** — *who keeps it alive*, as a lifecycle kind: `:subscription-cache-entry`, `:frame`, `:route`, `:scoped-resource-key`, `:machine-instance` or `:host-root`. When the owner releases, the node's value can be reclaimed.
- **`authority`** — present only on nodes whose source of truth lives *outside* the frame. A resource reads `authority :remote` alongside its local `:runtime-db` storage — the label answers "whose fact is this, really?" while storage still answers "where is the local copy?" Most nodes have no authority label; their value is local-authoritative.
- **`parametric`** — a parametric subscription (one taking a query argument) shows this marker. In static mode it contributes no edges, because its concrete inputs aren't known until it runs; its realized edges appear in live mode.

Below the family sections, the **edges** list the dependency records — `:input`, `:param`, and `:selector` edges, each as a `from → to` pair. A machine-selector edge points at exactly the machine(s) the selector reads, never the cross product of every selector against every machine.

## Static vs live

A toggle in the panel header, **static** and **live**, switches two modes:

- **Static** is the registration-derived graph: every registered fact and process plus the edges known from registration alone. It is process-global and frame-agnostic — the map of what *can* exist. A parametric subscription appears with its marker but contributes no static edges.
- **Live** is the graph realized in the *observed frame* at this moment: concrete subscription query vectors with their realized input edges, the active resource cache entries keyed by scoped key, live machine instances and spawned actors, and the materialized route slice with its nav-token owner. Live mode is dev-only — it is empty for a missing or destroyed frame, and its machinery is compiled out of production builds.

Use static when you want the structural map; use live when you want to see what your running frame actually built.

Under the toggle, the header counts what the current mode found: the mode, nodes, edges, how many nodes are derivations and how many processes, and the edges of each kind. An app with nothing to draw reads "No derivation/process nodes in the host app." A graph whose nodes have no edges between them, such as one made only of subscriptions over app-db and parametric subscriptions in static mode, says so under the edge list.

## Reading off-box is redacted

On your own box, in your own browser, the panel shows **raw** value summaries — that is the in-process truth, and read-only inspection of your own app is fine (the summaries are bounded only so a multi-megabyte value can't wreck the panel). The Graph tab shows the graph only there, in your browser; the rest of this section is about a tool that ships it off-box.

The boundary matters the moment a value-bearing graph leaves your machine — a capture streamed to a remote agent, serialized to disk, or posted to a service. At that egress the graph is projected through `project-egress` under the observed frame's **classification registry**, and value-bearing leaf fields (a node's `:value`, `:params`, `:query`, `:state`) are redacted **path-by-path**: the runtime substitutes `:rf/redacted` at each path the frame declared sensitive (and a size marker at each large path). Those declarations are the frame's own classification — durable `app-db` paths classified by the commit-plane `:sensitive` / `:large` effects (a `reg-event` returning them alongside `:db`), plus the `:sensitive` / `:large` paths a `reg-machine` or `reg-resource` declares for its own data. (Schemas describe *shape*, not durable-`app-db` egress policy — there is no schema-driven classification of an app-db path.) Projection is **path-based, never value-match**: a secret re-keyed onto an *unclassified* path is not chased by value — it ships raw, the intended fail-open, until you classify *that* path too. The projector itself **fails closed on the frame**: if the named frame isn't live, the whole value redacts to a `:rf/redacted` sentinel rather than ship raw under no registry. Crucially, **redaction never loses structure** — a redacted param is still an edge. A live resource node carries its scope and params in its id, so an off-box graph replaces them with one-way handles: the same key gets the same handle for the life of the runtime, so the edges naming that node still connect, but the raw scope and params never leave. Apart from those handles, node ids, the edge topology, the storage/evaluation/lifecycle classifications, and the source forms all ride through untouched; only the value leaves are elided. You keep the shape of the graph; you just don't leak its data.

## Read-only

Drawing the graph dispatches nothing, keeps nothing alive, and changes no app state. It reads registrations and the observed frame's state, like the rest of Xray.
