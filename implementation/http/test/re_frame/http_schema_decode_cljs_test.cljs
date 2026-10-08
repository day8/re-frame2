(ns re-frame.http-schema-decode-cljs-test
  "Schema-driven `:decode` on CLJS coerces with Malli's `json-transformer`,
  as on the JVM. The app posture under test requires `re-frame.schemas` and
  nothing from Malli directly, so this namespace must not require
  `malli.transform` itself: that would supply the very namespace whose
  presence in the build is under test."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.http.decode :as rf.http.decode]
            [re-frame.schemas]))

(deftest schema-decode-coerces-a-json-string-to-a-keyword
  (is (= {:id 7 :status :active}
         (rf.http.decode/decode-response-body
           {:body-text "{\"id\":7,\"status\":\"active\"}"
            :headers   {"content-type" "application/json"}
            :decode    [:map [:id :int] [:status :keyword]]}))))
