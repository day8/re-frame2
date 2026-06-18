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
      (rf/register-listener! :errors :test/recorder
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
      (rf/register-listener! :errors :test/recorder
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
      (rf/register-listener! :errors :test/recorder
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
      (rf/register-listener! :errors :test/recorder
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
      (rf/register-listener! :errors :test/recorder
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
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :teardown/diagnostic {:doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (try
            (rf/destroy-frame! :teardown/diagnostic)
            (finally (rf/unregister-listener! :trace ::rec)))))
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
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! reports conj record)))
      (rf/register-listener! :trace ::rec (fn [ev] (swap! traces conj ev)))
      (rf/reg-frame :teardown/both {:doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn []
          (try
            (rf/destroy-frame! :teardown/both)
            (finally (rf/unregister-listener! :trace ::rec)))))
      (let [warns   (filter #(= :rf.warning/teardown-hook-exception (:operation %))
                            @traces)
            reps    (filter #(= :rf.error/frame-teardown-failed (:error %)) @reports)]
        (is (= 2 (count warns)) "diagnostic channel: one per-hook row each")
        (is (= 1 (count reps)) "always-on channel: ONE bounded report")
        (is (= 2 (count (:hook-failures (first reps))))
            "the single report carries both hook failures")))))

;; ===========================================================================
;; (e) No-raw-values property of the always-on report (rf2-c80lom)
;; ---------------------------------------------------------------------------
;; The teardown report rides the ALWAYS-ON / production-surviving axis, which
;; is NOT privacy-gated like the dev trace. `dispatch-frame-teardown-report!`
;; builds the record with EXACTLY `{:error :frame :hook-failures :recovery
;; :reason :time}` — deliberately NO `:event` vector and NO app-db slice — and
;; each `:hook-failures` entry is structured-only `{:hook :exception :where}`.
;; The contrast partner `write_after_destroy_always_on_cljs_test.cljc` asserts
;; its record carries no raw values (`:event`/`:frame`/`:exception` nil); the
;; teardown report had no equivalent pin (the F2 review verified it "by
;; construction" only). This pins the property so a future change that folds an
;; app value into the report fails closed.
;; ===========================================================================

(deftest report-record-carries-no-raw-values
  (testing "Per rf2-c80lom / Spec 009 §Observability channels (always-on axis,
            non-privacy-gated): the `:rf.error/frame-teardown-failed` record's
            keys are EXACTLY the known structured set — no `:event` vector, no
            `:app-db` slice, no raw app-supplied payload — so a destroy report
            on a `goog.DEBUG=false` host carries no user data off-box. Each
            `:hook-failures` entry is structured-only `{:hook :exception
            :where}`. (The per-hook `:exception` object can itself carry app
            data in its ex-data — that is a SPEC question for 009, not pinned
            here; see the rf2-c80lom companion note.)"
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (rf/reg-frame :teardown/no-raw {:doc "two hooks throw"})
      (with-hooks*
        {:ssr/on-frame-destroyed      (throwing-hook :ssr)
         :schemas/on-frame-destroyed! (throwing-hook :schemas)}
        (fn [] (rf/destroy-frame! :teardown/no-raw)))
      (let [r (first (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen))]
        (is (some? r) "the report fired")
        (is (= #{:error :frame :hook-failures :recovery :reason :time}
               (set (keys r)))
            "the report record's keys are EXACTLY the known structured set —
             no :event vector, no :app-db slice, no raw payload leak")
        (is (not (contains? r :event))
            "no :event vector — a destroy report is not a per-event throw")
        (is (not (contains? r :app-db))
            "no :app-db slice rides the always-on report")
        (doseq [entry (:hook-failures r)]
          (is (= #{:hook :exception :where} (set (keys entry)))
              "each :hook-failures entry is structured-only {:hook :exception
               :where} — no raw user value folded into the entry"))))))

;; ===========================================================================
;; (f) Multi-listener fan-out + throwing-sibling isolation on the REPORT path
;; (rf2-tvoc63)
;; ---------------------------------------------------------------------------
;; `on_error_test.cljc` pins the per-event axis (`dispatch-on-error!`) against
;; multiple listeners incl. a throwing one (`error-listener-exception-is-
;; swallowed` → sibling still receives). The bounded-report sibling
;; (`dispatch-frame-teardown-report!`) shares the SAME `(:fan-out registry)`,
;; so the isolation is correct by construction — but it was only ever exercised
;; against a single recorder listener. This pins the throwing-sibling isolation
;; for the report fn directly.
;; ===========================================================================

(deftest report-fans-out-across-multiple-listeners-throwing-sibling-isolated
  (testing "Per rf2-tvoc63 / Spec 009 §register-error-listener! fan-out: the
            teardown report fans out to EVERY registered listener, and a
            throwing listener cannot starve a sibling — the report still
            reaches the recorder. The report path shares the per-event axis's
            `(:fan-out registry)`, so the defensive fan-out holds for it too."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/throws
                                   (fn [_record]
                                     (throw (ex-info "listener went boom" {}))))
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; Direct substrate exercise — the same fn frame.cljc reaches via the
      ;; :error-emit/dispatch-frame-teardown-report late-bind hook.
      (error-emit/dispatch-frame-teardown-report!
        :prod/frame
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}]
        12345)
      (is (= 1 (count @seen))
          "the report still reached the sibling recorder despite the throwing
           listener (fan-out is defensive across listeners)")
      (is (= :rf.error/frame-teardown-failed (:error (first @seen))))))

  (testing "Per rf2-tvoc63 (latent same-hook-key note): `safe-call-hook!`
            conj's one entry per call, so if the SAME hook key were to throw
            twice in one destroy the report would carry two entries with the
            same `:hook` (no de-dup). The current recipe calls each hook once,
            so this is latent — pinned directly at the report shape via a
            hand-built two-same-key failure vector to document the
            accumulate-don't-dedup contract."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      (error-emit/dispatch-frame-teardown-report!
        :prod/frame
        [{:hook :ssr/on-frame-destroyed :exception (ex-info "x" {}) :where :safe-call-hook!}
         {:hook :ssr/on-frame-destroyed :exception (ex-info "y" {}) :where :safe-call-hook!}]
        99)
      (let [r (first @seen)]
        (is (= 2 (count (:hook-failures r)))
            "duplicate hook keys accumulate — the report does NOT de-dup by
             :hook (one entry per safe-call-hook! failure)")
        (is (= [:ssr/on-frame-destroyed :ssr/on-frame-destroyed]
               (map :hook (:hook-failures r)))
            "both same-key entries are preserved in order")))))

;; ===========================================================================
;; (g) Re-entrant / nested destroy accumulator isolation (rf2-chpdkr)
;; ---------------------------------------------------------------------------
;; `destroy-frame!` holds the per-destroy hook-failure accumulator in a fresh
;; `(atom [])` bound to the dynamic `*teardown-hook-failures*` PER CALL. Spec
;; 002 re-entrancy (rf2-r1ciy) supports a nested `destroy-frame!` for a
;; DIFFERENT id from inside an `:on-destroy` handler. The correctness-by-
;; construction claim is that each destroy gets its OWN accumulator (the
;; `binding` shadows), so a nested destroy's hook failures cannot leak into the
;; outer destroy's report and vice-versa, and each emits its own bounded report.
;; This isolation invariant had NO test — a future refactor to a non-dynamic
;; accumulator would silently break it. Pinned here.
;;
;; Mechanism: `fire-on-destroy-event!` (teardown step 1) runs the user
;; `:on-destroy` synchronously BEFORE the outer frame's own cleanup hooks
;; (step *). So an `:on-destroy` that triggers a nested `destroy-frame!` of a
;; DIFFERENT frame runs that inner teardown — incl. the inner finally-flush —
;; fully nested inside the outer's step 1, while the outer's accumulator is
;; still empty. The `binding` shadow gives the inner destroy its own atom.
;;
;; Note: the cleanup hooks are late-bound by KEY (global), not per-frame — so
;; BOTH A and B run the same recipe and BOTH fail every installed hook. The
;; isolation invariant is therefore: each report carries exactly N entries
;; (its OWN extent's failures), NOT 2N (the combined set). A leak from the
;; binding-shadow breaking would show up as a 2N (double-counted) report.
;; ===========================================================================

(deftest nested-destroy-accumulators-are-isolated
  (testing "Per rf2-chpdkr / Spec 002 re-entrancy (rf2-r1ciy) + rf2-ini4wr: a
            frame A whose `:on-destroy` triggers a nested `destroy-frame!` of a
            DIFFERENT frame B, with throwing cleanup hooks installed for BOTH
            extents, yields TWO independent `:rf.error/frame-teardown-failed`
            reports — each carrying ONLY its own extent's failures (no
            cross-contamination, no double-count). The dynamic
            `*teardown-hook-failures*` binding shadow gives each destroy its own
            accumulator: the inner (B) destroy runs nested inside A's
            `:on-destroy` (step 1) under a SHADOWED atom, so its failures do not
            land in A's accumulator and A's later failures do not land in B's."
    (let [seen (atom [])]
      (rf/register-listener! :errors :test/recorder
                                   (fn [record] (swap! seen conj record)))
      ;; B: the inner frame, destroyed nested from A's :on-destroy.
      (rf/reg-frame :teardown/inner-B {:doc "inner frame, destroyed nested"})
      ;; A's :on-destroy event destroys B mid-teardown of A. B's full teardown
      ;; (incl. its finally-flush report) completes nested inside A's step 1,
      ;; while A's own accumulator is still empty (A's own hooks run AFTER).
      (rf/reg-event :teardown/destroy-inner
                       (fn [{:keys [db]} _]
                         (rf/destroy-frame! :teardown/inner-B)
                         {:db db}))
      (rf/reg-frame :teardown/outer-A
                    {:doc        "outer frame"
                     :on-destroy [:teardown/destroy-inner]})
      ;; Three throwing hooks installed for the whole extent. Both A and B run
      ;; the full recipe, so BOTH fail all three. The binding shadow must keep
      ;; the two accumulators separate — each report carries exactly THREE, not
      ;; six.
      (with-hooks*
        {:ssr/on-frame-destroyed           (throwing-hook :ssr)
         :schemas/on-frame-destroyed!      (throwing-hook :schemas)
         :flows/teardown-on-frame-destroy! (throwing-hook :flows)}
        (fn [] (rf/destroy-frame! :teardown/outer-A)))
      (let [reports  (filter #(= :rf.error/frame-teardown-failed (:error %)) @seen)
            by-frame (into {} (map (juxt :frame identity)) reports)
            report-A (get by-frame :teardown/outer-A)
            report-B (get by-frame :teardown/inner-B)
            expected #{:ssr/on-frame-destroyed
                       :schemas/on-frame-destroyed!
                       :flows/teardown-on-frame-destroy!}]
        (is (= 2 (count reports))
            "TWO independent reports — one per destroy (A and B), each frame-
             attributed; the binding shadow did not collapse them into one")
        (is (some? report-A) "the outer (A) report fired")
        (is (some? report-B) "the inner (B) report fired")
        ;; B's report carries exactly its OWN three failures — NOT six (which
        ;; would mean A's accumulator leaked into B's), NOT zero.
        (is (= 3 (count (:hook-failures report-B)))
            "B's report carries exactly its OWN three hook failures — no leak
             from / into A's extent (a broken binding shadow would show 6)")
        (is (= expected (set (map :hook (:hook-failures report-B))))
            "B's report carries B's own failing hook keys, no duplicates")
        ;; A's report carries exactly its OWN three failures — NOT six (B's
        ;; nested failures did NOT leak into A's accumulator).
        (is (= 3 (count (:hook-failures report-A)))
            "A's report carries exactly its OWN three hook failures — B's
             nested failures did NOT leak into A's accumulator (no double-count)")
        (is (= expected (set (map :hook (:hook-failures report-A))))
            "A's report carries A's own failing hook keys, no duplicates")))))
