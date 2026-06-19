(ns day8.re-frame2-xray.panels.app-db-diff-format-cljs-test
  "Per-leaf smoke test for `app-db-diff-format` (rf2-nb8if).

  The format leaf carries the pure display-only formatters used in
  production — `format-edn` and `display-value` — plus the
  `display-large-string-threshold` ceiling. CLJS-only because the
  underlying leaf is `.cljs` (parent panel is CLJS-only)."
  (:require [cljs.test :refer-macros [deftest is]]
            [day8.re-frame2-xray.panels.app-db-diff-format :as f]))

(deftest format-leaf-smoke
  (is (= "[:cart :items]" (f/format-edn [:cart :items])))
  (let [big (apply str (repeat (inc f/display-large-string-threshold) "x"))
        v   (f/display-value {:k big})]
    (is (map? (:rf.size/large-elided (:k v)))
        "large strings collapse to the elision marker")))

;; ---- rf2-4spyl — display-value identity preservation -----------------------
;;
;; The audit's H2 finding: `display-value` rebuilt the whole tree on every
;; call, even when nothing needed eliding. That defeated downstream
;; `(identical? before after)` short-circuits — notably `engine/project`
;; which only short-circuits when the same JS reference is passed.
;;
;; The fix has two surfaces under test here:
;;
;; 1. Fast path — inputs with no large strings pass through unchanged
;;    (same identity). Verified across map / vector / set / nested.
;; 2. Slow path — inputs with large strings get a stable rewritten
;;    output cached by input identity. Two calls with the same input
;;    return the same JS reference.

(deftest display-value-preserves-identity-when-no-elision-needed
  ;; The audit's load-bearing property: when nothing in the tree needs
  ;; eliding, `display-value` must return the input AS-IS so that
  ;; identity-stable inputs stay identity-stable through the call.
  (let [m {:a 1 :b "short string" :c [1 2 3] :d #{:x :y}}]
    (is (identical? m (f/display-value m))
        "map with no large strings returns the same reference"))
  (let [v [1 [2 [3 [4 "still short"]]]]]
    (is (identical? v (f/display-value v))
        "deeply nested vector with no large strings returns same ref"))
  (let [s #{:a :b :c}]
    (is (identical? s (f/display-value s))
        "set with no large strings returns the same reference"))
  (let [seqv (doall (map inc [1 2 3]))]
    (is (identical? seqv (f/display-value seqv))
        "seq with no large strings returns the same reference")))

(deftest display-value-caches-output-on-slow-path
  ;; When elision IS needed, the rewritten output must be stable across
  ;; repeated calls with the same input — otherwise the engine memo
  ;; downstream sees a fresh tree every render and recomputes.
  (let [big (apply str (repeat (inc f/display-large-string-threshold) "x"))
        m   {:k big :other 42}
        out1 (f/display-value m)
        out2 (f/display-value m)]
    (is (identical? out1 out2)
        "repeated calls with the same input return the same rewritten output")
    (is (map? (:rf.size/large-elided (:k out1)))
        "large string still collapses to the elision marker")
    (is (= 42 (:other out1))
        "non-elided keys passed through")))

(deftest display-value-still-elides-large-strings
  ;; Regression guard — the optimisation must not break the semantic.
  (let [big (apply str (repeat (inc f/display-large-string-threshold) "x"))]
    (is (map? (:rf.size/large-elided (f/display-value big)))
        "top-level large string elides")
    (is (map? (:rf.size/large-elided (-> (f/display-value [big])
                                          first)))
        "large string inside a vector elides")
    (is (map? (:rf.size/large-elided (-> (f/display-value #{big})
                                          first)))
        "large string inside a set elides")))
