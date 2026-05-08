(ns todomvc.views
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [reagent.core :as reagent]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(def enter-key "Enter")
(def escape-key "Escape")

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
          (let [key (.-key event)]
            (cond
              (= key enter-key)
              (do (.preventDefault event)
                  (save))

              (= key escape-key)
              (do (.preventDefault event)
                  (reset! suppress-blur true)
                  (stop))

              :else nil)))
        bind-node
        (fn [node]
          (when-let [prev @input-node]
            (when-let [handler (gobj/get prev "__todomvcKeyHandler")]
              (.removeEventListener prev "keydown" handler)))
          (reset! input-node node)
          (when node
            (.addEventListener node "keydown" handle-keydown)
            (gobj/set node "__todomvcKeyHandler" handle-keydown)))]
    (fn [props]
      [:input
       (merge
         (dissoc props :title :on-save :on-stop)
         {:type "text"
          :default-value (or title "")
          :autoFocus true
          :ref bind-node
          :on-blur
          (fn [_]
            (if @suppress-blur
              (reset! suppress-blur false)
              (save)))})])))

(defn todo-item []
  (let [editing? (reagent/atom false)]
    (fn [{:keys [id title completed]}]
      [:li {:class (str/join " " (cond-> []
                                 completed (conj "completed")
                                 @editing? (conj "editing")))}
       [:div.view
        [:input.toggle
         {:type "checkbox"
          :checked completed
          :readOnly true
          :on-click #(rf/dispatch [:todo/toggle-completed id])}]
        [:label {:on-double-click #(reset! editing? true)}
         title]
        [:button.destroy
         {:on-click #(rf/dispatch [:todo/delete id])}]]
       (when @editing?
         [todo-input
          {:class "edit"
           :title title
           :on-save #(rf/dispatch [:todo/save id %])
           :on-stop #(reset! editing? false)}])])))

(defn task-entry []
  [:header.header
   [:h1 "todos"]
   [todo-input
    {:id "new-todo"
     :class "new-todo"
     :placeholder "What needs to be done?"
     :on-save #(rf/dispatch [:todo/add %])}]])

(defn task-list []
  (let [s (rf/subscriber)]
    [:section.main {:id "main"}
     [:input#toggle-all.toggle-all
      {:type "checkbox"
       :checked @(s [:all-complete?])
       :readOnly true
       :on-click #(rf/dispatch [:todo/toggle-all])}]
     [:label {:for "toggle-all"} "Mark all as complete"]
     [:ul.todo-list {:id "todo-list"}
      (for [{:keys [id] :as todo} @(s [:visible-todos])]
        ^{:key id}
        [todo-item todo])]]))

(defn- filter-link [showing filter-kw label]
  [:a {:href (hash-for-filter filter-kw)
       :class (when (= showing filter-kw) "selected")}
   label])

(defn footer-controls []
  (let [s (rf/subscriber)
        [active completed] @(s [:footer-counts])
        showing @(s [:showing])]
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
         :on-click #(rf/dispatch [:todo/clear-completed])}
        "Clear completed"])]))

(def root-view
  (reg-view :todo.app/root-view
    (fn render-root-view []
      (let [s     (rf/subscriber)
            todos @(s [:todos])]
        [:<>
         [:section.todoapp
          [task-entry]
          (when (seq todos)
            [task-list])
          (when (seq todos)
            [footer-controls])]
         [:footer.info
          [:p "Double-click to edit a todo"]
          [:p
           "Inspired by "
           [:a {:href "https://github.com/day8/re-frame/tree/master/examples/todomvc"}
            "the original re-frame TodoMVC example"]]
          [:p
           "Part of "
           [:a {:href "https://todomvc.com/"} "TodoMVC"]]]]))))
