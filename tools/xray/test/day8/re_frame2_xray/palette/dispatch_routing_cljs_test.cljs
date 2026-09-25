(ns day8.re-frame2-xray.palette.dispatch-routing-cljs-test
  "Click-time frame-routing tests for the Xray command palette.

  ## The bug these tests defend against

  The palette modal mounts via `[rf/frame-provider {:frame :rf/xray}
  …]` in the shell. Subscribes inside the palette resolve through
  React context — at RENDER time, React's `_currentValue` for the
  `frame-context` is set to `:rf/xray` while the body of the
  frame-provider's children is rendering, so `(rf/subscribe …)`
  picks up the right frame with no explicit opt.

  Dispatches from `:on-click` / `:on-change` / `:on-key-down` /
  `:on-mouse-enter` fire LATER — after render commits and React has
  POPPED `_currentValue` back to the context's default, which is the
  NO-PROVIDER SENTINEL, not `:rf/default`. At click time the frame
  resolution chain has TWO tiers (dynamic var → React-context tier)
  and nothing beneath them: the sentinel coerces to nil, so a bare
  unscoped dispatch RAISES `:rf.error/no-frame-context` (EP-0002)
  rather than routing anywhere, so the `:rf.xray/palette-*` handler
  never reduces `:rf/xray`'s db. The symptom would be a frozen palette:
  backdrop click does not close, arrow keys do not move the cursor, Esc
  does not close, input text does not update the query.

  The palette therefore threads a FRAME-BOUND DISPATCHER captured at
  render time: the `palette/ModalView` boundary captures it with
  `(:dispatch (rf/capture-frame))`, so N isolated Xray instances each
  route to their own frame rather than all to a singleton
  `{:frame :rf/xray}` literal. The envelope's `:frame` is set at call
  time and never depends on the click-time context read, which is the
  property these rows defend.

  ## How these tests reproduce the click-time path

  Each test plucks the handler off the rendered hiccup and invokes
  it OUTSIDE any `with-frame` binding — simulating the browser's
  click-fires-after-render reality. Click handlers use queued
  `rf/dispatch` (not `dispatch-sync`); the router drain is async via
  `goog.async.nextTick`. Tests use `rf.test-support/poll-until` to await
  the drain before asserting.

  ONE WAY THE HARNESS DIFFERS FROM THE BROWSER, and the counterfactual
  clauses below are worded for it. `make-xray-runtime-fixture` does not
  opt out of `:ambient-frame`, so the fixture binds `:rf/default` as an
  AMBIENT SCOPE around every body here. A handler invoked \"outside any
  `with-frame`\" therefore still has that scope in effect: under this
  harness an unscoped dispatch reduces `:rf/default`'s db — which is
  precisely what the `:rf/default`-is-NOT-polluted rows pin — where the
  browser, having no ambient scope at all, raises
  `:rf.error/no-frame-context`. The property under test is the same
  either way: the envelope's `:frame` is set at call time and never
  depends on the click-time context read.

  ## Where the tree comes from

  `palette/Modal` is the `as-component` BRIDGE — it
  answers a `[:>]` interop vector, not a tree to walk — so these rows
  build the hiccup through `test-helpers.palette-tree`, which mirrors
  the `palette/ModalView` boundary's four reads in the same order behind
  the same open-gate, and defaults `dispatch` to the boundary's own
  `(:dispatch (rf/capture-frame))` door. That default is load-bearing
  HERE specifically: it is the captured dispatcher whose click-time
  routing is the whole subject of this ns."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.test-helpers :as rf.test-helpers]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-helpers.palette-tree :as palette-tree]
            [day8.re-frame2-xray.test-support :as xray-test-support]))

;; `make-xray-runtime-fixture` composes core
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
;; The walk delegates to `rf.test-helpers/find-by-testid`; there is no Xray
;; walker facade. The `with-frame` wrapper is deliberate (NOT a walker
;; difference):
(defn- find-by-testid [tree testid]
  ;; EP-0002: walking a rendered tree RE-INVOKES nested component fns
  ;; (`rf.test-helpers/expand-tree`), and those render-time `rf/subscribe`s
  ;; resolve through the surrounding frame. The palette mounts in the
  ;; `:rf/xray` own-frame, so an ambient (no-scope) re-expansion would
  ;; raise `:rf.error/no-frame-context` rather than falling through to a
  ;; synthesised `:rf/default`. (The click-time HANDLER is invoked OUTSIDE
  ;; any scope — that is the real click-fires-after-render path under
  ;; test.)
  ;;
  ;; For this tree the scope is BELT-AND-BRACES rather than load-bearing,
  ;; and is kept deliberately. `view/palette-view` is pure and every helper
  ;; below it is CALLED rather than headed, so the tree `palette-tree`
  ;; answers is keyword-headed all the way down and the walk has no fn
  ;; head to re-invoke. Keeping the scope costs nothing and means a helper
  ;; that becomes headed cannot turn this walk red for the wrong reason.
  (rf/with-frame :rf/xray
    (rf.test-helpers/find-by-testid tree testid)))

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
  (rf.test-support/poll-until
    #(pred (rf/app-db-value :rf/xray))
    {:label label :timeout-ms 1000}))

(defn- render-open-palette []
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/palette-open]))
  (rf/with-frame :rf/xray (palette-tree/palette-tree)))

;; ---- tests --------------------------------------------------------------

(deftest backdrop-click-closes-palette-from-default-frame-context
  (testing "clicking the backdrop from OUTSIDE the
            :rf/xray frame-provider's render context still closes the
            palette. Without the frame-bound dispatcher the
            click would reduce the fixture's ambient :rf/default db
            instead (and raise in the browser, which has no ambient
            scope), and the palette would stay open."
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
  (testing "Esc keydown on the palette input from OUTSIDE
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
  (testing "ArrowDown keydown on the palette input from
            OUTSIDE the :rf/xray frame-provider's render context still
            updates :rf/xray's :palette-cursor. Without the frame-bound
            dispatcher the dispatch would reduce the fixture's ambient
            :rf/default db instead (and raise in the browser), so the
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
  (testing "ArrowUp keydown also routes through the
            frame-bound dispatcher. Move cursor to 2 first, then ArrowUp
            should bring it back toward 0 against :rf/xray's db."
    (let [_ (rf/with-frame :rf/xray
              (rf/dispatch-sync [:rf.xray/palette-open])
              (rf/dispatch-sync [:rf.xray/palette-cursor-set 2]))
          rendered (rf/with-frame :rf/xray (palette-tree/palette-tree))
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
  (testing "typing into the palette input from OUTSIDE the
            :rf/xray frame-provider's render context still updates
            :rf/xray's :palette-query. Without the frame-bound dispatcher
            every keystroke would reduce the fixture's ambient
            :rf/default db instead (and raise in the browser), so the
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
