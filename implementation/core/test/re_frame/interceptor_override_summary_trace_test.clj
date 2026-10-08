(ns re-frame.interceptor-override-summary-trace-test
  "The dev-only `:rf.interceptor/override-summary` tag on `:rf.event/run-start`
  (Spec 009 §`:tags`): which authored refs the merged per-frame + per-call
  `:interceptor-overrides` removed or replaced, as ids and a count only.

  The overrides themselves act in every posture, so each dispatch case also
  reads the chain that actually ran (recording interceptors append their id);
  under the production gate that is the coverage of override resolution. The
  summary assertions sit in `rf.interop/debug-enabled?` arms. The marks
  projection is a pure fn over a synthetic event and runs in both postures."
  (:require [clojure.test :refer [deftest is use-fixtures]]
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
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces! [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- run-start-summary
  "Dispatch `event-v` (with `dispatch-opts` when non-nil) and return the
  `:rf.interceptor/override-summary` tag of its one `:rf.event/run-start`
  trace, or nil when the tag is absent."
  [event-v dispatch-opts]
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
        (rf/unregister-listener! :trace ::cap)))))

(def ^:private ran
  "Ids of the recording interceptors that ran, in order, for the last dispatch."
  (atom []))

(defn- reg-recording-ic!
  "Register `id` as an interceptor that appends `id` to [[ran]] when its
  `:before` fires."
  [id]
  (rf/reg-interceptor id {:before (fn [ctx] (swap! ran conj id) ctx)}))

(defn- reset-ran! [] (reset! ran []))

(deftest summary-classifies-each-override
  ;; `:matched` is compared as a set: the union of removed and replaced carries
  ;; no promised order. A key that matches no chain entry took no effect and
  ;; is not counted; with no overrides at all the tag is omitted.
  (doseq [[chain overrides expected-ran expected]
          [[[::log-a ::log-b] {::log-a nil} [::log-b]
            {:removed [::log-a] :replaced [] :matched #{::log-a} :count 1}]
           [[::log-x] {::log-x ::stub-x} [::stub-x]
            {:removed [] :replaced [::log-x] :matched #{::log-x} :count 1}]
           [[::log-a ::log-b] {::log-a nil ::log-b ::stub-b} [::stub-b]
            {:removed [::log-a] :replaced [::log-b] :matched #{::log-a ::log-b} :count 2}]
           [[::log-a] {::not-in-chain nil} [::log-a]
            {:removed [] :replaced [] :matched #{} :count 0}]
           [[::log-a] nil [::log-a]
            nil]]]
    (reset-ran!)
    (doseq [id [::log-a ::log-b ::log-x ::stub-x ::stub-b]]
      (reg-recording-ic! id))
    (rf/reg-event :sum/run
      {:interceptors chain}
      (fn [{:keys [db]} _] {:db db}))
    (let [summary (run-start-summary [:sum/run]
                                     (when overrides {:interceptor-overrides overrides}))]
      (is (= expected-ran @ran) (str "the chain that ran, for " (pr-str overrides)))
      (when rf.interop/debug-enabled?
        (is (= expected (some-> summary (update :matched set)))
            (str "the summary, for " (pr-str overrides)))))))

(deftest per-frame-override-surfaces-on-summary
  (reset-ran!)
  (reg-recording-ic! ::log-a)
  (reg-recording-ic! ::log-b)
  (rf/make-frame {:id :sum/framed :interceptor-overrides {::log-a nil}})
  (rf/reg-event :sum/run
    {:interceptors [::log-a ::log-b]}
    (fn [{:keys [db]} _] {:db db}))
  (rf/with-frame :sum/framed
    (let [summary (run-start-summary [:sum/run] {:frame :sum/framed})]
      (is (= [::log-b] @ran) "the per-frame override removed ::log-a")
      (when rf.interop/debug-enabled?
        (is (= {:removed [::log-a] :replaced [] :matched [::log-a] :count 1}
               summary))))))

(deftest marks-projection-redacts-non-ref-payload
  ;; The marks chokepoint re-asserts the id-only shape fail-closed: an
  ;; `[id arg]` ref keeps only its head id (the arg is not proven safe), a
  ;; value collapses to the redacted sentinel, and a non-map summary is dropped.
  (let [leak    (rf.interceptor/->interceptor* :id ::leak :before identity)
        project (fn [summary]
                  (-> {:operation :rf.event/run-start
                       :op-type   :rf.event
                       :tags      {:frame :rf/default
                                   :rf.interceptor/override-summary summary}}
                      rf.classification/project-trace-event
                      (get-in [:tags :rf.interceptor/override-summary] ::absent)))]
    (is (= {:matched [:rf.interceptor/path] :replaced [:rf.interceptor/path] :removed [] :count 1}
           (project {:matched  [[:rf.interceptor/path {:secret "tok"}]]
                     :replaced [[:rf.interceptor/path {:secret "tok"}]]
                     :removed  []
                     :count    1})))
    (is (= {:matched  [rf.privacy/redacted-sentinel]
            :replaced [rf.privacy/redacted-sentinel]
            :removed  []
            :count    1}
           (project {:matched [leak] :replaced [leak] :removed [] :count 1})))
    (is (= ::absent (project [:not :a :map])))))
