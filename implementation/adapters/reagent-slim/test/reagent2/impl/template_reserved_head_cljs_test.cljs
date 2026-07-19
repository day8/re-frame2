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

  The check lives in `parse-tag` (the cache-MISS path, and the path
  `reagent2.dom.server` calls directly) AND at the top of `cached-parse`,
  ahead of the cache lookup (rf2-sgbna) — because the reserved keyword
  `:rf/x` and a valid string head of the same qualified name share the
  cache key `rf/x`, so a `parse-tag`-only guard let the string-seeded
  keyword ride a cache HIT and skip the reject. Two guard points, all
  three surfaces.

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
          "the offending head rides the payload")
      ;; rf2-vzno0 — Spec 009's `:rf.error/invalid-hiccup-head` row promises
      ;; `:head`, `:element` on BOTH arms, and the JVM arm
      ;; (`re-frame.ssr.emit/reject-reserved-rf-hiccup-head!`) supplies both.
      ;; The client arm stamped `:head` alone, so a diagnostic consumer
      ;; reading the documented payload got the head but nil for the
      ;; offending vector — cross-host friction in a structured error API.
      (is (= [:rf/suspense-boundry {:id 1}] (:element data))
          "the COMPLETE offending hiccup vector rides the payload, as the
           catalogue row promises for both arms")))

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

(deftest reserved-head-survives-a-string-aliased-cache-entry
  ;; rf2-sgbna — the STRING-vs-keyword twin, distinct from the
  ;; keyword-vs-keyword case above. The rf2-01zvu fix keyed the cache on the
  ;; head's FULLY-QUALIFIED name, so the reserved keyword `:rf/x` keys to the
  ;; string "rf/x". A valid STRING head "rf/x" keys to the SAME "rf/x" (a
  ;; string's `cache-key` is its own `name`). Rendering the string form first
  ;; therefore SEEDS that entry, and the later reserved keyword took the
  ;; cache-HIT path — bypassing `parse-tag`'s `reject-reserved-rf-head!` and
  ;; painting a phantom <rf/x>, its React type the aliased string. The guard
  ;; must run BEFORE the cache lookup so a type-aliased hit cannot disarm it.
  ;; The lever: seed the STRING, then probe the reserved KEYWORD twin — with
  ;; the guard only in `parse-tag` (cache-miss-only) the probe returns nil
  ;; (rendered, no throw); with the guard hoisted ahead of the lookup it
  ;; throws the catalogued reject carrying the exact `:element` (#6460).
  (testing "a string-form twin seeded first does not disarm the guard"
    (is (some? (template/as-element ["rf/cache-string-twin" "ordinary"]))
        "seed the cache under the aliased string key \"rf/cache-string-twin\"")
    (let [data (head-error [:rf/cache-string-twin {:id 1}])]
      (is (= :rf.error/invalid-hiccup-head (:rf.error/id data))
          "the reserved keyword twin must still fail loud after the string seed")
      (is (= :rf/cache-string-twin (:head data))
          "the offending reserved head rides the payload")
      ;; #6460 (rf2-vzno0): the reject carries the COMPLETE offending vector,
      ;; and it must do so on the cache-hit path too — not just from parse-tag.
      (is (= [:rf/cache-string-twin {:id 1}] (:element data))
          "the COMPLETE offending vector rides :element, per #6460 / rf2-vzno0"))))
