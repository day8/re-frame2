# 2. Panel tour

Xray is open and you need to find your way around it: where the events are, what each tab answers, when Static mode is the better view, and which tab to open first.

## The shape of the shell

Xray's Dynamic shell has four layers:

```text
L1  ribbon       event steps, filters, frame, mode, theme,
                 pop-out, settings, close
L2  event list   recent epochs for the selected frame
L3  tabs         Epoch, app-db, Views, Trace, Machine, Routes,
                 Resources, Graph, Frames, Fresco
L4  detail       the selected tab's detail view
```

The event list chooses what the event tabs show. Click an event and Epoch, app-db, Views, Trace, Machine and Routes all read that one epoch. Resources, Graph and Frames read the app's live structure and do not follow the event you pick. Fresco ranks views over its retained window, and only its Causal view follows the event.

![The event list with an older event focused, the Dynamic tab bar, and the Epoch tab for that event](../images/xray/xray-tutorial-epoch.png)

## The ribbon

The ribbon sets what you are looking at. It does not show any diagnosis itself.

- **Mode** switches Dynamic and Static.
- **Frame** chooses which re-frame2 frame you are observing.
- **Filters** hide or show events in the list by event id.
- **Settings** opens the settings popup, described [below](#settings-and-the-command-palette).
- **Close** hides the panel without tearing down the mounted tree.
- **‹ › »** step to the previous or next event, and **»** returns to the newest. [Time-travel scrubbing](03-time-travel.md) covers them.
- **☾** and **☀** switch between the light and dark theme.
- **⛶** pops Xray out into a second window.
- **🔇 N** appears once you have muted event types, and opens the list of mutes. **● REDACTED N** appears when the privacy setting has held back sensitive trace events.

Frame selection matters in real apps. If your page has several isolated frames, the same event id can mean different state and different epoch history in each frame. Pick the frame first, then debug. The picker lists your app's frames, never Xray's own, and reads **Frame** until one is chosen.

## The event list

The event list, headed **Event History**, shows recent epochs for the selected frame, and new events keep arriving. While Xray follows the newest event, the tabs move with it. Clicking an older event focuses it: the app keeps running, but the tabs stay on the event you chose until you follow the newest again.

Start here when the symptom just happened and you want to know which event caused it.

Each row shows the event id, where the dispatch came from, a timestamp and the handler's duration. The source reads `ui` for a dispatch from your own code, or names the part of the runtime that dispatched it, such as `fx-dispatch` for an event another handler queued, `http` or `router`. Hover a row for the full event vector and its frame. A row whose epoch recorded an error or a warning is washed pink. Right-click a row to **Mute** its event id, or to **Always hide this event-type…** with a filter. Drag the edge under the list to give it more height, and drag Xray's left edge to make the whole panel wider.

## The Dynamic tabs

Ten tabs ship in Dynamic mode. Epoch, app-db, Views and Trace are the ones most debugging sessions use. Machine and Routes show the focused event's effect on a machine or on navigation. Resources, Graph, Frames and Fresco answer narrower questions about the app's structure.

### Epoch

Open Epoch first. It lists what happened during the event, in order, as numbered steps: DISPATCH (the event vector and who dispatched it), RECORDABLE COEFFECTS, INTERCEPTORS, EVENT HANDLER (the handler's source and the `:db` and `:fx` it returned, with each changed value marked `← was`), FLOW, EFFECT HANDLERS, SUBSCRIPTIONS and VIEWS. A step with nothing to show is left out, and an error or schema violation appears inside the step where it happened.

Read Epoch when you want the whole event explained without reconstructing it from raw trace records.

### app-db

Open app-db when the question is state. The tab shows the frame's state as the focused event left it, with each value that event changed marked `← was` and its old value. [9. The app-db tab](09-app-db-diff.md) is the chapter.

### Views

Open Views when the page looks wrong or slow. It shows which subscriptions recomputed and which views rendered, and why: a subscription changed, the props changed, or the view was mounted or unmounted.

The Reactive Flow graph runs from app-db through the subscriptions that recomputed to the views that re-rendered. Each view is marked with why it re-rendered and how long it took. Click a node to open its source; hover a view to highlight it on the page. Below the graph, Unmounted Views and Destroyed Subscriptions list what the epoch tore down. When nothing reacted, the graph reads "No subs subscribed to changed paths · no views re-rendered."

![The Views tab's Reactive Flow graph: app-db, the subscription that recomputed, and the view that re-rendered](../images/xray/xray-tutorial-views.png)

### Trace

Open Trace when Epoch has summarised something you need to see exactly. Trace lists the focused epoch's raw runtime records in order: exact operation names, source coordinates, durations and full payloads. [4. Trace stream](04-trace-stream.md) is the chapter.

### Machine

Open Machine when the focused event touched a state machine. It shows what this event did to that machine. To browse every machine definition, use Static mode. [8. Machine inspector](08-machine-inspector.md) is the chapter.

### Routes

Open Routes when navigation is the problem. The Dynamic Routes tab explains the focused epoch's route activity; the Static Routes tab is for browsing the registered route table and simulating how URLs rank.

The Dynamic tab has three sections. **Current route** shows the active route. **Navigation this epoch** shows the route the focused event navigated from and to, with its outcome. **Route table** lists every registered route and marks the current one. Each section says when it has nothing to show: "No active route.", "No route activity in this epoch." or "No routes registered in the host app."

### Resources

Open Resources when the question is server state. It is the lens on managed server state for the selected frame, and it does not follow the row you pick: the resource registry, live instances, in-flight work, invalidations, and the route-to-resource graph. Read-only.

The tab stacks one section per question, from the resource registry and live instances to "What is still running?", stale races, optimistic mutations and cache growth. Sensitive values show as `[redacted]`. An app that registers no resources reads "No resources registered in the host app."

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
- **Routes**: route catalogue and URL simulation. Type a URL into **Simulate URL** to see which routes match it and which one wins. Expand a row for its details, a **Simulate navigation** preview that dispatches nothing, and a **→ Dynamic** link to the Dynamic Routes tab.
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

## Xray inside Story

Story embeds Xray in its right rail, one panel at a time. A row of chips picks the panel (Epoch, App-db, Views, Trace, Machines or Routing), under a strip of the selected variant's recent events. Click an event in the strip to focus the panel on that epoch.

Each Story variant runs in its own frame, registered under the variant's id, and the rail watches the selected variant's frame. Select another variant and Xray follows it. **Pop out** opens the full shell, with every tab, in a second window.

Story switches off Xray's keyboard shortcuts so its own keys keep working. Inside Story, and in a shell popped out from it, use the mouse.

The usual way in is a failing variant. Story's Tests tab names the assertion that failed, and **open in Evidence →** opens the run's narrative at that step. Each step's beats carry **Xray: Epoch**, **Xray: App-db** and **Xray: Trace** links, which point the rail at that panel and that beat's epoch. [Xray, earned at failure](../story/06-xray-earned-at-failure.md) follows one failure from the Tests tab into Xray.

Story drives the panel through `focus!`, which any host can call to select a frame, an epoch and a tab. [Focusing a panel from a host](api/mount-control.md#focusing-a-panel-from-a-host) lists its keys.
