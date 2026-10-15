(ns re-frame.ssr-error-two-frame-attribution-test
  "Errors stamp only their emitting server frame. Route-parameter validation
  runs in dev; handler exceptions project in both postures."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as rf.fx]
            [re-frame.interop :as rf.interop]
            [re-frame.schemas :as rf.schemas]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(defn- with-stub-validator
  "Install a validator that treats a fn schema as a predicate and passes any
  other schema (such as routing's own Malli vectors); returns the restore fn."
  []
  (let [snap (rf.schemas/schema-fns)]
    (rf.schemas/set-schema-fns!
      {:validate (fn [schema value] (if (fn? schema) (boolean (schema value)) true))
       :explain  (fn [schema value] (when (fn? schema) {:reason :stub-explainer :value value}))})
    (fn [] (rf.schemas/set-schema-fns! snap))))

(defn- register-routes-and-fx! []
  ;; A navigate whose `:id` fails the `:params` predicate is rejected with
  ;; `:rf.error/schema-validation-failure :where :event` → 400.
  (rf/reg-route :route/article
                {:params (fn [{:keys [id]}] (str/starts-with? (or id "") "a"))}
                "/articles/:id")
  (rf.fx/reg-fx :rf.nav/push-url {:platforms #{:server :client}} (fn [_ _url] nil)))

(defn- server-frame []
  (let [id (keyword "ssr" (str (gensym "request-")))]
    (rf/make-frame {:id id :platform :server
                    :ssr {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}})
    id))

(defn- status [frame-id] (:status (:response (rf.ssr/flush-response-result! frame-id))))

(deftest two-server-frames-navigate-reject-stamps-only-the-emitting-frame
  (when rf.interop/debug-enabled?
    (let [restore (with-stub-validator)]
      (try
        (register-routes-and-fx!)
        (doseq [[emitter expected] [[0 [400 200]] [1 [200 400]]]]
          (let [frames [(server-frame) (server-frame)]]
            (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}]
                              {:frame (nth frames emitter)})
            (is (= expected (mapv status frames)))))
        (finally (restore))))))

(deftest always-on-handler-exception-attributes-each-frame-independently
  (rf/reg-event :boom/throw (fn [_ _] (throw (ex-info "handler boom" {}))))
  (let [fa (server-frame)
        fb (server-frame)]
    (rf/dispatch-sync [:boom/throw] {:frame fa})
    (is (= [500 200] [(status fa) (status fb)]))
    (rf/dispatch-sync [:boom/throw] {:frame fb})
    (is (= 500 (status fb)) "and the mirror, so attribution is not first-frame-wins")))
