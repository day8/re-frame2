(ns todomvc.views
  (:require [clojure.string :as str]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(defn- hash-for-filter [filter-kw]
  (case filter-kw
    :active "#/active"
    :completed "#/completed"
    "#/"))

(defn todo-input [{:keys [title on-save on-stop]}]
  (let [input-node    (atom nil)
        suppress-blur (atom false)
        stop          #(do
                         (when-let [node @input-node]
                           (set! (.-value node) ""))
                         (when on-stop (on-stop)))
        save          #(when-let [node @input-node]
                         (on-save (.-value node))
                         (stop))
        handle-keydown
        (fn [event]
          (case (.-key event)
            "Enter"  (do (.preventDefault event) (save))
            "Escape" (do (.preventDefault event)
                         (reset! suppress-blur true)
                         (stop))
            nil))]
    (fn [props]
      [:input
       (merge
         (dissoc props :title :on-save :on-stop)
         {:type "text"
          :default-value (or title "")
          :autoFocus true
          :ref #(reset! input-node %)
          :on-key-down handle-keydown
          :on-blur
          (fn [_]
            (if @suppress-blur
              (reset! suppress-blur false)
              (save)))})])))

(defn todo-item [dispatch]
  ;; editing? is local component state by design — matches v1 TodoMVC; not in app-db so it isn't persisted/inspected (TodoMVC tradeoff).
  (let [editing? (reagent/atom false)]
    (fn [_dispatch {:keys [id title completed]}]
      [:li {:class (str/join " " (cond-> []
                                 completed (conj "completed")
                                 @editing? (conj "editing")))}
       [:div.view
        ;; React's controlled-checkbox pattern: :on-click mutates app-db, :readOnly silences React's onChange warning, :checked reads from app-db.
        [:input.toggle
         {:type "checkbox"
          :checked completed
          :readOnly true
          :on-click #(dispatch [:todo/toggle-completed id])}]
        [:label {:on-double-click #(reset! editing? true)}
         title]
        [:button.destroy
         {:on-click #(dispatch [:todo/delete id])}]]
       (when @editing?
         [todo-input
          {:class "edit"
           :title title
           :on-save #(dispatch [:todo/save id %])
           :on-stop #(reset! editing? false)}])])))

(defn task-entry [dispatch]
  [:header.header
   [:h1 "todos"]
   [todo-input
    {:id "new-todo"
     :class "new-todo"
     :placeholder "What needs to be done?"
     :on-save #(dispatch [:todo/add %])}]])

(defn task-list [dispatch subscribe]
  [:section.main {:id "main"}
   [:input#toggle-all.toggle-all
    {:type "checkbox"
     :checked @(subscribe [:all-complete?])
     :readOnly true
     :on-click #(dispatch [:todo/toggle-all])}]
   [:label {:for "toggle-all"} "Mark all as complete"]
   [:ul.todo-list {:id "todo-list"}
    (for [{:keys [id] :as todo} @(subscribe [:visible-todos])]
      ^{:key id}
      [todo-item dispatch todo])]])

(defn- filter-link [showing filter-kw label]
  [:a {:href (hash-for-filter filter-kw)
       :class (when (= showing filter-kw) "selected")}
   label])

(defn footer-controls [dispatch subscribe]
  (let [[active completed] @(subscribe [:footer-counts])
        showing @(subscribe [:showing])]
    [:footer.footer {:id "footer"}
     [:span.todo-count {:id "todo-count"}
      [:strong active]
      " "
      (if (= active 1) "item" "items")
      " left"]
     [:ul.filters {:id "filters"}
      [:li (filter-link showing :all "All")]
      [:li (filter-link showing :active "Active")]
      [:li (filter-link showing :completed "Completed")]]
     (when (pos? completed)
       [:button.clear-completed
        {:id "clear-completed"
         :on-click #(dispatch [:todo/clear-completed])}
        "Clear completed"])]))

;; Sub-views (task-entry, task-list, todo-item, footer-controls) above
;; are plain Reagent fns. Per Spec 004 §reg-view auto-inject, the
;; capture-and-pass threading PR #51 introduced is no longer needed —
;; sub-views can read `dispatch` / `subscribe` directly from the
;; surrounding reg-view scope. We keep them as plain fns here (rather
;; than reg-view'ing each sub-piece) because they're internal helpers
;; with no need for their own registry slot or auto-defed Var; that
;; gives the cleanest read in this example.
(reg-view root-view []
  (let [todos @(subscribe [:todos])]
    [:<>
     [:section.todoapp
      [task-entry dispatch]
      (when (seq todos)
        [task-list dispatch subscribe])
      (when (seq todos)
        [footer-controls dispatch subscribe])]
     [:footer.info
      [:p "Double-click to edit a todo"]
      [:p
       "Inspired by "
       [:a {:href "https://github.com/day8/re-frame/tree/master/examples/todomvc"}
        "the original re-frame TodoMVC example"]]
      [:p
       "Part of "
       [:a {:href "https://todomvc.com/"} "TodoMVC"]]]]))
