# 025-Derivation-Graph-Panel

The Xray surface for the **derivation/process algebra graph** — the
consumer-side panel that renders the single unified `{:mode :nodes :edges}`
`DerivationGraph` view across all five algebra-view families
([`spec/Derivations.md`](../../../spec/Derivations.md);
[EP-0014](../../../docs/EP/EP-0014-derivation-and-process-algebra.md)). The
"where does this fact come from, when is it evaluated, where does it live,
and who owns it?" lens — answered for subscriptions, flows, resources, route
facts, and machine processes + selectors **in one place**.

> **Owning framework spec.**
> [`spec/Derivations.md`](../../../spec/Derivations.md) is the normative
> source for the derivation/process algebra: the node shape, the two closed
> superkinds, the storage / evaluation / lifecycle classifications, the
> static-vs-live rule, the don't-execute rule, the graph-assembly composer
> (`re-frame.derivation.graph`), and the redaction-metadata contract. This
> doc is the Xray-side consumer contract: the panel sections, the
> `:rf.xray/*` registry surface, the contributor map Xray supplies, the
> on-box-raw / off-box-redacted posture, and the egress redaction call
> site. Where this doc and Derivations.md differ, Derivations.md governs the
> algebra; this doc governs Xray's presentation.

> **Xray is the EP's NAMED FIRST CONSUMER.** EP-0014 §Reference
> Implementation / Bead Plan item 7: "Add an internal graph-inspection
> helper for static and live graphs. Xray may consume it first." This panel
> is that consumption — the disposition-1 proof that the structured graph
> shape survives real use (rf2-9ett2d).

## Bug class

> **Bug class:** "This page reads a dozen subscriptions, a couple of
> resources, a route param, and a machine selector, and I cannot see how
> they relate. Which fact feeds which? Is this value durable app-db state, a
> server-owned cache entry, or an ephemeral reaction? When does it
> re-evaluate? Which owner keeps it alive? Is this node a pure derivation or
> a stateful process?"
> **Insight Xray provides:** the whole derivation/process graph as ONE
> view — every fact + process node classified by its closed superkind,
> tinted by family, carrying its storage / evaluation / lifecycle
> classifications and its declared input / param / selector edges; static
> (registration-derived) or live (realized in the observed frame); all
> read-only, all summarized for display.

## The one graph (the composer)

Every declared fact and process is a node over the frame fold. The five
algebra-view tooling siblings each project one family
([`spec/Derivations.md`](../../../spec/Derivations.md) — subscriptions /
flows / resources / routes / machines); the composer
`re-frame.derivation.graph` (EP-0014 slice-7) stitches those per-family
projections into the single `{:mode :nodes :edges}` view this panel renders.
The composer is a **projection over the five sibling projections** — it adds
no new fact identity and re-runs no source form (the registrar-derived
discipline). It ships **no public accessor** and **no `re-frame.core` facade
export** (the issue-1 disposition; the public name is deferred until a
consumer beyond the two named first consumers — Xray + the conformance
fixtures — needs it, the graduation gate).

## The two superkinds are the contract

The panel groups + classifies **every** node by its closed superkind —
`:derivation` | `:process` — read off `:kind` **alone** (EP-0014 §Algebra
Declaration Shape: a tool MUST classify every node knowing only the two
superkinds). The refined kinds (`:resource-process`, `:route-fact`,
`:machine-process`, `:machine-selector`) ride the separate `:refinement`
axis and are **colour, not contract** — they tint the row's family accent
but never gate classification. A node carrying an unknown FUTURE refinement
still renders + classifies off its superkind. A malformed node missing
`:kind` degrades to an inert `:unknown` row (never throws).

## Static vs live

Two modes, toggled in the panel header
([`spec/Derivations.md`](../../../spec/Derivations.md) §Static and live
graphs):

- **STATIC** — `re-frame.derivation.graph/derivation-graph`: the
  registration-derived graph. Every registered fact/process + its
  registration-known `:input` / `:param` / `:selector` edges. A parametric
  subscription shows the `:parametric` marker and contributes **no** static
  edge (the don't-execute rule — its realized edges appear only in the live
  graph). Process-global, frame-agnostic.
- **LIVE** — `re-frame.derivation.graph/live-derivation-graph`: the graph
  realized in the **observed** frame (`:rf.xray/target-frame`) at a point in
  time. Concrete subscription query vectors with realized `[:sub q]` input
  edges, active resource cache entries keyed by scoped key, live machine
  instances + spawned actors, and the materialized route slice with its
  nav-token owner. Empty for a missing/destroyed frame or a production build
  (the live projections are dev-gated; their bodies DCE).

## Panel sections (top → bottom)

```
│ HEADER  — mode toggle · N nodes · M edges · derivation/process tally · roles │
│ ─────────────────────────────────────────────────────────────────────────── │
│ per-family node sections (editorial order):                                   │
│   Subscriptions · Flows · Resources · Routes · Machines                       │
│     each node row: ○ derivation / ◆ process · id · refinement · storage ·     │
│     eval · owner · authority · parametric marker · value summaries            │
│ ─────────────────────────────────────────────────────────────────────────── │
│ EDGES  — :input / :param / :selector records (from → to)                      │
```

## The contributor seam — present families only

The composer reaches each optional family through a `{family contributor}`
map. On CLJS the **consuming tool supplies it** from the tooling siblings it
statically `:require`s
([`spec/Derivations.md`](../../../spec/Derivations.md) §The graph-assembly
composer — "the optional siblings it has"). Xray's artefact deps are core
(→ `re-frame.subs.tooling`, always present), routing
(→ `re-frame.routing.tooling`), and flows (→ `re-frame.flows.tooling`); it
does **not** `:require` the optional `re-frame.machines.*` /
`re-frame.resources.*` runtime artefacts — the same decoupling the Resources
panel ([024](024-Resources-Panel.md)) and the Machine Inspector
([003](003-Machine-Inspector.md)) honour (they read those families'
runtime-db slices decoupled, never `:require`ing the artefact). So
`day8.re-frame2-xray.panels.derivation-graph/xray-contributors` carries the
**three families Xray HAS**; the composer's present-family-only contract
renders machines + resources as soon as those siblings join the map — a
family Xray lacks simply contributes no nodes (the no-machines /
no-resources story). The panel's silent state names this when nothing is
registered.

## Privacy — ON-BOX raw, OFF-BOX redacted

This is the heart of the panel's egress contract, and it splits cleanly
along the box boundary (the EP-0014 tail-2 redaction ruling, rf2-6y7wnb):

### On-box rendering is RAW (Security.md permits on-box)

The panel renders in the developer's own browser, in the `:rf/xray` frame,
against the developer's own app. On-box inspection sees **raw** value
summaries — that is the in-process truth (raw-on-box is correct-as-designed
for read-only projections; the composer composes nodes verbatim by design
and does **no** redaction). `derivation-graph-helpers/summarize` produces
bounded, render-safe previews **for display ergonomics only** (a multi-MB
value would wreck the panel) — it is a size/shape projection, **not** an
egress boundary, and consults no elision policy.

### Off-box egress is REDACTED, per-frame, FAIL-CLOSED

The egress redaction **call site is born here** — the wire boundary where a
tool ships the graph **off** the developer's box (an MCP surface streaming
the graph to a remote agent, a serialized capture written to disk / posted
to a service). `derivation-graph-helpers/redact-graph-for-egress` is that
call site:

- each node's value-bearing summary field (`:value` / `:params` / `:query`
  / `:state`) is projected through the single shared `rf/elide-wire-value`
  walker ([`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
  §Privacy, [`spec/015-Data-Classification.md`](../../../spec/015-Data-Classification.md),
  [`spec/Managed-Effects.md`](../../../spec/Managed-Effects.md) §5);
- **per-frame** — under the OBSERVED frame's own declared `:sensitive` /
  `:large` `:app-db` elision policy (`re-frame.elision`, sourced from
  `reg-frame`), passed as the explicit `:frame` opt so the named frame's
  policy applies, never a borrowed or ambient one;
- **fail-closed** — when the named frame is **not** a live frame (nil id, a
  destroyed / never-registered frame), the projection walks frameless so
  `rf/elide-wire-value` takes its own fail-closed branch and redacts the
  whole value to the `:rf/redacted` sentinel rather than ship it raw under
  no policy. A sensitive-declared value → `:rf/redacted`; a large-declared
  value → the `:rf.size/large-elided` marker.

**Redaction MUST NOT lose graph structure** (the headline guarantee — a
redacted param is still an edge): the node **keys** (canonical family-tagged
ids) and the `:edges` vector ride through **untouched**; the node is still
present and still classified by its superkind; the storage / evaluation /
lifecycle classifications, `:source-form`, `:refinement`, `:inputs`
topology, and `:output` address are **structure, not values**, and are never
walked. Only the value-bearing leaf fields inside node bodies are redacted.

## `:rf.xray/*` registry surface

All under the `:rf.xray/*` isolation prefix (the collision contract); all
read-only (assembling the graph dispatches nothing and pins nothing):

| Surface | Kind | Role |
|---|---|---|
| `:rf.xray/derivation-graph-mode` | sub | `:static` \| `:live` toggle (default `:static`) |
| `:rf.xray/set-derivation-graph-mode` | event | set the mode toggle |
| `:rf.xray/derivation-graph-override` | sub | test-only graph override |
| `:rf.xray/set-derivation-graph-override-for-test` | event | set the override |
| `:rf.xray/derivation-graph` | sub | the assembled `DerivationGraph` (static / live, over `xray-contributors`) |
| `:rf.xray/derivation-graph-tab-data` | sub | the view-facing composite (on-box summaries + family grouping + summary header) |

## Read-only

Same contract as every Xray panel — observing the graph pins nothing,
dispatches nothing, mutates no host state. Pure hiccup; the projection
algebra + the egress redaction call site live in
`derivation_graph_helpers.cljc` (JVM-portable). Frame isolation comes from
the enclosing `[rf/frame-provider {:frame :rf/xray}]` in `shell.cljs`.

## Test coverage

- **`derivation_graph_helpers_cljs_test.cljc`** (JVM + node) — superkind
  classification by `:kind` alone; family grouping; edge role grouping +
  node degree; on-box `summarize` raw-permitting posture; `summarize-node`
  structure preservation; `graph-summary` tallies.
- **`derivation_graph_redaction_cljs_test.cljc`** (JVM + node, runtime
  fixture) — the **positive** off-box egress test (rf2-yjarv6): a sensitive
  node value run through `rf/elide-wire-value` is elided **while** the node
  + edge structure survives (the "redact value, keep edge" arm the EP-0014
  testing-coverage audit flagged as missing); large elision → marker keeping
  structure; per-frame policy (a non-classifying frame ships the same value
  raw); frameless fail-closed.
- The composer assembly mechanics + the cross-family classification
  conformance are pinned by `re-frame.derivation-graph-test` (JVM) and the
  derivation-algebra conformance fixture (the other named first consumer).
