(ns re-frame.hicasso.examples.slice.app
  "THE SLICE'S ENTRY POINT — the four lines that start it.

  `re-frame.hicasso.consumer-app` is the smallest complete Hicasso
  application and proves that a clean consumer can compile and run one.
  This is the next size up: routing, a keyed list, an edit form with
  controlled fields, an async mutation with a real failure, an error
  region, a reset, and a runtime locale and theme switch — with the same
  `:require` discipline, which is what makes it evidence about the door
  rather than about the runtime.

      re-frame.core            events, subscriptions, frames
      re-frame.routing         reg-route, and the navigate event
      re-frame.hicasso         defview, sub, boundary, route-link,
                               reg-state, root!, render!
      re-frame.adapter.uix     the reactive adapter, installed at boot

  Nothing under `re-frame.hicasso.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `re-frame.hicasso.examples.fence-cljs-test` asserts that off every `ns`
  form under `examples/`, on every run, rather than off a reading of this
  list.

  ## Why the adapter, and why UIx

  Hicasso ships no reactive adapter and a consumer picks one, exactly as
  they already do for Reagent or UIx views (Spec 006 §Adapter selection
  at boot). It has to be a REACTIVE one here: a keystroke moves `app-db`
  and the boundary that read it must re-render, so the adapter's job is
  to bridge reactive change into React's re-render. The headless
  plain-atom adapter cannot — its derived value is not `IWatchable`, so a
  moving subscription under it notifies nothing at all.

  ## Why the handle is a `defonce`

  For the reason any hot-reloadable application holds one: a plain `def`
  is re-evaluated by the reload, and the handle would be replaced by the
  event it exists to survive. [[mount!]] re-renders the root React
  already has, so the reloaded view code meets its own DOM; a second
  handle would `createRoot` again and discard every node, subscription
  and scrap of component state."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.slice.events :as rf.hicasso.examples.slice.events]
            [re-frame.hicasso.examples.slice.routes :as rf.hicasso.examples.slice.routes]
            [re-frame.hicasso.examples.slice.views :as rf.hicasso.examples.slice.views]))

(def frame-id
  "This application's frame. One root, one frame; a page holding a second
  Hicasso application would mint a second and the two could not see each
  other's state."
  ::frame)

(defonce ^:private app-root
  ;; `defonce`, because a reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to render through.
  ;; Inert until the first render — no DOM work at allocation.
  (rf.hicasso/client-root))

(defn make-frame!
  "Make the slice's frame, seeded and pointed at the feed.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.hicasso.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the two steps below are the value a
  witness passes it. A consumer calls it once, here."
  []
  (rf.hicasso.examples.slice.routes/register!)
  (rf/make-frame {:id             frame-id
                  :initial-events [[::rf.hicasso.examples.slice.events/seed]
                                   [:rf.route/navigate {:to rf.hicasso.examples.slice.routes/feed}]]}))

(defn ^:dev/after-load mount!
  "Render the application through its one client-root handle — the boot
  path and the hot-reload hook, in one call. The FIRST call creates the
  React root; every later one updates that same root, so the DOM, the
  subscriptions and every scrap of component state survive a reload."
  []
  (rf.hicasso/render! app-root
    [rf.hicasso/frame-root {:id frame-id}
     [rf.hicasso.examples.slice.views/app {}]]
    (js/document.getElementById "app")))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! rf.adapter.uix/adapter)
  (make-frame!)
  (mount!)
  nil)
