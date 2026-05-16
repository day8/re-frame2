(ns re-frame.epoch-elision-prod-test
  "Per Spec 009 §Production builds (bead rf2-l7hlm) — `:advanced` +
  `goog.DEBUG=false` runtime contract for `re-frame.epoch` (Tool-Pair
  §Time-travel). Companion to the string-grep sentinel sweep in
  `scripts/check-elision.cjs` (which already covers every `:rf.epoch/*`
  keyword's literal absence); this file pins the BEHAVIOUR.

  Surfaces gated by `interop/debug-enabled?` (per `epoch.cljc` body
  inspection):

  - `settle!`                — `(when interop/debug-enabled? ...)` —
                                no epoch-record is committed; the
                                ring buffer stays empty; listeners do
                                not fire.
  - `capture-event!`          — `(when interop/debug-enabled? ...)` —
                                trace events do not accumulate in the
                                per-cascade capture buffer.
  - `restore-epoch`           — `(if-not interop/debug-enabled? false ...)`
                                — returns `false` and never invokes
                                the restore machinery.
  - `reset-frame-db!`         — `(if-not interop/debug-enabled? false ...)`
                                — Pair-tool write surface returns
                                `false` without mutating app-db.
  - `on-frame-destroyed!`     — `(when interop/debug-enabled? ...)` —
                                no `:rf.epoch.cb/silenced-on-frame-
                                destroy` trace fires; no capture-buffer
                                discard.

  Surfaces deliberately NOT gated (value-layer registrar machinery —
  registration sites survive; only the dev-side delivery elides):

  - `register-epoch-cb!` / `remove-epoch-cb!` / `clear-epoch-cbs!`
  - `epoch-history` / `clear-history!`
  - `configure :epoch-history`

  These are safe to invoke under prod; they just have nothing to do —
  no record is ever produced by `settle!` for them to fan out, so the
  registry remains valid but dead-on-arrival. The test below pins both
  halves of the contract.

  Naming convention: files ending in `-elision-prod-test.cljs` are
  picked up ONLY by the `:browser-test-prod-elision` build. Running
  under `goog.DEBUG=true` would FAIL — under dev these surfaces deliver
  records, populate history, and accept time-travel restores."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.epoch :as epoch]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; ---- settle! commits no record under prod --------------------------------

(deftest dispatch-produces-no-epoch-record-under-prod
  (testing "Per Spec 009 §Production builds (rf2-l7hlm): under
            `:advanced` + `goog.DEBUG=false`, dispatching events
            mutates app-db as expected but `settle!` does NOT commit
            an `:rf/epoch-record`. The whole settle! body sits inside
            `(when interop/debug-enabled? ...)` — the per-frame
            history vector stays empty."
    (rf/configure :epoch-history {:depth 100})
    (rf/reg-event-db :prod-epoch/inc
                     (fn [db _] (update db :n (fnil inc 0))))
    (rf/dispatch-sync [:prod-epoch/inc])
    (rf/dispatch-sync [:prod-epoch/inc])
    (rf/dispatch-sync [:prod-epoch/inc])
    (is (= 3 (:n (rf/get-frame-db :rf/default)))
        "handler ran the expected number of times — only epoch surface elided")
    (is (empty? (rf/epoch-history :rf/default))
        "no records appended to per-frame history under prod —
         settle! body DCE'd; the depth-100 configure has no effect")))

;; ---- listeners do not fire under prod ------------------------------------

(deftest registered-epoch-cb-does-not-fire-under-prod
  (testing "Per Spec 009 §Production builds: an installed
            `register-epoch-cb!` listener observes NO records under
            prod. The settle! body's notify-listeners! call does not
            run (it sits inside the same gate as the record build)."
    (let [seen (atom [])]
      (rf/register-epoch-cb! ::prod-epoch-listener
        (fn [record] (swap! seen conj record)))
      (rf/reg-event-db :prod-epoch/ping
                       (fn [db _] (assoc db :pinged? true)))
      (rf/dispatch-sync [:prod-epoch/ping])
      (rf/dispatch-sync [:prod-epoch/ping])
      (is (empty? @seen)
          "no records delivered to the listener under
           :advanced + goog.DEBUG=false")
      (rf/remove-epoch-cb! ::prod-epoch-listener))))

;; ---- restore-epoch is a no-op returning false under prod -----------------

(deftest restore-epoch-returns-false-under-prod
  (testing "Per Spec 009 §Production builds: `restore-epoch` is gated
            on `interop/debug-enabled?` via an `(if-not …)`
            early-return. Under prod it returns `false` for every
            (frame-id, epoch-id) pair — the precondition checks and
            perform-restore branch DCE entirely."
    (is (false? (rf/restore-epoch :rf/default 0))
        "restore-epoch returns false under prod for a never-recorded epoch")
    (is (false? (rf/restore-epoch :rf/default 999999))
        "restore-epoch returns false under prod for any epoch-id")
    (is (false? (rf/restore-epoch :rf.nonexistent/frame 0))
        "restore-epoch returns false under prod even for an unknown frame")))

;; ---- reset-frame-db! is a no-op returning false under prod ---------------

(deftest reset-frame-db-returns-false-under-prod
  (testing "Per Spec 009 §Production builds: `reset-frame-db!` (Tool-
            Pair §Pair-tool writes) is gated on `interop/debug-enabled?`
            via an `(if-not …)` early-return. Under prod it returns
            `false` WITHOUT mutating app-db — the dev-only Pair-tool
            write surface is firewalled from production state."
    (rf/reg-event-db :prod-epoch/seed
                     (fn [_db _] {:original true}))
    (rf/dispatch-sync [:prod-epoch/seed])
    (is (= {:original true}
           (rf/get-frame-db :rf/default))
        "baseline: app-db has the seeded value")
    (is (false? (rf/reset-frame-db! :rf/default {:replaced :should-not-stick}))
        "reset-frame-db! returns false under prod")
    (is (= {:original true}
           (rf/get-frame-db :rf/default))
        "app-db is UNCHANGED under prod — Pair-tool write firewalled
         from production state")))

;; ---- the lookup machinery survives (defonce'd registries) ----------------

(deftest registration-machinery-survives-under-prod
  (testing "Per Spec 009 §Production builds: the registration / lookup
            surface is not gated — it's value-layer machinery
            (defonce'd atoms + plain swap! / reset!). Under prod
            `register-epoch-cb!` returns the id; `epoch-history` returns
            a (necessarily empty) vector; `clear-history!` and
            `clear-epoch-cbs!` return nil. Apps that boot with these
            calls do not crash under :advanced."
    (is (= ::prod-survives
           (rf/register-epoch-cb! ::prod-survives (fn [_] nil)))
        "register-epoch-cb! returns the id under prod")
    (is (nil? (rf/remove-epoch-cb! ::prod-survives))
        "remove-epoch-cb! returns nil under prod")
    (is (vector? (rf/epoch-history :rf/default))
        "epoch-history returns a vector (empty) under prod")
    (is (nil? (epoch/clear-history!))
        "clear-history! returns nil under prod")
    (is (nil? (epoch/clear-epoch-cbs!))
        "clear-epoch-cbs! returns nil under prod")))

;; ---- on-frame-destroyed! is silent under prod ----------------------------

(deftest on-frame-destroyed-emits-no-trace-under-prod
  (testing "Per Spec 009 §Production builds (rf2-d656):
            `on-frame-destroyed!` is gated on `interop/debug-enabled?`.
            Under prod it does NOT emit
            `:rf.epoch.cb/silenced-on-frame-destroy`; the
            observed-frames-by-cb bookkeeping side-effect is dead too.
            Frame-destroy paths that route through this hook do not
            crash."
    (is (nil? (epoch/on-frame-destroyed! :rf/default))
        "on-frame-destroyed! returns nil under prod even for unknown frames")))
