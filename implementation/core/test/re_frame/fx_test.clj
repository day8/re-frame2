(ns re-frame.fx-test
  "Comprehensive edge-case coverage for the fx subsystem (rf2-mpug).

  Per-feature fx tests already live alongside their owners (the http
  stubs in smoke-test/login-machine-flow, the SSR fixtures, and the
  fx/db-first / fx/ordering-source-order / fx/override-by-id /
  fx/platforms conformance fixtures). This file consolidates the cross-
  cutting edge cases that are awkward to express in EDN fixtures:

    1. Source-order ordering across mixed effect types in one return.
    2. :fx-overrides precedence: per-call > per-frame > registered.
    3. fx-handler exception recovery is :isolated — sibling fx still fire.
    4. Missing fx-id emits :rf.error/no-such-fx and skips that entry.
    5. :platforms gating skips with :rf.fx/skipped-on-platform.
    6. Effect-map shape (M-8): legacy v1 top-level keys are policed.
    6b. :fx VALUE shape (rf2-24zly): a non-sequential :fx value is policed
        (dropped + traced), not thrown — symmetric with M-8.
    6c. :fx per-ENTRY shape (rf2-n6d3m): a non-nil/non-empty NON-vector entry
        is policed (dropped + traced); nil/empty stays a silent no-op.
    6d. :fx per-ENTRY arity / fx-id-type (rf2-18kwf): a non-keyword head or an
        arity-≥3 vector is policed (dropped + traced), not waved through.
    6e. RESERVED :dispatch arity-3 (rf2-tbuov): the `[:dispatch ev {:frame …}]`
        wild malformation is policed LOUDLY end-to-end, NOT silently truncated.

  Per Spec 002 §`:fx` ordering and atomicity guarantees and Spec 009
  §Error contract.

  ## Posture split (rf2-d2841)

  The fx subsystem's SEMANTICS are production-real and are asserted here
  WITHOUT a posture guard, so they run in the ordinary `clojure -M:test`
  suite AND in `scripts/test-core-prod-gate.sh` (the `-Dre-frame.debug=false`
  lane): source-order walking, override precedence, `:isolated` recovery past
  a throwing fx, `:platforms` gating, the M-8 / `:fx`-value / `:fx`-entry
  policing DROPS, the reject-tier neutralisation, prod-strip's returned map,
  and the registration-time `reg-fx` rejections.

  Two DIAGNOSTIC channels sit beside those semantics, and they are not the
  same channel:

  * The `:trace` stream (`collect-traces!`) is DEV-ONLY. Every `trace/emit!`
    / `trace/emit-error!` site rides `interop/debug-enabled?`, which the JVM
    reads once at load time, so under the real gate the ring is EMPTY BY
    DESIGN. `:rf.fx/skipped-on-platform`, `:rf.fx/override-applied`,
    `:rf.fx/do-fx` and `:rf.event/run-end` live ONLY here. Their assertions
    are kept VERBATIM inside a `(when interop/debug-enabled? …)` arm marked
    `rf2-d2841`.

  * The `:errors` stream (`collect-errors!`) is ALWAYS-ON and survives the
    gate. `:rf.error/fx-handler-exception`, `:rf.error/no-such-fx`,
    `:rf.error/override-fallthrough`, `:rf.error/reserved-fx-override` and
    (since rf2-04tx) `:rf.error/effect-map-shape`
    fan out through `emit-fx-error!` → `error-emit/emit-error-both!`, which
    additionally LIFTS `:failing-id` / `:reason` onto the record whenever the
    failing component differs from the dispatched event (Spec 009 §Component
    attribution). Where the dev trace had been an assertion's only witness
    and one of those four categories was available, the assertion gained a
    production-visible twin on this axis rather than being guarded away —
    including the NEGATIVE ones, which over the dev ring would pass
    vacuously under the gate.

  A negative dev-trace assertion (`empty?` / `zero? (count …)`) is the
  false-green this lane exists to close: left outside an arm it passes
  because the ring is empty, not because the framework stayed silent. Every
  one of them here either moved inside the arm or was re-pointed at the
  always-on axis."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.fx :as fx]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.error-emit :as error-emit]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (flows/reset-flows!)
  (schemas/clear-schemas-by-frame!)
  ;; The always-on error-emit listener registry is a `defonce` atom that
  ;; `clear-all!` does NOT touch; clear it so an error listener registered
  ;; by one test cannot leak into the next (rf2-uh5ic5).
  (error-emit/clear-error-listeners!)
  (rf/init! plain-atom/adapter)
  ;; EP-0002 (rf2-9o48ih): `init!` no longer synthesises `:rf/default`, and
  ;; the framework operation surfaces now require a carried frame stamp.
  ;; Register `:rf/default` explicitly and pin it as the body's ambient
  ;; scope — the carried-invariant equivalent of wrapping every test in
  ;; `(with-frame :rf/default …)`. Bare dispatches in the test bodies then
  ;; resolve to `:rf/default`; explicit `{:frame …}` opts still win.
  (rf/make-frame {:id :rf/default})
  ;; Framework registrations live at namespace-load time; clear-all!
  ;; wiped them. Reload so :rf/route, :rf.route/* subs and the framework
  ;; fx (e.g. :rf.fx/reg-flow) survive between tests.
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  "Register a trace listener under `id`, returning the atom that
  accumulates events. Tests must (rf/unregister-listener! :trace id) to detach."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- collect-errors!
  "Register an ALWAYS-ON error listener under `id` via
  `(rf/register-listener! :errors id f)`, returning the atom that accumulates the
  tight error-records (the production-survivable observability surface —
  Spec 009 §What IS available in production §Error-emit listener). Tests
  must (rf/unregister-listener! :errors id) to detach (the fixture also
  clears the registry). Distinct from `collect-traces!`: that listens on
  the dev-only trace surface (DCE'd in prod); this listens on the
  always-on axis."
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :errors id (fn [record] (swap! acc conj record)))
    acc))

;; ---- 1. Source-order ordering across mixed effect types -------------------
;;
;; Per Spec 002 §`:fx` ordering and atomicity guarantees, the runtime
;; walks the `:fx` vector strictly in source order regardless of fx-id.
;; A handler that mixes registered fx and the reserved `:dispatch` fx
;; in a single `:fx` vector must observe each entry in declared order;
;; subsequent entries (queued events included) drain after the source-
;; order walk finishes.

(deftest source-order-across-mixed-fx
  (testing "an :fx vector mixing :a, :b, :c (registered fx) fires in declared order"
    (let [log (atom [])]
      (rf/reg-fx :fx-test/a (fn [_ _] (swap! log conj :a)))
      (rf/reg-fx :fx-test/b (fn [_ _] (swap! log conj :b)))
      (rf/reg-fx :fx-test/c (fn [_ _] (swap! log conj :c)))
      (rf/reg-event :fx-test/run-mixed
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/a]
                [:fx-test/b]
                [:fx-test/c]]}))
      (rf/dispatch-sync [:fx-test/run-mixed])
      (is (= [:a :b :c] @log)
          "registered fx fire in source order")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed before :fx walked (Spec 002 rule 1)")))

  (testing "interleaving :dispatch with registered fx preserves source order"
    ;; :dispatch enqueues; the queued event drains after the original
    ;; handler's :fx walk completes. The walk itself is in declared order;
    ;; the queued events arrive in declared order on the FIFO.
    (let [log (atom [])]
      (rf/reg-fx :fx-test/sync-a (fn [_ _] (swap! log conj :sync-a)))
      (rf/reg-fx :fx-test/sync-b (fn [_ _] (swap! log conj :sync-b)))
      (rf/reg-event :fx-test/queued-a
        (fn [{:keys [db]} _] (swap! log conj :queued-a) {:db db}))
      (rf/reg-event :fx-test/queued-b
        (fn [{:keys [db]} _] (swap! log conj :queued-b) {:db db}))
      (rf/reg-event :fx-test/run-interleaved
        (fn [_ _]
          {:fx [[:fx-test/sync-a]
                [:dispatch [:fx-test/queued-a]]
                [:fx-test/sync-b]
                [:dispatch [:fx-test/queued-b]]]}))
      (rf/dispatch-sync [:fx-test/run-interleaved])
      ;; The synchronous fx in the original :fx walk run first in declared
      ;; order; queued :dispatch events drain afterwards in declared order.
      (is (= [:sync-a :sync-b :queued-a :queued-b] @log)
          "synchronous fx fire in source order; queued events follow in source order"))))

;; ---- 2. :fx-overrides precedence ------------------------------------------
;;
;; Per Spec 002 §Per-frame and per-call overrides and router.cljc's
;; (merge per-frame-fx per-call-fx): the per-call override map wins
;; over the per-frame override map, which wins over the registered fx.
;;
;; This test layers all three: a registered :fx-test/email fx, a per-
;; frame override redirecting to :fx-test/email.frame, and a per-call
;; override redirecting to :fx-test/email.call. The per-call route
;; should win.

(deftest fx-overrides-per-call-beats-per-frame-beats-registered
  (testing "per-call override > per-frame override > registered fx"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/email
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :registered)))
      (rf/reg-fx :fx-test/email.frame
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :per-frame)))
      (rf/reg-fx :fx-test/email.call
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :per-call)))
      (rf/reg-event :fx-test/send
        (fn [_ _] {:fx [[:fx-test/email {:to "alice"}]]}))

      (testing "no overrides — registered fx fires"
        (reset! fired [])
        (let [f (frame/make-anon-frame-record! {})]
          (rf/dispatch-sync [:fx-test/send] {:frame f})
          (is (= [:registered] @fired))))

      (testing "per-frame override applied"
        (reset! fired [])
        (let [f (frame/make-anon-frame-record!
                  {:fx-overrides {:fx-test/email :fx-test/email.frame}})]
          (rf/dispatch-sync [:fx-test/send] {:frame f})
          (is (= [:per-frame] @fired))))

      (testing "per-call override beats per-frame"
        (reset! fired [])
        (let [f (frame/make-anon-frame-record!
                  {:fx-overrides {:fx-test/email :fx-test/email.frame}})]
          (rf/dispatch-sync
            [:fx-test/send]
            {:frame         f
             :fx-overrides  {:fx-test/email :fx-test/email.call}})
          (is (= [:per-call] @fired)
              "per-call override redirects past the per-frame override"))))))

;; ---- 2b. rf/with-fx-overrides — lexical-scope :fx-overrides binding (rf2-5uwl)

(deftest with-fx-overrides-binds-fx-overrides-lexically
  (testing "rf/with-fx-overrides — every dispatch inside the body inherits the override map"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/wo-email
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :registered)))
      (rf/reg-fx :fx-test/wo-email.stub
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :lexical)))
      (rf/reg-fx :fx-test/wo-email.call
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :per-call)))
      (rf/reg-event :fx-test/wo-send
        (fn [_ _] {:fx [[:fx-test/wo-email {:to "alice"}]]}))

      (testing "outside with-fx-overrides the registered fx fires"
        (reset! fired [])
        (rf/dispatch-sync [:fx-test/wo-send])
        (is (= [:registered] @fired)))

      (testing "inside with-fx-overrides every dispatch inherits the override"
        (reset! fired [])
        (rf/with-fx-overrides {:fx-test/wo-email :fx-test/wo-email.stub}
          (rf/dispatch-sync [:fx-test/wo-send])
          (rf/dispatch-sync [:fx-test/wo-send])
          (rf/dispatch-sync [:fx-test/wo-send]))
        (is (= [:lexical :lexical :lexical] @fired)
            "all three dispatches inside the macro body picked up the lexical override"))

      (testing "after the body exits the registered fx fires again"
        (reset! fired [])
        (rf/dispatch-sync [:fx-test/wo-send])
        (is (= [:registered] @fired)
            "the dynamic var unwound; the override is gone"))

      (testing "per-call opt > lexical with-fx-overrides > per-frame"
        (reset! fired [])
        (let [f (frame/make-anon-frame-record!
                  {:fx-overrides {:fx-test/wo-email :fx-test/wo-email.stub}})]
          (rf/with-fx-overrides {:fx-test/wo-email :fx-test/wo-email.stub}
            ;; Per-call wins over both lexical and per-frame
            (rf/dispatch-sync [:fx-test/wo-send]
                              {:frame        f
                               :fx-overrides {:fx-test/wo-email :fx-test/wo-email.call}}))
          (is (= [:per-call] @fired)
              "per-call opt outranks the lexical with-fx-overrides binding"))))))

(deftest with-fx-overrides-composes-with-with-frame
  (testing "rf/with-fx-overrides nests cleanly inside rf/with-frame"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/comp-email
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :registered)))
      (rf/reg-fx :fx-test/comp-email.stub
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :stub)))
      (rf/reg-event :fx-test/comp-send
        (fn [_ _] {:fx [[:fx-test/comp-email]]}))
      (rf/make-frame {:id :wo/A :doc "frame A"})

      (reset! fired [])
      (rf/with-frame :wo/A
        (rf/with-fx-overrides {:fx-test/comp-email :fx-test/comp-email.stub}
          (rf/dispatch-sync [:fx-test/comp-send])))
      (is (= [:stub] @fired)
          "with-fx-overrides inside with-frame applies its override to dispatches targeted at the named frame"))))

;; ---- 3. fx-handler exception → :rf.error/fx-handler-exception -------------
;;
;; Per Spec 009 §Error contract, an fx implementation that throws emits
;; a structured :rf.error/fx-handler-exception trace. Per Spec 002
;; §`:fx` ordering rule 4 ("one bad fx does not halt the rest"), the
;; recovery is :isolated — the offending fx is skipped but sibling fx
;; in the same handler's :fx vector still run.

(deftest fx-handler-exception-is-isolated
  (testing "a throwing fx emits :rf.error/fx-handler-exception, sibling fx still fire"
    (let [traces (collect-traces! ::fx-exc)
          errors (collect-errors! ::fx-exc-errors)
          fired  (atom [])]
      (rf/reg-fx :fx-test/boom
        (fn [_ _] (throw (ex-info "kaboom" {:why :test}))))
      (rf/reg-fx :fx-test/after
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/with-bad-fx
        (fn [_ _]
          {:fx [[:fx-test/boom  {:reason :first}]
                [:fx-test/after {:reason :sibling}]]}))
      (rf/dispatch-sync [:fx-test/with-bad-fx])
      (rf/unregister-listener! :trace  ::fx-exc)
      (rf/unregister-listener! :errors ::fx-exc-errors)
      ;; Sibling fx ran — recovery is :isolated.
      (is (= [{:reason :sibling}] @fired)
          "the :fx after the throwing entry still fires (Spec 002 rule 4)")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). `:rf.error/fx-handler-exception`
      ;; is an ALWAYS-ON category (Spec 009 §Observability channels), so the
      ;; structured diagnostic is observable under `-Dre-frame.debug=false`
      ;; too — on the `:errors` axis rather than the DCE'd trace ring. The
      ;; failing fx-id is DISTINCT from the dispatched event-id, so
      ;; `emit-error-both!` lifts it onto the record as `:failing-id`
      ;; (Spec 009 §Component attribution).
      (let [records (filter #(= :rf.error/fx-handler-exception (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on record reached the :errors listener")
        (let [r (first records)]
          (is (= :fx-test/boom (:failing-id r))
              ":failing-id names the THROWING fx, not the dispatched event")
          (is (= :fx-test/with-bad-fx (:event-id r))
              ":event-id carries the dispatching event id")
          (is (= [:fx-test/with-bad-fx] (:event r)))
          (is (= :rf/default (:frame r)))
          (is (some? (:exception r))
              "the exception object rides the production record")))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The trace
      ;; ring carries the RICHER shape (`:rf.fx/args`, `:exception-message`,
      ;; `:op-type`) that the tight always-on record deliberately omits.
      (when interop/debug-enabled?
        (let [exc-traces (filter #(= :rf.error/fx-handler-exception (:operation %))
                                 @traces)]
          (is (= 1 (count exc-traces))
              "exactly one :rf.error/fx-handler-exception was emitted")
          (let [t (first exc-traces)]
            (is (= :error (:op-type t)))
            (is (= :fx-test/boom (get-in t [:tags :rf.fx/id])))
            (is (= :fx-test/boom (get-in t [:tags :failing-id])))
            (is (= {:reason :first} (get-in t [:tags :rf.fx/args]))
                ":rf.fx/args carry the offending args")
            (is (string? (get-in t [:tags :exception-message])))
            (is (some? (get-in t [:tags :exception])))))))))

;; ---- 4. Missing fx-id → :rf.error/no-such-fx ------------------------------
;;
;; Per Spec 009 §Error contract, an unknown fx-id in a returned :fx
;; entry emits :rf.error/no-such-fx. Per Spec 002, the walk continues
;; past the offending entry — recovery is :logged-and-skipped (the
;; runtime currently flags this with :recovery :no-recovery in the trace
;; tags, which is what the existing fx.cljc emits; the *behavioural*
;; contract is "skip and keep going" regardless of the recovery tag's
;; verb).

(deftest unknown-fx-id-is-logged-and-skipped
  (testing "a registered handler returning [:no-such-fx ...] traces and skips"
    (let [traces (collect-traces! ::no-such)
          errors (collect-errors! ::no-such-errors)
          fired  (atom [])]
      (rf/reg-fx :fx-test/sibling
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/missing
        (fn [_ _]
          {:fx [[:fx-test/never-registered  {:k 1}]
                [:fx-test/sibling           {:k 2}]]}))
      (rf/dispatch-sync [:fx-test/missing])
      (rf/unregister-listener! :trace  ::no-such)
      (rf/unregister-listener! :errors ::no-such-errors)
      ;; Sibling still fires — the unknown fx-id did not halt the walk.
      (is (= [{:k 2}] @fired)
          "the next :fx entry still fires after an unknown fx-id")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). Spec 009's catalogue marks
      ;; `:rf.error/no-such-fx` ALWAYS-ON, so an unknown fx-id is observable
      ;; off-box under `-Dre-frame.debug=false`.
      ;;
      ;; rf2-g0mep — the record must name WHICH fx-id was unknown. Spec 009
      ;; §Observability channels states the attribution rule generally: every
      ;; always-on error record carries `:failing-id` naming the failing
      ;; COMPONENT whenever that component is DISTINCT from the dispatched
      ;; event. Here `:event-id` is the DISPATCHING event, so the unregistered
      ;; fx-id is exactly such a component. `fx.cljc`'s trace-payload stamps
      ;; `:failing-id fx-id` and `emit-error-both!` lifts it onto the always-on
      ;; record; remove that key from the emit site and the `:failing-id`
      ;; assertion below reds while the dev-trace arm stays green — the fx-id
      ;; would once again ride ONLY the DCE'd `:rf.fx/id` tag.
      (let [records (filter #(= :rf.error/no-such-fx (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on record reached the :errors listener")
        (let [r (first records)]
          (is (= :fx-test/missing (:event-id r))
              ":event-id carries the dispatching event id")
          (is (= [:fx-test/missing] (:event r)))
          (is (= :rf/default (:frame r))
              ":frame names the frame the unknown fx-id was dispatched in")
          (is (= :fx-test/never-registered (:failing-id r))
              ":failing-id names the UNKNOWN fx-id — the failing component,
               distinct from the dispatched event (rf2-g0mep)")
          ;; The record is a PRODUCTION EGRESS surface: every slot here reaches
          ;; off-box shippers (Sentry / Datadog) through the frame-owned
          ;; `:observability :errors` sink. Pin the key set CLOSED so a future
          ;; slot is a deliberate decision, not a drive-by. `:rf.fx/args` in
          ;; particular must NOT appear — an unregistered fx-id has no
          ;; registration to read a `:sensitive` declaration off, so its args
          ;; are the documented EP-0025 fail-open and stay on the dev trace.
          (is (= #{:error :event :event-id :frame :time :exception :elapsed-ms
                   :failing-id :source-coord}
                 (set (keys r)))
              "the always-on record carries the tight rf2-bacs4 keys, the
               rf2-3un2g :source-coord for the dispatching event, and exactly
               one attribution slot — :failing-id (rf2-g0mep). No :reason (the
               category defines none, and nil attribution slots are dropped)
               and no :rf.fx/args.")))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). `:rf.fx/id` is
      ;; the dev-trace spelling of the same id the always-on record now carries
      ;; as `:failing-id`.
      (when interop/debug-enabled?
        (let [missing-traces (filter #(= :rf.error/no-such-fx (:operation %))
                                     @traces)]
          (is (= 1 (count missing-traces))
              "exactly one :rf.error/no-such-fx trace was emitted")
          (let [t (first missing-traces)]
            (is (= :error (:op-type t)))
            (is (= :fx-test/never-registered (get-in t [:tags :rf.fx/id])))
            (is (= :rf/default (get-in t [:tags :frame])))))))))

;; ---- 5. :platforms gating -------------------------------------------------
;;
;; Per Spec 011 §`:platforms` metadata on reg-fx, an fx with :platforms
;; #{:client} is gated off when the active platform is :server. The
;; runtime emits :rf.fx/skipped-on-platform with :recovery :skipped
;; instead of invoking the handler. JVM hosts default to :server (per
;; re-frame.interop/active-platform — settable via
;; re-frame.core/init-platform), so :client-only fx are silently inert
;; under JVM tests — exactly what we need for headless mode.

(deftest platforms-gating-skips-client-only-fx-on-jvm
  (testing ":platforms #{:client} fx is skipped on JVM (:server) and emits a trace"
    (let [traces (collect-traces! ::plat)
          fired? (atom false)]
      (rf/reg-fx :fx-test/local-storage
        {:platforms #{:client}}
        (fn [_ _] (reset! fired? true)))
      (rf/reg-event :fx-test/save
        (fn [_ _] {:fx [[:fx-test/local-storage {:k "key" :v "val"}]]}))
      (rf/dispatch-sync [:fx-test/save])
      (rf/unregister-listener! :trace ::plat)
      (is (false? @fired?)
          "the client-only handler did NOT run on the JVM (:server)")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      ;; `:rf.fx/skipped-on-platform` is a `trace/emit!` WARNING (fx.cljc
      ;; §handle-one-fx), not a promoted `:rf.error/*` — it has no always-on
      ;; twin, so the SKIP itself (asserted above, posture-independently) is
      ;; the production-visible fact and the diagnostic is dev-only by design.
      (when interop/debug-enabled?
        (let [skip-traces (filter #(= :rf.fx/skipped-on-platform (:operation %))
                                  @traces)]
          (is (= 1 (count skip-traces))
              "exactly one :rf.fx/skipped-on-platform trace was emitted")
          (let [t (first skip-traces)]
            (is (= :warning (:op-type t)))
            (is (= :skipped (:recovery t)))
            (is (= :fx-test/local-storage (get-in t [:tags :rf.fx/id])))
            (is (= :server (get-in t [:tags :rf.fx/platform])))
            (is (= #{:client} (get-in t [:tags :rf.fx/registered-platforms])))))))))

;; ---- 6. Effect-map shape policing (M-8) -----------------------------------
;;
;; Per migration/from-re-frame-v1/README.md §M-8 and Spec-Schemas.md §:rf/effect-map,
;; the effect map is CLOSED: #{:db :rf.db/runtime :fx} at the top level. App
;; handlers return only :db and :fx; :rf.db/runtime is the reserved
;; framework-authority runtime-db effect. A handler returning a key OUTSIDE
;; that closed set (e.g. a legacy v1 :dispatch / :dispatch-later / :dispatch-n /
;; :http at the top level) MUST raise a structured trace per Spec 009 §Error
;; contract; the runtime does NOT silently drop and does NOT silently route
;; the offending key through the fx machinery.
;;
;; Enforcement landed in rf2-ooc5 as a DROP and was converted to a REFUSAL in
;; rf2-04tx: `re-frame.events/effect-map-defect` is a pure first-defect-or-nil
;; carrier the ROUTER consults at the FINAL-effects boundary, and a foreign
;; top-level key (case a) or a non-sequential :fx value (case b) ABORTS the
;; event in-band — no :db, no :rf.db/runtime, no classification install, no :fx.
;; A malformed ENTRY inside a well-shaped :fx vector (case c) is a different
;; plane and keeps its per-entry :logged-and-skipped drop.
;;
;; POSTURE (rf2-d2841 / rf2-04tx). The REFUSAL is production-real and uniform
;; across builds — `effect-map-defect` and `fx-entry-ok?` run unconditionally,
;; so every "the offending key/value did NOT take effect, and neither did its
;; legal siblings" claim below is asserted posture-independently and runs under
;; the production gate. Erasing the abort in a release build would make dev
;; abort what production commits, which is a build fork, so it is not done.
;; What IS dev-only is the NARRATION: the whole category now fans through
;; `error-emit/emit-error-both!` (Spec 009's Channel cell reads `always-on`),
;; whose axis-2 `trace/emit-error!` leg DCEs under `:advanced` +
;; `goog.DEBUG=false`. So the TRACE assertions still ride a
;; `(when interop/debug-enabled? …)` arm, INCLUDING the negative "no shape trace
;; fired" ones on the clean paths: over an empty ring those pass automatically
;; and would report a false green.

(deftest legacy-effect-map-key-is-policed
  (testing "a handler returning {:dispatch ...} (legacy v1 top-level key) is policed"
    (let [traces (collect-traces! ::shape)
          fired? (atom false)]
      ;; Sentinel: if the runtime accidentally routes the legacy
      ;; top-level :dispatch through the FIFO, this would set fired?
      ;; — the M-8 contract says it must NOT.
      (rf/reg-event :fx-test/sentinel
        (fn [{:keys [db]} _] (reset! fired? true) {:db db}))
      (rf/reg-event :fx-test/legacy-dispatch
        (fn [_ _]
          ;; Legacy v1 shape — top-level :dispatch.
          {:dispatch [:fx-test/sentinel]}))
      (rf/dispatch-sync [:fx-test/legacy-dispatch])
      (rf/unregister-listener! :trace ::shape)
      ;; The legacy top-level :dispatch is NOT performed (silently
      ;; routing it would defeat the M-8 migration).
      (is (false? @fired?)
          "the legacy top-level :dispatch must NOT silently dispatch the event")
      ;; A structured :rf.error/effect-map-shape trace is emitted (Spec
      ;; 009 §Error contract).
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace was emitted")
          (let [t (first shape-traces)]
            (is (= :error (:op-type t)))
            (is (= :fix-effect (:recovery t)))
            (is (= :dispatch (get-in t [:tags :offending-key]))
                ":offending-key carries the legacy top-level key")
            (is (= :fx-test/legacy-dispatch (get-in t [:tags :rf.trace/event-id]))
                ":event-id carries the dispatching event id")
            (is (= [:fx-test/sentinel] (get-in t [:tags :value]))
                ":value carries the offending key's value")
            (is (string? (get-in t [:tags :reason]))
                ":reason is a one-sentence human-facing description")))))))

;; ---- clear-fx round-trip (rf2-634y) --------------------------------------
;;
;; Per Spec 002 / API.md §Lifecycle and `core.cljc:869`: `rf/clear-fx` is
;; an alias of `fx/clear-fx`. Used by hot-reload tooling and by
;; `http_managed.cljc:1606` for stub uninstall. Pre-rf2-634y no test
;; pinned this behaviour.
;;
;; Source: implementation/core/src/re_frame/fx.cljc:49.

(deftest clear-fx-removes-and-restores
  (testing "register :test/touch → fires; clear-fx → traces :rf.error/no-such-fx;
            re-register → fires again. The registry slot is not poisoned by clear."
    (let [counter (atom 0)
          traces  (collect-traces! ::clear-fx)]
      (rf/reg-fx :test.634y/touch
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! counter inc)))
      (rf/reg-event :test.634y/run
                       (fn [_ _] {:fx [[:test.634y/touch :payload]]}))

      ;; 1. Pre-clear: dispatch increments the counter.
      (rf/dispatch-sync [:test.634y/run])
      (is (= 1 @counter) "registered :test.634y/touch fired on dispatch")

      ;; 2. Clear via the public alias.
      (rf/clear-fx :test.634y/touch)
      (is (nil? (registrar/lookup :fx :test.634y/touch))
          "registry slot is gone after clear-fx")

      ;; 3. Post-clear: dispatch does NOT increment and traces no-such-fx.
      (rf/dispatch-sync [:test.634y/run])
      (is (= 1 @counter)
          "the counter did NOT increment — the cleared fx did not fire")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). The
      ;; production-visible half of "the cleared fx-id is now unknown" is the
      ;; counter above plus the always-on `:rf.error/no-such-fx` record, which
      ;; `unknown-fx-id-is-logged-and-skipped` pins on the `:errors` axis; the
      ;; cleared fx-id's IDENTITY rides only the dev trace (rf2-g0mep).
      (when interop/debug-enabled?
        (let [missing (filter #(= :rf.error/no-such-fx (:operation %)) @traces)]
          (is (pos? (count missing))
              "a :rf.error/no-such-fx trace fired for the cleared fx-id")
          (is (some #(= :test.634y/touch (get-in % [:tags :rf.fx/id])) missing)
              ":rf.fx/id in the trace identifies the cleared handler")))

      ;; 4. Re-register and confirm idempotence: clear-fx didn't poison
      ;;    the registrar's per-kind slot machinery.
      (rf/reg-fx :test.634y/touch
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! counter inc)))
      (rf/dispatch-sync [:test.634y/run])
      (is (= 2 @counter)
          "after re-registration, the fx fires again — clear-fx is idempotent and reversible")
      (rf/unregister-listener! :trace ::clear-fx))))

(deftest clear-fx-idempotent-on-unknown-id
  (testing "clear-fx against an un-registered fx-id is a no-op (idempotent)"
    ;; Tooling calls clear-fx defensively before re-registering; a
    ;; second clear on an already-gone slot must not throw.
    (rf/reg-fx :test.634y/once (fn [_ _] nil))
    (rf/clear-fx :test.634y/once)
    ;; Second clear on the already-gone slot.
    (is (nil? (rf/clear-fx :test.634y/once))
        "double-clear is a no-op, not an exception")
    (is (nil? (registrar/lookup :fx :test.634y/once))
        "the slot stays gone")))

(deftest multiple-legacy-effect-map-keys-refuse-the-event-once
  (testing "an effect map with several legacy top-level keys refuses the event once — nothing at all is applied"
    (let [traces     (collect-traces! ::shape-multi)
          fired      (atom [])
          never-ran  (atom 0)
          http-fired (atom 0)]
      (rf/reg-fx :fx-test/sibling
        (fn [_ args] (swap! fired conj args)))
      ;; Production-visible sentinels (rf2-d2841): the REFUSAL is the fact this
      ;; deftest is really about, and it is only observable by giving every
      ;; effect somewhere to land. Both legacy keys AND the legal :fx entry name
      ;; a live target, so "refused" and "silently routed anyway" are
      ;; distinguishable under the production gate, not only through the dev
      ;; trace ring.
      (rf/reg-event :fx-test/never-runs
        (fn [{:keys [db]} _] (swap! never-ran inc) {:db db}))
      (rf/reg-fx :http (fn [_ _] (swap! http-fired inc)))
      (rf/reg-event :fx-test/multi-legacy
        (fn [{:keys [db]} _]
          ;; Mixed: legal :db and :fx alongside two legacy keys.
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/sibling {:k :legit}]]
           :dispatch [:fx-test/never-runs]
           :http {:url "/api"}}))
      (rf/dispatch-sync [:fx-test/multi-legacy])
      (rf/unregister-listener! :trace ::shape-multi)
      ;; NO PARTIAL COMMIT (rf2-04tx). The whole event is refused: the legal
      ;; :fx entry does not run either, and the legal :db does not land. This
      ;; is the transactional half of the standing FX atomicity asymmetry —
      ;; pre-commit envelope validity is all-or-nothing.
      (is (= [] @fired)
          "the legal :fx entry does NOT fire — the whole event was refused")
      (is (= 0 @never-ran)
          "the legacy top-level :dispatch never ran")
      (is (= 0 @http-fired)
          "the legacy top-level :http was not routed through the fx machinery")
      (is (nil? (:seeded? (rf/app-db-value :rf/default)))
          "the legal :db did NOT commit — no partial commit beside a refusal")
      ;; ONE trace, naming ONE key. The refusal is a first-defect decision:
      ;; enumerating every foreign key would bury the first mistake, and the
      ;; event is already over after the first.
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)
              offending    (set (map #(get-in % [:tags :offending-key]) shape-traces))]
          (is (= 1 (count shape-traces))
              "exactly ONE :rf.error/effect-map-shape trace — the first defect ends the event")
          (is (contains? #{:dispatch :http} (first offending))
              "and it names one of the two legacy keys"))))))

;; ---- 6b. :fx VALUE-shape policing (rf2-24zly) -----------------------------
;;
;; M-8 policing above checks the top-level KEYS only. The one shape it skipped
;; was the :fx VALUE: per Spec-Schemas §:rf/effect-map the :fx value is a
;; vector of [fx-id args] pairs. A handler returning a NON-sequential :fx value
;; — `{:fx :oops}` or `{:fx {:dispatch [...]}}`, the forgot-the-outer-vector
;; typo — used to slip past policing, get assoc'd into the effects map, and
;; throw an uncaught host exception inside `fx/do-fx`'s `(doseq [pair fx-vec])`.
;; Because :fx runs AFTER the :db commit, that throw escaped process-event!
;; into the drain's emergency release: app-db mutated, no structured trace, no
;; :on-error fire, downstream queued events abandoned.
;;
;; The fix (events.cljc `effect-map-defect`, case b) polices the :fx value
;; symmetric with M-8: a non-nil, non-sequential :fx value emits
;; :rf.error/effect-map-shape (:offending-key :fx, recovery :fix-effect) and
;; REFUSES the event — nothing commits, the drain is not aborted, and `do-fx`
;; never sees the bad value. nil/absent :fx stays the legal no-op.

(deftest non-sequential-fx-value-keyword-is-policed
  (testing "{:fx :oops} (a bare keyword instead of a vector) is policed —
            structured :rf.error/effect-map-shape, NOT a raw host exception"
    (let [traces (collect-traces! ::fx-val-kw)]
      (rf/reg-event :fx-test/fx-is-keyword
        (fn [{:keys [db]} _]
          ;; Forgot-the-outer-vector typo: :fx is a bare keyword.
          {:db (assoc db :seeded? true)
           :fx :oops}))
      ;; The dispatch MUST NOT throw — the bug was an uncaught
      ;; IllegalArgumentException escaping the drain after the :db commit.
      (is (nil? (rf/dispatch-sync [:fx-test/fx-is-keyword]))
          "dispatch returns normally — no uncaught host exception escapes the drain")
      (rf/unregister-listener! :trace ::fx-val-kw)
      ;; NO COMMIT — the malformed envelope refuses the whole event (rf2-04tx).
      (is (nil? (:seeded? (rf/app-db-value :rf/default)))
          ":db did NOT commit — the malformed :fx refuses the event, not just the slot")
      ;; Exactly one structured trace, flagging :fx as the offending slot.
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the bad :fx value")
          (let [t (first shape-traces)]
            (is (= :error (:op-type t)))
            (is (= :fix-effect (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key]))
                ":offending-key is :fx (the value-shape gap, not a top-level key)")
            (is (= :fx-test/fx-is-keyword (get-in t [:tags :rf.trace/event-id]))
                ":event-id names the offending handler")
            (is (= :oops (get-in t [:tags :value]))
                ":value carries the offending non-sequential :fx value")
            (is (string? (get-in t [:tags :reason]))
                ":reason is a human-facing description")
            (is (re-find #"outer vector" (get-in t [:tags :reason]))
                ":reason hints at the forgot-the-outer-vector typo")))))))

(deftest non-sequential-fx-value-map-is-policed
  (testing "{:fx {:dispatch [...]}} (a map instead of a vector of pairs) is policed —
            structured error, the inner :dispatch is NOT performed"
    (let [traces (collect-traces! ::fx-val-map)
          fired? (atom false)]
      ;; Sentinel: if the runtime somehow routed the inner :dispatch, this
      ;; would flip — it must NOT (the whole :fx value is malformed + dropped).
      (rf/reg-event :fx-test/fx-sentinel
        (fn [{:keys [db]} _] (reset! fired? true) {:db db}))
      (rf/reg-event :fx-test/fx-is-map
        (fn [_ _]
          ;; Forgot the outer + inner vector nesting: :fx is a map.
          {:fx {:dispatch [:fx-test/fx-sentinel]}}))
      (is (nil? (rf/dispatch-sync [:fx-test/fx-is-map]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace ::fx-val-map)
      (is (false? @fired?)
          "the malformed :fx map refuses the event wholesale — nothing inside it runs")
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the bad :fx value")
          (let [t (first shape-traces)]
            (is (= :fix-effect (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key])))
            (is (= :fx-test/fx-is-map (get-in t [:tags :rf.trace/event-id])))
            (is (= {:dispatch [:fx-test/fx-sentinel]} (get-in t [:tags :value]))
                ":value carries the offending map")))))))

(deftest well-shaped-fx-vector-is-unchanged
  (testing "the normal {:fx [[fx-id args] ...]} path still fires and emits NO shape trace"
    (let [traces (collect-traces! ::fx-val-ok)
          fired  (atom [])]
      (rf/reg-fx :fx-test/touch-ok
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/fx-is-vector
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/touch-ok {:k :v}]]}))
      (rf/dispatch-sync [:fx-test/fx-is-vector])
      (rf/unregister-listener! :trace ::fx-val-ok)
      (is (= [{:k :v}] @fired)
          "the well-shaped :fx entry still fires")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed alongside the legal :fx")
      ;; rf2-d2841 — dev-instrumentation arm. A NEGATIVE over the trace ring
      ;; MUST sit inside the arm: under `-Dre-frame.debug=false` the ring is
      ;; empty by design, so `empty?` would pass whether the framework stayed
      ;; silent or shouted — the exact false green this lane exists to close.
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/effect-map-shape (:operation %)) @traces))
            "a well-shaped :fx value emits NO :rf.error/effect-map-shape trace")))))

(deftest nil-fx-value-is-a-legal-no-op
  (testing "{:db ... :fx nil} is the legal no-op — :db commits, NO shape trace"
    ;; nil/absent :fx must NOT be flagged (it is equivalent to omitting :fx);
    ;; only a non-nil, non-sequential value is the typo we police.
    (let [traces (collect-traces! ::fx-val-nil)]
      (rf/reg-event :fx-test/fx-is-nil
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx nil}))
      (is (nil? (rf/dispatch-sync [:fx-test/fx-is-nil])))
      (rf/unregister-listener! :trace ::fx-val-nil)
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed; nil :fx is a no-op")
      ;; rf2-d2841 — dev-instrumentation arm (negative over the trace ring).
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/effect-map-shape (:operation %)) @traces))
            "nil :fx emits NO :rf.error/effect-map-shape trace — it is the legal no-op")))))

;; ---- 6c. per-ENTRY :fx-shape policing (rf2-n6d3m) -------------------------
;;
;; 6b (rf2-24zly) polices the whole :fx VALUE before it reaches the walk. This
;; is the level DOWN: an individual ENTRY inside an otherwise-well-shaped :fx
;; vector. Per `:rf/effect-map` each entry is a `[fx-id args]` vector. The do-fx
;; walk used to guard with `(when (and (vector? pair) (seq pair)) …)`, which
;; silently dropped every non-vector entry with NO diagnostic — including the
;; clear typo `{:fx [[:good a] :oops]}` (a bare keyword where a `[fx-id args]`
;; pair was meant). The handler appears to fire its effects but the typo'd
;; entry vanishes without trace.
;;
;; The fix (fx.cljc `fx-entry-ok?`) tolerates the legal no-op and polices the
;; clear typo, symmetric with rf2-24zly:
;;   nil / [] (empty)             → silent no-op (conditional-fx idiom). NO trace.
;;   non-empty vector             → walked normally.
;;   non-nil, non-empty NON-vector → :rf.error/effect-map-shape (:offending-key
;;                                   :fx, recovery :logged-and-skipped); that
;;                                   entry dropped, siblings still run.

(deftest malformed-non-vector-fx-entry-is-policed-and-skipped
  (testing "a bare-keyword :fx entry (the forgot-the-inner-vector typo) is
            policed — structured :rf.error/effect-map-shape, that entry skipped,
            sibling entries still run"
    (let [traces (collect-traces! ::fx-entry-kw)
          fired  (atom [])]
      (rf/reg-fx :fx-test/entry-sibling
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-is-keyword
        (fn [{:keys [db]} _]
          ;; :good fires; :oops is a bare keyword (forgot the inner vector)
          ;; — it must be policed + skipped, NOT silently dropped.
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/entry-sibling {:k 1}]
                :oops
                [:fx-test/entry-sibling {:k 2}]]}))
      ;; The walk must NOT throw — the malformed entry never reaches
      ;; handle-one-fx's destructuring.
      (is (nil? (rf/dispatch-sync [:fx-test/entry-is-keyword]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace ::fx-entry-kw)
      ;; Both well-shaped siblings fired in order; the typo'd middle entry
      ;; was skipped — the walk continued past it.
      (is (= [{:k 1} {:k 2}] @fired)
          "the sibling entries on both sides of the typo still fired in order")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed; only the malformed entry was dropped")
      ;; Exactly one structured trace, flagging the offending entry.
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the bad entry")
          (let [t (first shape-traces)]
            (is (= :error (:op-type t)))
            (is (= :logged-and-skipped (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key]))
                ":offending-key is :fx (a per-entry shape gap)")
            (is (= :fx-test/entry-is-keyword (get-in t [:tags :rf.trace/event-id]))
                ":event-id names the offending handler")
            (is (= :rf/default (get-in t [:tags :frame]))
                ":frame is stamped (lands in the per-frame epoch trace buffer)")
            (is (= :oops (get-in t [:tags :value]))
                ":value carries the offending non-vector entry")
            (is (string? (get-in t [:tags :reason]))
                ":reason is a human-facing description")
            (is (re-find #"inner vector" (get-in t [:tags :reason]))
                ":reason hints at the forgot-the-inner-vector typo")))))))

(deftest malformed-map-fx-entry-is-policed
  (testing "a map :fx entry (another non-vector shape) is policed + skipped"
    (let [traces (collect-traces! ::fx-entry-map)
          fired  (atom [])]
      (rf/reg-fx :fx-test/entry-ok
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-is-map
        (fn [_ _]
          {:fx [{:dispatch [:whatever]}      ;; a map where a [fx-id args] pair belongs
                [:fx-test/entry-ok {:k :v}]]}))
      (is (nil? (rf/dispatch-sync [:fx-test/entry-is-map]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace ::fx-entry-map)
      (is (= [{:k :v}] @fired)
          "the well-shaped sibling still fired; the map entry was dropped")
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the map entry")
          (let [t (first shape-traces)]
            (is (= :logged-and-skipped (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key])))
            (is (= :fx-test/entry-is-map (get-in t [:tags :rf.trace/event-id])))
            (is (= {:dispatch [:whatever]} (get-in t [:tags :value]))
                ":value carries the offending map entry")))))))

(deftest nil-and-empty-fx-entries-are-silent-no-ops
  (testing "nil and [] :fx entries are the legal conditional-fx no-op — the
            well-shaped siblings still fire and NO shape trace is emitted"
    ;; This is the contract the lenient `(when (and (vector? pair) (seq pair)))`
    ;; guard preserved: the `(into [] (when cond? [[:fx ...]]))` idiom nil-pads
    ;; (or empties) entries on purpose. Policing them would punish that idiom.
    (let [traces (collect-traces! ::fx-entry-nil)
          fired  (atom [])]
      (rf/reg-fx :fx-test/entry-noop-ok
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-nil-empty
        (fn [_ _]
          {:fx [[:fx-test/entry-noop-ok {:k 1}]
                nil                                 ;; conditional no-op
                []                                  ;; conditional no-op
                [:fx-test/entry-noop-ok {:k 2}]]}))
      (is (nil? (rf/dispatch-sync [:fx-test/entry-nil-empty])))
      (rf/unregister-listener! :trace ::fx-entry-nil)
      (is (= [{:k 1} {:k 2}] @fired)
          "both well-shaped entries fired; nil + [] were silent no-ops")
      ;; rf2-d2841 — dev-instrumentation arm (negative over the trace ring).
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/effect-map-shape (:operation %)) @traces))
            "nil + [] entries emit NO :rf.error/effect-map-shape trace — the legal idiom")))))

(deftest well-formed-multi-entry-fx-emits-no-shape-trace
  (testing "a well-formed multi-entry :fx vector runs all entries and emits NO
            per-entry shape trace"
    (let [traces (collect-traces! ::fx-entry-all-ok)
          fired  (atom [])]
      (rf/reg-fx :fx-test/multi-a (fn [_ _] (swap! fired conj :a)))
      (rf/reg-fx :fx-test/multi-b (fn [_ _] (swap! fired conj :b)))
      (rf/reg-event :fx-test/multi-ok
        (fn [_ _]
          {:fx [[:fx-test/multi-a]
                [:fx-test/multi-b]]}))
      (rf/dispatch-sync [:fx-test/multi-ok])
      (rf/unregister-listener! :trace ::fx-entry-all-ok)
      (is (= [:a :b] @fired)
          "all well-formed entries ran in source order")
      ;; rf2-d2841 — dev-instrumentation arm (negative over the trace ring).
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/effect-map-shape (:operation %)) @traces))
            "no :rf.error/effect-map-shape trace for a clean multi-entry :fx")))))

;; ---- 6d. per-ENTRY arity / fx-id-type policing (rf2-18kwf) ----------------
;;
;; 6c (rf2-n6d3m) tightened the do-fx walk to police a non-vector ENTRY. But
;; the guard still waved through ANY non-empty vector, so two malformed VECTOR
;; shapes leaked into `handle-one-fx`:
;;
;;   (1) a NON-keyword head — `["not-a-keyword" {:x 1}]` reached fx lookup and
;;       was mis-reported as `:rf.error/no-such-fx` (an UNKNOWN fx-id), when it
;;       is really a bad fx-id TYPE (a shape violation).
;;   (2) a surplus 3rd field — `[:some/fx {:used true} {:dropped true}]` was
;;       SILENTLY truncated because `handle-one-fx` destructures only
;;       `[original-fx-id args]`; the 3rd slot vanished with no diagnostic.
;;
;; Per `:rf/effect-map` the entry shape is `[:tuple :keyword :any]` (Spec-
;; Schemas §:rf/effect-map; spec/009 §:rf.error/effect-map-shape case (c)).
;; The fix tightens `fx-entry-ok?` to walk ONLY a 1- or 2-element vector whose
;; head is a keyword; both malformed shapes above now emit one
;; :rf.error/effect-map-shape (:offending-key :fx, :logged-and-skipped), drop
;; just that entry, and let siblings run — symmetric with 6c.
;;
;; The 1-arity no-args shorthand `[:fx-id]` stays valid: `handle-one-fx`
;; destructures `args` as nil for it, and it is in wide use (source-order /
;; multi-entry tests above). `well-formed-multi-entry-fx-emits-no-shape-trace`
;; already pins that it emits NO shape trace.

(deftest fx-entry-non-keyword-head-is-policed-and-skipped
  (testing "a vector :fx entry whose head is NOT a keyword (`[\"str\" {}]`) is a
            shape violation — :rf.error/effect-map-shape, that entry skipped,
            siblings still run — NOT mis-reported as an unknown fx-id"
    (let [traces (collect-traces! ::fx-entry-non-kw)
          errors (collect-errors! ::fx-entry-non-kw-errors)
          fired  (atom [])]
      (rf/reg-fx :fx-test/entry-nonkw-sibling
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-non-keyword-head
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/entry-nonkw-sibling {:k 1}]
                ["not-a-keyword" {:x 1}]            ;; bad fx-id TYPE, not unknown id
                [:fx-test/entry-nonkw-sibling {:k 2}]]}))
      (is (nil? (rf/dispatch-sync [:fx-test/entry-non-keyword-head]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace  ::fx-entry-non-kw)
      (rf/unregister-listener! :errors ::fx-entry-non-kw-errors)
      (is (= [{:k 1} {:k 2}] @fired)
          "both well-shaped siblings fired in order; the bad-head entry was skipped")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed; only the malformed entry was dropped")
      ;; The diagnostic must be the SHAPE category, not no-such-fx — a string
      ;; head is a shape violation, not an unregistered keyword id.
      ;;
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). This negative is asserted on
      ;; the ALWAYS-ON `:errors` axis, not over the dev trace ring: an empty
      ;; ring would satisfy `empty?` under the gate for free, whereas the
      ;; `:errors` axis is live in both postures (the positive twin is
      ;; `unknown-fx-id-is-logged-and-skipped`, which sees a record there).
      ;; So "the bad-head entry was classified as a SHAPE violation and never
      ;; reached fx lookup" is now proven under `-Dre-frame.debug=false`.
      (is (empty? (filter #(= :rf.error/no-such-fx (:error %)) @errors))
          "a non-keyword head is NOT mis-reported as :rf.error/no-such-fx (always-on axis)")
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/no-such-fx (:operation %)) @traces))
            "a non-keyword head is NOT mis-reported as :rf.error/no-such-fx")
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the bad-head entry")
          (let [t (first shape-traces)]
            (is (= :error (:op-type t)))
            (is (= :logged-and-skipped (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key])))
            (is (= :fx-test/entry-non-keyword-head (get-in t [:tags :rf.trace/event-id]))
                ":event-id names the offending handler")
            (is (= :rf/default (get-in t [:tags :frame])))
            (is (= ["not-a-keyword" {:x 1}] (get-in t [:tags :value]))
                ":value carries the offending entry verbatim")
            (is (string? (get-in t [:tags :reason])))
            (is (re-find #"fx-id" (get-in t [:tags :reason]))
                ":reason flags the bad fx-id (the first element)")))))))

(deftest fx-entry-surplus-third-field-is-policed-not-truncated
  (testing "a vector :fx entry with a surplus 3rd field
            (`[:fx {:used true} {:dropped true}]`) is a shape violation —
            :rf.error/effect-map-shape, that entry skipped, siblings still run —
            NOT silently truncated to a 2-tuple by `handle-one-fx`"
    (let [traces (collect-traces! ::fx-entry-arity3)
          fired  (atom [])]
      (rf/reg-fx :fx-test/entry-arity3-sink
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-surplus-field
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           :fx [[:fx-test/entry-arity3-sink {:k 1}]
                [:fx-test/entry-arity3-sink {:used true} {:dropped true}]
                [:fx-test/entry-arity3-sink {:k 2}]]}))
      (is (nil? (rf/dispatch-sync [:fx-test/entry-surplus-field]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace ::fx-entry-arity3)
      ;; CRITICAL: the 3-element entry must NOT fire as a silently-truncated
      ;; 2-tuple `[:fx-test/entry-arity3-sink {:used true}]`. Only the two
      ;; well-shaped siblings run.
      (is (= [{:k 1} {:k 2}] @fired)
          "the surplus-field entry was dropped (NOT truncated + fired); siblings ran")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db committed; only the malformed entry was dropped")
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace for the surplus-field entry")
          (let [t (first shape-traces)]
            (is (= :logged-and-skipped (:recovery t)))
            (is (= :fx (get-in t [:tags :offending-key])))
            (is (= :fx-test/entry-surplus-field (get-in t [:tags :rf.trace/event-id])))
            (is (= [:fx-test/entry-arity3-sink {:used true} {:dropped true}]
                   (get-in t [:tags :value]))
                ":value carries the full over-long entry verbatim")
            (is (string? (get-in t [:tags :reason])))
            (is (re-find #"two elements|surplus" (get-in t [:tags :reason]))
                ":reason flags the surplus element(s)")))))))

(deftest fx-entry-no-args-shorthand-is-valid
  (testing "the 1-arity no-args shorthand `[:fx-id]` is a VALID entry — it
            fires (args nil) and emits NO :rf.error/effect-map-shape trace"
    ;; Pins the lower bound of the tightened arity check: 1-element vectors
    ;; with a keyword head are the documented no-args shorthand, NOT a shape
    ;; violation. (The upper bound — arity ≥ 3 — is pinned above.)
    (let [traces (collect-traces! ::fx-entry-noargs)
          fired  (atom [])]
      (rf/reg-fx :fx-test/noargs-sink
        (fn [_ args] (swap! fired conj args)))
      (rf/reg-event :fx-test/entry-noargs
        (fn [_ _] {:fx [[:fx-test/noargs-sink]]}))
      (rf/dispatch-sync [:fx-test/entry-noargs])
      (rf/unregister-listener! :trace ::fx-entry-noargs)
      (is (= [nil] @fired)
          "the no-args shorthand fired once with nil args")
      ;; rf2-d2841 — dev-instrumentation arm (negative over the trace ring).
      ;; The production-visible half of "it is VALID" is the `[nil] @fired`
      ;; assertion above: an entry policed as malformed would be dropped and
      ;; would never have fired at all.
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/effect-map-shape (:operation %)) @traces))
            "the no-args `[:fx-id]` shorthand emits NO shape trace — it is valid")))))

;; ---- 6e. RESERVED-fx 3-element entry is policed loudly (rf2-tbuov) ---------
;;
;; rf2-tbuov: the canonical malformation in the wild is a surplus 3rd element
;; on a RESERVED `:dispatch` fx entry — `[:dispatch [:ev] {:frame host-frame}]`
;; (someone mistook the per-frame-targeting slot for a positional 3rd arg; the
;; correct idiom is the 2-element `[:dispatch [:ev]]` with the frame inherited
;; from the dispatching envelope per Spec 002 §Cascade propagation). Before
;; rf2-18kwf this leaked past `fx-entry-ok?` as "any non-empty vector" into
;; `handle-one-fx`, whose `[original-fx-id args]` destructure SILENTLY DROPPED
;; the `{:frame ...}` slot — so the dispatch DID fire (truncated), and any
;; intent encoded in the 3rd slot vanished with no diagnostic. The arity-3
;; tests above pin the generic USER-fx case; THIS pins the reserved `:dispatch`
;; case the bead + w3ver actually hit, end-to-end through `dispatch-sync`'s
;; drain. Mike's ruling (rf2-tbuov): LOUD FAILURE — emit
;; :rf.error/effect-map-shape and drop the whole entry; do NOT silently fire a
;; truncated 2-tuple.

(deftest reserved-dispatch-three-element-entry-is-policed-loudly
  (testing "a 3-element reserved `[:dispatch [:ev] {:frame …}]` fx entry is a
            shape violation — :rf.error/effect-map-shape (loud), the whole
            entry dropped, NOT silently truncated-and-fired as `[:dispatch [:ev]]`"
    (let [traces     (collect-traces! ::reserved-dispatch-arity3)
          target-ran (atom 0)]
      (rf/reg-event :fx-test.tbuov/target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :fx-test.tbuov/emits-malformed-dispatch
        (fn [{:keys [db]} _]
          {:db (assoc db :seeded? true)
           ;; The exact wild malformation: a surplus `{:frame …}` 3rd slot on
           ;; a reserved :dispatch entry (the form w3ver hit in the xray runner).
           :fx [[:dispatch [:fx-test.tbuov/target] {:frame :rf/default}]]}))
      (is (nil? (rf/dispatch-sync [:fx-test.tbuov/emits-malformed-dispatch]))
          "dispatch returns normally — no uncaught host exception")
      (rf/unregister-listener! :trace ::reserved-dispatch-arity3)
      ;; CRITICAL: the malformed entry must NOT silently fire as a truncated
      ;; 2-tuple `[:dispatch [:fx-test.tbuov/target]]`. The queued target must
      ;; never have run.
      (is (= 0 @target-ran)
          "the malformed 3-element :dispatch was DROPPED, not truncated-and-fired")
      (is (= true (:seeded? (rf/app-db-value :rf/default)))
          ":db still committed; only the malformed fx entry was dropped")
      ;; rf2-d2841 — dev-instrumentation arm (see the §6 posture note above).
      ;; "LOUD, not silent" has two halves and only one of them is a trace:
      ;; the DROP (`@target-ran` = 0, above) is the production-real half and
      ;; is what distinguishes rf2-tbuov's ruling from the old silent
      ;; truncate-and-fire. The diagnostic's own loudness is dev-channel.
      (when interop/debug-enabled?
        (let [shape-traces (filter #(= :rf.error/effect-map-shape (:operation %))
                                   @traces)]
          (is (= 1 (count shape-traces))
              "exactly one :rf.error/effect-map-shape trace — LOUD, not silent")
          (let [t (first shape-traces)]
            (is (= :error (:op-type t)))
            (is (= :logged-and-skipped (:recovery t))
                "recovery is logged-and-skipped — recover/rollback posture kept, but NOT silent")
            (is (= :fx (get-in t [:tags :offending-key])))
            (is (= :fx-test.tbuov/emits-malformed-dispatch
                   (get-in t [:tags :rf.trace/event-id]))
                ":event-id names the offending handler")
            (is (= [:dispatch [:fx-test.tbuov/target] {:frame :rf/default}]
                   (get-in t [:tags :value]))
                ":value carries the full malformed entry verbatim")
            (is (string? (get-in t [:tags :reason]))
                ":reason is a human-facing diagnostic")))))))

;; ---- 7. :fx-overrides function-value branch -------------------------------
;;
;; Per Spec 002 §`:fx-overrides` §Pattern-level contract vs CLJS reference:
;; the CLJS reference accepts function-valued overrides — `(fn [m args] ...)` —
;; as a one-off lambda affordance for tests and story fixtures. The signature
;; matches the registered-fx handler shape, so a user can either point at
;; another registered fx (id-redirect) or hand a lambda in-line. The pattern-
;; level contract narrows to id-only for SSR-portability; the CLJS reference
;; (this code; `.cljc` so JVM tests run too) supports both.
;;
;; Spec/002's conceptual resolution sketch (§1080-1088):
;;   (nil? override)        → no override
;;   (keyword? override)    → id-redirect via registrar
;;   (fn? override)         → run the fn in place of the original fx
;;
;; The three sub-tests pin the contract:
;;   1. fn-value runs in place of the original; the original does NOT fire.
;;   2. id-redirect form (regression: existing pattern-level path still works).
;;   3. nil-value (or missing key) → no override active; original fires.

(deftest fx-overrides-fn-value-branch
  (testing "function-value override fires in place of the registered fx"
    (let [original-fired (atom 0)
          override-args  (atom nil)
          override-ctx   (atom nil)]
      (rf/reg-fx :fx-test/http
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! original-fired inc)))
      (rf/reg-event :fx-test/issue-request
        (fn [_ _] {:fx [[:fx-test/http {:method :get :url "/me"}]]}))
      (rf/dispatch-sync
        [:fx-test/issue-request]
        {:fx-overrides {:fx-test/http (fn [m args]
                                        (reset! override-ctx m)
                                        (reset! override-args args)
                                        {:status 200 :body {:user/id 42}})}})
      (is (= 0 @original-fired)
          "the registered :fx-test/http MUST NOT fire when overridden by a fn")
      (is (= {:method :get :url "/me"} @override-args)
          "the fn override receives the same args the registered fx would")
      (is (= :rf/default (:frame @override-ctx))
          "the fn override receives the standard ctx map (frame, optional :event)")))

  (testing "id-redirect form (regression: pattern-level pathway still works)"
    (let [fired (atom [])]
      (rf/reg-fx :fx-test/http-redir
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :original)))
      (rf/reg-fx :fx-test/http-redir-stub
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired conj :stub)))
      (rf/reg-event :fx-test/issue-redir
        (fn [_ _] {:fx [[:fx-test/http-redir {}]]}))
      (rf/dispatch-sync
        [:fx-test/issue-redir]
        {:fx-overrides {:fx-test/http-redir :fx-test/http-redir-stub}})
      (is (= [:stub] @fired)
          "the id-redirect form still resolves to the stub fx; the original is not invoked")))

  (testing "nil-valued override falls through to the original fx"
    ;; Per spec/002 §`:fx-overrides`: a `nil`-or-missing value is treated
    ;; as "no override active" — `(get overrides id)` returns nil in both
    ;; cases. The original registered fx fires.
    (let [fired (atom 0)]
      (rf/reg-fx :fx-test/http-nil-override
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! fired inc)))
      (rf/reg-event :fx-test/issue-nil-override
        (fn [_ _] {:fx [[:fx-test/http-nil-override {}]]}))
      (rf/dispatch-sync
        [:fx-test/issue-nil-override]
        {:fx-overrides {:fx-test/http-nil-override nil}})
      (is (= 1 @fired)
          "a nil-valued override is a no-op; the original registered fx fires"))))

(deftest fx-overrides-fn-value-emits-applied-trace
  (testing "fn-value override emits :rf.fx/override-applied (per spec/009)"
    (let [traces         (collect-traces! ::fn-override-trace)
          original-fired (atom 0)
          override-fired (atom 0)]
      (rf/reg-fx :fx-test/http-traced
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! original-fired inc)))
      (rf/reg-event :fx-test/issue-traced
        (fn [_ _] {:fx [[:fx-test/http-traced {}]]}))
      (rf/dispatch-sync
        [:fx-test/issue-traced]
        {:fx-overrides {:fx-test/http-traced (fn [_ _] (swap! override-fired inc) :ok)}})
      (rf/unregister-listener! :trace ::fn-override-trace)
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). The trace is `:rf.fx/override-
      ;; applied`, a bare `trace/emit!` with no always-on twin — but the FACT
      ;; the trace reports ("the override applied, exactly once, in place of
      ;; the original") is a fact about EXECUTION, so count the bodies. Without
      ;; this the deftest would execute nothing under the production gate.
      (is (= 1 @override-fired)
          "the fn-value override body ran exactly once")
      (is (= 0 @original-fired)
          "the registered fx did NOT also run — the override replaced it")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [applied (filter #(= :rf.fx/override-applied (:operation %)) @traces)]
          (is (= 1 (count applied))
              "exactly one :rf.fx/override-applied trace for the fn-value override")
          (let [t (first applied)]
            (is (= :fx-test/http-traced (get-in t [:tags :rf.fx/from]))
                ":rf.fx/from carries the original fx-id")))))))

(deftest fx-overrides-fn-value-receives-origin-event
  (testing "fn-value override receives :event in ctx when the dispatch carries one"
    ;; Per Spec 014 §Reply addressing the originating event is threaded
    ;; through to fx handlers as `:event` on their ctx; the fn-value
    ;; override branch follows the same code path, so the ctx shape is
    ;; identical to a registered-fx handler.
    (let [captured-ctx (atom nil)]
      (rf/reg-fx :fx-test/http-with-event
                 {:platforms #{:client :server}}
                 (fn [_ _] nil))
      (rf/reg-event :fx-test/issue-with-event
        (fn [_ _] {:fx [[:fx-test/http-with-event {:url "/x"}]]}))
      (rf/dispatch-sync
        [:fx-test/issue-with-event :payload-1]
        {:fx-overrides {:fx-test/http-with-event
                        (fn [m _] (reset! captured-ctx m))}})
      (is (= :rf/default (:frame @captured-ctx))
          "ctx :frame is present")
      (is (= [:fx-test/issue-with-event :payload-1]
             (:event @captured-ctx))
          "ctx :event is the originating event vector"))))

;; ---- 7b. :fx-overrides fn-value of a RESERVED fx-id (rf2-nrpj1) ------------
;;
;; The four reserved fx-ids (:dispatch, :dispatch-later, :rf.fx/reg-flow,
;; :rf.fx/clear-flow) live only in fx.cljc's `reserved-fx-handlers` table —
;; they are NOT in the registrar. Before rf2-nrpj1 a function-value override
;; of a reserved fx-id was SILENTLY IGNORED: the reserved body ran while the
;; override fn never fired AND a misleading :rf.fx/override-applied trace was
;; still emitted. Mike ruled (2026-06-01) Option A — HONOUR fn-value
;; :fx-overrides of reserved fx-ids: the override fn pre-empts the reserved
;; body (matches spec/002 §`:fx-overrides` resolution model, where
;; `(fn? override) → override` runs in place of the registered fx), and the
;; :rf.fx/override-applied trace fires only when the override actually
;; applies. This enables reserved-fx stubbing in tests/stories (e.g.
;; capturing dispatches without queueing them).

(deftest reserved-fx-fn-value-override-pre-empts-reserved-body
  (testing ":dispatch fn-value override FIRES + the real :dispatch does NOT run"
    ;; (fn [m args] (record! args)) — the natural test stub that captures the
    ;; dispatched event vector without round-tripping it through the queue.
    (let [captured-args (atom nil)
          captured-ctx  (atom nil)
          target-ran    (atom 0)]
      (rf/reg-event :fx-test.nrpj1/target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :fx-test.nrpj1/emits-dispatch
        (fn [_ _] {:fx [[:dispatch [:fx-test.nrpj1/target :payload]]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/emits-dispatch]
        {:fx-overrides {:dispatch (fn [m args]
                                    (reset! captured-ctx m)
                                    (reset! captured-args args))}})
      (is (= [:fx-test.nrpj1/target :payload] @captured-args)
          "the fn-value override fires and receives the :dispatch args (the event vector)")
      (is (= :rf/default (:frame @captured-ctx))
          "the override receives the standard fx-handler ctx map (frame, …)")
      (is (= 0 @target-ran)
          "the real reserved :dispatch body MUST NOT run — the override pre-empts it")))

  (testing ":dispatch-later fn-value override pre-empts the reserved body too"
    (let [later-args (atom nil)]
      (rf/reg-event :fx-test.nrpj1/emits-later
        (fn [_ _] {:fx [[:dispatch-later {:ms 50 :event [:fx-test.nrpj1/target]}]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/emits-later]
        {:fx-overrides {:dispatch-later (fn [_ args] (reset! later-args args))}})
      (is (= {:ms 50 :event [:fx-test.nrpj1/target]} @later-args)
          "the fn-value override of :dispatch-later fires with the reserved-fx args"))))

(deftest reserved-fx-fn-value-override-trace-honesty
  (testing ":rf.fx/override-applied fires only when the reserved-fx override truly applies"
    ;; rf2-nnma3: the trace previously fired at resolution time even though
    ;; the reserved body ran instead. It now rides the actual override-fn
    ;; invocation — fires when the override applies, absent otherwise.
    (let [traces     (collect-traces! ::reserved-override-trace)
          stub-fired (atom 0)
          sink-ran   (atom 0)]
      (rf/reg-event :fx-test.nrpj1/sink
        (fn [{:keys [db]} _] (swap! sink-ran inc) {:db db}))
      (rf/reg-event :fx-test.nrpj1/traced-dispatch
        (fn [_ _] {:fx [[:dispatch [:fx-test.nrpj1/sink]]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/traced-dispatch]
        {:fx-overrides {:dispatch (fn [_ _] (swap! stub-fired inc) :stubbed)}})
      (rf/unregister-listener! :trace ::reserved-override-trace)
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). "The trace fires only when
      ;; the override TRULY applies" presupposes a fact about execution — that
      ;; the override ran and the reserved body did not — and that fact is
      ;; production-real. The rf2-nnma3 regression (trace at resolution time
      ;; while the reserved body ran instead) is exactly a divergence between
      ;; these two counters and the trace, so pinning them here is the
      ;; substantive half of "trace honesty".
      (is (= 1 @stub-fired)
          "the :dispatch override body ran exactly once")
      (is (= 0 @sink-ran)
          "the reserved :dispatch body did NOT run — nothing was queued")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [applied (filter #(= :rf.fx/override-applied (:operation %)) @traces)]
          (is (= 1 (count applied))
              "exactly one :rf.fx/override-applied trace — emitted because the override fired")
          (is (= :dispatch (get-in (first applied) [:tags :rf.fx/from]))
              ":rf.fx/from carries the reserved fx-id that was overridden"))))))

(deftest reserved-fx-with-no-override-runs-reserved-body
  (testing "a reserved :dispatch with NO override still queues + runs the real event"
    ;; Regression guard: the fn-value-override detection must not perturb the
    ;; ordinary reserved-fx path. With no :fx-overrides, :dispatch runs its
    ;; reserved body and the target event handler fires.
    (let [target-ran (atom 0)]
      (rf/reg-event :fx-test.nrpj1/plain-target
        (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :fx-test.nrpj1/plain-dispatch
        (fn [_ _] {:fx [[:dispatch [:fx-test.nrpj1/plain-target]]]}))
      (rf/dispatch-sync [:fx-test.nrpj1/plain-dispatch])
      (is (= 1 @target-ran)
          "the reserved :dispatch body ran and the queued event drained"))))

(deftest reserved-fx-id-redirect-override-unchanged
  (testing "id-redirect TO a reserved fx-id still falls through (reserved ids aren't registered)"
    ;; Per the bead: {:my-fx :dispatch} → (registrar/lookup :fx :dispatch) is
    ;; nil → :rf.error/override-fallthrough → runs the original :my-fx. The
    ;; reserved fx-ids live only in `reserved-fx-handlers`, not the registrar,
    ;; so a keyword-redirect targeting one is a coherent, surfaced no-op. This
    ;; behaviour is intentionally UNCHANGED by rf2-nrpj1.
    (let [original-fired (atom 0)
          traces         (collect-traces! ::redirect-to-reserved)
          errors         (collect-errors! ::redirect-to-reserved-errors)]
      (rf/reg-fx :fx-test.nrpj1/my-fx
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! original-fired inc)))
      (rf/reg-event :fx-test.nrpj1/issue-redirect
        (fn [_ _] {:fx [[:fx-test.nrpj1/my-fx {}]]}))
      (rf/dispatch-sync
        [:fx-test.nrpj1/issue-redirect]
        {:fx-overrides {:fx-test.nrpj1/my-fx :dispatch}})
      (rf/unregister-listener! :trace  ::redirect-to-reserved)
      (rf/unregister-listener! :errors ::redirect-to-reserved-errors)
      (is (= 1 @original-fired)
          "the original :fx-test.nrpj1/my-fx ran — the redirect to the un-registered :dispatch fell through")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). `:rf.error/override-fallthrough`
      ;; is ALWAYS-ON (Spec 009 §Observability channels) and its emit stamps
      ;; `:failing-id` = the ORIGINAL fx-id, which differs from the dispatched
      ;; `:event-id`, so `emit-error-both!` lifts `:failing-id` + `:reason`
      ;; onto the production record. The "coherent, surfaced no-op" this
      ;; deftest is named for is therefore surfaced under the gate too.
      (let [records (filter #(= :rf.error/override-fallthrough (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on record surfaced the un-registered redirect target")
        (let [r (first records)]
          (is (= :fx-test.nrpj1/my-fx (:failing-id r))
              ":failing-id names the fx whose override fell through")
          (is (= :fx-test.nrpj1/issue-redirect (:event-id r)))
          (is (= :rf/default (:frame r)))
          (is (re-find #"not registered" (:reason r))
              ":reason names the un-registered redirect target as the cause")))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [fallthrough (filter #(= :rf.error/override-fallthrough (:operation %)) @traces)]
          (is (= 1 (count fallthrough))
              "exactly one :rf.error/override-fallthrough trace surfaced the un-registered redirect target"))))))

(deftest malformed-fx-override-value-fails-loud
  (testing "a non-fn / non-keyword / non-nil :fx-overrides value (number / string
            / map) emits :rf.error/override-fallthrough and runs the original fx —
            no longer silently swallowed (rf2-3az1vn P2)"
    (doseq [bad-value [42 "not-an-override" {:also :bad} [:vec :tor]]]
      (let [original-fired (atom 0)
            traces         (collect-traces! ::malformed-override)
            errors         (collect-errors! ::malformed-override-errors)]
        (rf/reg-fx :fx-test.3az1vn/target
                   {:platforms #{:client :server}}
                   (fn [_ _] (swap! original-fired inc)))
        (rf/reg-event :fx-test.3az1vn/issue
          (fn [_ _] {:fx [[:fx-test.3az1vn/target {}]]}))
        (rf/dispatch-sync
          [:fx-test.3az1vn/issue]
          {:fx-overrides {:fx-test.3az1vn/target bad-value}})
        (rf/unregister-listener! :trace  ::malformed-override)
        (rf/unregister-listener! :errors ::malformed-override-errors)
        (is (= 1 @original-fired)
            (str "the original fx ran (recovery :replaced-with-default) for "
                 (pr-str bad-value)))
        ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). "No longer silently
        ;; swallowed" is rf2-3az1vn's whole point, and `:rf.error/override-
        ;; fallthrough` is ALWAYS-ON — so the loudness is assertable under
        ;; `-Dre-frame.debug=false`, where a misconfigured override map is
        ;; most likely to be discovered.
        (let [records (filter #(= :rf.error/override-fallthrough (:error %)) @errors)]
          (is (= 1 (count records))
              (str "exactly ONE always-on record surfaced the malformed override value "
                   (pr-str bad-value)))
          (is (= :fx-test.3az1vn/target (:failing-id (first records)))
              (str ":failing-id names the overridden fx for " (pr-str bad-value)))
          (is (re-find #"not a valid" (:reason (first records)))
              (str ":reason states the value is not a valid :fx-overrides value for "
                   (pr-str bad-value))))
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
        (when interop/debug-enabled?
          (let [fallthrough (filter #(= :rf.error/override-fallthrough (:operation %)) @traces)]
            (is (= 1 (count fallthrough))
                (str "exactly one :rf.error/override-fallthrough surfaced the malformed "
                     "override value " (pr-str bad-value)))))))))

(deftest nil-and-false-fx-override-values-stay-silent
  (testing "nil and false :fx-overrides values are the documented noop placeholder
            (spec/002 §`:fx-overrides`): silent fall-through to the original fx,
            NO :rf.error/override-fallthrough trace (rf2-3az1vn P2 — the silent
            no-op is intentionally KEPT)"
    (doseq [noop-value [nil false]]
      (let [original-fired (atom 0)
            traces         (collect-traces! ::noop-override)
            errors         (collect-errors! ::noop-override-errors)]
        (rf/reg-fx :fx-test.3az1vn/noop-target
                   {:platforms #{:client :server}}
                   (fn [_ _] (swap! original-fired inc)))
        (rf/reg-event :fx-test.3az1vn/noop-issue
          (fn [_ _] {:fx [[:fx-test.3az1vn/noop-target {}]]}))
        (rf/dispatch-sync
          [:fx-test.3az1vn/noop-issue]
          {:fx-overrides {:fx-test.3az1vn/noop-target noop-value}})
        (rf/unregister-listener! :trace  ::noop-override)
        (rf/unregister-listener! :errors ::noop-override-errors)
        (is (= 1 @original-fired)
            (str "the original fx ran for the noop placeholder " (pr-str noop-value)))
        ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). A NEGATIVE, so it has to be
        ;; asserted on an axis that is LIVE in the posture being tested — the
        ;; always-on `:errors` stream, whose positive twin is
        ;; `malformed-fx-override-value-fails-loud` directly above. Over the
        ;; dev trace ring this same claim passes for free under the gate.
        ;; "Silent" is a production-reachable promise: an app that ships the
        ;; `{:some-fx (when cond? stub)}` idiom must not spray records at its
        ;; off-box shipper on every dispatch.
        (is (empty? (filter #(= :rf.error/override-fallthrough (:error %)) @errors))
            (str "no override-fallthrough record on the ALWAYS-ON axis for the "
                 "documented noop value " (pr-str noop-value)))
        ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
        (when interop/debug-enabled?
          (let [fallthrough (filter #(= :rf.error/override-fallthrough (:operation %)) @traces)]
            (is (empty? fallthrough)
                (str "no override-fallthrough trace for the documented noop value "
                     (pr-str noop-value)))))))))

;; ---- 7c. reserved-fx OVERRIDE TIER (rf2-snsup5) ---------------------------
;;
;; Mike-ruled 2026-06-10 (option A, STATE-INSTALLATION criterion): a reserved
;; fx-id stays OVERRIDABLE when its body only routes dispatches / touches
;; host-browser state (`:dispatch`, `:dispatch-later`,
;; `:rf.machine/dispatch-to-system`, `:rf.nav/*`); it HARD-REJECTS the
;; override (emit :rf.error/reserved-fx-override + run the real reserved body)
;; when its body installs/clears durable frame runtime state
;; (`:rf.machine/spawn`, `:rf.machine/destroy`, `:rf.fx/reg-flow`,
;; `:rf.fx/clear-flow`, `:rf.route/with-nav-token`).
;; rf2-0qsp5: the source set has a SIXTH member on a DIFFERENT rationale —
;; the private `:rf.machine/join-dispatch` join-completion transport, which
;; writes NO runtime state and threads NO nav-token but must not be captured
;; or suppressed (rf2-2lzk8a). §7e pins that its diagnostic says so.
;; Three defence layers:
;;   (1) dev per-call reject in `handle-one-fx`  — emit + run reserved body
;;   (2) production prod-strip `strip-rejected-overrides` — drop keys loudly
;;   (3) cascade-exclusion in `child-dispatch-opts` — never inherit a
;;       reject-tier override into a `[:dispatch …]` child.

(deftest reject-tier-fn-value-override-ignored-reserved-body-runs
  (testing "fn-value override of :rf.fx/reg-flow is IGNORED; the flow IS registered"
    ;; :rf.fx/reg-flow installs durable per-frame flow-registry state (reject
    ;; tier). A fn-value override that stubs it out would silently leave the
    ;; flow unregistered; the reject runs the reserved body so the flow lands.
    (let [traces        (collect-traces! ::reject-reg-flow)
          errors        (collect-errors! ::reject-reg-flow-errors)
          stub-fired    (atom 0)
          ;; rf2-bqstzr — :rf.fx/reg-flow carries the 3-slot triple
          ;; [flow-id metadata derive-fn].
          flow          [:fx-test.snsup5/a-flow
                         {:inputs [[:fx-test.snsup5 :seed]]
                          :output-path [:fx-test.snsup5 :out]}
                         (fn [_] 42)]]
      (rf/reg-event :fx-test.snsup5/install-flow
        (fn [_ _] {:fx [[:rf.fx/reg-flow flow]]}))
      (rf/dispatch-sync
        [:fx-test.snsup5/install-flow]
        {:fx-overrides {:rf.fx/reg-flow (fn [_ _] (swap! stub-fired inc))}})
      (rf/unregister-listener! :trace  ::reject-reg-flow)
      (rf/unregister-listener! :errors ::reject-reg-flow-errors)
      (is (= 0 @stub-fired)
          "the fn-value override stub MUST NOT fire — the reject pre-empts it")
      (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.snsup5/a-flow)
          "the reserved :rf.fx/reg-flow body ran — the flow is registered")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). `:rf.error/reserved-fx-override`
      ;; is ALWAYS-ON and stamps `:failing-id` = the rejected reserved fx-id,
      ;; distinct from `:event-id`, so both it and the id-specific `:reason`
      ;; ride the production record (Spec 009 §Component attribution).
      ;; `reject-tier-override-fans-out-on-always-on-axis` below pins the
      ;; record's full shape; this pins that the DEV per-call reject site
      ;; (`handle-one-fx`, not the prod-strip) reaches the same axis.
      (let [records (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on reserved-fx-override record")
        (is (= :rf.fx/reg-flow (:failing-id (first records)))
            ":failing-id names the rejected reserved fx-id in production too"))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). `:recovery`
      ;; and the `:rf.fx/id` tag spelling are trace-shape, not record-shape.
      (when interop/debug-enabled?
        (let [rejected (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces)]
          (is (= 1 (count rejected))
              "exactly one :rf.error/reserved-fx-override trace fired")
          (is (= :rf.fx/reg-flow (get-in (first rejected) [:tags :rf.fx/id]))
              ":rf.fx/id names the rejected reserved fx-id")
          (is (= :reserved-body-ran (:recovery (first rejected)))
              ":recovery is :reserved-body-ran")))))

  (testing "keyword-redirect override of a reject-tier id is ALSO ignored"
    ;; The reject covers both override shapes — fn-value AND keyword-redirect.
    (let [traces     (collect-traces! ::reject-redirect)
          errors     (collect-errors! ::reject-redirect-errors)
          redir-ran  (atom 0)]
      (rf/reg-fx :fx-test.snsup5/redir-target
                 {:platforms #{:client :server}}
                 (fn [_ _] (swap! redir-ran inc)))
      (rf/reg-event :fx-test.snsup5/install-flow-2
        (fn [_ _] {:fx [[:rf.fx/reg-flow [:fx-test.snsup5/b-flow {:inputs [[:fx-test.snsup5 :seed]] :output-path [:fx-test.snsup5 :b]} (fn [_] 1)]]]}))
      (rf/dispatch-sync
        [:fx-test.snsup5/install-flow-2]
        {:fx-overrides {:rf.fx/reg-flow :fx-test.snsup5/redir-target}})
      (rf/unregister-listener! :trace  ::reject-redirect)
      (rf/unregister-listener! :errors ::reject-redirect-errors)
      (is (= 0 @redir-ran)
          "the keyword-redirect target MUST NOT fire — the reject pre-empts it")
      (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.snsup5/b-flow)
          "the reserved :rf.fx/reg-flow body ran despite the redirect")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841) — the always-on axis, as above.
      (is (= 1 (count (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)))
          "one always-on reserved-fx-override record for the redirect form too")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (= 1 (count (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces)))
            "one :rf.error/reserved-fx-override trace fired for the redirect form too")))))

(deftest reject-tier-override-fans-out-on-always-on-axis
  ;; rf2-uh5ic5: the existing reserved-fx tests above assert the dev-only
  ;; TRACE surface (`:operation` via `collect-traces!`). But Spec 009 §Error
  ;; event catalogue marks `:rf.error/reserved-fx-override` as ALWAYS-ON — a
  ;; production-reachable misconfiguration MUST reach the corpus-wide
  ;; `register-error-listener!` substrate (surface #4, the off-box shipper
  ;; axis). This pins the always-on listener contract for the REAL producer
  ;; path (`fx.cljc`'s reject emit through `emit-fx-error!` →
  ;; `error-emit/dispatch-on-error!`), driven through the genuine
  ;; `dispatch-sync` → fx-walk path — NOT by calling the error substrate
  ;; directly. The companion prod-elision leg lives in
  ;; `re-frame.on-error-elision-prod-test` (proves it survives goog.DEBUG=false).
  (testing "the real fn-value reject producer fans :rf.error/reserved-fx-override
            out through register-error-listener! with event/id/frame context"
    (let [errors (collect-errors! ::reject-always-on)]
      (rf/reg-event :fx-test.uh5ic5/install-flow
        (fn [_ _] {:fx [[:rf.fx/reg-flow [:fx-test.uh5ic5/a-flow {:inputs [[:fx-test.uh5ic5 :seed]] :output-path [:fx-test.uh5ic5 :out]} (fn [_] 7)]]]}))
      (rf/dispatch-sync
        [:fx-test.uh5ic5/install-flow]
        {:fx-overrides {:rf.fx/reg-flow (fn [_ _] :should-not-fire)}})
      (rf/unregister-listener! :errors ::reject-always-on)
      (let [records (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on record reached register-error-listener!")
        (let [r (first records)]
          (is (= :rf.error/reserved-fx-override (:error r))
              "the always-on record carries the reserved-fx-override category")
          (is (= [:fx-test.uh5ic5/install-flow] (:event r))
              ":event carries the dispatched event vector")
          (is (= :fx-test.uh5ic5/install-flow (:event-id r))
              ":event-id is the dispatched event-vector head")
          (is (= :rf/default (:frame r))
              ":frame names the frame the override was rejected in")
          (is (number? (:time r)) ":time is a wall-clock millis number")))
      (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.uh5ic5/a-flow)
          "the reserved :rf.fx/reg-flow body still ran (recovery :reserved-body-ran)")))

  (testing "the keyword-redirect reject producer ALSO fans out on the always-on axis"
    (let [errors (collect-errors! ::reject-always-on-redir)]
      (rf/reg-fx :fx-test.uh5ic5/redir-target
                 {:platforms #{:client :server}}
                 (fn [_ _] :should-not-fire))
      (rf/reg-event :fx-test.uh5ic5/install-flow-2
        (fn [_ _] {:fx [[:rf.fx/reg-flow [:fx-test.uh5ic5/b-flow {:inputs [[:fx-test.uh5ic5 :seed]] :output-path [:fx-test.uh5ic5 :b]} (fn [_] 1)]]]}))
      (rf/dispatch-sync
        [:fx-test.uh5ic5/install-flow-2]
        {:fx-overrides {:rf.fx/reg-flow :fx-test.uh5ic5/redir-target}})
      (rf/unregister-listener! :errors ::reject-always-on-redir)
      (let [records (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)]
        (is (= 1 (count records))
            "one always-on record for the keyword-redirect reject form too")
        (is (= :fx-test.uh5ic5/install-flow-2 (:event-id (first records)))
            ":event-id is the dispatched event-vector head"))
      (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.uh5ic5/b-flow)
          "the reserved body still ran for the redirect form"))))

(deftest overridable-tier-unaffected-by-reject
  (testing "OVERRIDABLE reserved fxs (:dispatch) still honour fn-value overrides"
    ;; Regression guard: the reject must NOT perturb the rf2-nrpj1 contract for
    ;; the routing-primitive tier. :dispatch is OVERRIDABLE — its fn-value
    ;; override fires and pre-empts the reserved body.
    (let [captured   (atom nil)
          target-ran (atom 0)
          traces     (collect-traces! ::overridable-no-reject)
          errors     (collect-errors! ::overridable-no-reject-errors)]
      (rf/reg-event :fx-test.snsup5/target (fn [{:keys [db]} _] (swap! target-ran inc) {:db db}))
      (rf/reg-event :fx-test.snsup5/emits-dispatch
        (fn [_ _] {:fx [[:dispatch [:fx-test.snsup5/target]]]}))
      (rf/dispatch-sync
        [:fx-test.snsup5/emits-dispatch]
        {:fx-overrides {:dispatch (fn [_ ev] (reset! captured ev))}})
      (rf/unregister-listener! :trace  ::overridable-no-reject)
      (rf/unregister-listener! :errors ::overridable-no-reject-errors)
      (is (= [:fx-test.snsup5/target] @captured)
          "the :dispatch fn-value override fired (OVERRIDABLE tier — unchanged)")
      (is (= 0 @target-ran)
          "the reserved :dispatch body did NOT run — the override pre-empted it")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). A NEGATIVE, so it rides the
      ;; ALWAYS-ON axis where the category is live in both postures rather
      ;; than the dev ring where it would pass for free under the gate. The
      ;; tier boundary matters most in production: `strip-rejected-overrides`
      ;; runs there, and stripping `:dispatch` would break stubbed routing.
      (is (empty? (filter #(= :rf.error/reserved-fx-override (:error %)) @errors))
          "NO always-on reserved-fx-override record for an OVERRIDABLE id")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces))
            "NO :rf.error/reserved-fx-override fired for an OVERRIDABLE id")))))

(deftest production-prod-strip-drops-reject-tier-loudly
  (testing "strip-rejected-overrides removes reject-tier keys + emits one error per key"
    ;; The production prod-strip is exercised as a unit — the router calls it
    ;; only under `(not interop/debug-enabled?)`, so in the ordinary dev suite
    ;; the per-call reject is what fires on the dispatch path and this fn has
    ;; to be reached directly. Under `scripts/test-core-prod-gate.sh` the same
    ;; call IS the live router path, which is the posture this unit test was
    ;; always describing. The fn is pure-ish: filter the effective merged
    ;; override map, emit loudly.
    (let [traces   (collect-traces! ::prod-strip)
          errors   (collect-errors! ::prod-strip-errors)
          stub     (fn [_ _] :stub)
          stripped (fx/strip-rejected-overrides
                     {:rf.machine/spawn        stub        ;; reject
                      :rf.fx/reg-flow          :some-redir  ;; reject (keyword form)
                      :rf.route/with-nav-token stub        ;; reject
                      :dispatch                stub        ;; OVERRIDABLE — kept
                      :my-app/http             stub}       ;; user fx — kept
                     :rf/default
                     [:some/event])]
      (rf/unregister-listener! :trace  ::prod-strip)
      (rf/unregister-listener! :errors ::prod-strip-errors)
      (is (= #{:dispatch :my-app/http} (set (keys stripped)))
          "every reject-tier key is stripped; OVERRIDABLE + user keys survive")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841). "LOUDLY" is the deftest's
      ;; own claim and it must hold in the posture the fn exists for: the
      ;; always-on axis carries one record per stripped key, each naming its
      ;; own id through the lifted `:failing-id`.
      (let [records (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)]
        (is (= 3 (count records))
            "one always-on reserved-fx-override record per stripped reject-tier key")
        (is (= #{:rf.machine/spawn :rf.fx/reg-flow :rf.route/with-nav-token}
               (set (map :failing-id records)))
            "the three rejected ids are named on the production records")
        (is (every? #(and (string? (:reason %))
                          (re-find #"may NOT be overridden" (:reason %)))
                    records)
            "each production record carries the human-facing rejection reason"))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). `:where`,
      ;; which discriminates the prod-strip site from the per-call one, is a
      ;; trace-tag only — it is not lifted onto the always-on record.
      (when interop/debug-enabled?
        (let [rejected (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces)]
          (is (= 3 (count rejected))
              "one :rf.error/reserved-fx-override trace per stripped reject-tier key")
          (is (= #{:rf.machine/spawn :rf.fx/reg-flow :rf.route/with-nav-token}
                 (set (map #(get-in % [:tags :rf.fx/id]) rejected)))
              "the three rejected ids are named")
          (is (every? #(= :production-strip (get-in % [:tags :where])) rejected)
              ":where discriminates the production prod-strip site")))))

  (testing "strip-rejected-overrides is identity when no reject-tier key present"
    (let [m {:dispatch (fn [_ _]) :my-app/http (fn [_ _])}]
      (is (identical? m (fx/strip-rejected-overrides m :rf/default [:e]))
          "no churn: the same map is returned untouched on the dominant path"))))

(deftest reject-tier-nil-false-placeholder-stays-silent
  ;; rf2-x76af2.27: the reject-tier gates tested bare key PRESENCE
  ;; (`contains?`), so a reject-tier reserved id mapped to the documented
  ;; nil/false no-op placeholder (`{:rf.fx/reg-flow nil}` — the collapsed
  ;; `{:some-fx (when cond? stub)}` idiom) was treated as an ATTEMPTED override
  ;; and emitted a spurious `:rf.error/reserved-fx-override` onto the ALWAYS-ON
  ;; error channel per dispatched event. The reject tier now mirrors the
  ;; OVERRIDABLE tier's nil/false silent no-op treatment: nil/false falls
  ;; through silently; only a REAL fn/keyword override emits + is neutralised.
  (testing "dev per-call handle-one-fx: a nil/false reject-tier override falls
            through silently — NO :rf.error/reserved-fx-override on the trace
            OR always-on axes; the reserved :rf.fx/reg-flow body still runs"
    (doseq [noop-value [nil false]]
      (flows/reset-flows!)
      (let [traces (collect-traces! ::reject-noop-trace)
            errors (collect-errors! ::reject-noop-errors)
            flow   [:fx-test.x76af2-27/flow
                    {:inputs [[:fx-test.x76af2-27 :seed]]
                     :output-path [:fx-test.x76af2-27 :out]}
                    (fn [_] 1)]]
        (rf/reg-event :fx-test.x76af2-27/install-flow
          (fn [_ _] {:fx [[:rf.fx/reg-flow flow]]}))
        (rf/dispatch-sync
          [:fx-test.x76af2-27/install-flow]
          {:fx-overrides {:rf.fx/reg-flow noop-value}})
        (rf/unregister-listener! :trace  ::reject-noop-trace)
        (rf/unregister-listener! :errors ::reject-noop-errors)
        ;; The ALWAYS-ON leg is the load-bearing one and stays posture-
        ;; independent: rf2-x76af2.27's actual symptom was a spurious record
        ;; sprayed onto the production error channel PER DISPATCHED EVENT, so
        ;; silence has to be proven in the posture that channel serves.
        (is (empty? (filter #(= :rf.error/reserved-fx-override (:error %)) @errors))
            (str "NO reserved-fx-override on the always-on axis for the "
                 "nil/false placeholder " (pr-str noop-value)))
        (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.x76af2-27/flow)
            (str "the reserved :rf.fx/reg-flow body ran (silent fall-through) for "
                 (pr-str noop-value)))
        ;; rf2-d2841 — dev-instrumentation arm (negative over the trace ring).
        (when interop/debug-enabled?
          (is (empty? (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces))
              (str "NO reserved-fx-override trace for the nil/false placeholder "
                   (pr-str noop-value)))))))

  (testing "production prod-strip: a nil/false reject-tier override is NOT
            stripped and emits NO error; a REAL reject-tier override IS still
            stripped + emitted (one error, naming the real override only)"
    (let [traces   (collect-traces! ::reject-noop-strip)
          errors   (collect-errors! ::reject-noop-strip-errors)
          stub     (fn [_ _] :stub)
          stripped (fx/strip-rejected-overrides
                     {:rf.fx/reg-flow   nil    ;; reject-tier NO-OP — kept, silent
                      :rf.fx/clear-flow false  ;; reject-tier NO-OP — kept, silent
                      :rf.machine/spawn stub   ;; reject-tier REAL — stripped + emitted
                      :my-app/http      stub}  ;; user fx — kept
                     :rf/default
                     [:some/event])]
      (rf/unregister-listener! :trace  ::reject-noop-strip)
      (rf/unregister-listener! :errors ::reject-noop-strip-errors)
      (is (= {:rf.fx/reg-flow nil :rf.fx/clear-flow false :my-app/http stub}
             stripped)
          "the nil/false reject-tier placeholders fall through untouched; only the real override is stripped")
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841) — this is the prod-strip, so
      ;; its "exactly one, naming the REAL override" claim is asserted on the
      ;; axis that survives the gate.
      (let [records (filter #(= :rf.error/reserved-fx-override (:error %)) @errors)]
        (is (= 1 (count records))
            "exactly ONE always-on record — for the real :rf.machine/spawn override only")
        (is (= :rf.machine/spawn (:failing-id (first records)))
            "the production record names the REAL override, not the nil/false placeholders"))
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring).
      (when interop/debug-enabled?
        (let [rejected (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces)]
          (is (= 1 (count rejected))
              "exactly ONE reserved-fx-override — for the real :rf.machine/spawn override only")
          (is (= :rf.machine/spawn (get-in (first rejected) [:tags :rf.fx/id]))
              "the emitted error names the REAL override, not the nil/false placeholders")))))

  (testing "an all-nil/false reject-tier override map is returned by identity
            (no churn) — the guard sees no REAL reject-tier override"
    (let [m {:rf.fx/reg-flow nil :rf.fx/clear-flow false :dispatch (fn [_ _])}]
      (is (identical? m (fx/strip-rejected-overrides m :rf/default [:e]))
          "no real reject-tier override → identity return, no spurious churn"))))

(deftest cascade-exclusion-reject-tier-not-inherited
  (testing "a reject-tier override does NOT propagate into a [:dispatch …] child"
    ;; The cascade-exclusion (child-dispatch-opts) runs unconditionally (dev +
    ;; prod). A parent envelope carrying a reject-tier :fx-overrides must not
    ;; leak it onto the child dispatch's inherited overrides. We verify the
    ;; child's :rf.fx/reg-flow runs its reserved body (flow registered) — the
    ;; reject-tier override is gone by the time the child cascade resolves.
    (let [traces      (collect-traces! ::cascade-exclude)
          errors      (collect-errors! ::cascade-exclude-errors)
          child-stub  (atom 0)]
      (rf/reg-event :fx-test.snsup5/child-installs-flow
        (fn [_ _] {:fx [[:rf.fx/reg-flow [:fx-test.snsup5/child-flow {:inputs [[:fx-test.snsup5 :seed]] :output-path [:fx-test.snsup5 :child]} (fn [_] 7)]]]}))
      (rf/reg-event :fx-test.snsup5/parent-cascades
        (fn [_ _] {:fx [[:dispatch [:fx-test.snsup5/child-installs-flow]]]}))
      ;; The parent carries a reject-tier :rf.fx/reg-flow override. At the
      ;; parent it is rejected (per-call) AND excluded from the child opts.
      (rf/dispatch-sync
        [:fx-test.snsup5/parent-cascades]
        {:fx-overrides {:rf.fx/reg-flow (fn [_ _] (swap! child-stub inc))}})
      (rf/unregister-listener! :trace  ::cascade-exclude)
      (rf/unregister-listener! :errors ::cascade-exclude-errors)
      (is (= 0 @child-stub)
          "the reject-tier override did not fire in the child cascade either")
      (is (contains? (get (flows/flows-snapshot) :rf/default) :fx-test.snsup5/child-flow)
          "the CHILD's :rf.fx/reg-flow ran its reserved body — override not inherited")
      ;; The PARENT's :rf.fx had no :rf.fx/reg-flow entry of its own, so the
      ;; only reject site is the parent's per-call neutralisation of the
      ;; override key on resolution — NOT a second emit in the child cascade
      ;; (the override was excluded from the child opts, so the child never
      ;; sees it to reject). The child's :rf.fx/reg-flow resolves with NO
      ;; override → zero reject emits attributable to the child.
      ;;
      ;; PRODUCTION-VISIBLE WITNESS (rf2-d2841), and it is ATTRIBUTED rather
      ;; than a bare zero, because the total is genuinely posture-dependent
      ;; here. Under `-Dre-frame.debug=false` the router runs
      ;; `strip-rejected-overrides` over the PARENT's effective override map
      ;; (router.cljc §do-fx-for), which legitimately emits ONE always-on
      ;; record attributed to the parent event. What must be zero in BOTH
      ;; postures is any record attributed to the CHILD — that is exactly the
      ;; claim "the reject-tier override was excluded from the child opts,
      ;; not inherited-then-rejected", and asserting it this way makes the
      ;; cascade-exclusion load-bearing in the posture where the prod-strip
      ;; is the live path rather than a unit-tested one.
      (is (empty? (filter #(and (= :rf.error/reserved-fx-override (:error %))
                                (= :fx-test.snsup5/child-installs-flow (:event-id %)))
                          @errors))
          "no always-on reserved-fx-override attributed to the CHILD — the override was excluded from the child opts (not inherited-then-rejected)")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring). In the dev
      ;; posture there is no prod-strip at all, so the total is zero.
      (when interop/debug-enabled?
        (is (zero? (count (filter #(= :rf.error/reserved-fx-override (:operation %)) @traces)))
            "no :rf.error/reserved-fx-override fired — the override was excluded from the child opts (not inherited-then-rejected)")))))

;; ---- 7d. SOURCE vs TARGET policy separation (rf2-1w4af) -------------------
;;
;; `rejected-reserved-fx-ids` once served TWO policies conflated into one set:
;;   (1) non-overridable SOURCE  — an id whose real body may not be OVERRIDDEN.
;;   (2) non-redirectable TARGET — an id a keyword-redirect may not name.
;; A non-overridable source is NOT automatically a non-redirectable target: an
;; app can emit `:rf.machine/spawn` / `:rf.machine/destroy` directly, so a
;; custom effect may redirect to the same real handler. Only the private
;; `:rf.machine/join-dispatch` transport keeps BOTH policies (its own
;; self-recursion / framework-path rationale). The fixture reloads
;; `re-frame.machines`, so `:rf.machine/spawn` / `:rf.machine/destroy` are real
;; registrar fxs here.

(deftest source-nonoverridable-is-redirectable-target
  (testing "a NON-OVERRIDABLE SOURCE (:rf.machine/spawn / :rf.machine/destroy)
            is a REDIRECTABLE TARGET — a custom effect resolves to the real
            registered handler (was WRONGLY :protected-rejection when the two
            policies shared one set)"
    (doseq [id [:rf.machine/spawn :rf.machine/destroy]]
      (is (some? (registrar/lookup :fx id))
          (str "precondition: the machines artefact registered " id))
      (is (= {:disposition :applied-redirect :target id}
             (fx/classify-fx-override {:my/custom id} :my/custom))
          (str "a custom effect redirects to the registered " id " handler"))))

  (testing "the SAME ids remain NON-OVERRIDABLE SOURCES — a DIRECT override is
            stripped loudly (source policy intact)"
    (doseq [id [:rf.machine/spawn :rf.machine/destroy]]
      (is (= {} (fx/strip-rejected-overrides {id (fn [_ _] :stub)} :rf/default [:some/event]))
          (str "a direct " id " override is rejected/stripped"))))

  (testing "vice-versa: the private :rf.machine/join-dispatch transport stays a
            NON-REDIRECTABLE TARGET (independent redirect-specific rationale) AND
            a NON-OVERRIDABLE SOURCE — re-conflating the sets would break the
            redirect-target case above"
    (is (= {:disposition :protected-rejection :target :rf.machine/join-dispatch}
           (fx/classify-fx-override {:dispatch :rf.machine/join-dispatch} :dispatch))
        "redirecting canonical dispatch to :rf.machine/join-dispatch is still refused")
    (is (= {} (fx/strip-rejected-overrides
                {:rf.machine/join-dispatch (fn [_ _] :stub)} :rf/default [:some/event]))
        "a direct :rf.machine/join-dispatch override is still stripped")))

;; ---- 7e. the reject diagnostic's REASON is policy-accurate (rf2-0qsp5) -----
;;
;; The always-on `:rf.error/reserved-fx-override` `:reason` used to assert ONE
;; blanket rationale for the whole source set — "installs/clears durable frame
;; runtime state (or threads a correctness-critical nav-token)". That is FALSE
;; for `:rf.machine/join-dispatch`, which writes no runtime-db and threads no
;; nav-token: its source-policy membership is protection of a PRIVATE transport
;; from capture/suppression (rf2-2lzk8a). A programmer who overrode it was
;; handed the wrong reason on a production-reachable error channel.
;;
;; The reason is now id-specific. TAG SHAPE IS UNCHANGED — `:reason` was
;; already a string in `:tags`; only its content is derived per id, so no Spec
;; 009 catalogue / Spec-Schemas row moves on the shape axis.
;;
;; POSTURE (rf2-d2841). This whole section is PRODUCTION-REAL and needs no
;; guard. The premise in the paragraph above — "a programmer who overrode it
;; was handed the wrong reason on a production-reachable error channel" — is
;; only true if the `:reason` actually reaches that channel, and it does:
;; `emit-reserved-fx-override!` stamps `:failing-id` = the rejected fx-id,
;; which differs from `:event-id`, so `error-emit/emit-error-both!` lifts BOTH
;; `:failing-id` and `:reason` onto the always-on record (Spec 009 §Component
;; attribution). `reject-reason-for` therefore reads the string off the
;; ALWAYS-ON axis rather than the DCE'd trace tags, and every assertion below
;; runs unchanged under `-Dre-frame.debug=false`. Read off the dev trace these
;; were 21 NPEs under the gate (`re-find` on a nil reason) — the diagnostic
;; that production actually ships was never being checked.

(defn- reject-reason-for
  "The `:reason` string the reject diagnostic emits for `fx-id`, read off the
  real producer (the prod-strip emit) rather than reconstructed — and off the
  ALWAYS-ON `:errors` axis (rf2-d2841), which is the channel the reason's
  accuracy actually matters on and the one that survives
  `-Dre-frame.debug=false`."
  [fx-id]
  (let [errors (collect-errors! ::reject-reason)]
    (fx/strip-rejected-overrides {fx-id (fn [_ _] :stub)} :rf/default [:some/event])
    (rf/unregister-listener! :errors ::reject-reason)
    (->> @errors
         (filter #(= :rf.error/reserved-fx-override (:error %)))
         first
         :reason)))

(deftest reject-diagnostic-reason-is-id-specific
  (testing "the reason rides the ALWAYS-ON record, alongside the :failing-id it
            is about — the mechanism every assertion below depends on"
    ;; rf2-d2841: pin the lift itself, so a regression that dropped
    ;; `:failing-id` from `emit-reserved-fx-override!` (and with it the
    ;; `:reason`, which `emit-error-both!` lifts only when `:failing-id` is
    ;; present and distinct from `:event-id`) fails HERE with a clear cause
    ;; rather than as a wall of nil-reason NPEs below.
    (let [errors (collect-errors! ::reject-reason-lift)]
      (fx/strip-rejected-overrides {:rf.machine/spawn (fn [_ _] :stub)}
                                   :rf/default [:some/event])
      (rf/unregister-listener! :errors ::reject-reason-lift)
      (let [r (first (filter #(= :rf.error/reserved-fx-override (:error %)) @errors))]
        (is (some? r) "the reject reached the always-on error listener")
        (is (= :rf.machine/spawn (:failing-id r))
            ":failing-id names the rejected fx-id, DISTINCT from :event-id")
        (is (= :some/event (:event-id r))
            ":event-id still carries the dispatched event id")
        (is (string? (:reason r))
            ":reason was lifted onto the production record alongside :failing-id"))))

  (testing "join-dispatch's reason gives its OWN transport rationale and does
            NOT claim it installs runtime state or threads a nav-token"
    (let [reason (reject-reason-for :rf.machine/join-dispatch)]
      (is (string? reason) "a reason is emitted for :rf.machine/join-dispatch")
      (is (re-find #"(?i)transport" reason)
          "the reason names the private join-completion TRANSPORT rationale")
      (is (re-find #"(?i)captur|suppress" reason)
          "the reason names capture/suppression as the hazard")
      ;; The exact blanket clause the diagnostic used to assert for EVERY
      ;; member — the falsehood this bead removes.
      (is (not (re-find #"(?i)installs/clears durable" reason))
          "the old blanket state-installation clause is gone for join-dispatch")
      ;; Stronger than absence: the reason AFFIRMATIVELY disclaims both of the
      ;; rationales that do not apply, so a regression to the blanket clause
      ;; cannot pass by dropping words.
      (is (re-find #"(?i)installs NO runtime state" reason)
          "the reason states join-dispatch installs NO runtime state")
      (is (re-find #"(?i)threads NO nav-token" reason)
          "the reason states join-dispatch threads NO nav-token")))

  (testing "a state-installing member still gets its state-installation reason"
    (let [reason (reject-reason-for :rf.machine/spawn)]
      (is (re-find #"(?i)snapshot|runtime-db" reason)
          ":rf.machine/spawn's reason names the durable snapshot it installs")))

  (testing "the nav-token threader still gets the nav-token reason"
    (is (re-find #"(?i)nav-token" (reject-reason-for :rf.route/with-nav-token))
        ":rf.route/with-nav-token's reason names the nav-token"))

  (testing "every source-policy member has an id-specific reason — none falls
            back to the generic policy clause (rf2-1w4af set-membership pin)"
    (doseq [id [:rf.machine/spawn :rf.machine/destroy :rf.machine/join-dispatch
                :rf.fx/reg-flow :rf.fx/clear-flow :rf.route/with-nav-token]]
      (let [reason (reject-reason-for id)]
        (is (re-find (re-pattern (str "\\Q" id "\\E")) reason)
            (str "the reason names the offending id " id))
        (is (not (re-find #"is a non-overridable SOURCE \(" reason))
            (str id " has its own rationale line, not the generic fallback")))))

  (testing "the reason distinguishes the SOURCE policy from the TARGET policy —
            a rejected source is NOT thereby a forbidden redirect target"
    (is (re-find #"(?i)SOURCE policy ONLY" (reject-reason-for :rf.machine/spawn))
        "spawn's reason says redirecting a custom effect TO it stays permitted")
    (is (re-find #"(?i)ALSO the non-redirectable TARGET"
                 (reject-reason-for :rf.machine/join-dispatch))
        "join-dispatch's reason says it is additionally a refused redirect target")))

;; ---- rf2-twt7m Change 2 — :rf.fx/do-fx carries :fx + :db-present? ---------
;;
;; The handler's return shape is otherwise invisible at the trace level —
;; the :db value already rides through :rf.event/db-changed diffs (not
;; stamped on the do-fx marker because it can be huge), and the :fx
;; vector lives in the interceptor context's :effects slot for the
;; duration of the cascade but never makes it onto a trace. Per
;; rf2-twt7m Change 2 we now stamp :fx (the vector) and :db-present?
;; (boolean) onto the :rf.fx/do-fx marker that terminates the do-fx
;; walk. Stamps the SHAPE, not deep values; Event lens (rf2-zh2qc)
;; consumers can align cascade rows with handler returns.
;;
;; POSTURE (rf2-d2841). `:rf.fx/do-fx` is a bare `trace/emit!` marker with no
;; always-on twin — it exists FOR the Xray Event lens, a dev tool — so every
;; assertion about its tags rides a `(when interop/debug-enabled? …)` arm. What
;; the marker REPORTS, though, is a fact about the handler's return being
;; honoured, and that is production-real: each deftest below now pins the
;; corresponding execution fact posture-independently, so none of them is a
;; deftest that executes nothing under the production gate.

(deftest event-do-fx-stamps-fx-and-db-present
  (testing "a reg-event handler returning {:db ... :fx [...]} fires :rf.fx/do-fx
   with :fx (the vector) and :db-present? true under :tags (same slot
   placement as :frame — payload-shaped tags ride under :tags)"
    (let [fx-args (atom ::unfired)]
      (rf/reg-fx :fx-test/do-fx-shape (fn [_ args] (reset! fx-args args) :ok))
      (rf/reg-event :fx-test/returns-db-and-fx
        (fn [_ _]
          {:db {:seeded? true}
           :fx [[:fx-test/do-fx-shape {:k 1}]]}))
      (let [acc (collect-traces! ::do-fx-shape)]
        (try
          (rf/dispatch-sync [:fx-test/returns-db-and-fx])
          ;; Production-visible witness (rf2-d2841): the marker's two tags are
          ;; a REPORT of what the handler returned; the returns themselves are
          ;; observable without any trace surface.
          (is (= {:k 1} @fx-args)
              "the returned :fx entry actually fired with its args")
          (is (= true (:seeded? (rf/app-db-value :rf/default)))
              "the returned :db slot actually committed (:db-present? true's referent)")
          ;; rf2-d2841 — dev-instrumentation arm (see the section note above).
          (when interop/debug-enabled?
            (let [[dof] (filterv #(= :rf.fx/do-fx (:operation %)) @acc)
                  tags  (:tags dof)]
              (is (some? dof) ":rf.fx/do-fx fired")
              (is (= true (:rf.event/db-present? tags))
                  ":rf.event/db-present? true because the handler returned a :db slot")
              (is (= [[:fx-test/do-fx-shape {:k 1}]] (:rf.event/fx tags))
                  ":rf.event/fx vector matches what the handler returned")))
          (finally
            (rf/unregister-listener! :trace ::do-fx-shape)))))))

(deftest event-do-fx-stamps-when-only-fx-returned
  (testing "a reg-event handler returning {:fx [...]} only (no :db slot) stamps
   :db-present? false and :fx with the vector"
    (let [fx-fired (atom 0)]
      (rf/reg-fx :fx-test/no-db-fx (fn [_ _] (swap! fx-fired inc) :ok))
      (rf/reg-event :fx-test/db-seed
        (fn [{:keys [db]} _] {:db (assoc db :pre-existing :kept)}))
      (rf/reg-event :fx-test/fx-only
        (fn [_ _]
          {:fx [[:fx-test/no-db-fx {}]]}))
      (let [acc (collect-traces! ::no-db)]
        (try
          (rf/dispatch-sync [:fx-test/db-seed])
          (rf/dispatch-sync [:fx-test/fx-only])
          ;; Production-visible witness (rf2-d2841): "no :db slot" means the
          ;; commit path was not taken, which is observable as app-db being
          ;; left exactly as the previous event left it.
          (is (= 1 @fx-fired) "the returned :fx entry fired")
          (is (= :kept (:pre-existing (rf/app-db-value :rf/default)))
              "app-db is untouched by the :fx-only handler (:db-present? false's referent)")
          ;; rf2-d2841 — dev-instrumentation arm (see the section note above).
          (when interop/debug-enabled?
            (let [[dof] (filterv #(= :rf.fx/do-fx (:operation %)) @acc)
                  tags  (:tags dof)]
              (is (some? dof) ":rf.fx/do-fx fired")
              (is (= false (:rf.event/db-present? tags))
                  ":rf.event/db-present? false because the handler returned no :db slot")
              (is (= [[:fx-test/no-db-fx {}]] (:rf.event/fx tags))
                  ":rf.event/fx vector matches what the handler returned")))
          (finally
            (rf/unregister-listener! :trace ::no-db)))))))

(deftest event-do-fx-stamp-rides-under-tags
  (testing ":fx and :db-present? are payload-shaped slots — they ride
   under :tags alongside :frame (consistent with how every payload
   slot on a trace event is placed; top-level is reserved for
   substrate-hoisted slots like :rf.trace/call-site,
   :rf.trace/trigger-handler, :source, :recovery, :sensitive?)"
    (rf/reg-event :fx-test/top-level-shape
      (fn [_ _] {:db {:x 1} :fx []}))
    (let [acc (collect-traces! ::top-level)]
      (try
        (rf/dispatch-sync [:fx-test/top-level-shape])
        ;; Production-visible witness (rf2-d2841): an empty `:fx` vector is
        ;; still a legal return and the `:db` beside it must commit.
        (is (= 1 (:x (rf/app-db-value :rf/default)))
            "the {:db … :fx []} return committed its :db slot")
        ;; rf2-d2841 — dev-instrumentation arm. This deftest is about the
        ;; SLOT PLACEMENT of two dev-trace tags; that has no production
        ;; referent at all, so it is guarded whole.
        (when interop/debug-enabled?
          (let [[dof] (filterv #(= :rf.fx/do-fx (:operation %)) @acc)
                tags  (:tags dof)]
            (is (some? dof) ":rf.fx/do-fx fired")
            (is (contains? tags :rf.event/fx)          ":rf.event/fx lives under :tags")
            (is (contains? tags :rf.event/db-present?) ":rf.event/db-present? lives under :tags")
            (is (contains? tags :frame)                ":frame is alongside (pre-existing)")
            (is (not (contains? dof :rf.event/fx))
                ":rf.event/fx is NOT at top level")
            (is (not (contains? dof :rf.event/db-present?))
                ":rf.event/db-present? is NOT at top level")))
        (finally
          (rf/unregister-listener! :trace ::top-level))))))

;; ---- rf2-9dk9y: :coeffects stamp on :rf.event/run-end --------------------
;;
;; Per rf2-9dk9y (supersedes rf2-jhhqt) the user-injected subset of the
;; handler's final coeffects map rides under `:tags :rf.event/coeffects`
;; on :rf.event/run-end — NOT on :rf.fx/do-fx as it used to. The prior
;; placement silently dropped the stamp whenever a handler returned only
;; :db (no :fx), because the do-fx walk was short-circuited and the
;; marker never emitted. The Xray Event lens's COEFFECTS section was
;; therefore empty for a textbook fx-returning event like `:counter/inc`
;; that injected a cofx but returned only a :db slot. Pinning the stamp to
;; the always-fires run-end emit makes the COEFFECTS section render
;; uniformly across event flavours. The substrate-side filter
;; (`fx/user-injected-coeffects`) keeps the framework defaults
;; (:db :event :frame :source :trace-id) out at emit time.
;;
;; POSTURE (rf2-d2841). `:rf.event/run-end` is a dev-only `trace/emit!`, and
;; the stamp exists to feed the Xray Event lens — a dev tool — so the tag
;; assertions ride a `(when interop/debug-enabled? …)` arm. Underneath the
;; stamp sits a production-real fact: the declared `:rf.cofx/requires`
;; suppliers RAN and their values reached the handler body. Each deftest below
;; now pins that delivery posture-independently, so the cofx pipeline itself
;; is covered under the production gate even though its trace stamp is not.

(deftest event-run-end-stamps-user-injected-coeffects-with-fx
  (testing "a handler whose chain injects user coeffects (:now etc.)
   AND returns both :db + :fx sees them stamped under :tags
   :rf.event/coeffects on :rf.event/run-end; the framework defaults
   (:db :event :frame) are filtered out at the substrate"
    (rf/reg-fx :fx-test/cofx-sink (fn [_ _] :ok))
    (rf/reg-cofx :fx-test/now    (fn [] "2026-05-18T19:00:00Z"))
    (rf/reg-cofx :fx-test/locale (fn [] :en-AU))
    (let [delivered (atom nil)]
      (rf/reg-event :fx-test/uses-user-cofx
        {:rf.cofx/requires [:fx-test/now :fx-test/locale]}
        (fn [ctx _] (reset! delivered (select-keys ctx [:fx-test/now :fx-test/locale]))
                    {:db {:k 1}
                     :fx [[:fx-test/cofx-sink :go]]}))
      (let [acc (collect-traces! ::user-cofx)]
        (try
          (rf/dispatch-sync [:fx-test/uses-user-cofx])
          ;; Production-visible witness (rf2-d2841): the stamp REPORTS the
          ;; coeffect map the handler was given, and that delivery is
          ;; production-real — the `:rf.cofx/requires` suppliers run in both
          ;; postures. Pin what the handler body actually received.
          (is (= {:fx-test/now    "2026-05-18T19:00:00Z"
                  :fx-test/locale :en-AU}
                 @delivered)
              "both declared :rf.cofx/requires suppliers ran and reached the handler body")
          ;; rf2-d2841 — dev-instrumentation arm (see the section note above).
          (when interop/debug-enabled?
            (let [[re]  (filterv #(= :rf.event/run-end (:operation %)) @acc)
                  cofx  (get-in re [:tags :rf.event/coeffects])
                  [dof] (filterv #(= :rf.fx/do-fx (:operation %)) @acc)]
              (is (some? re) ":rf.event/run-end fired")
              (is (= {:fx-test/now    "2026-05-18T19:00:00Z"
                      :fx-test/locale :en-AU}
                     cofx)
                  "only the user-injected coeffects ride under :tags :rf.event/coeffects on run-end")
              (is (not (contains? cofx :db))    "framework :db NOT stamped")
              (is (not (contains? cofx :event)) "framework :event NOT stamped")
              (is (not (contains? cofx :frame)) "framework :frame NOT stamped")
              (is (not (contains? (:tags dof) :rf.event/coeffects))
                  "the stamp lives on run-end now — :rf.fx/do-fx no longer carries it")))
          (finally
            (rf/unregister-listener! :trace ::user-cofx)))))))

(deftest event-run-end-stamps-user-injected-coeffects-without-fx
  (testing "rf2-9dk9y bug A — a reg-event handler that injects user cofx and
   returns only {:db ...} (no :fx) STILL surfaces its coeffects on
   :rf.event/run-end. Was silently dropped under the prior do-fx-marker
   stamping (do-fx was short-circuited when the handler returned no :fx,
   so the COEFFECTS section was empty for textbook handlers like
   :counter/inc — the cofx never reached the Xray Event lens)"
    (rf/reg-cofx :fx-test/now (fn [] "2026-05-18T19:00:00Z"))
    (rf/reg-event :fx-test/db-only-with-cofx
      {:rf.cofx/requires [:fx-test/now]}
      (fn [{:keys [fx-test/now]} _]
        {:db {:stamped-at now}}))
    (let [acc (collect-traces! ::db-only-cofx)]
      (try
        (rf/dispatch-sync [:fx-test/db-only-with-cofx])
        ;; Production-visible witness (rf2-d2841): bug A's repro is a handler
        ;; that injects a cofx and returns ONLY `:db`. That the supplier ran
        ;; and its value reached the body is production-real and is visible
        ;; in app-db — the handler writes the injected value straight into it.
        (is (= "2026-05-18T19:00:00Z" (:stamped-at (rf/app-db-value :rf/default)))
            "the declared cofx supplier ran and its value reached the handler body")
        ;; rf2-d2841 — dev-instrumentation arm (see the section note above).
        (when interop/debug-enabled?
          (let [[re]  (filterv #(= :rf.event/run-end (:operation %)) @acc)
                cofx  (get-in re [:tags :rf.event/coeffects])
                dof   (first (filterv #(= :rf.fx/do-fx (:operation %)) @acc))]
            (is (some? re) ":rf.event/run-end fired (always — independent of :fx)")
            (is (= {:fx-test/now "2026-05-18T19:00:00Z"} cofx)
                "the user-injected cofx surfaces under :tags :rf.event/coeffects
                 EVEN THOUGH the handler returned no :fx — bug A's repro")
            (is (nil? dof)
                ":rf.fx/do-fx was correctly NOT emitted (no :fx vector); the
                 cofx stamp would have been dropped if it still rode on do-fx")))
        (finally
          (rf/unregister-listener! :trace ::db-only-cofx))))))

(deftest event-run-end-coeffects-stamp-absent-when-no-user-cofx
  (testing "a handler with no inject-cofx has its :rf.event/run-end fire
   WITHOUT a :rf.event/coeffects stamp (silent-by-default — distinct
   from a stamped empty map)"
    (rf/reg-event :fx-test/no-user-cofx
      (fn [{:keys [db]} _] {:db (assoc db :k 1)}))
    (let [acc (collect-traces! ::no-cofx)]
      (try
        (rf/dispatch-sync [:fx-test/no-user-cofx])
        ;; Production-visible witness (rf2-d2841): the handler ran to
        ;; completion — the precondition for "run-end fired" — and did so
        ;; without declaring any cofx.
        (is (= 1 (:k (rf/app-db-value :rf/default)))
            "the cofx-free handler ran and committed")
        ;; rf2-d2841 — dev-instrumentation arm. "The key is ABSENT from the
        ;; stamp" is a claim about a dev-only tag map with no production
        ;; referent; over an empty ring it would also pass vacuously.
        (when interop/debug-enabled?
          (let [[re]  (filterv #(= :rf.event/run-end (:operation %)) @acc)
                tags  (:tags re)]
            (is (some? re) ":rf.event/run-end fired")
            (is (not (contains? tags :rf.event/coeffects))
                ":rf.event/coeffects key ABSENT on :tags when no user cofx injected")))
        (finally
          (rf/unregister-listener! :trace ::no-cofx))))))

;; ---- reg-fx handler-required (rf2-x76af2.26) ------------------------------
;;
;; A `reg-fx` MUST supply a callable handler. The metadata-only form
;; `(reg-fx :id {…})` (a plausible typo) previously registered a nil
;; `:handler-fn` and failed LATE at fire-time as a misleading
;; `:rf.error/fx-handler-exception` (an NPE blaming the ABSENT handler for
;; THROWING). It now fails LOUD at REGISTRATION time — symmetric with
;; reg-cofx's registration-time missing-supplier rejection.

(deftest reg-fx-with-no-handler-is-registration-invalid
  (testing "`reg-fx` with metadata only and NO handler fn throws
            `:rf.error/fx-registration-invalid` at REGISTRATION time, naming
            the missing handler (rf2-x76af2.26). Mirrors reg-cofx's
            registration-time missing-supplier rejection — no more deferring
            to a misleading fire-time `:rf.error/fx-handler-exception`."
    (let [ex (try (rf/reg-fx :fx-test.x76af2-26/no-handler
                             {:doc "typo — handler omitted"})
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the handler-less registration threw at registration time")
      (is (= :rf.error/fx-registration-invalid (:rf.error/id (ex-data ex)))
          "rejected as a malformed fx registration shape, not a late fire-time NPE")
      (is (= :fx-test.x76af2-26/no-handler (:rf.fx/id (ex-data ex)))
          "the offending id rides the error payload")
      (is (re-find #"no handler" (:reason (ex-data ex)))
          "the reason names the missing handler")
      (is (nil? (registrar/lookup :fx :fx-test.x76af2-26/no-handler))
          "the handler-less fx did NOT register (no nil `:handler-fn` left behind)")))

  (testing "a non-callable positional value (a number typo'd as the handler)
            is ALSO rejected — the guard is `ifn?`, not merely non-nil"
    (let [ex (try (rf/reg-fx :fx-test.x76af2-26/bad-handler 42)
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "the non-callable-handler registration threw")
      (is (= :rf.error/fx-registration-invalid (:rf.error/id (ex-data ex)))
          "a non-IFn handler is a malformed registration shape")
      (is (nil? (registrar/lookup :fx :fx-test.x76af2-26/bad-handler))
          "the malformed fx did NOT register")))

  (testing "the well-formed `(reg-fx :id (fn …))` and `(reg-fx :id {…} (fn …))`
            shapes still register cleanly — the guard rejects only the
            handler-less / non-callable cases"
    (rf/reg-fx :fx-test.x76af2-26/ok-bare (fn [_ _] :ok))
    (rf/reg-fx :fx-test.x76af2-26/ok-meta {:doc "with meta"} (fn [_ _] :ok))
    (is (fn? (:handler-fn (registrar/lookup :fx :fx-test.x76af2-26/ok-bare)))
        "the bare-handler form registered its `:handler-fn`")
    (is (fn? (:handler-fn (registrar/lookup :fx :fx-test.x76af2-26/ok-meta)))
        "the metadata+handler form registered its `:handler-fn`")))
