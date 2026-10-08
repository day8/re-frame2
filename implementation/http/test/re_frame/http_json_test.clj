(ns re-frame.http-json-test
  "`re-frame.http.json` on the JVM (Cheshire): the per-call unique-key cap
  that guards keyword interning, and the bytes `json-stringify` writes."
  (:require [cheshire.core :as cheshire]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [re-frame.http.json :as rf.http.json]))

(defn- big-json [n-keys]
  (str "{" (str/join "," (for [i (range n-keys)] (str "\"k" i "\":" i))) "}"))

(defn- parse-ex-data [s opts]
  (try (rf.http.json/json-parse s opts) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest json-parse-caps-unique-keys
  (let [s (big-json 50)]
    (is (map? (rf.http.json/json-parse s {:max-decoded-keys 50})) "at the cap succeeds")
    (is (= {:rf.error/id :rf.error/malformed-json :cause :too-many-keys :limit 49}
           (select-keys (parse-ex-data s {:max-decoded-keys 49}) [:rf.error/id :cause :limit])))))

(deftest default-cap-enforced-at-default-max
  (is (= {:cause :too-many-keys :limit 10000}
         (select-keys (parse-ex-data (big-json 10001) nil) [:cause :limit]))))

(deftest cap-counts-unique-not-total
  (let [s (str "{" (str/join "," (repeat 200 "\"a\":1,\"b\":2,\"c\":3,\"d\":4,\"e\":5")) "}")]
    (is (map? (rf.http.json/json-parse s {:max-decoded-keys 10})))))

(def ^:private uuid-a #uuid "6f1c2b3a-0000-4000-8000-000000000001")

(deftest json-stringify-matches-across-hosts
  ;; The CLJS twin pins the same expectations against its own walk; parsed
  ;; JSON is compared because key order is not part of the contract.
  (is (= {"order/id" 1 "customer/id" 7 "status" "order/pending"
          "id" "6f1c2b3a-0000-4000-8000-000000000001"
          "ids" ["6f1c2b3a-0000-4000-8000-000000000001"]
          "6f1c2b3a-0000-4000-8000-000000000001" 1}
         (cheshire/parse-string
           (rf.http.json/json-stringify {:order/id 1 :customer/id 7 :status :order/pending
                                         :id uuid-a :ids [uuid-a] uuid-a 1}))))
  (is (= {:order/id 1 :customer/id 7}
         (rf.http.json/json-parse (rf.http.json/json-stringify {:order/id 1 :customer/id 7})))))

(deftest jvm-json-parse-nil-returns-nil
  ;; A response with no body text decodes to nil rather than throwing.
  (is (nil? (rf.http.json/json-parse nil))))
