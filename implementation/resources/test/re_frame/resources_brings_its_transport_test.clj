(ns re-frame.resources-brings-its-transport-test
  "Requiring `re-frame.resources` is enough to read a resource over managed
  HTTP: the namespace requires `re-frame.http.managed`, so its one transport,
  `:rf.http/managed`, registers with it. This namespace requires no
  `re-frame.http.*` namespace of its own, and serves the read from a local
  server through the real JVM transport rather than a stub."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.resources :as rf.resources]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-server!
  "Answer every exchange `200 {\"title\":\"Intro\"}`."
  ^HttpServer []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                bytes (.getBytes "{\"title\":\"Intro\"}" "UTF-8")]
            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
            (.sendResponseHeaders ex 200 (long (count bytes)))
            (with-open [os (.getResponseBody ex)] (.write os bytes))))))
    (.start server)
    server))

(deftest a-resource-read-completes-with-only-re-frame-resources-required
  (let [server (start-server!)
        base   (str "http://127.0.0.1:" (.getPort (.getAddress server)))
        reply  (promise)]
    (try
      (rf/reg-resource :article/detail
        {:params-schema [:map [:slug :string]]
         :scope         :rf.scope/global}
        (fn [{:keys [slug]} _ctx]
          {:request {:method :get :url (str base "/api/articles/" slug)}
           :decode  :json}))
      (rf/reg-event :article/loaded (fn [_ [_ r]] (deliver reply r) {}))
      (rf/dispatch-sync [:rf.resource/ensure {:resource :article/detail
                                              :params   {:slug "intro"}
                                              :owner    [:app :article 1]
                                              :reply-to [:article/loaded]}])
      (let [r (deref reply 30000 ::timed-out)]
        (is (not= ::timed-out r) "the read's reply arrived")
        (is (= :ok (:status r)) "the read succeeded over the real transport")
        (is (= {:title "Intro"} (:value r)) "the decoded body is the reply's value"))
      (is (= {:title "Intro"}
             (:data (rf.resources/resource-state {:resource :article/detail
                                                  :scope    :rf.scope/global
                                                  :params   {:slug "intro"}
                                                  :frame    :rf/default})))
          "the response is cached on the resource's entry")
      (finally (.stop server 0)))))
