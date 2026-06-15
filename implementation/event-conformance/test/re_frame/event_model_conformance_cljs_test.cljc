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
