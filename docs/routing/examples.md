# Examples

<a id="routing-examples"></a>

Runnable routing apps in the repo. Build something yourself first
([tutorial](tutorial.md) or [the model](concepts.md)), then open these in order.

| Example | What it shows | Read first |
|---|---|---|
| [routing](../../examples/capabilities/routing/routing) | Minimal surface: `reg-route`, navigate event, route subs, root `case` | [Tutorial](tutorial.md) |
| [infinite_feed](../../examples/capabilities/resources/infinite_feed) | The repo's only runnable intent prefetch: `:prefetch :intent` on a link warms a `:blocking? true` timeline, so hovering visibly changes what the click lands on | [Warming a destination](concepts.md#warming-a-destination-before-the-click) |
| [realworld_http](../../examples/real-apps/realworld_http) | Conduit: path + query params, a `:can-enter` auth gate with the terminal `:rf.route/entry-denied` recipe, hand-rolled `:on-match` activation work, leave guard | [The model](concepts.md), [Require sign-in](how-to/require-sign-in-on-a-route.md) |
| [realworld_resources](../../examples/real-apps/realworld_resources) | Same Conduit with route `:resources`, `:parent` branch composition (the favorites tab inherits its banner read from the profile shell), session scope, editor `:can-leave` | [Resources](../resources/concepts.md) |
