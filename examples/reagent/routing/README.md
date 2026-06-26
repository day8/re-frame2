# routing — Spec 012 worked example

The one idea to carry away: **the URL is just application state, and the
back button is just a dispatch.** Most frameworks bolt a router onto the
side of your app — its own context, its own lifecycle, its own opinion
about where truth lives. re-frame2 declines. A route is a registration;
navigating is dispatching an [event](../../../docs/guide/glossary.md#event);
the active route is a [subscription](../../../docs/guide/glossary.md#subscription)
your views watch. This example is the smallest app that puts all three
to work: a three-page site — home, an articles list, an article detail —
with a real URL, a 404, and working Back/Forward. The worked companion
to [Construction Prompt CP-7](../../../spec/Construction-Prompts.md) and
[Spec 012](../../../spec/012-Routing.md).

## What this demonstrates

- **The route table as data** (`reg-route`) — four registry rows, one
  per page: `:routing.app/home`, `:routing.app/articles`,
  `:routing.app/article-detail` (whose `:id` path param carries a Malli
  `:params` schema), and the reserved `:rf.route/not-found`. No router
  object is constructed; you just register rows, the same way you
  register an event handler.
- **The root view branches on the route** — `root-view` is a `case`
  over the `:rf.route/id` subscription. The active route is ordinary
  derived state, so picking the page to render is a plain read, not a
  framework callback. `:rf.route/params` is read the same way to pull
  `:id` out of the URL on the detail page.
- **Linking is a framework view, not glue you write** — every link uses
  `rf/route-link`, the registered view shipped by
  `day8/re-frame2-routing`. It renders an honest `<a href="…">`,
  intercepts a plain primary-button click to dispatch `:rf/url-requested`
  (an in-app navigation, no full-page reload), and — politely — defers
  modifier-key and middle-clicks to the browser, so open-in-new-tab
  still works. Notice what *isn't* here: no `onClick` handler hand-rolling
  the navigate, no `preventDefault`. That whole dance is the framework's.
- **The browser owns nothing the frame doesn't claim** — the app frame
  declares `:url-bound? true`, and that single flag is what makes it the
  owner of the address bar. `install-history-listener!` then wires
  Back/Forward (popstate) and the initial URL→state sync *to that owner*.
  It's the canonical surface for the job: a hand-rolled, frameless
  `:rf.route/handle-url-change` dispatch would have nowhere to land and
  would raise `:rf.error/no-frame-context`.
- **The same routing runs on the server** — the routing artefact is
  `.cljc` and side-agnostic, so this exact `reg-route` table works on a
  JVM server too. There's no browser-only path baked in: the same
  `:rf.route/handle-url-change` handler that popstate and the initial load
  drive on the client is the one a server render uses — the server just
  feeds it the request URL (Spec 012 §URL changes are events).

## Why this shape

This is the *smallest* example that exercises Spec 012 end to end, and
that's the point: a real single-page app needs a route table, navigation
as an event, the route as a subscription, and a root-view switch — and
this app has each of those once, in its canonical form, with nothing
extra to distract. Read it and you've got a complete, copy-able pattern
to scaffold a routed app against, with no incidental complexity to filter
out first. The deliberate restraint extends to small details worth
copying: the articles collection lives under `:routing.app/articles-list`
rather than `:routing.app/articles`, so the app-db key and sub-id never
visually collide with the same-named route-id (the registries are
independent, but the eye appreciates the separation). And the React root
is created lazily inside `run`, not at namespace load, so a co-required
example can't race a second `create-root` onto the shared `#app` node.

## Files

```
routing/
  core.cljs    — route table, app data, sub graph, root view, mount.
  index.html   — minimal host page.
```

## How to run

```bash
# From implementation/:
shadow-cljs watch examples/routing
```

Then serve the build over HTTP and open it.

## Cross-references

- [`examples/reagent/ssr/`](../ssr/) — the sibling server-render + hydration example (the other half of the side-agnostic `.cljc` story; it renders an HTTP-fetched page rather than a routed one).
- [`examples/reagent/realworld/`](../realworld/) — a fuller app that folds this routing surface in alongside auth, `:can-leave` guards, and `:rf.route/navigate`.
</content>
</invoke>
