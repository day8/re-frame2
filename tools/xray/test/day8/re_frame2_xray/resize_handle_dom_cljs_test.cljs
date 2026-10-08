(ns day8.re-frame2-xray.resize-handle-dom-cljs-test
  "Browser-lane half of the resize-handle suite.

  ## Why these rows live in a `-dom-cljs-test` namespace

  Every row below needs a real DOM. `:browser-test`'s `:ns-regexp` is
  `.*-dom-cljs-test$`, and under `:node-test` `js/document` is simply
  undefined — this repo ships no jsdom, no happy-dom and no DOM shim in
  any dependency list. Rows guarded by `(when (exists? js/document) ...)`
  inside a namespace ending only `-cljs-test` would therefore execute in
  NEITHER lane.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does. So the node
  build loads this namespace too, and `implementation/shadow-cljs.edn`
  records above `:browser-test` that this overlap is deliberate: the
  DOM-tagged files run on BOTH targets so their cross-runtime asserts keep
  firing in Node. A row here gains the browser lane and keeps the node
  lane.

  ## THE SKIP BRANCH ASSERTS RATHER THAN VANISHING

  A bare `(when ...)` body would leave the node lane holding a deftest with
  ZERO assertions — a hollow row. Each row below answers the node lane with a visible
  marker row instead, so the skip is legible in the node summary and no
  deftest here holds nothing.

  ## Scope, against the two neighbouring files

  `resize_handle_cljs_test` keeps everything that needs no host: the pure
  `handle-tree` markup, the drag lifecycle, write-time clamping, the
  keyboard rows and the subscription. `resize_handle_boundary_dom_cljs_test`
  owns the Fresco BOUNDARY — real React mounts through the `Handle` bridge.
  This file sits between them: the yield PREDICATE read off a real computed
  style, and `apply-panel-width!`'s writes to a real `<html>`. Neither can
  be stubbed, because `getComputedStyle` resolving an inline `resize:`
  declaration IS the behaviour under test."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.resize-handle :as resize-handle]
            [day8.re-frame2-xray.settings.effects :as settings-effects]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  ;; Mirrors the sibling's fixture. `:post-reset` force-clears the
  ;; module-level drag-state defonce (it survives the runtime reset) so no
  ;; stale drag leaks between tests.
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :post-reset (fn [] (resize-handle/simulate-up!))}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns and has no `js/document` — and no jsdom either,
  which is why these rows need the browser lane."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- yield-to-consumer -------------------------------------------------

(defn- ensure-stub-host!
  "Attach `<aside data-rf-xray-host>` to the page, optionally declaring
  `resize: <resize-value>` as an inline style. `getComputedStyle` — which
  is what `host-asserts-own-handle?` probes — resolves that declaration,
  and nothing short of a real browser does."
  [resize-value]
  (when (browser?)
    ;; Remove any prior host so the test is hermetic.
    (when-let [old (.querySelector js/document "[data-rf-xray-host]")]
      (when (.-parentNode old)
        (.removeChild (.-parentNode old) old)))
    (let [host (.createElement js/document "aside")]
      (.setAttribute host "data-rf-xray-host" "")
      (when resize-value
        (set! (-> host .-style .-resize) resize-value))
      (when (.-body js/document)
        (.appendChild (.-body js/document) host))
      host)))

(defn- remove-stub-host! [host]
  (when (and host (.-parentNode host))
    (.removeChild (.-parentNode host) host)))

(deftest host-yields-only-to-an-explicit-horizontal-or-both-resize
  ;; A zero-config `<aside data-rf-xray-host>` gets Xray's own handle; a
  ;; consumer declaring a browser-native `resize:` handle gets a yield, so
  ;; the panel never shows a double handle.
  (if-not (browser?)
    (is true "skipped: no DOM (node lane — see ns docstring)")
    (is (= [false true true]
           (mapv (fn [resize-value]
                   (let [host (ensure-stub-host! resize-value)]
                     (try
                       (resize-handle/host-asserts-own-handle?)
                       (finally
                         (remove-stub-host! host)))))
                 [nil "horizontal" "both"]))
        "no declaration renders the handle; horizontal and both yield")))

;; THE YIELD GATE LIVES IN `handle-view`, THE BOUNDARY, and
;; deliberately not in the `Handle` bridge beside the mode gate. The
;; bridge is scaffolding with a defined end: when `shell-view` becomes a
;; boundary, `Handle` is DELETED. A spec'd product behaviour
;; parked there would be deleted with it, silently.
;;
;; The row above asserts the PREDICATE the boundary gates on; the
;; markup it gates is `resize_handle_cljs_test`'s, and the gate driving a
;; real mount is `resize_handle_boundary_dom_cljs_test`'s W3.

;; ---- apply-panel-width! (CSS var write) --------------------------------

(defn- html-root []
  (when (browser?) (.-documentElement js/document)))

(deftest apply-panel-width-does-not-pin-host-inline-style
  (testing "Xray MUST NOT write the custom property as an INLINE style
            on the layout host; the host inherits it from `<html>`. Inline
            declarations beat any selector-based rule, so a consumer's
            `:root { --rf-xray-inline-width: 720px; }` would be silently
            shadowed."
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! nil)
            html (html-root)]
        (try
          (settings-effects/apply-panel-width! 480)
          ;; The `""` below passes just as happily against a call that
          ;; wrote NOTHING ANYWHERE, so prove the write landed first. That
          ;; makes this row a claim about WHERE the value went rather than
          ;; about whether anything happened. 480 is not
          ;; `default-panel-width-px` (560), so it writes rather than clears.
          (is (= "480px"
                 (some-> html .-style (.getPropertyValue "--rf-xray-inline-width")))
              "precondition: the call really did write the property on <html>")
          (is (= "" (some-> host .-style (.getPropertyValue "--rf-xray-inline-width")))
              "host element MUST NOT carry the CSS var as an inline style")
          (finally
            (remove-stub-host! host)))))))

(deftest apply-panel-width-clears-html-inline-when-default
  (testing "when the user has NOT explicitly resized (the value
            still equals `default-panel-width-px`), `apply-panel-width!`
            MUST clear any prior inline declaration so the consumer's
            `:root` override, or the host CSS's `var(...)` fallback, wins"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [html (html-root)]
        (is (some? html) "precondition: a real <html> root to clear")
        ;; Prime an inline declaration first, so the empty read below is
        ;; evidence that the CLEAR path ran rather than evidence that
        ;; nothing was ever set.
        (.setProperty (.-style html) "--rf-xray-inline-width" "999px")
        (settings-effects/apply-panel-width! config/default-panel-width-px)
        (is (= "" (some-> html .-style (.getPropertyValue "--rf-xray-inline-width")))
            "default value MUST clear the inline `<html>` declaration")
        ;; nil arg also routes to the default and must clear.
        (.setProperty (.-style html) "--rf-xray-inline-width" "888px")
        (settings-effects/apply-panel-width! nil)
        (is (= "" (some-> html .-style (.getPropertyValue "--rf-xray-inline-width")))
            "nil arg defaults to the published width and clears the inline")))))
