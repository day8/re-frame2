(ns day8.re-frame2-xray.panels-e2e.evicted-epoch-all-panels-cljs-test
  "Cross-panel regression for rf2-uo0rc.1 — spec/021 §10.7 (Evicted-epoch
  placeholder).

  ## The invariant (spec/021 §10.7, NORMATIVE)

  'When the operator scrubs onto an epoch evicted from the buffer, EVERY
  edn-inspector in EVERY panel renders the same placeholder: Epoch evicted
  from buffer.'

  ## The bug

  On a PINNED (RETRO) focus whose epoch had aged out of the per-frame
  epoch ring, the L4 panels DISAGREED:

    - Issues / Trace / Epoch (shared `focus-resolver/find-epoch-record`)
      + App-DB Diff (`find-epoch-in-history`) correctly resolved nil →
      rendered the evicted placeholder.
    - Machine Inspector (`machine-inspector-helpers/focused-epoch-record`)
      + Views (`reactive-panel-subs/focused-epoch-record`) SILENTLY FELL
      BACK TO HEAD → rendered the LATEST machine state / cascade. The
      operator believed they were inspecting the pinned (evicted) epoch
      but saw the most-recent state instead — a state-reconstruction lie.

  ## The fix

  Both panel `focused-epoch-record` helpers now route through the shared
  `panels.shared.focus-resolver/find-epoch-record`, which returns nil for
  a pinned-but-evicted epoch (preserving only the spec-correct NIL-focus
  head-fallback). So ALL panels resolve the evicted epoch to nil and
  render the §10.7 placeholder.

  This test drives the REAL panel composite subs (`:rf.xray/focus` →
  `compose-focus` RETRO branch + the per-panel projections) over a
  directly-seeded spine `:focus` + `:epoch-history` so the eviction is
  deterministic (no ring-depth/timing race). The RETRO `:else` branch of
  `compose-focus` preserves the pinned `:epoch-id` even when evicted — the
  exact state §10.7 governs."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-xray.preload :as preload]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            [day8.re-frame2-xray.trace-collector :as trace-collector]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- install-xray! []
  (trace-collector/reset-for-test!)
  (xray-test-support/reset-all!)
  (registry/register-xray-handlers!)
  (preload/register-trace-collector!)
  (preload/register-epoch-collector!)
  ;; A tiny test-local seeder for the spine slots the composite subs read.
  ;; Stamps a RETRO pin on an epoch-id + an epoch-history ring that does
  ;; NOT contain it — the exact "scrubbed onto an evicted epoch" state.
  (rf/reg-event :test/seed-evicted-pin
    (fn [{:keys [db]} [_ {:keys [pinned-epoch-id history]}]]
      {:db (-> db
          (assoc :epoch-history (vec history))
          (assoc :focus {:mode :retro :epoch-id pinned-epoch-id}))}))
  nil)

(defn- sub-xray [query]
  (rf/with-frame :rf/xray @(rf/subscribe query)))

(def ^:private evicted-id 1)

;; The surviving ring — the pinned epoch-id (1) is NOT present, i.e. it
;; has been evicted. Each surviving record is a fully-shaped epoch with a
;; distinct machine transition + sub-run + render so a HEAD-fallback bug
;; would surface NON-empty machine / Views data (catching the regression).
(def ^:private surviving-history
  [{:epoch-id 7
    :dispatch-id :d7
    :event [:counter/inc]
    :db-before {:counter 6} :db-after {:counter 7}
    :sub-runs [{:sub-id :counter/value :recomputed? true :value-changed? true}]
    :renders [{:render-key [:counter-view 0]}]
    :trace-events [{:operation :rf.machine/transition
                    :machine-id :traffic-light
                    :tags {:rf.machine/id :traffic-light}}
                   {:operation :rf.view/rendered
                    :tags {:rf.view/id :counter-view
                           :rf.view/render-key [:counter-view 0]
                           :rf.view/mount? false
                           :rf.view/deref-subs [[:counter/value]]}}]}
   {:epoch-id 9
    :dispatch-id :d9
    :event [:counter/inc]
    :db-before {:counter 7} :db-after {:counter 8}
    :sub-runs [{:sub-id :counter/value :recomputed? true :value-changed? true}]
    :renders [{:render-key [:counter-view 0]}]
    :trace-events [{:operation :rf.machine/transition
                    :machine-id :traffic-light
                    :tags {:rf.machine/id :traffic-light}}
                   {:operation :rf.view/rendered
                    :tags {:rf.view/id :counter-view
                           :rf.view/render-key [:counter-view 0]
                           :rf.view/mount? false
                           :rf.view/deref-subs [[:counter/value]]}}]}])

(defn- seed-evicted-pin! []
  (frame/reg-frame :rf/xray {})
  (rf/with-frame :rf/xray
    (rf/dispatch-sync [:test/seed-evicted-pin
                       {:pinned-epoch-id evicted-id
                        :history surviving-history}]))
  nil)

(deftest evicted-pin-surfaces-evicted-epoch-id-on-focus
  (testing "test setup: compose-focus's RETRO branch preserves the pinned
            (evicted) :epoch-id — the exact state spec/021 §10.7 governs"
    (install-xray!)
    (seed-evicted-pin!)
    (let [focus (sub-xray [:rf.xray/focus])]
      (is (= :retro (:mode focus)) "pin must be RETRO")
      (is (= evicted-id (:epoch-id focus))
          (str "the pinned-but-evicted :epoch-id must survive on :rf.xray/focus "
               "so the panels' focused-epoch lookup hits the eviction path. "
               "focus: " (pr-str focus))))))

(deftest evicted-pin-machine-inspector-renders-placeholder
  (testing "rf2-uo0rc.1 — Machine Inspector resolves an evicted pin to NO
            transitions (→ blank/evicted placeholder), NOT the latest
            machine state. Pre-fix it head-fell-back and showed epoch 9's
            :traffic-light transition."
    (install-xray!)
    (seed-evicted-pin!)
    (let [records (sub-xray [:rf.xray/machine-transitions-for-focused-event])]
      (is (= [] records)
          (str "Machine Inspector showed transitions for an EVICTED pinned "
               "epoch — spec/021 §10.7 violation (head-fallback bug). "
               "records: " (pr-str records))))))

(deftest evicted-pin-views-panel-renders-placeholder
  (testing "rf2-uo0rc.1 — Views (reactive-data) resolves an evicted pin to
            :has-event-bundle? false (→ empty/evicted placeholder), NOT the
            latest cascade. Pre-fix it head-fell-back to epoch 9's cascade."
    (install-xray!)
    (seed-evicted-pin!)
    (let [reactive (sub-xray [:rf.xray/reactive-data])]
      (is (false? (:has-event-bundle? reactive))
          (str "Views showed a cascade for an EVICTED pinned epoch — "
               "spec/021 §10.7 violation (head-fallback bug). "
               "reactive: " (pr-str (select-keys reactive
                                                 [:has-event-bundle? :epoch-id]))))
      (is (empty? (:subs-ran reactive))
          "Views projected sub-runs from the head record on an evicted pin")
      (is (empty? (:view-rows reactive))
          "Views projected view-rows from the head record on an evicted pin"))))

(deftest evicted-pin-all-panels-agree-on-placeholder
  (testing "rf2-uo0rc.1 / spec/021 §10.7 — EVERY focus-scoped panel renders
            the evicted placeholder for a pinned-evicted epoch; NONE shows
            HEAD. This is the cross-panel agreement the bug broke (two
            panels silently showed latest state while four showed evicted)."
    (install-xray!)
    (seed-evicted-pin!)
    (let [trace     (sub-xray [:rf.xray/trace-feed])
          epoch     (sub-xray [:rf.xray/epoch-pipeline])
          issues    (sub-xray [:rf.xray/issues-ribbon])
          app-db    (sub-xray [:rf.xray/selected-epoch-record])
          machine   (sub-xray [:rf.xray/machine-transitions-for-focused-event])
          reactive  (sub-xray [:rf.xray/reactive-data])]
      ;; Already-correct panels (regression-guard they STAY correct).
      (is (= :epoch-evicted (:empty-kind trace))
          (str "Trace feed must report :epoch-evicted. trace: "
               (pr-str (select-keys trace [:empty-kind :epoch-id]))))
      (is (= :epoch-evicted (:status epoch))
          (str "Epoch panel must report :epoch-evicted. epoch: "
               (pr-str (select-keys epoch [:status :epoch-id]))))
      (is (= :epoch-evicted (:empty-kind issues))
          (str "Issues ribbon must report :epoch-evicted. issues: "
               (pr-str (select-keys issues [:empty-kind]))))
      (is (nil? app-db)
          "App-DB Diff must resolve no record for an evicted pin")
      ;; The two panels the fix corrects.
      (is (= [] machine)
          "Machine Inspector must resolve no transitions for an evicted pin")
      (is (false? (:has-event-bundle? reactive))
          "Views must report :has-event-bundle? false for an evicted pin")
      ;; The headline invariant: NOT ONE panel surfaced the HEAD epoch (9).
      (is (not= 9 (:epoch-id trace)) "Trace leaked the HEAD epoch")
      (is (not= 9 (:epoch-id epoch)) "Epoch panel leaked the HEAD epoch")
      (is (not= 9 (:epoch-id reactive)) "Views leaked the HEAD epoch"))))
