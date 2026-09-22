;; Execute the published slice rewrite and the evals' actual predicates.
;; These checks grade lifecycle behaviour and fixture truth tables, not prose.
;; CI discovers this file through skills-structural's *_test.clj loop.
(ns render-lifecycle-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing run-tests]]))

(def skill-root (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile))
(println "gate root:" (.getCanonicalPath (.getParentFile (.getParentFile skill-root))))

(defn forms [source]
  (with-open [reader (java.io.PushbackReader. (java.io.StringReader. source))]
    (doall (take-while #(not= ::eof %) (repeatedly #(read {:eof ::eof} reader))))))

(defn registrations [source symbol]
  (into {} (for [form (forms source) :when (= symbol (first form))]
             [(second form) (eval (last form))])))

(def rewrite
  (let [md (slurp (io/file skill-root "references/manual-loading-flags.md"))
        blocks (map second (re-seq #"(?s)```clojure\r?\n(.*?)```" md))
        matches (filter #(str/includes? % "rf/reg-sub :items/status") blocks)]
    (assert (= 1 (count matches)) "one status-slice rewrite must exist")
    (registrations (first matches) 'rf/reg-event)))

(defn step [db id & args]
  (:db ((get rewrite id) {:db db} (into [id] args))))

(deftest initial-load-and-revalidation
  (let [initial (step {} :items/load-start)]
    (is (= {:status :loading :data nil :error nil} (:items initial)))
    (doseq [data [[] [{:id 1}]]]
      (testing (str "refresh keeps prior data, including empty success: " data)
        (let [loaded (step initial :items/load-success data)
              refreshing (step loaded :items/load-start)
              failed (step refreshing :items/load-failure :offline)
              recovered (step failed :items/load-success [{:id 2}])]
          (is (= :loaded (get-in loaded [:items :status])))
          (is (= :fetching (get-in refreshing [:items :status])))
          (is (= data (get-in refreshing [:items :data])))
          (is (= {:status :error :data data :error :offline} (:items failed)))
          (is (= {:status :loaded :data [{:id 2}] :error nil} (:items recovered))))))))

(def evals (-> (slurp (io/file skill-root "evals/evals.json")) (json/parse-string true) :evals))

(defn predicates [id]
  (let [source (:prompt (first (filter #(= id (:id %)) evals)))
        ;; The human request precedes the first form. reg-event and view forms
        ;; are read, never executed; only pure reg-sub functions are evaluated.
        source (subs source (str/index-of source "(rf/"))]
    (registrations source 'rf/reg-sub)))

(defn active [preds db]
  (set (for [[id f] preds :when (f db [id])] id)))

(deftest discriminator-fixtures-really-are-exclusive
  (doseq [[id states] [[18 (for [status [nil :idle :loading :error :loaded]
                                data [nil [] [{:id 1}]]]
                            {:article {:status status :data data}})]
                       [27 (for [loading? [nil false true]
                                 error [nil :offline]
                                 data [nil [] [{:id 1}]]]
                             {:items/loading? loading? :items/error error :items data})]]]
    (let [preds (predicates id)
          outcomes (map #(active preds %) states)]
      (is (= 4 (count preds)))
      (is (every? #(<= (count %) 1) outcomes) (str "eval " id " must not demand a false positive"))
      (is (= (set (keys preds)) (apply set/union outcomes)) "every branch is reachable")))
  (is (empty? (active (predicates 18) {:article {:status :idle :data nil}}))
      "mutual exclusion does not require a match before the first load"))

(deftest convenience-predicates-are-the-overlap-control
  (let [preds (predicates 34)
        result (set (for [id [:articles/loading? :articles/fetching? :articles/error? :articles/loaded?]
                          :when ((get preds id) [:loading] [id])] id))]
    (is (= #{:articles/loading? :articles/fetching?} result))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
