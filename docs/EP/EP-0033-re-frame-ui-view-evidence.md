# EP-0033: re-frame.ui View Evidence and Debugging

Status: accepted
Type: standards-track

> The ruled design for `re-frame.ui`'s debugging evidence: two evidence layers
> (a compiler **manifest** for what *can* happen, a **committed instance record**
> for what *did* happen), attribution emitted at the cause site, five
> never-conflated identities, source ↔ DOM ↔ cause navigation, the enrich-first
> tool posture, and production erasure proven by bundle-scan gates. Normative
> home: `spec/004-Views.md` §View identity and the instrumentation surface;
> every evidence schema, trace op, and error/warning id lands as a
> `spec/009-Instrumentation.md` catalogue row (the one-catalogue rule, rf2-cs0kd1).
>
> **Partially graduated.** That Spec 004 section already carries the interim
> normative text (the R-1 rewrite); per EP-0009, where it and this EP differ,
> **the spec governs**. Implementation lands in the S3 wave (asserts **S3**,
> budget/absence gates complete **S6**) via beads rf2-vxgfnd.95.6/.95.7/.95.8.

## Abstract

Dev builds of the compiled view substrate are a glass cockpit: per-view compiler
manifests describe every slot, site, and capability before mount; per-commit
instance records carry a `:rf.view/causes` vector, occurrence-level identity, and
loss-accounted histories. Attribution is **emitted at the cause site, never
reconstructed** — no Fiber walking, no DOM scraping, no cloneElement tagging. One
versioned evidence schema is shared by Xray, Story, Pair, compiler diagnostics,
and tests; production builds contain none of it, provably.

## Motivation

Legacy adapters reconstruct render attribution after the fact (post-render walks,
cloneElement tagging) — imprecise under concurrent rendering, identity-conflating,
and handing each tool a subtly different truth. The compiler already knows every
slot, site, and interop boundary; the runtime knows which committed commit each
observation belongs to. The unresolved alternatives — where evidence is emitted,
which identities exist, how tools consume them, what production keeps — are
contract decisions warranting a durable record, not a bead.

## Goals / Non-Goals

Goals: one versioned evidence schema consumed by Xray, Story, Pair, diagnostics,
and tests; evidence usable *before mount* (the manifest) and truthful *after
commit* (the instance record — speculative renders publish nothing); honest
bounds everywhere (loss accounting, `:owned? false` overrides, qualified
retroactive lifecycle annotations); production erasure as a proof (G-7/G-11).

Non-goals: no new Xray panels at S3 (mounted-views, SSR/roots, heatmap panels
come only after an information-architecture review — the v1 emit obligation is
the schema, not panels); no render flamegraphs or per-view timeline scrubbers
(React Performance Tracks correlate via render-key/epoch ids); no second runtime
model for Story/Pair, no remote mutation API, no general query engine over
evidence; no production telemetry — the always-on error channel is EP-0008's.

## Relationships

- **EP-0030** (the compiled-view substrate program) — the umbrella: stages,
  demand bar, and the blessed API surface this evidence tier instruments.
- **EP-0031** (the `re-frame.ui` programming model) — the compiler, `defview`,
  templates, and handler law whose sites the manifest indexes.
- **EP-0032** (reactivity and ownership) — observation targets and the committed
  push-ownership protocol; connected commit is the publication boundary for
  instance records, and the three-state connection lifecycle recorded here is
  that EP's observed-lifecycle contract.
- **EP-0034** (production, SSR, and testing posture) — owns the SSR/roots story
  (**root-id** lives with `spec/011`'s root manifests); the
  `:hydration-correction` cause names its server/client/probe disagreements;
  its G-7/G-11 gates prove this tier's production erasure.
- **EP-0035** (component-library readiness) — the 2026-07-16 amendment package:
  render-slot sites join the manifest roster (P0-3); the dev/test manifest
  projection becomes the versioned public docs/slot shape (P1-5).
- **EP-0008** (production observability channels) — after erasure, production
  keeps exactly the always-on error channel; this evidence is the dev-only tier.
- **EP-0025** (data classification) — occurrence keys, override values, query
  args, and event payloads route through the one egress-projection policy.
- **`spec/009-Instrumentation.md`** — the one-catalogue rule (rf2-cs0kd1): every
  evidence schema, trace op, and error/warning id this design names gets a
  catalogue row there.
- **`spec/004-Views.md`** §View identity and the instrumentation surface — the
  primary normative home (see banner).
- Design record: graduated into this EP + the normative `spec/009-Instrumentation.md`
  and `spec/004-Views.md` §View identity (from `04-debugging.md`, amended 2026-07-16);
  the S3 Xray consumption IA (`drafts/xray-ui-consumption-ia.md`) lands with W7a under
  `tools/xray/spec/`. Both synthesis-tree sources are tombstoned per rf2-mgy7pz.

## Specification

### Two evidence layers

**Compiler manifest — what *can* happen.** Per view, dev-only: source coords,
prop slots + schema, template fingerprint, hook signature, capability bits, and
every site — subs with query shapes; events with event shapes and a
`:serializable?`/`:dynamic` flag; leases; effects; presence sites; trusted-markup
`ui/html` sites; **render-slot sites [2026-07-16]** — each with source + template
path. No runtime values; useful before mount, to Xray, Story, editors, and
agents. **[AMENDED 2026-07-16 — readiness P1-5, cross-ref EP-0035]:** the
dev/test projection of this manifest is a **versioned public shape** additionally
carrying per-prop docs/defaults and declared slot metadata — one source of truth
replacing parallel args-description systems (re-com maintains ~57 such Vars).

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
inside one instance carry **occurrence-path** (e.g. `[{:site 17 :key order-id}]`)
on DOM annotations, event provenance, and presence records — render-key alone is
insufficient. Story overrides display honestly: `{:kind :story-override …
:owned? false}` — a visual override, never evidence a subscription computed it.

**Connection is recorded as observed** (name unified 2026-07-12): the runtime
emits exactly three states — `:connected` / `:disconnected` / `:dead` — and the
immediate cleanup fact is `:disconnected {:reason :unknown}` (public React cannot
distinguish an Activity hide from an unmount at cleanup). `:activity-hidden` and
`:unmounted` are qualified **retroactive annotations** of the *prior interval*,
never runtime states: a reconnect proves a hide; host/root teardown proves
unmount; GC-based inference (if enabled) is best-effort/eventual — no timestamp,
a bounded tombstone that never retains the cell. Records distinguish runtime
state vs tool label vs inference; transitions never fabricate renders.

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
event/sub) · `:story-override` · `:prop` (**changed top-level slots** — the sound
cheap promise; nested paths are a dev diff view on demand, never a default emit)
· `:local-state` · `:frame` / `:context` · `:resource` · `:hmr` / `:hmr-remount`
· `:hydration-correction` · `:reconnect-correction` · `:epoch-restore` (**the
restore operation token + target epoch** — the repaint is caused by the restore
*operation*; old epoch records are never rewritten or back-filled) ·
`:foreign-or-react` (the honest fallback — never fabricate precision).

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

Compile-time `data-rf2-source-coord` + render-key (+ occurrence-path) annotation
on compiler-owned host roots — today's attribute vocabulary, so existing Xray
click-to-source works day one. Chains both directions: app-db path → subs →
views → elements, and element → event → handler → effects → state diff → sub
cascade → commits. React DevTools stays independent; Performance Tracks correlate
via render-key/epoch ids. Xray answers "why does this view carry a client
runtime?" from capability bits + sites — the absence story made inspectable.

### Tool integration posture

- **Xray — enrich existing surfaces first.** The Views tab gains the causes
  vector, occurrence identity, and the three-layer lifecycle grammar; dispatch
  surfaces gain event-site provenance; `:epoch-restore` renders where causes
  render; warning families ride the issue surfaces. New panels come only after an
  IA review shows an existing surface can't answer the question. Every trace
  shape is a Spec 009 catalogue row first; every Xray PR updates `tools/xray/spec/*`.
- **Story:** mounts scenes by **view id**; asserts on JVM structural trees +
  app-db; sub-overrides via the observation-target protocol with `:owned? false`
  honesty; JVM override injection is the explicit `ui.test/render` option — one
  mechanism per host, both named.
- **Pair:** hot-swap rides the existing nREPL HMR path; the five frozen read-only
  projections (`view-manifest`, `mounted-views`, `explain-render`,
  `view-dependencies`, `view-event-sites`) live in `re-frame.ui.tool` — the tool
  tier, never the authoring namespace.
- **Epochs:** restore causes carry the operation token — the timeline stays
  truthful through time travel; history is never rewritten.

### Privacy and production erasure

Manifests carry shapes and source, never live values; render values classify
through their owning sub/schema; sensitive/large values redact per EP-0025 before
off-box egress; histories are bounded with loss accounting; paths are
project-relative. **Erasure is a proof:** compile-time defines + the G-7/G-11
bundle-scan gates put manifests, cause vectors, histories, warning text, and
`data-rf2-*` strings on the scanned absence roster; the always-on Spec 009
error contracts (EP-0008) remain.

## Rationale

Emit-at-cause-site over reconstruction is the load-bearing choice: reconstruction
made legacy attribution fragile and silently degrades under concurrent rendering.
Two layers keep the manifest useful before mount and the record truthful after
it; the five-identity split prevents the conflations that made "which one
re-rendered?" unanswerable. The honesty rules (`:owned? false`, bounded `:prop`
precision, `:disconnected {:reason :unknown}`, loss accounting) trade apparent
precision for provable truth — a tool that fabricates certainty is worse than one
that says "unknown". Enrich-first keeps Xray's hardest-won asset — its
information architecture — from dilution by panels bought before demand.

## Backwards Compatibility

Pre-alpha; no shims. The legacy `[view-id instance-token]` `:render-key` wire
shape and the cloneElement/post-render attribution path remain emitted by the
coexisting adapters (`[TRANSITION]` in Spec 004); the compiled
substrate emits its own versioned schema from day one, and its interim legacy attribution
machinery is deleted once equivalent evidence is live (.95.7).

## Bead Plan / Reference Implementation

S3 of the `re-frame.ui` plan (epic rf2-vxgfnd.95):

1. **rf2-vxgfnd.95.6 — versioned view evidence + public `re-frame.ui.tool`
   projections**: evidence schemas, occurrence/cause/lifecycle fields, loss
   accounting, the five projections; carries the 2026-07-16 P1-5 amendment. Spec
   009 catalogue rows precede all consumption (hot-zone — sequenced).
2. **rf2-vxgfnd.95.7 — Xray consumption** (gated on .95.6): enrich existing
   surfaces per the S3 IA; delete the legacy attribution path; update
   `tools/xray/spec/*` atomically.
3. **rf2-vxgfnd.95.8 — Story and Pair consumers** (gated on .95.6): view-id
   mounting, honest overrides, the five projections over MCP, HMR-path hot swap.
4. Conformance, cross-browser, and production-absence gates ride rf2-vxgfnd.95.10;
   budget/absence completion asserts S6 per the Spec 004 ledger.

Guide impact: the substrate guide's debugging chapter teaches the glass cockpit;
`docs/core` inherits it at promotion. No other human-facing guide change yet.

## Resolved Decisions

- **Attribution at the cause site (rf2-6j0knp lineage) — ruled.** Evidence is
  emitted where the cause happens, never reconstructed from React internals.
- **`:prop` cause precision — ruled.** Changed top-level slots only; nested-path
  deep diff is an on-demand dev view, never a default emit.
- **Connection recorded as observed — ruled (name unified 2026-07-12).** Three
  runtime states; hide/unmount are qualified retroactive annotations with named
  proofs; GC inference explicitly approximate.
- **P1-5 docs/slot projection — directed 2026-07-16 (EP-0035).** The dev/test
  projection is a versioned public shape with per-prop docs/defaults and slot
  metadata; render-slot sites join the site roster.

## Open Issues

None open. One question is deferred with a named trigger: a Static-mode
mounted-views/manifest-browse surface goes to the post-S3 IA review, triggered
only if dogfooding shows the Views-tab + `mounted-views` assembly failing.

## Recommendation

Keep EP-0033 `accepted` while the S3 beads land; move to `final` when the Spec 004
ledger rows assert (S3) and the G-7/G-11 gates complete (S6), residue as errata.
