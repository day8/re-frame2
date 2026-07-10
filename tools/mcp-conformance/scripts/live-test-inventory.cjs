// Single source of truth for the re-frame2-pair LIVE conformance gates.
//
// The live gates live in `test/live-re-frame2-pair-*.cjs`. Each is named in
// TWO runners that used to declare the roster independently:
//
//   1. `scripts/run-re-frame2-pair-live-hermetic-suite.cjs` — the ONLY place
//      the live path actually FIRES (boots shadow-cljs, sets
//      `$SHADOW_CLJS_NREPL_PORT`, spawns each entry, asserts the sentinel).
//   2. `scripts/test-all.cjs` — spawned by `npm test`, where they
//      SKIP-by-default (no `$SHADOW_CLJS_NREPL_PORT`) and ride green.
//
// Two hand-maintained lists could drift from each other and from disk; the
// old `live-list-completeness.test.cjs` existed mainly to ratchet the two
// lists back into agreement. This module makes the roster a SINGLE owner:
// each gate's basename, display name, and success sentinel are declared here
// ONCE. Both runners DERIVE their rows from this array (the hermetic runner
// resolves each `basename` to an absolute path; `test-all.cjs` derives a
// SKIP-by-default live row), so they can no longer drift from each other —
// the only remaining anti-drift check is disk-versus-this-inventory
// (`test/live-list-completeness.test.cjs`).
//
// Side-effect-free: requiring this module boots nothing (no shadow-cljs, no
// Chromium, no MCP server) — it is pure data, safe to import from the
// completeness gate and both runners.
//
// Each entry's `sentinel` is the `... CONFORMANCE GREEN` line the gate prints
// ONLY on a real (non-skipped) pass. The hermetic env guarantees
// `$SHADOW_CLJS_NREPL_PORT` is set, so the gate's SKIP path MUST NOT fire
// there — yet a SKIP still exits 0. The hermetic runner asserts the sentinel
// (and the absence of a `SKIP ` banner) so a silent in-hermetic SKIP turns
// the gate RED rather than riding green via the OTHER gates.
'use strict';

const LIVE_TESTS = [
  {
    basename: 'live-re-frame2-pair-overflow.cjs',
    name: 'live overflow conformance',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE OVERFLOW CONFORMANCE GREEN',
  },
  {
    // Pins the `notifications/progress` streaming wire surface.
    basename: 'live-re-frame2-pair-subscribe.cjs',
    name: 'live subscribe / notifications/progress conformance',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE SUBSCRIBE CONFORMANCE GREEN',
  },
  {
    // Egress-protection regression net for the pull-mode epoch tools
    // (trace-window / watch-epochs). Drives a declared-sensitive app-db
    // slot through both tools across the MCP wire and asserts the
    // sensitive epoch is WHOLE-DROPPED gate-OFF (the default) and shipped
    // gate-ON. This is the gate that pins the whole-drop behaviour.
    basename: 'live-re-frame2-pair-redaction.cjs',
    name: 'live egress-protection conformance (pull-mode epoch tools)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EGRESS-PROTECTION CONFORMANCE GREEN',
  },
  {
    // Pin the universal `:ok? false ⇒ isError:true` contract on the
    // read-family GENUINE error envelopes (read-dom/read-ui bad-selector).
    // Drives a malformed CSS selector against the live runtime so
    // `querySelectorAll` throws and the runtime fn returns a structured
    // `{:ok? false :reason :rf.error/...-bad-selector}` — then asserts the
    // SDK response is isError. This is the live SDK-boundary gate for the
    // error path; the degraded walk only exercises the
    // :nrepl-port-not-found shape, so the live runtime is required here.
    basename: 'live-re-frame2-pair-iserror.cjs',
    name: 'live isError-on-:ok?-false conformance (read-dom/read-ui bad selector)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE ISERROR-ON-OK-FALSE CONFORMANCE GREEN',
  },
  {
    // EP-0017 recordable-coeffects (`cofx`) over the live MCP wire.
    // Dispatches the fixture's [:counter/stamp] (a reg-event declaring
    // `:rf.cofx/requires [:rf/time-ms]`) with a scripted
    // `cofx "{:rf/time-ms <N>}"` and proves the supplied fact reaches the
    // resulting app-db state (reproducible-dispatch determinism), the
    // malformed-cofx refusals the degraded handler hides, and the EP-0017
    // tooling visibility (`list-handlers`/`handler-meta` for the `cofx`
    // kind + authored `:rf.cofx/requires` on the event). The degraded
    // end-to-end only proves the `cofx` arg is ACCEPTED — this is the
    // behaviour gate.
    basename: 'live-re-frame2-pair-cofx.cjs',
    name: 'live EP-0017 cofx conformance (reproducible dispatch + cofx tooling)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE COFX CONFORMANCE GREEN',
  },
  {
    // EP-0018 unified event-registration metadata over the live MCP wire.
    // Inspects a fixture `rf/reg-event` id (`:counter/inc`) via
    // `list-handlers`/`handler-meta {kind "event"}` and pins the unified
    // shape (`:ok? true`, `:kind :event`, `:id`, the `:rf/event-handler`
    // wrapper) AND the absence of any of the markers that must not appear
    // (`:event/kind`, `:rf/db-handler`/`:rf/fx-handler`/`:rf/ctx-handler`).
    // The degraded gate only proves the descriptor/CallToolResult wiring —
    // this proves the live event-metadata wire reflects EP-0018.
    basename: 'live-re-frame2-pair-event-meta.cjs',
    name: 'live EP-0018 event-metadata conformance (unified reg-event shape)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EVENT-METADATA CONFORMANCE GREEN',
  },
];

module.exports = { LIVE_TESTS };
