(ns re-frame.dropped-snapshot-diagnostic-test
  "Spec 005 / Conventions §The clobber footgun is eliminated structurally —
  the `{:db fresh-map}` footgun is structurally impossible under the
  two-partition frame.

  Machine snapshots live in the **runtime-db** partition at
  `[:rf.runtime/machines :snapshots <id>]`, and an ordinary `:db` effect
  replaces ONLY app-db — runtime-db is a partition the handler never holds.
  So a fresh-map `:db` return CANNOT touch a live machine snapshot: the
  footgun is structurally impossible, not merely warned.

  This machines-artefact integration test proves that property end-to-end
  against a genuine registered machine: a from-scratch `:db` return leaves the
  machine ALIVE and the snapshot intact.

  JVM-only by intent — the commit path is substrate-independent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]))

;; This ns uses a bespoke reset-runtime fixture (it ALSO clears listeners
;; and reloads the machines ns) rather than reusing the shared
;; make-reset-runtime-fixture.
(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default`, and machine fxs require a
  ;; carried frame stamp. Register `:rf/default` explicitly and pin it as the
  ;; established scope for the body.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.machines :reload)
  (rf.machines/reset-timers!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private toggle-spec
  {:initial :off
   :states  {:off {:on {:flip :on}}
             :on  {:on {:flip :off}}}})

;; snapshot lookup via the shared machines test-support.
(defn- live-snapshot?
  [machine-id]
  (some? (rf.machines.test-support/snapshot machine-id)))

(defn- with-recorder
  "Register a recorder, run `body-fn`, return captured
  :rf.warning/runtime-state-dropped events. Routed through the shared
  `rf.machines.test-support/with-trace-capture` — guaranteed unregister in a `finally`."
  [body-fn]
  (rf.machines.test-support/with-trace-capture recorded
    (body-fn)
    (->> @recorded
         (filter #(= :rf.warning/runtime-state-dropped (:operation %)))
         vec)))

(defn- register-live-machine!
  "Register `toggle-spec` under :diag/m1 and drive it once so a live
  snapshot is installed at [:rf.runtime/machines :snapshots :diag/m1]."
  []
  (rf/reg-machine :diag/m1 toggle-spec)
  (rf/dispatch-sync [:diag/m1 [:flip]])
  (assert (live-snapshot? :diag/m1) "machine must be live before the test body"))

;; ---- the footgun is structurally impossible under the partition ------------

(deftest fresh-db-return-cannot-drop-a-live-machine
  (testing "an event returning {:db fresh-map} leaves the live machine ALIVE — machine snapshots are runtime-db, an ordinary :db effect replaces ONLY app-db"
    (register-live-machine!)
    ;; A handler (here a plain event, but the same shape a boot-machine
    ;; action emits inside a cascade) rebuilds app-db from scratch. Under the
    ;; two-partition contract the live :diag/m1 snapshot lives in runtime-db,
    ;; which `:db` never holds — so the from-scratch `:db` cannot drop it.
    (rf/reg-event :diag/reboot (fn [{:keys [db]} _] {:db {:fresh-app-state true}}))
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/reboot]))]
      (is (empty? warnings)
          "no runtime-state-dropped warning — the footgun is structurally impossible")
      (is (live-snapshot? :diag/m1)
          "the machine survives the from-scratch :db replace — its snapshot is runtime-db")
      (is (= {:fresh-app-state true} (rf/app-db-value :rf/default))
          "app-db is the fresh map; runtime-db (the snapshot) is untouched"))))
