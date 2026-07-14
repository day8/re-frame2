(ns day8.re-frame2-xray.viewcell-evidence-cljs-test
  "rf2-vxgfnd.146 — the REAL Xray runtime owns the re-frame.ui ViewCell
  invalidation-evidence projection.

  Requiring `day8.re-frame2-xray.preload` here runs the ACTUAL preload
  boot block (the same namespace shadow-cljs `:devtools/preloads` loads
  into every dev host), and `owner-at-load` snapshots the evidence
  tier's owner IMMEDIATELY after — before any fixture can reset it. If
  the preload's `viewcell-evidence/install!` wiring is removed, that
  snapshot is nil and the wiring test goes RED (the mutation probe the
  bead demands; the browser counterpart lives in the feature-matrix
  shell sweep, which checks the install sentinel + the rendered Views
  panel section against the real chrome runtime).

  The end-to-end row test drives a REAL observation-port movement — a
  committed ViewCell whose subscription lease fans out an `:hmr`
  invalidation, flushed by the scheduler — and asserts the row surfaces
  through Xray's query path (`:rf.xray/viewcell-evidence`, subscribed in
  the `:rf/xray` frame exactly as the Views panel section reads it) with
  stable identity + the bounded epoch/count/batch/cause/target/loss
  fields, then that teardown prunes it. Lifecycle tests pin the HMR
  re-arm (evidence kept), the exact-owner release, and the
  never-clobber-a-foreign-owner contract."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.tool.evidence :as evidence]
            [day8.re-frame2-xray.core :as core]
            [day8.re-frame2-xray.mount :as mount]
            ;; The REAL preload — its debug-gated boot block runs at load,
            ;; installing the evidence projection under Xray's identity.
            [day8.re-frame2-xray.preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.viewcell-evidence :as viewcell-evidence]))

;; Snapshotted at NAMESPACE LOAD — i.e. right after the required preload's
;; boot block ran, before any fixture resets. THE wiring-removal probe.
(def ^:private owner-at-load (evidence/installed-owner))

(def ^:private sentinel-key "__day8_re_frame2_xray_viewcell_evidence")

(def ^:private sentinel-at-load (aget js/globalThis sentinel-key))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture {})
  (fn [f]
    (reactive/reset-scheduler!)
    (evidence/force-release!)
    (try (f) (finally
               (reactive/reset-scheduler!)
               (evidence/force-release!)))))

(def ^:private fid :rf/default)

(defn- boot-xray!
  "The production init path (mirrors `init_filter_reset_cljs_test`):
  register the Xray handlers, then `ensure-xray-frame!` — so the
  `:rf/xray` frame and the `:rf.xray/*` sub graph exist exactly as a
  real page load builds them."
  []
  (registry/register-xray-handlers!)
  (mount/ensure-xray-frame!))

(defn- rc!
  "Render (probe) `queries` under the host frame, then commit — the cell
  ends observing them through REAL subscription leases (the ui
  tool-evidence-test technique for driving genuine observation-port
  fan-out)."
  [cell queries]
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:vce/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- xray-query []
  (rf/with-frame :rf/xray
    @(rf/subscribe [:rf.xray/viewcell-evidence])))

;; ===========================================================================
;; The preload wiring — no test-only direct install stands in for it
;; ===========================================================================

(deftest preload-boot-installs-the-projection-under-xrays-stable-identity
  (is (= viewcell-evidence/owner-id owner-at-load)
      "the REAL preload boot block claimed the evidence projection —
       removing the preload install wiring turns this nil (AC: a mutation
       removing the Xray install must fail)")
  (is (some? sentinel-at-load)
      "…and mirrored the install on the browser-probe sentinel")
  (is (= (str viewcell-evidence/owner-id)
         (aget sentinel-at-load "owner"))))

;; ===========================================================================
;; A real observation movement → ViewCell flush → the Xray query row
;; ===========================================================================

(deftest real-movement-surfaces-in-the-xray-query-row
  (boot-xray!)
  (is (true? (viewcell-evidence/install!)))
  (rf/reg-sub :vce/a (fn [db _] (:a db)))
  (frame/replace-app-db! fid {:a 1})
  (let [cell (rc! (reactive/make-cell ::v) [[:vce/a]])]
    ;; a REAL port fan-out: re-registering the sub fires each committed
    ;; site's live lease `on-change` with the rich `:hmr` payload
    (rf/reg-sub :vce/a (fn [db _] (:a db)))
    (reactive/flush-pending!)

    (testing "the consumer projection carries the bounded row"
      (let [rows (viewcell-evidence/rows)
            row  (first rows)]
        (is (= 1 (count rows)))
        (is (= ::v (:view-id row)) "stable view identity")
        (is (number? (:cell-id row)) "stable per-instance ordinal")
        (is (nil? (:root-id row)) "no live client root owns a graft cell")
        (is (= 1 (:count row)))
        (is (= 1 (:batches row)))
        (is (= #{:hmr} (:causes row)) "the real cause is projected")
        (is (= [[:sub fid [:vce/a]]] (:targets row))
            "…with the moving target key")
        (is (= 0 (:dropped-count row)))
        (is (true? (:dropped-exact? row)) "the honest loss account")))

    (testing "the SAME row surfaces through Xray's query path — the
              `:rf.xray/viewcell-evidence` sub the Views panel reads"
      (let [rows (xray-query)]
        (is (= (viewcell-evidence/rows) rows))
        (is (= ::v (:view-id (first rows))))))

    (testing "identity is stable across further batches"
      (let [id-before (:cell-id (first (viewcell-evidence/rows)))]
        ;; a real re-render re-acquires fresh leases on the re-registered
        ;; nodes before any further movement can fan out to this cell
        (rc! cell [[:vce/a]])
        (rf/reg-sub :vce/a (fn [db _] (:a db)))
        (reactive/flush-pending!)
        (let [row (first (viewcell-evidence/rows))]
          (is (= id-before (:cell-id row)))
          (is (= 2 (:count row)))
          (is (= 2 (:batches row))))))

    (testing "teardown prunes the row — released references, empty query"
      (reactive/teardown! cell)
      (is (= [] (viewcell-evidence/rows)))
      ;; nudge the sub's freshness input (the epoch-pump axis) so a
      ;; cached composite recomputes before the re-query
      (frame/replace-app-db! :rf/xray {:epoch-history [::pumped]})
      (is (= [] (xray-query))))))

;; ===========================================================================
;; Lifecycle — HMR re-arm, exact-owner release, never clobber
;; ===========================================================================

(deftest hmr-rearm-keeps-the-accumulated-evidence
  (is (true? (viewcell-evidence/install!)))
  (let [cell (reactive/make-cell ::v)]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (:count (first (viewcell-evidence/rows)))))
    ;; the `:after-load` path: the preload boot re-runs → same-owner re-arm
    (is (true? (viewcell-evidence/install!)))
    (is (true? (viewcell-evidence/installed?)))
    (is (= 1 (:count (first (viewcell-evidence/rows))))
        "accumulated evidence survives the re-arm")
    (reactive/mark-dirty! cell 2)
    (reactive/flush-pending!)
    (is (= 2 (:count (first (viewcell-evidence/rows))))
        "…and delivery continues into the SAME accumulator")))

(deftest shutdown-releases-the-exact-owner-and-never-clobbers-a-foreign-one
  (testing "uninstall releases exactly Xray's registration"
    (is (true? (viewcell-evidence/install!)))
    (is (some? (aget js/globalThis sentinel-key)))
    (is (true? (viewcell-evidence/uninstall!)))
    (is (nil? (evidence/installed-owner)))
    (is (= [] (viewcell-evidence/rows)) "closed retains zero")
    (is (nil? (aget js/globalThis sentinel-key))
        "the install sentinel is withdrawn with the registration"))

  (testing "a foreign owner is NEVER clobbered — Xray's install is refused"
    (is (true? (evidence/install! ::another-tool)))
    (is (false? (viewcell-evidence/install!)))
    (is (= ::another-tool (evidence/installed-owner))
        "the foreign registration stands")
    (is (false? (viewcell-evidence/installed?)))
    (is (false? (viewcell-evidence/uninstall!))
        "…and Xray's uninstall cannot release what it does not own")
    (is (= ::another-tool (evidence/installed-owner)))))

;; ===========================================================================
;; Ownership fence on the READ path (rf2-vxgfnd.238) — a foreign owner's
;; accumulated evidence is NEVER observable through Xray
;; ===========================================================================

(deftest foreign-owner-evidence-never-surfaces-through-any-xray-read
  ;; A foreign tool owns the shared projection AND has accumulated evidence
  ;; in it — the case the earlier never-clobber test omitted (it installed a
  ;; foreign owner but delivered nothing, so `rows` was empty for the wrong
  ;; reason). Drive a real flush so `evidence/projection` is non-empty.
  (is (true? (evidence/install! ::another-tool)))
  (let [cell (reactive/make-cell ::foreign-v)]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (count (evidence/projection)))
        "the FOREIGN owner accumulated a row in the shared projection")
    ;; Xray's own claim is rejected — Xray does not own the projection.
    (is (false? (viewcell-evidence/install!)))
    (is (false? (viewcell-evidence/installed?)))
    ;; AC: rows + every derived Xray query return NO evidence unless Xray
    ;; owns the installed projection. Fail-before: pre-fix `rows` read the
    ;; shared projection regardless of ownership and surfaced this row.
    (is (= [] (viewcell-evidence/rows))
        "ownership rejection ⇒ zero observation through Xray's rows")
    (boot-xray!)
    (is (= [] (xray-query))
        "…and zero through the :rf.xray/viewcell-evidence query path")
    ;; Xray neither released nor read the foreign owner's projection.
    (is (= ::another-tool (evidence/installed-owner))
        "the foreign registration is untouched")
    (is (= 1 (count (evidence/projection)))
        "…and its evidence still lives in the shared projection")))

;; ===========================================================================
;; core/init! is a full evidence-projection install path (rf2-vxgfnd.238)
;; ===========================================================================

(deftest core-init-claims-the-projection-under-xrays-identity
  ;; A clean process that requires `core` and calls `init!` (the supported
  ;; manual alternative to the preload) must claim the evidence projection,
  ;; or Xray is enabled WITHOUT ViewCell evidence. Fail-before: pre-fix
  ;; `init!` never installed, so this owner stayed nil.
  (is (nil? (evidence/installed-owner)) "clean slate — nobody owns it")
  (core/init!)
  (is (= viewcell-evidence/owner-id (evidence/installed-owner))
      "core/init! claims the projection under Xray's stable owner id")
  (is (true? (viewcell-evidence/installed?)))
  (is (some? (aget js/globalThis sentinel-key))
      "…and mirrors the install on the browser-probe sentinel")
  (testing "idempotent — a second init! is the same-owner re-arm, not a reject"
    (core/init!)
    (is (= viewcell-evidence/owner-id (evidence/installed-owner)))
    (is (true? (viewcell-evidence/installed?)))))

(deftest core-init-does-not-clobber-a-foreign-owner
  ;; The manual path honours the same never-clobber contract as the preload.
  (is (true? (evidence/install! ::another-tool)))
  (core/init!)
  (is (= ::another-tool (evidence/installed-owner))
      "core/init! did not clobber the foreign registration")
  (is (false? (viewcell-evidence/installed?)))
  (is (= [] (viewcell-evidence/rows))
      "…and exposes no evidence while another owner holds the projection"))

(deftest uninstall-then-core-init-reclaims-a-fresh-projection
  ;; dispose (uninstall) then re-init: the ordinary tool-shutdown / test
  ;; door, and a re-init that reclaims a fresh, empty ownership span.
  (is (true? (viewcell-evidence/install!)))
  (let [cell (reactive/make-cell ::v)]
    (reactive/mark-dirty! cell 1)
    (reactive/flush-pending!)
    (is (= 1 (:count (first (viewcell-evidence/rows))))))
  (is (true? (viewcell-evidence/uninstall!)))
  (is (nil? (evidence/installed-owner)))
  (is (= [] (viewcell-evidence/rows)) "closed retains zero")
  ;; re-init through the manual path reclaims the projection, empty
  (core/init!)
  (is (true? (viewcell-evidence/installed?)))
  (is (= [] (viewcell-evidence/rows))
      "re-init starts from a fresh, empty projection"))
