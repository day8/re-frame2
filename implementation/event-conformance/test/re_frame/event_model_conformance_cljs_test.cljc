(ns re-frame.event-model-conformance-cljs-test
  "EP-0018 ONE-FORM event-MODEL conformance (rf2-xhfxcs.6; EP-0018
  §Conformance + Bead Plan item 7).

  The umbrella REGRESSION LOCK on the public contract the EP-0018 flip
  (Slice Z) settled: `reg-event` is the SINGLE public event-registration
  form (reg-event-fx semantics — coeffects in, a closed effects map out,
  `{:db …}` is the db-write effect, `:rf.cofx/requires` works uniformly);
  the three retired names survive ONLY as facade-exported `^:no-doc`
  throwing stubs raising their exact hard errors; ONE `:rf/event-handler`
  wrapper (`:rf/default? true`); `:event/kind` gone; `reg-event-ctx` is
  not a public facade var; realm-routing preserved (a15n62).

  ## Why a cross-artefact tier and not just the in-core unit suite

  `re-frame.reg-event-cljs-test` (core) carries the NARROW per-feature
  unit checks. This tier is the ADVERSARIAL UMBRELLA: every assertion is
  shaped so a future regression would go RED —

    - a RE-INTRODUCED working `reg-event-db` / `reg-event-fx` /
      `reg-event-ctx` (the stub assertions FAIL CLOSED: a call that does
      NOT throw the removed-error is detected, and the same id is proven
      absent from the registry afterwards);
    - a SECOND framework wrapper id, or a renamed wrapper (the wrapper is
      pinned to exactly `[:rf/event-handler]` with `:rf/default? true`);
    - a RE-APPEARING `:event/kind` sub-tag (pinned absent);
    - a `reg-event-ctx` PROMOTION back onto the public facade (the JVM
      `:no-doc`-meta probe FAILS if `reg-event-ctx` ever loses its
      `^:no-doc`, and `reg-event` FAILS if it ever GAINS one);
    - a removed-name error that THROWS but no longer fans out on the
      always-on error channel (production observability lock).

  The contract spans the events runtime, the public facade, the
  always-on error-emit channel, and the realm registrar — wider than any
  single artefact's test tree — so it lives in its own cross-artefact
  `event-conformance/` surface (the precedent is `reply-conformance/` +
  `derivation-conformance/`).

  `.cljc`, dual-runtime: the shadow-cljs `:node-test` build
  (`npm run test:cljs`, ns matches `cljs-test$`) AND the JVM
  `clojure -M:test` runner both pick it up. The harness mirrors
  `re-frame.reg-event-cljs-test` — the shared
  `test-support/make-reset-runtime-fixture` wraps every body in
  `(with-frame :rf/default …)` so ambient `dispatch-sync` resolves; an
  outer fixture clears the always-on error-listener registry (a `defonce`
  that survives re-runs) + drops constructed realms.

  Canonical contract: EP-0018 §1/§2/§3/§4/§5/§6/§7 + spec/002-Frames.md
  §Event handlers + spec/001-Registration.md §The retired
  event-registration names + spec/009-Instrumentation.md (the
  `:rf/event-handler` wrapper)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.cofx :as cofx]
            [re-frame.error-emit :as error-emit]
            [re-frame.realm :as realm]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; Harness. The runtime-reset fixture snapshot/restores the registrar and
;; wraps each body in `(with-frame :rf/default …)`. The second fixture clears
;; the always-on error-listener registry (a `defonce` atom that survives test
;; re-runs — rf2-bacs4) before AND after each test so a listener never leaks
;; between cases, and drops any constructed realm so the realm-routing case
;; leaves no residue.
;; ---------------------------------------------------------------------------

(defn- drop-non-default-realms! []
  (swap! realm/realms select-keys [realm/default-realm-id])
  (swap! realm/realms update realm/default-realm-id dissoc :app :capabilities))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [test-fn]
    (error-emit/clear-error-listeners!)
    (drop-non-default-realms!)
    (test-fn)
    (error-emit/clear-error-listeners!)
    (drop-non-default-realms!)))

;; ---------------------------------------------------------------------------
;; Shared helpers.
;; ---------------------------------------------------------------------------

(defn- thrown-error-id
  "Call `f` and return the `:rf.error/id` of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw. The retired-name stubs raise an ex-info
  carrying `:rf.error/id`; an old form that was silently RE-INTRODUCED would
  not throw, so the `:no-throw` sentinel makes the stub assertions FAIL CLOSED
  (a passing-by-not-throwing regression is detected, not skipped)."
  [f]
  (try
    (f)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (:rf.error/id (ex-data e)))))

(defn- thrown-error-reason
  "Call `f` and return the `:reason` text of the ExceptionInfo it raises, or
  `:no-throw` if it did not throw. The retired-name stubs carry the actionable
  replacement guidance in `:reason`; the `:no-throw` sentinel makes the
  replacement-guidance assertions FAIL CLOSED if the form ever stops throwing."
  [f]
  (try
    (f)
    :no-throw
    (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
      (:reason (ex-data e)))))

(defn- chain-ids
  "Map a STORED (unresolved) `:interceptors` chain to a vector of authored ids.
  Per EP-0022 reference-only (rf2-0adhqs.9) the stored chain holds REFS
  unresolved: a bare-keyword ref IS its id; an `[id arg]` factory ref's head
  is the id; the framework handler-wrapper (an inline value map) yields its
  `:id`."
  [chain]
  (mapv (fn [entry]
          (cond
            (keyword? entry)                          entry
            (and (vector? entry) (keyword? (first entry))) (first entry)
            (map? entry)                              (:id entry)
            :else                                     entry))
        chain))

;; ===========================================================================
;; (1) `reg-event` — the ONE form, reg-event-fx semantics
;;     (coeffects in / closed effects map out; `{:db …}` is the db-write).
;; ===========================================================================

(deftest reg-event-has-reg-event-fx-semantics-coeffects-in-effects-out
  (testing "EP-0018 §1/§4: a reg-event handler is BYTE-FOR-BYTE a reg-event-fx
            handler — it receives the coeffects map (NOT a bare db) and returns
            the CLOSED effects map; `{:db …}` is the db-write effect like any
            other (the db-only return shape is GONE)"
    (let [seen-cofx (atom ::unset)]
      (rf/reg-sub :evt-conf/count (fn [db _] (:count db 0)))
      (rf/reg-event :evt-conf/bump
        (fn [coeffects _]
          ;; The handler is handed the COEFFECTS MAP — the reg-event-fx shape.
          (reset! seen-cofx coeffects)
          {:db (update (:db coeffects) :count (fnil inc 0))}))
      (rf/dispatch-sync [:evt-conf/bump])
      (rf/dispatch-sync [:evt-conf/bump])
      (is (map? @seen-cofx)
          "the handler is handed the coeffects MAP, not a bare db value")
      (is (contains? @seen-cofx :db)
          "`:db` is delivered IN the coeffects map (coeffects-in)")
      (is (= 2 @(rf/subscribe [:evt-conf/count]))
          "the `{:db …}` effect committed cumulatively (the db-write IS an effect)"))))

(deftest reg-event-handler-receives-the-canonical-coeffect-keys
  (testing "EP-0018 §4 + spec/002 §Event handlers: the coeffects map a handler
            receives carries `:db`, `:event`, `:rf.frame/id`, `:rf.db/runtime`,
            and `:rf.cofx` — the framework-supplied baseline (NO `:event/kind`)"
    (let [cofx (atom ::unset)]
      (rf/reg-event :evt-conf/inspect-cofx
        (fn [coeffects _] (reset! cofx coeffects) {}))
      (rf/dispatch-sync [:evt-conf/inspect-cofx :payload])
      (let [c @cofx]
        (is (contains? c :db)           "`:db` present in the coeffects map")
        (is (contains? c :event)        "`:event` present in the coeffects map")
        (is (= [:evt-conf/inspect-cofx :payload] (:event c))
            "`:event` is the dispatched event vector (== the 2nd handler arg)")
        (is (contains? c :rf.frame/id)  "`:rf.frame/id` present in the coeffects map")
        (is (= :rf/default (:rf.frame/id c))
            "the ambient frame id is delivered as `:rf.frame/id`")
        (is (contains? c :rf.db/runtime) "`:rf.db/runtime` present in the coeffects map")
        (is (not (contains? c :event/kind))
            "the `:event/kind` sub-tag is GONE from the coeffects map (one form)")))))

(deftest reg-event-second-arg-is-the-event-vector
  (testing "EP-0018 §1 (D4): the handler is TWO-ARG `(fn [coeffects event-vec])`
            — the 2nd arg is the event vector and `(:event coeffects)` is the
            same value"
    (let [arg-event   (atom ::unset)
          cofx-event  (atom ::unset)]
      (rf/reg-event :evt-conf/two-arg
        (fn [coeffects event]
          (reset! arg-event event)
          (reset! cofx-event (:event coeffects))
          {}))
      (rf/dispatch-sync [:evt-conf/two-arg :a :b])
      (is (= [:evt-conf/two-arg :a :b] @arg-event)
          "the 2nd positional arg is the full event vector")
      (is (= @arg-event @cofx-event)
          "`(:event coeffects)` is the SAME value as the positional event arg"))))

(deftest reg-event-rf-cofx-requires-delivers-the-fact-flat
  (testing "EP-0018 §4 + EP-0017 §2/§5: `:rf.cofx/requires` works on reg-event
            (the EP-0017 hole the collapse closed — every event can declare
            coeffects, with NO db-handler exception), and the declared fact
            arrives FLAT under its id in the coeffects map"
    (let [seen (atom ::unset)]
      (rf/reg-cofx :evt-conf/locale (fn [] "en-AU"))
      (rf/reg-event :evt-conf/read-locale
        {:rf.cofx/requires [:evt-conf/locale]}
        (fn [{:keys [evt-conf/locale]} _] (reset! seen locale) {}))
      (rf/dispatch-sync [:evt-conf/read-locale])
      (is (= "en-AU" @seen)
          "the declared coeffect arrived FLAT under its id (no nesting)"))))

(deftest reg-event-rf-cofx-requires-generator-arg-path
  (testing "EP-0017 generator path on reg-event: a `[:cofx-id arg]` declaration
            passes the literal arg to the cofx generator, delivered flat"
    (let [seen (atom ::unset)]
      ;; The call-site-parameterized supplier is one-arg `(fn [arg] value)`
      ;; (cofx.cljc §Supplier signatures), declared `[id arg]` in :rf.cofx/requires.
      (rf/reg-cofx :evt-conf/echo (fn [arg] arg))
      (rf/reg-event :evt-conf/read-echo
        {:rf.cofx/requires [[:evt-conf/echo :hello]]}
        (fn [{:keys [evt-conf/echo]} _] (reset! seen echo) {}))
      (rf/dispatch-sync [:evt-conf/read-echo])
      (is (= :hello @seen)
          "the generator arg was threaded and the result delivered flat"))))

(deftest reg-event-typo-cofx-is-the-hard-error
  (testing "EP-0017 typo path on reg-event: declaring `:rf.cofx/requires` for an
            UNregistered cofx is the hard error `:rf.error/unregistered-cofx`
            (NO db-handler exception — the same path every event form takes)"
    (rf/reg-event :evt-conf/bad-requires
      {:rf.cofx/requires [:evt-conf/never-registered]}
      (fn [_ _] {}))
    (is (= :rf.error/unregistered-cofx
           (thrown-error-id #(rf/dispatch-sync [:evt-conf/bad-requires])))
        "an unregistered declared cofx raises :rf.error/unregistered-cofx")))

(deftest reg-event-undeclared-cofx-is-not-staged
  (testing "EP-0017 declared-only delivery on reg-event: an UNdeclared recordable
            leaf is NOT delivered to the handler (no silent ambient coupling)"
    (let [had-time? (atom ::unset)]
      (rf/reg-event :evt-conf/declares-nothing
        (fn [cofx _] (reset! had-time? (contains? cofx :rf/time-ms)) {}))
      (rf/dispatch-sync [:evt-conf/declares-nothing]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (false? @had-time?)
          "an undeclared recordable leaf is never staged into the coeffects map"))))

(deftest reg-event-declared-rf-time-ms-is-delivered-flat-from-the-token
  (testing "EP-0017 §2/§5 recordable-delivery on reg-event (rf2-q5w74i): a
            handler that DECLARES `:rf.cofx/requires [:rf/time-ms]` receives the
            recordable `:rf/time-ms` fact FLAT from the supplied/stamped
            `:rf.cofx` causal token — the framework's one provided recordable
            coeffect. This is the POSITIVE counterpart to
            `reg-event-undeclared-cofx-is-not-staged` (declared-only delivery):
            declared → delivered flat; undeclared → never staged"
    (let [seen (atom ::unset)]
      (rf/reg-event :evt-conf/reads-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms]} _] (reset! seen time-ms) {}))
      (rf/dispatch-sync [:evt-conf/reads-time]
                        {:rf.cofx {:rf/time-ms 1781078400123}})
      (is (= 1781078400123 @seen)
          "the DECLARED :rf/time-ms arrived FLAT under its id from the causal token"))))

(deftest reg-event-registered-but-absent-provided-fact-is-missing-required-cofx
  (testing "EP-0017 §5/§7 provided-recordable on reg-event (rf2-q5w74i): a
            DECLARED PROVIDED recordable fact (registered `{:recordable? true
            :provided? true}` — NO generator) that is ABSENT from the supplied
            causal token is the hard error `:rf.error/missing-required-cofx` —
            the cascade halts before the handler runs (it fails loudly rather
            than silently re-reading the host). This is DISTINCT from the typo
            case (`:rf.error/unregistered-cofx`, already covered): the id IS
            registered, it is just not supplied. The error rides the always-on
            error channel; observe it via the throw + a trace listener"
    (let [traces (atom [])
          fired? (atom false)]
      (rf/reg-cofx :evt-conf/required-boundary
        {:recordable? true :provided? true})
      (rf/reg-event :evt-conf/needs-boundary
        {:rf.cofx/requires [:evt-conf/required-boundary]}
        (fn [_ _] (reset! fired? true) {}))
      (rf/register-listener! :evt-conf/missing-recorder (fn [ev] (swap! traces conj ev)))
      ;; Dispatch WITHOUT supplying the provided fact on the token.
      (let [thrown (thrown-error-id #(rf/dispatch-sync [:evt-conf/needs-boundary]))]
        (rf/unregister-listener! :evt-conf/missing-recorder)
        (is (false? @fired?)
            "the handler never ran — missing-required halts the cascade before the handler")
        (is (= :rf.error/missing-required-cofx thrown)
            "a registered-but-absent provided fact raises :rf.error/missing-required-cofx")
        (let [errs (filter #(= :rf.error/missing-required-cofx (:operation %)) @traces)]
          (is (seq errs)
              "the missing-required error fanned out on the trace bus")
          (is (= :evt-conf/required-boundary (get-in (first errs) [:tags :rf.cofx/id]))
              ":rf.cofx/id names the absent provided fact"))))))

(deftest reg-event-framework-provided-rf-time-ms-declared-delivered-undeclared-absent
  (testing "EP-0017 §1/§2/§4/§5 (rf2-dkkmig) — the framework-STAMPED `:rf/time-ms`
            provided-recordable matrix, the no-implicit-time rule. `:rf/time-ms`
            is the framework's ONE built-in `{:recordable? true :provided? true}`
            registration, stamped onto every dispatch envelope at enqueue (so it
            is always present on the token without the caller supplying it). The
            two legs prove the provided-vs-ambient recordable contract for the
            STAMPED fact: (1) a handler DECLARING `:rf/time-ms` receives the
            framework-stamped value flat (the stamp guarantees the provided fact
            is present — no `:rf.error/missing-required-cofx`, distinct from the
            generator-less custom provided fact that IS absent); (2) a handler
            NOT declaring it never sees it in its coeffects map (no implicit
            time — the most-consumed fact gets no special treatment).
            A regression re-introducing implicit time delivery (staging
            `:rf/time-ms` for an undeclared handler) turns leg 2 RED; a
            regression that stopped stamping the provided fact turns leg 1 RED
            (it would surface as missing-required)"
    (let [declared-time   (atom ::unset)
          undeclared-has? (atom ::unset)]
      ;; Leg 1 — DECLARED: the framework-stamped provided `:rf/time-ms` is
      ;; delivered flat from the enqueue stamp, NO caller supply needed.
      (rf/reg-event :evt-conf/declares-time
        {:rf.cofx/requires [:rf/time-ms]}
        (fn [{:keys [rf/time-ms]} _] (reset! declared-time time-ms) {}))
      ;; Leg 2 — UNDECLARED: the same stamped fact is NOT staged.
      (rf/reg-event :evt-conf/ignores-time
        (fn [cofx _] (reset! undeclared-has? (contains? cofx :rf/time-ms)) {}))
      ;; Dispatch WITHOUT supplying `:rf/time-ms` — the enqueue stamp provides it.
      (rf/dispatch-sync [:evt-conf/declares-time])
      (rf/dispatch-sync [:evt-conf/ignores-time])
      (is (number? @declared-time)
          "the DECLARED framework-stamped :rf/time-ms arrived flat from the enqueue stamp (a provided fact, always present — never missing-required)")
      (is (false? @undeclared-has?)
          "the UNDECLARED handler never sees :rf/time-ms — no implicit time (declared-only delivery, the most-consumed fact gets no exemption)"))))

(deftest reg-event-custom-provided-cofx-supplied-on-token-is-delivered-flat
  (testing "EP-0017 §2/§4/§5 (rf2-dkkmig) — a CUSTOM `{:recordable? true
            :provided? true}` cofx (no generator), SUPPLIED on the dispatch
            token, is delivered FLAT under its id when declared. This is the
            POSITIVE delivery leg complementing
            `reg-event-registered-but-absent-provided-fact-is-missing-required-cofx`
            (the negative leg): a registered provided fact PRESENT on the token
            is delivered verbatim (supplied values win — no host read, no
            generator), exactly like the framework's own `:rf/time-ms` but for an
            app-owned boundary fact. Together the two legs lock the provided
            recordable matrix: present → delivered flat; absent →
            missing-required. A regression that failed to deliver a supplied
            provided fact (or that ran a phantom supplier) turns this RED"
    (let [seen (atom ::unset)]
      ;; A custom provided recordable fact — NO supplier (its owner stamps the
      ;; token). The valid provided shape: `{:recordable? true :provided? true}`.
      (rf/reg-cofx :evt-conf/session-token
        {:recordable? true :provided? true
         :doc "A boundary fact the dispatch site stamps onto the token."})
      (rf/reg-event :evt-conf/reads-token
        {:rf.cofx/requires [:evt-conf/session-token]}
        (fn [{:keys [evt-conf/session-token]} _] (reset! seen session-token) {}))
      ;; SUPPLY the provided fact's value on the dispatch token.
      (rf/dispatch-sync [:evt-conf/reads-token]
                        {:rf.cofx {:evt-conf/session-token "jwt-abc-123"}})
      (is (= "jwt-abc-123" @seen)
          "the SUPPLIED custom provided recordable fact arrived FLAT under its id from the token (supplied values win — no generator, no host read)"))))

(deftest inject-cofx-is-off-the-public-facade-and-the-removal-is-a-hard-error
  (testing "EP-0017 slice A.3 + rf2-w9xyx1 (rf2-q5w74i): the v1 ctx→ctx
            `inject-cofx` delivery idiom is REMOVED with no alias —
            `:rf.cofx/requires` is the ONE declaration surface. The umbrella
            lock has TWO legs: (1) `inject-cofx` is OFF the public `rf/`
            facade entirely — there is NO public `re-frame.core/inject-cofx`
            var (a re-introduced working public facade var goes RED); (2) the
            migration alarm survives as the always-on hard-error thrower
            (`re-frame.cofx/inject-cofx`), raising `:rf.error/inject-cofx-
            removed` and naming `:rf.cofx/requires` as the replacement (a
            removal that stopped throwing, or dropped the replacement guidance,
            goes RED)"
    ;; Leg 1 — OFF the public facade. JVM-only var-reflection probe: the
    ;; public facade carries no `inject-cofx` var. (CLJS has no runtime vars;
    ;; the CLJS publics surface is policed by the api-manifest --check gate.)
    #?(:clj
       (is (nil? (ns-resolve 're-frame.core 'inject-cofx))
           "there is NO public re-frame.core/inject-cofx var — the facade surface is removed"))
    ;; Leg 2 — the surviving private thrower is the always-on hard error.
    (is (= :rf.error/inject-cofx-removed
           (thrown-error-id #(cofx/inject-cofx :evt-conf/anything)))
        "the surviving inject-cofx thrower raises :rf.error/inject-cofx-removed")
    (let [reason (thrown-error-reason #(cofx/inject-cofx :evt-conf/anything))]
      (is (string? reason)
          "the removal stub raises an ex-info carrying a :reason string")
      (is (re-find #":rf.cofx/requires" reason)
          "the replacement guidance names `:rf.cofx/requires` (the one declaration surface)"))))

;; ===========================================================================
;; (2) Effect-map shape — closed return contract, nil/{} no-op, foreign-key
;;     and legacy-shortcut rejection.
;; ===========================================================================

(deftest reg-event-nil-and-empty-returns-are-noops
  (testing "EP-0018 §4a: `nil` and `{}` returns are documented no-ops — neither
            disturbs app-db"
    (rf/reg-sub :evt-conf/seed (fn [db _] (:seed db :untouched)))
    (rf/reg-event :evt-conf/seed!     (fn [{:keys [db]} _] {:db (assoc db :seed :set)}))
    (rf/reg-event :evt-conf/nil-ret   (fn [_ _] nil))
    (rf/reg-event :evt-conf/empty-ret (fn [_ _] {}))
    (rf/dispatch-sync [:evt-conf/seed!])
    (rf/dispatch-sync [:evt-conf/nil-ret])
    (rf/dispatch-sync [:evt-conf/empty-ret])
    (is (= :set @(rf/subscribe [:evt-conf/seed]))
        "neither the nil nor the {} return disturbed app-db")))

(deftest reg-event-fx-vector-walks-in-source-order
  (testing "EP-0018 §4: a reg-event handler's `:fx` vector dispatches its entries
            in source order alongside the `:db` write — the closed
            `#{:db :fx :rf.db/runtime}` return"
    (rf/reg-sub :evt-conf/log     (fn [db _] (:log db [])))
    (rf/reg-sub :evt-conf/kicked? (fn [db _] (:kicked? db false)))
    (rf/reg-event :evt-conf/append
      (fn [{:keys [db]} [_ v]] {:db (update db :log (fnil conj []) v)}))
    (rf/reg-event :evt-conf/kickoff
      (fn [{:keys [db]} _]
        {:db (assoc db :kicked? true)
         :fx [[:dispatch [:evt-conf/append :a]]
              [:dispatch [:evt-conf/append :b]]]}))
    (rf/dispatch-sync [:evt-conf/kickoff])
    (is (true? @(rf/subscribe [:evt-conf/kicked?]))
        "the `:db` effect committed alongside the `:fx` walk")
    (is (= [:a :b] @(rf/subscribe [:evt-conf/log]))
        "the `:fx`-dispatched events ran in source order")))

(deftest reg-event-foreign-top-level-key-is-effect-map-shape
  (testing "EP-0018 §4: a FOREIGN top-level effect key (outside the closed
            `#{:db :fx :rf.db/runtime}`) is the `:rf.error/effect-map-shape`
            diagnostic (`:recovery :logged-and-skipped`) — the v1 top-level
            shortcut shape stays rejected, NOT silently honoured. The
            diagnostic rides the dev trace bus (an `:op-type :error` op), so
            observe it via a trace listener filtering on `:operation`"
    (let [traces (atom [])
          fired? (atom false)]
      (rf/register-listener! :evt-conf/shape-recorder (fn [ev] (swap! traces conj ev)))
      ;; A sentinel the foreign shortcut would dispatch IF the runtime
      ;; wrongly honoured the legacy top-level `:dispatch` — it must NOT.
      (rf/reg-event :evt-conf/shortcut-target (fn [_ _] (reset! fired? true) {}))
      ;; `:dispatch` at the TOP LEVEL is the canonical v1 legacy shortcut — it
      ;; must be lowered through `{:fx [[:dispatch …]]}`, so the bare top-level
      ;; key is a foreign-key shape error, not a silently-honoured shortcut.
      (rf/reg-event :evt-conf/legacy-shortcut
        (fn [_ _] {:dispatch [:evt-conf/shortcut-target]}))
      (rf/dispatch-sync [:evt-conf/legacy-shortcut])
      (rf/unregister-listener! :evt-conf/shape-recorder)
      (is (false? @fired?)
          "the legacy top-level `:dispatch` was NOT silently honoured")
      (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %)) @traces)]
        (is (seq shape-traces)
            "a foreign / legacy top-level effect key emits :rf.error/effect-map-shape")
        (is (= :dispatch (get-in (first shape-traces) [:tags :offending-key]))
            "the diagnostic names the offending legacy top-level key")))))

(deftest reg-event-unchanged-db-return-is-a-true-noop
  (testing "EP-0018 §4 (rf2-na5i1z): a `{:db db}` return that re-installs the
            SAME app-db value is a true no-op — app-db is unchanged AND the
            change-trace stream reflects the no-op (`:rf.event/db-noop` fires,
            `:rf.event/db-changed` does NOT). A regression that treated every
            `{:db …}` return as a write (firing db-changed unconditionally,
            re-running dependent subs) would turn this RED. The observable
            stable surface for the no-op-vs-changed distinction is the dev
            trace bus, so observe it via a trace listener filtering `:operation`"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/noop-seed (fn [db _] (:seed db :untouched)))
      (rf/reg-event :evt-conf/noop-seed! (fn [{:keys [db]} _] {:db (assoc db :seed :set)}))
      ;; A handler that returns `{:db db}` — the SAME value it was handed, the
      ;; canonical unchanged-db return.
      (rf/reg-event :evt-conf/noop-rewrite (fn [{:keys [db]} _] {:db db}))
      (rf/dispatch-sync [:evt-conf/noop-seed!])
      ;; Listen only across the no-op dispatch so the seed write's db-changed
      ;; does not pollute the assertion.
      (rf/register-listener! :evt-conf/noop-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/noop-rewrite])
      (rf/unregister-listener! :evt-conf/noop-recorder)
      (is (= :set @(rf/subscribe [:evt-conf/noop-seed]))
          "the `{:db db}` rewrite left app-db at its prior value (a true no-op)")
      (let [ops (set (map :operation @traces))]
        (is (contains? ops :rf.event/db-noop)
            "an unchanged `{:db db}` return fires :rf.event/db-noop")
        (is (not (contains? ops :rf.event/db-changed))
            "an unchanged `{:db db}` return does NOT fire :rf.event/db-changed")))))

(deftest reg-event-db-nil-return-is-coerced-to-empty-map-with-diagnostic
  (testing "EP-0018 §4 + rf2-ekq28v (rf2-na5i1z): a `{:db nil}` return is
            COERCED to `{}` at the commit boundary (app-db is always a map,
            never nil — the v1 nil-footgun is removed structurally) AND emits
            the dev-mode `:rf.warning/db-nil-coerced` diagnostic for
            accidental-wipe visibility. The diagnostic rides the trace bus, so
            observe it via a trace listener filtering on `:operation`. A
            regression that wiped app-db to nil (or coerced silently) goes RED"
    (let [traces  (atom [])
          db-seen (atom ::unset)]
      ;; Seed a non-trivial app-db so the coercion-to-`{}` is observable.
      (rf/reg-event :evt-conf/nil-seed! (fn [{:keys [db]} _] {:db (assoc db :k :v)}))
      (rf/reg-event :evt-conf/return-nil-db (fn [_ _] {:db nil}))
      ;; A follow-up handler reads app-db so we can prove it is `{}` (a map),
      ;; never nil — `(:k db)` is absent and `(map? db)` holds.
      (rf/reg-event :evt-conf/inspect-db (fn [{:keys [db]} _] (reset! db-seen db) {}))
      (rf/dispatch-sync [:evt-conf/nil-seed!])
      (rf/register-listener! :evt-conf/nil-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/return-nil-db])
      (rf/unregister-listener! :evt-conf/nil-recorder)
      (rf/dispatch-sync [:evt-conf/inspect-db])
      (is (map? @db-seen)
          "app-db after a `{:db nil}` return is a MAP, never nil")
      (is (not (contains? @db-seen :k))
          "the `{:db nil}` return coerced app-db to an EMPTY map (the prior :k is gone)")
      (let [coerce-traces (filter #(= :rf.warning/db-nil-coerced (:operation %)) @traces)]
        (is (seq coerce-traces)
            "a `{:db nil}` return emits the :rf.warning/db-nil-coerced diagnostic")
        (is (= :warned (:recovery (first coerce-traces)))
            "the coercion diagnostic carries :recovery :warned (the value is still applied)")))))

(deftest reg-event-deliberate-empty-db-clear-emits-no-diagnostic
  (testing "EP-0018 §4 + rf2-ekq28v (rf2-na5i1z): a DELIBERATE clear — returning
            `{:db {}}` (a distinct, non-nil empty map) — clears app-db WITHOUT
            the `:rf.warning/db-nil-coerced` diagnostic, distinguishing the
            intentional empty-map clear from the accidental-nil footgun. A
            regression that warned on a deliberate `{:db {}}` clear goes RED"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/clear-mark (fn [db _] (:mark db :untouched)))
      (rf/reg-event :evt-conf/clear-seed! (fn [{:keys [db]} _] {:db (assoc db :mark :set)}))
      (rf/reg-event :evt-conf/clear-db    (fn [_ _] {:db {}}))
      (rf/dispatch-sync [:evt-conf/clear-seed!])
      (rf/register-listener! :evt-conf/clear-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/clear-db])
      (rf/unregister-listener! :evt-conf/clear-recorder)
      (is (= :untouched @(rf/subscribe [:evt-conf/clear-mark]))
          "the deliberate `{:db {}}` clear emptied app-db (the prior :mark is gone)")
      (is (empty? (filter #(= :rf.warning/db-nil-coerced (:operation %)) @traces))
          "a deliberate `{:db {}}` clear emits NO :rf.warning/db-nil-coerced diagnostic"))))

(deftest reg-event-malformed-fx-value-is-logged-and-skipped-without-aborting-siblings
  (testing "EP-0018 §4 + rf2-24zly (rf2-na5i1z): a non-sequential `:fx` VALUE
            (the forgot-the-outer-vector typo — e.g. `{:fx {:dispatch […]}}`)
            is the `:rf.error/effect-map-shape` diagnostic
            (`:recovery :logged-and-skipped`) and is DROPPED — it never reaches
            `fx/do-fx` to throw a raw host exception AFTER the db commit. The
            sibling `:db` write STILL commits and the cascade is NOT aborted. A
            regression that let the malformed `:fx` escape (a raw host throw,
            app-db left mutated, downstream events abandoned) goes RED"
    (let [traces    (atom [])
          sentinel? (atom false)]
      (rf/reg-sub :evt-conf/fx-shape-db (fn [db _] (:committed db :absent)))
      ;; A sentinel the malformed `:fx` would dispatch IF the map-shaped value
      ;; were wrongly walked as a pair — it must NOT run.
      (rf/reg-event :evt-conf/fx-shape-sentinel (fn [_ _] (reset! sentinel? true) {}))
      (rf/reg-event :evt-conf/malformed-fx
        (fn [{:keys [db]} _]
          ;; `:db` is a well-formed sibling; `:fx` is a non-sequential MAP
          ;; (the documented forgot-the-outer-vector typo).
          {:db (assoc db :committed :yes)
           :fx {:dispatch [:evt-conf/fx-shape-sentinel]}}))
      (rf/register-listener! :evt-conf/fx-shape-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/malformed-fx])
      (rf/unregister-listener! :evt-conf/fx-shape-recorder)
      (is (= :yes @(rf/subscribe [:evt-conf/fx-shape-db]))
          "the sibling `:db` write committed — a malformed `:fx` does NOT abort the cascade")
      (is (false? @sentinel?)
          "the non-sequential `:fx` value was DROPPED — it was not walked as a pair (sentinel never dispatched)")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :fx (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "a non-sequential `:fx` value emits :rf.error/effect-map-shape naming :fx")
        (is (= :logged-and-skipped (:recovery (first shape-traces)))
            "the malformed-:fx diagnostic carries :recovery :logged-and-skipped")))))

(deftest reg-event-final-effects-boundary-polices-after-interceptor-effects
  (testing "EP-0018 §4 + rf2-u1kdvg (rf2-na5i1z): the FINAL-effects boundary —
            not only the per-handler-return check — polices the closed
            effect-map. An `:after` interceptor that injects a FOREIGN
            top-level effect key AFTER the handler-return policing has run is
            still caught at the final boundary: the foreign key emits
            `:rf.error/effect-map-shape` and is dropped (never silently honoured
            at the partition commit). A regression that only policed the
            handler return (letting an `:after`-injected foreign key through)
            goes RED. EP-0022 reference-only: the interceptor is registered via
            the public surface and referenced by id"
    (let [traces (atom [])]
      (rf/reg-sub :evt-conf/boundary-db (fn [db _] (:committed db :absent)))
      ;; EP-0022: register the `:after`-injecting interceptor through the
      ;; PUBLIC reg-interceptor surface; reference it by id in the chain.
      (rf/reg-interceptor :evt-conf/inject-foreign
        {:after (fn [ctx]
                  ;; Inject a FOREIGN top-level effect key into the final
                  ;; effects map AFTER the handler-return policing already ran.
                  (assoc-in ctx [:effects :evt-conf/foreign] :should-be-dropped))})
      (rf/reg-event :evt-conf/handler-with-after
        {:interceptors [:evt-conf/inject-foreign]}
        (fn [{:keys [db]} _] {:db (assoc db :committed :yes)}))
      (rf/register-listener! :evt-conf/boundary-recorder (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:evt-conf/handler-with-after])
      (rf/unregister-listener! :evt-conf/boundary-recorder)
      (is (= :yes @(rf/subscribe [:evt-conf/boundary-db]))
          "the handler's well-formed `:db` write committed")
      (let [shape-traces (filter #(and (= :rf.error/effect-map-shape (:operation %))
                                       (= :evt-conf/foreign (get-in % [:tags :offending-key])))
                                 @traces)]
        (is (seq shape-traces)
            "an :after-interceptor-injected foreign key is policed at the FINAL boundary")
        (is (= :logged-and-skipped (:recovery (first shape-traces)))
            "the final-boundary diagnostic carries :recovery :logged-and-skipped")))))

;; ===========================================================================
;; (3) Registration SHAPE — registers under :event with the ONE wrapper,
;;     handler-meta surfaces metadata + the effective chain, NO :event/kind.
;; ===========================================================================

(deftest reg-event-registers-under-event-kind-with-the-one-wrapper
  (testing "EP-0018 §5: reg-event registers under registry kind :event;
            handler-meta returns the metadata + effective interceptor chain;
            the framework wrapper is the ONE `:rf/event-handler` interceptor
            with `:rf/default? true`, and NO `:event/kind` sub-tag survives"
    (rf/reg-event :evt-conf/shape (fn [{:keys [db]} _] {:db (assoc db :m :v)}))
    (let [meta (rf/handler-meta :event :evt-conf/shape)]
      (is (some? meta)
          "reg-event registers under registry kind :event (handler-meta finds it)")
      (is (fn? (:handler-fn meta))
          "handler-meta surfaces the registered handler-fn")
      (is (not (contains? meta :event/kind))
          "the `:event/kind` sub-tag is GONE (one form, no kind discriminator)")
      ;; THE wrapper lock — exactly ONE framework wrapper, named :rf/event-handler.
      (is (= [:rf/event-handler] (mapv :id (:interceptors meta)))
          "the ONLY framework wrapper is the single :rf/event-handler interceptor")
      (let [wrapper (first (:interceptors meta))]
        (is (= :rf/event-handler (:id wrapper))
            "the wrapper id is :rf/event-handler")
        (is (true? (:rf/default? wrapper))
            "the wrapper carries :rf/default? true (filtered as a framework auto-wrapper)")))))

(deftest reg-event-no-per-kind-wrapper-ids-survive
  (testing "ADVERSARIAL wrapper-drift lock (EP-0018 §5): the historical per-kind
            wrapper ids (`:rf/db-handler` / `:rf/fx-handler` / `:rf/ctx-handler`)
            are GONE — a reg-event chain carries NONE of them, only the unified
            `:rf/event-handler`. Re-introducing a per-kind wrapper would go RED"
    (rf/reg-event :evt-conf/wrapper-drift (fn [_ _] {}))
    (let [ids (set (mapv :id (:interceptors (rf/handler-meta :event :evt-conf/wrapper-drift))))]
      (is (= #{:rf/event-handler} ids)
          "exactly one wrapper id; the unified :rf/event-handler")
      (is (not (contains? ids :rf/db-handler))  "the retired :rf/db-handler wrapper is gone")
      (is (not (contains? ids :rf/fx-handler))  "the retired :rf/fx-handler wrapper is gone")
      (is (not (contains? ids :rf/ctx-handler)) "the retired :rf/ctx-handler wrapper is gone"))))

(deftest reg-event-metadata-interceptors-thread-before-the-wrapper
  (testing "EP-0018 §1: the metadata-map `:interceptors` superset slot threads
            the user chain BEFORE the framework wrapper; handler-meta surfaces
            both the reflection metadata and the effective chain"
    ;; EP-0022 reference-only: register the no-op interceptor, reference by id.
    (rf/reg-interceptor* :evt-conf/noop {:before identity :after identity})
    (rf/reg-event :evt-conf/with-icpt
      {:doc "documented" :interceptors [:evt-conf/noop]}
      (fn [{:keys [db]} _] {:db db}))
    (let [meta (rf/handler-meta :event :evt-conf/with-icpt)]
      (is (= "documented" (:doc meta))
          "the reflection metadata is retained on the registry entry")
      (is (= [:evt-conf/noop :rf/event-handler] (chain-ids (:interceptors meta)))
          "the user chain (authored ref) sits before the single framework wrapper"))))

(deftest reg-event-rf-cofx-requires-stored-on-registration
  (testing "EP-0018 §5: the raw + parsed `:rf.cofx/requires` declaration is
            stored on the reg-event registration (handler-meta surfaces it)"
    (rf/reg-cofx :evt-conf/who (fn [] :nobody))
    (rf/reg-event :evt-conf/declarer
      {:rf.cofx/requires [:evt-conf/who]}
      (fn [_ _] {}))
    (let [meta (rf/handler-meta :event :evt-conf/declarer)]
      (is (= [:evt-conf/who] (:rf.cofx/requires meta))
          "the raw :rf.cofx/requires is retained on the registry entry")
      (is (contains? meta :rf.cofx/requires-parsed)
          "the parsed entry vector is stored for the satisfaction step"))))

(deftest reg-event-chain-references-an-interceptor-from-public-reg-interceptor
  (testing "EP-0022 public authoring surface (rf2-9g5xla): an application
            interceptor used by an event chain is registered through the PUBLIC
            `rf/reg-interceptor` form — NOT only the programmatic
            `rf/reg-interceptor*` helper. EP-0022 makes `reg-interceptor` the
            public authoring surface (demoting `->interceptor` to an internal
            lowering constructor); this tier must exercise THAT path so a
            regression in the public facade / macro / metadata route for
            `reg-interceptor` goes RED rather than passing on the internal
            helper. The chain is reference-only: the event registration carries
            the interceptor ID, not an inline value. `handler-meta :interceptor`
            surfaces the registered descriptor + the registration metadata"
    (let [ran? (atom false)]
      (rf/reg-sub :evt-conf/public-icpt-marker (fn [db _] (:public-icpt-marker db)))
      ;; PUBLIC authoring surface — `rf/reg-interceptor` (macro on JVM,
      ;; fn-alias on CLJS), WITH a registration-metadata map (`:doc`) so the
      ;; metadata route is exercised, not just the descriptor.
      (rf/reg-interceptor :evt-conf/audit
        {:doc "an application audit interceptor authored via reg-interceptor"}
        {:before (fn [ctx] (reset! ran? true) ctx)})
      ;; handler-meta for the :interceptor kind surfaces the registered
      ;; descriptor + the registration metadata (EP-0022).
      (let [imeta (rf/handler-meta :interceptor :evt-conf/audit)]
        (is (some? imeta)
            "reg-interceptor registers under the :interceptor kind (handler-meta finds it)")
        (is (= "an application audit interceptor authored via reg-interceptor" (:doc imeta))
            "the registration metadata (:doc) is retained on the :interceptor entry")
        (is (contains? imeta :rf/interceptor-descriptor)
            "handler-meta surfaces the registered :rf/interceptor-descriptor (the public path)")
        (is (fn? (:before (:rf/interceptor-descriptor imeta)))
            "the descriptor carries the authored :before slot"))
      ;; The event chain references the public-registered interceptor by ID —
      ;; reference-only (an inline value would be :rf.error/inline-interceptor-removed).
      (rf/reg-event :evt-conf/uses-public-icpt
        {:interceptors [:evt-conf/audit]}
        (fn [{:keys [db]} _] {:db (assoc db :public-icpt-marker :handler-ran)}))
      (let [emeta (rf/handler-meta :event :evt-conf/uses-public-icpt)]
        (is (= [:evt-conf/audit :rf/event-handler] (chain-ids (:interceptors emeta)))
            "the event chain carries the interceptor REFERENCE (id), before the framework wrapper"))
      ;; Dispatch proves the public-registered interceptor actually runs in the
      ;; event chain (the reference resolved to the registered descriptor).
      (rf/dispatch-sync [:evt-conf/uses-public-icpt])
      (is (true? @ran?)
          "the public-registered interceptor's :before ran in the event chain")
      (is (= :handler-ran @(rf/subscribe [:evt-conf/public-icpt-marker]))
          "the event handler ran after the public-registered interceptor"))))

;; ===========================================================================
;; (4) The retired public names — facade-exported `^:no-doc` throwing stubs
;;     raising their EXACT hard errors, production-survivable (fan out on the
;;     error channel THEN throw), registering nothing.
;; ===========================================================================

(deftest retired-names-raise-their-exact-removal-errors
  (testing "EP-0018 §2/§3: the three retired public names are resolvable facade
            stubs that raise their EXACT naming hard errors — a hard runtime
            error, never a silent alias (FAILS CLOSED on a re-introduced
            working form via the :no-throw sentinel)"
    (is (= :rf.error/reg-event-db-removed
           (thrown-error-id #(rf/reg-event-db :evt-conf/via-db (fn [_ _] nil))))
        "reg-event-db raises :rf.error/reg-event-db-removed")
    (is (= :rf.error/reg-event-fx-removed
           (thrown-error-id #(rf/reg-event-fx :evt-conf/via-fx (fn [_ _] nil))))
        "reg-event-fx raises :rf.error/reg-event-fx-removed")
    (is (= :rf.error/reg-event-ctx-removed
           (thrown-error-id #(rf/reg-event-ctx :evt-conf/via-ctx (fn [_ _] nil))))
        "reg-event-ctx raises :rf.error/reg-event-ctx-removed")))

(deftest reg-event-ctx-removal-points-at-reg-interceptor-not-arrow-interceptor
  (testing "EP-0022 cross-wave coherence (rf2-0p3ix9): the public
            `reg-event-ctx` removal error guides users to the PUBLIC authoring
            form `reg-interceptor` (registered interceptors) — NOT the now
            framework-internal lowering constructor `->interceptor` /
            `rf/->interceptor`. The umbrella tier asserts the replacement
            GUIDANCE the user sees on the hard-error path, not just the error
            id; a regression that reverted the guidance back to `->interceptor`
            (or dropped the `:reason`) goes RED"
    (let [reason (thrown-error-reason
                   #(rf/reg-event-ctx :evt-conf/ctx-reason (fn [_ _] nil)))]
      (is (string? reason)
          "the removal stub raises an ex-info carrying a `:reason` string")
      (is (re-find #"reg-interceptor" reason)
          "the replacement guidance names `reg-interceptor` (the EP-0022 public form)")
      (is (not (re-find #"->interceptor" reason))
          "the guidance does NOT present `->interceptor` (internal-only post-EP-0022)"))))

(deftest reg-event-db-and-fx-removals-still-point-at-reg-event
  (testing "EP-0018 §2/§3: the reg-event-db / reg-event-fx removal errors keep
            naming `reg-event` (the ONE public registration form) as their
            replacement — guarding the db/fx guidance alongside the ctx one so a
            cross-wave edit that broke either still goes RED"
    (let [db-reason (thrown-error-reason
                      #(rf/reg-event-db :evt-conf/db-reason (fn [_ _] nil)))
          fx-reason (thrown-error-reason
                      #(rf/reg-event-fx :evt-conf/fx-reason (fn [_ _] nil)))]
      (is (re-find #"reg-event" db-reason)
          "reg-event-db removal names reg-event as the replacement")
      (is (re-find #"reg-event" fx-reason)
          "reg-event-fx removal names reg-event as the replacement"))))

(deftest retired-names-are-resolvable-facade-vars
  (testing "EP-0018 §2/§3: the retired names are RESOLVABLE facade vars (so the
            call reaches the throwing stub — a hard error, not an
            unresolved-symbol compile failure); each is a callable value on the
            `re-frame.core` facade"
    ;; Resolvability is proven by the fact the call above REACHED the stub and
    ;; threw the removal error (an unresolved var would be a compile/analysis
    ;; error, never a runtime :rf.error/*). Pin it explicitly: the facade vars
    ;; are callable functions.
    ;; The three retired names are plain FN stubs on both runtimes (so the
    ;; call reaches the throw). `reg-event`, the ONE live form, is a MACRO on
    ;; the JVM (source-coord capture) and a fn-alias on CLJS, so it is NOT a
    ;; `fn?` value on the JVM — its public-facade presence is locked by the
    ;; §5 no-doc probe + the api-manifest --check gate, not a `fn?` check here.
    (is (fn? rf/reg-event-db)  "reg-event-db is a resolvable callable facade fn (the throwing stub)")
    (is (fn? rf/reg-event-fx)  "reg-event-fx is a resolvable callable facade fn (the throwing stub)")
    (is (fn? rf/reg-event-ctx) "reg-event-ctx is a resolvable callable facade fn (the throwing stub)")))

(deftest retired-names-register-nothing-only-reg-event-commits
  (testing "EP-0018 §2/§3: the retired-name stubs register NOTHING — after each
            throws, its id is absent from the registry; only reg-event commits.
            (A re-introduced form that registered would leave a registry slot,
            turning the `nil?` assertions RED.)"
    (rf/reg-sub :evt-conf/tally (fn [db _] (:tally db [])))
    (rf/reg-event :evt-conf/live
      (fn [{:keys [db]} _] {:db (update db :tally (fnil conj []) :reg-event)}))
    (thrown-error-id #(rf/reg-event-db  :evt-conf/db-noreg  (fn [_ _] nil)))
    (thrown-error-id #(rf/reg-event-fx  :evt-conf/fx-noreg  (fn [_ _] nil)))
    (thrown-error-id #(rf/reg-event-ctx :evt-conf/ctx-noreg (fn [_ _] nil)))
    (is (nil? (registrar/lookup :event :evt-conf/db-noreg))
        "reg-event-db registered nothing")
    (is (nil? (registrar/lookup :event :evt-conf/fx-noreg))
        "reg-event-fx registered nothing")
    (is (nil? (registrar/lookup :event :evt-conf/ctx-noreg))
        "reg-event-ctx registered nothing")
    (rf/dispatch-sync [:evt-conf/live])
    (is (= [:reg-event] @(rf/subscribe [:evt-conf/tally]))
        "only the reg-event handler committed; the retired stubs registered nothing")))

(deftest retired-name-error-fans-out-on-the-always-on-channel-before-throwing
  (testing "EP-0018 §2 + spec/009: the removal error is PRODUCTION-SURVIVABLE —
            it fans out on the always-on error-emit channel (the off-box
            observability stream that survives `goog.DEBUG=false`) BEFORE the
            throw escapes. A removal that threw but went silent on the channel
            would turn this RED"
    (let [seen (atom [])]
      (rf/register-error-listener! :evt-conf/removal-recorder
        (fn [r] (swap! seen conj (:error r))))
      ;; The call throws; the listener must already have received the record.
      (is (= :rf.error/reg-event-db-removed
             (thrown-error-id #(rf/reg-event-db :evt-conf/fanned (fn [_ _] nil))))
          "the call still throws the removal error")
      (is (some #{:rf.error/reg-event-db-removed} @seen)
          "the removal error fanned out on the always-on channel before the throw")
      ;; Reset + prove the same for the fx + ctx removals (one channel, three errors).
      (reset! seen [])
      (thrown-error-id #(rf/reg-event-fx :evt-conf/fanned (fn [_ _] nil)))
      (is (some #{:rf.error/reg-event-fx-removed} @seen)
          "reg-event-fx removal also fans out on the always-on channel")
      (reset! seen [])
      (thrown-error-id #(rf/reg-event-ctx :evt-conf/fanned (fn [_ _] nil)))
      (is (some #{:rf.error/reg-event-ctx-removed} @seen)
          "reg-event-ctx removal also fans out on the always-on channel"))))

;; ===========================================================================
;; (5) PUBLIC FACADE classification — reg-event-ctx is NOT a public (doc'd)
;;     facade var, while reg-event IS. JVM-only meta probe (CLJS has no
;;     runtime vars; the CLJS publics probe filters `^:no-doc` at compile
;;     time, exercised by the api-manifest --check gate — see PR §Quality
;;     gates).
;; ===========================================================================

#?(:clj
   (deftest public-facade-no-doc-classification
     (testing "EP-0018 §3 + §Backwards Compatibility: `reg-event-ctx` is NOT a
               public (doc'd) facade var — it carries `^:no-doc` — and so do
               the two REMOVED names; `reg-event` (the ONE live form) carries
               NO `:no-doc` and IS public. A `reg-event-ctx` promotion back
               onto the public surface (losing `^:no-doc`), or `reg-event`
               accidentally gaining one, turns this RED"
       (is (nil? (:no-doc (meta #'re-frame.core/reg-event)))
           "reg-event is PUBLIC — it carries no :no-doc meta")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-ctx)))
           "public reg-event-ctx is NOT a doc'd facade var — it carries ^:no-doc")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-db)))
           "the REMOVED reg-event-db carries ^:no-doc (off the public manifest)")
       (is (true? (:no-doc (meta #'re-frame.core/reg-event-fx)))
           "the REMOVED reg-event-fx carries ^:no-doc (off the public manifest)"))))

;; ===========================================================================
;; (6) Path interceptors work with `{:db slice}` returns.
;; ===========================================================================

(deftest reg-event-path-interceptor-works-with-db-slice-return
  (testing "EP-0018 §6: the `[:rf.interceptor/path …]` ref focuses the handler
            on a sub-slice — the handler sees the SLICE as `:db` and returns
            `{:db slice}`, which the interceptor splices back at the path (the
            bare-slice return is gone; it is `{:db slice}` now). EP-0022: there
            is no public `rf/path` value constructor — the chain carries the ref"
    (rf/reg-sub :evt-conf/counter (fn [db _] (:counter db)))
    (rf/reg-event :evt-conf/inc-via-path
      {:interceptors [[:rf.interceptor/path [:counter]]]}
      ;; `db` here is the FOCUSED slice at [:counter], not the whole app-db;
      ;; the return is `{:db <new-slice>}`.
      (fn [{:keys [db]} _] {:db (update (or db {}) :value (fnil inc 0))}))
    (rf/dispatch-sync [:evt-conf/inc-via-path])
    (rf/dispatch-sync [:evt-conf/inc-via-path])
    (is (= {:value 2} @(rf/subscribe [:evt-conf/counter]))
        "the `{:db slice}` return was spliced back into app-db at [:counter]")))

;; ===========================================================================
;; (7) Raw context capture / short-circuit is expressible with an INTERCEPTOR
;;     — no public reg-event-ctx is required for the suite (EP-0018 §7).
;; ===========================================================================

(deftest raw-context-work-is-expressible-via-an-interceptor
  (testing "EP-0018 §7: full-context work that once used reg-event-ctx is
            expressed as an interceptor (the public `context → context`
            primitive) — `:rf/skip-handler?` short-circuits the handler and the
            interceptor installs effects directly, with NO public reg-event-ctx"
    (let [handler-ran? (atom false)]
      (rf/reg-sub :evt-conf/guard-marker (fn [db _] (:guard-marker db)))
      ;; EP-0022 reference-only: register the guard interceptor, then reference
      ;; it by id in the chain (an inline interceptor value is now rejected).
      (rf/reg-interceptor* :evt-conf/guard
        {:before
         (fn [ctx]
           ;; Capture (read the full context) + short-circuit the
           ;; handler + install an effect directly — the trio that
           ;; reg-event-ctx used to do, now an interceptor concern.
           (-> ctx
               (assoc :rf/skip-handler? true)
               (assoc-in [:effects :fx]
                         [[:dispatch [:evt-conf/guard-fired]]])))})
      (rf/reg-event :evt-conf/guard-fired
        (fn [{:keys [db]} _] {:db (assoc db :guard-marker :fired)}))
      (rf/reg-event :evt-conf/guarded
        {:interceptors [:evt-conf/guard]}
        (fn [_ _] (reset! handler-ran? true) {:db {:should :not-run}}))
      (rf/dispatch-sync [:evt-conf/guarded])
      (is (false? @handler-ran?)
          "the interceptor short-circuited the handler via :rf/skip-handler?")
      (is (= :fired @(rf/subscribe [:evt-conf/guard-marker]))
          "the interceptor's directly-installed effect ran (no reg-event-ctx needed)"))))

;; ===========================================================================
;; (8) Realm-routing preserved for reg-event (a15n62) — the public registrar
;;     resolves through the owning frame's realm registrar.
;; ===========================================================================

(defn- add-a    [{:keys [db]} _] {:db (assoc db :who :a)})
(defn- add-b    [{:keys [db]} _] {:db (assoc db :who :b)})

(deftest reg-event-routes-through-the-owning-frames-realm-registrar
  (testing "EP-0013 step 4 (a15n62) preserved for the ONE form: the SAME
            reg-event id installed into two realms carries genuinely different
            handlers, each resolved through ITS realm's registrar — no
            cross-realm bleed, and the default realm never sees them"
    (let [_ra (rf/realm {:id :evt-conf/ra})
          _rb (rf/realm {:id :evt-conf/rb})
          app-of (fn [h] (rf/app {:id :evt-conf/shared :modules
                                  [(rf/module {:id :m :events {:shared/e {:handler h}}})]}))]
      (rf/install! (realm/realm :evt-conf/ra) (app-of add-a))
      (rf/install! (realm/realm :evt-conf/rb) (app-of add-b))
      (is (= add-a (:handler-fn (rf/handler-meta {:realm :evt-conf/ra :kind :event :id :shared/e})))
          "realm ra resolves ITS handler for the shared reg-event id")
      (is (= add-b (:handler-fn (rf/handler-meta {:realm :evt-conf/rb :kind :event :id :shared/e})))
          "realm rb resolves ITS handler for the shared reg-event id")
      (is (not= (rf/handler-meta {:realm :evt-conf/ra :kind :event :id :shared/e})
                (rf/handler-meta {:realm :evt-conf/rb :kind :event :id :shared/e}))
          "the two realms hold genuinely different handlers for the same id")
      (is (nil? (rf/handler-meta :event :shared/e))
          "the shared id is absent from the default realm (no global bleed)"))))

(deftest public-reg-event-fn-honours-the-bound-realm-registrar
  (testing "EP-0013 step 4 (a15n62): the PUBLIC `reg-event` registrar fn itself
            routes to the active realm registrar — calling `rf/reg-event` inside
            a non-default realm's registrar binding seats the handler into THAT
            realm's own registrar, invisible to the default realm"
    (let [r (rf/realm {:id :evt-conf/bound})]
      ;; Bind the active registrar to the constructed realm's OWN atom — the
      ;; exact seam (`registrar/*registrar*`) the router's
      ;; `call-with-frame-realm-registrar` binds around a realm-routed dispatch
      ;; (frame.cljc) — then call the PUBLIC reg-event through it.
      (binding [registrar/*registrar* (realm/registrar r)]
        (rf/reg-event :evt-conf/realm-scoped (fn [{:keys [db]} _] {:db (assoc db :seated :here)})))
      ;; It landed in the realm's OWN registrar …
      (is (fn? (get-in @(realm/registrar r) [:event :evt-conf/realm-scoped :handler-fn]))
          "the public reg-event seated the handler into the bound realm's own registrar")
      (is (fn? (:handler-fn (rf/handler-meta {:realm :evt-conf/bound :kind :event :id :evt-conf/realm-scoped})))
          "the realm-targeted handler-meta resolves the realm-seated reg-event")
      ;; … and is INVISIBLE to the process-global / default-realm registrar.
      (is (nil? (registrar/lookup :event :evt-conf/realm-scoped))
          "the default-realm registrar did NOT receive the realm-scoped reg-event"))))
