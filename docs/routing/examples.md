# Examples

<a id="routing-examples"></a>

Runnable routing apps in the repo, smallest first.

| Example | What it shows | Related guide |
|---|---|---|
| [routing](../../examples/capabilities/routing/routing) | The basics: `reg-route`, `route-link`, the route subscriptions, and a root view that is a `case` over the route id | [Tutorial](tutorial.md) |
| [infinite_feed](../../examples/capabilities/resources/infinite_feed) | `:prefetch :intent` on a link: hovering the link starts loading the `:blocking? true` timeline, so the page is ready when you click. The only example that uses intent prefetch | [Warming a destination](concepts.md#warming-a-destination-before-the-click) |
| [realworld_http](../../examples/real-apps/realworld_http) | A full Conduit app: path and query params, a `:can-enter` sign-in guard with an `:rf.route/entry-denied` handler that sends the reader to login and back, `:on-match` loading, and a `:can-leave` guard on the editor | [Require sign-in](how-to/require-sign-in-on-a-route.md) |
| [realworld_resources](../../examples/real-apps/realworld_resources) | The same app loading data with route `:resources`: the profile's favorites tab declares `:parent` and receives the profile's banner read, data scoped to the session, and a `:can-leave` editor | [Resources](../resources/concepts.md) |
