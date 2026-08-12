(ns re-frame.hicasso.examples.typeahead.views
  "THE TYPEAHEAD'S VIEWS — five boundaries on the public door
  (rf2-hic-044).

  Everything reached for is `h/…`: `defview`, `sub`, and the `::h/value` /
  `::h/revision` markers. No `impl` namespace, no `re-frame.core` — a view
  neither dispatches nor subscribes directly, because an intent is a
  vector and a read is `h/sub`.

  ## The panel is where the whole experiment lives

  [[panel]] is rendered by [[screen]] only when a read is wanted, and it
  reads `[::subs/suggestions term]` — the resource, named with its
  parameter. **That committed read is the entire fact demand-driven
  resource ownership would need**: mount it and the resource is wanted,
  change `term` and a different one is wanted, stop rendering it and none
  is. Every OWNERSHIP row of the census in
  [[re-frame.hicasso.examples.typeahead.events]] exists to reconstruct
  that fact from `app-db`, because today nothing carries it out of the
  commit.

  ## The one POLICY decision that lives in a view

  Refresh-with-data. `[::subs/suggestions term]` answers `nil` while a
  request for a NEW term is out, which is correct — the rows held answer
  the old one — and a panel that painted that `nil` would blank itself on
  every keystroke. So the panel reads the held rows as well and prefers
  the live answer, falling back while a refresh is out. It is marked as a
  census site like any other, and it is POLICY: the criteria keep
  refresh-with-data explicit under demand too.

  ## Dismissal is a button, not a blur

  A production typeahead closes on blur, and then has to defeat the
  ordering bug where blur beats the suggestion's own click. That fight is
  about pointer events and has nothing to do with resources, so this
  witness spends an explicit *close* button instead and keeps the intent
  grammar declarative. Nothing about the resource story changes: a
  dismissal is an intent either way, and the census row is the release
  beside it."
  (:require [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.typeahead.events :as events]
            [re-frame.hicasso.examples.typeahead.subs :as subs]))

;; ---------------------------------------------------------------------------
;; The field
;; ---------------------------------------------------------------------------

(h/defview field
  "The controlled search box, and the two buttons that end a read.

  `::h/revision` is what makes *clear* work: dropping the model's text
  moves the value back to the empty string, and if the field was already
  showing an empty string React would see nothing to do. A changed
  revision re-baselines the field without remounting it (HD-019)."
  [_]
  (let [term     (h/sub [::subs/term])
        revision (h/sub [::subs/revision])]
    [:div.typeahead-field
     [:label {:for "typeahead-term"} "Search"]
     [:input#typeahead-term.term
      {:type        "text"
       :value       term
       ::h/revision revision
       ;; Positional, because `::h/value` substitutes at the intent
       ;; vector's top level only.
       :on-input    [::events/typed ::h/value]
       :on-focus    [::events/focus {}]}]
     [:button.clear {:type "button" :on-click [::events/clear {}]} "clear"]
     [:button.dismiss {:type "button" :on-click [::events/dismiss {}]} "close"]]))

;; ---------------------------------------------------------------------------
;; The suggestions
;; ---------------------------------------------------------------------------

(h/defview suggestion-row
  "One suggestion. Hovering it warms the row's detail — a demand no read
  expresses, and the reason C4 records prefetch as out of scope."
  [{:keys [id name]}]
  [:li.suggestion
   [:button.suggestion-choose
    {:type           "button"
     :on-click       [::events/choose {:id id}]
     :on-mouse-enter [::events/hover {:id id}]}
    name]])

(h/defview panel
  "The suggestion list. Rendered only while a read wants a term, so its
  body's `[::subs/suggestions term]` is live exactly when the resource is
  wanted and not otherwise."
  [{:keys [term]}]
  (let [rows   (h/sub [::subs/suggestions term])
        status (h/sub [::subs/status])
        ;; CENSUS P5 | POLICY | refresh-with-data | keep painting the rows held while a request for a NEW term is out
        painted (or rows (h/sub [::subs/held-rows]))
        ;; /CENSUS P5
        ]
    [:div.typeahead-panel
     (cond
       (= :failed status)
       [:p.panel-problem {:role "alert"} (str (h/sub [::subs/problem]))]

       (nil? painted)
       [:p.panel-loading "searching"]

       (empty? painted)
       [:p.panel-empty "no matches"]

       :else
       [:ul.suggestions
        (for [row painted]
          [suggestion-row (assoc row :key (:id row))])])

     (when (contains? #{:loading :refreshing :typing} status)
       [:span.panel-busy "busy"])]))

;; ---------------------------------------------------------------------------
;; The detail
;; ---------------------------------------------------------------------------

(h/defview detail-pane
  "The chosen row. Its read is `[::subs/detail id]` — the second resource,
  parameterised by the id rather than by the term."
  [{:keys [id]}]
  (let [detail (h/sub [::subs/detail id])]
    [:section.typeahead-detail
     (cond
       (= :pending detail) [:p.detail-pending "loading"]
       (nil? detail)       [:p.detail-absent "nothing chosen"]
       :else               [:<>
                            [:h3.detail-name (:name detail)]
                            [:p.detail-blurb (:blurb detail)]])]))

;; ---------------------------------------------------------------------------
;; The shell
;; ---------------------------------------------------------------------------

(h/defview screen
  "The whole application.

  Two conditional children, and each one is a resource read appearing and
  disappearing: the panel when a term is wanted, the detail pane when
  something is chosen. Those two `when`s are the read liveness the census
  is trying to keep a request in step with."
  [_]
  (let [term   (h/sub [::subs/wanted])
        open?  (h/sub [::subs/open?])
        chosen (h/sub [::subs/chosen])]
    [:main.typeahead
     [field {}]
     (cond
       (some? term) [panel {:term term}]
       open?        [:p.typeahead-hint "keep typing"])
     (when (some? chosen)
       [detail-pane {:id chosen}])]))
