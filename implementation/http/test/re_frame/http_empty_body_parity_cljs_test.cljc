(ns re-frame.http-empty-body-parity-cljs-test
  "Host-symmetric decode contracts (Spec 014 §Decoding), asserted on both
  hosts from one source.

  An empty or whitespace-only 2xx JSON body is a normal outcome, not an
  error. The host readers disagree on an empty document (Cheshire yields nil,
  `JSON.parse` throws), so `decode-response-body` short-circuits a blank body
  to nil on both. And the schema decode path is JSON-only: a present non-JSON
  Content-Type is refused up front rather than JSON-parsed."
  (:require
   #?(:clj  [clojure.test :refer [are deftest is]]
      :cljs [cljs.test :refer-macros [are deftest is]])
   [re-frame.http.decode :as rf.http.decode]))

(defn- decode [body-text headers decode]
  (rf.http.decode/decode-response-body {:body-text body-text :headers headers :decode decode}))

(defn- thrown-id
  "The `:rf.error/id` of what `f` throws, or ::no-throw."
  [f]
  (try (f) ::no-throw
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e
         (:rf.error/id (ex-data e)))))

(deftest empty-2xx-body-decodes-to-nil-cross-host
  (are [body dec] (nil? (decode body {"content-type" "application/json"} dec))
    ""        :json
    "  \n\t " :json
    ""        [:maybe [:map [:id :int]]]))

(deftest schema-rejects-non-json-content-type-cross-host
  ;; The declared MIME decides even when the body is JSON the schema accepts.
  (is (= :rf.error/http-schema-non-json-content-type
         (thrown-id #(decode "{\"a\":1}" {"content-type" "application/xml"} [:map [:a :int]])))))

(deftest schema-tolerates-absent-content-type-cross-host
  ;; Many JSON APIs omit the header; only a PRESENT non-JSON MIME is refused.
  (is (not= :rf.error/http-schema-non-json-content-type
            (thrown-id #(decode "{\"a\":1}" {} [:map [:a :int]])))))
