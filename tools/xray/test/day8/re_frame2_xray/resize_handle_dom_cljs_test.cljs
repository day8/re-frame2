(ns day8.re-frame2-xray.resize-handle-dom-cljs-test
  "Browser-lane half of the resize-handle suite, promoted out of
  `day8.re-frame2-xray.resize-handle-cljs-test` under rf2-r51p.

  ## What was wrong, and it was the file's LOCATION rather than its guard

  The eleven assertions below were wrapped in `(when (exists? js/document)
  ...)` inside a namespace ending `-cljs-test`. `:node-test` selected that
  namespace and the guard was false there — this repo ships no jsdom, no
  happy-dom and no DOM shim in any dependency list, so `js/document` is
  simply undefined under Node — while `:browser-test`, whose `:ns-regexp`
  is `.*-dom-cljs-test$`, never loaded the file at all. The rows therefore
  executed in NEITHER lane. Their first run is here.

  The sibling `resize_handle_boundary_dom_cljs_test` recorded the same
  finding from the other side and measured it rather than inferring it: a
  deliberate failure planted inside those `when` bodies leaves
  `npm run test:cljs` green.

  ## THE GUARD STAYS, BECAUSE THIS FILE RUNS ON BOTH LANES

  `:node-test`'s `:ns-regexp` is `cljs-test$` — a bare SUFFIX match, which
  `-dom-cljs-test` satisfies exactly as `-cljs-test` does. So the node
  build loads this namespace too, and `implementation/shadow-cljs.edn`
  records above `:browser-test` that this overlap is deliberate: the
  DOM-tagged files run on BOTH targets so their cross-runtime asserts keep
  firing in Node. Moving a row here ADDS the browser lane; it removes
  nothing.

  ## THE SKIP BRANCH ASSERTS RATHER THAN VANISHING

  A bare `(when ...)` body would leave the node lane holding a deftest with
  ZERO assertions — the hollow shape rf2-r51p exists to remove, relocated
  rather than fixed. Each row below answers the node lane with a visible
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
  declaration IS the behaviour under test.

  A failure here is evidence about `host-asserts-own-handle?` and
  `apply-panel-width!` arriving for the first time, not a regression
  introduced by the move."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [day8.re-frame2-xray.config :as config]
            [day8.re-frame2-xray.registry :as registry]
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

(defn- setup! []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- browser?
  "True only under the real-DOM `:browser-test` build. The `:node-test`
  build loads this ns and has no `js/document` — and no jsdom either,
  which is why these rows never executed before this file existed."
  []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

;; ---- the handle's own markup (rf2-k97c.3) -------------------------------
;;
;; `handle-tree` is the boundary's PURE inner fn. Driving it directly is
;; what a test can assert without a React render window: `handle-view` is a
;; Fresco boundary and its `rf.fresco/sub` is legal only inside one.

(defn- handle-markup
  "The handle's node as `handle-view` composes it.

  The DISPATCHER is `(:dispatch (rf/capture-frame))`, the same door the
  boundary uses — not `rf/dispatch`."
  []
  (resize-handle/handle-tree
    @(rf/subscribe [:rf.xray/panel-width-px])
    (resize-handle/aria-max-panel-width-px)
    (:dispatch (rf/capture-frame))))

;; ---- yield-to-consumer (rf2-70u8q) -------------------------------------

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

(deftest host-without-resize-does-not-yield
  (testing "rf2-70u8q — the zero-config consumer drops
            `<aside data-rf-xray-host></aside>` with no explicit `resize:`
            declaration, so Xray renders its own handle (auto-inject)"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! nil)]
        (try
          (is (false? (resize-handle/host-asserts-own-handle?))
              "no explicit resize → no yield → Xray handle renders")
          (finally
            (remove-stub-host! host)))))))

(deftest host-with-resize-horizontal-yields
  (testing "rf2-70u8q — the consumer asserts their own browser-native
            handle with `resize: horizontal`; Xray MUST yield to avoid a
            double handle"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! "horizontal")]
        (try
          (is (true? (resize-handle/host-asserts-own-handle?))
              "explicit resize:horizontal → yield → Xray renders nil")
          (finally
            (remove-stub-host! host)))))))

(deftest host-with-resize-both-yields
  (testing "rf2-70u8q — `resize: both` also gives the consumer a
            browser-native handle (covers a future vertical-resize use
            case too), so Xray yields"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! "both")]
        (try
          (is (true? (resize-handle/host-asserts-own-handle?))
              "explicit resize:both → yield → Xray renders nil")
          (finally
            (remove-stub-host! host)))))))

;; rf2-k97c.3 — THE YIELD GATE LIVES IN `handle-view`, THE BOUNDARY, and
;; deliberately not in the `Handle` bridge beside the mode gate. The
;; bridge is scaffolding with a defined end: when `shell-view` becomes a
;; boundary, `Handle` is DELETED. A spec'd product behaviour (rf2-70u8q)
;; parked there would be deleted with it, silently.
;;
;; The two rows below assert the PREDICATE the boundary gates on, plus the
;; markup it gates. The COMPOSITION of the two — the gate driving a real
;; mount — is `resize_handle_boundary_dom_cljs_test`'s W3.

(deftest handle-yields-when-host-asserts-own-handle
  (testing "rf2-k97c.3 / rf2-70u8q — the yield path: `handle-view`'s gate
            short-circuits, so the boundary renders nil and the page
            carries exactly one handle (the consumer's)"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! "horizontal")]
        (try
          (setup!)
          (is (true? (resize-handle/host-asserts-own-handle?))
              "yield path: the gate `handle-view` short-circuits on is true,
               so the boundary renders nil and the page carries one handle")
          (finally
            (remove-stub-host! host)))))))

(deftest handle-renders-when-host-does-not-yield
  (testing "rf2-k97c.3 / rf2-70u8q — the no-yield path: the zero-config
            consumer declares no `resize` at all, the gate is false, and
            the markup the boundary then composes is the documented node"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [host (ensure-stub-host! nil)]
        (try
          (setup!)
          (rf/with-frame :rf/xray
            (is (false? (resize-handle/host-asserts-own-handle?))
                "no-yield path: the boundary's gate is false, so it renders")
            (let [tree (handle-markup)]
              (is (some? tree)
                  "and the markup it then composes is present")
              (is (= "rf-xray-resize-handle" (:data-testid (second tree)))
                  "the rendered tree is the documented handle node")))
          (finally
            (remove-stub-host! host)))))))

;; ---- apply-panel-width! (CSS var write) --------------------------------

(defn- html-root []
  (when (browser?) (.-documentElement js/document)))

(deftest apply-panel-width-writes-css-var-on-html
  (testing "rf2-6fqr5 — the width lands as an inline custom property on
            `<html>`, so the cascade resolves it at the host through
            `var(--rf-xray-inline-width, ...)` inheritance"
    (if-not (browser?)
      (is true "skipped: no DOM (node lane — see ns docstring)")
      (let [html (html-root)]
        ;; BIND, ASSERT PRESENT, then reach through. The original row was
        ;; `(when-let [html ...] (is ...))`, which fails OPEN: a nil root
        ;; silently skips the assertion rather than reporting one, which
        ;; is the same shape that let this row die unnoticed.
        (is (some? html) "precondition: a real <html> root to write to")
        (settings-effects/apply-panel-width! 700)
        (is (= "700px"
               (some-> html .-style (.getPropertyValue "--rf-xray-inline-width")))
            "<html> CSS var carries the value so the cascade resolves")))))

(deftest apply-panel-width-does-not-pin-host-inline-style
  (testing "rf2-6fqr5 — an earlier draft wrote the custom property as an
            INLINE style on the layout host as well as on `<html>`. Inline
            declarations beat any selector-based rule, so a consumer's
            `:root { --rf-xray-inline-width: 720px; }` was silently
            shadowed. Xray MUST NOT write the property to the host; the
            host inherits it from `<html>`."
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
  (testing "rf2-6fqr5 — when the user has NOT explicitly resized (the value
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
