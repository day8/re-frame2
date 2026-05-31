# 17 - Tooling

You have just learned the trace and epoch substrate: the wire that remembers what the app did, what changed, which subscriptions ran, which views rendered, and which effects escaped. This chapter tells you which tool to reach for when that data becomes too much to read raw. It is a map, not the manual; Story and Xray have their own docs.

The important point is not that re-frame2 has tools. Every framework has tools, some of them glorious, some of them apparently assembled during a power outage. The important point is that these tools are thin presentations over the same runtime facts. If Xray, Story, an MCP tool, and a production error shipper disagree about a cascade, one of them is wrong. There is not supposed to be a second truth.

## One wire, several readers

The common substrate is the one from [chapter 16](16-observability.md):

- the trace stream for live event, effect, subscription, render, schema, and error records
- epoch records for settled cascades with before/after app-db and derived evidence
- the registrar, which knows what events, subs, effects, coeffects, views, schemas, and machines exist
- frame isolation, so a tool can observe one running app context without pretending to be that app
- redaction and elision, so tool output follows the same privacy rules as off-box observability

That substrate gives re-frame2 its tool shape. The framework does the hard part once: make runtime behaviour structured and inspectable. Each tool chooses a different human or agent workflow over that data.

## Xray answers: what happened?

Reach for Xray when you are debugging the running application.

The useful question is not "what component is selected?" It is "what did that event cause?" Xray follows the cascade: event, app-db diff, effects, subscription recomputes, renders, machines, routes, schema failures, and issues. It is the place you go when a click three dispatches ago left the app-db in a state that only becomes visibly wrong now.

Xray owns the diagnostic panels. Story can embed or focus those panels, but it should not grow a second app-db diff engine, a second schema timeline, or a second subscription inspector. Duplication here would be worse than waste; it would create two tools that can tell different stories about the same event. That is how developer trust dies, usually in a meeting where everyone keeps saying "interesting" and nobody is having fun.

For installation, panels, time travel, click-to-source, schema timelines, hydration, machines, and app-db diffing, use the [Xray docs](../xray/index.md).

## Story answers: what states should this thing have?

Reach for Story when you are building or reviewing a view, component, screen, or workflow state.

The Storybook-shaped problem is familiar: you want to see loading, empty, error, happy, disabled, permission-denied, and "the API sent us nonsense again" states without driving the whole app by hand. Story gives those states names, runs them in isolated frames, lets you vary args and state inputs, and then turns good examples into tests when that is the right move.

The guide deliberately does not teach Story authoring here. Story has its own tutorial track because it is a product-sized surface: variants, controls, fidelity, scripts, assertions, grids, snapshots, sharing, and the relationship to Xray on failure. The main guide only needs you to understand the boundary:

- use real app setup when you want integration fidelity
- use controlled view inputs when you want fast design exploration
- promote examples to tests when they represent behaviour you care about
- hand deep diagnosis to Xray rather than rebuilding an inspector inside Story

For the actual workflow, start with the [Story docs](../story/index.md).

## The MCP and skills answer: can an agent help?

The MCP and skill surfaces are for agent-assisted work. They let an AI inspect the same runtime you are looking at, instead of guessing from source files alone.

The pair shape is intentionally split. A browser-oriented MCP can click, type, take screenshots, and exercise the DOM. The re-frame2 runtime surface can read frames, inspect app-db, follow epochs, dispatch events, and explain registered handlers. The agent combines them: click the UI, watch the epoch, inspect the state, form a hypothesis, try a smaller reproduction.

That is powerful, so it has to stay boring at the boundary. Reads should be explicit. Writes should be gated. Sensitive and large data should pass through the same redaction and elision rules the rest of the tooling uses. The point is not to give an agent magic; it is to give it the same structured evidence a good human debugger would ask for.

For the human-facing skill docs, see [Skills](../skills/index.md), especially [re-frame2 pair](../skills/re-frame2-pair.md) and [re-frame2 Xray](../skills/re-frame2-xray.md). Story also has a dedicated MCP surface in its own docs.

## Which tool should I open?

| Question | Start here | Why |
|---|---|---|
| "What did that event do?" | [Xray](../xray/index.md) | It is the diagnostic view over epochs, traces, app-db diffs, renders, effects, schemas, and issues. |
| "What states should this view support?" | [Story](../story/index.md) | It lets you render named states and variants without driving the full app manually. |
| "Can I make this UI state visible quickly?" | Story | It gives you controls and view-state inputs, while still labelling fidelity honestly. |
| "Is this example actually a regression test?" | Story plus the testing APIs | A good variant can become an executable expectation instead of a screenshot with ambitions. |
| "Where did this failed assertion come from?" | Story, then Xray | Story owns the example and expectation; Xray owns the diagnostic detail. |
| "Can an AI inspect the live app?" | Skills / MCP | The agent reads the same frame, trace, epoch, and registrar surfaces humans use. |
| "Can I ship runtime telemetry to my observability stack?" | [Chapter 16](16-observability.md) and [chapter 23](23-privacy-and-large-things.md) | Production observability uses the always-on emission surfaces and privacy machinery, not the dev-only panels. |

## The rule for future tools

The door is open for custom tools: a domain monitor, a recorder, a migration assistant, a release-health dashboard. The rule is simple. Consume the public substrate; do not invent a private one.

If a tool needs to know what happened, it should read the trace or epoch record. If it needs to know what exists, it should query the registrar. If it needs to show state, it should respect frame identity and privacy markings. The framework owns the data shape. Tools own the presentation. That division is what keeps the ecosystem coherent instead of turning into a pile of almost-right panels.
