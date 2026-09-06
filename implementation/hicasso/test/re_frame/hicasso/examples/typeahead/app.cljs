(ns re-frame.hicasso.examples.typeahead.app
  "THE ENTRY POINT — the four lines that start the typeahead witness.

  The third witness application under `examples/`, and the one written to
  be MEASURED rather than to be read: the slice is the RealWorld-class
  arm, the Todo is the keyed-list arm, and this one exists so that the
  resource-demand verdict can decide whether committed-read resource
  demand earns adoption against the criteria frozen at `afbb58febc`.

  It reaches four namespaces and every one of them is public:

      re-frame.core          events, subscriptions, and :dispatch-later
      re-frame.hicasso       defview, sub, root!, render!, and the markers
      re-frame.adapter.uix   the reactive adapter, installed once at boot
      clojure.string         trim and lower-case, in the model and the
                             stand-in service

  Nothing under `re-frame.hicasso.impl.*`, nothing under
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
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.typeahead.events :as rf.hicasso.examples.typeahead.events]
            [re-frame.hicasso.examples.typeahead.views :as rf.hicasso.examples.typeahead.views]))

(def frame-id
  "This application's frame. One root, one frame."
  ::frame)

(defonce ^:private !root (atom nil))

(defn make-frame!
  "Make the application's frame, seeded.

  Exposed rather than inlined into [[-main]] because it is exactly what a
  test needs: `re-frame.hicasso.test.mounted/mount!` mints its own frame
  and takes `:initial-events`, so the vector below is the value a witness
  hands it."
  []
  (rf/make-frame {:id frame-id :initial-events [[::rf.hicasso.examples.typeahead.events/seed]]}))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload. React reconciles the new
  tree against the one on the page, so the DOM, the subscriptions and
  every scrap of component state survive it."
  []
  (when-some [root @!root]
    (rf.hicasso/render! root [rf.hicasso.examples.typeahead.views/screen {}])))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! rf.adapter.uix/adapter)
  (make-frame!)
  (reset! !root (rf.hicasso/mount! (js/document.getElementById "app") {:frame frame-id} [rf.hicasso.examples.typeahead.views/screen {}]))
  nil)
