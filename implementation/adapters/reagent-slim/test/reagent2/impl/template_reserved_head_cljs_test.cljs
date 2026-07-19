(ns reagent2.impl.template-reserved-head-cljs-test
  "Fail-loud on an UNRECOGNISED `:rf/*` hiccup head (rf2-01zvu, the client
  half of the rf2-j81hs SS4 ruling).

  rf2-j81hs made keyword heads HTML elements EVERYWHERE and closed the
  SERVER half: the two JVM emitters reject an unrecognised reserved head
  with `:rf.error/invalid-hiccup-head`. The CLIENT half was still silent —
  `[:rf/suspense-boundary …]` or a misspelt `:rf/…` head passed
  `hiccup-tag?`, reached `parse-tag`, and painted a phantom
  `<suspense-boundary>` element with the attrs map mangled onto it.

  The `:rf/*` root is framework-owned (Conventions §Reserved namespaces)
  and NO `:rf/*` head has a client render-tree meaning — `:rf/suspense-
  boundary` is a streaming-SSR-only marker — so the guard is TOTAL: every
  `:rf/*` / `:rf.<area>/*` head is rejected, with no allow-list carve-out.

  The check lives in `parse-tag`, which `cached-parse` reaches ONLY on a
  cache MISS (zero steady-state cost, per the ruling) and which
  `reagent2.dom.server` calls directly — one guard, both surfaces.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
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
    (let [data (head-error [:rf/suspense-boundry {:id 1}])]
      (is (some? data)
          "a misspelt :rf/* head must throw, not paint a phantom element")
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data))
          "reuses the id the JVM emitters' reserved-head arm already carries")
      (is (= :use-a-recognised-reserved-head-or-an-unreserved-keyword
             (:recovery data))
          "same recovery token as the server arm — one target grammar")
      (is (= :rf/suspense-boundry (:head data))
          "the offending head rides the payload")))

  (testing ":rf/suspense-boundary is server-streaming-only on the client"
    (let [data (head-error [:rf/suspense-boundary {:id 1}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data))
          "the recognised SERVER marker still has no client meaning")))

  (testing "a dotted rf.<area> namespace is reserved too"
    (is (= :rf.error/invalid-hiccup-head
           (:rf.error/id (head-error [:rf.ssr/nope {}])))))

  (testing "the human message names the reserved scheme"
    (let [msg (try (template/as-element [:rf/nope {}]) ""
                   (catch cljs.core/ExceptionInfo ex (ex-message ex)))]
      (is (re-find #"framework-reserved" msg)
          "the message must explain WHY, not just that it failed"))))

(deftest unreserved-keyword-heads-still-render
  (testing "ordinary and custom-element heads are untouched"
    (is (some? (template/as-element [:div "x"])))
    (is (some? (template/as-element [:my-element "x"])))
    (is (some? (template/as-element [:div.cls#id "x"]))))

  (testing "a NON-rf namespaced head keeps its existing behaviour"
    ;; Only the framework-owned `:rf/*` root is reserved. A `:svg/circle`
    ;; head is not this bead's business and must not start throwing.
    (is (nil? (:rf.error/id (head-error [:svg/circle {}])))))

  (testing "the interop heads are consumed before the tag grammar"
    (is (some? (template/as-element [:<> "a" "b"])))))

(deftest reserved-head-survives-a-colliding-cache-entry
  ;; `cached-parse` memoises parsed tags. Keyed on `(name k)` alone,
  ;; `:button` and `:rf/button` would COLLIDE: rendering `[:button]` first
  ;; would seed the entry, and the later `[:rf/button]` would HIT the cache,
  ;; never reach `parse-tag`, and paint a phantom — a fail-loud guard the
  ;; app's own ordinary markup silently disarms. The cache key is therefore
  ;; the FULLY-QUALIFIED name, so the reserved head always misses.
  (testing "an unreserved twin rendered first does not disarm the guard"
    (is (some? (template/as-element [:button "ordinary"]))
        "seed the cache under the bare name")
    (is (= :rf.error/invalid-hiccup-head
           (:rf.error/id (head-error [:rf/button {}])))
        "the reserved twin must still fail loud")))
