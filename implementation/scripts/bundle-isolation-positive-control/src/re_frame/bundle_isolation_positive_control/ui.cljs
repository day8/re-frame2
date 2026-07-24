(ns re-frame.bundle-isolation-positive-control.ui
  ;; re-frame.ui is in-tree only, never published (rf2-a32r7), but it is a
  ;; browser-capable substrate, so bundle isolation still proves its client
  ;; sentinel is emitted by re-frame.ui (not just absent from the no-feature
  ;; counter bundle) — otherwise an absence check passes on a typo. This
  ;; gate-owned advanced module references public re-frame.ui.runtime fns whose
  ;; bodies raise the :rf.error/ui-tree-malformed reason-id — the ARTEFACTS
  ;; sentinel — so the positive control finds it present here while the counter
  ;; bundle (which never :requires re-frame.ui.*) stays clean.
  (:require [re-frame.ui.runtime :as runtime]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationUi"
        #js [runtime/check-key!
             runtime/child
             runtime/invalid-slot!
             runtime/check-slot-arity!]))
