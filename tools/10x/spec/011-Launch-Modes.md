# 011-Launch-Modes

Causa launches in two complementary ways:

1. **In-app overlay** — the default. Causa preloads into the dev
   build, mounts a hidden DOM root, and toggles into view on
   `Ctrl+Shift+C` or via the floating launcher pill.

2. **Standalone via MCP** — the remote-attach story. An AI agent
   running on the user's machine (or elsewhere) drives Causa-MCP
   against the user's running browser session; Causa's UI may or
   may not be open in the browser.

The two modes share the **same data substrate** (the trace bus +
epoch history) — and the same hard rule applies to both: **the
runtime is the source of truth**; Causa observes; mutations are
explicit and user-confirmed.

This is lock #9 in [`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md): a
**hybrid** approach. No Chrome extension; no custom WebSocket
remote-attach protocol; the in-app posture covers the local case
and MCP covers the remote case.

## In-app overlay

### Install

```clojure
;; shadow-cljs.edn dev build:
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload registers Causa's listeners under `register-trace-cb!`
and `register-epoch-cb!`, mounts a hidden DOM root, listens for
`Ctrl+Shift+C`. No code change in the app itself.

Same convention as re-frame-10x v1; muscle-memory transfer is free.

### Disable

Remove the `:preloads` entry, or:

```clojure
:closure-defines {day8.re-frame2-causa.config/enabled? false}
```

…to force-disable in dev.

### Launch

| Action | How |
|---|---|
| Open | `Ctrl+Shift+C` or click the floating pill |
| Close | `Esc` or `Ctrl+Shift+C` again |
| Pop out to second window | `Ctrl+Shift+P` |
| Open AI co-pilot rail | `Ctrl+Shift+/` |

### The launcher pill

When Causa is closed, a 48×48px circular button sits in the
bottom-right corner of the viewport (z-index pinned just below modal
overlays, above app content). The Causa mark centred. Subtle 1px
violet ring at 60% opacity on idle.

Pulses softly when there's an active error in the issues feed —
600ms expand-fade pulse, every 4s, **max 3 pulses then stops**. The
pulse is bounded so a long-lived error doesn't become a perpetual
animation.

Click → Causa opens (same 320ms slide-in animation as the keyboard
shortcut).

Right-click → contextual mini-menu: Open · Pop out · Settings · Hide
button.

The button is **hideable** (Settings option for users who only use
the keyboard). Hidden state persists per-app in localStorage.

### Pop-out to a second window

`Ctrl+Shift+P` or button right-click → `Pop out`.

Mechanism: `window.open` whose JS realm is connected to the opener's
via `window.opener`. The pop-out renders into the new window but
**reads and dispatches against the opener's runtime atoms directly**
— no `BroadcastChannel`, no `postMessage`, no structured-clone
serialisation. Same JS realm, no protocol cost.

Constraints inherited from the `window.opener` posture:

- Same-origin required. The pop-out window must not be opened with
  `noopener` / `noreferrer`.
- If the user closes the opener window, the pop-out becomes
  orphaned. Pop-out detects this via `window.opener.closed` and
  shows a clean "opener gone — close this window" overlay.
- The pop-out can't survive a hard reload of the opener — atoms get
  garbage-collected. Pop-out re-bootstraps on opener reload by
  re-reading `window.opener.causaRuntime`.

Solves the "I want Causa on a second monitor while the app runs
full-screen" use case.

### Animation

Slide-in from the right edge: 320ms with `cubic-bezier(0, 0, 0.2, 1)`.
First paint under 80ms (Causa was mounted hidden; toggle is a CSS
class swap). Respects `prefers-reduced-motion` (instant fade).

App content underneath dims 12% via a CSS overlay; app interactions
still pass through (pointer-events on the dim overlay are disabled).

## Standalone via MCP

### Mechanism

The MCP server (`tools/10x-mcp/`, per [`010-MCP-Server.md`](./010-MCP-Server.md))
is an stdio JSON-RPC server launched by the agent host (Claude Code,
Cursor, etc.) as a subprocess. The server connects over nREPL to the
running shadow-cljs build (which is connected to the user's browser).

The data path:

```
AI agent
  ↓ (MCP / stdio)
re-frame2-causa-mcp (Node process)
  ↓ (nREPL / bencode)
shadow-cljs JVM
  ↓ (cljs-eval / WebSocket)
browser running the user's re-frame2 app
```

The agent sees the **same trace bus and epoch history** that Causa-the-panel
sees. Tool calls are read-mostly; writes (`restore-epoch`,
`reset-frame-db`, `dispatch`) are confirmed by the agent host
(typically Claude Code's tool-permission prompt).

### Remote-attach

The remote case: developer A's machine runs Claude Code; developer
A's browser runs the app. With MCP attached, A's AI assistant can
query the runtime. This works **whether or not Causa's panel is
open** in the browser.

The "developer A's browser, developer B's Claude" case is **not
directly supported** at v1.0. The MCP server connects to
`127.0.0.1:<nrepl-port>` by default; cross-machine MCP requires
agent-host configuration (SSH tunnels, port-forwarding) that lives
outside Causa's scope. Causa-MCP is the protocol; the network
plumbing is the user's.

### Why not a Chrome extension

Considered and rejected. The IPC overhead, the sandbox isolation,
and the manifest-v3 churn aren't worth the marginal "you don't have
to change build config" benefit. The in-app posture is the right one
for re-frame2 (lock #9, option (a) considered).

### Why not a custom WebSocket remote-attach protocol

Considered and rejected. Serialising the runtime state across the
wire is too costly — snapshot identities, machine references,
sub-graph nodes, source-coord chips don't survive JSON round-trip
cleanly. The use cases (mobile-from-desktop, cross-machine
pair-debug) are too narrow to justify the protocol-versioning,
security, and reconnect-logic surface (lock #9, option (b)
considered).

The remote case is handled by MCP (which already pays for those
costs in the agent-host ecosystem) — not by a custom Causa protocol.

### Why not a VS Code panel

Considered and rejected. Editor-embed surface is a category mistake:
debuggers belong at workstations alongside the app, not inside the
editor. Where editor integrations are useful (jump-to-source,
re-dispatch-from-IDE), they go through MCP (per
[`010-MCP-Server.md`](./010-MCP-Server.md)) — not through a
VS-Code-specific extension.

## Coexistence

The panel and the MCP server can run simultaneously without
conflict:

- The trace bus emits once; both subscribers (panel + MCP server's
  ensure-runtime trace listener) see every event.
- The epoch-history surface is read-mostly from both.
- Mutations from the MCP server are tagged `:origin :causa-mcp`;
  mutations from the panel's re-dispatch affordance are tagged
  `:origin :causa`. Both are distinguishable in the event log.

A common workflow: the developer has the panel open for direct
inspection; the AI assistant operates on the same runtime via MCP
in parallel. The panel surfaces the agent's actions (the `:origin
:causa-mcp` colour-coding is visible in the strip and event log).

## What this doesn't do

- **No Chrome extension** (rejected, see above).
- **No VS Code panel** (rejected, see above).
- **No custom WebSocket remote-attach** (replaced by MCP).
- **No standalone HTML viewer** at v1.0. The MCP server replaces
  this — if the agent needs to render a viewer-like surface, it
  composes Causa-MCP tool calls.
- **No mobile launch mode** (lock #5).
- **No tablet-responsive standalone viewer** at v1.0. An earlier
  design proposed one; the lock-#9 hybrid retires the use case to
  MCP.

## Default summary

| User scenario | Mode |
|---|---|
| Working locally; want to inspect the runtime | In-app panel (`Ctrl+Shift+C`) |
| Want a second monitor for Causa | In-app panel + pop-out (`Ctrl+Shift+P`) |
| Want my AI to inspect / time-travel programmatically | Causa-MCP (configure in agent host) |
| Want to debug a colleague's browser | Out of scope at v1.0 |
| Want to debug a mobile browser | Out of scope at v1.0 |

The 95% of cases — local development with in-app inspection — is
solved by the in-app overlay. The MCP server covers the
agent-driven case. The remaining 5% (cross-machine, mobile) is
explicitly out of scope at v1.0.
