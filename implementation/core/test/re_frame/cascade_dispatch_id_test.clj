(ns re-frame.cascade-dispatch-id-test
  "Per rf2-g6ih4 — `:rf.trace/dispatch-id` is cascade-wide on every trace event.

  Spec 009 §Dispatch correlation locks `:rf.trace/dispatch-id` as a cascade-wide
  correlation key: it rides on **every** trace event emitted inside a
  dispatch's run-to-completion drain — `:rf.event/dispatched`,
  `:rf.event/db-changed`, `:rf.fx/handled`, `:rf.sub/run`,
  `:rf.machine/transition`, `:rf.error/*`, every emit produced while
  processing the event. `:rf.trace/parent-dispatch-id` remains scoped to
  `:rf.event/dispatched` only.

  This file exercises the cascade-wide stamping by dispatching a
  representative cascade and asserting:

  (a) every non-`:rf.event/dispatched` trace event emitted while a drain
      is in flight carries `:tags :rf.trace/dispatch-id` matching the cascade
      that started the drain;
  (b) child dispatches issued from inside fx handlers get their OWN
      freshly-allocated `:rf.trace/dispatch-id` on their `:rf.event/dispatched`
      event (the parent's id rides on `:rf.trace/parent-dispatch-id` instead);
  (c) `*current-dispatch-id*` is unbound across cascade boundaries —
      trace events emitted outside any drain (e.g. registration-time
      trace events, frame creation) carry no `:rf.trace/dispatch-id`.

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
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
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
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try
      (body-fn)
      @seen
      (finally
        (rf/unregister-listener! :trace ::rec)))))

(defn- events-of [evs predicate]
  (filter predicate evs))

(defn- dispatch-id [ev] (get-in ev [:tags :rf.trace/dispatch-id]))

;; ---- cascade-wide stamping ------------------------------------------------

(deftest dispatch-id-rides-every-event-in-the-cascade
  (testing "every trace event emitted while a drain is in flight carries the cascade's :rf.trace/dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event :seed
                     (fn [_ _]
                       {:db {:n 1}
                        :fx [[:test/incr :go]]}))
    (let [fx-fired (atom 0)]
      (rf/reg-fx :test/incr (fn [_ _] (swap! fx-fired inc)))
      (let [evs (record-traces
                  (fn [] (rf/dispatch-sync [:seed] {:frame :test/main})))
            dispatched (first (events-of evs #(= :rf.event/dispatched (:operation %))))
            cascade-id (dispatch-id dispatched)
            ;; Every event we expect inside the cascade.
            during-drain (->> evs
                              (filter #(contains? #{:event :rf.event/db-changed
                                                    :rf.fx/do-fx :rf.fx/handled}
                                                  (:operation %))))]
        (is (some? cascade-id)
            "the cascade's :rf.trace/dispatch-id is on the :rf.event/dispatched event")
        (is (seq during-drain)
            "we saw events emitted inside the drain")
        (doseq [ev during-drain]
          (is (= cascade-id (dispatch-id ev))
              (str "event " (:operation ev) " carries the cascade's :rf.trace/dispatch-id")))
        (is (= 1 @fx-fired) "fx ran")))))

(deftest dispatch-id-rides-on-error-events-inside-the-cascade
  (testing "errors emitted inside the drain carry the cascade's :rf.trace/dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event :throws (fn [{:keys [db]} _] {:db (throw (ex-info "oops" {}))}))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:throws] {:frame :test/main})))
          dispatched (first (events-of evs #(= :rf.event/dispatched (:operation %))))
          cascade-id (dispatch-id dispatched)
          err        (first (events-of evs #(= :rf.error/handler-exception (:operation %))))]
      (is (some? cascade-id))
      (is (some? err) "the handler-exception fired")
      (is (= cascade-id (dispatch-id err))
          ":rf.error/* traces carry the cascade's :rf.trace/dispatch-id"))))

(deftest child-dispatch-gets-its-own-dispatch-id-and-parents-the-outer
  (testing "child dispatches from inside fx handlers get a fresh :rf.trace/dispatch-id and the parent's id rides on :rf.trace/parent-dispatch-id"
    (rf/reg-frame :test/main {})
    (rf/reg-event :parent
                     (fn [_ _]
                       {:fx [[:dispatch [:child]]]}))
    (rf/reg-event :child (fn [{:keys [db]} _] {:db (assoc db :got-child true)}))
    (let [evs        (record-traces
                       (fn [] (rf/dispatch-sync [:parent] {:frame :test/main})))
          dispatches (vec (events-of evs #(= :rf.event/dispatched (:operation %))))
          parent     (first (filter #(= [:parent] (get-in % [:tags :rf.event/v])) dispatches))
          child      (first (filter #(= [:child]  (get-in % [:tags :rf.event/v])) dispatches))]
      (is (some? parent))
      (is (some? child))
      (is (some? (dispatch-id parent)))
      (is (some? (dispatch-id child)))
      (is (not= (dispatch-id parent) (dispatch-id child))
          "child gets its own freshly-allocated :rf.trace/dispatch-id")
      (is (= (dispatch-id parent)
             (get-in child [:tags :rf.trace/parent-dispatch-id]))
          "child's :rf.trace/parent-dispatch-id is the parent cascade's :rf.trace/dispatch-id"))))

(deftest parent-dispatch-id-only-on-event-dispatched
  (testing ":rf.trace/parent-dispatch-id is scoped to :rf.event/dispatched events only — not on :rf.sub/run, :rf.event/db-changed, :rf.fx/handled, etc."
    (rf/reg-frame :test/main {})
    (rf/reg-event :outer (fn [_ _] {:fx [[:dispatch [:inner]]]}))
    (rf/reg-event :inner (fn [{:keys [db]} _] {:db (assoc db :v 1)}))
    (let [evs (record-traces
                (fn [] (rf/dispatch-sync [:outer] {:frame :test/main})))]
      (doseq [ev evs
              :when (not= :rf.event/dispatched (:operation ev))]
        (is (nil? (get-in ev [:tags :rf.trace/parent-dispatch-id]))
            (str "non-:rf.event/dispatched event " (:operation ev)
                 " must not carry :rf.trace/parent-dispatch-id"))))))

(deftest dispatch-id-unbound-outside-any-cascade
  (testing "trace events emitted outside any drain carry no :rf.trace/dispatch-id"
    ;; Register a frame and emit a handler-registered trace before any
    ;; dispatch fires — `*current-dispatch-id*` is unbound here, so the
    ;; trace event has no :rf.trace/dispatch-id stamped.
    (let [seen (atom [])]
      (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
      (try
        (rf/reg-frame :test/outside {})
        ;; reg-event / reg-fx emit :rf.registry/handler-registered traces
        ;; via the registrar; these fire OUTSIDE any drain.
        (rf/reg-event :foo (fn [{:keys [db]} _] {:db db}))
        (let [out-of-band (filter #(or (= :rf.frame/created (:operation %))
                                       (= :rf.registry/handler-registered (:operation %)))
                                  @seen)]
          (is (seq out-of-band) "we saw trace events emitted outside any drain")
          (doseq [ev out-of-band]
            (is (nil? (dispatch-id ev))
                (str "out-of-band event " (:operation ev)
                     " must NOT carry a :rf.trace/dispatch-id"))))
        (finally
          (rf/unregister-listener! :trace ::rec))))))

(deftest dispatch-id-is-fresh-across-cascade-boundaries
  (testing "two sequential dispatches get distinct :dispatch-ids on every event in their respective cascades"
    (rf/reg-frame :test/main {})
    (rf/reg-event :bump (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [evs1 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          evs2 (record-traces
                 (fn [] (rf/dispatch-sync [:bump] {:frame :test/main})))
          ids1 (set (keep dispatch-id evs1))
          ids2 (set (keep dispatch-id evs2))]
      (is (= 1 (count ids1))
          "every emit in the first cascade shares one :rf.trace/dispatch-id")
      (is (= 1 (count ids2))
          "every emit in the second cascade shares one :rf.trace/dispatch-id")
      (is (empty? (clojure.set/intersection ids1 ids2))
          "the two cascades' :dispatch-ids are disjoint"))))
