;;;; tests/article_lifecycle_test.clj — the canonical HTTP fix in
;;;; references/schemaless-events.md must SETTLE its lifecycle. A reader pastes
;;;; its "After" block into a status-driven page; a block that leaves
;;;; `[:article :status]` at `:loading` after a reply delivers the payload or
;;;; the error correctly and still shows a spinner for ever.
;;;;
;;;; The suite EVALUATES the shipped block: `rf/reg-event` and
;;;; `rf/reg-app-schema` are bound to local collectors, and the pure `:db`
;;;; transitions run unmodified.
;;;;
;;;; Run: bb tests/article_lifecycle_test.clj   (from the skill root)

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

(defn- run [id db event] (:db ((get @handlers id) {:db db} event)))

(def ^:private article {:slug "alpha" :title "Alpha" :body "Synthetic" :authors []})
(def ^:private failure {:category :rf.http/http-5xx :message "Synthetic failure"})

(deftest request-keeps-its-always-on-decode-gate-and-both-reply-targets
  (let [{:keys [fx]} ((get @handlers :article/load) {:db {}} [:article/load {:slug "alpha"}])
        opts         (second (first (filter #(= :rf.http/managed (first %)) fx)))]
    (is (vector? (:decode opts))
        "the :rf.http/managed request must carry the always-on :decode gate, the defect this leaf teaches against")
    (is (= [[:article/loaded] [:article/load-failed]] ((juxt :on-success :on-failure) opts))
        "both reply branches must be addressed")))

(deftest lifecycle-starts-and-settles-on-both-branches
  (let [loading (run :article/load {} [:article/load {:slug "alpha"}])]
    (is (= :loading (get-in loading [:article :status])) ":article/load must start the lifecycle at :loading")
    (is (= [:loaded article]
           ((juxt :status :data) (:article (run :article/loaded loading [:article/loaded {:status :ok :value article}]))))
        "a successful reply settles at :loaded and stores :value verbatim under [:article :data]")
    (is (= [:error failure]
           ((juxt :status :error)
            (:article (run :article/load-failed loading [:article/load-failed {:status :error :error failure}]))))
        "a failed reply settles at :error and stores the classified error verbatim")))

(let [{:keys [fail error]} (run-tests 'article-lifecycle-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
