(ns day8.re-frame2-xray.panels-e2e.sync-epoch-focus-e2e-cljs-test
  "Regression coverage for rf2-mdpfz — the Xray panel-gallery variants
  seed `:epoch-history` DIRECTLY via `:rf.xray/sync-epoch-history` (no
  live trace buffer / cascades), so nothing on the normal trace-driven
  path selects an epoch. Pre-fix every focus-keyed Dynamic panel then
  rendered its 'nothing focused' empty-state — the App-db panel showed
  'app-db has no user-domain keys yet' and the Epoch panel showed 'No
  event focused.'.

  The fix makes `:rf.xray/sync-epoch-history` ALSO focus the LATEST
  seeded epoch (stamping `[:focus :epoch-id]` — the single source of
  truth; rf2-uy7nz retired the former `:selected-epoch-id` mirror).
  This test reproduces the gallery scenario exactly — a history-only
  seed with NO trace buffer — and asserts:

    1. the spine `:rf.xray/focus` carries the latest seeded epoch-id;
    2. the App-db panel's `:rf.xray/app-db-current+diff` resolves the
       focused record's `:db-before` (populated diff pre-image) rather
       than nil (the empty-state trigger);
    3. the Epoch panel's `:rf.xray/epoch-pipeline` resolves `:focused`
       on the head record (NOT `:no-focus`).

  Failing-before / passing-after the rf2-mdpfz handler change."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.test-helpers.e2e-multi-frame :as e2e]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- epoch-record
  "Minimal `:rf/epoch-record` matching the panel-gallery fixture shape —
  enough for the App-db before-image + Epoch pipeline to resolve."
  [{:keys [epoch-id event db-before db-after]}]
  {:epoch-id      epoch-id
   :frame         :rf/default
   :committed-at  (* 1000 epoch-id)
   :event-id      (first event)
   :trigger-event event
   :db-before     db-before
   :db-after      db-after
   :trace-events  []})

(def ^:private three-epoch-history
  "Three oldest-first epochs — the panel-gallery `watched-keys` shape.
  Epoch 32 is the HEAD (latest); the sync must focus IT."
  [(epoch-record {:epoch-id 30 :event [:counter/inc]
                  :db-before {:counter 5} :db-after {:counter 6}})
   (epoch-record {:epoch-id 31 :event [:user/edit-profile]
                  :db-before {:counter 6 :user/profile {:name "Ada"}}
                  :db-after  {:counter 6 :user/profile {:name "Ada Lovelace"}}})
   (epoch-record {:epoch-id 32 :event [:cart/add-item :apple]
                  :db-before {:counter 6 :cart {:items []}}
                  :db-after  {:counter 6 :cart {:items [{:id :apple :qty 1}]}}})])

(defn- seed-history-only!
  "Drive ONLY `:rf.xray/sync-epoch-history` into the `:rf/xray` frame —
  the exact gallery panel-gallery seed (no `:rf.xray/sync-trace-buffer`,
  so `:rf.xray/event-bundles` stays empty and `compose-focus` has no head
  cascade to auto-follow)."
  [history]
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:rf.xray/sync-epoch-history history])))

(deftest sync-epoch-history-focuses-latest-epoch
  (testing "history-only seed focuses the HEAD (latest) epoch"
    (e2e/with-host-and-xray-frames
      {}
      (fn []
        (seed-history-only! three-epoch-history)
        (is (empty? (e2e/sub-xray [:rf.xray/event-bundles]))
            "precondition: no cascades seeded — this is the gallery's
             history-only seed path")
        (let [focus (e2e/sub-xray [:rf.xray/focus])]
          (is (= 32 (:epoch-id focus))
              "spine focus must carry the LATEST seeded epoch-id (32),
               not nil — rf2-mdpfz: pre-fix focus stayed unset because
               the sync bypassed the trace-driven auto-follow"))))))

(deftest sync-epoch-history-populates-app-db-before-image
  (testing "App-db panel resolves the focused record's :db-before (not nil)"
    (e2e/with-host-and-xray-frames
      {}
      (fn []
        (seed-history-only! three-epoch-history)
        (let [{:keys [epoch-id before]}
              (e2e/sub-xray [:rf.xray/app-db-current+diff])]
          (is (= 32 epoch-id)
              ":rf.xray/app-db-current+diff did not land on the latest
               focused epoch")
          (is (some? before)
              "the diff PRE-IMAGE (:before) is nil — the App-db panel
               renders its 'no user-domain keys yet' empty-state when
               :before is absent (rf2-mdpfz / rf2-yng0y: no head-fallback
               here, so focus MUST carry the epoch-id)")
          (is (= {:counter 6 :cart {:items []}} before)
              ":before must be epoch 32's :db-before — the populated
               pre-image, by construction"))))))

(deftest sync-epoch-history-focuses-epoch-pipeline
  (testing "Epoch panel resolves :focused on the head record"
    (e2e/with-host-and-xray-frames
      {}
      (fn []
        (seed-history-only! three-epoch-history)
        (let [{:keys [status epoch-id record]}
              (e2e/sub-xray [:rf.xray/epoch-pipeline])]
          (is (= :focused status)
              ":rf.xray/epoch-pipeline status must be :focused, not
               :no-focus — the gallery Epoch panel rendered 'No event
               focused' pre-fix")
          (is (= 32 epoch-id))
          (is (= [:cart/add-item :apple] (:trigger-event record))
              "the focused record must be the head epoch (32)"))))))

(deftest empty-history-seed-clears-focus
  (testing "an empty history seed clears focus so the empty-state renders"
    (e2e/with-host-and-xray-frames
      {}
      (fn []
        ;; First focus a real epoch, then seed an empty history — the
        ;; sync must clear the stale selection (no epoch to focus).
        (seed-history-only! three-epoch-history)
        (is (= 32 (:epoch-id (e2e/sub-xray [:rf.xray/focus]))))
        (seed-history-only! [])
        (let [focus (e2e/sub-xray [:rf.xray/focus])
              {:keys [status]} (e2e/sub-xray [:rf.xray/epoch-pipeline])]
          (is (nil? (:epoch-id focus))
              "empty seed must clear the focused epoch-id")
          (is (= :no-focus status)
              "empty history → Epoch panel renders its :no-focus
               empty-state"))))))
