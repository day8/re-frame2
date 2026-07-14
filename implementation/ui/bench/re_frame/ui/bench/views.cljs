(ns re-frame.ui.bench.views
  "G-1 bench components, COMPILED by re-frame.ui/defview — the same four
  shapes the S-1 spike measured (comparability of numbers):

    static-tree   fully static subtree (hoists to one module constant)
    counter       props + branches + capture-free/capturing/placeholder
                  handlers + keyed identical rows
    todo-list     20-row keyed `for` over an internal view call
    status-panel  conditional multi-branch (cond)"
  (:require [re-frame.ui :as ui :refer [defview]]))

(defview static-tree
  "Fully static tree — the whole subtree should hoist to one module constant."
  []
  [:div#about.panel {:style {:padding 16 :border-top "1px solid #ccc"}}
   [:h2.title "About"]
   [:p "A fully static subtree, " [:em "hoisted"] " to a module constant."]
   [:ul.links
    [:li [:a {:href "/docs" :title "Docs & guides"} "Docs"]]
    [:li [:a {:href "/api"} "API"]]
    [:li [:a {:href "/blog" :data-section "blog"} "Blog"]]]
   [:footer {:aria-label "footer" :tab-index 0} "(c) 2026 <re-frame2> & friends"]])

(defview counter
  "Counter: dynamic props, event vectors (captured + capture-free +
  placeholder), branches, and a keyed row of identical static content
  (prebuilt-static-props-object case)."
  [{:keys [n step locked?]}]
  [:div.counter
   [:h1 "Count: " n]
   [:div.controls
    [:button.btn {:on-click [:counter/inc step] :disabled locked?} "+"]
    [:button.btn {:on-click [:counter/dec step] :disabled locked?} "-"]
    [:button.btn.reset {:on-click [:counter/reset]} "Reset"]]
   [:input {:type :text :value (str n) :on-input [:counter/set :rf.ui/value]}]
   [:div.stars
    (for [i (range (min 5 (max 0 n)))]
      [:span.star {:key i} "*"])]
   (when locked? [:p.warn "Locked"])
   (if (neg? n) [:p.neg "negative"] [:p.pos "non-negative"])])

(defview todo-row
  "One row: class flag-map, dynamic keyword attr, boolean aria attr,
  checkbox with :rf.ui/checked placeholder capturing a prop-derived local."
  [{:keys [todo]}]
  (let [{:keys [id label done? priority]} todo]
    [:li.todo-item {:class {:done done?} :data-priority priority :aria-hidden false}
     [:input {:type :checkbox :checked done? :on-change [:todo/toggle id :rf.ui/checked]}]
     [:span.label label]
     (when (= priority :high) [:span.flag "HIGH"])]))

(defview todo-list
  "Keyed for over an internal view call with a props map."
  [{:keys [title todos]}]
  [:section.todos
   [:h2 title]
   [:ul.todo-ul
    (for [t todos]
      [todo-row {:key (:id t) :todo t}])]
   [:p.count (count todos) " items"]])

;; ---------------------------------------------------------------------------
;; G-1 lowering-regression control fixture (rf2-vxgfnd.206)
;;
;; A REAL compiled emit of the todo row whose ONLY difference from `todo-row`
;; is the `:class` lowering: writing the class as a dynamic expression over the
;; static base drives the compiler down the GENERIC `classes-str`/`class-val`
;; vector-join path (emit-cljs `class-form` `:else`) instead of the single-flag
;; straight-line specialization (`(if done? "todo-item done" "todo-item")`).
;; Output is byte-identical, so byte-equality still holds; the extra generic
;; join work per keyed row is the exact regression the specialization comment
;; (emit-cljs `class-form`) says it avoids. The G-1 mutation control benches
;; `todo-list-generic-class` against the SAME memo-matched hand baseline the
;; todos-20 gate uses, so it reddens for a genuine class-property lowering
;; regression — no side loop, no volatile sink, no second traversal.
(defview todo-row-generic-class
  "todo-row with the class-property lowered through the generic classes-str
  path (dynamic `:class` expr over the `.todo-item` static base) rather than
  the single-flag straight-line specialization. Byte-identical output."
  [{:keys [todo]}]
  (let [{:keys [id label done? priority]} todo]
    [:li.todo-item {:class (when done? "done") :data-priority priority :aria-hidden false}
     [:input {:type :checkbox :checked done? :on-change [:todo/toggle id :rf.ui/checked]}]
     [:span.label label]
     (when (= priority :high) [:span.flag "HIGH"])]))

(defview todo-list-generic-class
  "todos-20 list built from generic-class rows — the G-1 lowering-regression
  control candidate. Byte-identical to `todo-list`; only the row class-property
  lowering differs."
  [{:keys [title todos]}]
  [:section.todos
   [:h2 title]
   [:ul.todo-ul
    (for [t todos]
      [todo-row-generic-class {:key (:id t) :todo t}])]
   [:p.count (count todos) " items"]])

(defview status-panel
  "Conditional multi-branch view (cond + when + if)."
  [{:keys [state message retries]}]
  [:div.status
   (cond
     (= state :loading) [:div.spinner {:role :status :aria-label "loading"} "Loading..."]
     (= state :error)   [:div.error
                         [:strong "Error: "]
                         message
                         (when (pos? retries) [:span.retries "(retry " retries ")"])
                         [:button {:on-click [:status/retry]} "Retry"]]
     (= state :empty)   [:div.empty "Nothing here yet."]
     :else              [:div.ok [:span.msg message]])
   (if (= state :error) [:hr] nil)])
