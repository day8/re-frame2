(ns re-frame.configure-test
  "Lock the closed-key contract of `(rf/configure! ...)` (rf2-mmlci).

  `configure` is the process-level runtime-knob surface (per Conventions
  §Configuration surfaces bucket 1 and API.md §Configure keys). Its
  vocabulary is closed and fixed-and-additive:

    :epoch-history  — Tool-Pair epoch ring depth (deferred to the
                       day8/re-frame2-epoch artefact via late-bind)
    :trace-buffer   — per-frame cascade-keyed trace ring depth — sets
                       the process-default `:cascades-retained` that
                       applies to `:rf/default` and any frame that did
                       not set its own `:rf.trace/cascades-retained`
                       metadata (Spec 009 §Per-frame trace rings;
                       rf2-g1b2m)
    :elision        — wire-elision runtime size threshold (Spec 009)

  Per rf2-cmfln: the `:sub-cache` knob was retired. Sub-cache disposal
  is **synchronous on derefer-count → 0** — there is no deferred-grace
  timer to configure.

  This test pins the keys that ARE configurable and asserts that
  everything else is a silent no-op — `configure` returns `nil` and
  does not throw. Per-frame settings (e.g. SSR error projection,
  `:rf.trace/cascades-retained`) live on the frame's metadata, not on
  this surface."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.elision :as elision]
            [re-frame.trace :as trace]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (reset! schemas/schemas-by-frame {})
  (trace/clear-listeners!)
  (trace-tooling/clear-trace-rings!)
  (rf/init! plain-atom/adapter)
  (try (test-fn)
       (finally
         ;; Restore defaults so we do not leak tweaks into other suites.
         (rf/configure! :trace-buffer {:cascades-retained 50})
         (rf/configure! :elision {:rf.size/threshold-bytes 16384}))))

(use-fixtures :each reset-runtime)

(deftest configure-known-keys-take-effect
  (testing ":trace-buffer cascades-retained is wired"
    (rf/configure! :trace-buffer {:cascades-retained 7})
    (rf/reg-event-db :ping (fn [db _] db))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer :rf/default)) 7)
        ":trace-buffer {:cascades-retained 7} caps retained cascades at 7"))
  (testing ":elision is wired (rf2-le2qu)"
    (rf/configure! :elision {:rf.size/threshold-bytes 4096})
    (is (= 4096 (:rf.size/threshold-bytes (elision/current-config)))
        ":elision {:rf.size/threshold-bytes N} reaches the elision config")))

(deftest configure-unknown-key-is-silent-no-op
  (testing "rf2-mmlci — unknown keys silently no-op; configure returns nil"
    ;; The vocabulary is closed-and-additive: keys not enumerated in
    ;; API.md §Configure keys must not throw, must not partially apply,
    ;; and must return nil so user code wrapping `configure` can safely
    ;; pass through unknown keys without branching.
    (is (nil? (rf/configure! :strict-subs true))
        ":strict-subs is NOT a v1 configure key (per API.md §Configure keys); call no-ops")
    (is (nil? (rf/configure! :ssr {:public-error-id :anything}))
        ":ssr is per-frame metadata, not a configure key (per Conventions §Configuration surfaces)")
    (is (nil? (rf/configure! :totally-made-up {:foo 1}))
        "any unknown key returns nil")
    ;; rf2-cmfln — :sub-cache is no longer a valid configure key (sync
    ;; dispose has no grace-period to configure). The call must no-op.
    (is (nil? (rf/configure! :sub-cache {:grace-period-ms 71}))
        ":sub-cache is retired (rf2-cmfln); the call no-ops"))
  (testing "an unknown key does not perturb the known-key state"
    ;; Set known keys to non-default values, then attempt unknown keys,
    ;; then assert known-key state is unchanged.
    (rf/configure! :trace-buffer {:cascades-retained 11})
    (rf/configure! :strict-subs  true)
    (rf/configure! :ssr          {:public-error-id :nope})
    (rf/configure! :no-such-key  {})
    (rf/reg-event-db :ping (fn [db _] db))
    (dotimes [_ 30] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer :rf/default)) 11)
        ":trace-buffer cascades-retained survived bracketing unknown-key calls")))
