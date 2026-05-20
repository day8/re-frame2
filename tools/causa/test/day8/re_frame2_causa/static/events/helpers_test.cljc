(ns day8.re-frame2-causa.static.events.helpers-test
  "Pure-data unit tests for the Static Events helpers (rf2-o5f5f.6).
  JVM-runnable so the row-projection, payload-parsing, and hermetic
  simulate algebra are covered without a CLJS runtime."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test :refer-macros [deftest is testing]])
            #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])
            [day8.re-frame2-causa.static.events.helpers :as h]))

(def ^:private read-edn
  #?(:clj  edn/read-string
     :cljs reader/read-string))

;; ---- sanitize-row-id ----------------------------------------------------

(deftest sanitize-row-id-strips-leading-colon
  (is (= "user/login" (h/sanitize-row-id :user/login))
      "keyword namespaced — colon stripped")
  (is (= "foo" (h/sanitize-row-id :foo))
      "bare keyword — colon stripped"))

(deftest sanitize-row-id-non-keyword-fallback
  (is (= "\"string-id\"" (h/sanitize-row-id "string-id"))
      "string id surrounded by quotes from pr-str"))

;; ---- parse-payload-edn --------------------------------------------------

(deftest parse-payload-empty-returns-empty-vector
  (is (= [] (h/parse-payload-edn nil read-edn)))
  (is (= [] (h/parse-payload-edn "" read-edn)))
  (is (= [] (h/parse-payload-edn "   " read-edn))))

(deftest parse-payload-single-map
  (is (= [{:x 1}] (h/parse-payload-edn "{:x 1}" read-edn))))

(deftest parse-payload-multiple-args
  (is (= [{:x 1} :tag] (h/parse-payload-edn "{:x 1} :tag" read-edn))))

(deftest parse-payload-scalar
  (is (= [42] (h/parse-payload-edn "42" read-edn)))
  (is (= [:bar] (h/parse-payload-edn ":bar" read-edn))))

(deftest parse-payload-malformed-returns-error-map
  (let [result (h/parse-payload-edn "{unbalanced" read-edn)]
    (is (map? result))
    (is (string? (:parse-error result))
        "error map carries a human-readable :parse-error string")))

;; ---- build-event-vector -------------------------------------------------

(deftest build-event-vector-shape
  (is (= [:foo/bar] (h/build-event-vector :foo/bar [])))
  (is (= [:foo/bar {:x 1}] (h/build-event-vector :foo/bar [{:x 1}])))
  (is (= [:foo/bar {:x 1} :tag]
         (h/build-event-vector :foo/bar [{:x 1} :tag]))))

;; ---- run-simulate -------------------------------------------------------

(deftest run-simulate-missing-id-fails-cleanly
  (let [result (h/run-simulate {} :nope nil read-edn)]
    (is (false? (:ok? result)))
    (is (re-find #"No registration found" (:reason result)))))

(deftest run-simulate-no-handler-fn-fails-cleanly
  (let [registrations {:foo {:event/kind :db}}
        result (h/run-simulate registrations :foo nil read-edn)]
    (is (false? (:ok? result)))
    (is (re-find #":handler-fn" (:reason result)))))

(deftest run-simulate-db-handler-runs
  (let [registrations
        {:counter/inc
         {:event/kind :db
          :handler-fn (fn [db ev] (assoc db :last-event ev :n (inc (get db :n 0))))}}
        result (h/run-simulate registrations :counter/inc nil read-edn)]
    (is (true? (:ok? result)))
    (is (= :db (:kind result)))
    (is (= 1 (get-in result [:value :n])))
    (is (= [:counter/inc] (get-in result [:value :last-event])))))

(deftest run-simulate-db-handler-with-payload
  (let [registrations
        {:user/set
         {:event/kind :db
          :handler-fn (fn [db [_ user]] (assoc db :user user))}}
        result (h/run-simulate registrations :user/set "{:name \"alice\"}" read-edn)]
    (is (true? (:ok? result)))
    (is (= {:name "alice"} (get-in result [:value :user])))))

(deftest run-simulate-fx-handler-runs
  (let [registrations
        {:user/save
         {:event/kind :fx
          :handler-fn (fn [cofx _ev]
                        {:db (assoc (:db cofx) :saving? true)
                         :fx [[:dispatch [:user/saved]]]})}}
        result (h/run-simulate registrations :user/save nil read-edn)]
    (is (true? (:ok? result)))
    (is (= :fx (:kind result)))
    (is (true? (get-in result [:value :db :saving?])))
    (is (= [[:dispatch [:user/saved]]] (get-in result [:value :fx])))))

(deftest run-simulate-ctx-handler-runs
  (let [registrations
        {:ctx/touch
         {:event/kind :ctx
          :handler-fn (fn [ctx] (assoc ctx :touched? true))}}
        result (h/run-simulate registrations :ctx/touch nil read-edn)]
    (is (true? (:ok? result)))
    (is (= :ctx (:kind result)))
    (is (true? (get-in result [:value :touched?])))))

(deftest run-simulate-handler-throw-surfaces-friendly-failure
  (let [registrations
        {:bad/handler
         {:event/kind :db
          :handler-fn (fn [_db _ev] (throw (ex-info "boom" {})))}}
        result (h/run-simulate registrations :bad/handler nil read-edn)]
    (is (false? (:ok? result)))
    (is (re-find #"Handler threw" (:reason result)))
    (is (re-find #"boom" (:reason result)))))

(deftest run-simulate-malformed-payload-surfaces-parse-error
  (let [registrations
        {:foo {:event/kind :db
               :handler-fn (fn [db _] db)}}
        result (h/run-simulate registrations :foo "{unbalanced" read-edn)]
    (is (false? (:ok? result)))
    (is (re-find #"Could not parse payload" (:reason result)))))
