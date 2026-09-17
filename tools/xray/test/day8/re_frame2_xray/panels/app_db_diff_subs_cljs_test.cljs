(ns day8.re-frame2-xray.panels.app-db-diff-subs-cljs-test
  "Per-leaf smoke test for `app-db-diff-subs` (rf2-nb8if).

  Calls the leaf's `install!` directly (NOT the umbrella) and asserts
  the live app-db-tab subs are registered.

  ## rf2-p53m2 — dead diff-sub family pruned

  The `:rf.xray/selected-epoch-diff` → `:rf.xray/app-db-diff` composite
  (plus the `:rf.xray/selected-epoch-redacted-modified-count` /
  `:rf.xray/selected-epoch-flow-writes` inputs and the three
  `[frame-id epoch-id]` caches) had no production view consumer and was
  removed. The assertions below pin that the family stays gone — a
  regression guard against re-introducing the hardened-but-unrendered
  surface.

  ## rf2-y8doi.14 — the suppressed-signal count

  `:rf.xray/app-db-current+diff` carries `:redacted-modified`, read
  straight off the focused record's
  `:rf.epoch/redacted-modified-paths-count`. The panel redacts both sides
  of a declared-sensitive slot, so its structural diff emits no row for a
  slot that DID change; the count is how that suppressed signal reaches
  the operator (`tools/xray/spec/004-App-DB-Diff.md` §Count semantics).
  It is a slot on the surviving atomic sub — NOT a revival of the pruned
  `:rf.xray/selected-epoch-redacted-modified-count`, whose absence the
  guard below still pins."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            [day8.re-frame2-xray.panels.app-db-diff-subs :as subs]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter}))

(deftest leaf-install-registers-the-active-subs
  (subs/install!)
  ;; rf2-fvplw — `:rf.xray/observed-frame` is the picker/focus-aware
  ;; seam that replaces the legacy `:rf.xray/target-frame` read inside
  ;; `:rf.xray/target-frame-db`.
  (is (some? (rf.registrar/handler :sub :rf.xray/observed-frame)))
  (is (some? (rf.registrar/handler :sub :rf.xray/target-frame-db)))
  (is (some? (rf.registrar/handler :sub :rf.xray/selected-epoch-record)))
  (is (some? (rf.registrar/handler :sub :rf.xray/focused-slice-path)))
  (is (some? (rf.registrar/handler :sub :rf.xray/show-me-when-this-changed-result)))
  ;; rf2-yng0y — the atomic current-state + focused-epoch before-image
  ;; sub the panel pivots on (collapses the former 5-deep focus chain so
  ;; `:before` / `:epoch-id` move together — no stale-`before` frame).
  (is (some? (rf.registrar/handler :sub :rf.xray/app-db-current+diff)))
  ;; rf2-okvit — the current-state inspector's section-model sub (now
  ;; derived from the atomic sub above).
  (is (some? (rf.registrar/handler :sub :rf.xray/app-db-state))))

(deftest pruned-diff-sub-family-stays-gone
  ;; rf2-p53m2 — the dead composite diff family had no production view
  ;; consumer; the Epoch panel reads `:rf.xray/selected-epoch-record`
  ;; (not these), and the MCP `get-app-db-diff` tool goes directly
  ;; through `diff.engine/project`. Guard against re-introduction.
  (subs/install!)
  (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-diff)))
  (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-redacted-modified-count)))
  (is (nil? (rf.registrar/handler :sub :rf.xray/selected-epoch-flow-writes)))
  (is (nil? (rf.registrar/handler :sub :rf.xray/app-db-diff)))
  ;; rf2-e9tb0 — pinned-slices subs are gone (older prune; kept here as
  ;; part of the dead-surface guard).
  (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices-store)))
  (is (nil? (rf.registrar/handler :sub :rf.xray/pinned-slices))))

;; ---- rf2-y8doi.14 — :redacted-modified rides the atomic sub -------------
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
