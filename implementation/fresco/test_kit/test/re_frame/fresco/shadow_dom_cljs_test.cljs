(ns re-frame.fresco.shadow-dom-cljs-test
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test :as rf.fresco.test]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.test-support :as rf.test-support]
            [reagent.core :as r]))

;; Register above the fixture, which snapshots the source store when defined.
(rf/reg-sub :fresco.shadow/title (fn [db _] (:title db)))
(rf/reg-sub :fresco.shadow/editing? (fn [db _] (:editing? db)))
(rf/reg-sub :fresco.shadow/saved (fn [db _] (:saved db)))

(rf/reg-event :fresco.shadow/seed
              (fn [_ _] {:db {:title "Original title" :editing? false :saved nil}}))

(rf/reg-event :fresco.shadow/edit
              (fn [{:keys [db]} _] {:db (assoc db :editing? true)}))

(rf/reg-event :fresco.shadow/set-title
              (fn [{:keys [db]} [_ v]] {:db (assoc db :title v)}))
;; The extra save argument moves only the intent stream, leaving DOM and app-db equal.
(rf/reg-event :fresco.shadow/save
              (fn [{:keys [db]} _] {:db (assoc db :saved (:title db) :editing? false)}))
(rf/reg-event :fresco.shadow/noted
              (fn [{:keys [db]} [_ x]] {:db (update db :notes (fnil conj []) x)}))
(rf/reg-event :fresco.shadow/pick
              (fn [{:keys [db]} [_ v]] {:db (assoc db :picked v)}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.fresco.impl.collector/reset-runtime!))}))

(rf.fresco/defview reference-row
  [_]
  (let [title    (rf.fresco/sub [:fresco.shadow/title])
        editing? (rf.fresco/sub [:fresco.shadow/editing?])
        saved    (rf.fresco/sub [:fresco.shadow/saved])]
    [:div.row
     [:h3.title title]
     (if editing?
       [:span.editor
        [:input.title {:value       title
                       :placeholder "Title"
                       :on-input    [:fresco.shadow/set-title ::rf.fresco/value]}]
        [:button.save {:on-click [:fresco.shadow/save]} "Save"]]
       [:button.edit {:on-click [:fresco.shadow/edit]} "Edit"])
     [:p.saved (str "saved: " (or saved "—"))]]))

(defn- editor-markup
  [title]
  [:span.editor
   [:input.title {:value       title
                  :placeholder "Title"
                  :on-input    [:fresco.shadow/set-title ::rf.fresco/value]}]
   [:button.save {:on-click [:fresco.shadow/save]} "Save"]])

(rf.fresco/defview candidate-row
  [_]
  [:div.row
   [:h3.title (rf.fresco/sub [:fresco.shadow/title])]
   (if (rf.fresco/sub [:fresco.shadow/editing?])
     (editor-markup (rf.fresco/sub [:fresco.shadow/title]))
     [:button.edit {:on-click [:fresco.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (rf.fresco/sub [:fresco.shadow/saved]) "—"))]])

(rf.fresco/defview drifted-intent-row
  [_]
  [:div.row
   [:h3.title (rf.fresco/sub [:fresco.shadow/title])]
   (if (rf.fresco/sub [:fresco.shadow/editing?])
     [:span.editor
      [:input.title {:value       (rf.fresco/sub [:fresco.shadow/title])
                     :placeholder "Title"
                     :on-input    [:fresco.shadow/set-title ::rf.fresco/value]}]
      [:button.save {:on-click [:fresco.shadow/save :force]} "Save"]]
     [:button.edit {:on-click [:fresco.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (rf.fresco/sub [:fresco.shadow/saved]) "—"))]])

(rf.fresco/defview drifted-dom-row
  [_]
  [:div.row
   [:h3.title (rf.fresco/sub [:fresco.shadow/title])]
   (if (rf.fresco/sub [:fresco.shadow/editing?])
     [:span.editor
      [:input.title {:value       (rf.fresco/sub [:fresco.shadow/title])
                     :placeholder "Article title"
                     :on-input    [:fresco.shadow/set-title ::rf.fresco/value]}]
      [:button.save {:on-click [:fresco.shadow/save]} "Save"]]
     [:button.edit {:on-click [:fresco.shadow/edit]} "Edit"])
   [:p.saved (str "saved: " (or (rf.fresco/sub [:fresco.shadow/saved]) "—"))]])

(rf.fresco/defview select-row
  [{:keys [value]}]
  [:div.pick
   [:select.pick {:value     value
                  :on-change [:fresco.shadow/pick ::rf.fresco/value]}
    [:option {:value "one"} "One"]
    [:option {:value "two"} "Two"]
    [:option {:value "three"} "Three"]]])

(rf.fresco/defview uncontrolled-fields
  [_]
  [:div.fields
   [:input.flag {:type "checkbox"}]
   [:input.text {:type "text"}]
   [:select.multi {:multiple true}
    [:option {:value "a"} "A"]
    [:option {:value "b"} "B"]
    [:option {:value "c"} "C"]]])

(defn- browser? [] (exists? js/document))

(defn- skip! [why]
  (is true (str "a shadow run mounts two React roots — " why)))

(def ^:private seed [[:fresco.shadow/seed]])

(def ^:private script
  [{:click "button.edit"}
   {:type  ["input.title" "Better title"]}
   {:click "button.save"}])

(defn- run
  [candidate]
  (rf.fresco.test.mounted/shadow! {:reference      [reference-row {}]
               :candidate      [candidate {}]
               :initial-events seed
               :script         script}))

(deftest a-proved-same-pair-is-green
  (if-not (browser?)
    (skip! "the pair is two mounted implementations")
    (is (= {:status :green :checkpoints 4} (run candidate-row)))))

(deftest a-drifted-intent-names-its-checkpoint-and-both-vectors
  (if-not (browser?)
    (skip! "an intent stream needs a real click")
    (is (= {:status      :red
            :checkpoint  3
            :checkpoints 4
            :step        {:click "button.save"}
            :difference  {:kind      :intent
                          :reason    :differs
                          :index     0
                          :reference [:fresco.shadow/save]
                          :candidate [:fresco.shadow/save :force]}}
           (run drifted-intent-row)))))

(deftest a-drifted-attribute-names-the-exact-node
  (if-not (browser?)
    (skip! "an attribute is read off a real element")
    (let [{:keys [difference] :as verdict} (run drifted-dom-row)]
      (is (= {:status :red :checkpoint 1 :checkpoints 2 :step {:click "button.edit"}
              :difference {:kind :dom :reason :attribute :attribute "placeholder"
                           :reference "Title" :candidate "Article title"}}
             (update verdict :difference dissoc :at :path)))
      (is (re-find #"input" (:at difference))))))

(deftest a-step-that-matches-nothing-is-red-and-not-green
  (if-not (browser?)
    (skip! "a selector is resolved against a real container")
    (is (= {:status      :red
            :checkpoint  1
            :checkpoints 2
            :step        {:click "button.no-such-control"}
            :difference  {:kind     :script
                          :reason   :selector-matched-nothing
                          :selector "button.no-such-control"
                          :matched  :neither}}
           (rf.fresco.test.mounted/shadow! {:reference      [reference-row {}]
                        :candidate      [candidate-row {}]
                        :initial-events seed
                        :script         [{:click "button.no-such-control"}]})))))

(defn- markup-of [handle] (rf.fresco.test/canonical-dom (:container handle)))

(deftest a-select-showing-a-different-option-is-not-a-green
  (if-not (browser?)
    (skip! "a selection lives in a real element's properties")
    (let [s (rf.fresco.test.mounted/shadow!
              {:reference [select-row {:value "two"}]
               :candidate [select-row {:value "two"}]})]
      (try
        (is (= {:status :green :checkpoints 1} ((:checkpoint! s))))
        (set! (.-value (.querySelector (:container (:candidate s)) "select.pick")) "one")
        (is (= ["two" "one"]
               [(.-value (.querySelector (:container (:reference s)) "select.pick"))
                (.-value (.querySelector (:container (:candidate s)) "select.pick"))]))
        ;; Equal markup makes this a property-only difference.
        (is (= (markup-of (:reference s)) (markup-of (:candidate s))))
        (let [{:keys [difference] :as verdict} ((:checkpoint! s))]
          (is (= {:status :red :checkpoint 1 :checkpoints 2
                  :difference {:kind :dom :reason :property :property "value"
                               :reference "two" :candidate "one"}}
                 (update verdict :difference dissoc :at :path)))
          (is (re-find #"select" (:at difference))))
        (finally ((:stop! s)))))))

(defn- live-difference
  [form mutate!]
  (let [s (rf.fresco.test.mounted/shadow! {:reference form :candidate form})]
    (try
      (let [green? (= :green (:status ((:checkpoint! s))))]
        (mutate! (:container (:reference s)) (:container (:candidate s)))
        (assoc ((:checkpoint! s))
               ::started-green? green?
               ::same-markup?   (= (markup-of (:reference s))
                                   (markup-of (:candidate s)))))
      (finally ((:stop! s))))))

(defn- is-live-only [v]
  (is (= [true true] [(::started-green? v) (::same-markup? v)]))
  (:difference v))

(defn- pick-options!
  [container indexes]
  (let [opts (.-options (.querySelector container "select.multi"))]
    (doseq [i indexes] (set! (.-selected (.item opts i)) true))))

(deftest the-adjacent-live-slots-redden-too
  (if-not (browser?)
    (skip! "a dirty control is one a real browser has been driven through")
    (do
      (testing "a checkbox one side ticked"
        (let [d (is-live-only
                  (live-difference [uncontrolled-fields {}]
                                   (fn [r _] (.click (.querySelector r "input.flag")))))]
          (is (= {:kind :dom :reason :property :property "checked" :reference true :candidate false}
                 (dissoc d :at :path)))
          (is (re-find #"input\[0\]" (:at d)))))

      (testing "a text field one side typed into"
        (let [d (is-live-only
                  (live-difference [uncontrolled-fields {}]
                                   (fn [r _]
                                     (set! (.-value (.querySelector r "input.text"))
                                           "typed"))))]
          (is (= {:kind :dom :reason :property :property "value" :reference "typed" :candidate ""}
                 (dissoc d :at :path)))
          (is (re-find #"input\[1\]" (:at d)))))

      (testing "a checkbox one side left in the third state"
        (let [d (is-live-only
                  (live-difference [uncontrolled-fields {}]
                                   (fn [r _]
                                     (set! (.-indeterminate
                                             (.querySelector r "input.flag"))
                                           true))))]
          (is (= {:kind :dom :reason :property :property "indeterminate" :reference true :candidate false}
                 (dissoc d :at :path)))))

      (testing "a MULTIPLE select whose value alone cannot tell the two apart"
        (let [d (is-live-only
                  (live-difference [uncontrolled-fields {}]
                                   (fn [r c]
                                     (pick-options! r [0 2])
                                     (pick-options! c [0 1]))))]
          (is (= {:kind :dom :reason :property :property "selected" :reference false :candidate true}
                 (dissoc d :at :path)))
          (is (re-find #"option\[1\]" (:at d))))))))

(deftest each-mount-owns-its-own-frame
  (if-not (browser?)
    (skip! "two frames need two roots")
    (let [s (rf.fresco.test.mounted/shadow! {:reference      [reference-row {}]
                         :candidate      [candidate-row {}]
                         :initial-events seed})]
      (try
        (is (= :live (:status s)))
        (is (= {:status :green :checkpoints 1} ((:checkpoint! s))))
        (rf.fresco.test.mounted/dispatch-and-settle! (:reference s) [:fresco.shadow/edit])
        (testing "dispatch stays within its own mount"
          (is (= {:kind :intent :reason :only-in-reference :index 0 :reference [:fresco.shadow/edit]}
                 (dissoc (:difference ((:checkpoint! s))) :candidate))))
        (finally ((:stop! s)))))))

(deftest a-mounts-own-address-normalises
  (if-not (browser?)
    (skip! "an address is a live frame's keyword")
    (let [s (rf.fresco.test.mounted/shadow! {:reference      [reference-row {}]
                         :candidate      [candidate-row {}]
                         :initial-events seed})]
      (try
        ((:checkpoint! s))                                  ;; drain the seed
        (testing "each side's OWN frame keyword compares equal"
          (rf.fresco.test.mounted/dispatch-and-settle! (:reference s)
                                   [:fresco.shadow/noted (:frame (:reference s))])
          (rf.fresco.test.mounted/dispatch-and-settle! (:candidate s)
                                   [:fresco.shadow/noted (:frame (:candidate s))])
          (is (= :green (:status ((:checkpoint! s))))))
        (testing "and a FOREIGN address still reddens — the rule is narrow"
          (rf.fresco.test.mounted/dispatch-and-settle! (:reference s) [:fresco.shadow/noted ::alien-a])
          (rf.fresco.test.mounted/dispatch-and-settle! (:candidate s) [:fresco.shadow/noted ::alien-b])
          (is (= {:kind      :intent
                  :reason    :differs
                  :index     0
                  :reference [:fresco.shadow/noted ::alien-a]
                  :candidate [:fresco.shadow/noted ::alien-b]}
                 (:difference ((:checkpoint! s))))))
        (finally ((:stop! s)))))))

(defn- reagent-title-row
  [{:keys [title]}]
  [:div.row [:h3.title title]])

;; Reactify once so the foreign element type stays stable.
(def ^:private foreign-title-row
  (r/reactify-component reagent-title-row))

(rf.fresco/defview fresco-title-row
  [{:keys [title]}]
  [:div.row [:h3.title title]])

(deftest a-foreign-reference-crosses-in-and-compares-green
  (if-not (browser?)
    (skip! "a crossed foreign original needs a real React DOM")
    (is (= {:status :green :checkpoints 1}
           (rf.fresco.test.mounted/shadow! {:reference      [:> foreign-title-row {:title "Original title"}]
                        :candidate      [fresco-title-row {:title "Original title"}]
                        :initial-events seed
                        :script         []})))))

(defn- refusal
  [f]
  (try (f) ::no-refusal (catch :default e (ex-data e))))

(deftest the-option-roster-is-closed
  (let [d (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                 :candidate [candidate-row {}]
                                 :seed      seed}))]
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option :unknown [:seed]}
           (select-keys d [:rf.error/id :unknown])))))

(deftest the-script-verb-roster-is-closed
  (let [d (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                 :candidate [candidate-row {}]
                                 :script    [{:clik "button.edit"}]}))]
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option :step {:clik "button.edit"}}
           (select-keys d [:rf.error/id :step]))))
  (testing "and each verb's own argument shape"
    (is (= :rf.error/fresco-test-bad-option
           (:rf.error/id (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                                :candidate [candidate-row {}]
                                                :script    [{:click :button}]})))))
    (is (= :rf.error/fresco-test-bad-option
           (:rf.error/id (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                                :candidate [candidate-row {}]
                                                :script    [{:type "input.title"}]})))))))
