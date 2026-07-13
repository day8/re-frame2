(ns re-frame.ui.tool-evidence-dom-cljs-test
  "rf2-vxgfnd.75 — mounted proof for the tool-tier invalidation-evidence
  projection (`re-frame.ui.tool.evidence`).

  A REAL first-party ViewCell under a live client root: a dispatched event
  moves the sub, the observation port's `on-change` fires, the ViewCell
  flush delivers the coalesced bounded evidence into the installed
  projection — never a direct sink call (the AC's integration axis). The
  projection row carries the developer-facing identity (view id + the LIVE
  root's root-id) and the bounded accumulator; tearing the root down removes
  the cell from the projection, and uninstalling releases everything."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            [re-frame.ui.tool.evidence :as evidence]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn (fn []
               (reactive/reset-scheduler!)
               (evidence/force-release!))}))

(defview evidence-probe []
  [:output {:data-role "evidence-probe"}
   (str (ui/sub [::value]))])

(defn- probe-row []
  (some #(when (= ::evidence-probe (:view-id %)) %) (evidence/projection)))

(deftest a-real-invalidation-flows-through-the-flush-into-the-projection
  (when (browser?)
    (rf/reg-sub ::value (fn [db _] (:value db)))
    (rf/reg-event ::set-value
                  (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
    (let [f (uit/frame {:app-db {:value 0}})]
      (is (true? (evidence/install! ::xray-panel)))
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [evidence-probe]]]
              (testing "mount alone projects nothing — no invalidation yet"
                (is (= "0" (.-textContent
                            (uit/query root "[data-role='evidence-probe']"))))
                (is (nil? (probe-row))))
              (-> (uit/flush! #(uit/dispatch! f [::set-value 1]))
                  (.then
                   (fn []
                     (testing "the port movement rendered…"
                       (is (= "1" (.-textContent
                                   (uit/query root
                                              "[data-role='evidence-probe']")))))
                     (testing "…and the flush projected the bounded evidence
                               with developer-facing identity (AC1/AC2/AC6)"
                       (let [{:keys [cell-id view-id root-id evidence]}
                             (probe-row)]
                         (is (some? cell-id) "a stable projection ordinal")
                         (is (= ::evidence-probe view-id))
                         (is (keyword? root-id) "the LIVE root's root-id")
                         (is (= "rf.ui.test.root" (namespace root-id))
                             "…the with-root-minted client root")
                         (is (pos? (:count evidence)))
                         (is (= 1 (:batches evidence)) "one coalesced flush")
                         (is (contains? (:causes evidence) :value)
                             "the port's real :value cause")
                         (is (some #(= [::value] (nth % 2)) (:targets evidence))
                             "the moving sub is a shown target")
                         (is (some? (:latest-epoch evidence))
                             "epoch attribution rode the port payload")
                         (is (= #{} (:dropped evidence))
                             "one target — the honest loss field is empty")
                         (is (true? (:dropped-exact? evidence)))))
                     (testing "the scheduler stayed authoritative — no escape"
                       (is (nil? (reactive/last-evidence-sink-escape))))))))
            (.then
             (fn []
               (testing "root teardown removed the cell from the projection (AC4)"
                 (is (nil? (probe-row)))
                 (is (= [] (evidence/projection))))
               (testing "uninstall releases the registration"
                 (is (true? (evidence/uninstall! ::xray-panel)))
                 (is (nil? (evidence/installed-owner))))
               (rf/destroy-frame! f)
               (done)))
            (.catch
             (fn [e]
               (evidence/force-release!)
               (rf/destroy-frame! f)
               (is false (str "unexpected rejection: " e))
               (done))))))))
