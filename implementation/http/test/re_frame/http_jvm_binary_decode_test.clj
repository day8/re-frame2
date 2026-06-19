(ns re-frame.http-jvm-binary-decode-test
  "Spec 014 §Decoding + §JVM degradation table — JVM binary decode +
  per-host timeout `:elapsed-ms` (rf2-a3wxe).

  Two gaps closed:

  1. Binary decode on JVM. The JVM transport used to read every response
     body as a String (`BodyHandlers/ofString`), so a `:blob` /
     `:array-buffer` / `:form-data` decode fell through to the lossy
     `body-text` fallback in `decode-response-body` — a UTF-8 decode of
     raw bytes that CORRUPTS binary payloads. `jvm-fetch` now reads
     `BodyHandlers/ofByteArray` and, when the resolved decode mode is
     binary (`binary-read-kind`), rides the raw `byte[]` under
     `:body-binary` so the bytes survive verbatim. The text path
     reproduces `ofString`'s charset handling via `charset-of`.

  2. Per-host timeout `:elapsed-ms`. The JDK's `HttpTimeoutException`
     does not surface the elapsed wall clock, so `classify-jvm-error`
     used to leave `:elapsed-ms nil` on JVM. `run-attempt!` now captures
     a monotonic start mark and threads the measured wall-clock delta in,
     matching the CLJS path's intent (a value on both hosts, not nil-on-JVM).

  These exercise the live JVM transport via an in-process JDK HttpServer
  (real socket, real `HttpClient`) — they fail deterministically against
  the pre-fix code (which UTF-8-decoded the bytes / left elapsed-ms nil)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.http.managed :as http-managed]
            [re-frame.http.transport-jvm :as transport-jvm]
            [re-frame.test-support :as test-support])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress]))

;; ---- per-test reset --------------------------------------------------------

(defn- reset-runtime [t]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`,
  ;; and the managed-HTTP / machine / routing fxs now require a carried
  ;; frame stamp. This suite exercises the ambient dispatch path against
  ;; a single conventional app frame, so register `:rf/default` explicitly
  ;; and pin it as the established scope for the whole body via with-frame.
  (frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.http.managed :reload)
  (http-managed/clear-all-in-flight!)
  (http-managed/clear-all-http-interceptors!)
  (rf/with-frame :rf/default
    (t)))

(use-fixtures :each reset-runtime)

;; ---- a tiny in-process HTTP server (raw-bytes capable) --------------------

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
  (test-support/poll-until
    #(let [db (rf/app-db-value :rf/default)] (when (pred db) db))
    {:timeout-ms 5000 :label "http binary reply"}))

;; ---- (1) binary decode honours the bytes on JVM (rf2-a3wxe) ---------------

;; A payload with non-UTF-8 high bytes — a lossy String decode (the pre-fix
;; behaviour) would substitute U+FFFD replacement chars and re-encoding
;; would NOT round-trip to these bytes. ofByteArray preserves them exactly.
(def ^:private raw-bytes (byte-array [(byte 0x00) (byte -1) (byte -2)
                                      (byte 0x7f) (byte -128) (byte 0x42)]))

(deftest jvm-blob-decode-returns-raw-bytes
  (testing "rf2-a3wxe — :decode :blob on JVM rides the raw byte[] under
            :body-binary (ofByteArray), NOT a lossy UTF-8 String"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-bytes! ex 200 "application/octet-stream" raw-bytes)))]
      (try
        (rf/reg-event :blob/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/bin")}
                      :decode  :blob}]]})))
        (rf/dispatch-sync [:blob/load {}])
        (let [db    (await-reply! #(some? (:reply %)))
              value (get-in db [:reply :value])]
          (is (= :success (get-in db [:reply :kind])))
          (is (bytes? value)
              "the decoded value must be a byte[] (the raw bytes), not a String")
          (is (= (seq raw-bytes) (seq value))
              "the bytes must survive verbatim — no lossy UTF-8 round-trip"))
        (finally (stop-server! srv))))))

(deftest jvm-array-buffer-decode-returns-raw-bytes
  (testing "rf2-a3wxe — :decode :array-buffer on JVM also rides raw bytes"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-bytes! ex 200 "application/octet-stream" raw-bytes)))]
      (try
        (rf/reg-event :ab/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/bin")}
                      :decode  :array-buffer}]]})))
        (rf/dispatch-sync [:ab/load {}])
        (let [db    (await-reply! #(some? (:reply %)))
              value (get-in db [:reply :value])]
          (is (bytes? value))
          (is (= (seq raw-bytes) (seq value))))
        (finally (stop-server! srv))))))

(deftest jvm-text-decode-still-uses-response-charset
  (testing "rf2-a3wxe — the text path still decodes via the response charset
            (charset-of), faithfully reproducing the prior ofString
            behaviour for a non-UTF-8 charset"
    (let [latin1-bytes (.getBytes "café" "ISO-8859-1")
          {:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              (write-bytes! ex 200 "text/plain; charset=ISO-8859-1" latin1-bytes)))]
      (try
        (rf/reg-event :text/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request {:url (str "http://127.0.0.1:" port "/t")}
                      :decode  :text}]]})))
        (rf/dispatch-sync [:text/load {}])
        (let [db (await-reply! #(some? (:reply %)))]
          (is (= "café" (get-in db [:reply :value]))
              "ISO-8859-1 bytes must decode via the declared charset, not raw UTF-8"))
        (finally (stop-server! srv))))))

;; ---- (1b) charset-of unit coverage ----------------------------------------

(deftest charset-of-resolves-declared-charset
  (testing "rf2-a3wxe — charset-of parses the Content-Type charset param"
    (let [charset-of @#'transport-jvm/charset-of]
      (is (= "ISO-8859-1"
             (.name ^java.nio.charset.Charset
                    (charset-of {"content-type" "text/plain; charset=ISO-8859-1"}))))
      (is (= "UTF-8"
             (.name ^java.nio.charset.Charset
                    (charset-of {"content-type" "application/json; charset=utf-8"})))))))

(deftest charset-of-defaults-to-utf8
  (testing "rf2-a3wxe — absent / unparseable charset falls back to UTF-8"
    (let [charset-of @#'transport-jvm/charset-of]
      (is (= "UTF-8" (.name ^java.nio.charset.Charset (charset-of {}))))
      (is (= "UTF-8" (.name ^java.nio.charset.Charset
                            (charset-of {"content-type" "application/json"}))))
      (is (= "UTF-8" (.name ^java.nio.charset.Charset
                            (charset-of {"content-type" "text/x; charset=not-a-real-charset"})))
          "an unparseable charset name must not throw — falls back to UTF-8"))))

;; ---- (2) per-host timeout :elapsed-ms (rf2-a3wxe) --------------------------

(deftest classify-jvm-error-stamps-elapsed-ms-on-timeout
  (testing "rf2-a3wxe — classify-jvm-error's 3-arity stamps the measured
            :elapsed-ms onto a timeout failure (was nil pre-fix)"
    (let [classify transport-jvm/classify-jvm-error
          timeout  (java.net.http.HttpTimeoutException. "request timed out")
          failure  (classify timeout 30000 1234)]
      (is (= :rf.http/timeout (:kind failure)))
      (is (= 1234 (:elapsed-ms failure)) "measured elapsed wall-clock is carried")
      (is (= 30000 (:limit-ms failure)) "the configured limit is preserved"))))

(deftest classify-jvm-error-elapsed-ms-nil-when-unmeasured
  (testing "rf2-w59es5 — the single 3-arity carries a nil :elapsed-ms when no
            start mark was measured (the synthetic-caller case); :limit-ms
            still rides whatever was threaded"
    (let [classify transport-jvm/classify-jvm-error
          timeout  (java.net.http.HttpTimeoutException. "request timed out")]
      (is (nil? (:elapsed-ms (classify timeout 30000 nil))))
      (is (nil? (:elapsed-ms (classify timeout nil nil)))))))

(deftest jvm-real-timeout-populates-elapsed-ms
  (testing "rf2-a3wxe — a live JVM request that exceeds its per-attempt
            timeout surfaces :rf.http/timeout with a NON-nil :elapsed-ms
            (the end-to-end run-attempt! → classify-jvm-error path)"
    (let [{:keys [port] :as srv}
          (start-server!
            (fn [^HttpExchange ex]
              ;; Stall well past the request's tiny timeout-ms.
              (Thread/sleep 800)
              (write-bytes! ex 200 "application/json"
                            (.getBytes "{\"ok\":true}" "UTF-8"))))]
      (try
        (rf/reg-event :slow/load
          (fn [{:keys [db]} [_ msg]]
            (if-let [reply (:rf/reply msg)]
              {:db (assoc db :reply reply)}
              {:fx [[:rf.http/managed
                     {:request    {:url (str "http://127.0.0.1:" port "/slow")}
                      :decode     :json
                      :timeout-ms 50}]]})))
        (rf/dispatch-sync [:slow/load {}])
        (let [db      (await-reply! #(some? (:reply %)))
              failure (get-in db [:reply :failure])]
          (is (= :failure (get-in db [:reply :kind])))
          (is (= :rf.http/timeout (:kind failure)))
          (is (= 50 (:limit-ms failure)))
          (is (some? (:elapsed-ms failure))
              ":elapsed-ms must be populated on the JVM (was nil pre-fix)")
          (is (and (number? (:elapsed-ms failure))
                   (>= (:elapsed-ms failure) 0))
              ":elapsed-ms is a non-negative measured wall-clock delta"))
        (finally (stop-server! srv))))))
