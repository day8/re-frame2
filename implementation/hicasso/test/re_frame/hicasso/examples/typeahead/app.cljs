(ns re-frame.hicasso.examples.typeahead.app
  "THE ENTRY POINT — the four lines that start the typeahead witness
  (rf2-hic-044).

  The third witness application under `examples/`, and the one written to
  be MEASURED rather than to be read: the slice is the RealWorld-class
  arm, the Todo is the keyed-list arm, and this one exists so that
  `rf2-hic-050` can decide whether committed-read resource demand earns
  adoption against the criteria frozen at `afbb58febc`.

  It reaches four namespaces and every one of them is public:

      re-frame.core          events, subscriptions, and :dispatch-later
      re-frame.hicasso       defview, sub, root!, render!, and the markers
      re-frame.adapter.uix   the reactive adapter, installed once at boot
      clojure.string         trim and lower-case, in the model and the
                             stand-in service

  Nothing under `re-frame.hicasso.impl.*`, nothing under
  `re-frame.bench.*`, nothing under `tools/`, and no test-kit namespace.
  `…typeahead.surface-cljs-test` asserts that off the ClojureScript
  ANALYZER's dependency graph rather than off a reading of this list.

  ## It registers no route

  Deliberately, and the surface suite asserts the absent edge. Route paths
  are plain strings in a process-global registrar and the shared node
  bundle loads every application in the tree into one process
  (rf2-hic-025 finding 8, rf2-wqnl). Nothing about a resource witness
  needs a URL."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.typeahead.events :as events]
            [re-frame.hicasso.examples.typeahead.views :as views]))

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
  (rf/make-frame {:id frame-id :initial-events [[::events/seed]]}))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload. React reconciles the new
  tree against the one on the page, so the DOM, the subscriptions and
  every scrap of component state survive it."
  []
  (when-some [root @!root]
    (h/render! root [views/screen {}])))

(defn ^:export -main
  "Start the application."
  []
  (rf/init! uix-adapter/adapter)
  (make-frame!)
  (reset! !root (h/root! (js/document.getElementById "app") frame-id [views/screen {}]))
  nil)
