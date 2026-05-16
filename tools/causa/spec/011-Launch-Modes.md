# 011-Launch-Modes

Causa launches in two complementary ways:

1. **In-app true-inline panel** — the default. Causa preloads into
   the dev build, waits for the substrate adapter, then mounts into an
   app-provided right-side layout host (`[data-rf-causa-host]` by
   default). The panel participates in normal layout, so app controls
   remain visible and clickable to the left.

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

## In-app true-inline panel

### Layout host contract

The host app MUST provide a Causa layout host in its normal page
layout. Default selector:

```css
[data-rf-causa-host]
```

Minimal host markup (note the DOM order: `<main>` first, host
`<aside>` second — flex flow lays the aside to the right of the app
column):

```html
<div class="app-shell">
  <main id="app"></main>
  <aside data-rf-causa-host></aside>
</div>
```

Minimal layout (the `var(...)` reading of `--rf-causa-inline-width`
is the supported host-resize knob — see §Resizing the inline host
below):

```css
body { margin: 0; }
.app-shell { display: flex; min-height: 100vh; }
[data-rf-causa-host] {
  flex: 0 0 var(--rf-causa-inline-width, 420px);
  min-width: 320px;
  box-sizing: border-box;              /* the 1px border-left lives inside
                                          the documented width — without
                                          it, the host renders 1px wider
                                          than the var(...) value */
  border-left: 1px solid #2a2a2a;     /* visual separator on the app side */
  resize: horizontal;                  /* user-draggable width (see below) */
  overflow: auto;
}
#app { flex: 1; min-width: 0; }
```

The host owns sizing and layout. Causa owns the shell rendered inside
the host. For the framework-side Tool-Pair surfaces Causa consumes
(trace bus, epoch history, registrar queries) see
[`spec/Tool-Pair.md` §The Causa renderer](../../../spec/Tool-Pair.md#the-causa-renderer).
Hosts may override the selector before Causa opens:

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:layout/host-selector "#devtools-causa"})
```

### Resizing the inline host

Per `rf2-um813`, the recommended host snippet reads a single CSS
custom property — `--rf-causa-inline-width` — for its `flex-basis`,
so developers can resize the inline Causa panel without forking the
host rule or falling back to overlay / body-padding dock modes.

The contract is **JS-free** and **host-owned**: Causa itself does not
read the property; the host's stylesheet does. The host's
`[data-rf-causa-host]` rule uses the variable with a default fallback
identical to the historical fixed-pixel default (420px):

```css
[data-rf-causa-host] {
  flex: 0 0 var(--rf-causa-inline-width, 420px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
  resize: horizontal;
  overflow: auto;
}
```

To resize, override the property anywhere up the cascade — the closest
declaration wins as usual:

```css
/* Global default — every page in the app */
:root { --rf-causa-inline-width: 560px; }

/* Per-route override (e.g. a debugging route that wants more room) */
.debug-route { --rf-causa-inline-width: 720px; }

/* Per-user override via a developer stylesheet */
[data-rf-causa-host] { --rf-causa-inline-width: 380px; }
```

Sizing units are unrestricted (`px`, `rem`, `vw`, `min(...)`, `clamp(...)`,
…) — the variable is plugged straight into `flex-basis`. The
`min-width: 320px` floor in the recommended rule prevents the panel
from collapsing past readability when the developer specifies a small
value; remove the floor if you want truly unbounded shrink.

App content to the left (`#app { flex: 1; min-width: 0 }`) stays in
normal flow regardless of the inline width — Causa never overlays the
app, never claims a hit-test region outside its host, and never
mutates `body` padding in the default true-inline mode. This holds
during resize as well: changing the property triggers a flex reflow,
the host shrinks/grows, the app reflows to fill the remainder, and
nothing in the page becomes unclickable.

The property name and default are published as
`day8.re-frame2-causa.config/default-layout-host-css-var` and
`default-layout-host-width` so tooling, story-mode chrome, and the AI
co-pilot's snippet helper can refer to them without forking the
string. Causa MUST NOT introduce a runtime API that sets the property
from CLJS — the host's stylesheet is the single source of truth for
sizing; introducing a CLJS setter would split that source.

### User-draggable resize

The recommended host snippet ships two complementary resize
mechanisms — both browser-native, both JS-free, both writing the
same `flex-basis` slot:

1. **CSS variable** (host-owned, fixed-point sizing) — the
   `--rf-causa-inline-width` property described above. The host's
   stylesheet sets the *initial* width and any cascade-level
   overrides (per-route, per-user, per-build). One declaration, no
   pointer events, no runtime cost. This is the path for "the team
   agreed Causa should default to 560px on the debug route."
2. **Browser-native drag** (user-controlled, ad-hoc sizing) — the
   `resize: horizontal` + `overflow: auto` pair on
   `[data-rf-causa-host]`. The browser paints a drag-handle in the
   bottom corner of the host (per
   [CSS UI L3 §`resize`](https://drafts.csswg.org/css-ui-3/#resize)),
   the user drags it, the host's *used* width updates, and flex
   reflow shrinks `#app` to fill the remainder. No CLJS, no listener,
   no Causa surface — the browser owns the interaction. This is the
   path for "I want a bit more room for the event-detail panel right
   now."

The two cooperate cleanly. The variable establishes the initial
size; a drag overrides it for the page lifetime; reload (or a fresh
`--rf-causa-inline-width` override up the cascade) returns to the
declared initial. The drag handle is the bottom-right corner of the
host element (in LTR layouts) and is keyboard-accessible per the
browser's standard `resize` semantics; no ARIA wiring is required.

Causa MUST NOT introduce a JS-driven draggable separator — the
`resize: horizontal` contract covers the user-drag requirement at
zero runtime surface, zero new failure modes, and zero ARIA
semantics for Causa to maintain. If a future use case demands
pointer-capture beyond what the browser primitive provides, it MUST
write to `--rf-causa-inline-width` (preserving cascade semantics);
it MUST NOT introduce a parallel sizing channel.

If the selector cannot be found after the substrate adapter is ready,
Causa MUST fail loudly but safely: `console.error` with the selector
and snippet above, plus the same diagnostic exposed through
`window.day8.re_frame2_causa.status()`. It MUST NOT use `alert()` and
MUST NOT block host app startup.

### Install

```clojure
;; shadow-cljs.edn dev build:
{:builds {:app {:devtools {:preloads [day8.re-frame2-causa.preload]}}}}
```

The preload registers Causa's listeners under `register-trace-cb!`
and `register-epoch-cb!`, installs the browser API/keybinding, then
auto-opens into the configured layout host once `rf/init!` has
installed a substrate adapter.

Tool-owned pages that deliberately do not allocate app layout real
estate for Causa (for example Story-only browser-test canvases) MAY
suppress only the default page-load open before `rf/init!`:

```clojure
(require '[day8.re-frame2-causa.config :as causa-config])
(causa-config/configure! {:launch/auto-open? false})
```

This does not disable Causa. The collectors, browser API, keybinding,
and explicit `open!` / `toggle!` calls remain installed; if an explicit
open has no host, it MUST still emit the normal actionable missing-host
diagnostic. App dev pages should keep the default `true` posture and
provide `[data-rf-causa-host]`.

### Disable

Remove the `:preloads` entry, or:

```clojure
:closure-defines {day8.re-frame2-causa.config/enabled? false}
```

…to force-disable in dev.

### Launch

| Action | How |
|---|---|
| Auto-open | Page load after `rf/init!`, when `[data-rf-causa-host]` exists |
| Suppress auto-open on tool-only pages | `(causa-config/configure! {:launch/auto-open? false})` before `rf/init!` |
| Hide/show | `Ctrl+Shift+C` |
| Legacy overlay debug mode | `window.day8.re_frame2_causa.open_overlay_BANG_()` |
| Close | `Esc` or `Ctrl+Shift+C` again |
| Pop out to second window | `window.day8.re_frame2_causa.popout_BANG_()` |
| Open AI co-pilot rail | `Ctrl+Shift+/` |

Per `rf2-sbfb7` the body-padding dock surface (`dock!` / `undock!`) and
the imperative inline-panel surface (`mount-inline-panel!` /
`unmount-inline-panel!`) were removed. The true-inline default and the
`popout!` window cover the dock use case; declarative panel embedding
is the surface enumerated in
[`008-Embedding-Contract.md`](./008-Embedding-Contract.md).

### Closed state

When Causa is hidden, the inline mount node remains in the layout host
with `display: none`. The app does not receive body padding, viewport
overlays, or fixed-position chrome as part of the default developer
experience. `Ctrl+Shift+C` shows the existing shell again; no React
remount is required.

Hosts MAY add their own launcher affordance if they want a visible
button, but that affordance is host chrome, not Causa's default launch
contract. Causa's built-in `open-overlay!` debug surface remains an
optional debug mode and must not be described as the primary path.

### Pop-out to a second window

Programmatic entry: `(causa/popout!)` from CLJS, or
`window.day8.re_frame2_causa.popout_BANG_()` from a devtools console.
A right-click → `Pop out` affordance on the launcher pill is the
canonical chrome-side path. No keybinding is wired pre-alpha; the
programmatic call is the contract.

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
  shows a clean "opener gone — close this window" overlay. Per
  `rf2-h3ekl` the implementation is a sibling DOM node to the shell
  root (`#rf-causa-popout-opener-gone-overlay`,
  `data-testid="rf-causa-popout-opener-gone-overlay"`) plus a
  `setInterval` watchdog that polls `window.opener` every 500ms;
  the overlay matches Causa's visual language (token-derived
  surface / text / accent colours, sans-stack typography) and uses
  plain DOM rather than the substrate render tree so it remains
  operable if the broken opener has caused a substrate throw
  mid-render. The watchdog self-clears on first reveal; the
  overlay is also torn down by `teardown-popout-state!` (test path)
  and on the popout's own `pagehide` / `unload`.
- The pop-out can't survive a hard reload of the opener — atoms get
  garbage-collected. Pop-out re-bootstraps on opener reload by
  re-reading `window.opener.causaRuntime`.

Solves the "I want Causa on a second monitor while the app runs
full-screen" use case.

### Animation

Default inline launch is normal document layout: Causa renders inside
the host's right column and the app remains visible to the left. The
default hide/show operation is a CSS display swap on the mount node
and MUST respect `prefers-reduced-motion`.

Overlay-specific slide/dim affordances are allowed only for optional
debug modes. They MUST NOT be used to approximate the default
true-inline host contract.

### Mount lifecycle (rf2-9kkrm)

The in-app panel's first paint is paid after substrate readiness, not
at preload namespace load. The preload does foundation work first,
then auto-opens into the app-provided host when `current-adapter` is
available. The lifecycle is normative.

**Two-phase boot.** Loading the preload namespace runs the
**foundation** side-effects only:

1. Register Causa's `:rf.causa/*` handlers (subs / events / fxs)
   against the `:rf/causa` frame.
2. Register the trace collector via
   [`re-frame.trace/register-trace-cb!`](../../../spec/009-Instrumentation.md)
   under `:rf.causa/trace-collector`.
3. Register the epoch collector via `rf/register-epoch-cb!` under
   `:rf.causa/epoch-collector` (no-op when the
   `day8/re-frame2-epoch` artefact is absent).
4. Attach a global `Ctrl+Shift+C` keydown listener on
   `document`.
5. Schedule default true-inline auto-open once the substrate adapter
   is ready, unless `:launch/auto-open?` is false before the probe
   observes readiness.

The preload MUST NOT mount the shell synchronously during namespace
load. It MAY schedule a bounded adapter-ready retry. Once the adapter
is ready, it MUST find the configured layout host and mount the shell
there. If the host is missing, it MUST emit the diagnostic described in
§Layout host contract and leave the app running.

**Boot order.** Within the preload's foundation phase the side-
effects MUST run in the order **register-handlers → register-
trace-cb → register-epoch-cb → attach-keybinding**. The keybinding
listener is attached last so that, in the unlikely race where the
user presses `Ctrl+Shift+C` mid-load, the handlers required by the
shell render are already in the registry when the mount fires.

Within the mount phase the order MUST be **find-layout-host →
create-mount-node-in-host → substrate-render → mark-visible**. The
substrate adapter's `:render` slot is the canonical mount path
(per [`spec/006-ReactiveSubstrate.md`](../../../spec/006-ReactiveSubstrate.md)
§Render contract) — Causa MUST NOT bypass the adapter and call
React directly. The render call returns an unmount fn which the
mount machinery MUST retain for `teardown!`.

**Subsequent toggles.** Once mounted, the shell's container stays
in the DOM for the rest of the page's lifetime. Close MUST be a
CSS-only `display: none` on the mount-node — never an unmount.
Re-open MUST be a CSS-only `display: block`. This is what
preserves the <80ms first-paint on every toggle after the first
(per §Animation above): subsequent paints reuse the existing
React tree, the existing subscriptions, the existing local UI
state. A re-mount would discard internal panel state (current tab,
scroll position, selected epoch, AI-rail conversation) and miss
the toggle-paint target.

**Idempotency under hot-reload.** Every piece of mount-adjacent
state — the registration sentinels, the keybinding sentinel, the
mount-state singleton — MUST be `defonce`-guarded so that
shadow-cljs `:after-load` reruns the preload's side-effects
without double-attaching the listener, replacing the trace
callback (which would emit a console warning per
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Trace callbacks), or re-creating the mount node. The mount-state
itself MUST survive reload — the user's currently-open Causa
panel MUST remain open across an `:after-load`, with its internal
state intact. This is the hot-reload story for rf2-iw5ym's
reactive-container parity: trace-buffer and mount-state share the
same "outlast the namespace reload" posture.

**Before-mount probe.** Host code (re-frame app code, tests,
adjacent tools) MUST be able to ask "is Causa currently mounted /
currently visible?" **without** forcing a mount. The mount API
exposes two read-only predicates for this purpose:

- `mounted?` — `true` iff the shell has been mounted at least once
  in the current page lifetime. May be `true` while `visible?` is
  `false` (the user opened then closed Causa).
- `visible?` — `true` iff the shell exists *and* its container is
  currently displayed (`display != none`).

Calling either predicate MUST be side-effect-free: no DOM mutation,
no substrate render, no allocation of mount-state. The probes are
the contract surface for "is Causa loaded?" introspection — tools
that decorate their output when Causa is open (e.g. story-mode
re-dispatch chips) read `visible?` and degrade gracefully when
Causa is closed or absent.

**Unmount semantics.** Production sessions never tear the shell
down — the shell lives for the page's lifetime once mounted, and
`Ctrl+Shift+C` close is a CSS hide, not an unmount. The
`teardown!` operation is **test-only**: it MUST invoke the
substrate adapter's unmount fn (returned by `:render` at mount
time), MUST remove the mount-node from its layout host, and MUST
reset the mount-state singleton to `nil` so the next test starts
from a clean slate. The unmount fn MUST be invoked inside a
swallow-errors guard — substrate adapters MAY throw on a
double-unmount and `teardown!` is the test fixture's last-chance
cleanup, not a contract-checking call site.

Per `rf2-yudol` the teardown contract covers **both** mount
singletons, not just the in-app shell:

- `mount-state` — the in-app shell (above).
- `popout-state` — the optional second-window shell. `teardown!`
  MUST invoke the popout's substrate unmount, attempt to close the
  popout window (silently tolerating "already closed"), and reset
  the singleton to `nil`. A leaked `popout-state` would short-
  circuit the next `popout!` and return a stale state map whose
  `:window` is already closed.

(The third pre-existing singleton — `inline-mounts` for the
imperative `mount-inline-panel!` debug API — went away under
`rf2-sbfb7` together with the debug API itself; declarative panel
embedding under [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)
replaces it and does not touch the mount singleton surface.)

**Popout external-close cleanup.** Per `rf2-yudol` `popout!` MUST
register `pagehide` / `unload` listeners on the popout window so
that when the user closes the popout externally, the opener-side
`popout-state` singleton is cleared. Without this, a subsequent
`popout!` would short-circuit on the stale singleton whose
`:window` has `.closed = true` and Causa would never re-render
into a new window. The handler MUST verify the identity of the
window it was registered for (compared to the current
`popout-state.:window`) before clearing — a stale handler that
fires after a fresh `popout!` has replaced the singleton MUST NOT
nuke the new state.

**Production posture.** The whole foundation block MUST be gated
on a single dev-only sentinel (the framework's
`interop/debug-enabled?` flag, per
[`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md)
§Production elision) so Closure DCE strips every side-effect from
production bundles compiled with `(set! goog.DEBUG false)`. The
mount module itself carries no elision logic — the call-site gate
in the preload is sufficient. If the preload is mistakenly
included in a production bundle, the trace registration is a no-op
(the framework elides the trace surface) and the mount call fails
silently because `current-adapter` is unset; the fallback is
graceful, not catastrophic.

### Epoch pump (rf2-yp92j)

The foundation phase's third step registers an
[`rf/register-epoch-cb!`](../../../spec/009-Instrumentation.md#register-epoch-cb--assembled-epoch-listener)
callback under the key `:rf.causa/epoch-collector`. Where the trace
collector buffers raw events for panel-side projections (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md)), the epoch-collector serves
a different job: it is the **reactive pump** that keeps Causa's
cached epoch-history snapshot consistent with the framework's. Causa
cannot subscribe directly against `(rf/epoch-history target)` —
the framework atom backs a side-effecting read, not a reactive
source. Routing each settle through Causa's `:rf/causa` frame is
what makes the
[`:rf.causa/epoch-history`](./014-Registry-Catalogue.md#shared-infrastructure)
sub re-fire when the host appends an epoch. This subsection pins
the callback's contract.

**Registration key and signature.** The callback MUST be registered
under the keyword `:rf.causa/epoch-collector` (the
`:rf.causa/` namespace per
[`spec/Conventions.md`](../../../spec/Conventions.md)) with the
signature `(fn [record] ...)` where `record` is a fully-assembled
`:rf/epoch-record` per
[Spec-Schemas](../../../spec/Spec-Schemas.md#rfepoch-record). The
key is reserved — host code MUST NOT register a competing callback
under the same id (a duplicate registration would replace the
collector and silence Causa's epoch-driven panels).

**Trigger.** The callback fires once per **drain-settle**, after
the framework has appended the assembled record to its per-frame
`epoch-history` ring buffer. The callback runs synchronously on the
framework's emit call stack, per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules)
— there is no batching, no debounce, no background delivery. A
multi-event cascade yields exactly one callback invocation, not
one per event (the trace collector's job is per-event; the epoch
collector's is per-cascade).

**What the callback does.** On every invocation the callback MUST
re-enter the runtime under the `:rf/causa` frame binding (via
`rf/with-frame`) and dispatch
`[:rf.causa/epoch-recorded (:frame record)]`. The event handler is
registered against `:rf/causa` (per
[`014-Registry-Catalogue.md` §Shared infrastructure](./014-Registry-Catalogue.md#shared-infrastructure))
and is responsible for the no-op-vs-update decision: when the
record's `:frame` does not match the currently-selected target
frame, the handler returns `db` unchanged; when it matches, the
handler re-reads `(rf/epoch-history target)` and writes the fresh
vector into Causa's app-db. Re-reading rather than threading the
record's contents through the dispatch arg keeps the snapshot
consistent with the framework's own view — the record is the
trigger, not the payload.

**Ordering guarantees.** The callback receives records in
**emission order** — the order in which the framework finished
draining each cascade. Per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules),
each listener sees events in the runtime's emit order; no
re-ordering occurs between framework emit and the collector body.
Ordering *across* sibling listeners (other tools that register
their own `register-epoch-cb!` callbacks alongside Causa) is **not
contract** — the same rule that applies to `register-trace-cb!`
applies here. Causa's handler MUST NOT depend on the relative
order of Causa's invocation versus any other tool's.

**Frame-scoping.** The dispatch MUST be wrapped in
`(rf/with-frame :rf/causa ...)` so the resulting event handler
writes to Causa's own app-db, not the host frame's. The wrap is
load-bearing: without it, the dispatch would resolve in the
outermost-dispatch frame (typically the host's `:rf/default` per
[Spec 002 §Frame resolution](../../../spec/002-Frames.md)) and
Causa's `:rf.causa/epoch-recorded` handler — registered only
against `:rf/causa` — would miss entirely. The record's `:frame`
field (the *host* frame whose drain settled) is passed as the
dispatch arg so the handler can compare against its target-frame
sub and skip work for non-target frames; it MUST NOT be confused
with the dispatch's effective frame binding (`:rf/causa`).

**Backpressure.** None. The collector body is fire-and-forget per
[Spec 009 §Listener invocation rules](../../../spec/009-Instrumentation.md#listener-invocation-rules)
— the dispatched event enters the `:rf/causa` frame's event queue
and the callback returns immediately. The framework's emit path
MUST NOT block on Causa's dispatch draining. If the `:rf/causa`
queue is busy when an epoch settles, the new dispatch enqueues
behind the existing work and the framework moves on. Causa MUST
NOT introduce a back-pressure throttle on the framework's emit
path; the framework's epoch-cb fan-out is fire-and-forget and any
back-pressure attempt would violate
[`Principles.md`](./Principles.md) §Observation only — no new
runtime surfaces.

**No drop semantics.** Unlike the trace bus's bounded ring (per
[`013-Trace-Bus.md`](./013-Trace-Bus.md) §Eviction policy), the
epoch pump does **not** drop callbacks under load. Every settle
fires the callback; every callback dispatches into `:rf/causa`.
The framework's own `epoch-history` ring buffer is the only
lossy substrate in this chain — when its depth (default 50, per
[Tool-Pair](../../../spec/Tool-Pair.md)) is exceeded the oldest
epoch evicts, and Causa's next re-read picks up the post-eviction
vector. Causa MUST NOT maintain its own deeper epoch retention;
the framework's `epoch-history` is the source of truth and
Causa's cache is a pure mirror.

**Exception isolation.** An exception thrown inside the callback
body MUST be caught by the framework's epoch-cb fan-out (per
[Spec 009 §`register-epoch-cb!` invocation rules](../../../spec/009-Instrumentation.md#register-epoch-cb--assembled-epoch-listener))
and MUST NOT propagate to the framework or to other registered
epoch listeners. Causa's collector body is small (it only wraps a
dispatch); the realistic failure mode is the dispatched event
handler throwing, and that runs inside the `:rf/causa` frame's
own drain — its exception is the responsibility of the
re-frame2 error catalogue, not the epoch-cb surface.

**Idempotency.** The registration is gated by a `defonce`
sentinel (`epoch-cb-registered?`) so shadow-cljs `:after-load`
reruns of the preload do NOT re-register the callback. A
re-registration under the same key would be a no-op at the
framework level (the same-key replacement semantics) but the
sentinel suppresses it explicitly to keep the preload's
side-effect surface auditable. Test fixtures MAY call
`reset-for-test!` to drop the sentinel and drive multiple
registration cycles; production code MUST NOT.

**Absent-artefact behaviour.** The `day8/re-frame2-epoch`
artefact is optional. When it is not on the classpath,
`rf/register-epoch-cb!` is itself a no-op (per
[Spec 009 §Hook-table-driven late binding](../../../spec/009-Instrumentation.md))
and the registration call is silently a no-op. Causa's
time-travel panel detects the absent-artefact case via
`(empty? (rf/epoch-history ...))` and renders the
"epoch artefact not installed" empty state.

**Frame-destroy handling.** When the host frame whose drain
produced an epoch is later destroyed (per
[Spec 002 §Destroy](../../../spec/002-Frames.md)), the framework
emits a one-shot
`:rf.epoch.cb/silenced-on-frame-destroy` trace event for each
`(frame, cb-id)` pair whose previously-firing callback has gone
silent (per
[Tool-Pair §Surface behaviour against destroyed frames](../../../spec/Tool-Pair.md#surface-behaviour-against-destroyed-frames)).
Causa MAY surface this trace in the event log (it flows through
the trace bus like any other event) but MUST NOT take any
additional action on it — the silencing is the framework's
contract surface; Causa's role is read-only observation. If the
destroyed frame was Causa's selected target, the
`:rf.causa/epoch-history` sub returns the last-cached vector
until the user selects a different target frame.

**Unmount cancellation.** Causa's `teardown!` operation (test-
only, per §Mount lifecycle) MUST NOT unregister the epoch
callback — the callback is registered at preload time, not at
mount time, and the preload's foundation phase persists across
shell unmounts. Test fixtures driving teardown across runs MAY
call `rf/remove-epoch-cb!` directly on the
`:rf.causa/epoch-collector` key to unwire the pump; the
sentinel-based registration will then re-fire on the next
preload reload. Production sessions never tear down.

**Production elision.** Per
[`Principles.md`](./Principles.md) §Production elision is
non-negotiable, the entire foundation block (including the
epoch-collector registration) is gated on
`re-frame.interop/debug-enabled?` at the preload's call site.
Production builds compiled with `(set! goog.DEBUG false)` strip
the registration, the callback body, and the `:rf/causa`
event-queue entries entirely — no per-settle dispatch fires in
production. The framework's epoch surface elides under the same
gate (per
[Spec 009 §Production builds](../../../spec/009-Instrumentation.md#production-builds-zero-overhead-zero-code))
so even an accidentally-included preload would find the
register-epoch-cb! call resolve to a no-op.

## Standalone via MCP

### Mechanism

The MCP server (`tools/causa-mcp/`, per [`010-MCP-Server.md`](./010-MCP-Server.md))
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
| Want a second monitor for Causa | In-app panel + `(causa/popout!)` |
| Want my AI to inspect / time-travel programmatically | Causa-MCP (configure in agent host) |
| Want to debug a colleague's browser | Out of scope at v1.0 |
| Want to debug a mobile browser | Out of scope at v1.0 |

The 95% of cases — local development with in-app inspection — is
solved by the in-app true-inline panel. The MCP server covers the
agent-driven case. The remaining 5% (cross-machine, mobile) is
explicitly out of scope at v1.0.
