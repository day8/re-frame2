(ns day8.re-frame2-xray.theme.a11y-cljs-test
  "Pure-data tests for the shared a11y helper (rf2-7389r).

  The helper extracts the WAI-ARIA dialog contract + a full modal-
  focus `:ref` callback (`dialog-ref` — capture-on-open, Tab trap,
  restore-on-close) shared by Xray's six modal surfaces. Tests cover:

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
            [day8.re-frame2-xray.test-helpers.popout-document :as popout-document]
            [day8.re-frame2-xray.theme.a11y :as a11y]))

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

;; ---- dialog-ref reads the DIALOG's own document (rf2-3x7nj.25.5) ---------
;;
;; In pop-out mode the modal is painted into the pop-out's document by code
;; running in the OPENER's realm, where `js/document` is the opener's. Its
;; `activeElement` is never one of the pop-out dialog's controls, so a trap
;; reading it saw every Tab as focus-outside-the-cycle and pulled focus back
;; to the FIRST control: no control past the first was keyboard-reachable.
;; These rows stub the opener as the global document and build the dialog in
;; a SECOND document, so a trap reading the global cannot pass.

(defn- stub-el
  "A focusable stub element in `doc`: focusing it makes it `doc`'s
  `activeElement`."
  [^js doc label]
  (let [el (js-obj "label" label)]
    (set! (.-focus el) (fn [] (set! (.-activeElement doc) el) nil))
    el))

(defn- stub-dialog
  "A stub dialog node in `doc` holding `controls`: `querySelector` /
  `querySelectorAll` answer them in order, `contains` answers
  membership, and `addEventListener` records the keydown handler into
  `handler*`."
  [^js doc controls handler*]
  (let [node (js-obj "ownerDocument" doc)]
    (set! (.-querySelector node) (fn [_sel] (first controls)))
    (set! (.-querySelectorAll node)
          (fn [_sel] (js-obj "length" (count controls)
                             "item"   (fn [i] (nth controls i)))))
    (set! (.-contains node) (fn [el] (boolean (some #(identical? % el) controls))))
    (set! (.-focus node) (fn [] (set! (.-activeElement doc) node) nil))
    (set! (.-addEventListener node)
          (fn [ev-name h] (when (= "keydown" ev-name) (reset! handler* h)) nil))
    (set! (.-removeEventListener node)
          (fn [_ev-name _h] (reset! handler* nil) nil))
    node))

(defn- press-tab!
  "Hand the recorded keydown handler a Tab (Shift+Tab when `shift?`).
  Answers true when the trap intercepted it (called `preventDefault`)."
  [handler* shift?]
  (let [prevented? (atom false)]
    (when-let [h @handler*]
      (h (js-obj "key"            "Tab"
                 "shiftKey"       shift?
                 "preventDefault" (fn [] (reset! prevented? true) nil))))
    @prevented?))

(deftest dialog-ref-traps-tab-in-the-dialogs-own-document
  (testing "rf2-3x7nj.25.5 — a pop-out modal's trap reads focus from the
            DIALOG's document, so a mid-cycle Tab is left to the browser
            and only the boundaries wrap"
    (popout-document/with-opener-globals
      (fn [{opener-doc :doc}]
        (let [{pdoc :doc} (popout-document/mk-document)
              ;; The host page has its own focused element, in the OPENER's
              ;; document — the value a trap reading `js/document` sees.
              host-el   (stub-el opener-doc "host")
              _         (.focus host-el)
              [b0 b1 b2 :as controls] (mapv #(stub-el pdoc %) ["b0" "b1" "b2"])
              handler*  (atom nil)
              dialog    (stub-dialog pdoc controls handler*)
              ref       (a11y/dialog-ref)]
          (ref dialog)
          (is (identical? b0 (.-activeElement pdoc))
              "mount landed focus on the first control, in the pop-out")
          (.focus b1)
          (is (false? (press-tab! handler* false))
              "Tab from the MIDDLE control is not intercepted — the browser
               moves focus on (the opener-document read pinned it to b0)")
          (is (identical? b1 (.-activeElement pdoc))
              "and focus was not dragged back to the first control")
          (is (false? (press-tab! handler* true))
              "nor is Shift+Tab from the middle")
          (.focus b2)
          (is (true? (press-tab! handler* false))
              "Tab from the LAST control is intercepted")
          (is (identical? b0 (.-activeElement pdoc))
              "and wraps to the first")
          (is (identical? host-el (.-activeElement opener-doc))
              "the host page's focus was never touched"))))))

(deftest dialog-ref-restores-focus-in-the-dialogs-own-document
  (testing "rf2-3x7nj.25.5 — the opener captured at mount, and the body the
            restore checks, are the DIALOG's document's: closing a pop-out
            modal returns focus to the pop-out control that opened it"
    (popout-document/with-opener-globals
      (fn [{opener-doc :doc opener-contents :contents}]
        (let [{pdoc :doc pcontents :contents} (popout-document/mk-document)
              host-el      (stub-el opener-doc "host")
              opener-btn   (stub-el pdoc "settings-button")
              controls     [(stub-el pdoc "b0")]
              handler*     (atom nil)
              dialog       (stub-dialog pdoc controls handler*)
              ref          (a11y/dialog-ref)]
          (swap! opener-contents conj host-el)
          (swap! pcontents conj opener-btn)
          (.focus host-el)
          (.focus opener-btn)
          (ref dialog)
          (is (identical? (first controls) (.-activeElement pdoc))
              "precondition: the modal took focus")
          (ref nil)
          (is (identical? opener-btn (.-activeElement pdoc))
              "unmount restored focus to the pop-out control that opened the
               modal"))))))
