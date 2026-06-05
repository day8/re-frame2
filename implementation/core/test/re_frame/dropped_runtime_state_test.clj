(ns re-frame.dropped-runtime-state-test
  "Spec 009 — `:rf.warning/runtime-state-dropped` (rf2-p806o).

  The loud-failure guard for the silent `{:db fresh-map}` footgun: an event
  handler that returns a from-scratch `:db` effect WHOLESALE-REPLACES app-db
  and silently drops every live `:rf/runtime` subsystem (machine snapshots,
  routing slice, elision declarations, ssr hydration) with it. The clobber is
  intended (Conventions §Reserved app-db keys — `:rf/runtime` lives in app-db
  so it reverts atomically on restore-epoch / time-travel), so the fix is a
  DIAGNOSTIC, not a merge.

  These are core-only unit tests over the structural discriminator — they
  seed `:rf/runtime` directly (no machines artefact required) and drive the
  real dispatch / commit path. The machines-artefact integration tests (a
  boot-machine cascade fires; restore-epoch / reset-frame-db / `:rf/hydrate`
  do NOT) live in `implementation/machines`.

  JVM-only by intent — the commit path is substrate-independent.

  Per Spec 009 the warning is dev-only (`interop/debug-enabled?`-gated)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling]))

;; ---- fixtures --------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- capture-warnings
  "Dispatch-sync the seed event (to install a starting db), register a
  recorder, dispatch-sync the mutate event, and return the captured
  `:rf.warning/runtime-state-dropped` trace events."
  [seed-db mutate-handler]
  (let [recorded (atom [])]
    (rf/reg-event-db ::seed (fn [_ _] seed-db))
    (rf/reg-event-db ::mutate mutate-handler)
    (rf/dispatch-sync [::seed])
    (rf/register-listener! ::rec (fn [ev] (swap! recorded conj ev)))
    (rf/dispatch-sync [::mutate])
    (rf/unregister-listener! ::rec)
    (->> @recorded
         (filter #(= :rf.warning/runtime-state-dropped (:operation %)))
         vec)))

(def ^:private seed-with-machine
  {:app-state 1
   :rf/runtime {:machines {:snapshots {:login {:state :idle :data {}}
                                       :cart  {:state :empty :data {}}}}}})

;; ---- the footgun fires -----------------------------------------------------

(deftest fresh-db-drops-machine-snapshots-fires
  (testing "a `{:db fresh-map}` that drops live machine snapshots fires :rf.warning/runtime-state-dropped"
    (let [warnings (capture-warnings
                     seed-with-machine
                     ;; The footgun: build a fresh db from scratch, forgetting
                     ;; :rf/runtime entirely.
                     (fn [_db _] {:app-state 2}))]
      (is (= 1 (count warnings))
          "exactly one warning for the single dropped :machines subsystem")
      (let [{:keys [tags op-type recovery]} (first warnings)]
        (is (= :warning op-type))
        (is (= :no-recovery recovery))
        (is (= :machines (:subsystem tags)))
        (is (= #{:login :cart} (:dropped-machine-ids tags))
            "names exactly the machine-ids whose snapshot vanished")
        (is (string? (:reason tags)))
        (is (re-find #"silent no-ops" (:reason tags))
            "the reason explains the silent-death consequence")))))

(deftest fresh-db-drops-multiple-subsystems-one-warning-each
  (testing "dropping several non-empty :rf/runtime subsystems fires one warning per subsystem"
    (let [warnings (capture-warnings
                     {:rf/runtime {:machines {:snapshots {:m1 {:state :a :data {}}}}
                                   :routing  {:current {:id :home}}
                                   :ssr      {:hydration {:server-hash "abc"}}}}
                     (fn [_db _] {:fresh true}))]
      (is (= 3 (count warnings))
          "one warning each for :machines, :routing, :ssr")
      (is (= #{:machines :routing :ssr}
             (set (map #(get-in % [:tags :subsystem]) warnings))))
      (is (= #{:m1}
             (->> warnings
                  (filter #(= :machines (get-in % [:tags :subsystem])))
                  first :tags :dropped-machine-ids))
          "only the :machines warning carries :dropped-machine-ids")
      (is (every? #(nil? (get-in % [:tags :dropped-machine-ids]))
                  (remove #(= :machines (get-in % [:tags :subsystem])) warnings))
          "non-machine subsystem warnings carry no :dropped-machine-ids"))))

;; ---- the discriminator: legitimate writes do NOT fire ----------------------

(deftest changing-snapshot-value-does-not-fire
  (testing "a `:db` write that CHANGES a machine snapshot (still present post-commit) does not fire"
    (let [warnings (capture-warnings
                     seed-with-machine
                     ;; Transition :login idle -> active, drop nothing.
                     (fn [db _]
                       (assoc-in db [:rf/runtime :machines :snapshots :login :state] :active)))]
      (is (empty? warnings)
          "the subsystem is still non-empty post-commit — not a drop"))))

(deftest removing-one-snapshot-via-raw-db-write-fires
  (testing "dropping ONE machine snapshot via a raw `:db` write fires (the proper teardown is the :rf.machine/destroy fx, not a :db dissoc)"
    ;; Per-machine granularity: a raw `:db` write that removes :cart's
    ;; snapshot (leaving :login) IS a dropped live machine with no
    ;; replacement. The idiomatic teardown (`:rf.machine/destroy`) mutates
    ;; via `swap-frame-db!` in do-fx — never a `:db` effect — so it never
    ;; reaches this guard. A raw `:db` dissoc bypasses that path and is
    ;; exactly the silent-death class the guard surfaces.
    (let [warnings (capture-warnings
                     seed-with-machine
                     (fn [db _]
                       (update-in db [:rf/runtime :machines :snapshots] dissoc :cart)))]
      (is (= 1 (count warnings)))
      (is (= #{:cart} (get-in (first warnings) [:tags :dropped-machine-ids]))
          "names exactly the dropped machine — :login is unaffected"))))

(deftest db-effect-preserving-runtime-does-not-fire
  (testing "a fresh db that THREADS :rf/runtime through (the recommended fix) does not fire"
    (let [warnings (capture-warnings
                     seed-with-machine
                     (fn [db _] {:app-state 2 :rf/runtime (:rf/runtime db)}))]
      (is (empty? warnings)
          "the snapshots survive the replace — no drop"))))

(deftest empty-runtime-before-does-not-fire
  (testing "no live subsystem pre-commit means a wholesale replace drops nothing"
    (let [warnings (capture-warnings
                     {:app-state 1}        ;; no :rf/runtime at all
                     (fn [_db _] {:fresh true}))]
      (is (empty? warnings)
          "this is the :rf/hydrate-at-boot shape — nothing live to drop"))))

(deftest empty-subsystem-before-does-not-fire
  (testing "a subsystem present-but-empty pre-commit is not a live subsystem; dropping it does not fire"
    (let [warnings (capture-warnings
                     {:rf/runtime {:machines {:snapshots {}}}}
                     (fn [_db _] {:fresh true}))]
      (is (empty? warnings)
          "an empty :snapshots map carries no live machine — `seq` guards it"))))

(deftest no-db-effect-does-not-fire
  (testing "an event that returns no :db effect cannot drop anything"
    ;; A reg-event-FX handler returning `{}` (no :db slot) leaves app-db
    ;; untouched — `commit-db-effect!` short-circuits before the guard.
    (let [recorded (atom [])]
      (rf/reg-event-db ::seed (fn [_ _] seed-with-machine))
      (rf/reg-event-fx ::noop (fn [_ _] {}))
      (rf/dispatch-sync [::seed])
      (rf/register-listener! ::rec (fn [ev] (swap! recorded conj ev)))
      (rf/dispatch-sync [::noop])
      (rf/unregister-listener! ::rec)
      (is (empty? (->> @recorded
                       (filter #(= :rf.warning/runtime-state-dropped (:operation %)))))))))
