(ns re-frame.http-completion-fence-test
  "The completion fence, JVM half. `dispatch-reply!`'s reply-tail fence
  covers the `:after` chain and the reply dispatch; the completion fence covers
  everything above it (status cascade, body classification, retry decision,
  finalise and teardown). Unfenced on the JVM, a throw there escapes into the
  `whenComplete` stage nobody holds: no reply, no registry clear, a request in
  flight for ever. The CLJS double-send half is
  `re-frame.http-completion-fence-cljs-test`.

  Nothing user-facing throws above the reply tail (a `:decode` or `:accept`
  throw is classified by design), so `classify-decoded` is redefined to throw,
  landing the throw at the 2xx accept phase, above `finalise-success!`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.privacy-body :as rf.http.privacy-body]
            [re-frame.http.registry :as rf.http.registry]
            [re-frame.http.managed]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling])
  (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
           [java.net InetSocketAddress]
           [java.util.concurrent.atomic AtomicInteger]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-counting-200-server! [^AtomicInteger hits]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/"
      (reify HttpHandler
        (handle [_ ex]
          (let [^HttpExchange ex ex
                bytes (.getBytes "{\"ok\":true}" "UTF-8")]
            (.incrementAndGet hits)
            (-> ex .getResponseHeaders (.set "Content-Type" "application/json"))
            (.sendResponseHeaders ex 200 (long (count bytes)))
            (with-open [os (.getResponseBody ex)] (.write os bytes))))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- settled? [pred label]
  (try
    (rf.test-support/poll-until pred {:timeout-ms 5000 :interval-ms 10 :label label})
    (catch Exception _ false)))

(deftest completion-throw-is-fenced-and-torn-down-not-swallowed
  (let [hits     (AtomicInteger. 0)
        srv      (start-counting-200-server! hits)
        captured (atom [])
        ops      (fn [op] (filter #(= op (:operation %)) @captured))]
    (try
      (rf.trace.tooling/register-listener! ::cap #(swap! captured conj %))
      (with-redefs [rf.http.privacy-body/classify-decoded
                    (fn [& _]
                      (throw (ex-info "completion cascade kaboom"
                                      {:rf.error/id :rf.error/schemas-artefact-missing})))]
        (rf/reg-event :fence/reply
          (fn [{:keys [db]} [_ payload]] {:db (assoc db :reply payload)}))
        (rf/reg-event :fence/load
          (fn [_ _]
            {:fx [[:rf.http/managed
                   {:request    {:url (str "http://127.0.0.1:" (:port srv) "/x")}
                    :decode     :json
                    ;; The policy a throw misclassified as transport would retry under.
                    :retry      {:on #{:rf.http/transport} :max-attempts 3}
                    :request-id :fence/req
                    :reply-to   [:fence/reply]}]]}))
        (rf/dispatch-sync [:fence/load])
        (settled? #(seq (ops :rf.error/http-reply-tail-failed)) "reply-tail-failed surfaced")
        (is (empty? (ops :rf.http/replied))
            "PRECONDITION: the throw landed above the reply tail, so no reply envelope was built")
        (is (= [:rf.error/schemas-artefact-missing]
               (mapv (comp :reply-error-id :tags) (ops :rf.error/http-reply-tail-failed)))
            "surfaced exactly once, carrying the caught throw's own id")
        (is (= 1 (.get hits)) "not retried as a transport rejection")
        (is (settled? #(not (contains? (rf.http.registry/in-flight-snapshot) :fence/req))
                      "in-flight registry cleared")
            "the fence's teardown clears the in-flight entry")
        (is (nil? (:reply (rf/app-db-value :rf/default)))))
      (finally
        (rf.trace.tooling/unregister-listener! ::cap)
        (.stop ^HttpServer (:server srv) 0)))))
