(ns re-frame.ui.digest-probe.warm-watch.card
  "The declaring source under test. The runner rewrites the single properties
  marker below on a warm pass to SHRINK :probe-card's property set, so the harvest
  must re-read this source and the coarse manifest-change invalidation must
  re-bake the (no-`:require`-edge) consumer view against the new manifest.

  A syntax-broken variant of this file drives the failed-compile / successful-
  retry pass: while it does not compile, no candidate is finalized and the
  accepted last-known-good manifest is preserved."
  (:require [re-frame.ui :as ui]))

;; warm-watch-card-properties:model+size
(ui/custom-element :probe-card {:properties #{:model :size}})
