# 2. Panel Tour

You opened Xray and now you need orientation. This chapter teaches the shell as a working debugger: where the timeline lives, what the Dynamic tabs answer, when Static mode is better, and which panel to open first.

## The Shape Of The Shell

Xray's Dynamic shell has four layers:

```text
L1  ribbon       mode, frame, filters, settings, close
L2  event spine  recent epochs for the selected frame
L3  tabs         Epoch, app-db, Views, Trace, Machine, Routes,
                 Resources, Graph, Frames, Hicasso
L4  detail       the selected tab's view of the focused epoch
```

The event spine is the load-bearing piece. It is not a decorative timeline; it is the focus selector for the entire tool. Click an event row and every tab reads that same epoch.

![The event spine and Dynamic tabs](../images/xray/xray-tutorial-epoch.png)

## The Ribbon

The ribbon is for scope, not diagnosis.

- **Mode** switches Dynamic and Static.
- **Frame** chooses which re-frame2 frame you are observing.
- **Filters** hide or show event rows by event id.
- **Settings** controls theme, density, sensitive-value posture, and related shell behavior.
- **Close** hides the panel without tearing down the mounted tree.

Frame selection matters in real apps. If your page has multiple isolated frames, the same event id can mean different state and different epoch history in each frame. Pick the frame first, then debug.

## The Event Spine

The spine lists recent epochs for the selected frame. New rows arrive while Xray is following the live head. Clicking an older row puts you into a historical focus: the app can keep running, but the detail panels remain pointed at the row you chose until you follow the head again.

Use the spine when you know the symptom just happened and you want to answer: "Which event caused it?"

## The Dynamic Tabs

Ten tabs ship in Dynamic mode. The first six below are the daily ones and are
where a debugging session usually starts and ends; the last four answer
narrower questions and each has a chapter of its own.

### Epoch

Open Epoch first. It is the readable version of the cascade: dispatch, coeffects, interceptors, handler, app-db change, effects, subscriptions, views, schema checks, and issues where they occurred.

This is the tab for "explain the whole thing to me without making me reconstruct it from raw rows."

### app-db

Open app-db when the question is state. The panel starts with changed slices for the focused epoch. It is read-only and path-oriented: good for seeing what changed, where it changed, and whether the state you expected is actually present.

### Views

Open Views when the page looks wrong or slow. It shows the reactive side of the cascade: subscriptions and renders, with enough structure to see whether a view changed because a subscription changed, because props changed, or because the view was mounted or unmounted.

![The Views panel showing reactive activity](../images/xray/xray-tutorial-views.png)

### Trace

Open Trace when the friendly view is hiding too much. Trace is the flat, epoch-scoped feed of runtime records. It is excellent when you need exact ordering, exact operation names, source coordinates, durations, or a payload that was summarized elsewhere.

### Machine

Open Machine when the focused event touched a state machine. Dynamic Machine is event-coupled: it shows what this epoch did to the affected machine. If you want to browse all machine definitions, use Static mode.

### Routes

Open Routes when navigation is the problem. The Dynamic Routes tab explains the focused epoch's route activity; the Static Routes tab is for browsing the registered route table and simulating how URLs rank.

### Resources

Open Resources when the question is server state. It is the lens on managed server state for the focused event: the resource registry, live instances, in-flight work, invalidations, and the route-to-resource graph. Read-only.

### Graph

Open Graph when the question is structural rather than event-coupled — "where does this value come from, and what feeds it?" It draws every subscription, flow, resource, route fact, and machine selector as one dependency graph, with its own static and live modes. [10. Derivation graph](10-derivation-graph.md) is the chapter.

### Frames

Open Frames when your app loads images into frames. It shows each live image-loaded frame as an execution context carrying its resolved image's descriptors, which is what explains the same name resolving differently in two frames. A process not using image-loaded frames gets an honest no-image caption rather than a blank.

### Hicasso

Open Hicasso when a view re-rendered and you want to know why. Six views over one evidence read: which boundaries are mounted, which subscriptions they hold, what was dispatched, what changed, which boundary is hot, and one dispatch walked from event to paint. The tab is always present — on an app that is not running Hicasso it says so in those words rather than showing an empty table. [11. The Hicasso tab](11-hicasso-tab.md) is the chapter.

## Static Mode

Static mode removes the event spine. That is the point. You are no longer asking what one event did; you are browsing the app's registered structure.

Static tabs:

- **Machines**: registered machines and their topology.
- **Routes**: route catalogue and URL simulation.
- **Schemas**: registered app-db, event, sub, and related schemas.
- **Flows**: registered flows and their inputs.
- **Interceptors**: registered event chains and shared interceptors.

Use Static mode before a debugging session when you want the map. Use Dynamic mode during the debugging session when you want the journey.

## The Derivation Graph

The Graph tab deserves a second word, because it is the one Dynamic tab that does not read as a lens on one epoch. Where the tabs above each project the focused cascade, the derivation graph draws *how your derived values relate* — every subscription, flow, resource, route fact, and machine selector as nodes in one dependency graph, with its own static (what's registered) and live (what the observed frame realized) modes. That static/live split of its own is why it feels like it cuts across the mode switch. Reach for it when the question is structural rather than event-coupled. [10. Derivation graph](10-derivation-graph.md) is the chapter.

## The Daily Path

Most debugging sessions are pleasantly boring:

1. Pick the frame.
2. Click the event row.
3. Read Epoch.
4. Check app-db or Views depending on whether the symptom is state or rendering.
5. Drop to Trace only if you need the raw record.

That is the whole tool in its everyday form.
