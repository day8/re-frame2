(ns re-frame.capture-frame-test
  "`capture-frame`, the public hold primitive, and `re-frame.frame/bind-fn`,
  the internal rebinding primitive. Per Spec 002 §capture-frame.

  A capture targets the frame it was created against and pins that frame's
  exact incarnation: an op fired after the incarnation is destroyed and a
  same-id successor reseated recovers-but-emits `:rf.error/frame-destroyed`
  instead of reaching the successor. The interposition helpers below put the
  destroy-and-reseat at each seam a concurrent JVM can interleave it.

  The fence assertions read the always-on error-emit registry rather than the
  dev trace, so they hold under the production gate — including the zero-emit
  negatives, which over a dev trace ring would pass for free."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.router :as rf.router]
            [re-frame.subs :as rf.subs]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` registers no frame.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr :reload)
  (require 're-frame.machines :reload)
  (test-fn))

(use-fixtures :each reset-runtime)

(deftest capture-frame-captures-frame-at-creation
  (testing "(capture-frame) captures the scope's frame at creation; its :dispatch
            still routes there after the scope unwinds"
    (rf/make-frame {:id :fh/A})
    (rf/reg-event :fh/inc (fn [{:keys [db]} _] {:db (update db :n (fnil inc 0))}))
    (let [{:keys [dispatch]} (rf/with-frame :fh/A (rf/capture-frame))]
      (dispatch [:fh/inc])
      (is (rf.test-support/poll-until #(= 1 (:n (rf/app-db-value :fh/A)))
                                      {:label "captured handle drains to :fh/A"})))))

(deftest capture-frame-locked-frame-cannot-be-overridden
  (testing "a per-call :frame opt cannot redirect a captured :dispatch"
    (rf/make-frame {:id :fh/locked})
    (rf/make-frame {:id :fh/other})
    (rf/reg-event :fh/touch (fn [{:keys [db]} _] {:db (assoc db :touched? true)}))
    (let [{:keys [dispatch]} (rf/capture-frame :fh/locked)]
      (dispatch [:fh/touch] {:frame :fh/other})
      (is (rf.test-support/poll-until #(:touched? (rf/app-db-value :fh/locked))
                                      {:label "the event lands in the captured frame"})))))

;; ---- incarnation fence ------------------------------------------------------
;;
;; The fence on each captured op at the pre-check and late-mismatch seams, and
;; the record it emits, are pinned by
;; `capture_frame_reincarnation_sink_route_cljs_test.cljc`.

(deftest capture-frame-over-value-pins-exact-incarnation
  (testing "(capture-frame <frame-value>) pins the value's own incarnation token:
            live, it dispatches into its frame; once that incarnation is destroyed
            and a same-id successor reseated, it no longer reaches the successor"
    (rf/reg-event :fh/mark (fn [{:keys [db]} [_ v]] {:db (assoc db :mark v)}))
    (let [frame-a (rf/make-frame {:id :fh/vpin})
          {:keys [dispatch-sync]} (rf/capture-frame frame-a)]
      (dispatch-sync [:fh/mark :A-mark])
      (is (= :A-mark (:mark (rf/app-db-value :fh/vpin)))
          "a live value capture is not spuriously superseded")
      (rf/destroy-frame! frame-a)
      (rf/make-frame {:id :fh/vpin})
      (dispatch-sync [:fh/mark :leaked])
      (is (nil? (:mark (rf/app-db-value :fh/vpin)))
          "the stale value capture did not mutate the successor"))))

;; The pre-check validates the pinned incarnation, then the op resolves its
;; target by id. On the JVM, A can be destroyed and B installed between the two,
;; so the captured incarnation is carried through to the router / sub resolve.
;; This helper reproduces that window single-threaded: a one-shot interposition
;; on `frame-incarnation-live?` swaps A for B at the moment the pre-check reads
;; A as live.
(defn- run-supersede-during-precheck
  [frame-id a-token op]
  (let [real  rf.frame/frame-incarnation-live?
        fired (atom false)]
    (with-redefs [rf.frame/frame-incarnation-live?
                  (fn [id token]
                    (let [live? (real id token)]
                      (when (and (not @fired)
                                 (= id frame-id)
                                 (identical? token a-token)
                                 live?)
                        ;; set first, so the destroy/create's own reads take the real path
                        (reset! fired true)
                        (rf/destroy-frame! frame-id)
                        (rf/make-frame {:id frame-id}))
                      live?))]
      (op))))

(deftest stale-capture-subscribe-race-does-not-read-or-cache-in-same-id-successor
  (testing "a captured subscribe whose pre-check validated A, superseded by same-id
            B before the sub-cache resolve, caches nothing in B. Its nil result and
            single emit at this seam are pinned for every op by
            capture-frame-reincarnation-sink-route-cljs-test."
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/race})
    (let [a-token (rf.frame/frame-incarnation-token :fh/race)
          {:keys [subscribe]} (rf/capture-frame :fh/race)]
      (run-supersede-during-precheck :fh/race a-token #(subscribe [:fh/value]))
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race)))))))

;; The durable build a subscribe miss delegates to re-resolves the frame, so the
;; pinned incarnation is carried through `compute-and-cache!` too. This helper
;; swaps A for B on the first entry to `compute-and-cache!` for `trigger-qv` —
;; after the outer comparison passed, which the pre-check interposition above
;; cannot reach.
(defn- run-supersede-at-build
  [frame-id trigger-qv op]
  (let [real  @#'rf.subs/compute-and-cache!
        fired (atom false)]
    (with-redefs [rf.subs/compute-and-cache!
                  (fn [& args]
                    (when (and (= trigger-qv (second args))
                               (compare-and-set! fired false true))
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id}))
                    (apply real args))]
      (op))))

(deftest stale-capture-subscribe-post-comparison-miss-does-not-retarget-successor
  (testing "miss arm: superseded by same-id B after the outer incarnation comparison
            but before the durable build, a captured subscribe returns nil, caches
            nothing in B and emits exactly once"
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/race})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/race)
          errs   (atom [])
          _      (rf.error-emit/register-error-listener! ::pcb-miss (fn [rec] (swap! errs conj rec)))
          result (run-supersede-at-build :fh/race [:fh/value] #(subscribe [:fh/value]))]
      (rf.error-emit/unregister-error-listener! ::pcb-miss)
      (is (nil? result))
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race))))
      (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))))))

(deftest live-capture-subscribe-miss-still-reads-its-own-incarnation
  (testing "control: a live captured subscribe that misses builds against its own
            incarnation, reads its value and caches in its own sub-cache"
    (rf/reg-event :fh/seed (fn [{:keys [db]} [_ v]] {:db {:value v}}))
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/make-frame {:id :fh/live})
    (rf/dispatch-sync [:fh/seed :A-value] {:frame :fh/live})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/live)]
      (is (= :A-value @(subscribe [:fh/value])))
      (is (contains? @(:sub-cache (rf.frame/frame :fh/live)) [:fh/value])))))

(deftest stale-capture-subscribe-recursive-input-not-resolved-in-successor
  (testing "recursive-input arm: superseded by same-id B before a layer-2 sub's
            input build, neither the entry nor its input is cached in B, and the
            input build's fence emits"
    (rf/reg-sub :fh/value (fn [db _] (:value db)))
    (rf/reg-sub :fh/derived {:inputs [[:fh/value]]} (fn [[v] _] [:derived v]))
    (rf/make-frame {:id :fh/race})
    (let [{:keys [subscribe]} (rf/capture-frame :fh/race)
          errs (atom [])]
      (rf.error-emit/register-error-listener! ::pcb-rec (fn [rec] (swap! errs conj rec)))
      (run-supersede-at-build :fh/race [:fh/value] #(subscribe [:fh/derived]))
      (rf.error-emit/unregister-error-listener! ::pcb-rec)
      (is (empty? @(:sub-cache (rf.frame/frame :fh/race))))
      (is (some #(= :rf.error/frame-destroyed (:error %)) @errs)))))

;; ---- A lost after the token match -------------------------------------------
;;
;; A can also be lost after `dispatch!` / `dispatch-sync!` pass the token
;; comparison but before the async enqueue linearizes / the sync drain-lock is
;; acquired. The seam's own re-check keeps B untouched, and must still emit:
;; a silent return there would break recover-but-emit. These helpers swap A
;; for B on the first entry to that seam.

(defn- run-supersede-at-enqueue
  [frame-id op]
  (let [real  @#'rf.router/ensure-drain-scheduled!
        fired (atom false)]
    (with-redefs [rf.router/ensure-drain-scheduled!
                  (fn [fid frame-record router envelope continue?]
                    (when (compare-and-set! fired false true)
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id}))
                    (real fid frame-record router envelope continue?))]
      (op))))

(defn- run-supersede-at-drain-block
  [frame-id op]
  (let [real  @#'rf.router/drain-block!
        fired (atom false)]
    (with-redefs [rf.router/drain-block!
                  (fn [fid frame-record under-lock-fn]
                    (when (compare-and-set! fired false true)
                      (rf/destroy-frame! frame-id)
                      (rf/make-frame {:id frame-id}))
                    (real fid frame-record under-lock-fn))]
      (op))))

(deftest stale-capture-post-token-match-emits-exactly-once
  (testing "a captured dispatch that passed the token comparison, then lost A
            before the async enqueue / the sync drain-lock acquire, delivers
            nothing into successor B and emits exactly one
            :rf.error/frame-destroyed"
    (rf/reg-event :fh/mark (fn [{:keys [db]} _] {:db (assoc db :marked-by :stale-capture)}))
    (doseq [[op supersede!] [[:dispatch      run-supersede-at-enqueue]
                             [:dispatch-sync run-supersede-at-drain-block]]]
      (testing (name op)
        (let [fid   (keyword "fh.race" (name op))
              _     (rf/make-frame {:id fid})
              op-fn (op (rf/capture-frame fid))
              errs  (atom [])]
          (rf.error-emit/register-error-listener! ::post-token (fn [rec] (swap! errs conj rec)))
          (supersede! fid #(op-fn [:fh/mark]))
          (rf.error-emit/unregister-error-listener! ::post-token)
          (is (nil? (:marked-by (rf/app-db-value fid))))
          (is (= 1 (count (filter #(= :rf.error/frame-destroyed (:error %)) @errs)))))))))

;; ---- only the originating event OWNER dies ----------------------------------
;;
;; The enqueue / drain guard fuses owner liveness with target liveness, so it
;; also comes back falsey when only the ORIGINATING event owner died (a callback
;; still running after destroying its own frame) while the captured target is
;; live. Emitting frame-destroyed there would name a live frame. This helper
;; destroys only the owner on the first entry to `emit-dispatched-trace!`, the
;; first step of the captured op past the token comparison.

(defn- run-owner-death-at-dispatch
  [owner-id op]
  (let [real  @#'rf.router/emit-dispatched-trace!
        fired (atom false)]
    (with-redefs [rf.router/emit-dispatched-trace!
                  (fn
                    ([envelope sync?]
                     (real envelope sync?))
                    ([envelope sync? continue?]
                     (when (compare-and-set! fired false true)
                       (rf/destroy-frame! owner-id))
                     (real envelope sync? continue?)))]
      (op))))

(deftest live-target-not-reported-destroyed-when-only-owner-dies
  (testing "a captured dispatch whose originating event owner dies during the
            dispatch seam, while the captured target stays live, is dropped and
            emits no :rf.error/frame-destroyed"
    (rf/reg-event :audit/touch (fn [{:keys [db]} _] {:db (assoc db :marked-by :owner-death)}))
    (doseq [op [:dispatch :dispatch-sync]]
      (testing (name op)
        (let [owner       (keyword "audit.owner" (name op))
              target      (keyword "audit.target" (name op))
              _           (rf/make-frame {:id owner})
              _           (rf/make-frame {:id target})
              owner-token (rf.frame/frame-incarnation-token owner)
              op-fn       (op (rf/capture-frame target))
              errs        (atom [])]
          (rf.error-emit/register-error-listener! ::owner-death (fn [rec] (swap! errs conj rec)))
          (rf.frame/call-with-event-owner-token owner owner-token
            (fn [] (run-owner-death-at-dispatch owner #(op-fn [:audit/touch]))))
          (rf.error-emit/unregister-error-listener! ::owner-death)
          (is (nil? (rf.frame/frame owner)) "precondition: the interposition destroyed the owner")
          (is (nil? (:marked-by (rf/app-db-value target))) "the op is dropped; the live target is untouched")
          (is (not-any? #(= :rf.error/frame-destroyed (:error %)) @errs)
              "a live target is never reported destroyed for an owner cutoff"))))))

;; ---- no scope ---------------------------------------------------------------

(deftest frame-readers-outside-a-scope-raise-no-frame-context
  (testing "with no scope there is no :rf/default floor: each no-arg reader raises
            :rf.error/no-frame-context naming its own operation"
    (doseq [[operation reader] [[:capture-frame    #(rf/capture-frame)]
                                [:current-frame-id #(rf/current-frame-id)]]]
      (let [data (try (reader) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
        (is (= {:rf.error/id :rf.error/no-frame-context :operation operation}
               (select-keys data [:rf.error/id :operation])))))))

(deftest bind-fn-binds-explicit-frame-with-no-surrounding-scope
  (testing "bind-fn re-establishes its explicit frame around an arbitrary held fn,
            with no scope at creation or at call"
    (is (= :fbf/C ((rf.frame/bind-fn :fbf/C rf/current-frame-id))))))
