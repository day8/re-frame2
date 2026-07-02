# Debug with Xray

It's 11pm and the app is wrong. A value that should be `3` is `7`, or a button is disabled that shouldn't be. Whatever caused it happened several clicks ago. Here's the good news: you don't need to reproduce it, and you don't need a single `console.log`. Every [event](../glossary.md#event) the app processed is already recorded — the state change, the [effects](../glossary.md#effect), the [subscription](../glossary.md#subscription) recomputes, and the [views](../glossary.md#view) that re-rendered.

> **Your app's state isn't a mystery to reconstruct from console.log; it's a ledger you can read.**

Read this page straight through and you get a smooth path: get Xray open, walk the ledger backwards to the event that broke your state, then meet the rest of the panels one at a time. The callouts off to the side are optional extras — a JavaScript analogy, a note for re-frame v1 veterans, the deeper mechanics — there when you want them, skippable when you don't.

A word on vocabulary, because three terms shape Xray's whole layout. When you [dispatch](../glossary.md#dispatch) an [**event**](../glossary.md#event), it sets off the [**event cascade**](../glossary.md#event-cascade) — the fixed run of handler → commit → effects → subscriptions → render ([Events & the cascade](../concepts/events-and-the-cascade.md) owns this) — and that cascade leaves behind one [**epoch**](../glossary.md#epoch), the before/after record on the [trace stream](../glossary.md#trace-stream). So event, cascade, and epoch are three angles on one moment: *what arrived*, *everything that happened in response*, and *the record it left in the timeline*. Everything in Xray hangs off them — the event list is the ledger of epochs, and every tab is a lens on one.

One more term, because multi-frame apps get a picker: a [**frame**](../glossary.md#frame) is one isolated, running instance of your app, with its own app-db. It isolates *state*, not [registrations](../glossary.md#registrar) — so every frame resolves against the same handlers and subs ([Frames](../concepts/frames.md) covers the detail).

??? info "For JavaScript developers"

    If you know Redux DevTools, Xray is that same idea: an action ledger, a state diff, time travel. What Xray adds is the rest of the cascade — not just "which action, what state", but which effects fired, which subscriptions recomputed, which views re-rendered, and which line of *your* code each one came from.

??? info "From re-frame v1"

    Xray is the successor to re-frame-10x: the same epoch-centric debugging, rebuilt on re-frame2's structured [trace stream](../glossary.md#trace-stream). If you lived in 10x, the muscle memory transfers — same instinct to step the event list and watch the db diff.

## Step 1: get Xray open

Xray ships as a dev-build preload. Add it to your dev build only:

```clojure
;; shadow-cljs.edn — dev build only
{:builds {:app {:devtools {:preloads [day8.re-frame2-xray.preload]}}}}
```

The preload registers Xray's trace and epoch listeners, installs the keyboard listener and the browser API, then auto-opens once your app boots — specifically, once `rf/init!` has wired up the rendering substrate (Reagent, UIx, or Helix).

Xray mounts inline into a page element you mark with `data-rf-xray-host`. This is a normal flex column in your layout — not an overlay, not a body-padding dock. Drop one `<aside>` into your shell:

```html
<div class="app-shell">
  <div id="app"></div>
  <aside data-rf-xray-host></aside>
</div>
```

```css
.app-shell { display: flex; min-height: 100vh; }

/* The Xray host: a right-side flex column Xray renders into. */
[data-rf-xray-host] {
  flex: 0 0 var(--rf-xray-inline-width, 560px);
  min-width: 320px;
  box-sizing: border-box;
  border-left: 1px solid #2a2a2a;
}

/* Your app fills the rest. min-width: 0 lets it shrink. */
#app { flex: 1; min-width: 0; }
```

That's the whole contract — four declarations on the host, and Xray opens. Your app stays visible and clickable to the left because ordinary layout owns the relationship; nobody is fighting over z-index. Press `Ctrl+Shift+C` to toggle the shell (it's a CSS `display` flip, not a remount).

If Xray can't find the host element when it tries to open, it doesn't fail silently — it logs an actionable missing-host diagnostic on the console telling you to add `[data-rf-xray-host]`, and even hands you the snippet to paste.

!!! warning "Gotcha — Xray is desktop-only"

    Below a 600px viewport (phones) it refuses to mount: the DOM root is created but the visible UI is a single message explaining it's desktop-only. Between 600px and 900px it narrows; at 900px and below it takes the full viewport width. So if Xray seems to "vanish" on a small window, that's the floor — widen the window, don't hunt for a bug.

!!! note "No resize CSS required"

    You do *not* wire any resize CSS: Xray auto-injects its own drag handle on the host's left edge (drag to widen, double-click to reset, arrow keys when focused). Width is driven by the `--rf-xray-inline-width` custom property (default `560px`); override it anywhere up the cascade — `:root { --rf-xray-inline-width: 720px; }` — and a user drag writes back to the same property.

!!! note "A tool-only page that shouldn't reserve app real estate?"

    Suppress just the auto-open before `rf/init!` and drive Xray explicitly instead:

    ```clojure
    (require '[day8.re-frame2-xray.config :as xray-config])
    (xray-config/configure! {:rf.xray/auto-open? false})
    ```

    This disables only the page-load open. The collectors, the keybinding, and explicit `(day8.re-frame2-xray.core/open!)` / `toggle!` calls all stay live. (Story's browser-test canvases use this — they don't want a permanent Xray column.)

None of this exists in production, which is by design. Xray and the trace machinery it reads are [elided](../glossary.md#elide) entirely in `:advanced` builds — remove the consumer and the framework is byte-for-byte unchanged. See [Configure dev and production builds](configure-dev-and-prod.md).

## Step 2: the one mental model — one event in, full insight out

Before you debug anything, internalise the single idea the whole tool is built on. The shell has four layers stacked top to bottom: a **ribbon** (scope + nav controls), the **event list** (the ledger), a **tab bar**, and a **detail panel**. You select one event in the list, and every tab rebinds to it. Each tab is just a different *lens* on that one event: one shows the state diff, one shows the effects, one shows the renders, and so on.

!!! note "One event in, full insight out"

    This is the whole thing. There is no "current state" view floating free of an event — every panel is bound to the *one focused event* in the list. Move the focus and the whole shell re-points at that moment. Once that clicks, the rest of Xray is just choosing which lens (tab) you want on the moment you've focused.

Working in a multi-frame app? Pick the [frame](../glossary.md#frame) you're inspecting in the ribbon's frame picker. (The picker hides Xray's own tool frame by default; flip *Settings → General → "Show tool frames in picker"* if you ever need to see it.)

## Step 3: find the event that broke state

You're staring at bad state, and you don't yet know which event put it there. Here's the core loop — the thing you'll do ten times a day.

1. **Open Xray** (`Ctrl+Shift+C`). It lands on the latest event. Press `Space` to pause the live feed so new dispatches stop shoving the list out from under you.
2. **Press `a`** for the App-db tab. You'll see the diff this one event made — app-db before against app-db after. Each event shows only its own delta, never a cumulative pile, so the change is easy to read.
3. **Walk backwards in time** with `j` (it steps to the older event; `k` steps back toward the newest — or use the ribbon's `◀` `▶` nav buttons), watching the diff. Ask one small question per step: *did this event write the bad value?*
4. **Stop at the first event where the bad value appears.** That's your culprit — the epoch that wrote it. Press `e` for the Epoch tab, which lays out the full [event cascade](../glossary.md#event-cascade) in order: dispatch site, event vector, [coeffects](../glossary.md#coeffect), handler, then the effects that fired (with a wire-boundary diff per managed effect) and the subscriptions and views that followed.

That's it. Four keys — `Space`, `a`, `j`, `e` — find most bugs.

!!! note "Why walking backwards works"

    Each event's diff is *just that event's* delta. So the question at every step is purely local — "did this one write the bad value?" — never "what's the running total of everything so far?" You're bisecting a timeline of small, independent changes. The first step where the bad value appears in the *after* column is, by definition, the event that wrote it. No reasoning about accumulation required.

Once the core loop is muscle memory, three shortcuts make it faster:

- **Loud breakages tint pink.** An epoch that carries an issue — a thrown exception, a schema violation, a hydration mismatch, a perf-budget overrun, or a raw `console.error` from your app — tints its row pink in the list, so you can scan for the tint instead of stepping one by one. The exception's message and data show up inline in the Epoch panel for that event (there's no separate "Issues" tab — issues surface where the event already is). Want Xray to *come to you*? Flip *Settings → General → "Auto-open on error"* (off by default — Xray won't interrupt you in your own app unasked) and it pops open the moment the first issue lands.
- **Drowning in noise? Add a filter pill.** The events ribbon (which slides open the moment you add your first filter) takes filter-IN pills (show only matches, green `+`) and filter-OUT pills (hide matches, magenta `×`) keyed on an event-id or a wildcard like `:mouse/*`. They compose as `(any IN) AND NOT (any OUT)`, and a count of `N events filtered out` shows on the right so a filter can never silently hide the truth. Filters are transient — they reset on reload, so a stale pill never bites you in a fresh session.
- **Chase the cause across dispatches.** Was the culprit handler dispatched by another event's `:dispatch` effect? Then keep walking — its cascade lists the follow-up dispatches it queued, so the causal chain stays legible. ([Events and the cascade](../concepts/events-and-the-cascade.md) is the model behind this.)

Press `L` to snap back to live when you're done (`G` does the same — "go to head").

!!! warning "Gotcha"

    The ledger keeps the most recent 50 cascades per frame by default, and older ones age out. You can raise that depth in *Settings → Buffer* (the `:cascades-retained` knob) if a long session needs more history — at a heap cost. The same tab carries a "Clear buffer now" button if you want to wipe the slate mid-session.

## Step 4: see why a subscription recomputed

The next-most-common bug has a different symptom: a view re-rendered, or it shows a wrong derived value, and you don't know why.

Focus the suspect event and press `v` for the Views tab. It lists every [subscription](../glossary.md#subscription) that ran during this cascade, one row each. Each row flags two things: whether the sub's **value actually changed**, and whether the recompute was **driven by an upstream sub** — and if so, that upstream sub is named right on the row. Below that, you'll see the views that re-rendered this epoch. Hover a view row and it highlights that view's rendered DOM in the page, so "which view is this row?" never needs guessing.

Read it two ways:

- **Bottom-up to answer *why did this view re-render?*** — the view, the sub that triggered it, the upstream subs behind that, and finally the app-db path that changed this epoch (one keypress away on `a`).
- **Top-down to answer *why is this sub returning the wrong value?*** — find the sub's row, check whether its value changed this epoch, and follow its source chip into the registration code.

This is the [derivation graph](../glossary.md#the-derivation-graph) made visible: app-db at the root, views at the leaves, subscriptions as the nodes between. If the verdict turns out to be "correct, just too often", head to [Find and fix a slow view](fix-a-slow-view.md).

??? info "For JavaScript developers"

    Same question, deeper answer. React can tell you a component re-rendered and which prop changed. Xray follows the chain the whole way down: the view, the subscription that triggered it, the upstream subscriptions behind *that*, and finally the app-db path that actually moved this epoch. You read a causal chain, not just a leaf.

## Step 5: the other lenses

App-db, Epoch and Views cover the everyday bugs, and you'll live in them. But the tab bar carries more lenses — each answering a specific question about the focused event. The full roster, left to right (the first six carry a single-letter mnemonic, in parentheses):

| Tab | The question it answers |
|---|---|
| **Epoch** (`e`) | What did this event *do*? — the full event cascade. |
| **app-db** (`a`) | What *changed* because of this event? — the sectioned diff. |
| **Views** (`v`) | Why did these views re-render? — the app-db → subs → views chain. |
| **Trace** (`t`) | What raw [trace events](../glossary.md#trace-event) fired in this cascade? — the readable-line timeline, colour-banded by op family. |
| **Machine** (`m`) | What did this event do to my state machines? — transitions, guards, actions, `:after` rings, the cancellation cascade. Blank when the focused event touched no machine. |
| **Routes** (`r`) | What did this event do to my routes? — current route, this-epoch navigation, the registered route table. |
| **Resources** | What's the lifecycle state of my resources? — long-lived server-state reads, retained values, teardown. |
| **Graph** | How does my reactive graph hang together? — the cross-family derivation graph (subs, flows, machines, routes) as one picture. |
| **Modules** | How is my app's runtime wired? — the `image → frame` structure for multi-frame / [image](../glossary.md#image)-composed apps. |

Each tab is the same one-event-bound lens: focus an event, switch tab, read that projection of *that* moment. The first six carry a letter mnemonic (`e` `a` `v` `t` `m` `r`) and a number (`1`–`6`); `Ctrl+→` / `Ctrl+←` cycle through the whole strip. The last three — Resources, Graph and Modules — landed later and have no keystroke of their own yet, so you click them (or jump by name from the command palette, `Ctrl/Cmd+K`).

## Step 6: rewind to the bad epoch

Everything so far is **passive** — selecting an old event rebases the panels to show that moment, but your running app doesn't move. Sometimes you want the app *itself* back at that moment — so you can poke at it live, drive forward from there, or show a colleague. That's what rewind is for.

Focus the bad epoch and press `r` while the event list has focus (or click the epoch's `Reset` control on the tab ribbon). This rewinds the live app to the state the epoch left behind — app-db as it was *just after* that event committed, "as if the event had only now happened". It's a real write: the frame's whole state is restored atomically — app-db and the framework-owned [runtime-db](../glossary.md#runtime-db) beside it, machine snapshots and route included. Subscriptions recompute, and the UI repaints as it was. A rewind that can't be performed — because the epoch has aged out of the buffer, for instance — is refused with a stated reason rather than silently doing nothing.

!!! note "Rewind vs re-dispatch"

    `r` rewinds *state* to an epoch. Its capital sibling, `R`, **re-dispatches** the focused event — it runs that same event vector again *now*, against current state, appending a fresh cascade to the ledger. Use `r` to get back to a moment; use `R` to re-run a single event and watch its cascade afresh (handy right after you hot-swap its handler at the REPL). Pinning a cascade you want to keep referring to is `*`.

??? info "For JavaScript developers"

    Effects that already escaped — HTTP requests sent, writes to `localStorage` — happened and stay happened. Rewind is "put the app's *state* back to that point", not "undo the universe". If you know Redux DevTools' time travel, it's the same boundary: the store rewinds; side effects that already left the building do not come back.

## Step 7: jump to source from any panel

Everything in the panels that came from your code carries a source coordinate: the dispatch site, the handler, a sub's registration, a view. Each renders as a clickable chip. Click it and your editor opens at that file and line. With focus in the event list, `o` opens the focused event's dispatch site.

For most setups this Just Works with no configuration. There are two ways the jump can happen, and Xray prefers the first:

1. **A dev-server endpoint (zero-config, preferred).** If your shadow-cljs `:dev-http` server is wired with re-frame2's open-in-editor handler, Xray `POST`s the file and line to it and the server launches your editor locally — the same trick Vite and react-dev-utils use. No editor configuration, no on-disk path baked into the bundle.
2. **An `editor://` URI (fallback).** When no dev server answers (a static export, a non-shadow host, production-mode inspection), Xray falls back to building an `editor://file/<path>:<line>:<column>` URI and handing it to the browser.

!!! note "Pointing the chips at a different editor"

    The default for the URI path is VS Code. To point the chips elsewhere, set it at boot:

    ```clojure
    (require '[day8.re-frame2-xray.config :as xray-config])

    (xray-config/configure! {:rf.xray/editor :cursor})
    ;; :vscode (default) · :cursor · :windsurf · :zed · :idea
    ;; or {:custom "<uri-template>"} for an editor Xray doesn't know natively
    ```

    The custom template takes `{path}` / `{file}` / `{line}` / `{column}` placeholders, substituted at click time. An unknown editor keyword falls back to `:vscode` (so a typo still yields a clickable URI), and a source-coord with no file hides its chip entirely.

    If your editor's URI path needs an absolute on-disk root prepended (because your source-coords are classpath-relative and your editor can't resolve them), set it once:

    ```clojure
    (xray-config/configure! {:rf.xray/project-root "C:/Users/me/code/my-app"})
    ```

    This is only needed on the fallback URI path — the dev-server endpoint resolves paths on the dev machine at request time, so it needs no `:project-root`.

!!! warning "Gotcha — nothing happens when you click a chip?"

    The default `vscode://` scheme only fires if VS Code is actually registered as a handler on your machine — otherwise the OS silently swallows the navigation and JavaScript can't even see it failed. Xray notices this case and, rather than leaving you clicking a dead chip, pops a small "No editor configured" toast with an **Open Settings** button that lands you on the editor picker. Set `:rf.xray/editor` once (above) — or, on a shared machine, pick yours per-operator in *Settings → General → "Click-to-source links open in"*, which overrides the host default for your browser only — and the chips light up.

## Static mode: inspect the registry without an event

Everything above is *dynamic* mode — Xray reading the live event stream. Sometimes you don't have a bug in flight; you just want to ask "what's actually *registered* right now?" — which events, subscriptions, effects, machines, and routes the running app knows about, independent of any cascade.

That's **Static mode**. Toggle it with `Cmd/Ctrl+Shift+M`, or pick it from the `Dynamic / Static ▾` dropdown in the ribbon. The shell swaps to registry-browse surfaces: a catalogue of every [registration](../glossary.md#registrar), a machine explorer you can step through interactively, and the schema timeline. There's no event list here — you're browsing the app's wiring, not its history. (Static mode is always available; the mode choice persists across reloads, unlike filters.)

## The keys you'll actually use

Xray is keyboard-first, but you only need a handful of keys for everything above. They live in two scopes: anywhere (global), and while the event list has focus.

| Key | Scope | What it does |
|---|---|---|
| `Ctrl+Shift+C` | global | Show / hide the Xray shell |
| `Space` | event list | Pause / resume the live feed |
| `j` / `k` | event list | Step to the older / newer event |
| `L` or `G` | event list | Snap back to live (follow the head) |
| `a` / `e` / `v` | tab bar | Jump to App-db / Epoch / Views |
| `t` / `m` / `r` | tab bar | Jump to Trace / Machine / Routes |
| `1`–`6` | tab bar | Jump to the first six tabs by number |
| `r` | event list | Rewind the live app to the focused epoch |
| `R` | event list | Re-dispatch the focused event (fresh cascade) |
| `*` | event list | Pin / unpin the focused cascade |
| `o` | event list | Open the focused event's dispatch site in your editor |
| `/` | event list | Focus the add-filter input |
| `Cmd/Ctrl+Shift+M` | global | Toggle Dynamic / Static mode |
| `Cmd/Ctrl+K` | global | Command palette (everything by name) |
| `,` or `s` | global | Settings |

Don't memorise the table — `Cmd/Ctrl+K` opens a command palette where you can find any action by typing its name, and `?` pops a cheat-sheet. The keys are just the shortcuts you'll wear in over time.

!!! note "`r` does two things — but never ambiguously"

    When focus is *in the event list*, `r` rewinds. When focus is *elsewhere*, `r` is the Routes-tab mnemonic. The list's own key handler wins when you're in the list; the tab-bar mnemonic wins otherwise. Same key, two scopes, no collision. Likewise `s`: the tab mnemonic in the tab bar, Settings globally.

## When Xray isn't the tool

!!! note "The incident is in production"

    Xray is dev-only by construction, so there is nothing to open. Production failures reach you through the always-on error surface instead: [Report errors in production](report-errors-in-production.md).

!!! note "The panel shows `REDACTED`"

    Values you've classified as sensitive render redacted in Xray, exactly as they do on every other surface. That's working as intended, not a bug in the tool. By default Xray fails closed — `:sensitive?` events are dropped before they ever reach the buffer, and a `[● REDACTED N]` counter tells you how many. If you genuinely need to see them on a trusted-local machine, a host can widen the egress profile to `:rf.egress/local-raw` (`(xray-config/configure! {:rf.xray/egress-profile :rf.egress/local-raw})`) — that reveal is itself recorded in the trace, and narrowing back scrubs the buffer. [Keep secrets and large things out of traces](keep-secrets-out-of-traces.md) covers the classification.

??? note "Going deeper"

    Xray is a pure consumer of re-frame2's structured [trace stream](../glossary.md#trace-stream) — it holds no privileged hook into the runtime, only the same instrumentation API any tool may read (Spec 009). That is why it compiles away cleanly in production: remove the consumer and the framework is unchanged. The "one event in, full insight out" model is the trace stream's natural shape made visible — each cascade is an immutable, fully-described value (dispatch, coeffects, effects, the reactive recompute graph), so every panel is just a different projection of the *same* data. [Time-travel](../glossary.md#time-travel) rewind follows from the same property: because each epoch's state transition is a value, restoring one is a write, not a replay. The boundary rewind respects — state comes back, escaped effects do not — is exactly the line between the pure reduction (recoverable) and the effects at its edges (already in the world).
