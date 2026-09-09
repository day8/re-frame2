(ns re-frame.fresco.examples.grid.app
  "THE GRID'S ENTRY POINT — an adapter, a frame, a root.

  The editor's entry point with one difference: [[initial-events]] is a
  FUNCTION of the grid's dimensions, because the size is the variable the
  scaling witness moves. Everything else — the namespaced frame keyword,
  the reload handle, the absence of any registered route — is
  `examples.editor.app`'s, and its docstring carries the reasoning."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.examples.grid.events :as rf.fresco.examples.grid.events]
            [re-frame.fresco.examples.grid.views :as rf.fresco.examples.grid.views]))

(def frame-id
  "This application's frame. Namespaced, so two applications in one
  process cannot claim it."
  ::frame)

(defn initial-events
  "What seeds a fresh frame at `dimensions` — `{:rows n :cols n}`.

  A function and not a constant because the size is the independent
  variable of `grid.scaling-dom-cljs-test`: the suite mounts the same
  application twice and the only difference between the two mounts is
  what this returns."
  ([] (initial-events rf.fresco.examples.grid.events/default-dimensions))
  ([dimensions] [[::rf.fresco.examples.grid.events/seed dimensions]]))

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
     [rf.fresco.examples.grid.views/grid {}]]
    (js/document.getElementById "app")))

(defn ^:export -main
  "Mount the 100-cell grid on `#app`."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (mount!)
  nil)
