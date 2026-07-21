(ns re-frame.ui.reactive-frame-teardown-cljs-test
  "rf2-vxgfnd.42 — frame-destroy → ViewCell teardown wiring.

  core's `frame/destroy-frame!` fires the named cleanup hook
  `:ui/on-frame-destroyed!` (registered in `re-frame.ui.frames`), which sweeps
  every currently-connected ViewCell whose retained subscription targets name
  the destroyed frame to `:dead` (03 §4 dead-cell lifecycle): handles detached,
  pending notification dropped, the retained interval proven an unmount
  (`:unmounted {:proof :host-teardown}`).

  Before this wiring `teardown!` had ZERO callers: a destroyed frame left its
  mounted cells LIVE, so the sub-cache teardown's disposal notification marked
  them dirty and the next flush re-rendered them straight into a
  `:rf.error/frame-destroyed` port throw — the bug this fixture pins closed.

  `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`), so the wiring is graft-checked on both hosts against the
  REAL observation port + sub-cache (plain-atom adapter). The
  `re-frame.ui.frames` require is load-bearing: its `set-fn!` publishes the
  hook `frame/destroy-frame!` reaches."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                  :as rf]
            [re-frame.frame                 :as frame]
            [re-frame.late-bind             :as late-bind]
            [re-frame.live-frame            :as live-frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.substrate.plain-atom  :as plain-atom]
            [re-frame.test-support          :as test-support]
            ;; Load-bearing: registers the :ui/on-frame-destroyed! hook.
            [re-frame.ui.frames]
            [re-frame.ui.reactive           :as reactive]))

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
  (let [[_ capture] (rf/with-frame fid
                      (reactive/with-capture
                       cell (fn [] (mapv (fn [i q]
                                          (reactive/sub-read [:teardown/site i] q))
                                        (range) queries))))]
    (reactive/commit! cell capture))
  cell)

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ===========================================================================
;; The hook is actually published (the wiring, not just the logic)
;; ===========================================================================

(deftest destroy-hook-is-registered
  (is (some? (late-bind/get-fn :ui/on-frame-destroyed!))
      "re-frame.ui.frames must publish :ui/on-frame-destroyed! at load — the
       seam frame/destroy-frame! reaches the compiled-view substrate through")
  ;; rf2-vxgfnd.30 (a): the hook now COMPOSES install-record pruning with the
  ;; ViewCell teardown sweep, so it is no longer identical? to teardown-frame!.
  ;; Assert it still drives the sweep behaviourally — firing the registered
  ;; hook directly transitions a connected cell to :dead.
  (rf/reg-sub :hk/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :hk/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::hook-v) fid [[:hk/a]])]
    (is (= :connected (reactive/lifecycle cell)) "precondition: connected")
    ((late-bind/get-fn :ui/on-frame-destroyed!) fid)
    (is (= :dead (reactive/lifecycle cell))
        "the registered hook runs the substrate's frame-destroy sweep")))

;; ===========================================================================
;; The core contract: a destroyed frame transitions its cells to :dead
;; ===========================================================================

(deftest frame-destroy-transitions-connected-cell-to-dead
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :td/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:td/a]])]
    (testing "precondition — the cell is connected and observes the frame"
      (is (= :connected (reactive/lifecycle cell)))
      (is (reactive/cell-observes-frame? cell fid))
      (is (= 1 (ref-count fid [:td/a])) "one owner installed at commit"))

    (testing "destroying the frame completes cleanly and marks the cell :dead"
      ;; The destroy itself must not throw — the port throw the bug produced
      ;; came LATER, on a spurious re-render of a still-live cell.
      (is (nil? (frame/destroy-frame! fid)) "destroy-frame! is a clean nil")
      (is (= :dead (reactive/lifecycle cell))
          "the destroyed frame's cell followed the dead-cell lifecycle (03 §4)
           — NOT left live to throw :rf.error/frame-destroyed on next read"))))

(deftest frame-destroy-reaps-an-activity-hidden-cell
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :td/hidden-frame {:a 1})
        root (reactive/make-root-incarnation)
        cell (reactive/attach-root! (reactive/make-cell ::hidden) root)]
    (render+commit! cell fid [[:td/a]])
    (reactive/disconnect! cell)
    (testing "precondition — Activity cleanup released live deps but retained ownership"
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (false? (reactive/cell-observes-frame? cell fid)))
      (is (= 1 (reactive/root-cell-count root))))
    (testing "destroying its last published frame follows the clean dead lifecycle"
      (frame/destroy-frame! fid)
      (is (= :dead (reactive/lifecycle cell))
          "a later Activity reveal cannot read through a destroyed frame")
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell))))
      (is (= 0 (reactive/root-cell-count root))
          "final teardown drops the retained root membership"))))

(deftest frame-destroy-leaves-another-frames-activity-hidden-cell-reconnectable
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [destroyed-fid (make-frame! :td/destroyed-hidden-frame {:a 1})
        sibling-fid   (make-frame! :td/sibling-hidden-frame {:a 2})
        destroyed     (reactive/attach-root! (reactive/make-cell ::destroyed)
                                             (reactive/make-root-incarnation))
        sibling       (reactive/attach-root! (reactive/make-cell ::sibling)
                                             (reactive/make-root-incarnation))]
    (render+commit! destroyed destroyed-fid [[:td/a]])
    (render+commit! sibling sibling-fid [[:td/a]])
    (reactive/disconnect! destroyed)
    (reactive/disconnect! sibling)
    (frame/destroy-frame! destroyed-fid)
    (is (= :dead (reactive/lifecycle destroyed)))
    (is (= :disconnected (reactive/lifecycle sibling))
        "retained target keys scope the hidden-cell sweep to the destroyed frame")
    (reactive/settle-disconnect! sibling)
    (render+commit! sibling sibling-fid [[:td/a]])
    (is (= :connected (reactive/lifecycle sibling))
        "the other frame's hidden cell remains revealable")))

(deftest frame-destroy-emits-the-unmount-fact
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :td/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:td/a]])]
    (frame/destroy-frame! fid)
    (is (= {:state :unmounted :reason :unmounted :proof :host-teardown}
           (peek (reactive/intervals cell)))
        "a frame-destroy under a mounted cell proves the interval an unmount
         (03 §4 — :unmounted {:proof :host-teardown})")))

(deftest frame-destroy-detaches-handles-and-drops-pending
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :td/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:td/a]])
        rev0 (reactive/revision cell)]
    (frame/destroy-frame! fid)
    (testing "the dead cell holds no handles and no pending notification"
      (is (empty? (reactive/committed-target-keys cell))
          "teardown detached every committed handle")
      (is (false? (reactive/dirty? cell))
          "no stale pending notification lingers in the dirty registry"))
    (testing "a flush cannot re-render the dead cell into a port throw"
      (reactive/flush-pending!)
      (is (= rev0 (reactive/revision cell))
          "the dead cell's revision does not advance — nothing schedules a
           re-render that would probe the destroyed frame"))))

(deftest frame-really-destroyed-but-cell-took-the-lifecycle-path
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :td/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:td/a]])]
    (frame/destroy-frame! fid)
    (testing "the observation port DOES throw frame-destroyed against the gone
              frame — proving it is truly destroyed"
      (is (= :rf.error/frame-destroyed
             (throws-id #(obs/probe {:kind :subscription :frame-id fid
                                     :query [:td/a]}
                                    nil)))))
    (testing "…yet the cell reached :dead through the lifecycle sweep, not by
              being caught in that throw"
      (is (= :dead (reactive/lifecycle cell))))
    (testing "a :dead cell still fails loudly on re-commit — no resume (03 §4)"
      (let [[_ capture] (rf/with-frame fid
                          (reactive/with-capture cell (fn [] nil)))]
        (is (= :rf.error/frame-destroyed
               (throws-id #(reactive/commit! cell capture))))))))

;; ===========================================================================
;; The sweep is frame-scoped — a sibling frame's cells are untouched
;; ===========================================================================

(deftest frame-destroy-leaves-sibling-frames-cells-alive
  (rf/reg-sub :td/a (fn [db _] (:a db)))
  (let [fa     (make-frame! :td/frame-a {:a 1})
        fb     (make-frame! :td/frame-b {:a 2})
        cell-a (render+commit! (reactive/make-cell ::va) fa [[:td/a]])
        cell-b (render+commit! (reactive/make-cell ::vb) fb [[:td/a]])]
    (is (= :connected (reactive/lifecycle cell-a)))
    (is (= :connected (reactive/lifecycle cell-b)))
    (frame/destroy-frame! fa)
    (testing "only the destroyed frame's observers die"
      (is (= :dead (reactive/lifecycle cell-a)) "cell under the destroyed frame")
      (is (= :connected (reactive/lifecycle cell-b))
          "a cell observing a DIFFERENT live frame is untouched")
      (is (= 1 (ref-count fb [:td/a]))
          "the sibling frame's handle is neither released nor churned")
      (is (reactive/cell-observes-frame? cell-b fb)))
    (testing "the sibling frame still reads live"
      (is (= 2 (first (rf/with-frame fb
                        (reactive/with-capture cell-b
                          (fn [] (reactive/sub-read ::site [:td/a]))))))))))
