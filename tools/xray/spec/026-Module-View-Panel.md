# 026 — Module-view Panel

> The Xray-side consumer contract for EP-0013 app values + runtime realms:
> the **(realm, frame) address space** of the running process and the
> **disposition-6 demand-trigger** for per-module descriptor provenance.

Status: **shipped (scaffold)** — rf2-wtg9z4. The realm/frame address space
renders from public seams today; the per-module provenance section is
scaffolded behind an awaiting-seam caption until a public
realm→installed-app read surface graduates (follow-up bead rf2-imquoq).

## §1 Purpose & scope

EP-0013 (app values and runtime realms — framework
[`spec/Runtime-Subsystems.md`](../../../spec/Runtime-Subsystems.md) §Runtime
realms, [`docs/EP/EP-0013-app-values-and-runtime-realms.md`](../../../docs/EP/EP-0013-app-values-and-runtime-realms.md))
makes the **realm** the owner of the registrar / adapter / frame-registry.
A frame belongs to exactly one realm; the `(realm, frame)` pair is the
**full address** of a runtime context (disposition 3). An *app value* is the
immutable, composable description of a program (built from feature
*modules*); a *realm* is the container an app value is installed into.

The Module-view tab is the **cohesive home** for app-value / realm / module
inspection — per Mike's *cohesive-sub-domains-get-their-own-tab* ruling,
realm/module structure earns its own L4 tab rather than piling into App-db.
It is a **browse surface** (registry-wide, like the Static surfaces), not an
event-coupled lens.

It is also the **named demand trigger** of EP-0013 disposition 6: the EP
keeps per-module descriptor **provenance** / metadata internal *until an
Xray module-view demands it*. This is that view (see §4).

## §2 Layout

Two stacked sections (the shared `theme/section` rhythm):

```
│ ▼ REALMS  (N)                                                   │
│   :rf.realm/default  · 2 frames                                 │
│     frames: :app/main  :app/cart                                │
│ ─────────────────────────────────────────────────────────────  │
│ ▼ MODULES                                                       │
│   Per-module ownership, capability requirements, classification │
│   and descriptor provenance await a public realm→installed-app  │
│   read seam (EP-0013 disposition 6 graduation).                 │
```

## §3 REALMS section — the (realm, frame) address space

Every installed runtime realm, each followed by the frames it holds. Reads
the **public** seams:

- `rf/realm-ids` — the set of installed realm ids (EP-0013 disposition 3 ·
  PR #4038). A single-realm process returns exactly `#{:rf.realm/default}`.
- `rf/frame-ids` — the live frame ids.
- `rf/frame-realm` — a frame's realm (the frame-side address half).

The pure projection (`module_view_helpers/project-module-view`) groups
frames by realm (`realm-frames`), produces one realm-row per installed
realm (`project-realm-row`), and classifies single vs multi-realm
(`:multi-realm?`). Notes:

- **Zero-ceremony.** A single-realm process surfaces one realm with the
  realm dimension implicit — no realm-grouping ceremony, exactly as a
  single-frame app never spells a frame.
- **A frameless realm still appears** — a realm can exist with zero frames.
- **A stale frame never strands the view** — a frame resolving to a realm
  absent from `realm-ids` still gets a row (defensive); a nil realm buckets
  to `:rf.realm/default` (absence = default realm, the EP-0013 D1 rule).

## §4 MODULES section — the disposition-6 demand trigger (scaffolded)

The per-module facts EP-0013 disposition 6 rules public — **ownership**
(`:owns`), **capability requirements** (`:requires`), **EP-0015
classification** metadata — plus **descriptor provenance** (which module
owns a handler / sub / path; source coords).

**These cannot be read from a running process today**, and this slice does
**not** expand core scope to invent the seam:

- the per-module facts live on a **constructed** app value's `:modules` map
  (`rf/app` / `rf/module`) — they do not exist on a registrar projection;
- a **running realm exposes no public read of its installed app value**:
  `re-frame.realm/installed-app` is internal, and the internal `app-value`
  projection over a realm's registrar carries no module structure;
- the public inspectors `rf/app-registrations` / `rf/app-owns` /
  `rf/app-requires` operate on an app value you **already hold** — there is
  no public seam to obtain a running realm's app value to feed them.

So the demand trigger **files a follow-up bead** (rf2-imquoq) for the
public realm→installed-app provenance read surface (the graduation EP-0013
disposition 6 reserves), and the MODULES section renders the calm
**awaiting-seam caption** (`module_view_helpers/awaiting-provenance-caption`)
until it lands. The realm-row shape already carries the `:modules` /
`:owns` / `:requires` / `:classification` slots (defaulted nil/empty) and
`:provenance-available?` (false), so the fill-in is a **no-reshape change**
when the seam graduates.

## §5 Tab registration

A **Dynamic L4 tab** registered via `panel-registry/reg-l4-tab!` in
`panels/module_view.cljs`'s `install!`: id `:module-view`, label
**"Modules"**, mnemonic `u`, order **9** (after the Derivation-Graph at 8,
keeping the cross-feature runtime-structure tabs adjacent).

Like the Derivation-Graph tab this is an **L4-only** surface — it exposes
no standalone `mount-*!` facade, so it is **not** in
[`panel_enum.cljc`](../src/day8/re_frame2_xray/panel_enum.cljc) (that enum
carries the *mountable* surface; an L4-only tab is shell-internal). The
Cmd-K palette picks it up automatically (the palette reads
`panel-registry/tabs-for-mode :dynamic`).

## §6 Data sources & privacy

- `:rf.xray/module-view` — the view composite; reads `rf/realm-ids` ·
  `rf/frame-ids` · `rf/frame-realm` at recompute time and projects via the
  pure helper. **Read-only** — enumerating realms/frames pins nothing and
  dispatches nothing.
- The surface carries realm/frame **ids only** (keywords), no app-db values
  — there is no data egress concern in this slice. When the provenance seam
  graduates, the off-box egress posture (the
  [`025`](025-Derivation-Graph-Panel.md) §egress redaction pattern) applies
  to any value-bearing module metadata.

## §7 Implementation

- `panels/module_view.cljs` — the panel view + `install!` (sub + tab).
- `panels/module_view_helpers.cljc` — the pure `data → data` projection
  (`realm-frames` · `project-realm-row` · `project-module-view` ·
  `awaiting-provenance-caption` · `realm-summary-line`); JVM-testable.
- Tests: `panels/module_view_helpers_cljs_test.cljc` (the projection
  shape + the zero-ceremony / multi-realm classification + the empty /
  stale-frame edge cases + the awaiting-seam caption).
