(ns day8.re-frame2-causa.theme.a11y-cljs-test
  "Pure-data tests for the shared a11y helper (rf2-7389r).

  The helper extracts the WAI-ARIA dialog contract + a focus-on-mount
  `:ref` callback shared by Causa's six modal surfaces. Tests cover:

    1. `dialog-attrs` shape — role + aria-modal always present;
       labelled-by preferred over label; describedby attaches when set.
    2. `focus-on-mount-ref` returns a fn (callback ref) that is a no-op
       on unmount (`nil` node).

  The interactive `.focus()` behaviour is exercised indirectly via the
  modal integration tests (see `modals.aria-cljs-test`)."
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

;; ---- focus-on-mount-ref ---------------------------------------------------

(deftest focus-on-mount-ref-returns-a-function
  (testing "the factory yields a React :ref callback (a fn)"
    (let [ref (a11y/focus-on-mount-ref)]
      (is (fn? ref)))))

(deftest focus-on-mount-ref-tolerates-nil-unmount
  (testing "rf2-7389r — React invokes the ref with `nil` on unmount;
            the callback must short-circuit so it never throws after
            the modal closes (e.g. backdrop click while focus was
            pending)"
    (let [ref (a11y/focus-on-mount-ref)]
      (is (nil? (ref nil))
          "calling with nil returns nil and does not throw"))))

(deftest focus-on-mount-ref-focuses-first-focusable-child
  (testing "rf2-7389r — when a real DOM node mounts, the ref focuses
            the first focusable descendant (matches the WAI-ARIA APG
            dialog pattern: focus lands inside the dialog)"
    (when (exists? js/document)
      (let [host (.createElement js/document "div")
            input (.createElement js/document "input")
            btn   (.createElement js/document "button")]
        (set! (.-tabIndex host) -1)
        (.appendChild host input)
        (.appendChild host btn)
        (.appendChild (.-body js/document) host)
        (try
          (let [ref (a11y/focus-on-mount-ref)]
            (ref host)
            (is (= input (.-activeElement js/document))
                "first focusable child receives focus (the <input>)"))
          (finally
            (.removeChild (.-body js/document) host)))))))

(deftest focus-on-mount-ref-falls-back-to-root-when-no-focusable
  (testing "rf2-7389r — when the dialog has no focusable child (e.g.
            empty-state mute manager), the dialog root itself receives
            focus via tabindex=-1. Keeps Esc / arrow-keys functional."
    (when (exists? js/document)
      (let [host (.createElement js/document "div")]
        (set! (.-tabIndex host) -1)
        (.appendChild (.-body js/document) host)
        (try
          (let [ref (a11y/focus-on-mount-ref)]
            (ref host)
            (is (= host (.-activeElement js/document))
                "the dialog root receives focus as fallback"))
          (finally
            (.removeChild (.-body js/document) host)))))))
