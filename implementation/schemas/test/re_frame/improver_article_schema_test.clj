(ns re-frame.improver-article-schema-test
  "Exercise the improver's published HTTP rewrite with real app-db validation.
  The skill's Babashka transition tests deliberately stub schema registration;
  this fixture catches a payload schema that rejects the pre-response states."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.schemas.test-fixture :as fixture]))

(def ^:private repo-root
  (nth (iterate #(.getParentFile %)
                (io/file (.toURI (io/resource "re_frame/improver_article_schema_test.clj"))))
       5))

(println "gate root:" (.getCanonicalPath repo-root))

(defn- install-example! []
  (let [markdown (slurp (io/file repo-root "skills/re-frame2-improver/references/schemaless-events.md"))
        blocks (map second (re-seq #"(?s)```clojure\r?\n(.*?)```" markdown))
        matches (filter #(str/includes? % "reg-event :article/load-failed") blocks)]
    (assert (= 1 (count matches)) "one canonical HTTP rewrite must exist")
    (binding [*ns* (the-ns 're-frame.improver-article-schema-test)]
      (load-string (first matches)))))

(use-fixtures :each fixture/reset-runtime)

(def ^:private article
  {:slug "alpha" :title "Alpha" :body "Body" :authors []})

(def ^:private failed-reply
  {:status :error :error {:category :rf.http/transport}})

(deftest cold-load-and-replies-survive-real-schema-validation
  (install-example!)
  (let [requests (atom [])]
    ;; Only transport is replaced. Registration, Malli validation and the
    ;; router's candidate-commit decision execute normally.
    (rf/reg-fx :rf.http/managed (fn [_ctx args] (swap! requests conj args)))
    (testing "a cold load commits its loading status before any article exists"
      (rf/dispatch-sync [:article/load {:slug "alpha"}])
      (is (= :loading (get-in (rf/app-db-value :rf/default) [:article :status])))
      (is (nil? (get-in (rf/app-db-value :rf/default) [:article :data])))
      (is (= 1 (count @requests))))
    (testing "the response schema stays strict even though stored data may be absent"
      (when-let [decode (:decode (first @requests))]
        (is (m/validate decode article))
        (is (false? (m/validate decode nil)))
        (is (false? (m/validate decode {:slug "alpha"})))))
    (testing "a first-request failure commits without a payload"
      (rf/dispatch-sync [:article/load-failed failed-reply])
      (is (= :error (get-in (rf/app-db-value :rf/default) [:article :status])))
      (is (= (:error failed-reply) (get-in (rf/app-db-value :rf/default) [:article :error]))))
    (testing "a later successful reply commits the validated article"
      (rf/dispatch-sync [:article/loaded {:status :ok :value article}])
      (is (= {:status :loaded :data article :error nil}
             (:article (rf/app-db-value :rf/default)))))
    (testing "the storage schema still rejects malformed non-nil payloads"
      (let [before (rf/app-db-value :rf/default)]
        (rf/dispatch-sync [:article/loaded {:status :ok :value {:slug "bad"}}])
        (is (= before (rf/app-db-value :rf/default)))))))
