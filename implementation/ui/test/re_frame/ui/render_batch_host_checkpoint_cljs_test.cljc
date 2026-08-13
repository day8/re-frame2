(ns re-frame.ui.render-batch-host-checkpoint-cljs-test
  "rf2-vxgfnd.166 — the render-batch boundary is the HOST CHECKPOINT, driven by
  the REAL ROUTER.

  The retired rule said a render batch ends when a router drain completes. It
  does not, and the scheduler could not implement it if it wanted to: there is
  no hook from router drain finalization into `re-frame.ui.reactive`. The
  boundary is the pending read/render window, armed by the FIRST dirty mark and
  closed at the next host checkpoint — the next CLJS host microtask, or an
  explicit headless/test flush.

  The sibling `reactive-epoch-cljs-test` drives `mark-dirty!` / `flush-pending!`
  directly, so it cannot say anything about a ROUTER boundary. Every fixture
  here runs REAL `rf/dispatch-sync` drains and observes the window across them.

  ## The two real invalidation channels, and why the host picks one

  A committed ViewCell handle is invalidated by the observation port, and the
  port has more than one real channel. These fixtures use whichever one the
  host actually has:

    - VALUE MOVEMENT (CLJS only). `ui/adapter` on CLJS installs the retained
      watchable React substrate, so an app-db move fires a committed handle's
      on-change during the drain.
    - CANONICAL-NODE DISPOSAL (both hosts). Re-registering a sub disposes its
      canonical node and fires every committed handle's real on-change — the
      channel HMR drives, and the one the G-3 gate already uses.

  On the JVM `ui/adapter` IS `plain-atom/adapter`, whose derived value owns no
  source watches (it recomputes on deref), so an app-db move has NO push
  channel there at all — a movement is caught later, at the commit evidence
  comparison. That is a real, measured host divergence, so `invalidate!` below
  fires BOTH channels: the drain and its epoch are real on both hosts, and at
  least one real port on-change lands on both hosts. Nothing here pokes the
  scheduler seam directly.

  ## The rows

    - TWO SEQUENTIAL DRAINS, NO YIELD — two back-to-back `dispatch-sync!`
      drains complete in one stack before any checkpoint, and share ONE batch
      (guarantee 3). The window is asserted STILL OPEN after the first drain
      finished, which is the direct falsification of the retired rule.
    - NESTED CROSS-FRAME DRAIN — a drain nested inside another frame's drain
      likewise shares the one pending window.
    - LISTENER RE-ENTRY — re-marking after a batch COMPLETED opens a fresh
      window with no router drain at all, so a drain is not what opens one.
    - N EPOCHS IN ONE DRAIN — the queued cascade still coalesces (guarantee 2),
      unchanged by this correction.

  Guarantee 4 (drains separated by a real host yield render separately) needs a
  genuine microtask checkpoint and a mounted root, so it lives in
  `render-batch-host-checkpoint-dom-cljs-test`.

  `.cljc` ending `-cljs-test` rides `npm run test:cljs` (node) AND
  `clojure -M:test` (JVM), so the boundary is pinned on both hosts."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core         :as rf]
            [re-frame.frame        :as frame]
            [re-frame.test-support :as test-support]
            [re-frame.ui           :as ui]
            [re-frame.ui.reactive  :as reactive]))

(use-fixtures :each
  ;; `:async?` declares async-CAPABILITY; `make-reset-runtime-fixture` picks
  ;; the shape per host (rf2-e8ea) — the map on CLJS, the fn-form on the JVM,
  ;; where `clojure.test` would silently swallow a map fixture. So this is a
  ;; plain option, not the reader-conditional `cond->` dance it used to be.
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true})
  #?(:clj
     (fn [f]
       (reactive/reset-scheduler!)
       (try (f) (finally (reactive/reset-scheduler!))))
     :cljs
     {:before reactive/reset-scheduler!
      :after  reactive/reset-scheduler!}))

(def ^:private fid :rbhc/frame)
(def ^:private other-fid :rbhc/other)

(defn- reg-n-sub! [] (rf/reg-sub :rbhc/n (fn [db _] (:n db))))

(defn- register-frame!
  [id]
  (rf/make-frame {:id id :doc "render-batch host-checkpoint probe frame"})
  (reg-n-sub!)
  (rf/reg-event :rbhc/seed (fn [_ _] {:db {:n 0}}))
  (rf/reg-event :rbhc/inc (fn [{:keys [db]} _] {:db (update db :n inc)})))

(defn- observe!
  "Render+commit `cell` against `[:rbhc/n]` under frame `id`, so it holds a real
  committed handle whose on-change the port will fire."
  [cell id]
  (let [[_ capture] (rf/with-frame id
                      (reactive/with-capture
                       cell (fn [] [(reactive/sub-read [:rbhc/site 0] [:rbhc/n])])))]
    (reactive/commit! cell capture))
  cell)

(defn- invalidate!
  "ONE complete, real router drain that really invalidates the committed handle
  on both hosts. `dispatch-sync` runs the drain to completion and commits its
  epoch; the re-registration fires the port's canonical-node-disposal on-change
  (the only push channel the JVM plain-atom realization has). Returns nil."
  [id]
  (rf/dispatch-sync [:rbhc/inc] {:frame id})
  (reg-n-sub!)
  nil)

;; ===========================================================================
;; TWO SEQUENTIAL ROUTER DRAINS BEFORE ONE CHECKPOINT → ONE BATCH
;; ===========================================================================

(deftest two-sequential-router-drains-share-one-batch
  ;; The rf2-vxgfnd.166 correction, stated executably. Each `dispatch-sync!` is
  ;; a COMPLETE run-to-completion router drain. Two run back-to-back in one
  ;; stack with no host yield between. Under the retired rule that is two render
  ;; batches. Under the shipped scheduler it is ONE: the first mark armed the
  ;; pending window, the second drain folded into it, and nothing closed the
  ;; window until the explicit checkpoint.
  (register-frame! fid)
  (rf/dispatch-sync [:rbhc/seed] {:frame fid})
  (let [cell (observe! (reactive/make-cell ::seq) fid)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (is (= 0 (reactive/revision cell)) "baseline: nothing pending yet")

    ;; DRAIN 1 — a complete router drain, start to finish.
    (invalidate! fid)
    (testing "the FIRST drain has fully completed and did NOT close a batch"
      ;; This is the direct falsification of the retired per-drain rule.
      (is (reactive/dirty? cell)
          "the cell is pending after a FINISHED drain — drain completion is
           not a batch boundary")
      (is (= 0 (reactive/revision cell))
          "no revision advance: the window is still open across the drain end")
      (is (= 0 @hits) "and no notification has fired"))

    ;; DRAIN 2 — a second complete, INDEPENDENT router drain, same stack.
    (invalidate! fid)
    (testing "the second drain folds into the SAME still-open window"
      (is (= 1 (reactive/pending-cell-count))
          "enrolled ONCE across two separate completed drains"))

    (testing "the explicit host checkpoint closes ONE batch for both drains"
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell))
          "two router drains before one checkpoint ⇒ ONE revision advance")
      (is (= 1 @hits)
          "⇒ ONE notification, not one per drain (rf2-vxgfnd.166)")
      (is (= 2 (:n (frame/frame-app-db-value fid)))
          "…while BOTH drains really ran — coalescing loses no write"))))

;; ===========================================================================
;; NESTED CROSS-FRAME SYNCHRONOUS DRAIN → ONE BATCH
;; ===========================================================================

(deftest nested-cross-frame-drains-share-one-batch
  ;; A handler in frame A synchronously drains frame B. Two frames, two drains,
  ;; one nested inside the other — still one pending window, because the window
  ;; belongs to the host checkpoint and not to any frame's router.
  (register-frame! fid)
  (rf/make-frame {:id other-fid :doc "render-batch nested-drain peer frame"})
  (rf/reg-event :rbhc/outer
                (fn [{:keys [db]} _]
                  ;; a genuine nested synchronous drain of the PEER frame,
                  ;; run inside this handler's own drain
                  (rf/dispatch-sync [:rbhc/inc] {:frame other-fid})
                  {:db (update db :n inc)}))
  (rf/dispatch-sync [:rbhc/seed] {:frame fid})
  (rf/dispatch-sync [:rbhc/seed] {:frame other-fid})
  (let [cell-a (observe! (reactive/make-cell ::nested-a) fid)
        cell-b (observe! (reactive/make-cell ::nested-b) other-fid)
        hits-a (atom 0)
        hits-b (atom 0)]
    (reactive/subscribe cell-a (fn [] (swap! hits-a inc)))
    (reactive/subscribe cell-b (fn [] (swap! hits-b inc)))

    (rf/dispatch-sync [:rbhc/outer] {:frame fid})
    (reg-n-sub!)                       ;; the both-host port on-change

    (testing "the nested drain folded into the same pending window"
      (is (= 2 (reactive/pending-cell-count))
          "both frames' cells are pending in ONE window")
      (is (= 0 (reactive/revision cell-a)))
      (is (= 0 (reactive/revision cell-b))))

    (testing "one checkpoint closes one batch across both frames"
      (reactive/flush-pending!)
      (is (= 1 (reactive/revision cell-a)) "outer frame advanced once")
      (is (= 1 (reactive/revision cell-b)) "nested frame advanced once")
      (is (= 1 @hits-a))
      (is (= 1 @hits-b)))))

;; ===========================================================================
;; LISTENER RE-ENTRY OPENS A FRESH WINDOW WITH NO ROUTER DRAIN
;; ===========================================================================

(deftest listener-re-entry-opens-a-fresh-window-without-a-new-drain
  ;; The converse proof. If a router drain were what opens a render batch, a
  ;; re-mark with NO drain running could not open one. It does: the window is
  ;; opened by the first mark after the previous batch completed, whoever makes
  ;; it. Here a subscriber re-marks its own cell during notification — after
  ;; phase 1 completed the batch — and a fresh window opens with no router
  ;; involvement whatsoever.
  (register-frame! fid)
  (rf/dispatch-sync [:rbhc/seed] {:frame fid})
  (let [cell     (observe! (reactive/make-cell ::re-entry) fid)
        hits     (atom 0)
        re-mark? (atom true)]
    (reactive/subscribe cell
                        (fn []
                          (swap! hits inc)
                          ;; re-enter EXACTLY once, from inside the notification
                          (when (compare-and-set! re-mark? true false)
                            (reactive/mark-dirty! cell 99))))
    (invalidate! fid)
    (reactive/flush-pending!)

    (is (= 1 @hits) "the first batch notified once")
    (is (= 1 (reactive/revision cell)) "and advanced the revision once")
    (testing "the listener's re-mark opened a NEW pending window, no drain"
      (is (reactive/dirty? cell)
          "a fresh window is open although no router drain began")
      (is (= 1 (reactive/pending-cell-count))))
    (testing "the next checkpoint closes that window as its own batch"
      (reactive/flush-pending!)
      (is (= 2 (reactive/revision cell)))
      (is (= 2 @hits)))))

;; ===========================================================================
;; GUARANTEE 2 IS UNCHANGED — a queued cascade in ONE drain is ONE batch
;; ===========================================================================

(deftest a-queued-cascade-in-one-drain-remains-one-batch
  ;; The correction weakens nothing that already held. A parent event that
  ;; queues further events settles them all in ONE run-to-completion drain, and
  ;; the whole cascade is still exactly one batch — now because the drain cannot
  ;; be split across a checkpoint (guarantee 1), not because the drain's end IS
  ;; the boundary.
  (register-frame! fid)
  (rf/reg-event :rbhc/cascade
                (fn [{:keys [db]} _]
                  {:db (update db :n inc)
                   :fx [[:dispatch [:rbhc/inc]]
                        [:dispatch [:rbhc/inc]]
                        [:dispatch [:rbhc/inc]]]}))
  (rf/dispatch-sync [:rbhc/seed] {:frame fid})
  (let [cell (observe! (reactive/make-cell ::cascade) fid)
        hits (atom 0)]
    (reactive/subscribe cell (fn [] (swap! hits inc)))
    (rf/dispatch-sync [:rbhc/cascade] {:frame fid})
    (reg-n-sub!)                       ;; the both-host port on-change
    (is (= 1 (reactive/pending-cell-count))
        "the whole queued cascade enrolled the cell ONCE")
    (reactive/flush-pending!)
    (is (= 1 (reactive/revision cell))
        "a parent event plus its three queued children ⇒ ONE revision advance")
    (is (= 1 @hits) "⇒ ONE notification for the whole cascade")
    (is (= 4 (:n (frame/frame-app-db-value fid)))
        "…and every queued event really did run (the cascade was not truncated)")))
