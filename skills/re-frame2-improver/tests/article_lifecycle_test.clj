;; The HTTP rewrite must route both replies; lifecycle/schema coverage lives in
;; implementation/schemas/test/re_frame/improver_article_schema_test.clj.

(ns article-lifecycle-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]))

(def ^:private leaf-md
  (let [f (io/file (-> *file* io/file .getAbsoluteFile .getParentFile .getParentFile)
                   "references" "schemaless-events.md")]
    (delay (slurp f))))

(def ^:private after-block
  ;; Only the After block registers :article/load-failed; nil when absent or
  ;; duplicated, so a duplicate cannot pass a check its other copy fails.
  (delay (let [hits (->> (re-seq #"(?s)```clojure\r?\n(.*?)```" @leaf-md)
                         (map second)
                         (filter #(str/includes? % "reg-event :article/load-failed")))]
           (when (= 1 (count hits)) (first hits)))))

(def ^:private registry (atom {}))

(defn reg-event
  ([id f] (swap! registry assoc id f))
  ([id _opts f] (swap! registry assoc id f)))

(defn reg-app-schema [_path _schema] nil)

(def ^:private handlers
  (delay (reset! registry {})
         (load-string (str/replace @after-block "rf/" ""))
         @registry))

(deftest request-addresses-both-reply-targets
  (let [{:keys [fx]} ((get @handlers :article/load) {:db {}} [:article/load {:slug "alpha"}])
        opts         (second (first (filter #(= :rf.http/managed (first %)) fx)))]
    (is (= [[:article/loaded] [:article/load-failed]] ((juxt :on-success :on-failure) opts)))))

(let [{:keys [fail error]} (run-tests 'article-lifecycle-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
