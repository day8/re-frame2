# 25 - From re-frame v1

You know re-frame v1 and want to understand what changed without reading a migration ledger like it is a medieval tax document. This chapter maps the important reflexes: the data loop remains, but v2 is stricter about frames, registration, effects, tooling evidence, and keeping runtime state visible.

The v1 mental model still helps. Event -> handler -> app-db -> subscriptions -> views is alive. The difference is that re-frame2 makes more of the substrate explicit and toolable.

## Preserved instincts

| v1 instinct | v2 status |
|---|---|
| Events are data | Preserved. |
| `app-db` is the state value | Preserved, with frame scoping. |
| Subscriptions are the read graph | Preserved. |
| Effects are data | Preserved and broadened. |
| Interceptors wrap handlers | Preserved. |

## New pressure

Frames matter. A single app still mostly uses `:rf/default`, but tests, Story, SSR, and tools rely on multiple isolated frames. That is one of v2's biggest structural upgrades.

The registrar matters. Views, machines, routes, schemas, and runtime metadata are named and inspectable. This is how tools avoid guessing.

The trace matters. Xray, Story, MCP tools, test artifacts, and debugging all depend on structured evidence from the runtime.

## The devtools moved house

In v1, a lot of debugging muscle memory lived around re-frame-10x and browser-side inspection. In v2, that job moves into Xray and the Story surface around it: Xray reads epochs, traces, app-db diffs, subscriptions, renders, machines, routing, and issues from the runtime instead of reconstructing the scene from the outside.

That sounds like a tooling detail until you hit a production-shaped bug at 4:47 p.m. and discover that the evidence is already organized by frame and epoch. Story can show the example or failing variant, while Xray explains what changed and why. The old habit was "open devtools and hunt"; the new habit is "open the evidence and follow the causality."

## Removed or renamed habits

Some v1 surfaces are gone or tightened: broad globals, fuzzy keyword view lookup, older override names, and patterns that made multi-frame isolation ambiguous. The migration docs carry the full table. The guide-level rule is simpler: prefer explicit ids, explicit frames when crossing boundaries, and named effects at the edge.

## on-changes becomes flows

The v1 `on-changes` interceptor was a clever way to recompute derived state after particular inputs changed. In v2, that idea graduates into registered flows: named derived computations with explicit inputs, output, and target path.

```clojure
(rf/reg-flow
  {:id :cart/total
   :inputs [[:cart :items]]
   :output (fn [items]
             (reduce + (map :line-total items)))
   :path [:cart :total]})
```

The benefit is not just syntax. A flow is registered runtime data, so it can be inspected, toggled, traced, and reasoned about by tools. The derivation is no longer hiding inside one event handler's interceptor vector.

## Pitfall: porting syntax but not posture

A v1 app can be transliterated into v2-looking code and still miss the point. The win is not new names. The win is that tests, Story, Xray, SSR, and pair tools can all reason over the same runtime substrate. Preserve that and the migration is worth it.
