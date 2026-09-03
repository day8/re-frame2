(ns fixtures.multiline-libspec
  "POSITIVE fixture for TRAP 4 — a libspec vector that OPENS on one line and
  CLOSES on the next. `parallel.cljc` carried exactly this shape, and a regex
  anchored on the closing bracket skipped the libspec and 116 use sites with
  it while reporting a clean run. Core carries 39 such libspecs, all already
  canonical, so a line-oriented census would have read clean there too.

  The balanced-bracket scan must still find the bare `result` alias
  (1 finding)."
  (:require [re-frame.core :as rf]
            [re-frame.machines.result
             :as result
             :refer [depth-abort?]]))

(defn settle [info]
  (rf/console :log (result/summary info) (depth-abort? info)))
