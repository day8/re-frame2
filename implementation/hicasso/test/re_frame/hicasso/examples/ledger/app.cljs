(ns re-frame.hicasso.examples.ledger.app
  "THE LEDGER'S ENTRY POINT — an adapter, a frame, a root (rf2-hic-047).

  `examples.grid.app`'s shape, with the model size as [[initial-events]]'
  parameter for the same reason: the size is the independent variable of
  `ledger.virtualized-dom-cljs-test`, which mounts the same application
  at a hundred records and at ten thousand and asserts that the numbers
  it measures did not move.

  One root, one frame, no route — see `examples.witness-surface-cljs-test`
  on why an application in this tree registers no route."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.ledger.events :as events]
            [re-frame.hicasso.examples.ledger.views :as views]))

(def frame-id
  "This application's frame. Namespaced, so two applications in one
  process cannot claim it."
  ::frame)

(defn initial-events
  "What seeds a fresh frame holding `total` records."
  ([] (initial-events events/default-total))
  ([total] [[::events/seed {:total total}]]))

(defonce ^:private !root (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload."
  []
  (when-some [root @!root]
    (h/render! root [views/ledger {}])))

(defn ^:export -main
  "Mount the ten-thousand-row ledger on `#app`."
  []
  (rf/init! uix-adapter/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (reset! !root (h/root! (js/document.getElementById "app") frame-id
                         [views/ledger {}]))
  nil)
