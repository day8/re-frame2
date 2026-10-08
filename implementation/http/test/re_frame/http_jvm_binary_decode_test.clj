(ns re-frame.http-jvm-binary-decode-test
  "Spec 014 §Decoding + §JVM degradation table — JVM binary decode.

  `jvm-fetch` reads `BodyHandlers/ofByteArray` and, when the resolved decode
  mode is binary (`binary-read-kind`), rides the raw `byte[]` under
  `:body-binary` so the bytes survive verbatim. Reading every body as a String
  would send a `:blob` / `:array-buffer` / `:form-data` decode through a lossy
  UTF-8 round trip that corrupts binary payloads. The text path reproduces
  `ofString`'s charset handling via `charset-of`.

  The live tests use an in-process JDK HttpServer (real socket, real
  `HttpClient`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.http.managed :as rf.http.managed]
            [re-frame.http.transport-jvm :as rf.http.transport-jvm]
            [re-frame.test-support :as rf.test-support])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]
           [java.nio.charset StandardCharsets]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(defn- start-server! [handler]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)
        ctx    (.createContext server "/")]
    (.setHandler ctx (reify HttpHandler (handle [_ ex] (handler ex))))
    (.setExecutor server nil)
    (.start server)
    {:server server :port (.getPort (.getAddress server))}))

(defn- stop-server! [{:keys [server]}] (.stop server 0))

(defn- write-bytes! [^HttpExchange ex status content-type ^bytes body]
  (when content-type
    (-> ex .getResponseHeaders (.set "Content-Type" content-type)))
  (.sendResponseHeaders ex status (long (count body)))
  (with-open [os (.getResponseBody ex)]
    (.write os body)))

(defn- await-reply! [pred]
  (rf.test-support/poll-until
    #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
    {:timeout-ms 5000 :label "http binary reply"}))

;; Non-UTF-8 high bytes: a lossy String decode would substitute U+FFFD and
;; not round-trip to these bytes.
(def ^:private raw-bytes (byte-array [(byte 0x00) (byte -1) (byte -2)
                                      (byte 0x7f) (byte -128) (byte 0x42)]))

(deftest jvm-blob-decode-returns-raw-bytes
  (testing ":decode :blob on JVM rides the raw byte[] under
            :body-binary (ofByteArray), NOT a lossy UTF-8 String"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-bytes! ex 200 "application/octet-stream" raw-bytes)))]
      (try
        (rf/reg-event :blob/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:blob/load msg] :request {:url (str "http://127.0.0.1:" port "/bin")}
                      :decode  :blob}]]})))
        (rf/dispatch-sync [:blob/load {}])
        (let [db (await-reply! #(some? (:reply %)))]
          (is (= (seq raw-bytes) (seq (get-in db [:reply :value])))
              "the bytes survive verbatim — no lossy UTF-8 round-trip"))
        (finally (stop-server! srv))))))

(deftest jvm-text-decode-still-uses-response-charset
  (testing "the text path decodes via the response charset
            (charset-of), faithfully reproducing ofString's
            behaviour for a non-UTF-8 charset"
    (let [latin1-bytes (.getBytes "café" "ISO-8859-1")
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-bytes! ex 200 "text/plain; charset=ISO-8859-1" latin1-bytes)))]
      (try
        (rf/reg-event :text/load
          (fn [{:keys [db]} [_ msg reply]]
            (if reply
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:reply-to [:text/load msg] :request {:url (str "http://127.0.0.1:" port "/t")}
                      :decode  :text}]]})))
        (rf/dispatch-sync [:text/load {}])
        (let [db (await-reply! #(some? (:reply %)))]
          (is (= "café" (get-in db [:reply :value]))
              "ISO-8859-1 bytes must decode via the declared charset, not raw UTF-8"))
        (finally (stop-server! srv))))))

(deftest charset-of-defaults-to-utf8
  (testing "an unparseable charset name does not throw — it falls back to UTF-8"
    (is (= StandardCharsets/UTF_8
           (@#'rf.http.transport-jvm/charset-of {"content-type" "text/x; charset=not-a-real-charset"})))))
