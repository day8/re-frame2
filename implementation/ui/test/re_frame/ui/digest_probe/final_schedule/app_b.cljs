(ns re-frame.ui.digest-probe.final-schedule.app-b
  "Fixture source B. A stable cache-hit sibling whose accepted view must survive
  A's forced recompile. The runner rewrites B's marker only to TRIGGER a warm
  pass without touching A on disk."
  (:require [re-frame.ui :as ui]))

;; final-schedule-app-b-marker:v1
(ui/defview b-view []
  [:div "app-b-view-v1"])
