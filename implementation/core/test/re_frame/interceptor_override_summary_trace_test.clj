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
    * The marks chokepoint (`re-frame.classification/project-trace-event`) re-asserts
      the id-only shape FAIL-CLOSED.

  Attach point is `:rf.event/run-start` (NOT `:rf.event/dispatched`, which
  fires at enqueue BEFORE override resolution) per the ruling's timing
  requirement. Dev-only / production-elision is pinned separately by the
  `re-frame.elision-probe` + `scripts/check-elision.cjs` gate (the run-start
  emit body — and the summary construction feeding it — DCE under :advanced).

  ## Posture split (rf2-d2841)

  The docstring above already says the summary is DEV-ONLY — the run-start emit
  body and the summary construction feeding it both DCE. So the six
  dispatch-driven cases failed under `scripts/test-core-prod-gate.sh` and their
  summary assertions are guarded.

  THE SUMMARY IS A REPORT ABOUT SOMETHING THAT IS NOT DEV-ONLY, and that is
  what was missing. `:interceptor-overrides` REMOVE and REPLACE entries on the
  resolved chain in every posture; the tag merely narrates it. Every case
  therefore grew an always-on witness that reads the chain's ACTUAL BEHAVIOUR
  — recording interceptors that append their own id as they run — so
  the claims that `::log-a` was removed and that `::log-x` was replaced by
  `::stub-x` are now
  proven where they matter, in the posture that ships. Under the gate that is
  first-ever coverage of override resolution; in dev it is a control that the
  guarded tag agrees with the chain it describes.

  The four `marks-projection-*` cases need no guard at all:
  `rf.classification/project-trace-event` is a pure fn over a SYNTHETIC event and
  is not gated on `rf.interop/debug-enabled?` — `marks-projection-redacts-non-ref-
  payload` proves it, since a no-op projection would fail it. They were already
  green under the gate for a real reason, which is the distinction this pass
  keeps having to make.

  TWO VACUOUS PASSES CAME OFF (rf2-d2841 class 4): `summary-absent-on-no-
  override-path` and `summary-absent-with-empty-override-map` each certify the
  override-free hot path with `(is (nil? (run-start-summary …)))` — nil because
  the trace ring is empty, not because the tag was omitted. Their always-on
  replacements assert the un-overridden chain ran INTACT, which the absence of
  a tag was standing in for."
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
            [re-frame.trace :as rf.trace]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`.
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

  rf2-d2841 — the one-emit assertion is a TRACE-SHAPE claim and is guarded;
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

;; ---- always-on chain witness (rf2-d2841) ----------------------------------
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
      ;; ALWAYS-ON (rf2-d2841): the un-overridden chain ran INTACT — which is
      ;; what "no override fired" means. The nil-tag assertion below was
      ;; class-4 vacuous under the gate: nil because the trace ring is empty,
      ;; not because the tag was omitted.
      (is (= [::log-a] @ran) "the authored chain ran unmodified")
      (when rf.interop/debug-enabled?
        (is (nil? summary)
            "override-free dispatch carries no :rf.interceptor/override-summary tag")))))

(deftest summary-absent-with-empty-override-map
  (testing "an empty :interceptor-overrides map fires nothing => tag absent"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run] {:interceptor-overrides {}})]
      ;; ALWAYS-ON (rf2-d2841): an EMPTY override map leaves the chain intact.
      ;; The nil-tag assertion was class-4 vacuous under the gate.
      (is (= [::log-a] @ran) "an empty override map left the chain unmodified")
      (when rf.interop/debug-enabled?
        (is (nil? summary)
            "an empty override map is the no-override path — tag omitted")))))

;; ---- removed / replaced classification ------------------------------------

(deftest summary-records-removed-override
  (testing "a {ref nil} override is reported under :removed (and :matched)"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (reg-recording-ic! ::log-b)
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a nil}})]
      ;; ALWAYS-ON (rf2-d2841): the removal HAPPENED — `::log-a` did not run,
      ;; `::log-b` did. That is the fact the guarded tag reports.
      (is (= [::log-b] @ran) "the removed interceptor did not run; its sibling did")
      (when rf.interop/debug-enabled?
        (is (some? summary) "tag present when an override fires")
        (is (= [::log-a] (:removed summary)) ":removed carries the removed ref id")
        (is (= [] (:replaced summary)) ":replaced empty — nothing was replaced")
        (is (= [::log-a] (:matched summary)) ":matched is the union")
        (is (= 1 (:count summary)) ":count is (count :matched)")))))

(deftest summary-records-replaced-override
  (testing "a {ref other-ref} override is reported under :replaced (and :matched)"
    (reset-ran!)
    (reg-recording-ic! ::log-x)
    (reg-recording-ic! ::stub-x)
    (rf/reg-event :sum/run
      {:interceptors [::log-x]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-x ::stub-x}})]
      ;; ALWAYS-ON (rf2-d2841): the SUBSTITUTION happened — the stub ran in the
      ;; replaced entry's slot and the original did not run at all.
      (is (= [::stub-x] @ran) "the replacement ran in place of the original")
      (when rf.interop/debug-enabled?
        (is (some? summary))
        (is (= [::log-x] (:replaced summary)) ":replaced carries the replaced ref id")
        (is (= [] (:removed summary)))
        (is (= [::log-x] (:matched summary)))
        (is (= 1 (:count summary)))))))

(deftest summary-records-mixed-removed-and-replaced
  (testing "a mix of removed + replaced overrides classifies each correctly"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (reg-recording-ic! ::log-b)
    (reg-recording-ic! ::stub-b)
    (rf/reg-event :sum/run
      {:interceptors [::log-a ::log-b]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a nil
                                                              ::log-b ::stub-b}})]
      ;; ALWAYS-ON (rf2-d2841): removal and substitution compose — only the
      ;; stub ran, in the position the replaced entry held.
      (is (= [::stub-b] @ran)
          "the removed entry is gone and the replaced entry ran as its stub")
      (when rf.interop/debug-enabled?
        (is (some? summary))
        (is (= [::log-a] (:removed summary)))
        (is (= [::log-b] (:replaced summary)))
        (is (= #{::log-a ::log-b} (set (:matched summary)))
            ":matched is the union of removed + replaced")
        (is (= 2 (:count summary)))))))

;; ---- unmatched override key does not count --------------------------------

(deftest unmatched-override-key-not-counted
  (testing "an override key that matches NO chain entry is not in the summary"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    ;; ::not-in-chain references nothing on the chain — it is the
    ;; override-fallthrough candidate, NOT something that took effect.
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::not-in-chain nil}})]
      ;; ALWAYS-ON (rf2-d2841): an unmatched override key leaves the chain
      ;; untouched — the production statement of ":count 0".
      (is (= [::log-a] @ran) "an unmatched override key changed nothing")
      (when rf.interop/debug-enabled?
        ;; The override map is non-empty (so the helper runs) but nothing
        ;; matched — :matched/:removed/:replaced are empty, :count 0.
        (is (some? summary) "tag present (override map non-empty)")
        (is (= [] (:matched summary)))
        (is (= [] (:removed summary)))
        (is (= [] (:replaced summary)))
        (is (= 0 (:count summary)))))))

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
        ;; ALWAYS-ON (rf2-d2841): a PER-FRAME override acts on the chain in
        ;; production too — the frame-scoped removal is not a dev affordance.
        (is (= [::log-b] @ran) "the per-frame override removed ::log-a from the chain")
        (when rf.interop/debug-enabled?
          (is (some? summary))
          (is (= [::log-a] (:removed summary)))
          (is (= 1 (:count summary))))))))

;; ---- value safety: no interceptor values / fns leak -----------------------

(deftest summary-egresses-ids-only-no-values
  (testing "the summary carries ONLY keyword ref ids — no fns, maps, or values"
    (reset-ran!)
    (reg-recording-ic! ::log-a)
    (reg-recording-ic! ::stub-a)
    (rf/reg-event :sum/run
      {:interceptors [::log-a]}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     {:interceptor-overrides {::log-a ::stub-a}})
          all-ids (concat (:matched summary) (:replaced summary) (:removed summary))]
      ;; ALWAYS-ON (rf2-d2841): the substitution under scrutiny really happened,
      ;; so the guarded value-safety claim below is about a real summary rather
      ;; than an empty one. `(seq all-ids)` over the empty concat the gate
      ;; yields would otherwise have gone red for a posture reason.
      (is (= [::stub-a] @ran) "the replacement ran in place of the original")
      (when rf.interop/debug-enabled?
        (is (seq all-ids))
        (doseq [id all-ids]
          (is (or (keyword? id)
                  (and (vector? id) (= 2 (count id)) (keyword? (first id))))
              (str "summary id is an interceptor reference, not a value: " (pr-str id)))
          ;; No executable interceptor map / fn ever appears.
          (is (not (map? id)) "no interceptor value map in the summary")
          (is (not (fn? id)) "no fn in the summary"))))))

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
          out   (rf.classification/project-trace-event ev)
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
          out  (rf.classification/project-trace-event ev)
          summ (-> out :tags :rf.interceptor/override-summary)]
      (is (= [:rf.interceptor/path] (:matched summ))
          "[id arg] reduced to head id")
      (is (= [:rf.interceptor/path] (:replaced summ)))
      ;; The {:secret "tok"} arg must be gone from the egressed shape.
      (is (not (some #(and (map? %) (contains? % :secret)) (:matched summ)))
          "no [id arg] arg payload egresses"))))

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
      (is (= [rf.privacy/redacted-sentinel] (:replaced summ)))
      (is (not-any? map? (:matched summ)) "no raw interceptor value egresses"))))

(deftest marks-projection-drops-malformed-non-map-summary
  (testing "a non-map summary payload is dropped entirely (fail closed)"
    (let [ev  {:operation :rf.event/run-start
               :op-type   :rf.event
               :tags      {:frame :rf/default
                           :rf.interceptor/override-summary [:not :a :map]}}
          out (rf.classification/project-trace-event ev)]
      (is (not (contains? (:tags out) :rf.interceptor/override-summary))
          "malformed non-map summary slot is removed"))))
