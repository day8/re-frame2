(ns re-frame.interceptor-test
  "Dedicated coverage for the v2 retained interceptor surface (Spec 002).

  v2 trims the v1 interceptor stdlib down to a tiny retained set:
    - the framework-standard `[:rf.interceptor/path <path-vector>]` ref
      (its consumer `standard-path-interceptor`)
    - ->interceptor (and the supporting context plumbing)

  (`inject-cofx` is removed — EP-0017 / rf2-w9xyx1 — not on the facade;
  coeffect delivery is the `:rf.cofx/requires` declaration. The public
  `rf/path` VALUE constructor is removed too — EP-0022 / rf2-dgtdna — a stale
  `(rf/path …)` call is the hard error `:rf.error/path-removed` naming the ref.
  The standard `unwrap-interceptor` VALUE is removed too — EP-0022 / rf2-3qeu38
  — the framework ships no standard unwrap; the canonical spelling is
  handler-payload destructuring, or a PROJECT-registered `:app/unwrap`
  interceptor. The unwrap deftests below exercise such a project-local
  interceptor — they no longer depend on a framework-owned value.)

  These are exercised obliquely by smoke / conformance tests, but nothing
  pins their contract directly. This namespace does — one deftest per
  interceptor plus a chain-composition deftest covering before-order /
  after-reverse-order / exception-interruption.

  Tests run on the JVM via the plain-atom adapter; per the project
  invariant the JVM interop layer must work."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.interceptor :as interceptor]
            [re-frame.std-interceptors :as std-interceptors]
            [re-frame.trace :as trace]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  (rf/init! plain-atom/adapter)
  ;; Framework-shipped registrations live in routing.cljc / ssr.cljc /
  ;; machines.cljc and are wiped by clear-all!. None of these tests need
  ;; them, so we skip the require-reload dance — keeps the fixture cheap.
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win.
  (rf/reg-frame :rf/default {})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- path -----------------------------------------------------------------

(deftest path-interceptor
  (testing "(path :foo :bar) scopes a handler to the [:foo :bar] sub-tree:
            the handler sees only the slice as :db, and its returned value
            is spliced back at that path."
    (rf/reg-event :path-test/init
                     (fn [{:keys [db]} _]
                       {:db {:foo {:bar  10
                              :keep :untouched}
                        :other :preserved}}))
    (rf/reg-event :path-test/inc
                     {:interceptors [[:rf.interceptor/path [:foo :bar]]]}
                     ;; The handler's `db` is the SLICE value, not the full
                     ;; app-db. Returning a new slice value writes it back
                     ;; at [:foo :bar].
                     (fn [{slice :db} _]
                       (is (= 10 slice)
                           "path interceptor presents the slice as :db")
                       {:db (inc slice)}))
    (rf/dispatch-sync [:path-test/init])
    (rf/dispatch-sync [:path-test/inc])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 11 (get-in db [:foo :bar]))
          "the handler's return value was spliced back at [:foo :bar]")
      (is (= :untouched (get-in db [:foo :keep]))
          "siblings under :foo are preserved")
      (is (= :preserved (:other db))
          "keys outside the path are preserved"))))

(deftest path-interceptor-uses-reserved-namespace
  (testing "the path interceptor stashes its db-stack under
            :rf.interceptor.path/stack (reserved namespace per Spec
            Conventions §Reserved namespaces). Regression for rf2-mn0qc.
            EP-0022 (rf2-0adhqs.9): the standard `[:rf.interceptor/path …]`
            factory interceptor stashes under the `:rf.interceptor.path/stack`
            reserved slot (the old `:rf/path-stack` belonged to the now-
            deprecated `rf/path` value-constructor)."
    (let [seen-keys (atom nil)]
      ;; A spy interceptor sandwiched between `path` and the handler runs
      ;; AFTER path's :before, so the context it sees must carry the stash
      ;; under the reserved :rf.interceptor.path/stack — not a bare key.
      (rf/reg-event :path-ns/init (fn [{:keys [db]} _] {:db {:foo {:bar 10}}}))
      (rf/reg-interceptor* :path-ns/spy
                           {:before (fn [ctx]
                                      (reset! seen-keys (set (keys ctx)))
                                      ctx)})
      (rf/reg-event :path-ns/inc
                       {:interceptors [[:rf.interceptor/path [:foo :bar]]
                                       :path-ns/spy]}
                       ;; a path-focused reg-event handler receives the focused
                       ;; slice in its coeffects `:db`; `inc` is arity-1 so wrap
                       ;; it explicitly to return a `{:db new-slice}` effect.
                       (fn [{slice :db} _] {:db (inc slice)}))
      (rf/dispatch-sync [:path-ns/init])
      (rf/dispatch-sync [:path-ns/inc])
      (is (contains? @seen-keys :rf.interceptor.path/stack)
          "path interceptor stashes its stack under the reserved :rf.interceptor.path/stack key")
      (is (not (contains? @seen-keys :path-stack))
          "the bare :path-stack key must NOT appear (reserved-namespace contract)")
      (is (= 11 (get-in (rf/app-db-value :rf/default) [:foo :bar]))
          "the rename did not break the path interceptor's splice-back behaviour"))))

(deftest path-interceptor-nesting
  (testing "nested [:rf.interceptor/path …] interceptors compose correctly —
            the LIFO stack semantics of :rf.interceptor.path/stack mean an inner
            path's :after restores the outer slice and the outer :after splices
            back into the full app-db. Pins the stack semantics independent of
            the slot-key rename (rf2-mn0qc)."
    (rf/reg-event :path-nest/init (fn [{:keys [db]} _] {:db {:a {:b {:c 7} :sib :keep}}}))
    (rf/reg-event :path-nest/inc
                     ;; The outer `path` focuses to {:b {:c 7} :sib :keep};
                     ;; the inner `path` focuses to 7. The handler returns 8.
                     {:interceptors [[:rf.interceptor/path [:a]]
                                     [:rf.interceptor/path [:b :c]]]}
                     (fn [{slice :db} _] {:db (inc slice)}))
    (rf/dispatch-sync [:path-nest/init])
    (rf/dispatch-sync [:path-nest/inc])
    (let [db (rf/app-db-value :rf/default)]
      (is (= 8 (get-in db [:a :b :c]))
          "inner+outer path splice the inner slice back through both levels")
      (is (= :keep (get-in db [:a :sib]))
          "siblings under the outer focus are preserved"))))

;; Per rf2-rwlj2 / Round-2 audit finding CQ-R2.5: the `:after` arm of
;; `path` only writes `:effects :db` when the handler actually produced
;; one. When the handler emits no `:db` effect (an `:fx`-only handler
;; return, or any handler that returns `{}`), the path
;; interceptor passes through cleanly — no spurious `:db` effect, no
;; allocation. Pins the "no `:db` effect = no DB write" contract.
;;
;; Source: std_interceptors.cljc — :after arm gates the splice-back on
;; `(contains? (:effects ctx) :db)`.

(deftest path-interceptor-passes-through-when-handler-emits-no-db
  (testing "(path ...) does NOT synthesise a :db effect when the handler
            emits none — `:fx`-only handlers stay `:fx`-only through
            the path interceptor"
    (let [final-ctx (atom nil)]
      (rf/reg-event :path-noop/init
                       (fn [{:keys [db]} _] {:db {:foo {:bar 10} :other :preserved}}))
      ;; A reg-event handler that emits ONLY `:fx`, no `:db`.
      ;; Pre-fix the path interceptor would have spliced the unchanged
      ;; slice back into `:effects :db` regardless — producing a
      ;; spurious DB write the handler never asked for.
      ;; Sandwich-spy that captures the effects map produced by the
      ;; handler-side chain — i.e. AFTER the handler ran and BEFORE the
      ;; path interceptor's :after re-runs.
      (rf/reg-interceptor* :path-noop/spy
                           {:after (fn [ctx]
                                     (reset! final-ctx ctx)
                                     ctx)})
      (rf/reg-event :path-noop/fx-only
                       {:interceptors [[:rf.interceptor/path [:foo :bar]]
                                       :path-noop/spy]}
                       (fn [_cofx _ev]
                         ;; No `:db` in the returned effect map.
                         {:fx []}))
      (rf/dispatch-sync [:path-noop/init])
      (rf/dispatch-sync [:path-noop/fx-only])

      ;; The spy's view sits between the handler and `path`'s :after —
      ;; if the handler emitted no `:db`, neither does the chain at
      ;; this point.
      (is (not (contains? (:effects @final-ctx) :db))
          "handler that returned no :db produced no :db effect mid-chain")

      ;; Final app-db value is unchanged — the handler asked for
      ;; nothing and got nothing.
      (let [db (rf/app-db-value :rf/default)]
        (is (= 10 (get-in db [:foo :bar]))
            "slice value is preserved when handler emits no :db effect")
        (is (= :preserved (:other db))
            "the path didn't touch the rest of app-db either")))))

(deftest path-interceptor-still-splices-back-when-handler-emits-db
  (testing "(path ...) still splices when the handler DOES emit :db —
            rf2-rwlj2 fix preserves the happy path"
    (rf/reg-event :path-emit/init (fn [{:keys [db]} _] {:db {:foo {:bar 10}}}))
    (rf/reg-event :path-emit/inc
                     {:interceptors [[:rf.interceptor/path [:foo :bar]]]}
                     (fn [{:keys [db]} _]
                       ;; Explicitly emit `:db` (the slice + 1).
                       {:db (inc db)}))
    (rf/dispatch-sync [:path-emit/init])
    (rf/dispatch-sync [:path-emit/inc])
    (is (= 11 (get-in (rf/app-db-value :rf/default) [:foo :bar]))
        "handler that emits :db still gets its slice spliced back")))

;; Per rf2-mas2y: when an EARLIER interceptor's `:before` throws,
;; `execute-chain` short-circuits all downstream `:before` stages
;; (including the path interceptor's) yet still runs every `:after` in reverse
;; (teardown contract). The path interceptor's `:before` therefore never pushed
;; onto `:rf.interceptor.path/stack`, so its `:after` must NOT call `(pop [])` —
;; that would throw a SPURIOUS second error masking the original cause. The
;; sibling `unwrap` interceptor is already guarded for this case (it no-ops when
;; `:rf/unwrap-stash` is absent); the path interceptor mirrors that guard.
;;
;; rf2-dgtdna: the legacy `rf/path` value constructor is removed (EP-0022); the
;; surviving path surface is `standard-path-interceptor` (the
;; `[:rf.interceptor/path …]` factory's consumer), so this raw-chain test builds
;; that interceptor value directly. Source: std_interceptors.cljc —
;; `standard-path-interceptor`'s `:after` no-ops when
;; `:rf.interceptor.path/stack` is empty.

(deftest path-interceptor-after-no-ops-when-before-short-circuited
  (testing "the path interceptor's :after does not throw and preserves the
            original error when an upstream interceptor's :before throws first"
    (let [boom    (interceptor/->interceptor*
                    :id     :path-short/boom
                    :before (fn [_ctx]
                              (throw (ex-info "before blew up"
                                              {:src :path-short/boom}))))
          ;; Order: boom (throws in :before) → path interceptor (its :before is
          ;; short-circuited, but its :after still runs in reverse).
          chain   [boom (std-interceptors/standard-path-interceptor [:foo :bar])]
          final   (interceptor/execute-chain
                    chain
                    {:coeffects {:db {:foo {:bar 10}}} :effects {}})
          errs    (:rf/interceptor-errors final)]
      (is (= :path-short/boom (get-in final [:rf/interceptor-error :id]))
          "the original :before failure is the recorded FIRST error")
      (is (= :before (get-in final [:rf/interceptor-error :phase]))
          "the FIRST error is the upstream :before throw")
      (is (= 1 (count errs))
          "exactly one error — the path interceptor's :after did NOT synthesise
           a spurious second error from (pop [])")
      (is (= [:path-short/boom] (mapv :id errs))
          "no error attributed to the path interceptor — its :after no-opped
           cleanly")
      (is (not (contains? (:effects final) :db))
          "the path interceptor's no-op :after produced no :db effect when its
           :before never ran"))))

;; ---- unwrap (PROJECT-LOCAL, EP-0022 / rf2-3qeu38) -------------------------
;;
;; EP-0022 removed the framework `unwrap-interceptor` VALUE: the framework
;; ships no standard unwrap; the canonical spelling is handler-payload
;; destructuring, or — for genuine chain-wide reshaping — a PROJECT-registered
;; `:app/unwrap` interceptor with the project's own `:before` / `:after`.
;; These deftests register exactly such a project-local interceptor and
;; reference it by id, so they pin the chain mechanics (event-coeffect rewrite
;; + restore, bad-shape diagnostic) WITHOUT depending on a framework-owned
;; value. The diagnostic category is project-owned
;; (`:test.app/unwrap-bad-event-shape`) — the framework
;; `:rf.error/unwrap-bad-event-shape` category was removed with the value
;; (Spec 009 §Error event catalogue).

(def ^:private project-unwrap-interceptor
  "A PROJECT-LOCAL unwrap interceptor (NOT a framework value). Asserts the
  event is the canonical `[<id> <payload-map>]` shape (M-19), replaces the
  `:event` coeffect with the payload map, and stashes the original so `:after`
  can restore it for downstream consumers. On a bad shape it emits the
  project-owned `:test.app/unwrap-bad-event-shape` diagnostic and returns ctx
  unchanged. This is the shape an application registers as `:app/unwrap`."
  (interceptor/->interceptor*
    :id    :app/unwrap
    :before
    (fn [ctx]
      (let [event (interceptor/get-coeffect ctx :event)]
        (if-not (and (vector? event)
                     (= 2 (count event))
                     (map? (second event)))
          (do (trace/emit-error! :test.app/unwrap-bad-event-shape
                                 {:event event
                                  :expected "[event-id payload-map]"
                                  :recovery :no-recovery})
              ctx)
          (-> ctx
              (assoc :rf/unwrap-stash event)
              (interceptor/assoc-coeffect :event (second event))))))
    :after
    (fn [ctx]
      (if-let [original (:rf/unwrap-stash ctx)]
        (-> ctx
            (dissoc :rf/unwrap-stash)
            (interceptor/assoc-coeffect :event original))
        ctx))))

(deftest project-unwrap-rewrites-event-coeffect
  (testing "a project-registered :app/unwrap replaces the :event coeffect with
            the payload map from the canonical [event-id payload-map] shape."
    (let [seen-event (atom ::not-set)]
      (rf/reg-interceptor* :app/unwrap project-unwrap-interceptor)
      (rf/reg-event :unwrap-test/consume
                       {:interceptors [:app/unwrap]}
                       ;; With unwrap, the second arg is the payload map
                       ;; itself — not the [id payload] vector.
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      (rf/dispatch-sync [:unwrap-test/consume {:k "v" :n 7}])
      (is (= {:k "v" :n 7} @seen-event)
          "handler receives the payload map directly")
      (is (map? @seen-event)
          "the unwrapped event arg is a map, not a vector"))))

;; The negative path: when the event does NOT match the canonical
;; [event-id payload-map] shape, the project interceptor emits its
;; bad-shape diagnostic and returns ctx unchanged (no recovery — the handler
;; still runs, but it sees the original event vector). The trace's diagnostic
;; tags carry `:expected` / `:recovery` so tooling can surface the misuse.

(deftest project-unwrap-bad-event-shape-traces-and-keeps-event-unchanged
  (testing "a project :app/unwrap with a non-[id payload-map] event emits
            :test.app/unwrap-bad-event-shape and leaves the :event
            coeffect unchanged"
    (let [traces     (atom [])
          seen-event (atom ::not-set)]
      (rf/register-listener! :trace ::unwrap-bad (fn [ev] (swap! traces conj ev)))
      (rf/reg-interceptor* :app/unwrap project-unwrap-interceptor)
      (rf/reg-event :unwrap-bad-test/consume
                       {:interceptors [:app/unwrap]}
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      ;; A malformed event: second slot is :not-a-map (a keyword, not a map).
      (rf/dispatch-sync [:unwrap-bad-test/consume :not-a-map])
      (rf/unregister-listener! :trace ::unwrap-bad)

      ;; The handler still ran — the bad path is a trace-and-continue,
      ;; not a throw — but it saw the ORIGINAL event vector (ctx unchanged),
      ;; not the payload.
      (is (= [:unwrap-bad-test/consume :not-a-map] @seen-event)
          "handler runs with the original event vector when the shape check fails")

      ;; The structured trace fired.
      (let [bad-shape (filterv #(= :test.app/unwrap-bad-event-shape (:operation %))
                               @traces)]
        (is (= 1 (count bad-shape))
            "exactly one :test.app/unwrap-bad-event-shape trace was emitted")
        (let [ev (first bad-shape)]
          (is (= :error (:op-type ev)))
          (is (= [:unwrap-bad-test/consume :not-a-map]
                 (get-in ev [:tags :event]))
              ":event tag carries the offending event vector")
          (is (= "[event-id payload-map]"
                 (get-in ev [:tags :expected]))
              ":expected describes the canonical envelope shape")
          (is (= :no-recovery (:recovery ev))
              ":recovery is :no-recovery — the bad path is documented as not-recoverable")))))

  (testing "a project :app/unwrap with a 3-element vector (correct id, wrong arity) traces"
    (let [traces     (atom [])
          seen-event (atom ::not-set)]
      (rf/register-listener! :trace ::unwrap-arity (fn [ev] (swap! traces conj ev)))
      (rf/reg-interceptor* :app/unwrap project-unwrap-interceptor)
      (rf/reg-event :unwrap-bad-test/arity
                       {:interceptors [:app/unwrap]}
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      ;; Wrong arity — three elements instead of two.
      (rf/dispatch-sync [:unwrap-bad-test/arity {:ok :map} :extra])
      (rf/unregister-listener! :trace ::unwrap-arity)
      (is (some #(= :test.app/unwrap-bad-event-shape (:operation %)) @traces)
          ":test.app/unwrap-bad-event-shape fires for arity mismatch too")
      (is (= [:unwrap-bad-test/arity {:ok :map} :extra] @seen-event)
          "handler sees the original (still-wrongly-shaped) event")))

  (testing "a project :app/unwrap on the happy [id payload-map] path does NOT emit the bad-shape trace"
    ;; Sanity: confirm the trace is silent on a well-shaped event so the
    ;; coverage above is genuinely catching the negative branch.
    (let [traces (atom [])]
      (rf/register-listener! :trace ::unwrap-ok (fn [ev] (swap! traces conj ev)))
      (rf/reg-interceptor* :app/unwrap project-unwrap-interceptor)
      (rf/reg-event :unwrap-bad-test/ok
                       {:interceptors [:app/unwrap]}
                       (fn [_ _] {}))
      (rf/dispatch-sync [:unwrap-bad-test/ok {:k 1}])
      (rf/unregister-listener! :trace ::unwrap-ok)
      (is (empty? (filter #(= :test.app/unwrap-bad-event-shape (:operation %))
                          @traces))
          "no bad-shape trace on the canonical [id payload-map] event"))))

;; ---- cofx delivery (EP-0017: declared-only, value-returning) --------------
;;
;; `inject-cofx` is removed (EP-0017 slice A.3). Coeffect delivery is context
;; assembly, not a chain member: a handler declares `:rf.cofx/requires` and the
;; value-returning supplier's result arrives flat. The full cofx behaviour /
;; error family is pinned in `re-frame.cofx-test`; here we keep a thin
;; smoke that the delivery threads into a handler alongside the standard
;; `:db` / `:event` coeffects.

(deftest cofx-declared-delivery
  (testing "a declared value-returning cofx is delivered flat under its id,
            alongside the standard :db / :event coeffects (EP-0017 §5)"
    (rf/reg-cofx :now (fn [] 1234567890))
    (let [seen-cofx (atom nil)]
      (rf/reg-event :cofx-test/read-now
                       {:rf.cofx/requires [:now]}
                       (fn [cofx _event]
                         (reset! seen-cofx cofx)
                         {}))
      (rf/dispatch-sync [:cofx-test/read-now])
      (is (= 1234567890 (:now @seen-cofx))
          "the :now value the supplier returned is delivered flat to the handler")
      (is (contains? @seen-cofx :db)
          "standard :db coeffect is present alongside the declared :now")
      (is (contains? @seen-cofx :event)
          "standard :event coeffect is present")))

  (testing "a `[id arg]` declaration passes the arg to the supplier's 1-arity"
    (rf/reg-cofx :greeting (fn [greeting] greeting))
    (let [seen (atom nil)]
      (rf/reg-event :cofx-test/use-greeting
                       {:rf.cofx/requires [[:greeting "hello"]]}
                       (fn [cofx _]
                         (reset! seen (:greeting cofx))
                         {}))
      (rf/dispatch-sync [:cofx-test/use-greeting])
      (is (= "hello" @seen)
          "the parameterized declaration threads the arg into the supplier"))))

;; ---- ->interceptor primitive ----------------------------------------------

(deftest make-interceptor-via-primitive
  (testing "->interceptor builds a custom interceptor whose :before runs in
            chain order and whose :after runs in reverse — both can mutate
            the context."
    (let [trail (atom [])
          ;; Three custom interceptors, named A / B / C, that each push a
          ;; tagged entry into `trail` from both their :before and :after
          ;; slots. The handler itself pushes :handler. Since EP-0022
          ;; (chains are reference-only) `mk` REGISTERS the interceptor under
          ;; its tag id and RETURNS the id keyword for the chain to reference.
          mk (fn [tag]
               (rf/reg-interceptor*
                 tag
                 {:before (fn [ctx]
                            (swap! trail conj [:before tag])
                            (assoc ctx tag :touched))
                  :after  (fn [ctx]
                            (swap! trail conj [:after tag])
                            ctx)})
               tag)]
      (rf/reg-event :primitive/run
                       {:interceptors [(mk :a) (mk :b) (mk :c)]}
                       (fn [_ _]
                         (swap! trail conj :handler)
                         {}))
      (rf/dispatch-sync [:primitive/run])
      (is (= [[:before :a]
              [:before :b]
              [:before :c]
              :handler
              [:after :c]
              [:after :b]
              [:after :a]]
             @trail)
          ":before runs in declaration order, :after in reverse"))))

;; ---- chain composition ----------------------------------------------------
;;
;; Driven directly through interceptor/execute-chain so the test pins the
;; chain runtime's contract without leaning on the dispatch path. This is
;; the level the bead's "chain composition" deliverable refers to.

(deftest chain-composition
  (testing "execute-chain runs every :before in order then every :after in
            reverse; the captured order matches the standard pattern."
    (let [trail (atom [])
          mk    (fn [tag]
                  (interceptor/->interceptor*
                    :id     tag
                    :before (fn [ctx]
                              (swap! trail conj [:before tag])
                              (update ctx :seen (fnil conj []) tag))
                    :after  (fn [ctx]
                              (swap! trail conj [:after tag])
                              ctx)))
          handler (interceptor/->interceptor*
                    :id :handler
                    :before (fn [ctx]
                              (swap! trail conj :handler)
                              ctx))
          chain   [(mk :a) (mk :b) (mk :c) handler]
          final   (interceptor/execute-chain chain {:coeffects {} :effects {}})]
      (is (= [[:before :a] [:before :b] [:before :c]
              :handler
              [:after :c] [:after :b] [:after :a]]
             @trail)
          "before-in-order then after-in-reverse — the handler is itself
           an interceptor whose :after slot is nil, so it contributes
           nothing on the way back out")
      (is (= [:a :b :c] (:seen final))
          "each :before stage saw the prior :before's mutations")))

  (testing "an :after that throws does NOT prevent the handler from completing,
            but IS captured on the context as :rf/interceptor-error.

            The downstream chain runtime currently records the error and lets
            subsequent :after stages still run (they receive the error-bearing
            context). This pins THAT contract — the handler completed (we see
            :handler-ran), the throwing :after's id is recorded, and we DID
            see at least one upstream :after run before the throw."
    (let [trail (atom [])
          ran-handler? (atom false)
          mk-good (fn [tag]
                    (interceptor/->interceptor*
                      :id     tag
                      :before (fn [ctx]
                                (swap! trail conj [:before tag])
                                ctx)
                      :after  (fn [ctx]
                                (swap! trail conj [:after tag])
                                ctx)))
          mk-bad-after (fn [tag]
                         (interceptor/->interceptor*
                           :id     tag
                           :before (fn [ctx]
                                     (swap! trail conj [:before tag])
                                     ctx)
                           :after  (fn [_ctx]
                                     (swap! trail conj [:after tag])
                                     (throw (ex-info "after blew up"
                                                     {:tag tag})))))
          handler (interceptor/->interceptor*
                    :id :handler
                    :before (fn [ctx]
                              (reset! ran-handler? true)
                              (swap! trail conj :handler)
                              ctx))
          ;; Order in declaration:  a (good) → boom (bad-after) → c (good) → handler
          ;; :after order (reverse): handler → c → boom (throws) → a
          chain   [(mk-good :a) (mk-bad-after :boom) (mk-good :c) handler]
          final   (interceptor/execute-chain chain {:coeffects {} :effects {}})]
      (is @ran-handler?
          "handler completed even though a downstream :after throws")
      (is (= :after (get-in final [:rf/interceptor-error :phase]))
          "the captured error remembers it happened in the :after phase")
      (is (= :boom (get-in final [:rf/interceptor-error :id]))
          "the captured error names the failing interceptor")
      (is (some #(= [:after :c] %) @trail)
          ":after stages downstream of the failing one (in reverse order:
           those reached BEFORE the throw — i.e. :handler's and :c's :after)
           did execute")))

  (testing "multiple interceptors failing record ALL errors but
            :rf/interceptor-error remains the FIRST.

            Per rf2-mm2a: prior to the fix, the second `assoc` would
            clobber the first — the original cause was lost. The fix
            keeps `:rf/interceptor-error` pinned to the FIRST error
            (existing tracing reads the root cause) and appends every
            error in occurrence order to `:rf/interceptor-errors`."
    (let [;; A :before that throws (interceptor :before-bad), then an
          ;; :after that throws on the way back out (interceptor :after-bad).
          ;; The short-circuit means :before-bad's :before fires, but
          ;; subsequent :before stages are skipped. All :after stages
          ;; run (teardown contract), so :after-bad's :after will fire.
          before-bad (interceptor/->interceptor*
                       :id :before-bad
                       :before (fn [_ctx]
                                 (throw (ex-info "before blew up"
                                                 {:src :before-bad}))))
          after-bad  (interceptor/->interceptor*
                       :id :after-bad
                       :after  (fn [_ctx]
                                 (throw (ex-info "after blew up"
                                                 {:src :after-bad}))))
          ;; Order: after-bad first so its :after runs LAST (reverse order)
          ;; AFTER before-bad's :before failure has already stamped the
          ;; context. That way we exercise the "later error doesn't
          ;; overwrite earlier" guarantee.
          chain [after-bad before-bad]
          final (interceptor/execute-chain chain {:coeffects {} :effects {}})
          errs  (:rf/interceptor-errors final)
          first-err (:rf/interceptor-error final)]
      (is (vector? errs)
          ":rf/interceptor-errors is a vector")
      (is (= 2 (count errs))
          "both errors were recorded — neither was overwritten")
      (is (= :before (:phase first-err))
          ":rf/interceptor-error retains the FIRST error (the :before failure)")
      (is (= :before-bad (:id first-err))
          ":rf/interceptor-error identifies the FIRST failing interceptor")
      (is (= [:before :after] (mapv :phase errs))
          "errors appear in the vector in occurrence order")
      (is (= [:before-bad :after-bad] (mapv :id errs))
          "both failing interceptor ids are preserved in order")
      (is (= first-err (first errs))
          ":rf/interceptor-error equals the first element of :rf/interceptor-errors")))

  (testing "a failing :before short-circuits subsequent :before stages
            but :after stages still run (teardown contract).

            Per rf2-mm2a: with the short-circuit, downstream :before
            stages don't see partial context. The :after pass is
            unconditional so interceptors can clean up resources their
            :before claimed."
    (let [trail (atom [])
          mk-good (fn [tag]
                    (interceptor/->interceptor*
                      :id     tag
                      :before (fn [ctx]
                                (swap! trail conj [:before tag])
                                ctx)
                      :after  (fn [ctx]
                                (swap! trail conj [:after tag])
                                ctx)))
          boom    (interceptor/->interceptor*
                    :id     :boom
                    :before (fn [_ctx]
                              (swap! trail conj [:before :boom])
                              (throw (ex-info "before blew up"
                                              {:src :boom})))
                    :after  (fn [ctx]
                              (swap! trail conj [:after :boom])
                              ctx))
          ;; a's :before runs, boom's :before throws, c's :before MUST
          ;; be skipped (short-circuit). All :after stages run.
          chain   [(mk-good :a) boom (mk-good :c)]
          final   (interceptor/execute-chain chain {:coeffects {} :effects {}})]
      (is (= [[:before :a]
              [:before :boom]
              ;; NO [:before :c] — short-circuited.
              [:after :c]
              [:after :boom]
              [:after :a]]
             @trail)
          "subsequent :before stages skipped after the failure;
           every :after still ran in reverse order")
      (is (= :boom (get-in final [:rf/interceptor-error :id]))
          "the original :before failure is recorded")
      (is (= 1 (count (:rf/interceptor-errors final)))
          "only the one (uncaught) :before error is in the vector —
           skipped :before stages don't synthesize errors"))))

;; ---- context-plumbing helpers (rf2-ynjts.1) -------------------------------
;;
;; `get-coeffect` / `assoc-coeffect` / `update-coeffect` / `get-effect` /
;; `assoc-effect` are the public read/write substrate every custom
;; interceptor uses (re-exported as `rf/get-coeffect` etc. per
;; spec/API.md §Interceptors). The chain / dispatch tests above exercise
;; them obliquely, but nothing pinned their ARITY contracts directly —
;; in particular the 3-arity `not-found` forms of the two readers and
;; `update-coeffect`'s trailing-args form had no coverage. These are
;; pure fns over the context map; pinning them here means an arity
;; regression surfaces at the source rather than via a far-off cascade
;; assertion. Driven directly against a literal context map — no runtime.

(deftest get-coeffect-arities
  (let [ctx {:coeffects {:db {:n 1} :event [:e 7] :now 42} :effects {}}]
    (testing "1-arity returns the whole :coeffects map"
      (is (= {:db {:n 1} :event [:e 7] :now 42}
             (interceptor/get-coeffect ctx))))
    (testing "2-arity returns the value at k, nil when absent"
      (is (= {:n 1} (interceptor/get-coeffect ctx :db)))
      (is (= [:e 7] (interceptor/get-coeffect ctx :event)))
      (is (nil? (interceptor/get-coeffect ctx :missing))
          "absent key yields nil under the 2-arity form"))
    (testing "3-arity returns not-found when the key is absent, the value when present"
      (is (= ::sentinel (interceptor/get-coeffect ctx :missing ::sentinel))
          "absent key yields the not-found arg")
      (is (= {:n 1} (interceptor/get-coeffect ctx :db ::sentinel))
          "present key wins over not-found"))
    (testing "3-arity distinguishes a stored nil from absence"
      (let [ctx-nil {:coeffects {:maybe nil} :effects {}}]
        (is (nil? (interceptor/get-coeffect ctx-nil :maybe ::sentinel))
            "a key present with value nil returns nil, NOT the not-found arg")
        (is (= ::sentinel (interceptor/get-coeffect ctx-nil :absent ::sentinel))
            "a genuinely absent key still returns the not-found arg")))))

(deftest get-effect-arities
  (let [ctx {:coeffects {} :effects {:db {:n 2} :fx [[:do-thing]]}}]
    (testing "1-arity returns the whole :effects map"
      (is (= {:db {:n 2} :fx [[:do-thing]]} (interceptor/get-effect ctx))))
    (testing "2-arity returns the value at k, nil when absent"
      (is (= {:n 2} (interceptor/get-effect ctx :db)))
      (is (= [[:do-thing]] (interceptor/get-effect ctx :fx)))
      (is (nil? (interceptor/get-effect ctx :missing))))
    (testing "3-arity returns not-found when absent"
      (is (= ::none (interceptor/get-effect ctx :missing ::none)))
      (is (= {:n 2} (interceptor/get-effect ctx :db ::none))))
    (testing "3-arity distinguishes a stored nil from absence"
      (let [ctx-nil {:coeffects {} :effects {:db nil}}]
        (is (nil? (interceptor/get-effect ctx-nil :db ::none)))
        (is (= ::none (interceptor/get-effect ctx-nil :fx ::none)))))))

(deftest assoc-coeffect-writes-and-is-readable
  (testing "assoc-coeffect sets k in :coeffects and returns the updated ctx;
            the round-trip is observable via get-coeffect"
    (let [ctx  {:coeffects {:db {:n 1}} :effects {}}
          ctx' (interceptor/assoc-coeffect ctx :now 99)]
      (is (= 99 (interceptor/get-coeffect ctx' :now))
          "the assoc'd value reads back through get-coeffect")
      (is (= {:n 1} (interceptor/get-coeffect ctx' :db))
          "sibling coeffects are preserved")
      (is (= {} (:effects ctx'))
          ":effects is untouched by a coeffect write")
      (is (nil? (interceptor/get-coeffect ctx :now))
          "the original ctx is unchanged (persistent — no in-place mutation)"))))

(deftest assoc-effect-writes-and-is-readable
  (testing "assoc-effect sets k in :effects and returns the updated ctx"
    (let [ctx  {:coeffects {:db {:n 1}} :effects {}}
          ctx' (interceptor/assoc-effect ctx :db {:n 2})]
      (is (= {:n 2} (interceptor/get-effect ctx' :db))
          "the assoc'd effect reads back through get-effect")
      (is (= {:n 1} (interceptor/get-coeffect ctx' :db))
          ":coeffects :db is independent of :effects :db")
      (is (= {} (:effects ctx))
          "the original ctx's :effects is unchanged (persistent)"))))

(deftest update-coeffect-applies-fn-with-trailing-args
  (testing "update-coeffect applies f to the value at k, threading trailing args"
    (let [ctx {:coeffects {:n 10} :effects {}}]
      (is (= 11 (interceptor/get-coeffect
                  (interceptor/update-coeffect ctx :n inc) :n))
          "no-arg fn form: inc applied to the value at :n")
      (is (= 13 (interceptor/get-coeffect
                  (interceptor/update-coeffect ctx :n + 3) :n))
          "single trailing arg threaded after the current value")
      (is (= 16 (interceptor/get-coeffect
                  (interceptor/update-coeffect ctx :n + 3 2 1) :n))
          "multiple trailing args threaded in order")))
  (testing "update-coeffect on an absent key applies f to nil"
    ;; A fn that records the arg it was handed for the absent key, then
    ;; returns a deterministic value. Confirms f is invoked with nil
    ;; (clojure.core/update-in semantics) — not skipped.
    (let [seen-arg (atom ::unset)
          result   (interceptor/update-coeffect
                     {:coeffects {} :effects {}}
                     :missing
                     (fn [v] (reset! seen-arg v) :computed))]
      (is (nil? @seen-arg)
          "f saw nil for the absent key (matches clojure.core/update-in semantics)")
      (is (= :computed (interceptor/get-coeffect result :missing))
          "f's return value is written back at the previously-absent key")))
  (testing "update-coeffect preserves siblings and leaves :effects untouched"
    (let [ctx  {:coeffects {:n 10 :keep :me} :effects {:db {}}}
          ctx' (interceptor/update-coeffect ctx :n inc)]
      (is (= :me (interceptor/get-coeffect ctx' :keep))
          "sibling coeffects preserved")
      (is (= {:db {}} (:effects ctx'))
          ":effects untouched"))))

;; ---- pipeline-exception attribution (rf2-mszrz) ---------------------------
;;
;; The :before/:after chain runs three distinct kinds of component — the
;; coeffect injectors, the user interceptors, and the handler-wrapping
;; interceptor (the terminal :before). Before rf2-mszrz a throw from ANY of
;; them collapsed into a single :rf.error/handler-exception attributed to the
;; event. These tests pin the post-fix contract: each kind emits its own
;; category with :failing-id = the true failing component, mirroring the
;; already-distinct flow (:rf.error/flow-eval-exception) and fx
;; (:rf.error/fx-handler-exception) categories.

(defn- capture-error-traces
  "Dispatch `event` and return the vector of emitted :op-type :error trace
  events. The handler is wired to settle :ok (failure rides :trace-events
  per the runtime's no-abort contract) — we only read the trace stream."
  [event]
  (let [traces (atom [])]
    (rf/register-listener! :trace ::mszrz (fn [ev] (when (= :error (:op-type ev))
                                              (swap! traces conj ev))))
    (rf/dispatch-sync event)
    (rf/unregister-listener! :trace ::mszrz)
    @traces))

(deftest pipeline-exception-attributed-to-true-component
  (testing "event HANDLER throw → :rf.error/handler-exception (failing-id = event)"
    (rf/reg-event :mszrz/handler-boom
                     (fn [{:keys [db]} _] {:db (throw (ex-info "handler blew up" {}))}))
    (let [errs (capture-error-traces [:mszrz/handler-boom])
          hx   (filterv #(= :rf.error/handler-exception (:operation %)) errs)]
      (is (= 1 (count hx)) "exactly one handler-exception")
      (let [ev (first hx)]
        (is (= :mszrz/handler-boom (get-in ev [:tags :failing-id]))
            ":failing-id is the event id")
        (is (= :mszrz/handler-boom (get-in ev [:tags :handler-id]))
            ":handler-id is retained for the genuine handler case")
        (is (= "Event handler threw." (get-in ev [:tags :reason]))))
      (is (empty? (filterv #(#{:rf.error/coeffect-exception
                               :rf.error/interceptor-exception}
                             (:operation %)) errs))
          "no coeffect / interceptor category for a pure handler throw")))

  ;; The former "coeffect INJECTION throw → :rf.error/coeffect-exception"
  ;; sub-test is retired with `inject-cofx` (EP-0017 slice A.3): coeffect
  ;; delivery is now context assembly, not a `:before` chain member, so a
  ;; throwing supplier no longer rides the interceptor classification path.

  (testing "user interceptor :BEFORE throw → :rf.error/interceptor-exception (failing-id = interceptor, phase :before)"
    (rf/reg-interceptor* :mszrz/before-icpt
                         {:before (fn [_] (throw (ex-info "before blew up" {})))})
    (rf/reg-event :mszrz/before-boom
                     {:interceptors [:mszrz/before-icpt]}
                     (fn [{:keys [db]} _] {:db db}))
    (let [errs (capture-error-traces [:mszrz/before-boom])
          ix   (filterv #(= :rf.error/interceptor-exception (:operation %)) errs)]
      (is (= 1 (count ix)) "exactly one interceptor-exception")
      (let [ev (first ix)]
        (is (= :mszrz/before-icpt (get-in ev [:tags :failing-id]))
            ":failing-id is the interceptor id")
        (is (= :before (get-in ev [:tags :phase]))
            ":phase is :before"))
      (is (empty? (filterv #(= :rf.error/handler-exception (:operation %)) errs))
          "an interceptor :before throw does NOT report as handler-exception")))

  (testing "user interceptor :AFTER throw → :rf.error/interceptor-exception (failing-id = interceptor, phase :after)"
    ;; The :after chain runs after the handler; an :after throw must
    ;; attribute to the interceptor (phase :after), not the handler — the
    ;; collapse rf2-mszrz explicitly fixes for the :after side too.
    (rf/reg-interceptor* :mszrz/after-icpt
                         {:after (fn [_] (throw (ex-info "after blew up" {})))})
    (rf/reg-event :mszrz/after-boom
                     {:interceptors [:mszrz/after-icpt]}
                     (fn [{:keys [db]} _] {:db db}))
    (let [errs (capture-error-traces [:mszrz/after-boom])
          ix   (filterv #(= :rf.error/interceptor-exception (:operation %)) errs)]
      (is (= 1 (count ix)) "exactly one interceptor-exception")
      (let [ev (first ix)]
        (is (= :mszrz/after-icpt (get-in ev [:tags :failing-id]))
            ":failing-id is the interceptor id")
        (is (= :after (get-in ev [:tags :phase]))
            ":phase is :after — NOT collapsed into the handler"))
      (is (empty? (filterv #(= :rf.error/handler-exception (:operation %)) errs))
          "an interceptor :after throw does NOT report as handler-exception"))))

;; ---- macro-path source-coord capture (rf2-siheh) --------------------------
;;
;; The `->interceptor` MACRO captures the definition-site coord from
;; `(meta &form)` (riding the rf2-wvsxg absolutise path, exactly like the
;; reg-* macros) and bakes `:source-coord` into the interceptor map. When
;; that interceptor throws, the coord rides the error-record (interceptor/
;; error-record) → the router threads it onto the
;; `:rf.error/interceptor-exception` trace's `:source-coord` tag, so the
;; Xray Epoch INTERCEPTOR row can render a jump-to-source chip (parity with
;; EVENT HANDLER / SUBSCRIPTIONS / VIEWS). The `->interceptor*` FN path
;; captures no coord — there is no syntactic call site to attribute.

(deftest macro-interceptor-carries-absolutised-source-coord
  (testing "a macro-defined interceptor map carries an absolutised
            :source-coord (:ns / :file / :line); :file is an absolute
            on-disk path per the rf2-wvsxg absolutise path"
    (let [icpt (rf/->interceptor
                 :id     :siheh/probe
                 :before identity)
          {:keys [ns file line] :as coord} (:source-coord icpt)]
      (is (map? coord)
          "the macro bakes a :source-coord map into the interceptor")
      (is (= 're-frame.interceptor-test ns)
          ":ns is the metadata-free consumer namespace symbol")
      (is (integer? line) ":line is the macro call-site line")
      (is (string? file) ":file is a string")
      ;; rf2-wvsxg — the macro feeds the picked :file through
      ;; absolutise-file at expansion time, so the coord ships an
      ;; absolute on-disk path (here the classpath resolved the test
      ;; source under the core artefact's test root).
      (is (re-find #"interceptor_test\.clj$" file)
          ":file resolves to this test source file")
      (is (re-find #"^(?:/|[A-Za-z]:)" file)
          ":file is absolute (leading slash or drive letter) — absolutised")))

  (testing "the ->interceptor* fn path captures NO :source-coord (HoF /
            programmatic — no syntactic call site to attribute)"
    (let [icpt (interceptor/->interceptor* :id :siheh/fn-probe :before identity)]
      (is (nil? (:source-coord icpt))
          "fn-built interceptor carries no coord"))))

(deftest macro-interceptor-source-coord-rides-the-exception-trace
  (testing "a throwing macro-defined interceptor threads its :source-coord
            onto the :rf.error/interceptor-exception trace (the slot the
            Xray Epoch INTERCEPTOR row's jump-to-source chip reads)"
    ;; EP-0022 reference-only: register the MACRO-BUILT interceptor VALUE as
    ;; the descriptor (the registration boundary accepts an interceptor value),
    ;; so the :source-coord the macro captured from `(meta &form)` rides
    ;; through resolution onto the trace. Then reference it by id.
    (rf/reg-interceptor* :siheh/before-icpt
                         (rf/->interceptor
                           :id     :siheh/before-icpt
                           :before (fn [_] (throw (ex-info "before blew up" {})))))
    (rf/reg-event :siheh/before-boom
                     {:interceptors [:siheh/before-icpt]}
                     (fn [{:keys [db]} _] {:db db}))
    (let [errs (capture-error-traces [:siheh/before-boom])
          ix   (filterv #(= :rf.error/interceptor-exception (:operation %)) errs)]
      (is (= 1 (count ix)) "exactly one interceptor-exception")
      (let [coord (get-in (first ix) [:tags :source-coord])]
        (is (map? coord)
            "the trace carries the interceptor's :source-coord tag")
        (is (re-find #"interceptor_test\.clj$" (:file coord))
            ":file points at this test source")
        (is (integer? (:line coord)) ":line is captured"))))

  (testing "a throwing ->interceptor*-built interceptor threads NO
            :source-coord tag (degrades to plain text in the panel)"
    ;; The fn-built (`->interceptor*`) value carries NO :source-coord;
    ;; register it verbatim + reference it so the "no coord" intent rides
    ;; through resolution.
    (rf/reg-interceptor* :siheh/fn-before-icpt
                         (interceptor/->interceptor*
                           :id     :siheh/fn-before-icpt
                           :before (fn [_] (throw (ex-info "fn before blew up" {})))))
    (rf/reg-event :siheh/fn-before-boom
                     {:interceptors [:siheh/fn-before-icpt]}
                     (fn [{:keys [db]} _] {:db db}))
    (let [errs (capture-error-traces [:siheh/fn-before-boom])
          ix   (filterv #(= :rf.error/interceptor-exception (:operation %)) errs)]
      (is (= 1 (count ix)) "exactly one interceptor-exception")
      (is (nil? (get-in (first ix) [:tags :source-coord]))
          "no :source-coord tag — the fn path captured none"))))
