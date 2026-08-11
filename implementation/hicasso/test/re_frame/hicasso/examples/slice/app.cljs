(ns re-frame.hicasso.examples.slice.app
  "THE SLICE'S ENTRY POINT — the four lines that start it (rf2-hic-025).

  `re-frame.hicasso.consumer-app` is the smallest complete Hicasso
  application and proves that a clean consumer can compile and run one.
  This is the next size up: routing, a keyed list, an edit form with
  controlled fields, an async mutation with a real failure, an error
  region, a reset, and a runtime locale and theme switch — with the same
  `:require` discipline, which is what makes it evidence about the door
  rather than about the runtime.

      re-frame.core            events, subscriptions, frames
      re-frame.routing         reg-route, and the navigate event
      re-frame.hicasso         defview, sub, use-subs, boundary,
                               route-link, reg-state, root!, render!
      re-frame.adapter.uix     the reactive adapter, installed at boot

  Nothing under `re-frame.hicasso.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `re-frame.hicasso.examples.slice.surface-cljs-test` asserts that off
  the ClojureScript ANALYZER's own dependency graph rather than off a
  reading of this list.

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
  event it exists to survive. [[reload!]] re-renders the root React
  already has, so the reloaded view code meets its own DOM; a second
  `h/root!` would `createRoot` again and discard every node, subscription
  and scrap of component state."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.slice.events :as events]
            [re-frame.hicasso.examples.slice.routes :as routes]
            [re-frame.hicasso.examples.slice.views :as views]))

(def frame-id
  "This application's frame. One root, one frame; a page holding a second
  Hicasso application would mint a second and the two could not see each
  other's state."
  ::frame)

(defonce ^:private !root (atom nil))

(defn make-frame!
  "Make the slice's frame, seeded and pointed at the feed.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.hicasso.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the two steps below are the value a
  witness passes it. A consumer calls it once, here."
  []
  (routes/register!)
  (rf/make-frame {:id             frame-id
                  :initial-events [[::events/seed]
                                   [:rf.route/navigate {:to routes/feed}]]}))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload. React reconciles the new
  tree against the one on the page, so the DOM, the subscriptions and
  every scrap of component state survive it and only the changed view
  code is different."
  []
  (when-some [root @!root]
    (h/render! root [views/app {}])))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! uix-adapter/adapter)
  (make-frame!)
  (reset! !root (h/root! (js/document.getElementById "app") frame-id [views/app {}]))
  nil)
