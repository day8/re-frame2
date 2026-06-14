(ns re-frame.dropped-snapshot-diagnostic-test
  "Spec 005 / Conventions §The clobber footgun is eliminated structurally —
  the `{:db fresh-map}` footgun is GONE under the two-partition frame
  (EP-0001 rf2-vzld77).

  Pre-migration, machine snapshots sat under an `:rf/runtime` root INSIDE
  app-db, so an event returning a from-scratch `:db` effect wholesale-replaced
  app-db and silently dropped every live machine snapshot — the footgun the
  `:rf.warning/runtime-state-dropped` diagnostic flagged. Under the
  two-partition contract machine snapshots live in the **runtime-db**
  partition at `[:rf.runtime/machines :snapshots <id>]`, and an ordinary `:db`
  effect replaces ONLY app-db — runtime-db is a partition the handler never
  holds. So a fresh-map `:db` return CANNOT touch a live machine snapshot:
  the footgun is structurally impossible, not merely warned.

  These machines-artefact integration tests prove that property end-to-end
  against a genuine registered machine: a from-scratch `:db` return leaves the
  machine ALIVE and the snapshot intact. (The legacy `:rf/runtime`-root hard
  error + the retirement of the now-vestigial `:rf.warning/runtime-state-dropped`
  diagnostic are bead 9 — rf2-tfepxu.)

  JVM-only by intent — the commit path is substrate-independent."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling]))

;; This ns keeps a bespoke reset-runtime fixture (it ALSO clears listeners
;; and reloads the machines ns) rather than reusing the shared
;; make-reset-runtime-fixture.
(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-nn0jqa): `init!` no longer synthesises `:rf/default`, and
  ;; machine fxs require a carried frame stamp. Register `:rf/default`
  ;; explicitly and pin it as the established scope for the body.
  (frame/ensure-default-frame!)
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(def ^:private toggle-spec
  {:initial :off
   :states  {:off {:on {:flip :on}}
             :on  {:on {:flip :off}}}})

;; snapshot lookup via the shared machines test-support (rf2-3l8lqe finding #4).
(defn- live-snapshot?
  [machine-id]
  (some? (mtest/snapshot machine-id)))

(defn- with-recorder
  "Register a recorder, run `body-fn`, return captured
  :rf.warning/runtime-state-dropped events. Routed through the shared
  `mtest/with-trace-capture` (rf2-3l8lqe finding #4) — guaranteed
  unregister in a `finally`."
  [body-fn]
  (mtest/with-trace-capture recorded
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

;; ---- the footgun is structurally GONE under the partition ------------------

(deftest fresh-db-return-cannot-drop-a-live-machine
  (testing "an event returning {:db fresh-map} leaves the live machine ALIVE — machine snapshots are runtime-db, an ordinary :db effect replaces ONLY app-db"
    (register-live-machine!)
    ;; The former footgun: a handler (here a plain event, but the same shape a
    ;; boot-machine action emits inside a cascade) rebuilds app-db from
    ;; scratch. Pre-migration this dropped the `:rf/runtime` app-db root and
    ;; with it the live :diag/m1 snapshot. Under the two-partition contract
    ;; the snapshot lives in runtime-db, which `:db` never holds.
    (rf/reg-event-db :diag/reboot (fn [_db _] {:fresh-app-state true}))
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/reboot]))]
      (is (empty? warnings)
          "no runtime-state-dropped warning — the footgun is structurally gone")
      (is (live-snapshot? :diag/m1)
          "the machine survives the from-scratch :db replace — its snapshot is runtime-db")
      (is (= {:fresh-app-state true} (rf/app-db-value :rf/default))
          "app-db is the fresh map; runtime-db (the snapshot) is untouched"))))

;; ---- an ordinary transition mutates the snapshot in place ------------------

(deftest idiomatic-transition-keeps-machine-alive
  (testing "an ordinary machine transition mutates the snapshot in place — machine stays alive, no drop"
    (register-live-machine!)
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/m1 [:flip]]))]
      (is (empty? warnings))
      (is (live-snapshot? :diag/m1)
          "a transition updates the runtime-db snapshot — the machine stays live"))))

;; ---- a direct runtime-db replace (restore-epoch! shape) DOES revert it ------

(deftest direct-runtime-db-replace-reverts-the-snapshot
  (testing "restore-epoch! / reset install a fresh runtime-db via a direct partition write — that legitimately reverts the snapshot"
    (register-live-machine!)
    ;; Reproduce the revertible-restore path: install a fresh runtime-db that
    ;; carries NO machine snapshot (e.g. restoring to a pre-spawn epoch) via
    ;; the runtime-db PARTITION write `frame/swap-runtime-db!`. This is the
    ;; LEGITIMATE way machine liveness reverts (Goal 2 — frame state
    ;; revertibility); no warning, the snapshot is gone by design.
    (let [warnings (with-recorder
                     #(frame/swap-runtime-db! :rf/default (constantly {})))]
      (is (empty? warnings)
          "a direct runtime-db replace is the revertible-restore path — no footgun")
      (is (not (live-snapshot? :diag/m1))
          "the snapshot IS gone — legitimately, via the runtime-db revert path"))))

;; ---- hydration carrying its own runtime-db snapshots keeps the machine -----

(deftest hydrate-shape-runtime-db-carrying-snapshots-keeps-machine
  (testing ":rf/hydrate-shape — installing a runtime-db that carries the machine snapshot keeps the machine alive"
    (register-live-machine!)
    ;; The reference :rf/hydrate handler installs the server's runtime-db slice
    ;; into the runtime-db partition (per the SSR hydration path). When the
    ;; server ran the machine, that slice carries the snapshot, so the live
    ;; machine is replaced-in-place, not dropped. Simulate that shape with a
    ;; framework-authority runtime-db effect.
    (rf/reg-event-fx :diag/hydrate
                     (fn [{rt :rf.db/runtime} _]
                       {:db {:server-state true}
                        :rf.db/runtime rt}))
    (let [warnings (with-recorder #(rf/dispatch-sync [:diag/hydrate]))]
      (is (empty? warnings)
          "the runtime-db snapshot is present post-commit — a replacement, not a drop")
      (is (live-snapshot? :diag/m1)
          "the machine survived hydration — its runtime-db snapshot rode along"))))
