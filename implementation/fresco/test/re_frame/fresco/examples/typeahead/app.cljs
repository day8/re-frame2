(ns re-frame.fresco.examples.typeahead.app
  "THE ENTRY POINT — the four lines that start the typeahead witness.

  The third witness application under `examples/`, and the one written to
  be MEASURED rather than to be read: the slice is the RealWorld-class
  arm, the Todo is the keyed-list arm, and this one exists so that the
  resource-demand verdict can decide whether committed-read resource
  demand earns adoption against the criteria frozen at `afbb58febc`.

  It reaches four namespaces and every one of them is public:

      re-frame.core          events, subscriptions, and :dispatch-later
      re-frame.fresco       defview, sub, root!, render!, and the markers
      re-frame.adapter.uix   the reactive adapter, installed once at boot
      clojure.string         trim and lower-case, in the model and the
                             stand-in service

  Nothing under `re-frame.fresco.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `examples.fence-cljs-test` asserts that for every application under
  `examples/`, read off each `ns` form at run time rather than off this
  list.

  ## It registers no route

  Deliberately, and nothing enforces the absence. Route paths
  are plain strings in a process-global registrar and the shared node
  bundle loads every application in the tree into one process. Nothing
  about a resource witness needs a URL."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.typeahead.events :as rf.fresco.examples.typeahead.events]
            [re-frame.fresco.examples.typeahead.views :as rf.fresco.examples.typeahead.views]))

(def frame-id
  "This application's frame. One root, one frame."
  ::frame)

(defonce ^:private app-root
  ;; `defonce`, because a reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to render through.
  ;; Inert until the first render — no DOM work at allocation.
  (rf.fresco/client-root))

(defn make-frame!
  "Make the application's frame, seeded.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.fresco.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the vector below is the value a witness
  hands it."
  []
  (rf/make-frame {:id frame-id :initial-events [[::rf.fresco.examples.typeahead.events/seed]]}))

(defn ^:dev/after-load mount!
  "Render the application through its one client-root handle — the boot
  path and the hot-reload hook, in one call. The FIRST call creates the
  React root; every later one updates that same root, so the DOM, the
  subscriptions and every scrap of component state survive a reload."
  []
  (rf.fresco/render! app-root
    [rf.fresco/frame-root {:id frame-id}
     [rf.fresco.examples.typeahead.views/screen {}]]
    (js/document.getElementById "app")))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! rf.adapter.uix/adapter)
  (make-frame!)
  (mount!)
  nil)
