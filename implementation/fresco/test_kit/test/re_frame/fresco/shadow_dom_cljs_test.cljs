(ns re-frame.fresco.shadow-dom-cljs-test
  "`hm/shadow!` — the comparator, driven the way a test drives any page.

  Every row drives BOTH mounts itself, with the same DOM events on each,
  and takes a `checkpoint!` where it wants the two compared. The flow at
  the bottom of the file is the one a synchronous interaction script
  could not reach: a route-link click whose navigation lands on a later
  task, waited for on each mount with `settle-until!` and only then
  compared — with assertions of the page's own beside the checkpoints,
  because two sides agreeing does not make either of them right.

  The clicks and keystrokes are the platform events a Testing Library
  `user-event` sequence ends in: `HTMLElement.click()`, and a write
  through the prototype's `value` setter followed by a real `input`
  event. Nothing here depends on Testing Library."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.fresco :as rf.fresco]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test :as rf.fresco.test]
            [re-frame.fresco.test.mounted :as rf.fresco.test.mounted]
            [re-frame.routing :as rf.routing]
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

;; The navigation flow's application: a list and an item page, under a
;; leading path segment of its own, because route paths share one
;; process-wide match table with every other application the bundle loads.
(rf/reg-sub :fresco.shadow/items
            (fn [db _] (vec (sort-by :slug (vals (:items db))))))
(rf/reg-sub :fresco.shadow/item
            (fn [db [_ slug]] (get-in db [:items slug])))
(rf/reg-event :fresco.shadow/seed-items
              (fn [_ _] {:db {:items {"first"  {:slug "first" :title "First"}
                                      "second" {:slug "second" :title "Second"}}}}))
(rf/reg-event :fresco.shadow/rename
              (fn [{:keys [db]} [_ slug v]] {:db (assoc-in db [:items slug :title] v)}))

(defn- register-routes!
  "The flow's two routes. Also called from the fixture: the reset restores
  the registrar to the baseline it captured when the fixture was built."
  []
  (rf.routing/reg-route ::list {:doc "The item list."} "/fresco-shadow")
  (rf.routing/reg-route ::item {:doc "One item."} "/fresco-shadow/item/:slug")
  nil)

(register-routes!)

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.fresco.impl.collector/reset-runtime!)
                      (register-routes!))}))

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

;; ---------------------------------------------------------------------------
;; Driving a mount — the events a user-event sequence ends in
;; ---------------------------------------------------------------------------

(defn- node [m sel] (.querySelector (:container m) sel))

(defn- text [m sel] (some-> (node m sel) .-textContent))

(defn- click!
  "A real click, and then a settle: an intent's own dispatch is
  synchronous, so the settle is all that is owed."
  [m sel]
  (.click (node m sel))
  (rf.fresco.test.mounted/settle! m))

(defn- type-into!
  "Type `v` into the field at `sel` — a write through the PROTOTYPE's
  `value` setter, then a real `input` event. React patches the instance
  setter to keep its own change tracker, so a plain `set!` would update
  the tracker too and the event would reach no handler."
  [m sel v]
  (let [n (node m sel)
        d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)
    (.dispatchEvent n (js/InputEvent. "input" #js {:bubbles true}))
    (rf.fresco.test.mounted/settle! m)))

(defn- on-both!
  "The same interaction on each mount, reference first."
  [s f]
  (f (:reference s))
  (f (:candidate s)))

;; ---------------------------------------------------------------------------
;; The comparator over a synchronous flow
;; ---------------------------------------------------------------------------

(defn- edit-flow
  "Drive the edit flow on both mounts with a checkpoint after the mount and
  after each interaction, and answer the first red, or the last green."
  [candidate]
  (let [s (rf.fresco.test.mounted/shadow! {:reference      [reference-row {}]
                                           :candidate      [candidate {}]
                                           :initial-events seed})]
    (try
      (reduce (fn [_ interaction]
                (when interaction (on-both! s interaction))
                (let [verdict ((:checkpoint! s))]
                  (if (= :red (:status verdict)) (reduced verdict) verdict)))
              nil
              [nil
               #(click! % "button.edit")
               #(type-into! % "input.title" "Better title")
               #(click! % "button.save")])
      (finally ((:stop! s))))))

(deftest a-proved-same-pair-is-green
  (if-not (browser?)
    (skip! "the pair is two mounted implementations")
    (is (= {:status :green :checkpoints 4} (edit-flow candidate-row)))))

(deftest a-drifted-intent-names-its-checkpoint-and-both-vectors
  (if-not (browser?)
    (skip! "an intent stream needs a real click")
    (is (= {:status      :red
            :checkpoint  3
            :checkpoints 4
            :difference  {:kind      :intent
                          :reason    :differs
                          :index     0
                          :reference [:fresco.shadow/save]
                          :candidate [:fresco.shadow/save :force]}}
           (edit-flow drifted-intent-row)))))

(deftest a-drifted-attribute-names-the-exact-node
  (if-not (browser?)
    (skip! "an attribute is read off a real element")
    (let [{:keys [difference] :as verdict} (edit-flow drifted-dom-row)]
      (is (= {:status :red :checkpoint 1 :checkpoints 2
              :difference {:kind :dom :reason :attribute :attribute "placeholder"
                           :reference "Title" :candidate "Article title"}}
             (update verdict :difference dissoc :at :path)))
      (is (re-find #"input" (:at difference))))))

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
        (is (= #{:reference :candidate :checkpoint! :stop!} (set (keys s))))
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
    (let [s (rf.fresco.test.mounted/shadow!
              {:reference      [:> foreign-title-row {:title "Original title"}]
               :candidate      [fresco-title-row {:title "Original title"}]
               :initial-events seed})]
      (try
        (is (= {:status :green :checkpoints 1} ((:checkpoint! s))))
        (finally ((:stop! s)))))))

(defn- refusal
  [f]
  (try (f) ::no-refusal (catch :default e (ex-data e))))

(deftest the-option-roster-is-closed
  (let [d (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                                     :candidate [candidate-row {}]
                                                     :seed      seed}))]
    (is (= {:rf.error/id :rf.error/fresco-test-bad-option :unknown [:seed]}
           (select-keys d [:rf.error/id :unknown]))))
  (testing "and there is no interaction grammar to hand it — the caller drives"
    (let [d (refusal #(rf.fresco.test.mounted/shadow! {:reference [reference-row {}]
                                                       :candidate [candidate-row {}]
                                                       :script    [{:click "button.edit"}]}))]
      (is (= {:rf.error/id :rf.error/fresco-test-bad-option :unknown [:script]}
             (select-keys d [:rf.error/id :unknown]))))))

;; ---------------------------------------------------------------------------
;; A flow with a navigation in it — compared at the checkpoints it chooses
;; ---------------------------------------------------------------------------

(rf.fresco/defview flow-original
  "The screen as first written: one body, branching on the route."
  [_]
  (let [route (rf.fresco/sub [:rf.route/id])]
    [:main.flow
     (if (= route ::item)
       (let [slug (:slug (rf.fresco/sub [:rf.route/params]))
             item (rf.fresco/sub [:fresco.shadow/item slug])]
         [:section.item
          [:h2.heading (:title item)]
          [:input.title {:value    (:title item)
                         :on-input [:fresco.shadow/rename slug ::rf.fresco/value]}]
          (rf.fresco/route-link {:to ::list :class "back"} "Back")])
       [:section.list
        [:h2.heading "Items"]
        [:ul
         (for [{:keys [slug title]} (rf.fresco/sub [:fresco.shadow/items])]
           [:li {:key slug}
            (rf.fresco/route-link {:to ::item :params {:slug slug} :class "item-link"}
                                  title)])]])]))

(rf.fresco/defview list-pane
  [_]
  [:section.list
   [:h2.heading "Items"]
   [:ul
    (for [{:keys [slug title]} (rf.fresco/sub [:fresco.shadow/items])]
      [:li {:key slug}
       (rf.fresco/route-link {:to ::item :params {:slug slug} :class "item-link"} title)])]])

(defn- item-pane-body
  [heading-tag {:keys [slug]}]
  (let [item (rf.fresco/sub [:fresco.shadow/item slug])]
    [:section.item
     [heading-tag {:class "heading"} (:title item)]
     [:input.title {:value    (:title item)
                    :on-input [:fresco.shadow/rename slug ::rf.fresco/value]}]
     (rf.fresco/route-link {:to ::list :class "back"} "Back")]))

(rf.fresco/defview item-pane [props] (item-pane-body :h2 props))

;; The sabotage twin: the item page's heading is an `h3`, a difference
;; only a checkpoint taken AFTER the navigation can see.
(rf.fresco/defview drifted-item-pane [props] (item-pane-body :h3 props))

(defn- flow-port-body
  "The port: the same screen split into a pane per route."
  [item-view]
  [:main.flow
   (if (= ::item (rf.fresco/sub [:rf.route/id]))
     [item-view {:slug (:slug (rf.fresco/sub [:rf.route/params]))}]
     [list-pane {}])])

(rf.fresco/defview flow-port-view [_] (flow-port-body item-pane))

(rf.fresco/defview flow-drifted-port-view [_] (flow-port-body drifted-item-pane))

(defn- route-of [m] (rf/subscribe-once [:rf.route/id] {:frame (:frame m)}))

(defn- both-arrive!
  "Wait, on EACH mount, until its route is `route-id`. A route-link click
  enqueues its navigation and the router drains it on a later task, so
  no synchronous settle can reach it."
  [s route-id label]
  (js/Promise.all
    #js [(rf.fresco.test.mounted/settle-until!
           (:reference s) #(= route-id (route-of (:reference s)))
           {:label (str "the reference reaching " label)})
         (rf.fresco.test.mounted/settle-until!
           (:candidate s) #(= route-id (route-of (:candidate s)))
           {:label (str "the candidate reaching " label)})]))

(defn- navigation-flow
  "Drive list → item → rename → back on both mounts, taking a checkpoint at
  each point the page is at rest, and answer a promise of the verdicts in
  order. `at` is called with the session and a step name at each rest
  point, for the row's own assertions about the page."
  [candidate at]
  (let [s         (rf.fresco.test.mounted/shadow!
                    {:reference      [flow-original {}]
                     :candidate      [candidate {}]
                     :initial-events [[:fresco.shadow/seed-items]
                                      [:rf.route/navigate {:to ::list}]]})
        !verdicts (atom [])
        rest!     (fn [step]
                    (swap! !verdicts conj ((:checkpoint! s)))
                    (at s step))]
    (-> (js/Promise.resolve nil)
        (.then (fn [_]
                 (rest! :list)
                 (on-both! s #(click! % "a.item-link"))
                 (both-arrive! s ::item "the item page")))
        (.then (fn [_]
                 (rest! :item)
                 (on-both! s #(type-into! % "input.title" "First, renamed"))
                 (rest! :renamed)
                 (on-both! s #(click! % "a.back"))
                 (both-arrive! s ::list "the list again")))
        (.then (fn [_] (rest! :back)))
        (.then (fn [_] ((:stop! s)) @!verdicts)
               (fn [e] ((:stop! s)) (throw e))))))

(deftest a-flow-through-a-navigation-is-compared-where-the-caller-checkpoints
  (if-not (browser?)
    (skip! "a route-link click needs a real DOM")
    (async done
      (-> (navigation-flow
            flow-port-view
            (fn [s step]
              ;; The page's own facts, asserted on the candidate: the
              ;; checkpoints say the two sides agree, these say what they
              ;; agree ON.
              (let [c (:candidate s)]
                (case step
                  :list    (is (= "Items" (text c "h2.heading")))
                  :item    (is (= "First" (text c "h2.heading")))
                  :renamed (is (= "First, renamed" (text c "h2.heading")))
                  :back    (is (= "First, renamed" (text c "a.item-link")))))))
          (.then (fn [verdicts]
                   (is (= [{:status :green :checkpoints 1}
                           {:status :green :checkpoints 2}
                           {:status :green :checkpoints 3}
                           {:status :green :checkpoints 4}]
                          verdicts))))
          (.catch (fn [e] (is false (str "the flow never settled: " (ex-message e)
                                         " " (pr-str (ex-data e))))))
          (.then (fn [_] (done)))))))

(deftest a-drift-behind-a-navigation-reddens-at-the-checkpoint-after-it
  (if-not (browser?)
    (skip! "a route-link click needs a real DOM")
    (async done
      (-> (navigation-flow flow-drifted-port-view (fn [_ _] nil))
          (.then (fn [verdicts]
                   (is (= {:status :green :checkpoints 1} (first verdicts))
                       "the list pages agree, so the drift is invisible before the click")
                   (is (= {:status :red :checkpoint 1 :checkpoints 2
                           :difference {:kind :dom :reason :tag
                                        :reference "h2" :candidate "h3"}}
                          (update (second verdicts) :difference dissoc :at :path)))))
          (.catch (fn [e] (is false (str "the flow never settled: " (ex-message e)
                                         " " (pr-str (ex-data e))))))
          (.then (fn [_] (done)))))))
