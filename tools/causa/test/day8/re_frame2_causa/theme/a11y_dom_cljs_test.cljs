(ns day8.re-frame2-causa.theme.a11y-dom-cljs-test
  "Live-DOM tests for the modal focus contract `a11y/dialog-ref`
  (rf2-tpn0u): focus capture on open, the Tab/Shift+Tab focus TRAP, and
  focus RESTORE to the opener on close.

  These exercise behaviour Node can't fake — `document.activeElement`,
  real `addEventListener('keydown', …)` on a DOM node, and synthetic
  `KeyboardEvent` dispatch. The filename ends in `_dom_cljs_test.cljs`
  so it runs under the `:browser-test` build (real DOM via Chromium) per
  `implementation/shadow-cljs.edn` (rf2-2hrj8). Under the `:node-test`
  build the `cljs-test$` regex also matches this ns, but every test
  short-circuits via `(when (exists? js/document) …)` so the suite stays
  green on Node and runs fully under Chromium.

  The pure Tab-wrap math (`trap-wrap-target`) and the no-throw factory
  contracts live in the DOM-free `theme.a11y-cljs-test`."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-causa.theme.a11y :as a11y]))

;; ---- DOM scaffolding -----------------------------------------------------

(defn- mk
  "Create an element of `tag`; return it (not yet attached)."
  [tag]
  (.createElement js/document tag))

(defn- mk-dialog!
  "Build a dialog host with `n` <button> children (labelled b0..b(n-1)),
  a tab-index=-1 root, attached under document.body. Also creates +
  attaches a sibling `opener` <button> outside the dialog and focuses
  it (simulating the ribbon button that opened the modal).

  Returns `{:dialog dialog :buttons [..] :opener opener :cleanup fn}`."
  [n]
  (let [opener  (mk "button")
        dialog  (mk "div")
        buttons (vec (for [i (range n)]
                       (let [b (mk "button")]
                         (set! (.-textContent b) (str "b" i))
                         b)))]
    (set! (.-tabIndex dialog) -1)
    (doseq [b buttons] (.appendChild dialog b))
    (.appendChild (.-body js/document) opener)
    (.appendChild (.-body js/document) dialog)
    (.focus opener)
    {:dialog  dialog
     :buttons buttons
     :opener  opener
     :cleanup (fn []
                (when (.contains (.-body js/document) opener)
                  (.removeChild (.-body js/document) opener))
                (when (.contains (.-body js/document) dialog)
                  (.removeChild (.-body js/document) dialog)))}))

(defn- press-tab!
  "Dispatch a synthetic Tab (or Shift+Tab) keydown on `el`. The bubbling
  flag is true so the dialog node's listener (installed by dialog-ref)
  catches it the same way a real Tab does."
  ([el] (press-tab! el false))
  ([el shift?]
   (let [ev (js/KeyboardEvent. "keydown"
                               #js {:key      "Tab"
                                    :shiftKey shift?
                                    :bubbles  true
                                    :cancelable true})]
     (.dispatchEvent el ev))))

;; ---- focus capture on open -----------------------------------------------

(deftest dialog-ref-focuses-first-focusable-on-open
  (testing "rf2-tpn0u — mounting the dialog ref lands focus on the first
            focusable descendant (WAI-ARIA APG dialog pattern)"
    (when (exists? js/document)
      (let [{:keys [dialog buttons cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (is (= (nth buttons 0) (.-activeElement js/document))
              "first <button> receives focus on open")
          (finally (ref nil) (cleanup)))))))

(deftest dialog-ref-falls-back-to-root-when-no-focusable
  (testing "rf2-tpn0u — an empty-state dialog (no focusable child) lands
            focus on the tab-index=-1 root so Esc / arrow-keys still work"
    (when (exists? js/document)
      (let [{:keys [dialog cleanup]} (mk-dialog! 0)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (is (= dialog (.-activeElement js/document))
              "dialog root receives focus as fallback")
          (finally (ref nil) (cleanup)))))))

(deftest dialog-ref-respects-pre-focused-descendant
  (testing "rf2-tpn0u — when a child already holds focus on mount (an
            :auto-focus input like the edit-popup pattern field), the ref
            leaves it there rather than stealing focus to the first
            focusable"
    (when (exists? js/document)
      (let [{:keys [dialog buttons cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          ;; pre-focus the SECOND button (stand-in for :auto-focus)
          (.focus (nth buttons 1))
          (ref dialog)
          (is (= (nth buttons 1) (.-activeElement js/document))
              "pre-focused descendant keeps focus on open")
          (finally (ref nil) (cleanup)))))))

;; ---- focus trap ----------------------------------------------------------

(deftest dialog-ref-traps-tab-off-last-to-first
  (testing "rf2-tpn0u — Tab from the LAST focusable wraps to the FIRST
            instead of leaking to the chrome beneath"
    (when (exists? js/document)
      (let [{:keys [dialog buttons cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (.focus (nth buttons 2))           ;; last
          (press-tab! (nth buttons 2) false) ;; Tab
          (is (= (nth buttons 0) (.-activeElement js/document))
              "Tab off the last button wraps to the first")
          (finally (ref nil) (cleanup)))))))

(deftest dialog-ref-traps-shift-tab-off-first-to-last
  (testing "rf2-tpn0u — Shift+Tab from the FIRST focusable wraps to the
            LAST"
    (when (exists? js/document)
      (let [{:keys [dialog buttons cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (.focus (nth buttons 0))          ;; first
          (press-tab! (nth buttons 0) true) ;; Shift+Tab
          (is (= (nth buttons 2) (.-activeElement js/document))
              "Shift+Tab off the first button wraps to the last")
          (finally (ref nil) (cleanup)))))))

(deftest dialog-ref-empty-dialog-pins-tab-on-root
  (testing "rf2-tpn0u — with no focusable child, Tab cannot escape: the
            trap pins focus back on the tab-index=-1 dialog root"
    (when (exists? js/document)
      (let [{:keys [dialog cleanup]} (mk-dialog! 0)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (.focus dialog)
          (press-tab! dialog false)
          (is (= dialog (.-activeElement js/document))
              "Tab keeps focus on the empty dialog root")
          (finally (ref nil) (cleanup)))))))

;; ---- focus restore on close ----------------------------------------------

(deftest dialog-ref-restores-focus-to-opener-on-close
  (testing "rf2-tpn0u — unmounting (ref nil) restores focus to the
            element that had it before the dialog opened (the opener),
            not <body>"
    (when (exists? js/document)
      (let [{:keys [dialog opener cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          ;; opener already focused by mk-dialog!
          (is (= opener (.-activeElement js/document))
              "precondition: opener holds focus before open")
          (ref dialog)                        ;; open → focus moves inside
          (is (not= opener (.-activeElement js/document))
              "focus moved into the dialog on open")
          (ref nil)                           ;; close
          (is (= opener (.-activeElement js/document))
              "focus restored to the opener on close")
          (finally (cleanup)))))))

(deftest dialog-ref-skips-restore-when-opener-detached
  (testing "rf2-tpn0u — if the opener was removed from the document while
            the dialog was open, close must not throw (and must not
            re-focus a detached node)"
    (when (exists? js/document)
      (let [{:keys [dialog opener cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          ;; opener leaves the document while the modal is open
          (.removeChild (.-body js/document) opener)
          (is (nil? (ref nil))
              "close with a detached opener returns nil and does not throw")
          (finally (cleanup)))))))

(deftest dialog-ref-teardown-removes-trap-listener
  (testing "rf2-tpn0u — after close, the keydown trap listener is gone:
            a Tab on the (now-detached) former dialog node does not move
            focus via the trap"
    (when (exists? js/document)
      (let [{:keys [dialog buttons opener cleanup]} (mk-dialog! 3)
            ref (a11y/dialog-ref)]
        (try
          (ref dialog)
          (ref nil)                           ;; close → listener removed
          ;; opener is focused again post-restore; a Tab dispatched on
          ;; the old dialog node must not be intercepted by a stale trap.
          (.focus opener)
          (press-tab! dialog false)
          (is (= opener (.-activeElement js/document))
              "no stale trap listener fires after close")
          (finally (cleanup)))))))
