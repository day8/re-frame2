# 2. Panel Tour

You opened Xray and now you need orientation. This chapter teaches the shell as a working debugger: where the timeline lives, what the Dynamic tabs answer, when Static mode is better, and which panel to open first.

## The Shape Of The Shell

Xray's Dynamic shell has four layers:

```text
L1  ribbon       event steps, filters, frame, mode, theme,
                 pop-out, settings, close
L2  event spine  recent epochs for the selected frame
L3  tabs         Epoch, app-db, Views, Trace, Machine, Routes,
                 Resources, Graph, Frames, Fresco
L4  detail       the selected tab's detail view
```

The event spine is the load-bearing piece. It is not a decorative timeline; it is the focus selector for the event lenses. Click an event row and Epoch, app-db, Views, Trace, Machine, and Routes read that same epoch. (Resources, Graph and Frames read live structure instead and do not follow the row you pick. Fresco ranks boundaries over its retained window, and only its Causal view follows the row.)

![The event spine with an older event focused, the Dynamic tab bar, and the Epoch tab for that event](../images/xray/xray-tutorial-epoch.png)

## The Ribbon

The ribbon is for scope, not diagnosis.

- **Mode** switches Dynamic and Static.
- **Frame** chooses which re-frame2 frame you are observing.
- **Filters** hide or show event rows by event id.
- **Settings** opens the settings popup, described [below](#settings-and-the-command-palette).
- **Close** hides the panel without tearing down the mounted tree.
- **‹ › »** step to the previous or next event, and **»** returns to the newest. [Time-travel scrubbing](03-time-travel.md) covers them.
- **☾** and **☀** switch between the light and dark theme.
- **⛶** pops Xray out into a second window.
- **🔇 N** appears once you have muted event types, and opens the list of mutes. **● REDACTED N** appears when the privacy setting has held back sensitive trace events.

Frame selection matters in real apps. If your page has multiple isolated frames, the same event id can mean different state and different epoch history in each frame. Pick the frame first, then debug. The picker lists your app's frames, never Xray's own, and reads **Frame** until one is chosen.

## The Event Spine

The spine lists recent epochs for the selected frame, and new rows always arrive. While Xray follows the newest event, the detail panels move with it. Clicking an older row puts you into a historical focus: the app can keep running, but the detail panels remain pointed at the row you chose until you follow the head again.

Use the spine when you know the symptom just happened and you want to answer: "Which event caused it?"

Each row shows the event id, where the dispatch came from, a timestamp and the handler's duration. The source reads `ui` for a dispatch from your own code, or names the part of the runtime that dispatched it, such as `fx-dispatch` for an event another handler queued, `http` or `router`. Hover a row for the full event vector and its frame. A row whose epoch recorded an error or a warning is washed pink. Right-click a row to **Mute** its event id, or to **Always hide this event-type…** with a filter. Drag the seam under the list to give it more height, and drag Xray's left edge to make the whole panel wider.

## The Dynamic Tabs

Ten tabs ship in Dynamic mode. The first six below are the daily ones and are
where a debugging session usually starts and ends; the last four answer
narrower questions and each has a chapter of its own.

### Epoch

Open Epoch first. It is the readable version of the cascade: dispatch, coeffects, interceptors, handler, app-db change, effects, subscriptions, views, schema checks, and any error or schema violation at the step where it happened.

This is the tab for "explain the whole thing to me without making me reconstruct it from raw rows."

### app-db

Open app-db when the question is state. The tab shows the frame's state as the focused event left it, with each value that event changed marked `← was` and its old value. It is read-only: good for seeing what changed, where it changed, and whether the state you expected is actually present. [9. The app-db tab](09-app-db-diff.md) is the chapter.

### Views

Open Views when the page looks wrong or slow. It shows the reactive side of the cascade: subscriptions and renders, with enough structure to see whether a view changed because a subscription changed, because props changed, or because the view was mounted or unmounted.

The Reactive Flow graph runs from app-db through the subscriptions that recomputed to the views that re-rendered. Each view is marked with why it re-rendered and how long it took. Click a node to open its source; hover a view to highlight it on the page. Below the graph, Unmounted Views and Destroyed Subscriptions list what the epoch tore down. When nothing reacted, the graph reads "No subs subscribed to changed paths · no views re-rendered."

![The Views tab's Reactive Flow graph: app-db, the subscription that recomputed, and the view that re-rendered](../images/xray/xray-tutorial-views.png)

### Trace

Open Trace when the friendly view is hiding too much. Trace is the flat, epoch-scoped feed of runtime records. It is excellent when you need exact ordering, exact operation names, source coordinates, durations, or a payload that was summarized elsewhere.

### Machine

Open Machine when the focused event touched a state machine. Dynamic Machine is event-coupled: it shows what this epoch did to the affected machine. If you want to browse all machine definitions, use Static mode.

### Routes

Open Routes when navigation is the problem. The Dynamic Routes tab explains the focused epoch's route activity; the Static Routes tab is for browsing the registered route table and simulating how URLs rank.

The Dynamic tab has three sections. **Current route** shows the active route. **Navigation this epoch** shows the route the focused event navigated from and to, with its outcome. **Route table** lists every registered route and marks the current one. Each section says when it has nothing to show: "No active route.", "No route activity in this epoch." or "No routes registered in the host app."

### Resources

Open Resources when the question is server state. It is the lens on managed server state for the selected frame, and it does not follow the row you pick: the resource registry, live instances, in-flight work, invalidations, and the route-to-resource graph. Read-only.

The tab stacks one section per question, from the resource registry and live instances to "What is still running?", stale races, optimistic mutations and cache growth. Sensitive values show as `[redacted]`. An app that registers no resources reads "No resources registered in the host app."

### Graph

Open Graph when the question is structural rather than event-coupled — "where does this value come from, and what feeds it?" It draws every subscription, flow, resource, route fact, and machine selector as one dependency graph, with its own static and live modes. [10. Derivation graph](10-derivation-graph.md) is the chapter.

### Frames

Open Frames when you want to know which registrations a frame runs. It lists each live frame with the image it runs: how many descriptors and kinds it holds, any capabilities the frame requires, and its first `[kind id]` descriptors with where each one came from. Comparing two frames' lists is what explains the same name resolving differently in two frames.

### Fresco

Open Fresco when a view re-rendered and you want to know why. Six views over one evidence read: which boundaries are mounted, which subscriptions they hold, what was dispatched, what changed, which boundary is hot, and one dispatch walked from event to paint. The tab is always present, and each view says what an empty roster means for it rather than showing a bare table. [11. The Fresco tab](11-fresco-tab.md) is the chapter.

## Static Mode

Static mode removes the event spine. That is the point. You are no longer asking what one event did; you are browsing the app's registered structure.

Static tabs:

- **Machines**: registered machines and their topology. [8. Machine inspector](08-machine-inspector.md#static-machines) covers its simulator.
- **Routes**: route catalogue and URL simulation. Type a URL into **Simulate URL** to see which routes match it and which one wins. Expand a row for its details, a **Simulate navigation** preview that dispatches nothing, and a **→ Dynamic** link to the Dynamic Routes tab.
- **Schemas**: the selected frame's app-db schemas, and the registered event and subscription schemas.
- **Flows**: registered flows and their inputs, with the app-db path each one writes.
- **Interceptors**: registered event chains and shared interceptors. Each interceptor is listed once, with how many chains use it.

Use Static mode before a debugging session when you want the map. Use Dynamic mode during the debugging session when you want the journey.

## The Derivation Graph

The Graph tab deserves a second word, because it is the one Dynamic tab that does not read as a lens on one epoch. Where the tabs above each project the focused cascade, the derivation graph draws *how your derived values relate* — every subscription, flow, resource, route fact, and machine selector as nodes in one dependency graph, with its own static (what's registered) and live (what the observed frame realized) modes. That static/live split of its own is why it feels like it cuts across the mode switch. Reach for it when the question is structural rather than event-coupled. [10. Derivation graph](10-derivation-graph.md) is the chapter.

## Settings And The Command Palette

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

## The Daily Path

Most debugging sessions are pleasantly boring:

1. Pick the frame.
2. Click the event row.
3. Read Epoch.
4. Check app-db or Views depending on whether the symptom is state or rendering.
5. Drop to Trace only if you need the raw record.

That is the whole tool in its everyday form.

## Xray Inside Story

Story embeds Xray in its right rail, one panel at a time. A row of chips picks the panel (Epoch, App-db, Views, Trace, Machines or Routing), under a strip of the selected variant's recent events. Click an event in the strip to focus the panel on that epoch.

Each Story variant runs in its own frame, registered under the variant's id, and the rail watches the selected variant's frame. Select another variant and Xray follows it. **Pop out** opens the full shell, with every tab, in a second window.

Story switches off Xray's keyboard shortcuts so its own keys keep working. Inside Story, and in a shell popped out from it, use the mouse.

The usual way in is a failing variant. Story's Tests tab names the assertion that failed, and **open in Evidence →** opens the run's narrative at that step. Each step's beats carry **Xray: Epoch**, **Xray: App-db** and **Xray: Trace** links, which point the rail at that panel and that beat's epoch. [Xray, earned at failure](../story/06-xray-earned-at-failure.md) follows one failure from the Tests tab into Xray.

Story drives the panel through `focus!`, which any host can call to select a frame, an epoch and a tab. [Focusing a panel from a host](api/mount-control.md#focusing-a-panel-from-a-host) lists its keys.
