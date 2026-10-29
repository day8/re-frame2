(ns re-frame.ssr.ring.renderer-seam-test
  "The `:renderer` render-body seam (Spec 011 §HTTP response contract). The
  renderer returns body markup; the JVM keeps the request frame, head,
  `__rf_payload`, shell, status, headers, error projection and teardown."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(defn- payload-edn-of [body]
  (second (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)))

(def ^:private request
  {:uri "/seam" :request-method :get :headers {"x-seam-probe" "yes"}})

(def ^:private fixed-body
  "<main id=\"native\"><h1>Rendered elsewhere</h1></main>")

(defn- fixed-renderer [_]
  {:body-html fixed-body})

(defn- serve
  "Register the app and serve `request` through an `ssr-handler` built from `opts`."
  [opts]
  (rf/reg-event :rf.test.seam/init
    {:platforms #{:server}}
    (fn [_ _] {:db {:heading "Seam"}}))
  (rf/reg-sub :seam/heading (fn [db _] (:heading db)))
  (rf/reg-view* :seam/root
    (fn [] [:main.page [:h1 (rf/subscribe-once [:seam/heading])] [:p "jvm body"]]))
  ((rf.ssr.ring/ssr-handler (merge {:frame {:initial-events [[:rf.test.seam/init]]}
                                    :payload        :rf.ssr.payload/whole-app-db}
                                   opts))
   request))

(deftest a-custom-renderer-body-lands-verbatim-in-a-jvm-built-document
  ;; No `:root-view` is needed, and a supplied one is never read.
  (let [{:keys [status body]} (serve {:renderer fixed-renderer})]
    (is (= 200 status))
    (is (str/includes? body fixed-body))
    (is (str/includes? body "<div id=\"app\""))
    (is (str/includes? (payload-edn-of body) "\"Seam\"")
        "the payload is built from the post-drain app-db")
    (is (= body (:body (serve {:renderer  fixed-renderer
                               :root-view [(rf/view :seam/root)]}))))))

(deftest root-view-is-required-exactly-when-renderer-is-absent
  ;; An explicit-nil `:renderer` is absent: the default renderer reads `:root-view`.
  (doseq [opts [{} {:renderer nil}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/ssr-ring-missing-root-view"
                          (rf.ssr.ring/ssr-handler
                            (merge {:frame {:initial-events [[:rf.test.seam/init]]}
                                    :payload        :rf.ssr.payload/whole-app-db}
                                   opts))))))

(deftest the-renderer-sees-the-live-post-drain-frame-the-request-and-the-opts
  ;; It runs inside the request frame's scope, so an unqualified read resolves.
  (let [seen (atom nil)]
    (serve {:renderer (fn [{:keys [frame-id request opts] :as in}]
                        (reset! seen {:keys    (set (keys in))
                                      :app-db  (rf/app-db-value frame-id)
                                      :scoped  (rf/subscribe-once [:seam/heading])
                                      :request request
                                      :opts    (select-keys opts [:frame :payload])})
                        {:body-html "<p>seen</p>"})})
    (is (= {:keys    #{:frame-id :request :opts}
            :app-db  {:heading "Seam"}
            :scoped  "Seam"
            :request request
            :opts    {:frame   {:initial-events [[:rf.test.seam/init]]}
                      :payload :rf.ssr.payload/whole-app-db}}
           @seen))))

(deftest the-body-is-never-rewritten
  (let [body (:body (serve {:renderer (fn [_] {:body-html "<div>bare</div>"})}))]
    (is (str/includes? body "<div id=\"app\"><div>bare</div></div>")
        "the pipeline wraps the renderer's bytes and adds nothing to them")))

(defn- render-failures
  "Serve with `renderer`; the status, and `[id got-type]` of each projected
  render failure's exception."
  [renderer]
  (let [seen (atom [])]
    (rf.error-emit/register-error-listener! ::seam-recorder #(swap! seen conj %))
    (try
      (let [{:keys [status]} (serve {:renderer renderer})]
        [status
         (->> @seen
              (filter #(= :rf.error/ssr-render-failed (:rf.error/id %)))
              (mapv #(let [data (-> % :exception ex-data)]
                       [(:rf.error/id data) (get-in data [:got :type])])))])
      (finally (rf.error-emit/unregister-error-listener! ::seam-recorder)))))

(deftest a-renderer-result-that-is-not-body-html-is-projected-as-a-render-failure
  ;; Returning the markup bare is the natural slip against a fn-shaped seam,
  ;; and would otherwise ship a 200 around an empty app root.
  (is (= [200 []] (render-failures fixed-renderer)) "control: the documented shape")
  (doseq [[result got] [["<p>bare</p>" :string]
                        [{:html "<p>misnamed</p>"} :nil]
                        [nil :nil]]]
    (is (= [500 [[:rf.error/ssr-ring-renderer-result-invalid got]]]
           (render-failures (fn [_] result)))
        (pr-str result))))

(deftest fn-valued-opts-are-shape-checked-at-construction
  ;; Each would otherwise construct and then fail on every request, or ship
  ;; a 200 with an empty app root.
  (let [base    {:frame    {:initial-events [[:rf.test.seam/init]]}
                 :payload  :rf.ssr.payload/whole-app-db
                 :renderer fixed-renderer}
        refusal (fn [opts]
                  (try (rf.ssr.ring/ssr-handler opts) nil
                       (catch clojure.lang.ExceptionInfo e
                         ((juxt :rf.error/id :opt :recovery) (ex-data e)))))]
    (doseq [[k v recovery] [[:renderer :not-a-fn :supply-a-fn]
                            [:renderer "<p>not-a-fn</p>" :supply-a-fn]
                            [:html-shell "not-a-fn" :supply-a-fn]
                            [:on-error {:status 500} :supply-a-fn]
                            [:error-view "not-a-view" :supply-a-view-keyword-or-fn]]]
      (is (= [:rf.error/ssr-ring-fn-opt-invalid k recovery] (refusal (assoc base k v)))
          (pr-str k v)))
    (doseq [[k v] [[:renderer #'fixed-renderer]
                   [:html-shell nil]
                   [:on-error (fn [_ _] {:status 500})]
                   [:error-view :seam/registered-later]
                   [:error-view (fn [_] [:p "error"])]]]
      (is (nil? (refusal (assoc base k v))) (str "accepted: " (pr-str k v))))))
