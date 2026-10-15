(ns re-frame.source-coord-parity-test
  "JVM source-coordinate values match the client literals. Registered views
  emit both annotations in dev and bare markup in production."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr :as rf.ssr]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]
            [re-frame.views.jvm-source-coord-annotation :as rf.views.jvm-source-coord-annotation]))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime)

(def fixture-id :rf.parity-test/sample-view)

(def fixture-coords {:ns         'rf.parity-test
                     :line       42
                     :column     7
                     :file       "rf/parity_test.cljs"
                     :handler-id fixture-id})

(def expected-source-coord "rf.parity-test:sample-view:42:7")

(def expected-view-id ":rf.parity-test/sample-view")

(def fixture-coords-no-line-no-col
  {:ns         'rf.parity-test
   :file       "rf/parity_test.cljs"
   :handler-id fixture-id})

(def expected-source-coord-no-line-no-col "rf.parity-test:sample-view:?:?")

(deftest jvm-format-source-coord-degraded-shape-byte-identical
  (testing "Missing line and column use the canonical degraded value."
    (is (= expected-source-coord-no-line-no-col
           (rf.views.jvm-source-coord-annotation/format-source-coord fixture-id fixture-coords-no-line-no-col))
        "JVM degraded source-coord must match the canonical degraded literal")))

(deftest ssr-rendered-html-carries-both-annotations-through-callable-head
  (testing "The registered callable head emits the canonical attribute values."
    (rf/reg-view* fixture-id fixture-coords (fn [] [:p "body"]))
    (let [html (rf.ssr/render-to-string [(rf/view fixture-id)] {})]
      (when rf.interop/debug-enabled?
        (is (= (str "<p data-rf2-source-coord=\"" expected-source-coord "\""
                    " data-rf-view=\"" expected-view-id "\">body</p>")
               html)
            (str "the full annotated root, both attributes present; got: "
                 (pr-str html))))
      (when-not rf.interop/debug-enabled?
        (is (= "<p>body</p>" html)
            (str "under the production gate the callable head must render the "
                 "bare root, both annotations elided; got: " (pr-str html)))))))
