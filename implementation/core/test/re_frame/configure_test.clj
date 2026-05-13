(ns re-frame.configure-test
  "Lock the closed-key contract of `(rf/configure ...)` (rf2-mmlci).

  `configure` is the process-level runtime-knob surface (per Conventions
  §Configuration surfaces bucket 1 and API.md §Configure keys). Its
  vocabulary is closed and fixed-and-additive:

    :epoch-history  — Tool-Pair epoch ring depth (deferred to the
                       day8/re-frame2-epoch artefact via late-bind)
    :trace-buffer   — retain-N trace ring buffer depth (Spec 009)
    :sub-cache      — sub-cache deferred-disposal grace period (Spec 006)

  This test pins the keys that ARE configurable and asserts that
  everything else is a silent no-op — `configure` returns `nil` and
  does not throw. Per-frame settings (e.g. SSR error projection) live
  on the frame's metadata, not on this surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.subs :as subs]
            [re-frame.trace :as trace]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-cbs!)
  (rf/clear-trace-buffer!)
  (rf/init! plain-atom/adapter)
  (try (test-fn)
       (finally
         ;; Restore defaults so we do not leak tweaks into other suites.
         (rf/configure :trace-buffer {:depth 200})
         (subs/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

(deftest configure-known-keys-take-effect
  (testing ":trace-buffer is wired"
    (rf/configure :trace-buffer {:depth 7})
    (rf/reg-event-db :ping (fn [db _] db))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer)) 7)
        ":trace-buffer {:depth 7} caps retained events at 7"))
  (testing ":sub-cache is wired"
    (rf/configure :sub-cache {:grace-period-ms 123})
    (is (= 123 (:grace-period-ms (subs/current-config)))
        ":sub-cache {:grace-period-ms N} reaches the subs config")))

(deftest configure-unknown-key-is-silent-no-op
  (testing "rf2-mmlci — unknown keys silently no-op; configure returns nil"
    ;; The vocabulary is closed-and-additive: keys not enumerated in
    ;; API.md §Configure keys must not throw, must not partially apply,
    ;; and must return nil so user code wrapping `configure` can safely
    ;; pass through unknown keys without branching.
    (is (nil? (rf/configure :strict-subs true))
        ":strict-subs is NOT a v1 configure key (per API.md §Configure keys); call no-ops")
    (is (nil? (rf/configure :ssr {:public-error-id :anything}))
        ":ssr is per-frame metadata, not a configure key (per Conventions §Configuration surfaces)")
    (is (nil? (rf/configure :totally-made-up {:foo 1}))
        "any unknown key returns nil"))
  (testing "an unknown key does not perturb the known-key state"
    ;; Set known keys to non-default values, then attempt unknown keys,
    ;; then assert known-key state is unchanged.
    (rf/configure :trace-buffer {:depth 11})
    (rf/configure :sub-cache    {:grace-period-ms 71})
    (rf/configure :strict-subs  true)
    (rf/configure :ssr          {:public-error-id :nope})
    (rf/configure :no-such-key  {})
    (rf/reg-event-db :ping (fn [db _] db))
    (dotimes [_ 30] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer)) 11)
        ":trace-buffer depth survived bracketing unknown-key calls")
    (is (= 71 (:grace-period-ms (subs/current-config)))
        ":sub-cache grace-period survived bracketing unknown-key calls")))
