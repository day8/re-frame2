(ns re-frame.ui.root-teardown-wiring-cljs-test
  "rf2-vxgfnd.62 — the `client/unmount!*` → `reactive/teardown-root!` WIRING.

  `reactive-root-teardown-cljs-test` graft-checks the teardown-window logic on
  both hosts; this file pins the client-kernel wiring on node WITHOUT real
  React: a fake `Root` whose host `react-root.unmount` fires a ViewCell's
  effect cleanup (`disconnect!`) exactly as React does synchronously inside
  `.unmount`. `unmount!*` must drive that through the teardown window, so the
  cell ends `:unmounted` → `:dead` (not stuck `:unknown`), while still
  releasing the live-root claim in its `finally` (contract §7 / AC4).

  No DOM — the react-root is a plain JS object with an `unmount` method;
  container ownership is identity-based. The real-React counterpart is the
  browser `re-frame.ui.root-teardown-dom-cljs-test`."
  (:require [cljs.test :refer [deftest is testing use-fixtures async]]
            [re-frame.core                 :as rf]
            [re-frame.frame                :as frame]
            [re-frame.live-frame           :as live-frame]
            [re-frame.test-support         :as test-support]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.ui.client            :as client]
            [re-frame.ui.reactive          :as reactive]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter})
  (fn [f]
    (reactive/reset-scheduler!)
    (client/reset-live-roots!)
    (try (f) (finally (reactive/reset-scheduler!) (client/reset-live-roots!)))))

(defn- connected-cell! [fid queries]
  (let [cell (reactive/make-cell ::v)]
    (let [[_ capture] (rf/with-frame fid
                        (reactive/with-capture
                         cell (fn [] (mapv (fn [i q]
                                            (reactive/sub-read [:wiring/site i] q))
                                          (range) queries))))]
      (reactive/commit! cell capture))
    cell))

(defn- register!
  "Register a fake Root whose host `.unmount` runs `on-unmount` — the seam
  React's synchronous effect-cleanup sweep fires through."
  [root-id on-unmount]
  (let [react-root #js {:unmount (fn [] (when on-unmount (on-unmount)))}
        container  (js-obj)
        root       (client/->Root react-root container root-id)]
    (client/register-live-root! {:root-id root-id :provenance :authored}
                                container root)
    root))

(defn- thrown-id [f]
  (try (f) nil (catch cljs.core/ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- captured-error [f]
  (try (f) nil (catch cljs.core/ExceptionInfo e e)))

(deftest unmount-drives-the-cell-to-dead-and-releases-the-claim
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell (connected-cell! :rtw/frame [[:rtw/a]])
        ;; the host `.unmount` fires the cell's cleanup, as React does
        root (register! :rtw/root #(reactive/disconnect! cell))
        inc  (:root-incarnation (client/live-root-entry :rtw/root))]
    ;; Attach the cell to the REAL root incarnation, exactly as the production
    ;; lifecycle effect does (rf2-vxgfnd.251): the explicit-incarnation teardown
    ;; reaps ONLY cells that carry positive ownership of the named root, so this
    ;; wiring fixture must own its cell rather than lean on the retired nil-root
    ;; exception.
    (reactive/attach-root! cell inc)
    (is (= :connected (reactive/lifecycle cell)) "precondition: mounted + connected")
    (is (= #{:rtw/root} (client/live-root-ids)) "precondition: root is live")
    (is (nil? (client/unmount!* root)) "unmount!* returns nil")
    (testing "the root's cell is proven :unmounted → :dead through the wiring"
      (is (= :dead (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell)))))
    (testing "the live-root claim is released (contract §7)"
      (is (= #{} (client/live-root-ids))))))

(deftest a-throwing-host-unmount-quarantines-the-claim-and-rethrows
  ;; rf2-vxgfnd.275 — the host-throws settlement fixture. React can consume its
  ;; Root handle before a synchronous unmount flush throws; whether the container
  ;; is actually free is then UNKNOWABLE in-process. FAIL CLOSED: the exact
  ;; id/container/prefix claim is QUARANTINED `:tearing-down` (NOT released — the
  ;; pre-.275 behaviour), so a reused id or container fails loud instead of racing
  ;; React self-completing the aborted teardown. The primary host error still
  ;; propagates and the exact generation is force-dead.
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell (connected-cell! :rtw/frame [[:rtw/a]])
        boom (ex-info "React refused synchronous unmount" {:host :react})
        root (register! :rtw/root (fn [] (throw boom)))
        inc  (:root-incarnation (client/live-root-entry :rtw/root))]
    (reactive/attach-root! cell inc)
    (is (identical? boom
                    (try (client/unmount!* root) nil
                         (catch :default e e)))
        "the original host error propagates, never masked")
    (testing "the claim is QUARANTINED despite the throw — fail closed (rf2-vxgfnd.275)"
      (is (contains? (client/live-root-ids) :rtw/root)
          "the id/container claim is NOT released — the container is not proven free")
      (is (= :rf.error/duplicate-root-id
             (thrown-id #(client/check-root-claim!
                          'test {:root-id :rtw/root :provenance :authored}
                          (:container (client/live-root-entry :rtw/root)))))
          "a reused root-id fails loud — the honest recovery is a fresh container")
      (is (nil? (client/unmount!* root)) "a second unmount! is a no-op (tearing-down guard)"))
    (testing "the consumed root generation retains no framework owner"
      (is (= :dead (reactive/lifecycle cell)))
      (is (zero? (reactive/root-cell-count inc))))))

(deftest tearing-down-root-diagnostics-carry-complete-ownership-evidence
  ;; rf2-vxgfnd.277/.291: the three existing root diagnostics keep their IDs,
  ;; but the transitional ownership state must be machine-readable on every
  ;; admission/render arm. This pins the MERELY-DEFERRED `:tearing-down` arm — a
  ;; committed reporter plus a normally-returning `.unmount` whose teardown React
  ;; defers (rf2-vxgfnd.275), so `on-settled` is held and the claim stays a stable
  ;; deferred `:tearing-down` WITHOUT cleanup-failure. (A throwing `.unmount` is now
  ;; the DISTINCT cleanup-failure quarantine — rf2-sddbc — whose reuse diagnostics
  ;; are the consumed-container family, pinned in root-registry-cljs-test.)
  ;; This is the IMPLEMENTATION-side tooth of the Root lifecycle drift gate
  ;; (rf2-vizyct): it pins the runtime `:existing {… :tearing-down? true}`
  ;; ex-data, paired with the CONTRACT-side spec-009 row anchors in
  ;; scripts/check_ui_root_lifecycle_drift.py — strip the evidence from either
  ;; and a gate turns red.
  (let [container     (js-obj)
        site          {:file "teardown.cljs" :line 11 :column 7}
        arriving-site {:file "retry.cljs" :line 22 :column 3}
        incarnation   (reactive/make-root-incarnation)
        react-root    #js {:unmount (fn [] nil)}
        root          (client/->Root react-root container :rtw/diagnostic)]
    (client/register-live-root!
     {:root-id :rtw/diagnostic :provenance :authored :site site}
     container root incarnation)
    ;; commit the reporter so the normally-returning `.unmount` is classified a
    ;; DEFERRED host teardown: the claim holds `:tearing-down` (no cleanup-failure).
    (reactive/report-root-commit! incarnation)
    (is (nil? (client/unmount!* root)))
    (is (true? (:tearing-down?
                (client/live-root-entry :rtw/diagnostic))))
    (is (not (:cleanup-failure?
              (client/live-root-entry :rtw/diagnostic)))
        "a deferred teardown is NOT cleanup-failure — the deferred diagnostics apply")
    (let [duplicate
          (captured-error
           #(client/check-root-claim!
             'diagnostic/duplicate
             {:root-id :rtw/diagnostic
              :provenance :derived
              :site arriving-site}
             container))
          container-use
          (captured-error
           #(client/check-root-claim!
             'diagnostic/container
             {:root-id :rtw/other
              :provenance :authored
              :site arriving-site}
             container))
          not-live
          (captured-error
           #(client/require-live-root! root 'diagnostic/render))]
      (testing ":rf.error/duplicate-root-id exposes both owners + transition"
        (is (= #{:rf.error/id :where :recovery :reason :root-id
                 :existing :arriving}
               (set (keys (ex-data duplicate)))))
        (is (= {:rf.error/id :rf.error/duplicate-root-id
                :where 'diagnostic/duplicate
                :recovery :make-root-ids-unique
                :root-id :rtw/diagnostic
                :existing {:provenance :authored
                           :site site
                           :tearing-down? true}
                :arriving {:provenance :derived :site arriving-site}}
               (dissoc (ex-data duplicate) :reason)))
        (is (re-find #"tearing down" (ex-message duplicate))))
      (testing ":rf.error/root-container-in-use identifies fenced owner state"
        (is (= #{:rf.error/id :where :recovery :reason :root-id
                 :owner-root-id :existing}
               (set (keys (ex-data container-use)))))
        (is (= {:rf.error/id :rf.error/root-container-in-use
                :where 'diagnostic/container
                :recovery :unmount-the-owning-root-first
                :root-id :rtw/other
                :owner-root-id :rtw/diagnostic
                :existing {:tearing-down? true}}
               (dissoc (ex-data container-use) :reason)))
        (is (re-find #"teardown is in flight" (ex-message container-use))))
      (testing ":rf.error/root-not-live distinguishes an unmounting handle"
        (is (= #{:rf.error/id :where :recovery :reason :root-id :existing}
               (set (keys (ex-data not-live)))))
        (is (= {:rf.error/id :rf.error/root-not-live
                :where 'diagnostic/render
                :recovery :recreate-the-root
                :root-id :rtw/diagnostic
                :existing {:tearing-down? true}}
               (dissoc (ex-data not-live) :reason)))
        (is (re-find #"root is tearing down" (ex-message not-live)))))))

(deftest unmount-on-a-stale-root-is-a-noop
  ;; A superseded/already-torn-down handle must not run the teardown window at
  ;; all — the membership guard short-circuits before `.unmount`.
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell    (connected-cell! :rtw/frame [[:rtw/a]])
        fired?  (atom false)
        ;; a Root that is NOT registered in live-roots (stale handle)
        stale   (client/->Root #js {:unmount (fn [] (reset! fired? true)
                                               (reactive/disconnect! cell))}
                               (js-obj) :rtw/gone)]
    (is (nil? (client/unmount!* stale)) "stale unmount is an idempotent no-op")
    (is (false? @fired?) "the host `.unmount` was never called")
    (is (= :connected (reactive/lifecycle cell)) "the cell is untouched")))

;; ===========================================================================
;; rf2-vxgfnd.182 — a DEFERRED host teardown fences the root-id/container claim
;;
;; react-dom 19.2 refuses a SYNCHRONOUS unmount from inside render/commit: it
;; consumes the handle, clears its container marker, and RETURNS NORMALLY while
;; the teardown work stays scheduled for a later microtask. The ONLY in-process
;; signal is at `reactive/teardown-root!`'s cleanup window: a deferred `.unmount`
;; ran NONE of the root's effect cleanups, so the cell is still `:connected`
;; after the thunk. Here the fake host `.unmount` models exactly that — it
;; returns without firing the cell's cleanup. `unmount!*` must therefore HOLD the
;; claim `:tearing-down` (rejecting a reentrant mount/create-root) until the
;; settlement boundary reaps the cell and frees the claim. These fixtures pin the
;; SYNCHRONOUS fence; the microtask-settlement completion is graft-checked
;; synchronously on the JVM (`reactive-root-teardown-cljs-test`) and end-to-end
;; under real react-dom in `root-mount-dom-cljs-test`.
;; ===========================================================================

(deftest deferred-host-teardown-fences-the-claim-not-released-immediately
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell (connected-cell! :rtw/frame [[:rtw/a]])
        ;; the host `.unmount` DEFERS: it returns normally without firing the
        ;; cell's effect cleanup (react-dom 19.2's in-render unmount refusal)
        root (register! :rtw/root (fn [] nil))
        inc  (:root-incarnation (client/live-root-entry :rtw/root))]
    (reactive/attach-root! cell inc)
    ;; a rendered root has a COMMITTED reporter whose host teardown teardown-root!
    ;; must await — the ROOT-LEVEL deferral signal (rf2-vxgfnd.275)
    (reactive/report-root-commit! inc)
    (is (= :connected (reactive/lifecycle cell)) "precondition: mounted + connected")
    (is (nil? (client/unmount!* root)) "unmount!* returns nil (host .unmount did not throw)")
    (testing "the deferred teardown is FENCED — the claim is held :tearing-down"
      (is (contains? (client/live-root-ids) :rtw/root)
          "the root-id/container claim is NOT released while teardown is deferred")
      (is (= :rf.error/duplicate-root-id
             (thrown-id #(client/check-root-claim!
                          'test {:root-id :rtw/root :provenance :authored}
                          (:container (client/live-root-entry :rtw/root)))))
          "a reentrant mount on the exact root-id is rejected before any React root")
      (is (not= :dead (reactive/lifecycle cell))
          "the cell is not yet reaped — React's deferred teardown has not settled"))))

(deftest deferred-teardown-rejects-a-reentrant-different-id-same-container
  ;; The container arm: while root A's teardown is deferred, a DIFFERENT root-id
  ;; targeting A's exact container is rejected `:root-container-in-use`.
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell (connected-cell! :rtw/frame [[:rtw/a]])
        root (register! :rtw/root (fn [] nil))
        inc  (:root-incarnation (client/live-root-entry :rtw/root))
        c    (:container (client/live-root-entry :rtw/root))]
    (reactive/attach-root! cell inc)
    (reactive/report-root-commit! inc)     ;; rendered root: committed reporter to await
    (is (nil? (client/unmount!* root)))
    (is (= :rf.error/root-container-in-use
           (thrown-id #(client/check-root-claim!
                        'test {:root-id :rtw/other :provenance :authored} c)))
        "a different root-id targeting the fenced container is rejected")))

(deftest double-unmount-during-deferred-teardown-is-a-noop
  ;; A second `unmount!*` while the first teardown is still deferred must NOT
  ;; re-drive the (consumed) host handle — the :tearing-down guard makes it a
  ;; no-op, extending idempotency across the deferral window.
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell   (connected-cell! :rtw/frame [[:rtw/a]])
        calls  (atom 0)
        root   (register! :rtw/root (fn [] (swap! calls inc)))
        inc    (:root-incarnation (client/live-root-entry :rtw/root))]
    (reactive/attach-root! cell inc)
    (reactive/report-root-commit! inc)     ;; rendered root: committed reporter to await
    (is (nil? (client/unmount!* root)))
    (is (= 1 @calls) "first unmount drove the host handle once")
    (is (nil? (client/unmount!* root)) "a second unmount during deferral is a no-op")
    (is (= 1 @calls) "the consumed host handle was not re-driven")))

;; ===========================================================================
;; rf2-vxgfnd.275 — a DEFERRED teardown of a CELL-LESS root still fences
;;
;; A compiled static/cell-less root owns NO ViewCell. Pre-fix, `unmount!*` drove
;; teardown-root! whose only deferral signal was residual cell-connectivity, so a
;; cell-less root — nothing to observe — was always classified SYNCHRONOUS and the
;; claim released immediately, even when react-dom deferred the actual teardown.
;; The root-level settlement law fences it: the fake host `.unmount` here DEFERS
;; (returns without firing the reporter sentinel), and no cell exists, so the
;; claim must be held `:tearing-down` past the window.
;; ===========================================================================

(deftest deferred-cell-less-teardown-fences-the-claim-not-released-immediately
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  ;; NO cell is attached to this root — it is cell-less. But a RENDERED cell-less
  ;; root DID commit a reporter (report-root-commit!), so its host teardown is
  ;; awaited. The fake host `.unmount` DEFERS: it returns normally, firing no
  ;; cleanup and no reporter sentinel.
  (let [root (register! :rtw/root (fn [] nil))
        inc  (:root-incarnation (client/live-root-entry :rtw/root))
        c    (:container (client/live-root-entry :rtw/root))]
    (reactive/report-root-commit! inc)     ;; the rendered cell-less root's reporter
    (is (zero? (reactive/root-cell-count inc))
        "precondition: a cell-less root — no ViewCell to observe")
    (is (nil? (client/unmount!* root)) "unmount!* returns nil (host .unmount did not throw)")
    (testing "the cell-less deferral is FENCED — the claim is held :tearing-down"
      (is (contains? (client/live-root-ids) :rtw/root)
          "the claim is NOT released immediately though the root owns no cell")
      (is (= :rf.error/duplicate-root-id
             (thrown-id #(client/check-root-claim!
                          'test {:root-id :rtw/root :provenance :authored} c)))
          "a reentrant mount on the exact root-id is rejected while teardown is deferred")
      (is (= :rf.error/root-container-in-use
             (thrown-id #(client/check-root-claim!
                          'test {:root-id :rtw/other :provenance :authored} c)))
          "a different root-id targeting the fenced container is rejected too"))))
