(ns day8.re-frame2-xray.panels.app-db-diff-subs-cljs-test
  "Per-leaf test for `app-db-diff-subs`, calling the leaf's own `install!`
  rather than the umbrella.

  `:rf.xray/app-db-current+diff` carries `:redacted-modified`, read straight
  off the focused record's `:rf.epoch/redacted-modified-paths-count`. The
  panel redacts both sides of a declared-sensitive slot, so its structural
  diff emits no row for a slot that DID change; the count is how that
  suppressed signal reaches the operator
  (`tools/xray/spec/004-App-DB-Diff.md` §Count semantics)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(defn- install-input-stubs!
  "Register the three cross-leaf input subs `:rf.xray/app-db-current+diff`
  joins (owned by `epoch.cljs` and `spine.cljs` in production), seeded from
  fixed values, inside the test body so the fixture's registrar rollback
  owns them."
  [history focus]
  (rf/reg-sub :rf.xray/epoch-history (fn [_db _q] history))
  (rf/reg-sub :rf.xray/focus         (fn [_db _q] focus))
  (rf/reg-sub :rf.xray/target-frame  (fn [_db _q] :rf/default)))

(deftest current+diff-carries-the-records-redacted-modified-count
  (subs/install!)
  (install-input-stubs!
    [{:epoch-id  7
      :db-before {:counter 1}
      :db-after  {:counter 2}
      :rf.epoch/redacted-modified-paths-count 2}]
    {:epoch-id 7})
  (is (= 2 (:redacted-modified @(rf/subscribe [:rf.xray/app-db-current+diff])))))

(deftest current+diff-redacted-modified-is-nil-when-the-record-omits-it
  (testing "the slot is OPTIONAL and absent reads as 0, so a record from a
            host with no classification layer yields nil and the renderer
            draws no `0 redacted paths modified` chip"
    (subs/install!)
    (install-input-stubs!
      [{:epoch-id 7 :db-before {:counter 1} :db-after {:counter 2}}]
      {:epoch-id 7})
    (let [data @(rf/subscribe [:rf.xray/app-db-current+diff])]
      (is (= 7 (:epoch-id data)) "PRECONDITION: the record resolved")
      (is (nil? (:redacted-modified data))))))
