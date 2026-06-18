(ns re-frame.cofx-cljs-test
  "EP-0017 slice-A.3: value-returning `reg-cofx`, `:rf.cofx/requires`
  declared-only delivery, the cofx error family, and `inject-cofx` removal.
  Per Spec 001 §`reg-cofx` / §The declaration key, Spec 002 §Satisfaction
  algorithm, and Spec 009 §Error catalogue.

  ## Dual-runtime (rf2-49eush)

  Named `*_cljs_test.cljc` so the shadow-cljs `:node-test` build
  (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both discover it. The whole adversarial spine —
  declared-only NON-delivery, missing-required throw, the typo-vs-absent
  error SPLIT, inject-cofx-removed, world-inputs-renamed, the
  registration-error / collision cases — was previously a `.clj`
  (`re-frame.cofx-test`), invisible to `:node-test` (shadow compiles only
  `.cljc/.cljs`). That left `re-frame.cofx`'s CLJS reader-conditional arms
  behaviorally UNEXERCISED: the supplier-exception message read
  `#?(:clj (.getMessage t) :cljs (.-message t))` and the catch
  `#?(:clj Throwable :cljs :default)` in the delivery path. Per EP §10 the
  testing story targets the CLJS reference implementation, so the contract
  gating replay determinism must run where apps run. The exception catches
  here use the canonical `#?(:clj clojure.lang.ExceptionInfo
  :cljs cljs.core/ExceptionInfo)` reader-conditional so the same assertions
  hold on both runtimes.

  These tests establish an explicit frame scope: the shared
  `make-reset-runtime-fixture` installs the plain-atom adapter, ensures the
  conventional `:rf/default` app frame, and binds `*current-frame*` to it for
  the body (the default `:ambient-frame :rf/default`) — equivalent to
  wrapping every body in `(with-frame :rf/default …)`, so the ambient
  `dispatch-sync` calls resolve their target through scope rather than a
  synthesised default (EP-0002 carried-invariant contract — no `:rf/default`
  floor). The fixture's snapshot/restore baseline (rf2-7hwnu) preserves the
  framework registrations that landed at ns-load — including the standard
  `:rf/time-ms` provided-recordable cofx — across both runtimes, replacing
  the JVM-only `(require … :reload)` resurrection the prior `.clj` used."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.late-bind]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that accumulates
  events. Tests must (rf/unregister-listener! :trace id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
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
      (rf/reg-event :cofx-test/read-locale
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
      (rf/reg-event :cofx-test/read-echo
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
      (rf/reg-event :cofx-test/declares-only-time
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
      (rf/reg-event :cofx-test/declares-nothing
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
;; 2b. NEGATIVE: a NESTED / grouped :rf.cofx sub-map is NOT staged flat
;;     (rf2-rfdd6v — regression-prevention for the flat-vs-grouped decision)
;; ===========================================================================

(deftest grouped-cofx-sub-map-is-not-staged-flat
  (testing "ADVERSARIAL / NEGATIVE (rf2-rfdd6v): EP-0017 §3 mandates `:rf.cofx`
            is FLAT (fact-name → value, NO grouping sub-maps; Open Issue 1
            settled flat-vs-grouped). The RETIRED grouped shape — a sub-map
            keyed by a group, e.g. `{:rf.cofx {:random {:roll 4}}}` — must NOT
            be silently re-accepted: a handler declaring the NESTED leaf
            (`:random/roll`) never sees `4` staged flat, because the declared
            id is not a flat key on the token. The grouped sub-map's owner key
            (`:random`) is itself only a recordable leaf-name, never an
            implicit container the runtime descends into.

            This locks the migration intent against a regression that would
            re-introduce grouping by descending one level into a grouped
            sub-map. `undeclared-leaf-on-token-is-not-delivered` covers the
            flat-undeclared dimension; this extends the negative pin to the
            STRUCTURAL-SHAPE dimension."
    (let [staged-leaf?  (atom ::unset)
          staged-group? (atom ::unset)
          ex            (atom ::unset)]
      ;; The handler declares the FLAT nested-leaf id `:random/roll` — the
      ;; key the retired grouped shape `{:random {:roll 4}}` would have to be
      ;; descended into to satisfy. It is NOT registered as a cofx (the
      ;; grouped seats never shipped a producer), so declared-only delivery
      ;; must FAIL CLOSED on it as an unregistered id rather than silently
      ;; dig `4` out of the grouped sub-map and stage it flat.
      (rf/reg-event :cofx-test/declares-nested-leaf
        {:rf.cofx/requires [:random/roll]}
        (fn [{:keys [random/roll] :as cofx} _]
          (reset! staged-leaf? (contains? cofx :random/roll))
          (reset! staged-group? (contains? cofx :random))
          {}))
      (reset! ex
        (try (rf/dispatch-sync [:cofx-test/declares-nested-leaf]
                               ;; the RETIRED grouped shape supplied on the token
                               {:rf.cofx {:random {:roll 4}}})
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) e e)))
      ;; The declared nested leaf is unregistered → the cascade halts loud
      ;; with :rf.error/unregistered-cofx; the grouped sub-map was never
      ;; descended into to satisfy it.
      (is (some? @ex)
          "a grouped sub-map does NOT silently satisfy a declared nested leaf — it fails loud")
      (is (= :rf.error/unregistered-cofx (:rf.error/id (ex-data @ex)))
          "the declared :random/roll is an unregistered id (the grouped shape is not descended into)")
      (is (= :random/roll (:rf.cofx/id (ex-data @ex)))
          ":rf.cofx/id names the declared nested leaf, NOT the grouped owner key")
      ;; And the handler never ran, so neither the nested leaf nor the grouped
      ;; owner key was staged as a flat coeffect.
      (is (= ::unset @staged-leaf?)
          "the handler never ran — the nested leaf was never staged flat")
      (is (= ::unset @staged-group?)
          "the grouped owner key :random was never staged flat either")))

  (testing "and the grouped OWNER key, declared as a leaf, also fails closed —
            it is not registered, so the sub-map value is never staged under it"
    ;; The complementary angle: declare the grouped owner key (`:random`)
    ;; itself. It is likewise unregistered, so the `{:roll 4}` sub-map riding
    ;; the token under `:random` is NOT staged flat as `:random`'s value — the
    ;; declared-only machinery rejects the unregistered id rather than
    ;; accepting the grouped sub-map verbatim. (A flat recordable leaf would
    ;; have to be REGISTERED to be delivered; the retired group seat is not.)
    (let [staged? (atom ::unset)
          ex      (atom ::unset)]
      (rf/reg-event :cofx-test/declares-group-owner
        {:rf.cofx/requires [:random]}
        (fn [{:keys [random] :as cofx} _]
          (reset! staged? (contains? cofx :random))
          {}))
      (reset! ex
        (try (rf/dispatch-sync [:cofx-test/declares-group-owner]
                               {:rf.cofx {:random {:roll 4}}})
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) e e)))
      (is (some? @ex)
          "an unregistered grouped owner key fails loud — the sub-map is not blessed into a flat leaf")
      (is (= :rf.error/unregistered-cofx (:rf.error/id (ex-data @ex)))
          ":random is unregistered — the grouped sub-map under it is never staged flat")
      (is (= ::unset @staged?)
          "the handler never ran — the grouped sub-map was not staged under :random"))))

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
      (rf/reg-event :cofx-test/read-boot
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
      (rf/reg-event :cofx-test/read-time
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
;; 3b. A REPLY / completion envelope carries :rf.cofx FLAT, freshly stamped
;;     (rf2-ada4xt — reply-envelope :rf.cofx carry-through)
;;
;; EP-0017 §Relationships (EP-0011) + the Backwards-Compatibility table state
;; that reply / completion envelopes carry `:rf.cofx` in the SAME canonical
;; flat slot, and "completion events stamp their own values". A reply, in
;; slice-A terms, IS a completion event re-dispatched as a child (`:dispatch`
;; fx): per `fx/inheritable-envelope-keys`, `:rf.cofx` is DELIBERATELY NOT
;; inherited (router.cljc §EP-0017 stamping), so the child re-enters
;; `build-envelope` and is stamped a FRESH `:rf/time-ms` — a DISTINCT causal
;; token, not the originating dispatch's.
;;
;; The reviewer (rf2-ada4xt) suggested `reply_test.cljc`, but that suite is
;; the PURE-substrate `re-frame.reply` conformance (no runtime, no router) and
;; is JVM-only-discovered (its ns ends `-test`, not `-cljs-test`). The
;; carry-through is a router / envelope behavior with no `re-frame.reply`
;; substrate fn to pin in isolation, so the faithful runtime pin lives HERE,
;; in the dual-runtime cofx envelope suite that ACTUALLY runs on CLJS.
;; ===========================================================================

(deftest reply-envelope-carries-rf-cofx-flat-and-freshly-stamped
  (testing "rf2-ada4xt: a completion / reply event dispatched as a child of an
            originating event carries :rf.cofx in the canonical FLAT slot, and
            its :rf/time-ms is FRESHLY stamped — distinct from the originating
            request token's scripted value (NOT inherited)."
    (let [traces (collect-traces! ::reply-cofx)]
      ;; The originating ("request") event: scripted with a fixed causal
      ;; :rf/time-ms so we can prove the reply does NOT inherit it. Its
      ;; handler dispatches the completion ("reply") event — the reply
      ;; envelope re-enters build-envelope and is stamped fresh.
      (rf/reg-event :cofx-test/request
        (fn [_ _]
          {:fx [[:dispatch [:cofx-test/replied {:status :ok :value {:title "Welcome"}}]]]}))
      (rf/reg-event :cofx-test/replied (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:cofx-test/request]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (rf/unregister-listener! :trace ::reply-cofx)
      (let [dispatched   (filter #(= :rf.event/dispatched (:operation %)) @traces)
            request-env  (first (filter #(= :cofx-test/request
                                            (first (get-in % [:tags :rf.event/v])))
                                        dispatched))
            reply-env    (first (filter #(= :cofx-test/replied
                                            (first (get-in % [:tags :rf.event/v])))
                                        dispatched))
            request-cofx (get-in request-env [:tags :rf.cofx])
            reply-cofx   (get-in reply-env   [:tags :rf.cofx])]
        (is (some? request-env) "the originating request event was dispatched")
        (is (some? reply-env)   "the completion / reply event was dispatched")
        ;; (a) the reply envelope carries :rf.cofx, present and a FLAT map
        (is (map? reply-cofx)
            "the reply envelope carries :rf.cofx in the canonical slot, a flat map")
        (is (contains? reply-cofx :rf/time-ms)
            "the reply's :rf.cofx carries :rf/time-ms flat (fact-name → value, no grouping)")
        ;; (c) FLAT shape: every value is a leaf under an owner-qualified key,
        ;; not a grouping sub-map (the retired grouped shape would nest here).
        (is (every? (complement map?) (vals reply-cofx))
            "the reply's :rf.cofx is FLAT — no value is a grouping sub-map")
        ;; (b) freshly stamped: the reply's :rf/time-ms is a real epoch-ms
        ;; integer and is DISTINCT from the request token's scripted value —
        ;; the child stamps its OWN causal time (`:rf.cofx` is not inherited).
        (is (integer? (:rf/time-ms reply-cofx))
            "the reply's :rf/time-ms is a freshly stamped epoch-ms integer")
        (is (= 1781078400123 (:rf/time-ms request-cofx))
            "the request token kept its scripted :rf/time-ms verbatim")
        (is (not= (:rf/time-ms request-cofx) (:rf/time-ms reply-cofx))
            "the reply STAMPS ITS OWN :rf/time-ms — it does NOT inherit the originating request's causal token")))))

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
      (rf/reg-event :cofx-test/needs-boundary
        {:rf.cofx/requires [:cofx-test/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      ;; Dispatch WITHOUT supplying the provided fact on the token.
      (let [ex (try (rf/dispatch-sync [:cofx-test/needs-boundary]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
        (rf/unregister-listener! :trace ::missing)
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
      (rf/reg-event :cofx-test/has-typo
        {:rf.cofx/requires [:cofx-test/typpo]}
        (fn [_ _] {}))
      (let [ex (try (rf/dispatch-sync [:cofx-test/has-typo]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
        (rf/unregister-listener! :trace ::typo)
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
    (rf/reg-event :cofx-test/absent-registered
      {:rf.cofx/requires [:cofx-test/registered-provided]}
      (fn [_ _] {}))
    (rf/reg-event :cofx-test/absent-unregistered
      {:rf.cofx/requires [:cofx-test/never-reg]}
      (fn [_ _] {}))
    (let [missing-id   (-> (try (rf/dispatch-sync [:cofx-test/absent-registered]) nil
                                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
                           ex-data :rf.error/id)
          unregistered (-> (try (rf/dispatch-sync [:cofx-test/absent-unregistered]) nil
                                (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
                           ex-data :rf.error/id)]
      (is (= :rf.error/missing-required-cofx missing-id))
      (is (= :rf.error/unregistered-cofx unregistered))
      (is (not= missing-id unregistered)
          "the two failure modes are distinct error categories"))))

;; ===========================================================================
;; 6. reg-event accepts :rf.cofx/requires uniformly + malformed / collision
;; ===========================================================================

(deftest one-event-form-accepts-requires-uniformly
  (testing "EP-0018 closed the EP-0017 hole: `:rf.cofx/requires` is uniformly
            available on the ONE `reg-event` form. There is no longer a
            db-handler exception raising `:rf.error/cofx-request-invalid` at
            registration — a well-formed declaration registers cleanly and the
            declared fact is delivered flat into the coeffects map."
    (let [seen-time (atom ::unset)
          ex (try
               (rf/reg-event :cofx-test/event-with-requires
                 {:rf.cofx/requires [:rf/time-ms]}
                 (fn [{:keys [rf/time-ms]} _]
                   (reset! seen-time time-ms)
                   {}))
               nil
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (nil? ex)
          "a well-formed :rf.cofx/requires on reg-event registers without throwing")
      (rf/dispatch-sync [:cofx-test/event-with-requires]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (= 1781078400123 @seen-time)
          "the declared :rf/time-ms fact arrived flat in the coeffects map"))))

(deftest malformed-requires-is-cofx-request-invalid
  (testing "a non-vector / non-id `:rf.cofx/requires` is
            `:rf.error/cofx-request-invalid` at registration"
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event :cofx-test/bad-requires-1
                      {:rf.cofx/requires :not-a-vector}
                      (fn [_ _] {}))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event :cofx-test/bad-requires-2
                      {:rf.cofx/requires [42]}
                      (fn [_ _] {}))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))))

(deftest duplicate-requires-is-name-collision
  (testing "declaring the same id twice in one consumer scope is
            `:rf.error/cofx-name-collision` (EP-0017 §4)"
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-event :cofx-test/dup-requires
                      {:rf.cofx/requires [:rf/time-ms :rf/time-ms]}
                      (fn [_ _] {}))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))))

(deftest reg-cofx-colliding-with-fold-arg-is-collision
  (testing "a `reg-cofx` id colliding with a fold argument key (`:db` /
            `:event`) is `:rf.error/cofx-name-collision` (EP-0017 §8)"
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-cofx :db (fn [] :nope))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-cofx :event (fn [] :nope))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))))

(deftest provided-without-recordable-is-rejected-at-registration
  (testing "`reg-cofx` with `{:provided? true}` but no `:recordable? true` is
            a registration-time hard error — a provided fact is recordable by
            definition; the malformed grade would otherwise register as an
            ambient fact with a nil supplier and surface only as an opaque
            host throw at delivery (rf2-cu8wet · Spec-Schemas §`:rf/cofx-meta`).
            The taxonomy is `:rf.error/cofx-registration-invalid` (malformed
            metadata), NOT `:rf.error/cofx-name-collision` (rf2-d8mvke.6
            finding-1 — collision is reserved for duplicate ownership)."
    (let [ex (try (rf/reg-cofx :cofx-test/bad-grade {:provided? true})
                  nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "the malformed registration threw")
      (is (= :rf.error/cofx-registration-invalid (:rf.error/id (ex-data ex)))
          "rejected as malformed metadata, not a name collision and not a late delivery NPE")
      (is (= :cofx-test/bad-grade (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #":recordable\? true" (:reason (ex-data ex)))
          "the reason points the author at the fix")
      (is (nil? (registrar/lookup :cofx :cofx-test/bad-grade))
          "the malformed fact did NOT register"))))

(deftest no-supplier-non-provided-is-registration-invalid
  (testing "`reg-cofx` with NO supplier and no `:provided?` is
            `:rf.error/cofx-registration-invalid` — an ambient fact must carry
            a value-returning supplier; only a provided recordable fact may
            omit it (rf2-d8mvke.6 finding-1 — malformed metadata, NOT a name
            collision)"
    (let [ex (try (rf/reg-cofx :cofx-test/no-supplier {:doc "missing supplier"})
                  nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "the no-supplier registration threw")
      (is (= :rf.error/cofx-registration-invalid (:rf.error/id (ex-data ex)))
          "a missing supplier is malformed metadata, not a name collision")
      (is (= :cofx-test/no-supplier (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #"no supplier" (:reason (ex-data ex)))
          "the reason names the missing supplier")
      (is (nil? (registrar/lookup :cofx :cofx-test/no-supplier))
          "the malformed fact did NOT register"))))

(deftest provided-with-supplier-is-rejected-at-registration
  (testing "ADVERSARIAL (rf2-d8mvke.1): `reg-cofx` with `{:recordable? true
            :provided? true}` AND a supplier is a contradictory registration —
            a provided recordable fact has NO generator (its owner stamps the
            token; delivery reads it verbatim), so the supplier would be
            SILENTLY IGNORED and the first consumer would fail as
            missing-required. It is rejected at the call site as
            `:rf.error/cofx-registration-invalid`."
    (let [ex (try (rf/reg-cofx :cofx-test/provided-with-fn
                    {:recordable? true :provided? true}
                    (fn [] "this would be silently ignored"))
                  nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "the contradictory registration threw")
      (is (= :rf.error/cofx-registration-invalid (:rf.error/id (ex-data ex)))
          "a provided fact carrying a supplier is malformed metadata")
      (is (= :cofx-test/provided-with-fn (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #"silently ignored" (:reason (ex-data ex)))
          "the reason explains the supplier is silently ignored at delivery")
      (is (nil? (registrar/lookup :cofx :cofx-test/provided-with-fn))
          "the contradictory fact did NOT register"))))

(deftest provided-without-supplier-registers-cleanly
  (testing "the VALID provided shape — `{:recordable? true :provided? true}`
            with NO supplier — still registers (rf2-d8mvke.1 keeps it valid:
            only the supplier-bearing contradiction is rejected)"
    (is (= :cofx-test/clean-provided
           (rf/reg-cofx :cofx-test/clean-provided
             {:recordable? true :provided? true})))
    (let [meta (registrar/lookup :cofx :cofx-test/clean-provided)]
      (is (true? (:recordable? meta)) "recordable")
      (is (true? (:provided? meta)) "provided")
      (is (nil? (:handler-fn meta)) "no supplier — its owner stamps the token"))))

(deftest recordable-with-supplier-no-provided-registers-cleanly
  (testing "a RECORDABLE fact WITH a supplier but no `:provided?` still
            registers (rf2-d8mvke.1 — recordable-with-supplier is valid; the
            generator backs the recordable fact)"
    (is (= :cofx-test/recordable-gen
           (rf/reg-cofx :cofx-test/recordable-gen
             {:recordable? true}
             (fn [] "generated"))))
    (let [meta (registrar/lookup :cofx :cofx-test/recordable-gen)]
      (is (true? (:recordable? meta)) "recordable")
      (is (false? (:provided? meta)) "not provided")
      (is (fn? (:handler-fn meta)) "carries its generator supplier"))))

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

(deftest rf-prefixed-id-is-not-a-registration-time-collision
  (testing "an `rf.`-prefixed coeffect id registers CLEANLY — the
            owner-qualified-naming rule is a lint/tooling diagnostic, NOT a
            runtime registration-time `:rf.error/cofx-name-collision`
            (rf2-kkw7cn). `reg-cofx` cannot structurally tell an app id from a
            framework / subsystem one, so the framework and its subsystems may
            register many `:rf.*` cofx ids; the reserved-namespace convention is
            enforced by the recommended cofx lint (EP-0017 §9), not a structural
            guard. Spec 001 §Collisions + Conventions §Recordable-coeffect fact
            naming + Spec 009 cofx error catalogue all describe lint-only."
    (is (= :rf.route/some-subsystem-fact
           (rf/reg-cofx :rf.route/some-subsystem-fact
             {:recordable? true :provided? true}))
        "an `rf.X/*` (subsystem-shaped) id registers without throwing")
    (is (= :rf.app/ambient-pref
           (rf/reg-cofx :rf.app/ambient-pref
             {:doc "Lint would flag this app id; the registrar does not."}
             (fn [] "value")))
        "even an obviously app-shaped `rf.`-prefixed id is accepted at registration")
    (is (some? (registrar/lookup :cofx :rf.app/ambient-pref))
        "the `rf.`-prefixed registration is present in the registry")))

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
      (rf/reg-event :cofx-test/read-pref
        {:rf.cofx/requires [[:cofx-test/local-pref "theme"]]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-pref])
      (rf/unregister-listener! :trace ::run-tags)
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
      (rf/reg-event :cofx-test/read-locale2
        {:rf.cofx/requires [:cofx-test/locale2]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-locale2])
      (rf/unregister-listener! :trace ::run-noarg)
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
      (rf/reg-event :cofx-test/read-session
        {:rf.cofx/requires [:cofx-test/session]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:cofx-test/read-session])
      (rf/unregister-listener! :trace ::run-redact)
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

;; rf2-w9xyx1: `inject-cofx` / `inject-cofx*` are no longer on the public
;; `re-frame.core` facade. The migration alarm is the private (non-facade)
;; hard-error thrower `re-frame.cofx/inject-cofx`; a stale call to it still
;; raises `:rf.error/inject-cofx-removed` naming the replacement.
(deftest inject-cofx-call-is-hard-error
  (testing "calling the retained private `re-frame.cofx/inject-cofx` thrower is
            the hard error `:rf.error/inject-cofx-removed` naming the
            replacement (EP-0017 §8; facade removal rf2-w9xyx1)"
    (let [ex (try (cofx/inject-cofx :anything) nil
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "cofx/inject-cofx threw")
      (is (= :rf.error/inject-cofx-removed (:rf.error/id (ex-data ex))))
      (is (= :anything (:rf.cofx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #":rf.cofx/requires" (:reason (ex-data ex)))
          "the reason names :rf.cofx/requires as the replacement"))))

(deftest cofx-inject-cofx-fn-throws
  (testing "the underlying `re-frame.cofx/inject-cofx` stub throws the same
            removal error regardless of arity"
    (is (= :rf.error/inject-cofx-removed
           (-> (try (cofx/inject-cofx :x) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/inject-cofx-removed
           (-> (try (cofx/inject-cofx :x :v) nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))))

;; ===========================================================================
;; 8. :rf.world/inputs dispatch opt is renamed (hard error)
;; ===========================================================================

(deftest world-inputs-dispatch-opt-renamed
  (testing "supplying the retired `:rf.world/inputs` dispatch opt is the hard
            error `:rf.error/world-inputs-renamed` naming `:rf.cofx` (EP-0017 §3)"
    (rf/reg-event :cofx-test/wi-renamed (fn [_ _] {}))
    (let [ex (try (rf/dispatch-sync [:cofx-test/wi-renamed]
                                    {:rf.world/inputs {:rf/time-ms 1}})
                  nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "the dispatch threw")
      (is (= :rf.error/world-inputs-renamed (:rf.error/id (ex-data ex))))
      (is (= :rf.cofx (:replacement (ex-data ex)))
          "the error names :rf.cofx as the replacement"))))

;; ===========================================================================
;; 9. :platforms gating on ambient suppliers (preserved from the prior model)
;; ===========================================================================

(deftest platforms-gating-skips-off-platform-ambient
  (testing ":platforms #{:client} ambient supplier is skipped when the active
            platform is :server — emits :rf.cofx/skipped-on-platform and
            delivers nothing; the event still runs (Spec 011 §634-642).

            Cross-runtime: the host platform default differs (JVM :server,
            CLJS :client), so the test PINS the active platform to :server for
            the body via `rf/init-platform`, restoring the host default after
            — so the `#{:client}` supplier is deterministically OFF-platform
            and skipped on BOTH runtimes (rf2-49eush)."
    (let [host-default (interop/active-platform)]
      (rf/init-platform :server)
      (try
        (let [traces       (collect-traces! ::plat)
              cofx-fired?  (atom false)
              event-fired? (atom false)
              seen         (atom ::unset)]
          (rf/reg-cofx :cofx-test/browser-locale
            {:platforms #{:client}}
            (fn [] (reset! cofx-fired? true) "en-US"))
          (rf/reg-event :cofx-test/read-browser-locale
            {:rf.cofx/requires [:cofx-test/browser-locale]}
            (fn [{:keys [cofx-test/browser-locale] :as cofx} _]
              (reset! event-fired? true)
              (reset! seen (contains? cofx :cofx-test/browser-locale))
              {}))
          (rf/dispatch-sync [:cofx-test/read-browser-locale])
          (rf/unregister-listener! :trace ::plat)
          (is (false? @cofx-fired?) "the client-only supplier did NOT run on :server")
          (is (true? @event-fired?) "the event still ran — only the supplier was skipped")
          (is (false? @seen) "the skipped fact was NOT delivered flat")
          (let [skips (filter #(= :rf.cofx/skipped-on-platform (:operation %)) @traces)]
            (is (= 1 (count skips)) "exactly one skipped-on-platform trace")
            (is (= :cofx-test/browser-locale (get-in (first skips) [:tags :rf.cofx/id])))))
        (finally
          (rf/init-platform host-default))))))

;; ===========================================================================
;; 10. handler-meta surfaces :rf.cofx/requires as authored (reflection)
;; ===========================================================================

(deftest handler-meta-surfaces-requires
  (testing "`:rf.cofx/requires` surfaces in handler-meta exactly as authored
            (Spec 009 §9 — the complete consumption record)"
    (rf/reg-event :cofx-test/reflective
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
      (rf/reg-event :cofx-test/frameless
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [_ _] (reset! fired? true) {}))
      (binding [frame/*current-frame* nil]
        (let [ex (try (rf/dispatch-sync [:cofx-test/frameless]) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))))
      (is (false? @fired?) "the handler never ran"))))

;; ===========================================================================
;; 12. EP-0017 slice-B.7: recordable generator machinery (rf2-ygpac8)
;;
;;     A declared-absent generator-backed recordable fact runs its generator
;;     at PROCESSING-START under the router's `:live` mint policy; the produced
;;     value is validated against the registration's `:schema` (a PRODUCTION
;;     hard error on mismatch — `:rf.error/cofx-value-invalid`), written back
;;     into the causal record, and the `:rf.cofx/generated` trace op is
;;     emitted. Generation is the only slice-B step that mints an
;;     un-boundary-checked recordable value, so the per-leaf `:schema` check
;;     and the generation step ship together (Spec 002 §Mint policies / §5
;;     step 3; EP-0017 §5).
;; ===========================================================================

(defn- with-passthrough-validator
  "Install a minimal `(fn [schema value] truthy?)` validator + explainer
  through the schemas late-bind seam for the body, then restore the prior
  registrations. Lets the cofx `:schema` path exercise the REAL validation
  branch without depending on the optional schemas artefact being on the
  core-test classpath. The validator treats a `[:fn pred]`-style schema as a
  predicate call and any keyword/other schema as a structural `int?`/`any?`
  probe just rich enough for these tests; the contract under test is the cofx
  delivery wiring, not Malli."
  [validate-fn explain-fn body-fn]
  (let [prior-v (re-frame.late-bind/get-fn :schemas/validate-with-registered-fn)
        prior-e (re-frame.late-bind/get-fn :schemas/explain-with-registered-fn)]
    (re-frame.late-bind/set-fn! :schemas/validate-with-registered-fn validate-fn)
    (re-frame.late-bind/set-fn! :schemas/explain-with-registered-fn explain-fn)
    (try
      (body-fn)
      (finally
        (re-frame.late-bind/set-fn! :schemas/validate-with-registered-fn prior-v)
        (re-frame.late-bind/set-fn! :schemas/explain-with-registered-fn prior-e)))))

(deftest generator-runs-at-processing-start-fills-and-records
  (testing "ADVERSARIAL: a declared-absent generator-backed recordable fact
            runs its generator at processing-start (AFTER the enqueue-stamped
            `:rf/time-ms`), delivers the produced value flat, and writes it
            back into the causal `:rf.cofx` record so the post-generation
            token is what the fold (and the epoch) sees (EP-0017 §4/§5)"
    (let [gen-calls   (atom 0)
          seen-cofx   (atom nil)
          seen-delta  (atom nil)
          seen-time   (atom nil)]
      ;; A generator-backed recordable fact: recordable, NOT provided, with a
      ;; value-returning supplier. Deterministic body (returns a fixed value)
      ;; so the assertion is exact — the contract under test is WHEN it runs
      ;; and WHERE the value lands, not the randomness.
      (rf/reg-cofx :gen-test/delta
        {:recordable? true :doc "A generator-backed replayable delta."}
        (fn [] (swap! gen-calls inc) 7))
      (rf/reg-event :gen-test/inc
        {:rf.cofx/requires [:rf/time-ms :gen-test/delta]}
        (fn [{:keys [rf/time-ms gen-test/delta] :as cofx} _]
          (reset! seen-delta delta)
          (reset! seen-time time-ms)
          ;; The canonical complete record is always staged under :rf.cofx
          ;; (a flat key the runtime injects; not declarable, read via :as).
          (reset! seen-cofx (:rf.cofx cofx))
          {}))
      (rf/dispatch-sync [:gen-test/inc])
      (is (= 1 @gen-calls) "the generator ran exactly once, at processing-start")
      (is (= 7 @seen-delta) "the produced value is delivered flat under its id")
      (is (integer? @seen-time) "the enqueue-stamped :rf/time-ms is present")
      (is (= 7 (:gen-test/delta @seen-cofx))
          "the generated value was written back into the causal :rf.cofx record")
      (is (= @seen-time (:rf/time-ms @seen-cofx))
          "the record carries BOTH the enqueue-stamped time and the generated fact"))))

(deftest supplied-value-wins-generator-does-not-run
  (testing "ADVERSARIAL: a recordable fact PRESENT on the token (supplied /
            replayed) is delivered verbatim — the generator does NOT run, so
            replay re-presents the value and the generation step finds nothing
            to do (EP-0017 §4)"
    (let [gen-calls  (atom 0)
          seen-delta (atom nil)]
      (rf/reg-cofx :gen-test/supplied-delta
        {:recordable? true}
        (fn [] (swap! gen-calls inc) 99))
      (rf/reg-event :gen-test/use-supplied
        {:rf.cofx/requires [:gen-test/supplied-delta]}
        (fn [{:keys [gen-test/supplied-delta]} _]
          (reset! seen-delta supplied-delta) {}))
      ;; Supply the fact on the token — the replay / dispatch-opts path.
      (rf/dispatch-sync [:gen-test/use-supplied]
                        {:rf.cofx {:gen-test/supplied-delta 3}})
      (is (zero? @gen-calls) "supplied wins — the generator never ran")
      (is (= 3 @seen-delta) "the supplied value is delivered verbatim"))))

(deftest generator-emits-generated-trace-op
  (testing "ADVERSARIAL: the generation step emits exactly one
            `:rf.cofx/generated` trace op naming the generated fact + supplier
            id, distinct from the ambient `:rf.cofx/run` op (Spec 009 §Trace
            ops / EP-0017 §9)"
    (let [traces (collect-traces! ::generated)]
      (rf/reg-cofx :gen-test/traced
        {:recordable? true}
        (fn [] 42))
      (rf/reg-event :gen-test/traced-evt
        {:rf.cofx/requires [:gen-test/traced]}
        (fn [_ _] {}))
      (rf/dispatch-sync [:gen-test/traced-evt])
      (rf/unregister-listener! :trace ::generated)
      (let [gens (filter #(= :rf.cofx/generated (:operation %)) @traces)
            runs (filter #(= :rf.cofx/run (:operation %)) @traces)]
        (is (= 1 (count gens)) "exactly one :rf.cofx/generated op")
        (is (empty? runs)
            "a generated recordable fact does NOT emit the ambient :rf.cofx/run op")
        (let [gen (first gens)]
          (is (= :rf.cofx (:op-type gen)) "the op rides the :rf.cofx family")
          (is (= :gen-test/traced (get-in gen [:tags :rf.cofx/id]))
              ":rf.cofx/id names the generated fact + supplier id")
          (is (= 42 (get-in gen [:tags :rf.cofx/value]))
              ":rf.cofx/value carries the produced value"))))))

(deftest generated-value-schema-mismatch-is-hard-error
  (testing "ADVERSARIAL: a generated value that fails the registration's
            `:schema` is `:rf.error/cofx-value-invalid` — a HARD ERROR (the
            cascade halts before the handler runs; the bad value never folds
            into durable state). EP-0017 §5 / Spec 009"
    (with-passthrough-validator
      ;; The schema for :gen-test/bad is `:gen-test/positive`; the generator
      ;; produces -1, which fails. The validator returns false for that pair.
      (fn [schema value]
        (if (= schema :gen-test/positive) (and (integer? value) (pos? value)) true))
      (fn [schema value] {:schema schema :value value :failed true})
      (fn []
        (let [traces (collect-traces! ::bad-gen)
              fired? (atom false)]
          (rf/reg-cofx :gen-test/bad
            {:recordable? true :schema :gen-test/positive}
            (fn [] -1))                          ;; violates the schema
          (rf/reg-event :gen-test/uses-bad
            {:rf.cofx/requires [:gen-test/bad]}
            (fn [_ _] (reset! fired? true) {}))
          (let [ex (try (rf/dispatch-sync [:gen-test/uses-bad]) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e e))]
            (rf/unregister-listener! :trace ::bad-gen)
            (is (false? @fired?)
                "the handler never ran — a schema-invalid generated value halts the cascade")
            (is (some? ex) "the dispatch threw rather than folding the bad value")
            (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex)))
                "the throw carries :rf.error/cofx-value-invalid")
            (is (= :gen-test/bad (:rf.cofx/id (ex-data ex)))
                ":rf.cofx/id names the offending fact")
            (let [errs (filter #(= :rf.error/cofx-value-invalid (:operation %)) @traces)]
              (is (= 1 (count errs)) "exactly one cofx-value-invalid error trace")
              (is (= :gen-test/bad (get-in (first errs) [:tags :rf.cofx/id]))
                  "the error trace names the fact"))))))))

;; ---------------------------------------------------------------------------
;; 12b. STRUCTURAL-EDN check of the GENERATED value (rf2-rmroo4 slice B /
;;      rf2-uqz2ir). A generator-backed recordable value rides the durable
;;      causal record (write-back, epoch, replay, SSR, Xray) so it MUST be
;;      ordinary EDN data (EP-0017:386). Slice A pinned the SUPPLIED-value half
;;      at the dispatch boundary; this pins the GENERATED-value half at the
;;      `run-generator` write-back site. DEV-MODE gated (the declared-`:schema`
;;      check stays the prod contract); reuses the slice-A walker + error shape
;;      (`:rf.error/cofx-value-invalid`, reason `:non-edn-recordable-value`).
;; ---------------------------------------------------------------------------

(deftest generated-non-edn-value-is-cofx-value-invalid
  (testing "ADVERSARIAL (rf2-uqz2ir): a generator that mints a NON-EDN host
            handle (here an atom — a stand-in for a DOM node / Promise /
            function / Date / any host object) is `:rf.error/cofx-value-invalid`
            (reason `:non-edn-recordable-value`) — an ALWAYS-ON hard error
            (rf2-q34j26: production too) that halts the cascade BEFORE the bad
            value is written back into the durable `:rf.cofx` record and BEFORE
            the handler runs. EP-0017:386."
    (let [traces    (collect-traces! ::gen-non-edn)
          gen-calls (atom 0)
          fired?    (atom false)]
      ;; Generator-backed recordable fact (recordable, NOT provided, with a
      ;; supplier). It mints an ATOM — a genuine host handle, not EDN data.
      ;; No `:schema` declared, so the ALWAYS-ON `:schema` check is a no-op and
      ;; ONLY the structural-EDN guard can catch this (the point of slice B).
      (rf/reg-cofx :gen-test/host-handle
        {:recordable? true :doc "A generator that wrongly mints a host handle."}
        (fn [] (swap! gen-calls inc) (atom :a-host-handle)))
      (rf/reg-event :gen-test/uses-host-handle
        {:rf.cofx/requires [:gen-test/host-handle]}
        (fn [_ _] (reset! fired? true) {}))
      (let [ex (try (rf/dispatch-sync [:gen-test/uses-host-handle]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (rf/unregister-listener! :trace ::gen-non-edn)
        (is (= 1 @gen-calls) "the generator ran (the value is checked AFTER it mints)")
        (is (false? @fired?)
            "the handler never ran — a non-EDN generated value halts the cascade")
        (is (some? ex) "the dispatch threw rather than folding the host handle")
        (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex)))
            "the throw carries :rf.error/cofx-value-invalid")
        (is (= :non-edn-recordable-value (:rf.cofx/value-error (ex-data ex)))
            "the structural sub-kind rides :rf.cofx/value-error, NOT a :schema miss (:reason is the human sentence)")
        (is (= :gen-test/host-handle (:rf.cofx/id (ex-data ex)))
            ":rf.cofx/id names the offending generated fact")
        (is (= [:gen-test/host-handle] (:path (ex-data ex)))
            "the reported path is rooted at the fact id (the bad leaf is the value itself)")
        (is (some? (:bad-type (ex-data ex)))
            "the host :bad-type is surfaced (a printable type name, never the raw object)")
        (let [errs (filter #(= :rf.error/cofx-value-invalid (:operation %)) @traces)]
          (is (= 1 (count errs)) "exactly one cofx-value-invalid error trace")
          (is (= :non-edn-recordable-value (get-in (first errs) [:tags :reason]))
              "the trace carries the structural-EDN reason")
          (is (= :gen-test/host-handle (get-in (first errs) [:tags :rf.cofx/id]))
              "the trace names the fact"))))))

(deftest generated-non-edn-value-is-rejected-in-production
  (testing "ADVERSARIAL (rf2-q34j26 — EP-0017 Open Issue 9): the structural-EDN
            check of a GENERATED recordable value is ALWAYS-ON, so a generator
            minting a host handle is rejected even with the dev gate OFF — a
            non-EDN value folded into the durable record is corrupt durable
            state in production as much as dev, never silently written back."
    (let [gen-calls (atom 0)
          fired?    (atom false)]
      (rf/reg-cofx :gen-test/prod-host-handle
        {:recordable? true :doc "A generator that wrongly mints a host handle."}
        (fn [] (swap! gen-calls inc) (atom :a-host-handle)))
      (rf/reg-event :gen-test/prod-uses-host-handle
        {:rf.cofx/requires [:gen-test/prod-host-handle]}
        (fn [_ _] (reset! fired? true) {}))
      (with-redefs [interop/debug-enabled? false]
        (let [ex (try (rf/dispatch-sync [:gen-test/prod-uses-host-handle]) nil
                      (catch #?(:clj clojure.lang.ExceptionInfo
                                :cljs cljs.core/ExceptionInfo) e e))]
          (is (some? ex)
              "a non-EDN generated value is rejected with the dev gate OFF (production hard error)")
          (is (false? @fired?)
              "the handler never ran — the structural floor halts the cascade in prod too")
          (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex))))
          (is (= :non-edn-recordable-value (:rf.cofx/value-error (ex-data ex)))))))))

(deftest generated-non-edn-value-nested-reports-path
  (testing "ADVERSARIAL (rf2-uqz2ir): a generator minting a map with a non-EDN
            leaf NESTED inside otherwise-good EDN reports the PATH to the bad
            leaf (rooted at the fact id), and the `:bad-type` only — never the
            raw host object. A `:preview` may accompany only a value that is
            itself recordable; the raw handle is never surfaced."
    (let [fired? (atom false)]
      (rf/reg-cofx :gen-test/nested-bad
        {:recordable? true}
        ;; `:ok` and `:n` are clean EDN; `:handle` nests a function (host handle).
        (fn [] {:ok "fine" :n 3 :handle (fn [] :nope)}))
      (rf/reg-event :gen-test/uses-nested-bad
        {:rf.cofx/requires [:gen-test/nested-bad]}
        (fn [_ _] (reset! fired? true) {}))
      (let [ex (try (rf/dispatch-sync [:gen-test/uses-nested-bad]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (is (false? @fired?) "the handler never ran")
        (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex))))
        (is (= :non-edn-recordable-value (:rf.cofx/value-error (ex-data ex))))
        (is (= [:gen-test/nested-bad :handle] (:path (ex-data ex)))
            "the path is rooted at the fact id and descends to the bad nested leaf")))))

(deftest generated-edn-value-passes-structural-check
  (testing "an EDN-clean generated value (a plain map of strings / ints — the
            shape the REAL routing / resources allocation generators mint, e.g.
            `{:token \"nav-N\" :counter N}`) passes the structural-EDN gate and
            is delivered + written back normally. The complement of the
            adversarial case — the gate must not reject ordinary data."
    (let [seen-delta (atom nil)
          seen-cofx  (atom nil)]
      ;; Mirror the real allocation-cofx value shape: a plain EDN map.
      (rf/reg-cofx :gen-test/allocation-shaped
        {:recordable? true :doc "EDN-clean, like the real allocation cofx."}
        (fn [] {:token "nav-7" :counter 7}))
      (rf/reg-event :gen-test/uses-allocation
        {:rf.cofx/requires [:gen-test/allocation-shaped]}
        (fn [{:keys [gen-test/allocation-shaped] :as cofx} _]
          (reset! seen-delta allocation-shaped)
          (reset! seen-cofx (:rf.cofx cofx))
          {}))
      (rf/dispatch-sync [:gen-test/uses-allocation])
      (is (= {:token "nav-7" :counter 7} @seen-delta)
          "the EDN-clean generated value is delivered flat unchanged")
      (is (= {:token "nav-7" :counter 7} (:gen-test/allocation-shaped @seen-cofx))
          "and written back into the causal :rf.cofx record — the gate passed it through"))))

(deftest supplied-value-schema-mismatch-is-hard-error
  (testing "ADVERSARIAL: a SUPPLIED / replayed recordable value that fails the
            registration's `:schema` is also `:rf.error/cofx-value-invalid` —
            the `:schema` is the type of the replay hole, validated before the
            value folds into durable state (EP-0017 §5)"
    (with-passthrough-validator
      (fn [schema value]
        (if (= schema :gen-test/positive) (and (integer? value) (pos? value)) true))
      (fn [schema value] {:schema schema :value value :failed true})
      (fn []
        (let [fired? (atom false)]
          (rf/reg-cofx :gen-test/checked
            {:recordable? true :schema :gen-test/positive}
            (fn [] 5))
          (rf/reg-event :gen-test/uses-checked
            {:rf.cofx/requires [:gen-test/checked]}
            (fn [_ _] (reset! fired? true) {}))
          ;; Supply an out-of-contract value (-9) on the token.
          (let [ex (try (rf/dispatch-sync [:gen-test/uses-checked]
                                          {:rf.cofx {:gen-test/checked -9}}) nil
                        (catch #?(:clj clojure.lang.ExceptionInfo
                                  :cljs cljs.core/ExceptionInfo) e e))]
            (is (false? @fired?) "the handler never ran")
            (is (= :rf.error/cofx-value-invalid (:rf.error/id (ex-data ex)))
                "a supplied value failing :schema is also cofx-value-invalid")))))))

(deftest valid-generated-value-passes-schema
  (testing "a generated value that CONFORMS to `:schema` is delivered normally
            (the validator's pass branch does not block the cascade)"
    (with-passthrough-validator
      (fn [schema value]
        (if (= schema :gen-test/positive) (and (integer? value) (pos? value)) true))
      (fn [_ _] nil)
      (fn []
        (let [seen (atom nil)]
          (rf/reg-cofx :gen-test/good
            {:recordable? true :schema :gen-test/positive}
            (fn [] 11))
          (rf/reg-event :gen-test/uses-good
            {:rf.cofx/requires [:gen-test/good]}
            (fn [{:keys [gen-test/good]} _] (reset! seen good) {}))
          (rf/dispatch-sync [:gen-test/uses-good])
          (is (= 11 @seen) "a conforming generated value is delivered flat"))))))

(deftest strict-policy-does-not-generate
  (testing "ADVERSARIAL: under the `:strict` mint policy a declared-absent
            generator-backed fact is `:rf.error/missing-required-cofx` — the
            generator does NOT run, no host read happens (replay is
            unconditionally strict; EP-0017 §6). Exercised at the
            `deliver-declared-cofx` seam since slice-B.8 wires the policy
            binding points."
    (let [gen-calls (atom 0)]
      (rf/reg-cofx :gen-test/strict-delta
        {:recordable? true}
        (fn [] (swap! gen-calls inc) 1))
      (let [requires (cofx/parse-requires :gen-test/strict-evt [:gen-test/strict-delta])
            ex (try (cofx/deliver-declared-cofx
                      {:db {}} requires {} :gen-test/strict-evt :rf/default :strict)
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (is (zero? @gen-calls) ":strict ran no generator — no host read")
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
            "a strict-mode absent generator-backed fact is missing-required"))
      (testing "the same fact under `:live` DOES generate (policy is the switch)"
        (reset! gen-calls 0)
        (let [requires (cofx/parse-requires :gen-test/strict-evt [:gen-test/strict-delta])
              {:keys [coeffects]} (cofx/deliver-declared-cofx
                                    {:db {}} requires {} :gen-test/strict-evt :rf/default :live)]
          (is (= 1 @gen-calls) ":live generated the absent fact")
          (is (= 1 (:gen-test/strict-delta coeffects))
              "the generated value is delivered under :live"))))))

;; ===========================================================================
;; 9. Mint-policy BINDING POINTS (EP-0017 §6 / slice-B.8, rf2-5spzo7)
;;
;; B.7 wired the `mint-policy` arg on `deliver-declared-cofx`; B.8 wires the
;; binding points that SELECT it. The policy resolves most-specific-wins —
;; per-call dispatch opt ▸ frame config (the `:test` preset's `:strict`) ▸
;; the router's `:live` default — and gates ONLY the declared-absent
;; generator-backed branch. The four tests below exercise each binding point
;; through the FULL dispatch path (not the seam directly), so the wiring in
;; `build-envelope` / `assemble-initial-ctx` / the `:test` preset is covered.
;; ===========================================================================

(deftest resolve-mint-policy-precedence
  (testing "ADVERSARIAL UNIT: `resolve-mint-policy` is most-specific-wins —
            per-call opt beats frame config beats the `:live` default
            (EP-0017 §6 binding points)"
    ;; 1. Neither present → the router's :live default.
    (is (= :live (cofx/resolve-mint-policy nil nil))
        "no binding point → :live (the router default)")
    ;; 2. Frame config only (the :test preset's :strict).
    (is (= :strict (cofx/resolve-mint-policy nil :strict))
        "frame config wins when no per-call opt is supplied")
    ;; 3. Per-call opt only.
    (is (= :strict (cofx/resolve-mint-policy :strict nil))
        "the per-call opt selects the policy when the frame has none")
    ;; 4. Per-call opt OVERRIDES the frame config (the :explicit-live escape
    ;;    over a :test frame's :strict).
    (is (= :explicit-live (cofx/resolve-mint-policy :explicit-live :strict))
        "the per-call opt is the most-specific binding point — it wins")
    (is (= cofx/default-mint-policy (cofx/resolve-mint-policy nil nil))
        "the documented default is :live")))

(deftest router-default-is-live-generates
  (testing "ADVERSARIAL (binding point 1 — ROUTER DEFAULT): a dispatch into a
            frame with NO mint-policy config and NO per-call opt generates a
            declared-absent generator-backed recordable fact — the router's
            `:live` default (EP-0017 §6). Exercised through the full dispatch
            path into the fixture's ambient `:rf/default` frame."
    (let [gen-calls  (atom 0)
          seen-delta (atom nil)]
      (rf/reg-cofx :mint-test/live-delta
        {:recordable? true :doc "Generator-backed, exercised under :live."}
        (fn [] (swap! gen-calls inc) 7))
      (rf/reg-event :mint-test/live-evt
        {:rf.cofx/requires [:mint-test/live-delta]}
        (fn [{:keys [mint-test/live-delta]} _] (reset! seen-delta live-delta) {}))
      ;; The fixture's ambient frame is :rf/default (no preset → no mint-policy
      ;; config); no per-call opt → the :live default applies.
      (rf/dispatch-sync [:mint-test/live-evt])
      (is (= 1 @gen-calls) "the router default is :live — the generator ran")
      (is (= 7 @seen-delta) "the generated value was delivered flat"))))

(deftest test-preset-default-is-strict-does-not-generate
  (testing "ADVERSARIAL (binding point 3 — :test PRESET DEFAULT): a dispatch
            into a `:preset :test` frame does NOT generate a declared-absent
            generator-backed fact — the `:test` preset defaults the mint policy
            to `:strict`, so the fact is `:rf.error/missing-required-cofx`
            (EP-0017 §6). No host read, no fresh per-run value."
    (let [gen-calls (atom 0)
          fired?    (atom false)]
      (rf/reg-frame :mint-test/strict-frame {:preset :test})
      (rf/reg-cofx :mint-test/strict-delta
        {:recordable? true}
        (fn [] (swap! gen-calls inc) 3))
      (rf/reg-event :mint-test/strict-evt
        {:rf.cofx/requires [:mint-test/strict-delta]}
        (fn [_ _] (reset! fired? true) {}))
      (let [ex (try (rf/dispatch-sync [:mint-test/strict-evt]
                                      {:frame :mint-test/strict-frame})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (is (zero? @gen-calls)
            "the :test preset is strict — NO generator ran, no host read")
        (is (false? @fired?) "the handler never ran (the cascade halted)")
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
            "a strict-frame declared-absent generator fact is missing-required")
        (is (= :mint-test/strict-delta (:rf.cofx/id (ex-data ex)))
            "the error names the absent fact")))))

(deftest replay-per-call-strict-does-not-generate
  (testing "ADVERSARIAL (binding point 2 — TOOL-PAIR REPLAY): the per-call
            `:rf.cofx/mint-policy :strict` dispatch opt (how a replay
            re-dispatches a recorded event) does NOT generate even on a frame
            whose config would otherwise be `:live` — replay is unconditionally
            strict, so an incomplete record fails loudly rather than minting a
            fresh value (EP-0017 §6 / Tool-Pair §Replay)."
    (let [gen-calls (atom 0)
          fired?    (atom false)]
      (rf/reg-cofx :mint-test/replay-delta
        {:recordable? true}
        (fn [] (swap! gen-calls inc) 5))
      (rf/reg-event :mint-test/replay-evt
        {:rf.cofx/requires [:mint-test/replay-delta]}
        (fn [_ _] (reset! fired? true) {}))
      ;; The ambient :rf/default frame is :live; the per-call :strict opt — the
      ;; replay lever — wins over it. A record MISSING :mint-test/replay-delta
      ;; therefore fails loudly rather than minting a divergent value.
      (let [ex (try (rf/dispatch-sync [:mint-test/replay-evt]
                                      {:rf.cofx/mint-policy :strict
                                       ;; the recorded token (missing the fact)
                                       :rf.cofx              {:rf/time-ms 1781078400123}})
                    nil
                    (catch #?(:clj clojure.lang.ExceptionInfo
                              :cljs cljs.core/ExceptionInfo) e e))]
        (is (zero? @gen-calls)
            "per-call :strict ran NO generator — replay never re-mints")
        (is (false? @fired?) "the handler never ran (the incomplete record halts)")
        (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data ex)))
            "an incomplete replay record is missing-required, not a fresh mint"))
      (testing "the SAME event with the fact PRESENT on the record replays it
                verbatim under :strict (supplied wins; replay re-presents)"
        (reset! gen-calls 0)
        (reset! fired? false)
        (let [seen (atom nil)]
          (rf/reg-event :mint-test/replay-evt
            {:rf.cofx/requires [:mint-test/replay-delta]}
            (fn [{:keys [mint-test/replay-delta]} _]
              (reset! fired? true) (reset! seen replay-delta) {}))
          (rf/dispatch-sync [:mint-test/replay-evt]
                            {:rf.cofx/mint-policy :strict
                             :rf.cofx              {:rf/time-ms        1781078400123
                                                    :mint-test/replay-delta 42}})
          (is (zero? @gen-calls) "a present fact needs no generation, even were it :live")
          (is (true? @fired?) "the handler ran — the record was complete")
          (is (= 42 @seen) "the recorded value is re-presented verbatim"))))))

(deftest explicit-live-overrides-strict-frame-generates
  (testing "ADVERSARIAL (binding point 4 — :explicit-live ESCAPE): the per-call
            `:rf.cofx/mint-policy :explicit-live` opt OVERRIDES a `:test`
            frame's `:strict` default and DOES generate — the test has declared
            it accepts nondeterminism, and the per-call opt is the most-specific
            binding point (EP-0017 §6)."
    (let [gen-calls  (atom 0)
          seen-delta (atom nil)]
      (rf/reg-frame :mint-test/escape-frame {:preset :test})
      (rf/reg-cofx :mint-test/escape-delta
        {:recordable? true}
        (fn [] (swap! gen-calls inc) 9))
      (rf/reg-event :mint-test/escape-evt
        {:rf.cofx/requires [:mint-test/escape-delta]}
        (fn [{:keys [mint-test/escape-delta]} _] (reset! seen-delta escape-delta) {}))
      ;; Without the opt this :test frame would be :strict → missing-required
      ;; (the test above pins that). With :explicit-live the per-call opt wins.
      (rf/dispatch-sync [:mint-test/escape-evt]
                        {:frame               :mint-test/escape-frame
                         :rf.cofx/mint-policy :explicit-live})
      (is (= 1 @gen-calls)
          ":explicit-live overrode the :test frame's :strict — the generator ran")
      (is (= 9 @seen-delta)
          "the generated value was delivered flat under the escape policy"))))

;; ===========================================================================
;; 13b. Per-call mint policy is preserved across CASCADE child dispatches
;;      (rf2-aflgcc — EP-0017 §6)
;; ===========================================================================

(deftest per-call-strict-inherited-by-cascade-child
  (testing "ADVERSARIAL (rf2-aflgcc): a parent dispatched with per-call
            `:rf.cofx/mint-policy :strict` (a replay / strict-test lever) that
            emits a child via `:dispatch` — the child requiring a
            generator-backed recordable fact ABSENT from its (fresh) token —
            must FAIL with `:rf.error/missing-required-cofx`, NOT silently fall
            back to the frame-config / `:live` default and re-mint a fresh
            nondeterministic value. The strict mint DISCIPLINE holds across the
            whole cascade; only the `:rf.cofx` TOKEN is per-causal-run. EP-0017
            §6 / Tool-Pair §Replay."
    (let [child-gen-calls (atom 0)
          child-fired?    (atom false)
          child-error     (atom ::unset)]
      ;; The ambient :rf/default frame is :live; only the per-call :strict on
      ;; the PARENT carries the discipline forward to the child.
      (rf/reg-cofx :cascade-test/child-delta
        {:recordable? true}
        (fn [] (swap! child-gen-calls inc) 7))
      ;; Child: requires a generator-backed fact with NOTHING supplied on its
      ;; (freshly-stamped) token → under :live it would generate, under the
      ;; inherited :strict it must be missing-required.
      (rf/reg-event :cascade-test/child-evt
        {:rf.cofx/requires [:cascade-test/child-delta]}
        (fn [_ _] (reset! child-fired? true) {}))
      ;; Parent: emits the child via a :dispatch fx. The child cascade's
      ;; missing-required throw (strict, no generator) propagates out of the
      ;; sync drain — capture it directly off the dispatch-sync call.
      (rf/reg-event :cascade-test/parent-evt
        (fn [_ _] {:fx [[:dispatch [:cascade-test/child-evt]]]}))
      (reset! child-error
        (try (rf/dispatch-sync [:cascade-test/parent-evt]
                               {:rf.cofx/mint-policy :strict})
             nil
             (catch #?(:clj clojure.lang.ExceptionInfo
                       :cljs cljs.core/ExceptionInfo) e e)))
      (is (zero? @child-gen-calls)
          "the inherited :strict ran NO generator for the child — no host read / re-mint")
      (is (false? @child-fired?)
          "the child handler never ran (its incomplete record halted under inherited :strict)")
      (is (some? @child-error)
          "the child failed with :rf.error/missing-required-cofx, not a silent fresh mint")
      (is (= :rf.error/missing-required-cofx (:rf.error/id (ex-data @child-error)))
          "the child cascade halted with missing-required under the inherited :strict")
      (is (= :cascade-test/child-delta (:rf.cofx/id (ex-data @child-error)))
          "the error names the absent generator-backed fact the child required")))

  (testing "FOIL: without the per-call :strict the SAME cascade child generates
            under the ambient :live default (proving the strict result above is
            the inheritance, not an unrelated failure)"
    (let [child-gen-calls (atom 0)
          child-seen      (atom nil)]
      (rf/reg-cofx :cascade-test/live-delta
        {:recordable? true}
        (fn [] (swap! child-gen-calls inc) 11))
      (rf/reg-event :cascade-test/live-child
        {:rf.cofx/requires [:cascade-test/live-delta]}
        (fn [{:keys [cascade-test/live-delta]} _] (reset! child-seen live-delta) {}))
      (rf/reg-event :cascade-test/live-parent
        (fn [_ _] {:fx [[:dispatch [:cascade-test/live-child]]]}))
      ;; No per-call mint policy → the child inherits nothing and runs under the
      ;; ambient :live default, so the generator fills the absent fact.
      (rf/dispatch-sync [:cascade-test/live-parent])
      (is (= 1 @child-gen-calls)
          "under :live the cascade child's generator ran (the foil to the strict case)")
      (is (= 11 @child-seen)
          "the generated value was delivered flat to the child handler"))))
