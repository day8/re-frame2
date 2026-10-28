'use strict';
// THE LOGIN-HOST FIXTURE — the render module the login arm's JVM host
// witness (`re-frame.ssr.ring.login-host-crossing-test`) spawns the sidecar
// on.
//
// It carries NO identity of its own: it exports a FACTORY, and the test binds
// it to the application's entry id and build id in a one-line module it
// writes and hands the renderer as `:module` — the entry id is
// `fresco.login.policy/root-entry`, the same Var `examples/substrates/
// fresco/login/server.cljs` publishes as the real bundle's entry table.
// Which keys reach it is the JVM host's `:render-state` policy alone.
//
// What it is NOT is the login application's render. That is a Fresco render
// on React, witnessed in CLJS by
// `re-frame.fresco.login-server-crossing-ssr-dom-cljs-test`, which drives
// the real views, the real `login.model` registrations and this module's
// published entry table. What THIS module witnesses is the other half — the
// JVM host: that `fresco.login.host` loads on a Clojure classpath, that its
// handler projects the settled frame under the shared policy, and that a
// complete JVM-owned document comes back around Node's body bytes over a
// real socket.
//
// So it echoes what it was handed, and decodes nothing: the values arrive as
// EDN TEXT, per key, exactly as `re-frame.ssr.render-state/serialize` put
// them on the wire, and what the JVM reads back out of the document is
// byte-for-byte what it sent.

const escapeHtmlText = (value) =>
  String(value ?? '').replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');


module.exports = ({ entry: publishedEntry, buildId }) => ({
  protocol: 1,
  buildId,
  entries: [publishedEntry],

  async render({ entry, state, runtime }) {
    const sortedKeyList = (partition) => Object.keys(partition ?? {}).sort().join(' ');
    return (
      `<main data-entry="${escapeHtmlText(entry)}">` +
        `<div class="login-state-keys">${escapeHtmlText(sortedKeyList(state))}</div>` +
        `<div class="login-runtime-keys">${escapeHtmlText(sortedKeyList(runtime))}</div>` +
        `<div class="login-draft">${escapeHtmlText(state[':auth'])}</div>` +
        `<div class="login-notice">${escapeHtmlText(state[':auth.login/server-notice'])}</div>` +
        `<div class="login-machines">${escapeHtmlText(runtime[':rf.runtime/machines'])}</div>` +
        `</main>`
    );
  },
});
