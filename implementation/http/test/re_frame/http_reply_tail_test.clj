(ns re-frame.http-reply-tail-test
  "Two coupled reply-tail contracts on the JVM:

  - the reply-target shape (vector or nil) and the exclusivity of `:reply-to`
    with the `:on-success` / `:on-failure` sugar are refused at DISPATCH time,
    before the request is issued;
  - a throw in the reply tail after the transport succeeded is fenced: it
    surfaces once as `:rf.error/http-reply-tail-failed`, is never retried as a
    transport failure, and (on the JVM) never vanishes into the unobserved
    `whenComplete` future. The CLJS retry-storm half is
    `re-frame.http-reply-tail-cljs-test`.

  A hit-counting loopback server makes a re-send observable."
  (:require [clojure.string :as str]
            [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.http.handlers :as rf.http.handlers]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.test-support :as rf.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-counting-200-server!
  "A server that 200s a JSON body and counts every request reaching the wire."
  [^AtomicInteger hits]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.setHandler (.createContext server "/")
                 (reify HttpHandler
                   (handle [_ ex]
                     (.incrementAndGet hits)
                     (let [bytes (.getBytes "{\"ok\":true}" "UTF-8")]
                       (-> ^HttpExchange ex .getResponseHeaders (.set "Content-Type" "application/json"))
                       (.sendResponseHeaders ^HttpExchange ex 200 (long (count bytes)))
                       (with-open [os (.getResponseBody ^HttpExchange ex)] (.write os bytes))))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop ^HttpServer server 0))

(defn- with-trace-capture [body-fn]
  (let [captured (atom [])
        cb-id    (gensym "reply-tail-cap-")]
    (try
      (rf.trace.tooling/register-listener! cb-id (fn [ev] (swap! captured conj ev)))
      (body-fn captured)
      (finally
        (rf.trace.tooling/unregister-listener! cb-id)))))

(defn- ops [captured op]
  (filter #(= op (:operation %)) @captured))

(defn- await-op! [captured op]
  (rf.test-support/poll-until #(seq (ops captured op)) {:timeout-ms 5000 :label (str op)}))

(defn- refusal [args]
  (try (rf.http.handlers/validate-reply-target! args) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest bvw9ut-shape-validated-at-dispatch-time-unit
  ;; The map row is the input that separates `vector?` from `coll?`.
  (are [args k] (= {:rf.error/id :rf.error/http-bad-reply-target :key k}
                   (select-keys (refusal args) [:rf.error/id :key]))
    {:on-success :items/loaded}   :on-success
    {:reply-to {:not :a-vector}}  :reply-to))

(deftest mixed-reply-addressing-refused-at-dispatch
  ;; Refusal is on key PRESENCE, so an explicit nil branch is a mixture too.
  (are [args ks] (= {:rf.error/id :rf.error/http-bad-reply-target :reason :mixed-addressing :keys ks}
                    (select-keys (refusal args) [:rf.error/id :reason :keys]))
    {:reply-to nil :on-success [:b]}                    [:reply-to :on-success]
    {:reply-to [:a] :on-success [:b] :on-failure nil}   [:reply-to :on-success :on-failure])
  (are [args] (nil? (refusal args))
    {:reply-to nil}
    {:on-success [:a] :on-failure nil}))

(deftest bvw9ut-bare-keyword-on-success-rejected-before-network
  (let [hits (AtomicInteger. 0)
        {:keys [port] :as srv} (start-counting-200-server! hits)]
    (try
      (with-trace-capture
        (fn [captured]
          (rf/reg-event :bvw9ut/load
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/x")}
                      :decode     :json
                      :on-success :items/loaded}]]}))
          (try (rf/dispatch-sync [:bvw9ut/load]) (catch Throwable _ nil))
          (is (zero? (.get hits)) "the request was refused before it reached the wire")
          (is (some (fn [{:keys [operation tags]}]
                      (or (= :rf.error/http-bad-reply-target operation)
                          (= :rf.error/http-bad-reply-target (:rf.error/id tags))
                          (= :rf.error/http-bad-reply-target (:rf.error/id (:exception tags)))
                          (= :rf.error/fx-handler-exception operation)))
                    @captured)
              "the dispatch-time refusal surfaced")))
      (finally (stop-server! srv)))))

(deftest ln85eg-after-throw-over-2xx-observed-not-swallowed
  ;; Under a retryable :rf.http/transport policy, a reply-tail throw
  ;; misclassified as a transport failure would re-send the completed 2xx.
  (let [hits (AtomicInteger. 0)
        {:keys [port] :as srv} (start-counting-200-server! hits)]
    (try
      (with-trace-capture
        (fn [captured]
          (rf/reg-http-interceptor :boom-after
            {:after (fn [_ctx _resp] (throw (ex-info "reply-tail kaboom" {})))})
          (rf/reg-event :ln85eg/reply
            (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
          (rf/reg-event :ln85eg/load
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/x")}
                      :decode     :json
                      :retry      {:on #{:rf.http/transport} :max-attempts 3}
                      :on-success [:ln85eg/reply]
                      :on-failure [:ln85eg/reply]}]]}))
          (rf/dispatch-sync [:ln85eg/load])
          (await-op! captured :rf.error/http-reply-tail-failed)
          (is (= 1 (.get hits)) "no re-send of the completed request")
          (is (= [{:kind :success :reply-error-id :rf.error/http-interceptor-failed}]
                 (mapv #(select-keys (:tags %) [:kind :reply-error-id])
                       (ops captured :rf.error/http-reply-tail-failed))))
          (is (nil? (:reply (rf/app-db-value :rf/default))) "delivery is what threw")))
      (finally (stop-server! srv)))))

(deftest sensitive-after-bad-return-leaves-no-secret-in-reply-tail-cause
  ;; An :after returning a slice of its ctx makes the caught throw's message,
  ;; and so the reply-tail row's :cause, echo the request's secret header.
  (let [secret   "REPLY_TAIL_SECRET"
        hits     (AtomicInteger. 0)
        records  (atom [])
        error-id (gensym "reply-tail-record-")
        {:keys [port] :as srv} (start-counting-200-server! hits)]
    (rf.error-emit/register-error-listener! error-id #(swap! records conj %))
    (try
      (with-trace-capture
        (fn [captured]
          (rf/reg-http-interceptor :mark-sensitive
            {:before (fn [ctx] (assoc-in ctx [:request :sensitive?] true))})
          (rf/reg-http-interceptor :echo-auth
            {:after (fn [ctx _resp] [(get-in ctx [:request :headers "Authorization"])])})
          (rf/reg-event :rtsecret/reply
            (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
          (rf/reg-event :rtsecret/load
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:url     (str "http://127.0.0.1:" port "/x")
                                   :headers {"Authorization" secret}}
                      :decode     :json
                      :on-success [:rtsecret/reply]
                      :on-failure [:rtsecret/reply]}]]}))
          (rf/dispatch-sync [:rtsecret/load])
          (await-op! captured :rf.error/http-reply-tail-failed)
          (let [rtf    (first (ops captured :rf.error/http-reply-tail-failed))
                br     (first (ops captured :rf.error/http-interceptor-bad-return))
                record (first (filter #(= :rf.error/http-reply-tail-failed (:error %)) @records))]
            (is (= [true :rf.error/http-interceptor-bad-return :rf/redacted]
                   ((juxt :sensitive? (comp :reply-error-id :tags) (comp :cause :tags)) rtf)))
            (is (= [true :echo-auth] ((juxt :sensitive? (comp :id :tags)) br)))
            (is (= :rf.error/http-interceptor-bad-return (some-> record :exception ex-data :rf.error/id))
                "the always-on record fires, carrying the caught bad-return")
            (is (not (str/includes? (pr-str [(:tags rtf) (:tags br) record]) secret))))))
      (finally
        (rf.error-emit/unregister-error-listener! error-id)
        (stop-server! srv)))))

(deftest non-sensitive-after-throw-leaves-no-denylisted-param-in-reply-tail-record
  ;; The record's exception is the caught chain error, which carries the
  ;; request :url; a denylisted param's value is redacted, the rest rides.
  (let [denied   "REPLY_TAIL_DENYLISTED"
        hits     (AtomicInteger. 0)
        records  (atom [])
        error-id (gensym "reply-tail-denylist-record-")
        {:keys [port] :as srv} (start-counting-200-server! hits)
        url      (str "http://127.0.0.1:" port "/x?api_key=" denied "&page=2")]
    (rf.error-emit/register-error-listener! error-id #(swap! records conj %))
    (try
      (with-trace-capture
        (fn [captured]
          (rf/reg-http-interceptor :boom-after
            {:after (fn [_ctx _resp] (throw (ex-info "after kaboom" {})))})
          (rf/reg-event :rtdeny/reply
            (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
          (rf/reg-event :rtdeny/load
            (fn [_ _]
              {:fx [[:rf.http/managed
                     {:request    {:url url}
                      :decode     :json
                      :on-success [:rtdeny/reply]
                      :on-failure [:rtdeny/reply]}]]}))
          (rf/dispatch-sync [:rtdeny/load])
          (await-op! captured :rf.error/http-reply-tail-failed)
          (let [record (first (filter #(= :rf.error/http-reply-tail-failed (:error %)) @records))]
            (is (= {:rf.error/id :rf.error/http-interceptor-failed
                    :url         (str "http://127.0.0.1:" port "/x?api_key=:rf/redacted&page=2")}
                   (select-keys (some-> record :exception ex-data) [:rf.error/id :url])))
            (is (not (str/includes? (pr-str record) denied))))))
      (finally
        (rf.error-emit/unregister-error-listener! error-id)
        (stop-server! srv)))))
