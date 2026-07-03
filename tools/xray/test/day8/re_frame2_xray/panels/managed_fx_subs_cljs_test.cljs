(ns day8.re-frame2-xray.panels.managed-fx-subs-cljs-test
  "Composite-sub test for `:rf.xray/managed-fx-for-focused-event` +
  the `:rf.xray/focus-event` cross-link event (rf2-uyp86).

  Uses the same test-runtime + seed-buffer pattern as
  `event_detail_cljs_test.cljs` — install Xray's handlers, allocate
  the `:rf/xray` frame, push trace events through the production
  `trace-collector/seed-trace-for-test!` path, then read the composite via
  `subscribe`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

;; ---- fixtures -----------------------------------------------------------

(defn- xray-init! []
  (xray-test-support/reset-all!)
  (trace-collector/reset-for-test!)
  (config/set-egress-profile! config/default-egress-profile)
  (config/reset-suppressed-count!))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn xray-init!}))

(defn- seed-buffer! [evs]
  (registry/register-xray-handlers!)
  (frame/reg-frame :rf/xray {})
  (doseq [ev evs]
    (trace-collector/seed-trace-for-test! ev)))

;; ---- trace fixture ------------------------------------------------------

(defn- cascade-evs-http
  "One cascade containing a single `:rf.http/managed` invocation. The
  fx args carry the standard Spec 014 shape so the helper can extract
  request / handler / correlation-id."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event    :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:user/load]}}
   {:id (+ id-base 2) :op-type :rf.fx    :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx       :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id
           :rf.fx/id :rf.http/managed
           :rf.fx/args {:request {:method :get :url "/api/users/42"}
                     :request-id :req-abc
                     :on-success [:user/loaded]}}}])

(defn- cascade-evs-non-managed
  "A cascade with only `:db` / `:dispatch` fxs — should produce zero
  managed-fx records."
  [dispatch-id id-base]
  [{:id (+ id-base 1) :op-type :rf.event :operation :rf.event/dispatched
    :tags {:rf.trace/dispatch-id dispatch-id :rf.event/v [:counter/inc]}}
   {:id (+ id-base 2) :op-type :rf.fx :operation :rf.fx/do-fx
    :tags {:rf.trace/dispatch-id dispatch-id}}
   {:id (+ id-base 3) :op-type :rf.fx    :operation :rf.fx/handled
    :tags {:rf.trace/dispatch-id dispatch-id :rf.fx/id :db}}])

;; ---- tests --------------------------------------------------------------

(deftest empty-when-no-focus
  (testing "with cascades in the buffer but no focused dispatch-id, the
            composite returns empty records (the spine snaps to head in
            LIVE mode but the cascade picked may have no managed-fx)"
    (seed-buffer! (cascade-evs-non-managed 100 0))
    (rf/with-frame :rf/xray
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= [] (:records out))
            "non-managed cascade yields empty records in LIVE-head mode")))))

(deftest projects-records-for-focused-cascade
  (testing "focused cascade with managed-fx → records populated"
    (seed-buffer! (cascade-evs-http 200 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 200 :rf/default])
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= 200 (:dispatch-id out)))
        (is (= 1 (count (:records out))))
        (is (= :http (-> out :records first :surface)))
        (is (= :rf.http/managed (-> out :records first :fx-id)))
        (is (= [:user/loaded] (-> out :records first :handler)))
        (is (= :req-abc (-> out :records first :correlation-id)))))))

(deftest empty-records-for-cascade-without-managed-fx
  (testing "focused cascade with no managed-fx → empty records"
    (seed-buffer! (cascade-evs-non-managed 300 0))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 300 :rf/default])
      (let [out @(rf/subscribe [:rf.xray/managed-fx-for-focused-event])]
        (is (= 300 (:dispatch-id out)))
        (is (= [] (:records out)))))))

(deftest focus-event-writes-spine-slot
  (testing ":rf.xray/focus-event dispatches through to the spine slot —
            this is the cross-link the HANDLER DISPATCHED row uses to
            pivot the spine to the handler's event. The row reuses the
            spine's canonical `:rf.xray/focus-event`, so focusing a PAST
            (non-head) event pins the spine to RETRO — head-aware, per
            spine semantics (rf2-fsqlgz collapsed the panel-local
            wrapper onto the spine event)."
    ;; Seed 400 then a LATER head event (500) so 400 is genuinely a
    ;; PAST event — focusing it must flip the spine to :retro.
    (seed-buffer! (concat (cascade-evs-http 400 0)
                          (cascade-evs-http 500 100)))
    (rf/with-frame :rf/xray
      (rf/dispatch-sync [:rf.xray/focus-event 400 :rf/default])
      (let [focus @(rf/subscribe [:rf.xray/focus])]
        (is (= 400 (:dispatch-id focus)))
        (is (= :rf/default (:frame focus)))
        (is (= :retro (:mode focus)))))))
