(ns re-frame.ui.digest-probe.warm-watch.trigger
  "A VIEWLESS, declarationless source the runner rewrites to drive a warm watch
  pass WITHOUT any custom-element manifest delta. Editing it must move nothing in
  the manifest and must NOT trigger the coarse consumer invalidation, so it is the
  zero-declaration warm-edit proof and the control against which a real
  declaration edit is measured.

  Its top level also emits the runtime MID-CHAIN witness (rf2-4vm19): when a warm
  edit recompiles this source, shadow's loader re-runs it between the
  `^:dev/before-load` `notify-reload!` and the `^:dev/after-load`
  `commit-reload!`, so the recorded `:cycles-open` proves the build's reload
  cycle is genuinely OPEN mid-load — the lifecycle-annotation load-bearingness
  proof at the runtime seam. (Requiring the observe ns adds no re-frame.ui
  require edge, so this source stays outside the UI literal-consumer set.)"
  (:require [re-frame.ui.digest-probe.warm-watch.observe :as observe]))

;; warm-watch-trigger-marker:v1
(def trigger :v1)

(observe/note-load! "trigger")
