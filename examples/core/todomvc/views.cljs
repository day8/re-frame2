(ns todomvc.views
  "The TodoMVC markup, written as hiccup.

  One authoring rule runs through this file, and it's the re-frame2 house rule:
  a view that touches state — that `subscribe`s or `dispatch`es — is a
  `reg-view`, so the frame-bound `dispatch`/`subscribe` arrive in scope and the
  view gets a name in the trace. A plain `defn` is for a genuine helper that
  takes *data + callbacks* only and never reaches for state itself. So the
  sub-views here (`task-entry`, `task-list`, `todo-item`, `footer-controls`) are
  each a `reg-view` that reads exactly the slice it needs; the two true helpers
  (`todo-input`, `filter-link`) stay plain fns. No `dispatch`/`subscribe` is
  threaded down as an argument — a view that needs state subscribes to it.

  And hiccup throughout, which is really just markup-as-data. The job of a view
  is small and strict: read derived state, dispatch events when the user does
  something. No business logic sneaks in here.
  See docs/core/views.md (the reg-view authoring lane)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

;; Each filter is a ROUTE, so its link is a `route-link` naming the route id —
;; never a hand-built `#/active` string. The router's hash strategy (declared on
;; the frame in core.cljs) encodes the href to `#/active`; a plain left-click
;; dispatches `:rf.route/url-requested` and the URL updates through the ordinary
;; navigation cascade, while cmd/ctrl-click and open-in-new-tab keep working
;; because it renders a real `<a href>`. See docs/routing/concepts.md.
(def ^:private filter->route
  {:all :todo/all, :active :todo/active, :completed :todo/completed})

;; A CONTROLLED text input — the workhorse behind both the header box and the
;; edit-in-place box. It's a plain `defn` on purpose: it touches no state of its
;; own. Everything it needs arrives as data + callbacks in its props map, which
;; is the honest shape for a reusable helper — what it depends on is right there
;; in the signature. `:value` comes from a draft its caller subscribed to, and
;; every keystroke calls `on-change` so the caller can write that draft into
;; app-db. The input itself never holds the text; app-db does. Enter commits
;; (`on-commit`), Escape cancels (`on-cancel`), and losing focus commits too.
;; There's a subtle bit here: cancelling unmounts the input, so the blur that
;; follows finds nothing left to save. Tidy by accident, and on purpose.
;;
;; `:autofocus?` drops the cursor into the input the moment it mounts — exactly
;; what the edit box wants, since the row just flipped into edit mode. A `:ref`
;; callback calls `.focus()` on the real DOM node. It touches focus and nothing
;; else; `:value` stays bound to the draft.
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

(rf/reg-view todo-item [{:keys [id title completed]}]
  ;; A state-touching view, so a `reg-view`. It subscribes to what it needs by
  ;; the row's own id — "am I the one being edited?" — rather than being handed
  ;; the editing state from above. The per-row editing flag lives in app-db.
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
       ;; `todo-input` is a plain helper, so this view subscribes to the draft
       ;; and hands it down as data, with dispatching callbacks alongside.
       [todo-input
        {:class       "edit"
         :draft       @(subscribe [:todo.ui/draft :edit])
         :autofocus?  true
         :on-change   #(dispatch [:todo.ui/edit-field :edit %])
         :on-commit   #(dispatch [:todo.ui/commit-edit])
         :on-cancel   #(dispatch [:todo.ui/stop-edit])}])]))

(rf/reg-view task-entry []
  [:header.header
   [:h1 "todos"]
   ;; Same pattern as the edit box: subscribe to the draft here, hand it to the
   ;; plain `todo-input` helper as data with dispatching callbacks.
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

(rf/reg-view task-list []
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
      ;; `todo-item` is itself a `reg-view` — it subscribes to its own editing
      ;; state — so all it needs from here is the todo map to render.
      [todo-item todo])]])

(defn- filter-link [showing filter-kw label]
  ;; A plain `defn`: it touches no state, it's a pure function from data
  ;; (`showing`, the filter it stands for) to hiccup. `route-link` renders the
  ;; `<a>` and owns the click: the href is the route's URL (encoded to `#/active`
  ;; by the frame's hash strategy), and a plain click navigates through
  ;; `:rf.route/url-requested`. The `:class` marks the active filter.
  [rf/route-link {:to    (filter->route filter-kw)
                  :class (when (= showing filter-kw) "selected")}
   label])

(rf/reg-view footer-controls []
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

;; The root view. Like every state-touching view in this file it's a `reg-view`,
;; and because its children subscribe to what they need, all it does is decide
;; which sub-views to show — no `dispatch`/`subscribe` gets threaded down. That's
;; the reg-view authoring lane at work: a view that needs state asks for it,
;; rather than receiving it prop-drilled from above.
;; See docs/core/views.md.
(rf/reg-view root-view []
  (let [todos @(subscribe [:todo/todos])]
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
       [:a {:href "https://todomvc.com/"} "TodoMVC"]]]]))
