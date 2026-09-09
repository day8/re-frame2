(ns re-frame.bundle-isolation-positive-control.hicasso
  ;; day8/re-frame2-hicasso (rf2-gra70) is a separately published,
  ;; browser-reachable OPTIONAL view substrate, so bundle isolation asserts its
  ;; client kernel is ABSENT from the no-feature counter bundle. An absence
  ;; check passes on a typo, so this gate-owned `:advanced` module proves the
  ;; two sentinels are really emitted by the artefact.
  ;;
  ;; Both are chosen because they SURVIVE `:advanced` with `goog.DEBUG` false —
  ;; which most Hicasso strings deliberately do not, the package's whole
  ;; production story being that its complaint machinery folds away
  ;; (`hicasso/scripts/check_production_erasure.cjs`). These two are that
  ;; script's own positive controls, i.e. the strings it asserts PRESENT in the
  ;; `:hicasso-release` bundle:
  ;;
  ;;   - `hicassoBoundary` — the own-property marker `mark-boundary!` stamps on
  ;;     every minted head, `unchecked-set` with a literal string key, ungated;
  ;;   - `rf.error/hicasso-empty-vector` — a refusal id minted by `fail!` on the
  ;;     path every build keeps, not inside its dev guard.
  ;;
  ;; `impl.codec` rather than the public door: the sentinels live in that
  ;; namespace's function bodies, and naming the exact fns is what keeps the
  ;; control honest under DCE.
  (:require [re-frame.hicasso.impl.codec :as rf.hicasso.impl.codec]))

(defn ^:export run []
  (aset js/globalThis "__rf2BundleIsolationHicasso"
        #js [rf.hicasso.impl.codec/mark-boundary!
             rf.hicasso.impl.codec/boundary-head?
             rf.hicasso.impl.codec/vector-kind]))
