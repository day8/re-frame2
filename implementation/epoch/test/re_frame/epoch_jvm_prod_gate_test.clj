(ns re-frame.epoch-jvm-prod-gate-test
  "Per rf2-0la4f (security audit): the epoch artefact's dev-only
  surfaces — `register-epoch-cb!`, `restore-epoch`, `reset-frame-db!`,
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
  (trace/clear-trace-cbs!)
  (epoch/clear-history!)
  (epoch/clear-epoch-cbs!)
  (reset! @#'epoch/config {:depth 50})
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
        (epoch/register-epoch-cb!
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
