(ns re-frame.hicasso.examples.todo.views
  "THE WHOLE OF THE MARKUP (rf2-hic-086).

  Five boundaries. Everything they reach for is `h/…` — `defview`, `sub`,
  `route-link`, the `::h/value` and `::h/checked` markers, the `::h/clear`
  event and the `h/reg-state` pair `db` declared. There is no `impl`
  namespace in the `:require` list and no `re-frame.core` either: a view
  neither subscribes nor dispatches, because a read is `h/sub` and an
  intent is a vector.

  ## The three things an author has to know that are not in a signature

  1. **`h/route-link` is CALLED, not written as a head.** `(h/route-link
     {…} \"Active\")` inside a body, where every other markup-producing
     thing on the page is `[some-view {…}]`. It is a plain function on
     purpose — a link is not a unit of re-render — and nothing at the
     call site says which grammar applies. rf2-hic-025 recorded this and
     it is confirmed here: three filter tabs, three calls, and the
     one-time cost paid once.

  2. **`:on-key-down` takes a MAP.** `{\"Enter\" [::events/commit-edit
     id] \"Escape\" [::h/clear db/draft id]}` is a first-class lowered
     shape — one `.key` lookup per event, composition-gated centrally so
     an IME's Enter commits nothing — and it is why this file contains no
     callback at all. It is also documented NOWHERE an author reads:
     `re-frame.hicasso`'s own docstrings never mention it, and the only
     statement of the four event-value shapes is in
     `re-frame.hicasso.impl.intent`, a namespace the door tells authors
     they never need to open. See the authoring report; without it, the
     Todo class's Enter/Escape pair reaches for `h/fn` and hand-written
     `.-key` tests.

  3. **A `:ref` must be a STABLE function**, so [[focus-on-mount]] is a
     top-level `def`. React detaches and re-attaches a ref whose identity
     changed, so a `(fn [node] …)` written inline in a body would re-run
     on every keystroke — refocusing the field, and moving the caret to
     the end of the text on every character. Nothing on the door says so,
     and the failure is a caret that jumps rather than an error.

  ## Every controlled element is `:value` / `:checked` plus an intent

  No ref, no local draft, no `onChange` closure, no effect reconciling
  two copies of the text, and no `:readOnly` to hush React about a
  checkbox it does not drive: `::h/value` and `::h/checked` say *the
  thing the user just did*, and the model is the only place it lives."
  (:require [clojure.string :as str]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.todo.db :as db]
            [re-frame.hicasso.examples.todo.events :as events]
            [re-frame.hicasso.examples.todo.routes :as routes]
            [re-frame.hicasso.examples.todo.subs :as subs]))

(def ^:private focus-on-mount
  "React's own `:ref` contract: called in the commit phase with the node,
  and again with `nil` on detach. Hoisted to a top-level `def` because
  its IDENTITY is what React uses to decide whether to re-attach — see
  the namespace docstring."
  (fn [node] (when node (.focus node))))

;; ---------------------------------------------------------------------------
;; The header — one controlled box in a form
;; ---------------------------------------------------------------------------

(h/defview new-todo-box
  "The box you type a new to-do into.

  It is a `<form>`, and that is the whole of *Enter adds a to-do*: a text
  input inside a form submits on Enter, and `:on-submit` AUTO-PREVENTS,
  so the intent is `[::events/add]` with no key test, no callback and no
  `.preventDefault` anywhere in this file."
  [_]
  [:form.new-todo-form {:on-submit [::events/add]}
   [:label {:for "new-todo"} "What needs to be done?"]
   [:input#new-todo.new-todo
    {:type        "text"
     :placeholder "What needs to be done?"
     :value       (h/sub [::subs/new-todo])
     :on-input    [::events/typed ::h/value]}]])

;; ---------------------------------------------------------------------------
;; One row — and the whole of edit-in-place
;; ---------------------------------------------------------------------------

(h/defview todo-row
  "One to-do: a checkbox, its title, a delete button, and — when this
  row's draft is set — an edit box in place of the title.

  The draft is `h/reg-state` keyed by the to-do's id, so opening one
  editor opens ONE editor. Double-clicking the title writes the current
  title into that row's draft, which both opens the editor and fills it:
  one write, because *the editor is open* and *the editor holds this
  text* are one fact.

  Escape is `[::h/clear db/draft id]` — removal, back to the default —
  and it needs no `::h/revision`, because clearing the draft UNMOUNTS the
  field rather than handing a still-mounted one a value it is already
  showing. That is the boundary of rf2-hic-025's fifth finding, from the
  other side: the revision counter is what a reset needs when the field
  SURVIVES it."
  [{:keys [id title done?]}]
  (let [draft (h/sub [db/draft id])]
    [:li.todo-row {:class (str/join " " (cond-> []
                                          done?         (conj "completed")
                                          (some? draft) (conj "editing")))}
     [:div.view
      [:input.toggle
       {:type      "checkbox"
        :aria-label (str "Done: " title)
        :checked   done?
        :on-change [::events/toggle id ::h/checked]}]
      [:label.todo-title {:on-double-click [db/draft id title]} title]
      [:button.destroy {:type       "button"
                        :aria-label (str "Delete " title)
                        :on-click   [::events/destroy id]}]]
     (when (some? draft)
       [:input.edit
        {:type        "text"
         :aria-label  "Edit to-do"
         :ref         focus-on-mount
         :value       draft
         :on-input    [db/draft id ::h/value]
         :on-blur     [::events/commit-edit id]
         :on-key-down {"Enter"  [::events/commit-edit id]
                       "Escape" [::h/clear db/draft id]}}])]))

;; ---------------------------------------------------------------------------
;; The list
;; ---------------------------------------------------------------------------

(h/defview todo-list
  "The rows, keyed by the to-do's own id.

  The key is the domain id and never the position. A list keyed by its
  index reuses the wrong row the moment the order changes — and here it
  would do worse than that: flipping the filter from All to Active
  removes rows from the MIDDLE of the list, so an index-keyed row would
  keep the DOM node, the caret and the open editor belonging to a
  different to-do."
  [_]
  [:section.main
   [:input#toggle-all.toggle-all
    {:type      "checkbox"
     :checked   (h/sub [::subs/all-done?])
     :on-change [::events/toggle-all ::h/checked]}]
   [:label {:for "toggle-all"} "Mark all as complete"]
   [:ul.todo-list
    (for [{:keys [id] :as todo} (h/sub [::subs/visible])]
      [todo-row (assoc todo :key id)])]])

;; ---------------------------------------------------------------------------
;; The footer — three routed tabs and a count
;; ---------------------------------------------------------------------------

(def ^:private tabs
  "The three filter tabs, as data: the filter each one stands for, the
  route it links to, the params that route wants, and its label."
  [{:kind :all       :to routes/all      :params nil                   :label "All"}
   {:kind :active    :to routes/filtered :params {:filter "active"}    :label "Active"}
   {:kind :completed :to routes/filtered :params {:filter "completed"} :label "Completed"}])

(h/defview footer
  "The count, the three tabs, and *Clear completed* — which is present
  only when there is something to clear."
  [_]
  (let [left    (h/sub [::subs/active-count])
        done    (h/sub [::subs/completed-count])
        showing (h/sub [::subs/showing])]
    [:footer.footer
     [:span.todo-count
      [:strong (str left)]
      (if (= 1 left) " item left" " items left")]
     [:ul.filters
      (for [{:keys [kind to params label]} tabs]
        [:li {:key (name kind)}
         ;; CALLED, not a head. The href is routing's own synthesis — no
         ;; URL is written anywhere in this application.
         (h/route-link (cond-> {:to to :class (when (= kind showing) "selected")}
                         params (assoc :params params))
                       label)])]
     (when (pos? done)
       [:button.clear-completed {:type     "button"
                                 :on-click [::events/clear-completed]}
        (str "Clear completed (" done ")")])]))

;; ---------------------------------------------------------------------------
;; The application
;; ---------------------------------------------------------------------------

(h/defview app
  "The whole page.

  The list and the footer are ABSENT while there is nothing to show —
  the Todo class's one piece of conditional chrome, and the read that
  decides it is `[::subs/any?]` rather than a count, so adding a
  hundredth to-do does not re-render this boundary."
  [_]
  (let [any? (h/sub [::subs/any?])]
    [:main.todo-app
     [:h1 "todos"]
     [new-todo-box {}]
     (when any?
       [:<>
        [todo-list {}]
        [footer {}]])]))
