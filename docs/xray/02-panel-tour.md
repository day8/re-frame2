# 2. Panel tour

Xray is open and you need to find your way around it: where the events are, what each tab answers, when Static mode is the better view, and which tab to open first.

## The shape of the shell

![The standard-epochs testbed with Xray open beside it, and Xray's four parts numbered: 1 the ribbon, 2 the event list, 3 the tab strip, 4 the detail panel](../images/xray/xray-tutorial-shell.png)

In Dynamic mode Xray has four parts, numbered in the screenshot:

1. The **ribbon** chooses what you are looking at: the frame, the mode, filters and settings.
2. The **event list** shows recent epochs for the selected frame.
3. The **tab strip** chooses which view of the epoch you want: Epoch, app-db, Views, Trace, Machine, Routes, Resources, Graph, Frames or Fresco.
4. The **detail panel** shows the selected tab.

The event list chooses what the event tabs show. Click an event and Epoch, app-db, Views, Trace, Machine and Routes all read that one epoch. Resources, Graph and Frames read the app's current structure and do not follow the event you pick. Fresco ranks views over its retained window, and only its Causal view follows the event.

## The ribbon

The ribbon sets what you are looking at. It does not show any diagnosis itself.

- **Mode** switches Dynamic and Static.
- **Frame** chooses which re-frame2 frame you are observing.
- **+ filter** hides or shows events in the list by event id; see [Filters](#filters) below.
- **‹ › »** step to the previous or next event, and **»** returns to the newest. [Time-travel scrubbing](03-time-travel.md) covers them.
- **☾** and **☀** switch between the light and dark theme.
- **⛶** pops Xray out into a second window.
- **⚙** opens Settings, described [below](#settings-and-the-command-palette).
- **✕** hides the panel without tearing down the mounted tree.
- **🔇 N** appears once you have muted event types, and opens the list of mutes. **● REDACTED N** appears when the privacy setting has held back sensitive trace events.

Frame selection matters in real apps. If your page has several isolated frames, the same event id can mean different state and different epoch history in each frame. Pick the frame first, then debug. The picker lists your app's frames, never Xray's own, and reads **Frame** until one is chosen.

## The event list

The event list, headed **Event History**, shows recent epochs for the selected frame, and new events keep arriving. While Xray follows the newest event, the tabs move with it. Clicking an older event focuses it: the app keeps running, but the tabs stay on the event you chose until you follow the newest again.

Start here when the symptom just happened and you want to know which event caused it.

Each row shows the event id, where the dispatch came from, a timestamp and the handler's duration. The source reads `ui` for a dispatch from your own code, or names the part of the runtime that dispatched it, such as `fx-dispatch` for an event another handler queued, `http` or `router`. Hover a row for the full event vector and its frame. A row whose epoch recorded an error or a warning is washed pink. Drag the edge under the list to give it more height, and drag Xray's left edge to make the whole panel wider.

### Filters

Real apps produce noise. Filter pills include or exclude event ids so the list stays readable. Press **+ filter** in the ribbon to add one, or right-click a row and choose **Always hide this event-type…**; **Mute** on the same menu hides that event id too.

A pattern is an event id or part of one:

| Pattern | Matches |
| --- | --- |
| `:auth/login` | exactly that event id |
| `:auth/*` | every id in the `auth` namespace |
| `:auth` | the id `:auth` and every id in the `auth` namespace |
| `login` | every id containing `login` |

With no IN pills, the list shows everything no OUT pill matches. With IN pills, it shows only events matching one of them, minus the OUT matches.

An event whose epoch errored stays in the list even when a pill or a mute would hide it; its tooltip reads "⚠ shown because it errored — a filter would normally hide it". A warning does not keep a row visible. The `:rf.xray/filters-auto-hide-error-overrides?` setting turns that off ([Configuration keys](api/config-keys.md#filters-cluster)).

Pills last for the page's life. A reload starts unfiltered, unless the host seeds a starting set with `:rf.xray/filters`. Mutes are listed under the ribbon's 🔇 count.

Some habits that help:

- Exclude high-volume housekeeping events when chasing a user action.
- Include only one feature's namespace when debugging that feature.
- Clear your filters before deciding an event did not happen.

## The Dynamic tabs

Ten tabs ship in Dynamic mode. Epoch, app-db, Views and Trace are the ones most debugging sessions use. Machine and Routes show the focused event's effect on a machine or on navigation. Resources, Graph, Frames and Fresco answer narrower questions about the app's structure.

![Xray with an older event focused, numbered: 1 the focused event, 2 the strip counting newer events, 3 the step and follow buttons, 4 the Epoch tab's numbered steps, 5 the Reset button](../images/xray/xray-tutorial-epoch.png)

### Epoch

Open Epoch first. It lists what happened during the event, in order, as numbered steps (4 in the screenshot): DISPATCH (the event vector and who dispatched it), RECORDABLE COEFFECTS, INTERCEPTORS, EVENT HANDLER (the handler's source and the `:db` and `:fx` it returned, with each changed value marked `← was`), FLOW, EFFECT HANDLERS, SUBSCRIPTIONS and VIEWS. A step with nothing to show is left out, and an error or schema violation appears inside the step where it happened.

Read Epoch when you want the whole event explained without reconstructing it from raw trace records.

### app-db

Open app-db when the question is state. The tab shows the frame's state as the focused event left it, with each value that event changed marked `← was` and its old value. [9. The app-db tab](09-app-db-diff.md) is the chapter.

### Views

Open Views when the page looks wrong or slow. It shows which subscriptions recomputed and which views rendered, and why: a subscription changed, the props changed, or the view was mounted or unmounted.

![The Views tab's Reactive Flow graph, numbered: 1 app-db, 2 the subscription that recomputed, 3 the view that re-rendered and why](../images/xray/xray-tutorial-views.png)

The Reactive Flow graph runs from app-db (1) through the subscriptions that recomputed (2) to the views that re-rendered (3). Each view is marked with why it re-rendered and how long it took. Click a node to open its source; hover a view to highlight it on the page. Below the graph, Unmounted Views and Destroyed Subscriptions list what the epoch tore down. When nothing reacted, the graph reads "No subs subscribed to changed paths · no views re-rendered."

### Trace

Open Trace when Epoch has summarised something you need to see exactly. Trace lists the focused epoch's raw runtime records in order: exact operation names, source coordinates, durations and full payloads. [4. Trace stream](04-trace-stream.md) is the chapter.

### Machine

Open Machine when the focused event touched a state machine. It shows what this event did to that machine. To browse every machine definition, use Static mode. [8. Machine inspector](08-machine-inspector.md) is the chapter.

### Routes

Open Routes when navigation is the problem. It shows the current route, what the focused event did to navigation and with what outcome, and the route table. [12. Routes](12-routes.md) is the chapter.

### Resources

Open Resources when the question is server data. It shows the frame's resources as they are now: what is registered, what is cached, what is in flight, and what loaded or invalidated each entry. It does not follow the event you pick. [13. Resources](13-resources.md) is the chapter.

### Graph

Open Graph when the question is where a value comes from and what feeds it. It draws every subscription, flow, resource, route fact and machine selector as one dependency graph, with its own static and live modes, and does not follow the event you pick. [10. Derivation graph](10-derivation-graph.md) is the chapter.

### Frames

Open Frames when you want to know which registrations a frame runs. It lists each live frame with the image it runs: how many descriptors and kinds it holds, any capabilities the frame requires, and its first `[kind id]` descriptors with where each one came from. Comparing two frames' lists explains why the same name resolves differently in two frames.

### Fresco

Open Fresco when a Fresco view re-rendered and you want to know why. Its six views show which views are mounted, which subscriptions they read, what was dispatched, what changed, which view is hot, and one dispatch followed from event to paint. The tab is always present, and each view says what an empty list means for it. [11. The Fresco tab](11-fresco-tab.md) is the chapter.

## Static mode

Static mode removes the event list. You are no longer asking what one event did; you are browsing what the app has registered.

Static tabs:

- **Machines**: registered machines and their topology. [8. Machine inspector](08-machine-inspector.md#static-machines) covers its simulator.
- **Routes**: the route table, and **Simulate URL** to see which route a URL would match. [12. Routes](12-routes.md#the-static-routes-tab) covers it.
- **Schemas**: the selected frame's app-db schemas, and the registered event and subscription schemas.
- **Flows**: registered flows and their inputs, with the app-db path each one writes.
- **Interceptors**: registered event chains and shared interceptors. Each interceptor is listed once, with how many chains use it.

Use Static mode when you want a map of the app, and Dynamic mode when you want to follow what happened.

## Settings and the command palette

The gear icon, or `,`, opens Settings. It has four tabs:

- **General**: whether Xray sits in the right rail or covers the page as a fullscreen overlay, whether it opens itself when an issue is observed, how many epochs it keeps per frame, and which editor source links open in.
- **Keybindings**: the shortcut table below, and a **Handle keys?** switch that stops Xray taking any keystrokes.
- **Buffer**: how many trace events each frame keeps, and **Clear buffer now**.
- **Diff**: whether view diffs highlight changed function references.

Ctrl+K (⌘K on macOS) opens the command palette. It searches panels, events, frames, handlers and commands, such as switching the theme or density, clearing the buffer, and popping Xray out.

## Keyboard

| Key | Does |
| --- | --- |
| Ctrl+Shift+C | Show or hide Xray |
| Ctrl+Shift+M (⌘⇧M) | Switch between Dynamic and Static mode |
| Ctrl+K (⌘K) | Open the command palette |
| `j` / `k` | Step to the previous / next event |
| `l` or Shift+G | Follow the newest event again |
| Space | Pause or resume following new events |
| `,` or `s` | Open Settings |
| Esc | Dismiss the open-in-editor hint |

The single-letter keys work only while focus is inside Xray and not in a text field. The letters in tab tooltips, such as "Trace (t)", are labels, not shortcuts. Inside Settings, `g`, `k`, `b` and `d` switch its tabs. A popped-out window takes every key except Ctrl+Shift+C.

## A typical session

1. Pick the frame.
2. Click the event.
3. Read Epoch.
4. Open app-db if the symptom is state, or Views if it is rendering.
5. Open Trace only if you need the raw records.
