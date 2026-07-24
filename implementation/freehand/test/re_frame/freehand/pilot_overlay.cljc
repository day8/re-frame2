(ns re-frame.freehand.pilot-overlay
  "THE SECOND COMPONENT PILOT, CASE B — a dropdown, a nested submenu and a
  modal confirm, built the way a component-library author would build them,
  plus the application that calls them.

  Everything in this namespace is CONSUMER code. It declares views with
  `v/defview`, registers one behavior with `v/defbehavior`, reads with
  `v/sub`, emits intent as event vectors, and moves state with ordinary
  `reg-event` / `reg-sub`. It adds no substrate machinery, and it is the
  artefact the fitness harness's CASE B requirements
  (`docs/design/freehand/studio/fitness-harness.md`, §B.2) are judged
  against — one requirement at a time, in `pilot-overlay-cljs-test` and
  `pilot-overlay-dom-cljs-test`.

  ## Three components, and why they are three

  [[dropdown]] is a SEMANTIC CONTROLLER. It owns a protocol that spans
  several interactions — an open flag, a highlighted option, a keyboard
  grammar — so its state is ordinary frame data addressed by
  `(kind, :control)`. It is NOT buffered: an open flag holds no draft a
  caller could reject, so it asks for an address and never for a
  generation. That asymmetry is the substrate's, not this library's, and
  it is worth stating: `v/controller-key` is what makes a controller
  writable, and `v/controller-revision` is a SECOND, separable
  obligation that only a draft-holding control pays.

  [[confirm]] is PROPS-ONLY — value in, intent out. A modal's open state
  belongs to whatever decided to ask the question, so the component holds
  nothing at all. Three quarters of a real component library looks like
  this, and a library that made everything a controller would be
  advertising storage as the normal way to build a component.

  [[menu-bar]] is the composition: a dropdown whose panel contains another
  dropdown, which is the arrangement hand-rolled overlays get wrong.

  ## Where the overlay machinery went

  There is none. re-com's dropdown pays a document-level click listener
  installed on open, a `requestAnimationFrame` loop re-armed every frame
  while open, a z-index ladder coordinated across four component families,
  and a 100 ms `setTimeout` per transition — and it leaks the listener on
  unmount-while-open because the outer component has no lifecycle hook.
  This library has none of those, because the platform already owns them:

  - `:popover :auto` gives light dismiss, Escape, one-at-a-time siblings
    and LIFO nesting, with ZERO listeners of ours;
  - the top layer escapes every clipping ancestor and every stacking
    context by construction, so there is no z-index to coordinate;
  - `<dialog>` + `modal-open?` gives focus entry, background inertness,
    Escape and focus return;
  - transitions are CSS (`transition-behavior: allow-discrete` and
    `@starting-style`), so there is no timer of ours to orphan.

  What is genuinely left is MEASUREMENT — where to put the panel — and
  that is exactly one registered behavior.

  ## The two refs, and why they still sit apart

  [[anchor-box]] is attached to the dropdown's ROOT element, and the
  desired-state property is declared on the PANEL element inside it. That
  split is a modelling choice: the measurement is of the anchor's box and
  the promotion is of the panel, and those are different things. It is not
  an avoidance — a behavior attaches through `cloneElement` with its own
  ref and the top layer installs its idempotent host call on a ref of its
  own, and ref composition chains the two, so one element carrying both
  hands the node to both. `pilot-overlay-dom-cljs-test` mounts exactly
  that element and reads both back rather than leaving the combination to
  be rediscovered.

  ## How the measurement reaches the panel

  Through the CASCADE, not through a second ref and not through a render
  round trip. The behavior measures its own node — the dropdown root,
  whose only in-flow child is the anchor button, because an open popover
  is out of flow — and writes the result as inline CUSTOM PROPERTIES on
  that node. The panel is a DOM DESCENDANT of the root even while the
  browser paints it in the top layer, so the properties inherit and the
  panel's `top` / `left` resolve against them.

  That is the whole reason the ruling preferred a top layer to a portal:
  the element never leaves the tree, so inheritance, subscriptions and the
  frame context all keep working, and there is no boundary to propagate
  anything across.

  ## The tracking frequency, declared

  The panel is placed at the COMMIT whose configuration moved, and at no
  other moment. No scroll listener, no resize observer, no animation-frame
  loop, no timer. If an application knows the anchor moved for a reason
  the substrate cannot see, it says so with one command
  (`:re-frame.freehand.host/command` → `:reposition`) rather than paying a
  loop against the possibility. `pilot-overlay-dom-cljs-test` asserts the
  count of retained observers and listeners as an exact integer."
  (:require [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.web :as web]))

;; ===========================================================================
;; THE COMPONENT LIBRARY — `acme.ui`
;; ===========================================================================
;;
;; `:acme.ui/*` is LIBRARY vocabulary. The substrate reserves no namespace
;; for controls and fixes no storage path: the root below, the record
;; shape and every id are this library's choices.

(def records-root
  "Where this library keeps its controller records. A library decision,
  not a substrate one."
  :acme.ui/controllers)

(def dropdown-kind
  "The dropdown controller family. It is half of every record key this
  library writes, so a second family addressed at the SAME domain identity
  reads its own record rather than this one's."
  :acme.ui/dropdown)

(def parts
  "The PUBLIC part addresses each component emits, by component id.

  A part id is API: renaming one is a breaking change to every stylesheet
  that reaches it, exactly as renaming a prop is. They are literal
  `data-part` values under a `data-component` scope, so `root` and `panel`
  can be common names without colliding across libraries."
  {"acme/dropdown" ["root" "anchor" "panel" "option"]
   "acme/confirm"  ["root" "title" "body" "cancel" "accept"]})

(def custom-properties
  "The inline custom properties [[anchor-box]] publishes on the dropdown
  root, and the panel resolves its position from.

  They are API for the same reason a part id is: a stylesheet may read
  them, and an application that wants a different placement rule overrides
  the panel's `top` / `left` in terms of them rather than re-measuring."
  ["--acme-anchor-x" "--acme-anchor-y" "--acme-anchor-w" "--acme-anchor-h"])

;; ---------------------------------------------------------------------------
;; The one imperative thing a dropdown needs: a box
;; ---------------------------------------------------------------------------

(defn- px [n] (str n "px"))

(defn- measure!
  "Read the node's border box and publish it as inline custom properties.

  `:layout` timing, so this runs BEFORE the browser paints — which is the
  only honest home for measure-then-place work, and the reason there is no
  wrong-position frame to hide behind an opacity-0 park or a -10000px
  offset. The properties inherit to the panel, so nothing here reaches for
  a second node, a selector, or a parent walk."
  [{:keys [node config]}]
  #?(:clj  (do node config nil)
     :cljs (let [^js r  (.getBoundingClientRect node)
                 gap    (get config :gap 0)
                 ^js st (.-style node)]
             (.setProperty st "--acme-anchor-x" (px (.-left r)))
             (.setProperty st "--acme-anchor-y" (px (+ (.-bottom r) gap)))
             (.setProperty st "--acme-anchor-w" (px (.-width r)))
             (.setProperty st "--acme-anchor-h" (px (.-height r)))
             nil)))

(v/defbehavior anchor-box
  "Publish the dropdown root's measured box as inherited custom
  properties.

  Everything it needs arrives as `:config` — the open flag and the gap —
  so the connection re-measures exactly when the configuration MOVES and
  at no other moment. There is nothing to release, which is why
  `:disconnect` is absent rather than empty: the behavior acquires no
  listener, no observer and no timer, so teardown has nothing to undo and
  a test can assert that as an absence.

  `:reposition` is the declared escape for the case the frequency does not
  cover — an application that knows its anchor moved sends one command
  instead of the substrate arming a loop against the possibility."
  {:timing   :layout
   :connect  measure!
   :update   measure!
   :commands {:reposition measure!}})

;; ---------------------------------------------------------------------------
;; The library's dataflow — ordinary re-frame, registered by the consumer
;; ---------------------------------------------------------------------------

(defn- clamp [n lo hi] (max lo (min hi n)))

(defn- record [db k] (get-in db [records-root k]))

(defn- close
  "Closing is one write, and it is the same write from every cause — the
  anchor, Escape, a chosen option, a light dismiss the browser performed
  without asking. A control whose four close paths wrote four shapes of
  state would eventually disagree with itself."
  [db k]
  (update db records-root dissoc k))

(defn- choose-fx
  "Commit option `i` of `options`: close, and produce the caller's intent
  carrying the chosen value. Shared by the click site and the Enter branch
  so the pointer grammar and the keyboard grammar cannot drift."
  [db k i options on-change]
  (if-let [opt (get options i)]
    {:db (close db k)
     :fx [[:dispatch (conj (vec on-change) (:value opt))]]}
    {:db (close db k)}))

(defn register!
  "Register the library's whole dataflow: one read and five transitions.

  A library ships this as an ordinary function its consumer calls at boot.
  There is no registry to join, no component to instantiate, and nothing
  here that a `reg-sub` and a `reg-event` do not already do."
  []
  ;; --- The read ------------------------------------------------------
  ;;
  ;; ONE read for the whole control. A dropdown's open flag and its
  ;; highlighted option move together, are read together, and are
  ;; meaningless apart; splitting them into two subscriptions would buy a
  ;; finer invalidation on state that always changes at once.
  (rf/reg-sub :acme.ui.dropdown/state
    (fn [db [_ k]]
      (let [r (record db k)]
        {:open?  (boolean (:open? r))
         :active (:active r 0)})))

  ;; --- The dismissal handshake ---------------------------------------
  ;;
  ;; The browser reports a top-layer element's state changes through
  ;; `ToggleEvent`, and the useful field is `newState` — "open" or
  ;; "closed". There is no reserved projection for it: the closed roster
  ;; is `::v/value` / `::v/checked` / `::v/key`, so the report cannot ride
  ;; a declarative event vector at all. `v/event` (the sanctioned
  ;; conversion seam) would take it, at the cost the data-intent idiom
  ;; exists to avoid: the site records as the OPAQUE marker, so what this
  ;; component dispatches on a dismissal would stop being comparable data
  ;; and no headless test could assert it.
  ;;
  ;; So the library counts instead of reading. Every open session produces
  ;; exactly one open-toggle followed by at most one close-toggle, in that
  ;; order, so the FIRST report of a session is the one the application
  ;; caused and any LATER one is the browser dismissing without asking.
  ;; One boolean, no live event object, and the whole path stays data.
  (rf/reg-event :acme.ui.dropdown/toggle-reported
    (fn [{:keys [db]} [_ k]]
      (let [r (record db k)]
        (cond
          ;; The report of a close the application already performed. The
          ;; record is gone, so there is nothing to reconcile.
          (nil? r)        {}
          ;; The opening report. Acknowledge it and wait.
          (not (:acked? r)) {:db (assoc-in db [records-root k :acked?] true)}
          ;; A second report while still open: the browser closed it —
          ;; Escape, a light dismiss, a sibling popover taking the layer.
          :else           {:db (close db k)}))))

  ;; --- The transitions -----------------------------------------------

  (rf/reg-event :acme.ui.dropdown/anchor-clicked
    (fn [{:keys [db]} [_ k]]
      (if (:open? (record db k))
        {:db (close db k)}
        {:db (assoc-in db [records-root k] {:open? true :active 0})})))

  (rf/reg-event :acme.ui.dropdown/option-chosen
    (fn [{:keys [db]} [_ k i options on-change]]
      (choose-fx db k i options on-change)))

  ;; The browser's own dismissal, reconciled. Escape, a light dismiss and
  ;; a sibling popover opening all close the node WITHOUT asking the
  ;; application, and the substrate deliberately writes no state on their
  ;; behalf — so an unreconciled control springs back open on the next
  ;; render. This is the reconciliation, and it is idempotent: a dismissal
  ;; the application already caused finds the record gone and writes
  ;; nothing.
  (rf/reg-event :acme.ui.dropdown/dismissed
    (fn [{:keys [db]} [_ k]]
      {:db (close db k)}))

  ;; The keyboard grammar as DATA. `::v/key` carries the live
  ;; `KeyboardEvent.key`, so the whole single_dropdown parity set —
  ;; open, cancel-restore, arrows, Home/End — is ONE registered event a
  ;; structural test drives by equality. No callback body, no `.-key`
  ;; read, nothing a JVM host cannot run.
  ;;
  ;; The cost is stated rather than hidden: this site fires on EVERY key,
  ;; and a key outside the grammar settles an event that changes nothing.
  (rf/reg-event :acme.ui.dropdown/key-pressed
    (fn [{:keys [db]} [_ k options on-change pressed]]
      (let [r      (record db k)
            open?  (boolean (:open? r))
            active (:active r 0)
            last*  (max 0 (dec (count options)))
            ;; Two narrow writes rather than a replacement, so the
            ;; dismissal handshake's acknowledgement survives a highlight
            ;; move. A control that forgot it had already been reported
            ;; open would treat the browser's next dismissal as an opening
            ;; and spring back open.
            move   (fn [i] {:db (-> db
                                    (assoc-in [records-root k :open?] true)
                                    (assoc-in [records-root k :active] (clamp i 0 last*)))})]
        (cond
          (not open?) (case pressed
                        ("ArrowDown" "ArrowUp" "Enter" " ") (move (if (= "ArrowUp" pressed) last* 0))
                        {})
          :else       (case pressed
                        "ArrowDown" (move (inc active))
                        "ArrowUp"   (move (dec active))
                        "Home"      (move 0)
                        "End"       (move last*)
                        "Enter"     (choose-fx db k active options on-change)
                        ;; Escape CANCELS: the highlight is discarded and
                        ;; the caller's value is untouched. The browser
                        ;; will also light-dismiss the popover and fire
                        ;; `toggle`, which lands on `dismissed` — the same
                        ;; write, so the two paths converge rather than
                        ;; race.
                        "Escape"    {:db (close db k)}
                        ;; Tab-out closes. The panel takes no focus, so the
                        ;; next tab stop is wherever it would have been.
                        "Tab"       {:db (close db k)}
                        {}))))))

;; ---------------------------------------------------------------------------
;; `dropdown` — the semantic controller
;; ---------------------------------------------------------------------------

(defn- option-id [name* i] (str "acme-dropdown-" name* "-option-" i))
(defn- panel-id  [name*]   (str "acme-dropdown-" name* "-panel"))
(defn- anchor-id [name*]   (str "acme-dropdown-" name* "-anchor"))

(defn- selected-label
  "The label of the caller's currently selected value, or the placeholder.
  A plain helper: it touches no state, so it is a `defn` rather than a
  declared view, and it runs inside the boundary that called it."
  [options selected placeholder]
  (or (some (fn [o] (when (= selected (:value o)) (:label o))) options)
      placeholder))

(v/defview dropdown
  "An anchored listbox: a button that opens a panel, a panel the browser
  paints in its top layer, and a keyboard grammar.

  `:control` is the domain identity that OWNS the open state
  (`[:invoice 42 :account]`), not a render position: a sort, a view rename
  or a parent extraction leaves it exactly where it was. It is the only
  ceremony a caller pays, and it buys per-instance state with no registry,
  no `:model` atom, and no second way to spell the same thing — the
  caller-supplied-model-or-internal-ratom dual contract is not offered,
  because two ways to own one flag is how a component library acquires
  callers who own it differently.

  The panel is IN THE TREE. It is a DOM child of the root, so it resolves
  the same frame and the same subscriptions as the anchor, it inherits the
  root's custom properties, and a server renders it as an ordinary closed
  element. The browser paints it in the top layer, which is what escapes
  the clipping ancestor — and that is a painting fact, not a tree fact."
  {:props [:map
           [:name :string]
           [:label :string]
           [:control :some]
           [:options [:vector :any]]
           [:selected {:optional true} :any]
           [:on-change :vector]
           [:gap {:optional true} :int]]
   ;; Children are governed HERE and only here. They arrive as the trailing
   ;; forms of a call, so a `[:children …]` schema entry would be a second
   ;; contract for the same thing — and one no caller could satisfy, since a
   ;; map-carried `:children` is refused at the boundary.
   :children-policy :optional}
  [{:keys [name label options selected on-change gap children] :as props}]
  (let [k      (v/controller-key dropdown-kind props)
        state  (v/sub [:acme.ui.dropdown/state k])
        open?  (:open? state)
        active (:active state)]
    ;; The behavior owns the ROOT; the desired-state property is on the
    ;; PANEL. Two nodes, two refs, no collision — see the namespace
    ;; docstring.
    [v/behavior {:use    anchor-box
                 :target k
                 :config {:open? open? :gap (or gap 0)}}
     [:div {:data-component "acme/dropdown"
            :data-part      "root"
            :data-field     name
            :style          {:display "inline-block" :position "relative"}}
      [:button {:data-part             "anchor"
                :id                    (anchor-id name)
                :type                  "button"
                :aria-haspopup         "listbox"
                :aria-expanded         (if open? "true" "false")
                :aria-controls         (panel-id name)
                :aria-activedescendant (when open? (option-id name active))
                :on-click              [:acme.ui.dropdown/anchor-clicked k]
                :on-key-down           [:acme.ui.dropdown/key-pressed k options on-change ::v/key]}
       (selected-label options selected label)]
      [:div {:data-part          "panel"
             :id                 (panel-id name)
             :role               "listbox"
             :popover            :auto
             ::web/popover-open? open?
             :on-toggle          [:acme.ui.dropdown/toggle-reported k]
             ;; The placement, resolved from the properties the behavior
             ;; published on the root. `inset: auto` un-does the popover
             ;; UA rule that centres it in the viewport; the top layer
             ;; makes `fixed` resolve against the viewport whatever
             ;; transform an ancestor carries.
             :style              {:position  "fixed"
                                  :inset     "auto"
                                  :top       "var(--acme-anchor-y)"
                                  :left      "var(--acme-anchor-x)"
                                  :min-width "var(--acme-anchor-w)"
                                  :margin    0}}
       (for [[i opt] (map-indexed vector options)]
         [:div {:key           i
                :id            (option-id name i)
                :data-part     "option"
                :role          "option"
                :aria-selected (if (= i active) "true" "false")
                :on-click      [:acme.ui.dropdown/option-chosen k i options on-change]}
          (:label opt)])
       ;; A slot, so a panel can hold a nested overlay. It is ordinary
       ;; children — nesting needs no mechanism, only structure.
       children]]]))

;; ---------------------------------------------------------------------------
;; `confirm` — props only. A modal owns nothing.
;; ---------------------------------------------------------------------------

(v/defview confirm
  "A modal confirmation: `<dialog>` promoted with `modal-open?`.

  It holds NO state. Whatever decided to ask the question owns the answer,
  so `:open?` arrives as a prop and both outcomes leave as event vectors.

  Focus entry, background inertness, Escape and focus return are the
  platform's — `showModal()` does all four, and re-com ships none of them.
  `:on-close` is the reconciliation seam the substrate requires: Escape
  and the dialog's own close both fire it without asking the application."
  {:props [:map
           [:name :string]
           [:title :string]
           [:open? :boolean]
           [:on-cancel :vector]
           [:on-accept :vector]]}
  [{:keys [name title open? on-cancel on-accept]}]
  [:dialog {:data-component    "acme/confirm"
            :data-part         "root"
            :id                (str "acme-confirm-" name)
            ::web/modal-open?  open?
            :aria-label        title
            :on-close          on-cancel}
   [:h2 {:data-part "title"} title]
   [:div {:data-part "body"}
    [:button {:data-part "cancel"
              :id        (str "acme-confirm-" name "-cancel")
              :type      "button"
              :on-click  on-cancel}
     "Cancel"]
    [:button {:data-part "accept"
              :id        (str "acme-confirm-" name "-accept")
              :type      "button"
              :on-click  on-accept}
     "Delete"]]])

;; ===========================================================================
;; THE APPLICATION — a document toolbar
;; ===========================================================================
;;
;; From here down is ordinary application code. It knows nothing about
;; controller records: it holds its own values, reads them the ordinary
;; way, and calls the library's components like any other view.

(def format-options
  [{:value :pdf   :label "PDF"}
   {:value :docx  :label "Word"}
   {:value :odt   :label "OpenDocument"}])

(def scope-options
  [{:value :page  :label "This page"}
   {:value :doc   :label "Whole document"}])

(defn register-app!
  "The application's own registrations."
  []
  (rf/reg-sub :toolbar/format    (fn [db [_ id]] (get-in db [:toolbar id :format])))
  (rf/reg-sub :toolbar/scope     (fn [db [_ id]] (get-in db [:toolbar id :scope])))
  (rf/reg-sub :toolbar/deleting? (fn [db [_ id]] (boolean (get-in db [:toolbar id :deleting?]))))
  (rf/reg-sub :toolbar/deleted   (fn [db _] (get db ::deleted [])))

  (rf/reg-event :toolbar/format-chosen
    (fn [{:keys [db]} [_ id value]] {:db (assoc-in db [:toolbar id :format] value)}))

  (rf/reg-event :toolbar/scope-chosen
    (fn [{:keys [db]} [_ id value]] {:db (assoc-in db [:toolbar id :scope] value)}))

  (rf/reg-event :toolbar/delete-requested
    (fn [{:keys [db]} [_ id]] {:db (assoc-in db [:toolbar id :deleting?] true)}))

  (rf/reg-event :toolbar/delete-cancelled
    (fn [{:keys [db]} [_ id]] {:db (assoc-in db [:toolbar id :deleting?] false)}))

  (rf/reg-event :toolbar/delete-accepted
    (fn [{:keys [db]} [_ id]]
      {:db (-> db
               (assoc-in [:toolbar id :deleting?] false)
               (update ::deleted (fnil conj []) id))})))

(v/defview toolbar
  "One document's toolbar: an export-format dropdown, a delete button and
  the modal that confirms it.

  The whole thing sits inside a CLIPPING, TRANSFORMED ancestor — the exact
  arrangement that defeats `position: fixed` emulation, because a
  transform makes an ancestor the containing block. The panel escapes it
  anyway, and it escapes it without a z-index, because the browser paints
  a popover in the top layer rather than in the ancestor's stacking
  context."
  {:props [:map [:id :any]]}
  [{:keys [id]}]
  (let [format*   (v/sub [:toolbar/format id])
        deleting? (v/sub [:toolbar/deleting? id])]
    [:div {:data-component "acme/toolbar"
           :id             (str "toolbar-clip-" (name id))
           ;; The two things that defeat `position: fixed` emulation, in
           ;; one element: a clipping box and a containing-block-forming
           ;; transform.
           ;; Deliberately TIGHT: an 18px box that the panel could not
           ;; possibly fit inside, so `escaped the clip` is a measurement
           ;; rather than a coincidence.
           :style          {:overflow  "hidden"
                            :height    "18px"
                            :width     "220px"
                            :transform "translateX(10px)"}}
     [dropdown {:name      (str "format-" (name id))
                :label     "Export as…"
                :control   [:toolbar id :format]
                :options   format-options
                :selected  format*
                :gap       4
                :on-change [:toolbar/format-chosen id]}]
     [:button {:id       (str "toolbar-delete-" (name id))
               :type     "button"
               :on-click [:toolbar/delete-requested id]}
      "Delete"]
     [confirm {:name      (name id)
               :title     "Delete this document?"
               :open?     deleting?
               :on-cancel [:toolbar/delete-cancelled id]
               :on-accept [:toolbar/delete-accepted id]}]]))

(v/defview menu-bar
  "A dropdown whose panel contains another dropdown — the nesting case.

  The inner control is ordinary CHILDREN of the outer one. Nothing
  coordinates them: the browser knows the inner panel is a DOM descendant
  of the outer, so it stacks them rather than treating the inner as a
  sibling that closes the outer, and Escape unwinds them innermost-first."
  {:props [:map [:id :any]]}
  [{:keys [id]}]
  (let [format* (v/sub [:toolbar/format id])
        scope*  (v/sub [:toolbar/scope id])]
    [:div {:data-component "acme/menu-bar"}
     [dropdown {:name      (str "outer-" (name id))
                :label     "Export as…"
                :control   [:menu id :format]
                :options   format-options
                :selected  format*
                :on-change [:toolbar/format-chosen id]}
      [dropdown {:name      (str "inner-" (name id))
                 :label     "Scope…"
                 :control   [:menu id :scope]
                 :options   scope-options
                 :selected  scope*
                 :on-change [:toolbar/scope-chosen id]}]]]))

(v/defview toolbars
  "Two documents on one page — the same components, four controller
  records, and no coordination between them. Each control's state is
  addressed by the domain identity that owns it, so there is nothing for
  two instances to collide over."
  {:props [:map [:ids [:vector :any]]]}
  [{:keys [ids]}]
  [:div {:data-component "acme/toolbars"}
   (for [id ids]
     [toolbar {:key id :id id}])])
