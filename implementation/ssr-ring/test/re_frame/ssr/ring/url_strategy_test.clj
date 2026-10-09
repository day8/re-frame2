(ns re-frame.ssr.ring.url-strategy-test
  "A handler-declared `:url-strategy` reaches the per-request frame, so
  `route-link` hrefs render as the hydrated client encodes them. It is
  threaded by presence: an explicit nil is a declaration and fails."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- serve
  "Serve `/` for an app whose only content is a `route-link`."
  [extra-opts]
  (rf/reg-route :route/active {} "/active")
  (rf/reg-event :init/url-strategy {:platforms #{:server}} (fn [_ _] {}))
  (rf/reg-view* :pages/linked
    (fn [] [:div.page [rf/route-link {:to :route/active} "Active"]]))
  ((rf.ssr.ring/ssr-handler (merge {:initial-events [[:init/url-strategy]]
                                    :root-view      [(rf/view :pages/linked)]
                                    :payload        :rf.ssr.payload/whole-app-db}
                                   extra-opts))
   {:uri "/" :request-method :get}))

(deftest ssr-handler-declared-url-strategy-reaches-the-request-frame
  ;; The default shell emits no other `href=`.
  (is (= "/realworld/active"
         (->> (serve {:url-strategy (rf.routing/with-base-path
                                      rf.routing/history-url-strategy "/realworld")})
              :body
              (re-find #"href=\"([^\"]+)\"")
              second))))

(deftest explicit-nil-url-strategy-is-a-declaration-and-fails
  (let [captured (atom nil)
        response (serve {:url-strategy nil
                         :on-error     (fn [_request t]
                                         (reset! captured t)
                                         {:status 599 :headers {} :body ""})})]
    (is (= 599 (:status response)))
    (is (= :rf.error/invalid-url-strategy (:rf.error/id (ex-data @captured))))))
