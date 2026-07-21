(ns re-frame.bundle-isolation-positive-control.ui
  ;; rf2-vxgfnd.99.2 — day8/re-frame2-ui became a published, browser-optional
  ;; artefact, so bundle isolation must prove its client sentinel is emitted by
  ;; re-frame.ui (not just absent from the no-feature counter bundle). This
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
