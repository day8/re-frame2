(ns re-frame.http-json-cljs-test
  "`re-frame.http.json` on CLJS (`js/JSON.parse` / `js/JSON.stringify`)."
  (:require [cljs.test :refer-macros [deftest is]]
            [re-frame.http.json :as rf.http.json]))

(deftest cljs-json-parse-malformed-throws
  ;; The managed decode site classifies the throw as :rf.http/decode-failure.
  (is (not= ::no-throw (try (rf.http.json/json-parse "{not-json") ::no-throw
                            (catch :default e e)))))

(def ^:private uuid-a #uuid "6f1c2b3a-0000-4000-8000-000000000001")

(deftest cljs-json-stringify-matches-across-hosts
  ;; The JVM twin pins the same expectations against Cheshire; parsed JSON is
  ;; compared because key order is not part of the contract.
  (is (= {"order/id" 1 "customer/id" 7 "status" "order/pending"
          "id" "6f1c2b3a-0000-4000-8000-000000000001"
          "ids" ["6f1c2b3a-0000-4000-8000-000000000001"]
          "6f1c2b3a-0000-4000-8000-000000000001" 1}
         (js->clj (js/JSON.parse
                    (rf.http.json/json-stringify {:order/id 1 :customer/id 7 :status :order/pending
                                                  :id uuid-a :ids [uuid-a] uuid-a 1})))))
  (is (= {:order/id 1 :customer/id 7}
         (rf.http.json/json-parse (rf.http.json/json-stringify {:order/id 1 :customer/id 7})))))
