(ns re-frame.interceptor-override-summary-trace-test
  "EP-0022 trace surfacing — Spec 009 §`:tags` interceptor
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
    * The marks chokepoint (`re-frame.classification/project-trace-event`) re-asserts
      the id-only shape FAIL-CLOSED.

  Attach point is `:rf.event/run-start` (NOT `:rf.event/dispatched`, which
  fires at enqueue BEFORE override resolution). Dev-only / production-elision
  is pinned separately by the
  `re-frame.elision-probe` + `scripts/check-elision.cjs` gate (the run-start
  emit body — and the summary construction feeding it — DCE under :advanced).

  ## Posture split

  The summary is DEV-ONLY — the run-start emit body and the summary
  construction feeding it both DCE — so the dispatch-driven cases guard
  their summary assertions for `scripts/test-core-prod-gate.sh`.

  THE SUMMARY IS A REPORT ABOUT SOMETHING THAT IS NOT DEV-ONLY.
  `:interceptor-overrides` REMOVE and REPLACE entries on the
  resolved chain in every posture; the tag merely narrates it. Every case
  therefore carries an always-on witness that reads the chain's ACTUAL BEHAVIOUR
  — recording interceptors that append their own id as they run — so
  the claims that `::log-a` was removed and that `::log-x` was replaced by
  `::stub-x` are
  proven where they matter, in the posture that ships. Under the gate that is
  the coverage of override resolution; in dev it is a control that the
  guarded tag agrees with the chain it describes.

  The three `marks-projection-*` cases need no guard at all:
  `rf.classification/project-trace-event` is a pure fn over a SYNTHETIC event and
  is not gated on `rf.interop/debug-enabled?` — `marks-projection-redacts-non-ref-
  payload` proves it, since a no-op projection would fail it. They are green
  under the gate for a real reason.

  `summary-absent-on-no-override-path` certifies the override-free hot path
  with `(is (nil? (run-start-summary …)))`, which is VACUOUS under the gate —
  nil because the trace ring is empty, not because the tag was omitted — so
  that assertion is guarded, and the case also asserts always-on that the
  un-overridden chain ran INTACT, which the absence of a tag stands in for.
  An empty `:interceptor-overrides` map is the same input: the dispatch
  envelope defaults the slot to `{}`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.frame :as rf.frame]
            [re-frame.interceptor :as rf.interceptor]
            [re-frame.classification :as rf.classification]
            [re-frame.privacy :as rf.privacy]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; `init!` does not synthesise `:rf/default`, so register it here.
  (rf/make-frame {:id :rf/default})
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
  trace event. nil when the tag is absent.

  The one-emit assertion is a TRACE-SHAPE claim and is guarded;
  under `-Dre-frame.debug=false` there are no run-start emits at all, so
  asserting `(= 1 (count run-starts))` there would fail for a posture reason
  rather than a defect."
  ([event-v] (run-start-summary event-v nil))
  ([event-v dispatch-opts]
   (let [acc (collect-traces! ::cap)]
     (try
       (if dispatch-opts
         (rf/dispatch-sync event-v dispatch-opts)
         (rf/dispatch-sync event-v))
       (let [run-starts (filterv #(= :rf.event/run-start (:operation %)) @acc)]
         (when rf.interop/debug-enabled?
           (is (= 1 (count run-starts))
               "exactly one :rf.event/run-start emit per dispatch"))
         (-> run-starts first :tags :rf.interceptor/override-summary))
       (finally
         (rf/unregister-listener! :trace ::cap))))))

;; ---- always-on chain witness ----------------------------------------------
;;
;; The summary REPORTS an override; the override itself is production
;; behaviour. A recording interceptor appends its own id as it runs, so the
;; chain that actually executed is readable in both postures.

(def ^:private ran
  "Ids of the recording interceptors that ran, in order, for the last dispatch."
  (atom []))

(defn- reg-recording-ic!
  "Register `id` as an interceptor that appends `id` to [[ran]] when its
  `:before` fires."
  [id]
  (rf/reg-interceptor id {:before (fn [ctx] (swap! ran conj id) ctx)}))

(defn- reset-ran! [] (reset! ran []))

(defn- reg-noop-ic! [id]
  (rf/reg-interceptor id {:before (fn [ctx] ctx)}))

;; ---- absent when no override fires ----------------------------------------

(deftest summary-absent-on-no-override-path
  (testing "no :interceptor-overrides => tag is omitted entirely"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run])]
      ;; ALWAYS-ON: the un-overridden chain ran INTACT — which is
      ;; what "no override fired" means. The nil-tag assertion below is
      ;; vacuous under the gate: nil because the trace ring is empty,
      ;; not because the tag was omitted.
      (is (= [::log-a] @ran) "the authored chain ran unmodified")
      (when rf.interop/debug-enabled?
        (is (nil? summary)
            "override-free dispatch carries no :rf.interceptor/override-summary tag")))))

;; ---- removed / replaced / unmatched classification -------------------------

(deftest summary-classifies-each-override
  (testing "each override is reported under :removed or :replaced, :matched is
            their union and :count its size; a key matching NO chain entry is
            the override-fallthrough candidate, not something that took effect,
            so it is not counted"
    (doseq [[label chain overrides expected-ran expected]
            [["a {ref nil} override is reported under :removed (and :matched)"
              [::log-a ::log-b] {::log-a nil} [::log-b]
              {:removed [::log-a] :replaced [] :matched [::log-a] :count 1}]
             ["a {ref other-ref} override is reported under :replaced (and :matched)"
              [::log-x] {::log-x ::stub-x} [::stub-x]
              {:removed [] :replaced [::log-x] :matched [::log-x] :count 1}]
             ;; removal and substitution compose — only the stub runs, in the
             ;; position the replaced entry held. `:matched` is compared as a
             ;; set: the union carries no promised order across the two kinds.
             ["a mix of removed + replaced overrides classifies each correctly"
              [::log-a ::log-b] {::log-a nil ::log-b ::stub-b} [::stub-b]
              {:removed [::log-a] :replaced [::log-b] :matched #{::log-a ::log-b} :count 2}]
             ;; The override map is non-empty (so the summary is built) but
             ;; nothing matched.
             ["an override key that matches NO chain entry is not counted"
              [::log-a] {::not-in-chain nil} [::log-a]
              {:removed [] :replaced [] :matched [] :count 0}]]]
      (testing label
        (reset-ran!)
        (doseq [id [::log-a ::log-b ::log-x ::stub-x ::stub-b]]
          (reg-recording-ic! id))
        (rf/reg-event :sum/run
          {:interceptors chain}
          (fn [{:keys [db]} _] {:db db}))
        (let [summary (run-start-summary [:sum/run] {:interceptor-overrides overrides})]
          ;; ALWAYS-ON: the override HAPPENED (or, unmatched, changed nothing)
          ;; — the chain that actually ran is the fact the guarded tag reports.
          (is (= expected-ran @ran) "the chain that ran reflects the override")
          (when rf.interop/debug-enabled?
            (is (= (:removed expected) (:removed summary)) ":removed carries the removed ref ids")
            (is (= (:replaced expected) (:replaced summary)) ":replaced carries the replaced ref ids")
            (if (set? (:matched expected))
              (is (= (:matched expected) (set (:matched summary))) ":matched is the union")
              (is (= (:matched expected) (:matched summary)) ":matched is the union"))
            (is (= (:count expected) (:count summary)) ":count is (count :matched)")))))))

;; ---- per-frame overrides also surface -------------------------------------

(deftest per-frame-override-surfaces-on-summary
  (testing "a per-frame :interceptor-overrides also produces a run-start summary"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (reg-recording-ic! ::log-b)
    (rf/make-frame {:id :sum/framed :interceptor-overrides {::log-a nil}})
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (rf/with-frame :sum/framed
      (let [summary (run-start-summary [:sum/run] {:frame :sum/framed})]
        ;; ALWAYS-ON: a PER-FRAME override acts on the chain in
        ;; production too — the frame-scoped removal is not a dev affordance.
        (is (= [::log-b] @ran) "the per-frame override removed ::log-a from the chain")
        (when rf.interop/debug-enabled?
          (is (= [::log-a] (:removed summary)))
          (is (= 1 (:count summary))))))))

;; ---- marks chokepoint fail-closed -----------------------------------------
;;
;; `summary-classifies-each-override`'s exact equality on `:removed`,
;; `:replaced` and `:matched` already pins that only ref ids egress from a real
;; dispatch; the cases below pin the chokepoint that fails closed if a value
;; ever slips into the summary.

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
          out  (rf.classification/project-trace-event ev)
          summ (-> out :tags :rf.interceptor/override-summary)]
      (is (= [:rf.interceptor/path] (:matched summ))
          "[id arg] reduced to head id")
      (is (= [:rf.interceptor/path] (:replaced summ))))))

(deftest marks-projection-redacts-non-ref-payload
  (testing "a non-ref payload (a refactor regression smuggling a value) FAILS CLOSED to :rf/redacted"
    (let [leak (rf.interceptor/->interceptor* :id ::leak :before identity)
          ev   {:operation :rf.event/run-start
                :op-type   :rf.event
                :tags      {:frame :rf/default
                            :rf.interceptor/override-summary
                            {:matched  [leak]
                             :replaced [leak]
                             :removed  []
                             :count    1}}}
          out  (rf.classification/project-trace-event ev)
          summ (-> out :tags :rf.interceptor/override-summary)]
      (is (= [rf.privacy/redacted-sentinel] (:matched summ))
          "an interceptor VALUE map collapses to the redacted sentinel")
      (is (= [rf.privacy/redacted-sentinel] (:replaced summ))))))

(deftest marks-projection-drops-malformed-non-map-summary
  (testing "a non-map summary payload is dropped entirely (fail closed)"
    (let [ev  {:operation :rf.event/run-start
               :op-type   :rf.event
               :tags      {:frame :rf/default
                           :rf.interceptor/override-summary [:not :a :map]}}
          out (rf.classification/project-trace-event ev)]
      (is (not (contains? (:tags out) :rf.interceptor/override-summary))
          "malformed non-map summary slot is removed"))))
