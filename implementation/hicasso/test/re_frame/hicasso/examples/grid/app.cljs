(ns re-frame.hicasso.examples.grid.app
  "THE GRID'S ENTRY POINT — an adapter, a frame, a root (rf2-hic-078).

  The editor's entry point with one difference: [[initial-events]] is a
  FUNCTION of the grid's dimensions, because the size is the variable the
  scaling witness moves. Everything else — the namespaced frame keyword,
  the reload handle, the absence of any registered route — is
  `examples.editor.app`'s, and its docstring carries the reasoning."
  (:require [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.grid.events :as events]
            [re-frame.hicasso.examples.grid.views :as views]))

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
  ([] (initial-events events/default-dimensions))
  ([dimensions] [[::events/seed dimensions]]))

(defonce ^:private !root (atom nil))

(defn ^:dev/after-load reload!
  "Re-render the mounted root after a hot reload."
  []
  (when-some [root @!root]
    (h/render! root [views/grid {}])))

(defn ^:export -main
  "Mount the 100-cell grid on `#app`."
  []
  (rf/init! uix-adapter/adapter)
  (rf/make-frame {:id frame-id :initial-events (initial-events)})
  (reset! !root (h/root! (js/document.getElementById "app") frame-id
                         [views/grid {}]))
  nil)
