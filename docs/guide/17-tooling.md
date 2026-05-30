# 17 - Tooling

You want tools that understand your app instead of tools that merely stare at the DOM and make guesses. This chapter explains Xray, Story, the MCP surfaces, and why they are powerful: they are not sidecar magic; they are different views over the same events, frames, plans, and epoch evidence.

The tools are strongest when you keep the app inside the re-frame2 model. Events are data. Effects are named. Views are registered. Frames isolate. Trace explains. Tools read those facts.

## Xray

Xray is the diagnostic instrument. It shows epochs, app-db diffs, traces, views, machines, routes, and issues. It is where you go when the question is "what happened?"

Xray is not React DevTools with a new coat. React DevTools shows a component tree. Xray shows the causal path through your app's runtime.

## Story

Story is the workshop. It renders views and variants in isolated frames, with args, setup, scripts, expectations, and evidence. It is for UI states, examples, regression cases, and human exploration.

The important re-frame2 twist is that a story can be executable. A variant can become a test plan, and a failed generated run can be promoted into a named regression.

## MCP and skills

The MCP surfaces let an agent inspect, dispatch, explain, and replay against the same model a human uses. The associated skills should not invent a second operational vocabulary. They should drive Story and Xray concepts through the same primitives.

## Pitfall: using tools as compensation for unclear code

Good tools amplify a clear model. They cannot rescue a codebase where state is hidden in component locals, effects happen inline, and ids are unsearchable. Keep the runtime legible and the tools become unfairly good.
