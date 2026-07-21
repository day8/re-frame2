(ns re-frame.ui.conditional-sub-dom-cljs-test
  "rf2-vxgfnd.101 — mounted proof for a compiled conditional `ui/sub` site.

  One real first-party ViewCell moves OFF -> ON -> OFF -> ON under StrictMode.
  The middle transition drains value/disable/value writes to completion before
  exactly one read/render commit.  The disappearing lexical site releases its
  exact handle, ignores already-queued dirty evidence, performs no work while
  absent, then reacquires at the same sid with a fresh handle and the latest
  value."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.substrate.observation :as obs]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.client :as client]
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

(defonce ^:private conditional-computes (atom 0))

(defn- empty-evidence []
  {:bodies 0
   :enabled-reads 0
   :conditional-reads 0
   :root-commits 0})

(defview conditional-value [{:keys [evidence]}]
  (let [_        (swap! evidence update :bodies inc)
        enabled? (ui/sub [::enabled?])
        _        (swap! evidence update :enabled-reads inc)
        value    (when enabled?
                   ;; Deliberately rebuild an rf=-equal query on every render.
                   ;; The compiler supplies one stable lexical sid; the
                   ;; reconciler must preserve its exact committed query/handle.
                   (let [v (ui/sub (mapv identity
                                         [::conditional-value :slot]))]
                     (swap! evidence update :conditional-reads inc)
                     v))]
    [:output {:data-role "conditional-value"}
     (if enabled? (str value) "off")]))

(defn profiled-strict-root [^js props]
  (let [evidence (unchecked-get props "evidence")]
    (React/createElement
     (.-Profiler React)
     #js {:id "conditional-sub-root"
          :onRender (fn [& _]
                      (swap! evidence update :root-commits inc))}
     (React/createElement
      (.-StrictMode React) nil
      (React/createElement conditional-value #js {:evidence evidence})))))

(defn- site-for-query-head
  "Returns `[cell sid record]` from the canonical lexical-site seam only."
  [query-head]
  (some (fn [cell]
          (some (fn [[sid {:keys [query] :as record}]]
                  (when (= query-head (first query))
                    [cell sid record]))
                (reactive/committed-sites cell)))
        (reactive/current-live-cells)))

(defn- cache-entry [frame-id query]
  (get @(:sub-cache (frame/frame frame-id)) query))

(defn- reject-unexpectedly! [done f label e]
  (rf/destroy-frame! f)
  (is false (str label ": " e))
  (done))

(deftest compiled-conditional-sub-releases-and-reacquires-exact-site
  (when (browser?)
    (reset! conditional-computes 0)
    (rf/reg-sub ::enabled? (fn [db _] (:enabled? db)))
    (rf/reg-sub ::conditional-value
                (fn [db _]
                  (swap! conditional-computes inc)
                  (:value db)))
    (rf/reg-event ::set-enabled
                  (fn [{:keys [db]} [_ enabled?]]
                    {:db (assoc db :enabled? enabled?)}))
    (rf/reg-event ::set-value
                  (fn [{:keys [db]} [_ value]]
                    {:db (assoc db :value value)}))
    (rf/reg-event ::value-disable-value
                  (fn [{:keys [db]} [_ first-value final-value]]
                    {:db (assoc db :value first-value)
                     :fx [[:dispatch [::set-enabled false]]
                          [:dispatch [::set-value final-value]]]}))
    (let [f              (rf/make-frame {:initial-events [[:rf/set-db {:enabled? false :value 0}]]})
          frame-id       (frame/frame-target->id f)
          evidence       (atom (empty-evidence))
          baseline       {:roots (client/live-root-ids)
                          :root-cells (reactive/root-cell-count)
                          :live-cells (count (reactive/current-live-cells))
                          :pending-cells (reactive/pending-cell-count)}
          teardown-proof (atom nil)]
      (async done
        (->
         (uit/with-root [root [ui/frame-provider {:frame f}
                               [profiled-strict-root {:evidence evidence}]]]
           (testing "OFF owns only the unconditional lexical site"
             (is (= "off" (.-textContent
                             (.querySelector root
                                        "[data-role='conditional-value']"))))
             (is (nil? (site-for-query-head ::conditional-value)))
             (is (zero? @conditional-computes))
             (let [[cell _ enabled-record]
                   (site-for-query-head ::enabled?)]
               (is (some? cell))
               (is (= 1 (count (reactive/committed-sites cell))))
               (is (= 1 (:ref-count
                         (cache-entry frame-id (:query enabled-record)))))))
           (reset! evidence (empty-evidence))
           (->
            (uit/flush! #(rf/dispatch-sync [::set-enabled true] {:frame f}))
            (.then
             (fn []
               (testing "ON acquires one real owner at the compiled sid"
                 (is (= "0" (.-textContent
                              (.querySelector root
                                         "[data-role='conditional-value']"))))
                 (let [[cell sid record]
                       (site-for-query-head ::conditional-value)
                       query    (:query record)
                       handle    (:handle record)
                       target   (:target record)
                       reaction (:reaction (cache-entry frame-id query))]
                   (is (some? cell))
                   (is (some? sid))
                   (is (= [::conditional-value :slot] query))
                   (is (obs/owned? handle))
                   (is (obs/current? handle target))
                   (is (= 1 (:ref-count (cache-entry frame-id query))))
                   (is (= 1 (obs/active-owner-count reaction)))
                   (reset! evidence (empty-evidence))
                   (let [epoch-before    (frame/frame-commit-epoch frame-id)
                         revision-before (reactive/revision cell)
                         p               (uit/flush!
                                          #(rf/dispatch-sync
                                            [::value-disable-value 1 2]
                                            {:frame f}))]
                     (testing "all queued event writes finish before the render phase"
                       (is (= {:enabled? false :value 2}
                              (rf/app-db-value f)))
                       (is (= (+ 3 epoch-before)
                              (frame/frame-commit-epoch frame-id)))
                       (is (= "0" (.-textContent
                                    (.querySelector
                                     root
                                     "[data-role='conditional-value']"))))
                       (is (= (empty-evidence) @evidence))
                       (is (= revision-before (reactive/revision cell)))
                       ;; Observation evidence is stamped at the event's
                       ;; pre-increment commit epoch; the frame counter moves
                       ;; immediately after that commit.  Thus three writes
                       ;; span E..E+2 while the settled counter is E+3.
                       (is (= epoch-before
                              (reactive/pending-epoch cell)))
                       (is (= {:first-epoch epoch-before
                               :latest-epoch (+ 2 epoch-before)
                               :count 3}
                              (select-keys (reactive/pending-evidence cell)
                                           [:first-epoch :latest-epoch :count])))
                       (is (= 1 (reactive/pending-cell-count)))
                       (is (identical?
                            handle
                            (:handle (reactive/committed-site cell sid))))
                       (is (= 1 (:ref-count (cache-entry frame-id query))))
                       (is (= 1 (obs/active-owner-count reaction))))
                     (->
                      p
                      (.then
                       (fn []
                         (testing "one commit drops the absent site; stale delivery is absorbed without stale read or reattachment"
                           (is (= "off" (.-textContent
                                          (.querySelector
                                           root
                                           "[data-role='conditional-value']"))))
                           (is (= 1 (:root-commits @evidence)))
                           (is (pos? (:bodies @evidence))
                               "StrictMode may replay render bodies")
                           (is (pos? (:enabled-reads @evidence)))
                           (is (zero? (:conditional-reads @evidence))
                               "the disabled render never executes the absent site")
                           (is (= (inc revision-before)
                                  (reactive/revision cell)))
                           (is (nil? (reactive/committed-site cell sid)))
                           (is (= 1 (count (reactive/committed-sites cell))))
                           (is (nil? (cache-entry frame-id query)))
                           (is (zero? (obs/active-owner-count reaction)))
                           (is (not (obs/current? handle target)))
                           (is (zero? (reactive/pending-cell-count))))
                         (reset! evidence (empty-evidence))
                         (let [absent-sites    (reactive/committed-sites cell)
                               absent-revision (reactive/revision cell)
                               absent-computes @conditional-computes
                               absent-epoch    (frame/frame-commit-epoch frame-id)]
                           (->
                            (uit/flush! #(rf/dispatch-sync [::set-value 3] {:frame f}))
                            (.then
                             (fn []
                               (testing "a value move performs no work while the site is absent"
                                 (is (= {:enabled? false :value 3}
                                        (rf/app-db-value f)))
                                 (is (= (inc absent-epoch)
                                        (frame/frame-commit-epoch frame-id)))
                                 (is (= "off" (.-textContent
                                                (.querySelector
                                                 root
                                                 "[data-role='conditional-value']"))))
                                 (is (= (empty-evidence) @evidence))
                                 (is (= absent-revision
                                        (reactive/revision cell)))
                                 (is (identical?
                                      absent-sites
                                      (reactive/committed-sites cell)))
                                 (is (= absent-computes @conditional-computes))
                                 (is (nil? (cache-entry frame-id query)))
                                 (is (zero? (reactive/pending-cell-count))))
                               (reset! evidence (empty-evidence))
                               (->
                                (uit/flush!
                                 #(rf/dispatch-sync [::set-enabled true] {:frame f}))
                                (.then
                                 (fn []
                                   (testing "reappearance reuses the sid but owns a fresh handle"
                                     (is (= "3" (.-textContent
                                                  (.querySelector
                                                   root
                                                   "[data-role='conditional-value']"))))
                                     (let [[cell-next sid-next record-next]
                                           (site-for-query-head
                                            ::conditional-value)
                                           query-next    (:query record-next)
                                           handle-next    (:handle record-next)
                                           reaction-next (:reaction
                                                          (cache-entry
                                                           frame-id query-next))
                                           version-next  (:version
                                                          (obs/read handle-next))
                                           revision-next (reactive/revision cell)]
                                       (is (identical? cell cell-next))
                                       (is (= sid sid-next))
                                       (is (= query query-next))
                                       (is (not (identical? query query-next)))
                                       (is (not (identical? handle handle-next)))
                                       (is (= 1 (:ref-count
                                                 (cache-entry frame-id query-next))))
                                       (is (= 1
                                              (obs/active-owner-count
                                               reaction-next)))
                                       (reset! evidence (empty-evidence))
                                       (->
                                        (uit/flush!
                                         #(rf/dispatch-sync [::set-value 4] {:frame f}))
                                        (.then
                                         (fn []
                                           (testing "a live move preserves exact ownership"
                                             (is (= "4" (.-textContent
                                                          (.querySelector
                                                           root
                                                           "[data-role='conditional-value']"))))
                                             (is (= 1 (:root-commits @evidence)))
                                             (is (= (inc revision-next)
                                                    (reactive/revision cell)))
                                             (is (= (inc version-next)
                                                    (:version
                                                     (obs/read handle-next))))
                                             (let [record-final
                                                   (reactive/committed-site
                                                    cell sid)]
                                               (is (identical?
                                                    query-next
                                                    (:query record-final)))
                                               (is (identical?
                                                    handle-next
                                                    (:handle record-final))))
                                             (is (= 1
                                                    (:ref-count
                                                     (cache-entry
                                                      frame-id query-next))))
                                             (is (= 1
                                                    (obs/active-owner-count
                                                     reaction-next)))
                                             (is (zero?
                                                  (reactive/pending-cell-count))))
                                           (reset! teardown-proof
                                                   {:cell cell
                                                    :old-reaction reaction
                                                    :new-reaction reaction-next})))))))))))))))))))))))
         (.then
          (fn []
            (let [{:keys [cell old-reaction new-reaction]} @teardown-proof]
              (testing "root teardown returns every ownership surface to zero"
                (is (= :dead (reactive/lifecycle cell)))
                (is (empty? (reactive/committed-sites cell)))
                (is (zero? (obs/active-owner-count old-reaction)))
                (is (zero? (obs/active-owner-count new-reaction)))
                (is (empty? @(:sub-cache (frame/frame frame-id))))
                (is (= baseline
                       {:roots (client/live-root-ids)
                        :root-cells (reactive/root-cell-count)
                        :live-cells (count (reactive/current-live-cells))
                        :pending-cells (reactive/pending-cell-count)})))
              (rf/destroy-frame! f)
              (done)))
          (fn [e]
            (reject-unexpectedly!
             done f "compiled conditional-sub proof rejected" e))))))))
