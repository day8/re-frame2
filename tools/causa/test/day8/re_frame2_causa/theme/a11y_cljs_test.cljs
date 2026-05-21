(ns day8.re-frame2-causa.theme.a11y-cljs-test
  "Pure-data tests for the shared a11y helper (rf2-7389r).

  The helper extracts the WAI-ARIA dialog contract + a full modal-
  focus `:ref` callback (`dialog-ref` — capture-on-open, Tab trap,
  restore-on-close) shared by Causa's six modal surfaces. Tests cover:

    1. `dialog-attrs` shape — role + aria-modal always present;
       labelled-by preferred over label; describedby attaches when set.
    2. `trap-wrap-target` — the pure Tab-wrap math (JVM/node-runnable):
       wrap at the boundaries, no-op mid-cycle, pull-in when focus is
       outside the cycle, single-focusable wraps to itself.
    3. `dialog-ref` returns a fn (callback ref) that is a no-op on
       unmount (`nil` node) and tolerates a DOM-less runtime.

  The live `.focus()` capture / trap / restore behaviour against a real
  DOM is exercised by `theme.a11y-dom-cljs-test` (browser-test build)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.a11y :as a11y]))

;; ---- dialog-attrs --------------------------------------------------------

(deftest dialog-attrs-sets-role-and-aria-modal
  (testing "every dialog gets role=\"dialog\" + aria-modal=\"true\"
            regardless of which name is used"
    (let [attrs (a11y/dialog-attrs {:label "Demo"})]
      (is (= "dialog" (:role attrs)))
      (is (= "true"   (:aria-modal attrs))))))

(deftest dialog-attrs-prefers-labelledby-over-label
  (testing "rf2-7389r — when both :labelled-by and :label are
            supplied, :aria-labelledby wins and :aria-label is dropped
            so the heading text drives the accessible name (the
            preferred a11y pattern)"
    (let [attrs (a11y/dialog-attrs {:label       "fallback"
                                    :labelled-by "the-heading-id"})]
      (is (= "the-heading-id" (:aria-labelledby attrs)))
      (is (nil? (:aria-label attrs))
          ":aria-label is dropped in favour of :aria-labelledby"))))

(deftest dialog-attrs-falls-back-to-label
  (testing "without a heading id, :aria-label provides the accessible
            name. The palette modal uses this path (no visible title)."
    (let [attrs (a11y/dialog-attrs {:label "Command palette"})]
      (is (= "Command palette" (:aria-label attrs)))
      (is (nil? (:aria-labelledby attrs))))))

(deftest dialog-attrs-attaches-describedby
  (testing "optional :describedby points at a description element id
            for verbose modals"
    (let [attrs (a11y/dialog-attrs {:label "Demo"
                                    :describedby "demo-desc"})]
      (is (= "demo-desc" (:aria-describedby attrs))))))

(deftest dialog-attrs-without-name-still-yields-role-and-modal
  (testing "a degenerate {} input still produces a syntactically valid
            dialog attribute map (caller may add the name separately)"
    (let [attrs (a11y/dialog-attrs {})]
      (is (= "dialog" (:role attrs)))
      (is (= "true"   (:aria-modal attrs)))
      (is (nil? (:aria-label attrs)))
      (is (nil? (:aria-labelledby attrs))))))

;; ---- trap-wrap-target (pure) ---------------------------------------------
;;
;; `:a` / `:b` / `:c` stand in for DOM elements — `trap-wrap-target` only
;; ever compares them by identity, so plain keywords exercise the math.

(deftest trap-wrap-target-no-op-mid-cycle
  (testing "rf2-tpn0u — Tab from a non-boundary focusable returns nil so
            the browser's native Tab handles the move (no wrap needed)"
    (is (nil? (a11y/trap-wrap-target [:a :b :c] :b false))
        "Tab from the middle element → nil")
    (is (nil? (a11y/trap-wrap-target [:a :b :c] :b true))
        "Shift+Tab from the middle element → nil")))

(deftest trap-wrap-target-tab-off-last-wraps-to-first
  (testing "rf2-tpn0u — Tab from the LAST focusable wraps to the FIRST"
    (is (= :a (a11y/trap-wrap-target [:a :b :c] :c false)))))

(deftest trap-wrap-target-shift-tab-off-first-wraps-to-last
  (testing "rf2-tpn0u — Shift+Tab from the FIRST focusable wraps to the
            LAST"
    (is (= :c (a11y/trap-wrap-target [:a :b :c] :a true)))))

(deftest trap-wrap-target-pulls-in-when-focus-outside-cycle
  (testing "rf2-tpn0u — when focus is OUTSIDE the focusable set (e.g. on
            the tab-index=-1 dialog root), Tab pulls to the first +
            Shift+Tab pulls to the last so the trap re-captures focus"
    (is (= :a (a11y/trap-wrap-target [:a :b :c] :root false)))
    (is (= :c (a11y/trap-wrap-target [:a :b :c] :root true)))))

(deftest trap-wrap-target-single-focusable-wraps-to-itself
  (testing "rf2-tpn0u — with one focusable, it is both first and last,
            so any Tab keeps focus pinned on it"
    (is (= :only (a11y/trap-wrap-target [:only] :only false)))
    (is (= :only (a11y/trap-wrap-target [:only] :only true)))))

(deftest trap-wrap-target-empty-is-nil
  (testing "rf2-tpn0u — no focusables → nil (caller pins focus on the
            dialog root instead)"
    (is (nil? (a11y/trap-wrap-target [] :anything false)))))

;; ---- dialog-ref factory --------------------------------------------------

(deftest dialog-ref-returns-a-function
  (testing "the factory yields a React :ref callback (a fn)"
    (let [ref (a11y/dialog-ref)]
      (is (fn? ref)))))

(deftest dialog-ref-tolerates-nil-unmount
  (testing "rf2-tpn0u — React invokes the ref with `nil` on unmount;
            the callback must short-circuit so it never throws after the
            modal closes (e.g. backdrop click before any mount)"
    (let [ref (a11y/dialog-ref)]
      (is (nil? (ref nil))
          "calling with nil returns nil and does not throw"))))
