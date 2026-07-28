(ns re-frame.router-carried-frame-test
  "Per rf2-9wa0lf (EP-0002 chain bead 2/11) — the router honours the
  carried-invariant frame contract (Spec 002 §Frame target resolution —
  the carried invariant; EP-0002 §Dispatch And Router / Reference Impl
  Plan §3).

  Router envelope frame resolution order:
    1. explicit `{:frame …}` opt WINS (override);
    2. otherwise `frame/require-current-frame!` reads the scope/hold stamp
       (`with-frame`, a `frame-provider` (SCOPE) or a `frame-root`
       (ENSURE) boundary, or a captured `*current-frame*` binding via a
       capture-frame);
    3. no frame ⇒ NO enqueue — the dispatch raises
       `:rf.error/no-frame-context` at envelope-build time, BEFORE any
       frame-registry lookup. There is no `:rf/default` floor.

  This file pins the EP §3 test matrix:
    - bare dispatch outside any context FAILS;
    - dispatch under `with-frame` works;
    - async bare dispatch after the scope unwinds FAILS;
    - frame-bound (held) dispatch after the scope unwinds works;
    - explicit `{:frame :rf/default}` works only if that frame is
      registered (and raises `:rf.error/frame-destroyed`, NOT
      `:rf.error/no-frame-context`, when it is not — a bad explicit
      target is a registry-lookup failure, a different category from
      absence).

  The `frame-provider` (React-context) tier is platform-specific and is
  exercised in `re-frame.router-carried-frame-cljs-test`.

  JVM-only — the dynamic-var scope tier and the require-or-raise logic
  are platform-agnostic.

  ## Posture split (rf2-d2841)

  Both error categories this file asserts on are ALWAYS-ON, so the split here
  is NOT a guard — it is a change of AXIS. `:rf.error/no-frame-context` fans
  through `frame/emit-no-frame-context!`, which calls the late-bound
  `:error-emit/dispatch-on-error` hook BEFORE it reaches the dev-only
  `trace/emit-error!` leg; `:rf.error/frame-destroyed` is in the promoted
  always-on set (`re-frame.error-emit` ns docstring §Corpus-wide listener
  registry). Reading those counts off the `:errors` stream instead of the
  `:trace` stream keeps the assertions VERBATIM and load-bearing in BOTH
  postures — `clojure -M:test` and `scripts/test-core-prod-gate.sh` alike.

  The one genuinely dev-only claim is `NO :rf.event/dispatched` — a negative
  over the trace stream, which under `-Dre-frame.debug=false` is empty for
  every dispatch, enqueued or not. It is kept verbatim inside a
  `(when interop/debug-enabled? …)` arm marked `rf2-d2841`. Its production
  counterpart is the always-on one immediately above it: an
  `:rf.error/no-frame-context` record on the `:errors` axis IS the
  before-enqueue rejection, since the emit site sits at envelope-build time."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (trace/clear-listeners!)
  (rf/init! plain-atom/adapter)
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
  "Attach an ALWAYS-ON `:errors` listener and return its atom (rf2-d2841).
  The corpus-wide error-emit registry survives production elision — it is
  the axis Sentry-style shippers read — so counts taken off it are
  posture-independent. Records are the tight error-record map, keyed by
  `:error` rather than the trace stream's `:operation`."
  [listener-id]
  (let [a (atom [])]
    (rf/register-listener! :errors listener-id (fn [rec] (swap! a conj rec)))
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
      (binding [frame/*current-frame* nil]
        (let [ex (no-frame-context-ex #(rf/dispatch [:app/noop]))]
          (is (some? ex) "frameless dispatch raised")
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
              "the throw carries :rf.error/no-frame-context")
          (is (= :dispatch (:operation (ex-data ex)))
              ":operation tags the dispatch surface")))
      (rf/unregister-listener! :trace ::bare)
      (rf/unregister-listener! :errors ::bare-errors)
      ;; ALWAYS-ON axis (rf2-d2841): the count reads the corpus-wide error-emit
      ;; registry, which survives production elision, so "exactly one fired"
      ;; is a claim about the production wire and not about the dev ring.
      (is (= 1 (count (errors-of errs :rf.error/no-frame-context)))
          "exactly one always-on :rf.error/no-frame-context error fired")
      ;; rf2-d2841 — dev-instrumentation arm (see ns docstring §Posture split).
      ;; A NEGATIVE over the trace stream: under `-Dre-frame.debug=false` the
      ;; stream is empty whether or not the event was enqueued, so outside the
      ;; arm this would report "caught before enqueue" for free.
      (when interop/debug-enabled?
        (is (empty? (filter #(= :rf.event/dispatched (:operation %)) @recorded))
            "NO :rf.event/dispatched — the absence is caught before enqueue")))))

(deftest bare-dispatch-sync-outside-context-raises-no-frame-context
  (testing "dispatch-sync under no scope raises the same way as dispatch"
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (binding [frame/*current-frame* nil]
      (let [ex (no-frame-context-ex #(rf/dispatch-sync [:app/noop]))]
        (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
            "dispatch-sync raises :rf.error/no-frame-context too")))))

(deftest no-frame-error-precedes-registry-lookup
  (testing "the absence error fires BEFORE frame-registry lookup — a
            frameless dispatch of an event whose handler does not exist
            still raises :rf.error/no-frame-context (never
            :rf.error/no-such-handler / :rf.error/frame-destroyed)"
    (let [recorded (record-traces! ::precede)]
      (binding [frame/*current-frame* nil]
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

(deftest dispatch-via-binding-scope-works
  (testing "a dispatch under a *current-frame* binding (the dynamic-var
            scope tier with-frame expands to) resolves that frame"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/reg-event :app/inc {:frame :app/main}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (binding [frame/*current-frame* :app/main]
      (rf/dispatch-sync [:app/inc]))
    (is (= 1 (:n (rf/app-db-value :app/main))))))

;; ---- async bare dispatch after scope unwinds FAILS ------------------------

(deftest async-bare-dispatch-after-unwind-fails
  (testing "a callback CAPTURED inside a scope but INVOKED after the scope
            unwinds — with a bare `dispatch` (no captured handle) — fails:
            the dynamic binding did not survive the async escape, so there
            is no carried stamp"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/reg-event :app/noop {:frame :app/main} (fn [{:keys [db]} _] {:db db}))
    ;; Capture a thunk inside the scope; the bare dispatch reads the
    ;; ambient frame at INVOKE time, not capture time.
    (let [thunk (rf/with-frame :app/main
                  (fn [] (rf/dispatch-sync [:app/noop])))]
      ;; Invoke after the scope has unwound (the JS async-callback shape:
      ;; fresh stack, no dynamic binding).
      (binding [frame/*current-frame* nil]
        (let [ex (no-frame-context-ex thunk)]
          (is (= :rf.error/no-frame-context (:rf.error/id (ex-data ex)))
              "the unwound bare dispatch raised :rf.error/no-frame-context"))))))

;; ---- frame-bound (held) dispatch after unwind works -----------------------

(deftest frame-bound-dispatch-after-unwind-works
  (testing "a capture-frame CAPTURED inside the scope carries the frame
            stamp as a VALUE; calling its `:dispatch` after the scope
            unwinds still targets the captured frame (the hold tier)"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/reg-event :app/inc {:frame :app/main}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [handle (rf/with-frame :app/main
                   (rf/capture-frame))]            ;; no-arg capture inside scope
      (is (= :app/main (:frame handle))
          "the handle captured the scope frame at creation time")
      ;; Fire after the scope has unwound — the held stamp survives.
      (binding [frame/*current-frame* nil]
        ((:dispatch-sync handle) [:app/inc]))
      (is (= 1 (:n (rf/app-db-value :app/main)))
          "the held dispatch ran against the captured frame despite no scope"))))

(deftest bind-fn-after-unwind-works
  (testing "re-frame.frame/bind-fn (the internal relocated frame-bound-fn*
            dynamic-rebinding primitive, API-shrink #1 rf2-csbbwu) re-
            establishes the captured scope so an inner bare dispatch
            resolves the captured frame after unwind"
    (rf/make-frame {:id :app/main :doc "scope frame"})
    (rf/reg-event :app/inc {:frame :app/main}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [bound (rf/with-frame :app/main
                  (frame/bind-fn :app/main (fn [] (rf/dispatch-sync [:app/inc]))))]
      (binding [frame/*current-frame* nil]
        (bound))
      (is (= 1 (:n (rf/app-db-value :app/main)))
          "bind-fn re-bound the captured frame for the inner dispatch"))))

;; ---- explicit {:frame :rf/default} works only if registered ---------------

(deftest explicit-default-works-when-registered
  (testing "explicit `{:frame :rf/default}` is an override and works when
            `:rf/default` is registered as an ordinary frame"
    (rf/make-frame {:id :rf/default :doc "ordinary explicit frame"})
    (rf/reg-event :app/inc {:frame :rf/default}
      (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    ;; No surrounding scope — the explicit override carries the stamp.
    (binding [frame/*current-frame* nil]
      (rf/dispatch-sync [:app/inc] {:frame :rf/default}))
    (is (= 1 (:n (rf/app-db-value :rf/default)))
        "the explicit :rf/default override resolved and ran the handler")))

(deftest explicit-default-unregistered-is-frame-destroyed-not-no-frame-context
  (testing "explicit `{:frame :rf/default}` when :rf/default is NOT
            registered is a BAD TARGET — a registry-lookup failure
            (:rf.error/frame-destroyed), NOT absence
            (:rf.error/no-frame-context). The override SUCCEEDED at
            resolution; the lookup is what failed."
    ;; :rf/default is intentionally NOT registered.
    (rf/reg-event :app/noop (fn [{:keys [db]} _] {:db db}))
    (let [errs (record-errors! ::bad-explicit-errors)]
      (binding [frame/*current-frame* nil]
        ;; An explicit target that does not resolve to a frame-record is
        ;; recover-but-emit (rf2-2hvga): no throw, but a
        ;; :rf.error/frame-destroyed always-on error.
        (rf/dispatch-sync [:app/noop] {:frame :rf/default}))
      (rf/unregister-listener! :errors ::bad-explicit-errors)
      ;; ALWAYS-ON axis (rf2-d2841): `:rf.error/frame-destroyed` is in the
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
