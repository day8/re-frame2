(ns re-frame.ssr-error-two-frame-attribution-test
  "Per-frame error attribution with TWO live server frames — the concurrent
  SSR shape, where one request's error must stamp its own response and no
  sibling's. An error routes by the frame its record names and never
  guesses; with one frame live a guess would succeed, so these tests keep two.

  The navigate-reject trigger is boundary validation, which is elided under
  `-Dre-frame.debug=false` (Spec 010 §Production builds), so those two tests
  sit in `debug-enabled?` arms. The throwing-handler test pins the same
  contract on the always-on path in both postures."
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

(defn- server-frame [frame-id]
  (rf/make-frame {:id frame-id :platform :server
                  :ssr {:public-error-id :rf.ssr/default-error-projector :dev-error-detail? false}})
  frame-id)

(defn- status [frame-id] (:status (rf.ssr/get-response frame-id)))

(deftest two-server-frames-navigate-reject-stamps-only-the-emitting-frame
  (when rf.interop/debug-enabled?
    (let [restore (with-stub-validator)]
      (try
        (register-routes-and-fx!)
        (let [fa (server-frame :ssr/req-a)
              fb (server-frame :ssr/req-b)]
          (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}] {:frame fa})
          (is (= [400 200] [(status fa) (status fb)])))
        (finally (restore))))))

(deftest two-server-frames-navigate-reject-attributes-each-frame-independently
  (when rf.interop/debug-enabled?
    (let [restore (with-stub-validator)]
      (try
        (register-routes-and-fx!)
        (let [fa (server-frame :ssr/req-a)
              fb (server-frame :ssr/req-b)]
          (rf/dispatch-sync [:rf.route/navigate {:to :route/article :params {:id "zoo"}}] {:frame fb})
          (is (= [200 400] [(status fa) (status fb)])))
        (finally (restore))))))

(deftest always-on-handler-exception-attributes-each-frame-independently
  (rf/reg-event :boom/throw (fn [_ _] (throw (ex-info "handler boom" {}))))
  (let [fa (server-frame :ssr/req-a)
        fb (server-frame :ssr/req-b)]
    (rf/dispatch-sync [:boom/throw] {:frame fa})
    (is (= [500 200] [(status fa) (status fb)]))
    (rf/dispatch-sync [:boom/throw] {:frame fb})
    (is (= 500 (status fb)) "and the mirror, so attribution is not first-frame-wins")))
