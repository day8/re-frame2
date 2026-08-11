(ns re-frame.hicasso.examples.todo.app
  "THE ENTRY POINT — the four lines that start a Todo-class application
  (rf2-hic-086).

  `re-frame.hicasso.consumer-app` is the smallest complete Hicasso
  application; `…examples.slice.app` is the RealWorld-class arm — two
  routes, an editor, an async mutation that fails, a runtime locale
  switch. This is the OTHER ordinary shape §7's use-case row names: a
  keyed list that grows and shrinks under the user's hands, a routed
  filter, edit-in-place, and chrome that comes and goes.

  It reaches five namespaces and every one of them is public:

      re-frame.core          events and subscriptions
      re-frame.routing       reg-route — the filter is a URL
      re-frame.hicasso       defview, sub, route-link, reg-state,
                             root!, render!, and the markers
      re-frame.adapter.uix   the reactive adapter, installed once at boot
      clojure.string         trim, and joining two class names

  Nothing under `re-frame.hicasso.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `re-frame.hicasso.examples.todo.surface-cljs-test` asserts that off the
  ClojureScript ANALYZER's dependency graph rather than off a reading of
  this list.

  ## Why the adapter, and why UIx

  Hicasso ships no reactive adapter; a consumer picks one exactly as they
  already do for Reagent or UIx views (Spec 006 §Adapter selection at
  boot). It has to be a REACTIVE one: a keystroke moves `app-db` and the
  boundary that read it must re-render, and the headless plain-atom
  adapter's derived value is not `IWatchable`, so a moving subscription
  under it notifies nothing at all."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.todo.events :as events]
            [re-frame.hicasso.examples.todo.routes :as routes]
            [re-frame.hicasso.examples.todo.views :as views]))

(def frame-id
  "This application's frame. One root, one frame; a page holding a second
  Hicasso application would mint a second, and the two could not see each
  other's state."
  ::frame)

(def sample-todos
  "What a fresh page opens with. Data rather than an empty list, because
  a screen with nothing on it exercises none of the chrome the
  conditional branches are about."
  ["Read the spec" "Write the witness" "Merge the PR"])

(defonce ^:private !root (atom nil))

(defn make-frame!
  "Make the application's frame, seeded and pointed at *All*.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.hicasso.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the two events below are the value a
  witness hands it."
  []
  (routes/register!)
  (rf/make-frame {:id             frame-id
                  :initial-events [[::events/seed sample-todos]
                                   [:rf.route/navigate {:to routes/all}]]}))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload. React reconciles the
  new tree against the one on the page, so the DOM, the subscriptions and
  every scrap of component state survive it and only the changed view
  code is different. A second `h/root!` would `createRoot` again and
  discard all three."
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
