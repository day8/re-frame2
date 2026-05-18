;;;; tests/runtime/parse_rf2_coord_test.clj
;;;;
;;;; Babashka-runnable verification of `parse-rf2-coord` from
;;;; `preload/re_frame2_pair/runtime.cljs`.
;;;;
;;;; Why a parallel implementation lives here:
;;;;
;;;;   `preload/re_frame2_pair/runtime.cljs` is a CLJS-only file loaded
;;;;   into the consumer app via shadow-cljs `:devtools :preloads`. It
;;;;   uses `js/parseInt`, so it can't run under bb directly. The real shadow-cljs test build (planned per
;;;;   `docs/TESTING.md` §1) will exercise the `.cljs` source in place
;;;;   under Node — that's the canonical home for these tests once the
;;;;   build is wired up.
;;;;
;;;;   Until then, this file mirrors the parser logic and asserts
;;;;   behaviour against samples cribbed from re-frame2's own tests
;;;;   (`implementation/reagent/test/re_frame/source_coord_dom_cljs_test.cljs`
;;;;   and `implementation/core/test/re_frame/ssr_source_coord_test.clj`)
;;;;   so format drift in `runtime.cljs` shows up under `bb` until the
;;;;   shadow-cljs harness lands.
;;;;
;;;; Run:    bb tests/runtime/parse_rf2_coord_test.clj
;;;; Exit:   0 = pass, non-zero = fail.

(ns parse-rf2-coord-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests testing]]))

;; ---------------------------------------------------------------------------
;; Mirror of preload/re_frame2_pair/runtime.cljs `parse-rf2-coord`.
;;
;; KEEP IN SYNC WITH preload/re_frame2_pair/runtime.cljs. Logic is identical except
;; for `js/parseInt` -> `Long/parseLong` (bb runtime). The regex,
;; segment count, and shape are the contract under test.
;; ---------------------------------------------------------------------------

(defn- parse-int-str [s]
  (when (and (string? s) (re-matches #"\d+" s))
    (Long/parseLong s)))

(defn parse-rf2-coord
  "See preload/re_frame2_pair/runtime.cljs for the canonical version.
   Returns {:ns :handler-id :line :col} or nil."
  [attr-val]
  (when (and (string? attr-val) (seq attr-val))
    (let [parts (str/split attr-val #":")]
      (when (= 4 (count parts))
        (let [[ns-part sym-part line-part col-part] parts]
          (when (and (seq ns-part) (seq sym-part))
            {:ns         ns-part
             :handler-id sym-part
             :line       (parse-int-str line-part)
             :col        (parse-int-str col-part)}))))))

;; ---------------------------------------------------------------------------
;; Canonical case — the DOM snippet from PR #135 (rf2-z7f7).
;; ---------------------------------------------------------------------------

(deftest canonical-form-1
  (testing "canonical 4-segment shape from PR #135"
    (is (= {:ns         "counter.core"
            :handler-id "counter-buttons"
            :line       47
            :col        11}
           (parse-rf2-coord "counter.core:counter-buttons:47:11")))))

;; ---------------------------------------------------------------------------
;; Real Form-1 sample shape — derived from
;; re-frame2/implementation/reagent/test/re_frame/source_coord_dom_cljs_test.cljs
;; (annotates-dom-root-without-attrs). The real test asserts the regex
;; `^rf\.src-coord-test:no-attrs:\d+:\d+$` — concrete sample below.
;; ---------------------------------------------------------------------------

(deftest form-1-rf2-test-sample
  (testing "Form-1 sample matching the rf2 test regex"
    (is (= {:ns         "rf.src-coord-test"
            :handler-id "no-attrs"
            :line       54
            :col        7}
           (parse-rf2-coord "rf.src-coord-test:no-attrs:54:7")))))

;; ---------------------------------------------------------------------------
;; Form-2 sample shape — re-frame2 tests confirm the inner output is
;; annotated using the SAME 4-segment format (the wrapper recurses on
;; the inner fn's hiccup; the attribute value is identical to Form-1).
;; Sample: rf.src-coord-test/form-2 from source_coord_dom_cljs_test.cljs.
;; ---------------------------------------------------------------------------

(deftest form-2-inner-output
  (testing "Form-2 wrapper annotates inner hiccup with the same 4-segment shape"
    (is (= {:ns         "rf.src-coord-test"
            :handler-id "form-2"
            :line       104
            :col        5}
           (parse-rf2-coord "rf.src-coord-test:form-2:104:5")))))

;; ---------------------------------------------------------------------------
;; SSR sample — derived from
;; re-frame2/implementation/core/test/re_frame/ssr_source_coord_test.clj
;; (reg-view-root-is-annotated). Test asserts the regex
;; `data-rf2-source-coord="rf\.ssr-coord-test:banner:\d+:\d+"`.
;; ---------------------------------------------------------------------------

(deftest ssr-sample
  (testing "SSR-rendered registered view carries the same 4-segment format"
    (is (= {:ns         "rf.ssr-coord-test"
            :handler-id "banner"
            :line       48
            :col        13}
           (parse-rf2-coord "rf.ssr-coord-test:banner:48:13")))))

;; ---------------------------------------------------------------------------
;; Programmatic-registration degradation — Spec 006 §Attribute value
;; format: <line>/<col> may be `?` when the macro path was bypassed.
;; The CLJS test (programmatic-registration-degrades-gracefully)
;; asserts the literal value "rf.src-coord-test:programmatic:?:?".
;; ---------------------------------------------------------------------------

(deftest degraded-line-col
  (testing "programmatic registration with `?` placeholders parses the id portion;
            line/col are nil"
    (is (= {:ns         "rf.src-coord-test"
            :handler-id "programmatic"
            :line       nil
            :col        nil}
           (parse-rf2-coord "rf.src-coord-test:programmatic:?:?"))))

  (testing "partial degradation — only column missing"
    (is (= {:ns         "ns.x"
            :handler-id "view"
            :line       42
            :col        nil}
           (parse-rf2-coord "ns.x:view:42:?")))))

;; ---------------------------------------------------------------------------
;; Malformed / edge cases — must never throw; return nil.
;; ---------------------------------------------------------------------------

(deftest malformed-too-few-segments
  (testing "too few segments returns nil"
    (is (nil? (parse-rf2-coord "ns:view:42")))
    (is (nil? (parse-rf2-coord "ns:view")))
    (is (nil? (parse-rf2-coord "ns")))
    (is (nil? (parse-rf2-coord "")))))

(deftest malformed-too-many-segments
  (testing "more than 4 segments returns nil — strict 4-segment shape"
    (is (nil? (parse-rf2-coord "a:b:c:d:e")))
    (is (nil? (parse-rf2-coord "a:b:1:2:3")))))

(deftest empty-segments
  (testing "empty <ns> or <handler-id> segments return nil"
    (is (nil? (parse-rf2-coord ":handler:1:2")))
    (is (nil? (parse-rf2-coord "ns::1:2")))))

(deftest non-string-input
  (testing "non-string input returns nil — never throws"
    (is (nil? (parse-rf2-coord nil)))
    (is (nil? (parse-rf2-coord 42)))
    (is (nil? (parse-rf2-coord :keyword)))
    (is (nil? (parse-rf2-coord ["v" "e" "c"])))))

;; ---------------------------------------------------------------------------
;; Hyphenated / dotted ids — re-frame's namespace and registry-id
;; conventions allow dots in the namespace and hyphens in the symbol.
;; ---------------------------------------------------------------------------

(deftest dotted-and-hyphenated
  (testing "dotted ns and hyphenated handler-id parse cleanly"
    (is (= {:ns         "my-app.cart.view"
            :handler-id "apply-coupon-button"
            :line       125
            :col        4}
           (parse-rf2-coord "my-app.cart.view:apply-coupon-button:125:4")))))

;; ---------------------------------------------------------------------------
;; Round-trip (idempotence) — re-formatting a parsed coord and parsing
;; that string back yields equivalent shape (line/col only meaningful
;; when both were captured numerically).
;; ---------------------------------------------------------------------------

(defn- format-coord
  "Mirror of re-frame2/views.cljs/format-source-coord: emits the
   <ns>:<sym>:<line>:<col> shape, with `?` for nil line/col."
  [{:keys [ns handler-id line col]}]
  (str ns ":" handler-id ":"
       (if line (str line) "?")
       ":"
       (if col (str col) "?")))

(deftest round-trip-numeric
  (testing "parse -> format -> parse is idempotent for numeric coords"
    (let [s     "counter.core:counter-buttons:47:11"
          once  (parse-rf2-coord s)
          twice (parse-rf2-coord (format-coord once))]
      (is (= once twice)))))

(deftest round-trip-degraded
  (testing "parse -> format -> parse is idempotent for ?:? coords"
    (let [s     "rf.x:programmatic:?:?"
          once  (parse-rf2-coord s)
          twice (parse-rf2-coord (format-coord once))]
      (is (= once twice)))))

;; ---------------------------------------------------------------------------
;; Run.
;; ---------------------------------------------------------------------------

(let [{:keys [fail error]} (run-tests 'parse-rf2-coord-test)]
  (System/exit (if (and (zero? fail) (zero? error)) 0 1)))
