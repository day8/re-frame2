(ns re-frame.cascade-dispatch-id-test
  "Per rf2-g6ih4 — `:dispatch-id` is cascade-wide on every trace event.

  Spec 009 §Dispatch correlation locks `:dispatch-id` as a cascade-wide
  correlation key: it rides on **every** trace event emitted inside a
  dispatch's run-to-completion drain — `:event/dispatched`,
  `:event/db-changed`, `:rf.fx/handled`, `:sub/run`,
  `:rf.machine/transition`, `:rf.error/*`, every emit produced while
  processing the event. `:parent-dispatch-id` remains scoped to
  `:event/dispatched` only.

  This file exercises the cascade-wide stamping by dispatching a
  representative cascade and asserting:

  (a) every non-`:event/dispatched` trace event emitted while a drain
      is in flight carries `:tags :dispatch-id` matching the cascade
      that started the drain;
  (b) child dispatches issued from inside fx handlers get their OWN
      freshly-allocated `:dispatch-id` on their `:event/dispatched`
      event (the parent's id rides on `:parent-dispatch-id` instead);
  (c) `*current-dispatch-id*` is unbound across cascade boundaries —
      trace events emitted outside any drain (e.g. registration-time
      trace events, frame creation) carry no `:dispatch-id`.

  JVM-only — the dynamic-var binding mechanism is platform-agnostic."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-traces
  "Run `body-fn` with a trace listener attached and return the captured
  events."
  [body-fn]
  (let [seen (atom [])]
    (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf/remove-trace-cb! ::rec)))))

(defn- events-of [evs predicate]
  (filter predicate evs))

(defn- dispatch-id [ev] (get-in ev [:tags :dispatch-id]))

;; ---- cascade-wide stamping ------------------------------------------------

(deftest dispatch-id-rides-every-event-in-the-cascade
  (testing "every trace event emitted while a drain is in flight carries the cascade's :dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event-fx :seed
                     (fn [_ _]
                       {:db {:n 1}
                        :fx [[:test/incr :go]]}))
    (let [fx-fired (atom 0)]
      (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
      (let [evs (record-traces
                  (fn [] (rf/dispatch-sync [:seed] {:frame :test/main})))
            dispatched (first (events-of evs #(= :event/dispatched (:operation %))))
            cascade-id (dispatch-id dispatched)
            ;; Every event we expect inside the cascade.
            during-drain (->> evs
                              (filter #(contains? #{:event :event/db-changed
                                                    :event/do-fx :rf.fx/handled}
                                                  (:operation %))))]
        (is (some? cascade-id)
            "the cascade's :dispatch-id is on the :event/dispatched event")
        (is (seq during-drain)
            "we saw events emitted inside the drain")
        (doseq [ev during-drain]
          (is (= cascade-id (dispatch-id ev))
              (str "event " (:operation ev) " carries the cascade's :dispatch-id")))
        (is (= 1 @fx-fired) "fx ran")))))

(deftest dispatch-id-rides-on-error-events-inside-the-cascade
  (testing "errors emitted inside the drain carry the cascade's :dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :throws (fn [_ _] (throw (ex-info "oops" {}))))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:throws] {:frame :test/main})))
          dispatched (first (events-of evs #(= :event/dispatched (:operation %))))
          cascade-id (dispatch-id dispatched)
          err        (first (events-of evs #(= :rf.error/handler-exception (:operation %))))]
      (is (some? cascade-id))
      (is (some? err) "the handler-exception fired")
      (is (= cascade-id (dispatch-id err))
          ":rf.error/* traces carry the cascade's :dispatch-id"))))

(deftest child-dispatch-gets-its-own-dispatch-id-and-parents-the-outer
  (testing "child dispatches from inside fx handlers get a fresh :dispatch-id and the parent's id rides on :parent-dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event-fx :parent
                     (fn [_ _]
                       {:fx [[:dispatch [:child]]]}))
    (rf/reg-event-db :child (fn [db _] (assoc db :got-child true)))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:parent] {:frame :test/main})))
          dispatches (vec (events-of evs #(= :event/dispatched (:operation %))))
          parent     (first (filter #(= [:parent] (get-in % [:tags :event])) dispatches))
          child      (first (filter #(= [:child]  (get-in % [:tags :event])) dispatches))]
      (is (some? parent))
      (is (some? child))
      (is (some? (dispatch-id parent)))
      (is (some? (dispatch-id child)))
      (is (not= (dispatch-id parent) (dispatch-id child))
          "child gets its own freshly-allocated :dispatch-id")
      (is (= (dispatch-id parent)
             (get-in child [:tags :parent-dispatch-id]))
          "child's :parent-dispatch-id is the parent cascade's :dispatch-id"))))

(deftest parent-dispatch-id-only-on-event-dispatched
  (testing ":parent-dispatch-id is scoped to :event/dispatched events only — not on :sub/run, :event/db-changed, :rf.fx/handled, etc."
    (rf/reg-frame :test/main {})
    (rf/reg-event-fx :outer (fn [_ _] {:fx [[:dispatch [:inner]]]}))
    (rf/reg-event-db :inner (fn [db _] (assoc db :v 1)))
    (let [evs (record-traces
                (fn [] (rf/dispatch-sync [:outer] {:frame :test/main})))]
      (doseq [ev evs
              :when (not= :event/dispatched (:operation ev))]
        (is (nil? (get-in ev [:tags :parent-dispatch-id]))
            (str "non-:event/dispatched event " (:operation ev)
                 " must not carry :parent-dispatch-id"))))))

(deftest dispatch-id-unbound-outside-any-cascade
  (testing "trace events emitted outside any drain carry no :dispatch-id"
    ;; Register a frame and emit a handler-registered trace before any
    ;; dispatch fires — `*current-dispatch-id*` is unbound here, so the
    ;; trace event has no :dispatch-id stamped.
    (let [seen (atom [])]
      (rf/register-trace-cb! ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-frame :test/outside {})
        ;; reg-event-db / reg-fx emit :rf.registry/handler-registered traces
        ;; via the registrar; these fire OUTSIDE any drain.
        (rf/reg-event-db :foo (fn [db _] db))
        (let [out-of-band (filter #(or (= :frame/created (:operation %))
                                       (= :rf.registry/handler-registered (:operation %)))
                                  @seen)]
          (is (seq out-of-band) "we saw trace events emitted outside any drain")
          (doseq [ev out-of-band]
            (is (nil? (dispatch-id ev))
                (str "out-of-band event " (:operation ev)
                     " must NOT carry a :dispatch-id"))))
        (finally
          (rf/remove-trace-cb! ::rec))))))

(deftest dispatch-id-is-fresh-across-cascade-boundaries
  (testing "two sequential dispatches get distinct :dispatch-ids on every event in their respective cascades"
    (rf/reg-frame :test/main {})
    (rf/reg-event-db :bump (fn [db _] (update db :n (fnil inc 0))))
    (let [evs1 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          evs2 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          ids1 (set (keep dispatch-id evs1))
          ids2 (set (keep dispatch-id evs2))]
      (is (= 1 (count ids1))
          "every emit in the first cascade shares one :dispatch-id")
      (is (= 1 (count ids2))
          "every emit in the second cascade shares one :dispatch-id")
      (is (empty? (clojure.set/intersection ids1 ids2))
          "the two cascades' :dispatch-ids are disjoint"))))
