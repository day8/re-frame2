(ns re-frame.test-support-test
  "Coverage for the public test-flavoured helpers:

    - assert-path-equals

  Plus explicit hook-cascade coverage for `make-reset-runtime-fixture` —
  pins that every row in the late-bind reset-hook-table fires the
  documented number of times per fixture invocation, so a change that
  drops a row breaks loudly rather than silently.

  The fixture machinery (snapshot-registrar / restore-registrar! /
  make-reset-runtime-fixture) is exercised transitively by the rest of the
  test suite — these tests pin the helper *signatures* and the
  per-helper contract in Spec 008 §Built-in test-runner namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures
                                  do-report report]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.flows :as rf.flows]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.schemas :as rf.schemas]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.trace.tooling :as rf.trace.tooling]
            [re-frame.test-support :as rf.test-support]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf.trace.tooling/clear-listeners!)
  (rf/init! rf.substrate.plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  ;; EP-0002: `init!` does not synthesise `:rf/default`;
  ;; framework operation surfaces require a carried frame stamp. Register
  ;; `:rf/default` + pin it as the body's ambient scope (the carried-
  ;; invariant equivalent of `(with-frame :rf/default …)`); explicit
  ;; `{:frame …}` opts in the test bodies still win. A top-level
  ;; `make-frame …:initial-events` still drain synchronously — the lifecycle
  ;; async/sync split keys off `*handler-scope*` (a real cascade), not
  ;; this ambient scope.
  (rf/make-frame {:id :rf/default})
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; ---- helpers --------------------------------------------------------------

(defn- record-reports
  "Run `body-fn` with `clojure.test/report` rebound to record into the
  returned atom. The recorded value is the seq of `:type` keys in the
  order they fired so tests can assert pass/fail outcomes from
  helpers that emit through `do-report`."
  [body-fn]
  (let [recorded (atom [])]
    (with-redefs [report (fn [m] (swap! recorded conj (:type m)))]
      (body-fn))
    @recorded))

(defn- register-counter-handlers! []
  (rf/reg-event :counter/init (fn [{:keys [db]} _] {:db {:n 0}}))
  (rf/reg-event :counter/inc  (fn [{:keys [db]} _] {:db (update db :n inc)}))
  (rf/reg-event :counter/dec  (fn [{:keys [db]} _] {:db (update db :n dec)}))
  (rf/reg-event :counter/add
    (fn [{:keys [db]} [_ amt]] {:db (update db :n + amt)})))

;; ---- assert-path-equals ---------------------------------------------------

(deftest assert-path-equals-pass
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (rf/dispatch-sync [:counter/add 7])
  (let [outcomes (record-reports
                   (fn [] (rf.test-support/assert-path-equals [:n] 7)))]
    (is (= [:pass] outcomes)
        "matching path/value pair fires a clojure.test :pass")))

(deftest assert-path-equals-fail
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (let [outcomes (record-reports
                   (fn [] (rf.test-support/assert-path-equals [:n] 99)))]
    (is (= [:fail] outcomes)
        "mismatching path/value pair fires a clojure.test :fail")))

(deftest assert-path-equals-frame-opt
  (testing ":frame opt selects which frame's app-db is asserted against"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/make-frame {:id :test-support/assert-frame :initial-events [[:counter/init]]})
    (rf/dispatch-sync [:counter/add 3] {:frame :test-support/assert-frame})
    (let [outcomes (record-reports
                     (fn []
                       (rf.test-support/assert-path-equals [:n] 3 {:frame :test-support/assert-frame})
                       (rf.test-support/assert-path-equals [:n] 0 {:frame :rf/default})))]
      (is (= [:pass :pass] outcomes)
          ":rf/default and the named frame each carry their own state"))))

(deftest assert-path-equals-inside-with-new-frame
  (testing "a frame OBJECT — the ambient scope `with-new-frame` binds, or an
            explicit `{:frame <object>}` — resolves to that frame's app-db"
    (register-counter-handlers!)
    (rf/with-new-frame [f (rf/make-frame {:initial-events [[:counter/init]
                                                           [:counter/add 5]]})]
      ;; An unresolved frame reads nil, so `nil` is the mismatch a
      ;; mis-resolution would silently accept; `6` is an ordinary mismatch.
      (let [outcomes (record-reports
                       (fn []
                         (rf.test-support/assert-path-equals [:n] 5)
                         (rf.test-support/assert-path-equals [:n] 6)
                         (rf.test-support/assert-path-equals [:n] nil)
                         (rf.test-support/assert-path-equals [:n] 5 {:frame f})
                         (rf.test-support/assert-path-equals [:n] nil {:frame f})))]
        (is (= [:pass :fail :fail :pass :fail] outcomes)
            "the match passes and both mismatches fail, ambient and explicit")))))

;; ---- make-reset-runtime-fixture hook-cascade coverage --------------------------
;;
;; The fixture's per-test reset drives an inline table
;; (`test_support.cljc/reset-hook-table`) of late-bind hook keys
;; across two phases (`:pre-dispose` and `:post-dispose`). The table makes
;; the call-set explicit, but nothing else checks it: a change that drops a
;; row would silently break the contract that "no hook gets dropped". This test
;; pins the contract by REPLACING each registered hook with a counting
;; sentinel, running one fixture invocation, and asserting each
;; sentinel fired exactly once.
;;
;; Mechanism:
;;   1. Snapshot the late-bind hooks atom.
;;   2. Replace each known reset-hook-table key in
;;      `reset-hook-expected-counts` with a
;;      sentinel that increments a per-key counter atom. (Replace
;;      regardless of whether the producer registered it on the test
;;      classpath — the sentinel ensures the fixture's hook walk
;;      actually hit it.)
;;   3. Invoke the fixture's per-test body with a no-op test-fn so the
;;      fixture itself fires all the hooks.
;;   4. Assert each counter is exactly 1.
;;   5. Restore the late-bind atom from the snapshot.
;;
;; Why this exists: the rest of the test suite exercises the fixture
;; transitively (through `use-fixtures :each`), so a dropped hook would
;; eventually surface as cross-test pollution. But that's a noisy
;; long-range signal; this test catches a dropped hook at the immediate
;; seam.

(def ^:private reset-hook-expected-counts
  "Per-fixture-invocation call count for every reset-hook-table key.

  Most keys fire exactly once: the fixture's `run-reset-hooks!` walks
  the table in `:pre-dispose` then `:post-dispose` phase order, hitting
  each row once.

  `:flows/reset-flows!` is the documented exception: it fires twice —
  once mid-body (via `:pre-dispose`) and once in the `finally` block,
  per the fixture docstring's post-test step that resets `rf.frame/frames`
  back to `{}` for symmetry along with the flows registry. The two-fire
  shape is load-bearing — symmetric pre-test and post-test reset so a
  failing test leaves no residue for the next. Pinning the count here
  means a change that drops EITHER call site (or unifies them into one)
  fails this test."
  {:flows/reset-flows!              2  ;; pre + finally (symmetry)
   :schemas/clear-by-frame!         1
   :machines/reset-timers!          1
   :fx/reset-dispatch-later-timers! 1
   :machines/reset-spawn-order!     1
   :routing/reset-counters!         1
   :routing/reset-nav-counters!     1  ;; host-side counters
   :routing/reset-url-claims!       1
   :routing/reset-url-listener!     1
   :resources/reset-resources!      1  ;; host-side resources state
   :http/clear-all-in-flight!       1
   :http/clear-all-http-interceptors! 1  ;; interceptor-chain reset
   :epoch/clear-history!            1
   :epoch/clear-epoch-listeners!          1
   :epoch/reset-config!             1
   :adapter/clear-warn-once-caches! 1})

(deftest make-reset-runtime-fixture-fires-every-hook-the-documented-number-of-times
  (testing "every row in reset-hook-table fires the documented number of
            times per fixture call — once for most, twice for
            `:flows/reset-flows!` (pre-test + finally symmetry per the
            fixture docstring's post-test frames/flows reset)"
    (let [snapshot      @rf.late-bind/hooks
          call-counts   (atom (zipmap (keys reset-hook-expected-counts)
                                      (repeat 0)))
          orig-restore  (rf.late-bind/get-fn :schemas/restore-by-frame!)]
      (try
        ;; Install sentinels for every reset-hook-table key.
        (doseq [k (keys reset-hook-expected-counts)]
          (rf.late-bind/set-fn! k (fn [& _]
                                 (swap! call-counts update k inc)
                                 nil)))
        ;; The fixture also calls :schemas/snapshot-by-frame /
        ;; :schemas/restore-by-frame! around the test body. We don't
        ;; count those (they're not part of the reset-hook-table
        ;; cascade — they're independent snapshot/restore plumbing per
        ;; the fixture's docstring). Replace `:schemas/restore-by-frame!`
        ;; with a benign stub so the test-body's finally clause doesn't
        ;; depend on the production restore.
        (rf.late-bind/set-fn! :schemas/restore-by-frame! (fn [_snap] nil))
        (rf.late-bind/set-fn! :schemas/snapshot-by-frame (fn [] nil))

        (let [fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})]
          (fixture (fn [] :ran)))

        (is (= (set (map :hook @#'rf.test-support/reset-hook-table))
               (set (keys reset-hook-expected-counts)))
            "the expected counts name every reset-hook-table row and no other key")
        (doseq [[k expected] reset-hook-expected-counts]
          (is (= expected (get @call-counts k))
              (str k " fired " expected " time(s) per fixture invocation")))
        (finally
          ;; Restore the late-bind atom so subsequent tests inherit the
          ;; producer's real fns. Reset the registrar / frames so the
          ;; test that follows starts clean.
          (reset! rf.late-bind/hooks snapshot)
          ;; Restore the schemas restore-fn we displaced (in case the
          ;; snapshot didn't capture it).
          (when orig-restore
            (rf.late-bind/set-fn! :schemas/restore-by-frame! orig-restore)))))))

(deftest make-reset-runtime-fixture-resets-per-frame-schemas-with-no-option
  (testing "an app schema registered before the fixture runs is absent inside
            the body and back afterwards — the per-frame schema reset is part
            of every fixture run, with no option to ask for it"
    (rf/reg-app-schema [:counter] :int)
    (let [present? #(some? (rf.schemas/app-schema-meta {:frame :rf/default :path [:counter]}))
          in-body  (atom :unset)]
      (is (present?) "precondition: the schema is registered on :rf/default")
      ((rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
       (fn [] (reset! in-body (present?))))
      (is (false? @in-body) "the body sees no per-frame schemas")
      (is (present?) "the schema is restored when the fixture finishes"))))

(deftest make-reset-runtime-fixture-clears-per-frame-schemas-once
  (testing "the fixture takes no option that clears per-frame schemas: passing
            `:clear-app-schemas? true` leaves the one per-frame schema reset
            every run makes, and adds no second clear"
    (let [snapshot @rf.late-bind/hooks
          clears   (atom 0)]
      (try
        (rf.late-bind/set-fn! :schemas/clear-by-frame! (fn [] (swap! clears inc) nil))
        ((rf.test-support/make-reset-runtime-fixture
           {:adapter rf.substrate.plain-atom/adapter :clear-app-schemas? true})
         (fn [] :ran))
        (is (= 1 @clears))
        (finally
          (reset! rf.late-bind/hooks snapshot)
          (rf.late-bind/invalidate-cache! :schemas/clear-by-frame!))))))

(deftest make-reset-runtime-fixture-pre-dispose-fires-before-adapter-dispose
  (testing "the `:pre-dispose` phase fires BEFORE adapter dispose and
            the `:post-dispose` phase fires AFTER — phase ordering is
            load-bearing per the fixture docstring"
    ;; Capture invocation order, not just counts. The test asserts
    ;; the relative ordering of one pre-dispose hook (:flows/reset-flows!),
    ;; the adapter dispose (observed via a sentinel registered on the
    ;; adapter), and one post-dispose hook (:epoch/clear-history!).
    (let [snapshot @rf.late-bind/hooks
          order    (atom [])]
      (try
        (rf.late-bind/set-fn! :flows/reset-flows!
                           (fn [] (swap! order conj :pre)))
        (rf.late-bind/set-fn! :epoch/clear-history!
                           (fn [] (swap! order conj :post)))
        ;; The adapter's dispose call lands between the two phases, but
        ;; there is no late-bind hook on dispose itself, so observe the
        ;; two flows reset calls (pre-test + finally) on
        ;; `:flows/reset-flows!` and assert `:pre` and `:post` interleave
        ;; the way the docstring describes — `:epoch/clear-history!` runs
        ;; AFTER the first flows reset and BEFORE the finally flows reset.
        (let [fixture (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})]
          (fixture (fn [] :ran)))
        ;; Expected: pre (flows pre-dispose) → post (epoch post-dispose) →
        ;; pre (flows finally).
        (is (= [:pre :post :pre] @order)
            "pre-dispose runs before post-dispose; the finally-block
             flows reset is the third event")
        (finally
          (reset! rf.late-bind/hooks snapshot))))))

;; ---- `:init-fn` runs under the body's ambient frame -----------------------
;;
;; The fixture's `:init-fn` is per-suite setup that needs the registrar /
;; adapter live (the docstring's `:init-fn` step) — e.g. re-running an app's
;; `register-all!` / `install!` thunks, which can perform context-required
;; frame-local ops (`reg-app-schema` / `reg-flow` / a bare `dispatch`). Those
;; resolve `*current-frame*` and raise `:rf.error/no-frame-context` under no
;; scope. Were the `:init-fn` run OUTSIDE the ambient `*current-frame*`
;; binding the fixture establishes around the body, a bare frame-local op in
;; setup would throw frameless — surfacing (in the shared `:node-test` JS
;; runtime) as an intermittent double-`done` suite abort. Pin that the
;; `:init-fn` runs under the same carried-frame floor as the body.

(deftest make-reset-runtime-fixture-runs-init-fn-under-ambient-frame
  (testing "an adapter fixture runs `:init-fn` under the ambient `:rf/default`
            scope, so a context-required frame-local op in setup does NOT
            raise `:rf.error/no-frame-context`"
    ;; Neutralise the enclosing `reset-runtime` fixture's ambient
    ;; `(with-frame :rf/default …)` so the `:rf/default` we observe is the
    ;; one THIS fixture binds around `:init-fn`, not the outer fixture's —
    ;; mirroring the cljs.test context (cljs.test has no enclosing
    ;; `with-frame`, so a frameless `:init-fn` would otherwise see nil).
    (binding [rf.frame/*current-frame* nil]
      (let [seen-frame (atom :unset)
            threw      (atom nil)]
        (try
          (let [fixture (rf.test-support/make-reset-runtime-fixture
                          {:adapter rf.substrate.plain-atom/adapter
                           :init-fn (fn []
                                      ;; A frame-local op the way an app's
                                      ;; setup thunk would run it — bare, no
                                      ;; explicit `{:frame …}`.
                                      (reset! seen-frame (rf.frame/current-frame))
                                      (rf/reg-app-schema [:counter] :int))})]
            (fixture (fn [] :ran)))
          (catch Throwable t (reset! threw t)))
        (is (nil? @threw)
            "the bare `reg-app-schema` in `:init-fn` did not throw frameless")
        (is (= :rf/default @seen-frame)
            "`:init-fn` observed the ambient `:rf/default` scope (same as the
             test body)")))))

(deftest make-reset-runtime-fixture-init-fn-frameless-when-ambient-opted-out
  (testing "`:ambient-frame nil` keeps `:init-fn` frameless — tests that own
            their frame creation must not run setup under a synthetic scope"
    ;; Neutralise the enclosing `reset-runtime` fixture's ambient
    ;; `(with-frame :rf/default …)` so we observe THIS fixture's scope
    ;; decision (not the outer one's) — under cljs.test there is no such
    ;; enclosing `with-frame`.
    (binding [rf.frame/*current-frame* nil]
      (let [seen-frame (atom :unset)]
        (let [fixture (rf.test-support/make-reset-runtime-fixture
                        {:adapter       rf.substrate.plain-atom/adapter
                         :ambient-frame nil
                         :init-fn       (fn []
                                          (reset! seen-frame
                                                  (rf.frame/current-frame)))})]
          (fixture (fn [] :ran)))
        (is (nil? @seen-frame)
            "no ambient frame was bound around `:init-fn` when
             `:ambient-frame nil` opts out (the fixture leaves
             `*current-frame*` unbound, so `current-frame` is nil)")))))

;; ---- destroy-frame! hook-cascade coverage ---------------------------------
;;
;; `destroy-frame!` fires these cleanup hooks, each guarded
;; (`safe-call-hook!` for ssr / machines, `notify-epoch-listeners!` for
;; the epoch hook):
;;
;;   :ssr/on-frame-destroyed            — SSR side-channel atoms cleanup
;;   :machines/on-frame-destroyed!      — machines timer-table cleanup
;;   :epoch/on-frame-destroyed          — fired via notify-epoch-listeners!
;;
;; Same shape as the reset-fixture coverage above: register a sentinel
;; under each key, destroy a frame, assert all fired exactly once.

(def ^:private destroy-frame-hook-keys
  "The destroy-frame! cleanup-hook keys. `:ssr` / `:machines` fire through
  `rf.frame/safe-call-hook!` inside the destroy cascade; the two epoch
  keys fire directly (`:epoch/snapshot-frame-destroyed` BEFORE dissoc,
  `:epoch/on-frame-destroyed` AFTER). Mirrored here so the
  assertion visits each by name."
  [:ssr/on-frame-destroyed
   :machines/on-frame-destroyed!
   :epoch/snapshot-frame-destroyed
   :epoch/on-frame-destroyed])

(deftest destroy-frame-fires-every-cleanup-hook-exactly-once
  (testing "every cleanup-hook in destroy-frame! fires exactly once
            per destruction"
    (let [snapshot    @rf.late-bind/hooks
          call-counts (atom (zipmap destroy-frame-hook-keys (repeat 0)))]
      (try
        ;; Install sentinels.
        (doseq [k destroy-frame-hook-keys]
          (rf.late-bind/set-fn! k (fn [& _]
                                 (swap! call-counts update k inc)
                                 nil)))
        ;; Register and destroy a frame. The destroy cascade walks every
        ;; cleanup hook exactly once.
        (rf/make-frame {:id :rf2-j9phb/target})
        (rf.frame/destroy-frame! :rf2-j9phb/target)

        (doseq [k destroy-frame-hook-keys]
          (is (= 1 (get @call-counts k))
              (str k " fired exactly once during destroy-frame!")))
        (finally
          (reset! rf.late-bind/hooks snapshot))))))

(deftest destroy-frame-cleanup-hooks-receive-frame-id
  (testing "cleanup hooks that take the destroyed frame's id receive it
            correctly — :ssr / :machines / :epoch all pass the id"
    ;; :ssr / :machines take the destroyed id. The epoch teardown spans
    ;; two hooks: the PRE-dissoc :epoch/snapshot-frame-destroyed
    ;; takes (id db-before db-after committed-at) — the two snapshots
    ;; for the :halted-destroy record + the destroying event's causal
    ;; :time-ms — and the POST-dissoc :epoch/on-frame-destroyed takes
    ;; (id owner-token terminal-evidence) — exact frame ownership plus the
    ;; pre-dissoc bundle. Pin each arg shape so a change that swaps
    ;; positional → varargs (or drops the id) breaks loudly.
    (let [snapshot   @rf.late-bind/hooks
          captured-args (atom {})
          original-snap (rf.late-bind/get-fn :epoch/snapshot-frame-destroyed)]
      (try
        (doseq [k [:ssr/on-frame-destroyed
                   :machines/on-frame-destroyed!]]
          (rf.late-bind/set-fn! k (fn [id]
                                 (swap! captured-args assoc k id))))
        ;; The pre-dissoc snapshot hook's four-arg shape — record the id and
        ;; that the two snapshot args + committed-at arrive (all nil here save
        ;; fs-after: an out-of-cascade destroy carries no pre-cascade snapshot
        ;; and no in-flight causal token). Delegate to the real hook so the
        ;; downstream :epoch/on-frame-destroyed still receives a live bundle.
        (rf.late-bind/set-fn! :epoch/snapshot-frame-destroyed
                           (fn [id db-before db-after committed-at]
                             (swap! captured-args assoc
                                    :epoch/snapshot-frame-destroyed id
                                    :epoch/snapshot-args [db-before db-after]
                                    :epoch/committed-at committed-at)
                             (when original-snap
                               (original-snap id db-before db-after committed-at))))
        ;; The post-dissoc hook's three-arg shape — record the id, exact
        ;; incarnation owner, and that the terminal-evidence bundle arrives.
        (rf.late-bind/set-fn! :epoch/on-frame-destroyed
                           (fn [id owner-token terminal-evidence]
                             (swap! captured-args assoc
                                    :epoch/on-frame-destroyed id
                                    :epoch/owner-token owner-token
                                    :epoch/terminal-evidence-arrived?
                                    (contains? @captured-args
                                               :epoch/snapshot-frame-destroyed))))
        (rf/make-frame {:id :rf2-j9phb/arg-target})
        (rf.frame/destroy-frame! :rf2-j9phb/arg-target)

        (is (= :rf2-j9phb/arg-target
               (get @captured-args :ssr/on-frame-destroyed))
            "ssr hook receives the destroyed frame id")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :machines/on-frame-destroyed!))
            "machines hook receives the destroyed frame id")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :epoch/snapshot-frame-destroyed))
            "pre-dissoc snapshot hook receives the destroyed frame id")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :epoch/on-frame-destroyed))
            "post-dissoc epoch hook receives the destroyed frame id")
        (is (some? (get @captured-args :epoch/owner-token))
            "epoch hook receives the destroyed incarnation's stable owner token")
        (is (true? (get @captured-args :epoch/terminal-evidence-arrived?))
            "the post-dissoc hook runs AFTER the pre-dissoc snapshot hook —
             the terminal-evidence bundle it publishes was captured first")
        ;; For this OUT-OF-RUN destroy, fs-before
        ;; (the pre-run snapshot from rf.frame/*run-frame-state-before*)
        ;; is nil (no in-flight run), while fs-after is the live
        ;; frame-state value read at destroy-time — the frame's initial empty
        ;; two-partition frame-state. EP-0001 (Decision 2): the
        ;; snapshot hook threads the whole frame-state (both partitions),
        ;; not app-db alone.
        (is (= [nil {:rf.db/app {} :rf.db/runtime {}}]
               (get @captured-args :epoch/snapshot-args))
            "snapshot hook receives (fs-before fs-after): nil pre-run
             (out-of-run destroy) + the destroy-time frame-state
             {:rf.db/app {} :rf.db/runtime {}}")
        ;; An out-of-run destroy has no in-flight causal
        ;; token, so rf.frame/*run-time-ms* is unbound (nil) and the hook's
        ;; committed-at arrives nil. (No :halted-destroy record is committed
        ;; on this path, so the value is moot — the assertion pins the arg
        ;; shape, not a committed timestamp.)
        (is (contains? @captured-args :epoch/committed-at)
            "snapshot hook receives the terminal committed-at arg")
        (is (nil? (get @captured-args :epoch/committed-at))
            "committed-at is nil for an out-of-cascade destroy (no token)")
        (finally
          (reset! rf.late-bind/hooks snapshot))))))

;; ---- poll-until pred-exception semantics (JVM ↔ CLJS parity) --------------

(deftest poll-until-swallows-a-throwing-pred-jvm
  (testing "a `pred` that throws transiently is a falsy probe — keep polling to
  the deadline — matching the CLJS arm."
    ;; Throws on the first two probes, then succeeds → must resolve, not surface
    ;; the pred's exception.
    (let [calls (atom 0)]
      (is (= :ok
             (rf.test-support/poll-until
               (fn []
                 (if (< (swap! calls inc) 3)
                   (throw (ex-info "transient" {}))
                   :ok))
               {:timeout-ms 2000 :interval-ms 1}))
          "throwing-then-succeeding pred resolves rather than propagating the throw")
      (is (>= @calls 3)))
    ;; An always-throwing pred hits the deadline and surfaces the
    ;; poll-until-timeout discriminator — NOT the pred's own exception.
    (let [e (try (rf.test-support/poll-until (fn [] (throw (ex-info "always" {:boom true})))
                                {:timeout-ms 40 :interval-ms 5})
                 nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e) "poll-until throws on the deadline")
      (is (= :rf.error/poll-until-timeout (:rf.error/id (ex-data e)))
          "the deadline surfaces poll-until-timeout, not the pred's own throw"))))

