# 008-Embedding-Contract

Causa's default full-shell integration is an app-provided true-inline
layout host (`[data-rf-causa-host]`) described in
[`011-Launch-Modes.md`](./011-Launch-Modes.md). This file covers the
separate embedded-panel contract.

Causa is consumed by Story (per `spec/007-Stories.md` §6.7) as a
per-variant observability ribbon. This doc specifies the contract:
every Causa panel exports a React component (or hiccup-fn equivalent)
that can be embedded outside Causa's own chrome.

## The contract

Every panel exposes a public `Panel` component that accepts a props
map:

```clojure
{:frame    :story.auth/login         ;; which frame to observe
 :compact? true                      ;; reduced chrome — no sidebar, no header
 :height   320                       ;; in pixels (optional; default flex)
 :scope    {:dispatch-id-prefix ...} ;; optional filter to restrict the observation window
 :on-event #(...)}                   ;; optional callback when the panel emits an event (e.g. click-to-source)
```

### Props

| Prop | Required | Default | Meaning |
|---|---|---|---|
| `:frame` | yes | n/a | The frame-id this panel observes. The panel does not switch frames; the host owns frame selection. |
| `:compact?` | no | `false` | If true: no sidebar, no top strip, no bottom rail. The panel renders as a self-contained card. |
| `:height` | no | `nil` (flex) | Fixed height in pixels. Useful for Story variants that want a uniform ribbon height. |
| `:scope` | no | `nil` | A filter map narrowing what the panel observes. See §Scoping below. |
| `:on-event` | no | `nil` | A callback `(fn [event-map])` invoked when the panel emits a host-meaningful event (source-coord click, re-dispatch request, etc.). |

### Embedded posture

When embedded (`:compact? true`), the panel:

- Does **not** claim `Ctrl+Shift+C`.
- Does **not** open its own pop-out window.
- Does **not** show a "close" button.
- Does **not** render the sidebar, top strip, or bottom rail.
- **Does** render its own content area identically to the
  non-embedded version.
- **Does** respect the user's density / theme / keybinding preferences
  (the same localStorage settings drive both modes).

The panel **knows it's embedded** via the `:compact?` flag.

### Full-shell embed contract (Causa-as-Story-RHS)

When a host mounts the **full Causa shell** (not a single panel) as
its right-hand-side observability surface — Story does this per
[`tools/story/spec/Tool-Pair.md`](../../story/spec/Tool-Pair.md) §RHS —
the host MUST surrender Causa's global keybinding capture so its own
shortcuts (typically `Cmd/Ctrl+K` for the host's command palette) are
not swallowed by Causa's capture-phase listener:

```clojure
(causa-config/configure! {:launch.keybinding/enabled? false})
```

The slot is documented in [`015-Configuration.md`](./015-Configuration.md)
§`:launch.keybinding/enabled?`. Per rf2-4eyik (rf2-q7who Thread A —
embed-contract gap discovered via rf2-drprn). With the slot at `false`
Causa's `keybinding/attach!` short-circuits and no global listener
lands on `js/document`; the host's own bindings reach their handlers
unimpeded. Causa's other surfaces — the in-shell ribbon buttons,
explicit `(mount/open!)` / `(mount/toggle!)` calls, the `:rf.causa/*`
event surface — remain fully usable; only the window-level keystroke
capture is suppressed.

## Scoping

The `:scope` prop narrows the observation window:

```clojure
{:scope {:dispatch-id-prefix "story-variant-7c2-"  ;; observe only dispatches in this Story variant
         :since-time         <ms>                  ;; observe only events newer than this
         :only-origins       #{:app :story}        ;; filter by :origin axis
         :include-children?  true}}                ;; if true, also observe child cascades
```

Scoping rules:

- All scope keys are **optional**; absent means "no filter on this
  axis."
- Multiple scope keys are **AND-combined**.
- Scope applies to the panel's data feed: an embedded event-detail
  panel with `:dispatch-id-prefix "story-variant-..."` only shows
  events whose `:dispatch-id` starts with that prefix.
- Scope does **not** filter the trace bus itself — other Causa
  instances and other consumers see all events. The framework's
  emission is unchanged.

### Why scoping exists

Story renders multiple variants on one page. Each variant runs in
its own frame and emits its own dispatches; the embedded ribbon for
variant A must not show variant B's events. Scoping is the
mechanism.

## What ships embedded at v1.0

| Panel | v1.0 embeddable? |
|---|---|
| **Event detail** | Yes (the primary embed target for Story). |
| **Causality strip** | Yes. |
| **App-db inspector** | Yes (compact-mode renders the slice-centric view only; no full-tree escape). |
| **Issues ribbon** | Yes. |
| **Performance ribbon** | Yes. |
| Causality graph | v1.1 — graph rendering is expensive for many small embeds. |
| Machine inspector | v1.1 — would re-import `tools/machines-viz/` per embed. |
| Subscription graph | v1.1. |
| Schema timeline | v1.1. |
| Hydration debugger | Not embeddable (the panel is contextual to a specific hydration; doesn't compose). |
| Settings | Not embeddable. |

The v1.0 embed set covers Story's needs (per `spec/007-Stories.md`
§6.7 — Story embeds the **epoch panel** at v1.0). The wider v1.1
embed set covers Story's planned ribbon expansions.

## How Story wires it in

Per `tools/story/` (when it lands):

```clojure
(ns day8.story.variants
  (:require [re-frame.story :as story]
            [day8.re-frame2-causa.panels.event-detail :as causa-event]))

(story/reg-story-panel :story.auth/login
  {:panels
   [{:title  "Login form"
     :render (fn [variant-data] [login-form variant-data])}
    {:title  "Causa: events"
     :render (fn [variant-data]
               [causa-event/Panel
                {:frame    (:frame variant-data)
                 :compact? true
                 :height   320
                 :scope    {:dispatch-id-prefix (:scope-prefix variant-data)
                            :include-children?  true}}])}]})
```

The host (Story) controls layout; Causa supplies the content.

## Hook-style integration

For React-only hosts that don't render Reagent, Causa exposes a
plain-React surface:

```javascript
import {EventDetailPanel} from '@day8/re-frame2-causa/panels';

function StoryVariant() {
  return (
    <EventDetailPanel
      frame=":story.auth/login"
      compact
      height={320}
      scope={{dispatchIdPrefix: 'story-variant-7c2-'}}
    />
  );
}
```

The JS API kebab-cases / camelCases the props (`compact` for
`:compact?`, `dispatchIdPrefix` for `:dispatch-id-prefix`).

Internally the JS surface delegates to the same Reagent components;
the JS wrapper is a thin adapter.

## What the host owns

When embedded, the host (Story) owns:

- **Frame selection.** The `:frame` prop is fixed by the host; Causa
  never re-binds it from inside the embedded panel.
- **Layout.** Where the embed goes on the page, its surrounding
  chrome, its size.
- **Lifecycle.** Mount / unmount on variant change. Causa's panels
  are pure with respect to their props; they tear down cleanly on
  unmount.
- **Event routing.** The host's `:on-event` callback decides what
  to do with embed-emitted events (open a side panel, log,
  ignore).

What Causa owns:

- **The panel's content.** What's rendered inside the embed.
- **Internal state** (selected slice, scroll position, expand/collapse
  state) — local to the panel instance, not persisted.
- **Live updates** from the trace bus / epoch history, scoped by the
  `:scope` prop.

## State isolation (Option-C frame-provider)

Causa's panels mount **inside the host's React tree** so embedding is
zero-config — drop a `[Panel ...]` into Story / your own layout and it
renders. But Causa's *state* must never bleed into the host's app-db,
its subs, or its dispatch queue. That isolation is achieved by an
internal frame-provider wrapper; see [`011-Launch-Modes.md`](./011-Launch-Modes.md)
for the in-app overlay context and [`007-UX-IA.md`](./007-UX-IA.md)
for shell layout. The mechanism, locked under rf2-tijr (2026-05-12):

### Frame-provider wraps every panel

Each public `Panel` component opens with an internal
`[rf/frame-provider {:frame :rf/causa} ...]`. Descendant subscriptions
and dispatches re-anchor to the `:rf/causa` frame, *not* the host's
`:rf/default` (or whatever frame the host's tree is providing).
Consequences:

- **App-db isolated.** `:rf.causa/buffer-cleared` writes touch
  `:rf/causa`'s db; the host app-db is untouched.
- **Subs isolated.** A panel sub like `:rf.causa/trace-buffer` reads
  `:rf/causa`'s db.
- **Dispatches isolated.** Events fired from inside the panel run on
  `:rf/causa`'s event queue and interceptor chain.
- **Machines isolated.** Causa's machines live in `:rf/causa` and
  don't share state with host machines.

Host code never sees `:rf/causa`; the wrapper is an implementation
detail of `Panel`. Story (and any other host) embeds Causa with no
awareness of the frame split.

### Registry-key isolation via `:rf.causa/*` prefix

The registrar is **process-global** — frames isolate state but share
the registrar's `{kind id}` keyspace. Causa avoids collisions by
namespacing every event-id, sub-id, fx-id, and cofx-id under
`:rf.causa/*`. A host registering `:user/login` and Causa registering
`:rf.causa/select-tab` cannot stamp on each other; the prefix is the
contract.

The convention is enforced by code review and by the registry
namespace docstring (see `tools/causa/src/day8/re_frame2_causa/registry.cljs`).

### Adapter resolution

Causa renders pure hiccup; all four supported substrates (Reagent,
Reagent-slim, UIx, Helix) accept the same hiccup shape, so the
component code itself is substrate-agnostic. Where Causa needs an
imperative escape hatch (canvas refs, mount-lifecycle hooks for large
list virtualisation, etc.) it resolves the active adapter via
`re-frame.substrate.adapter/current-adapter` and dispatches on the
returned keyword. These escape-hatch sites are bounded — roughly five
of them across the codebase — and each lives next to the component
that needs it, not in a central shim layer.

## What this doesn't do

- **No two-way binding.** The host doesn't push state into Causa
  beyond the props; Causa doesn't push state back beyond
  `:on-event` callbacks.
- **No cross-embed coordination.** Two embedded panels on the same
  page do not share state. Each panel reads the trace bus
  independently. (The trace bus emits once; multiple subscribers see
  the same event — the per-panel scope filters happen client-side.)
- **No standalone styling overrides.** Embedded panels use Causa's
  theme tokens. The host can wrap them in a container that overrides
  CSS variables but cannot patch panel internals.
- **No security boundary.** Causa runs in the host page's JS realm.
  If the host is untrusted, do not embed Causa.

## Future: third-party panels

v1.0 is **first-party panels only.** No plugin API, no panel registry.
Third-party-extensible panels are a v2.0 design discussion.

The current contract leaves room: every panel is already a
self-contained component with a props-only interface. A future
plugin registry would `:require` a third-party namespace and register
it under a new sidebar entry with the same `Panel` shape.

No commitment is made about the third-party plugin surface
shape — the embedding contract above is for the **canonical
first-party panels**, not for any future third-party kind.

## Vision — Story ↔ Causa preset round-tripping

**Bug class:** "I built a Story variant that captures a specific
debugging posture (filters set, tab selected, pinned epoch); when
someone else opens that story, they should land in the same posture."

The Story embedding contract (above) covers the **structural** props
the host passes to a panel. The next-step affordance is **deep preset
round-tripping**: when a Story variant declares `{:causa/preset {…}}`,
Causa restores **the full visible state** on mount:

- **Selected tab** (`:tab :machines`).
- **Active filters** (`:filters {:in […] :out […]}`).
- **Focused machine** + **selected instance** (for Machines tab
  embeds).
- **Pinned cascade** (`:pinned-dispatch-id <id>`) — restored if the
  cascade is still in the trace buffer at story mount; otherwise
  surfaced as a "pinned cascade aged out — re-run to recapture" hint.
- **Settings sub-state** — density, theme override per story.

The preset is **per-Story** (not per-Causa-instance); each story
carries its own preset; switching stories switches the preset.

## Vision — per-story Causa state snapshots via share-URL

Story already supports share-URLs that round-trip the story state.
Causa extends this: when a developer pins an interesting debugging
posture in a Story variant, the share-URL captures:

- The Story variant (existing).
- The Causa preset (above).
- A **trace snapshot** — the last N cascades up to and including the
  pinned focused cascade, serialised into the URL fragment (when small
  enough) or fetched from a session-local cache when the URL refers
  to a recent same-session pin.

The recipient opens the share-URL → Story renders the variant → Causa
mounts with the preset → the trace snapshot is loaded into Causa's
read-only buffer → they see exactly what the sender saw.

The snapshot is **read-only** (rewinds work; new dispatches do not
mutate the snapshot — the story's app-db is the source of truth). Lock
#4 (no session export) is preserved by scope: this is a Story-shared
state, not a free-standing Causa export.
