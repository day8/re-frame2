(ns day8.re-frame2-xray.theme.a11y-cljs-test
  "DOM-free tests for the shared a11y helper: the dialog ARIA attrs, the
  Tab-wrap math, and `dialog-ref` reading the dialog's own document in
  pop-out mode. The live-DOM focus contract is exercised by
  `theme.a11y-dom-cljs-test` (browser-test build)."
  (:require [cljs.test :refer-macros [are deftest is testing]]
            [day8.re-frame2-xray.test-helpers.popout-document :as popout-document]
            [day8.re-frame2-xray.theme.a11y :as a11y]))

;; ---- dialog-attrs --------------------------------------------------------

(deftest dialog-attrs-names-the-dialog-by-heading-else-label
  (testing "a visible heading (`:labelled-by`) names the dialog and drops
            `:label`; without one, `:label` is the accessible name"
    (are [opts expected] (= expected (a11y/dialog-attrs opts))
      {:label "fallback" :labelled-by "the-heading-id"}
      {:role "dialog" :aria-modal "true" :aria-labelledby "the-heading-id"}

      {:label "Command palette"}
      {:role "dialog" :aria-modal "true" :aria-label "Command palette"})))

;; ---- trap-wrap-target (pure) ---------------------------------------------
;;
;; `:a` / `:b` / `:c` stand in for DOM elements — `trap-wrap-target` only
;; ever compares them by identity, so plain keywords exercise the math.

(deftest trap-wrap-target-wraps-only-at-the-boundaries
  (testing "the element Tab (or Shift+Tab) moves focus to, or nil where the
            browser's native Tab handles the move"
    (are [focusables current shift? expected]
         (= expected (a11y/trap-wrap-target focusables current shift?))
      ;; mid-cycle: no wrap needed, either direction
      [:a :b :c] :b        false nil
      [:a :b :c] :b        true  nil
      ;; Tab off the LAST wraps to the FIRST; Shift+Tab off the FIRST to the LAST
      [:a :b :c] :c        false :a
      [:a :b :c] :a        true  :c
      ;; focus OUTSIDE the cycle (the tab-index=-1 root): Tab pulls to the
      ;; first, Shift+Tab to the last, so the trap re-captures focus
      [:a :b :c] :root     false :a
      [:a :b :c] :root     true  :c
      ;; one focusable is both first and last
      [:only]    :only     false :only
      [:only]    :only     true  :only
      ;; no focusables: nil, and the caller pins focus on the dialog root
      []         :anything false nil)))

;; ---- dialog-ref reads the DIALOG's own document --------------------------
;;
;; In pop-out mode the modal is painted into the pop-out's document by code
;; running in the OPENER's realm, where `js/document` is the opener's. Its
;; `activeElement` is never one of the pop-out dialog's controls, so a trap
;; reading it would see every Tab as focus-outside-the-cycle and pull focus
;; back to the FIRST control: no control past the first would be
;; keyboard-reachable.
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
  (testing "a pop-out modal's trap reads focus from the
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
               moves focus on (an opener-document read would pin it to b0)")
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
  (testing "the opener captured at mount, and the body the
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
