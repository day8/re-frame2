'use strict';
// THE CROSSING FIXTURE — the render module the JVM->Node->JVM witness
// (`re-frame.ssr.ring.node-crossing-test`) spawns the sidecar on.
//
// It is the same plain shape implementation/ssr-node's own fixtures use —
// `{protocol, buildId, entries, render}`, CommonJS on nothing but `node:`
// builtins, no React and no shadow-cljs build — because the witness proves
// the CROSSING, not a renderer. Its whole job is to make what it was handed
// visible through the one channel the contract gives a render module: the
// markup it emits.
//
// The values arrive as EDN TEXT, per key, exactly as the wire carries them
// (`re-frame.ssr.render-state/serialize` on the JVM side). This module
// echoes that text rather than decoding it, so what the JVM reads back out
// of the document is byte-for-byte what it sent — a string value keeps its
// quotes, and that is the point.
//
//   `:heading`     set by the JVM's boot event. Its presence here proves the
//                  JVM drained `:initial-events` BEFORE the crossing (a) and
//                  that the state partition carried the drained value.
//   `:server-only` a key the witness allowlists for RENDER but not for the
//                  hydration payload — rendered here, absent from
//                  `__rf_payload` (c).
//   `:delay-ms`    a sleep, so one entry can be made to overrun `timeoutMs`
//                  and the sidecar's 504 can be crossed back (d).

const escapeHtmlText = (value) =>
  String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

module.exports = {
  protocol: 1,
  buildId: 'crossing-build-1',
  entries: {
    'app/root': {
      stateAllowlist: [':heading', ':server-only', ':delay-ms'],
      runtimeAllowlist: [':rf.runtime/routing'],
    },
  },

  async render({ entry, state, args }, emit) {
    const delay = Number(state[':delay-ms'] ?? 0);
    if (delay > 0) await new Promise((r) => setTimeout(r, delay));
    emit(
      `<main data-entry="${escapeHtmlText(entry)}" data-args="${escapeHtmlText(args)}">` +
        `<h1 class="crossing-heading">${escapeHtmlText(state[':heading'])}</h1>` +
        `<p class="crossing-server-only">${escapeHtmlText(state[':server-only'])}</p>` +
        `</main>`,
    );
  },
};
