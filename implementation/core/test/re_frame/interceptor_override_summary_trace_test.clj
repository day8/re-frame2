(ns re-frame.interceptor-override-summary-trace-test
  "Per rf2-9vx0jk (EP-0022 trace surfacing) — Spec 009 §`:tags` interceptor
  family. The router stamps a SINGLE dev-only `:rf.interceptor/override-summary`
  tag onto the `:rf.event/run-start` TRACE emit when this dispatch's merged
  per-frame + per-call `:interceptor-overrides` actually acted on the resolved
  interceptor chain.

  Contract:

    * Present ONLY when overrides fired; the override-free hot path omits the
      tag entirely (byte-identical run-start).
    * STRICTLY id/count-only: the value is
      `{:matched [<ref-id>…] :replaced [<ref-id>…] :removed [<ref-id>…]
        :count N}`, where each `<ref-id>` is an authored interceptor REFERENCE
      (a bare keyword id or `[id arg]` 2-vector head id). NO interceptor
      values, executable maps, fns, raw factory args, or raw replacement
      values ever egress.
    * The marks chokepoint (`re-frame.marks/project-trace-event`) re-asserts
      the id-only shape FAIL-CLOSED.

  Attach point is `:rf.event/run-start` (NOT `:rf.event/dispatched`, which
  fires at enqueue BEFORE override resolution) per the ruling's timing
  requirement. Dev-only / production-elision is pinned separately by the
  `re-frame.elision-probe` + `scripts/check-elision.cjs` gate (the run-start
  emit body — and the summary construction feeding it — DCE under :advanced)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interceptor :as interceptor]
            [re-frame.marks :as marks]
            [re-frame.privacy :as privacy]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- run-start-summary
  "Dispatch `event-v` (with optional `dispatch-opts`) and return the
  `:rf.interceptor/override-summary` tag from the single `:rf.event/run-start`
  trace event. nil when the tag is absent."
  ([event-v] (run-start-summary event-v nil))
  ([event-v dispatch-opts]
   (let [acc (collect-traces! ::cap)]
     (try
       (if dispatch-opts
         (rf/dispatch-sync event-v dispatch-opts)
         (rf/dispatch-sync event-v))
       (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)]
         (is (= 1 (count run-starts))
             "exactly one :rf.event/run-start emit per dispatch")
         (-> run-starts first :tags :rf.interceptor/override-summary))
       (finally
         (rf/unregister-listener! :trace ::cap))))))

(defn- reg-noop-ic! [id]
  (rf/reg-interceptor* id {:before (fn [ctx] ctx)}))

;; ---- absent when no override fires ----------------------------------------

(deftest summary-absent-on-no-override-path
  (testing "no :interceptor-overrides => tag is omitted entirely"
    (reg-noop-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (is (nil? (run-start-summary [:sum/run]))
        "override-free dispatch carries no :rf.interceptor/override-summary tag")))

(deftest summary-absent-with-empty-override-map
  (testing "an empty :interceptor-overrides map fires nothing => tag absent"
    (reg-noop-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (is (nil? (run-start-summary [:sum/run] {:interceptor-overrides {}}))
        "an empty override map is the no-override path — tag omitted")))

;; ---- removed / replaced classification ------------------------------------

(deftest summary-records-removed-override
  (testing "a {ref nil} override is reported under :removed (and :matched)"
    (reg-noop-ic! ::log-a)
    (reg-noop-ic! ::log-b)
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a nil}})]
      (is (some? summary) "tag present when an override fires")
      (is (= [::log-a] (:removed summary)) ":removed carries the removed ref id")
      (is (= [] (:replaced summary)) ":replaced empty — nothing was replaced")
      (is (= [::log-a] (:matched summary)) ":matched is the union")
      (is (= 1 (:count summary)) ":count is (count :matched)"))))

(deftest summary-records-replaced-override
  (testing "a {ref other-ref} override is reported under :replaced (and :matched)"
    (reg-noop-ic! ::log-x)
    (reg-noop-ic! ::stub-x)
    (rf/reg-event :sum/run
      {:interceptors [::log-x]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-x ::stub-x}})]
      (is (some? summary))
      (is (= [::log-x] (:replaced summary)) ":replaced carries the replaced ref id")
      (is (= [] (:removed summary)))
      (is (= [::log-x] (:matched summary)))
      (is (= 1 (:count summary))))))

(deftest summary-records-mixed-removed-and-replaced
  (testing "a mix of removed + replaced overrides classifies each correctly"
    (reg-noop-ic! ::log-a)
    (reg-noop-ic! ::log-b)
    (reg-noop-ic! ::stub-b)
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a nil
                                                              ::log-b ::stub-b}})]
      (is (some? summary))
      (is (= [::log-a] (:removed summary)))
      (is (= [::log-b] (:replaced summary)))
      (is (= #{::log-a ::log-b} (set (:matched summary)))
          ":matched is the union of removed + replaced")
      (is (= 2 (:count summary))))))

;; ---- unmatched override key does not count --------------------------------

(deftest unmatched-override-key-not-counted
  (testing "an override key that matches NO chain entry is not in the summary"
    (reg-noop-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    ;; ::not-in-chain references nothing on the chain — it is the
    ;; override-fallthrough candidate, NOT something that took effect.
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::not-in-chain nil}})]
      ;; The override map is non-empty (so the helper runs) but nothing
      ;; matched — :matched/:removed/:replaced are empty, :count 0.
      (is (some? summary) "tag present (override map non-empty)")
      (is (= [] (:matched summary)))
      (is (= [] (:removed summary)))
      (is (= [] (:replaced summary)))
      (is (= 0 (:count summary))))))

;; ---- per-frame overrides also surface -------------------------------------

(deftest per-frame-override-surfaces-on-summary
  (testing "a per-frame :interceptor-overrides also produces a run-start summary"
    (reg-noop-ic! ::log-a)
    (reg-noop-ic! ::log-b)
    (rf/reg-frame :sum/framed {:interceptor-overrides {::log-a nil}})
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/with-frame :sum/framed
      (let [summary (run-start-summary [:sum/run] {:frame :sum/framed})]
        (is (some? summary))
        (is (= [::log-a] (:removed summary)))
        (is (= 1 (:count summary)))))))

;; ---- value safety: no interceptor values / fns leak -----------------------

(deftest summary-egresses-ids-only-no-values
  (testing "the summary carries ONLY keyword ref ids — no fns, maps, or values"
    (reg-noop-ic! ::log-a)
    (reg-noop-ic! ::stub-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a ::stub-a}})
          all-ids (concat (:matched summary) (:replaced summary) (:removed summary))]
      (is (seq all-ids))
      (doseq [id all-ids]
        (is (or (keyword? id)
                (and (vector? id) (= 2 (count id)) (keyword? (first id))))
            (str "summary id is an interceptor reference, not a value: " (pr-str id)))
        ;; No executable interceptor map / fn ever appears.
        (is (not (map? id)) "no interceptor value map in the summary")
        (is (not (fn? id)) "no fn in the summary")))))

;; ---- marks chokepoint fail-closed -----------------------------------------

(deftest marks-projection-keeps-id-only-shape
  (testing "project-trace-event passes through a clean id-only summary unchanged"
    (let [ev    {:operation :rf.event/run-start
                 :op-type   :rf.event
                 :tags      {:frame :rf/default
                             :rf.interceptor/override-summary
                             {:matched  [::log-a]
                              :replaced []
                              :removed  [::log-a]
                              :count    1}}}
          out   (marks/project-trace-event ev)
          summ  (-> out :tags :rf.interceptor/override-summary)]
      (is (= [::log-a] (:matched summ)))
      (is (= [::log-a] (:removed summ)))
      (is (= 1 (:count summ))))))

(deftest marks-projection-reduces-param-ref-to-head-id
  (testing "an [id arg] ref is reduced to its head id (arg dropped — not proven safe)"
    (let [ev   {:operation :rf.event/run-start
                :op-type   :rf.event
                :tags      {:frame :rf/default
                            :rf.interceptor/override-summary
                            {:matched  [[:rf.interceptor/path {:secret "tok"}]]
                             :replaced [[:rf.interceptor/path {:secret "tok"}]]
                             :removed  []
                             :count    1}}}
          out  (marks/project-trace-event ev)
          summ (-> out :tags :rf.interceptor/override-summary)]
      (is (= [:rf.interceptor/path] (:matched summ))
          "[id arg] reduced to head id")
      (is (= [:rf.interceptor/path] (:replaced summ)))
      ;; The {:secret "tok"} arg must be gone from the egressed shape.
      (is (not (some #(and (map? %) (contains? % :secret)) (:matched summ)))
          "no [id arg] arg payload egresses"))))

(deftest marks-projection-redacts-non-ref-payload
  (testing "a non-ref payload (a refactor regression smuggling a value) FAILS CLOSED to :rf/redacted"
    (let [leak (interceptor/->interceptor* :id ::leak :before identity)
          ev   {:operation :rf.event/run-start
                :op-type   :rf.event
                :tags      {:frame :rf/default
                            :rf.interceptor/override-summary
                            {:matched  [leak]
                             :replaced [leak]
                             :removed  []
                             :count    1}}}
          out  (marks/project-trace-event ev)
          summ (-> out :tags :rf.interceptor/override-summary)]
      (is (= [privacy/redacted-sentinel] (:matched summ))
          "an interceptor VALUE map collapses to the redacted sentinel")
      (is (= [privacy/redacted-sentinel] (:replaced summ)))
      (is (not-any? map? (:matched summ)) "no raw interceptor value egresses"))))

(deftest marks-projection-drops-malformed-non-map-summary
  (testing "a non-map summary payload is dropped entirely (fail closed)"
    (let [ev  {:operation :rf.event/run-start
               :op-type   :rf.event
               :tags      {:frame :rf/default
                           :rf.interceptor/override-summary [:not :a :map]}}
          out (marks/project-trace-event ev)]
      (is (not (contains? (:tags out) :rf.interceptor/override-summary))
          "malformed non-map summary slot is removed"))))
