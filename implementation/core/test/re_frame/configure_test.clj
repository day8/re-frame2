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
  this surface.

  ## Posture split (rf2-d2841)

  The `:elision` knob is PRODUCTION STATE — `rf.elision/current-config` is not
  gated — and so are the closed-vocabulary rules: unknown keys return nil, a
  non-map argument fails loud on an ALWAYS-ON guard (rf2-xn13 — a plain
  `when-not` + `throw-error!`, deliberately NOT an `assert`, so the
  contract holds in an assertion-elided build too). Those run under
  `scripts/test-core-prod-gate.sh` unchanged and are the substance of
  \"closed and fixed-and-additive\".

  The `:trace-buffer` knob is a different animal, and the tempting reading of
  it is wrong. It is NOT \"a dev-only warning attached to production state\":
  `trace.tooling/configure-trace-buffer!` opens BOTH of its arms with
  `(when (and rf.interop/debug-enabled? …))`, so under `-Dre-frame.debug=false`
  the whole surface — the retention it sets AND the
  `:rf.warning/trace-buffer-unrecognised-opts` it emits — is a no-op, and
  `trace-buffer` itself returns `[]` because the ring is never allocated.
  Every `:trace-buffer` assertion is therefore kept verbatim inside a
  `(when rf.interop/debug-enabled? …)` arm marked `rf2-d2841`.

  That includes four assertions that currently PASS under the gate and pass
  for no reason at all: `(is (<= (count (rf/trace-buffer :rf/default)) N))`
  over an empty vector is true for every N, so outside the arm the retention
  cap would certify itself with the ring never allocated — the same
  false-green as a negative over an empty trace ring, and the reason
  \"retention survived the bad call\" cannot be read off this surface in
  production. The always-on residue kept beside them is the one production
  claim the `:trace-buffer` key still makes: the call is a silent no-op that
  returns nil and does not throw."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.repl :as repl]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.elision :as rf.elision]
            [re-frame.trace :as rf.trace]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace/clear-listeners!)
  (rf.trace.tooling/clear-trace-rings!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/make-frame {:id :rf/default})
  (try (rf/with-frame :rf/default (test-fn))
       (finally
         ;; Restore defaults so we do not leak tweaks into other suites —
         ;; one composite config map (rf2-dzxixe single-map entry point).
         (rf/configure! {:trace-buffer {:events-retained 50}
                         :elision      {:rf.size/threshold-bytes 16384}}))))

(use-fixtures :each reset-runtime)

(deftest configure-known-keys-take-effect
  (testing ":trace-buffer events-retained is wired"
    ;; ALWAYS-ON (rf2-d2841): the knob is a documented no-op under the
    ;; production gate — it must still be ACCEPTED, silently, returning nil.
    (is (nil? (rf/configure! {:trace-buffer {:events-retained 7}}))
        ":trace-buffer is accepted and returns nil in BOTH postures")
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    ;; `(<= (count []) 7)` is true for every N; the cap is unreadable here
    ;; under the gate because the ring is never allocated.
    (when rf.interop/debug-enabled?
      (is (<= (count (rf/trace-buffer :rf/default)) 7)
          ":trace-buffer {:events-retained 7} caps retained events at 7")))
  (testing ":elision is wired (rf2-le2qu)"
    (rf/configure! {:elision {:rf.size/threshold-bytes 4096}})
    (is (= 4096 (:rf.size/threshold-bytes (rf.elision/current-config)))
        ":elision {:rf.size/threshold-bytes N} reaches the elision config")))

(deftest trace-buffer-rejected-opts-warn-not-silent
  (testing "rf2-x3m8c finding 1 — the retired {:depth N} shape (and any
            non-numeric / negative :events-retained) is a no-op that
            emits :rf.warning/trace-buffer-unrecognised-opts and leaves
            retention unchanged, rather than silently doing nothing.
            :events-retained is the SOLE canonical opt — impl, core
            docstring, API.md, and Spec 009 all agree."
    ;; ALWAYS-ON (rf2-d2841): a rejected opts shape is a no-op that returns
    ;; nil rather than throwing — true in BOTH postures, and the only half of
    ;; this deftest that survives the production gate.
    (is (nil? (rf/configure! {:trace-buffer {:depth 200}}))
        "the retired {:depth N} shape no-ops and returns nil in BOTH postures")
    (is (nil? (rf/configure! {:trace-buffer {:events-retained -1}}))
        "a negative :events-retained no-ops and returns nil in BOTH postures")
    ;; Establish a known retention first.
    (rf/configure! {:trace-buffer {:events-retained 9}})
    (when rf.interop/debug-enabled?
     ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
     ;; The whole `configure-trace-buffer!` surface — warning AND retention —
     ;; sits behind `rf.interop/debug-enabled?`.
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
          (rf/unregister-listener! :trace ::trace-buffer-opts)))))))

(deftest trace-buffer-severity-warning-filter-catches-unrecognised-opts
  (testing "rf2-ho20xj — a {:severity :warning} trace-buffer filter must
            catch :rf.warning/trace-buffer-unrecognised-opts. Before the
            fix, configure-trace-buffer! routed the warning through
            rf.trace/emit-error! (a hardcoded :op-type :error), so the
            Spec 009-declared :op-type :warning row and a
            {:severity :warning} filter silently missed it. Dispatch the
            bad {:configure! {:trace-buffer {:depth N}}} call FROM INSIDE
            an event handler so the emit rides an in-flight dispatch-id +
            frame (push-to-ring! skips frameless/dispatch-id-less emits
            per the B3 ruling) and lands in the frame's retained ring."
    ;; The handler stamps a SENTINEL rather than returning `{:db db}`.
    ;; MERGED-PR AUDIT #7245 (rf2-d2841): the witness here used to be
    ;; `(some? (rf/app-db-value :rf/default))` over a handler that committed
    ;; `db` unchanged — but `reset-runtime`'s `make-frame` seeds `:rf/default`
    ;; app-db as `{}`, which is already `some?` BEFORE any dispatch. It could
    ;; not fail, so it proved nothing about the handler. An actual state
    ;; TRANSITION can: the key is absent up front and present afterwards, so a
    ;; handler that never ran, or a `configure!` throw that derailed the
    ;; dispatch before the commit, reddens this.
    (rf/reg-event :bad-configure-call
                  (fn [{:keys [db]} _]
                    (rf/configure! {:trace-buffer {:depth 200}})
                    {:db (assoc db ::bad-configure-committed :yes)}))
    (is (nil? (::bad-configure-committed (rf/app-db-value :rf/default)))
        "the sentinel is absent before the dispatch — the witness below is a
         real transition, not a property app-db already had")
    (rf/dispatch-sync [:bad-configure-call])
    ;; ALWAYS-ON (rf2-d2841): the bad `configure!` call from inside a handler
    ;; must not derail the dispatch — the handler's `:db` effect still commits.
    (is (= :yes (::bad-configure-committed (rf/app-db-value :rf/default)))
        "the enclosing dispatch completed despite the rejected configure! call
         — the handler ran to its end and its commit landed")
    ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
    ;; BOTH reads go inside: `trace-buffer` returns [] in production, so the
    ;; `{:severity :error}` sanity NEGATIVE would pass over an empty vector —
    ;; certifying "no longer rides the :error op-type" with nothing retained.
    (when rf.interop/debug-enabled?
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
            :error op-type")))))

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
    ;; `:ping` COUNTS. MERGED-PR AUDIT #7245 (rf2-d2841): this used to be a
    ;; `{:db db}` no-op handler witnessed by `(some? (rf/app-db-value
    ;; :rf/default))`, which is true of the `{}` `make-frame` seeds before any
    ;; dispatch at all — so a missing handler, a perturbed registry or a
    ;; dispatch loop that ran three times instead of thirty all passed. An
    ;; exact counter cannot: the claim is "thirty landings", so assert thirty.
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db (update db ::pings (fnil inc 0))}))
    (is (nil? (::pings (rf/app-db-value :rf/default)))
        "no landings recorded yet")
    (dotimes [_ 30] (rf/dispatch-sync [:ping]))
    ;; ALWAYS-ON (rf2-d2841): whatever the posture, thirty dispatches bracketed
    ;; by four unknown-key `configure!` calls still all landed — the
    ;; production-visible half of "an unknown key perturbs nothing".
    (is (= 30 (::pings (rf/app-db-value :rf/default)))
        "all thirty bracketed dispatches ran to completion and committed")
    ;; rf2-d2841 — dev-instrumentation arm. `(<= (count []) 11)` is true for
    ;; every N under the gate: the retention cap is unreadable in production.
    (when rf.interop/debug-enabled?
      (is (<= (count (rf/trace-buffer :rf/default)) 11)
          ":trace-buffer events-retained survived bracketing unknown-key calls")))
  (testing "rf2-dzxixe — a single map mixing known + unknown top-level
            keys applies the known subsystems and silently ignores the
            unknown ones (closed-and-additive)"
    (rf/configure! {:trace-buffer {:events-retained 6}
                    :elision      {:rf.size/threshold-bytes 2048}
                    :no-such-key  {:foo 1}
                    :strict-subs  true})
    (is (= 2048 (:rf.size/threshold-bytes (rf.elision/current-config)))
        ":elision applied from the composite map")
    (rf/reg-event :ping (fn [{:keys [db]} _] {:db db}))
    (dotimes [_ 20] (rf/dispatch-sync [:ping]))
    ;; rf2-d2841 — dev-instrumentation arm. Same empty-vector false-green; the
    ;; composite map's PRODUCTION half is the `:elision` assertion above,
    ;; which is unguarded and is what proves "known subsystems applied".
    (when rf.interop/debug-enabled?
      (is (<= (count (rf/trace-buffer :rf/default)) 6)
          ":trace-buffer applied from the composite map; unknown keys ignored"))))

;; ---------------------------------------------------------------------------
;; rf2-xn13 — the non-map guard, in BOTH assertion postures.
;; ---------------------------------------------------------------------------

(def ^:private non-map-args
  "The three non-map arguments the contract names, worst first: the RETIRED
  keyed-arity first arg (what a stale call site / a v1 migration actually
  produces), a vector, and `nil`."
  [:trace-buffer [:trace-buffer {:events-retained 3}] nil])

(deftest configure-non-map-arg-fails-loud
  (testing "rf2-dzxixe / rf2-xn13 — configure! takes a SINGLE nested config
            map. A non-map argument is a programmer error and throws the
            canonical structured error, NOT a bare AssertionError."
    (doseq [bad non-map-args]
      (let [ex (try (rf/configure! bad) nil
                    (catch clojure.lang.ExceptionInfo e e))
            d  (some-> ex ex-data)]
        (is (some? ex)
            (str "a non-map arg fails loud: " (pr-str bad)))
        (is (= :rf.error/configure-bad-arg (:rf.error/id d))
            "the canonical machine discriminator")
        (is (= 'rf/configure! (:where d))
            "the user-facing symbol that threw")
        (is (= :pass-a-config-map (:recovery d))
            "the recovery disposition names the fix")
        (is (string? (:reason d))
            "a one-sentence human diagnostic is present")
        ;; The offending value is reported by SHAPE, never echoed: `configure!`
        ;; is handed application configuration, so the ex-data must survive
        ;; off-box capture. `diag-value-summary` is content-free by
        ;; construction (a closed `:type` vocabulary + an integer count).
        (is (contains? (:received d) :type)
            ":received carries the content-free shape summary")
        (is (not= bad (:received d))
            ":received is a SHAPE summary, not the raw argument")))))

;; The `configure!` guard must hold when the host compiler ELIDES assertions —
;; CLJS `:elide-asserts true`, or a JVM load under `*assert*` false. That is the
;; whole point of rf2-xn13: the previous `(assert (map? …))` compiled to NOTHING
;; in such a build and every call below silently returned nil.
;;
;; A test that merely rebinds `*assert*` at CALL time proves nothing — `assert`
;; is a MACRO, so the decision was already taken when `re-frame.core` was
;; compiled. This harness therefore takes the ACTUAL source form of the ACTUAL
;; public fn and RE-COMPILES it with `*assert*` false, which is a genuine
;; compile-time control. It deliberately does NOT reload `re-frame.core`: the
;; form is evaluated into a throwaway namespace carrying `re-frame.core`'s own
;; aliases, so no global runtime state is disturbed for the rest of the suite.

(def ^:private probe-ns-sym 're-frame.configure-elision-probe)

(defn- eval-with-assertions-elided
  "Compile `form` with `*assert*` FALSE in a throwaway namespace that carries
  `re-frame.core`'s aliases, and return the resulting Var."
  [form sym]
  (remove-ns probe-ns-sym)
  (let [probe (create-ns probe-ns-sym)]
    (binding [*ns* probe]
      (refer-clojure)
      (doseq [[a n] (ns-aliases (find-ns 're-frame.core))]
        (.addAlias ^clojure.lang.Namespace probe a n))
      (binding [*assert* false]
        (eval form)))
    (ns-resolve probe sym)))

(defn- configure!-source-form
  "The `(defn configure! …)` form as it is WRITTEN in
  `re_frame/core.cljc` — read from source, so this test cannot drift into
  asserting over a copy of the guard."
  []
  (let [src (repl/source-fn 're-frame.core/configure!)]
    (assert (string? src) "could not read re-frame.core/configure! source")
    (read {:read-cond :allow}
          (java.io.PushbackReader. (java.io.StringReader. src)))))

(deftest configure-non-map-guard-survives-assertion-elision
  (testing "rf2-xn13 — the harness really DOES elide assertions (anti-vacuity
            control). Without this, every assertion below could pass because
            nothing was elided at all."
    (let [elided-assert-fn (eval-with-assertions-elided
                             '(defn probe-fn [x] (assert (map? x)) :applied)
                             'probe-fn)]
      (is (= :applied (elided-assert-fn :not-a-map))
          "a language `assert` compiled under *assert* false does NOT fire —
           so this harness reproduces the defective posture faithfully")))

  (testing "rf2-xn13 — configure!'s OWN source, recompiled with assertions
            elided, still rejects every non-map argument with the SAME
            canonical error. Against the previous `(assert (map? …))`
            implementation each of these calls returned nil."
    (let [elided-configure! (eval-with-assertions-elided
                              (configure!-source-form) 'configure!)]
      (doseq [bad non-map-args]
        (let [ex (try (elided-configure! bad) nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              (str "assertion-elided build still fails loud on " (pr-str bad)))
          (is (= :rf.error/configure-bad-arg (:rf.error/id (ex-data ex)))
              "the SAME canonical id in both postures")))))

  (testing "rf2-xn13 — the elided build still APPLIES a valid map, so the
            guard did not turn configure! into a throw-everything stub."
    (let [elided-configure! (eval-with-assertions-elided
                              (configure!-source-form) 'configure!)]
      (is (nil? (elided-configure! {:elision {:rf.size/threshold-bytes 4096}}))
          "a valid map returns nil as documented")
      (is (= 4096 (:rf.size/threshold-bytes (rf.elision/current-config)))
          "and the known subsystem really was configured")
      (is (nil? (elided-configure! {:no-such-key 1}))
          "an unknown top-level key remains a silent nil-returning no-op"))))
