(ns day8.re-frame2-causa.static.machines.sim-cljs-test
  "CLJS-side wiring + view + integration tests for the Static Machines
  Sim sub-mode (rf2-r4nao rehost; engine originally rf2-v869p Phase 2,
  parent rf2-2tkza).

  ## What's under test (in addition to the pure-data tests in
  `sim_helpers_cljs_test.cljc`)

    1. **Registry** wires the `:rf.causa.static.machines/sim-*` sub +
       event family under `:rf/causa`.

    2. **Sim start** clones the machine definition into Causa state +
       seeds the initial snapshot (production registry untouched).

    3. **Sim step** with a valid event → engine OK Result → snapshot
       advances + audit-trail grows. Sim is hermetic — the host's
       app-db / registered-machine snapshots stay put.

    4. **Sim step** with a fail-Result → snapshot stays + `:last-error`
       populated.

    5. **Sim reset** rewinds the snapshot, clears the trail, preserves
       the active flag.

    6. **Sim stop** disposes the per-machine slot (no leak in Causa's
       `:rf.causa.static.machines/sim-by-machine` map).

    7. **Sim rail** mounts when active + carries the testid hooks the
       design calls out (banner, event input, step button, reset button,
       exit button, audit trail).

    8. **Body auto-start** — `sim/body` dispatches `:sim-start` when no
       sim-state exists yet for the selected machine + definition.

    9. **Frame isolation** — sim state stays on `:rf/causa`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [day8.re-frame2-causa.registry :as registry]
            [day8.re-frame2-causa.static.machines.sim :as sim]
            [day8.re-frame2-causa.test-support :as causa-test-support]
            [day8.re-frame2-causa.trace-bus :as trace-bus]))

;; ---- fixtures -----------------------------------------------------------

(defn- causa-init! []
  (causa-test-support/reset-all!)
  (trace-bus/clear-buffer!))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn causa-init!}))

(defn- setup-causa-frame! []
  (registry/register-causa-handlers!)
  (frame/reg-frame :rf/causa {}))

;; ---- hiccup walker -------------------------------------------------------

(declare expand-fn-component)

(defn- expand-children [node]
  (cond
    (vector? node) (mapv expand-fn-component node)
    (seq? node)    (map  expand-fn-component node)
    :else          node))

(defn- expand-fn-component [node]
  (if (and (vector? node) (fn? (first node)))
    (expand-children (apply (first node) (rest node)))
    (expand-children node)))

(defn- hiccup-seq [tree]
  (let [expanded (expand-fn-component tree)]
    (tree-seq (some-fn vector? seq?) seq expanded)))

(defn- find-by-testid [tree testid]
  (some (fn [node]
          (when (and (vector? node)
                     (map? (second node))
                     (= testid (:data-testid (second node))))
            node))
        (hiccup-seq tree)))

(defn- find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (hiccup-seq tree)))

;; ---- fixture data --------------------------------------------------------

(def ^:private fixture-definition
  {:initial :idle
   :data    {:counter 0}
   :states  {:idle    {:on {:start :authing}}
             :authing {:on {:ok :done :err :failed}}
             :done    {:final? true}
             :failed  {:final? true}}})

(defn- override-machines! [machines]
  (rf/dispatch-sync
    [:rf.causa/set-registered-machines-override-for-test machines]))

(defn- override-snapshots! [snapshots]
  (rf/dispatch-sync
    [:rf.causa/set-machine-snapshots-override-for-test snapshots]))

(defn- override-definitions! [definitions]
  (rf/dispatch-sync
    [:rf.causa/set-machine-definitions-override-for-test definitions]))

(defn- select-static-machine! [machine-id]
  (rf/dispatch-sync [:rf.causa.static.machines/select machine-id]))

(def ^:private ok-result
  {:re-frame.machines.result/tag :ok
   :re-frame.machines.result/snap {:state :authing :data {:counter 1}}
   :re-frame.machines.result/fx []})

(def ^:private fail-result
  {:re-frame.machines.result/tag :fail
   :re-frame.machines.result/info {:reason :no-matching-transition}})

;; ---- (1) registry wiring ------------------------------------------------

(deftest registry-installs-sim-handlers
  (testing "register-causa-handlers! installs every rf2-r4nao Sim handler"
    (registry/register-causa-handlers!)
    (is (some? (registrar/handler :sub :rf.causa.static.machines/sim-by-machine)))
    (is (some? (registrar/handler :sub :rf.causa.static.machines/sim-state)))
    (is (some? (registrar/handler :sub :rf.causa.static.machines/sim-active?)))
    (is (some? (registrar/handler :sub :rf.causa.static.machines/sim-available-transitions)))
    (is (some? (registrar/handler :sub :rf.causa.static.machines/sim-event-suggestions)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-start)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-step)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-reset)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-stop)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-set-pending-event)))
    (is (some? (registrar/handler :event :rf.causa.static.machines/sim-set-pending-data)))))

;; ---- (2) sim-start ------------------------------------------------------

(deftest sim-start-clones-definition-and-seeds-snapshot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
      (is (true? (:active? sim)))
      (is (= :auth/login (:machine-id sim)))
      (is (= fixture-definition (:definition sim))
          "definition cloned into Causa state")
      (is (= :idle (get-in sim [:snapshot :state])))
      (is (= {:counter 0} (get-in sim [:snapshot :data]))))))

(deftest sim-start-does-not-touch-production-registry
  (testing "sim isolation — Causa's overrides for the *production*
            machine surfaces (the registered set + snapshot map) are
            unchanged by sim-start"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-snapshots!   {:auth/login {:state :idle :data {:counter 99}}})
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      ;; Production-side snapshot still has counter 99 — sim's clone
      ;; started fresh from the definition's :data slot.
      (let [snaps @(rf/subscribe [:rf.causa/machine-snapshots-override])]
        (is (= 99 (get-in snaps [:auth/login :data :counter]))
            "the production snapshot's :data is untouched"))
      ;; Sim's snapshot started clean from the definition's initial :data.
      (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
        (is (= 0 (-> sim :snapshot :data :counter))
            "the sim snapshot used the definition's :data, not the live snapshot")))))

;; ---- (3) sim-step OK ----------------------------------------------------

(deftest sim-step-ok-advances-snapshot-and-trail
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    ;; Stub the engine to return an OK Result without booting the
    ;; machines artefact.
    (with-redefs [rf/machine-transition (fn [_def _snap _event] ok-result)]
      (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
      (is (= :authing (get-in sim [:snapshot :state]))
          "snapshot advanced")
      (is (= {:counter 1} (get-in sim [:snapshot :data])))
      (is (= 1 (count (:audit-trail sim))))
      (is (= :idle (-> sim :audit-trail last :from)))
      (is (= :authing (-> sim :audit-trail last :to)))
      (is (= [:start] (-> sim :audit-trail last :event)))
      (is (nil? (:last-error sim))))))

;; ---- (4) sim-step FAIL --------------------------------------------------

(deftest sim-step-fail-leaves-snapshot-and-records-error
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot unchanged on fail")
      (is (= 0 (count (:audit-trail sim)))
          "trail unchanged on fail")
      (is (= [:bad] (-> sim :last-error :event))
          "error stamped onto sim state"))))

(deftest sim-step-engine-throw-treated-as-fail
  (testing "When the machines artefact is not on the classpath,
            `rf/machine-transition` throws; the sim handler catches and
            synthesises a fail-Result so the user sees an error instead
            of a runtime crash."
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e]
                                            (throw (js/Error. "no artefact")))]
        (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                           {:machine-id :auth/login
                            :event [:start]}]))
      (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
        (is (= :idle (get-in sim [:snapshot :state])))
        (is (some? (:last-error sim)))))))

;; ---- (5) sim-reset ------------------------------------------------------

(deftest sim-reset-rewinds-snapshot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    ;; Confirm we moved
    (is (= :authing (-> @(rf/subscribe [:rf.causa.static.machines/sim-state])
                        :snapshot :state)))
    (rf/dispatch-sync [:rf.causa.static.machines/sim-reset
                       {:machine-id :auth/login}])
    (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
      (is (true? (:active? sim))
          "still in sim mode after reset")
      (is (= :idle (get-in sim [:snapshot :state]))
          "snapshot rewound to initial")
      (is (= [] (:audit-trail sim))
          "trail cleared")
      (is (nil? (:last-error sim))))))

;; ---- (6) sim-stop --------------------------------------------------------

(deftest sim-stop-disposes-per-machine-slot
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (is (some? @(rf/subscribe [:rf.causa.static.machines/sim-state])))
    (rf/dispatch-sync [:rf.causa.static.machines/sim-stop
                       {:machine-id :auth/login}])
    (is (nil? @(rf/subscribe [:rf.causa.static.machines/sim-state]))
        "per-machine slot deleted on stop")
    (let [by-machine @(rf/subscribe [:rf.causa.static.machines/sim-by-machine])]
      (is (not (contains? by-machine :auth/login))
          "Causa's sim-by-machine map carries no entry for the stopped sim"))))

;; ---- (7) sim rail (the in-body content rail) ----------------------------

(deftest rail-renders-nothing-when-sim-inactive
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (is (nil? (sim/SimRail))
        "rail returns nil when sim is inactive")))

(deftest rail-mounts-when-sim-active
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-rail"))
          "rail present when sim is on")
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-banner")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-current-state")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-event-input")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-data-input")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-step-button")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-reset-button")))
      (is (some? (find-by-testid tree "rf-causa-static-machines-sim-exit-button"))))))

(deftest rail-renders-available-transitions
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)
          available (find-all-by-testid-prefix
                      tree "rf-causa-static-machines-sim-available-")]
      (is (some? (find-by-testid
                   tree "rf-causa-static-machines-sim-available-list")))
      ;; :idle declares :start — should be in the available list.
      (is (some #(= "rf-causa-static-machines-sim-available-start"
                    (:data-testid (second %)))
                available)))))

(deftest rail-renders-audit-trail-after-step
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-causa-static-machines-sim-audit-empty"))
          "empty audit message before any steps"))
    (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
      (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:start]}]))
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-causa-static-machines-sim-audit-list")))
      (is (some? (find-by-testid
                   tree "rf-causa-static-machines-sim-audit-0"))
          "one audit row after one step"))))

(deftest rail-surfaces-error-on-fail
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (override-machines!    [:auth/login])
    (override-definitions! {:auth/login fixture-definition})
    (select-static-machine! :auth/login)
    (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                       {:machine-id :auth/login
                        :definition fixture-definition}])
    (with-redefs [rf/machine-transition (fn [_d _s _e] fail-result)]
      (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                         {:machine-id :auth/login
                          :event [:bad]}]))
    (let [tree (sim/SimRail)]
      (is (some? (find-by-testid
                   tree "rf-causa-static-machines-sim-error"))
          "error toast surfaces inline"))))

;; ---- (8) body auto-start -----------------------------------------------

(deftest body-auto-starts-sim-when-definition-present
  (testing "sim/body dispatches :sim-start when called without an
            existing sim-state — the auto-start lands the slot so the
            next subscribe re-fires with the rail populated"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      ;; Pre-condition: no sim-state for the machine.
      (is (nil? @(rf/subscribe [:rf.causa.static.machines/sim-state])))
      ;; Render the body — auto-start fires via rf/dispatch (async).
      (let [_tree (sim/body {:machine-id :auth/login
                             :definition fixture-definition})]
        ;; Drain the event queue so the dispatched :sim-start lands.
        (rf/dispatch-sync [:rf.causa.static.machines/sim-set-pending-data
                           {:machine-id :auth/login :text ""}]))
      ;; The auto-started slot should exist post-flush.
      (let [sim @(rf/subscribe [:rf.causa.static.machines/sim-state])]
        (is (some? sim) "sim-state landed via the body's auto-start")
        (is (= :idle (get-in sim [:snapshot :state])))))))

(deftest body-renders-no-definition-hint-when-missing
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (select-static-machine! :auth/login)
    (let [tree (sim/body {:machine-id :auth/login :definition nil})]
      (is (some? (find-by-testid tree
                                 "rf-causa-static-machines-sim-no-definition"))))))

(deftest body-renders-no-machine-hint-when-missing
  (setup-causa-frame!)
  (rf/with-frame :rf/causa
    (let [tree (sim/body {:machine-id nil :definition fixture-definition})]
      (is (some? (find-by-testid tree
                                 "rf-causa-static-machines-sim-no-machine"))))))

;; ---- (9) frame isolation -----------------------------------------------

(deftest sim-state-does-not-leak-into-default-frame
  (testing "sim state lives on :rf/causa, never :rf/default"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}]))
    (let [causa-db   (frame/frame-app-db-value :rf/causa)
          default-db (frame/frame-app-db-value :rf/default)]
      (is (some? (:rf.causa.static.machines/sim-by-machine causa-db))
          "sim slot lands on Causa")
      (is (nil? (:rf.causa.static.machines/sim-by-machine default-db))
          "sim slot did NOT leak into :rf/default"))))

;; ---------------------------------------------------------------------------
;; rf2-ppzid — React unique-key warning regression guard (preserved from the
;; rf2-r4nao rehost source; see ns docstring in `static/machines/sim.cljs`).
;;
;; Two `for` loops in the Sim rail wrap function-call list forms — the
;; available-transition rows and audit-trail rows. Reagent's
;; `get-react-key` only reads `:key` from vector meta, so the keys must
;; land on the returned `[:li …]` vectors via `with-meta` rather than
;; reader-meta on the source list. This test asserts the meta is
;; preserved across the rehost.
;; ---------------------------------------------------------------------------

(defn- meta-preserving-children [node]
  (cond
    (and (vector? node) (fn? (first node)))
    [(apply (first node) (rest node))]

    (vector? node)
    (if (map? (second node))
      (drop 2 node)
      (rest node))

    (seq? node)
    node

    :else nil))

(defn- raw-find-all-by-testid-prefix [tree prefix]
  (filter (fn [node]
            (and (vector? node)
                 (map? (second node))
                 (some-> (:data-testid (second node))
                         (.startsWith prefix))))
          (tree-seq (some-fn vector? seq?) meta-preserving-children tree)))

(deftest sim-available-transitions-carry-key-meta
  (testing "available-transition-row for-loop ships per-transition <li>
            children with :key meta on the returned vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (let [tree      (sim/SimRail)
            available (raw-find-all-by-testid-prefix
                        tree "rf-causa-static-machines-sim-available-")
            ;; Drop the container <ul> (testid `…-available-list`); we
            ;; want only the per-row <li> children.
            li-rows   (remove
                        (fn [n]
                          (= "rf-causa-static-machines-sim-available-list"
                             (:data-testid (second n))))
                        available)]
        (is (>= (count li-rows) 1) "at least one available-transition row")
        (doseq [row li-rows]
          (is (vector? row) "available-transition row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "available-transition row carries :key meta — got "
                   (pr-str (meta row)))))))))

(deftest sim-audit-trail-rows-carry-key-meta
  (testing "audit-trail-row for-loop ships per-step <li> children with
            :key meta on the returned vector (rf2-ppzid)"
    (setup-causa-frame!)
    (rf/with-frame :rf/causa
      (override-machines!    [:auth/login])
      (override-definitions! {:auth/login fixture-definition})
      (select-static-machine! :auth/login)
      (rf/dispatch-sync [:rf.causa.static.machines/sim-start
                         {:machine-id :auth/login
                          :definition fixture-definition}])
      (with-redefs [rf/machine-transition (fn [_d _s _e] ok-result)]
        (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                           {:machine-id :auth/login :event [:start]}])
        (rf/dispatch-sync [:rf.causa.static.machines/sim-step
                           {:machine-id :auth/login :event [:ok]}]))
      (let [tree (sim/SimRail)
            rows (raw-find-all-by-testid-prefix
                   tree "rf-causa-static-machines-sim-audit-")
            ;; Drop the container <ol> (testid `…-audit-list`).
            li-rows (remove
                      (fn [n]
                        (or (= "rf-causa-static-machines-sim-audit-list"
                               (:data-testid (second n)))
                            (= "rf-causa-static-machines-sim-audit-empty"
                               (:data-testid (second n)))))
                      rows)]
        (is (>= (count li-rows) 2) "two audit rows after two steps")
        (doseq [row li-rows]
          (is (vector? row) "audit row is a hiccup vector")
          (is (some? (some-> (meta row) :key))
              (str "audit row carries :key meta — got "
                   (pr-str (meta row)))))))))
