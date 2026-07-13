(ns re-frame.ui.digest-probe.base
  (:require [re-frame.ui :as ui]
            [re-frame.ui.client :as client]
            [shadow.cljs.devtools.client.browser :as shadow-browser]))

(ui/defview base-view []
  [:main {:data-probe "base"} "digest probe"])

(defn init []
  ;; The carrier is a read-time O(1) value. Keep the accessor stable across
  ;; lazy-only HMR updates; do not cache one sampled scalar and accidentally
  ;; require this unrelated base namespace's after-load hook to run.
  (set! (.-__rf2ReadDigest js/globalThis)
        (fn [] (client/current-build-digest)))
  ;; Exact processed-ready witness from pinned Shadow 3.4.10. A WebSocket
  ;; packet arriving is earlier than the client applying :welcome; the runner
  ;; waits on this before its first edit so no HMR update can race registration.
  (set! (.-__rf2HmrReady js/globalThis)
        (fn [] @shadow-browser/ws-was-welcome-ref))
  (set! (.-__rf2BaseLoaded js/globalThis) true))
