(ns re-frame.hicasso.test-kit-dogfood-cljs-test
  "THE DOGFOOD WITNESSES, RE-EXPRESSED ON THE PUBLIC KIT (rf2-hic-020).

  `re-frame.bench.hicasso.front.dogfood-cljs-test` proves the front half
  of a real screen: the state layer the renderings share, and the
  composition — *an intent written in the authoring spelling, lowered
  through the codec's prop walk, invoked as the browser would invoke it,
  reaching a real re-frame2 event handler and moving a real app-db*. It
  proves it by reaching into bench-private machinery: `intent/with-frame`
  around `codec/as-element`, then `(aget (.-props el) \"onClick\")` to get
  the wrapper back out.

  This file makes **the same claims through `re-frame.hicasso.test` and
  nothing else** — no `impl.*` require, no element surgery, no private
  door. That is rf2-hic-020's acceptance criterion, and it is a real
  test of the design rather than a restatement: if a claim needed an
  escape hatch, the kit would not be sufficient and the honest answer
  would be to say so rather than to open one.

  ## The screen is local, and it has to be

  The freeze gate seals `implementation/hicasso/**` against requiring a
  benchmark-tree namespace, so the dogfood screen itself cannot be
  imported here. What is re-expressed is therefore the CLAIMS, over a
  screen of the same shape: a filtered to-do list with per-instance
  drafts, a keyed row list, a controlled new-item field, a submitting
  form and a data key-map. Every row below names the bench witness it
  answers.

  ## Which tier each claim went to, which is the finding

  Re-expressing them sorted the file into three tiers, and the sorting is
  the interesting part:

  | bench witness | here | tier |
  |---|---|---|
  | the seeded shape, the subs, narrow/broad writes, keyed insert/delete, drafts | unchanged, and needing nothing from the kit | L0 |
  | a lowered intent reaches a real event handler | `ht/fire!` | L1 |
  | the controlled field carries its value through the marker | `ht/fire!` + `ht/controlled?` | L1 |
  | the form submits once and prevents navigation | `ht/fire!` | L1 |
  | the key-map commits, cancels, and is silent mid-composition | `ht/fire!` | L1 |
  | what the rendered screen OFFERS to dispatch | `ht/tree` + `ht/intents` | L2 |

  The L0 rows needed no kit at all, which is L0's contract holding: they
  are pure re-frame and the substrate is not in them. The composition
  rows needed exactly one door, and the tree row is a claim the bench
  file could not make at all."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.test :as ht]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::dogfood)
(def ^:private new-draft-key ::new)

;; ---------------------------------------------------------------------------
;; The state layer — the shape the screen is written against
;; ---------------------------------------------------------------------------

(defn- seed-db [n]
  {:todos   (into {} (map (fn [i] [i {:id i :title (str "todo " i) :done? false}]))
                  (range n))
   :order   (vec (range n))
   :drafts  {}
   :filter  :all
   :next-id n})

(rf/reg-sub :dg/filter      (fn [db _] (:filter db)))
(rf/reg-sub :dg/todo        (fn [db [_ id]] (get-in db [:todos id])))
(rf/reg-sub :dg/done?       (fn [db [_ id]] (boolean (get-in db [:todos id :done?]))))
(rf/reg-sub :dg/draft       (fn [db [_ k]] (get-in db [:drafts k] "")))
(rf/reg-sub :dg/remaining   (fn [db _] (count (remove :done? (vals (:todos db))))))
(rf/reg-sub :dg/visible-ids
            (fn [db _]
              (let [keep? (case (:filter db)
                            :all    (constantly true)
                            :active (fn [id] (not (get-in db [:todos id :done?])))
                            :done   (fn [id] (get-in db [:todos id :done?])))]
                (filterv keep? (:order db)))))

(rf/reg-event :dg/seed       (fn [_ [_ n]] {:db (seed-db n)}))
(rf/reg-event :dg/toggle     (fn [{:keys [db]} [_ id]]
                               {:db (update-in db [:todos id :done?] not)}))
(rf/reg-event :dg/set-filter (fn [{:keys [db]} [_ f]] {:db (assoc db :filter f)}))
(rf/reg-event :dg/edit-draft (fn [{:keys [db]} [_ k v]] {:db (assoc-in db [:drafts k] v)}))
(rf/reg-event :dg/cancel     (fn [{:keys [db]} [_ k]] {:db (update db :drafts dissoc k)}))
(rf/reg-event :dg/remove     (fn [{:keys [db]} [_ id]]
                               {:db (-> db
                                        (update :todos dissoc id)
                                        (update :order #(filterv (complement #{id}) %))
                                        (update :drafts dissoc id))}))
(rf/reg-event :dg/commit
              (fn [{:keys [db]} [_ id]]
                (let [draft (get-in db [:drafts id] "")]
                  {:db (cond-> db
                         (seq draft) (assoc-in [:todos id :title] draft)
                         :always     (update :drafts dissoc id))})))
(rf/reg-event :dg/create
              (fn [{:keys [db]} _]
                (let [draft (get-in db [:drafts new-draft-key] "")]
                  (if (empty? draft)
                    {:db db}
                    (let [id (:next-id db)]
                      {:db (-> db
                               (assoc-in [:todos id] {:id id :title draft :done? false})
                               (update :order conj id)
                               (update :drafts dissoc new-draft-key)
                               (assoc :next-id (inc id)))})))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (collector/reset-runtime!))}))

(defn- seeded!
  ([] (seeded! 3))
  ([n]
   (rf/make-frame {:id frame-id})
   (rf/with-frame frame-id (rf/dispatch-sync [:dg/seed n]))
   frame-id))

(defn- read-sub [query] (rf/with-frame frame-id (deref (rf/subscribe query))))
(defn- send!    [event] (rf/with-frame frame-id (rf/dispatch-sync event)) nil)

;; ---------------------------------------------------------------------------
;; L0 — the state layer, needing nothing from the kit
;; ---------------------------------------------------------------------------
;;
;; Bench counterparts: the-seeded-shape…, the-subscriptions-read-what-the-
;; screen-needs, the-narrow-write…, the-broad-write…, keyed-insert-delete-
;; and-reorder…, one-parametric-sub-and-named-events…, an-empty-draft-
;; creates-and-commits-nothing.
;;
;; They are reproduced with `rf/dispatch-sync` and `rf/subscribe` and no
;; `ht/` call anywhere, which is L0's contract *demonstrated* rather than
;; asserted: a substrate's test kit that had to be involved in a pure
;; state test would be a kit that had leaked into the state layer.

(deftest l0-the-state-layer-behaves-with-no-view-substrate-in-sight
  (seeded! 3)
  (testing "the seeded shape"
    (is (= [0 1 2] (read-sub [:dg/visible-ids])))
    (is (= 3 (read-sub [:dg/remaining])))
    (is (= "todo 1" (:title (read-sub [:dg/todo 1]))))
    (is (= "" (read-sub [:dg/draft 1])) "an untouched draft reads as empty, not nil"))

  (testing "the narrow write touches one row and the header"
    (send! [:dg/toggle 1])
    (is (true?  (read-sub [:dg/done? 1])))
    (is (false? (read-sub [:dg/done? 0])))
    (is (= 2 (read-sub [:dg/remaining])))
    (is (= [0 1 2] (read-sub [:dg/visible-ids])) "under :all the list is unchanged"))

  (testing "the broad write rebuilds the list, in all three branches"
    (send! [:dg/set-filter :active])
    (is (= [0 2] (read-sub [:dg/visible-ids])))
    (send! [:dg/set-filter :done])
    (is (= [1] (read-sub [:dg/visible-ids])))
    (send! [:dg/set-filter :all])
    (is (= [0 1 2] (read-sub [:dg/visible-ids]))))

  (testing "one parametric sub serves every draft instance, and commit is by
            explicit caller action rather than by value equality"
    (send! [:dg/edit-draft 0 "zero"])
    (send! [:dg/edit-draft new-draft-key "new"])
    (is (= "zero" (read-sub [:dg/draft 0])))
    (is (= "new"  (read-sub [:dg/draft new-draft-key])))
    (is (= ""     (read-sub [:dg/draft 1])) "an instance nobody typed into")
    (send! [:dg/commit 0])
    (is (= "zero" (:title (read-sub [:dg/todo 0]))))
    (is (= "" (read-sub [:dg/draft 0])) "and only then is the draft cleared"))

  (testing "insert appends and mints the next id; an EMPTY draft creates
            nothing, which is the row that keeps the one above honest"
    ;; The block above typed into the new-item draft; discard it, so what
    ;; the empty-draft row measures is the empty draft and not the residue
    ;; of the row before it.
    (send! [:dg/cancel new-draft-key])
    (send! [:dg/create])
    (is (= [0 1 2] (read-sub [:dg/visible-ids])))
    (send! [:dg/edit-draft new-draft-key "milk"])
    (send! [:dg/create])
    (is (= [0 1 2 3] (read-sub [:dg/visible-ids])))
    (is (= "milk" (:title (read-sub [:dg/todo 3])))))

  (testing "delete removes the row and its draft"
    (send! [:dg/edit-draft 1 "half-typed"])
    (send! [:dg/remove 1])
    (is (= [0 2 3] (read-sub [:dg/visible-ids])))
    (is (nil? (read-sub [:dg/todo 1])))
    (is (= "" (read-sub [:dg/draft 1])))))

;; ---------------------------------------------------------------------------
;; L1 — the composition, on ht/fire! alone
;; ---------------------------------------------------------------------------
;;
;; Bench counterparts: a-lowered-intent-reaches-a-real-event-handler,
;; the-controlled-field-carries-its-value-through-the-marker,
;; the-form-submits-once-and-prevents-the-browsers-navigation,
;; the-key-map-commits-on-enter-cancels-on-escape-and-is-silent-mid-
;; composition.
;;
;; Each bench row lowered a form with `codec/as-element` and pulled the
;; wrapper off `(.-props el)`. Each row here calls one public door.

(deftest l1-a-lowered-intent-reaches-a-real-event-handler
  (seeded! 3)
  (is (false? (read-sub [:dg/done? 1])))
  (let [{:keys [intents prevented?]}
        (ht/fire! frame-id [:button {:on-click [:dg/toggle 1]} "toggle"] :on-click {})]

    (testing "the intent the position dispatched, as the vector itself"
      (is (= [[:dg/toggle 1]] intents)))

    (testing "and it reached a real handler: app-db moved"
      (is (true? (read-sub [:dg/done? 1])))
      (is (= 2 (read-sub [:dg/remaining]))))

    (testing "a click is not a submit, so nothing was prevented — the
              control that keeps the prevent row below from being green
              for a door that prevents everything"
      (is (false? prevented?)))))

(deftest l1-the-controlled-field-carries-its-value-through-the-marker
  (seeded! 3)
  (let [field [:input {:type      "text"
                       :value     (read-sub [:dg/draft new-draft-key])
                       :on-input  [:dg/edit-draft new-draft-key
                                   :re-frame.hicasso/value]}]]

    (testing "the form IS the controlled door — the runtime's own selection"
      (is (true? (ht/controlled? field)))
      (is (= "" (get (ht/element-props field) "value"))))

    (testing "the marker is substituted from the event's own target, and the
              substituted intent is what reached the handler"
      (is (= {:intents [[:dg/edit-draft new-draft-key "mi"]] :prevented? false}
             (ht/fire! frame-id field :on-input {:value "mi"})))
      (is (= "mi" (read-sub [:dg/draft new-draft-key])))
      (is (= {:intents [[:dg/edit-draft new-draft-key "milk"]] :prevented? false}
             (ht/fire! frame-id field :on-input {:value "milk"})))
      (is (= "milk" (read-sub [:dg/draft new-draft-key]))))

    (testing "and the same substitution read as pure data agrees with what
              the fired handler dispatched — two doors, one law"
      (is (= [:dg/edit-draft new-draft-key "milk"]
             (ht/materialize [:dg/edit-draft new-draft-key :re-frame.hicasso/value]
                             {:value "milk"}))))))

(deftest l1-the-form-submits-once-and-prevents-the-browsers-navigation
  (seeded! 3)
  (send! [:dg/edit-draft new-draft-key "milk"])
  (let [{:keys [intents prevented?]}
        (ht/fire! frame-id [:form {:on-submit [:dg/create]}] :on-submit {})]

    (testing "the default action is prevented, by policy and without the
              author writing `::h/prevent` — `on-submit` carries it"
      (is (true? prevented?)))

    (testing "and the create ran exactly once"
      (is (= [[:dg/create]] intents))
      (is (= [0 1 2 3] (read-sub [:dg/visible-ids])))
      (is (= "milk" (:title (read-sub [:dg/todo 3]))))
      (is (= "" (read-sub [:dg/draft new-draft-key]))))))

(deftest l1-the-key-map-commits-cancels-and-is-silent-mid-composition
  (seeded! 3)
  (send! [:dg/edit-draft 1 "renamed"])
  (let [field [:input {:on-key-down {"Enter"  [:dg/commit 1]
                                     "Escape" [:dg/cancel 1]}}]]

    (testing "a composing Enter commits nothing — the modern signal
              (`isComposing`) and the legacy one (keyCode 229) both, and the
              expectation for both is SILENCE, which is a claim about the
              intents and not only about app-db"
      (is (= {:intents [] :prevented? false}
             (ht/fire! frame-id field :on-key-down
                       {:key "Enter" :composing? true :key-code 13})))
      (is (= {:intents [] :prevented? false}
             (ht/fire! frame-id field :on-key-down
                       {:key "Enter" :composing? false :key-code 229})))
      (is (= "todo 1" (:title (read-sub [:dg/todo 1]))))
      (is (= "renamed" (read-sub [:dg/draft 1])) "and the draft survives"))

    (testing "a settled Enter commits — the control that says the two rows
              above are silence rather than a dead handler"
      (is (= [[:dg/commit 1]]
             (:intents (ht/fire! frame-id field :on-key-down
                                 {:key "Enter" :composing? false :key-code 13}))))
      (is (= "renamed" (:title (read-sub [:dg/todo 1])))))

    (testing "Escape takes the map's other branch"
      (send! [:dg/edit-draft 1 "second thoughts"])
      (is (= [[:dg/cancel 1]]
             (:intents (ht/fire! frame-id field :on-key-down
                                 {:key "Escape" :composing? false :key-code 27}))))
      (is (= "" (read-sub [:dg/draft 1])))
      (is (= "renamed" (:title (read-sub [:dg/todo 1])))))

    (testing "and a key the map does not name dispatches nothing, so a
              key-map is not a catch-all wearing a lookup"
      (is (= [] (:intents (ht/fire! frame-id field :on-key-down {:key "a"})))))))

(deftest l1-a-position-the-form-does-not-write-refuses
  (testing "a silently absent handler is the fault this door exists to make
            loud, so naming one refuses rather than answering no intents —
            which would be indistinguishable from a handler that fired and
            dispatched nothing"
    (let [refused (try (ht/fire! frame-id [:button {:on-click [:dg/toggle 1]}]
                                 :on-double-click {})
                       nil
                       (catch :default e (ex-data e)))]
      (is (= {:rf.error/id :rf.error/hicasso-test-no-handler-at-position
              :where       're-frame.hicasso.test
              :recovery    :name-a-position-the-form-writes
              :position    :on-double-click}
             (select-keys refused [:rf.error/id :where :recovery :position]))))))

;; ---------------------------------------------------------------------------
;; L2 — what the rendered screen OFFERS to dispatch
;; ---------------------------------------------------------------------------
;;
;; No bench counterpart: the front file could not make this claim, because
;; there was no tree to make it against. It is the claim the intent script
;; makes in a browser — *these are the intents this page carries* — taken
;; on the rendering instead of on the interaction, and the two are
;; deliberately different evidence. A site can exist and never fire; a
;; fired intent can carry a materialized value no tree can hold.

(defn- row-body
  [{:keys [id]}]
  (let [todo  (h/sub [:dg/todo id])
        draft (h/sub [:dg/draft id])]
    [:li.row {:data-id id}
     [:input.toggle {:type      "checkbox"
                     :checked   (:done? todo)
                     :on-change [:dg/toggle id]}]
     [:span.title (:title todo)]
     [:input.draft {:value       draft
                    :on-input    [:dg/edit-draft id :re-frame.hicasso/value]
                    :on-key-down {"Enter"  [:dg/commit id]
                                  "Escape" [:dg/cancel id]}}]
     [:button.remove {:on-click [:dg/remove id]} "×"]]))

(defn- filters-body
  [_]
  (let [current (h/sub [:dg/filter])]
    [:nav.filters
     (for [f [:all :active :done]]
       [:button.filter {:key      f
                        :data-filter f
                        :class    (when (= f current) "on")
                        :on-click [:dg/set-filter f]}
        (name f)])]))

(deftest l2-the-rendered-row-carries-its-intents-as-data
  (let [tree (ht/tree [row-body {:id 1}]
                        {:subs {[:dg/todo 1]  {:id 1 :title "todo 1" :done? false}
                                 [:dg/draft 1] ""}})]

    (testing "every handler site the row offers, in document order, against a
              STATED expectation — comparing one rendering to another is
              green for a drift they share; comparing to a written-out
              vector is not"
      (is (= [[:dg/toggle 1]
              [:dg/edit-draft 1 :re-frame.hicasso/value]
              [:dg/commit 1]
              [:dg/cancel 1]
              [:dg/remove 1]]
             (ht/intents tree))))

    (testing "the marker is retained as the authored keyword rather than
              resolved — a tree cannot know what a target will carry, and
              writing a value there would be the tree claiming one"
      (is (= [:dg/edit-draft 1 :re-frame.hicasso/value]
             (:on-input (ht/attrs (ht/find tree #(= "draft" (:class (:attrs %)))))))))

    (testing "the read values reached the markup"
      (is (= "todo 1×" (ht/text tree)))
      (is (false? (:checked (ht/attrs (ht/find tree #(= "toggle" (:class (:attrs %)))))))))

    (testing "and the control: the same body with a DONE to-do renders the
              other value, so the row above is reading the fixture"
      (is (true? (:checked (ht/attrs (ht/find (ht/tree [row-body {:id 1}]
                                                         {:subs {[:dg/todo 1] {:done? true}
                                                                  [:dg/draft 1] ""}})
                                              #(= "toggle" (:class (:attrs %)))))))))))

(deftest l2-a-dynamic-child-list-renders-inside-the-body-s-own-window
  (let [tree (ht/tree [filters-body {}] {:subs {[:dg/filter] :active}})]

    (testing "a `for` inside the body splices its members as children — the
              runtime's one-level flatten, not a second rule"
      (is (= 3 (count (ht/find-all tree #(= :button (:tag %))))))
      (is (= "allactivedone" (ht/text tree))))

    (testing "each member keeps the key it was written with"
      (is (= [:all :active :done]
             (mapv :key (ht/find-all tree #(= :button (:tag %)))))))

    (testing "and the read taken INSIDE the loop is the read the fixture
              answered — which is what says the walk happened inside the
              body's own render window rather than after it"
      (is (= "filter on"
             (:class (ht/attrs (ht/find tree #(= :active (:data-filter (:attrs %))))))))
      (is (= "filter"
             (:class (ht/attrs (ht/find tree #(= :all (:data-filter (:attrs %))))))))
      (is (= [[:dg/set-filter :all] [:dg/set-filter :active] [:dg/set-filter :done]]
             (ht/intents tree))))))
