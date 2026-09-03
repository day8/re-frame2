'use strict';
// THE LOGIN-HOST FIXTURE — the render module the login arm's JVM host
// witness (`re-frame.ssr.ring.login-host-crossing-test`) spawns the sidecar
// on.
//
// It carries NO policy of its own. The entry id, the build id and both
// per-partition allowlists arrive in the environment, and the test sets them
// from `hicasso.login.policy` — the same Vars `examples/substrates/hicasso/
// login/server.cljs` derives the real bundle's entry table from. So the
// sidecar in this witness enforces the application's own list rather than a
// second copy of it, and a host that drifted off that list is refused here
// exactly as it would be against the shipped bundle.
//
// What it is NOT is the login application's render. That is a Hicasso render
// on React, witnessed in CLJS by
// `re-frame.hicasso.login-server-crossing-ssr-dom-cljs-test`, which drives
// the real views, the real `login.model` registrations and this module's
// published entry table. What THIS module witnesses is the other half — the
// JVM host: that `hicasso.login.host` loads on a Clojure classpath, that its
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

const readJsonListEnv = (environmentKey) => {
  const rawValue = process.env[environmentKey];
  if (!rawValue) return [];
  return JSON.parse(rawValue);
};

const entry = process.env.RF2_LOGIN_ENTRY || 'hicasso.login/root';

module.exports = {
  protocol: 1,
  buildId: process.env.RF2_LOGIN_BUILD_ID || 'login-hicasso-dev',
  entries: {
    [entry]: {
      stateAllowlist: readJsonListEnv('RF2_LOGIN_STATE_ALLOWLIST'),
      runtimeAllowlist: readJsonListEnv('RF2_LOGIN_RUNTIME_ALLOWLIST'),
    },
  },

  async render({ entry, state, runtime }, emit) {
    const sortedKeyList = (partition) => Object.keys(partition ?? {}).sort().join(' ');
    emit(
      `<main data-entry="${escapeHtmlText(entry)}">` +
        `<div class="login-state-keys">${escapeHtmlText(sortedKeyList(state))}</div>` +
        `<div class="login-runtime-keys">${escapeHtmlText(sortedKeyList(runtime))}</div>` +
        `<div class="login-draft">${escapeHtmlText(state[':auth'])}</div>` +
        `<div class="login-notice">${escapeHtmlText(state[':auth.login/server-notice'])}</div>` +
        `<div class="login-machines">${escapeHtmlText(runtime[':rf.runtime/machines'])}</div>` +
        `</main>`,
    );
  },
};
