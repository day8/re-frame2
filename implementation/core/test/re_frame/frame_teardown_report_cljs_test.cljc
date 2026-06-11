(ns re-frame.frame-teardown-report-cljs-test
  "EP-0008 / rf2-ini4wr — the frame-teardown report. On frame destroy,
  the best-effort teardown recipe runs many optional late-bound cleanup
  hooks (`:ssr/on-frame-destroyed`, `:schemas/on-frame-destroyed!`,
  `:flows/teardown-on-frame-destroy!`, …). When one or more throw, the
  runtime emits ONE bounded always-on `:rf.error/frame-teardown-failed`
  record carrying a `:hook-failures` vector — NOT one always-on emission
  per hook (Spec 009 §Observability channels §Channel-promotion catalogue
  rows; the promotion of the EP-0008 C4 prod-silence bug off the DCE'd
  `:rf.warning/teardown-hook-exception`).

  Pins the four acceptance legs:

    (a) N hook failures → exactly ONE always-on report carrying N
        `:hook-failures` entries (single report, not per-hook flood).
    (b) Partial-teardown-abort still flushes the collected entries — the
        FINALLY boundary (EP-0008 R1): a downstream teardown step throws
        AFTER some hooks failed; the report still ships the entries
        gathered so far.
    (c) The report rides the ALWAYS-ON axis — exercised via the
        `register-error-listener!` substrate that survives a production
        build path (`error-emit/dispatch-frame-teardown-report!` is NOT
        gated by `interop/debug-enabled?`).
    (d) The dev per-hook DIAGNOSTIC rows still emit at their causal
        positions (EP-0008 R2 — per-hook visibility is KEPT on the
        diagnostic axis; only the always-on emission collapsed).

  Dual-runtime: named `*_cljs_test.cljc` so the shadow-cljs `:node-test`
  build (`npm run test:cljs`, `:ns-regexp \"cljs-test$\"`) AND the JVM
  `clojure -M:test` runner both pick it up. The teardown path is plain
  CLJC; no DOM dependency."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.error-emit :as error-emit]
            [re-frame.late-bind :as late-bind]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]))

;; ---------------------------------------------------------------------------
;; Fixture — fresh registrar + plain-atom adapter per test; the always-on
;; error-listener registry (a `defonce` atom) cleared so a listener from one
;; test cannot leak into the next.
;; ---------------------------------------------------------------------------

(use-fixtures :each
  (ts/make-reset-runtime-fixture
    {:adapter plain-atom/adapter
     :init-fn (fn []
                (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Cleanup-hook-key install helper.
;;
;; The teardown cleanup hooks are late-bound (optional artefacts). In a
;; core-only build they are usually UNBOUND. We install a throwing fn under
;; a hook key for the duration of `f`, snapshotting + restoring the prior
;; binding so the install never leaks across tests (set-fn! to the original,
;; or to nil when there was none — get-fn returns nil for both, the unbound
;; state).
;; ---------------------------------------------------------------------------

(defn- with-hooks*
  "Install each `hook-key -> fn` from `hook-map` via `late-bind/set-fn!`
  for the dynamic extent of `f`, restoring the prior bindings after."
  [hook-map f]
  (let [originals (into {} (map (fn [k] [k (late-bind/get-fn k)]) (keys hook-map)))]
    (try
      (doseq [[k v] hook-map] (late-bind/set-fn! k v))
      (f)
      (finally
        (doseq [[k orig] originals] (late-bind/set-fn! k orig))))))

(defn- throwing-hook
  "A cleanup-hook fn that always throws — models a leaked optional-artefact
  cleanup. Accepts any arity (the cache-reset hooks take no frame arg; the
  per-frame hooks take an id)."
  [label]
  (fn [& _] (throw (ex-info (str "teardown hook threw: " label) {:hook label}))))

;; ===========================================================================
;; (a) N hook failures → exactly ONE always-on report with N entries
;; ===========================================================================

(deftest n-hook-failures-yield-one-report-with-n-entries
  (testing "Per rf2-ini4wr / Spec 009 §Channel-promotion catalogue rows:
            N cleanup hooks throwing during destroy produce EXACTLY ONE
            always-on `:rf.error/frame-teardown-failed` record carrying N
            `:hook-failures` entries — NOT one record per failed hook."
    (let [seen (atom [])]
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :teardown/n-failures {:doc "three hooks will throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed         (throwing-hook :ssr)
         :schemas/on-frame-destroyed!    (throwing-hook :schemas)
         :flows/teardown-on-frame-destroy! (throwing-hook :flows)}
        (fn [] (rf/destroy-frame! :teardown/n-failures)))
      (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)]
        (is (= 1 (count reports))
            "exactly ONE always-on report per destroy — not three (one per hook)")
        (let [r (first reports)]
          (is (= :teardown/n-failures (:frame r))
              ":frame names the destroyed frame")
          (is (= 3 (count (:hook-failures r)))
              "the report carries one :hook-failures entry per failed hook")
          (is (= #{:ssr/on-frame-destroyed
                   :schemas/on-frame-destroyed!
                   :flows/teardown-on-frame-destroy!}
                 (set (map :hook (:hook-failures r))))
              "every failed hook key is represented in :hook-failures")
          (is (every? #(= :safe-call-hook! (:where %)) (:hook-failures r))
              "each entry carries :where :safe-call-hook!")
          (is (every? #(some? (:exception %)) (:hook-failures r))
              "each entry carries the thrown exception")
          (is (= :ignored (:recovery r))
              ":recovery :ignored — teardown is best-effort")
          (is (string? (:reason r)) ":reason is a human-readable sentence")
          (is (number? (:time r)) ":time is a wall-clock millis number"))))))

(deftest clean-destroy-emits-no-report
  (testing "Per rf2-ini4wr: a destroy with NO failing hook emits NO
            `:rf.error/frame-teardown-failed` report (the report fn
            short-circuits on an empty :hook-failures vector)."
    (let [seen (atom [])]
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :teardown/clean {:doc "no hooks throw"})
      (rf/destroy-frame! :teardown/clean)
      (is (empty? (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen))
          "no report when teardown completes cleanly"))))

;; ===========================================================================
;; (b) Partial-teardown-abort still flushes — the FINALLY boundary (R1)
;; ===========================================================================

(deftest partial-teardown-abort-still-flushes-collected-entries
  (testing "Per rf2-ini4wr EP-0008 R1 / Spec 009 §Emit-safety (finally-
            shaped flush): if teardown ABORTS mid-recipe after some hooks
            have already failed, the entries collected so far MUST still
            ship. We make two cleanup hooks throw (accumulating two
            entries) and then force a downstream teardown step
            (`emit-frame-destroyed-trace!`, which runs AFTER the cleanup
            hooks) to throw unrecoverably — the throw propagates out of
            `destroy-frame!`, yet the finally-shaped flush still emits the
            report with the two gathered entries."
    (let [seen (atom [])]
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :teardown/abort {:doc "aborts mid-teardown"})
      (with-hooks*
        ;; These two run BEFORE emit-frame-destroyed-trace! in the recipe,
        ;; so both accumulate before the abort.
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          ;; Force a mid-teardown collapse: a downstream NON-hook step
          ;; throws. `safe-call-hook!` swallows hook throws, so to model a
          ;; genuine abort we redef a later teardown step to throw.
          (with-redefs [frame/emit-frame-destroyed-trace!
                        (fn [_id]
                          (throw (ex-info "mid-teardown collapse" {})))]
            (is (thrown? #?(:clj Throwable :cljs js/Error)
                         (rf/destroy-frame! :teardown/abort))
                "the downstream teardown step's throw propagates"))))
      (let [reports (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)]
        (is (= 1 (count reports))
            "the report STILL flushed despite the mid-teardown abort")
        (let [r (first reports)]
          (is (= 2 (count (:hook-failures r)))
              "the report carries the TWO entries gathered before the abort
               (the finally boundary flushed the partial accumulation)")
          (is (= #{:ssr/on-frame-destroyed :schemas/on-frame-destroyed!}
                 (set (map :hook (:hook-failures r))))
              "the gathered hook keys are exactly the ones that ran + threw
               before the collapse"))))))

;; ===========================================================================
;; (c) The report rides the ALWAYS-ON axis (survives the production path)
;; ===========================================================================

(deftest report-rides-the-always-on-axis
  (testing "Per rf2-ini4wr EP-0008: the report is delivered through the
            corpus-wide `register-error-listener!` substrate
            (`error-emit/dispatch-frame-teardown-report!`), which is NOT
            gated by `interop/debug-enabled?` and so survives `:advanced`
            + `goog.DEBUG=false`. Exercising the listener directly proves
            the report is on the always-on axis, not the DCE'd diagnostic
            trace. (Companion to the dispatch-on-error always-on contract
            in on-error-test.)"
    (let [seen (atom [])]
      ;; Direct substrate exercise — the same fn frame.cljc reaches via
      ;; the :error-emit/dispatch-frame-teardown-report late-bind hook.
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (error-emit/dispatch-frame-teardown-report!
        :prod/frame
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}]
        12345)
      (is (= 1 (count @seen)) "the always-on listener received the report")
      (let [r (first @seen)]
        (is (= :rf.error/frame-teardown-failed (:error r)))
        (is (= :prod/frame (:frame r)))
        (is (= 1 (count (:hook-failures r))))
        (is (= 12345 (:time r))))))

  (testing "Per rf2-ini4wr: the report fn is a no-op on an empty
            :hook-failures vector (no failures, no always-on flood)."
    (let [seen (atom [])]
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (error-emit/dispatch-frame-teardown-report! :prod/frame [] 1)
      (is (empty? @seen) "empty :hook-failures → no record fanned out"))))

(deftest report-late-bind-hook-is-published
  (testing "Per rf2-ini4wr: error-emit publishes the
            `:error-emit/dispatch-frame-teardown-report` late-bind hook
            (frame.cljc reaches it via late-bind to avoid the
            error-emit → elision → frame load cycle)."
    (is (some? (late-bind/get-fn :error-emit/dispatch-frame-teardown-report))
        "the hook is registered at error-emit ns-load")))

;; ===========================================================================
;; (d) Dev per-hook DIAGNOSTIC rows still emit at causal positions (R2)
;; ===========================================================================

(deftest dev-per-hook-diagnostic-rows-still-emit
  (testing "Per rf2-ini4wr EP-0008 R2 / Spec 009: the per-hook
            `:rf.warning/teardown-hook-exception` DIAGNOSTIC trace still
            emits at its causal position inside `safe-call-hook!` (dev
            visibility KEPT — only the always-on emission collapsed to the
            single report). One diagnostic row per failed hook, carrying
            the hook key + frame."
    (let [traces (atom [])]
      (rf/register-listener! ::rec (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :teardown/diagnostic {:doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (try
            (rf/destroy-frame! :teardown/diagnostic)
            (finally (rf/unregister-listener! ::rec)))))
      (let [warns (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                          @traces)]
        ;; The trace surface is live in dev (this runner), so the per-hook
        ;; rows are present. (Under :advanced + goog.DEBUG=false they DCE —
        ;; the contract is that they ride the DIAGNOSTIC channel, not that
        ;; they survive prod.)
        (is (= 2 (count warns))
            "one diagnostic row per failed hook at its causal position")
        (is (= #{:ssr/on-frame-destroyed :schemas/on-frame-destroyed!}
               (set (map #(get-in % [:tags :hook]) warns)))
            "each diagnostic row names the hook that threw")
        (is (every? #(= :teardown/diagnostic (get-in % [:tags :frame])) warns)
            "each diagnostic row is frame-attributed")))))

(deftest both-channels-fire-together
  (testing "Per rf2-ini4wr: a single destroy with failing hooks fires
            BOTH channels — the dev per-hook diagnostic rows (one each)
            AND the one always-on report (carrying both). The two axes are
            independent and complementary (Spec 009 §Observability
            channels)."
    (let [traces (atom [])
          reports (atom [])]
      (rf/register-error-listener! :test/recorder
                                   (fn [record] (swap! reports conj record)))
      (rf/register-listener! ::rec (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :teardown/both {:doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (try
            (rf/destroy-frame! :teardown/both)
            (finally (rf/unregister-listener! ::rec)))))
      (let [warns   (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                            @traces)
            reps    (filter #(= :rf.error/frame-teardown-failed (:error %)) @reports)]
        (is (= 2 (count warns)) "diagnostic channel: one per-hook row each")
        (is (= 1 (count reps)) "always-on channel: ONE bounded report")
        (is (= 2 (count (:hook-failures (first reps))))
            "the single report carries both hook failures")))))
