(ns day8.re-frame2-xray.palette.dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Xray command palette
  (rf2-w8lxg). Sibling of rf2-smvvz's settings popup fix (PR #1465).

  ## The bug these tests defend against

  The palette modal mounts via `[rf/frame-provider {:frame :rf/xray}
  …]` in the shell. Subscribes inside the palette resolve through
  React context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/xray` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)`
  picks up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` /
  `:on-mouse-enter` fire LATER — after render commits and React has
  POPPED `_currentValue` back to the context's default
  (`:rf/default`). At click time the 3-tier frame resolution chain
  (dynamic var → React-context tier → `:rf/default`) falls all the
  way through, the dispatch lands on `:rf/default`'s router, and the
  `:rf.xray/palette-*` handler reduces `:rf/default`'s db — leaving
  Xray's palette slots untouched. Symptom: backdrop click does not
  close, arrow keys do not move the cursor, Esc does not close, input
  text does not update the query — the palette appears frozen.

  The fix in `palette/view.cljs` is mechanical: every `rf/dispatch`
  from a deferred handler carries `{:frame :rf/xray}` so the
  envelope's `:frame` is set at call time and never depends on the
  click-time React-context read.

  ## How these tests reproduce the click-time path

  Each test plucks the handler off the rendered hiccup and invokes
  it OUTSIDE any `with-frame` binding — simulating the browser's
  click-fires-after-render reality. Click handlers use queued
  `rf/dispatch` (not `dispatch-sync`); the router drain is async via
  `goog.async.nextTick`. Tests use `test-support/poll-until` to await
  the drain before asserting."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-helpers :as th]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.palette :as palette]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; `make-xray-runtime-fixture` (rf2-vj80u8) composes core
;; `make-reset-runtime-fixture` (snapshot/restore + frames-reset + adapter
;; dispose/install) with Xray's own reset tier. `:tier :runtime` folds the
;; sentinel + trace-collector + persisted-settings reset; `:async? true` is the
;; map-form cljs.test/async requires; `:post-reset` re-registers Xray's
;; :rf.xray/* handlers + the :rf/xray frame (rolled back with the per-test
;; registrar snapshot).
(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:tier       :runtime
     :async?     true
     :post-reset (fn []
                   (registry/register-xray-handlers!)
                   (rf/make-frame {:id :rf/xray}))}))

;; ---- hiccup walker ------------------------------------------------------
;; The private expand-tree / hiccup-seq copies were semantically identical to
;; `re-frame.test-helpers`; the walk delegates to `th/find-by-testid`
;; (rf2-vj80u8 — no Xray walker facade). The `with-frame` wrapper is retained
;; deliberately (NOT a walker difference):
(defn- find-by-testid [tree testid]
  ;; EP-0002 (rf2-bd4div): walking the rendered tree RE-INVOKES nested
  ;; component fns (`th/expand-tree`), and those render-time `rf/subscribe`s
  ;; resolve through the surrounding frame. The palette mounts in the
  ;; `:rf/xray` own-frame, so the expansion must run under that scope —
  ;; ambient (no-scope) re-expansion would raise `:rf.error/no-frame-context`
  ;; rather than falling through to a synthesised `:rf/default`. (The
  ;; click-time HANDLER is still invoked OUTSIDE any scope — that is the
  ;; real click-fires-after-render path under test.)
  (rf/with-frame :rf/xray
    (th/find-by-testid tree testid)))

;; ---- click-time helpers ------------------------------------------------

(defn- on-click [node] (:on-click (second node)))
(defn- on-key-down [node] (:on-key-down (second node)))
(defn- on-change [node] (:on-change (second node)))

(defn- fake-event []
  #js {:preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-key-event [key]
  #js {:key             key
       :ctrlKey         false
       :metaKey         false
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- fake-change-event [value]
  #js {:target          #js {:value value}
       :preventDefault  (fn [])
       :stopPropagation (fn [])})

(defn- await-xray-db
  "Poll until `pred` of `:rf/xray`'s app-db returns truthy."
  [pred label]
  (test-support/poll-until
    #(pred (rf/app-db-value :rf/xray))
    {:label label :timeout-ms 1000}))

(defn- render-open-palette []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open]))
  (rf/with-frame :rf/xray (palette/Modal)))

;; ---- tests --------------------------------------------------------------

(deftest backdrop-click-closes-palette-from-default-frame-context
  (testing "rf2-w8lxg — clicking the backdrop from OUTSIDE the
            :rf/xray frame-provider's render context still closes the
            palette. Without the explicit `{:frame :rf/xray}` opt the
            click would land on :rf/default and the palette would stay
            open."
    (let [rendered (render-open-palette)
          backdrop (find-by-testid rendered "rf-xray-palette-backdrop")
          handler  (on-click backdrop)]
      (is (some? handler) "backdrop exposes an :on-click handler")
      ;; Pre-condition — palette is open on :rf/xray.
      (is (true? (boolean (:palette-open? (rf/app-db-value :rf/xray)))))
      (handler (fake-event))
      (async done
        (-> (await-xray-db #(false? (boolean (:palette-open? %)))
                            "palette-open? flips false after backdrop click")
            (.then (fn [_]
                     (is (false? (boolean (:palette-open? (rf/app-db-value :rf/xray))))
                         ":rf/xray's :palette-open? flips to false")
                     (is (nil? (:palette-open? (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by the dispatch")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

(deftest esc-keydown-closes-palette-from-default-frame-context
  (testing "rf2-w8lxg — Esc keydown on the palette input from OUTSIDE
            the :rf/xray frame-provider's render context still closes
            the palette."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-xray-palette-input")
          handler  (on-key-down input)]
      (is (some? handler) "input exposes an :on-key-down handler")
      (handler (fake-key-event "Escape"))
      (async done
        (-> (await-xray-db #(false? (boolean (:palette-open? %)))
                            "palette-open? flips false after Esc")
            (.then (fn [_]
                     (is (false? (boolean (:palette-open? (rf/app-db-value :rf/xray))))
                         ":rf/xray's :palette-open? flips to false")
                     (is (nil? (:palette-open? (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by Esc dispatch")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

(deftest arrow-down-keydown-moves-cursor-from-default-frame-context
  (testing "rf2-w8lxg — ArrowDown keydown on the palette input from
            OUTSIDE the :rf/xray frame-provider's render context still
            updates :rf/xray's :palette-cursor. Without the explicit
            frame opt the dispatch would land on :rf/default and the
            cursor would never move."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-xray-palette-input")
          handler  (on-key-down input)]
      (is (some? handler) "input exposes an :on-key-down handler")
      ;; Pre-condition — cursor at 0.
      (is (= 0 (:palette-cursor (rf/app-db-value :rf/xray))))
      (handler (fake-key-event "ArrowDown"))
      (async done
        (-> (await-xray-db #(pos? (or (:palette-cursor %) 0))
                            "palette-cursor advances past 0 after ArrowDown")
            (.then (fn [_]
                     (is (pos? (or (:palette-cursor (rf/app-db-value :rf/xray)) 0))
                         ":rf/xray's :palette-cursor advances after ArrowDown")
                     (is (nil? (:palette-cursor (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by the cursor dispatch")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

(deftest arrow-up-keydown-routes-to-xray-frame
  (testing "rf2-w8lxg — ArrowUp keydown also routes through the
            explicit frame opt. Move cursor to 2 first, then ArrowUp
            should bring it back toward 0 against :rf/xray's db."
    (let [_ (rf/with-frame :rf/xray
              (rf/dispatch-sync [:rf.xray/palette-open])
              (rf/dispatch-sync [:rf.xray/palette-cursor-set 2]))
          rendered (rf/with-frame :rf/xray (palette/Modal))
          input    (find-by-testid rendered "rf-xray-palette-input")
          handler  (on-key-down input)]
      (is (= 2 (:palette-cursor (rf/app-db-value :rf/xray))))
      (handler (fake-key-event "ArrowUp"))
      (async done
        (-> (await-xray-db #(< (or (:palette-cursor %) 0) 2)
                            "palette-cursor decrements after ArrowUp")
            (.then (fn [_]
                     (is (< (or (:palette-cursor (rf/app-db-value :rf/xray)) 0) 2)
                         ":rf/xray's :palette-cursor decrements after ArrowUp")
                     (is (nil? (:palette-cursor (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by ArrowUp")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))

(deftest input-on-change-updates-query-from-default-frame-context
  (testing "rf2-w8lxg — typing into the palette input from OUTSIDE the
            :rf/xray frame-provider's render context still updates
            :rf/xray's :palette-query. Without the explicit frame opt
            every keystroke would land on :rf/default and the
            displayed query would freeze."
    (let [rendered (render-open-palette)
          input    (find-by-testid rendered "rf-xray-palette-input")
          handler  (on-change input)]
      (is (some? handler) "input exposes an :on-change handler")
      (handler (fake-change-event "search"))
      (async done
        (-> (await-xray-db #(= "search" (:palette-query %))
                            "palette-query flips to 'search' after on-change")
            (.then (fn [_]
                     (is (= "search" (:palette-query (rf/app-db-value :rf/xray)))
                         ":rf/xray's :palette-query reflects the typed text")
                     (is (nil? (:palette-query (rf/app-db-value :rf/default)))
                         ":rf/default's db is NOT polluted by typing")))
            (.catch (fn [e] (is false (.-message e)) nil))
            (.then (fn [_] (done))))))))
