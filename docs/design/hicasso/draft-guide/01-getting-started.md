# Getting started

You already know re-frame2's pipeline: events write app-db, subscriptions
derive values, and views paint the screen. What you still choose is *how*
those views are written. That choice is the view adapter — the layer that
turns your view code into something React can mount.

## What Hicasso is

[Hicasso](glossary.md#hicasso) is re-frame2's **native view adapter**. You
write ordinary ClojureScript data: Hiccup vectors, props maps, and event
vectors in attributes. At the point of use you call [`h/sub`](glossary.md#hsub)
like any other function. The runtime turns that data into React
function-component elements. The app-db, the event handlers, the
subscriptions, the frames — none of that changes. Hicasso is the view
language on top of the pipeline you already trust.

"Native" here does not mean "the only legal way to write views." It means
this is the adapter designed *for* re-frame2's data-first habits, not
borrowed from another UI library's mental model and bolted on. Reagent and
UIx adapters remain first-class products you can ship on forever. Hicasso is
the path that treats markup as data, reads as ordinary calls, and handlers as
values a test can assert with `=`.

### Beside Reagent and UIx

**Reagent** is how a generation of re-frame apps learned to paint: Hiccup that
feels like HTML, reactions that track reads, and a deep ecosystem. If you are
migrating a Reagent app, Hicasso is a cousin, not a foreign country —
[Migrating from Reagent](19-migration-from-reagent.md) is built for that
journey. What you leave behind is reaction-local state and Form-2 ceremony;
what you keep is the shape of the tree.

**UIx** is the best answer when the *screen* is React-first: hooks everywhere,
a mature component library at the centre, authors who already think in React.
Hicasso does not try to win that contest. Foreign React still walks in through
[`h/defhost`](09-interop.md), and a measured hot region can drop into native
React or UIx under the same root and frame
([Native tier](10-native-tier.md)). Pick UIx when the product *is* a React app
that happens to use re-frame2 for state. Pick Hicasso when the product is a
re-frame2 app that wants data all the way to the leaf.

### Why people reach for it

The attractions are few, and they compound.

**Markup stays data.** A button's meaning is `[:todo/toggle id]`, not a
closure you cannot print. Structural tests compare trees with `=`. Tools and
AI pairs can read an intent off the tree without running the app.

**Reads live where you use them.** You do not thread subscription values down
from a parent "so the helper can see the filter." An ordinary `defn` helper
calls [`h/sub`](glossary.md#hsub); the enclosing [boundary](glossary.md#boundary)
owns the read. Re-render granularity stays visible in the source: a vector is
a boundary; a call is inline.

**Controlled fields are a law, not a folk recipe.** Caret jumps, dropped
keystrokes, and IME mishaps are the same bugs every React app rediscovers.
Hicasso centralises that path so ordinary `:value` / `:on-input` attributes
are enough for most forms.

**Performance is "good enough," with an honest exit.** Ordinary screens stay
on interpreted Hiccup. When measurement names a hot 1–2% of the tree, you
cross an explicit fence to native React — same frame, same app-db, same
diagnostics — and only if the escape pays for itself
([Performance](18-performance.md), [Native tier](10-native-tier.md)). There is
no `:fast` flag and no second meaning for `[...]`.

### Tradeoffs, said plainly

Interpreted Hiccup is not free. Cold mount carries a small premium against a
hand-rolled UIx twin on the same screen; much of that premium is capability
(ambient reads in loops and helpers) rather than walking vectors alone. You
pay a short fixed cost per boundary so the ordinary path stays simple. If
every screen is a hook-heavy design-system tree, UIx will feel more natural
from day one. If you need a second reactive store inside the view layer,
Hicasso will not give you one — application-visible state lives in app-db
([Ephemeral state](11-ephemeral-state.md)).

Those are deliberate prices. The rest of this guide is how to spend them
well.

## This page

Install the artifact, mount a root, click a button, and ship a release. By
the end you have the three habits every later chapter assumes:
[`defview`](glossary.md#defview) as a Hiccup head, [`h/sub`](glossary.md#hsub)
at the point of use, and handlers as data.

## Install

Install one artifact:

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
`h`. Optional modules (forms, [overlays](glossary.md#overlay), motion, routing, the
[native tier](glossary.md#native-tier), the [test kit](glossary.md#test-kit)) are separate requires — a
build that never requires them ships none of their code.

## The app

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

## When not to use Hicasso

Stay on **Reagent** if the migration cost is the dominant fact and the app
already works — Hicasso can wait. Choose **UIx** when React-first authoring
(hooks everywhere, a design system at the centre) is the product shape, not
an island. [Hicasso](glossary.md#hicasso) is for data-first views on the
re-frame2 pipeline: markup as data, reads at the point of use, intents you
can assert. It meets foreign React at [`h/defhost`](09-interop.md); it does
not try to be the best pure-React CLJS library.
