(ns todomvc.views
  "The TodoMVC markup, as hiccup.

  Demonstrates: `reg-view` for the root (inside its body `subscribe` and
  `dispatch` are already in scope), plain helper fns for the sub-views (which
  take `dispatch`/`subscribe` as explicit args — the honest shape for a plain
  fn), and hiccup as data-as-markup. A view reads derived state and dispatches
  events on interaction; no business logic lives here.
  See docs/guide/glossary.md (view)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn- hash-for-filter [filter-kw]
  (case filter-kw
    :active "#/active"
    :completed "#/completed"
    "#/"))

;; A CONTROLLED text input. `:value` reads a draft sub. Every keystroke
;; dispatches `on-change`, which writes the draft into app-db; the input never
;; holds its own value. Enter commits (dispatches `on-commit`), Escape cancels
;; (dispatches `on-cancel`), and blur commits. Cancel renders the input away, so
;; a trailing blur has nothing left to save.
;;
;; `:autofocus?` focuses the input when it first mounts — the edit input wants
;; this, since the row just entered edit mode. A `:ref` callback calls `.focus()`
;; on the live node. It touches focus only, leaving `:value` bound to the sub.
(defn todo-input [{:keys [draft on-change on-commit on-cancel autofocus?] :as props}]
  (let [handle-keydown
        (fn [event]
          (case (.-key event)
            "Enter"  (do (.preventDefault event) (on-commit))
            "Escape" (do (.preventDefault event) (on-cancel))
            nil))]
    [:input
     (merge
       (dissoc props :draft :on-change :on-commit :on-cancel :autofocus?)
       {:type        "text"
        :value       (or draft "")
        :on-change   (fn [e] (on-change (.. e -target -value)))
        :on-key-down handle-keydown
        :on-blur     (fn [_] (on-commit))}
       (when autofocus?
         ;; Focus-only ref: move the cursor into the freshly-mounted input.
         {:ref (fn [node] (when node (.focus node)))}))]))

(defn todo-item [dispatch subscribe {:keys [id title completed]}]
  ;; A plain form-1 fn. The per-row editing flag lives in app-db, so the row just
  ;; reads "am I the editing row?" from a sub keyed by its id.
  (let [editing? @(subscribe [:todo.ui/editing? id])]
    [:li {:class (str/join " " (cond-> []
                                 completed (conj "completed")
                                 editing?  (conj "editing")))}
     [:div.view
      ;; Controlled checkbox, re-frame2 style: `:checked` reads the fact from
      ;; app-db and `:on-click` dispatches the event that changes it. The state
      ;; round-trips through app-db, so `:readOnly` is set to silence React's
      ;; onChange warning for a checkbox React doesn't itself control.
      [:input.toggle
       {:type "checkbox"
        :checked completed
        :readOnly true
        :on-click #(dispatch [:todo/toggle-completed id])}]
      [:label {:on-double-click #(dispatch [:todo.ui/start-edit id])}
       title]
      [:button.destroy
       {:on-click #(dispatch [:todo/delete id])}]]
     (when editing?
       [todo-input
        {:class       "edit"
         :draft       @(subscribe [:todo.ui/draft :edit])
         :autofocus?  true
         :on-change   #(dispatch [:todo.ui/edit-field :edit %])
         :on-commit   #(dispatch [:todo.ui/commit-edit])
         :on-cancel   #(dispatch [:todo.ui/stop-edit])}])]))

(defn task-entry [dispatch subscribe]
  [:header.header
   [:h1 "todos"]
   [todo-input
    {:id          "new-todo"
     :class       "new-todo"
     :placeholder "What needs to be done?"
     :draft       @(subscribe [:todo.ui/draft :new])
     :on-change   #(dispatch [:todo.ui/edit-field :new %])
     :on-commit   #(dispatch [:todo.ui/commit-new])
     ;; The header input has nothing to cancel — Escape just clears the draft.
     :on-cancel   #(dispatch [:todo.ui/edit-field :new ""])}]])

(defn task-list [dispatch subscribe]
  [:section.main {:id "main"}
   [:input#toggle-all.toggle-all
    {:type "checkbox"
     :checked @(subscribe [:todo/all-complete?])
     :readOnly true
     :on-click #(dispatch [:todo/toggle-all])}]
   [:label {:for "toggle-all"} "Mark all as complete"]
   [:ul.todo-list {:id "todo-list"}
    (for [{:keys [id] :as todo} @(subscribe [:todo/visible-todos])]
      ^{:key id}
      [todo-item dispatch subscribe todo])]])

(defn- filter-link [showing filter-kw label]
  [:a {:href (hash-for-filter filter-kw)
       :class (when (= showing filter-kw) "selected")}
   label])

(defn footer-controls [dispatch subscribe]
  (let [[active completed] @(subscribe [:todo/footer-counts])
        showing @(subscribe [:todo/showing])]
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

;; The sub-views above (task-entry, task-list, todo-item, footer-controls) are
;; plain Reagent helper fns that take `dispatch` / `subscribe` as explicit args —
;; the clearest shape for internal helpers. `reg-view` is for the root: inside
;; its body `dispatch` and `subscribe` are injected into scope, which is why the
;; root threads them down to the helpers.
;; See docs/guide/concepts/views.md.
(reg-view root-view []
  (let [todos @(subscribe [:todo/todos])]
    [:<>
     [:section.todoapp
      [task-entry dispatch subscribe]
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
