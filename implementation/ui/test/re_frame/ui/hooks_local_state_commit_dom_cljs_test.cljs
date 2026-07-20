(ns re-frame.ui.hooks-local-state-commit-dom-cljs-test
  "rf2-bvqu0 — the MOUNTED proof that `:local-state` is attributed ONLY after a
  REAL React commit, driven through the ACTUAL `re-frame.ui/local` setter on a real
  React root (jsdom/browser), not a private comparison helper.

  The hooks `local-state` updater is PURE; attribution rides a DEBUG committed-value
  `useLayoutEffect`. Because a layout effect runs ONLY in the commit phase (and only
  when the committed value changed), a REAL change contributes exactly one
  :local-state cause to that connected commit, while a same-batch net-zero
  `0->1->0` — which React bails WITHOUT committing — never runs the effect and so
  leaves NO stale marker to contaminate a later unrelated (subscription) commit. A
  StrictMode replay is idempotent by construction: the updater is pure and the
  attribution is a boolean commit-time flag, so a doubled effect run cannot
  duplicate or fabricate a cause.

  The node companion (`hooks_local_state_cause_cljs_test`) pins the reactive-side
  flag contract. `(browser?)`-gated so the node build runs it trivially; the real
  assertions run under `npm run test:browser`."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview local]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn reactive/reset-scheduler!}))

;; ---------------------------------------------------------------------------
;; Harness helpers (mirroring local_effect_dispatch_fn_dom_cljs_test)
;; ---------------------------------------------------------------------------

(defn- host-turn! []
  (js/Promise. (fn [resolve _] (js/setTimeout #(resolve nil) 0))))

(defn- click! [el] (.dispatchEvent el (js/MouseEvent. "click" #js {:bubbles true})))

(defn- make-frame [id db]
  (rf/make-frame {:id id :initial-events [[:rf/set-db db]]}))

;; The mounted view records its owning ViewCell so the test can read that cell's
;; per-commit :rf.view/causes after each host turn.
(defonce ^:private probe-cell (atom nil))

(defview cause-probe []
  (let [[n set-n] (local 0)
        a         (ui/sub [::a])
        _         (reset! probe-cell (reactive/ambient-cell))]
    [:div
     [:span {:data-role "n"} (str n)]
     [:span {:data-role "a"} (str a)]
     ;; a REAL committed change: 0 -> 1
     [:button {:data-role "real"
               :on-click (ui/event [_] (set-n (inc n)) nil)} "r"]
     ;; a NET-ZERO batch in one turn: 0 -> 1 -> 0 (React bails, commits nothing)
     [:button {:data-role "netzero"
               :on-click (ui/event [_] (set-n 1) (set-n 0) nil)} "z"]]))

(defn- causes-of []
  (mapv :cause (:rf.view/causes (reactive/commit-record @probe-cell))))

;; ===========================================================================
;; A REAL committed local write -> the connected commit is exactly :local-state
;; ===========================================================================

(deftest a-real-committed-local-write-is-attributed-local-state
  (if-not (browser?)
    (is true ":node — browser gate runs the committed-local-write attribution")
    (do
      (rf/reg-sub ::a (fn [db _] (:a db)))
      (reset! probe-cell nil)
      (let [f (make-frame ::real {:a 1})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [cause-probe]]]
                (-> (host-turn!)
                    (.then
                     (fn []
                       (is (= [:mount] (causes-of)) "the first connected commit is :mount")
                       (uit/flush! #(do (click! (uit/query root "[data-role='real']"))
                                        (host-turn!)))))
                    (.then
                     (fn []
                       (is (= "1" (.-textContent (uit/query root "[data-role='n']")))
                           "the real local change committed to the DOM")
                       (is (= [:local-state] (causes-of))
                           "a committed host-only write is attributed exactly :local-state")))))
              (.then (fn [] (rf/destroy-frame! f) (done))
                     (fn [e] (rf/destroy-frame! f)
                       (is false (str "real committed local write: " e)) (done)))))))))

;; ===========================================================================
;; A net-zero batch commits nothing -> no stale :local-state on a later commit
;; ===========================================================================

(deftest a-net-zero-local-batch-leaves-no-local-state-on-a-later-commit
  (if-not (browser?)
    (is true ":node — browser gate runs the net-zero contamination proof")
    (do
      (rf/reg-sub ::a (fn [db _] (:a db)))
      (rf/reg-event ::set-a (fn [{:keys [db]} [_ v]] {:db (assoc db :a v)}))
      (reset! probe-cell nil)
      (let [f (make-frame ::nz {:a 1})]
        (async done
          (-> (uit/with-root [root [ui/frame-provider {:frame f} [cause-probe]]]
                (-> (host-turn!)
                    (.then
                     (fn []
                       ;; 0 -> 1 -> 0 in ONE turn: React bails, commits nothing.
                       (uit/flush! #(do (click! (uit/query root "[data-role='netzero']"))
                                        (host-turn!)))))
                    (.then
                     (fn []
                       (is (= "0" (.-textContent (uit/query root "[data-role='n']")))
                           "the net-zero batch committed nothing (DOM value unchanged)")
                       ;; drive an UNRELATED subscription movement -> the next commit
                       (uit/flush! #(uit/dispatch! f [::set-a 2]))))
                    (.then (fn [] (host-turn!)))
                    (.then
                     (fn []
                       (is (= "2" (.-textContent (uit/query root "[data-role='a']")))
                           "the subscription actually moved (an unrelated commit ran)")
                       (is (= [:subscription] (causes-of))
                           "the bailed net-zero batch left NO stale :local-state marker")))))
              (.then (fn [] (rf/destroy-frame! f) (done))
                     (fn [e] (rf/destroy-frame! f)
                       (is false (str "net-zero contamination: " e)) (done)))))))))
