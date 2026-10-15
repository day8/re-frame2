(ns re-frame.http-reply-tail-test
  "A throw after a successful JVM request surfaces once as
  `:rf.error/http-reply-tail-failed` and is never retried. Its trace and
  always-on error records redact sensitive data and denylisted query values.

  The Fetch retry-storm counterpart is `re-frame.http-reply-tail-cljs-test`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.http.managed]
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
