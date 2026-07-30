# EP-0033: re-frame.ui View Evidence and Debugging

Status: accepted
Type: standards-track
Created: 2026-07-16
Resolution: accepted 2026-07-11

> **Historical record — 2026-07-30.** This EP's programme
> ([EP-0030](EP-0030-the-compiled-view-substrate-program.md)) was absorbed into
> Freehand, and Freehand
> ([EP-0036](EP-0036-the-freehand-view-substrate-programme.md)) is now
> **withdrawn**; the EP is kept as the record of the donor programme and no work
> is in flight under it.

## Abstract

Dev builds of the compiled view substrate are a glass cockpit: per-view compiler
manifests describe every slot, site, and capability before mount; per-commit
instance records carry a `:rf.view/causes` vector, occurrence-level identity,
and loss-accounted histories. Attribution is **emitted at the cause site, never
reconstructed** — no Fiber walking, no DOM scraping, no cloneElement tagging.
One versioned evidence schema is shared by Xray, Story, Pair, compiler
diagnostics, and tests; production builds contain none of it, provably.

The primary normative home is [`spec/004D-Freehand-Compiled-Grammar.md`](../../spec/004D-Freehand-Compiled-Grammar.md)
§View identity and the instrumentation surface; per EP-0009, where this EP and
the spec differ, **the spec governs**. Every runtime-tier evidence schema, trace
op, and error/warning id this design names gets a catalogue row in
[`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) — the
one-catalogue rule, scoped to the runtime tier: compile-tier `:rf.ui.compile/*`
ids live in the maintained compiler roster and deliberately get no Spec 009
rows.

## Motivation

Legacy adapters reconstruct render attribution after the fact (post-render
walks, cloneElement tagging) — imprecise under concurrent rendering,
identity-conflating, and handing each tool a subtly different truth. The
compiler already knows every slot, site, and interop boundary; the runtime knows
which committed commit each observation belongs to. The unresolved alternatives
— where evidence is emitted, which identities exist, how tools consume them,
what production keeps — are contract decisions warranting a durable record, not
a bead.

## Specification

**Scope.** This EP fixes one versioned evidence schema consumed by Xray, Story,
Pair, diagnostics, and tests; evidence usable *before mount* (the manifest) and
truthful *after commit* (the instance record — speculative renders publish
nothing); honest bounds everywhere (loss accounting, `:owned? false` overrides,
qualified retroactive lifecycle annotations); and production erasure as a proof
(G-7/G-11). It does not add new Xray panels (mounted-views, SSR/roots, and
heatmap panels come only after an information-architecture review — the v1 emit
obligation is the schema, not panels); no render flamegraphs or per-view
timeline scrubbers (React Performance Tracks correlate via render-key/epoch
ids); no second runtime model for Story/Pair, no remote mutation API, no
general query engine over evidence; and no production telemetry — the always-on
error channel is EP-0008's.

### Shipped surface and specified extensions

The evidence tier shipped with the S3 conformance slice, and the certified
surface (`spec/conformance/S3-view-conformance-profile.md`) is a bounded subset
of the schema this EP specifies.

**Shipped:** the five `re-frame.ui.tool` projections; occurrence-shaped
instance records (`:occurrence` ordinal + `:view-id` + `:root-id` +
`:connection` + `:lifecycle` intervals + bounded render evidence) with explicit
loss accounting; and a bounded render-cause **set** — `#{:subscription :hmr
:disposed}`. Host-state writers (`local`) are proven through G-15's writer
composition and render count, not a `:local-state` cause row — that render
cause is specified for S6, not shipped at S3.

**Shipped at S6 (rf2-vxgfnd.98.1 — see §S6 view-evidence delta below):** the
integer `:render-key` and `:generation` record fields, `:root-id`, per-observation
`:observations`, and the per-commit `:rf.view/causes` **vector** with its shipped
six-kind roster (`:mount`, `:subscription` with version from→to, `:story-override`,
`:local-state`, `:hmr`, `:disposed`) plus the `:foreign-or-react` honesty fallback;
the `data-rf2-source-coord` + render-key DOM annotation on compiler-owned host
roots; and the Spec 009 evidence-schema rows.

**Deliberately deferred with named triggers (never a silent drop):**
`:parent-render-key`, the singular keyword `:frame-id` (frame attribution is
per-observation instead), and the `:prop` / `:frame`-`:context` / `:resource` /
`:hydration-correction` / `:reconnect-correction` / `:epoch-restore` / `:hmr-remount`
cause kinds. Consumers still tolerate those absences explicitly rather than
fabricating them (`tools/xray/spec/021` §3.4.1: no `:epoch-restore` / `:hmr-remount`
evidence lands, and Xray invents neither). The dated delta below records exactly
what shipped and enumerates each deferral's trigger; the Spec 004 §View identity
amendment is its normative home. The schema originally sketched here is the ruled
design intent.

### S6 view-evidence delta — shipped (2026-07-21, rf2-vxgfnd.98.1)

The §Two evidence layers / §Render causes design below is the ruled intent; this
dated delta records what the S6 slice (rf2-vxgfnd.98.1, slices a–d) ACTUALLY
shipped and where it deliberately trims or defers under the delta protocol ("trim
deliberately, never silently drop"). It records the shipped surface; the parallel
`spec/004-Views.md` §View identity amendment is the normative home.

**Record shape (Ruling 1 — Option 1A, honest capture).** The DEBUG-only per-commit
committed-instance record, minted at each connected commit and read via
`re-frame.ui.reactive/commit-record`:

```clojure
{:render-key 1042            ; module-global monotonic integer, minted fresh per connected commit
 :view-id :cart/row          ; the cell's authoring identity
 :generation 3               ; the committed view-body generation
 :root-id :page/shop         ; owning root incarnation (nil under no root — Tier-1/JVM)
 :connection :connected      ; the certified three-state model, at a connected commit
 :observations [{:kind :subscription :query [:cart/item 17]
                 :target-id 88 :version 12 :owned? true :frame-id :shop}]
 :rf.view/causes [{:cause :subscription :target 88 :query [:cart/item 17]
                   :frame-id :shop :from 11 :to 12 :epoch 4}]}
```

- **`:parent-render-key` is DEFERRED out of the record** — trigger: "an Xray IA
  review designs a hierarchy-consuming surface". No parent-capture machinery is
  built in S6 (no `tools/xray/spec` consumer reads view parentage; capture would be
  decoration).
- **Frame attribution is PER-OBSERVATION, not a singular `:frame-id`.** Each
  observation carries its own target's `:frame-id`; a cell observes a SET of frames
  (`cell-frames` is a set by design — an override-only cell observes none), so the
  record carries NO singular top-level `:frame-id`. This amends the singular
  `:frame-id` sketched in §Two evidence layers.
- Speculative renders publish nothing (I-1/I-2); production mints no record and no
  `:render-key` — the whole plane is elided (G-7/G-11).

**Causes roster (Ruling 2, as reworked — a deliberate 6-ships / 7-defers trim).**
The per-commit `:rf.view/causes` **vector** ships six kinds plus the honesty
fallback, each projected from an existing signal at its cause site (never a new
capture machine, never a general evidence framework):

- `:mount` — the first connected commit (`:fresh`→`:connected`); a bare `{:cause :mount}`.
- `:subscription` — a value-movement port note carrying its ruled detail
  `{:target :query :frame-id :from :to :epoch}` (coalesced: earliest `:from`, latest
  `:to`). The port note itself carries the `:subscription` keyword (unified
  port-wide from the original `:value` — rf2-ao46i), so the record adopts it
  unchanged.
- `:story-override` — a (re)acquired static Story-override target, carrying
  `{:override-id :version}`.
- `:local-state` — the substrate-owned local writer bridge (gated on an `Object.is`
  committed change); a bare marker.
- `:hmr` — a real registrar-replacement fan-out; a bare marker.
- `:disposed` — a `:disposed` port note; a bare marker. **`:disposed` is a ROSTER
  ADDITION** — it ships today from the port but was absent from the specified
  roster; recorded here so the trim protocol does not orphan a real cause.
- `:foreign-or-react` — the honesty fallback for a commit whose pending evidence
  window is empty.

**Deferred, as deferred-with-triggers rows** (consumers keep tolerating absence
exactly as `tools/xray/spec/021` §3.4.1 does; nothing is emitted for these):

- `:prop` — trigger: an IA review designs a per-commit cause-detail surface.
- `:frame` / `:context` — trigger: the substrate gains context-driven re-render
  visibility (until then a context re-render falls into `:foreign-or-react`).
- `:resource` — trigger: a resource-attribution question from dogfooding.
- `:hydration-correction` — trigger: EP-0034's hydration probes land.
- `:reconnect-correction` — trigger: hidden-reveal repaints confuse in dogfooding
  (until then `:subscription` stays honest for a value that moved while hidden).
- `:epoch-restore` — trigger: an Xray or Pair debugging surface is designed that
  needs restore-driven commits distinguishable from ordinary `:subscription`
  movement, naming the consumer that will read the restore attribution (restore
  token + target epoch). Ruled option (c): restore provenance is outside
  `perform-restore!`'s dynamic extent and headless hosts have no value-movement
  watch, so honest emission needs a cross-artefact restore-provenance channel no
  consumer reads; until then a restore-driven movement surfaces honestly as
  `:subscription` (watchable hosts) or `:foreign-or-react` (headless).
- `:hmr-remount` — trigger: a substrate remount-pairing channel tying a mount to its
  predecessor's teardown. Deferred because a remounted instance is
  React-indistinguishable from a fresh mount at the same view-global generation
  using only the view-global remount counter; honest per-instance attribution needs
  a teardown→remount pairing signal (cross-surface + a fiddly closing rule).

**Schema version + vocabulary.** The record + roster ship under
`re-frame.ui.tool/schema-version` **3** (bumped from 2 in this same change; Xray,
Story, and Pair move in lockstep so none degrades to `[]`). The `:value`→
`:subscription` rename first landed on the per-commit record surface; the port-wide
unification (rf2-ao46i, RULING 2) then carried it to the observation **port keyword**
and Xray's **cumulative** `explain-render` window, so the whole substrate now speaks
the one `#{:subscription :hmr :disposed}` vocabulary — no two-vocabulary residue.

### Two evidence layers

**Compiler manifest — what *can* happen.** Per view, dev-only: source coords,
prop slots + schema, template fingerprint, hook signature, capability bits, and
every site — subs with query shapes; events with event shapes and a
`:serializable?`/`:dynamic` flag; effects; presence sites;
trusted-markup `ui/html` sites; render-slot sites (P0-3) — each with source +
template path. No runtime values; useful before mount, to Xray, Story, editors,
and agents. Per the readiness package (EP-0035, P1-5), the dev/test projection
of this manifest is a **versioned public shape** additionally carrying per-prop
docs/defaults and declared slot metadata — one source of truth replacing
parallel args-description systems (re-com maintains ~57 such Vars).

**Committed instance record — what *did* happen.** Published only at connected
commit; speculative renders publish nothing. Shape (versioned):

```clojure
{:render-key 1042  :parent-render-key 1039
 :root-id :page/shop  :frame-id :shop  :view-id :cart/row
 :generation 3  :connection :connected
 :observations [{:kind :subscription :query [:cart/item 17]
                 :target-id 88 :version 12 :owned? true}]
 :rf.view/causes [{:kind :subscription :target-id 88 :from 11 :to 12}]}
```

`parent-render-key` gives direct hierarchy — no Fiber or DOM walking (legacy
adapters keep their fallback; this substrate never needs it). Keyed repetitions
inside one instance carry **occurrence-path** (e.g. `[{:site 17 :key
order-id}]`) on DOM annotations, event provenance, and presence records —
render-key alone is insufficient. Story overrides display honestly: `{:kind
:story-override … :owned? false}` — a visual override, never evidence a
subscription computed it.

**Connection is recorded as observed:** the runtime emits exactly three states
— `:connected` / `:disconnected` / `:dead` — and the immediate cleanup fact is
`:disconnected {:reason :unknown}` (public React cannot distinguish an Activity
hide from an unmount at cleanup). `:activity-hidden` and `:unmounted` are
qualified **retroactive annotations** of the *prior interval*, never runtime
states: a reconnect proves a hide; host/root teardown proves unmount; GC-based
inference (if enabled) is best-effort/eventual — no timestamp, a bounded
tombstone that never retains the cell. Records distinguish runtime state vs
tool label vs inference; transitions never fabricate renders.

### Five identities, never conflated

**root-id** (Spec 011), **frame-id** (Spec 002), **render-key** (one committed
view instance — Spec 004), **occurrence-path** (a keyed repetition inside one
instance — Spec 004), **observation-target** (Spec 006). Sites get compile-time
indexes + source anchors; identity under HMR is source anchor + structural path
+ generation, released/remounted on ambiguity.

### Render causes

**`:rf.view/causes` is a vector** — a commit can have several causes; headers
summarize ("3 dependencies changed in `::refresh-complete`"). The roster:
`:mount` · `:subscription` (target, query, version from→to, epoch, upstream
event/sub) · `:story-override` · `:prop` (**changed top-level slots** — the
sound cheap promise; nested paths are a dev diff view on demand, never a
default emit) · `:local-state` · `:frame` / `:context` · `:resource` · `:hmr` /
`:hmr-remount` · `:hydration-correction` · `:reconnect-correction` ·
`:epoch-restore` (**the restore operation token + target epoch** — the repaint
is caused by the restore *operation*; old epoch records are never rewritten or
back-filled) · `:foreign-or-react` (the honest fallback — never fabricate
precision).

**Loss accounting:** every bounded buffer reports `total` / `retained` /
`dropped`; counts are labeled exact only when `dropped` = 0 — no silent
truncation presenting as completeness.

### The static interaction surface

Handlers are data: the inspector shows any element's event vector before it is
clicked, plus the registered handler's source/schema; unregistered ids warn at
render. Sites classified `:dynamic` say so — the static surface covers literal
and normalized-branch sites and is honest about the rest. Dev warning families
(unregistered ids, render-phase dispatch, cross-frame carried ops, suppressible
accessibility checks) land as catalogue rows on the issue surfaces.

### Source ↔ DOM ↔ cause navigation

Compile-time `data-rf2-source-coord` + render-key (+ occurrence-path)
annotation on compiler-owned host roots — today's attribute vocabulary, so
existing Xray click-to-source works day one. Chains both directions: app-db
path → subs → views → elements, and element → event → handler → effects → state
diff → sub cascade → commits. React DevTools stays independent; Performance
Tracks correlate via render-key/epoch ids. Xray answers "why does this view
carry a client runtime?" from capability bits + sites — the absence story made
inspectable.

### Tool integration posture

- **Xray — enrich existing surfaces first.** The Views tab gains the causes
  vector, occurrence identity, and the three-layer lifecycle grammar; dispatch
  surfaces gain event-site provenance; `:epoch-restore` renders where causes
  render; warning families ride the issue surfaces. New panels come only after
  an IA review shows an existing surface can't answer the question. Every trace
  shape is a Spec 009 catalogue row first; every Xray change updates
  `tools/xray/spec/*` in the same change.
- **Story:** mounts scenes by **view id**; asserts on JVM structural trees +
  app-db; sub-overrides via the observation-target protocol with `:owned?
  false` honesty; JVM override injection is the explicit `ui.test/render`
  option — one mechanism per host, both named.
- **Pair:** hot-swap rides the existing nREPL HMR path; the five frozen
  read-only projections (`view-manifest`, `mounted-views`, `explain-render`,
  `view-dependencies`, `view-event-sites`) live in `re-frame.ui.tool` — the
  tool tier, never the authoring namespace.
- **Epochs:** restore causes carry the operation token — the timeline stays
  truthful through time travel; history is never rewritten.

### Privacy and production erasure

Manifests carry shapes and source, never live values; render values classify
through their owning sub/schema; sensitive/large values redact per EP-0025
before off-box egress; histories are bounded with loss accounting; paths are
project-relative. Occurrence keys, override values, and event payloads route
through the one egress-projection policy (EP-0025). Sub query vectors
deliberately do **not** route through the classification egress chokepoint:
they are identity — the sub-cache key, the skip-dedup key, reactive-graph edge
endpoints — and egress raw on every query-vector-bearing slot as an accepted,
documented fail-open (the positional-event-args precedent); only the Spec 010
schema-axis backstop whole-slot scrubs them, for `:sensitive?`-schema'd subs.
**Erasure is a proof:** compile-time defines + the G-7/G-11 bundle-scan gates
put manifests, cause vectors, histories, warning text, and `data-rf2-*` strings
on the scanned absence roster; the always-on Spec 009 error contracts (EP-0008)
remain.

### Guide impact

The substrate guide's debugging chapter teaches the glass cockpit; `docs/core`
inherits it at promotion. No other human-facing guide change yet.

## Rationale

Emit-at-cause-site over reconstruction is the load-bearing choice:
reconstruction made legacy attribution fragile and silently degrades under
concurrent rendering. Two layers keep the manifest useful before mount and the
record truthful after it; the five-identity split prevents the conflations that
made "which one re-rendered?" unanswerable. The honesty rules (`:owned?
false`, bounded `:prop` precision, `:disconnected {:reason :unknown}`, loss
accounting) trade apparent precision for provable truth — a tool that
fabricates certainty is worse than one that says "unknown". Enrich-first keeps
Xray's hardest-won asset — its information architecture — from dilution by
panels bought before demand.

## Backwards Compatibility

Pre-alpha; no shims. The legacy `[view-id instance-token]` `:render-key` wire
shape and the cloneElement/post-render attribution path remain emitted by the
retained adapters (`[TRANSITION]` in Spec 004); the compiled substrate emits
its own versioned schema from day one.

## Resolved Decisions

- **Attribution at the cause site — ruled.** Evidence is emitted where the
  cause happens, never reconstructed from React internals.
- **`:prop` cause precision — ruled.** Changed top-level slots only;
  nested-path deep diff is an on-demand dev view, never a default emit.
- **Connection recorded as observed (name unified 2026-07-12).** Three runtime
  states; hide/unmount are qualified retroactive annotations with named
  proofs; GC inference explicitly approximate.
- **P1-5 docs/slot projection (2026-07-16, EP-0035).** The dev/test projection
  is a versioned public shape with per-prop docs/defaults and slot metadata;
  render-slot sites join the site roster.
- **One-catalogue scope qualified (2026-07-19).** The one-catalogue rule
  covers runtime-tier ids only, matching Spec 004 §One catalogue (runtime
  tier); compile-tier `:rf.ui.compile/*` ids live in the maintained compiler
  roster and deliberately get no Spec 009 rows.
- **Sub query vectors egress raw (2026-07-19, delegated).** Query vectors are
  identity, not payload, and do not route through the EP-0025 egress
  chokepoint; the raw egress on query-vector-bearing slots is an accepted,
  documented fail-open, backstopped only by the Spec 010 schema axis for
  `:sensitive?`-schema'd subs.

## Open Issues

1. **Shipped (rf2-vxgfnd.98.1, 2026-07-21).** The evidence-schema delta
   (§Shipped surface and specified extensions) shipped at **S6** — the per-commit
   committed-instance record, the six-kind `:rf.view/causes` vector, the DOM
   annotation, and the Spec 009 rows — under `re-frame.ui.tool/schema-version` 3;
   see §S6 view-evidence delta for the shipped shape and the deferred-with-triggers
   rows. `:parent-render-key`, the singular `:frame-id`, and five further cause
   kinds are deferred with named triggers, not dropped.
2. Deferred with a named trigger: a Static-mode mounted-views/manifest-browse
   surface goes to the post-S3 IA review, triggered only if dogfooding shows
   the Views-tab + `mounted-views` assembly failing.

Graduation: the S6 evidence schema now asserts (rf2-vxgfnd.98.1) and the G-7/G-11
erasure gates hold; this EP moves to `final` once the deferred-with-triggers rows
recorded in §S6 view-evidence delta are either triggered-and-shipped or confirmed
errata.

## References

- Primary normative home: [`spec/004D-Freehand-Compiled-Grammar.md`](../../spec/004D-Freehand-Compiled-Grammar.md) §View
  identity and the instrumentation surface;
  [`spec/009-Instrumentation.md`](../../spec/009-Instrumentation.md) (the
  runtime-tier catalogue); [`spec/011-SSR.md`](../../spec/011-SSR.md) (root-id
  lives with its root manifests); `spec/conformance/S3-view-conformance-profile.md`
  (the certified S3 surface); `tools/xray/spec/021` (consumer tolerance of the
  unshipped delta).
- [EP-0030](EP-0030-the-compiled-view-substrate-program.md) — the umbrella:
  stages, demand bar, and the blessed API surface this evidence tier
  instruments.
- [EP-0031](EP-0031-re-frame-ui-programming-model.md) — the compiler,
  `defview`, templates, and handler law whose sites the manifest indexes.
- [EP-0032](EP-0032-re-frame-ui-reactivity-and-ownership.md) — observation
  targets and the committed push-ownership protocol; connected commit is the
  publication boundary for instance records, and the three-state connection
  lifecycle recorded here is that EP's observed-lifecycle contract.
- [EP-0034](EP-0034-re-frame-ui-production-ssr-testing.md) — owns the SSR/roots
  story; the `:hydration-correction` cause names its server/client/probe
  disagreements; its G-7/G-11 gates prove this tier's production erasure.
- [EP-0035](EP-0035-component-library-readiness.md) — the readiness amendment
  package (render-slot manifest sites, the public docs/slot projection).
- [EP-0008](EP-0008-production-observability-channels.md) — after erasure,
  production keeps exactly the always-on error channel; this evidence is the
  dev-only tier. [EP-0025](EP-0025-data-classification.md) — the
  egress-projection policy the record slots route through.
- Design provenance: the debugging study in the tombstoned synthesis tree
  (`ai/findings/new-substrate-synthesis/`); the S3 Xray consumption IA lands
  with W7a under `tools/xray/spec/`.
