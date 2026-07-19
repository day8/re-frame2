(ns re-frame.ui.digest-probe.warm-watch.trigger
  "A VIEWLESS, declarationless source the runner rewrites to drive a warm watch
  pass WITHOUT any custom-element manifest delta. Editing it must move nothing in
  the manifest and must NOT trigger the coarse consumer invalidation, so it is the
  zero-declaration warm-edit proof and the control against which a real
  declaration edit is measured.")

;; warm-watch-trigger-marker:v1
(def trigger :v1)
