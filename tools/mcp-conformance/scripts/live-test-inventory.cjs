// Single, side-effect-free roster for both live runners. The completeness
// test compares it with disk. A sentinel distinguishes a real pass from the
// live tests' exit-0 SKIP path inside the hermetic runner.
'use strict';

const LIVE_TESTS = [
  {
    basename: 'live-re-frame2-pair-overflow.cjs',
    name: 'live overflow conformance',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE OVERFLOW CONFORMANCE GREEN',
  },
  {
    basename: 'live-re-frame2-pair-subscribe.cjs',
    name: 'live subscribe / notifications/progress conformance',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE SUBSCRIBE CONFORMANCE GREEN',
  },
  {
    // Pull-mode epoch projection with sensitive reads closed and open.
    basename: 'live-re-frame2-pair-redaction.cjs',
    name: 'live egress-protection conformance (pull-mode epoch tools)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EGRESS-PROTECTION CONFORMANCE GREEN',
  },
  {
    // Genuine read-dom/read-ui error envelopes, unavailable in degraded mode.
    basename: 'live-re-frame2-pair-iserror.cjs',
    name: 'live isError-on-:ok?-false conformance (read-dom/read-ui bad selector)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE ISERROR-ON-OK-FALSE CONFORMANCE GREEN',
  },
  {
    // Recordable coeffects: dispatch semantics, refusals, and metadata.
    basename: 'live-re-frame2-pair-cofx.cjs',
    name: 'live EP-0017 cofx conformance (reproducible dispatch + cofx tooling)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE COFX CONFORMANCE GREEN',
  },
  {
    // Unified event-registration metadata over the live wire.
    basename: 'live-re-frame2-pair-event-meta.cjs',
    name: 'live EP-0018 event-metadata conformance (unified reg-event shape)',
    sentinel: 'RE-FRAME2-PAIR-MCP LIVE EVENT-METADATA CONFORMANCE GREEN',
  },
];

module.exports = { LIVE_TESTS };
