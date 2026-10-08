(ns re-frame.success-path-trigger-handler-test
  "`:rf.trace/trigger-handler` rides success-path trace events (Spec 009
  §Trace correlation): every trace emitted inside a handler's scope (event
  chain, fx body, sub recompute, cofx body) carries that handler's
  registration coord top-level as `{:kind :id :source-coord}`, so tools can
  jump to source from any event in a cascade.

  ## Posture split

  The slot rides the dev trace gate, so trace claims sit inside
  `(when rf.interop/debug-enabled? …)`. The COORD itself is always-on:
  `rf.registrar/register!` records it in the `error-coords-by-id` registry in
  both postures, so every case also asserts that registry (and the
  programmatic cases assert its absence beside a macro-path control)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.source-coords :as rf.source-coords]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- record-traces [body-fn]
  (let [seen (atom [])]
    (rf/register-listener! :trace ::rec (fn [ev] (swap! seen conj ev)))
    (try (body-fn)
         (finally (rf/unregister-listener! :trace ::rec)))
    @seen))

(defn- first-of [evs op]
  (first (filter #(= op (:operation %)) evs)))

(defn- assert-always-on-coord
  "The always-on registry holds a real coord for a macro-path registration."
  [kind id]
  (let [c (rf.source-coords/error-coords-for kind id)]
    (is (= [true true true] ((juxt (comp symbol? :ns) (comp string? :file) (comp integer? :line)) c))
        (str "always-on registration coord for " kind " " id))
    c))

(defn- assert-no-always-on-coord
  "A programmatic registration leaves the always-on registry empty; the
  macro-path control proves the negative is not free."
  [kind id control-id]
  (is (= [nil true] [(rf.source-coords/error-coords-for kind id)
                     (some? (rf.source-coords/error-coords-for kind control-id))])))

(defn- assert-trigger
  "The trace's trigger-handler names `kind`/`id` and carries exactly the
  registrar's coord, which equals the always-on registry's."
  [ev kind id]
  (let [ks   [:ns :file :line :column]
        meta (select-keys (rf/handler-meta {:source :store :kind kind :id id}) ks)
        errc (assert-always-on-coord kind id)
        t    (:rf.trace/trigger-handler ev)]
    (is (= (select-keys meta [:ns :file :line]) (select-keys errc [:ns :file :line])))
    (is (= {:kind kind :id id :source-coord meta}
           (update t :source-coord select-keys ks)))))

(deftest fx-handled-carries-fx-handler-trigger
  ;; the FX handler's coord, not the enclosing event's: jump-to-source lands
  ;; on the reg-fx site
  (let [ran (atom nil)]
    (rf/reg-fx :t/my-fx (fn [_ctx args] (reset! ran args) :ok))
    (rf/reg-event :t/uses-my-fx (fn [_ _] {:fx [[:t/my-fx {:k 1}]]}))
    (let [handled (first-of (record-traces #(rf/dispatch-sync [:t/uses-my-fx])) :rf.fx/handled)]
      (is (= {:k 1} @ran))
      (assert-always-on-coord :fx :t/my-fx)
      (when rf.interop/debug-enabled?
        (assert-trigger handled :fx :t/my-fx)
        (is (not (contains? (:tags handled) :rf.trace/trigger-handler))
            "rides at top level, NOT under :tags")))))

(deftest dispatch-fx-success-trigger-is-event-handler
  ;; the reserved :dispatch fx has no registration of its own, so its trace
  ;; carries the enclosing event handler's coord
  (rf/reg-event :t/parent (fn [_ _] {:fx [[:dispatch [:t/child]]]}))
  (rf/reg-event :t/child (fn [{:keys [db]} _] {:db (assoc db :child? true)}))
  (let [evs (record-traces #(rf/dispatch-sync [:t/parent]))]
    (assert-always-on-coord :event :t/parent)
    (is (true? (:child? (rf/app-db-value :rf/default))))
    (when rf.interop/debug-enabled?
      (assert-trigger (first (filter #(= :dispatch (get-in % [:tags :rf.fx/id])) evs))
                      :event :t/parent))))

(deftest fx-handled-omits-trigger-when-no-coord
  ;; a programmatic registration has no coord, so the field is omitted —
  ;; better no-data than poison-data
  ((requiring-resolve 're-frame.fx/reg-fx) :t/programmatic-fx (fn [_ _] :ok))
  ;; the event is programmatic too, so no outer coord can ride the emit
  ((requiring-resolve 're-frame.events/reg-event)
   :t/uses-prog-fx (fn [_ _] {:fx [[:t/programmatic-fx {}]]}))
  (rf/reg-fx :t/macro-fx (fn [_ _] :ok))
  (let [handled (first-of (record-traces #(rf/dispatch-sync [:t/uses-prog-fx])) :rf.fx/handled)]
    (assert-no-always-on-coord :fx :t/programmatic-fx :t/macro-fx)
    (when rf.interop/debug-enabled?
      (is (= [true false] [(some? handled) (contains? handled :rf.trace/trigger-handler)])))))

(deftest event-db-changed-and-do-fx-carry-event-handler-trigger
  (rf/reg-event :t/changes-db (fn [_ _] {:db {:n 1} :fx []}))
  (let [evs (record-traces #(rf/dispatch-sync [:t/changes-db]))]
    (is (= 1 (:n (rf/app-db-value :rf/default))))
    (assert-always-on-coord :event :t/changes-db)
    (when rf.interop/debug-enabled?
      (assert-trigger (first-of evs :rf.event/db-changed) :event :t/changes-db)
      (assert-trigger (first-of evs :rf.fx/do-fx) :event :t/changes-db))))

(deftest registration-traces-omit-trigger-handler
  ;; traces emitted outside any handler scope carry no trigger-handler
  (let [evs (record-traces #(rf/reg-event :t/reg-time-event (fn [{:keys [db]} _] {:db db})))]
    (assert-always-on-coord :event :t/reg-time-event)
    (when rf.interop/debug-enabled?
      (let [reg-traces (filter #(= :rf.registry/handler-registered (:operation %)) evs)]
        (is (seq reg-traces))
        (is (not-any? #(contains? % :rf.trace/trigger-handler) reg-traces))))))

(deftest sub-run-trigger-is-sub-not-enclosing-event
  ;; a sub recomputed inside an event handler still rebinds the scope, so
  ;; :rf.sub/run carries the SUB's coord, not the upstream event's
  (rf/reg-sub :t/from-cascade (fn [db _] (:n db)))
  (rf/reg-event :t/changes-n
    (fn [{:keys [db]} _]
      @(rf/subscribe [:t/from-cascade])
      {:db (assoc db :n 1)}))
  (let [evs (record-traces #(rf/dispatch-sync [:t/changes-n]))]
    (is (not= (assert-always-on-coord :sub :t/from-cascade)
              (assert-always-on-coord :event :t/changes-n))
        "distinct coords, so 'the inner scope wins' has teeth")
    (is (= 1 (:n (rf/app-db-value :rf/default))))
    (when rf.interop/debug-enabled?
      (assert-trigger (first-of evs :rf.sub/run) :sub :t/from-cascade))))

(deftest programmatic-sub-omits-trigger-on-run
  ((requiring-resolve 're-frame.subs/reg-sub) :t/programmatic (fn [db _] db))
  (rf/reg-sub :t/macro-sub (fn [db _] db))
  (let [run (first-of (record-traces #(deref (rf/subscribe [:t/programmatic]))) :rf.sub/run)]
    (assert-no-always-on-coord :sub :t/programmatic :t/macro-sub)
    (when rf.interop/debug-enabled?
      (is (= [true false] [(some? run) (contains? run :rf.trace/trigger-handler)])))))

;; The stock cofx surface emits no success trace of its own, so each cofx body
;; here emits a probe — what an instrumented cofx (http, persistence) does.

(deftest cofx-body-trace-carries-cofx-trigger
  (rf/reg-cofx :t/instrumented-cofx
    (fn [] (rf.trace/emit! :t/probe :t/probe {:from :cofx}) :ok))
  (rf/reg-event :t/uses-cofx {:rf.cofx/requires [:t/instrumented-cofx]} (fn [_ _] {}))
  (let [evs (record-traces #(rf/dispatch-sync [:t/uses-cofx]))]
    (assert-always-on-coord :cofx :t/instrumented-cofx)
    (when rf.interop/debug-enabled?
      (assert-trigger (first-of evs :t/probe) :cofx :t/instrumented-cofx))))

(deftest programmatic-cofx-omits-trigger-on-body-trace
  ((requiring-resolve 're-frame.cofx/reg-cofx)
   :t/prog-cofx (fn [] (rf.trace/emit! :t/probe :t/probe {}) :ok))
  (rf/reg-event :t/use-prog-cofx {:rf.cofx/requires [:t/prog-cofx]} (fn [_ _] {}))
  (rf/reg-cofx :t/macro-cofx (fn [] :ok))
  (let [probe (first-of (record-traces #(rf/dispatch-sync [:t/use-prog-cofx])) :t/probe)]
    (assert-no-always-on-coord :cofx :t/prog-cofx :t/macro-cofx)
    (when rf.interop/debug-enabled?
      (is (= [true false] [(some? probe) (contains? probe :rf.trace/trigger-handler)])))))
