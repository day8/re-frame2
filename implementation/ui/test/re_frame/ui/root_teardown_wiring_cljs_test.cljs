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
  (:require [cljs.test :refer [deftest is testing use-fixtures]]
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

(deftest unmount-drives-the-cell-to-dead-and-releases-the-claim
  (rf/reg-sub :rtw/a (fn [db _] (:a db)))
  (live-frame/make-frame {:id :rtw/frame})
  (frame/replace-app-db! :rtw/frame {:a 1})
  (let [cell (connected-cell! :rtw/frame [[:rtw/a]])
        ;; the host `.unmount` fires the cell's cleanup, as React does
        root (register! :rtw/root #(reactive/disconnect! cell))]
    (is (= :connected (reactive/lifecycle cell)) "precondition: mounted + connected")
    (is (= #{:rtw/root} (client/live-root-ids)) "precondition: root is live")
    (is (nil? (client/unmount!* root)) "unmount!* returns nil")
    (testing "the root's cell is proven :unmounted → :dead through the wiring"
      (is (= :dead (reactive/lifecycle cell)))
      (is (= {:state :disconnected :reason :unmounted :proof :host-teardown}
             (peek (reactive/intervals cell)))))
    (testing "the live-root claim is released (contract §7)"
      (is (= #{} (client/live-root-ids))))))

(deftest a-throwing-host-unmount-still-releases-the-claim-and-rethrows
  ;; React can consume its Root handle before a synchronous unmount flush
  ;; throws. The public claim is released and the exact incarnation's framework
  ;; ownership is force-dead before the original host error propagates.
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
    (testing "the claim is released despite the throw (AC4)"
      (is (= #{} (client/live-root-ids))))
    (testing "the consumed root generation retains no framework owner"
      (is (= :dead (reactive/lifecycle cell)))
      (is (zero? (reactive/root-cell-count inc))))))

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
