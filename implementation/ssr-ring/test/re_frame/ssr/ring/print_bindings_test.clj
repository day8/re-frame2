(ns re-frame.ssr.ring.print-bindings-test
  "The EDN the Ring host prints for another reader — the hydration payload,
  and the Node renderer's `args` and `state` — prints whole under an ambient
  `*print-length*` / `*print-level*`, which a REPL thread or a root binding
  sets. A truncated `[0 1 2 ...]` still reads, so a truncation would ship
  silently. No Node: the Node renderer's peer is an in-process JDK
  `HttpServer` that records what it is sent."
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr.ring :as rf.ssr.ring]
            [re-frame.ssr.ring.node :as rf.ssr.ring.node]
            [re-frame.ssr.ring.test-support :as rf.ssr.ring.test-support])
  (:import [java.net InetSocketAddress]
           [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]))

(use-fixtures :each rf.ssr.ring.test-support/reset-runtime)

(def ^:private app-db
  "Longer than `*print-length* 3` and deeper than `*print-level* 2`."
  {:items  (vec (range 10))
   :nested {:a {:b {:c 1}}}})

(def ^:private request {:uri "/print" :request-method :get :headers {}})

(defn- register-app! []
  (rf/reg-event :rf.test.print/init
    {:platforms #{:server}}
    (fn [_ _] {:db app-db}))
  (rf/reg-view* :print/root (fn [] [:main "page"])))

(defn- payload-app-db [{:keys [body]}]
  (-> (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>" body)
      second
      edn/read-string
      :rf/app-db))

(defmacro ^:private under-print-limits [& body]
  `(binding [*print-length* 3 *print-level* 2] ~@body))

(deftest the-hydration-payload-prints-whole-under-ambient-print-limits
  (register-app!)
  (let [h (rf.ssr.ring/ssr-handler {:frame     {:initial-events [[:rf.test.print/init]]}
                                    :root-view [(rf/view :print/root)]
                                    :payload   [:items :nested]})]
    (is (= app-db (payload-app-db (h request))) "control: no ambient binding")
    (is (= app-db (payload-app-db (under-print-limits (h request))))
        "the payload is printed whole whatever the calling thread binds")))

(defn- with-recording-peer
  "Run `(f url sent)` against a peer whose `/render` records each request
  body in the atom `sent` and answers 200 from `build-id`."
  [^String build-id f]
  (let [sent   (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        bytes  (.getBytes "<main>peer</main>" "UTF-8")]
    (.createContext server "/render"
                    (proxy [HttpHandler] []
                      (handle [^HttpExchange ex]
                        (swap! sent conj (slurp (.getRequestBody ex) :encoding "UTF-8"))
                        (.add (.getResponseHeaders ex) "x-rf-ssr-build" build-id)
                        (.sendResponseHeaders ex 200 (alength bytes))
                        (with-open [out (.getResponseBody ex)] (.write out bytes))
                        nil)))
    (.start server)
    (try
      (f (str "http://127.0.0.1:" (.getPort (.getAddress server))) sent)
      (finally (.stop server 0)))))

(defn- read-partition [wire]
  (into {} (map (fn [[k v]] [(edn/read-string k) (edn/read-string v)])) wire))

(deftest the-node-renderer-sends-args-and-state-whole-under-ambient-print-limits
  (register-app!)
  (with-recording-peer "print-build-1"
    (fn [url sent]
      (with-open [renderer (rf.ssr.ring.node/renderer {:endpoint     url
                                                       :entry        "app/root"
                                                       :build-id     "print-build-1"
                                                       :args         app-db
                                                       :render-state {:app-db [:items :nested]}})]
        (let [h (rf.ssr.ring/ssr-handler {:frame    {:initial-events [[:rf.test.print/init]]}
                                          :payload  []
                                          :renderer renderer})]
          (is (= 200 (:status (under-print-limits (h request)))))
          (let [{:strs [args state]} (json/read-str (peek @sent))]
            (is (= app-db (edn/read-string args)) "the root arguments")
            (is (= app-db (read-partition state)) "the app-db partition")))))))
