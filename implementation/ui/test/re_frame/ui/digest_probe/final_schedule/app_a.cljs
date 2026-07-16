(ns re-frame.ui.digest-probe.final-schedule.app-a
  "Fixture source A. The runner rewrites the single marker below to REMOVE this
  source's final ui/defview between warm passes; the build-local hook then forces
  A to recompile after re-frame.ui observed the schedule."
  (:require [re-frame.ui :as ui]))

;; final-schedule-app-a-marker:present
(ui/defview a-view []
  [:div "app-a-view-v1"])
