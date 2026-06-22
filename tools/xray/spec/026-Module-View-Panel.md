# 026 — Module-view Panel

> The Xray-side consumer contract for the EP-0023 **`image -> frame -> event
> stream`** public model: images as registration-set values, frames as
> execution contexts, frame-derived resolution as the lookup path. (The
> EP-0013 realm / app-value / install substrate this tab once also surfaced
> was **deleted in full** — no public facade under EP-0023, then removed
> outright by EP-0024; see the framework [`spec/Spec-Schemas.md`
> §`:rf/realm`](../../../spec/Spec-Schemas.md). The MODULES + REALMS sections
> that read it are gone.)

Status: **shipped** — rf2-wtg9z4 + **rf2-32siq3.12** (the EP-0023 image/frame
model). The tab presents each EP-0023 live image-loaded frame as an execution
context carrying its resolved image (the generation's `[kind id]` descriptors).
A process not using image-loaded frames shows the honest no-image caption. The
former MODULES section (per-module provenance read off a realm's installed app
value via `re-frame.realm/installed-app`) and the (realm, frame) REALMS section
(`re-frame.realm/realm-ids` × `re-frame.frame/frame-realm`) were **removed** with
the realm substrate (no public facade under EP-0023, deleted outright by
EP-0024). There is no `re-frame.realm` namespace; the live panel
(`panels/module_view.cljs`) reads only the EP-0023 image-loaded-frame registry.

## EP-0023 / EP-0024 — the public model is `image -> frame -> event stream`

[EP-0023](../../../docs/EP/EP-0023-image-loaded-frames.md) makes the PUBLIC
architecture `image -> frame -> event stream`, **superseding** the EP-0013
app/realm surface. [EP-0024](../../../docs/EP/EP-0024-unified-frame-identity-and-lifecycle.md)
then **deleted the realm substrate in full** — there is no `re-frame.realm`
namespace, no installed-app value, no realm coordinate on any wire record, and a
frame's event / subscription / fx / cofx handlers resolve directly against the
process registrar (framework [`spec/Spec-Schemas.md` §`:rf/realm`](../../../spec/Spec-Schemas.md)).

This tab is the cohesive home for the EP-0023 public model — the FRAMES/IMAGES
section (§8), which is the model the operator reasons in. The earlier MODULES
section (the retained EP-0013 installation substrate) and REALMS section (the
(realm, frame) address space) are gone with the substrate they read.

## §1 Purpose & scope

The Module-view tab is the **cohesive home** for runtime-structure inspection —
per Mike's *cohesive-sub-domains-get-their-own-tab* ruling, the image/frame
runtime structure earns its own L4 tab rather than piling into App-db. It is a
**browse surface** (registry-wide, like the Static surfaces), not an
event-coupled lens.

It surfaces the EP-0023 public nouns — image, frame, and frame-derived
resolution (§8) — reading the live image-loaded-frame registry. It dispatches
nothing and pins nothing.

## §2 Layout

A single section (the shared `theme/section` rhythm) — the EP-0023 public
model (§8):

```
│ ▼ FRAMES  (N)                                                   │
│   :counter/main                                                 │
│     image     12 descriptors · 6 kinds  :docs.counter/v2        │
│     caps      :rf.capability/http                               │
│     resolves  this frame resolves (kind id) through its image   │
│       event :counter/inc    docs.counter.v2                     │
│       sub   :counter/value  docs.counter.v2                     │
```

The FRAMES section presents the EP-0023 `image -> frame` model (an image as
its `[kind id]` descriptor set, a frame as the execution context running it);
see §8.

## §3 REALMS section — REMOVED (realm substrate deleted)

The REALMS section (the (realm, frame) address space — one row per installed
realm, frames grouped by realm) is **gone** with the realm substrate it read.
There is no `re-frame.realm` namespace, no `realm-ids`, and no
`re-frame.frame/frame-realm` (the realm coordinate was removed from frames under
EP-0024). The frame/image dimension is the FRAMES section's concern (§8); there
is no realm dimension to group it by.

## §4 MODULES section — REMOVED (installed-app substrate deleted)

The MODULES section — per-module ownership / capability requirements /
descriptor provenance read off a realm's installed app value via
`re-frame.realm/installed-app` — is **gone**. The EP-0013 app-value / module /
install substrate it read was deleted in full (no public facade under EP-0023,
removed outright by EP-0024): there is no installed app value, no `:modules`
map, no `re-frame.realm/installed-app` seam. Registration provenance is now a
per-descriptor fact carried on the resolved image generation (a source namespace
or inline coordinate), surfaced per descriptor in the FRAMES section (§8), not
projected off a module-owned `:registrations` table.

**EP-0015 classification is frame-owned, not module-owned.** Durable data
classification is declared on `reg-frame` / `make-frame` and installed by
`re-frame.frame-classification` (Spec 015 §Frame-owned durable classification) —
it was never a module fact. The classification dimension lives on the frame
side; this tab shows the image (the instruction set), and frame state /
classification lives on the App-db tab.

## §5 Tab registration

A **Dynamic L4 tab** registered via `panel-registry/reg-l4-tab!` in
`panels/module_view.cljs`'s `install!`: id `:module-view`, label
**"Frames"**, mnemonic `u`, order **9** (after the Derivation-Graph at 8,
keeping the cross-feature runtime-structure tabs adjacent).

Like the Derivation-Graph tab this is an **L4-only** surface — it exposes
no standalone `mount-*!` facade, so it is **not** in
[`panel_enum.cljc`](../src/day8/re_frame2_xray/panel_enum.cljc) (that enum
carries the *mountable* surface; an L4-only tab is shell-internal). The
Cmd-K palette picks it up automatically (the palette reads
`panel-registry/tabs-for-mode :dynamic`).

## §6 Data sources & privacy

- `:rf.xray/image-view` — the FRAMES/IMAGES composite (see §8.3). Reads the
  live image-loaded frames + each frame's sealed generation at recompute time.
  **Read-only** — enumerating image-loaded frames and reading sealed
  generations pins nothing and dispatches nothing.
- The surface carries frame/image **ids and structural descriptors** — frame
  ids, image ids, `[kind id]` pairs, provenance namespace strings / inline
  coordinates, and capability keywords. These are **structural descriptors**,
  not app-db values. No handler values or app-db data egress through this
  surface (frame STATE — app-db / runtime-db — is the App-db tab's concern;
  this tab shows the IMAGE, the instruction set). Should a future slice surface
  value-bearing metadata, the off-box egress posture — the
  [`025`](025-Derivation-Graph-Panel.md) §egress redaction pattern — would
  apply to it.

## §7 Implementation

- `panels/module_view.cljs` — the panel view + `install!` (the
  `:rf.xray/image-view` sub + the L4 tab). The view renders only the FRAMES /
  IMAGES section (§8); there is no realm or installed-app read.

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

**Scope (the production singleton runs in Xray's own image-loaded frame).** The
dogfood is LIVE on the default path: Xray builds its OWN real `rf/image` (a
separate registration-set value), the implementation **proves** it
registration-disjoint from a target frame's image, AND
`image_view_reads/seat-xray-frame!` **SEATS a running Xray frame built from that
image** via `rf/make-frame {:id … :images [(xray-image)]}` — true runtime
self-seating in genuine registration ISOLATION (the seated frame resolves ONLY
Xray's `:rf.xray/*` registrations plus the framework standards the assembly
unions into every generation, not the shared process registrar). This is the
literal `(rf/make-frame {:id … :images [(xray-image)] …})` shape the EP names,
and the production-singleton mount path (`mount/ensure-xray-frame!`) calls it for
the `:rf/xray` frame.

The seating preserves the `:rf.trace/frame-no-emit?` gate that keeps
Xray's own reactivity out of the trace ring it inspects: `make-frame` is the
EP-0023 OBJECT constructor and honours only the frame-creation opts (`:images` /
`:id` / `:initial-events` / …), rejecting the record-config flag, so the gate is set
directly through `re-frame.trace/set-frame-no-emit!` (the same canonical seam
`reg-frame` routed it through) — asserted on every seat / re-seat. The seating is
idempotent: `make-frame {:id …}` on a duplicate live `:id` is idempotent
replacement (EP-0024, rf2-tu2vr7), so a re-open / hot-reload / repeated testbed
mount finds the frame already live (`xray-frame-seated?`) and skips the
re-create, re-asserting only the gate.

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

**Host-registry reads under image-loaded seating (the second flip blocker, now
fixed).** Flipping the singleton onto `seat-xray-frame!` surfaced a second blocker
beyond the dup-id: when a `:rf.xray/*` subscription RECOMPUTES, the framework
binds registrar resolution to the seated frame's sealed image generation for the
extent of the sub build (`re-frame.registrar/*generation*`, bound by
`re-frame.live-frame/call-with-frame-resolution` around every subscribe targeting
an image-loaded frame). So a bare `(rf/registrations :route)` /
`(rf/handler-meta :event id)` *inside a sub computation* resolved through Xray's
OWN image — which selects only Xray's `:rf.xray/*` namespaces, NOT the host app's
registrations. The inspector then saw only its own handlers / routes / resources:
the Routing panel rendered `currentId:null` with an empty route table (the
`routes-epochs` nightly xray-feature-gate scenario), the palette listed only
Xray's own handlers, the Static/Resources/Machines/Epoch panels lost the host
registry. The node-test suite did NOT catch it (a frame-only node-fixture path;
the failure is browser-only). This is correct framework behaviour (a frame
resolves its own image) and exactly the wrong thing for an INSPECTOR, which reads
the registry of the INSPECTED app — the **process-global registrar** — not its
own image's resolver. The fix is the `day8.re-frame2-xray.host-registry` helper:
it reads the process-global registrar atom
(`re-frame.registrar/kind->id->metadata`) directly, BYPASSING any bound
`*generation*`. (A registrar-query map is ALWAYS a frame-targeted read — there is
no realm-scoped query spelling — so the generation-bypass home is the direct
registrar-atom read.) Every Xray host-registry read that happens inside a sub
computation routes through it; view-time reads (no generation bound) are
unaffected. With this in place the production singleton ships flipped and the
`routes-epochs` gate is green.

Xray therefore models its registration set as a **separate image**, NOT as
shared registration state:

- `image_view_reads/xray-image` constructs Xray's OWN EP-0023 `rf/image` — an
  inert value selecting Xray's own source namespaces (`:select-ns {:include
  ["day8.re-frame2-xray.**"]`, under which every `:rf.xray/*` registration is
  authored and stamped `:rf.provenance/ns`, which survives production elision),
  NARROWED by `:exclude ["day8.re-frame2-xray.**.*-cljs-test"
  "day8.re-frame2-xray.test-helpers.**"]}` so Xray's own test + test-support
  namespaces are subtracted (rf2-rjml45 — they co-register the production ids in
  a dev/test build; the exclude keeps the production image collision-free).
  (EP-0026, rf2-dlvmpc: the sibling `:include-ns` / `:exclude-ns` keys were
  consolidated into the single `:select-ns {:include … :exclude …}` map.)
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

The EP-0023 sections are demand-gated. EP-0023's public model is OPT-IN: a
process that never calls `rf/make-frame` with `:images` has no image-loaded
frames (no `frames` record carries a `:generation` — EP-0024, rf2-tu2vr7), so
`project-image-view` reports `:images?` false and the FRAMES section renders
the calm **no-image caption** (`image_view_helpers/no-images-caption`) naming
the `rf/image` / `rf/make-frame` remedy — the honest *not-using-images-yet*
state, not a broken surface. A frame whose generation resolves zero descriptors
does not flip `:images?` (there is no image content to show).

### §8.3 Data sources & privacy

- `:rf.xray/image-view` — the FRAMES/IMAGES composite; reads the image-loaded
  frames (`re-frame.live-frame/image-view-frames` — the EP-0024 one-registry
  read that projects each `frames` record carrying a `:generation` into an inert
  frame view) + each frame's sealed generation
  (`re-frame.image-assembly/resolve-descriptor`) at recompute time via the
  fail-soft `image_view_reads` seam, and projects via the pure
  `image_view_helpers/project-image-view`. **Read-only** — enumerating
  image-loaded frames and reading sealed generations pins nothing and dispatches
  nothing (a sealed generation is an immutable VALUE, not a routing path).
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
  (`live-frames` — over `re-frame.live-frame/image-view-frames`, the EP-0024
  one-registry read · `resolve-descriptor` · `image-view-data`) over the core
  surfaces (`re-frame.live-frame` / `re-frame.image` /
  `re-frame.image-assembly`), PLUS the Xray-as-its-own-image constructor +
  SEATING (`xray-image` · `xray-image-id` · `xray-source-glob` ·
  `xray-exclude-globs` · `resolver-keyset` · `application-resolver-keyset` ·
  `xray-image-isolated-from?` · `xray-frame-seated?` · `seat-xray-frame!`).
  `xray-image` declares `:select-ns {:include [xray-source-glob] :exclude
  xray-exclude-globs}` (`:exclude` = `["day8.re-frame2-xray.**.*-cljs-test"
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
  trace gate, guarded by `xray-frame-seated?` (an image-loaded-frame probe — the
  frame's `frames` record carries a `:generation`) for idempotency on re-seat.
  EP-0024 (rf2-tu2vr7): a duplicate `:id` is now idempotent replacement, so the
  probe is a benign skip-optimisation rather than a fail-loud guard. Xray may
  require these core
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
- `host_registry.cljs` — the host-app registry read seam that survives Xray
  running in its OWN image-loaded frame (`registrations` · `handler-meta`). Reads
  the process-global registrar atom (`re-frame.registrar/kind->id->metadata`)
  directly, BYPASSING any bound `re-frame.registrar/*generation*`. (A
  registrar-query map is ALWAYS a frame-targeted read — there is no realm-scoped
  query spelling — so the generation-bypass home is the direct registrar-atom
  read.) Every Xray host-registry read INSIDE a sub
  computation (the Routing route table, the palette handler index, the
  Static Flows / Interceptors / Schemas registries, the Resources registry, the
  Machine Inspector machine list + definitions, the Epoch pipeline's INTERCEPTORS
  + RECORDABLE COEFFECTS resolvers) routes through it — without it those reads
  resolve through Xray's OWN image generation (no host registrations) when the
  singleton is image-loaded. Fail-soft: a throw degrades to the bare
  `(rf/registrations kind)` / `(rf/handler-meta kind id)` facade reads.
- `mount.cljs/ensure-xray-frame!` — seats the production singleton in its OWN
  image-loaded frame via `image_view_reads/seat-xray-frame!` (`seat-xray-frame!
  :rf/xray` → `rf/make-frame {:id :rf/xray :images [(xray-image)]}`). The dup-id
  blocker is resolved by the `:exclude-ns` selector on `xray-image` (§8.1), and
  the host-registry read regression under image-loaded seating is resolved by
  `host_registry.cljs` (the generation-bypassing process-registrar reads above);
  both node-test and the `routes-epochs` nightly xray-feature-gate are green with
  the flip. The runtime-reset test fixture
  (`re-frame.test-support/make-reset-runtime-fixture`) clears the ONE
  `frame/frames` registry (EP-0024, rf2-tu2vr7 — the live-frame registry
  dissolved into it; an image-loaded frame is a record carrying a `:generation`),
  so a frame seated via `make-frame {:id …}` in one test does not leak into the
  next.
- `panels/module_view.cljs` — renders the FRAMES section (`frame-row` ·
  `descriptor-rows` · `frames-section-body`) + registers the `:rf.xray/image-view`
  sub.
- Tests: `panels/image_view_helpers_cljs_test.cljc` (the generation/image
  projection, the frame-row shape, the frame-derived resolution — same id /
  different image → different descriptor, the demand-gated `:images?`
  decision, the display strings) + `panels/image_view_reads_cljs_test.cljs`
  (the Xray-as-its-own-image isolation proven on assembled resolver KEYSETS —
  bidirectional disjointness, the constructed overlapping-glob case the string
  proxy would miss, and the negative leaky-image case — plus the fail-soft
  live-read seam end-to-end and frame-derived resolution through a real
  generation).
