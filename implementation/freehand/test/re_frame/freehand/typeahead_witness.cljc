(ns re-frame.freehand.typeahead-witness
  "FH-CTRL-020 — the TYPEAHEAD WITNESS: a control with an anchored popover,
  written the way the first-party kit writes one, plus the application that
  calls it.

  A typeahead is the control every substrate gets to prove itself on,
  because it is where four hard things meet at once: a draft the caller can
  reject, real latency with two answers in flight for two different
  questions, a listbox that has to be positioned against an input it is not
  a child of, and a keyboard protocol that must not fight an input method.
  Each one alone is ordinary; together they are what separates a view
  substrate from a template language.

  ## What this namespace is, and what it deliberately is NOT

  It is EVIDENCE. It lives under `test/` and nothing in a production bundle
  reaches it, because promoting a control onto the published door is an
  API-manifest decision (rf2-drpa3.182 §DELIVERY keeps public-verb
  additions in that serial lane) and this witness does not take it. The
  bead's own non-goals say the same thing from the other end: no universal
  listbox framework, no catalogue commitment.

  What it IS written as is a kit member. Every correctness decision it
  makes that [[re-frame.freehand.controls]] has already made, it DELEGATES
  rather than re-decides — the composing-Enter rule through
  `c/key-intent`, the owner's clear through `c/release`, the address and
  generation through `v/controller-key` / `v/controller-revision` /
  `v/controller-current?`. The kit's own docstring names a typeahead's
  settled query as the case its controller verbs exist for; this is that
  case, exercised.

  ## The anchoring: the platform's, entirely

  The suggestion list is a `popover` element whose desired state is the
  reserved `::web/popover-open?` fact, and whose POSITION is CSS anchor
  positioning: the input declares `anchor-name`, the list declares
  `position-anchor` naming it and resolves its own inset from
  `anchor(bottom)` / `anchor(left)`. Collision handling is
  `position-try-fallbacks`.

  So there is no measurement. Freehand computes no rectangle, installs no
  `ResizeObserver`, `IntersectionObserver` or scroll listener, and holds no
  geometry in app-db — the whole style map is LEXICAL CONSTANTS, which is a
  property a structural render can assert and `FH-CTRL-020` does. The
  substrate's contribution is the desired-state pair and the ref that
  reconciles it; everything downstream of that is the browser's own layout
  engine, which is what rf2-drpa3.182.8 acceptance 2 asks for and what no
  hand-rolled positioning loop can match for correctness.

  The cost of that choice is stated rather than hidden: `anchor-name` and
  friends are Chromium-first, so the ANCHORING claim is a browser claim
  bound to the mounted tier, while everything the tree can carry is proven
  on both hosts. A substrate that shipped its own geometry would have
  bought portability with a permanent maintenance surface and a wrong
  answer at every scroll container.

  ## Where the state is

  In app-db, under one record keyed by the caller's `:control` address —
  the open flag, the query, the highlighted index, the supersession token,
  the in-flight request and the settled results. Every one of them is
  readable through an ordinary subscription with nothing mounted, which is
  what makes the async rows below provable headlessly. What is NOT in
  app-db is geometry, a node, a timer handle or a channel: there is nothing
  to leak because there is nothing held.

  ## Three fences, and every one of them is a comparison over frame data

  1. **The GENERATION fence** (`v/controller-current?`). The draft belongs
     to the caller's `:reset-key`; a superseded draft is INVISIBLE rather
     than erased, so a caller that rejects a selection by reasserting the
     value it already had is still seen.
  2. **The TOKEN fence.** Every keystroke mints a token, and the debounce
     IS that comparison: a scheduled search firing for a superseded token
     does nothing, so there is no timer to cancel and no handle to leak. A
     reply naming a token other than the in-flight one is dropped, and a
     keystroke REVOKES the in-flight claim it supersedes so no acceptance
     gap opens between the keystroke and the next schedule.
  3. **The ANSWERS-WHICH-QUESTION fence.** A settled result set is stored
     WITH the query it answers and shown only while that is still what the
     user has typed. A late reply therefore cannot rewrite the input — it
     has nowhere to write that the typing lives.

  Cleanup follows the causal OWNER, never the render: one `released` event,
  dispatched from whichever exit path actually happened, and after it a
  scheduled search finds no record, a late reply finds no record, and the
  control holds nothing.

  Dev/test scope ONLY. This namespace lives under `test/` and nothing in a
  production bundle may reach it."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]
            [re-frame.freehand.controls :as c]
            [re-frame.freehand.web :as web]))

;; ===========================================================================
;; THE CONTROL
;; ===========================================================================

(def records-root
  "Where this control keeps its protocol records — an application-visible
  app-db key, because the record IS ordinary frame data."
  :kit.ui/controls)

(def typeahead-kind
  "The controller family. Half of the record's address; the other half is
  the caller's `:control`."
  :kit.ui/typeahead)

(def default-debounce-ms
  "The quiet period a keystroke waits out before its search goes — a
  library default the caller may override, never a substrate constant."
  120)

;; ---------------------------------------------------------------------------
;; The keyboard law, as a pure function
;; ---------------------------------------------------------------------------

(def next-key "ArrowDown")
(def previous-key "ArrowUp")

(defn key-intent
  "This control's keyboard grammar as a pure function of the two scalars a
  host keyboard event carries: the `key` and whether an IME composition is
  in flight. Answers `:commit`, `:cancel`, `:next`, `:previous` or nil.

      (key-intent \"Enter\"     false)  ;=> :commit
      (key-intent \"Enter\"     true)   ;=> nil
      (key-intent \"ArrowDown\" false)  ;=> :next
      (key-intent \"ArrowDown\" true)   ;=> nil

  **Enter and Escape are DELEGATED to [[re-frame.freehand.controls/key-intent]]
  rather than re-decided here.** That is the whole point of the kit
  publishing it: a composing Enter belongs to the input method, and a
  control that re-implements the rule re-implements the bug — mid-phrase
  domain events for every user of a CJK input method, invisible to every
  keyboard the author owns.

  The composition SCALAR is the kit's too:
  [[re-frame.freehand.controls/composing?]] reads it off the host event, so
  this witness holds no copy of the `nativeEvent`/`keyCode` 229 adaptation.

  The ARROWS are this control's own, and they are withheld during a
  composition for exactly the same reason: while a candidate window is
  open, ArrowDown moves through the IME's candidates and never through the
  listbox. Stealing it there is the same defect one key over.

  Pure, so the whole grammar is provable by CALLING it — no browser, no
  host event, and no mount."
  [key composing?]
  (or (c/key-intent key composing?)
      (when-not composing?
        (condp = key
          next-key     :next
          previous-key :previous
          nil))))

(defn- host-key
  "The `key` of a host keyboard event. The JVM arm reads a map, so a
  structural test speaks the same shape."
  [e]
  #?(:cljs (.-key e)
     :clj  (:key e)))

;; ---------------------------------------------------------------------------
;; The record, and the fences over it
;; ---------------------------------------------------------------------------

(defn- record [db k] (get-in db [records-root k]))

(defn answers-current-question?
  "Is the settled result set an answer to what the user has typed NOW?

  A settle never writes the typed text — it writes a result set tagged with
  the query it answers — so `did a late reply clobber my typing` cannot
  arise: the reply has nowhere to write that the typing lives."
  [r]
  (and (some? r) (contains? r :results-for) (= (:results-for r) (:query r))))

(defn visible-results
  [r]
  (if (answers-current-question? r) (vec (:results r)) []))

;; ---------------------------------------------------------------------------
;; The public part addresses
;; ---------------------------------------------------------------------------

(def component-id "kit/typeahead")

(def parts
  "The PUBLIC part addresses. A part id is API — a caller styles and a tool
  reads through these and through nothing else. `retry` is the one that is
  state-dependent: it exists only while a search has failed."
  ["root" "label" "input" "status" "list" "option" "retry"])

(defn anchor-name
  "The CSS anchor name this instance's input publishes and its list
  positions against. Derived from the instance name, so two typeaheads on
  one page anchor to their OWN inputs rather than to each other's."
  [nm]
  (str "--kit-typeahead-" nm))

(defn input-id [nm] (str "kit-typeahead-" nm))
(defn listbox-id [nm] (str "kit-typeahead-" nm "-list"))
(defn option-id [nm i] (str "kit-typeahead-" nm "-option-" i))

(def list-style
  "The list's whole style, and it is a LEXICAL CONSTANT — which is the
  claim, not the convenience.

  `position-anchor` names the input's `anchor-name`; the insets resolve
  from `anchor(bottom)` and `anchor(left)`, so the list's top-left corner
  IS the input's bottom-left corner; `position-try-fallbacks` hands
  collision handling to the browser's own layout engine. Nothing here is
  measured, so nothing here can be stale, and there is no observer to
  release.

  `:right` and `:bottom` are `auto` deliberately: the UA stylesheet gives
  every popover `inset: 0`, and an inset that stayed 0 on the other two
  edges would stretch the list to the viewport instead of sizing it to its
  content. `:margin 0` retires the UA's centring margin for the same
  reason.

  The value is a var rather than a literal in the body so the mounted suite
  can assert the DOM against the same constants the structural suite reads
  off the tree, with no second copy to drift."
  {:position               "fixed"
   :position-anchor        :ANCHOR                ; replaced per instance
   :top                    "anchor(bottom)"
   :left                   "anchor(left)"
   :right                  "auto"
   :bottom                 "auto"
   :margin                 0
   :position-try-fallbacks "flip-block"
   :min-width              "anchor-size(width)"})

(defn list-style-for
  "[[list-style]] with the instance's anchor name substituted in."
  [nm]
  (assoc list-style :position-anchor (anchor-name nm)))

;; ---------------------------------------------------------------------------
;; The dataflow — two reads and eight transitions
;; ---------------------------------------------------------------------------

(defn register!
  "Register the control's whole dataflow. Ordinary `reg-sub` / `reg-event`
  throughout: there is no control-specific registration mechanism, and
  that is the ergonomics claim rather than an accident."
  []

  ;; --- The reads -----------------------------------------------------

  ;; THE GENERATION FENCE, read side. The draft while it belongs to the
  ;; caller's current generation, the caller's committed value otherwise.
  ;; A superseded draft is not erased — it is invisible — which is why a
  ;; caller's rejection costs no write and no render-time mutation.
  (rf/reg-sub :kit.ui.typeahead/text
    (fn [db [_ k revision baseline]]
      (let [r (record db k)]
        (if (v/controller-current? (:reset-key r) revision)
          (:query r)
          baseline))))

  ;; ONE status read for the whole control. Open flag, highlight, in-flight
  ;; state, error and the visible results move together and are rendered
  ;; together; splitting them would buy a finer invalidation on state that
  ;; changes at once.
  (rf/reg-sub :kit.ui.typeahead/status
    (fn [db [_ k revision]]
      (let [r        (record db k)
            current? (v/controller-current? (:reset-key r) revision)
            results  (if current? (visible-results r) [])]
        {:open?    (boolean (and current? (:open? r) (seq results)))
         :active   (:active r 0)
         :pending? (boolean (and current? (some? (:in-flight r))))
         :error    (when current? (:error r))
         :results  results
         ;; The user has typed something we do not have an answer for yet.
         ;; Distinct from `:pending?`: between the keystroke and the
         ;; debounce firing nothing is in flight, and the list is still not
         ;; to be trusted.
         :stale?   (boolean (and current?
                                 (seq (:query r))
                                 (not (answers-current-question? r))))})))

  ;; --- The transitions -----------------------------------------------

  ;; A keystroke. It mints a TOKEN and schedules the search behind it
  ;; through the framework's own `:dispatch-later`; the token is what makes
  ;; a superseded schedule inert. An empty query schedules nothing — there
  ;; is no question to ask.
  ;;
  ;; It also REVOKES the in-flight claim, because the request that claim
  ;; names has just been superseded. Without this the claim outlives its
  ;; request until the NEXT debounce fires, and in that window a reply for
  ;; the superseded token still names the standing claim and is believed.
  (rf/reg-event :kit.ui.typeahead/typed
    (fn [{:keys [db]} [_ k revision ms on-search text]]
      (let [r     (record db k)
            token (inc (:token r 0))
            r*    (-> (or r {})
                      (assoc :reset-key revision
                             :query     text
                             :open?     true
                             :active    0
                             :token     token
                             :error     nil)
                      (dissoc :in-flight))]
        (cond-> {:db (assoc-in db [records-root k] r*)}
          (not (str/blank? text))
          (assoc :fx [[:dispatch-later
                       {:ms    ms
                        :event [:kit.ui.typeahead/due k token on-search]}]])))))

  ;; THE DEBOUNCE, and it is one comparison. A timer firing for a token the
  ;; record has moved past belongs to a keystroke the user already
  ;; replaced, so it asks nothing and leaves nothing behind. There is no
  ;; cancellation because there is nothing to cancel.
  (rf/reg-event :kit.ui.typeahead/due
    (fn [{:keys [db]} [_ k token on-search]]
      (let [r (record db k)]
        (if (and r (= token (:token r)) (not (str/blank? (:query r))))
          {:db (assoc-in db [records-root k :in-flight] {:token token :query (:query r)})
           ;; The caller receives the query and a REPLY PREFIX. It cannot
           ;; lose the correlation because it never builds one — it conj's
           ;; its outcome onto what it was handed.
           :fx [[:dispatch (conj (vec on-search)
                                 (:query r)
                                 [:kit.ui.typeahead/replied k token])]]}
          {}))))

  ;; THE CORRELATION AND SUPERSESSION FENCE. A reply lands only when it
  ;; names the request that is in flight. A superseded request whose answer
  ;; was slower, a cancelled request answering anyway, a reply arriving
  ;; after the control was released — each is INERT. Cancellation is
  ;; best-effort everywhere; a guard is the only thing that survives its
  ;; failure.
  (rf/reg-event :kit.ui.typeahead/replied
    (fn [{:keys [db]} [_ k token reply]]
      (let [r (record db k)
            f (:in-flight r)]
        (if (and r f (= token (:token f)))
          {:db (update-in db [records-root k]
                          (fn [rec]
                            (-> rec
                                (dissoc :in-flight)
                                (assoc :results     (vec (:results reply))
                                       :results-for (:query f)
                                       :error       (:error reply)
                                       :active      0))))}
          {}))))

  ;; A retry is a NEW request and takes a new token — which is exactly what
  ;; makes the failed one's late answer inert rather than a resurrection.
  (rf/reg-event :kit.ui.typeahead/retried
    (fn [{:keys [db]} [_ k on-search]]
      (let [r     (record db k)
            token (inc (:token r 0))]
        (if (and r (not (str/blank? (:query r))))
          {:db (update-in db [records-root k] assoc :token token :error nil)
           :fx [[:dispatch [:kit.ui.typeahead/due k token on-search]]]}
          {}))))

  ;; THE KEYBOARD, arriving as a SEMANTIC INTENT rather than a keystroke.
  ;; The view decided `:next` / `:previous` / `:commit` / `:cancel` through
  ;; the pure [[key-intent]], so this handler — and the trace it leaves —
  ;; is about what the user MEANT.
  ;;
  ;; `:seen` counts the intents that reached here, and it is the reason the
  ;; browser's composing-Enter row can distinguish "the control declined to
  ;; dispatch" from "the keydown was never wired at all". Without it,
  ;; "a composing Enter commits nothing" is equally green when nothing is
  ;; listening.
  (rf/reg-event :kit.ui.typeahead/keyed
    (fn [{:keys [db]} [_ k revision on-select intent]]
      (let [r (record db k)]
        (if-not (and r (v/controller-current? (:reset-key r) revision))
          {}
          (let [db      (update-in db [records-root k :seen] (fnil inc 0))
                results (visible-results r)
                n       (count results)
                active  (:active r 0)
                move    (fn [i]
                          {:db (assoc-in db [records-root k :active]
                                         (max 0 (min (dec (max n 1)) i)))})]
            (case intent
              :next     (move (inc active))
              :previous (move (dec active))
              ;; Enter commits the highlighted option and RETIRES the
              ;; record, so the retirement and the domain event it causes
              ;; settle as one epoch.
              :commit   (if (pos? n)
                          {:db (update db records-root dissoc k)
                           :fx [[:dispatch (conj (vec on-select)
                                                 (:value (get results active)))]]}
                          {:db db})
              ;; Escape closes the list and KEEPS the draft: the user is
              ;; still editing, only the suggestions went away.
              :cancel   {:db (assoc-in db [records-root k :open?] false)}
              {:db db}))))))

  ;; A pointer choice — the same commit, addressed by index rather than by
  ;; the highlight, and fenced on the same generation.
  (rf/reg-event :kit.ui.typeahead/chosen
    (fn [{:keys [db]} [_ k revision i on-select]]
      (let [r (record db k)]
        (if (v/controller-current? (:reset-key r) revision)
          (let [opt (get (visible-results r) i)]
            (cond-> {:db (update db records-root dissoc k)}
              opt (assoc :fx [[:dispatch (conj (vec on-select) (:value opt))]])))
          {}))))

  ;; THE PLATFORM'S OWN DISMISSAL, reconciled. A `popover` closes itself on
  ;; Escape and on a light dismiss, and the substrate writes NO application
  ;; state on the browser's behalf — the `toggle` event reaches the author,
  ;; and this is the author's handler. So the record follows the platform
  ;; rather than the platform being fought.
  (rf/reg-event :kit.ui.typeahead/toggle-reported
    (fn [{:keys [db]} [_ k new-state]]
      (if (record db k)
        {:db (assoc-in db [records-root k :open?] (= "open" new-state))}
        {})))

  ;; THE END EVENT. One event, dispatched from whichever exit path actually
  ;; happened — and after it a scheduled search finds no record, a late
  ;; reply finds no record, and the control holds nothing. It goes through
  ;; the KIT's own release rather than a second spelling of `dissoc`.
  (rf/reg-event :kit.ui.typeahead/released
    (fn [{:keys [db]} [_ k]]
      {:db (c/release db [records-root k])})))

;; ---------------------------------------------------------------------------
;; The view — one instance identity across input, list and status
;; ---------------------------------------------------------------------------
;;
;; THE COMPILED TIER, AND WHY THIS CONTROL DOES NOT REACH IT. FH-CTRL-020's
;; applicability is `interpreted jvm browser`, and that is a LIMIT rather
;; than an omission. `typeahead` is outside the compiled grammar as it
;; stands, and the grammar's own read-only checker is what says so:
;;
;;     (v/check "test/re_frame/freehand/typeahead_witness.cljc")
;;     ;; => :re-frame.freehand.typeahead-witness/typeahead   ELIGIBLE? false
;;     ;;      :id       :rf.ui.compile/loop-capturing-handler
;;     ;;      :form     [:kit.ui.typeahead/chosen k g i on-select]
;;     ;;      :recovery [:keep-interpreted]
;;     ;;    :re-frame.freehand.typeahead-witness/reviewer-form ELIGIBLE? true
;;
;; The option's commit is an event site inside the `for` over the results,
;; and it closes over that loop's own `i`. A compiled site is LEXICAL — one
;; site, one handler, settled at build time — and a handler whose arguments
;; vary per iteration is not one site. So the recovery ladder has exactly
;; ONE rung, and it is `:keep-interpreted`: this is the grammar declining,
;; not the author declining to try.
;;
;; The control keeps the ordinary shape anyway, because rendering options in
;; a loop is what a listbox IS, and contorting a witness to reach a tier it
;; has no performance reason to want would be the catalogue commitment this
;; bead's non-goals refuse. That the CALLER is eligible — `reviewer-form`,
;; `:compile-eligible? true` in the same report — is what makes the verdict
;; a fact about this body rather than about the file.
;;
;; None of the above is taken on trust. `typeahead-witness-cljs-test`'s
;; `fh-ctrl-020-the-compiled-tier-refuses-this-control-and-says-why` runs the
;; REAL checker on the JVM and compares it against the fixture's `:compiled`
;; value, so the day the grammar learns a per-item lexical site, the row goes
;; red and this paragraph is revisited rather than quietly outliving its
;; reason.

(def typeahead-props
  "The declared props, hoisted so the declaration reads as one thing and the
  schema can be cited — by the structural suite, and by anything else that
  wants the control's public shape — without transcribing a second copy of
  it."
  [:map
   [:name :string]
   [:label :string]
   [:control :some]
   [:reset-key :some]
   [:value :string]
   [:on-search :vector]
   [:on-select :vector]
   [:debounce-ms {:optional true} :int]])

(v/defview typeahead
  "(v/defview) A composed asynchronous control: a controlled input, an
  anchored suggestion popover and a status region, all under ONE instance
  identity.

      [typeahead {:name      \"reviewer\"
                  :label     \"Reviewer\"
                  :control   [:doc id :reviewer]
                  :reset-key revision
                  :value     committed
                  :on-search [:desk/search-requested]
                  :on-select [:desk/reviewer-chosen id]}]

  `:control` is the domain identity that owns the state; `:reset-key` is
  the caller's baseline generation and is REQUIRED, because this control
  holds a draft the caller can reject and a rejection is often spelled by
  reasserting the value it already had. `:on-search` is the caller's
  request intent — the control hands it the query and a reply prefix —
  and `:on-select` is the caller's commit intent.

  The input's `:on-input` is a LITERAL event vector on a controlled native,
  which is what keeps this site inside D009's synchronous door: the
  keystroke's state change lands before the listener returns, so React's
  end-of-event value restore finds the value it just rendered rather than
  clobbering the caret.

  The `:on-key-down` is a `v/event`, because the keyboard branch is a
  function of two scalars the site has to READ — and what it dispatches is
  a semantic intent, never a keystroke.

  Focus never leaves the input. The highlight is `aria-activedescendant`
  and the options are not focusable, which is the ARIA combobox pattern and
  also the only arrangement in which typing continues to work while a
  listbox is open."
  {:props typeahead-props}
  [{:keys [name label value on-search on-select debounce-ms] :as props}]
  (let [k    (v/controller-key typeahead-kind props)
        g    (v/controller-revision typeahead-kind props)
        st   (v/sub [:kit.ui.typeahead/status k g])
        text (v/sub [:kit.ui.typeahead/text k g value])
        ms   (or debounce-ms default-debounce-ms)]
    [:div {:data-component component-id
           :data-part      "root"
           :data-field     name}
     [:label {:data-part "label" :for (input-id name)} label]
     [:input {:data-part             "input"
              :id                    (input-id name)
              :type                  "text"
              :role                  "combobox"
              :autocomplete          "off"
              :value                 text
              :aria-expanded         (if (:open? st) "true" "false")
              :aria-controls         (listbox-id name)
              :aria-activedescendant (when (:open? st)
                                       (option-id name (:active st)))
              ;; The anchor half of the anchoring, and the whole of this
              ;; control's contribution to it.
              :style                 {:anchor-name (anchor-name name)}
              :on-input              [:kit.ui.typeahead/typed k g ms on-search ::v/value]
              :on-key-down           (v/event [e]
                                       (when-let [intent (key-intent (host-key e)
                                                                     (c/composing? e))]
                                         [:kit.ui.typeahead/keyed k g on-select intent]))}]
     ;; The status is DERIVED, never a local flag: `:pending?` is the
     ;; in-flight request's own state and `:stale?` is the gap between what
     ;; the user has typed and what has been answered.
     [:div {:data-part "status" :role "status"}
      (cond
        (:error st)    (str "Search failed: " (:error st))
        (:pending? st) "Searching…"
        (:stale? st)   "Searching…"
        :else          "")]
     (when (:error st)
       [:button {:data-part "retry"
                 :type      "button"
                 :on-click  [:kit.ui.typeahead/retried k on-search]}
        "Retry"])
     ;; THE ANCHORED POPOVER. It is rendered unconditionally and its
     ;; OPENNESS is the desired-state fact — that is what makes the
     ;; platform's own dismissal observable through `:on-toggle` instead of
     ;; the element vanishing under it. It follows the input in document
     ;; order, which is what makes the input an acceptable anchor for it.
     [:ul {:data-part          "list"
           :id                 (listbox-id name)
           :role               "listbox"
           :popover            :auto
           ::web/popover-open? (:open? st)
           :on-toggle          [:kit.ui.typeahead/toggle-reported k ::v/new-state]
           :style              (list-style-for name)}
      (for [[i opt] (map-indexed vector (:results st))]
        [:li {:key           i
              :id            (option-id name i)
              :data-part     "option"
              :role          "option"
              :aria-selected (if (= i (:active st)) "true" "false")
              :on-click      [:kit.ui.typeahead/chosen k g i on-select]}
         (:label opt)])]]))

;; ===========================================================================
;; THE APPLICATION — assigning a reviewer to a document
;; ===========================================================================
;;
;; The application owns the request, the committed value, the revision it
;; advances when it makes a new baseline decision, and the exit path.

(def requests-key :desk/requests)
(def selections-key :desk/selections)

(defn register-app!
  "The application's own registrations.

  `:desk/search-requested` deliberately performs NO transport. It records
  the request and its reply prefix, and the tests dispatch the reply by
  hand — which is what makes latency a VARIABLE rather than a wait, and
  what lets the supersession rows be deterministic instead of timed."
  []
  (rf/reg-sub :desk/reviewer          (fn [db [_ id]] (get-in db [:doc id :reviewer] "")))
  (rf/reg-sub :desk/reviewer-revision (fn [db [_ id]] (get-in db [:doc id :reviewer-revision] 0)))
  (rf/reg-sub :desk/requests          (fn [db _] (get db requests-key [])))

  (rf/reg-event :desk/search-requested
    (fn [{:keys [db]} [_ query reply-prefix]]
      {:db (update db requests-key (fnil conj []) {:query query :reply-to reply-prefix})}))

  ;; The commit. The revision advances because this is a new baseline
  ;; decision, and `:desk/selections` counts what actually landed.
  (rf/reg-event :desk/reviewer-chosen
    (fn [{:keys [db]} [_ id value]]
      {:db (-> db
               (assoc-in [:doc id :reviewer] value)
               (update-in [:doc id :reviewer-revision] (fnil inc 0))
               (update selections-key (fnil conj []) value))}))

  ;; The exit path — a route leave, a dialog closing, a record being
  ;; archived. Whatever it actually is, it is an ordinary event, and it
  ;; releases the control.
  (rf/reg-event :desk/closed
    (fn [{:keys [db]} [_ k]]
      {:fx [[:dispatch [:kit.ui.typeahead/released k]]]})))

(v/defview reviewer-form
  "(v/defview) One document's reviewer field — the whole call site.

  `:debounce-ms` rides through because a SUITE has to be able to set it
  past the run. `:dispatch-later` is the real framework effect, so a
  keystroke really does arm a real host timer, and a suite that let one
  fire after its own frame was torn down would dispatch into a destroyed
  frame at an arbitrary later moment — a failure that lands in whichever
  unrelated suite happens to be running. Setting the quiet period past the
  run and delivering the delayed event by hand drives the production path
  with the clock as a parameter instead."
  {:props [:map
           [:id :any]
           [:field-name :string]
           [:debounce-ms {:optional true} :int]]}
  [{:keys [id field-name debounce-ms]}]
  (let [value (v/sub [:desk/reviewer id])
        rev   (v/sub [:desk/reviewer-revision id])]
    [:form {:data-component "kit/reviewer-form" :data-doc (str id)}
     [typeahead {:name        field-name
                 :label       "Reviewer"
                 :control     [:doc id :reviewer]
                 :reset-key   rev
                 :value       value
                 :debounce-ms (or debounce-ms 20)
                 :on-search   [:desk/search-requested]
                 :on-select   [:desk/reviewer-chosen id]}]]))
