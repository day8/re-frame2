(ns re-frame.interceptor-test
  "Dedicated coverage for the v2 retained interceptor surface (Spec 002).

  v2 trims the v1 interceptor stdlib down to four primitives:
    - path
    - unwrap
    - inject-cofx
    - ->interceptor (and the supporting context plumbing)

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
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  ;; Framework-shipped registrations live in routing.cljc / ssr.cljc /
  ;; machines.cljc and are wiped by clear-all!. None of these tests need
  ;; them, so we skip the require-reload dance — keeps the fixture cheap.
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- path -----------------------------------------------------------------

(deftest path-interceptor
  (testing "(path :foo :bar) scopes a handler to the [:foo :bar] sub-tree:
            the handler sees only the slice as :db, and its returned value
            is spliced back at that path."
    (rf/reg-event-db :path-test/init
                     (fn [_ _]
                       {:foo {:bar  10
                              :keep :untouched}
                        :other :preserved}))
    (rf/reg-event-db :path-test/inc
                     [(rf/path :foo :bar)]
                     ;; The handler's `db` is the SLICE value, not the full
                     ;; app-db. Returning a new slice value writes it back
                     ;; at [:foo :bar].
                     (fn [slice _]
                       (is (= 10 slice)
                           "path interceptor presents the slice as :db")
                       (inc slice)))
    (rf/dispatch-sync [:path-test/init])
    (rf/dispatch-sync [:path-test/inc])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 11 (get-in db [:foo :bar]))
          "the handler's return value was spliced back at [:foo :bar]")
      (is (= :untouched (get-in db [:foo :keep]))
          "siblings under :foo are preserved")
      (is (= :preserved (:other db))
          "keys outside the path are preserved"))))

(deftest path-interceptor-uses-reserved-namespace
  (testing "the path interceptor stashes its db-stack under :rf/path-stack
            (reserved namespace per Spec Conventions §Reserved namespaces).
            Regression for rf2-mn0qc."
    (let [seen-keys (atom nil)]
      ;; A spy interceptor sandwiched between `path` and the handler runs
      ;; AFTER path's :before, so the context it sees must carry the stash
      ;; under :rf/path-stack — not the bare :path-stack.
      (rf/reg-event-db :path-ns/init (fn [_ _] {:foo {:bar 10}}))
      (rf/reg-event-db :path-ns/inc
                       [(rf/path :foo :bar)
                        (interceptor/->interceptor
                         :id     :path-ns/spy
                         :before (fn [ctx]
                                   (reset! seen-keys (set (keys ctx)))
                                   ctx))]
                       ;; reg-event-db handlers receive (slice, event-vec);
                       ;; `inc` is arity-1 so wrap it explicitly to honour
                       ;; the (fn [db event] new-db) contract.
                       (fn [slice _] (inc slice)))
      (rf/dispatch-sync [:path-ns/init])
      (rf/dispatch-sync [:path-ns/inc])
      (is (contains? @seen-keys :rf/path-stack)
          "path interceptor stashes its stack under the reserved :rf/path-stack key")
      (is (not (contains? @seen-keys :path-stack))
          "the bare :path-stack key must NOT appear (reserved-namespace contract)")
      (is (= 11 (get-in (rf/get-frame-db :rf/default) [:foo :bar]))
          "the rename did not break the path interceptor's splice-back behaviour"))))

(deftest path-interceptor-nesting
  (testing "nested (path ...) interceptors compose correctly — the LIFO
            stack semantics of :rf/path-stack mean an inner path's :after
            restores the outer slice and the outer :after splices back into
            the full app-db. Pins the stack semantics independent of the
            slot-key rename (rf2-mn0qc)."
    (rf/reg-event-db :path-nest/init (fn [_ _] {:a {:b {:c 7} :sib :keep}}))
    (rf/reg-event-db :path-nest/inc
                     ;; The outer `path` focuses to {:b {:c 7} :sib :keep};
                     ;; the inner `path` focuses to 7. The handler returns 8.
                     [(rf/path :a)
                      (rf/path :b :c)]
                     (fn [slice _] (inc slice)))
    (rf/dispatch-sync [:path-nest/init])
    (rf/dispatch-sync [:path-nest/inc])
    (let [db (rf/get-frame-db :rf/default)]
      (is (= 8 (get-in db [:a :b :c]))
          "inner+outer path splice the inner slice back through both levels")
      (is (= :keep (get-in db [:a :sib]))
          "siblings under the outer focus are preserved"))))

;; Per rf2-rwlj2 / Round-2 audit finding CQ-R2.5: the `:after` arm of
;; `path` only writes `:effects :db` when the handler actually produced
;; one. When the handler emits no `:db` effect (an `:fx`-only handler
;; under `reg-event-fx`, or any handler that returns `{}`), the path
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
      (rf/reg-event-db :path-noop/init
                       (fn [_ _] {:foo {:bar 10} :other :preserved}))
      ;; A `reg-event-fx` handler that emits ONLY `:fx`, no `:db`.
      ;; Pre-fix the path interceptor would have spliced the unchanged
      ;; slice back into `:effects :db` regardless — producing a
      ;; spurious DB write the handler never asked for.
      (rf/reg-event-fx :path-noop/fx-only
                       [(rf/path :foo :bar)
                        ;; Sandwich-spy that captures the effects map
                        ;; produced by the handler-side chain — i.e.
                        ;; AFTER the handler ran and BEFORE the path
                        ;; interceptor's :after re-runs.
                        (interceptor/->interceptor
                          :id    :path-noop/spy
                          :after (fn [ctx]
                                   (reset! final-ctx ctx)
                                   ctx))]
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
      (let [db (rf/get-frame-db :rf/default)]
        (is (= 10 (get-in db [:foo :bar]))
            "slice value is preserved when handler emits no :db effect")
        (is (= :preserved (:other db))
            "the path didn't touch the rest of app-db either")))))

(deftest path-interceptor-still-splices-back-when-handler-emits-db
  (testing "(path ...) still splices when the handler DOES emit :db —
            rf2-rwlj2 fix preserves the happy path"
    (rf/reg-event-db :path-emit/init (fn [_ _] {:foo {:bar 10}}))
    (rf/reg-event-fx :path-emit/inc
                     [(rf/path :foo :bar)]
                     (fn [{:keys [db]} _]
                       ;; Explicitly emit `:db` (the slice + 1).
                       {:db (inc db)}))
    (rf/dispatch-sync [:path-emit/init])
    (rf/dispatch-sync [:path-emit/inc])
    (is (= 11 (get-in (rf/get-frame-db :rf/default) [:foo :bar]))
        "handler that emits :db still gets its slice spliced back")))

;; ---- unwrap ---------------------------------------------------------------

(deftest unwrap-interceptor
  (testing "[unwrap] replaces the :event coeffect with the payload map from
            the canonical [event-id payload-map] envelope shape."
    (let [seen-event (atom ::not-set)]
      (rf/reg-event-fx :unwrap-test/consume
                       [rf/unwrap-interceptor]
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

;; Per rf2-rav3 / Spec 009 §Error contract — the negative path:
;; when the event does NOT match the canonical [event-id payload-map]
;; shape, `unwrap` emits `:rf.error/unwrap-bad-event-shape` and returns
;; ctx unchanged (no recovery — the handler still runs, but it sees the
;; original event vector). The trace's diagnostic tags carry
;; `:expected` / `:recovery` so tooling can surface the misuse.
;;
;; Source: implementation/core/src/re_frame/std_interceptors.cljc lines 75-79.

(deftest unwrap-bad-event-shape-traces-and-keeps-event-unchanged
  (testing "[unwrap] with a non-[id payload-map] event emits
            :rf.error/unwrap-bad-event-shape and leaves the :event
            coeffect unchanged"
    (let [traces     (atom [])
          seen-event (atom ::not-set)]
      (rf/register-trace-listener! ::unwrap-bad (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-fx :unwrap-bad-test/consume
                       [rf/unwrap-interceptor]
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      ;; A malformed event: second slot is :not-a-map (a keyword, not a map).
      (rf/dispatch-sync [:unwrap-bad-test/consume :not-a-map])
      (rf/unregister-trace-listener! ::unwrap-bad)

      ;; The handler still ran — unwrap's bad path is a trace-and-continue,
      ;; not a throw — but it saw the ORIGINAL event vector (ctx unchanged),
      ;; not the payload.
      (is (= [:unwrap-bad-test/consume :not-a-map] @seen-event)
          "handler runs with the original event vector when unwrap's shape check fails")

      ;; The structured trace fired.
      (let [bad-shape (filterv #(= :rf.error/unwrap-bad-event-shape (:operation %))
                               @traces)]
        (is (= 1 (count bad-shape))
            "exactly one :rf.error/unwrap-bad-event-shape trace was emitted")
        (let [ev (first bad-shape)]
          (is (= :error (:op-type ev)))
          (is (= [:unwrap-bad-test/consume :not-a-map]
                 (get-in ev [:tags :event]))
              ":event tag carries the offending event vector")
          (is (= "[event-id payload-map]"
                 (get-in ev [:tags :expected]))
              ":expected describes the canonical envelope shape")
          (is (= :no-recovery (:recovery ev))
              ":recovery is :no-recovery — unwrap's bad path is documented as not-recoverable")))))

  (testing "[unwrap] with a 3-element vector (correct id, wrong arity) traces"
    (let [traces     (atom [])
          seen-event (atom ::not-set)]
      (rf/register-trace-listener! ::unwrap-arity (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-fx :unwrap-bad-test/arity
                       [rf/unwrap-interceptor]
                       (fn [_cofx event-arg]
                         (reset! seen-event event-arg)
                         {}))
      ;; Wrong arity — three elements instead of two.
      (rf/dispatch-sync [:unwrap-bad-test/arity {:ok :map} :extra])
      (rf/unregister-trace-listener! ::unwrap-arity)
      (is (some #(= :rf.error/unwrap-bad-event-shape (:operation %)) @traces)
          ":rf.error/unwrap-bad-event-shape fires for arity mismatch too")
      (is (= [:unwrap-bad-test/arity {:ok :map} :extra] @seen-event)
          "handler sees the original (still-wrongly-shaped) event")))

  (testing "[unwrap] on the happy [id payload-map] path does NOT emit the bad-shape trace"
    ;; Sanity: confirm the trace is silent on a well-shaped event so the
    ;; coverage above is genuinely catching the negative branch.
    (let [traces (atom [])]
      (rf/register-trace-listener! ::unwrap-ok (fn [ev] (swap! traces conj ev)))
      (rf/reg-event-fx :unwrap-bad-test/ok
                       [rf/unwrap-interceptor]
                       (fn [_ _] {}))
      (rf/dispatch-sync [:unwrap-bad-test/ok {:k 1}])
      (rf/unregister-trace-listener! ::unwrap-ok)
      (is (empty? (filter #(= :rf.error/unwrap-bad-event-shape (:operation %))
                          @traces))
          "no bad-shape trace on the canonical [id payload-map] event"))))

;; ---- inject-cofx ----------------------------------------------------------

(deftest inject-cofx-interceptor
  (testing "registered cofx is injected into :coeffects under its keyword id"
    (rf/reg-cofx :now (fn [ctx] (assoc-in ctx [:coeffects :now] 1234567890)))
    (let [seen-cofx (atom nil)]
      (rf/reg-event-fx :cofx-test/read-now
                       [(rf/inject-cofx :now)]
                       (fn [cofx _event]
                         (reset! seen-cofx cofx)
                         {}))
      (rf/dispatch-sync [:cofx-test/read-now])
      (is (= 1234567890 (:now @seen-cofx))
          "the :now value injected by the cofx is visible to the handler")
      (is (contains? @seen-cofx :db)
          "standard cofx (e.g. :db) are still present alongside :now")
      (is (contains? @seen-cofx :event)
          "standard :event cofx is present")))

  (testing "(inject-cofx :id value) passes the value as a second arg"
    (rf/reg-cofx :greeting
                 (fn [ctx greeting]
                   (assoc-in ctx [:coeffects :greeting] greeting)))
    (let [seen (atom nil)]
      (rf/reg-event-fx :cofx-test/use-greeting
                       [(rf/inject-cofx :greeting "hello")]
                       (fn [cofx _]
                         (reset! seen (:greeting cofx))
                         {}))
      (rf/dispatch-sync [:cofx-test/use-greeting])
      (is (= "hello" @seen)
          "the value-arity inject-cofx threads the value into the cofx fn")))

  (testing "a cofx that throws surfaces as :rf.error/handler-exception via trace"
    ;; Per re-frame.interceptor/invoke-before, an exception from a :before
    ;; stage is captured into :rf/interceptor-error and the chain keeps
    ;; running so :after stages can clean up. The router then converts
    ;; that to a :rf.error/handler-exception trace event with :phase :before.
    (rf/reg-cofx :boom
                 (fn [_ctx]
                   (throw (ex-info "cofx blew up" {:why :testing}))))
    (rf/reg-event-fx :cofx-test/explode
                     [(rf/inject-cofx :boom)]
                     (fn [_ _] {}))
    (let [traces (atom [])]
      (rf/register-trace-listener! ::cofx-throw (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:cofx-test/explode])
      (rf/unregister-trace-listener! ::cofx-throw)
      (is (some (fn [ev]
                  (and (= :rf.error/handler-exception (:operation ev))
                       (= :before (get-in ev [:tags :phase]))))
                @traces)
          "expected :rf.error/handler-exception trace with :phase :before"))))

;; ---- ->interceptor primitive ----------------------------------------------

(deftest make-interceptor-via-primitive
  (testing "->interceptor builds a custom interceptor whose :before runs in
            chain order and whose :after runs in reverse — both can mutate
            the context."
    (let [trail (atom [])
          ;; Three custom interceptors, named A / B / C, that each push a
          ;; tagged entry into `trail` from both their :before and :after
          ;; slots. The handler itself pushes :handler.
          mk (fn [tag]
               (rf/->interceptor
                 :id     tag
                 :before (fn [ctx]
                           (swap! trail conj [:before tag])
                           (assoc ctx tag :touched))
                 :after  (fn [ctx]
                           (swap! trail conj [:after tag])
                           ctx)))]
      (rf/reg-event-fx :primitive/run
                       [(mk :a) (mk :b) (mk :c)]
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
                  (interceptor/->interceptor
                    :id     tag
                    :before (fn [ctx]
                              (swap! trail conj [:before tag])
                              (update ctx :seen (fnil conj []) tag))
                    :after  (fn [ctx]
                              (swap! trail conj [:after tag])
                              ctx)))
          handler (interceptor/->interceptor
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
                    (interceptor/->interceptor
                      :id     tag
                      :before (fn [ctx]
                                (swap! trail conj [:before tag])
                                ctx)
                      :after  (fn [ctx]
                                (swap! trail conj [:after tag])
                                ctx)))
          mk-bad-after (fn [tag]
                         (interceptor/->interceptor
                           :id     tag
                           :before (fn [ctx]
                                     (swap! trail conj [:before tag])
                                     ctx)
                           :after  (fn [_ctx]
                                     (swap! trail conj [:after tag])
                                     (throw (ex-info "after blew up"
                                                     {:tag tag})))))
          handler (interceptor/->interceptor
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
          before-bad (interceptor/->interceptor
                       :id :before-bad
                       :before (fn [_ctx]
                                 (throw (ex-info "before blew up"
                                                 {:src :before-bad}))))
          after-bad  (interceptor/->interceptor
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
                    (interceptor/->interceptor
                      :id     tag
                      :before (fn [ctx]
                                (swap! trail conj [:before tag])
                                ctx)
                      :after  (fn [ctx]
                                (swap! trail conj [:after tag])
                                ctx)))
          boom    (interceptor/->interceptor
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
