(ns re-frame.ui.reactive-tear-browser-dom-cljs-test
  "rf2-vxgfnd.65 — the watch-fired uSES tear window closes before paint,
  under REAL React + real DOM (the end-to-end counterpart to the
  node-only microtask-ordering fixture `reactive-tear-cljs-test`).

  The `reactive-tear-cljs-test` node fixture pins the SCHEDULER primitive
  (rf2-vxgfnd.40): a coalesced flush rides a TRUE host microtask
  (`js/queueMicrotask`), which drains after the synchronous run-to-
  completion drain and BEFORE the next paint — not the pre-fix
  `goog.async.nextTick` macrotask that could let a torn frame paint. But
  it drives the ViewCell directly (`mark-dirty!` + manual `flush-pending!`);
  it never crosses the observation port, `useSyncExternalStore`, or React.

  This file drives the SAME correction through the whole live stack —
  observation port → ViewCell → `useSyncExternalStore` → React —
  under a WATCHABLE substrate (the UIx function-component spine, whose
  derived values are `IWatchable`, so a sub value-movement fires the
  port's per-lease `on-change` watch — the real trigger of the tear the
  .40 fix addresses; the plain-atom / test-react substrates are
  `IDeref`-only and never fire it). Two sibling ViewCells share one sub
  (each `defview` instance owns its own `useSyncExternalStore`); a plain
  `dispatch-sync` MOVES that sub. We assert the whole subtree stays
  CONSISTENT (never torn) at every observable checkpoint and reflects the
  moved value by the MICROTASK checkpoint — before any macrotask/paint.

  The discriminator is `react-dom/flushSync`: at each checkpoint we force
  React to COMMIT whatever the reactive scheduler has notified so far,
  then read the DOM. This decouples the assertion from React's own
  re-render scheduling and isolates the ONE thing under test — WHEN the
  reactive coalesced flush fires:

    - synchronously after `dispatch-sync`: the flush is DEFERRED (the
      revision has not advanced, coalescing preserved), so `flushSync`
      commits nothing and the subtree is still at the old value —
      consistent, not a synchronous per-mark repaint.
    - on the MICROTASK checkpoint: the coalesced flush has ALREADY run
      (`queueMicrotask` after the mark), the revisions advanced, and
      `flushSync` commits the re-render — the whole subtree reflects the
      new value, consistently. Were the flush still a macrotask, it would
      NOT have run here and the subtree would read stale — the assertion
      fails, gating a .40 regression.
    - on the MACROTASK checkpoint: stable.

  Browser-only bodies — the `-dom-cljs-test$` suffix opts this file into
  the `:browser-test` build; `:node-test` / `:node-test-ui` load it too,
  where every DOM body gates on `(browser?)` and no-ops."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            ["react-dom" :as react-dom]
            [re-frame.core :as rf]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview frame-provider sub]]
            [re-frame.ui.client :as client]
            ;; The CLJS emitter wraps a SUB-BEARING view's body in
            ;; `re-frame.ui.viewcell/render` (sub-free views are never
            ;; wrapped), so a consumer of a sub-bearing `defview` must load
            ;; the viewcell runtime for that emitted reference to resolve.
            [re-frame.ui.viewcell]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

;; A WATCHABLE function-component substrate (UIx spine): its derived values
;; are IWatchable, so a sub value-movement fires the observation port's
;; per-lease `on-change` watch — the watch-fired invalidation the .40 fix
;; targets. `:ambient-frame nil` opts out of the fixture's default ambient
;; `:rf/default` scope so the compiled views resolve their frame ONLY from
;; the enclosing `frame-provider` (the React-context tier UIx publishes via
;; `:adapter/current-frame`), across the async re-renders too.
;;
;; `:async? true` returns the MAP-form fixture (persistent `set!` scope, not
;; a `binding` unwound before the async body resumes) — mandatory here: a
;; test using `(async done …)` requires every `:each` fixture to be a map,
;; and this fixture must survive the microtask/macrotask checkpoints. The
;; live-root reset rides the same map form.
;; This ns is a flushSync-TIMING discriminator, not an act test: every
;; `flushSync` checkpoint below deliberately forces React to commit exactly
;; what the reactive scheduler has notified SO FAR, to observe WHEN the
;; coalesced flush fires. `act` would drain the pending microtask and collapse
;; the microtask/macrotask distinction under test, so the whole ns runs the
;; real flushSync path with IS_REACT_ACT_ENVIRONMENT OFF (the
;; react_shared_suite.cljs pattern) — no "not wrapped in act" warning, timing
;; preserved. Sibling suites on the shared `:browser-test` page leak the flag
;; true; the fixture stashes the prior value and restores it in :after (which
;; cljs.test runs after the async body completes), so it neither depends on nor
;; leaks that flag.
(defonce ^:private prior-act-env (atom nil))

(defn- disable-act-env! []
  (when (exists? js/globalThis)
    (reset! prior-act-env (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)))

(defn- restore-act-env! []
  (when (exists? js/globalThis)
    (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) @prior-act-env)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter :ambient-frame nil :async? true})
  {:before #(do (disable-act-env!) (client/reset-live-roots!))
   :after  #(do (client/reset-live-roots!) (restore-act-env!))})

(def ^:private frame-kw :rf.tear/frame)

(defn- container [] (js/document.createElement "div"))

(defn- leaf-texts
  "The text of every `.leaf` sibling, in document order — the whole
  subtree's surfaced sub values. A torn paint shows as unequal entries."
  [c]
  (mapv #(.-textContent %)
        (array-seq (.querySelectorAll c ".leaf"))))

;; Two sibling instances of `leaf` each own their OWN ViewCell
;; (`useSyncExternalStore`), and both read the SAME sub — sibling ViewCells
;; sharing a sub.
(defview leaf [_] [:span.leaf (str (sub [:rf.tear/n]))])
(defview app  [_] [:div.app [leaf {}] [leaf {}]])

(defn- register-app! []
  (rf/make-frame {:id frame-kw :doc "reactive-tear browser probe frame"})
  (rf/reg-sub :rf.tear/n (fn [db _] (:n db)))
  (rf/reg-event :rf.tear/seed (fn [_ _] {:db {:n 1}}))
  (rf/reg-event :rf.tear/bump (fn [{:keys [db]} _] {:db (update db :n inc)})))

(deftest sub-move-defers-then-corrects-whole-subtree-on-the-microtask
  (if-not (browser?)
    (is true ":node — no DOM; the :browser-test runner exercises the DOM body")
    (do
      (register-app!)
      (rf/dispatch-sync [:rf.tear/seed] {:frame frame-kw})
      (let [c    (container)
            root (react-dom/flushSync
                   #(ui/mount [frame-provider {:frame frame-kw} [app {}]]
                              c {:root-id :rf.tear/root}))]
        (async done
          (is (= ["1" "1"] (leaf-texts c))
              "both sibling ViewCells mounted, sharing the sub at v1")
          ;; MOVE the sub — a plain synchronous drain. On the watchable
          ;; substrate the move fires each lease's `on-change`, marking both
          ;; cells dirty and arming ONE coalesced microtask flush; it does
          ;; NOT advance a revision or repaint synchronously.
          (rf/dispatch-sync [:rf.tear/bump] {:frame frame-kw})
          ;; SYNCHRONOUS checkpoint: force React to commit anything pending.
          ;; The reactive flush is deferred, so nothing is pending — the
          ;; subtree is still v1 (deferred off the drain, coalescing intact),
          ;; and it is CONSISTENT (no synchronous per-mark tear).
          (react-dom/flushSync (fn []))
          (is (= ["1" "1"] (leaf-texts c))
              "deferred off the synchronous drain — no synchronous repaint, subtree consistent")
          ;; MICROTASK checkpoint (before any paint): the coalesced flush
          ;; has run; committing React shows the WHOLE subtree at v2,
          ;; consistently. A macrotask-scheduled flush would still be pending
          ;; here — the subtree would read stale — so this is the .40 gate.
          (js/queueMicrotask
            (fn []
              (react-dom/flushSync (fn []))
              (is (= ["2" "2"] (leaf-texts c))
                  "corrected to v2 on the MICROTASK — before any macrotask; whole subtree consistent (no torn paint)")
              ;; MACROTASK checkpoint: the corrected, coalesced state is
              ;; stable one macrotask on (where a paint could have interleaved).
              (js/setTimeout
                (fn []
                  (react-dom/flushSync (fn []))
                  (is (= ["2" "2"] (leaf-texts c))
                      "stable across the macrotask boundary")
                  (ui/unmount! root)
                  (done))
                0))))))))
