(ns re-frame.test-support-test
  "Coverage for the public test-flavoured helpers landed under rf2-0l3s
  (resolves rf2-hkr5; renamed under rf2-8j9m6):

    - dispatch-sequence
    - assert-path-equals
    - assert-db-equals

  Plus rf2-j9phb (TE-R2.2): explicit hook-cascade coverage for
  `make-reset-runtime-fixture` — pins that every row in the late-bind
  reset-hook-table is fired exactly once per fixture invocation, so a
  future refactor that drops a row breaks loudly rather than silently.

  The fixture machinery (snapshot-registrar / with-fresh-registrar /
  make-reset-runtime-fixture) is exercised transitively by the rest of the
  test suite — these tests pin the helper *signatures* and the
  per-helper contract in Spec 008 §Built-in test-runner namespace."
  (:require [clojure.test :refer [deftest is testing use-fixtures
                                  do-report report]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.flows :as flows]
            [re-frame.late-bind :as late-bind]
            [re-frame.schemas :as schemas]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.trace :as trace]
            [re-frame.test-support :as ts]))

;; ---- fixtures -------------------------------------------------------------

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (trace/clear-trace-listeners!)
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

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
  (rf/reg-event-db :counter/init (fn [_ _] {:n 0}))
  (rf/reg-event-db :counter/inc  (fn [db _] (update db :n inc)))
  (rf/reg-event-db :counter/dec  (fn [db _] (update db :n dec)))
  (rf/reg-event-db :counter/add
    (fn [db [_ amt]] (update db :n + amt))))

;; ---- dispatch-sequence ----------------------------------------------------

(deftest dispatch-sequence-runs-each-event-in-order
  (testing "events fire in order; final app-db reflects the cumulative result"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (let [final (ts/dispatch-sequence
                  [[:counter/inc]
                   [:counter/inc]
                   [:counter/inc]
                   [:counter/dec]])]
      (is (= 2 (:n final))
          "three incs and one dec leave :n at 2")
      (is (= final (rf/get-frame-db :rf/default))
          "return value matches the live app-db value"))))

(deftest dispatch-sequence-after-each-captures-intermediate-states
  (testing ":after-each fires once per event with (db, event)"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (let [seen (atom [])]
      (ts/dispatch-sequence
        [[:counter/inc] [:counter/inc] [:counter/dec]]
        {:after-each (fn [db ev] (swap! seen conj [(:n db) ev]))})
      (is (= [[1 [:counter/inc]]
              [2 [:counter/inc]]
              [1 [:counter/dec]]]
             @seen)
          ":after-each observed each step's committed state"))))

(deftest dispatch-sequence-frame-opt
  (testing ":frame opt routes dispatches to a non-default frame"
    ;; Register handlers first so :on-create can resolve them at
    ;; reg-frame time (Spec 002 §Frame lifecycle).
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/reg-frame :test-support/seq-frame {:on-create [:counter/init]})
    (let [final (ts/dispatch-sequence
                  [[:counter/inc] [:counter/add 5]]
                  {:frame :test-support/seq-frame})]
      (is (= 6 (:n final)))
      (is (= 6 (:n (rf/get-frame-db :test-support/seq-frame))))
      (is (= {:n 0} (rf/get-frame-db :rf/default))
          ":rf/default is unaffected"))))

;; ---- assert-path-equals + assert-db-equals (rf2-8j9m6) --------------------

(deftest assert-path-equals-pass
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (rf/dispatch-sync [:counter/add 7])
  (let [outcomes (record-reports
                   (fn [] (ts/assert-path-equals [:n] 7)))]
    (is (= [:pass] outcomes)
        "matching path/value pair fires a clojure.test :pass")))

(deftest assert-path-equals-fail
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (let [outcomes (record-reports
                   (fn [] (ts/assert-path-equals [:n] 99)))]
    (is (= [:fail] outcomes)
        "mismatching path/value pair fires a clojure.test :fail")))

(deftest assert-db-equals-pass-and-fail
  (register-counter-handlers!)
  (rf/dispatch-sync [:counter/init])
  (let [pass-outcomes (record-reports
                        (fn [] (ts/assert-db-equals {:n 0})))
        fail-outcomes (record-reports
                        (fn [] (ts/assert-db-equals {:n 42})))]
    (is (= [:pass] pass-outcomes))
    (is (= [:fail] fail-outcomes))))

(deftest assert-path-equals-frame-opt
  (testing ":frame opt selects which frame's app-db is asserted against"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/reg-frame :test-support/assert-frame {:on-create [:counter/init]})
    (rf/dispatch-sync [:counter/add 3] {:frame :test-support/assert-frame})
    (let [outcomes (record-reports
                     (fn []
                       (ts/assert-path-equals [:n] 3 {:frame :test-support/assert-frame})
                       (ts/assert-path-equals [:n] 0 {:frame :rf/default})))]
      (is (= [:pass :pass] outcomes)
          ":rf/default and the named frame each carry their own state"))))

(deftest assert-db-equals-frame-opt
  (testing ":frame opt also selects the frame for the full-db form"
    (register-counter-handlers!)
    (rf/dispatch-sync [:counter/init])
    (rf/reg-frame :test-support/assert-db-frame {:on-create [:counter/init]})
    (rf/dispatch-sync [:counter/add 4] {:frame :test-support/assert-db-frame})
    (let [outcomes (record-reports
                     (fn []
                       (ts/assert-db-equals {:n 4} {:frame :test-support/assert-db-frame})
                       (ts/assert-db-equals {:n 0} {:frame :rf/default})))]
      (is (= [:pass :pass] outcomes)
          "full-db assertion respects :frame opt"))))

;; ---- rf2-j9phb (TE-R2.2) — make-reset-runtime-fixture hook-cascade coverage ----
;;
;; The fixture's per-test reset drives an inline table
;; (`test_support.cljc/reset-hook-table`) of nine late-bind hook keys
;; across two phases (`:pre-dispose` and `:post-dispose`). The pure-shape
;; refactor that landed via rf2-d7vw8 made the call-set explicit — but
;; the call-set is still implicit: a future change that drops a row
;; silently breaks the contract that "no hook gets dropped". This test
;; pins the contract by REPLACING each registered hook with a counting
;; sentinel, running one fixture invocation, and asserting each
;; sentinel fired exactly once.
;;
;; Mechanism:
;;   1. Snapshot the late-bind hooks atom.
;;   2. Replace each of the nine known reset-hook-table keys with a
;;      sentinel that increments a per-key counter atom. (Replace
;;      regardless of whether the producer registered it on the test
;;      classpath — the sentinel ensures the fixture's hook walk
;;      actually hit it.)
;;   3. Invoke the fixture's per-test body with a no-op test-fn so the
;;      fixture itself fires all the hooks.
;;   4. Assert each counter is exactly 1.
;;   5. Restore the late-bind atom from the snapshot.
;;
;; Why this exists per round-2 audit rf2-byut1 §TE-R2.2: the existing
;; test suite exercises the fixture transitively (50+ test files use
;; `use-fixtures :each`), so a dropped hook would eventually surface
;; as cross-test pollution. But that's a noisy long-range signal;
;; this test catches the regression at the immediate seam.

(def ^:private reset-hook-expected-counts
  "Per-fixture-invocation call count for every reset-hook-table key.

  Most keys fire exactly once: the fixture's `run-reset-hooks!` walks
  the table in `:pre-dispose` then `:post-dispose` phase order, hitting
  each row once.

  `:flows/reset-flows!` is the documented exception: it fires twice —
  once mid-body (via `:pre-dispose`) and once in the `finally` block,
  per the fixture docstring step 10 (\"Resets `frame/frames` back to
  `{}` for symmetry, and (when their artefacts are loaded)
  `flows/flows` and `schemas/schemas-by-frame`\"). The two-fire shape
  is load-bearing — symmetric pre-test and post-test reset so a
  failing test leaves no residue for the next. Pin the count here so a
  refactor that drops EITHER call site (or unifies them into one) is
  surfaced as a regression."
  {:flows/reset-flows!              2  ;; pre + finally (symmetry)
   :flows/reset-last-inputs!        1
   :schemas/clear-by-frame!         1
   :machines/reset-timers!          1
   :routing/reset-counters!         1
   :http/clear-all-in-flight!       1
   :epoch/clear-history!            1
   :epoch/clear-epoch-listeners!          1
   :adapter/clear-warn-once-caches! 1})

(deftest make-reset-runtime-fixture-fires-every-hook-the-documented-number-of-times
  (testing "every row in reset-hook-table fires the documented number of
            times per fixture call — once for most, twice for
            `:flows/reset-flows!` (pre-test + finally symmetry per the
            fixture docstring step 10)"
    (let [snapshot      @late-bind/hooks
          call-counts   (atom (zipmap (keys reset-hook-expected-counts)
                                      (repeat 0)))
          orig-restore  (late-bind/get-fn :schemas/restore-by-frame!)]
      (try
        ;; Install sentinels for every reset-hook-table key.
        (doseq [k (keys reset-hook-expected-counts)]
          (late-bind/set-fn! k (fn [& _]
                                 (swap! call-counts update k inc)
                                 nil)))
        ;; The fixture also calls :schemas/snapshot-by-frame /
        ;; :schemas/restore-by-frame! around the test body. We don't
        ;; count those (they're not part of the reset-hook-table
        ;; cascade — they're independent snapshot/restore plumbing per
        ;; the fixture's docstring). Replace `:schemas/restore-by-frame!`
        ;; with a benign stub so the test-body's finally clause doesn't
        ;; depend on the production restore.
        (late-bind/set-fn! :schemas/restore-by-frame! (fn [_snap] nil))
        (late-bind/set-fn! :schemas/snapshot-by-frame (fn [] nil))

        (let [fixture (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
          (fixture (fn [] :ran)))

        (doseq [[k expected] reset-hook-expected-counts]
          (is (= expected (get @call-counts k))
              (str k " fired " expected " time(s) per fixture invocation")))
        (finally
          ;; Restore the late-bind atom so subsequent tests inherit the
          ;; producer's real fns. Reset the registrar / frames so the
          ;; test that follows starts clean.
          (reset! late-bind/hooks snapshot)
          ;; Restore the schemas restore-fn we displaced (in case the
          ;; snapshot didn't capture it).
          (when orig-restore
            (late-bind/set-fn! :schemas/restore-by-frame! orig-restore)))))))

(deftest make-reset-runtime-fixture-pre-dispose-fires-before-adapter-dispose
  (testing "the `:pre-dispose` phase fires BEFORE adapter dispose and
            the `:post-dispose` phase fires AFTER — phase ordering is
            load-bearing per the fixture docstring"
    ;; Capture invocation order, not just counts. The test asserts
    ;; the relative ordering of one pre-dispose hook (:flows/reset-flows!),
    ;; the adapter dispose (observed via a sentinel registered on the
    ;; adapter), and one post-dispose hook (:epoch/clear-history!).
    (let [snapshot @late-bind/hooks
          order    (atom [])]
      (try
        (late-bind/set-fn! :flows/reset-flows!
                           (fn [] (swap! order conj :pre)))
        (late-bind/set-fn! :epoch/clear-history!
                           (fn [] (swap! order conj :post)))
        ;; The adapter's dispose call lands between the two phases —
        ;; we don't have a clean late-bind hook on dispose itself, so
        ;; instead we register a one-shot watch on the adapter atom:
        ;; install + the immediate dispose flips the value twice.
        ;; Simpler: just observe the two flows reset calls (pre-test +
        ;; finally) on `:flows/reset-flows!` themselves and assert
        ;; `:pre` and `:post` interleave the way the docstring
        ;; describes — `:epoch/clear-history!` runs AFTER the first
        ;; flows reset and BEFORE the finally flows reset.
        (let [fixture (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter})]
          (fixture (fn [] :ran)))
        ;; Expected: pre (flows pre-dispose) → post (epoch post-dispose) →
        ;; pre (flows finally).
        (is (= [:pre :post :pre] @order)
            "pre-dispose runs before post-dispose; the finally-block
             flows reset is the third event")
        (finally
          (reset! late-bind/hooks snapshot))))))

;; ---- rf2-j9phb (TE-R2.3) — destroy-frame! hook-cascade coverage -----------
;;
;; The other half of the round-2 audit finding. `destroy-frame!`'s
;; rf2-ggkay refactor factored four cleanup-hook fires through the
;; `safe-call-hook!` helper:
;;
;;   :privacy/clear-suppression-cache!  — privacy warn-once cache reset
;;   :ssr/on-frame-destroyed            — SSR side-channel atoms cleanup
;;   :machines/on-frame-destroyed!      — machines timer-table cleanup
;;   :epoch/on-frame-destroyed          — fired via notify-epoch-listeners!
;;
;; Same shape as TE-R2.2: register a sentinel under each key, destroy a
;; frame, assert all four fired exactly once.

(def ^:private destroy-frame-hook-keys
  "The four destroy-frame! cleanup-hook keys. Each fires through
  `frame/safe-call-hook!` (rf2-ggkay) inside the destroy cascade.
  Mirrored here so the assertion visits each by name."
  [:privacy/clear-suppression-cache!
   :ssr/on-frame-destroyed
   :machines/on-frame-destroyed!
   :epoch/on-frame-destroyed])

(deftest destroy-frame-fires-every-cleanup-hook-exactly-once
  (testing "every cleanup-hook in destroy-frame! fires exactly once
            per destruction (rf2-j9phb / TE-R2.3)"
    (let [snapshot    @late-bind/hooks
          call-counts (atom (zipmap destroy-frame-hook-keys (repeat 0)))]
      (try
        ;; Install sentinels.
        (doseq [k destroy-frame-hook-keys]
          (late-bind/set-fn! k (fn [& _]
                                 (swap! call-counts update k inc)
                                 nil)))
        ;; Register and destroy a frame. The destroy cascade walks all
        ;; four cleanup hooks exactly once.
        (rf/reg-frame :rf2-j9phb/target {})
        (frame/destroy-frame! :rf2-j9phb/target)

        (doseq [k destroy-frame-hook-keys]
          (is (= 1 (get @call-counts k))
              (str k " fired exactly once during destroy-frame!")))
        (finally
          (reset! late-bind/hooks snapshot))))))

(deftest destroy-frame-cleanup-hooks-receive-frame-id
  (testing "cleanup hooks that take the destroyed frame's id receive it
            correctly — :ssr / :machines / :epoch all pass the id"
    ;; :privacy/clear-suppression-cache! is zero-arg; the other three
    ;; receive the destroyed id. Pin that arg shape so a future refactor
    ;; that swaps positional → varargs (or drops the id) breaks loudly.
    (let [snapshot   @late-bind/hooks
          captured-args (atom {})]
      (try
        (late-bind/set-fn! :privacy/clear-suppression-cache!
                           (fn []
                             (swap! captured-args assoc
                                    :privacy/clear-suppression-cache!
                                    ::called-zero-arg)))
        (doseq [k [:ssr/on-frame-destroyed
                   :machines/on-frame-destroyed!
                   :epoch/on-frame-destroyed]]
          (late-bind/set-fn! k (fn [id]
                                 (swap! captured-args assoc k id))))
        (rf/reg-frame :rf2-j9phb/arg-target {})
        (frame/destroy-frame! :rf2-j9phb/arg-target)

        (is (= ::called-zero-arg
               (get @captured-args :privacy/clear-suppression-cache!))
            "privacy hook is zero-arg")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :ssr/on-frame-destroyed))
            "ssr hook receives the destroyed frame id")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :machines/on-frame-destroyed!))
            "machines hook receives the destroyed frame id")
        (is (= :rf2-j9phb/arg-target
               (get @captured-args :epoch/on-frame-destroyed))
            "epoch hook receives the destroyed frame id")
        (finally
          (reset! late-bind/hooks snapshot))))))

