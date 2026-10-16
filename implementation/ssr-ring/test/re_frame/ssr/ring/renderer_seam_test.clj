(ns re-frame.ssr.ring.renderer-seam-test
  "The `:renderer` render-body seam (Spec 011 §HTTP response contract). The
  renderer returns body markup; the JVM keeps the request frame, head,
  `__rf_payload`, shell, status, headers, error projection and teardown."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
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
  ((rf.ssr.ring/ssr-handler (merge {:initial-events [[:rf.test.seam/init]]
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
                            (merge {:initial-events [[:rf.test.seam/init]]
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
                                      :opts    (select-keys opts [:initial-events :payload])})
                        {:body-html "<p>seen</p>"})})
    (is (= {:keys    #{:frame-id :request :opts}
            :app-db  {:heading "Seam"}
            :scoped  "Seam"
            :request request
            :opts    {:initial-events [[:rf.test.seam/init]]
                      :payload        :rf.ssr.payload/whole-app-db}}
           @seen))))

(deftest the-body-is-never-rewritten
  (let [body (:body (serve {:renderer (fn [_] {:body-html "<div>bare</div>"})}))]
    (is (str/includes? body "<div id=\"app\"><div>bare</div></div>")
        "the pipeline wraps the renderer's bytes and adds nothing to them")))
