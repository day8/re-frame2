(ns re-frame.ui.test-tier3-dom-cljs-test
  "Mounted Tier-3 contract for re-frame.ui.test: real React ownership,
  native scoped CSS queries, deterministic flush, and total teardown."
  (:require [cljs.test :refer [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? [] (exists? js/document))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter ui/adapter
                                            :ambient-frame nil
                                            :async? true}))

(defview query-fixture []
  [:section {:data-scope "inside"}
   [:input {:data-role "field" :value "ready" :read-only true}]
   [:span "mounted"]])

(defn throwing-foreign-component [^js props]
  (throw (.-error props)))

(defview throwing-fixture [{:keys [error]}]
  (ui/raw
   (React/createElement throwing-foreign-component #js {:error error})))

(defview strict-query-fixture []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement query-fixture nil))))

(defview observed-fixture []
  [:output {:data-role "observed"} (ui/sub [::observed-value])])

(defview strict-observed-fixture []
  (ui/raw
   (React/createElement (.-StrictMode React) nil
                        (React/createElement observed-fixture nil))))

(defn commit-probe [^js props]
  (React/useLayoutEffect
   (fn []
     (swap! (.-evidence props) update :commits inc)
     js/undefined))
  nil)

(defn lifecycle-probe [^js props]
  (let [evidence (.-evidence props)
        label    (.-label props)]
    (React/useLayoutEffect
     (fn []
       (swap! evidence conj [:mount label])
       (fn [] (swap! evidence conj [:cleanup label])))
     #js [])
    (React/createElement "span" #js {:data-lifecycle label} label)))

(defview lifecycle-fixture [{:keys [evidence label]}]
  (ui/raw
   (React/createElement lifecycle-probe
                        #js {:evidence evidence :label label})))

(defn throwing-cleanup-probe [^js props]
  (let [cleanup-error (.-cleanupError props)]
    (React/useLayoutEffect
     (fn [] (fn [] (throw cleanup-error)))
     #js [])
    (React/createElement "span" nil "cleanup-owner")))

(defview throwing-cleanup-fixture [{:keys [cleanup-error]}]
  (ui/raw
   (React/createElement throwing-cleanup-probe
                        #js {:cleanupError cleanup-error})))

(defview fixed-point-fixture [{:keys [evidence]}]
  (let [_ (swap! evidence update :renders inc)
        n (ui/sub [::fixed-point-value])
        _ (swap! evidence update :reads inc)]
    [:output {:data-role "fixed-point"}
     (str n)
     (ui/raw (React/createElement commit-probe #js {:evidence evidence}))]))

(defn cascading-commit-probe [^js props]
  (let [n        (.-n props)
        f        (.-frame props)
        evidence (.-evidence props)]
    (React/useLayoutEffect
     (fn []
       (swap! evidence update :commits inc)
       (when (and (= 1 n) (not (:cascaded? @evidence)))
         (swap! evidence assoc :cascaded? true)
         (uit/dispatch! f [::cascade-step]))
       js/undefined)
     #js [n])
    nil))

(defview cascading-fixed-point-fixture [{:keys [evidence frame]}]
  (let [_ (swap! evidence update :renders inc)
        n (ui/sub [::cascade-value])]
    [:output {:data-role "cascade-fixed-point"}
     (str n)
     (ui/raw
      (React/createElement cascading-commit-probe
                           #js {:n n :frame frame :evidence evidence}))]))

(defn- mounted-baseline []
  {:live-roots (client/live-root-ids)
   :cells      (reactive/root-cell-count)
   :containers (.-length
                (.querySelectorAll js/document "[data-rf-ui-test-root]"))})

(defn- error-id [f]
  (try (f) nil
       (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- reject-unexpectedly!
  [done label e]
  (is false (str label ": " e))
  (done))

(deftest with-root-mounts-queries-and-tears-down
  (when (browser?)
    (async done
      (let [outside (doto (js/document.createElement "input")
                      (.setAttribute "data-role" "field"))
            before  (mounted-baseline)]
        (.appendChild js/document.body outside)
        (let [p (uit/with-root [root [strict-query-fixture]]
                  ;; Initial mount/commit has ALREADY settled before this body.
                  (let [inside (uit/query root "[data-role='field']")]
                    (is (some? inside))
                    (is (not (identical? outside inside)) "query is container-scoped")
                    (is (= "ready" (.-value inside)))))]
          (is (instance? js/Promise p) "CLJS with-root returns a Promise")
          (-> p
              (.then (fn []
                       (is (= before (mounted-baseline))
                           "the Promise settles only after total teardown")
                       (.remove outside)
                       (done))
                     (fn [e]
                       (.remove outside)
                       (reject-unexpectedly! done "with-root rejected" e)))))))))

(deftest with-root-preserves-body-failure-and-still-tears-down
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            primary (ex-info "body failed" {:kind ::body-failed})]
        (-> (uit/with-root [_root [query-fixture]]
              (throw primary))
            (.then (fn []
                     (reject-unexpectedly! done "body failure resolved" nil))
                   (fn [e]
                     (is (identical? primary e) "the primary body error is preserved")
                     (is (= before (mounted-baseline))
                         "rejection settles after root, ViewCell, and DOM teardown")
                     (done))))))))

(deftest with-root-awaits-an-async-body
  (when (browser?)
    (async done
      (let [evidence (atom [])]
        (-> (uit/with-root [root [query-fixture]]
              (swap! evidence conj :body-start)
              (-> (js/Promise.resolve nil)
                  (.then (fn []
                           (is (= "mounted" (.-textContent (uit/query root "span"))))
                           (swap! evidence conj :body-finished)))))
            (.then (fn []
                     (swap! evidence conj :with-root-settled)
                     (is (= [:body-start :body-finished :with-root-settled]
                            @evidence))
                     (done))
                   (fn [e]
                     (reject-unexpectedly! done "async body rejected" e))))))))

(deftest with-root-resolves-to-the-awaited-body-value
  (when (browser?)
    (async done
      (-> (uit/with-root [_root [query-fixture]]
            (js/Promise.resolve ::body-result))
          (.then (fn [value]
                   (is (= ::body-result value)
                       "teardown does not replace the awaited body result")
                   (done))
                 (fn [e]
                   (reject-unexpectedly! done "body-result root rejected" e)))))))

(deftest nested-with-root-restores-each-ownership-scope
  (when (browser?)
    (async done
      (let [before   (mounted-baseline)
            evidence (atom [])]
        (-> (uit/with-root [outer [lifecycle-fixture
                                   {:evidence evidence :label "outer"}]]
              (let [outer-state (mounted-baseline)]
                (is (= "outer" (.-textContent (uit/query outer "span"))))
                (-> (uit/with-root [inner [lifecycle-fixture
                                           {:evidence evidence :label "inner"}]]
                      (is (= (+ 2 (count (:live-roots before)))
                             (count (:live-roots (mounted-baseline)))))
                      (is (= "inner" (.-textContent (uit/query inner "span"))))
                      (swap! evidence conj :inner-body))
                    (.then (fn []
                             (swap! evidence conj :inner-settled)
                             (is (= outer-state (mounted-baseline))
                                 "inner teardown leaves the outer scope live"))))))
            (.then (fn []
                     (is (= [[:mount "outer"] [:mount "inner"] :inner-body
                             [:cleanup "inner"] :inner-settled [:cleanup "outer"]]
                            @evidence)
                         "awaited nested roots clean up in strict LIFO order")
                     (is (= before (mounted-baseline)))
                     (done))
                   (fn [e]
                     (reject-unexpectedly! done "nested with-root rejected" e))))))))

(deftest with-root-tears-down-a-root-whose-first-render-fails
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            primary (ex-info "render failed" {:kind ::render-failed})]
        (-> (uit/with-root [_root [throwing-fixture {:error primary}]]
              (is false "the body is unreachable"))
            (.then (fn []
                     (reject-unexpectedly! done "render failure resolved" nil))
                   (fn [e]
                     (is (identical? primary e))
                     (is (= before (mounted-baseline))
                         "failed initial act still restores every ownership baseline")
                     (done))))))))

(deftest with-root-preserves-flush-failure-and-still-tears-down
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            primary (ex-info "flush failed" {:kind ::flush-failed})]
        (-> (uit/with-root [_root [query-fixture]]
              (uit/flush! #(throw primary)))
            (.then (fn []
                     (reject-unexpectedly! done "flush failure resolved" nil))
                   (fn [e]
                     (is (identical? primary e))
                     (is (= before (mounted-baseline))
                         "flush rejection cannot strand mounted ownership")
                     (done))))))))

(deftest cleanup-failure-is-attached-without-masking-the-primary
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            primary (ex-info "primary body failure" {:kind ::primary})
            cleanup (js/Error. "cleanup failed")]
        (-> (uit/with-root [_root [throwing-cleanup-fixture
                                   {:cleanup-error cleanup}]]
              (throw primary))
            (.then (fn []
                     (reject-unexpectedly! done "dual failure resolved" nil))
                   (fn [e]
                     (is (identical? primary e))
                     (is (identical? cleanup
                                     (unchecked-get e "rfUiTestCleanupError"))
                         "cleanup diagnostic rides the preserved primary error")
                     (is (= before (mounted-baseline)))
                     (done))))))))

(deftest cleanup-only-failure-rejects-with-the-cleanup-error
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            cleanup (js/Error. "cleanup-only failure")]
        (-> (uit/with-root [_root [throwing-cleanup-fixture
                                   {:cleanup-error cleanup}]]
              ::body-succeeded)
            (.then (fn [_]
                     (reject-unexpectedly! done "cleanup-only failure resolved" nil))
                   (fn [e]
                     (is (identical? cleanup e)
                         "without a body failure, cleanup is the primary rejection")
                     (is (= before (mounted-baseline)))
                     (done))))))))

;; --- rf2-vxgfnd.201: failure/teardown tracking by PRESENCE, not JS truthiness.
;; A body/mount/flush/cleanup may reject with ANY value — including a falsy one
;; (nil, false). Those are REAL failures and must not be reclassified as success;
;; a body that legitimately returns nil/false is a REAL success and must not be
;; reclassified as a failure. (Off-DOM decision coverage: test-outcome-cljs-test.)

(deftest with-root-preserves-a-falsy-body-rejection-and-still-tears-down
  (when (browser?)
    (async done
      (let [before (mounted-baseline)]
        (-> (uit/with-root [_root [query-fixture]]
              (js/Promise.reject false))
            (.then
             (fn [] (throw (js/Error. "reject(false) body unexpectedly resolved")))
             (fn [e]
               (is (false? e)
                   "Promise.reject(false) is a real failure, not a swallowed success")
               (is (= before (mounted-baseline))
                   "a falsy rejection still settles after total teardown")
               (uit/with-root [_root [query-fixture]]
                 (js/Promise.reject nil))))
            (.then
             (fn [] (reject-unexpectedly! done "reject(nil) body unexpectedly resolved" nil))
             (fn [e]
               (is (nil? e) "Promise.reject(nil) is a real failure")
               (is (= before (mounted-baseline)))
               (done))))))))

(deftest with-root-resolves-a-falsy-body-value
  (when (browser?)
    (async done
      (-> (uit/with-root [_root [query-fixture]] false)
          (.then
           (fn [value]
             (is (false? value)
                 "a body returning false succeeds with false — not misread as a failure")
             (uit/with-root [_root [query-fixture]] nil))
           (fn [e] (reject-unexpectedly! done "false body value rejected" e)))
          (.then
           (fn [value]
             (is (nil? value) "a body returning nil succeeds with nil")
             (done))
           (fn [e] (reject-unexpectedly! done "nil body value rejected" e)))))))

(deftest with-root-preserves-a-falsy-flush-failure
  (when (browser?)
    (async done
      (let [before (mounted-baseline)]
        (-> (uit/with-root [_root [query-fixture]]
              (uit/flush! #(throw false)))
            (.then (fn [] (reject-unexpectedly! done "falsy flush failure resolved" nil))
                   (fn [e]
                     (is (false? e) "a flush thunk throwing false is a real failure")
                     (is (= before (mounted-baseline))
                         "a falsy flush rejection cannot strand mounted ownership")
                     (done))))))))

(deftest a-falsy-cleanup-failure-rides-the-primary-and-is-not-lost
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            primary (ex-info "primary body failure" {:kind ::primary})]
        (-> (uit/with-root [_root [throwing-cleanup-fixture {:cleanup-error false}]]
              (throw primary))
            (.then (fn [] (reject-unexpectedly! done "falsy-cleanup dual failure resolved" nil))
                   (fn [e]
                     (is (identical? primary e) "the primary reason is preserved and thrown")
                     (is (false? (unchecked-get e "rfUiTestCleanupError"))
                         "a FALSY cleanup failure still rides the object primary — presence, not truthiness")
                     (is (= before (mounted-baseline)))
                     (done))))))))

(deftest a-falsy-primary-wins-over-a-truthy-cleanup-failure
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            cleanup (js/Error. "cleanup failed")]
        (-> (uit/with-root [_root [throwing-cleanup-fixture {:cleanup-error cleanup}]]
              (js/Promise.reject false))
            (.then (fn [] (reject-unexpectedly! done "falsy-primary + cleanup resolved" nil))
                   (fn [e]
                     (is (false? e)
                         "a falsy PRIMARY failure wins as the rejection over a later cleanup failure")
                     (is (= before (mounted-baseline))
                         "both roots/containers are still reclaimed under a primitive primary")
                     (done))))))))

(deftest act-environment-is-restored-after-success-and-rejection
  (when (browser?)
    (async done
      (let [prior   (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)
            primary (js/Error. "body rejection")]
        (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
        (-> (uit/with-root [_root [query-fixture]] nil)
            (.then
             (fn []
               (is (false? (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
                   "success restores the exact pre-existing false value")
               (uit/with-root [_root [query-fixture]] (throw primary))))
            (.then
             (fn []
               (throw (js/Error. "rejected body unexpectedly resolved")))
             (fn [e]
               (is (identical? primary e))
               (is (false? (.-IS_REACT_ACT_ENVIRONMENT js/globalThis))
                   "rejection restores the exact pre-existing false value")
               nil))
            (.then
             (fn []
               (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)
               (done))
             (fn [e]
               (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) prior)
               (reject-unexpectedly! done "act-environment test rejected" e))))))))

(deftest with-root-strictmode-teardown-releases-cells-and-observation-owners
  (when (browser?)
    (rf/reg-sub ::observed-value (fn [db _] (:value db)))
    (let [f            (rf/make-frame {:initial-events [[:rf/set-db {:value "owned"}]]})
          frame-id     (frame/frame-target->id f)
          cell-baseline (reactive/root-cell-count)]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [strict-observed-fixture]]]
              (is (= "owned" (.-textContent
                               (uit/query root "[data-role='observed']"))))
              (is (> (reactive/root-cell-count) cell-baseline)
                  "the settled initial commit owns a ViewCell")
              (is (some? (get @(:sub-cache (frame/frame frame-id))
                              [::observed-value]))))
            (.then (fn []
                     (is (= cell-baseline (reactive/root-cell-count)))
                     (is (nil? (get @(:sub-cache (frame/frame frame-id))
                                    [::observed-value])))
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (reject-unexpectedly! done "StrictMode teardown rejected" e))))))))

(deftest query-rejects-the-wrong-tier-and-non-css-input
  (when (browser?)
    (async done
      (-> (uit/with-root [root [query-fixture]]
            (is (= :rf.error/ui-test-bad-selector
                   (error-id #(uit/query root :input))))
            (is (= :rf.error/ui-test-tier-mismatch
                   (error-id #(uit/query {:tag :div :children []} "div")))))
          (.then (fn [] (done))
                 (fn [e]
                   (reject-unexpectedly! done "query test rejected" e)))))))

(deftest overlapping-unawaited-act-fails-loud
  (when (browser?)
    (async done
      (let [release (volatile! nil)
            first  (uit/flush!
                    (fn []
                      (js/Promise.
                       (fn [resolve _reject]
                         (vreset! release resolve)))))]
        (is (= :rf.error/ui-test-overlapping-act
               (error-id uit/flush!))
            "a forgotten await is rejected synchronously at the second call")
        (@release nil)
        (-> first
            (.then (fn [] (done))
                   (fn [e]
                     (reject-unexpectedly! done "first flush rejected" e))))))))

(deftest overlapping-with-root-preflight-allocates-nothing
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            release (volatile! nil)
            first   (uit/flush!
                     (fn []
                       (js/Promise.
                        (fn [resolve _reject]
                          (vreset! release resolve)))))
            thrown  (try
                      (uit/with-root [_root [query-fixture]]
                        (is false "the rejected root body is unreachable"))
                      nil
                      (catch cljs.core/ExceptionInfo e e))]
        (is (= :rf.error/ui-test-overlapping-act
               (:rf.error/id (ex-data thrown))))
        (is (= before (mounted-baseline))
            "the synchronous overlap guard runs before container/root allocation")
        (@release nil)
        (-> first
            (.then (fn []
                     (is (= before (mounted-baseline)))
                     (done))
                   (fn [e]
                     (reject-unexpectedly! done "first flush rejected" e))))))))

(deftest unawaited-nested-root-rejects-but-reclaims-every-owner
  (when (browser?)
    (async done
      (let [before  (mounted-baseline)
            inner-p (volatile! nil)]
        (-> (uit/with-root [_outer [query-fixture]]
              ;; Deliberate misuse: start a nested owner and fail to return its
              ;; Promise. The outer teardown collides with the nested mount act.
              (vreset! inner-p
                       (uit/with-root [_inner [query-fixture]]
                         nil))
              nil)
            (.then
             (fn []
               (reject-unexpectedly! done "unawaited nested root resolved" nil))
             (fn [e]
               (is (= :rf.error/ui-test-overlapping-act
                      (:rf.error/id (ex-data e)))
                   "forgotten nesting is still a loud contract failure")
               (-> @inner-p
                   (.then (fn []
                            (is (= before (mounted-baseline))
                                "queued cleanup reclaims both roots and containers")
                            (done))
                          (fn [inner-error]
                            (reject-unexpectedly!
                             done "nested root cleanup rejected" inner-error)))))))))))

(deftest flush-rejects-an-open-event-drain
  (when (browser?)
    (rf/reg-event ::flush-during-handler
                  (fn [{:keys [db]} _]
                    {:db (assoc db :flush-error
                                (error-id uit/flush!))}))
    (let [f (rf/make-frame {:initial-events [[:rf/set-db {}]]})]
      (try
        (uit/dispatch! f [::flush-during-handler])
        (is (= :rf.error/flush-in-open-epoch
               (:flush-error (rf/app-db-value f))))
        (finally
          (rf/destroy-frame! f))))))

(deftest flush-rejects-a-run-after-its-frame-destroys-itself
  (when (browser?)
    (let [target (volatile! nil)
          seen   (volatile! nil)]
      (rf/reg-event ::destroy-self-then-flush
                    (fn [_ _]
                      (rf/destroy-frame! @target)
                      (vreset! seen (error-id uit/flush!))
                      nil))
      (let [f (rf/make-frame {:initial-events [[:rf/set-db {}]]})]
        (vreset! target f)
        (uit/dispatch! f [::destroy-self-then-flush])
        (is (= :rf.error/flush-in-open-epoch @seen)
            "the current run remains open after its frame leaves the registry")))))

(deftest mounted-dispatch-and-flush-settle-one-read-render-commit
  (when (browser?)
    (rf/reg-sub ::fixed-point-value (fn [db _] (:n db)))
    (rf/reg-event ::fixed-point-step
                  (fn [{:keys [db]} [_ remaining]]
                    (cond-> {:db (update db :n inc)}
                      (pos? remaining)
                      (assoc :fx [[:dispatch [::fixed-point-step
                                              (dec remaining)]]]))))
    (let [f        (rf/make-frame {:initial-events [[:rf/set-db {:n 0}]]})
          evidence (atom {:reads 0 :renders 0 :commits 0})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [fixed-point-fixture {:evidence evidence}]]]
              (reset! evidence {:reads 0 :renders 0 :commits 0})

              ;; The thunk itself runs inside the awaited act operation. Its
              ;; eight queued events finish their update and commit phases synchronously;
              ;; Promise settlement then performs the one read/render batch.
              (let [p (uit/flush!
                       (fn []
                         (is (true? (.-IS_REACT_ACT_ENVIRONMENT js/globalThis)))
                         (uit/dispatch! f [::fixed-point-step 7])))]
                (is (= 8 (:n (rf/app-db-value f))))
                (is (= "0" (.-textContent
                             (uit/query root "[data-role='fixed-point']"))))
                (is (pos? (reactive/pending-cell-count)))
                (is (= {:reads 0 :renders 0 :commits 0} @evidence))
                (-> p
                    (.then (fn []
                             (is (= "8" (.-textContent
                                          (uit/query root "[data-role='fixed-point']"))))
                             (is (= {:reads 1 :renders 1 :commits 1} @evidence)
                                 "eight writes settle into one read/render/layout commit")
                             (is (zero? (reactive/pending-cell-count))))))))
            (.then (fn []
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (reject-unexpectedly! done "fixed-point test rejected" e))))))))

(deftest flush-promise-recurses-when-the-first-commit-marks-more-work
  (when (browser?)
    (rf/reg-sub ::cascade-value (fn [db _] (:n db)))
    (rf/reg-event ::cascade-step
                  (fn [{:keys [db]} _]
                    {:db (update db :n inc)}))
    (let [f        (rf/make-frame {:initial-events [[:rf/set-db {:n 0}]]})
          evidence (atom {:renders 0 :commits 0 :cascaded? false})]
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [cascading-fixed-point-fixture
                                   {:evidence evidence :frame f}]]]
              (reset! evidence {:renders 0 :commits 0 :cascaded? false})
              (-> (uit/flush! #(uit/dispatch! f [::cascade-step]))
                  (.then (fn []
                           (is (= "2" (.-textContent
                                        (uit/query root
                                                   "[data-role='cascade-fixed-point']"))))
                           nil))))
            (.then (fn []
                     (is (= 2 (:n (rf/app-db-value f)))
                         "the first layout commit synchronously queued a second write")
                     (is (= {:renders 2 :commits 2 :cascaded? true} @evidence)
                         "one returned Promise waited for both drain/commit cycles")
                     (is (zero? (reactive/pending-cell-count)))
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (reject-unexpectedly! done "recursive fixed-point rejected" e))))))))

;; rf2-qxgve — the PUBLIC async `flush!` nonconvergence BOUND.
;;
;; The finite cascade above proves `flush-async!`'s Promise recursion RUNS, but
;; only for two passes — it never reaches the bound. And the #6038 proofs
;; (`reactive-flush-convergence-bound-cljs-test`,
;; `substrate-flush-render-convergence-cljs-test`) drive the SHARED synchronous
;; `converge-flush!` law and the synchronous first-party adapter directly; none
;; drives the public async `ui.test/flush!` path, which INDEPENDENTLY implements
;; its Promise recursion AND its own pass comparison against
;; `reactive/flush-convergence-budget` in `re-frame.ui.test/flush-async!`.
;; Removing or misplacing THAT async check restores unbounded Promise chaining
;; while every synchronous proof stays green (the false-green).
;;
;; DRIVING THE BOUND DETERMINISTICALLY. A real re-dirtying path — a listener or a
;; mounted commit that re-marks a ViewCell on every flush — cannot cleanly drive
;; this loop to its bound: re-frame's invalidation arms an AUTONOMOUS
;; `schedule-flush!` microtask, so a perpetual re-dirty spins an INDEPENDENT drain
;; loop that outraces (and can starve) the act-gated async passes — the registry
;; quiesces or the runner stalls before pass `budget`, nondeterministically
;; (empirically it resolved at whatever re-mark cap was chosen, never at the
;; bound). Instead we make the registry genuinely non-quiescent with NO re-dirty
;; loop: a really-dirty `cell` (the REAL `pending-cell-count` sees it, so the
;; bound logic runs against real registry state) whose DRAIN is WITHHELD —
;; `flush-pending!` is neutralized for the first `drain-at` async passes
;; (drain-at > budget), then restored so the registry can finally settle. The
;; single microtask `mark-dirty!` arms hits the withholding stub (no drain, no
;; re-mark) and stops, so there is no runaway loop. With the bound present, the
;; async flush! rejects at `budget` passes (before drain-at). TEETH: remove or
;; misplace the async bound and flush! recurses until the drain lands at
;; `drain-at`, then RESOLVES — the `:rejected` assertion fails, a crisp red rather
;; than a runner-stalling hang. Cleanup is proven on every ownership axis: the
;; mounted-baseline (no root/container/root-cell leak), a zero pending-cell count
;; (the withheld cell drained), and a follow-up public flush! that resolves — a
;; strand of the mid-flight act would reject it `:rf.error/ui-test-overlapping-act`.
(deftest flush-async-nonconvergence-trips-the-public-bound
  (when (browser?)
    (let [before     (mounted-baseline)
          real-flush reactive/flush-pending!
          restore!   (fn [] (set! reactive/flush-pending! real-flush))
          passes     (atom 0)
          drain-at   (+ reactive/flush-convergence-budget 10)
          outcome    (volatile! nil)]
      (async done
        (-> (uit/with-root [_root [query-fixture]]
              (let [cell (reactive/make-cell ::runaway)]
                ;; A genuinely dirty cell whose drain is withheld: every async
                ;; flush pass finds the registry non-quiescent, with NO re-dirty
                ;; loop. The one microtask `mark-dirty!` arms hits the stub (no
                ;; drain, no re-mark) and stops. `flush-pending!` is restored to
                ;; real once `drain-at` (> budget) passes elapse, so a
                ;; bound-REMOVED flush can finally quiesce and RESOLVE.
                (reactive/mark-dirty! cell)
                (set! reactive/flush-pending!
                      (fn []
                        (when (>= (swap! passes inc) drain-at)
                          (real-flush))))
                ;; Drive the PUBLIC async flush! (flush-async!'s Promise
                ;; recursion). Settle EITHER way inside the body so with-root
                ;; still runs total teardown; assert on the outcome after.
                (-> (uit/flush!)
                    (.then (fn [_] (restore!) (vreset! outcome [:resolved @passes]))
                           (fn [e] (restore!) (vreset! outcome [:rejected e]))))))
            (.then
             (fn []
               (reactive/flush-pending!) ; drain the withheld cell (restored; no re-dirty)
               (let [[kind e] @outcome
                     data     (ex-data e)]
                 (is (= :rejected kind)
                     (str "a non-quiescent async flush! must fail loud at the "
                          "bound, not chain Promises forever (removing/misplacing "
                          "the flush-async! bound lets it quiesce at drain-at and "
                          "RESOLVE, failing HERE); got " (pr-str @outcome)))
                 (is (= :rf.error/flush-convergence-exceeded (:rf.error/id data))
                     "the SHARED typed nonconvergence diagnostic")
                 (is (= reactive/flush-convergence-budget (:passes data))
                     "carries the exhausted shared budget")
                 (is (pos? (:pending data))
                     "carries a positive residual pending-cell count")
                 (is (= 'rf.ui.test/flush! (:where data))
                     "names the public flush! forcing site — not the sync adapter")
                 (is (= :no-recovery (:recovery data)))
                 (is (= before (mounted-baseline))
                     "teardown leaves no mounted roots, containers, or root cells")
                 (is (zero? (reactive/pending-cell-count))
                     "the withheld cell drained clean — no dirty cell survives"))
               ;; No active-act ownership stranded by the mid-flight rejection: a
               ;; fresh public flush! resolves (a strand rejects overlapping-act).
               (uit/flush!))
             (fn [e]
               (restore!)
               (reject-unexpectedly! done "nonconvergence flush test rejected" e)))
            (.then (fn [_] (done))
                   (fn [e]
                     (reject-unexpectedly!
                      done "post-teardown flush! rejected (stranded act ownership?)" e))))))))
