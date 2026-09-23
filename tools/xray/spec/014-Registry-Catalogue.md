# 014-Registry-Catalogue

The contributor-facing ownership and naming reference for Xray's
subscriptions, events, and effects. The complete executable membership
contract is the exact `all-sub-names`, `all-event-names`, and
`all-fx-names` sets in
[`registry_cljs_test.cljs`](../test/day8/re_frame2_xray/registry_cljs_test.cljs),
checked against the installed registry. The grouped tables below explain
the principal seams; they are not a standalone exhaustive inventory.
Panel-internal registrations are not automatically public host APIs —
that boundary is defined by [`API.md`](./API.md).

Rows explicitly described as test overrides require the panel's
`install-test-overrides!` fixture path; they are not installed by the
production `install!` path (rf2-e8330v).

> **rf2-yhl5v follow-up (2026-06-02).** The §Time-travel scrubber
> subsection has been reconciled to the surviving live surface: the
> panel-private pin store, label-input, and confirmed-rewind-failure
> rows that died with the deleted panel (`:rf.xray/last-restore-failure`,
> `:rf.xray/restore-epoch-tick`, `:rf.xray/pin-store`,
> `:rf.xray/pinned-snapshots`, `:rf.xray/time-travel-label-input`,
> `:rf.xray/clear-selected-epoch`, `:rf.xray/pin-current`,
> `:rf.xray/unpin`, `:rf.xray/rename-pin`,
> `:rf.xray/dismiss-pin-overflow-toast`, `:rf.xray/bump-restore-epoch-tick`,
> `:rf.xray/reset-to-pinned`, `:rf.xray/time-travel-set-label-input`, and
> the `:rf.xray.fx/reset-frame-db!` effect) were removed. The
> cross-cutting epoch primitives that outlived the panel
> (`:rf.xray/select-epoch`, `:rf.xray/reset-to-epoch`,
> `:rf.xray/set-target-frame`, `:rf.xray/sync-epoch-history`,
> `:rf.xray/selected-epoch-record`, `:rf.xray.fx/restore-epoch`) remain,
> matched against `registry_cljs_test`'s enumerations. The subsection is
> now normative again.
>
> **rf2-ee38b.2 follow-up (2026-05-23).** The dead `:rf.xray/selected-
> dispatch-id` / `:rf.xray/selected-dispatch-frame` shim subs were
> deleted (zero production consumers; the Epoch panel reads focus off the
> spine `:rf.xray/focus`). Their rows are removed from what is now
> §Cross-panel focused-cascade primitives below.
>
> **rf2-y8doi.41 (2026-09-18).** Every row in a table below was checked
> against `registry_cljs_test`'s `all-sub-names` / `all-event-names` /
> `all-fx-names` and override sets. The dead-panel sections were deleted
> (§Hydration debugger stays as an anchor-preserving tombstone), and
> §Machine inspector and §Static mode were regenerated from the `reg-*`
> forms. This is still not an exhaustive inventory; the exact-set test
> is the complete membership.

The thesis: per [`spec/Conventions.md` §Library-owned prefixes](../../../spec/Conventions.md#library-owned-prefixes)
and [`008-Embedding-Contract.md` §Registry-key isolation via `:rf.xray/*` prefix](./008-Embedding-Contract.md#registry-key-isolation-via-rfxray-prefix),
Xray namespaces every registrar id under `:rf.xray/*` to keep
process-global collisions impossible. The prefix is the contract;
this doc enumerates what sits inside it.

## Naming convention

| Prefix | Used for |
|---|---|
| `:rf.xray/<id>` | Every subscription, every cofx, and every cross-panel event (consumed from ≥2 panels) or shared-infrastructure event (trace-buffer pump, epoch-history pump, etc.). |
| `:rf.xray.<panel>/<id>` | Panel-owned registrations, for example `:rf.xray.static.routes/set-query`. The namespace identifies ownership, not a prohibition on a coordinating shell or palette dispatching the event. |
| `:rf.xray.fx/<id>` | Cross-panel effects (fx). Panel-owned fx follow the `:rf.xray.<panel>/<id>` row above (`:rf.xray.static/persist-mode`, `:rf.xray.palette.fx/persist-recents`, …), so `:rf.xray.fx/` is NOT a complete fx discriminator — `registry_cljs_test`'s `all-fx-names` is. |

Xray MUST NOT register a handler under any non-`:rf.xray*/` keyword.
A host registering `:user/login` and Xray registering
`:rf.xray/select-tab` cannot stamp on each other; the prefix is the
collision-avoidance contract enforced by code review and the registry
namespace docstring.

The catalogue below groups registrations by **owning panel** (per
[`007-UX-IA.md`](./007-UX-IA.md) §The 4-layer chrome, which carries the
tab inventory). Where a registration is shared across panels (e.g.
`:rf.xray/select-dispatch-id`, which the cancellation-cascade surface
dispatches and which writes the spine focus every Dynamic panel reads),
it appears once under its primary owner with cross-panel use noted.

Cross-panel infrastructure (the trace-buffer sub, the target-frame
sub, the epoch-history pump) is enumerated under
[§Shared infrastructure](#shared-infrastructure).

## Idempotency

Every registration is installed inside a `compare-and-set!` idempotency
gate (`register-xray-handlers!`) so shadow-cljs `:after-load` reloads
do NOT re-register. Tests MAY use `reset-for-test!` to drop the
sentinel and drive multiple registration cycles. Production code MUST
NOT call `reset-for-test!`. Per-panel `install!` helpers run inside
the same gate so panel-owned registrations inherit idempotency
without re-doing the dance.

### Schema version + live-upgrade migration seam (rf2-ykaq4u)

The `compare-and-set!` boolean gate handles the ONE-TIME bulk install,
but a boolean alone cannot express *"this process registered under OLD
code and is now running NEW code that adds a handler the old install
never ran"*. On `:after-load` the umbrella gate no-ops the whole leaf
install, so a handler introduced by newer code — kept inside a per-panel
`install!` — never reaches the already-registered process; it stays
behind until a full page reload. The Views panel's view-evidence bridge
stranded exactly this way in a pre-#5915 → current live upgrade: the
umbrella was set, so the reloaded preload's `register-xray-handlers!`
no-op'd, leaving the process's cached evidence sub at its old topology and
the bridge's newer registrations uninstalled — so a change the bridge was
supposed to make reactive could not invalidate a held Views subscription
until an unrelated epoch pump.

The fix is a SECOND axis alongside the boolean gate — a
`schema-version` integer plus an `installed-schema` `defonce` stamp:

- A **fresh boot** runs the whole leaf install (the current bridge
  included) and stamps `installed-schema` CURRENT.
- The **migration seam** runs INDEPENDENTLY of the umbrella gate: when
  `installed-schema` is behind `schema-version` (nil ⇒ a
  pre-schema-versioning process reads as schema 0), `migrate-schema!`
  installs EXACTLY the bounded delta of handlers each newer version adds,
  then stamps CURRENT. Each clause is additive, idempotent (re-frame's
  registrar replaces in place), and gated on the process predating the
  version that introduced its handler — so this is **NOT** an
  unconditional whole-registry re-registration, only the missing delta,
  and only for a process that is behind.
- Once at `schema-version` (a fresh boot, or a completed upgrade) the
  seam no-ops, so a current process re-loading itself emits no
  handler-replaced flood and installs exactly one of each singleton sink.

Bump `schema-version` and pair it with a `migrate-schema!` clause
whenever a gated registration change would otherwise strand an
already-registered older process behind a page reload — an ADD (a handler
newer code registers that the old install never ran) **or** a REPLACE (a
gated registration whose body/topology newer code changes; the umbrella
no-ops it because the id is unchanged, and the name-set snapshots don't see
it because the id is unchanged — rf2-sa8j3). A migration clause is idempotent
because re-frame's registrar replaces each handler in place, and replacing a
sub also **evicts its stale cache** via the registrar replacement-hook, so a
held subscription rebuilds against the new body without a reload. Versions `1`
and `2` were the donor view-evidence bridge and its later consumption subs;
rf2-7gth0 deleted both along with the donor ownership plane they published, so
neither carries a migration clause any more — their history entries stay so the
numbering remains a record rather than a sequence that renumbers itself.
Version `3` (rf2-sa8j3) is the first REPLACEMENT delta — the
`:rf.xray/reactive-data` sub grew from two inputs to four (the panel-local
`:rf.xray/reactive-show-unchanged?` quick-toggle + the Settings
show-unchanged pin), so the clause re-runs the owning `reactive-panel`
facade's `install!` to re-register it (evicting the stale two-input
reaction). Version `4` (rf2-7gth0) is the Freehand tool-door cutover, and the first
delta with a REMOVAL in it — five donor-era registrations cleared: the subs
`:rf.xray/viewcell-evidence`, `:rf.xray/viewcell-evidence-version`,
`:rf.xray/view-evidence-sites` and `:rf.xray/viewcell-evidence-ownership`,
and the event `:rf.xray/viewcell-evidence-ownership-changed`. A removal needs
its own clause for the mirror image of the reason a replacement does: the fn
that installed those ids is deleted, so no re-registration happens and the
umbrella has nothing to compare against — left alone they resolve forever as
phantom ids backed by a deleted namespace's resident closures. Version `5`
(rf2-hic-023) is the Fresco evidence tab: three new registrations and one L4
tab entry, all inside the gated `fresco/install!` the umbrella no-ops.
Version `6` (rf2-l86mm) is a REMOVAL-ONLY delta and the exact mirror of
version 4's clear — the three sub ids version 4 ADDED
(`:rf.xray/mounted-views`, `:rf.xray/mounted-views-schema`,
`:rf.xray/mounted-view-sites`) are cleared, because the Views panel's Mounted
Views + Declared View Sites sections retired with the Freehand substrate
rather than migrating to Fresco (`021-Dynamic-Panel-Designs.md` §3.4.1).
Version 4's clause no longer installs them; a process crossing 3 → 6 in one
step never acquires an id it would immediately have to be relieved of, and
clearing an id a process never registered is inert, which is what lets the
version-6 clause run for every behind process rather than branching on how far
behind. Version `7` (rf2-2qtgt) adds ONE event,
`:rf.xray/focus-after-frame`, inside the gated `focus/install!` — the
continuation `focus!`'s async path queues behind a frame step; a pure
addition, so re-running `focus/install!` is the whole delta.
`migrate-schema!` reaches each delta through the facade the
orchestrator already requires (the idempotent re-install applies only the
missing/changed delta).

Tests drive the upgrade via `registry/simulate-legacy-registration!`
(test-only: poses the umbrella-set / no-schema-stamp sentinels of a pre-#5915
process, i.e. schema 0) or `registry/simulate-registration-at-schema!`
(test-only: poses an INTERMEDIATE installed schema so a fixture can isolate
the newest migration clause); `reset-for-test!` clears both the boolean gate
and the schema stamp. `schema-version` is a **public read-only** contract
number: the governance pin
`schema-version-is-pinned-so-changed-registrations-name-a-migration` in
`registry_cljs_test.cljs` reads it and the shipped reactive-data topology, so
any gated registration edit — add or replace — must consciously bump
`schema-version` (or record an explicit no-migration rationale) and update
the pin.

#### Schema 4 is reload-required for a donor-resident process (rf2-7gth0)

The seam migrates REGISTRATIONS, and registrations are the only thing a live
upgrade can migrate. Schema 4 is the first version whose delta also includes
state that is **not** a registration, so it is the first version that can
fail to complete — and the seam reports that instead of hiding it.

A schema-3 process claimed the donor `re-frame.ui.tool.evidence` projection
at boot under the owner id `:rf.xray/viewcell-evidence`. Releasing it means
calling that tier's `uninstall!`, which schema-4 code cannot do: `re-frame.ui`
is off `tools/xray/deps.edn` and must stay off it, because dropping that
coordinate is the cutover. Re-adding it to run a teardown would reinstate the
donor coupling this version exists to remove, for a tier the donor-deletion
chain removes outright — the teardown would be dead code the day it shipped.
Deleting a namespace from source does not execute teardown in a process that
already loaded it, so the projection stays owned, its sink stays armed, its
entries stay retained, and another tool is refused the slot.

So the clause splits:

- **The registrar half always migrates.** The five donor-era ids are cleared,
  live, exactly like any other delta. (It also installed the three Freehand
  reads until version 6 removed them — see above.) A process the developer
  does not reload is left as current as a live process can be.
- **The ownership half is reload-only, and is declared so.** When the process
  carries the donor claim, `migrate-schema!` returns false: the process is
  **not** stamped current, and one `console.warn` names the owner id still
  holding the donor slot and the reload that ends it. `register-xray-handlers!`
  stamps `installed-schema` only on a true return, so a live upgrade can never
  assert an upgrade that did not finish. A subsequent `:after-load` repeats
  both the refusal and the warning; only a page reload — which discards the
  whole module registry, donor state included — clears the condition.

The probe is Xray's own `js/globalThis` marker
(`__day8_re_frame2_xray_viewcell_evidence`), which the deleted
`viewcell_evidence.cljs` wrote on a successful acquire and removed on
release. Reading it needs no donor dependency, which is exactly why it is the
probe — the tier's own `installed-owner` is not askable from an artefact that
does not depend on it. A process that booted at schema 4, and an older
process whose acquire was rejected by a foreign owner, both read false and
upgrade to completion. `reset-for-test!` clears the marker along with the
sentinels, since no registrar rollback or `defonce` reset reaches a
`globalThis` key.

`registry/installed-schema-version` is the read-only diagnostic counterpart to
`schema-version`: equal means current, behind means a reload is outstanding.
Regressions: `schema-4-migrates-the-registrar-half-of-the-donor-cutover-rf2-7gth0`
(the completing path) and
`schema-4-refuses-to-stamp-a-donor-resident-process-rf2-7gth0` (the
reload-required path — registrar half migrated, stamp withheld, warning
emitted, refusal stable across a second `:after-load`, and current the instant
the residue goes).

#### Fresh-install atomicity (rf2-g2jf3)

The umbrella flips to `true` BEFORE the bulk leaf install and stamps
`installed-schema` only AFTER the last installer. So the bulk block is
wrapped: if any registrar / leaf installer throws mid-install, the guard
rolls the umbrella back to `false` and rethrows, leaving the fresh install
**retryable** (the next `register-xray-handlers!` replays every installer;
partial registrar writes are replaced in place) and `installed-schema`
**unstamped** — the migration seam can never mistake a failed fresh install
for a live-upgrade delta and stamp the registry "current" over a partial
install. `registered?`/`installed-schema` are published current only after
every installer succeeds. Regression:
`fresh-install-retryable-after-partial-failure-rf2-g2jf3` (a throw-once
mid-list leaf; a second call must replay the whole bulk, not run a
bridge-only migration).

### Production registration carries no test seams (rf2-e8330v / xxo3zz F3)

`register-xray-handlers!` (and the per-panel `install!` helpers it
calls) registers **no id ending in `-for-test`** and **no `*-override`
reader sub**. The production data subs read their live source directly —
they carry no `(or override …)` branch. This keeps the test-only
override surface off the public dispatch / subscribe contract.

The override seam is split out per panel as
`<panel-ns>/install-test-overrides!`, orchestrated by the test-only
`day8.re-frame2-xray.test-support/install-test-overrides!`. Each per-
panel `install-test-overrides!`:

1. registers the panel's `:rf.xray*/set-*-override-for-test` events +
   companion `*-override` subs (plus the Machine Inspector's
   `:rf.xray/set-epoch-history-for-test` /
   `:rf.xray/set-focus-epoch-id-for-test` SEEDING events, which write
   the real `:epoch-history` / `:focus` slots), and
2. RE-registers the affected production data subs to layer the override
   read on top (`(or override (real …))`) — re-frame's registrar
   replaces in place.

A test (or a feature-gate dev testbed that injects synthetic state via
those events) opts in by calling `install-test-overrides!` AFTER
`register-xray-handlers!`. The production-vs-seam split is asserted by
`registry_cljs_test.cljs`:
`production-registration-installs-no-for-test-ids` (no `-for-test`
events, no `*-override` subs after production registration) and
`test-seam-installs-exactly-the-override-surface` (the seam installs
exactly the `test-override-sub-names` / `test-override-event-names`
snapshot). The shared value-source `defn`s the production sub and the
seam's override sub both call (e.g. `routing/registered-routes-value`)
keep the projection logic single-sourced.

## Shared infrastructure

Subscriptions and events the entire panel set composes against. These
registrations have no single owning panel; they back the trace bus,
the time-travel scrubber, and the per-frame target selection.

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.xray/trace-buffer` | reads `(get db :trace-buffer [])` (populated by the coalesced task mirror sync) | Vector of `:rf/trace-event` records, oldest-first (per [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) §Consumer contract) — the merged snapshot across every registered host frame's ring + Xray's frameless secondary ring. | Layer-1 sub re-fires on every app-db write to `:trace-buffer`. The slot is populated by `trace-collector/refresh-trace-rings!` — production drives via TASK-coalesced mirror sync (one dispatch per scheduled task regardless of trace volume — the scheduling primitive is `re-frame.interop/next-tick`, which is explicitly never a host microtask); tests drive synchronously via the same entrypoint (per the rf2-3g9nw D3=b ruling). Per rf2-43koh — supersedes the rf2-e9s81 atom-thunk fall-through; the framework's per-frame rings own the data plane now. |
| `:rf.xray/suppressed-sensitive-count` | `db` (reads `:suppressed-counters`) | Integer — total suppressed `:sensitive? true` events under the current local-render egress profile (`:rf.xray/egress-profile`). | On `db` write to `:suppressed-counters` (rf2-0vxdn — reactive immediate update of the `[● REDACTED N]` L1 chrome-ribbon indicator). |
| `:rf.xray/target-frame` | `db` | Keyword frame-id, or **`nil` = UNSELECTED**, which is the default (`defaults/default-target-frame`). EP-0002 removed the `:rf/default` fallback: `:rf/default` is an ordinary frame id, never an absence-repair default, so a host MUST NOT read a nil here as "the default frame". A target leaves UNSELECTED only by explicit choice or by unique resolution from observed evidence — the rule, and the paths that satisfy it, are owned by `defaults/default-target-frame`'s docstring; [`API.md`](./API.md) §Public CLJS API carries the `init! {:target-frame …}` opt. | On `db` write to `:target-frame`. |
| `:rf.xray/epoch-history` | `db` | Vector of `:rf/epoch-record`, oldest-first (cached snapshot of `(rf/epoch-history target)`). | On `:rf.xray/epoch-recorded` dispatch. |
| `:rf.xray/target-frame-db` | `:rf.xray/observed-frame`, `:rf.xray/epoch-history` | The observed frame's current `app-db` value (via `rf/app-db-value`). `:rf.xray/observed-frame` is the focus's `:frame`, else `:rf.xray/target-frame` — so it is `nil` only when the focus has resolved no `:frame` AND the target is UNSELECTED (the cold start). A focus that has resolved a frame is observed while the target is still UNSELECTED, so the observed coordinate is not the selected scope. | Every settled epoch on the observed frame. |
| `:rf.xray/target-frame-runtime-db` | `:rf.xray/observed-frame`, `:rf.xray/epoch-history` | The observed frame's live **runtime-db** partition (`:rf.db/runtime` of `rf/frame-state-value`) — machine snapshots, the route slice, the spawn registry (EP-0001 rf2-vzld77). The app-db sibling above carries none of this, which is why the Machine inspector and Routing read from here. | Every settled epoch on the observed frame. |
| `:rf.xray/event-bundles` | `:rf.xray/trace-buffer` | Vector of grouped cascade entries (per `projection/group-by-event`). Shared substrate for any panel that needs the cascade grouping without re-projecting (`:rf.xray/focused-event-bundle-detail`, etc. declare the dep via `:inputs` so the projection runs once per buffer change). | On `:rf.xray/trace-buffer` recompute. |

### Events

| Event | Vector shape | Returns | Notes |
|---|---|---|---|
| `:rf.xray/epoch-recorded` | `[_ frame-id]` | `{:db ...}` | Pumped from the epoch-cb registered in `install.cljs` (re-exported by `preload.cljs`), **task-coalesced to one dispatch per distinct frame per tick** rather than one per settled epoch (rf2-chs7). Re-reads `rf/epoch-history` to keep the cached snapshot consistent — a trigger, not a payload, so a coalesced burst writes the same snapshot the last un-coalesced dispatch would have. No-ops when `frame-id` ≠ the current target. |
| `:rf.xray/note-sensitive-suppressed` | `[_ counts]` | `{:db ...}` | rf2-0vxdn — adds `counts`, a `{frame-id → n}` map (`:global` for frameless events), into `[:suppressed-counters]` in Xray's app-db. Dispatched at most once per task by `config/note-suppressed!`'s coalesced drain (CLJS, rf2-p03xh) when the privacy gate drops `:sensitive? true` events. Drives the `:rf.xray/suppressed-sensitive-count` sub reactively. |
| `:rf.xray/reset-suppressed-counters` | `[_]` or `[_ frame-id]` | `{:db ...}` | rf2-0vxdn — clears all buckets (no arg) or just the named bucket. Dispatched from `trace-collector/retroactive-scrub!` (CLJS) — the wholesale clear (privacy toggle-off, Settings clear, palette clear) drops the `[● REDACTED N]` indicator state alongside the rings. |
| `:rf.xray/clear-trace-buffer` | `[_]` | `{:db ...}` | `:rf.trace/no-emit? true`. Drops the `:trace-buffer` slot. Dispatched from `trace-collector/retroactive-scrub!` (CLJS) when the user clears the rings or the privacy gate transitions true → false. |
| `:rf.xray/sync-trace-buffer` | `[_ buffer]` | `{:db ...}` | `:rf.trace/no-emit? true`. Wholly replaces the `:trace-buffer` slot with the supplied buffer vector. Dispatched from `trace-collector/refresh-trace-rings!` — the task-coalesced production path snapshots the framework's per-frame rings + Xray's frameless secondary ring on every tick; tests drive the same entrypoint synchronously for deterministic ordering. |
| `:rf.xray/open-in-editor` | `[_ payload]` | `{:fx [...]}` | Source-coord jump. Owned by `open_in_editor.cljs` (relocated here from the retired §Hydration debugger section, which was its only catalogue row). **Its only dispatch sites are the two shared affordances `panels.shared.coord-chip` and `panels.shared.coord-link`**, so the panels that require those are the roster — Trace, Epoch and Reactive today. Both dispatch the wrapper `{:source-coord coord}`, where `coord` is a structured map or a `"file:line"` display string. The handler unwraps that wrapper and then also accepts a bare structured map or a bare `"file:line"` string: the bare map was the deleted hydration-debugger panel's shape, and although nothing dispatches it bare today, the branch stays live as the unwrapped tail of the wrapper path. **Returns no `:db`** — the click is pure navigation, not a state transition (Spec 002 §Effect map shape), so Xray's app-db is untouched while the dispatch still lands in the trace bus as an observable operation. Coerces the payload, then emits `:rf.xray.fx/open-in-editor` with the structured `:source-coord` (rf2-wn3bh) — **always**, even for an unresolvable coord, so the fx stays the single instrumentable seam. When no editor is effectively configured (`config/editor-configured?` false) it dispatches `:rf.xray/editor-hint-show` instead, rather than navigating a `vscode:` URI the OS may silently drop (rf2-4s08ov / rf2-ffijtp). Pre-rf2-g5q8d this was a stub that only recorded the coord and never opened an editor. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/open-in-editor` | `{:source-coord …}` or `{:uri …}` | The single side-effecting seam for the editor jump. Prefers the structured `:source-coord` (resolved through the dev-server endpoint via `open-coord!`); a caller holding a pre-resolved URI — e.g. an MCP-side open-uri replay — may pass `:uri` instead and it navigates directly. |

### Callback identifiers

Not subs/events/fxs — these are the keyword ids Xray registers
with the framework's instrumentation surfaces in `preload.cljs`.
They live under the `:rf.xray/*` namespace for the same isolation
discipline as the rest of the registry.

| Id | Surface | Behaviour |
|---|---|---|
| `:rf.xray/trace-collector` | `(rf/register-listener! :trace …)` | Xray's trace consumer listener. Drops self-noise (`:frame :rf/xray`), applies the privacy gate, pushes frameless events into Xray's secondary ring, and requests a coalesced task sync into `:rf/xray`'s `:trace-buffer` slot — the framework's per-frame rings own the frame-bound data plane (per rf2-43koh). Idempotent per preload installation. |
| `:rf.xray/epoch-collector` | `(rf/register-listener! :epoch …)` | Xray's epoch-settle pump. Notes the settled epoch's frame and requests a task-coalesced drain, which dispatches `:rf.xray/epoch-recorded` once per distinct frame per tick (rf2-chs7 — the per-settle form overflowed `:rf/xray`'s own queue past the router's depth cap under load), so the cached `:rf.xray/epoch-history` snapshot stays consistent with `(rf/epoch-history target)`. Short-circuits when Xray is not mounted. |

Both collectors attach through the `rf/register-listener!` facade, like any other listener. What keeps them out of a production build is that they are registered from a `:devtools/preloads` entry inside the preload's `(when rf.interop/debug-enabled? …)` block — not the namespace the call names. For the `:epoch` collector the compile-time guarantee comes from `install.cljs`'s bare `[re-frame.epoch]` require plus the `day8/re-frame2-epoch` coordinate in `tools/xray/deps.edn`, which together load the producer on every startup path. See [Spec Tool-Pair §Facade vs home-namespace verb](../../../spec/Tool-Pair.md#facade-vs-home-verb-the-dce-tier-rule) (rf2-kuky.20).

## Cross-panel focused-cascade primitives (relocated from the retired event-detail panel · rf2-5gl5r; sub renamed off the retired-panel name · rf2-7ed9ms)

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §The default landing view.
rf2-5gl5r retired the Event/Handler panel in favour of the Epoch
panel ([`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
§9.1). The composite sub + spine-shim events listed below were
**relocated** from the deleted `panels/event_detail.cljs` to
`registry.cljs` as cross-panel primitives — multiple consumers
(the trace panel's status bar, machine-inspector, cancellation-cascade,
the unit-test corpus) read them and they outlived the panel that
originally owned them. (rf2-nugvv removed `share.cljs`'s cascade-export,
which was also a consumer.)

### Subscriptions

| Sub | Inputs | Returns | When recomputes |
|---|---|---|---|
| `:rf.xray/focused-event-bundle-detail` | `:rf.xray/event-bundles`, `:rf.xray/focus` | `{:event-bundles [...] :selected-dispatch-id ... :selected-dispatch-frame ... :selected-event-bundle ...}` — composite. **rf2-7ed9ms** renamed this sub from `:rf.xray/event-detail` (retired-panel vocabulary) to the behaviour name `:rf.xray/focused-event-bundle-detail`: it means "the focused cascade's detail record", not "the Event Detail panel's data". Derives the focused cascade off the spine `:rf.xray/focus` (rf2-ee38b.2 removed the standalone `:rf.xray/selected-dispatch-id` / `-frame` shim subs — focus is the single source of truth; the `:selected-dispatch-id`/`-frame` KEYS in this composite's return map remain live). `:selected-event-bundle` is `nil` when no selection OR when the id is no longer in the buffer. | Cascades or focus change. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-dispatch-id` | `[_ dispatch-id]` | Sets selection (writes through the spine via `spine/focus-event-bundle-reducer`). |
| `:rf.xray/clear-selected-dispatch-id` | `[_]` | Drops selection (resets spine focus to LIVE per rf2-s0s5x Phase A). |

## Time-travel scrubber

Spec: [`002-Time-Travel.md`](./002-Time-Travel.md).

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/selected-epoch-record` | `:rf/epoch-record` or `nil`. | Resolved from history + the spine focus epoch (`:rf.xray/focus-epoch-id`). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-epoch` | `[_ epoch-id]` | Passive scrub — does NOT call `restore-epoch`. Spine shim (rf2-adve5): stamps the spine `[:focus :epoch-id]` slot surfaced by `:rf.xray/focus-epoch-id`; a `nil` epoch-id clears the focus. |
| `:rf.xray/reset-to-epoch` | `[_ frame epoch-id]` | `event-fx` — emits `{:fx [[:rf.xray.fx/restore-epoch {:frame frame :epoch-id epoch-id}]]}`. The confirmed-rewind affordance (rf2-hga49); a nil frame / epoch-id is a guarded no-op. |
| `:rf.xray/reset-flash-failed` | `[_]` | `:rf.trace/no-emit? true`. Sets the inline `:reset-flash` failure notice; dispatched from `:rf.xray.fx/restore-epoch` when `rf/restore-epoch!` returns false. |
| `:rf.xray/set-target-frame` | `[_ frame-id]` | Sets the active target frame and refreshes `:epoch-history` from `(rf/epoch-history target)`. A **`nil` `frame-id` CLEARS the slot back to UNSELECTED** — it `dissoc`s `:target-frame` and symmetrically drops the focus slot's `:frame`; it does **not** substitute `:rf/default` (EP-0002 removed that fallback). Mirrored by `core/set-target-frame!` from the public CLJS API. |
| `:rf.xray/sync-epoch-history` | `[_ history]` | `:rf.trace/no-emit? true`. Replaces the cached `:epoch-history` with the supplied vector AND focuses the LATEST seeded epoch — stamps the spine `[:focus :epoch-id]` (surfaced by `compose-focus` when no live cascade head is present) to `(:epoch-id (peek history))`; an empty `history` clears it. Pumped from the depth-shrink path so the scrubber reflects the trimmed history without an explicit re-read, and from history-only seeds (the panel-gallery Story variants) where no trace buffer exists for the trace-driven auto-follow to act on — without the head-focus the focus-keyed Dynamic panels (App-db, Epoch, …) would render their "nothing focused" empty-state (rf2-mdpfz). When a live trace buffer IS also seeded, `compose-focus`'s LIVE auto-follow re-derives `:epoch-id` from the head cascade; this stamp is authoritative only for history-only seeds. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/restore-epoch` | `{:frame :epoch-id}` | Calls `rf/restore-epoch!`; on failure dispatches `:rf.xray/reset-flash-failed` so the inline tab-ribbon flash surfaces the failed confirmed rewind (the framework also emits a structured `:rf.epoch/*` trace row the Trace panel shows). The fx indirection lets test fixtures stub the framework call. |

## App-DB Diff panel

Spec: [`004-App-DB-Diff.md`](./004-App-DB-Diff.md).

Current-state `app-db` inspector (rf2-okvit). Reads the observed
frame's `app-db` via `rf/app-db-value` + the target-frame's
epoch-history; the body shows the focused epoch's `:db-after`
(per-epoch delta, rf2-02j4r) sectioned by reserved `:rf/*` area.

> **rf2-p53m2 — dead diff-sub family pruned.** The `:rf.xray/selected-
> epoch-diff` → `:rf.xray/app-db-diff` composite (plus its
> `:rf.xray/selected-epoch-flow-writes` /
> `:rf.xray/selected-epoch-redacted-modified-count` inputs) had no
> production view consumer and was removed. The Epoch panel's `:db`
> diff reads `:rf.xray/selected-epoch-record`; an out-of-process diff read
> projects directly through `diff.engine/project`. The catalogue
> rows are gone; this note is the audit trail.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/selected-epoch-record` | The focused epoch's `:rf/epoch-record` (the Epoch panel's `:db` diff source), or `nil`. |
| `:rf.xray/app-db-current+diff` | Atomic `{:value :before :epoch-id}` — the focused epoch's `:db-after` / `:db-before` / id (cold boot → `:value` falls back to the live db, `:before`/`:epoch-id` nil). The app-db tab's primary read-model (rf2-yng0y / rf2-02j4r). |
| `:rf.xray/app-db-state` | Current-state section model derived from `:rf.xray/app-db-current+diff` — TOP user-domain section + one section per reserved `:rf/*` area, with the focused epoch's `:db-before` threaded as the inline diff pre-image. |

### No panel-owned events

rf2-y8doi.29 (2026-09-17) deleted the segment-inspector popup, the
"Show me when this changed" sub/event/walker and the diff-flash, and
dropped `:path` from `focus!`; the path-click semantic is now the zoom
gesture ([`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
§10.5, [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)). The
subs `:rf.xray/segment-inspector-open?` / `-path` / `-value` /
`:rf.xray/focused-slice-path` /
`:rf.xray/show-me-when-this-changed-result` and the events
`:rf.xray/open-segment-inspector` / `:rf.xray/close-segment-inspector` /
`:rf.xray/focus-slice-path` / `:rf.xray/clear-slice-focus` went with
them. The tab registers no event of its own today
(`app_db_diff_events.cljs` installs only the `copy-to-clipboard` fx
below). Its body mounts the EDN-inspector widget, and the zoom gesture
runs through that widget's own `:rf.xray.edn-inspector/*` events
(`zoom-to` / `zoom-up` / `zoom-reset`, registered in
`views/edn_inspector.cljs`).

> **rf2-e9tb0 — pinned-slices removed.** The `:rf.xray/pin-slice`,
> `:rf.xray/unpin-slice`, `:rf.xray/reorder-pinned-slices` events
> and the `:rf.xray/pinned-slices-store` + `:rf.xray/pinned-slices`
> subs were dropped when the pinned-watches strip was superseded by
> the segment-inspector popup. Catalogued here for the audit trail.

> **rf2-6r9j.24 (2026-09-04) — the two clipboard copy events removed.**
> `:rf.xray/copy-value-to-clipboard` (`[_ value]`, which routed the
> value through `egress/egress-value` before the clipboard write) and
> `:rf.xray/copy-path-to-clipboard` (`[_ path]`, value-free and never
> elided) were dropped with the universal EDN-widget `⎘` affordance
> that was their only intended dispatcher. Neither had a dispatcher
> anywhere in `tools/xray/src`: the affordance's only call site went in
> the rf2-oqa60 phase-1 rebuild and was never re-wired, and the design
> lock at [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md)
> §10.5 (super-prompt B.9) puts copy-value and copy-path explicitly OUT
> on that renderer. The `:rf.xray.fx/copy-to-clipboard` fx below
> SURVIVES — Static Machines' `Copy Mermaid` is its reachable consumer.
> Catalogued here for the audit trail.

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.fx/copy-to-clipboard` | `{:text <string>}` | Best-effort write via `navigator.clipboard.writeText`. No-op on non-browser targets (Node test, JVM). |

## Hydration debugger

> **Tombstone — no registrations survive (2026-09-18).** The
> hydration-debugger panel went in the rf2-qy0nu 8-dead-panel sweep, and
> its subs `:rf.xray/selected-mismatch-id`,
> `:rf.xray/hydration-reroot-path`, `:rf.xray/hydration-has-mismatch?`
> and `:rf.xray/hydration-debugger-data`, plus the events
> `:rf.xray/select-mismatch`, `:rf.xray/clear-mismatch-selection` and
> `:rf.xray/reroot-tree-view`, are registered nowhere today — verified
> against `registry_cljs_test.cljs`'s `all-sub-names` / `all-event-names`
> sets. The design record is
> [`006-Hydration-Debugger.md`](./006-Hydration-Debugger.md), which is
> itself marked historical. **This heading is retained deliberately**:
> 006 links to this section by anchor, so deleting it would break an
> inbound link from a page outside this chapter's fence.
>
> `:rf.xray/open-in-editor` was catalogued here and is **live** — it has
> moved to [§Shared infrastructure](#shared-infrastructure), which is
> where its owner `open_in_editor.cljs` installs it.

## Views tab (incl. nested subs — replaces the pre-rewrite Subscriptions panel)

Spec: [`021-Dynamic-Panel-Designs.md` §3.2](./021-Dynamic-Panel-Designs.md#32-layout--dag-visualised-as-indented-cascade).
The current Views lens is a reactive flow graph with explicit unmounted,
destroyed, and unchanged disclosures. The old sub-cache selection and
chain modal registrations retired with the table-based layout.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/reactive-data` | Focused-epoch reactive projection consumed by `reactive_panel_view.cljs`: graph data, statuses, and the supporting disclosures. |
| `:rf.xray/reactive-show-unchanged?` | Whether the unchanged-subscription disclosure is shown. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/reactive-toggle-unchanged` | `[_]` | Toggles the unchanged disclosure in the owning Xray frame. |
| `:rf.xray/reactive-set-unchanged` | `[_ show?]` | Sets the unchanged disclosure. |

## Issues ribbon

Spec: [`000-Vision.md` L94](./000-Vision.md), [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md#error-namespace-convention--six-prefix-shapes).

The dedicated Issues tab and its filter controls were removed under
rf2-gbz39 Option (c). The focused-epoch projection survives for signal
consumers; errors remain visible in Epoch, Trace, and the L2 signal
surface. See [`016-Auxiliary-Panels.md` §Issues](./016-Auxiliary-Panels.md#issues--the-dedicated-tab-was-removed-rf2-gbz39-option-c).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/issues-ribbon` | Focused-epoch issue feed projected from `:rf.xray/focus` and `:rf.xray/epoch-history`; no independent issues-filter state. |

## Trace panel

Epoch-scoped raw-event ribbon (rf2-td380): reads the focused epoch
record's `:trace-events` (the complete domino trail for one event —
both the synchronous event-side rows AND the async nil-dispatch-id
reactive rows) via `:rf.xray/focus` + `:rf.xray/epoch-history`,
resolved through `panels.shared.focus-resolver`. No chip-filtering
(rf2-gkczt) and no top header row (rf2-o6yqq) — the focused epoch IS
the scope, and the per-row payload-expand affordance is the drill-down.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/trace-feed` | Composite over the focused epoch's `:trace-events` — `{:rows :total :rendered :epoch-id :empty-kind}`. `:empty-kind` ∈ `#{:no-focus :epoch-evicted :no-events nil}`. |
| `:rf.xray/trace-expanded-row-ids` | The set of trace-row ids whose inline payload is expanded (spec/021 §5.4). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/toggle-trace-row-expand` | `[_ row-id]` | Toggle a row's inline payload expansion membership. |
| `:rf.xray/clear-trace-expand` | `[_]` | Drop every expanded trace-row id. |

## Routes panel

Spec: [`spec/012-Routing.md`](../../../spec/012-Routing.md) (framework
substrate) + [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md)
§Dynamic Routing (Xray-side lens). The live Routing lens projects route
topology, the target runtime's current route, and frame-strict focused
event navigation activity. Search and Simulate-URL belong to Static
Routes, not this event-coupled lens.

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-routes` | `(rf/registrations {:source :store :kind :route})`. Test override via `:registered-routes-override`. |
| `:rf.xray/registered-routes-override` | Test override slot. |
| `:rf.xray/current-route-slice` | The current route from the target frame's routing runtime-db. |
| `:rf.xray/current-route-slice-override` | Test override slot. |
| `:rf.xray/routing-tab-data` | Topology-plus-navigation projection from `routing_helpers/project-topology-data`, using registered routes, current route, event bundles, and the complete focus map. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-registered-routes-override-for-test` | `[_ ov]` | Test-only override hook. |
| `:rf.xray/set-current-route-slice-override-for-test` | `[_ ov]` | Test-only slice override. |

## Resources panel

Spec: [`024-Resources-Panel.md`](./024-Resources-Panel.md) (Xray-side
lens) + [`spec/016-Resources.md`](../../../spec/016-Resources.md)
(framework substrate). Declarative-server-state lens: the static resource
registry, the live per-frame instance + work-ledger tables, the
route/resource graph, the lifecycle timeline, the invalidation graph, the
cache-growth view, and the scope audit. Read-only — the panel
registers NO `:rf.resource/*` event (observing pins no resource, Spec 016
§Active owners and causes). The panel reads registry and runtime-db data
without requiring `re-frame.resources` itself. This does not make the
Xray artifact dependency optional: its unified graph uses the Resources
artifact (see 024's dependency boundary).

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-resources` | `(rf/registrations {:source :store :kind :resource})` — the static registry. Test override via `:registered-resources-override`. |
| `:rf.xray/registered-scope-resolvers` | Registered resource scope resolvers used by the scope audit. |
| `:rf.xray/registered-resources-override` | Test override slot. |
| `:rf.xray/registered-scope-resolvers-override` | Test override slot. |
| `:rf.xray/resource-entries` | The live cache entries map from the target frame's runtime-db at `[:rf.runtime/resources :entries]`. Test override. |
| `:rf.xray/resource-entries-override` | Test override slot. |
| `:rf.xray/resource-work-ledger` | The live work-ledger map at `[:rf.runtime/work-ledger]`. Test override. |
| `:rf.xray/resource-work-ledger-override` | Test override slot. |
| `:rf.xray/resource-routing-slice` | The live routing-runtime subtree at `[:rf.runtime/routing]` (current route + nav-token + per-nav-token unsettled-blocking set) backing the live route/resource graph. Test override. |
| `:rf.xray/resource-routing-slice-override` | Test override slot. |
| `:rf.xray/resources-tab-data` | View-facing composite — `{:silent? :registry :instances :work :route-graph :timeline :invalidations :cache-growth :audit}` over the registry + entries + ledger + route registry + trace buffer + routing slice. The `:route-graph` joins the static route plan against the live instance/work rows + routing slice (per-resource freshness rollup; the active route flagged `:current?`). PRIVACY: every param/scope/data/cause/outcome value is summarized (never raw). |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-registered-resources-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-registered-scope-resolvers-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-entries-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-work-ledger-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |
| `:rf.xray/set-resource-routing-slice-override-for-test` | `[_ ov]` | Test-only override hook. `nil` clears. |

The Resources override seam is **five** events, not six: rf2-jd0bp
(2026-09-17) deleted the scope-mismatch lint outright, taking with it
both registrations of `:rf.xray/resource-sub-reads`, its `-override`
sub, `:rf.xray/set-resource-sub-reads-override-for-test` and the
`:mismatches` slot on `:rf.xray/resources-tab-data`'s `:audit` map.

### No tool accessors

The five read-only resource accessors that once sat on
the Xray runtime seam retired with that namespace (rf2-7htk7).
The panel projections above are Xray's whole resource surface; an
out-of-process reader uses `re-frame2-pair.runtime` +
`tools/re-frame2-pair-mcp/` against the framework's own registry and
runtime-db surfaces.

## Machine inspector

Spec: [`003-Machine-Inspector.md`](./003-Machine-Inspector.md). Reads
the `:rf/machine?`-filtered `:event` registrations, the live snapshots
off the target frame's **runtime-db** (see `:rf.xray/machine-snapshots`
below — not an app-db slot), and the trace-buffer's
`:rf.machine/transition` slice. Read-only over the target frame — the
Sim that *does* step a machine clones the definition and lives under
[§Static mode](#static-mode), never touching the production registry.
Since rf2-y9xmf the Dynamic panel is a **focused-event lens**: it binds
to the focused epoch's first transition record rather than to a picker,
which is why several ids below are named "for-focused-event" /
"for-focused-machine". (rf2-nugvv removed the panel's Share affordance.)

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/registered-machines` | Vector of machine-ids — `(rf/registrations {:source :store :kind :event})` filtered on `:rf/machine?`. Wrapped in `try` so future API changes collapse to `[]` rather than throwing. |
| `:rf.xray/machine-definitions` | `{machine-id meta}` — each registered machine's definition. Input: `:rf.xray/registered-machines`. |
| `:rf.xray/machine-snapshots` | `{machine-id <snapshot>}`, read from the target frame's **runtime-db at `[:rf.runtime/machines :snapshots]`** (EP-0001 runtime-db partition) — **not** from a `:rf/machines` app-db slot. Inputs: `:rf.xray/target-frame`, `:rf.xray/target-frame-runtime-db`. **EGRESS-REDACTED (rf2-kq8nac / EP-0005)**: the runtime-db value is live frame state, not a trace, so it has not passed the trace-path redactor. Each snapshot is therefore routed through the same `project-trace-event` chokepoint as a synthetic `:rf.machine/snapshot-updated` event stamped with the target frame — a frame-declared sensitive `:data` path lands as `:rf/redacted`, a large one as the size marker, plain siblings ride verbatim (EP-0025, rf2-398kql — `:data` classification is frame-owned). A frame declaring no matching path leaves the snapshot reference-identical. |
| `:rf.xray/selected-machine-id` | The picker slot, or `nil`. Kept post-collapse as the focus the Static Machines surfaces and `:rf.xray/cancellation-cascade-for-focused-machine` read; the Dynamic panel itself binds to the focused epoch, not to this slot. |
| `:rf.xray/machine-inspector-data` | Composite — `{:machines :total :selected-id :selected :chart-props :transitions :empty-kind}`. `:selected-id` is the *effective* selection: the picker slot when set, else the first row of an alphabetically-sorted list. |
| `:rf.xray/machine-transitions-for-focused-event` | Per-machine transition sections for the focused epoch, each carrying that epoch's fired-edge-ids (rf2-qeemm G3) so traversed chart arms paint. Inputs: `:rf.xray/focus`, `:rf.xray/epoch-history`, `:rf.xray/machine-definitions`. |
| `:rf.xray/machine-focused-epoch-cascade` | `{:cascade :event-id}` — the numbered machine-cascade rows for the focused epoch, projected by the SAME `machine-cascade-rows` the Epoch panel's HANDLER row uses, so the two surfaces agree byte-for-byte. |
| `:rf.xray/machine-scrubber-position` | `:present` or an integer index; defaults to `:present`. The scrubber UI itself went with rf2-y9xmf, but the slot survives because the `:after`-rings overlay gates ring rendering on it. |
| `:rf.xray/machine-tab-fit-signal` | Monotonic counter (default `0`) bumped by `:rf.xray/select-tab :machines` **and** `:rf.xray.static/select-tab :machines`. Forwarded as `MachineChart`'s `:fit-signal` so tab entry re-fits the topology; the layout-key auto-fit deliberately preserves manual zoom/pan, and this is the orthogonal entry-fit escape hatch. Registered in `registry.cljs`, read by both the Dynamic and Static machine surfaces. |
| `:rf.xray/active-timers-for-focused-machine` | Vector of timer records — `:armed` for live rings, `:cancelled` for fading/crossed-out ones. Inputs: `:rf.xray/trace-buffer`, `:rf.xray/machine-transitions-for-focused-event`, `:rf.xray/target-frame`, `:rf.xray/selected-machine-id`. The machine is picked by `pick-focused-transition`, the same rule the chart picks its record by, so rings and chart cannot drift (rf2-y8doi.23). **`:rf.xray/selected-machine-id` is an input for exactly that reason** (rf2-mj4jp): the rule takes the operator's explicit selection as an argument, so a consumer that calls the shared fn without it has not shared the rule — it has only shared the function name, and would fold rings for one machine while the chart drew another. **`:rf.xray/now-ms` is deliberately NOT an input** — with the clock as an input the whole-buffer fold re-ran at ~60 Hz; the now-keyed filter now runs in `overlay-tree` where the clock already is, which also makes retro-mode eviction honest. |
| `:rf.xray/now-ms` | Wall-clock ms from the `:rings/now-ms` slot — the reactive driver for ring animation, written by `:rf.xray/timer-tick`. |
| `:rf.xray/timer-hover` | The hovered timer's payload, or `nil`. Plumbed for a follow-on rich tooltip; v1 uses native SVG `<title>`. Shares its name with the event below. |
| `:rf.xray/cancellation-cascade-for-focused-machine` | The cancellation cascade for the selected machine, or a `:no-trigger` shape when nothing is selected. Inputs: `:rf.xray/trace-buffer`, `:rf.xray/selected-machine-id`. Registered in `cancellation_cascade_subs.cljs`. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/select-machine-id` | `[_ machine-id]` | Writes the picker slot **and LANDS the selection** — moves the spine focus to the newest epoch touching `machine-id` through the same walk Prev/Next uses, so the move sticks against `compose-focus`'s LIVE head-tracking (rf2-y8doi.23). A machine with no epoch in the window is a no-op. |
| `:rf.xray/clear-machine-selection` | `[_]` | Drops the picker slot. |
| `:rf.xray/machine-focus-prev` | `[_]` | Steps the spine focus back to the adjacent epoch whose cascade targets the currently-viewed machine, skipping epochs that touched only other machines. |
| `:rf.xray/machine-focus-next` | `[_]` | As above, forwards. |
| `:rf.xray/machine-state-clicked` | `[_ payload]` | Click-on-state in the topology view. Registered no-op slot — it exists so the chart's dispatch lands on a known handler instead of emitting a `:rf.warning/no-handler` trace. |
| `:rf.xray/machine-chart-layout-pulse` | `[_]` | Bumps `:machine-inspector/elk-pulse-tick`, re-driving the ELK layout pass. |
| `:rf.xray/set-scrubber-position` | `[_ position]` | Sets the scrubber slot. `:present` and integers are stored; the companion read defaults to `:present`. |
| `:rf.xray/timer-tick` | `[_ t]` | `:rf.trace/no-emit? true` (rf2-qsjda — the rAF tick would otherwise bomb Xray's own trace buffer 60×/second). Writes `t`, or `(.now js/Date)`, into `:rings/now-ms`. |
| `:rf.xray/timer-hover` | `[_ payload]` | Sets or (on `nil`) clears the hovered-timer slot. Shares its name with the sub above. |
| `:rf.xray/filter-by-machine` | `[_ machine-id]` | Appends an `:in` filter pill scoped to `machine-id`; a duplicate add collapses to a no-op. Registered in `filters.cljs`. |

### Test-only override seam

Not production ids — these require `install-test-overrides!` and are
absent after a plain `register-xray-handlers!` (rf2-e8330v; asserted by
`production-registration-installs-no-for-test-ids`).

| Id | Kind | Purpose |
|---|---|---|
| `:rf.xray/machine-snapshots-override` | sub | Override slot layered over the live snapshots. |
| `:rf.xray/machine-definitions-override` | sub | Override slot layered over the live definitions. |
| `:rf.xray/set-registered-machines-override-for-test` | event | Sets / clears the registered-machines override. |
| `:rf.xray/set-machine-snapshots-override-for-test` | event | Sets / clears the snapshots override. |
| `:rf.xray/set-machine-definitions-override-for-test` | event | Sets / clears the definitions override. |
| `:rf.xray/set-now-ms-override-for-test` | event | Pins a deterministic `now-ms` for ring-animation fixtures. |
| `:rf.xray/set-epoch-history-for-test` | event | **Seeding**, not an override — writes the real `:epoch-history` slot. |
| `:rf.xray/set-focus-epoch-id-for-test` | event | **Seeding**, not an override — writes the real `:focus` slot. |

### Machine-canvas chart widget

`:rf.xray.machine-canvas/*` is the chart widget shared by the Dynamic
Machine inspector and the Static Machines topology view, so it is owned
by neither and catalogued here once.

| Id | Kind | Behaviour |
|---|---|---|
| `:rf.xray.machine-canvas/chart-collapsed-for` | sub | `[_ machine-id]` — is that machine's chart collapsed? |
| `:rf.xray.machine-canvas/chart-collapsed-by-id` | sub | The whole `{machine-id boolean}` map. |
| `:rf.xray.machine-canvas/set-chart-collapsed` | event | `[_ {:machine-id :mode}]`, `mode` ∈ `:collapsed` / `:expanded` / `:toggle`. Persists the post-mutation map **via `:fx`** — rf2-04tx: an fx-id at the top level of the effect map is policed as `:rf.error/effect-map-shape` and refuses the event pre-commit. Before that refusal existed the stray key was silently dropped while the `:db` write landed, so the toggle looked like it worked and the choice never survived a reload. |
| `:rf.xray.machine-canvas/hydrate-chart-collapsed` | event | Lifts the persisted map back into app-db at boot. |
| `:rf.xray.machine-canvas/persist-chart-collapsed` | fx | Writes the map to localStorage. |

## Module-view tab (EP-0023 image/frame model)

Spec: [`026-Module-View-Panel.md`](./026-Module-View-Panel.md). The cohesive
home for runtime-structure inspection: the EP-0023 `image -> frame` PUBLIC
model (the FRAMES/IMAGES section, §8). A BROWSE surface (registry-wide, not
event-coupled) — read-only, dispatches nothing. (The retired EP-0013 `(realm,
frame)` / module substrate this tab once also surfaced — the REALMS + MODULES
sections — was deleted in full; there is no `re-frame.realm` namespace.)

### Subscriptions

| Sub | Returns |
|---|---|
| `:rf.xray/image-view` | Composite — the EP-0023 `image -> frame` model (rf2-32siq3.12). `{:frames [<frame-row> …] :frame-count :images?}` over the image-loaded frames: each as an execution context carrying its resolved image (the generation's `[kind id]` descriptors + per-descriptor provenance). EP-0024 (rf2-tu2vr7): the registries collapsed — an image-loaded frame is a single `re-frame.frame/frames` record carrying a `:generation`; the read goes through `re-frame.live-frame/image-view-frames` (which projects each such record into an inert frame view) + sealed generations (`re-frame.image-assembly/resolve-descriptor`) via the fail-soft `image_view_reads` seam; projects via `image_view_helpers/project-image-view`. `:images?` false → the no-image caption (the image/frame model is opt-in). Xray inspects the target frame as DATA here; Xray's OWN image (`image_view_reads/xray-image`) is a separate registration set that never mixes with a target frame's image (EP-0023 §Xray Beside The Target). |

This sub is L4-tab-internal — `module_view.cljs` registers no panel-internal
events (a browse surface). The tab is registered via `reg-l4-tab!` (id
`:module-view`, label **"Frames"**), so it is NOT in `panel_enum.cljc`.

## Static mode

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §Static mode +
[`018-Event-Spine.md`](./018-Event-Spine.md) §Static surface
architectural section. Static mode is unconditionally available
(per rf2-8l3uk — the prior `:rf.xray/static-mode?` feature gate
was removed). The mode pill mounts at ribbon-right, `Cmd-Shift-M` /
`Ctrl-Shift-M` toggles between Dynamic and Static surfaces, and the
**selected mode persists to localStorage under the key `xray.mode`**.
Per rf2-o5f5f.1 + rf2-o5f5f.2 + rf2-o5f5f.3 + rf2-ybjkx + rf2-8l3uk.

**The sub-tab does NOT persist.** `static/persistence.cljs` owns exactly
one key, `xray.mode`, and `:rf.xray.static/select-tab` returns `{:db …}`
with no `:fx` attached, so the Static sub-tab resets to
`static-shell/default-tab` (`:machines`) on every reload —
[`007-UX-IA.md`](./007-UX-IA.md) §Static mode → §Mode-state lifecycle
agrees: only set and toggle attach the persist fx. The only other
Static persistence is the Static **Machines** pair
(`:rf.xray.static.machines/persist-selection` / `-sub-mode`), which is a
different slot under a different key.

**Process-registrar browse.** The static browse panels read their
registrations off the process-global registrar via
`(rf/registrations {:source :store :kind k})` — the SOURCE-STORE read, which
never consults a bound image generation (see
[`026`](./026-Module-View-Panel.md) §8.4 and [`007-UX-IA.md`](./007-UX-IA.md)
§Runtime-structure awareness). There is no realm dimension to qualify by: a
registration belongs to the process registrar, full stop. The former
realm-qualified browse (`static/shared/realm.cljs` and the Static Interceptors
`:rf.xray.static.interceptors/realm-pairs` sub) was removed with the realm
substrate.

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/mode` | `:dynamic` / `:static`. | Default `:dynamic`. Hydrated from `xray.mode` localStorage on boot. |
| `:rf.xray.static/selected-tab` | Keyword sub-tab id (`:machines` / `:routes` / `:schemas` / `:flows` / `:interceptors`). | Default `:machines` (`static-shell/default-tab`). **Not persisted** — resets to the default on reload. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/set-mode` | `[_ mode]` | Sets `:rf.xray/mode` to `:dynamic` / `:static` and fires `:rf.xray.static/persist-mode` so the selection round-trips through localStorage. |
| `:rf.xray/toggle-mode` | `[_]` | Flips between `:dynamic` and `:static`, also firing `:rf.xray.static/persist-mode`. The `Cmd-Shift-M` / `Ctrl-Shift-M` chord dispatches this. |
| `:rf.xray.static/select-tab` | `[_ tab-id]` | Selects a Static sub-tab. Returns `{:db …}` **only — no fx, so the choice is not persisted**. An unknown `tab-id` (not in `static-shell/tab-ids`, which reads the L4 tab registry for `:static`) is ignored. Selecting `:machines` also bumps `:rf.xray/machine-tab-fit-signal`. |

### Effects

| Fx | Args | Behaviour |
|---|---|---|
| `:rf.xray.static/persist-mode` | `mode` keyword | Writes the bare string (`"dynamic"` / `"static"`) to localStorage key `xray.mode`. No-ops on JVM / when localStorage is unavailable. |

### Static Machines (`:rf.xray.static.machines/*`)

Spec: [`003-Machine-Inspector.md`](./003-Machine-Inspector.md). Master-detail
browse over the registered machines plus a per-machine sub-strip
(`:topology` / `:sim` / `:instances` / `:cascade`). The composites reuse
the Machine inspector's `:rf.xray/registered-machines`,
`:rf.xray/machine-definitions` and `:rf.xray/machine-snapshots` rather
than re-reading the registrar.

| Id | Kind | Behaviour |
|---|---|---|
| `:rf.xray.static.machines/selected-id` | sub | Selected machine-id, `nil` until first selection. |
| `:rf.xray.static.machines/search` | sub | Browse-list search text; `""` when unset. |
| `:rf.xray.static.machines/sort-key` | sub | One of `:name` / `:states` / `:live`; default `:name`. |
| `:rf.xray.static.machines/sub-mode-by-id` | sub | `{machine-id sub-mode}`; `{}` when unset. |
| `:rf.xray.static.machines/sub-mode` | sub | `[_ machine-id]` — that machine's effective sub-mode; default `:topology`. |
| `:rf.xray.static.machines/rows` | sub | Projected machine rows over registered machines + definitions + live snapshots. |
| `:rf.xray.static.machines/data` | sub | Browse-list composite — rows filtered by `search`, ordered by `sort-key`, marked with `selected-id`. |
| `:rf.xray.static.machines/copy-mermaid-status` | sub | `[_ machine-id]` — `:copied` / `:failed` for **that** machine only, else `nil`. `:pending` also reads `nil`, so an in-flight write never renders as a completed copy (rf2-sxw06). |
| `:rf.xray.static.machines/select` | event | `[_ machine-id]` — sets the selection, clears any Copy-Mermaid feedback, and fires `persist-selection`. |
| `:rf.xray.static.machines/set-search` | event | `[_ query]` — sets the search text. |
| `:rf.xray.static.machines/clear-search` | event | `[_]` — drops it. |
| `:rf.xray.static.machines/cycle-sort` | event | `[_]` — advances `sort-key` through `:name` → `:states` → `:live`. |
| `:rf.xray.static.machines/set-sub-mode` | event | `[_ machine-id sub-mode]` — stores the normalised sub-mode and fires `persist-sub-mode` with the whole map. |
| `:rf.xray.static.machines/hydrate` | event | `[_ {:selected-id :sub-mode-by-id}]` — lifts the persisted pair back into app-db at boot. |
| `:rf.xray.static.machines/state-clicked` | event | `[_ payload]` — registered **no-op** slot, so the topology chart's state-click lands on a known handler rather than a `:rf.warning/no-handler` trace. |
| `:rf.xray.static.machines/open-chart-popout` | event | `[_ machine-id]` — registered **no-op** slot, reserved for a chart pop-out affordance; the window orchestration is not built, so the Topology toolbar renders no pop-out button and nothing dispatches this today. |
| `:rf.xray.static.machines/copy-mermaid` | event | `[_ machine-id definition]` — emits the definition as Mermaid and hands it to `:rf.xray.fx/copy-to-clipboard`, the only Xray gesture that reaches that fx. A definition that cannot be projected lands as honest `:failed` feedback rather than an event error. |
| `:rf.xray.static.machines/copy-mermaid-done` | event | `[_ machine-id status]` — records the settled clipboard outcome, **only while that machine is still selected**, so a late settlement cannot repopulate a slot `select` just cleared. |
| `:rf.xray.static.machines/persist-selection` | fx | Writes the selected id to localStorage `xray.static.machines.selected-id`. |
| `:rf.xray.static.machines/persist-sub-mode` | fx | Writes the sub-mode map to localStorage `xray.static.machines.sub-mode-by-id`. |

**Sim.** The `:sim` sub-mode steps a **cloned** definition; the
production registry is never touched, and `sim-stop` drops the clone.
Every sim event takes a single map argument keyed by `:machine-id`.

| Id | Kind | Behaviour |
|---|---|---|
| `:rf.xray.static.machines/sim-by-machine` | sub | `{machine-id sim-state}` for every machine with a sim. |
| `:rf.xray.static.machines/sim-state` | sub | The selected machine's sim slot, or `nil` when it has none. |
| `:rf.xray.static.machines/sim-active?` | sub | Boolean over `sim-state`. |
| `:rf.xray.static.machines/sim-available-transitions` | sub | Transitions available from the sim's current snapshot, for the picker. |
| `:rf.xray.static.machines/sim-event-suggestions` | sub | Distinct event ids from the definition, for the autocomplete datalist. |
| `:rf.xray.static.machines/sim-current-state` | sub | The sim's current state, for the on-chart active-state highlight. |
| `:rf.xray.static.machines/sim-last-transition` | sub | `{:from :to :event}` of the most recent step, so the taken edge animates; `nil` before the first step. |
| `:rf.xray.static.machines/sim-start` | event | `{:machine-id :definition}` — creates the sim slot; the definition is pinned at start rather than re-resolved per step. |
| `:rf.xray.static.machines/sim-stop` | event | `{:machine-id}` — removes the slot. |
| `:rf.xray.static.machines/sim-reset` | event | `{:machine-id}` — rewinds to the initial snapshot and clears the trail, staying in sim mode. |
| `:rf.xray.static.machines/sim-step` | event | `{:machine-id :event}` — fires one event vector against the clone. |
| `:rf.xray.static.machines/sim-chart-edge-clicked` | event | `{:machine-id :event-id}` — an on-chart edge click, folded through the **same** step path as `sim-step`; an inert auto edge coerces to a no-op. |
| `:rf.xray.static.machines/sim-set-pending-event` | event | `{:machine-id :text}` — controlled input for the pending event. |
| `:rf.xray.static.machines/sim-set-pending-data` | event | `{:machine-id :text}` — controlled input for the pending payload. |

### Static Routes (`:rf.xray.static.routes/*`)

Search and Simulate-URL live here, not in the event-coupled Dynamic
Routing lens ([§Routes panel](#routes-panel)).

| Id | Kind | Behaviour |
|---|---|---|
| `:rf.xray.static.routes/query` | sub | Search text, or `nil`. |
| `:rf.xray.static.routes/sim-url` | sub | The Simulate-URL input, or `nil`. |
| `:rf.xray.static.routes/expanded` | sub | Set of route-ids whose meta-expander is open; default `#{}`. |
| `:rf.xray.static.routes/sim-nav-open` | sub | Set of route-ids whose simulated-navigation panel is open; default `#{}`. |
| `:rf.xray.static.routes/tab-data` | sub | Composite over `:rf.xray/registered-routes` + `query` + `sim-url`. |
| `:rf.xray.static.routes/set-query` | event | `[_ q]` — `nil` or `""` clears. |
| `:rf.xray.static.routes/set-sim-url` | event | `[_ url]` — `nil` or `""` clears. |
| `:rf.xray.static.routes/toggle-row` | event | `[_ route-id]` — toggles membership of `expanded`. |
| `:rf.xray.static.routes/toggle-sim-nav` | event | `[_ route-id]` — toggles membership of `sim-nav-open`. |
| `:rf.xray.static.routes/jump-to-dynamic` | event | `[_ route-id]` — the `→ Dynamic` chip: dispatches `[:rf.xray/set-mode :dynamic]` and `[:rf.xray/select-tab :routing]`. The route-id is not plumbed through; the Dynamic lens orients on whatever event is focused. |

### Static Schemas, Flows and Interceptors

Three read-only browse tabs sharing one shape: a `registry` read, a
search `query`, a `set-query` event, and a `tab-data` composite. Each
`registry` read declares `:rf.xray/trace-buffer` as an input purely as a
"something changed" pulse, so a fresh registration surfaces without
waiting for an unrelated re-render. Schemas and Flows are frame-scoped
by the L1 picker (`:rf.xray/observed-frame`); Interceptors is
process-global.

| Id | Kind | Behaviour |
|---|---|---|
| `:rf.xray.static.schemas/registry` | sub | App-db schemas via the `re-frame.schemas` façade, plus event and sub `:schema` metadata read from the host registrar with `{:source :store}` — the source-store read, which never consults Xray's own bound image generation. |
| `:rf.xray.static.schemas/query` | sub | Search text, or `nil`. |
| `:rf.xray.static.schemas/tab-data` | sub | Composite over `registry` + `:rf.xray/observed-frame` + `query`. |
| `:rf.xray.static.schemas/set-query` | event | `[_ q]` — `nil` or `""` clears. |
| `:rf.xray.static.flows/registered-flows` | sub | `(re-frame.flows/flows-snapshot)` — the per-frame `{frame-id {flow-id flow-map}}` store, the SOLE store since framework rf2-en00bk. The registrar `:flow` slot is reserved-but-empty, so a `(rf/registrations {:source :store :kind :flow})` read returns `{}`. **This is the live id**; `:rf.xray/registered-flows`, which this catalogue listed until 2026-09-18, is registered nowhere. |
| `:rf.xray.static.flows/query` | sub | Search text, or `nil`. |
| `:rf.xray.static.flows/tab-data` | sub | Composite over `registered-flows` + `:rf.xray/observed-frame` + `query`. |
| `:rf.xray.static.flows/set-query` | event | `[_ q]` — `nil` or `""` clears. |
| `:rf.xray.static.interceptors/registry` | sub | The host `:event` registrar's entries with their interceptor chains, read with `{:source :store}`. |
| `:rf.xray.static.interceptors/query` | sub | Search text, or `nil`. |
| `:rf.xray.static.interceptors/tab-data` | sub | Composite over `registry` + `query`. |
| `:rf.xray.static.interceptors/set-query` | event | `[_ q]` — `nil` or `""` clears. |

### Static test-only override seam

Installed only by `install-test-overrides!` (rf2-e8330v). Each pair
re-registers its production `registry` read as
`(or override (registry-value))`. The seam also re-registers
`:rf.xray.static.machines/rows` and `/data` with
`:rf.xray/machine-snapshots-override` layered over the live snapshots —
see the Machine inspector's [override seam](#test-only-override-seam).

| Id | Kind |
|---|---|
| `:rf.xray.static.schemas/registry-override` | sub |
| `:rf.xray.static.schemas/set-registry-override-for-test` | event |
| `:rf.xray.static.flows/registered-flows-override` | sub |
| `:rf.xray.static.flows/set-registered-flows-override-for-test` | event |
| `:rf.xray.static.interceptors/registry-override` | sub |
| `:rf.xray.static.interceptors/set-registry-override-for-test` | event |

## Command palette

Spec: [`007-UX-IA.md`](./007-UX-IA.md) §Command palette. Per
rf2-ybjkx / PR #1572 the palette extensions ship six new verbs, a
mode-aware command index (the palette's source list filters by
`:rf.xray/mode`), and a recents slot that boosts the most-recently-
invoked commands to the head of the result list (top-3 persisted to
localStorage).

### Subscriptions

| Sub | Returns | Notes |
|---|---|---|
| `:rf.xray/palette-open?` | Boolean — palette dialog mounted? | Toggled by the `Cmd-K` / `Ctrl-K` chord. |
| `:rf.xray/palette-query` | Search text. | Empty on open. |
| `:rf.xray/palette-cursor` | Active result index. | Reset when the query changes. |
| `:rf.xray/palette-recents` | Vector of command-ids in MRU order, capped at 3. | Persisted under localStorage key `re-frame2.xray.palette.recents.v1`. Lazy-seeded from localStorage on first open. |
| `:rf.xray/palette-index` | Mode-filtered command and navigation items. | Includes recents weighting. |
| `:rf.xray/palette-results` | Query-ranked results. | Feeds the visible list. |
| `:rf.xray/palette-active-item` | Item at the cursor. | Used by Enter / Ctrl-Enter. |

### Events

| Event | Vector shape | Behaviour |
|---|---|---|
| `:rf.xray/palette-open` | `[_]` | Opens the dialog, seeds recents once, and resets query/cursor. |
| `:rf.xray/palette-close` | `[_]` | Closes the dialog. |
| `:rf.xray/palette-toggle` | `[_]` | Toggles open/closed state. |
| `:rf.xray/palette-set-query` | `[_ text]` | Sets query and resets cursor. |
| `:rf.xray/palette-cursor-up` | `[_]` | Moves up, clamped at zero. |
| `:rf.xray/palette-cursor-down` | `[_ max-idx]` | Moves down, clamped at the last result. |
| `:rf.xray/palette-cursor-set` | `[_ idx]` | Selects a result index. |
| `:rf.xray/palette-invoke` | `[_ item popout?]` | Interprets the complete item's `:action` tuple, records command recents, and closes the dialog. Action tuples are not registrar event ids. |

### Command-item names (the 6 new verbs landed by rf2-ybjkx)

That landing expanded the catalogue; the complete current nine-item
catalogue is owned by [`API.md` §Command palette verbs](./API.md#command-palette-verbs-catalogue).
In particular, the shipped names are `:cycle-reduced-motion` and
`:snapshot-app-db`, not `:toggle-reduced-motion` or `:snapshot-db`.
Snapshot copies an egress-projected value to console/clipboard; it does
not create a pin. (`:clear-epoch-history` was retired under
rf2-y8doi.27 — see API.md §Command palette verbs.)

The `:modes` filter is the normative convention for palette command
authoring: a command's `:modes` set MUST include every mode in which
the command should appear in the palette's result list. Commands
without a `:modes` slot default to `#{:dynamic :static}` (both modes).

## Cross-references

The catalogue is reference material; the linked per-panel specs are
the normative source for *why* each panel registers what it does.
Cross-reference structure:

- Each panel doc SHOULD link here for "what subs/events this panel
  uses" rather than re-enumerating the registry surface in-line. The
  linking convention is a markdown link to the panel's section in this
  doc (`014-Registry-Catalogue.md#machine-inspector` and peers).
- This doc cross-refs back to the owning panel spec for *meaning*. The
  panel spec MUST own the panel's semantic contract (sub status
  taxonomy, layout, locks). Voice split: panel spec = *why and how*;
  this doc = *ownership and principal names*; the exact-set registry
  tests linked above = *complete installed membership*.

The naming convention itself is owned by
[`008-Embedding-Contract.md` §Registry-key isolation](./008-Embedding-Contract.md#registry-key-isolation-via-rfxray-prefix);
this doc enumerates what sits inside the namespace.

For consumers reading the buffer (the substrate every composite sub
projects from), see [`013-Trace-Consumer.md`](./013-Trace-Consumer.md).

For the API surface this catalogue describes from the *outside*
(the consolidated user-facing reference), see
[`API.md`](./API.md). API.md is consumer-facing; this doc is
contributor-facing — the catalogue lets a new agent or human reader
audit the registry surface without grepping the source.

## Vision — per-id metadata for golden-path navigation

**Bug class:** "I'm reading an unfamiliar Xray codebase; I see
`:rf.xray/event-bundles` in the source; what's its shape? where is it
registered? what consumes it?"

Today the catalogue enumerates names + roles. The next-step affordance
is **per-id metadata stamped at registration**:

- **Source coords** — every `reg-sub` / `reg-event` / `reg-fx`
  registration carries a `:source-coord` stamp (per Spec 001 + 006).
  Xray's own registrations should expose theirs through this
  catalogue so a human reader can jump directly to the registration
  site.
- **Version stamps** — when the registration shape changes (new input
  sub, removed output key), bump a per-id version. This catalogue
  surfaces the version alongside the id; downstream consumers can
  audit "is my code calling the v1 or v2 shape?"
- **Dependency arrows** — for composite subs, surface the input subs
  inline so the catalogue reads as a topology, not a flat list.

The catalogue grows from "reference list of names" to **"navigable
golden-path map of Xray's internal registry"** — a contributor
opening the spec can trace any path from a panel's high-level
behaviour all the way down to the source file where the registration
lives, in one click each step.
