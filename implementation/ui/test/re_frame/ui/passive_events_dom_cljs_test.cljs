(ns re-frame.ui.passive-events-dom-cljs-test
  "Real-browser proofs for the deliberately narrow literal-passive seam.

  The compiler omits React's synthetic handler and composes one native
  listener owner through the element ref. These fixtures instrument the real
  EventTarget methods; they do not replace dispatch with a test abstraction."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.events :as events]
            [re-frame.ui.runtime :as runtime]
            [re-frame.ui.test :as uit]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter :ambient-frame nil :async? true}))

(defonce ^:private delivered (atom []))

(defn- host-turn! []
  (js/Promise.
   (fn [resolve _reject]
     (js/setTimeout #(resolve nil) 0))))

(defn- click! [el]
  (.dispatchEvent el (js/MouseEvent. "click" #js {:bubbles true
                                                   :cancelable true})))

(defn- dispatch-event! [el event-name]
  (.dispatchEvent el (js/CustomEvent. event-name #js {:bubbles true
                                                       :cancelable true})))

(defn- install-listener-spy!
  [records]
  (let [proto       (.-prototype js/EventTarget)
        original-add (.-addEventListener proto)
        original-remove (.-removeEventListener proto)
        watched?    (fn [target]
                      (and (instance? js/Element target)
                           (.hasAttribute target "data-passive-role")))
        capture?    (fn [options]
                      (if (boolean? options)
                        options
                        (boolean (and options (.-capture options)))))]
    (set! (.-addEventListener proto)
          (fn [event-name callback options]
            (this-as target
              (when (watched? target)
                (swap! records conj
                       {:op :add
                        :node target
                        :role (.getAttribute target "data-passive-role")
                        :event-name event-name
                        :callback callback
                        :capture? (capture? options)
                        :passive? (boolean (and (not (boolean? options))
                                                options
                                                (.-passive options)))}))
              (.call original-add target event-name callback options))))
    (set! (.-removeEventListener proto)
          (fn [event-name callback options]
            (this-as target
              (when (watched? target)
                (swap! records conj
                       {:op :remove
                        :node target
                        :role (.getAttribute target "data-passive-role")
                        :event-name event-name
                        :callback callback
                        :capture? (capture? options)}))
              (.call original-remove target event-name callback options))))
    (fn restore-listener-methods! []
      (set! (.-addEventListener proto) original-add)
      (set! (.-removeEventListener proto) original-remove))))

(defn- records-for
  [records op role]
  (filter #(and (= op (:op %)) (= role (:role %))) records))

(defn- active-listener-count
  [records role]
  (- (count (records-for records :add role))
     (count (records-for records :remove role))))

(defn- register-domain! []
  (reset! delivered [])
  (rf/reg-sub ::target-frame (fn [db _] (:target-frame db)))
  (rf/reg-sub ::node-key (fn [db _] (:node-key db)))
  (rf/reg-sub ::rows (fn [db _] (:rows db)))
  (rf/reg-event
   ::record
   (fn [{:keys [db]} [_ label]]
     (swap! delivered conj [(:frame-label db) label])
     {:db db}))
  (rf/reg-event
   ::retarget
   (fn [{:keys [db]} [_ target-frame]]
     {:db (assoc db :target-frame target-frame)}))
  (rf/reg-event
   ::replace-node
   (fn [{:keys [db]} _]
     {:db (update db :node-key inc)}))
  (rf/reg-event
   ::set-rows
   (fn [{:keys [db]} [_ rows]]
     {:db (assoc db :rows rows)})))

(defview passive-panel [{:keys [node-key authored-ref object-ref]}]
  ^{:rf.ui/suppress
    {:rf.ui.compile/a11y-click-non-interactive
     "not a control: this outer div exists only to observe the CAPTURE phase of
      clicks on the buttons inside it, which are the real controls"}}
  [:div {:data-passive-role "outer"
         :on-click {:event [::record :capture]
                    :passive true
                    :capture true}}
   [:button {:key node-key
             :data-passive-role "passive"
             :ref (ui/raw-fn authored-ref)
             :on-click {:event [::record :passive]
                        :passive true}}
    "passive"]
   [:button {:data-passive-role "once"
             :ref object-ref
             :on-click {:event [::record :once]
                        :passive true
                        :once true}}
    "once"]
   [:button {:data-role "synthetic"
             :on-click [::record :bubble]}
    "synthetic"]])

(defview selected-passive-panel [{:keys [authored-ref object-ref]}]
  [ui/frame-provider {:frame (ui/sub [::target-frame])}
   [passive-panel {:node-key (ui/sub [::node-key])
                   :authored-ref authored-ref
                   :object-ref object-ref}]])

(defview strict-selected-passive-panel [{:keys [authored-ref object-ref]}]
  (ui/raw
   (React/createElement
    (.-StrictMode React) nil
    (React/createElement selected-passive-panel
                         #js {:authored-ref authored-ref
                              :object-ref object-ref}))))

(defview keyed-passive-list []
  [:div
   (for [{:keys [id value]} (ui/sub [::rows])]
     [:button {:key id
               :value value
               :data-passive-role (str "row-" (name id))
               :on-click {:event [::record :rf.ui/value]
                          :passive true}}
      value])])

(defview custom-passive-event-probe []
  [:rf-passive-probe
   {:data-passive-role "custom-hyphen-event"
    :on-my-event {:event [::record :custom-hyphen-event]
                  :passive true}}])

(deftest keyed-list-passive-listeners-are-owned-per-row-occurrence
  (if-not (browser?)
    (is true ":node — browser gate runs keyed passive ownership")
    (do
      (register-domain!)
      (let [records  (atom [])
            restore! (install-listener-spy! records)
            rows-a-b [{:id :a :value "A"} {:id :b :value "B"}]
            rows-b-a (vec (reverse rows-a-b))
            f        (rf/make-frame {:id ::keyed-list-frame
                                     :initial-events [[:rf/set-db
                                                       {:frame-label :keyed
                                                        :rows rows-a-b}]]})]
        (async done
          (let [run
                (uit/with-root
                  [root [ui/frame-provider {:frame f} [keyed-passive-list]]]
                  (let [row-a (.querySelector root "[data-passive-role='row-a']")
                        row-b (.querySelector root "[data-passive-role='row-b']")
                        adds-a (count (records-for @records :add "row-a"))
                        adds-b (count (records-for @records :add "row-b"))]
                    (testing "each keyed occurrence owns a live native listener"
                      (is (= 1 (active-listener-count @records "row-a")))
                      (is (= 1 (active-listener-count @records "row-b"))))
                    (click! row-a)
                    (click! row-b)
                    (-> (uit/flush! host-turn!)
                        (.then
                         (fn []
                           (is (= [[:keyed "A"] [:keyed "B"]] @delivered)
                               "both rows dispatch independently through their committed slots")
                           (reset! delivered [])
                           (uit/flush! #(rf/dispatch-sync [::set-rows rows-b-a] {:frame f}))))
                        (.then
                         (fn []
                           (let [after-a (.querySelector root "[data-passive-role='row-a']")
                                 after-b (.querySelector root "[data-passive-role='row-b']")]
                             (is (identical? row-a after-a)
                                 "keyed reorder preserves row A's node")
                             (is (identical? row-b after-b)
                                 "keyed reorder preserves row B's node")
                             (is (= adds-a
                                    (count (records-for @records :add "row-a")))
                                 "reorder does not churn row A's attachment")
                             (is (= adds-b
                                    (count (records-for @records :add "row-b")))
                                 "reorder does not churn row B's attachment")
                             (is (= 1 (active-listener-count @records "row-a")))
                             (is (= 1 (active-listener-count @records "row-b")))
                             (click! after-b)
                             (click! after-a)
                             (uit/flush! host-turn!))))
                        (.then
                         (fn []
                           (is (= [[:keyed "B"] [:keyed "A"]] @delivered)
                               "reorder cannot cross-retarget the row callbacks")
                           (reset! delivered [])
                           (uit/flush! #(rf/dispatch-sync [::set-rows [(second rows-a-b)]] {:frame f}))))
                        (.then
                         (fn []
                           (let [survivor (.querySelector root "[data-passive-role='row-b']")]
                             (is (zero? (active-listener-count @records "row-a"))
                                 "removing row A detaches only row A")
                             (is (= 1 (active-listener-count @records "row-b"))
                                 "row B remains live after its sibling is removed")
                             (click! survivor)
                             (uit/flush! host-turn!))))
                        (.then
                         (fn []
                           (is (= [[:keyed "B"]] @delivered)))))))]
            (.then run
                   (fn []
                     (is (zero? (active-listener-count @records "row-a")))
                     (is (zero? (active-listener-count @records "row-b"))
                         "final unmount leaves no keyed passive listener")
                     (restore!)
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (restore!)
                     (rf/destroy-frame! f)
                     (is false (str "keyed passive fixture rejected: " e))
                     (done)))))))))

(deftest custom-element-passive-event-keeps-the-verbatim-hyphenated-tail
  (if-not (browser?)
    (is true ":node — browser gate runs custom passive event spelling")
    (do
      (register-domain!)
      (let [f (rf/make-frame {:id ::custom-passive-frame
                              :initial-events [[:rf/set-db
                                                {:frame-label :custom}]]})]
        (async done
          (let [run
                (uit/with-root
                  [root [ui/frame-provider {:frame f}
                         [custom-passive-event-probe]]]
                  (let [node (.querySelector root "rf-passive-probe")]
                    (is (some? node)
                        "the proof dispatches on an actual custom-element node")
                    (if node
                      (do
                        (dispatch-event! node "my-event")
                        (-> (uit/flush! host-turn!)
                            (.then
                             (fn []
                               (is (= [[:custom :custom-hyphen-event]]
                                      @delivered)
                                   (str "CustomEvent(\"my-event\") reaches the "
                                        "verbatim passive listener"))))))
                      (js/Promise.resolve nil))))]
            (.then run
                   (fn []
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (rf/destroy-frame! f)
                     (is false (str "custom passive event fixture rejected: " e))
                     (done)))))))))

(deftest passive-native-listeners-own-options-order-retarget-and-cleanup
  (if-not (browser?)
    (is true ":node — browser gate runs the passive-listener proof")
    (do
      (register-domain!)
      (let [records  (atom [])
            ref-log  (atom [])
            restore! (install-listener-spy! records)
            authored-ref
            (fn [node]
              (when node
                (swap! ref-log conj [:set node])
                (fn [] (swap! ref-log conj [:cleanup node]))))
            object-ref (React/createRef)
            fa (rf/make-frame {:id ::a
                               :initial-events [[:rf/set-db
                                                 {:frame-label :a}]]})
            fb (rf/make-frame {:id ::b
                               :initial-events [[:rf/set-db
                                                 {:frame-label :b}]]})
            fc (rf/make-frame {:id ::controller
                               :initial-events [[:rf/set-db
                                                 {:target-frame ::a
                                                  :node-key 0}]]})]
        (async done
          (let [run
                (uit/with-root
                  [root [ui/frame-provider {:frame fc}
                         [strict-selected-passive-panel
                          {:authored-ref authored-ref
                           :object-ref object-ref}]]]
                  (let [outer    (.querySelector root "[data-passive-role='outer']")
                        passive  (.querySelector root "[data-passive-role='passive']")
                        once     (.querySelector root "[data-passive-role='once']")
                        synthetic (.querySelector root "[data-role='synthetic']")]
                    (testing "the live listener set is singular and faithfully optioned"
                      (is (= 1 (active-listener-count @records "outer")))
                      (is (= 1 (active-listener-count @records "passive")))
                      (is (= 1 (active-listener-count @records "once")))
                      (is (identical? once (.-current object-ref))
                          "the composed object ref receives the passive node")
                      (is (every? :passive?
                                  (filter #(= :add (:op %)) @records)))
                      (is (true? (:capture?
                                  (last (records-for @records :add "outer")))))
                      (is (false? (:capture?
                                   (last (records-for @records :add "passive"))))))
                    (reset! delivered [])
                    (click! passive)
                    (-> (uit/flush! host-turn!)
                        (.then
                         (fn []
                           (is (= [[:a :capture] [:a :passive]] @delivered)
                               "native capture precedes target and no synthetic duplicate fires")
                           (reset! delivered [])
                           (click! synthetic)
                           (uit/flush! host-turn!)))
                        (.then
                         (fn []
                           (is (= [[:a :capture] [:a :bubble]] @delivered)
                               "native capture composes with React's delegated bubble order")
                           (reset! delivered [])
                           (click! once)
                           (click! once)
                           (uit/flush! host-turn!)))
                        (.then
                         (fn []
                           (is (= [[:a :capture] [:a :once] [:a :capture]]
                                  @delivered)
                               "the committed once fence survives the native-ref seam")
                           (uit/flush! #(rf/dispatch-sync [::retarget ::b] {:frame fc}))))
                        (.then
                         (fn []
                           (reset! delivered [])
                           (click! passive)
                           (uit/flush! host-turn!)))
                        (.then
                         (fn []
                           (is (= [[:b :capture] [:b :passive]] @delivered)
                               "the same native callback reads the newest committed frame")
                           (-> (uit/flush!
                                #(rf/dispatch-sync [::replace-node] {:frame fc}))
                                 (.then
                                  (fn []
                                    (let [replacement
                                          (.querySelector
                                           root
                                           "[data-passive-role='passive']")]
                                      (is (not (identical? passive replacement)))
                                      (is (= 1
                                             (count
                                              (filter
                                               #(and (= :remove (:op %))
                                                     (identical? passive
                                                                 (:node %)))
                                               @records)))
                                          "the replaced node's attachment detaches once")
                                      (is (= 1 (active-listener-count
                                                @records "passive")))
                                      (is (= 1
                                             (count
                                              (filter
                                               #(and (= :cleanup (first %))
                                                     (identical? passive
                                                                 (second %)))
                                               @ref-log)))
                                          "the authored ref cleanup composes exactly once for the old node")
                                      (is (= 1
                                             (- (count
                                                 (filter
                                                  #(and (= :set (first %))
                                                        (identical? replacement
                                                                    (second %)))
                                                  @ref-log))
                                                (count
                                                 (filter
                                                  #(and (= :cleanup (first %))
                                                        (identical? replacement
                                                                    (second %)))
                                                  @ref-log))))
                                          "StrictMode replay still leaves one live replacement ref"))))))))))]
            (.then run
                   (fn []
                     (testing "StrictMode replay and final unmount are balanced"
                       (is (zero? (active-listener-count @records "outer")))
                       (is (zero? (active-listener-count @records "passive")))
                       (is (zero? (active-listener-count @records "once")))
                       (is (nil? (.-current object-ref))
                           "object refs clear on final unmount")
                       (is (= (count (filter #(= :set (first %)) @ref-log))
                              (count (filter #(= :cleanup (first %)) @ref-log)))
                           "every authored-ref setup, including StrictMode replay, cleaned"))
                     (restore!)
                     (doseq [f [fa fb fc]] (rf/destroy-frame! f))
                     (done))
                   (fn [e]
                     (restore!)
                     (doseq [f [fa fb fc]] (rf/destroy-frame! f))
                     (is false (str "passive listener fixture rejected: " e))
                     (done)))))))))

(defn- register-hmr-passive!
  [id label]
  (runtime/register-view!
   id
   (fn [_props]
     (React/createElement
      "button"
      #js {:data-passive-role "hmr-passive"
           :ref (events/passive-ref
                 [[:hmr-passive-site
                   "click"
                   (events/data-handler
                    :hmr-passive-site [::record label] 0 nil)
                   false]]
                 nil)}
      (name label)))
   (fn [_ _] true)
   "PassiveHmrProbe"
   {:view-id id
    :display-name "PassiveHmrProbe"
    :template-fingerprint "tf-passive-hmr"
    :hook-signature "hs-passive-hmr"
    :sites {}}))

(defview passive-hmr-host [{:keys [shell]}]
  (ui/raw (React/createElement shell nil)))

(deftest passive-listener-survives-same-signature-hmr-without-churn
  (if-not (browser?)
    (is true ":node — browser gate runs passive HMR")
    (do
      (register-domain!)
      (let [records  (atom [])
            restore! (install-listener-spy! records)
            f         (rf/make-frame {:id ::hmr-frame
                                      :initial-events [[:rf/set-db
                                                        {:frame-label :hmr}]]})
            id        ::passive-hmr
            shell     (register-hmr-passive! id :v1)]
        (async done
          (let [run
                (uit/with-root
                  [root [ui/frame-provider {:frame f}
                         [passive-hmr-host {:shell shell}]]]
                  (let [button (.querySelector root "[data-passive-role='hmr-passive']")
                        adds   (count (records-for @records :add "hmr-passive"))
                        removes (count (records-for @records :remove "hmr-passive"))]
                    (click! button)
                    (-> (uit/flush! host-turn!)
                        (.then
                         (fn []
                           (is (= [[:hmr :v1]] @delivered))
                           (reset! delivered [])
                           (uit/flush! #(register-hmr-passive! id :v2))))
                        (.then
                         (fn []
                           (let [after (.querySelector root
                                                  "[data-passive-role='hmr-passive']")]
                             (is (identical? button after))
                             (is (= adds
                                    (count (records-for @records :add
                                                       "hmr-passive"))))
                             (is (= removes
                                    (count (records-for @records :remove
                                                       "hmr-passive"))))
                             (click! after)
                             (uit/flush! host-turn!))))
                        (.then
                         (fn []
                           (is (= [[:hmr :v2]] @delivered)
                               "the stable native callback reads the HMR-committed descriptor"))))))]
            (.then run
                   (fn []
                     (is (zero? (active-listener-count @records "hmr-passive")))
                     (restore!)
                     (rf/destroy-frame! f)
                     (done))
                   (fn [e]
                     (restore!)
                     (rf/destroy-frame! f)
                     (is false (str "passive HMR fixture rejected: " e))
                     (done)))))))))
