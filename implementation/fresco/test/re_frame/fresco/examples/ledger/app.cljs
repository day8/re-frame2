(ns re-frame.fresco.examples.ledger.app
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
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.ledger.events :as rf.fresco.examples.ledger.events]
            [re-frame.fresco.examples.ledger.views :as rf.fresco.examples.ledger.views]))

(def frame-id
  "This application's frame. Namespaced, so two applications in one
  process cannot claim it."
  ::frame)

(defn initial-events
  "What seeds a fresh frame holding `total` records."
  ([] (initial-events rf.fresco.examples.ledger.events/default-total))
  ([total] [[::rf.fresco.examples.ledger.events/seed {:total total}]]))

(defonce ^:private app-root
  ;; `defonce`, because a reload re-evaluates this namespace and a plain
  ;; `def` would replace the handle the reload exists to render through.
  ;; Inert until the first render — no DOM work at allocation.
  (rf.fresco/client-root))

(defn ^:dev/after-load mount!
  "Render the application through its one client-root handle — the boot
  path and the hot-reload hook, in one call. The FIRST call creates the
  React root; every later one updates that same root, so the DOM, the
  subscriptions and every scrap of component state survive a reload."
  []
  (rf.fresco/render! app-root
    [rf.fresco/frame-root {:id frame-id}
     [rf.fresco.examples.ledger.views/ledger {}]]
    (js/document.getElementById "app")))

(defn ^:export -main
  "Mount the ten-thousand-row ledger on `#app`."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (mount!)
  nil)
