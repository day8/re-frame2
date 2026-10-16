(ns re-frame.ssr.ring.doc-example-hydration-replay-test
  "The JVM replay recipe in `docs/ssr/testing.md` §On the JVM: replay the
  payload. Its `app-element-html` helper is read out of the page and driven
  against a real `ssr-handler` response: a client frame hydrated from the
  shipped payload renders the markup the server put inside `<div id=\"app\">`,
  and a root view that reads state the payload leaves out makes the two
  strings differ — the failure the recipe exists to catch."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr.ring]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private testing-page
  "Anchored to this file's classpath resource (seven parents up is the repo root)."
  (let [res (io/resource "re_frame/ssr/ring/doc_example_hydration_replay_test.clj")]
    (assert res "the ssr-ring test/ dir must be on the classpath to find the testing page")
    (-> (io/file res)
        .getParentFile .getParentFile .getParentFile .getParentFile
        .getParentFile .getParentFile .getParentFile
        (io/file "docs" "ssr" "testing.md")
        .getCanonicalFile)))

(def ^:private app-element-html
  "The page's own `app-element-html`, read from its one ```clojure fence."
  (let [md    (str/replace (slurp testing-page) "\r\n" "\n")
        hits  (->> (re-seq #"(?s)```clojure\n(.*?)```" md)
                   (map second)
                   (filter #(str/includes? % "(defn app-element-html")))
        _     (assert (= 1 (count hits))
                      (str "expected EXACTLY ONE fence defining app-element-html in " testing-page))
        code  (first hits)
        form  (read-string (subs code (str/index-of code "(defn app-element-html")))
        [_ _ args & body] form]
    (eval `(fn ~args ~@body))))

(defn- read-payload [body]
  (some->> body
           (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>")
           second
           edn/read-string))

(defn- reg-app!
  "The server boot seeds a list and a server-only key; the payload ships only
  the list. `root` is the root view's render fn."
  [root]
  (rf/reg-event :replay/init {:platforms #{:server}}
    (fn [_ _] {:db {:articles ["Alpha" "Beta"] :server/only "kept on the server"}}))
  (rf/reg-sub :replay/articles (fn [db _] (:articles db)))
  (rf/reg-sub :replay/server-only (fn [db _] (:server/only db)))
  (rf/reg-view* :replay/root root))

(defn- served-body []
  (:body ((ssr.ring/ssr-handler {:initial-events [[:replay/init]]
                                 :root-view      (fn [] ((rf/view :replay/root)))
                                 :payload        [:articles]})
          {:request-method :get :uri "/"})))

(defn- replay
  "The recipe: hydrate a client frame `frame-id` from `body`'s payload and
  render the root against it -> `[payload hydrate!'s-return server-html client-html]`."
  [frame-id body]
  (let [payload (read-payload body)]
    (rf/make-frame {:id frame-id :platform :client})
    [payload
     (ssr/hydrate! {:frame frame-id :payload payload})
     (app-element-html body)
     (rf/with-frame frame-id (ssr/render-to-string [(rf/view :replay/root)]))]))

(deftest the-payload-rebuilds-the-page
  (reg-app! (fn [] [:ul (for [a (rf/subscribe-once [:replay/articles])] ^{:key a} [:li a])]))
  (let [[payload hydrated server-html client-html] (replay :replay/app (served-body))]
    (is (= payload hydrated) "hydrate! applied the shipped payload")
    (is (str/includes? server-html "<li>Alpha</li>") "the helper found the app element's markup")
    (is (= server-html client-html))))

(deftest a-root-reading-state-the-payload-leaves-out-fails-the-replay
  (reg-app! (fn [] [:p (rf/subscribe-once [:replay/server-only])]))
  (let [[_ _ server-html client-html] (replay :replay/app-control (served-body))]
    (is (str/includes? server-html "kept on the server"))
    (is (not= server-html client-html)
        "the client frame never received :server/only, so its render differs")))
