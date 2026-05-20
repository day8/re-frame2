(ns re-frame.epoch-jvm-prod-gate-test
  "Per rf2-0la4f (security audit): the epoch artefact's dev-only
  surfaces — `register-epoch-listener!`, `restore-epoch`, `reset-frame-db!`,
  the per-frame ring buffer carrying `:db-before` / `:db-after` /
  raw `:trace-events` — MUST honour the JVM-side production gate
  `re-frame.interop/debug-enabled?`. When the gate reads `false`
  (the SSR production posture per rf2-vnjfg / rf2-0la4f), the epoch
  surface drops to its no-op floor: no record lands in the ring, no
  cb fires, `restore-epoch` and `reset-frame-db!` return `false`.

  The companion core gate vocabulary suite is
  `re-frame.interop-debug-gate-test`; the core integration suite is
  `re-frame.jvm-prod-gate-integration-test`. This file is the epoch
  artefact's contribution."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.epoch :as epoch]
            [re-frame.epoch.state :as state]
            [re-frame.flows :as flows]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            ;; Side-effect require: machines publishes the late-bind hook
            ;; (see epoch_test.clj for the same dance).
            [re-frame.machines]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (when-let [li-var (resolve 're-frame.flows/last-inputs)]
    (reset! (deref li-var) {}))
  (trace/clear-trace-listeners!)
  (epoch/clear-history!)
  (epoch/clear-epoch-listeners!)
  (reset! @#'state/config {:depth 50 :trace-events-keep 5 :redact-fn nil})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest epoch-history-inert-when-debug-disabled
  (testing "Per rf2-0la4f: when the JVM debug gate reads false, the
            per-frame epoch ring stays empty regardless of how many
            events drain. No `:db-before` / `:db-after` /
            `:trace-events` payloads land in heap memory — the
            primary motivating concern of the audit (tokens / PII /
            secrets retained in SSR process memory) is addressed."
    (with-redefs [interop/debug-enabled? false]
      (rf/reg-event-db :prod-gate.epoch/inc
                       (fn [db _] (update db :n (fnil inc 0))))
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
        (rf/reg-event-db :prod-gate.epoch/silent
                         (fn [db _] (update db :n (fnil inc 0))))
        (rf/dispatch-sync [:prod-gate.epoch/silent])
        (is (empty? @seen)
            "epoch listener silent under disabled debug gate")))))

(deftest restore-epoch-refuses-when-debug-disabled
  (testing "Per rf2-0la4f: `restore-epoch` MUST refuse to operate
            when the JVM debug gate is off. The state-rewrite admin
            surface is dev-only; SSR production processes do NOT
            give arbitrary in-process code the ability to mutate
            `app-db` out of band."
    (with-redefs [interop/debug-enabled? false]
      (is (false? (rf/restore-epoch :rf/default :some-epoch-id))
          "restore-epoch returns false (refuses to operate)"))))

(deftest reset-frame-db-refuses-when-debug-disabled
  (testing "Per rf2-0la4f: `reset-frame-db!` MUST refuse to operate
            when the JVM debug gate is off. Same admin-surface
            concern as `restore-epoch` — pair-tool writes (Tool-Pair
            §Pair-tool writes) are a dev-only surface."
    (with-redefs [interop/debug-enabled? false]
      (is (false? (rf/reset-frame-db! :rf/default {:any "db"}))
          "reset-frame-db! returns false (refuses to operate)"))))

(deftest epoch-still-records-with-default-gate
  (testing "Sanity: with the gate at its default `true` reading
            (dev parity), epoch recording continues to work. This
            test fails fast if a future refactor accidentally
            disables the surface in dev."
    (rf/reg-event-db :prod-gate.epoch/dev-inc
                     (fn [db _] (update db :n (fnil inc 0))))
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
      (rf/reg-event-db :prod-gate.priv/silent
                       (fn [db _] (update db :n (fnil inc 0))))
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
      (rf/reg-event-db :prod-gate.priv/sensitive
                       {:sensitive? true}
                       (fn [db _] (assoc db :token "shh")))
      (rf/dispatch-sync [:prod-gate.priv/sensitive])
      (is (empty? (epoch/epoch-history :rf/default))
          "no record assembled — rollup never reached"))))

;; ---- rf2-wp70d.5 :redact-fn surface JVM false-path coverage --------------

(deftest redact-fn-never-invoked-under-disabled-gate
  (testing "Per rf2-wp70d.5: with the JVM debug gate off, the
            installed :redact-fn is NEVER invoked — `settle!`'s body
            is the gated frontier and elides record assembly entirely.
            An app that ships a `:redact-fn` and flips the gate to
            false in production pays zero invocation cost (the slot
            sits in `@config` but the call path is gone)."
    (with-redefs [interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure :epoch-history
                      {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)})
        (rf/reg-event-db :prod-gate.redact/inc
                         (fn [db _] (update db :n (fnil inc 0))))
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (rf/dispatch-sync [:prod-gate.redact/inc])
        (is (zero? @invocations)
            ":redact-fn was never called — the gated build-record /
             maybe-redact path is inert under the disabled gate")
        ;; Cleanup: clear the fn so it doesn't survive into other
        ;; tests via the global config atom.
        (rf/configure :epoch-history {:redact-fn nil})))))

(deftest redact-fn-not-invoked-on-reset-frame-db-under-disabled-gate
  (testing "Per rf2-wp70d.5: `reset-frame-db!` returns false under the
            disabled gate (already pinned above) — the gated arm that
            would call `perform-reset-frame-db!` → `maybe-redact` is
            elided. A throwing `:redact-fn` cannot run, so it cannot
            cause a warning emit, and the early-return false is
            preserved regardless of whether a fn is installed."
    (with-redefs [interop/debug-enabled? false]
      (let [invocations (atom 0)]
        (rf/configure :epoch-history
                      {:redact-fn (fn [r]
                                    (swap! invocations inc)
                                    r)})
        (is (false? (rf/reset-frame-db! :rf/default {:any "db"}))
            "reset-frame-db! refuses under the disabled gate")
        (is (zero? @invocations)
            ":redact-fn was never reached — perform-reset-frame-db!
             never ran")
        (rf/configure :epoch-history {:redact-fn nil})))))

(deftest redact-fn-warning-not-emitted-under-disabled-gate
  (testing "Per rf2-wp70d.5: a throwing :redact-fn cannot emit
            `:rf.warning/epoch-redact-fn-exception` under the disabled
            gate — the warning op is sourced inside `maybe-redact`'s
            try/catch, and `maybe-redact` is unreachable because every
            call site is itself gated. Pinned here so a future
            refactor that hoists `maybe-redact` outside the universal
            `interop/debug-enabled?` gate would break visibly."
    (with-redefs [interop/debug-enabled? false]
      (let [warnings (atom [])]
        (rf/register-trace-listener! ::warn-watch
                               (fn [ev]
                                 (when (= :warning (:op-type ev))
                                   (swap! warnings conj ev))))
        (rf/configure :epoch-history
                      {:redact-fn (fn [_r]
                                    (throw (ex-info "boom" {})))})
        (rf/reg-event-db :prod-gate.redact/throw
                         (fn [db _] (update db :n (fnil inc 0))))
        (rf/dispatch-sync [:prod-gate.redact/throw])
        (rf/dispatch-sync [:prod-gate.redact/throw])
        (let [redact-warns (filter (fn [ev]
                                     (= :rf.warning/epoch-redact-fn-exception
                                        (:operation ev)))
                                   @warnings)]
          (is (empty? redact-warns)
              ":rf.warning/epoch-redact-fn-exception never fires —
               maybe-redact is unreachable under the disabled gate"))
        (rf/unregister-trace-listener! ::warn-watch)
        (rf/configure :epoch-history {:redact-fn nil})))))

(deftest redact-fn-slot-survives-default-gate-as-sanity
  (testing "Sanity companion to the above: under the default-true
            gate, an installed :redact-fn DOES fire — fails fast if a
            future refactor accidentally inverts the gate polarity in
            the redact-fn surface."
    (let [invocations (atom 0)]
      (rf/configure :epoch-history
                    {:redact-fn (fn [r]
                                  (swap! invocations inc)
                                  r)})
      (rf/reg-event-db :prod-gate.redact/dev-inc
                       (fn [db _] (update db :n (fnil inc 0))))
      (rf/dispatch-sync [:prod-gate.redact/dev-inc])
      (is (pos? @invocations)
          ":redact-fn fires under the default-true gate")
      (rf/configure :epoch-history {:redact-fn nil}))))

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
