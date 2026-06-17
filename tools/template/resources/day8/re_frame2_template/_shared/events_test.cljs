(ns {{namespace}}.events-test
  "Unit tests for {{namespace}}.events.

   Substrate-agnostic — uses the plain-atom reactive substrate so the
   tests run under shadow-cljs `:node-test` without a DOM. The same
   events / subs handlers are exercised regardless of which view-side
   substrate (Reagent / UIx / Helix) the app uses for rendering.

   Run:

       npx shadow-cljs compile test
       node out/node-test.js"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support         :as ts]
            ;; Side-effecting requires — load the user's handlers / subs
            ;; into the registrar so the tests can dispatch / compute
            ;; against them.
            [{{namespace}}.events]
            [{{namespace}}.subs]))

;; --- Fixture ---------------------------------------------------------------
;;
;; `make-reset-runtime-fixture` snapshot/restores the registrar around each
;; test, resets every frame's app-db to `{}`, and reinstalls the
;; plain-atom adapter. Per-test `reg-*` calls roll back on exit;
;; framework registrations (and the app's own ns-load registrations
;; above) survive.
;;
;; STRICT cofx mint policy (EP-0017). The fixture's `:init-fn` re-registers
;; `:rf/default` with `{:preset :test}`, which sets
;; `:rf.cofx/mint-policy :strict`. Under strict, a handler that DECLARES a
;; generator-backed recordable coeffect via `:rf.cofx/requires` but is NOT
;; supplied the fact FAILS as `:rf.error/missing-required-cofx` rather than
;; silently minting a fresh per-run value (a random uuid, the wall clock, …).
;; That is EP-0017's *supply-data-don't-stub* testing posture: a test makes
;; the durable fact explicit instead of letting a nondeterministic live read
;; leak in. Supply required facts FLAT under `:rf.cofx` in the dispatch opts:
;;
;;     (rf/dispatch-sync [:order/place]
;;                       {:rf.cofx {:order/id #uuid "..."
;;                                  :rf/time-ms 1700000000000}})
;;
;; A test that INTENTIONALLY accepts nondeterministic generation opts back
;; into the live policy per-call with `:rf.cofx/mint-policy :explicit-live`:
;;
;;     (rf/dispatch-sync [:order/place] {:rf.cofx/mint-policy :explicit-live})
;;
;; The plain counter events below declare no recordable coeffects, so they
;; run identically under strict — the policy guards future handlers that add
;; one, so the scaffold never regresses to live minting by default.

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                ;; `:preset :test` ⇒ `:rf.cofx/mint-policy :strict` (EP-0017).
                ;; Re-registering `:rf/default` is last-write-wins; it runs
                ;; AFTER the fixture's `ensure-default-frame!`, so this is the
                ;; effective default-frame config the tests dispatch into.
                (rf/reg-frame :rf/default {:preset :test}))}))

;; --- Tests -----------------------------------------------------------------

(deftest counter-initialise-seeds-app-db
  (testing ":counter/initialise puts {:counter/value 0} into app-db"
    (rf/dispatch-sync [:counter/initialise])
    (is (= 0 (:counter/value (rf/app-db-value :rf/default))))))

(deftest counter-increment-bumps-value
  (testing ":counter/increment increases :counter/value by one each fire"
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (is (= 3 (:counter/value (rf/app-db-value :rf/default))))))

(deftest counter-value-sub-reads-current-count
  (testing ":counter/value sub returns the slice value after event flow"
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (is (= 2 @(rf/subscribe [:counter/value])))))
