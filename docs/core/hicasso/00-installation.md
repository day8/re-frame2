# Installation

Chapter `00` of the Hicasso draft guide. This page adds Hicasso to a browser
build and mounts a small counter. The example establishes the three forms used
throughout the guide: [`h/defview`](glossary.md#defview) for a view,
[`h/sub`](glossary.md#hsub) for a subscription read, and event vectors for
ordinary handlers.

## Add the dependencies

!!! warning "Pre-alpha: no Clojars coordinate"

    `day8/re-frame2-hicasso` is **not published**, and there is no date at
    which it will be. It lives in the re-frame2 monorepo, so today you resolve
    it — and `day8/re-frame2` with it — from a checkout using `:local/root`.
    Treat the snippet below as the shape of the dependency, not as a
    coordinate you can paste into a fresh project.

Add the Hicasso artifact and a [substrate
adapter](#hicasso-needs-a-substrate-adapter) to `deps.edn`:

```clojure
;; deps.edn — resolved from a re-frame2 checkout beside your project
{:deps {day8/re-frame2-hicasso {:local/root "../re-frame2/implementation/hicasso"}
        day8/re-frame2-uix     {:local/root "../re-frame2/implementation/adapters/uix"}}}
```

The artifact brings `day8/re-frame2` with it. Install React from npm:

```bash
npm install react react-dom
```

Hicasso interprets Hiccup at runtime, so it needs no compiler hook, macro
allow-list, or build flag. A normal shadow-cljs browser build is enough:

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

The main namespace is `re-frame.hicasso`, conventionally required as `h`.
Forms, overlays, motion, routing, the native tier, and the test kit use separate
namespaces. A build that does not require an optional module does not include
its code.

## Hicasso needs a substrate adapter

Hicasso is a view layer, not a [substrate](../glossary.md#substrate). It owns
Hiccup interpretation and the render boundary; the reactive container app-db
lives in comes from an [adapter](../glossary.md#adapter), and re-frame2 installs
none for you. **So every Hicasso application calls
[`rf/init!`](../glossary.md#init) with an adapter before it mounts anything** —
one line, on the first line of boot:

```clojure
(rf/init! uix-adapter/adapter)
```

It is not optional and it does not fail quietly. `h/mount!` ensures its frame,
creating a frame asks the adapter for a state container, and a container asked
for before `init!` throws:

```text
rf/make-state-container was called before (rf/init! ...); require an adapter
ns and pass its `adapter` Var, e.g. (rf/init! reagent/adapter).
[:rf.error/no-adapter-installed]
```

*Which* adapter is your choice, and it is the only line that changes between
substrates — see [Use UIx or reagent-slim](../how-to/use-uix-or-slim.md) for the
three and their coordinates. This guide passes `uix-adapter/adapter` throughout,
because that is what Hicasso's own test lane and witnesses run on. It buys
plumbing, not notation: you write Hicasso views either way and never call the
adapter yourself. The headless plain-atom adapter is not a substitute for a
browser app — its derived values are not `IWatchable`, so a subscription under
it notifies nothing.

## Mount a first screen

A production application normally separates registrations and views into
several namespaces. This complete example keeps them together so the boot
sequence is visible:

```clojure
(ns counter.core
  (:require [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(rf/reg-event :counter/initialise
  (fn [_cofx _event]
    {:db {:count 0}}))

(rf/reg-event :counter/increment
  (fn [{:keys [db]} _event]
    {:db (update db :count inc)}))

(rf/reg-sub :counter/count
  (fn [db _query]
    (:count db)))

(h/defview counter [_]
  [:main
   [:h1 "Clicked " (h/sub [:counter/count]) " times"]
   [:button {:on-click [:counter/increment]} "Click me"]])

(defonce root
  (h/mount! (js/document.getElementById "app")
            {:frame          :rf/default
             :initial-events [[:counter/initialise]]}
            [counter]))

(defn ^:dev/after-load rerender! []
  (h/render! root [counter]))
```

Start the build and open the page:

```bash
npx shadow-cljs watch app
# http://localhost:8080
```

The example uses three Hicasso rules:

- `h/defview` creates a Hiccup head. Render it as `[counter]` or
  `[counter {}]`; do not call it as `(counter {})`. Use a plain `defn` for
  markup that should inline into its caller.
- `h/sub` reads during the synchronous view body. It is legal inside a `let`,
  conditional, loop, or ordinary helper called by that body.
- `{:on-click [:counter/increment]}` is an
  [intent](glossary.md#intent). The runtime creates the callback and dispatches
  the event vector to this root's frame.

## What `h/mount!` creates

[`h/mount!`](glossary.md#mount) receives a DOM node, a root configuration, and
one view form. Its `:frame` identifies the re-frame2 frame used by that root.
A frame has its own app-db, event queue, and subscription cache.

Mounting **ensures** the named frame: it creates the frame if it does not exist,
or joins the existing frame if another root already uses it.
`:initial-events` run once, in order, when that mount creates the frame. They
complete before the first paint, which prevents an empty initial render.
Initial state still arrives through events; Hicasso does not add a separate
`:db` seed option.

`[counter]` and `[counter {}]` are equivalent. The body receives an empty props
map in either case.

Keep the returned handle for later renders and teardown:

```clojure
(h/render! root [counter])
(h/unmount! root)
```

`h/unmount!` is idempotent. A second call is a no-op because teardown can be
reached independently by fixtures, reload hooks, and `finally` blocks. The
unmount releases the root's subscriptions as their reference counts reach
zero and makes the DOM node available for another root.

## More than one root

A page can mount several Hicasso roots. The frame id determines whether those
roots share an application.

### Two roots sharing one frame

The first root creates and seeds the frame. A later root that names the same
frame joins its current state and does not replay `:initial-events`:

```clojure
(defonce app-root
  (h/mount! (js/document.getElementById "app")
            {:frame          :app/main
             :initial-events [[:app/initialise]]}
            [main-screen]))

(defonce status-root
  (h/mount! (js/document.getElementById "status")
            {:frame :app/main}
            [connection-badge]))
```

Both roots read the same app-db and dispatch into the same queue. Seed from the
root that creates the frame; joining roots should omit `:initial-events`.

Unmounting one root does not destroy state still used by another root. Teardown
remains per root.

### Two roots using different frames

Give each root a different frame id when they must be isolated. Each frame then
has its own app-db, queue, and subscription cache. A view reads under the frame
of the root that renders it, and subscriptions never cross frames.

This is suitable for cases such as an editor and a live preview that use the
same view code but must not share state.

## Hot reload

The `^:dev/after-load` hook calls `h/render!` with the redefined view. The root,
frame, app-db, and subscriptions survive, so changing the view does not reset
the counter or leak registrations.

Hot reload also means a changed initialisation handler does not re-seed an
already live frame. Reload the page, destroy the frame, or dispatch an explicit
reset event when you need the new initial state.

## Production builds

Build the release normally:

```bash
npx shadow-cljs release app
```

Advanced compilation removes development-only warnings, warning strings, and
Xray instrumentation hooks. Optional modules that were never required add no
code. The Hiccup interpretation itself has the same meaning in development and
production; there is no production-only view mode.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| A mount throws `:rf.error/no-adapter-installed`, naming `rf/make-state-container` | No [adapter](#hicasso-needs-a-substrate-adapter) is installed: `rf/init!` never ran, or ran after the mount | Make `(rf/init! uix-adapter/adapter)` the first line of boot |
| `(counter {})` throws and names the view | A `defview` is a Hiccup head, not a directly callable helper | Render `[counter {}]`. Use a plain `defn` for inline markup |
| `h/sub` in a callback, timer, or promise throws | `:rf.error/hicasso-sub-outside-render`: the read happened outside a synchronous view body | Read during the body and close over the value. Async work should read state through events and coeffects |
| The first paint is empty and then fills in | Initial state was dispatched after mounting | Put the seed events in `:initial-events` so they finish before the first paint |
| A second `h/mount!` fails on the same DOM node | A live root already owns the node | Keep the handle with `defonce`; unmount that root before mounting another |
| A joining root's `:initial-events` never run | The named frame already exists; joining does not replay setup | Seed only from the mount that creates the frame |
| A changed initialisation handler has no effect after hot reload | The live frame kept its existing app-db | Reload, recreate the frame, or dispatch an explicit reset event |
| A view body runs twice when first mounted in development | React StrictMode probes bodies twice | Expected. Keep view bodies pure and safe to re-run |
