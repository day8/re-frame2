(ns re-frame.cofx-test
  "EP-0017 slice-A.3: value-returning `reg-cofx`, `:rf.cofx/requires`
  declared-only delivery, the cofx error family, and `inject-cofx` removal.
  Per Spec 001 §`reg-cofx` / §The declaration key, Spec 002 §Satisfaction
  algorithm, and Spec 009 §Error catalogue.

  These tests establish an explicit frame scope (the fixture registers an
  ordinary `:rf/default` frame and binds `*current-frame*` to it for the
  body — equivalent to wrapping every body in `(with-frame :rf/default …)`),
  so the ambient `dispatch-sync` calls resolve their target through scope
  rather than a synthesised default (EP-0002 carried-invariant contract — no
  `:rf/default` floor)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; Framework registrations live at namespace-load time; clear-all! wiped
  ;; them. Reload so :rf/route, :rf.route/* subs and the framework fx survive
  ;; between tests, plus the standard cofx (`:rf/time-ms`).
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (require 're-frame.cofx     :reload)
  (frame/ensure-default-frame!)
  (binding [frame/*current-frame* :rf/default]
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that accumulates
  events. Tests must (rf/unregister-listener! id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! id (fn [ev] (swap! acc conj ev)))
    acc))

;; ===========================================================================
;; 1. Value-returning reg-cofx + ambient delivery
;; ===========================================================================

(deftest ambient-supplier-delivers-value-flat
  (testing "a value-returning ambient supplier delivers its value FLAT under
            its id into the declaring handler's coeffects (EP-0017 §2/§5)"
    (let [seen (atom ::unset)]
      (rf/reg-cofx :cofx-test/locale
        {:doc "Ambient supplier."}
        (fn [] "en-AU"))
      (rf/reg-event-fx :cofx-test/read-locale
        {:rf.cofx/requires [:cofx-test/locale]}
        (fn [{:keys [cofx-test/locale]} _]
          (reset! seen locale)
          {}))
      (rf/dispatch-sync [:cofx-test/read-locale])
      (is (= "en-AU" @seen)
          "the ambient supplier's return value arrived flat under its id"))))

(deftest parameterized-supplier-receives-arg
  (testing "a `[id arg]` declaration delivers under the bare id, passing the
            arg to the supplier's 1-arity (EP-0017 §2/§4)"
    (let [seen (atom ::unset)]
      (rf/reg-cofx :cofx-test/echo
        (fn [arg] (str "echo:" arg)))
      (rf/reg-event-fx :cofx-test/read-echo
        {:rf.cofx/requires [[:cofx-test/echo "hi"]]}
        (fn [{:keys [cofx-test/echo]} _]
          (reset! seen echo)
          {}))
      (rf/dispatch-sync [:cofx-test/read-echo])
      (is (= "echo:hi" @seen)
          "the parameterized supplier received the declared arg, delivered under the bare id"))))

;; ===========================================================================
;; 2. Declared-only delivery (ADVERSARIAL — undeclared NOT delivered)
;; ===========================================================================

(deftest undeclared-leaf-on-token-is-not-delivered
  (testing "ADVERSARIAL: a recordable leaf present on the token but UNDECLARED
            by the handler is NOT staged into its coeffects (EP-0017 §5 —
            declared-only delivery; no silent green-in-test coupling)"
    (let [seen-time (atom ::unset)
          seen-undeclared (atom ::unset)]
      (rf/reg-event-fx :cofx-test/declares-only-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms] :as cofx} _]
          (reset! seen-time time-ms)
          ;; `:app/extra` rode the token but was not declared — it must NOT
          ;; appear as a flat coeffect leaf.
          (reset! seen-undeclared (contains? cofx :app/extra))
          {}))
      (rf/dispatch-sync [:cofx-test/declares-only-time]
                        {:rf.cofx {:rf/time-ms 1781078400123
                                   :app/extra  :should-not-be-delivered}})
      (is (= 1781078400123 @seen-time)
          "the DECLARED :rf/time-ms was delivered flat")
      (is (false? @seen-undeclared)
          "the UNDECLARED :app/extra leaf was NOT staged as a flat coeffect"))))

(deftest no-declaration-stages-no-cofx-leaves
  (testing "ADVERSARIAL: a handler with NO `:rf.cofx/requires` receives only
            :db / :event / framework keys — no recordable leaf is staged flat,
            even one present on the token"
    (let [had-time? (atom ::unset)]
      (rf/reg-event-fx :cofx-test/declares-nothing
        (fn [{:keys [rf/time-ms] :as cofx} _]
          (reset! had-time? (contains? cofx :rf/time-ms))
          (is (nil? time-ms)
              "an undeclared :rf/time-ms is not delivered flat")
          {}))
      (rf/dispatch-sync [:cofx-test/declares-nothing]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (false? @had-time?)
          "nothing implicit — :rf/time-ms is delivered ONLY on declaration"))))

;; ===========================================================================
;; 3. Recordable / provided facts + supplied-value-wins
;; ===========================================================================

(deftest provided-recordable-delivered-from-token-verbatim
  (testing "a declared PROVIDED recordable fact present on the token is
            delivered verbatim — a scripted value comes back exactly (no host
            read; replay-stable). EP-0017 §5"
    (let [seen (atom ::unset)]
      (rf/reg-cofx :cofx-test/boot-token
        {:recordable? true :provided? true
         :doc "Provided boundary fact."})
      (rf/reg-event-fx :cofx-test/read-boot
        {:rf.cofx/requires [:cofx-test/boot-token]}
        (fn [{:keys [cofx-test/boot-token]} _]
          (reset! seen boot-token)
          {}))
      (rf/dispatch-sync [:cofx-test/read-boot]
                        {:rf.cofx {:cofx-test/boot-token "abc.jwt.xyz"}})
      (is (= "abc.jwt.xyz" @seen)
          "the supplied recordable value was delivered verbatim from the token"))))

(deftest rf-time-ms-is-provided-recordable-and-always-present
  (testing ":rf/time-ms is the framework's one provided recordable
            registration; declaring it delivers the router-stamped value flat
            (EP-0017 §2)"
    (let [seen (atom ::unset)]
      (rf/reg-event-fx :cofx-test/read-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms]} _]
          (reset! seen time-ms)
          {}))
      (rf/dispatch-sync [:cofx-test/read-time])
      (is (number? @seen)
          ":rf/time-ms delivered the stamped epoch-ms (always present — the enqueue stamp guarantees it)")
      (let [reg (registrar/lookup :cofx :rf/time-ms)]
        (is (true? (:recordable? reg)) ":rf/time-ms is recordable")
        (is (true? (:provided? reg)) ":rf/time-ms is provided")))))

;; ===========================================================================
;; 4. Strict-replay missing-required fails loudly (ADVERSARIAL)
;; ===========================================================================

(deftest missing-required-provided-fact-fails-loudly
  (testing "ADVERSARIAL: a declared PROVIDED recordable fact ABSENT from the
            token is `:rf.error/missing-required-cofx` in every mode — the
            cascade halts before the handler runs (strict-replay loud failure;
            EP-0017 §5)"
    (let [traces (collect-traces! ::missing)
          fired? (atom false)]
      (rf/reg-cofx :cofx-test/required-boundary
        {:recordable? true :provided? true})
      (rf/reg-event-fx :cofx-test/needs-boundary
        {:rf.cofx/requires [:cofx-test/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      ;; Dispatch WITHOUT supplying the provided fact on the token.
      (let [ex (try (rf/dispatch-sync [:cofx-test/needs-boundary]) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (rf/unregister-listener! ::missing)
        (is (false? @fired?)
            "the handler never ran — missing-required halts the cascade")
        (is (some? ex)
            "the dispatch threw rather than silently re-reading the host")
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
            "the throw carries :rf.error/missing-required-cofx")
        (let [errs (filter #(= :rf.error/missing-required-cofx (:operation %)) @traces)]
          (is (= 1 (count errs)) "exactly one missing-required-cofx error trace")
          (is (= :cofx-test/required-boundary
                 (get-in (first errs) [:tags :rf.cofx/id]))
              ":rf.cofx/id names the absent fact"))))))

;; ===========================================================================
;; 5. typo→unregistered vs declared-absent→missing-required SPLIT (ADVERSARIAL)
;; ===========================================================================

(deftest typo-yields-unregistered-not-missing
  (testing "ADVERSARIAL: a declared id with NO `reg-cofx` registration (the
            typo case) is `:rf.error/unregistered-cofx` — DISTINCT from
            `:rf.error/missing-required-cofx` (a registered-but-absent provided
            fact). The two-error split is the EP-0017 §7 contract."
    (let [traces (collect-traces! ::typo)]
      ;; :cofx-test/typpo is NOT registered anywhere — a typo.
      (rf/reg-event-fx :cofx-test/has-typo
        {:rf.cofx/requires [:cofx-test/typpo]}
        (fn [_ _] {}))
      (let [ex (try (rf/dispatch-sync [:cofx-test/has-typo]) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (rf/unregister-listener! ::typo)
        (is (= :rf.error/unregistered-cofx (:rf.error/id (ex-data ex)))
            "an unregistered (typo'd) id is :rf.error/unregistered-cofx, NOT missing-required")
        (let [errs (filter #(= :rf.error/unregistered-cofx (:operation %)) @traces)]
          (is (= 1 (count errs)) "exactly one unregistered-cofx trace")
          (is (= :cofx-test/typpo (get-in (first errs) [:tags :rf.cofx/id]))
              ":rf.cofx/id names the unregistered id")
          (is (= :cofx-test/has-typo (get-in (first errs) [:tags :failing-id]))
              ":failing-id names the declaring handler"))))))

(deftest registered-absent-vs-unregistered-are-different-errors
  (testing "the split is real: a REGISTERED-but-absent provided fact →
            missing-required; an UNREGISTERED id → unregistered-cofx — never
            conflated"
    (rf/reg-cofx :cofx-test/registered-provided {:recordable? true :provided? true})
    (rf/reg-event-fx :cofx-test/absent-registered
      {:rf.cofx/requires [:cofx-test/registered-provided]}
      (fn [_ _] {}))
    (rf/reg-event-fx :cofx-test/absent-unregistered
      {:rf.cofx/requires [:cofx-test/never-reg]}
      (fn [_ _] {}))
    (let [missing-id   (-> (try (rf/dispatch-sync [:cofx-test/absent-registered]) nil
                                (catch clojure.lang.ExceptionInfo e e))
                           ex-data :rf.error/id)
          unregistered (-> (try (rf/dispatch-sync [:cofx-test/absent-unregistered]) nil
                                (catch clojure.lang.ExceptionInfo e e))
                           ex-data :rf.error/id)]
      (is (= :rf.error/missing-required-cofx missing-id))
      (is (= :rf.error/unregistered-cofx unregistered))
      (is (not= missing-id unregistered)
          "the two failure modes are distinct error categories"))))

;; ===========================================================================
;; 6. reg-event-db rejects :rf.cofx/requires + malformed / collision
;; ===========================================================================

(deftest db-handler-requires-is-registration-error
  (testing "`:rf.cofx/requires` on `reg-event-db` is a registration-time
            `:rf.error/cofx-request-invalid` (a db handler cannot take
            delivery; EP-0017 §4)"
    (let [ex (try
               (rf/reg-event-db :cofx-test/db-with-requires
                 {:rf.cofx/requires [:rf/time-ms]}
                 (fn [db _] db))
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "reg-event-db with :rf.cofx/requires threw at registration")
      (is (= :rf.error/cofx-request-invalid (:rf.error/id (ex-data ex)))))))

(deftest malformed-requires-is-cofx-request-invalid
  (testing "a non-vector / non-id `:rf.cofx/requires` is
            `:rf.error/cofx-request-invalid` at registration"
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event-fx :cofx-test/bad-requires-1
                      {:rf.cofx/requires :not-a-vector}
                      (fn [_ _] {}))
                    nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event-fx :cofx-test/bad-requires-2
                      {:rf.cofx/requires [42]}
                      (fn [_ _] {}))
                    nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))))

(deftest duplicate-requires-is-name-collision
  (testing "declaring the same id twice in one consumer scope is
            `:rf.error/cofx-name-collision` (EP-0017 §4)"
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-event-fx :cofx-test/dup-requires
                      {:rf.cofx/requires [:rf/time-ms :rf/time-ms]}
                      (fn [_ _] {}))
                    nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))))

(deftest reg-cofx-colliding-with-fold-arg-is-collision
  (testing "a `reg-cofx` id colliding with a fold argument key (`:db` /
            `:event`) is `:rf.error/cofx-name-collision` (EP-0017 §8)"
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-cofx :db (fn [] :nope))
                    nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-cofx :event (fn [] :nope))
                    nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))))

(deftest provided-without-recordable-is-rejected-at-registration
  (testing "`reg-cofx` with `{:provided? true}` but no `:recordable? true` is
            a registration-time hard error — a provided fact is recordable by
            definition; the malformed grade would otherwise register as an
            ambient fact with a nil supplier and surface only as an opaque
            host throw at delivery (rf2-cu8wet · Spec-Schemas §`:rf/cofx-meta`)"
    (let [ex (try (rf/reg-cofx :cofx-test/bad-grade {:provided? true})
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the malformed registration threw")
      (is (= :rf.error/cofx-name-collision (:rf.error/id (ex-data ex)))
          "rejected at the call site, not as a late delivery NPE")
      (is (= :cofx-test/bad-grade (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #":recordable\? true" (:reason (ex-data ex)))
          "the reason points the author at the fix")
      (is (nil? (registrar/lookup :cofx :cofx-test/bad-grade))
          "the malformed fact did NOT register"))))

(deftest provided-recordable-registers-cleanly
  (testing "the well-formed provided-recordable grade
            (`{:recordable? true :provided? true}`) still registers without a
            supplier — the cu8wet guard rejects ONLY the meaningless combo"
    (is (= :cofx-test/well-formed
           (rf/reg-cofx :cofx-test/well-formed
             {:recordable? true :provided? true})))
    (let [meta (registrar/lookup :cofx :cofx-test/well-formed)]
      (is (true? (:recordable? meta)))
      (is (true? (:provided? meta)))
      (is (nil? (:handler-fn meta)) "a provided fact legitimately omits its supplier"))))

;; ===========================================================================
;; 6c. :rf.cofx/run stamps the PRODUCED value under :rf.cofx/value and the
;;     requirement-arg under :rf.cofx/arg (rf2-sepqgg)
;; ===========================================================================

(deftest cofx-run-stamps-produced-value-and-arg
  (testing "the `:rf.cofx/run` success op carries the supplier's PRODUCED
            value under `:rf.cofx/value` (the coeffect that egresses) and the
            requirement-arg under the distinct `:rf.cofx/arg` (rf2-sepqgg)"
    (let [traces (collect-traces! ::run-tags)]
      ;; A parameterized ambient supplier: the requirement-arg is the
      ;; storage key; the produced value is what it reads back.
      (rf/reg-cofx :cofx-test/local-pref
        (fn [storage-key] (str "value-for-" storage-key)))
      (rf/reg-event-fx :cofx-test/read-pref
        {:rf.cofx/requires [[:cofx-test/local-pref "theme"]]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-pref])
      (rf/unregister-listener! ::run-tags)
      (let [runs (filter #(= :rf.cofx/run (:operation %)) @traces)
            run  (first (filter #(= :cofx-test/local-pref
                                    (get-in % [:tags :rf.cofx/id]))
                                runs))]
        (is (some? run) "the parameterized supplier emitted a :rf.cofx/run op")
        (is (= "value-for-theme" (get-in run [:tags :rf.cofx/value]))
            ":rf.cofx/value is the PRODUCED value, not the requirement-arg")
        (is (= "theme" (get-in run [:tags :rf.cofx/arg]))
            "the requirement-arg rides the distinct :rf.cofx/arg tag")))))

(deftest cofx-run-no-arg-omits-arg-tag
  (testing "a bare (no-arg) ambient supplier stamps `:rf.cofx/value` (the
            produced value) and OMITS `:rf.cofx/arg` (rf2-sepqgg — parity with
            the prior arg-omission on the 1-arity path)"
    (let [traces (collect-traces! ::run-noarg)]
      (rf/reg-cofx :cofx-test/locale2 (fn [] "en-AU"))
      (rf/reg-event-fx :cofx-test/read-locale2
        {:rf.cofx/requires [:cofx-test/locale2]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-locale2])
      (rf/unregister-listener! ::run-noarg)
      (let [run (first (filter #(and (= :rf.cofx/run (:operation %))
                                     (= :cofx-test/locale2
                                        (get-in % [:tags :rf.cofx/id])))
                               @traces))]
        (is (some? run))
        (is (= "en-AU" (get-in run [:tags :rf.cofx/value])))
        (is (not (contains? (:tags run) :rf.cofx/arg))
            "no requirement-arg ⇒ :rf.cofx/arg is omitted")))))

(deftest cofx-run-sensitive-produced-value-is-redacted-end-to-end
  (testing "a sensitive PRODUCED value from a real ambient supplier is
            redacted on the `:rf.cofx/run` trace by the marks chokepoint
            (`marks/project-cofx-run-tags`, wired to `:rf.cofx/value`) before
            the event reaches any listener — the bug rf2-sepqgg guards against:
            the redaction must act on what actually egresses"
    (let [traces (collect-traces! ::run-redact)]
      ;; The supplier PRODUCES a map with a sensitive sub-path. Marks are
      ;; declared on the cofx registration; the produced value egresses into
      ;; :coeffects, so the run-op stamp of it must be redacted.
      (rf/reg-cofx :cofx-test/session
        {:sensitive [[:token]]}
        (fn [] {:token "super-secret-jwt" :public "ok"}))
      (rf/reg-event-fx :cofx-test/read-session
        {:rf.cofx/requires [:cofx-test/session]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-session])
      (rf/unregister-listener! ::run-redact)
      (let [run (first (filter #(and (= :rf.cofx/run (:operation %))
                                     (= :cofx-test/session
                                        (get-in % [:tags :rf.cofx/id])))
                               @traces))]
        (is (some? run) "the supplier emitted a :rf.cofx/run op")
        (is (= :rf/redacted (get-in run [:tags :rf.cofx/value :token]))
            "the sensitive sub-path of the PRODUCED value is redacted on the trace")
        (is (= "ok" (get-in run [:tags :rf.cofx/value :public]))
            "non-sensitive sub-paths pass through")))))

;; ===========================================================================
;; 7. inject-cofx is REMOVED — hard error :rf.error/inject-cofx-removed
;; ===========================================================================

(deftest inject-cofx-call-is-hard-error
  (testing "calling `inject-cofx` (fn form) is the hard error
            `:rf.error/inject-cofx-removed` naming the replacement (EP-0017 §8)"
    (let [ex (try (rf/inject-cofx* :anything) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "inject-cofx* threw")
      (is (= :rf.error/inject-cofx-removed (:rf.error/id (ex-data ex))))
      (is (= :anything (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #":rf.cofx/requires" (:reason (ex-data ex)))
          "the reason names :rf.cofx/requires as the replacement"))))

(deftest cofx-inject-cofx-fn-throws
  (testing "the underlying `re-frame.cofx/inject-cofx` stub throws the same
            removal error regardless of arity"
    (is (= :rf.error/inject-cofx-removed
           (-> (try (cofx/inject-cofx :x) nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/inject-cofx-removed
           (-> (try (cofx/inject-cofx :x :v) nil (catch clojure.lang.ExceptionInfo e e))
               ex-data :rf.error/id)))))

;; ===========================================================================
;; 8. :rf.world/inputs dispatch opt is renamed (hard error)
;; ===========================================================================

(deftest world-inputs-dispatch-opt-renamed
  (testing "supplying the retired `:rf.world/inputs` dispatch opt is the hard
            error `:rf.error/world-inputs-renamed` naming `:rf.cofx` (EP-0017 §3)"
    (rf/reg-event-fx :cofx-test/wi-renamed (fn [_ _] {}))
    (let [ex (try (rf/dispatch-sync [:cofx-test/wi-renamed]
                                    {:rf.world/inputs {:rf/time-ms 1}})
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the dispatch threw")
      (is (= :rf.error/world-inputs-renamed (:rf.error/id (ex-data ex))))
      (is (= :rf.cofx (:replacement (ex-data ex)))
          "the error names :rf.cofx as the replacement"))))

;; ===========================================================================
;; 9. :platforms gating on ambient suppliers (preserved from the prior model)
;; ===========================================================================

(deftest platforms-gating-skips-client-only-ambient-on-jvm
  (testing ":platforms #{:client} ambient supplier is skipped on JVM (:server)
            — emits :rf.cofx/skipped-on-platform and delivers nothing; the
            event still runs (Spec 011 §634-642)"
    (let [traces       (collect-traces! ::plat)
          cofx-fired?  (atom false)
          event-fired? (atom false)
          seen         (atom ::unset)]
      (rf/reg-cofx :cofx-test/browser-locale
        {:platforms #{:client}}
        (fn [] (reset! cofx-fired? true) "en-US"))
      (rf/reg-event-fx :cofx-test/read-browser-locale
        {:rf.cofx/requires [:cofx-test/browser-locale]}
        (fn [{:keys [cofx-test/browser-locale] :as cofx} _]
          (reset! event-fired? true)
          (reset! seen (contains? cofx :cofx-test/browser-locale))
          {}))
      (rf/dispatch-sync [:cofx-test/read-browser-locale])
      (rf/unregister-listener! ::plat)
      (is (false? @cofx-fired?) "the client-only supplier did NOT run on :server")
      (is (true? @event-fired?) "the event still ran — only the supplier was skipped")
      (is (false? @seen) "the skipped fact was NOT delivered flat")
      (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %)) @traces)]
        (is (= 1 (count skips)) "exactly one skipped-on-platform trace")
        (is (= :cofx-test/browser-locale (get-in (first skips) [:tags :rf.cofx/id])))))))

;; ===========================================================================
;; 10. handler-meta surfaces :rf.cofx/requires as authored (reflection)
;; ===========================================================================

(deftest handler-meta-surfaces-requires
  (testing "`:rf.cofx/requires` surfaces in handler-meta exactly as authored
            (Spec 009 §9 — the complete consumption record)"
    (rf/reg-event-fx :cofx-test/reflective
      {:rf.cofx/requires [:rf/time-ms]}
      (fn [_ _] {}))
    (let [meta (registrar/lookup :event :cofx-test/reflective)]
      (is (= [:rf/time-ms] (:rf.cofx/requires meta))
          "the raw declaration is retained for reflection")
      (is (= [{:id :rf/time-ms :arg :re-frame.cofx/no-arg}]
             (:rf.cofx/requires-parsed meta))
          "the parsed entry vector drives delivery"))))

;; ===========================================================================
;; 11. EP-0002 frameless dispatch still raises no-frame-context
;; ===========================================================================

(deftest frameless-dispatch-raises-no-frame-context
  (testing "a bare dispatch-sync outside any frame scope raises
            :rf.error/no-frame-context (no :rf/default floor); the handler
            never runs"
    (let [fired? (atom false)]
      (rf/reg-event-fx :cofx-test/frameless
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [_ _] (reset! fired? true) {}))
      (binding [frame/*current-frame* nil]
        (let [ex (try (rf/dispatch-sync [:cofx-test/frameless]) nil
                      (catch clojure.lang.ExceptionInfo e e))]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))))
      (is (false? @fired?) "the handler never ran"))))
