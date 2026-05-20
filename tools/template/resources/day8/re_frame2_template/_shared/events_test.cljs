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
;; `reset-runtime-fixture-factory` snapshot/restores the registrar around each
;; test, resets every frame's app-db to `{}`, and reinstalls the
;; plain-atom adapter. Per-test `reg-*` calls roll back on exit;
;; framework registrations (and the app's own ns-load registrations
;; above) survive.

(use-fixtures :each (ts/reset-runtime-fixture-factory {:adapter plain-atom/adapter}))

;; --- Tests -----------------------------------------------------------------

(deftest counter-initialise-seeds-app-db
  (testing ":counter/initialise puts {:counter/value 0} into app-db"
    (rf/dispatch-sync [:counter/initialise])
    (is (= 0 (:counter/value (rf/get-frame-db :rf/default))))))

(deftest counter-increment-bumps-value
  (testing ":counter/increment increases :counter/value by one each fire"
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (is (= 3 (:counter/value (rf/get-frame-db :rf/default))))))

(deftest counter-value-sub-reads-current-count
  (testing ":counter/value sub returns the slice value after event flow"
    (rf/dispatch-sync [:counter/initialise])
    (rf/dispatch-sync [:counter/increment])
    (rf/dispatch-sync [:counter/increment])
    (is (= 2 @(rf/subscribe [:counter/value])))))
