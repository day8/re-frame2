(ns re-frame.adapter.uix-clear-warn-once-chain-cljs-test
  "Per rf2-e54wc: exercise the chained `:adapter/clear-warn-once-caches!`
  hook through the UIx adapter surface.

  The UIx adapter wires its per-adapter `clear-warned-non-dom-roots!`
  thunk into the chained hook at ns-load via
  `spine/install-clear-warn-once-step!` (see `uix.cljs:180`). The hook
  is then invoked by `make-reset-runtime-fixture` after every test to keep
  warn-once state from leaking across sibling tests.

  The publication test (`uix_late_bind_publication_cljs_test.cljs`)
  pins that the hook is REGISTERED (i.e. `late-bind/get-fn` returns a
  non-nil fn). This file goes one step further: it drives the hook end-
  to-end and asserts the UIx adapter's `warn-cache` actually empties.

  Strategy. The warn-cache is private to the adapter's spine-fns
  closure, so we reach it indirectly via the substrate behaviour it
  governs: `wrap-view` wrapping a user-fn whose output is a non-DOM
  React element fires the per-id warn-once. After one fire the same id
  is silenced on subsequent invocations. Invoking the chained hook
  clears the cache and re-arms the same id.

  Sibling test for the Reagent path:
  `warn_once_fixture_isolation_cljs_test.cljs` (rf2-4edk).

  ns ends in `-cljs-test` so shadow-cljs `:node-test` picks it up."
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.late-bind :as late-bind]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter}))

;; ---- helper: capture console.warn calls -----------------------------------

(defn- with-captured-console-warn
  "Replace js/console.warn with a recording shim around `thunk`. Returns
  the vector of joined-message strings observed. Restores the original
  on the way out, even if thunk throws."
  [thunk]
  (let [calls    (atom [])
        original (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args]
              (swap! calls conj (apply str args))))
      (thunk)
      @calls
      (finally
        (set! (.-warn js/console) original)))))

;; ---- helper: non-DOM React element output ---------------------------------

(defn- non-dom-element
  "Build a React element whose `.-type` is NOT a string — `wrap-view`'s
  source-coord annotator classifies this as a non-DOM root and routes
  it to the warn-once path (Spec 006 §Source-coord annotation).
  React.Fragment is a Symbol on React 18+, so `.-type` is non-string —
  exactly the shape needed to exercise the warn path."
  []
  (React/createElement React/Fragment #js {} "non-dom"))

;; ---- the chained-hook exercise --------------------------------------------

(deftest chained-clear-warn-once-caches-empties-uix-warn-cache
  (testing "rf2-e54wc: the chained :adapter/clear-warn-once-caches! hook
            (registered via spine/install-clear-warn-once-step! at UIx
            adapter ns-load) clears the UIx adapter's warn-cache. After
            one warn-once fire the same id is silenced; after the
            chained hook fires the same id re-warns — proving the
            adapter's cache participates in the chain."
    (let [target-id    :rf.uix-clear-warn-once/shared
          wrapped      (uix-adapter/wrap-view target-id {}
                                              (fn user-fn [] (non-dom-element)))
          phase-1-ws   (with-captured-console-warn
                         (fn []
                           ;; Three calls in the same phase prove the
                           ;; warn-once contract within a phase: exactly
                           ;; one emission for the id.
                           (dotimes [_ 3] (wrapped))))
          _            (is (= 1 (count phase-1-ws))
                           (str "phase-1 sanity: warn-once fires exactly "
                                "once WITHIN a single phase; got "
                                (count phase-1-ws) ": " (pr-str phase-1-ws)))
          chained-hook (late-bind/get-fn :adapter/clear-warn-once-caches!)
          _            (is (some? chained-hook)
                           "precondition: the chained hook is registered")
          _            (chained-hook)
          phase-2-ws   (with-captured-console-warn
                         (fn []
                           ;; Same id, post-chain. Without the chain
                           ;; clearing the UIx adapter's cache the
                           ;; emission would be silenced; with the chain
                           ;; the cache is empty and the warn re-fires.
                           (wrapped)))]
      (is (= 1 (count phase-2-ws))
          (str "phase-2 must re-emit the warning for the same id AFTER "
               "the chained :adapter/clear-warn-once-caches! hook fires "
               "(per rf2-e54wc). Got " (count phase-2-ws) ": "
               (pr-str phase-2-ws) ". If this is zero, the UIx adapter's "
               "warn-cache is no longer participating in the chained "
               "clear-step, defeating the rf2-4edk fixture-isolation "
               "guarantee under UIx.")))))

;; ---- positive control: direct `clear-warned-non-dom-roots!` works ---------

(deftest clear-warned-non-dom-roots-resets-uix-cache-directly
  (testing "Calling the UIx adapter's `clear-warned-non-dom-roots!`
            thunk directly also resets the cache — this is the seam the
            chained hook invokes. Pin it independently so a future
            refactor that drops the chain wiring but keeps the fn name
            is caught here (and vice versa)."
    (let [target-id :rf.uix-clear-warn-once/direct
          wrapped   (uix-adapter/wrap-view target-id {}
                                           (fn user-fn [] (non-dom-element)))
          ws-1      (with-captured-console-warn
                      (fn [] (wrapped)))]
      (is (= 1 (count ws-1)) "first emission fires")
      (uix-adapter/clear-warned-non-dom-roots!)
      (let [ws-2 (with-captured-console-warn (fn [] (wrapped)))]
        (is (= 1 (count ws-2))
            (str "after `uix-adapter/clear-warned-non-dom-roots!` the "
                 "same id re-emits. Got " (count ws-2) ": "
                 (pr-str ws-2)))))))
