(ns todomvc.views
  "The TodoMVC markup, written as hiccup.

  A few things on show. `reg-view` for the root view — inside its body
  `subscribe` and `dispatch` are simply in scope, no ceremony. Plain helper fns
  for the sub-views, which take `dispatch`/`subscribe` as explicit args, because
  that's the honest shape for an ordinary function. And hiccup throughout, which
  is really just markup-as-data. The job of a view is small and strict: read
  derived state, dispatch events when the user does something. No business logic
  sneaks in here. See docs/guide/glossary.md (view)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn- hash-for-filter [filter-kw]
  (case filter-kw
    :active "#/active"
    :completed "#/completed"
    "#/"))

;; A CONTROLLED text input — the workhorse behind both the header box and the
;; edit-in-place box. `:value` comes from a draft sub, and every keystroke
;; dispatches `on-change` to write that draft into app-db. The input itself
;; never holds the text; app-db does. Enter commits (`on-commit`), Escape
;; cancels (`on-cancel`), and losing focus commits too. There's a subtle bit
;; here: cancelling unmounts the input, so the blur that follows finds nothing
;; left to save. Tidy by accident, and on purpose.
;;
;; `:autofocus?` drops the cursor into the input the moment it mounts — exactly
;; what the edit box wants, since the row just flipped into edit mode. A `:ref`
;; callback calls `.focus()` on the real DOM node. It touches focus and nothing
;; else; `:value` stays bound to the sub.
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
         ;; Focus-only ref: nudge the cursor into the just-mounted input.
         {:ref (fn [node] (when node (.focus node)))}))]))

(defn todo-item [dispatch subscribe {:keys [id title completed]}]
  ;; A plain form-1 fn — nothing fancy. The per-row editing flag lives in app-db,
  ;; so each row just asks a sub keyed by its own id: "am I the one being edited?"
  (let [editing? @(subscribe [:todo.ui/editing? id])]
    [:li {:class (str/join " " (cond-> []
                                 completed (conj "completed")
                                 editing?  (conj "editing")))}
     [:div.view
      ;; A controlled checkbox, re-frame2 style: `:checked` reads the fact out
      ;; of app-db, and `:on-click` dispatches the event that changes it. Since
      ;; the state takes the long way round through app-db, we set `:readOnly` to
      ;; hush React's onChange warning about a checkbox it doesn't get to drive
      ;; itself.
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
     ;; The header input isn't editing anything, so there's nothing to cancel —
     ;; Escape just empties the draft.
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
;; plain Reagent helper fns. They take `dispatch` / `subscribe` as explicit args
;; because that's the clearest shape for an internal helper — what it needs is
;; right there in the signature. `reg-view` is reserved for the root: inside its
;; body `dispatch` and `subscribe` arrive in scope for free, which is exactly why
;; the root is the one threading them down to the helpers below.
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
