(ns re-frame.ssr.ring-draintime-error-view-test
  "The projected-error pre-commit contract at the handler boundary. A
  projected 5xx before the body commits ships the error page (`:error-view`
  or the locked default template) with no root render and no hydration
  payload; a projected 4xx, or an app-set status with no projection, stays on
  the app arm. A failing `:error-view` falls back ONCE to the template without
  re-projecting."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]
            [re-frame.test-support :refer [with-emit-recorder!]]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private request {:uri "/page" :request-method :get})

;; A throwing FX is caught, buffered and projected (500); a throwing EVENT
;; handler would escape the drain to `:on-error` instead.
(defn- reg-drain-boom-event! []
  (rf/reg-fx :test/boom-fx {:platforms #{:server}}
    (fn [_ _] (throw (ex-info "drain-boom-internal-detail" {}))))
  (rf/reg-event :init/boom {:platforms #{:server}}
    (fn [_ _] {:fx [[:test/boom-fx nil]]})))

(def ^:private root-calls (atom 0))

(defn- counting-root []
  (reset! root-calls 0)
  (rf/reg-view* :pages/counting-root
    (fn [] (swap! root-calls inc) [:main "ROOT-RENDERED-MARKER"]))
  [(rf/view :pages/counting-root)])

(deftest draintime-500-invokes-error-view-once-never-root
  (rf/reg-event :init/set-safe {:platforms #{:server}}
    (fn [_ _]
      {:fx [[:rf.server/set-header {:name "X-Safe" :value "safe-value"}]
            [:rf.server/set-cookie {:name "sid" :value "keep-me"}]]}))
  (reg-drain-boom-event!)
  (let [error-view-calls (atom 0)
        response         ((rf.ssr.ring/ssr-handler
                            {:initial-events [[:init/set-safe] [:init/boom]]
                             :root-view      (counting-root)
                             :error-view     (fn [_] (swap! error-view-calls inc)
                                               [:h1 "BRANDED-ERROR-MARKER"])
                             :payload        :rf.ssr.payload/whole-app-db})
                          request)
        body             (:body response)]
    (is (= 500 (:status response)))
    (is (= 1 @error-view-calls))
    (is (zero? @root-calls))
    (is (str/includes? body "BRANDED-ERROR-MARKER"))
    (is (not (re-find #"__rf_payload|drain-boom-internal-detail" body)))
    (testing "safe headers and cookies accumulated before the failure survive"
      (is (= "safe-value" (get-in response [:headers "X-Safe"])))
      (is (some #(str/includes? (str %) "sid=keep-me") (vals (:headers response)))))))

(deftest draintime-4xx-keeps-root-and-payload-never-error-view
  (rf/reg-event :init/route-to-missing {:platforms #{:server}}
    (fn [_ _] {:fx [[:dispatch [:rf.route/handle-url-change "/no-such-page"]]]}))
  (let [error-view-calls (atom 0)
        {:keys [status body]} ((rf.ssr.ring/ssr-handler
                                 {:initial-events [[:init/route-to-missing]]
                                  :root-view      [:div "APP-NOT-FOUND-UI"]
                                  :error-view     (fn [_] (swap! error-view-calls inc) [:div])
                                  :payload        :rf.ssr.payload/whole-app-db})
                               request)]
    (is (= 404 status))
    (is (zero? @error-view-calls))
    (is (str/includes? body "APP-NOT-FOUND-UI"))
    (is (str/includes? body "__rf_payload"))))

(deftest custom-503-error-arm-vs-appwritten-500-app-arm
  (testing "a custom projected 503 takes the error arm"
    (rf/reg-error-projector :myapp/degraded
      (fn [_] {:status 503 :code :unavailable :message "SERVICE-DEGRADED-MARKER" :retryable? true}))
    (reg-drain-boom-event!)
    (let [{:keys [status body]} ((rf.ssr.ring/ssr-handler
                                   {:initial-events [[:init/boom]]
                                    :root-view      (counting-root)
                                    :ssr            {:public-error-id :myapp/degraded}
                                    :payload        :rf.ssr.payload/whole-app-db})
                                 request)]
      (is (= 503 status))
      (is (zero? @root-calls))
      (is (str/includes? body "SERVICE-DEGRADED-MARKER"))
      (is (not (str/includes? body "__rf_payload")))))
  (testing "an app-written 500 with no projection stays on the app arm"
    (rf/reg-event :init/set-500 {:platforms #{:server}}
      (fn [_ _] {:fx [[:rf.server/set-status 500]]}))
    (let [{:keys [status body]} ((rf.ssr.ring/ssr-handler
                                   {:initial-events [[:init/set-500]]
                                    :root-view      (counting-root)
                                    :payload        :rf.ssr.payload/whole-app-db})
                                 request)]
      (is (= 500 status))
      (is (= 1 @root-calls))
      (is (str/includes? body "ROOT-RENDERED-MARKER"))
      (is (str/includes? body "__rf_payload")))))

(deftest a-failing-error-view-falls-back-once-no-reproject
  ;; Two containment arms: the error view throws, or a sub inside it recovers
  ;; to nil under production hardening (no throw for the try to see).
  (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/render-throws (fn [] (throw (ex-info "root-throw-detail" {}))))
  (rf/reg-sub :err/throwing (fn [_ _] (throw (ex-info "sub-in-error-view-boom" {}))))
  (let [calls (atom 0)]
    (rf/reg-view* :myapp/sub-error-view
      (fn [_] (swap! calls inc)
        [:span "DEGRADED-ERROR-VIEW-MARKER" @(rf/subscribe [:err/throwing])]))
    (doseq [[error-view leaked] [[(fn [_] (swap! calls inc) (throw (ex-info "error-view-broke-detail" {})))
                                  "error-view-broke-detail"]
                                 [:myapp/sub-error-view "DEGRADED-ERROR-VIEW-MARKER"]]]
      (reset! calls 0)
      (with-emit-recorder! [errs {:pred #(= :rf.error/ssr-ring-error-view-failed (:error %))}]
        (with-redefs [rf.interop/debug-enabled? false]
          (let [{:keys [status body]} ((rf.ssr.ring/ssr-handler
                                         {:initial-events [[:init/ok]]
                                          :root-view      [(rf/view :pages/render-throws)]
                                          :error-view     error-view
                                          :payload        :rf.ssr.payload/whole-app-db})
                                       request)]
            (is (= 500 status) leaked)
            (is (= 1 @calls) leaked)
            (is (str/includes? body "Something went wrong") leaked)
            (is (not (str/includes? body leaked)) leaked)
            (is (= 1 (count @errs)) leaked)))))))

(deftest redirect-beats-pending-projection
  (rf/reg-event :init/redirect-then-boom {:platforms #{:server}}
    (fn [_ _] {:fx [[:rf.server/redirect {:location "/login"}]
                    [:dispatch [:init/boom]]]}))
  (reg-drain-boom-event!)
  (let [response ((rf.ssr.ring/ssr-handler
                    {:initial-events [[:init/redirect-then-boom]]
                     :root-view      [:p "root"]
                     :payload        :rf.ssr.payload/whole-app-db})
                  request)]
    (is (= [302 "/login" ""]
           [(:status response) (get-in response [:headers "Location"]) (:body response)]))))

(deftest stream-draintime-500-non-streamed-error-arm
  (reg-drain-boom-event!)
  (let [{:keys [status body]} ((rf.ssr.ring/stream-handler
                                 {:initial-events [[:init/boom]]
                                  :root-view      [:p "root"]
                                  :error-view     (fn [_] [:div "STREAM-BRANDED-ERROR"])
                                  :payload        :rf.ssr.payload/whole-app-db})
                               request)]
    (is (= 500 status))
    ;; A plain String, not the streaming InputStream: no writer thread.
    (is (string? body))
    (is (str/includes? body "STREAM-BRANDED-ERROR"))
    (is (not (str/includes? body "__rf_payload")))))

(deftest stream-post-shell-recovered-sub-500-non-streamed-error-arm
  (rf/reg-event :init/ok {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-sub :shell/throwing (fn [_ _] (throw (ex-info "shell-sub-boom" {}))))
  (rf/reg-view* :pages/shell-uses-throwing-sub
    (fn [] [:main "DEGRADED-SHELL-MARKER" (str @(rf/subscribe [:shell/throwing]))]))
  (with-redefs [rf.interop/debug-enabled? false]
    (let [{:keys [status body]} ((rf.ssr.ring/stream-handler
                                   {:initial-events [[:init/ok]]
                                    :root-view      [(rf/view :pages/shell-uses-throwing-sub)]
                                    :payload        :rf.ssr.payload/whole-app-db})
                                 request)]
      (is (= 500 status))
      (is (string? body))
      (is (str/includes? body "Something went wrong"))
      (is (not (re-find #"DEGRADED-SHELL-MARKER|__rf_payload" body))))))
