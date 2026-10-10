(ns re-frame.http-decode-test
  "The response-body decode pipeline in `re-frame.http.decode`: Malli
  decode, coercion and validation, JSON media-type sniffing, the keyword cap
  and the refusal of a schema `:decode` when Malli is absent. Malli is on
  this test classpath through the schemas test dep, so the schema branch runs
  the real Malli."
  (:require [clojure.test :refer [are deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.http.managed]
            [re-frame.http.transport :as rf.http.transport]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [re-frame.trace.tooling :as rf.trace.tooling]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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

;; ---- Malli absent ------------------------------------------------------------
;;
;; The Malli resolve delays are realised with Malli present on this classpath,
;; so the absent shape is rebound directly.

(defmacro ^:private without-malli [& body]
  `(with-redefs [rf.http.decode/malli-decode-fn      (delay nil)
                 rf.http.decode/malli-transformer-fn (delay nil)
                 rf.http.decode/malli-validate-fn    (delay nil)]
     ~@body))

(defn- error-id [thunk]
  (try (thunk) :no-throw
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest schema-decode-without-malli-is-refused-at-dispatch
  (let [issued   (atom [])
        captured (atom [])
        request  (fn [decode]
                   [:rf.http/managed {:request  {:url "http://127.0.0.1:9/x"}
                                      :decode   decode
                                      :reply-to [:decode/reply]}])]
    (rf/reg-event :decode/reply (fn [_ _] {}))
    (rf/reg-event :decode/load
      (fn [_ _] {:fx [(request [:map [:id :int]]) (request :json)]}))
    (try
      (rf.trace.tooling/register-listener! ::cap #(swap! captured conj %))
      (without-malli
        (with-redefs [rf.http.transport/run-attempt! #(swap! issued conj (:decode %))]
          (rf/dispatch-sync [:decode/load])))
      (is (= [:json] @issued)
          "the schema request never reached the transport; the :json one did")
      (is (= [:rf.error/schemas-artefact-missing]
             (->> @captured
                  (filter #(and (= :rf.error/fx-handler-exception (:operation %))
                                (= :rf.http/managed (get-in % [:tags :rf.fx/id]))))
                  (mapv #(:rf.error/id (ex-data (get-in % [:tags :exception])))))))
      (finally
        (rf.trace.tooling/unregister-listener! ::cap)))))

(deftest non-schema-decodes-need-no-malli
  (without-malli
    (are [decode out] (= out (rf.http.decode/decode-response-body
                               {:body-text "{\"id\":1}"
                                :headers   {"content-type" "application/json"}
                                :decode    decode}))
      :auto                 {:id 1}
      :json                 {:id 1}
      :text                 "{\"id\":1}"
      (fn [body _] (count body)) 8)))

(deftest schema-decode-without-malli-throws-at-decode
  (without-malli
    (is (= :rf.error/schemas-artefact-missing
           (error-id #(rf.http.decode/decode-response-body
                        {:body-text "{\"id\":\"not-an-int\"}"
                         :headers   {"content-type" "application/json"}
                         :decode    [:map [:id :int]]}))))))
