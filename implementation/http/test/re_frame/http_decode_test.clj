(ns re-frame.http-decode-test
  "The response-body decode pipeline in `re-frame.http.decode`: Malli
  decode, coercion and validation, JSON media-type sniffing, the keyword cap
  and the Malli-absent degradation. Malli is on this test classpath through
  the schemas test dep, so the schema branch runs the real Malli."
  (:require [clojure.test :refer [are deftest is]]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(def ^:private malli-decode @#'rf.http.decode/malli-decode)

(deftest decode-response-body-schema-success-parses-then-coerces
  (is (= {:title "hello" :id 42 :status :active}
         (rf.http.decode/decode-response-body
           {:body-text "{\"title\":\"hello\",\"id\":42,\"status\":\"active\"}"
            :headers   {"content-type" "application/json"}
            :decode    [:map [:title :string] [:id :int] [:status :keyword]]}))))

(deftest decode-response-body-schema-validation-failure-throws-canonical
  (let [ex (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/http-schema-validation-failed"
                                 (rf.http.decode/decode-response-body
                                   {:body-text "{\"id\":\"not-an-int\"}"
                                    :headers   {"content-type" "application/json"}
                                    :decode    [:map [:id :int]]})))]
    (is (= {:rf.error/id :rf.error/http-schema-validation-failed
            :recovery    :no-recovery
            :where       'rf.http/decode-response-body
            :schema      [:map [:id :int]]
            :value       {:id "not-an-int"}}
           (select-keys (ex-data ex) [:rf.error/id :recovery :where :schema :value])))))

(deftest decode-response-body-schema-branch-malformed-json-throws-not-string-schema
  ;; Under a raw-text fallback a :string schema would validate malformed JSON
  ;; (the raw text IS a string). It must throw the JSON-parse failure instead,
  ;; and not a schema-validation failure.
  (let [thrown (try (rf.http.decode/decode-response-body
                      {:body-text "tru"
                       :headers   {"content-type" "application/json"}
                       :decode    :string})
                    ::no-throw
                    (catch Exception e e))]
    (is (not= ::no-throw thrown))
    (is (not= :rf.error/http-schema-validation-failed (:rf.error/id (ex-data thrown))))))

(deftest decode-response-body-json-branch-also-reraises-too-many-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf.error/malformed-json"
                        (rf.http.decode/decode-response-body
                          {:body-text        "{\"a\":1,\"b\":2,\"c\":3}"
                           :headers          {"content-type" "application/json"}
                           :decode           :json
                           :max-decoded-keys 2}))))

(deftest schema-decode-accepts-vendor-plus-json-media-types
  (is (= {:title "hello" :id 42}
         (rf.http.decode/decode-response-body
           {:body-text "{\"title\":\"hello\",\"id\":42}"
            :headers   {"content-type" "application/vnd.api+json; charset=utf-8"}
            :decode    [:map [:title :string] [:id :int]]}))))

(deftest auto-sniff-parses-only-json-media-types
  ;; JSON is subtype `json` or a `+json` suffix, parameters stripped and case
  ;; folded; a subtype merely containing "json" is not JSON.
  (are [ct out] (= out (rf.http.decode/decode-response-body
                         {:body-text "{\"ok\":true}" :headers {"content-type" ct} :decode :auto}))
    "application/json; charset=utf-8"            {:ok true}
    "APPLICATION/JSON"                           {:ok true}
    "text/json"                                  {:ok true}
    "application/vnd.github+json; charset=utf-8" {:ok true}
    "application/jsonrequest"                    "{\"ok\":true}"
    "text/plain"                                 "{\"ok\":true}"))

(deftest malli-absent-decode-passes-through-and-warns-once
  ;; The Malli resolve delays are realised with Malli present on this
  ;; classpath, so the absent shape is rebound directly.
  (let [latch    @#'rf.http.decode/malli-absent-warned?
        prior    @latch
        captured (atom [])]
    (try
      (reset! latch false)
      (rf.trace.tooling/register-listener! ::cap #(swap! captured conj %))
      (with-redefs [rf.http.decode/malli-decode-fn      (delay nil)
                    rf.http.decode/malli-transformer-fn (delay nil)
                    rf.http.decode/malli-validate-fn    (delay nil)]
        (is (= "notanumber" (malli-decode :int "notanumber")))
        (malli-decode [:map [:id :int]] {:id 1})
        (malli-decode :keyword "foo"))
      (is (= [[:warning :int]]
             (->> @captured
                  (filter #(= :rf.warning/http-malli-absent (:operation %)))
                  (mapv (juxt :op-type #(get-in % [:tags :schema]))))))
      (finally
        (rf.trace.tooling/unregister-listener! ::cap)
        (reset! latch prior)))))
