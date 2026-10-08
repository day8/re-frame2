(ns re-frame.ssr-render-state-jvm-test
  "The JVM-only half of the render-state contract's tests (the shared corpus,
  policy and restore door are in `re-frame.ssr.render-state-cljs-test`)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.ssr.render-state :as rf.ssr.render-state]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(deftest jvm-only-values-fail-at-projection-and-their-in-domain-twins-ride
  ;; The wire domain is `manifest/edn-carryable?`, whose own tests enumerate
  ;; it; a JVM-only number proves the projection applies THAT cross-host
  ;; domain rather than a looser same-host one.
  (let [sfid    :rf.ssrrs/server-values
        project #(do (rf.frame/replace-frame-state! sfid {rf.frame/app-partition-key {:value %}})
                     (rf.ssr.render-state/project sfid {:render-state {:app-db [:value]}}))]
    (rf/make-frame {:id sfid :platform :server})
    (is (= [:rf.error/ssr-render-state-invalid :unserialisable :value :value]
           (try (project 9007199254740993)
                nil
                (catch clojure.lang.ExceptionInfo e
                  ((juxt :rf.error/id :invalid :key :half) (ex-data e)))))
        "an integer past 2^53 fails AT PROJECTION")
    (is (= {:rf/app-db {:value 9007199254740991} :rf/runtime-db {}}
           (project 9007199254740991))
        "control: its in-domain twin under the same key rides")))
