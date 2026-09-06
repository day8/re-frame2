(ns re-frame.hicasso.examples.grid.app
  "THE GRID'S ENTRY POINT — an adapter, a frame, a root.

  The editor's entry point with one difference: [[initial-events]] is a
  FUNCTION of the grid's dimensions, because the size is the variable the
  scaling witness moves. Everything else — the namespaced frame keyword,
  the reload handle, the absence of any registered route — is
  `examples.editor.app`'s, and its docstring carries the reasoning."
  (:require [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]
            [re-frame.hicasso.examples.grid.events :as rf.hicasso.examples.grid.events]
            [re-frame.hicasso.examples.grid.views :as rf.hicasso.examples.grid.views]))

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
  ([] (initial-events rf.hicasso.examples.grid.events/default-dimensions))
  ([dimensions] [[::rf.hicasso.examples.grid.events/seed dimensions]]))

(defonce ^:private !root (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload."
  []
  (when-some [root @!root]
    (rf.hicasso/render! root [rf.hicasso.examples.grid.views/grid {}])))

(defn ^:export -main
  "Mount the 100-cell grid on `#app`."
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (reset! !root (rf.hicasso/mount! (js/document.getElementById "app") {:frame frame-id}
                          [rf.hicasso.examples.grid.views/grid {}]))
  nil)
