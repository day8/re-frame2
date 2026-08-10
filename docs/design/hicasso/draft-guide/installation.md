# Installation

You have decided [Hicasso](glossary.md#hicasso) is the view adapter you want
([Getting started](01-getting-started.md)). This page gets it on the classpath,
into a browser build, and onto a DOM node. By the end you have a running
counter and the three habits every later chapter assumes:
[`defview`](glossary.md#defview) as a Hiccup head, [`h/sub`](glossary.md#hsub)
at the point of use, and handlers as data.

## Install the artifact

One Clojure dependency:

```clojure
;; deps.edn
{:deps {io.github.day8/re-frame2-hicasso {:mvn/version "1.0.0"}}}
```

This artifact brings `day8/re-frame2` with it — the core you already use.
Install React from npm:

```bash
npm install react react-dom
```

Interpreted Hiccup needs no build configuration: no compiler hook, no macro
allow-list, no build flag. Any ordinary shadow-cljs browser build works:

```clojure
;; shadow-cljs.edn
{:deps     true
 :dev-http {8080 "public"}
 :builds   {:app {:target     :browser
                  :output-dir "public/js"
                  :asset-path "/js"
                  :modules    {:main {:entries [counter.core]}}}}}
```

```html
<!-- public/index.html -->
<!doctype html>
<html>
  <body>
    <div id="app"></div>
    <script src="/js/main.js"></script>
  </body>
</html>
```

You require one namespace: `[re-frame.hicasso :as h]`. The usual alias is
`h`. Optional modules (forms, [overlays](glossary.md#overlay), motion, routing,
the [native tier](glossary.md#native-tier), the [test kit](glossary.md#test-kit))
are separate requires — a build that never requires them ships none of their
code.

## Your first screen

A real app splits events, subscriptions, and views into their own
namespaces. The boot namespace requires those namespaces for their
registrations. One screen fits in one namespace:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

;; Events write app-db.
(rf/reg-event :counter/initialise
  (fn [_cofx _event]
    {:db {:count 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :count inc)}))

;; Subscriptions derive values.
(rf/reg-sub :counter/count
  (fn [db _query] (:count db)))

;; The view: a props map in, hiccup out.
(h/defview counter [_]
  [:main
   [:h1 "Clicked " (h/sub [:counter/count]) " times"]
   [:button {:on-click [:counter/increment]} "Click me"]])

;; The root.
(defonce root
  (h/mount! (js/document.getElementById "app")
            {:frame          :rf/default
             :initial-events [[:counter/initialise]]}
            [counter]))

;; Hot reload: re-render the redefined view into the surviving root.
(defn ^:dev/after-load rerender! []
  (h/render! root [counter]))
```

```bash
npx shadow-cljs watch app    # then open http://localhost:8080
```

Click the button. The count changes. This screen shows the three habits of a
[Hicasso](glossary.md#hicasso) view:

- **[`h/defview`](glossary.md#defview) mints a view, and a view is a Hiccup head.** You mount it as
  `[counter]`, which is a vector. You never call it as a function. A direct
  call refuses (see the first row of the
  [troubleshooting table](#troubleshooting)). Markup that should inline into
  its caller is a plain `defn` helper.
- **[`h/sub`](glossary.md#hsub) reads at the point of use.** It is an ordinary call. It is legal
  in a `let`, a `when`, a loop, a helper — anywhere in the view's synchronous
  body.
- **The click handler is data** — an [intent](glossary.md#intent): an event vector in the
  attribute, not a closure. `{:on-click [:counter/increment]}` stays
  assertable with `=`. The runtime builds the callback and dispatches that
  vector to this root's frame.

[Views and reads](02-views-and-reads.md) and
[Events as data](03-events-as-data.md) teach each habit in more depth.

## What `h/mount!` did

> **One [`h/mount!`](glossary.md#mount) associates a DOM node, a frame, and initial events, and
> returns an idempotent handle.**

[`h/mount!`](glossary.md#mount) takes the DOM node, a config map, and one view. `:frame` names the
re-frame2 *frame* — an isolated world with its own app-db, event queue, and
subscription cache. The root *ensures* that frame: creates it if missing,
otherwise joins the one that already exists. Two roots with two frame ids are
two isolated apps on one page. `:initial-events` runs ordinary events once,
in order, and seeds app-db **before** the first paint. That is why the heading
renders `0` and not an empty flash. Initial values arrive by event; that rule
does not change because the view layer changed.

`[counter]` and `[counter {}]` render the same output. The body receives an
empty props map in both cases.

The handle controls the rest of the root's life:

```clojure
(h/render! root [counter])   ;; render into the existing root
(h/unmount! root)            ;; idempotent — a second call is a no-op
```

After [`h/unmount!`](glossary.md#mount), the root unmounts, subscriptions release with ref-counts
at zero, and the DOM node is available to you again. Teardown paths run from
`finally` blocks, fixtures, and reload hooks, and these paths do not
coordinate. For that reason a second call is a no-op, not a crash.

## More than one root

[`h/mount!`](glossary.md#mount) composes. A page can hold several roots, and `:frame` decides
whether they are one app or two apps.

**Two roots, one frame — one app in two containers.** The root *ensures* its
frame. The first mount creates the frame and runs `:initial-events`. A later
mount that names the same frame joins the frame as it stands and replays
nothing. Both roots read one app-db and dispatch into one queue. A widget
mounted in the page's header therefore follows the same state as the main
screen:

```clojure
(defonce app-root
  (h/mount! (js/document.getElementById "app")
            {:frame          :app/main
             :initial-events [[:app/initialise]]}   ;; this mount creates the frame — it seeds
            [main-screen]))

(defonce status-root
  (h/mount! (js/document.getElementById "status")
            {:frame :app/main}                      ;; this one joins — no re-seed
            [connection-badge]))
```

Seed from the root that creates the frame. A joining root carries no
`:initial-events` of its own.

**Two roots, two frames — two isolated apps.** Give each root its own
`:frame` id, and each root has its own app-db, queue, and subscription cache.
A view reads under the frame of the root that renders it. Subscriptions never
cross frames: nothing mounted in `:app/main` can observe `:app/preview`. This
isolation makes a live preview beside an editor — the same views, different
state — an ordinary page. The independent second frame is a deliberate
choice. Use one when two surfaces must not share state; share the frame when
they must.

Teardown stays per root in both cases. [`h/unmount!`](glossary.md#mount) releases that root's
subscriptions and returns its DOM node. A frame that other roots still use
keeps its state and continues to serve them.

## Hot reload

Leave the watch running and edit the `:h1` text. On save, shadow-cljs reloads
the namespace, and the `^:dev/after-load` hook calls [`h/render!`](glossary.md#mount) with the
redefined view. The root, the frame, and app-db survive, so the count keeps
its value. The changed body is the body that runs, and no subscriptions leak.

One consequence: hot reload preserves state *past your setup event*. An edit
to `:counter/initialise` does not re-seed a live app-db. Reload the page when
you want the new seed.

## Production

```bash
npx shadow-cljs release app
```

A release build runs advanced compilation, and the development surface erases
with it. Dev-only warnings, their message strings, and every Xray
instrumentation hook are absent from the bundle. Optional modules that you
never required contribute zero code. Nothing else changes: interpreted Hiccup
is the one semantics in development and in production, and no build flag
alters what your views mean.

## Troubleshooting

| Symptom | What went wrong | Fix |
|---|---|---|
| Calling a view throws — `(counter {})` | The refusal fires at the call and names the view: you mount a [`defview`](glossary.md#defview) as a Hiccup head; you never invoke it | Write `[counter {}]`. Markup that should inline into its caller is a plain `defn` helper |
| [`h/sub`](glossary.md#hsub) in a callback, timeout, or promise throws, naming the query | `:rf.error/hicasso-sub-outside-render` — the read escaped the synchronous body that owned it | Read during the body and close over the value; async work reads app-db through events and coeffects |
| First paint is empty, then content flickers in | Seeding raced the first render | Seed through `:initial-events`, not a post-mount dispatch |
| Second [`h/mount!`](glossary.md#mount) on the same node | A live root already owns that DOM node | `defonce` the handle; [`h/unmount!`](glossary.md#mount) it before mounting again |
| A second root's `:initial-events` never ran | Its `:frame` already existed — ensure joins a live frame and replays nothing | Seed from the root that creates the frame; joining roots omit `:initial-events` |
| View body runs twice on mount in development | React StrictMode double-invokes bodies | Expected — bodies are pure and re-runnable |
