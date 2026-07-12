(ns re-frame.ui.reactive-root-teardown-cljs-test
  "rf2-vxgfnd.62 — explicit host/ROOT teardown → ViewCell dead-state wiring.

  The frame-destroy arm (rf2-vxgfnd.42) gave `teardown!` a caller for the
  frame path; this fixture pins the ROOT path — the counterpart driven from
  `re-frame.ui.client/unmount!*`. The contract (03 §4): React effect cleanup
  fires FIRST during a real `.unmount` and emits `:disconnected {:reason
  :unknown}` (indistinguishable from an Activity hide at that moment); an
  explicit root teardown then retroactively proves those cells `:unmounted
  {:proof :host-teardown}` → `:dead`.

  `reactive/teardown-root!` models this: it arms a collection window around the
  host `.unmount` thunk, so every cell whose cleanup (`disconnect!`) fires
  DURING the unmount is captured, then upgraded via the shared `teardown!`
  primitive. An Activity hide (a `disconnect!` with the window UNARMED) is never
  captured and stays reconnectable — the discriminator this file pins.

  `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`), so the ownership/lifecycle logic is graft-checked on
  both hosts against the REAL observation port + sub-cache (plain-atom
  adapter). The end-to-end `client/unmount!*` wiring is pinned by the cljs-only
  `re-frame.ui.root-teardown-wiring-cljs-test`; the real-DOM proof by the
  browser `re-frame.ui.root-teardown-dom-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- ref-count [fid q]
  (:ref-count (get @(:sub-cache (frame/frame fid)) q)))

(defn- render+commit! [cell fid queries]
  (rf/with-frame fid
    (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))
  (reactive/commit! cell)
  cell)

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ===========================================================================
;; The core contract: a real root unmount proves :unmounted → :dead, and the
;; cleanup fact seen DURING the unmount is the same transient :unknown as a hide
;; ===========================================================================

(deftest root-teardown-collects-a-disconnect-and-proves-unmount
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        cell   (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])
        during (atom nil)]
    (testing "precondition — connected, observing the frame, one owner"
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count fid [:rt/a]))))
    ;; The thunk is the host `.unmount`: it fires the cell's effect cleanup
    ;; (`disconnect!`) synchronously, exactly as React does inside `.unmount`.
    (reactive/teardown-root!
     (fn []
       (reactive/disconnect! cell)
       (reset! during (peek (reactive/intervals cell)))))
    (testing "DURING the unmount the fact is the transient :unknown (03 §4)"
      (is (= {:state :disconnected :reason :unknown} @during)
          "cleanup emits the same immediate fact a real unmount and a hide share"))
    (testing "AFTER the unmount the interval is retroactively proven an unmount"
      (is (= :dead (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell)))
          "the prior disconnect is upgraded to :unmounted {:proof :host-teardown}"))
    (testing "the dead cell holds no leases and no pending notification"
      (is (empty? (reactive/committed-target-keys cell)))
      (is (false? (reactive/dirty? cell)))
      (is (nil? (ref-count fid [:rt/a])) "the lease was released, ref-count gone"))))

(deftest dead-cell-fails-loud-on-recommit-no-resume
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])]
    (reactive/teardown-root! (fn [] (reactive/disconnect! cell)))
    (is (= :dead (reactive/lifecycle cell)))
    (testing "a retained handle recommit fails through the dead-cell lifecycle,
              not by probing a torn-down context (03 §4; commit! step 2)"
      (rf/with-frame fid
        (reactive/with-capture cell (fn [] (reactive/sub-read [:rt/a]))))
      (is (= :rf.error/frame-destroyed (throws-id #(reactive/commit! cell)))))))

;; ===========================================================================
;; The Activity-hide transient path is untouched — a hide never goes :dead
;; ===========================================================================

(deftest activity-hide-then-reveal-stays-transient-never-dead
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])]
    (testing "a hide is a `disconnect!` with NO teardown window armed"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unknown}
             (peek (reactive/intervals cell)))
          "the immediate fact — not :unmounted, and the cell is NOT :dead"))
    (testing "a reveal reconnects and proves the prior interval an Activity hide"
      (render+commit! cell fid [[:rt/a]])
      (is (= :connected (reactive/lifecycle cell)) "reconnected, never :dead")
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "annotated :activity-hidden {:proof :reconnect} — never mislabeled unmounted"))))

(deftest hide-outside-a-window-is-not-captured-by-a-later-teardown
  ;; A cell hidden BEFORE any root teardown left the window unarmed, so it is
  ;; not captured — the collection is bounded to cells disconnecting DURING the
  ;; host unmount (matching live-cells' removed-on-disconnect membership).
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])]
    (reactive/disconnect! cell)                       ;; hide, window unarmed
    (is (= 0 (reactive/teardown-root! (fn [] nil)))   ;; an empty host unmount
        "a teardown whose unmount disconnects nothing tears down nothing")
    (is (= :disconnected (reactive/lifecycle cell))
        "the already-hidden cell is untouched — still reconnectable, not :dead")))

;; ===========================================================================
;; Root isolation — a teardown only claims the cells its own unmount disconnects
;; ===========================================================================

(deftest root-teardown-isolates-sibling-roots
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        cell-a (render+commit! (reactive/make-cell ::a) fid [[:rt/a]])
        cell-b (render+commit! (reactive/make-cell ::b) fid [[:rt/a]])]
    (is (= :connected (reactive/lifecycle cell-a)))
    (is (= :connected (reactive/lifecycle cell-b)))
    ;; Root A's `.unmount` sweeps only root A's tree — only cell-a disconnects.
    (reactive/teardown-root! (fn [] (reactive/disconnect! cell-a)))
    (testing "only the unmounted root's cells die"
      (is (= :dead (reactive/lifecycle cell-a)) "root A's cell")
      (is (= :connected (reactive/lifecycle cell-b))
          "a sibling root's cell is neither annotated nor killed")
      (is (= 1 (ref-count fid [:rt/a]))
          "the sibling's lease is neither released nor churned"))
    (testing "the sibling cell still reads live"
      (is (= 1 (rf/with-frame fid
                 (reactive/with-capture cell-b
                   (fn [] (reactive/sub-read [:rt/a])))))))))

;; ===========================================================================
;; A throwing host `.unmount` — nothing torn down, original error preserved
;; ===========================================================================

(deftest throwing-host-unmount-tears-down-nothing-and-rethrows
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])
        boom (ex-info "host .unmount refused" {:host :react})]
    (testing "the host refused to unmount — React did NOT tear the tree down"
      (is (identical? boom
                      (try (reactive/teardown-root! (fn [] (throw boom)))
                           nil
                           (catch #?(:clj Throwable :cljs :default) e e)))
          "the original host error propagates, never masked"))
    (testing "the orphaned tree's cell stays live — never falsely killed"
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count fid [:rt/a]))))))

;; ===========================================================================
;; Re-entrancy — a nested teardown does not corrupt the outer window
;; ===========================================================================

(deftest teardown-window-is-reentrancy-safe
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        outer  (render+commit! (reactive/make-cell ::outer) fid [[:rt/a]])
        inner  (render+commit! (reactive/make-cell ::inner) fid [[:rt/a]])]
    (reactive/teardown-root!
     (fn []
       ;; a nested teardown claims ONLY the inner cell it disconnects…
       (reactive/teardown-root! (fn [] (reactive/disconnect! inner)))
       (is (= :dead (reactive/lifecycle inner)) "inner torn down by the nested window")
       ;; …then the outer window is restored and claims the outer cell.
       (reactive/disconnect! outer)))
    (is (= :dead (reactive/lifecycle outer))
        "the outer window survived the nested one and proved its own cell")))
