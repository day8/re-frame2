(ns re-frame.flows-t2-trace-test
  "The framework stamps `:rf.event/db-pending-post-flow`
  (t2) when one or more flows transformed the pending `:db` between
  the handler's return and the deferred commit. The flows artefact is
  loaded here so the `:flows/run-flows-on-db` late-bind hook is wired
  in and the post-flow path actually runs.

  Contract — Spec 009 §Canonical per-event trace sequence + Spec 013
  §Drain integration:

    t2 fires INSIDE the outermost flows-after-interceptor `:after`,
    AFTER `run-flows-on-db` returns, when the new value is not
    `identical?` to the pre-flow value. Position in the trace stream:
    AFTER the last `:rf.flow/computed` emit and BEFORE
    `:rf.event/db-changed` (the deferred commit).

    No flow touched `:db` (value-equal skips across the board, or no
    registered flow read inputs the handler changed) — t2 is OMITTED:
    t1 == t2, no information.

  Same-shape-as-`:fx` posture (Mike 2026-05-25): the value lives under
  `:tags :rf.event/db` as the full persistent reference. PDS structural
  sharing keeps the cost pointer-sized; `day8/de-dupe` at the wire
  boundary collapses repeated subtrees on egress."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (flows/reset-last-inputs!)
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  ;; EP-0002: reg-flow is context-required frame-local — an ambient call
  ;; under no scope raises :rf.error/no-frame-context. Pin :rf/default (an
  ;; ordinary frame) as the established scope for the body.
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

;; ---- t2 fires when a flow transforms :db ---------------------------------

(deftest t2-emits-when-flow-changes-db
  (testing ":rf.event/db-pending-post-flow fires once when a registered flow
   `:after` transformed the pending :db value the handler returned"
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
    (rf/reg-event :t2/set-n (fn [{:keys [db]} _] {:db (assoc db :n 7)}))
    (let [acc (collect-traces! ::t2-emit)]
      (try
        (rf/dispatch-sync [:t2/set-n])
        (let [t2s (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @acc)]
          (is (= 1 (count t2s)) "exactly one t2 emit")
          (let [[t2] t2s
                stamped (-> t2 :tags :rf.event/db)]
            (is (= :rf.event (:op-type t2)) ":op-type rides the :rf.event family")
            (is (= 14 (:doubled stamped))
                "the post-flow stamp carries the flow's :output written to :path")
            (is (= 7 (:n stamped))
                "the post-flow stamp carries the handler's :n write too")
            (is (= :rf/default (-> t2 :tags :frame))
                ":tags :frame routes canonically")))
        (finally
          (rf/unregister-listener! :trace ::t2-emit))))))

(deftest t2-suppressed-when-flow-makes-no-change
  (testing "when a flow's value-equal skip leaves :db identical?-equal to
   the pre-flow value, t2 is OMITTED — t1 == t2 carries no information.
   First dispatch primes the flow's last-inputs; second dispatch with the
   same inputs hits the skip branch."
    (rf/reg-flow :doubled {:inputs [[:n]] :output-path [:doubled]} (fn [n] (* 2 n)))
    (rf/reg-event :t2/set-n (fn [{:keys [db]} _] {:db (assoc db :n 5)}))
    (rf/dispatch-sync [:t2/set-n])      ; prime
    (let [acc (collect-traces! ::t2-skip)]
      (try
        ;; second dispatch — same :n, so the flow's dirty-check finds inputs
        ;; equal and skips; the post-flow new-db is identical? to pending-db
        ;; (no assoc-in happened), so t2 is suppressed.
        (rf/dispatch-sync [:t2/set-n])
        (let [t2s (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @acc)]
          (is (zero? (count t2s))
              "no t2 emit when the flow skipped (pending-db is unchanged)"))
        (finally
          (rf/unregister-listener! :trace ::t2-skip))))))

;; ---- ordering against :rf.flow/computed and :rf.event/db-changed ---------

(deftest t2-sits-after-flow-computed-before-db-changed
  (testing "Spec 009 §Canonical per-event trace sequence — t2 fires AFTER
   the last :rf.flow/computed and BEFORE :rf.event/db-changed (the deferred
   commit). Mirrors the t1 contract — t1 leads the flow walk, t2 trails it."
    (rf/reg-flow :sum {:inputs [[:a] [:b]] :output-path [:sum]} +)
    (rf/reg-event :t2/seed (fn [{:keys [db]} _] {:db {:a 3 :b 4}}))
    (let [acc (collect-traces! ::t2-order)]
      (try
        (rf/dispatch-sync [:t2/seed])
        (let [ops (mapv :operation @acc)
              idx (fn [op] (first (keep-indexed (fn [i x] (when (= x op) i)) ops)))]
          (is (some? (idx :rf.event/db-pending))           "t1 emitted")
          (is (some? (idx :rf.flow/computed))              "flow emitted")
          (is (some? (idx :rf.event/db-pending-post-flow)) "t2 emitted")
          (is (some? (idx :rf.event/db-changed))           "commit emitted")
          (is (< (idx :rf.event/db-pending) (idx :rf.flow/computed))
              "t1 precedes the flow's :rf.flow/computed")
          (is (< (idx :rf.flow/computed) (idx :rf.event/db-pending-post-flow))
              ":rf.flow/computed precedes t2")
          (is (< (idx :rf.event/db-pending-post-flow) (idx :rf.event/db-changed))
              "t2 precedes the commit (atomicity contract — Spec 002)"))
        (finally
          (rf/unregister-listener! :trace ::t2-order))))))

;; ---- t1 and t2 carry distinct values reflecting the reshape -------------

(deftest t1-t2-pair-captures-reshape
  (testing "the (t1, t2) pair lets Xray render the t1→t2 reshape without
   a framework-precomputed diff: t1 carries the handler's :db, t2 carries
   the flow-augmented :db. Mike's ruling — full values, no diff."
    (rf/reg-flow :len {:inputs [[:items]] :output-path [:item-count]} (fn [items] (count items)))
    (rf/reg-event :t2/add-items (fn [{:keys [db]} _] {:db {:items [:a :b :c]}}))
    (let [acc (collect-traces! ::t1-t2-pair)]
      (try
        (rf/dispatch-sync [:t2/add-items])
        (let [[t1] (filterv #(= :rf.event/db-pending           (:operation %)) @acc)
              [t2] (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @acc)
              t1-db (-> t1 :tags :rf.event/db)
              t2-db (-> t2 :tags :rf.event/db)]
          (is (and (some? t1) (some? t2))
              "both t1 and t2 fired")
          (is (= {:items [:a :b :c]} t1-db)
              "t1 carries what the handler returned (no :item-count yet)")
          (is (= {:items [:a :b :c] :item-count 3} t2-db)
              "t2 carries the flow-augmented value (with :item-count written by :len)")
          (is (not (identical? t1-db t2-db))
              "t1 and t2 are distinct references — the reshape is observable"))
        (finally
          (rf/unregister-listener! :trace ::t1-t2-pair))))))

;; ---- flow-throw abort suppresses t2 --------------------------------------

(deftest t2-suppressed-when-flow-throws
  (testing "a flow throw aborts the event before commit (Spec 013 atomicity).
   No t2 emit: the partial-cascade :db was discarded. t1 stays — it
   recorded what the handler returned, before the throw."
    (rf/reg-flow :boom {:inputs [[:x]] :output-path [:boom]} (fn [_] (throw (ex-info "boom" {}))))
    (rf/reg-event :t2/trigger-boom (fn [{:keys [db]} _] {:db {:x 1}}))
    (let [acc (collect-traces! ::t2-throw)]
      (try
        (rf/dispatch-sync [:t2/trigger-boom])
        (let [t1s (filterv #(= :rf.event/db-pending           (:operation %)) @acc)
              t2s (filterv #(= :rf.event/db-pending-post-flow (:operation %)) @acc)]
          (is (= 1 (count t1s))
              "t1 still fired — the handler returned :db before the flow throw")
          (is (zero? (count t2s))
              "t2 did NOT fire — the cascade aborted, the pending :db was discarded"))
        (finally
          (rf/unregister-listener! :trace ::t2-throw))))))
