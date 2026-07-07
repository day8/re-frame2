(ns re-frame.configure-test
  "Lock the closed-key contract of `(rf/configure! ...)` (rf2-mmlci).

  `configure` is the process-level runtime-knob surface (per Conventions
  §Configuration surfaces bucket 1 and API.md §Configure keys). Its
  vocabulary is closed and fixed-and-additive:

    :epoch-history  — Tool-Pair epoch ring depth (deferred to the
                       day8/re-frame2-epoch artefact via late-bind)
    :trace-buffer   — per-frame per-event trace ring depth — sets
                       the process-default `:events-retained` that
                       applies to `:rf/default` and any frame that did
                       not set its own `:rf.trace/events-retained`
                       metadata (Spec 009 §Per-frame trace rings;
                       rf2-g1b2m)
    :elision        — wire-elision runtime size threshold (Spec 009)

  Per rf2-cmfln: the `:sub-cache` knob was retired. Sub-cache disposal
  is **synchronous on derefer-count → 0** — there is no deferred-grace
  timer to configure.

  This test pins the keys that ARE configurable and asserts that
  everything else is a silent no-op — `configure` returns `nil` and
  does not throw. Per-frame settings (e.g. SSR error projection,
  `:rf.trace/events-retained`) live on the frame's metadata, not on
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
  (schemas/clear-schemas-by-frame!)
  (trace/clear-listeners!)
  (trace-tooling/clear-trace-rings!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (try (rf/with-frame :rf/default (test-fn))
       (finally
         ;; Restore defaults so we do not leak tweaks into other suites —
         ;; one composite config map (rf2-dzxixe single-map entry point).
         (rf/configure! {:trace-buffer {:events-retained 50}
                         :elision      {:rf.size/threshold-bytes 16384}}))))

(use-fixtures :each reset-runtime)

(deftest configure-known-keys-take-effect
  (testing ":trace-buffer events-retained is wired"
    (rf/configure! {:trace-buffer {:events-retained 7}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer :rf/default)) 7)
        ":trace-buffer {:events-retained 7} caps retained events at 7"))
  (testing ":elision is wired (rf2-le2qu)"
    (rf/configure! {:elision {:rf.size/threshold-bytes 4096}})
    (is (= 4096 (:rf.size/threshold-bytes (elision/current-config)))
        ":elision {:rf.size/threshold-bytes N} reaches the elision config")))

(deftest trace-buffer-rejected-opts-warn-not-silent
  (testing "rf2-x3m8c finding 1 — the retired {:depth N} shape (and any
            non-numeric / negative :events-retained) is a no-op that
            emits :rf.warning/trace-buffer-unrecognised-opts and leaves
            retention unchanged, rather than silently doing nothing.
            :events-retained is the SOLE canonical opt — impl, core
            docstring, API.md, and Spec 009 all agree."
    ;; Establish a known retention first.
    (rf/configure! {:trace-buffer {:events-retained 9}})
    (let [warnings (atom [])]
      (rf/register-listener! :trace ::trace-buffer-opts
                             (fn [ev]
                               (when (= :rf.warning/trace-buffer-unrecognised-opts
                                        (:operation ev))
                                 (swap! warnings conj ev))))
      (try
        ;; The documented footgun: a user following stale docs passes the
        ;; retired {:depth N} shape.
        (rf/configure! {:trace-buffer {:depth 200}})
        (is (= 1 (count @warnings))
            "the retired {:depth N} shape emits exactly one warning")
        (let [ev (first @warnings)]
          (is (= :warning (:op-type ev))
              "op-type is :warning (rf2-ho20xj — routed via :trace/emit!'s
              :warning path, matching Spec 009's catalogued :op-type for
              :rf.warning/trace-buffer-unrecognised-opts; a
              {:severity :warning} trace-buffer filter must catch it)")
          (is (= {:depth 200} (-> ev :tags :opts))
              "the rejected opts map rides the :opts tag")
          (is (string? (-> ev :tags :reason))
              "the :reason names the fix"))
        ;; A negative value is likewise rejected.
        (rf/configure! {:trace-buffer {:events-retained -1}})
        (is (= 2 (count @warnings))
            "a negative :events-retained also warns")
        ;; Retention is still the last GOOD value — the bad calls were
        ;; pure no-ops.
        (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
        (dotimes [_ 20] (rf/dispatch-sync [:ping]))
        (is (<= (count (rf/trace-buffer :rf/default)) 9)
            "retention stayed at the last valid {:events-retained 9}")
        ;; The canonical shape still applies cleanly (no warning).
        (rf/configure! {:trace-buffer {:events-retained 3}})
        (is (= 2 (count @warnings))
            "the canonical {:events-retained N} shape does NOT warn")
        (finally
          (rf/unregister-listener! :trace ::trace-buffer-opts))))))

(deftest trace-buffer-severity-warning-filter-catches-unrecognised-opts
  (testing "rf2-ho20xj — a {:severity :warning} trace-buffer filter must
            catch :rf.warning/trace-buffer-unrecognised-opts. Before the
            fix, configure-trace-buffer! routed the warning through
            trace/emit-error! (a hardcoded :op-type :error), so the
            Spec 009-declared :op-type :warning row and a
            {:severity :warning} filter silently missed it. Dispatch the
            bad {:configure! {:trace-buffer {:depth N}}} call FROM INSIDE
            an event handler so the emit rides an in-flight dispatch-id +
            frame (push-to-ring! skips frameless/dispatch-id-less emits
            per the B3 ruling) and lands in the frame's retained ring."
    (rf/reg-event :bad-configure-call
                  (fn [{:keys [db]} _]
                    (rf/configure! {:trace-buffer {:depth 200}})
                    {:db db}))
    (rf/dispatch-sync [:bad-configure-call])
    (let [warnings (rf/trace-buffer :rf/default {:flat true :severity :warning})]
      (is (some #(= :rf.warning/trace-buffer-unrecognised-opts (:operation %))
                warnings)
          "a {:severity :warning} trace-buffer read surfaces
          :rf.warning/trace-buffer-unrecognised-opts (rf2-ho20xj)"))
    ;; Sanity: a {:severity :error} filter must NOT catch it — the whole
    ;; point of the fix is that this category no longer masquerades as
    ;; an :error op-type.
    (let [errors (rf/trace-buffer :rf/default {:flat true :severity :error})]
      (is (not (some #(= :rf.warning/trace-buffer-unrecognised-opts (:operation %))
                     errors))
          ":rf.warning/trace-buffer-unrecognised-opts no longer rides the
          :error op-type"))))

(deftest configure-unknown-key-is-silent-no-op
  (testing "rf2-mmlci — unknown keys silently no-op; configure returns nil"
    ;; The vocabulary is closed-and-additive: keys not enumerated in
    ;; API.md §Configure keys must not throw, must not partially apply,
    ;; and must return nil so user code wrapping `configure` can safely
    ;; pass through unknown keys without branching.
    (is (nil? (rf/configure! {:strict-subs true}))
        ":strict-subs is NOT a v1 configure key (per API.md §Configure keys); call no-ops")
    (is (nil? (rf/configure! {:ssr {:public-error-id :anything}}))
        ":ssr is per-frame metadata, not a configure key (per Conventions §Configuration surfaces)")
    (is (nil? (rf/configure! {:totally-made-up {:foo 1}}))
        "any unknown key returns nil")
    ;; rf2-cmfln — :sub-cache is no longer a valid configure key (sync
    ;; dispose has no grace-period to configure). The call must no-op.
    (is (nil? (rf/configure! {:sub-cache {:grace-period-ms 71}}))
        ":sub-cache is retired (rf2-cmfln); the call no-ops"))
  (testing "an unknown key does not perturb the known-key state"
    ;; Set known keys to non-default values, then attempt unknown keys,
    ;; then assert known-key state is unchanged.
    (rf/configure! {:trace-buffer {:events-retained 11}})
    (rf/configure! {:strict-subs true})
    (rf/configure! {:ssr {:public-error-id :nope}})
    (rf/configure! {:no-such-key {}})
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 30] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer :rf/default)) 11)
        ":trace-buffer events-retained survived bracketing unknown-key calls"))
  (testing "rf2-dzxixe — a single map mixing known + unknown top-level
            keys applies the known subsystems and silently ignores the
            unknown ones (closed-and-additive)"
    (rf/configure! {:trace-buffer {:events-retained 6}
                    :elision      {:rf.size/threshold-bytes 2048}
                    :no-such-key  {:foo 1}
                    :strict-subs  true})
    (is (= 2048 (:rf.size/threshold-bytes (elision/current-config)))
        ":elision applied from the composite map")
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    (is (<= (count (rf/trace-buffer :rf/default)) 6)
        ":trace-buffer applied from the composite map; unknown keys ignored")))

(deftest configure-non-map-arg-fails-loud
  (testing "rf2-dzxixe — configure! takes a SINGLE nested config map.
            A non-map argument is a programmer error and fails loudly
            (assert) rather than silently no-opping."
    (is (thrown? AssertionError
                 (rf/configure! :trace-buffer))
        "a bare keyword (the retired keyed-arity first arg) fails loud")
    (is (thrown? AssertionError
                 (rf/configure! [:trace-buffer {:events-retained 3}]))
        "a vector fails loud")
    (is (thrown? AssertionError
                 (rf/configure! nil))
        "nil fails loud")))
