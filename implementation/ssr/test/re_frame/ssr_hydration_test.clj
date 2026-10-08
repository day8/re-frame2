(ns re-frame.ssr-hydration-test
  "The `:rf/hydrate` handler and the `hydrate!` boot helper on the JVM: which
  slice installs, which payload is refused, and that a refusal leaves BOTH
  partitions untouched.

  The fail-closed contract holds in every posture and is asserted without a
  guard. The diagnostic accompanying a refusal is a dev trace, elided under
  `-Dre-frame.debug=false`, so those assertions sit in
  `(when interop/debug-enabled? …)` arms."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.payload-policy :as rf.ssr.payload-policy]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.test-support :refer [with-trace-recorder!]]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- record-check-fx-dispatches!
  "Re-register the two `:rf.ssr/check-*` fxs with a recorder that delegates to
  the real handler, WITHOUT their `:platforms #{:client}` gate, so an enqueue on
  any platform is recorded rather than skipped. -> the atom of `[fx-id value]`."
  []
  (let [seen (atom [])]
    (doseq [id [:rf.ssr/check-version :rf.ssr/check-schema-digest]]
      (let [slot (rf.registrar/lookup :fx id)
            real (:handler-fn slot)]
        (rf.fx/reg-fx id
                   (select-keys slot [:schema])
                   (fn [frame-id v]
                     (swap! seen conj [id v])
                     (when real (real frame-id v))))))
    seen))

;; The `testbeds/ssr_basic` payload, minus its `:rf/frame-id` (the handler
;; fails closed on a frame-id naming another frame).
(def ^:private baseline-payload
  {:rf/version     1
   :rf/render-hash nil
   :rf/app-db      {:count 7 :title "seeded"}
   :rf/response    {:status   200
                    :headers  {"content-type" "text/html; charset=utf-8"}
                    :cookies  [{:name "session" :value "abc123"}]
                    :redirect nil}})

(defn- client-frame! []
  (rf.frame/make-anon-frame-record! {:platform :client}))

(defn- seeded-client-frame!
  "A `:client` frame holding recognisable state, so a refused hydrate that
  installed anything — even an empty map — shows."
  []
  (rf/reg-event ::seed (fn [_ _] {:db {:count 1}}))
  (let [frame (client-frame!)]
    (rf/dispatch-sync [::seed] {:frame frame})
    frame))

(defn- runtime-db [frame]
  (:rf.db/runtime (rf/frame-state-value frame)))

(deftest hydration-baseline-replaces-app-db-and-stashes-metadata
  (testing "the payload's :rf/app-db replaces app-db, and the version lands in the
            hydration metadata with the nil render-hash pruned"
    (let [frame (client-frame!)]
      (rf/dispatch-sync [:rf/hydrate baseline-payload] {:frame frame})
      (is (= {:count 7 :title "seeded"} (rf/app-db-value frame)))
      (is (= {:version 1} (get-in (runtime-db frame) [:rf.runtime/ssr :hydration]))))))

(deftest hydration-baseline-post-hydrate-dispatch-mutates-seeded-db
  (rf/reg-event ::inc (fn [{:keys [db]} _] {:db (update db :count inc)}))
  (let [frame (client-frame!)]
    (rf/dispatch-sync [:rf/hydrate baseline-payload] {:frame frame})
    (rf/dispatch-sync [::inc] {:frame frame})
    (is (= 8 (:count (rf/app-db-value frame))))))

(deftest hydration-baseline-rf-response-slice-round-trips-via-payload
  (testing "the testbed hoists :rf/response into app-db, where it lands intact"
    (let [frame (client-frame!)]
      (rf/dispatch-sync [:rf/hydrate (update baseline-payload :rf/app-db assoc
                                             :server-response (:rf/response baseline-payload))]
                        {:frame frame})
      (is (= (:rf/response baseline-payload)
             (:server-response (rf/app-db-value frame)))))))

(deftest hydration-baseline-version-matches-ssr-constant-silently
  (let [frame (client-frame!)]
    (with-trace-recorder! [traces]
      (rf/dispatch-sync [:rf/hydrate baseline-payload] {:frame frame})
      ;; Dev-instrumentation arm.
      (when rf.interop/debug-enabled?
        (is (not-any? #(= :rf.ssr/version-mismatch (:operation %)) @traces))))))

(def ^:private check-payload
  (assoc baseline-payload :rf/schema-digest "test-digest-abc"))

(deftest hydration-on-server-platform-skips-client-only-check-fxs
  (testing "a :server frame's hydrate enqueues neither :rf.ssr/check-* fx"
    (let [dispatched (record-check-fx-dispatches!)
          frame      (rf.frame/make-anon-frame-record! {:platform :server})]
      (rf/dispatch-sync [:rf/hydrate check-payload] {:frame frame})
      (is (= [] @dispatched)))))

(deftest hydration-on-client-platform-still-dispatches-check-fxs
  (testing "a :client frame's hydrate enqueues both, carrying the payload's values"
    (let [dispatched (record-check-fx-dispatches!)]
      (rf/dispatch-sync [:rf/hydrate check-payload] {:frame (client-frame!)})
      (is (= [[:rf.ssr/check-version 1]
              [:rf.ssr/check-schema-digest "test-digest-abc"]]
             @dispatched)))))

(deftest hydration-baseline-no-mismatch-trace-when-server-hash-nil
  (testing "a nil server hash short-circuits verify-hydration!: even
            :on-mismatch :hard-error has nothing to escalate"
    (let [frame (rf.frame/make-anon-frame-record!
                  {:platform :client
                   :ssr      {:on-mismatch :hard-error}})]
      (rf/dispatch-sync [:rf/hydrate baseline-payload] {:frame frame})
      (is (nil? (rf.ssr/verify-hydration! frame "abcdef01"))))))

(deftest malformed-hydration-payload-fails-closed-through-router
  (testing "a non-map payload, or a present-but-non-map partition slice, leaves
            the whole frame-state unchanged"
    (doseq [bad-payload [nil
                         {:rf/app-db [:slice :is :a :vector]}
                         {:rf/app-db {:count 99} :rf/runtime-db [:runtime :is :a :vector]}
                         ;; falsy but present: still refused
                         {:rf/app-db {:count 99} :rf/runtime-db false}]]
      (let [frame  (seeded-client-frame!)
            before (rf/frame-state-value frame)]
        (with-trace-recorder! [traces]
          (rf/dispatch-sync [:rf/hydrate bad-payload] {:frame frame})
          (is (= before (rf/frame-state-value frame)) (pr-str bad-payload))
          ;; Dev-instrumentation arm.
          (when rf.interop/debug-enabled?
            (is (some #(= :rf.error/malformed-hydration-payload (:operation %)) @traces)
                (pr-str bad-payload))))))))

(deftest wellformed-runtime-db-slice-still-installs-through-router
  (let [frame (client-frame!)]
    (rf/dispatch-sync [:rf/hydrate {:rf/app-db     {:count 7}
                                    :rf/runtime-db {:rf.runtime/routing {:current {:route-id :home}}}}]
                      {:frame frame})
    (is (= {:route-id :home} (get-in (runtime-db frame) [:rf.runtime/routing :current])))))

(deftest boot-hydrate-nil-payload-is-client-only-noop
  (is (nil? (rf.ssr/hydrate! {:frame (client-frame!) :payload nil}))))

(deftest boot-hydrate-absent-frame-raises-no-frame-context
  (let [ex (try (rf.ssr/hydrate! {:payload {:rf/app-db {:count 1}}})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))))

(deftest boot-hydrate-frame-id-mismatch-raises-structured-error
  (testing "a payload stamped for another frame throws rather than picking a side"
    (let [client-frame (client-frame!)
          other-frame  (rf.frame/make-anon-frame-record! {:platform :server})
          policy       {:version 1 :payload [:count]}
          payload      (rf.ssr.payload-policy/build-payload
                         other-frame
                         (rf.ssr.payload-policy/apply-policy {:count 7} policy)
                         "deadbeef"
                         policy)
          ex           (try (rf.ssr/hydrate! {:frame client-frame :payload payload})
                            nil
                            (catch clojure.lang.ExceptionInfo e e))]
      (is (= {:rf.error/id      :rf.error/hydration-frame-id-mismatch
              :target-frame     client-frame
              :payload-frame-id other-frame}
             (select-keys (ex-data ex) [:rf.error/id :target-frame :payload-frame-id]))))))

(deftest direct-dispatch-frame-id-mismatch-fails-closed
  (testing "a direct [:rf/hydrate payload] naming another frame installs nothing:
            the handler, not only hydrate!, refuses it"
    (let [frame  (seeded-client-frame!)
          before (rf/frame-state-value frame)]
      (with-trace-recorder! [traces]
        (rf/dispatch-sync [:rf/hydrate {:rf/frame-id    :some/other-frame
                                        :rf/app-db      {:count 42}
                                        :rf/runtime-db  {:rf.runtime/machines {:snapshots {:m :installed}}}
                                        :rf/render-hash "deadbeef"}]
                          {:frame frame})
        (is (= before (rf/frame-state-value frame)))
        ;; Dev-instrumentation arm.
        (when rf.interop/debug-enabled?
          (is (= [{:target-frame frame :payload-frame-id :some/other-frame}]
                 (for [ev @traces
                       :when (= :rf.error/hydration-frame-id-mismatch (:operation ev))]
                   (select-keys (:tags ev) [:target-frame :payload-frame-id])))))))))

(deftest mismatch-always-on-record-redacts-sensitive-payload-frame-id
  (testing "a frame that declares :payload-frame-id :sensitive gets it redacted on
            the always-on record, reason prose included; the dev trace keeps it raw"
    (rf/reg-event :rf.b5/classify (fn [_ _] {:sensitive [[:payload-frame-id]]}))
    (let [frame  (rf.frame/make-anon-frame-record!
                   {:platform       :client
                    :initial-events [[:rf.b5/classify]]})
          corpus (atom [])]
      (rf.error-emit/register-error-listener! ::b5-corpus
        (fn [record] (swap! corpus conj record)))
      (try
        (with-trace-recorder! [dev-traces]
          (rf/dispatch-sync [:rf/hydrate {:rf/frame-id    :secret/other-frame
                                          :rf/app-db      {:count 42}
                                          :rf/render-hash "deadbeef"}]
                            {:frame frame})
          (let [record    (first (filter #(= :rf.error/hydration-frame-id-mismatch (:error %))
                                         @corpus))
                dev-trace (first (filter #(= :rf.error/hydration-frame-id-mismatch (:operation %))
                                         @dev-traces))]
            (is (= :rf/redacted (:payload-frame-id record)))
            (is (not (re-find #"secret/other-frame" (pr-str record))))
            (when dev-trace
              (is (= :secret/other-frame (-> dev-trace :tags :payload-frame-id))))))
        (finally
          (rf.error-emit/unregister-error-listener! ::b5-corpus))))))
