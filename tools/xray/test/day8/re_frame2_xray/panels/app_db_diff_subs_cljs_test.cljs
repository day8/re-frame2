(ns day8.re-frame2-xray.panels.app-db-diff-subs-cljs-test
  "Per-leaf smoke test for `app-db-diff-subs`.

  Calls the leaf's `install!` directly (NOT the umbrella).

  ## No diff-sub family

  There is no `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff`
  composite (nor the `:rf.xray/selected-epoch-redacted-modified-count` /
  `:rf.xray/selected-epoch-flow-writes` inputs and the three
  `[frame-id epoch-id]` caches): nothing in a production view would
  consume it.

  ## The suppressed-signal count

  `:rf.xray/app-db-current+diff` carries `:redacted-modified`, read
  straight off the focused record's
  `:rf.epoch/redacted-modified-paths-count`. The panel redacts both sides
  of a declared-sensitive slot, so its structural diff emits no row for a
  slot that DID change; the count is how that suppressed signal reaches
  the operator (`tools/xray/spec/004-App-DB-Diff.md` §Count semantics).
  It is a slot on the atomic sub — NOT a separate
  `:rf.xray/selected-epoch-redacted-modified-count` sub."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

;; ---- :redacted-modified rides the atomic sub -----------------------------
;;
;; `:rf.xray/app-db-current+diff` declares four inputs, two of which this
;; leaf does NOT install: `:rf.xray/epoch-history` and `:rf.xray/focus`
;; belong to `epoch.cljs` and `spine.cljs` (and `:rf.xray/target-frame`,
;; which `:rf.xray/observed-frame` joins, to `epoch.cljs`). The umbrella
;; wires all of them in production.
;;
;; The stubs below supply exactly those three, and NOTHING else, so the
;; namespace keeps its stated character — a PER-LEAF test that calls the
;; leaf's own `install!` rather than the umbrella. Standing the umbrella up
;; here would make this file a second integration test and would stop it
;; being evidence about the leaf.

(defn- install-input-stubs!
  "Register the three cross-leaf input subs `:rf.xray/app-db-current+diff`
  joins, seeded from fixed values. Registered inside the test body so the
  fixture's registrar rollback owns them."
  [history focus]
  (rf/reg-sub :rf.xray/epoch-history (fn [_db _q] history))
  (rf/reg-sub :rf.xray/focus         (fn [_db _q] focus))
  (rf/reg-sub :rf.xray/target-frame  (fn [_db _q] :rf/default)))

(deftest current+diff-carries-the-records-redacted-modified-count
  (testing "the exact framework count rides the atomic sub, read off the
            SAME focused record every other slot comes from"
    (subs/install!)
    (install-input-stubs!
      [{:epoch-id  7
        :db-before {:counter 1}
        :db-after  {:counter 2}
        :rf.epoch/redacted-modified-paths-count 2}]
      {:epoch-id 7})
    (let [data @(rf/subscribe [:rf.xray/app-db-current+diff])]
      (is (= 7 (:epoch-id data))
          "PRECONDITION: the sub really resolved the seeded record — a
           nil :redacted-modified below would otherwise be unreadable")
      (is (= 2 (:redacted-modified data))))))

(deftest current+diff-redacted-modified-is-nil-when-the-record-omits-it
  (testing "Spec-Schemas marks the slot OPTIONAL and tells consumers to
            read absent as 0, so a record from a host with no
            classification layer yields nil here — the renderer draws no
            chip rather than a `0 redacted paths modified` one"
    (subs/install!)
    (install-input-stubs!
      [{:epoch-id 7 :db-before {:counter 1} :db-after {:counter 2}}]
      {:epoch-id 7})
    (let [data @(rf/subscribe [:rf.xray/app-db-current+diff])]
      (is (= 7 (:epoch-id data)) "PRECONDITION: the record resolved")
      (is (nil? (:redacted-modified data))))))

(deftest current+diff-redacted-modified-is-nil-when-no-epoch-is-focused
  (testing "CONTROL — with no focus there is no record to read a count
            off, and the slot must not carry a stale one from anywhere
            else. Pairs with the two arms above: they show the slot
            tracking the record, this shows it tracking its ABSENCE"
    (subs/install!)
    (install-input-stubs!
      [{:epoch-id 7
        :db-before {:counter 1}
        :db-after  {:counter 2}
        :rf.epoch/redacted-modified-paths-count 2}]
      {})
    (let [data @(rf/subscribe [:rf.xray/app-db-current+diff])]
      (is (nil? (:epoch-id data)) "PRECONDITION: nothing is focused")
      (is (nil? (:redacted-modified data))
          "the count belongs to a record, so no record means no count"))))
