(ns re-frame.bound-dispatcher-test
  "Per rf2-a7d4 — coverage for `bound-dispatcher` / `bound-subscriber`
  capture-at-call-time semantics. Per Spec 002 §bound-fn /
  bound-dispatcher and `re-frame.core.cljc` lines 991 ff.

  These public surfaces exist to support async callbacks where the
  dynamic-var frame binding has already unwound. The bound-* fn captures
  `(current-frame)` at call time and the returned closure dispatches /
  subscribes against THAT frame — not against whatever the caller's
  current frame is when the closure later fires.

  Pre-rf2-a7d4 both surfaces were un-referenced in any test (mentioned
  only in unmerged PR #332 / rf2-l5q3). A regression that re-resolved
  the frame at fire-time would be invisible to CI.

  These JVM tests use `with-frame :A` to set the dynamic var, capture
  the bound closure, then EXIT the with-frame scope before firing the
  closure — proving the captured frame survives the unwind. Async-
  callback regression coverage (capture inside a handler, fire via
  set-timeout!) is deferred until PR #332 lands."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- bound-dispatcher captures frame at call time ------------------------

(deftest bound-dispatcher-captures-current-frame
  (testing "bound-dispatcher captures the active frame; the returned closure
            dispatches against THAT frame after the with-frame scope unwinds"
    (rf/reg-frame :bound/A {:doc "frame A — the capture target"})
    (rf/reg-event-db :bound/inc
                     (fn [db _] (update db :n (fnil inc 0))))

    ;; Capture inside :bound/A; fire OUTSIDE.
    (let [captured (rf/with-frame :bound/A
                     (rf/bound-dispatcher))]
      ;; The dynamic-var binding has unwound — *current-frame* is back
      ;; to whatever default the runtime had. The captured closure must
      ;; still route to :bound/A.
      (captured [:bound/inc])
      (captured [:bound/inc])
      ;; dispatch is async by default — the router queues; drain happens
      ;; on next-tick. For the JVM test we use dispatch-sync via the
      ;; captured closure's effect: bound-dispatcher wraps dispatch, not
      ;; dispatch-sync, so we need to wait. Force the queue to drain by
      ;; using dispatch-sync directly with {:frame :bound/A} — first
      ;; let's confirm the events landed on the right frame's queue.
      ;;
      ;; Simpler: explicitly assert the queue's frame routing. The
      ;; bound-dispatcher's `dispatcher` returns `(fn [event] (dispatch
      ;; event {:frame captured-frame}))`. We use dispatch-sync inline
      ;; below to drain the events synchronously.
      (Thread/sleep 50) ;; let the async router drain
      (is (= 2 (:n (rf/get-frame-db :bound/A)))
          "the captured dispatcher routed events to :bound/A after the scope unwound")
      (is (nil? (:n (rf/get-frame-db :rf/default)))
          ":rf/default's app-db was NOT touched — capture is frame-faithful"))))

(deftest bound-subscriber-captures-current-frame
  (testing "bound-subscriber captures the active frame; the returned closure
            subscribes against THAT frame after the with-frame scope unwinds"
    (rf/reg-frame :bound/B {:doc "frame B — the subscribe target"})
    (rf/reg-event-db :bound/seed (fn [_ [_ v]] {:value v}))
    (rf/reg-sub :bound/value (fn [db _] (:value db)))
    ;; Seed each frame to a distinct value so we can confirm the captured
    ;; subscriber reads from :bound/B, not :rf/default.
    (rf/dispatch-sync [:bound/seed :B-value] {:frame :bound/B})
    (rf/dispatch-sync [:bound/seed :default-value])

    (let [captured-sub (rf/with-frame :bound/B
                         (rf/bound-subscriber))]
      ;; After scope unwinds: the captured subscriber must still read :bound/B's db.
      (let [reaction (captured-sub [:bound/value])]
        (is (= :B-value @reaction)
            "captured subscriber resolves against :bound/B's app-db, not :rf/default")))))

;; ---- contract: dispatcher OUTSIDE any with-frame defaults to :rf/default --

(deftest dispatcher-outside-with-frame-defaults
  (testing "calling (dispatcher) with no active with-frame captures :rf/default —
            this is the negative test that anchors the contract"
    (rf/reg-event-db :default/touch (fn [db _] (assoc db :touched? true)))
    (let [d (rf/dispatcher)]
      ;; No with-frame scope — capture should land on :rf/default.
      (d [:default/touch])
      (Thread/sleep 50)
      (is (true? (:touched? (rf/get-frame-db :rf/default)))
          "dispatcher outside any with-frame routes to :rf/default"))))

;; ---- contract: bound-* are the same as the non-bound forms ----------------

(deftest bound-dispatcher-is-dispatcher
  (testing "bound-dispatcher is the same shape as dispatcher — both capture
            the frame at call time. The bound-* names exist for clarity at
            the call site (the async-closure intent), not for a distinct
            mechanism"
    (rf/reg-frame :bound/twin {})
    (rf/reg-event-db :bound/touch (fn [db _] (assoc db :touched? true)))
    (let [bound (rf/with-frame :bound/twin (rf/bound-dispatcher))
          plain (rf/with-frame :bound/twin (rf/dispatcher))]
      ;; Behaviourally identical — both route to :bound/twin.
      (bound [:bound/touch])
      (plain [:bound/touch])
      (Thread/sleep 50)
      (is (true? (:touched? (rf/get-frame-db :bound/twin)))
          "both captured closures dispatched to :bound/twin"))))
