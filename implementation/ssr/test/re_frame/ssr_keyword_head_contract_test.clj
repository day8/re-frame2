(ns re-frame.ssr-keyword-head-contract-test
  "Keyword heads name DOM elements; callable heads resolve views. Named children
  render their unqualified name. Reserved rf heads fail closed."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.ssr.emit :as rf.ssr.emit]
            [re-frame.ssr.test-fixture :as rf.ssr.test-fixture]))

(defn card-view [card-id]
  [:div.card [:h3 (str card-id)]])

;; Register after reset so the keyword head names a live view.

(defn- with-registered-card [test-fn]
  (rf/reg-view* :dashboard/card {} card-view)
  (test-fn))

(use-fixtures :each rf.ssr.test-fixture/reset-runtime with-registered-card)

(deftest keyword-head-is-an-element-not-a-view
  (testing "A registered view id remains a DOM tag when used as a keyword."
    (is (= "<card>revenue</card>"
           (rf.ssr.emit/render-to-string [:dashboard/card :revenue] nil)))))

(deftest scalar-children-are-spelled-by-name
  (testing "Named children drop their namespace."
    (are [expected tree] (= expected (rf.ssr.emit/render-to-string tree nil))
      "<div>b</div>"       [:div :a/b]
      "<div>b</div>"       [:div 'a/b]))

  (testing "Named children still escape."
    (is (= "<div>x&lt;y</div>" (rf.ssr.emit/render-to-string [:div :x<y] nil)))))

(deftest views-are-referenced-by-callable-head
  (testing "Callable heads resolve views; registered handles carry dev annotations."
    (testing "A bare function stays unannotated."
      (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
             (rf.ssr.emit/render-to-string [card-view :revenue] nil))))
    (when rf.interop/debug-enabled?
      (testing "The registered handle carries both dev annotations."
        (is (= (str "<div class=\"card\""
                    " data-rf2-source-coord=\"dashboard:card:?:?\""
                    " data-rf-view=\":dashboard/card\">"
                    "<h3>:revenue</h3></div>")
               (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))
    (when-not rf.interop/debug-enabled?
      (testing "Production emits the same bare view."
        (is (= "<div class=\"card\"><h3>:revenue</h3></div>"
               (rf.ssr.emit/render-to-string [(rf/view :dashboard/card) :revenue] nil)))))))

(defn- head-error
  "Render `tree` and return the thrown ex-data, or nil if it did not throw."
  [tree]
  (try (rf.ssr.emit/render-to-string tree nil) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest unrecognised-reserved-rf-head-fails-loud
  (testing "Unrecognised reserved heads fail closed."
    (let [data (head-error [:rf/suspense-boundary {:id :x} [:p "hi"]])]
      (is (= {:rf.error/id :rf.error/invalid-hiccup-head
              :recovery    :use-a-recognised-reserved-head-or-an-unreserved-keyword
              :head        :rf/suspense-boundary
              :element     [:rf/suspense-boundary {:id :x} [:p "hi"]]}
             (select-keys data [:rf.error/id :recovery :head :element]))
          "the reserved-head arm reuses the malformed-head id and is told apart
           by its :recovery, as the Spec 009 row promises")))

  (testing "Dotted rf namespaces are reserved."
    (is (some? (head-error [:rf.ssr/nope]))))

  (testing "App namespaces remain valid DOM tags."
    (is (= "<widget></widget>"
           (rf.ssr.emit/render-to-string [:rfid/widget] nil))
        "`:rfid/widget` starts with `rf` but is NOT the reserved scheme —
         the check must match the `rf` namespace exactly or an `rf.` dotted
         prefix, not a bare string prefix")))
