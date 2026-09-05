(ns {{namespace}}.events-test
  "Unit tests for {{namespace}}.events.

   Substrate-agnostic — uses the plain-atom reactive substrate so the
   tests run under shadow-cljs `:node-test` without a DOM. The same
   events / subs handlers are exercised regardless of which view-side
   substrate (Reagent / UIx) the app uses for rendering.

   Run:

       npx shadow-cljs compile test
       node out/node-test.js"
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core                 :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support         :as rf.test-support]
            ;; Requiring these namespaces installs their registrations.
            [{{namespace}}.events]
            [{{namespace}}.subs]))

;; --- Fixture ---------------------------------------------------------------
;;
;; The fixture restores registrations and app-db between tests and installs a
;; DOM-free adapter.
;;
;; The test preset uses strict coeffect minting. Handlers that require a
;; generated fact must receive it under `:rf.cofx` instead of reading a live,
;; nondeterministic value:
;;
;;     (rf/dispatch-sync [:order/place]
;;                       {:rf.cofx {:order/id #uuid "..."
;;                                  :rf/time-ms 1700000000000}})
;;
;; A test that intentionally exercises live generation can opt in per call:
;;
;;     (rf/dispatch-sync [:order/place] {:rf.cofx/mint-policy :explicit-live})
;;
;; The counter does not require generated coeffects; this default protects
;; future tests as handlers grow.

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn []
                ;; The init hook overrides the fixture's default frame config.
                (rf/make-frame {:id :rf/default :preset :test}))}))

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
