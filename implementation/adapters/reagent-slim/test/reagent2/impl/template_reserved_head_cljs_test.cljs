(ns reagent2.impl.template-reserved-head-cljs-test
  "The client half of the reserved-head guard: every `:rf/*` / `:rf.<area>/*`
  hiccup head throws `:rf.error/invalid-hiccup-head` rather than painting a
  phantom element, as the JVM emitters do. The guard runs in `parse-tag` AND
  ahead of the tag cache, because `:rf/x` shares its cache key with the
  string head \"rf/x\"."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [reagent2.impl.template :as template]))

(defn- head-error
  "ex-data of the throw `as-element` raises for `form`, or nil when it
  rendered without throwing."
  [form]
  (try
    (template/as-element form)
    nil
    (catch cljs.core/ExceptionInfo ex
      (ex-data ex))))

(deftest unrecognised-reserved-head-fails-loud
  (testing "a misspelt reserved head is rejected, not painted"
    (is (= {:rf.error/id :rf.error/invalid-hiccup-head
            :recovery    :use-a-recognised-reserved-head-or-an-unreserved-keyword
            :head        :rf/suspense-boundry
            :element     [:rf/suspense-boundry {:id 1}]}
           (select-keys (head-error [:rf/suspense-boundry {:id 1}])
                        [:rf.error/id :recovery :head :element]))
        "the id, recovery, head and whole vector the Spec 009 row promises on both hosts"))

  (testing ":rf/suspense-boundary is server-streaming-only on the client"
    (let [data (head-error [:rf/suspense-boundary {:id 1}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data))
          "the recognised SERVER marker still has no client meaning")))

  (testing "a dotted rf.<area> namespace is reserved too"
    (is (= :rf.error/invalid-hiccup-head
           (:rf.error/id (head-error [:rf.ssr/nope {}]))))))

(deftest unreserved-keyword-heads-still-render
  (testing "ordinary and custom-element heads are untouched"
    ;; Read `.-type`: a head `parse-tag` cannot parse still yields an
    ;; element, with a nil type, so `some?` alone would pass on it.
    (is (= "div" (.-type (template/as-element [:div "x"]))))
    (is (= "my-element" (.-type (template/as-element [:my-element "x"])))))

  (testing "a NON-rf namespaced head is not reserved"
    (is (nil? (:rf.error/id (head-error [:svg/circle {}]))))))

(deftest reserved-head-survives-a-colliding-cache-entry
  (testing "an unreserved twin rendered first (seeding the cache under the
            bare name) does not disarm the guard"
    (template/as-element [:button "ordinary"])
    (is (= :rf.error/invalid-hiccup-head
           (:rf.error/id (head-error [:rf/button {}])))
        "the reserved twin must still fail loud")))

(deftest reserved-head-survives-a-string-aliased-cache-entry
  (testing "the string head \"rf/x\" seeds the same cache key as :rf/x, so
            the guard must run ahead of the cache lookup"
    (template/as-element ["rf/cache-string-twin" "ordinary"])
    (is (= {:rf.error/id :rf.error/invalid-hiccup-head
            :head        :rf/cache-string-twin
            :element     [:rf/cache-string-twin {:id 1}]}
           (select-keys (head-error [:rf/cache-string-twin {:id 1}])
                        [:rf.error/id :head :element])))))
