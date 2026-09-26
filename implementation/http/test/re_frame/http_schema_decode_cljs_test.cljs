(ns re-frame.http-schema-decode-cljs-test
  "CLJS coverage for schema-driven `:decode` (Spec 014 §Schema-driven,
  step 3): a schema `:decode` coerces the parsed JSON with Malli's
  `json-transformer` on CLJS, as it does on the JVM, where
  `re-frame.http-decode-test` covers the same decode.

  The app posture under test is the ordinary one: it requires
  `re-frame.schemas` and nothing from Malli directly. This namespace must
  not require `malli.transform` itself, because it would then supply the
  very namespace whose presence in the build is under test.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.schemas]))

(deftest schema-decode-coerces-a-json-string-to-a-keyword
  (testing "a `:keyword` field arrives from JSON as a string, and the schema
  decode coerces it to a keyword before validating, so a well-formed reply
  decodes rather than failing schema validation"
    (is (some? (resolve 'malli.core/decode))
        "precondition: requiring re-frame.schemas puts malli.core in the build")
    (is (= {:id 7 :status :active}
           (rf.http.decode/decode-response-body
             {:body-text "{\"id\":7,\"status\":\"active\"}"
              :headers   {"content-type" "application/json"}
              :decode    [:map [:id :int] [:status :keyword]]}))
        "string \"active\" is coerced to :active by the json-transformer")))
