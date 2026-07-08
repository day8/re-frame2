# A three-page site with real URLs

This is a small website you click through: a home page, an articles
list, and an article-detail page, plus a 404 page for anything else.
Each page has its own real URL, and the browser's Back and Forward
buttons work. Click a link and the page changes in place — no
full-page reload — while the address bar updates to match.

Here's the one idea worth taking away: **the URL is just application
state.** A route is a
[registration](../../../../docs/core/glossary.md#registration). Navigating is
dispatching an [event](../../../../docs/core/glossary.md#event). The active
route is a [subscription](../../../../docs/core/glossary.md#subscription) your
views watch. There is no router object and no route context to thread
through your tree.

This is the smallest app that puts all three to work — just a
three-page site. It's the worked companion to
[Construction Prompt CP-7](../../../../spec/Construction-Prompts.md) and
[Spec 012](../../../../spec/012-Routing.md).

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
  uses `rf/route-link`, a [view](../../../../docs/core/glossary.md#view)
  shipped by `day8/re-frame2-routing`. It renders a real `<a href="…">`.
  A plain left-click dispatches `:rf.route/url-requested` to navigate in-app
  (no full-page reload). Modifier-key and middle-clicks fall through to
  the browser, so open-in-new-tab still works. You write no `onClick`
  and no `preventDefault` — that work is the framework's.
- **One frame owns the address bar** — the app
  [frame](../../../../docs/core/glossary.md#frame) declares `:url-bound? true`,
  and that one flag makes it the URL owner. The frame's creation then
  automatically wires Back/Forward (popstate) and the initial URL→state sync
  to that owner — no separate install call. It's the right surface for the
  job: a hand-rolled, frameless `:rf.route/handle-url-change` dispatch has no
  frame to land in and raises `:rf.error/no-frame-context`.
- **The same routing runs on the server** — the routing artefact is
  `.cljc`, so this exact `reg-route` table works on a JVM server too.
  Nothing in the table is browser-only (this demo's file is `.cljs` only
  because the mount beside it is browser code). The
  `:rf.route/handle-url-change` handler
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

- [`examples/capabilities/ssr/ssr/`](../../ssr/ssr/) — the sibling server-render + hydration example (the other half of the side-agnostic `.cljc` story; it renders an HTTP-fetched page rather than a routed one).
- [`examples/real-apps/realworld_http/`](../../../real-apps/realworld_http/) — a fuller app that folds this routing surface in alongside auth, `:can-leave` guards, and `:rf.route/navigate`.
