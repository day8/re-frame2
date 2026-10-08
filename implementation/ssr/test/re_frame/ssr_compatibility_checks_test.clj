(ns re-frame.ssr-compatibility-checks-test
  "`:rf.ssr/check-version` and `:rf.ssr/check-schema-digest` are the
  client-only, best-effort compatibility checks `:rf/hydrate` dispatches
  (Spec 011 §The :rf/hydrate event): a mismatch emits a warning trace and
  hydration proceeds. The trace is their only output and is elided under
  `-Dre-frame.debug=false`, so the trace assertions sit in dev arms; the
  registration gate and never-halting the drain hold in both postures."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- traces-of [traces op]
  (filterv #(= op (:operation %)) traces))

(defn- run-check!
  "Dispatch `fx` from a client frame; return every trace recorded."
  [fx]
  (rf/reg-event ::probe {:platforms #{:client}} (fn [_ _] {:fx [fx]}))
  (with-trace-recorder! [traces]
    (rf/dispatch-sync [::probe] {:frame (rf.frame/make-anon-frame-record! {:platform :client})})
    @traces))

(deftest compatibility-check-fxs-are-registered-client-only
  (is (= [#{:client} #{:client}]
         (map #(:platforms (rf.registrar/lookup :fx %))
              [:rf.ssr/check-version :rf.ssr/check-schema-digest]))))

(deftest compatibility-checks-are-best-effort-and-never-halt-the-drain
  (testing "a double mismatch commits the handler's :db and runs the effect
            queued after it"
    (let [ran (atom [])]
      (rf.fx/reg-fx ::after-checks
                 {:platforms #{:client}}
                 (fn [_ v] (swap! ran conj v)))
      (rf/reg-event ::probe-best-effort
        {:platforms #{:client}}
        (fn [{:keys [db]} _]
          {:db (assoc db :probe/handler-ran true)
           :fx [[:rf.ssr/check-version       {:expected 1 :actual 2}]
                [:rf.ssr/check-schema-digest {:expected "sha256:aaaa"
                                              :actual   "sha256:bbbb"}]
                [::after-checks :marker]]}))
      (let [f (rf.frame/make-anon-frame-record! {:platform :client})]
        (rf/dispatch-sync [::probe-best-effort] {:frame f})
        (is (= [true [:marker]]
               [(:probe/handler-ran (rf.frame/frame-app-db-value f)) @ran]))))))

(deftest matching-checks-are-silent
  (when rf.interop/debug-enabled?
    (doseq [[label fx mismatch-op]
            [["check-version" [:rf.ssr/check-version {:expected 1 :actual 1}]
              :rf.ssr/version-mismatch]
             ["check-schema-digest" [:rf.ssr/check-schema-digest {:expected "sha256:deadbeefcafef00d"
                                                                   :actual   "sha256:deadbeefcafef00d"}]
              :rf.ssr/schema-digest-mismatch]]]
      (is (empty? (filter #(#{mismatch-op :rf.ssr/compatibility-check-skipped} (:operation %))
                          (run-check! fx)))
          (str label " — no mismatch and no skipped trace")))))

(deftest mismatching-map-checks-emit-a-warning-trace
  (when rf.interop/debug-enabled?
    (doseq [[fx op expected actual]
            [[[:rf.ssr/check-version {:expected 1 :actual 2}]
              :rf.ssr/version-mismatch 1 2]
             [[:rf.ssr/check-schema-digest {:expected "sha256:deadbeefcafef00d"
                                            :actual   "sha256:0000000000000000"}]
              :rf.ssr/schema-digest-mismatch "sha256:deadbeefcafef00d" "sha256:0000000000000000"]]]
      (is (= [[:warning expected actual :warned-and-applied]]
             (mapv (juxt :op-type (comp :expected :tags) (comp :actual :tags) :recovery)
                   (traces-of (run-check! fx) op)))
          (str op " — one warning trace carrying both values")))))

(deftest check-version-scalar-differs-from-ssr-constant-emits-mismatch
  (testing "the scalar form's client-side :actual is the SSR artefact's own
            pattern-protocol constant, so it is never skipped"
    (when rf.interop/debug-enabled?
      (let [server-version (inc rf.ssr.payload-policy/pattern-protocol-version)
            traces         (run-check! [:rf.ssr/check-version server-version])]
        (is (= [[] [[server-version rf.ssr.payload-policy/pattern-protocol-version]]]
               [(traces-of traces :rf.ssr/compatibility-check-skipped)
                (mapv (juxt (comp :expected :tags) (comp :actual :tags))
                      (traces-of traces :rf.ssr/version-mismatch))]))))))

(deftest check-schema-digest-scalar-with-no-hook-emits-skipped
  (testing "scalar form with the :schemas/app-schemas-digest hook absent → skipped"
    (let [prior-hook (rf.late-bind/get-fn :schemas/app-schemas-digest)]
      (swap! rf.late-bind/hooks dissoc :schemas/app-schemas-digest)
      (try
        (let [traces (run-check! [:rf.ssr/check-schema-digest "sha256:deadbeefcafef00d"])]
          (when rf.interop/debug-enabled?
            (is (= [[:rf.ssr/check-schema-digest "sha256:deadbeefcafef00d" :skipped]]
                   (mapv (juxt (comp :check :tags) (comp :expected :tags) :recovery)
                         (traces-of traces :rf.ssr/compatibility-check-skipped))))))
        (finally
          (when prior-hook
            (rf.late-bind/set-fn! :schemas/app-schemas-digest prior-hook)))))))
