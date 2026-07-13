(ns re-frame.ui.digest-probe.lazy
  (:require [re-frame.ui :as ui]))

;; The integration gate rewrites only this marker while one real Shadow watch
;; daemon stays warm. The lazy module is never loaded in the browser; a digest
;; movement therefore proves whole-build compiler membership, not registration.
(ui/defview lazy-view []
  [:aside "digest-probe-v1"])

(set! (.-__rf2LazyExecuted js/globalThis) true)
