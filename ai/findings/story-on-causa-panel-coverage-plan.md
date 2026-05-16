# Story on Causa: Panel Coverage Plan

## Thesis

Use Story as Causa's deterministic panel gallery, not as a replacement for the live Causa gates. Story should make each Causa panel easy to inspect in stable, named states: empty, one-event, many-events, errors, redaction, large values, schema/no-schema, route/no-route, hydration normal/mismatch, small/deep machines, inline/popout-compatible projections. The live Causa browser feature gate must continue to prove the behaviours that only a real runtime can prove: trace collection, epoch history, frame isolation, preload/mount lifecycle, pop-out opener sharing, recursive Causa tracing avoidance, and load under real dispatch.

The first model substrate is `tools/causa/testbeds/cart_total`. It is already the right shape: real handlers/subs/views, shared fixture events in `fixtures.cljs`, Story variants under `#/stories`, and a known Causa debugging narrative around a wrong-slot subscription. Extend that pattern before introducing a broad synthetic gallery. The rule should be: Story variants replay deterministic Causa-shaped data into isolated frames; the live testbeds prove that Causa observes the real framework correctly.

## Boundary: Story vs Live Causa Gates

Story should test deterministic UI and state coverage:

- Panel rendering against fixed Causa-shaped records.
- Empty, nominal, overloaded, and malformed panel states.
- Cross-panel selection state when it is pure Causa app-db state.
- Visual/readability regressions in panels and Story-hosted workspaces.
- Fixture-builder contracts: record shapes, ids, ordering, redaction markers, elision markers, source coords, and error rows.

The Causa feature gates must keep testing real observer behaviour:

- Trace callbacks, epoch callbacks, ring-buffer eviction, and ordering under actual dispatch.
- Frame targeting and isolation, including multi-frame fan-out and destroyed frames.
- Preload, mount, keybinding, true-inline host, missing-host diagnostics, and production elision.
- Pop-out lifecycle, opener sharing, duplicate-listener prevention, and inline embedded panels connected to live runtime state.
- Recursive Causa tracing, especially Story mounting Causa panels while Causa itself is a frame/app.
- Browser load: 20-event checks, virtualisation budgets, slow render paths, and feature-matrix scenarios.

This split keeps Story fast and readable while preserving confidence that Causa still observes reality.

## First Substrate: Cart Total

Make `cart_total` the exemplar for every future Causa+Story slice. It should remain human-readable as a tutorial app, not become a dumping ground for every panel. Its Story surface should cover the cart debugging narrative:

- `empty`: no cart lines, no checkout snapshot, no total.
- `seeded-wrong-total`: one visible symptom, one event sequence, wrong `:cart/total`.
- `checkout-snapshot`: the snapshot state after checkout.
- `live-basket-drift`: the core mismatch state between `:cart/items` and `:checkout/items`.
- `discounted-snapshot`: derived wrongness through a secondary path.

Next cart-total additions should be narrow and illustrative:

- Add a Story workspace that pairs the app view with Causa panel projections for Event Detail, App-DB Diff, Subscriptions, Trace, and Issues.
- Add a "one event" Causa fixture from the seeded scenario: event vector, epoch record, changed paths, sub recompute rows, and a small causality graph.
- Add a "20 events/load-shaped" fixture only as static data for UI caps and overflow copy. Do not drive 20 real dispatches in Story.

Use other feature-matrix testbeds only where cart-total would become fake: `deep_machine` for Machines, SSR hydration testbeds for Hydration, schema violation for Schemas, long-flow for Flows, multi-frame for frame selectors/causality, sensitive/large dispatchers for privacy markers, and `perf_counter` for Performance.

## Panel-by-Panel Story Coverage

Event Detail should have variants for empty trace, one cart-total event, one event with effects/subs/renders, handler/fx/sub/view exception, sensitive/large payload markers, and 20-event overflow selection. Story asserts readable grouping and stable cross-links; live Causa asserts that the latest real dispatch is selected and grouped correctly.

Causality Graph should cover no cascades, a simple parent/child pair, a branch with sibling dispatches, multi-frame edges, an orphan parent, and a capped large/deep graph. Story can use deterministic node/edge records. The live gate owns parent-id collection and cross-frame fan-out.

Time Travel should cover empty epoch history, one epoch, several epochs with selected index, passive scrub state, pinned epoch, restore failure rows, evicted epoch copy, and effects-already-fired advisory. Story should not call `restore-epoch`; the live gate must prove restore success/failure against real frame state.

App-DB Diff should cover no diff, cart-total changed slices, deep nested maps/vectors, pinned watches, unchanged sibling collapse, redacted values, large-elided values, and missing before/after snapshots. Story owns diff readability; the feature gate owns real before/after epoch capture.

Subscriptions should cover no subscriptions, extractor/derived chain, cart-total wrong upstream edge, no watchers, stale/fresh/inflight/error badges, large output marker, and source chip hidden/present. Cart-total is ideal for the first real narrative. Live Causa owns actual sub topology collection and recompute counts.

Effects should cover no fx, `:ok`, `:error`, `:skipped-on-platform`, `:overridden`, no-such-fx, HTTP success/failure/abort/decode failure, and sensitive/large args. Story can render fixed invocation rows. Live gates own actual fx handler instrumentation and platform skipping.

Trace should cover empty buffer, one row, mixed op-types, free-text/no-match filters, origin/frame/severity chips, redaction count, large elision markers, clear-buffer projection, and 200-row render cap copy. Live gates own trace registration, buffer caps, and row budget under dispatch storms.

Machine Inspector should use small and large machine snapshots: no machines, simple two-state machine, hierarchical/parallel deep machine, child actors, timers, invoked work, guard failure, action failure, destroyed machine, and long transition history. Story should consume machine snapshot records. Live gates own actual machine lifecycle and transition tracing.

Flows should cover no flows, idle, recomputed, skipped/no-op, failed evaluator, cycle/registration issue if surfaced, redacted output, and long DAG display. Story owns panel shape. Live gates own real flow recompute semantics.

Routes should cover no routes, route table without active route, active route with params/query/fragment, navigation history, blocked transition, not-found, loading/error transition, stale token suppression, and multi-frame route selection. Story can use route records. Live gates own actual router integration and URL/history behaviour.

Schemas should cover no schemas, schemas/no violations, event payload violation, cofx violation, app-db violation, sub-return violation, malformed violation payload, all recovery-mode badges, source chips, redacted violating value, and issue linkage. Story should reuse the schema violation testbed shapes where possible. Live gates own Malli adapter behaviour and rollback/skipped-handler semantics.

Hydration should cover no SSR detected, clean hydration, server/client hash mismatch, divergent render-tree row, missing payload, corrupt payload, and multi-frame mismatch. Story should present fixed hydration records. Live gates own actual SSR payload capture and post-hydration persistence.

Performance should cover no samples, fast/medium/slow/blocking tiers, slowest cascade, histogram, missing User Timing support, budget threshold changes, and 20-sample overflow. Story can render fixed perf samples. Live gates own User Timing collection and dispatch-storm budget.

Issues Ribbon should cover all-clear, one error, warning/advisory, schema violation, hydration mismatch, mixed severity filters, no-match filters, malformed issue row, source chip, and pivot target. Story owns feed composition. Live gates own issue creation from real traces.

MCP Server should cover no activity, read-only tool call, confirmed write/dispatch, restore request, failed tool call, lifecycle row without dispatch id, origin filter enabled, and 20-operation ordered stream. Story can use synthetic `:origin :causa-mcp` rows. Live or Causa-MCP gates own JSON-RPC/tool runtime behaviour.

AI Co-pilot should cover collapsed rail, empty runtime, slash command menu, provider success stub, provider failure stub, cited trace/epoch ids, redacted/large values not leaked, and long conversation scroll. Story owns deterministic conversation model rendering. Live/provider gates own actual provider calls if any.

Open in Editor / Source Coordinates should cover chips for event, trace, sub, route, machine, flow, hydration, missing file, unknown editor keyword, and custom template output. Story can render source coord rows and expected href/copy text. Live gates own browser click bridge and OS-handler no-op behaviour.

Shell / Launch / Embedding should have limited Story coverage: panel chrome states, inline panel render contract, missing runtime empty state, and popout-compatible selected-panel projection. The live Causa gate must own true-inline host auto-mount, keybinding, missing-host diagnostics, opener lifecycle, duplicate listener checks, settings persistence, and production elision.

## Shared Fixture Builders Needed

Create a small Causa fixture namespace rather than letting every story invent shapes. Builders should be plain data, deterministic, and usable from CLJC helper tests where possible:

- Event traces: ids, timestamps, frame ids, op-type, operation, dispatch-id, parent-dispatch-id, origin, severity, tags, source coord, message.
- Epoch records: event vector, db-before/after hashes, changed paths, effects, sub recomputes, renders, duration, restore result/failure, evicted/pinned flags.
- App-db diffs: touched paths, before/after values, structural markers, redaction markers, large-value handles/digests.
- Graph records: nodes, edges, root/selected ids, orphan edges, frame grouping, cap/overflow stats.
- Machine snapshots: registered machines, active state paths, state nodes, transitions, actors, timers, invoke status, failures, source coords.
- Flow records: id, inputs, output path, current value marker, last operation, recompute/skip/failure status, exception summary.
- Schema rows: schema id, kind, path, recovery mode, value marker, source coord, linked issue id.
- Hydration rows: server/client hashes, render-tree paths, mismatch category, payload status, frame id.
- Perf samples: cascade id, tier, duration buckets, budget threshold, user-timing entries.
- Copilot/MCP activity: tool name, origin, request/result status, cited trace ids, redaction/elision counts, conversation turns.

Builder outputs should be close to Causa's panel inputs, not Story-specific decorations. Story registrations then select named fixture states.

## Sequencing

First PR: establish fixture-builder foundations and cart-total panel gallery. Add builders for trace rows, epoch records, app-db diffs, and sub topology. Register Story variants/workspaces that show cart-total Event Detail, App-DB Diff, Subscriptions, Trace, and Issues states. Keep changes localized to `tools/causa/testbeds/cart_total` plus a new fixture namespace to avoid panel hot-file conflicts.

Next PRs: add panel-family slices that can land independently. Slice one should cover Graph/Time Travel because they share epoch/edge builders. Slice two should cover Schemas/Issues/Effects/Trace because they share trace issue rows. Slice three should cover Machines/Flows/Routes because they need domain-specific fixtures. Slice four should cover Hydration/Performance because they depend on specialized testbeds. Slice five should cover MCP/Co-pilot once their data contracts settle.

Later: embed Causa panel views as Story `reg-story-panel` surfaces, replacing Story's epoch stub where appropriate. Add visual-regression snapshots only after fixture shapes stabilize. Consider static export for a "Causa panel gallery" that reviewers can browse without running the feature gate.

Avoid hot-file conflicts by treating each panel story namespace as append-only and preferring shared builders over edits to panel implementation files. Do not make cart-total the global feature matrix; keep it as the readable tutorial substrate.

## Risks and Open Questions

Story+Causa coexistence can create recursive tracing: Story mounts a frame, Causa observes frames, and a Causa panel rendered inside Story may itself dispatch Causa-frame events. The plan needs an explicit "fixture mode" or "panel projection mode" where Causa panels render fixed records without registering trace listeners.

Frame targeting is the main design question for embedded panels. Story panel `:for` scoping is load-bearing today; Causa panel embeds need an equally explicit target frame and must not silently fall back to `:rf/default`.

Popout/inline projections are useful in Story, but only as static render states. Real popout and inline mounting require browser windows, opener state, listener counts, and shared runtime assertions, so they belong in the live feature gate.

CI blow-up is a real risk. Story should add many cheap deterministic variants, but not many browser-load tests. Keep Story checks scoped to registration, selected render smoke, and optional visual snapshots. The 20-event/load row remains explicit/pre-PR, not default CI.

Human testbeds must remain readable. Cart-total should keep its tutorial narrative and small fixture list. Synthetic exhaustive data belongs in a dedicated Causa fixture gallery namespace, not in the app code.

The open product question is whether Causa panels should expose pure panel-render functions that accept records directly, or whether Story should seed Causa app-db and render the normal panel views. Prefer seeding Causa app-db when testing real panel composition; prefer direct pure views for leaf components and malformed-record states.
