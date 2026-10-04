(ns re-frame.router-carried-frame-test
  "The router honours the
  carried-invariant frame contract (Spec 002 §Frame target resolution —
  the carried invariant; EP-0002 §Dispatch And Router / Reference Impl
  Plan §3).

  Router envelope frame resolution order:
    1. explicit `{:frame …}` opt WINS (override);
    2. otherwise `rf.frame/require-current-frame!` reads the scope/hold stamp
       (`with-frame`, a `frame-provider` (SCOPE) or a `frame-root`
       (ENSURE) boundary, or a captured `*current-frame*` binding via a
       capture-frame);
    3. no frame ⇒ NO enqueue — the dispatch raises
       `:rf.error/no-frame-context` at envelope-build time, BEFORE any
       frame-registry lookup. There is no `:rf/default` floor.

  This file pins the EP §3 test matrix:
    - bare dispatch outside any context FAILS. An async bare dispatch after
      the scope unwinds is the same case: a captured thunk reads the
      ambient frame when it is invoked, not when it was captured;
    - dispatch under `with-frame` works;
    - frame-bound (held) dispatch after the scope unwinds works — pinned,
      together with `bind-fn`'s rebinding, by `re-frame.capture-frame-test`;
    - an explicit `{:frame …}` wins over the scope, and an explicit
      `{:frame :rf/default}` that is not registered raises
      `:rf.error/frame-destroyed`, NOT `:rf.error/no-frame-context` — a
      bad explicit target is a registry-lookup failure, a different
      category from absence.

  The `frame-provider` (React-context) tier is platform-specific and is
  exercised in `re-frame.router-carried-frame-cljs-test`.

  JVM-only — the dynamic-var scope tier and the require-or-raise logic
  are platform-agnostic.

  ## Posture split

  Both error categories this file asserts on are ALWAYS-ON, so the split here
  is NOT a guard — it is a change of AXIS. `:rf.error/no-frame-context` fans
  through `rf.frame/emit-no-frame-context!`, which calls the late-bound
  `:error-emit/dispatch-on-error` hook BEFORE it reaches the dev-only
  `rf.trace/emit-error!` leg; `:rf.error/frame-destroyed` is in the promoted
  always-on set (`re-frame.error-emit` ns docstring §Corpus-wide listener
  registry). Reading those counts off the `:errors` stream instead of the
  `:trace` stream keeps the assertions VERBATIM and load-bearing in BOTH
  postures — `clojure -M:test` and `scripts/test-core-prod-gate.sh` alike.

  The one genuinely dev-only claim is `NO :rf.event/dispatched` — a negative
  over the trace stream, which under `-Dre-frame.debug=false` is empty for
  every dispatch, enqueued or not. It is kept verbatim inside a
  `(when rf.interop/debug-enabled? …)` arm. Its production
  counterpart is the always-on one immediately above it: an
  `:rf.error/no-frame-context` record on the `:errors` axis IS the
  before-enqueue rejection, since the emit site sits at envelope-build time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.interop :as rf.interop]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.trace :as rf.trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (test-fn))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- no-frame-context-ex
  "Run `f`; return the ExceptionInfo it threw, or nil. Distinguishes the
  `:rf.error/no-frame-context` throw (envelope-build absence) from any
  other failure by returning the ex-data id to the caller."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e e)))

(defn- record-traces!
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :trace listener-id (fn [ev] (swap! a conj ev)))
    a))

(defn- record-errors!
  "Attach an ALWAYS-ON `:errors` listener and return its atom.
  The corpus-wide error-emit registry survives production elision — it is
  the axis Sentry-style shippers read — so counts taken off it are
  posture-independent. Records are the tight error-record map, keyed by
  `:error` rather than the trace stream's `:operation`."
  [listener-id]
  (let [a (atom [])]
    (rf.error-emit/register-error-listener! listener-id (fn [rec] (swap! a conj rec)))
    a))

(defn- errors-of
  [recorded error-id]
  (filter #(= error-id (:error %)) @recorded))

;; ---- bare dispatch outside any context FAILS ------------------------------

(deftest bare-dispatch-outside-context-raises-no-frame-context
  (testing "a top-level `dispatch` under no scope and no explicit frame
            raises :rf.error/no-frame-context (no :rf/default floor) — and
            emits the always-on error, with NO enqueue"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [recorded (record-traces! ::bare)
          errs     (record-errors! ::bare-errors)]
      (binding [rf.frame/*current-frame* nil]
        (let [ex (no-frame-context-ex #(rf/dispatch [:app/noop]))]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
              "the throw carries :rf.error/no-frame-context")
          (is (= :dispatch (:operation (ex-data ex)))
              ":operation tags the dispatch surface")))
      (rf/unregister-listener! :trace ::bare)
      (rf.error-emit/unregister-error-listener! ::bare-errors)
      ;; ALWAYS-ON axis: the count reads the corpus-wide error-emit
      ;; registry, which survives production elision, so "exactly one fired"
      ;; is a claim about the production wire and not about the dev ring.
      (is (= 1 (count (errors-of errs :rf.error/no-frame-context)))
          "exactly one always-on :rf.error/no-frame-context error fired")
      ;; Dev-instrumentation arm (see ns docstring §Posture split).
      ;; A NEGATIVE over the trace stream: under `-Dre-frame.debug=false` the
      ;; stream is empty whether or not the event was enqueued, so outside the
      ;; arm this would report "caught before enqueue" for free.
      (when rf.interop/debug-enabled?
        (is (empty? (filter #(= :rf.event/dispatched (:operation %)) @recorded))
            "NO :rf.event/dispatched — the absence is caught before enqueue")))))

(deftest bare-dispatch-sync-outside-context-raises-no-frame-context
  (testing "dispatch-sync under no scope raises the same way as dispatch"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (binding [rf.frame/*current-frame* nil]
      (let [ex (no-frame-context-ex #(rf/dispatch-sync [:app/noop]))]
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
            "dispatch-sync raises :rf.error/no-frame-context too")))))

(deftest no-frame-error-precedes-registry-lookup
  (testing "the absence error fires BEFORE frame-registry lookup — a
            frameless dispatch of an event whose handler does not exist
            still raises :rf.error/no-frame-context (never
            :rf.error/no-such-handler / :rf.error/frame-destroyed)"
    (let [recorded (record-traces! ::precede)]
      (binding [rf.frame/*current-frame* nil]
        (let [ex (no-frame-context-ex #(rf/dispatch-sync [:never/registered]))]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex))))))
      (rf/unregister-listener! :trace ::precede)
      (is (empty? (filter #(= :rf.error/no-such-handler (:operation %)) @recorded))
          "no no-such-handler — resolution never reached the registry")
      (is (empty? (filter #(= :rf.error/frame-destroyed (:operation %)) @recorded))
          "no frame-destroyed — absence is not mis-reported as a bad target"))))

;; ---- dispatch under with-frame works --------------------------------------

(deftest dispatch-under-with-frame-works
  (testing "a dispatch inside `with-frame` resolves the scope frame and
            runs the handler against it"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/reg-event :app/inc {:frame :app/main}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/with-frame :app/main
      (rf/dispatch-sync [:app/inc]))
    (is (= 1 (:n (rf/app-db-value :app/main)))
        "the handler ran against the with-frame scope frame")))

;; ---- explicit {:frame :rf/default} unregistered is a bad target ----------

(deftest explicit-default-unregistered-is-frame-destroyed-not-no-frame-context
  (testing "explicit `{:frame :rf/default}` when :rf/default is NOT
            registered is a BAD TARGET — a registry-lookup failure
            (:rf.error/frame-destroyed), NOT absence
            (:rf.error/no-frame-context). The override SUCCEEDED at
            resolution; the lookup is what failed."
    ;; :rf/default is intentionally NOT registered.
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [errs (record-errors! ::bad-explicit-errors)]
      (binding [rf.frame/*current-frame* nil]
        ;; An explicit target that does not resolve to a frame-record is
        ;; recover-but-emit: no throw, but a
        ;; :rf.error/frame-destroyed always-on error.
        (rf/dispatch-sync [:app/noop] {:frame :rf/default}))
      (rf.error-emit/unregister-error-listener! ::bad-explicit-errors)
      ;; ALWAYS-ON axis: `:rf.error/frame-destroyed` is in the
      ;; promoted set that fans to the corpus-wide error-emit registry, so
      ;; BOTH assertions — the positive AND the "not the other category"
      ;; negative — stay load-bearing under the production gate. The negative
      ;; is meaningful here precisely because its sibling positive proves the
      ;; stream is live in this posture.
      (is (= 1 (count (errors-of errs :rf.error/frame-destroyed)))
          "a bad explicit target emits :rf.error/frame-destroyed")
      (is (empty? (errors-of errs :rf.error/no-frame-context))
          "NOT :rf.error/no-frame-context — the stamp was carried, just bad"))))

;; ---- override beats scope -------------------------------------------------

(deftest explicit-frame-overrides-scope
  (testing "an explicit `{:frame …}` opt wins over an established
            with-frame scope (override beats scope)"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/make-frame {:id :app/other :doc "override target"})
    (rf/reg-event :app/inc {:frame :app/other}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (rf/with-frame :app/main
      (rf/dispatch-sync [:app/inc] {:frame :app/other}))
    (is (= 1 (:n (rf/app-db-value :app/other)))
        "the dispatch landed on the explicit override frame, not the scope frame")
    (is (nil? (:n (rf/app-db-value :app/main)))
        "the scope frame was untouched")))
