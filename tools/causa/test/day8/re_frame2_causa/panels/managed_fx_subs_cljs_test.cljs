(ns day8.re-frame2-causa.panels.managed-fx-subs-cljs-test
  "Composite-sub test for `:rf.causa/managed-fx-for-focused-event` +
  the `:rf.causa/focus-event` cross-link event (rf2-uyp86).

  Uses the same test-runtime + seed-buffer pattern as
  `event_detail_cljs_test.cljs` — install Causa's handlers, allocate
  the `:rf/causa` frame, push trace events through the production
  `trace-bus/collect-trace!` path, then read the composite via
  `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.config :as config]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!)
  (config/set-show-sensitive! false)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- seed-buffer! [evs]
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {})
  (doseq [ev evs]
    (trace-bus/collect-trace! ev)))

;; ---- trace fixture ------------------------------------------------------

(defn- cascade-evs-http
  "One cascade containing a single `:rf.http/managed` invocation. The
  fx args carry the standard Spec 014 shape so the helper can extract
  request / handler / correlation-id."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :event    :operation :event/dispatched
    :tags {:dispatch-id dispatch-id :event [:user/load]}}
   {:id (+ id-base 2) :op-type :event    :operation :event/do-fx
    :tags {:dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :fx       :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id
           :fx-id :rf.http/managed
           :fx-args {:request {:method :get :url "/api/users/42"}
                     :request-id :req-abc
                     :on-success [:user/loaded]}}}])

(defn- cascade-evs-non-managed
  "A cascade with only `:db` / `:dispatch` fxs — should produce zero
  managed-fx records."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :event :operation :event/dispatched
    :tags {:dispatch-id dispatch-id :event [:counter/inc]}}
   {:id (+ id-base 2) :op-type :event :operation :event/do-fx
    :tags {:dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :fx    :operation :rf.fx/handled
    :tags {:dispatch-id dispatch-id :fx-id :db}}])

;; ---- tests --------------------------------------------------------------

(deftest empty-when-no-focus
  (testing "with cascades in the buffer but no focused dispatch-id, the
            composite returns empty records (the spine snaps to head in
            LIVE mode but the cascade picked may have no managed-fx)"
    (seed-buffer! (cascade-evs-non-managed 100 0))
    (rf/with-frame :rf/causa
      (let [out @(rf/subscribe [:rf.causa/managed-fx-for-focused-event])]
        (is (= [] (:records out))
            "non-managed cascade yields empty records in LIVE-head mode")))))

(deftest projects-records-for-focused-cascade
  (testing "focused cascade with managed-fx → records populated"
    (seed-buffer! (cascade-evs-http 200 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-event 200 :rf/default])
      (let [out @(rf/subscribe [:rf.causa/managed-fx-for-focused-event])]
        (is (= 200 (:dispatch-id out)))
        (is (= 1 (count (:records out))))
        (is (= :http (-> out :records first :surface)))
        (is (= :rf.http/managed (-> out :records first :fx-id)))
        (is (= [:user/loaded] (-> out :records first :handler)))
        (is (= :req-abc (-> out :records first :correlation-id)))))))

(deftest empty-records-for-cascade-without-managed-fx
  (testing "focused cascade with no managed-fx → empty records"
    (seed-buffer! (cascade-evs-non-managed 300 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-event 300 :rf/default])
      (let [out @(rf/subscribe [:rf.causa/managed-fx-for-focused-event])]
        (is (= 300 (:dispatch-id out)))
        (is (= [] (:records out)))))))

(deftest focus-event-writes-spine-slot
  (testing ":rf.causa/focus-event dispatches through to the spine slot —
            this is the cross-link the HANDLER DISPATCHED row uses to
            pivot the spine to the handler's cascade"
    (seed-buffer! (cascade-evs-http 400 0))
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa/focus-event 400 :rf/default])
      (let [focus @(rf/subscribe [:rf.causa/focus])]
        (is (= 400 (:dispatch-id focus)))
        (is (= :rf/default (:frame focus)))
        (is (= :retro (:mode focus)))))))
