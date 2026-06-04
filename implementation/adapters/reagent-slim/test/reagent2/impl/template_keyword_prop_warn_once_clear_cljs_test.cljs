(ns reagent2.impl.template-keyword-prop-warn-once-clear-cljs-test
  "Re-arm coverage for the slim hiccup interpreter's keyword-prop warn-once
  cache (rf2-qy6cl).

  `reagent2.impl.template` carries a process-wide `defonce` warn-once cache
  (`warned-keyword-prop`) backing the §7.2 D2 informational notice: a
  keyword value on a non-HTML-attribute prop passes through unchanged AND
  fires a one-shot console.warn keyed on `[k name-of-v]`. The user-facing
  contract is `warn once per pair for the process lifetime`; tests, though,
  must RE-ARM the cache between cases or a sibling test that already warned
  for a pair silently swallows a later test's same-pair warning.

  This is the same test-isolation hazard rf2-4edk fixed for the spine's
  source-coord / non-DOM-root warn-cache. The fix (rf2-qy6cl) chains the
  keyword-prop cache's clear-step into the SAME
  `:adapter/clear-warn-once-caches!` late-bind hook — via
  `spine/install-clear-warn-once-step!`, registered at slim-adapter
  ns-load. Requiring `re-frame.adapter.reagent-slim` here runs that
  registration.

  This file proves the re-arm end-to-end: a keyword-prop warning fires,
  the chained clear hook runs, and the SAME pair warns AGAIN (not
  swallowed). Mirrors the chained-clear assertion in
  `re-frame.adapter.react-shared-suite/assert-chained-clear-warn-once-empties-cache`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing]]
            ;; load-bearing: requiring the slim adapter runs the
            ;; `spine/install-clear-warn-once-step!` registration that wires
            ;; template/clear-warned-keyword-prop! into the chained hook.
            [re-frame.adapter.reagent-slim]
            [re-frame.late-bind :as late-bind]
            [reagent2.impl.template :as template]))

(defn- with-warn-spy
  "Run `f` with js/console.warn redirected to record invocations onto an
  internal vector; return that vector of joined arg-strings. Restores the
  original on exit, even if `f` throws."
  [f]
  (let [calls (atom [])
        orig  (.-warn js/console)]
    (try
      (set! (.-warn js/console)
            (fn [& args] (swap! calls conj (apply str args))))
      (f)
      @calls
      (finally
        (set! (.-warn js/console) orig)))))

;; ---------------------------------------------------------------------------
;; Chained hook re-arms the keyword-prop cache (the rf2-qy6cl fix)
;; ---------------------------------------------------------------------------

(deftest chained-clear-warn-once-re-arms-keyword-prop-cache
  (testing "After a keyword-prop warning fires for a pair, the chained
            :adapter/clear-warn-once-caches! hook re-arms the cache so the
            SAME pair warns AGAIN — it is no longer silently swallowed
            (rf2-qy6cl; the rf2-4edk hazard applied to the slim template's
            keyword-prop cache)."
    (let [k :rf2-rearm-test-k
          v :rf2-rearm-test-v
          phase-1 (with-warn-spy
                    (fn []
                      ;; warn-once: three calls, one warning within the phase
                      (template/convert-prop-value k v)
                      (template/convert-prop-value k v)
                      (template/convert-prop-value k v)))]
      (is (= 1 (count phase-1))
          (str "phase-1 sanity: warn fires exactly once within a phase; got "
               (count phase-1) ": " (pr-str phase-1)))
      (let [chained-hook (late-bind/get-fn :adapter/clear-warn-once-caches!)]
        (is (some? chained-hook)
            "precondition: the chained :adapter/clear-warn-once-caches! hook is registered")
        (chained-hook)
        (let [phase-2 (with-warn-spy (fn [] (template/convert-prop-value k v)))]
          (is (= 1 (count phase-2))
              (str "phase-2 MUST re-emit the warning for the SAME pair AFTER "
                   "the chained :adapter/clear-warn-once-caches! hook fires "
                   "(this is the rf2-qy6cl fix; before it the warning was "
                   "swallowed). Got " (count phase-2) ": " (pr-str phase-2))))))))

;; ---------------------------------------------------------------------------
;; The direct clear-fn seam the chained hook invokes
;; ---------------------------------------------------------------------------

(deftest clear-warned-keyword-prop-re-arms-directly
  (testing "Calling template/clear-warned-keyword-prop! directly also
            re-arms the cache — the seam the chained hook invokes. Returns
            nil (the make-clear-warned-fn shape)."
    (let [k :rf2-rearm-direct-k
          v :rf2-rearm-direct-v
          phase-1 (with-warn-spy
                    (fn []
                      (template/convert-prop-value k v)
                      (template/convert-prop-value k v)))]
      (is (= 1 (count phase-1))
          (str "phase-1 sanity: warn-once within a phase; got "
               (count phase-1) ": " (pr-str phase-1)))
      (is (nil? (template/clear-warned-keyword-prop!))
          "clear-warned-keyword-prop! returns nil")
      (let [phase-2 (with-warn-spy (fn [] (template/convert-prop-value k v)))]
        (is (= 1 (count phase-2))
            (str "phase-2 re-warns the SAME pair after the direct clear; got "
                 (count phase-2) ": " (pr-str phase-2)))))))
