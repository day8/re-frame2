# 026 — Module-view Panel

> The Xray-side consumer contract for **two** runtime-structure models, side
> by side: the EP-0023 **`image -> frame -> event stream`** public model
> (images as registration-set values, frames as execution contexts,
> frame-derived resolution as the lookup path), and the retained EP-0013
> internal substrate (the **(realm, frame) address space** + the
> **disposition-6 demand-trigger** for per-module descriptor provenance).

Status: **shipped** — rf2-wtg9z4 (address space) + rf2-at0oen (per-module
provenance) + **rf2-32siq3.12** (the EP-0023 image/frame model). The realm/frame
address space renders from public seams, the MODULES section reads real
per-module provenance off each realm's installed app value via the public
`rf/installed-app` read seam (EP-0013 disposition 6, PR #4061), and the FRAMES
section presents each EP-0023 live image-loaded frame as an execution context
carrying its resolved image (the generation's `[kind id]` descriptors). A
process running entirely on the `reg-*` sugar / load-order path carries no
constructed app value, so its MODULES section shows the honest no-module
caption; a process not using `rf/make-frame` image-loaded frames shows the
honest no-image caption in the FRAMES section.

## EP-0023 partial supersession (rf2-32siq3.12)

[EP-0023](../../../docs/EP/EP-0023-image-loaded-frames.md) makes the PUBLIC
architecture `image -> frame -> event stream`, **partially superseding** the
EP-0013 app/realm surface while RETAINING the realm machinery as the internal
installation substrate. This tab is the cohesive home for BOTH:

- the EP-0023 PUBLIC model (the FRAMES/IMAGES section, §8) — rendered FIRST,
  because it is the public model the operator reasons in;
- the EP-0013 internal substrate (the REALMS + MODULES sections, §3/§4) —
  retained below as the implementation structure the public model rides on.

The two read DIFFERENT surfaces and are each demand-gated: a process using one
and not the other renders only the section it has. §8 is the normative contract
for the EP-0023 model on this tab.

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

Three stacked sections (the shared `theme/section` rhythm) — the EP-0023
public model FIRST (§8), then the retained EP-0013 substrate (§3/§4):

```
│ ▼ FRAMES  (N)                                                   │
│   :counter/main                                                 │
│     image     12 descriptors · 6 kinds  :docs.counter/v2        │
│     caps      :rf.capability/http                               │
│     resolves  this frame resolves (kind id) through its image   │
│       event :counter/inc    docs.counter.v2                     │
│       sub   :counter/value  docs.counter.v2                     │
│ ─────────────────────────────────────────────────────────────  │
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

The FRAMES section presents the EP-0023 `image -> frame` model (an image as
its `[kind id]` descriptor set, a frame as the execution context running it);
see §8. In a single-realm process the modules list renders with the realm
dimension implicit; with more than one realm each realm's modules sit under an
uppercase realm header — zero-ceremony.

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
(`:rf.module/owns`), **capability requirements** (`:rf.module/requires`), and
**descriptor provenance** (which module owns which descriptors, owner-stamped;
source coords) — read off each realm's installed app value via the public
`rf/installed-app` read seam (graduated by rf2-imquoq → rf2-at0oen, PR #4061).
The module FACT keys are owner-qualified (`:rf.module/*`, EP-0007 / EP-0017 v5 —
rf2-yk6u2x); the panel's internal module-row shape keeps the bare row keys
`:owns` / `:requires`.

The seam yields a running realm's installed app value **without installing
anything**:

- a realm seated via `rf/install!` returns the **rich constructed value**
  whose `:modules` map (`{module-id module}`) carries each module's
  `:rf.module/owns` / `:rf.module/requires` and its `:owner`-stamped
  `:registrations`;
- a realm seated only through the `reg-*` sugar / load-order path returns the
  registrar **projection** — registrations by kind, but **no `:modules`**
  (load-order registrations declare no module). That is the honest
  no-provenance case: the MODULES section renders the calm **no-module
  caption** (`module_view_helpers/no-modules-caption`), naming the
  `rf/app` / `rf/module` / `rf/install!` remedy, rather than fabricating rows.

### The three-way empty-state decision (rf2-e0mq7a)

The core app-value contract distinguishes a **projected / load-order** app
(no `:modules` slot) from an app **CONSTRUCTED from zero modules** (an explicit
empty `:modules {}` map). The MODULES section preserves that distinction so a
genuinely-installed zero-module app is never mislabelled as the load-order case:

- some realm carries **module rows** (`any-modules?` true) → render the module
  list;
- some realm carries **provenance** (`any-provenance?` true — a CONSTRUCTED,
  installed app whose `:modules` projects to a **vector**, `[]` for zero
  modules) but no realm has any modules → render the **zero-module-app caption**
  (`module_view_helpers/zero-module-app-caption`): the honest
  installed-but-empty state, naming the `rf/module` remedy;
- no realm carries provenance at all (every `:modules` nil — load-order /
  sugar-only) → render the **no-module caption** (`no-modules-caption`).

`project-app-modules` keys provenance off the **presence** of the `:modules`
key, not its non-emptiness: an explicit `:modules {}` projects to an empty
**vector** `[]` (constructed, zero modules), while an absent/nil `:modules`
projects to **nil** (no provenance). Collapsing `{}` to nil — the prior
`(when (seq modules) …)` bug — falsely rendered the load-order caption over an
installed zero-module app.

The pure projection (`module_view_helpers`):

- `project-module-row` — one module value → `{:module-id :owns :requires
  :registration-kinds :registration-count :source}`;
- `project-app-modules` — an app value → its sorted module rows + union
  `:requires`. `:modules` is **nil** when the app carries no `:modules` slot
  (no provenance), an empty **vector** `[]` when `:modules {}` (constructed,
  zero modules), or a non-empty row vector;
- `project-realm-row` (3-arity) — fills a realm's `:modules` / `:requires`
  from `(rf/installed-app realm)`;
- `project-module-view` (4-arity) — takes an `installed-app-of` resolver
  (`rf/installed-app`) and sets `:provenance-available?` **true**;
- `any-provenance?` — true when some realm's `:modules` is a vector (a
  constructed app, including a zero-module one); separates the zero-module-app
  caption from the no-provenance caption;
- `any-modules?` — true when some realm carries at least one module row.

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
  frame ids, module ids, `:rf.module/owns` paths/routes, `:rf.module/requires`
  capability keywords,
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
  `project-realm-row` · `project-module-view` · `any-provenance?` ·
  `any-modules?` · `no-modules-caption` · `zero-module-app-caption` ·
  `realm-summary-line`); JVM-testable.
- Tests: `panels/module_view_helpers_cljs_test.cljc` (the address-space
  projection + the zero-ceremony / multi-realm classification + the empty /
  stale-frame edge cases + the module-row shape + the seam-fed
  `project-module-view` + the three-way empty-state decision: module list /
  zero-module-app caption / no-provenance caption via
  `any-provenance?` / `any-modules?`, incl. the explicit `:modules {}`
  constructed app — rf2-e0mq7a).

## §8 FRAMES / IMAGES section — the EP-0023 public model (rf2-32siq3.12)

The EP-0023 `image -> frame -> event stream` public model on this tab. It
presents the three load-bearing nouns
([EP-0023 §Specification Summary](../../../docs/EP/EP-0023-image-loaded-frames.md)):

- **image** — the selected registration-set VALUE a frame resolves against.
  The inspectable, sealed form is the *resolved image generation*: a
  `{[kind id] descriptor}` resolver plus the union capability requirements and
  the kinds present. An image is presented AS THAT SET OF `[kind id]`
  descriptors — *"which registrations are visible to this frame?"*. Each
  descriptor carries its **provenance**: a source namespace (`:rf.provenance/ns`
  string), an inline coordinate (`:rf.provenance/image` / `:rf.provenance/inline`),
  or the framework-standard marker (`:standard true`). An image is data, not
  state, and not the running object (EP-0023 §Image).
- **frame** — the live EXECUTION CONTEXT that POINTS AT the ONE resolved image
  generation it runs (EP-0023 §Frame). Presented as a frame-row: the frame id
  (or `<anonymous>` for a direct, no-id frame object), the image summary
  (`N descriptors · K kinds`), the host capability keys, the active-substrate
  adapter binding presence, and the resolved descriptor set. The frame is
  *"what has happened in this run?"*; the image is *"what can this frame
  run?"* (EP-0023 §Two Boundaries).
- **frame-derived RESOLUTION** — the lookup path
  `target frame -> resolved image generation -> registration resolution`
  (EP-0023 §Specification). The SAME `(kind, id)` resolves to DIFFERENT
  descriptors in frames running different images, because each frame resolves
  through its own generation. The section makes this visible: every frame-row's
  descriptor set is *that frame's* resolution, not a global registry.

### §8.1 Xray-beside-the-target as its OWN image (EP-0023 §Xray Beside The Target)

The load-bearing dogfooding the EP names: Xray is itself a running surface with
registrations, state, views, subscriptions, and effects, and it MUST NOT share
the target's registration set.

> Xray runs in its own frame. Xray inspects the target frame. That keeps the
> inspection tool from becoming part of the thing being inspected.

**Scope (runtime self-seating shipped as a callable + test-ns collision fixed;
production-singleton flip gated on a routing-panel read fix — rf2-rjml45).** The
dogfood has a working RUNTIME arm: Xray builds its OWN real `rf/image` (a
separate registration-set value), the implementation **proves** it
registration-disjoint from a target frame's image, AND
`image_view_reads/seat-xray-frame!` **SEATS a running Xray frame built from that
image** via `rf/make-frame {:id … :images [(xray-image)]}` — true runtime
self-seating in genuine registration ISOLATION (the seated frame resolves ONLY
Xray's `:rf.xray/*` registrations plus the framework standards the assembly
unions into every generation, not the shared default registrar). This is the
literal `(rf/make-frame {:id … :images [(xray-image)] …})` shape the EP names,
shipped as a tested, callable seam.

The seating preserves the `:rf.trace/frame-no-emit?` gate that keeps
Xray's own reactivity out of the trace ring it inspects: `make-frame` is the
EP-0023 OBJECT constructor and honours only the frame-creation opts (`:images` /
`:id` / `:initial-db` / …), rejecting the record-config flag, so the gate is set
directly through `re-frame.trace/set-frame-no-emit!` (the same canonical seam
`reg-frame` routed it through) — asserted on every seat / re-seat. The seating is
idempotent: `make-frame {:id …}` is fail-loud on a duplicate live `:id`, so a
re-open / hot-reload / repeated testbed mount finds the frame already live
(`xray-frame-seated?`) and skips the re-create, re-asserting only the gate.

**The test-namespace collision and its fix (rf2-rjml45).** Flipping the singleton
exposed a selector-grain blocker: `xray-image`'s `day8.re-frame2-xray.**`
`:include-ns` glob sweeps in Xray's OWN `*-cljs-test` + `test-helpers.**`
namespaces in any dev/test build that loads them, and those co-register the same
`:rf.xray/*` ids the production sources do (e.g. `[:fx :rf.editor/open]` from both
`open-in-editor` and `open-in-editor-cljs-test`) — so assembling the image failed
loud (`:rf.error/image-duplicate-id`) under the node-test build. The fix is an
EP-0023 `:exclude-ns` SELECTOR (added to the framework image API) that subtracts
those namespaces from the production image: `xray-image` declares
`:exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
"day8.re-frame2-xray.test-helpers.**"]`. `:exclude-ns` is a subtractive narrowing
knob over `:include-ns` (matched by provenance namespace, NOT zero-match
fail-loud — a defensive guard); the `*-cljs-test` form relies on the EP-0023
intra-segment `*` wildcard (each `*` matches zero-or-more chars within one
segment, never crossing a `.`), so the trailing `*-cljs-test` matches a leaf
segment suffix at any depth via `**`. Production builds never load the excluded
namespaces, so the exclude is a no-op there. With the test registrations
subtracted, the production image assembles WITHOUT a collision (the node-test
suite is green with the flip applied), and `xray-image` ships this `:exclude-ns`
today.

**What is still deferred (rf2-rjml45 follow-up).** The default production-singleton
mount path (`mount/ensure-xray-frame!`) is NOT yet flipped onto
`seat-xray-frame!`; it keeps the legacy realm seating
(`reg-frame {:rf.trace/frame-no-emit? true}`) for now. Flipping it surfaced a
SECOND, distinct blocker beyond the dup-id: a browser-runtime regression in the
**Routing panel** (the `routes-epochs` nightly xray-feature-gate scenario). Under
image-loaded seating the routing-tab-data composite's target-frame read
(`:rf.xray/observed-frame` → `:rf.xray/current-route-slice` /
`:rf.xray/target-frame-runtime-db`) resolves empty — the panel renders
`currentId:null` with an empty route table even though the target frame
(`:rf/default`) navigates correctly (the trace shows `:rf.route/activated`). The
node-test suite does NOT catch it (it is a browser-only feature-gate path; the
node suite is green). The disjointness + seating-core claims hold regardless; only
the default-singleton flip waits on fixing that panel-read path under image-loaded
seating.

Xray therefore models its registration set as a **separate image**, NOT as
shared registration state:

- `image_view_reads/xray-image` constructs Xray's OWN EP-0023 `rf/image` — an
  inert value selecting Xray's own source namespaces (`:include-ns
  ["day8.re-frame2-xray.**"]`, under which every `:rf.xray/*` registration is
  authored and stamped `:rf.provenance/ns`, which survives production elision),
  NARROWED by `:exclude-ns ["day8.re-frame2-xray.**.*-cljs-test"
  "day8.re-frame2-xray.test-helpers.**"]` so Xray's own test + test-support
  namespaces are subtracted (rf2-rjml45 — they co-register the production ids in
  a dev/test build; the exclude keeps the production image collision-free).
  It is Xray's instruction set as data.
- Xray's `:rf.xray/*` registrations do NOT leak INTO a target frame's image:
  a target frame's image selects the TARGET's own namespaces, not Xray's.
- A target frame's registrations do NOT leak INTO Xray's image: the two images
  resolve disjoint `[kind id]` sets, so a frame built from one cannot resolve
  the other's registrations.
- Xray reads the target frame's **generation + state as DATA** through the
  live-read seam (`image_view_reads`), never by sharing the target's
  registrar. The target remains ordinary data from the tool's point of view.

`image_view_reads/xray-image-isolated-from?` is the registration-disjointness
predicate, and the proof is on the **REAL non-leakage invariant**, not a proxy:
it **assembles BOTH images into sealed generations and compares their
APPLICATION-OWNED `:rf.gen/resolver` KEYSETS** (the `[kind id]` pairs each frame
would resolve, EXCLUDING the framework-standard registrations), returning true
iff those application-owned keysets are DISJOINT. The exclusion is load-bearing
(rf2-32siq3.41): EP-0023 assembly unions the framework standards (e.g.
`[:interceptor :rf.interceptor/path]`, stamped `:standard true`) into **every**
resolved generation, so a framework standard is shared by every frame *by
construction* — it is the framework, not a leak between two application images.
Comparing the full keysets would therefore report a false-positive overlap on
the shared standard; `application_resolver_keyset` filters the `:standard true`
descriptors out before the comparison, so the predicate measures the genuine
application-registration leak. This is stronger than comparing the
`:rf.image/include-ns` selector STRINGS (the prior proxy): different globs
can select OVERLAPPING namespaces, and inline `:registrations` carry no
`:include-ns` selector at all, yet either can introduce a shared `[kind id]` —
the keyset comparison catches both, the string comparison neither. The
predicate is fail-soft: a throw during assembly (a zero-match `:include-ns`, a
collision, an old core) means isolation could not be assembled and proven, so it
is CONSERVATIVE and returns `false` (not-proven-isolated) rather than a
false-positive `true`. It offers a live-store arity (assemble both against the
live source store — the production-runtime check) and an explicit-pool arity
(assemble both against a supplied descriptor pool — the deterministic test
form). The `image_view_reads_cljs_test` asserts the isolation **bidirectionally**
against assembled generations — including the load-bearing case where two
DIFFERENT globs select OVERLAPPING namespaces (a constructed overlap the string
proxy would mislabel isolated, the keyset check correctly reports NOT isolated),
and that the framework standard rides into BOTH generations yet is excluded from
the leak comparison — the assertion the .29 dogfooding review verifies.

### §8.2 Demand-gating & empty state

The EP-0023 sections are demand-gated like the realm dimension. EP-0023's
public model is OPT-IN over the retained EP-0013 substrate: a process that
never calls `rf/make-frame` with `:images` has an empty live-frame registry, so
`project-image-view` reports `:images?` false and the FRAMES section renders
the calm **no-image caption** (`image_view_helpers/no-images-caption`) naming
the `rf/image` / `rf/make-frame` remedy — the honest *not-using-images-yet*
state, not a broken surface. A frame whose generation resolves zero descriptors
does not flip `:images?` (there is no image content to show).

### §8.3 Data sources & privacy

- `:rf.xray/image-view` — the FRAMES/IMAGES composite; reads the EP-0023
  live-frame registry (`re-frame.live-frame/live-frames`) + each frame's sealed
  generation (`re-frame.image-assembly/resolve-descriptor`) at recompute time
  via the fail-soft `image_view_reads` seam, and projects via the pure
  `image_view_helpers/project-image-view`. **Read-only** — enumerating live
  frames and reading sealed generations pins nothing and dispatches nothing (a
  sealed generation is an immutable VALUE, not a routing path).
- The surface carries frame/image **ids and structural descriptors** — frame
  ids, image ids, `[kind id]` pairs, provenance namespace strings / inline
  coordinates, and capability keywords. These are **structural descriptors**,
  not app-db values. No handler values or app-db data egress through this
  surface (frame STATE — app-db / runtime-db — is the App-db tab's concern; this
  tab shows the IMAGE, the instruction set).

### §8.4 Implementation & tests

- `panels/image_view_helpers.cljc` — the pure `data → data` projection
  (`descriptor-provenance` · `project-generation` · `project-frame-row` ·
  `project-frames` · `resolve-in-frame` · `project-image-view` ·
  `provenance-summary` · `image-row-summary` · `no-images-caption`);
  JVM-testable.
- `panels/image_view_reads.cljs` — the READ-TIME fail-soft live read seam
  (`live-frames` · `resolve-descriptor` · `image-view-data`) over the EP-0023
  core surfaces (`re-frame.live-frame` / `re-frame.image` /
  `re-frame.image-assembly`), PLUS the Xray-as-its-own-image constructor +
  SEATING (`xray-image` · `xray-image-id` · `xray-source-glob` ·
  `xray-exclude-globs` · `resolver-keyset` · `application-resolver-keyset` ·
  `xray-image-isolated-from?` · `xray-frame-seated?` · `seat-xray-frame!`).
  `xray-image` declares `:include-ns [xray-source-glob]` NARROWED by
  `:exclude-ns xray-exclude-globs` (`["day8.re-frame2-xray.**.*-cljs-test"
  "day8.re-frame2-xray.test-helpers.**"]`) so Xray's own test + test-support
  namespaces are subtracted from the production image (rf2-rjml45 — they
  co-register the production ids in a dev/test build).
  `resolver-keyset` is the full `[kind id]`-keyset reader (every resolved
  registration, framework standards included — what the FRAMES section
  displays); `application-resolver-keyset` is the application-owned subset
  (excluding the `:standard true` framework standards the assembly unions into
  every generation — rf2-32siq3.41) that the disjointness predicate compares;
  `xray-image-isolated-from?` assembles both images and compares those
  application-owned keysets (live-store + explicit-pool arities, fail-soft to a
  conservative `false`). `seat-xray-frame!` is the TRUE runtime seating
  (EP-0023 §Xray Beside The Target): `rf/make-frame {:id frame-id :images
  [(xray-image)]}` (live-store + explicit-pool arities) +
  `re-frame.trace/set-frame-no-emit!` for the
  trace gate, guarded by `xray-frame-seated?` (a live-frame registry probe) for
  idempotency on re-seat. Xray may require these core
  namespaces directly — bundle isolation forbids `implementation/` requiring
  from `tools/`, not the reverse, the same pattern Xray uses for
  `re-frame.frame` / `re-frame.registrar` / `re-frame.trace`. (The read seam's
  fail-soft is READ-TIME robustness, not absent-core-surface tolerance: the core
  EP-0023 namespaces are hard-`:require`d, so an old core fails at LOAD before
  the try/catch.) `seat-xray-frame!` + `xray-frame-seated?` are covered by
  `image_view_reads_cljs_test` (seats against an explicit pool; asserts the
  seated frame resolves ONLY Xray's app-owned ids, the trace-no-emit gate is
  set, and re-seat is idempotent — no duplicate-`:id` throw). The `xray-image`
  `:exclude-ns` is covered by `xray-image-excludes-its-own-test-registrations`
  (a pool carrying a production id and its `*-cljs-test` sibling selects ONLY the
  production descriptor and assembles without a dup-id).
- `mount.cljs/ensure-xray-frame!` — keeps the legacy realm seating
  (`reg-frame {:rf.trace/frame-no-emit? true}`) for the production singleton. The
  TRUE image-loaded flip (`seat-xray-frame! :rf/xray` →
  `rf/make-frame {:id :rf/xray :images [(xray-image)]}`) is proven + the dup-id
  blocker resolved (the `:exclude-ns` selector on `xray-image`, §8.1; node-test
  green with the flip), but a SECOND blocker — a browser-runtime Routing-panel
  read regression (the `routes-epochs` nightly gate) — gates the default flip; see
  the rf2-rjml45 follow-up. The runtime-reset test fixture
  (`re-frame.test-support/make-reset-runtime-fixture`) now clears the EP-0023
  live-frame registry in lockstep with `frame/frames` (so a frame seated via
  `make-frame {:id …}` in one test does not leak into the next) — kept regardless
  of the flip.
- `panels/module_view.cljs` — extended with the FRAMES section (`frame-row` ·
  `descriptor-rows` · `frames-section-body`) + the `:rf.xray/image-view` sub.
- Tests: `panels/image_view_helpers_cljs_test.cljc` (the generation/image
  projection, the frame-row shape, the frame-derived resolution — same id /
  different image → different descriptor, the demand-gated `:images?`
  decision, the display strings) + `panels/image_view_reads_cljs_test.cljs`
  (the Xray-as-its-own-image isolation proven on assembled resolver KEYSETS —
  bidirectional disjointness, the constructed overlapping-glob case the string
  proxy would miss, and the negative leaky-image case — plus the fail-soft
  live-read seam end-to-end and frame-derived resolution through a real
  generation).
