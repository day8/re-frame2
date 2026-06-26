# routing — Spec 012 worked example

The one idea: **the URL is just application state.** A route is a
[registration](../../../docs/guide/glossary.md#registration). Navigating is
dispatching an [event](../../../docs/guide/glossary.md#event). The active
route is a [subscription](../../../docs/guide/glossary.md#subscription) your
views watch. There is no router object and no route context to thread
through your tree.

This is the smallest app that puts all three to work: a three-page
site — home, an articles list, an article detail — with a real URL, a
404 page, and working Back/Forward. It's the worked companion to
[Construction Prompt CP-7](../../../spec/Construction-Prompts.md) and
[Spec 012](../../../spec/012-Routing.md).

## What this demonstrates

- **The route table is data** (`reg-route`) — four rows, one per page:
  `:routing.app/home`, `:routing.app/articles`, `:routing.app/article-detail`
  (whose `:id` path param carries a Malli `:params` schema), and the
  reserved `:rf.route/not-found`. You register rows, the same way you
  register an event handler. No router object is built.
- **The root view branches on the route** — `root-view` is a `case`
  over the `:rf.route/id` subscription. The active route is ordinary
  derived state, so choosing which page to render is a plain read, not
  a framework callback. The detail page reads `:rf.route/params` the
  same way to pull `:id` out of the URL.
- **Links come from the framework, not glue you write** — every link
  uses `rf/route-link`, a [view](../../../docs/guide/glossary.md#view)
  shipped by `day8/re-frame2-routing`. It renders a real `<a href="…">`.
  A plain left-click dispatches `:rf/url-requested` to navigate in-app
  (no full-page reload). Modifier-key and middle-clicks fall through to
  the browser, so open-in-new-tab still works. You write no `onClick`
  and no `preventDefault` — that work is the framework's.
- **One frame owns the address bar** — the app
  [frame](../../../docs/guide/glossary.md#frame) declares `:url-bound? true`,
  and that one flag makes it the URL owner. `install-history-listener!`
  then wires Back/Forward (popstate) and the initial URL→state sync to
  that owner. It's the right surface for the job: a hand-rolled,
  frameless `:rf.route/handle-url-change` dispatch has no frame to land
  in and raises `:rf.error/no-frame-context`.
- **The same routing runs on the server** — the routing artefact is
  `.cljc`, so this exact `reg-route` table works on a JVM server too.
  Nothing here is browser-only. The `:rf.route/handle-url-change` handler
  that popstate and the initial load drive on the client is the same one
  a server render uses — the server just feeds it the request URL
  (Spec 012 §URL changes are events).

## Why this shape

This is the *smallest* example that exercises Spec 012 end to end, and
that's the point. A real single-page app needs four things: a route
table, navigation as an event, the route as a subscription, and a
root-view switch. This app has each one once, in its plainest form, with
nothing extra to distract. Read it and you have a complete pattern to
copy when scaffolding a routed app.

Two small details are worth copying too:

- The articles collection lives under `:routing.app/articles-list`, not
  `:routing.app/articles`, so the app-db key and sub-id never look like
  the same-named route-id. The registries are independent, but keeping
  the names apart is easier on the eye.
- The React root is created lazily inside `run`, not at namespace load.
  That way a co-required example can't race a second `create-root` onto
  the shared `#app` node.

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
