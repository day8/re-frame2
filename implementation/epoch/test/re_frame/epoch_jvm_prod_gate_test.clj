(ns re-frame.epoch-jvm-prod-gate-test
  "Per rf2-0la4f (security audit): the epoch artefact's dev-only
  surfaces — `register-epoch-listener!`, `restore-epoch!`, `replace-app-db!`,
  the per-frame ring buffer carrying `:db-before` / `:db-after` /
  raw `:trace-events` — MUST honour the JVM-side production gate
  `re-frame.interop/debug-enabled?`. When the gate reads `false`
  (the SSR production posture per rf2-vnjfg / rf2-0la4f), the epoch
  surface drops to its no-op floor: no record lands in the ring, no
  cb fires, `restore-epoch!` and `replace-app-db!` return `false`.

  The companion core gate vocabulary suite is
  `re-frame.interop-debug-gate-test`; the core integration suite is
  `re-frame.jvm-prod-gate-integration-test`. This file is the epoch
  artefact's contribution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.interop :as interop]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            ;; Side-effect require: machines publishes the late-bind hook
            ;; (see epoch_test.clj for the same dance).
            [re-frame.machines]))

;; rf2-yw1w1u — canonical capture/restore fixture. Snapshots the
;; registrar at ns-load + restores around each test, fires the epoch
;; reset-hook table (history / listeners / config-to-default), and the
;; `:init-fn` re-applies the suite's non-default `:trace-events-keep 5`
;; (NOT the shipped 50 = :depth; Mike pair-debug 2026-05-27) through the
;; public `configure!` boundary — no test ns reaches into the private
;; `state/config` var. The `:init-fn` runs OUTSIDE each test's
;; `(with-redefs [interop/debug-enabled? false] ...)`, so config lands at
;; the normal gate value.
;;
;; EP-0002 (rf2-9o48ih / rf2-nn0jqa): `init!` no longer synthesises
;; `:rf/default`. The canonical fixture, when handed an `:adapter`, ALSO
;; ensures the conventional `:rf/default` frame and binds it as the body's
;; ambient scope — the carried-invariant equivalent of wrapping every test
;; in `(with-frame :rf/default …)`. So the bare framework-operation surfaces
;; this suite drives (dispatch / epoch / restore-epoch! / replace-app-db!)
;; resolve a carried frame stamp without a hand-rolled `reg-frame` + `with-
;; frame` dance here. Explicit `{:frame …}` opts in the bodies still win.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn [] (rf/configure! :epoch-history {:trace-events-keep 5}))}))

(deftest epoch-history-inert-when-debug-disabled
  (testing "Per rf2-0la4f: when the JVM debug gate reads false, the
            per-frame epoch ring stays empty regardless of how many
            events drain. No `:db-before` / `:db-after` /
            `:trace-events` payloads land in heap memory — the
            primary motivating concern of the audit (tokens / PII /
            secrets retained in SSR process memory) is addressed."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod-gate.epoch/inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (rf/dispatch-sync [:prod-gate.epoch/inc])
      (is (empty? (epoch/epoch-history :rf/default))
          "epoch ring is empty under disabled debug gate"))))

(deftest epoch-cb-silent-when-debug-disabled
  (testing "Per rf2-0la4f: a registered epoch listener does NOT
            fire under the disabled debug gate. No record fan-out
            means no tool/plugin callback in-process can observe
            `:db-before` / `:db-after` / raw trace vectors."
    (with-redefs [interop/debug-enabled? false]
      (let [seen (atom [])]
        (epoch/register-epoch-listener!
          :prod-gate.epoch/recorder
          (fn [record] (swap! seen conj record)))
        (rf/reg-event :prod-gate.epoch/silent
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate.epoch/silent])
        (is (empty? @seen)
            "epoch listener silent under disabled debug gate")))))

(deftest restore-epoch-refuses-when-debug-disabled
  (testing "Per rf2-0la4f: `restore-epoch!` MUST refuse to operate
            when the JVM debug gate is off. The state-rewrite admin
            surface is dev-only; SSR production processes do NOT
            give arbitrary in-process code the ability to mutate
            `app-db` out of band."
    (with-redefs [interop/debug-enabled? false]
      (is (false? (rf/restore-epoch! :rf/default :some-epoch-id))
          "restore-epoch! returns false (refuses to operate)"))))

(deftest replace-app-db-refuses-when-debug-disabled
  (testing "Per rf2-0la4f: `replace-app-db!` MUST refuse to operate
            when the JVM debug gate is off. Same admin-surface
            concern as `restore-epoch!` — pair-tool writes (Tool-Pair
            §Pair-tool writes) are a dev-only surface."
    (with-redefs [interop/debug-enabled? false]
      (is (false? (rf/replace-app-db! :rf/default {:any "db"}))
          "replace-app-db! returns false (refuses to operate)"))))

(deftest epoch-still-records-with-default-gate
  (testing "Sanity: with the gate at its default `true` reading
            (dev parity), epoch recording continues to work. This
            test fails fast if a future refactor accidentally
            disables the surface in dev."
    (rf/reg-event :prod-gate.epoch/dev-inc
                     (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/dispatch-sync [:prod-gate.epoch/dev-inc])
    (is (pos? (count (epoch/epoch-history :rf/default)))
        "epoch ring has at least one record under default gate")))

;; ---- rf2-vq5o0 privacy-surface JVM false-path coverage ------------------

(deftest projected-history-empty-under-disabled-gate
  (testing "Per rf2-mrsck / rf2-vq5o0: with the JVM debug gate off,
            no records land in the ring, so projected-history reads
            the empty vector. The projection surface composes with the
            production-elision gate at the upstream (record assembly)
            seam; the projection itself is a pure data transform that
            no consumer can reach a record through under the disabled
            gate."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod-gate.priv/silent
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.priv/silent])
      (is (= [] (epoch/projected-history :rf/default))
          "empty projected-history under the disabled gate"))))

(deftest sensitive-rollup-not-computed-under-disabled-gate
  (testing "Per rf2-mrsck: the sensitive rollup is computed once per
            assembled record (in build-record). The gate-disabled
            path elides record assembly entirely; the rollup never
            runs. We verify by asserting the ring stays empty — no
            record means no rollup compute path was reached."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event :prod-gate.priv/sensitive
                       {:sensitive? true}
                       (fn [{:keys [db]} _] {:db (assoc db :token "shh")}))
      (rf/dispatch-sync [:prod-gate.priv/sensitive])
      (is (empty? (epoch/epoch-history :rf/default))
          "no record assembled — rollup never reached"))))

;; ---- EP-0015 §15 / open-issue 6 :redact-fn surface JVM coverage ----------
;;
;; Post-ruling the `:redact-fn` is the PROJECTION-SIDE advanced override —
;; storage-side mutation was REMOVED. It is NEVER invoked by `settle!` /
;; `replace-app-db!` / the back-fill (regardless of gate); it runs ONLY
;; inside `projected-record`, applied to the projected egress copy. The
;; ring is causal replay material, delivered raw.

(deftest redact-fn-never-invoked-at-storage-under-disabled-gate
  (testing "Per EP-0015 §15 + open-issue 6: with the JVM debug gate off,
            `settle!` elides record assembly entirely AND the :redact-fn
            is a projection-side hook anyway — so an installed :redact-fn
            is NEVER invoked along the dispatch/storage path. An app that
            ships a `:redact-fn` and flips the gate to false in production
            pays zero invocation cost."
    (with-redefs [interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure! :epoch-history
                      {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)})
        (rf/reg-event :prod-gate.redact/inc
                         (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (is (zero? @invocations)
            ":redact-fn was never called along the storage path — it is a
             projection-side hook, and record assembly is elided anyway")
        (is (empty? (epoch/epoch-history :rf/default))
            "no record assembled under the disabled gate")
        (rf/configure! :epoch-history {:redact-fn nil})))))

(deftest redact-fn-not-invoked-at-storage-under-default-gate
  (testing "Per EP-0015 §15 + open-issue 6: even under the DEFAULT-TRUE
            gate, dispatching does NOT invoke the :redact-fn — it is no
            longer a storage-side hook. The ring record is RAW; the
            override fires only when `projected-record` is called."
    (let [invocations (atom 0)]
      (rf/reg-frame :prod-gate.dev/frame {})
      (rf/configure! :epoch-history
                    {:redact-fn (fn [r] (swap! invocations inc) r)})
      (rf/reg-event :prod-gate.redact/dev-inc
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.redact/dev-inc] {:frame :prod-gate.dev/frame})
      (is (zero? @invocations)
          ":redact-fn NOT invoked by settle — it is projection-side only")
      ;; Projecting the recorded record IS where the override fires.
      (epoch/projected-record (last (epoch/epoch-history :prod-gate.dev/frame)))
      (is (pos? @invocations)
          ":redact-fn fires when projected-record is called (the egress
           override), under the default gate")
      (rf/configure! :epoch-history {:redact-fn nil}))))

(deftest redact-fn-not-invoked-on-replace-app-db-under-disabled-gate
  (testing "`replace-app-db!` returns false under the disabled gate — the
            gated arm that would record the synthetic epoch is elided. The
            :redact-fn (projection-side) is never reached on this path
            regardless; the early-return false is preserved."
    (with-redefs [interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure! :epoch-history
                      {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)})
        (is (false? (rf/replace-app-db! :rf/default {:any "db"}))
            "replace-app-db! refuses under the disabled gate")
        (is (zero? @invocations)
            ":redact-fn was never reached — no synthetic record recorded
             (and the fn is projection-side anyway)")
        (rf/configure! :epoch-history {:redact-fn nil})))))

(deftest redact-fn-warning-not-emitted-on-storage-path
  (testing "Per EP-0015 §15 + open-issue 6: a throwing :redact-fn cannot
            emit `:rf.warning/epoch-redact-fn-exception` along the
            dispatch/storage path — the warning is sourced inside
            `apply-redact-fn`'s try/catch, which runs ONLY at projection
            time, never at settle. Pinned so a future refactor that
            re-attaches redaction to the storage seam would break visibly."
    (let [warnings (atom [])]
      (rf/reg-frame :prod-gate.throw/frame {})
      (rf/register-listener! :trace ::warn-watch
                             (fn [ev]
                               (when (= :warning (:op-type ev))
                                 (swap! warnings conj ev))))
      (rf/configure! :epoch-history
                    {:redact-fn (fn [_r] (throw (ex-info "boom" {})))})
      (rf/reg-event :prod-gate.redact/throw
                       (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
      (rf/dispatch-sync [:prod-gate.redact/throw] {:frame :prod-gate.throw/frame})
      (rf/dispatch-sync [:prod-gate.redact/throw] {:frame :prod-gate.throw/frame})
      (let [redact-warns (filter (fn [ev]
                                   (= :rf.warning/epoch-redact-fn-exception
                                      (:operation ev)))
                                 @warnings)]
        (is (empty? redact-warns)
            ":rf.warning/epoch-redact-fn-exception never fires on the
             storage path — apply-redact-fn runs only at projection time"))
      (rf/unregister-listener! :trace ::warn-watch)
      (rf/configure! :epoch-history {:redact-fn nil}))))

(deftest projected-record-pure-transform-survives-disabled-gate
  (testing "Per rf2-vq5o0: projected-record is a pure data transform
            — it does NOT consult interop/debug-enabled?. A consumer
            that already holds a record (replayed in a JVM test
            fixture, or surfaced from a recorded session) can still
            project it. The gate elides record ASSEMBLY, not record
            PROJECTION."
    (let [synthetic-record
          {:epoch-id      42
           :frame         :test/main
           :committed-at  0
           :event-id      :synthetic
           :trigger-event [:synthetic]
           :db-before     {:n 0}
           :db-after      {:n 1}
           :outcome       :ok
           :rf.epoch/sensitive? false
           :trace-events  []
           :sub-runs      []
           :renders       []
           :effects       []}]
      (with-redefs [interop/debug-enabled? false]
        (let [projected (epoch/projected-record synthetic-record)]
          (is (some? projected)
              "projection runs even under the disabled gate")
          (is (= 42 (:epoch-id projected))
              "bookkeeping preserved"))))))
