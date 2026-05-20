(ns re-frame.dispatch-fallthrough-warn-dom-cljs-test
  "Per rf2-nmusx — CLJS-side integration coverage for the async-escape
  fallthrough warning (`:rf.warning/dispatch-from-async-callback-fell-
  through-to-default`). The JVM test `re-frame.dispatch-fallthrough-
  warn-test` simulates the trigger by rebinding `frame/*current-frame*`
  to nil; this file exercises the REAL JS async-callback surfaces
  (`setTimeout`, `addEventListener`, `requestAnimationFrame`) so the
  end-to-end trigger path is pinned.

  Why both: the JVM proxy validates the runtime's detection logic; the
  CLJS integration test validates that the canonical JS async-escape
  surfaces actually produce the trigger envelope. A future refactor of
  the router's frame-resolution chain (or of how `*current-frame*`
  loses its dynamic binding across the JS event loop) could regress
  the integration shape without disturbing the JVM proxy.

  Each test:
  1. registers a non-default frame (precondition; single-frame apps
     do not see the warning),
  2. records every trace event via `register-trace-cb!`,
  3. dispatches `[:game/tick]` (handler intentionally NOT registered)
     from inside the relevant JS callback,
  4. awaits the callback to fire via `cljs.test/async`,
  5. asserts exactly one `:rf.warning/dispatch-from-async-callback-fell-
     through-to-default` is observed with the documented payload shape.

  The `:detected-at` numeric, the `:routed-to` :rf/default,
  the `:recovery` :no-recovery, and the human-readable `:reason`
  string are all pinned (they are the user-facing surface the warning
  exists to deliver).

  Naming convention (rf2-2hrj8): the `-dom-cljs-test$` suffix opts
  this file into the `:browser-test` build (Playwright + Chromium).
  `:node-test` still loads it (its regex `cljs-test$` matches both
  `-cljs-test` and `-dom-cljs-test`), so the cross-runtime assertions
  below run under both targets. The DOM-mounting branches gate on
  `(browser?)` and exit early under `:node-test`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.substrate.adapter :as substrate-adapter]
            [re-frame.test-support :as test-support]
            ;; rf2-qwm0a — listener surface lives in `re-frame.trace.tooling`.
            [re-frame.trace.tooling :as trace-tooling]))

;; Per cljs.test: async tests require fixtures supplied as a map
;; (fn-form fixtures don't suspend across the async body, so a finally
;; clause runs before the async work completes). Mirrors the rf2-l5q3
;; CLJS async-test idiom in
;; re-frame.dispatch-frame-capture-cljs-test.

(def ^:private registrar-snapshot (atom nil))

(defn- before! []
  (reset! registrar-snapshot (test-support/snapshot-registrar))
  (reset! frame/frames {})
  (substrate-adapter/dispose-adapter!)
  (trace-tooling/clear-trace-cbs!)
  (substrate-adapter/install-adapter! reagent-adapter/adapter)
  (frame/ensure-default-frame!))

(defn- after! []
  (when-let [snap @registrar-snapshot]
    (test-support/restore-registrar! snap)
    (reset! registrar-snapshot nil))
  (reset! frame/frames {})
  (trace-tooling/clear-trace-cbs!))

(use-fixtures :each {:before before! :after after!})

;; ---- browser gate --------------------------------------------------------
;;
;; This ns is loaded by both :node-test (matches `cljs-test$`) and
;; :browser-test (matches `-dom-cljs-test$` per rf2-2hrj8). Two of the
;; three async-callback
;; surfaces (`addEventListener` on a DOM node, `requestAnimationFrame`)
;; require a real browser. setTimeout is universal. Each test gates its
;; body on `(browser?)` where needed and exits early under :node-test
;; with a `(is true ...)` placeholder so the silent-on-success reporter
;; still observes a passing assertion.

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- raf-available? []
  (exists? js/requestAnimationFrame))

;; ---- helpers --------------------------------------------------------------

(defn- record-traces!
  "Install a recording listener; return the atom that accumulates events."
  [listener-id]
  (let [a (atom [])]
    (trace-tooling/register-trace-cb!
      listener-id
      (fn [ev] (swap! a conj ev)))
    a))

(defn- fallthrough-warnings
  "Filter recorded events to the async-escape fallthrough warning."
  [recorded]
  (filterv (fn [ev]
             (and (= :warning (:op-type ev))
                  (= :rf.warning/dispatch-from-async-callback-fell-through-to-default
                     (:operation ev))))
           @recorded))

(defn- assert-canonical-warn!
  "Pin every documented payload slot on the captured warning event so a
  silent shape-drift surfaces."
  [warn]
  (let [tags (:tags warn)]
    (is (= [:game/tick] (:event tags))
        ":event tag carries the original event vector")
    (is (= :game/tick (:event-id tags))
        ":event-id tag is the first-of-event keyword")
    (is (= :rf/default (:routed-to tags))
        ":routed-to tag pins the fallthrough target")
    (is (number? (:detected-at tags))
        ":detected-at tag is a numeric timestamp")
    (is (string? (:reason tags))
        ":reason tag is a human-readable explanation string")
    (is (re-find #"setTimeout|addEventListener|async"
                 (:reason tags))
        ":reason references the async-callback trigger surfaces")
    (is (= :no-recovery (:recovery warn))
        ":recovery slot pins the no-recovery contract")))

(defn- setup!
  "Common setup: register a non-default frame so the warning's
  single-frame-suppression guard does not fire. The :game/tick handler
  is intentionally NOT registered — that is the precondition for the
  warning."
  []
  (rf/reg-frame :game {:doc "non-default frame the user intended"}))

;; ---- setTimeout integration ----------------------------------------------

(deftest fires-from-setTimeout-callback
  (testing "Per rf2-nmusx: dispatching from a `setTimeout(fn, 0)`
            callback for a handler intended for a non-default frame
            but missing on `:rf/default` fires the async-escape
            fallthrough warning. The setTimeout callback runs on a
            fresh JS event-loop turn — the surrounding
            `*current-frame*` binding does NOT survive the boundary."
    (async done
      (setup!)
      (let [recorded (record-traces! ::set-timeout-fallthrough)]
        ;; Wrap the dispatch in a setTimeout-0 callback — the canonical
        ;; "fresh stack, no surrounding frame binding" shape.
        (js/setTimeout
          (fn []
            (try
              (rf/dispatch-sync [:game/tick])
              (catch :default _e
                ;; The handler is intentionally missing — the runtime
                ;; emits the :rf.error/no-such-handler trace and may
                ;; or may not throw depending on the per-category
                ;; recovery; we don't care about the throw here, only
                ;; about the trace.
                nil))
            (let [warns (fallthrough-warnings recorded)]
              (is (= 1 (count warns))
                  (str "expected exactly one fallthrough warning from
                        the setTimeout callback, got " (count warns)))
              (when (seq warns)
                (assert-canonical-warn! (first warns))))
            (trace-tooling/remove-trace-cb! ::set-timeout-fallthrough)
            (done))
          0)))))

;; ---- addEventListener integration ----------------------------------------

(deftest fires-from-addEventListener-callback
  (testing "Per rf2-nmusx: dispatching from an `addEventListener`
            callback for a handler intended for a non-default frame
            but missing on `:rf/default` fires the warning. The
            listener runs on a fresh event-loop turn just like
            setTimeout — the canonical 2048-tile rf2-o8m0 trigger."
    (if-not (browser?)
      (is true ":node-test: no DOM — browser-test runner exercises the
                addEventListener assertions")
      (async done
      (setup!)
      (let [recorded     (record-traces! ::add-event-listener-fallthrough)
            ;; Create a transient DOM node so we don't depend on
            ;; anything ambient. jsdom supports document.createElement
            ;; + dispatchEvent + addEventListener out of the box.
            target       (js/document.createElement "div")
            handler-ref  (atom nil)
            handler      (fn [_ev]
                           (try
                             (rf/dispatch-sync [:game/tick])
                             (catch :default _e nil))
                           (let [warns (fallthrough-warnings recorded)]
                             (is (= 1 (count warns))
                                 (str "expected exactly one fallthrough
                                       warning from the addEventListener
                                       callback, got " (count warns)))
                             (when (seq warns)
                               (assert-canonical-warn! (first warns))))
                           (.removeEventListener target "click" @handler-ref)
                           (trace-tooling/remove-trace-cb!
                             ::add-event-listener-fallthrough)
                           (done))]
        (reset! handler-ref handler)
        (.addEventListener target "click" handler)
        (.dispatchEvent target (js/Event. "click")))))))

;; ---- requestAnimationFrame integration -----------------------------------

(deftest fires-from-requestAnimationFrame-callback
  (testing "Per rf2-nmusx: dispatching from a `requestAnimationFrame`
            callback for a handler intended for a non-default frame
            but missing on `:rf/default` fires the warning. rAF runs
            on its own event-loop step; the surrounding frame binding
            does not survive."
    (if-not (raf-available?)
      (is true ":node-test: no requestAnimationFrame — browser-test
                runner exercises the rAF assertions")
      (async done
        (setup!)
        (let [recorded (record-traces! ::raf-fallthrough)]
          (js/requestAnimationFrame
            (fn [_ts]
              (try
                (rf/dispatch-sync [:game/tick])
                (catch :default _e nil))
              (let [warns (fallthrough-warnings recorded)]
                (is (= 1 (count warns))
                    (str "expected exactly one fallthrough warning from
                          the requestAnimationFrame callback, got "
                         (count warns)))
                (when (seq warns)
                  (assert-canonical-warn! (first warns))))
              (trace-tooling/remove-trace-cb! ::raf-fallthrough)
              (done))))))))

;; ---- suppression: single-frame app does not fire the warn ----------------

(deftest suppressed-from-setTimeout-when-only-default-frame
  (testing "Per rf2-nmusx / rf2-o8m0: single-frame apps (only
            `:rf/default`) cannot hit the footgun — the warning is
            suppressed even when dispatched from an async-callback
            for a handler that does not exist on `:rf/default`. The
            existing `:rf.error/no-such-handler` trace still fires
            (existing contract preserved)."
    (async done
      ;; Note: no setup! call — the only frame is :rf/default.
      (let [recorded (record-traces! ::single-frame-async)]
        (js/setTimeout
          (fn []
            (try
              (rf/dispatch-sync [:never-registered])
              (catch :default _e nil))
            (is (empty? (fallthrough-warnings recorded))
                "warning suppressed: single-frame apps cannot hit the footgun")
            (trace-tooling/remove-trace-cb! ::single-frame-async)
            (done))
          0)))))

;; ---- suppression: explicit :frame opt is not a fallthrough ---------------

(deftest suppressed-from-setTimeout-when-explicit-frame-opt
  (testing "Per rf2-nmusx / rf2-o8m0: an explicit `:frame` opt on the
            dispatch means the user was deliberate — the resolution
            chain never falls through. No warning fires even from an
            async callback, even when the handler is missing."
    (async done
      (setup!)
      (let [recorded (record-traces! ::explicit-frame-async)]
        (js/setTimeout
          (fn []
            (try
              (rf/dispatch-sync [:game/tick] {:frame :game})
              (catch :default _e nil))
            (is (empty? (fallthrough-warnings recorded))
                "explicit :frame opt is not a fallthrough — warning suppressed")
            (trace-tooling/remove-trace-cb! ::explicit-frame-async)
            (done))
          0)))))
