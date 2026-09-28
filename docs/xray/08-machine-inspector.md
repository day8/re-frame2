# 8. Machine Inspector

Your bug is not a value; it is a process. This chapter shows how Xray reads re-frame2 state machines: Dynamic mode explains what one epoch did to a machine, while Static mode lets you browse machine definitions and topology.

## Dynamic Machine

The Dynamic Machine tab is event-coupled. It is useful when the focused epoch touched a machine.

It reads the one machine the focused event targeted. A header line names the trigger event, the machine and the state it was in, and numbered rows follow in the order the machine ran them, the same rows the Epoch tab shows under EVENT HANDLER:

- **GUARD** for each guard the machine checked;
- **ACTION** for each action, labelled by when it ran, such as **EXIT ACTION** or **ENTRY ACTION**, with the data it produced;
- **TRANSITION** with the state and tags before and after;
- **ALWAYS** for an eventless `:always` transition, and **TIMER** for an `:after` timer;
- **START** when the event created the machine, and **NO OP** when the machine had no transition for the event.

When a destroyed child actor aborts work it had in flight, that cancellation cascade is in Trace, behind the **⟲** button ([4. Trace stream](04-trace-stream.md#what-trace-shows)).

If the focused event did not target a machine, the tab reads only "This event does not target a state machine". That is not failure; it is honest scope.

Below the rows, the machine's chart marks the state it left with a dashed outline and the state it entered in bold, and draws a countdown ring on each state with an `:after` timer running. The chart has its own zoom, pan and fit controls. **◀ Prev** and **Next ▶** step to the previous and next event that touched the same machine. An app that registers no machines reads "No machines registered.", and a machine whose definition cannot be read shows "No introspectable definition — chart cannot render." in place of the chart.

![The Machine tab for a :door/push event: an exit action, the :closed → :open transition, and an entry action, with the chart below](../images/xray/xray-tutorial-machine.png)

## Static Machines

Static Machines answers a different question: "what machines are registered, and what shape do they have?"

Use it before or during debugging when you need the map:

- browse registered machine ids;
- inspect topology;
- read states and transitions;
- simulate the definition without touching your app.

Pick a machine to see its source link, how many states it has and how many instances are live, and four views of it:

- **Topology** draws the chart. **Copy Mermaid** copies it as a Mermaid diagram.
- **Sim** runs a copy of the definition, starting from its initial state, and never touches your app. Type an event and an optional EDN payload and press **Step ▶︎**, or click a transition on the chart. A failed guard shows inline and the state stays put. A timer on the current state can be fired by hand, since the simulator keeps no clock. **Reset** returns to the initial state and **Exit Sim** leaves.
- **Instances** jumps to the machine's live instances in Dynamic mode.
- **Cascade** is disabled here, because cancellation cascades belong to Dynamic mode.

Dynamic is the black box recorder for one transition. Static is the diagram on the wall.

## A Good Machine Debugging Loop

When a machine flow behaves wrongly:

1. Reproduce the action.
2. Click the event row in the spine.
3. Open Dynamic Machine and read the transition.
4. If the transition is surprising, flip to Static Machines and inspect the definition.
5. Return to Epoch or Trace for the exact guard/action records.

That loop keeps you out of the most common machine-debugging trap: staring at the current state while forgetting the event that moved it there.

## Why Machines Are Especially Good In Xray

Machines have a small, named state space. Xray can exploit that. A generic logger can tell you "something updated a map"; Xray can tell you "this `:door/push` moved `:door/main` from `:closed` to `:open`, ran `:clear-hold` on the way out and `:count-open` on the way in, and here is the data each one wrote."

That is the difference between seeing state and seeing behavior.
