(ns re-frame.ui.reactive-root-teardown-cljs-test
  "rf2-vxgfnd.62 — explicit host/ROOT teardown → ViewCell dead-state wiring.

  The frame-destroy arm (rf2-vxgfnd.42) gave `teardown!` a caller for the
  frame path; this fixture pins the ROOT path — the counterpart driven from
  `re-frame.ui.client/unmount!*`. The contract (03 §4): React effect cleanup
  fires FIRST during a real `.unmount` and emits `:disconnected {:reason
  :unknown}` (indistinguishable from an Activity hide at that moment); an
  explicit root teardown then retroactively proves those cells `:unmounted
  {:proof :host-teardown}` → `:dead`.

  `reactive/teardown-root!` models this: it arms a collection window around the
  host `.unmount` thunk, so every cell whose cleanup (`disconnect!`) fires
  DURING the unmount is captured, then upgraded via the shared `teardown!`
  primitive. An Activity hide (a `disconnect!` with the window UNARMED) is never
  captured — but a cell hidden BEFORE the window still belongs to its root, so
  root teardown reaps it through the root-incarnation ownership registry
  (rf2-vxgfnd.85), while a hide with NO teardown stays reconnectable. Both are
  the discriminators this file pins.

  `.cljc` ending `-cljs-test` runs on node (`test:cljs`) AND JVM
  (`clojure -M:test`), so the ownership/lifecycle logic is graft-checked on
  both hosts against the REAL observation port + sub-cache (plain-atom
  adapter). The end-to-end `client/unmount!*` wiring is pinned by the cljs-only
  `re-frame.ui.root-teardown-wiring-cljs-test`; the real-DOM proof by the
  browser `re-frame.ui.root-teardown-dom-cljs-test`."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (try (f) (finally (reactive/reset-scheduler!)))))

(defn- make-frame! [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- ref-count [fid q]
  (:ref-count (get @(:sub-cache (frame/frame fid)) q)))

(defn- render+commit! [cell fid queries]
  (rf/with-frame fid
    (reactive/with-capture cell (fn [] (mapv reactive/sub-read queries))))
  (reactive/commit! cell)
  cell)

(defn- mount!
  "Graft analogue of a real mount UNDER a root: mint a cell for view `vid`,
  attach it to root `incarnation` (the ownership that survives an Activity hide),
  then commit it connected under frame `fid` observing `queries`."
  [vid incarnation fid queries]
  (let [cell (reactive/make-cell vid)]
    (reactive/attach-root! cell incarnation)
    (render+commit! cell fid queries)))

(defn- throws-id [thunk]
  (try (thunk) nil
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs cljs.core/ExceptionInfo) e
         (:rf.error/id (ex-data e)))))

;; ===========================================================================
;; The core contract: a real root unmount proves :unmounted → :dead, and the
;; cleanup fact seen DURING the unmount is the same transient :unknown as a hide
;; ===========================================================================

(deftest root-teardown-collects-a-disconnect-and-proves-unmount
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        cell   (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])
        during (atom nil)]
    (testing "precondition — connected, observing the frame, one owner"
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count fid [:rt/a]))))
    ;; The thunk is the host `.unmount`: it fires the cell's effect cleanup
    ;; (`disconnect!`) synchronously, exactly as React does inside `.unmount`.
    (reactive/teardown-root!
     (fn []
       (reactive/disconnect! cell)
       (reset! during (peek (reactive/intervals cell)))))
    (testing "DURING the unmount the fact is the transient :unknown (03 §4)"
      (is (= {:state :disconnected :reason :unknown} @during)
          "cleanup emits the same immediate fact a real unmount and a hide share"))
    (testing "AFTER the unmount the interval is retroactively proven an unmount"
      (is (= :dead (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell)))
          "the prior disconnect is upgraded to :unmounted {:proof :host-teardown}"))
    (testing "the dead cell holds no leases and no pending notification"
      (is (empty? (reactive/committed-target-keys cell)))
      (is (false? (reactive/dirty? cell)))
      (is (nil? (ref-count fid [:rt/a])) "the lease was released, ref-count gone"))))

(deftest dead-cell-fails-loud-on-recommit-no-resume
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])]
    (reactive/teardown-root! (fn [] (reactive/disconnect! cell)))
    (is (= :dead (reactive/lifecycle cell)))
    (testing "a retained handle recommit fails through the dead-cell lifecycle,
              not by probing a torn-down context (03 §4; commit! step 2)"
      (rf/with-frame fid
        (reactive/with-capture cell (fn [] (reactive/sub-read [:rt/a]))))
      (is (= :rf.error/frame-destroyed (throws-id #(reactive/commit! cell)))))))

;; ===========================================================================
;; The Activity-hide transient path is untouched — a hide never goes :dead
;; ===========================================================================

(deftest activity-hide-then-reveal-stays-transient-never-dead
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])]
    (testing "a hide is a `disconnect!` with NO teardown window armed"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unknown}
             (peek (reactive/intervals cell)))
          "the immediate fact — not :unmounted, and the cell is NOT :dead"))
    (testing "a reveal reconnects and proves the prior interval an Activity hide"
      ;; a GENUINE hide→reveal: the disconnect outlived its commit (settle models
      ;; the host yield a real reveal spans; on CLJS a microtask does this). Only
      ;; a SETTLED disconnect proves a hide — an unsettled same-commit reconnect
      ;; is a StrictMode dev replay (rf2-vxgfnd.44).
      (reactive/settle-disconnect! cell)
      (render+commit! cell fid [[:rt/a]])
      (is (= :connected (reactive/lifecycle cell)) "reconnected, never :dead")
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "annotated :activity-hidden {:proof :reconnect} — never mislabeled unmounted"))))

;; ===========================================================================
;; rf2-vxgfnd.85 — root teardown reaps cells ALREADY Activity-hidden before the
;; window, via the root-incarnation ownership that survives the hide
;; ===========================================================================

(deftest root-teardown-reaps-a-cell-hidden-before-the-window
  ;; The corrected contract (replaces the #5755 test that blessed the miss). A
  ;; cell hidden by Activity BEFORE any teardown window left :connected, so its
  ;; cleanup can never enrol it in the window — and React does not re-run an
  ;; already-destroyed effect when it discards the hidden fiber at root unmount.
  ;; Root membership SURVIVES the hide via `root-cells`, so an explicit root
  ;; teardown reaps the already-hidden cell: it must NOT linger reconnectable
  ;; after its root is gone.
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        inc  (reactive/make-root-incarnation)
        cell (mount! ::v inc fid [[:rt/a]])]
    (testing "Activity hide — window unarmed — is the transient :unknown, still owned"
      (reactive/disconnect! cell)
      (is (= :disconnected (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unknown} (peek (reactive/intervals cell))))
      (is (identical? inc (reactive/cell-root cell)) "root membership SURVIVES the hide")
      (is (= 1 (reactive/root-cell-count inc)) "…the cell is still owned"))
    (testing "root teardown reaps the already-hidden cell → :dead {:proof :host-teardown}"
      (is (= 1 (reactive/teardown-root! inc (fn [] nil)))
          "the empty host unmount collects nothing, yet the owned hidden cell is reaped")
      (is (= :dead (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell)))
          "the prior :unknown disconnect is upgraded — never left reconnectable"))
    (testing "ownership is bounded — the incarnation is dropped on final teardown"
      (is (= 0 (reactive/root-cell-count inc)) "no membership lingers for the incarnation")
      (is (= 0 (reactive/root-cell-count)) "no historical incarnation retained"))
    (testing "the reaped cell fails a recommit through the dead-cell lifecycle"
      (rf/with-frame fid
        (reactive/with-capture cell (fn [] (reactive/sub-read [:rt/a]))))
      (is (= :rf.error/frame-destroyed (throws-id #(reactive/commit! cell)))
          "a retained handle cannot reconnect after its root is gone"))))

(deftest a-hide-without-root-teardown-stays-reconnectable
  ;; The discriminator: an Activity hide with NO root teardown must stay
  ;; reconnectable and be proven :activity-hidden on reveal — the ownership
  ;; registry must never itself kill a merely-hidden cell.
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        inc  (reactive/make-root-incarnation)
        cell (mount! ::v inc fid [[:rt/a]])]
    (reactive/disconnect! cell)
    (is (= :disconnected (reactive/lifecycle cell)) "hidden, not :dead")
    (is (= 1 (reactive/root-cell-count inc)) "still owned — reapable only by ITS root")
    (testing "a reveal reconnects and proves the interval an Activity hide"
      ;; GENUINE hide→reveal — settle models the host yield a real reveal spans;
      ;; an unsettled same-commit reconnect would be a StrictMode dev replay and
      ;; must NOT be proven a hide (rf2-vxgfnd.44).
      (reactive/settle-disconnect! cell)
      (render+commit! cell fid [[:rt/a]])
      (is (= :connected (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :activity-hidden :proof :reconnect}
             (peek (reactive/intervals cell)))
          "never mislabeled unmounted — the registry did not kill a live-again cell"))))

(deftest root-teardown-reaps-hidden-siblings-via-a-captured-cells-incarnation
  ;; The `[unmount-thunk]` arity (the current client call, no explicit
  ;; incarnation): a cell that disconnects DURING the window names its root's
  ;; incarnation, so a SIBLING under the same root that was hidden BEFORE the
  ;; window is reaped too — the common real unmount (some cells shown, some
  ;; already Activity-hidden).
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        inc    (reactive/make-root-incarnation)
        shown  (mount! ::shown inc fid [[:rt/a]])
        hidden (mount! ::hidden inc fid [[:rt/a]])]
    (reactive/disconnect! hidden)                      ;; hidden BEFORE the window
    (is (= :disconnected (reactive/lifecycle hidden)))
    (is (= :connected (reactive/lifecycle shown)))
    (is (= 2 (reactive/teardown-root! (fn [] (reactive/disconnect! shown))))
        "both reaped: the window-captured cell + its already-hidden sibling")
    (is (= :dead (reactive/lifecycle shown)) "the captured cell")
    (is (= :dead (reactive/lifecycle hidden))
        "the pre-hidden sibling is reaped via the captured cell's incarnation")
    (is (= 0 (reactive/root-cell-count inc)) "the incarnation is fully drained")))

(deftest root-teardown-does-not-reap-a-sibling-roots-hidden-cell
  ;; Incarnation-scoped: tearing down root A never reaps root B's hidden cell,
  ;; even though both are :disconnected at once (sibling-root isolation).
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        inc-a  (reactive/make-root-incarnation)
        inc-b  (reactive/make-root-incarnation)
        cell-a (mount! ::a inc-a fid [[:rt/a]])
        cell-b (mount! ::b inc-b fid [[:rt/a]])]
    (reactive/disconnect! cell-a)                      ;; both hidden at once
    (reactive/disconnect! cell-b)
    (is (= 1 (reactive/teardown-root! inc-a (fn [] nil))) "root A reaps only its own")
    (is (= :dead (reactive/lifecycle cell-a)) "root A's hidden cell dies")
    (is (= :disconnected (reactive/lifecycle cell-b))
        "root B's hidden cell is untouched — still reconnectable")
    (is (= 1 (reactive/root-cell-count inc-b)) "…and still owned by B")
    (testing "root B's hidden cell still reconnects cleanly"
      (render+commit! cell-b fid [[:rt/a]])
      (is (= :connected (reactive/lifecycle cell-b))))))

(deftest a-stale-incarnation-teardown-cannot-reap-a-replacement-roots-cell
  ;; Root incarnation is a per-mount identity, NOT the reusable root-id. A root
  ;; torn down and re-mounted under the SAME root-id gets a FRESH incarnation, so
  ;; replaying the STALE incarnation's teardown reaps nothing in the replacement
  ;; (rf2-vxgfnd.85 — incarnation-safety).
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid   (make-frame! :rt/frame {:a 1})
        inc-1 (reactive/make-root-incarnation)
        cell1 (mount! ::v inc-1 fid [[:rt/a]])]
    (reactive/disconnect! cell1)
    (reactive/teardown-root! inc-1 (fn [] nil))
    (is (= :dead (reactive/lifecycle cell1)) "the first incarnation is torn down")
    (let [inc-2 (reactive/make-root-incarnation)
          cell2 (mount! ::v inc-2 fid [[:rt/a]])]     ;; replacement, same view-id
      (reactive/disconnect! cell2)                     ;; hidden
      (is (not (identical? inc-1 inc-2)) "a fresh incarnation despite the same root-id")
      (is (= 0 (reactive/teardown-root! inc-1 (fn [] nil)))
          "replaying the stale incarnation reaps nothing")
      (is (= :disconnected (reactive/lifecycle cell2))
          "the replacement root's hidden cell is untouched")
      (is (= 1 (reactive/root-cell-count inc-2))))))

;; ===========================================================================
;; Root isolation — a teardown only claims the cells its own unmount disconnects
;; ===========================================================================

(deftest root-teardown-isolates-sibling-roots
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        cell-a (render+commit! (reactive/make-cell ::a) fid [[:rt/a]])
        cell-b (render+commit! (reactive/make-cell ::b) fid [[:rt/a]])]
    (is (= :connected (reactive/lifecycle cell-a)))
    (is (= :connected (reactive/lifecycle cell-b)))
    ;; Root A's `.unmount` sweeps only root A's tree — only cell-a disconnects.
    (reactive/teardown-root! (fn [] (reactive/disconnect! cell-a)))
    (testing "only the unmounted root's cells die"
      (is (= :dead (reactive/lifecycle cell-a)) "root A's cell")
      (is (= :connected (reactive/lifecycle cell-b))
          "a sibling root's cell is neither annotated nor killed")
      (is (= 1 (ref-count fid [:rt/a]))
          "the sibling's lease is neither released nor churned"))
    (testing "the sibling cell still reads live"
      (is (= 1 (rf/with-frame fid
                 (reactive/with-capture cell-b
                   (fn [] (reactive/sub-read [:rt/a])))))))))

;; ===========================================================================
;; A throwing host `.unmount` — nothing torn down, original error preserved
;; ===========================================================================

(deftest throwing-host-unmount-tears-down-nothing-and-rethrows
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid  (make-frame! :rt/frame {:a 1})
        cell (render+commit! (reactive/make-cell ::v) fid [[:rt/a]])
        boom (ex-info "host .unmount refused" {:host :react})]
    (testing "the host refused to unmount — React did NOT tear the tree down"
      (is (identical? boom
                      (try (reactive/teardown-root! (fn [] (throw boom)))
                           nil
                           (catch #?(:clj Throwable :cljs :default) e e)))
          "the original host error propagates, never masked"))
    (testing "the orphaned tree's cell stays live — never falsely killed"
      (is (= :connected (reactive/lifecycle cell)))
      (is (= 1 (ref-count fid [:rt/a]))))))

;; ===========================================================================
;; Re-entrancy — a nested teardown does not corrupt the outer window
;; ===========================================================================

(deftest teardown-window-is-reentrancy-safe
  (rf/reg-sub :rt/a (fn [db _] (:a db)))
  (let [fid    (make-frame! :rt/frame {:a 1})
        outer  (render+commit! (reactive/make-cell ::outer) fid [[:rt/a]])
        inner  (render+commit! (reactive/make-cell ::inner) fid [[:rt/a]])]
    (reactive/teardown-root!
     (fn []
       ;; a nested teardown claims ONLY the inner cell it disconnects…
       (reactive/teardown-root! (fn [] (reactive/disconnect! inner)))
       (is (= :dead (reactive/lifecycle inner)) "inner torn down by the nested window")
       ;; …then the outer window is restored and claims the outer cell.
       (reactive/disconnect! outer)))
    (is (= :dead (reactive/lifecycle outer))
        "the outer window survived the nested one and proved its own cell")))
