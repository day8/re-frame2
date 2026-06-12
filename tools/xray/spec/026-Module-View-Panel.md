# 026 — Module-view Panel

> The Xray-side consumer contract for EP-0013 app values + runtime realms:
> the **(realm, frame) address space** of the running process and the
> **disposition-6 demand-trigger** for per-module descriptor provenance.

Status: **shipped** — rf2-wtg9z4 (address space) + rf2-at0oen (per-module
provenance). The realm/frame address space renders from public seams, and the
MODULES section reads real per-module provenance off each realm's installed app
value via the public `rf/installed-app` read seam (EP-0013 disposition 6,
PR #4061). A process running entirely on the `reg-*` sugar / load-order path
carries no constructed app value, so its MODULES section shows the honest
no-module caption.

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
│   :shop/cart                                                    │
│     owns      :app-db [:cart]   :routes :shop/checkout          │
│     requires  :rf.capability/http                               │
│     registers 2 descriptors (event · sub)                       │
│     source    shop.cart:12                                      │
```

(In a single-realm process the modules list with the realm dimension
implicit; with more than one realm each realm's modules sit under an uppercase
realm header — zero-ceremony.)

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

## §4 MODULES section — the disposition-6 demand trigger (shipped)

The per-module facts EP-0013 disposition 6 rules public — **ownership**
(`:owns`), **capability requirements** (`:requires`), and **descriptor
provenance** (which module owns which descriptors, owner-stamped; source
coords) — read off each realm's installed app value via the public
`rf/installed-app` read seam (graduated by rf2-imquoq → rf2-at0oen, PR #4061).

The seam yields a running realm's installed app value **without installing
anything**:

- a realm seated via `rf/install!` returns the **rich constructed value**
  whose `:modules` map (`{module-id module}`) carries each module's `:owns` /
  `:requires` and its `:owner`-stamped `:registrations`;
- a realm seated only through the `reg-*` sugar / load-order path returns the
  registrar **projection** — registrations by kind, but **no `:modules`**
  (load-order registrations declare no module). That is the honest
  no-provenance case: the MODULES section renders the calm **no-module
  caption** (`module_view_helpers/no-modules-caption`), naming the
  `rf/app` / `rf/module` / `rf/install!` remedy, rather than fabricating rows.

The pure projection (`module_view_helpers`):

- `project-module-row` — one module value → `{:module-id :owns :requires
  :registration-kinds :registration-count :source}`;
- `project-app-modules` — an app value → its sorted module rows + union
  `:requires` (nil `:modules` when the app carries none);
- `project-realm-row` (3-arity) — fills a realm's `:modules` / `:requires`
  from `(rf/installed-app realm)`;
- `project-module-view` (4-arity) — takes an `installed-app-of` resolver
  (`rf/installed-app`) and sets `:provenance-available?` **true**.

**EP-0015 classification is NOT a per-module fact.** Durable data
classification is **frame-owned** — declared on `reg-frame` / `make-frame` and
installed by `re-frame.frame-classification` (Spec 015 §Frame-owned durable
classification) — not carried on a module value (`rf/module` has no
`:classification` slot). So the MODULES section surfaces ownership, capability
requirements, and descriptor provenance; the classification dimension lives on
the frame side, not here. The realm-row keeps a reserved `:classification` slot
(defaulted nil) for shape stability, but it is not populated from `:modules`.

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
  `rf/frame-ids` · `rf/frame-realm` · `rf/installed-app` at recompute time and
  projects via the pure helper. **Read-only** — enumerating realms/frames and
  reading installed app values pins nothing and dispatches nothing
  (`rf/installed-app` is a *static* read of the install-time value, not a
  routing path).
- The surface carries realm/frame/module **ids and declarations** — realm ids,
  frame ids, module ids, `:owns` paths/routes, `:requires` capability keywords,
  registry kinds, and source coordinates (ns/line). These are **structural
  descriptors**, not app-db values. No handler values or app-db data egress
  through this surface. (Should a future slice surface value-bearing module
  metadata, the off-box egress posture — the
  [`025`](025-Derivation-Graph-Panel.md) §egress redaction pattern — would
  apply to it.)

## §7 Implementation

- `panels/module_view.cljs` — the panel view + `install!` (sub + tab); the sub
  reads `rf/installed-app` per realm.
- `panels/module_view_helpers.cljc` — the pure `data → data` projection
  (`realm-frames` · `project-module-row` · `project-app-modules` ·
  `project-realm-row` · `project-module-view` · `any-modules?` ·
  `no-modules-caption` · `realm-summary-line`); JVM-testable.
- Tests: `panels/module_view_helpers_cljs_test.cljc` (the address-space
  projection + the zero-ceremony / multi-realm classification + the empty /
  stale-frame edge cases + the module-row shape + the seam-fed
  `project-module-view` + the no-modules / `any-modules?` empty state).
