(ns re-frame.fresco.examples.todo.app
  "THE ENTRY POINT — the four lines that start a Todo-class application.

  `re-frame.fresco.consumer-app` is the smallest complete Fresco
  application; `…examples.slice.app` is the RealWorld-class arm — two
  routes, an editor, an async mutation that fails, a runtime locale
  switch. This is the OTHER ordinary shape §7's use-case row names: a
  keyed list that grows and shrinks under the user's hands, a routed
  filter, edit-in-place, and chrome that comes and goes.

  It reaches five namespaces and every one of them is public:

      re-frame.core          events and subscriptions
      re-frame.routing       reg-route — the filter is a URL
      re-frame.fresco       defview, sub, route-link, reg-state,
                             root!, render!, and the markers
      re-frame.adapter.uix   the reactive adapter, installed once at boot
      clojure.string         trim, and joining two class names

  Nothing under `re-frame.fresco.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `re-frame.fresco.examples.fence-cljs-test` asserts that off every `ns`
  form under `examples/`, on every run, rather than off a reading of this
  list.

  ## Why the adapter, and why UIx

  Fresco ships no reactive adapter; a consumer picks one exactly as they
  already do for Reagent or UIx views (Spec 006 §Adapter selection at
  boot). It has to be a REACTIVE one: a keystroke moves `app-db` and the
  boundary that read it must re-render, and the headless plain-atom
  adapter's derived value is not `IWatchable`, so a moving subscription
  under it notifies nothing at all."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.todo.events :as rf.fresco.examples.todo.events]
            [re-frame.fresco.examples.todo.routes :as rf.fresco.examples.todo.routes]
            [re-frame.fresco.examples.todo.views :as rf.fresco.examples.todo.views]))

(def frame-id
  "This application's frame. One root, one frame; a page holding a second
  Fresco application would mint a second, and the two could not see each
  other's state."
  ::frame)

(def sample-todos
  "What a fresh page opens with. Data rather than an empty list, because
  a screen with nothing on it exercises none of the chrome the
  conditional branches are about."
  ["Read the spec" "Write the witness" "Merge the PR"])

(defonce ^:private app-root
  ;; `defonce`, because a reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to render through.
  ;; Inert until the first render — no DOM work at allocation.
  (rf.fresco/client-root))

(defn make-frame!
  "Make the application's frame, seeded and pointed at *All*.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.fresco.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the two events below are the value a
  witness hands it."
  []
  (rf.fresco.examples.todo.routes/register!)
  (rf/make-frame {:id             frame-id
                  :initial-events [[::rf.fresco.examples.todo.events/seed sample-todos]
                                   [:rf.route/navigate {:to rf.fresco.examples.todo.routes/all}]]}))

(defn ^:dev/after-load mount!
  "Render the application through its one client-root handle — the boot
  path and the hot-reload hook, in one call. The FIRST call creates the
  React root; every later one updates that same root, so the DOM, the
  subscriptions and every scrap of component state survive a reload."
  []
  (rf.fresco/render! app-root
    [rf.fresco/frame-root {:id frame-id}
     [rf.fresco.examples.todo.views/app {}]]
    (js/document.getElementById "app")))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! rf.adapter.uix/adapter)
  (make-frame!)
  (mount!)
  nil)
