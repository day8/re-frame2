(ns re-frame.ssr-error-emit-promotion-test
  "The SSR error categories on the always-on `register-error-listener!` axis
  reach a listener under `interop/debug-enabled? = false`, and surfacing them
  there does not change the status the request answers with."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.boot :as rf.ssr.boot]
            [re-frame.ssr.error-listener :as rf.ssr.error-listener]
            [re-frame.ssr.error-projector :as rf.ssr.error-projector]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

;; Clearing the registry leaves this namespace's recorder alone on it, so a
;; test here sees only what its recorder sees. SSR's own capture rides a
;; late-bind hook, not the registry, and keeps projecting.
(use-fixtures :each
  (fn [t]
    (rf.ssr.test-fixture/reset-runtime
      (fn []
        (rf.error-emit/clear-error-listeners!)
        (t)))))

(defn- server-frame
  "The default projector maps any projected `:rf.error/*` to 500, so a 200
  means nothing was projected."
  ([] (server-frame :rf.ssr/default-error-projector))
  ([projector-id]
   (rf.frame/make-anon-frame-record!
     {:platform :server
      :ssr      {:public-error-id projector-id :dev-error-detail? false}})))

(defn- capture-records! []
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::recorder #(swap! seen conj %))
    seen))

(defn- records-of [seen category]
  (filterv #(= category (:error %)) @seen))

(deftest ssr-render-failed-reaches-listener-under-debug-off
  (let [f    (server-frame)
        seen (capture-records!)]
    (with-redefs [rf.interop/debug-enabled? false]
      (rf.ssr.error-listener/project-render-exception! f (ex-info "render boom" {}))
      (is (= 1 (count (records-of seen :rf.error/ssr-render-failed))))
      (is (= 500 (:status (:response (rf.ssr/flush-response-result! f))))
          "the direct projection's 500, not double-stamped or re-projected"))))

(deftest sanitised-on-projection-reaches-listener-under-debug-off
  (rf/reg-error-projector :test/throwing-projector
    {:doc "always throws"}
    (fn [_event] (throw (ex-info "projector boom" {}))))
  (let [f    (server-frame :test/throwing-projector)
        seen (capture-records!)]
    (with-redefs [rf.interop/debug-enabled? false]
      (rf.ssr.error-projector/project-error
        f {:op-type :error :operation :rf.error/ssr-render-failed :tags {:frame f}})
      (is (= 1 (count (records-of seen :rf.error/sanitised-on-projection)))
          "one-shot: the catch arm and the non-conforming-shape arm are exclusive"))))

(deftest malformed-hydration-payload-frameless-reaches-listener-under-debug-off
  (let [seen (capture-records!)]
    (with-redefs [rf.interop/debug-enabled? false]
      (rf.ssr.boot/dispatch-malformed-hydration-frameless!
        'rf.ssr/read-server-payload "__rf_payload" "did not parse as EDN"))
    (is (= [nil] (map :frame (records-of seen :rf.error/malformed-hydration-payload))))))

(deftest malformed-hydration-payload-frameful-reaches-listener-under-debug-off
  (testing "both fail-closed branches of the `:rf/hydrate` shape guard"
    (doseq [bad-payload [{:rf/app-db "slice-is-a-string"}
                         {:rf/app-db {:count 99} :rf/runtime-db [:runtime :is :a :vector]}]]
      (let [seen   (capture-records!)
            client (rf.frame/make-anon-frame-record! {:platform :client})]
        (with-redefs [rf.interop/debug-enabled? false]
          (rf/dispatch-sync [:rf/hydrate bad-payload] {:frame client}))
        (is (= [{:frame client :where 'rf.ssr/hydrate :failing-id :rf/hydrate
                 :recovery :no-recovery :reason? true}]
               (map #(-> (select-keys % [:frame :where :failing-id :recovery])
                         (assoc :reason? (string? (:reason %))))
                    (records-of seen :rf.error/malformed-hydration-payload)))
            (pr-str bad-payload))))))

(deftest non-projecting-categories-do-not-move-the-status-on-the-always-on-axis
  (testing "the always-on projection listener skips the fallback category;
            a projection-eligible category is the control"
    (doseq [[category status] [[:rf.error/sanitised-on-projection 200]
                               [:rf.error/ssr-render-failed 500]]]
      (let [f (server-frame)]
        (rf.ssr.error-listener/error-emit-projection-listener
          {:error category :frame f :time 0 :exception (ex-info "synthetic" {})})
        (is (= status (:status (:response (rf.ssr/flush-response-result! f)))) (str category))))))
