(ns re-frame.hicasso.examples.ledger.app
  "THE LEDGER'S ENTRY POINT — an adapter, a frame, a root.

  `examples.grid.app`'s shape, with the model size as [[initial-events]]'
  parameter for the same reason: the size is the independent variable of
  `ledger.virtualized-dom-cljs-test`, which mounts the same application
  at a hundred records and at ten thousand and asserts that the numbers
  it measures did not move.

  One root, one frame, no route — `examples.editor.app` says why an
  application in this tree registers none."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.ledger.events :as rf.hicasso.examples.ledger.events]
            [re-frame.hicasso.examples.ledger.views :as rf.hicasso.examples.ledger.views]))

(def frame-id
  "This application's frame. Namespaced, so two applications in one
  process cannot claim it."
  ::frame)

(defn initial-events
  "What seeds a fresh frame holding `total` records."
  ([] (initial-events rf.hicasso.examples.ledger.events/default-total))
  ([total] [[::rf.hicasso.examples.ledger.events/seed {:total total}]]))

(defonce ^:private !root (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload."
  []
  (when-some [root @!root]
    (rf.hicasso/render! root [rf.hicasso.examples.ledger.views/ledger {}])))

(defn ^:export -main
  "Mount the ten-thousand-row ledger on `#app`."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (reset! !root (rf.hicasso/mount! (js/document.getElementById "app") {:frame frame-id}
                          [rf.hicasso.examples.ledger.views/ledger {}]))
  nil)
