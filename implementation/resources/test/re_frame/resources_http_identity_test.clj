(ns re-frame.resources-http-identity-test
  "A resource read's managed-HTTP identity names its attempt, never its scoped
  key. The transport request id is `[:rf.req <frame-id> [:rf.work/resource
  <resource-id> <generation>]]`, so the HTTP failure map the entry keeps as
  `:error` and every `:rf.http/*` trace row hold neither the read's resolved
  scope nor its params — with no projector between them and the wire.

  Served from a local server through the real JVM transport, so the failure
  map and the trace rows are the ones production builds."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.resources :as rf.resources]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private scope-secret "tok-scope-identity-31f")
(def ^:private params-secret "tok-params-identity-31f")

(defn- start-server!
  "Answer every exchange `503 {\"error\":\"down\"}`."
  ^HttpServer []
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                bytes (.getBytes "{\"error\":\"down\"}" "UTF-8")]
            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
            (.sendResponseHeaders ex 503 (long (count bytes)))
            (with-open [os (.getResponseBody ex)] (.write os bytes))))))
    (.start server)
    server))

(defn- carries-identity?
  "Whether `x` holds either secret anywhere, or any scoped key of
  `:secret/article` — a `[scope :secret/article {params}]` vector."
  [x]
  (or (str/includes? (pr-str x) scope-secret)
      (str/includes? (pr-str x) params-secret)
      (boolean (some #(and (vector? %) (= 3 (count %))
                           (= :secret/article (nth % 1)) (map? (nth % 2)))
                     (tree-seq coll? seq x)))))

(deftest a-resource-reads-http-identity-carries-no-scoped-key
  (let [server (start-server!)
        base   (str "http://127.0.0.1:" (.getPort (.getAddress server)))
        reply  (promise)
        traces (atom [])
        lid    (keyword (str (gensym "http-identity-")))]
    (rf.trace.tooling/register-listener! lid (fn [ev] (swap! traces conj ev)))
    (try
      (rf/reg-event :t/sign-in (fn [{:keys [db]} _] {:db (assoc-in db [:auth :user] scope-secret)}))
      (rf/reg-resource-scope :t/session
        {:inputs {:user [:db [:auth :user]]}}
        (fn [{:keys [user]} _ctx] (when user [:rf.scope/session {:user user}])))
      (rf/reg-resource :secret/article
        {:params-schema [:map [:slug :string]]
         :scope         {:from-db :t/session}}
        (fn [_params _ctx]
          {:request {:method :get :url (str base "/api/article")}
           :decode  :json}))
      (rf/reg-event :t/replied (fn [_ [_ r]] (deliver reply r) {}))
      (rf/dispatch-sync [:t/sign-in])
      (rf/dispatch-sync [:rf.resource/ensure {:resource :secret/article
                                              :params   {:slug params-secret}
                                              :owner    [:app :article 1]
                                              :reply-to [:t/replied]}])
      (let [r     (deref reply 30000 ::timed-out)
            entry (rf.resources/resource-state
                    {:resource :secret/article
                     :scope    [:rf.scope/session {:user scope-secret}]
                     :params   {:slug params-secret}
                     :frame    :rf/default})
            err   (:error entry)
            rows  (fn [op] (filterv #(= op (:operation %)) @traces))]
        (is (not= ::timed-out r) "the read's failure reply arrived")
        (testing "the entry keeps the production HTTP failure map"
          (is (= [:error :rf.http/http-5xx 503] [(:status entry) (:kind err) (:http-status err)])))
        (testing "its request id names frame, resource and generation only"
          (is (= [:rf.req :rf/default :rf.work/resource :secret/article]
                 [(nth (:request-id err) 0) (nth (:request-id err) 1)
                  (get-in err [:request-id 2 0]) (get-in err [:request-id 2 1])]))
          (is (= [:rf.work/http 1 1] (:work/id err))
              "the HTTP work id is the frame's issuance number and the attempt, never the request id"))
        (testing "AC3 — neither the failure map's request id nor its work id holds the scope or params"
          (is (not (carries-identity? (:request-id err))))
          (is (not (carries-identity? (:work/id err)))))
        (testing "AC4 — no :rf.http/* row carries the scoped key, the scope or the params"
          (is (seq (rows :rf.http/issued)) "an issued row was emitted")
          (is (seq (rows :rf.http/replied)) "a completion row was emitted")
          (is (= [] (filterv #(carries-identity? (:tags %))
                             (filter #(some-> (:operation %) namespace (= "rf.http")) @traces)))))
        (testing "CONTROL — the probe finds the key where it does ride"
          (is (carries-identity? (:tags (first (rows :rf.resource/work-started)))))
          (is (carries-identity? (:resource/key r))
              "the app-facing continuation keeps its top-level :resource/key")))
      (finally
        (rf.trace.tooling/unregister-listener! lid)
        (.stop server 0)))))
