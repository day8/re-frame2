(ns re-frame.views.source-coord-warn-parity-cljs-test
  "Cross-adapter parity for the non-DOM-root warning emitted by the two
  source-coord annotation walks (rf2-9ex1i9):

    * Reagent hiccup walk:
      `re-frame.views.source-coord-annotation/inject-source-coord-attr`
    * React-hook walk (UIx / Helix):
      `re-frame.substrate.spine/inject-source-coord-attr`

  THE BUG: the React-hook walk warned whenever its output was non-element
  AND non-nil (so a registered view returning a STRING or NUMBER warned);
  the Reagent hiccup walk warned ONLY when the output was a vector. A
  string-returning view therefore emitted a one-shot non-DOM-root console
  warning under UIx/Helix but was SILENT under Reagent — an observable
  cross-adapter inconsistency on a pair-tool warning surface. The message
  TEXT was already shared (adapter/context) so it could not drift; the
  TRIGGER condition had drifted. The fix unifies both walks on the honest
  warn-on-ANY-non-nil-non-element predicate (a string root is equally
  un-annotatable on both substrates), and `nil` stays silent on both (a
  view legitimately renders nothing).

  These tests drive BOTH walks directly (no JSDOM, no adapter install) and
  assert their warn behaviour is IDENTICAL for a matrix of un-annotatable
  outputs. The Reagent walk routes its warning through the process-wide
  `warn-once` cache + `js/console.warn`; the spine walk takes an injectable
  `warn-fn`. We observe both via a `js/console.warn` stub so the assertion
  is uniform across walks.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.substrate.spine :as spine]
            [re-frame.views.source-coord-annotation :as sca]
            [re-frame.views.warn-once :as warn-once]))

;; ---- harness --------------------------------------------------------------

(def ^:private orig-console-warn (when (exists? js/console) (.-warn js/console)))

(defn console-warn-stub-fixture
  "Stub `js/console.warn` to count calls so both walks' warnings are
  observable uniformly, and clear the process-wide non-DOM-root warn-once
  cache before AND after each test so a sibling test's first-encounter
  warning can't swallow ours (and ours can't leak)."
  [test-fn]
  (warn-once/clear-warned-non-dom-roots!)
  (let [calls (atom 0)]
    (set! (.-warn js/console) (fn [& _] (swap! calls inc)))
    ;; Stash the counter where the test bodies can read it.
    (set! (.-rf2-warn-calls js/console) calls)
    (try (test-fn)
         (finally
           (set! (.-warn js/console) orig-console-warn)
           (warn-once/clear-warned-non-dom-roots!)))))

(use-fixtures :each console-warn-stub-fixture)

(defn- warn-count [] @(.-rf2-warn-calls js/console))

;; Drive the Reagent hiccup walk for `out` under a fresh warn-once cache and
;; return whether it warned (1) or stayed silent (0).
(defn- reagent-warned? [out]
  (warn-once/clear-warned-non-dom-roots!)
  (let [before (warn-count)]
    (sca/inject-source-coord-attr :rf.test/view "rf.test:view:1:1" out)
    (- (warn-count) before)))

;; Drive the React-hook (spine) walk for `out` and return whether it warned.
;; The private fn takes an injectable warn-fn; we route it through the SAME
;; warn-once helper the production wiring uses so the observable matches the
;; Reagent path (one-shot per id, lands on the stubbed console.warn).
(defn- spine-warned? [out]
  (warn-once/clear-warned-non-dom-roots!)
  (let [before  (warn-count)
        warn-fn (fn [id type-tag] (warn-once/warn-non-dom-root! id type-tag))]
    (#'spine/inject-source-coord-attr
      warn-fn :rf.test/view "rf.test:view:1:1" ":rf.test/view" out)
    (- (warn-count) before)))

;; ---- parity matrix --------------------------------------------------------

(deftest string-returning-view-warns-on-both-walks
  (testing "a reg-view'd component returning a STRING root warns on BOTH
            walks (rf2-9ex1i9 — formerly silent under Reagent, warned under
            UIx/Helix)"
    (is (= 1 (reagent-warned? "just a string"))
        "Reagent hiccup walk warns on a string root")
    (is (= 1 (spine-warned? "just a string"))
        "React-hook spine walk warns on a string root")))

(deftest number-returning-view-warns-on-both-walks
  (testing "a NUMBER root is equally un-annotatable and warns on both walks"
    (is (= 1 (reagent-warned? 42)) "Reagent walk warns on a number root")
    (is (= 1 (spine-warned? 42))   "spine walk warns on a number root")))

(deftest nil-returning-view-is-silent-on-both-walks
  (testing "a view legitimately rendering NOTHING (nil) stays silent on
            both walks — the warn predicate is non-NIL, not non-element"
    (is (= 0 (reagent-warned? nil)) "Reagent walk silent on nil")
    (is (= 0 (spine-warned? nil))   "spine walk silent on nil")))

(deftest dom-tag-root-is-silent-on-reagent-walk
  (testing "a real DOM-tag-rooted hiccup is annotated, not warned (Reagent
            walk control case)"
    (is (= 0 (reagent-warned? [:div "hi"]))
        "Reagent walk does not warn on a :div root")))

(deftest fn-headed-vector-warns-on-reagent-walk
  (testing "a fn/component-headed vector still warns on the Reagent walk
            (the case that ALWAYS warned — regression guard that unifying
            the predicate did not drop the original trigger)"
    (is (= 1 (reagent-warned? [(fn [] [:div]) "child"]))
        "Reagent walk still warns on a fn-headed vector")))
