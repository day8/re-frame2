(ns re-frame.ui.digest-probe.final-schedule.trigger
  "A VIEWLESS source the runner rewrites to trigger a warm watch pass WITHOUT
  editing app-a or app-b on disk. Because it declares no ui/defview, editing it
  moves nothing in the accepted view aggregate, so any digest change observed
  across the forced-recompile pass is attributable solely to app-a's eviction —
  never to a fingerprint change in an edited view source.")

;; final-schedule-trigger-marker:v1
(def trigger :v1)
