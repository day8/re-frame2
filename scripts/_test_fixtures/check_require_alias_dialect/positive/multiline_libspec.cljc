(ns fixtures.multiline-libspec
  "POSITIVE fixture for TRAP 4 — a libspec vector that OPENS on one line and
  CLOSES on the next. A regex anchored on the closing bracket skips such a
  libspec and every use site with it while reporting a clean run, and
  where every such libspec is already canonical a line-oriented census
  reads clean too.

  The balanced-bracket scan must still find the bare `result` alias
  (1 finding)."
  (:require [re-frame.core :as rf]
            [re-frame.machines.result
             :as result
             :refer [depth-abort?]]))

(defn settle [info]
  (rf/console :log (result/summary info) (depth-abort? info)))
