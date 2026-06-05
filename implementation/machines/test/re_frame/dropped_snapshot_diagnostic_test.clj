(ns re-frame.dropped-snapshot-diagnostic-test
  "Spec 005 / Spec 009 — `:rf.warning/runtime-state-dropped` against a REAL
  live machine (rf2-p806o).

  The loud-failure guard fires when a durable `:db` commit drops a live
  machine snapshot under `[:rf/runtime :machines :snapshots <id>]` with no
  replacement — the silent `{:db fresh-map}` footgun (a v1→v2 boot-machine
  action that rebuilds app-db from scratch and forgets `:rf/runtime`, killing
  every live machine). The core-side structural unit tests live in
  `implementation/core` (`re-frame.dropped-runtime-state-test`); these are the
  machines-artefact integration tests that exercise the guard with a genuine
  registered machine and prove the discriminator does not false-fire on the
  legitimate wholesale-replace mechanisms.

  JVM-only by intent — the commit path is substrate-independent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private toggle-spec
  {:initial :off
   :states  {:off {:on {:flip :on}}
             :on  {:on {:flip :off}}}})

(defn- live-snapshot?
  [machine-id]
  (some? (get-in (rf/app-db-value :rf/default)
                 [:rf/runtime :machines :snapshots machine-id])))

(defn- with-recorder
  "Register a recorder, run `body-fn`, return captured
  :rf.warning/runtime-state-dropped events."
  [body-fn]
  (let [recorded (atom [])
        id       (gensym "dropped-snap-rec")]
    (trace/register-listener! id (fn [ev] (swap! recorded conj ev)))
    (try (body-fn)
         (finally (trace/unregister-listener! id)))
    (->> @recorded
         (filter #(= :rf.warning/runtime-state-dropped (:operation %)))
         vec)))

(defn- register-live-machine!
  "Register `toggle-spec` under :diag/m1 and drive it once so a live
  snapshot is installed at [:rf/runtime :machines :snapshots :diag/m1]."
  []
  (rf/reg-machine :diag/m1 toggle-spec)
  (rf/dispatch-sync [:diag/m1 [:flip]])
  (assert (live-snapshot? :diag/m1) "machine must be live before the test body"))

;; ---- the footgun fires against a real machine ------------------------------

(deftest boot-machine-style-fresh-db-drops-live-machine-fires
  (testing "an event returning {:db fresh-map} that forgets :rf/runtime kills a live machine and fires the warning"
    (register-live-machine!)
    ;; The footgun: a handler (here a plain event, but the same shape a
    ;; boot-machine action emits inside a cascade) rebuilds app-db from
    ;; scratch, dropping :rf/runtime — and with it the live :diag/m1 snapshot.
    (rf/reg-event-db :diag/reboot (fn [_db _] {:fresh-app-state true}))
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/reboot]))]
      (is (= 1 (count warnings)))
      (let [{:keys [tags recovery]} (first warnings)]
        (is (= :machines (:subsystem tags)))
        (is (= #{:diag/m1} (:dropped-machine-ids tags))
            "names the real live machine that just died")
        (is (= :no-recovery recovery)))
      (is (not (live-snapshot? :diag/m1))
          "the machine really is dead — its snapshot is gone"))))

;; ---- discriminator: the idiomatic destroy fx does NOT fire -----------------

(deftest idiomatic-transition-does-not-fire
  (testing "an ordinary machine transition (snapshot changes, not dropped) does not fire"
    (register-live-machine!)
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/m1 [:flip]]))]
      (is (empty? warnings)
          "a transition mutates the snapshot in place — no drop"))))

;; ---- discriminator: direct-replace mechanisms bypass the :db commit path ---

(deftest direct-container-replace-bypasses-the-guard
  (testing "restore-epoch / reset-frame-db install a COMPLETE db via a direct adapter/replace-container! — they never flow through a :db effect, so they never reach the guard"
    (register-live-machine!)
    ;; Reproduce exactly what re-frame.epoch's restore-epoch / reset-frame-db
    ;; do: call adapter/replace-container! directly with a complete db that
    ;; carries NO machine snapshot (e.g. restoring to a pre-spawn epoch).
    ;; Because this is not a `:db` effect, `commit-db-effect!` is never on the
    ;; stack and the guard cannot fire.
    (let [container (frame/app-db-container :rf/default)
          warnings  (with-recorder
                      #(adapter/replace-container! container {:restored-past true}))]
      (is (empty? warnings)
          "a direct container replace is not a :db commit — out of the guard's scope")
      (is (not (live-snapshot? :diag/m1))
          "the snapshot IS gone — but legitimately, via the revertible-restore path"))))

;; ---- discriminator: a complete-db :db replace carrying its OWN snapshots ---

(deftest hydrate-shape-replace-carrying-snapshots-does-not-fire
  (testing ":rf/hydrate-shape — a :db effect that installs a COMPLETE db carrying its own machine snapshot does not fire (the snapshot has a replacement)"
    (register-live-machine!)
    ;; The :rf/hydrate handler returns {:db <server's complete db>}. When the
    ;; server ran the machine, that db carries the snapshot, so the live
    ;; machine is NOT dropped — it is replaced. Simulate that shape.
    (rf/reg-event-db :diag/hydrate
                     (fn [db _]
                       {:server-state true
                        :rf/runtime {:machines {:snapshots
                                                {:diag/m1 (get-in db [:rf/runtime :machines
                                                                      :snapshots :diag/m1])}}}}))
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/hydrate]))]
      (is (empty? warnings)
          "the snapshot is present post-commit — a replacement, not a drop")
      (is (live-snapshot? :diag/m1)
          "the machine survived the wholesale replace"))))
