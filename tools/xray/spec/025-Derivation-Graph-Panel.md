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
  nav-token owner. The live graph additionally resolves the **realized
  route-owned resource edge** (rf2-k0meap.1): a route-owned resource entry
  (whose lifecycle owner is `[:route route-id nav-token]`) draws a `:param`
  edge from the live route node to the **concrete** `[:resource <scoped-key>]`
  node — the live resolution of the static graph's `:parametric` route-resource
  marker. Empty for a missing/destroyed frame or a production build (the live
  projections are dev-gated; their bodies DCE).

Across **both** modes, a **machine-selector** subscription node is enriched
with `:refinement :machine-selector` (rf2-k0meap.2) — it stays an ordinary
`:derivation` superkind (the refinement is the colour axis, never a third
superkind), and is the `:to` end of the `:selector` edge from the specific
machine it reads.

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

## The contributor seam — all five families

The composer reaches each optional family through a `{family contributor}`
map. On CLJS the **consuming tool supplies it** from the tooling siblings it
statically `:require`s
([`spec/Derivations.md`](../../../spec/Derivations.md) §The graph-assembly
composer — "the optional siblings it has"). As EP-0014's **named first
consumer**, Xray hard-deps **all five** algebra-view families so the single
graph it renders is complete (rf2-1fc459): core (→ `re-frame.subs.tooling`,
always present), routing (→ `re-frame.routing.tooling`), flows
(→ `re-frame.flows.tooling`), resources (→ `re-frame.resources.tooling`),
and machines (→ `re-frame.machines.tooling`). So
`day8.re-frame2-xray.panels.derivation-graph/xray-contributors` carries
**all five families**; a family with no registrations in the host app
simply contributes no nodes — the no-machines / no-resources story now
holds **per-app** (an app that never registered a machine), not per-tool
(Xray missing the artefact). The panel's silent state names this when
nothing at all is registered.

> **Why hard deps, not the decoupled-slice posture.** The Resources panel
> ([024](024-Resources-Panel.md)) and the Machine Inspector
> ([003](003-Machine-Inspector.md)) read those families' **runtime-db
> slices** decoupled (never `:require`ing the artefact). The
> Derivation-Graph tab is a **separate surface**: it needs the
> **algebra-view projection** (`resource-algebra-view` /
> `machine-algebra-view` + their live counterparts and the
> `machine-selector-targets` extractor), which lives in the resources /
> machines **tooling siblings**, so it `:require`s them. The machines /
> resources artefacts depend only on core; their tooling-sibling bodies are
> dev-gated (`interop/debug-enabled?`) + bundle-isolated, and Xray itself is
> dev-only (`:devtools/preloads`) — so none of this reaches a production
> bundle (the bundle-isolation gate verifies it).

### The `:machines` contributor's selector-target extractor

The `:machines` contributor carries `machine-selector-targets` (not the
boolean `machine-selector?` recognizer) in its `:selector-targets` slot. The
composer mines each selector subscription's **target machine ids** from its
static `[:rf/machine machine-id …]` / `[:rf/machine-has-tag? machine-id …]`
inputs and draws the `:selector` edge from **exactly** the
`[:machine target-id]` node(s) the selector reads — never the cross product
of every machine against every selector (rf2-4qmiij). In a multi-machine app
an unrelated machine receives **no** spurious selector edge.

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
redacted param/value is still an edge): the node is still present and still
classified by its superkind; the storage / evaluation / lifecycle
classifications, `:source-form`, and `:refinement` are **structure, not
values**, and are never walked. The value-bearing leaf fields inside node
bodies are redacted by the policy walk above.

#### Live resource IDENTITY redaction (rf2-k0meap.1)

The value-path walk above matches a frame's declared `:sensitive` `:app-db`
**paths** — but a live **resource** node carries its sensitive scope/params
in its **identity**, a place that walk can never reach: the concrete scoped
key `[cache-scope resource-id canonical-params]` is the node **key**
(`[:resource <scoped-key>]`), the node `:id`, and is embedded in the
`:output` runtime path, the realized `:inputs` `[:scope …]` / `[:param …]`,
and the in-flight `:work-ledger :record :resource/key`; a route-owned
activation names it on a `:param` edge endpoint. So `redact-graph-for-egress`
**also** projects each scoped key's secret-bearing **scope** and **params**
into **stable opaque handles** — minted from the core CEDN-1 identity
primitive (`identity/canonical-bytes`) then **hashed one-way** so the same
key maps to the same handle (connectivity survives) but the raw scope/params
can never be read back off the wire (fail-closed to `:rf/redacted` for any
value outside the CEDN-1 domain). The non-sensitive registration
`resource-id` is **preserved** so a tool still sees *which* resource the node
is. The **same** projection is applied consistently to the `:nodes` keys, the
identity positions inside node bodies (`:id` / `:output` / `:inputs` /
`:work-ledger`), **and** every `:edges` endpoint that names a resource node —
a redacted resource node is still a node, and the edges naming it still
connect. Structure survives; the identity-embedded secret does not egress.

The projection is **idempotent** (rf2-g197ep): a value may egress more than
once (re-egress on re-render / re-subscribe / a forwarder cascade that ships
an already-projected graph onward), so `redact-graph-for-egress` ∘
`redact-graph-for-egress` **must equal** `redact-graph-for-egress` — a second
pass changes nothing. This holds at the source: the opaque-handle minter
returns an already-`[:rf.resource/opaque …]` handle (and the `:rf/redacted`
sentinel) **unchanged** instead of re-hashing it into a fresh, different
handle. Without that guard the realized `:inputs` `[:scope …]` / `[:param …]`
payloads — the one position projected unconditionally rather than gated by the
scoped-key shape test — would be re-hashed on every pass, silently changing
the live node's input-edge identity across the boundary even though the node
key and `:id` (gated by the 3-tuple-with-map-tail shape) stayed stable.

## `:rf.xray/*` registry surface

All under the `:rf.xray/*` isolation prefix (the collision contract); all
read-only (assembling the graph dispatches nothing and pins nothing):

| Surface | Kind | Role |
|---|---|---|
| `:rf.xray/derivation-graph-mode` | sub | `:static` \| `:live` toggle (default `:static`) |
| `:rf.xray/set-derivation-graph-mode` | event | set the mode toggle |
| `:rf.xray/derivation-graph` | sub | the assembled `DerivationGraph` (static / live, over `xray-contributors`) — production registration reads the live source directly, no override branch |
| `:rf.xray/derivation-graph-tab-data` | sub | the view-facing composite (on-box summaries + family grouping + summary header) |

The test-only graph override (`:rf.xray/derivation-graph-override` sub +
`:rf.xray/set-derivation-graph-override-for-test` event) is **NOT**
installed by `register-xray-handlers!` (rf2-e8330v / xxo3zz F3). It lives
behind `derivation-graph/install-test-overrides!` (orchestrated by
`test-support/install-test-overrides!`), which a test opts into AFTER
production registration; the seam also re-registers `:rf.xray/derivation-
graph` with the override input layered on top.

## Read-only

Same contract as every Xray panel — observing the graph pins nothing,
dispatches nothing, mutates no host state. Pure hiccup; the projection
algebra + the egress redaction call site live in
`derivation_graph_helpers.cljc` (JVM-portable). Frame isolation comes from
the enclosing `[rf/frame-provider-existing {:frame :rf/xray}]` in `shell.cljs`.

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
  raw); frameless fail-closed. Plus the **adversarial** live resource
  identity arm (rf2-k0meap.1): a live resource scoped key carrying a session
  token in BOTH its cache scope and its canonical params, with an in-flight
  work-ledger record and a route-owned `:param` edge naming it — asserting
  NO raw secret survives anywhere in the egressed graph (node key, `:id`,
  `:inputs`, `:output`, work-ledger, edges) while connectivity survives (the
  node is still classified, the edge still connects to the projected key),
  the projection is deterministic across all identity positions, and it is
  **idempotent** — `project(project(x)) == project(x)`, asserted as full graph
  equality across two (and three) passes plus per-position witnesses (node
  keys, `:id`, `:inputs`, `:output`, work-ledger `:resource/key`, edges), so a
  forwarder pipeline that re-egresses an already-projected graph cannot drift
  the realized `:inputs` handles (rf2-g197ep).
- **`derivation_graph_consumer_cljs_test.cljs`** (node) — the **behavioral
  consumer** test (rf2-4wtllq): registers the panel handlers via
  `registry/register-xray-handlers!` + `test-support/install-test-overrides!`
  (the override seam, rf2-e8330v), then
  asserts the `:rf.xray/derivation-graph` subscription actually calls the
  shared composer path — static mode returns a graph the composer produced
  (`:mode :static`, real node/edge counts, by-family grouping, edge roles
  through `:rf.xray/derivation-graph-tab-data`); switching to `:live` +
  setting `:rf.xray/target-frame` makes it call `live-derivation-graph` with
  the observed `:frame`; the test override (installed by the seam) still
  bypasses the composer; and a
  node carrying the reserved EP-0013 relocation coordinates (`:realm/id`,
  `:app/id`, `:module/id`) survives tab-data summarization unchanged. This
  closes the seam the helper/registry/redaction tests leave: that Xray's
  ACTUAL subscriptions consume `re-frame.derivation.graph` in both modes
  across all five families. Plus the **live-content** arm (rf2-k0meap.3): a
  navigation materializes a route slice in the TARGET frame; live mode then
  surfaces that realized route node through the consumer path (graph +
  tab-data carry the live node + its `:routes` family grouping, NOT an empty
  graph), and pointing Xray at a DIFFERENT frame proves target-frame
  isolation (the first frame's slice does not leak).
- The composer assembly mechanics + the cross-family classification
  conformance are pinned by `re-frame.derivation-graph-test` (JVM) and the
  derivation-algebra conformance test + the host-agnostic
  `derivation-graph-algebra.edn` corpus fixture (the `:derivation-graph` call
  op, rf2-k0meap.3) — including the **precise machine→selector edge
  targeting** (rf2-4qmiij: two machines, one selector reading only one of
  them, asserting no edge from the unrelated machine), the **frame-scoped
  flow node id** (`[:flow <frame-id> <flow-id>]` — a reused flow-id across two
  frames stays distinct, rf2-k0meap.2), the **`:machine-selector` refinement**
  enrichment (rf2-k0meap.2), and the **realized route-owned resource edge**
  (rf2-k0meap.1).
