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
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

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
      (rf/reg-event-fx :cofx-test/declares-nested-leaf
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
      (rf/reg-event-fx :cofx-test/declares-group-owner
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
      (rf/reg-event-fx :cofx-test/request
        (fn [_ _]
          {:fx [[:dispatch [:cofx-test/replied {:status :ok :value {:title "Welcome"}}]]]}))
      (rf/reg-event-db :cofx-test/replied (fn [db _] db))
      (rf/dispatch-sync [:cofx-test/request]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (rf/unregister-listener! ::reply-cofx)
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
      (rf/reg-event-fx :cofx-test/needs-boundary
        {:rf.cofx/requires [:cofx-test/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      ;; Dispatch WITHOUT supplying the provided fact on the token.
      (let [ex (try (rf/dispatch-sync [:cofx-test/needs-boundary]) nil
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
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
                    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
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
               (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
      (is (some? ex) "reg-event-db with :rf.cofx/requires threw at registration")
      (is (= :rf.error/cofx-request-invalid (:rf.error/id (ex-data ex)))))))

(deftest malformed-requires-is-cofx-request-invalid
  (testing "a non-vector / non-id `:rf.cofx/requires` is
            `:rf.error/cofx-request-invalid` at registration"
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event-fx :cofx-test/bad-requires-1
                      {:rf.cofx/requires :not-a-vector}
                      (fn [_ _] {}))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))
    (is (= :rf.error/cofx-request-invalid
           (-> (try (rf/reg-event-fx :cofx-test/bad-requires-2
                      {:rf.cofx/requires [42]}
                      (fn [_ _] {}))
                    nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))
               ex-data :rf.error/id)))))

(deftest duplicate-requires-is-name-collision
  (testing "declaring the same id twice in one consumer scope is
            `:rf.error/cofx-name-collision` (EP-0017 §4)"
    (is (= :rf.error/cofx-name-collision
           (-> (try (rf/reg-event-fx :cofx-test/dup-requires
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
            host throw at delivery (rf2-cu8wet · Spec-Schemas §`:rf/cofx-meta`)"
    (let [ex (try (rf/reg-cofx :cofx-test/bad-grade {:provided? true})
                  nil (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
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
                  (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
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
    (rf/reg-event-fx :cofx-test/wi-renamed (fn [_ _] {}))
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
            (is (= :cofx-test/browser-locale (get-in (first skips) [:tags :rf.cofx/id])))))
        (finally
          (rf/init-platform host-default))))))

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
                      (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e e))]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))))
      (is (false? @fired?) "the handler never ran"))))
