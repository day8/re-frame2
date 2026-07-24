(ns re-frame.freehand.pilot-typeahead
  "THE SECOND COMPONENT PILOT, CASE C — an asynchronous typeahead, built
  the way a component-library author would build it, plus the application
  that calls it.

  Everything here is CONSUMER code: `v/defview`, `v/sub`, event vectors,
  `reg-event` and `reg-sub`. It is the artefact the fitness harness's
  CASE C requirements (`docs/design/freehand/studio/fitness-harness.md`,
  §C.2) are judged against, one requirement at a time, in
  `pilot-typeahead-cljs-test` and `pilot-typeahead-dom-cljs-test`.

  ## The shape of the problem

  A typeahead is where a buffered draft and real latency meet. The user
  types, a request goes out, the user types again, and now two answers are
  in flight for two different questions. Every naive implementation
  corrupts here: the slower reply lands last and overwrites the faster
  one, or a settle seeds the input and eats the keystrokes typed since, or
  a cancelled request's answer arrives anyway and is believed.

  re-com's answer is a core.async channel and a hand-rolled debounce
  closed over inside the component — invisible to traces, untestable
  headlessly. The corpus's app-tier answer is correlated replies with a
  generation gate, and it is the right answer; this control is that answer
  wearing a component's clothes.

  ## Four fences, and every one of them is a comparison over frame data

  1. **The GENERATION fence** (`v/controller-current?`, D016). The draft belongs
     to the caller's `:reset-key`; a superseded draft is INVISIBLE rather
     than erased, so a caller that rejects a selection by reasserting the
     value it already had is still seen.
  2. **The TOKEN fence.** Every keystroke mints a token. A scheduled search
     that fires for a superseded token does nothing, which is how the
     debounce works — see below. A reply that names a token other than the
     in-flight one is dropped.
  3. **The ANSWERS-WHICH-QUESTION fence.** A settled result set is stored
     WITH the query it answers, and the view shows it only while that
     query is still what the user has typed. So a late reply cannot
     rewrite the input, and cannot show a list that answers a question the
     user has moved on from — it simply is not the current answer yet.
  4. **The LIFETIME fence.** One end event, `released`, drops the record.
     Every exit path — unmount, route leave, the dialog closing — dispatches
     that one event, and after it a late reply finds no record and lands
     nowhere.

  ## Where the debounce lives, and what it costs

  In the EVENT LAYER, as a delayed dispatch that is inert unless it is
  still current. A keystroke bumps the token and schedules
  `[:acme.ui.typeahead/due k token …]` through the framework's own
  `:dispatch-later`; when that fires it compares its token against the
  record's, and a keystroke since has already moved it.

  So there is no cancellation, and there is nothing to cancel — which is
  the point. re-com's debounce owns a channel it must close; a timer-based
  one owns a handle it must clear, and a handle nobody clears is the leak
  R-B3 is about. Here a superseded timer fires, finds it is not current,
  and returns `{}`.

  The cost is stated rather than hidden: typing N characters arms N timers
  and settles N-1 events that change nothing. That is one no-op event per
  keystroke, every one of them visible in the trace — against a hidden
  channel and a closure. The pilot judges that the better trade and says
  so out loud.

  ## What the LIBRARY owns and what the CALLER owns

  The library owns the draft, the token, the debounce, the correlation and
  the result-freshness rule. The caller owns the actual request: the
  library dispatches the caller's `:on-search` prefix with the query and a
  REPLY PREFIX the caller conj's its outcome onto. The caller therefore
  cannot lose the correlation, because it never constructs one.

  ## What is NOT here

  Resources (Spec 016) are the framework's real answer to a remote read —
  identity, scope, staleness, invalidation, cancellation, `:reply-to`. A
  control that could declare its search AS a resource parameterised by its
  instance key would get supersession and cancellation as policy rather
  than as the four fences above. Freehand does not depend on the resources
  artefact and this pilot deliberately does not add one, so what is proven
  here is that the view substrate alone is SUFFICIENT — not that it is the
  end state."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.freehand :as v]))

;; ===========================================================================
;; THE COMPONENT LIBRARY — `acme.ui`
;; ===========================================================================

(def records-root
  "Where this library keeps its controller records — a library decision."
  :acme.ui/controllers)

(def typeahead-kind
  "The typeahead controller family."
  :acme.ui/typeahead)

(def parts
  "The PUBLIC part addresses, by component id. A part id is API."
  {"acme/typeahead" ["root" "label" "input" "status" "list" "option" "retry"]})

(def default-debounce-ms
  "The quiet period a keystroke waits out before its search goes. A
  library default the caller may override, not a substrate constant."
  120)

;; ---------------------------------------------------------------------------
;; The record, and the four fences over it
;; ---------------------------------------------------------------------------

(defn- record [db k] (get-in db [records-root k]))

(defn- answers-current-question?
  "Is the settled result set an answer to what the user has typed NOW?

  This is fence 3, and it is the whole of R-C1: a settle NEVER writes the
  typed text, it writes a result set tagged with the query it answers. So
  the question `did a late reply clobber my typing` cannot arise — the
  reply has nowhere to write that the typing lives."
  [r]
  (and (some? r) (contains? r :results-for) (= (:results-for r) (:query r))))

(defn- visible-results
  [r]
  (if (answers-current-question? r) (vec (:results r)) []))

(defn register!
  "Register the library's whole dataflow: two reads and six transitions."
  []
  ;; --- The reads -----------------------------------------------------

  ;; THE GENERATION FENCE, read side. The draft while it belongs to the
  ;; caller's current generation, the caller's committed label otherwise.
  ;; A superseded draft is not erased — it is invisible, which is why a
  ;; caller's rejection costs no write and no render-time mutation.
  (rf/reg-sub :acme.ui.typeahead/text
    (fn [db [_ k revision baseline]]
      (let [r (record db k)]
        (if (v/controller-current? (:reset-key r) revision)
          (:query r)
          baseline))))

  ;; ONE status read for the whole control. Open flag, highlight, in-flight
  ;; status, error and the visible results move together and are rendered
  ;; together; splitting them would buy a finer invalidation on state that
  ;; changes at once.
  (rf/reg-sub :acme.ui.typeahead/status
    (fn [db [_ k revision]]
      (let [r        (record db k)
            current? (v/controller-current? (:reset-key r) revision)]
        {:open?    (boolean (and current? (:open? r)))
         :active   (:active r 0)
         :pending? (boolean (and current? (some? (:in-flight r))))
         :error    (when current? (:error r))
         :results  (if current? (visible-results r) [])
         ;; The user has typed something we do not yet have an answer for.
         ;; Distinct from `:pending?`: between the keystroke and the
         ;; debounce firing nothing is in flight, and the list is still
         ;; not to be trusted.
         :stale?   (boolean (and current?
                                 (seq (:query r))
                                 (not (answers-current-question? r))))})))

  ;; --- The transitions -----------------------------------------------

  ;; A keystroke. It mints a TOKEN and schedules the search behind it; the
  ;; schedule is ordinary `:dispatch-later`, and the token is what makes a
  ;; superseded one inert. An empty query schedules nothing — there is no
  ;; question to ask.
  ;;
  ;; It also REVOKES the in-flight claim, because the request that claim
  ;; names has just been superseded — the keystroke moved the record past
  ;; its token. The claim is the request token stamped into the slot it
  ;; wrote, so dropping the slot drops the token with it: existence and
  ;; identity stay one question. Without this the claim outlives its
  ;; request until the NEXT debounce fires, and in that window a reply for
  ;; the superseded token still names the standing claim and is believed —
  ;; visible corruption the moment the query returns to what that reply
  ;; answered. Supersession is the keystroke, not the next timer; the claim
  ;; is revoked the instant the keystroke lands, leaving no acceptance gap.
  (rf/reg-event :acme.ui.typeahead/typed
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
                        :event [:acme.ui.typeahead/due k token on-search]}]])))))

  ;; THE DEBOUNCE, and it is one comparison. A timer that fires for a
  ;; token the record has moved past belongs to a keystroke the user has
  ;; already replaced, so it asks nothing and leaves nothing behind. There
  ;; is no cancellation because there is nothing to cancel.
  (rf/reg-event :acme.ui.typeahead/due
    (fn [{:keys [db]} [_ k token on-search]]
      (let [r (record db k)]
        (if (and r (= token (:token r)) (not (str/blank? (:query r))))
          {:db (assoc-in db [records-root k :in-flight] {:token token :query (:query r)})
           ;; The caller receives the query and a REPLY PREFIX. It cannot
           ;; lose the correlation because it never builds one — it conj's
           ;; its outcome onto what it was handed.
           :fx [[:dispatch (conj (vec on-search)
                                 (:query r)
                                 [:acme.ui.typeahead/replied k token])]]}
          {}))))

  ;; THE CORRELATION AND SUPERSESSION FENCE. A reply lands only when it
  ;; names the request that is in flight. Everything else — a superseded
  ;; request whose answer was slower, a cancelled request answering
  ;; anyway, a reply arriving after the control was released — is INERT.
  ;; Cancellation is best-effort everywhere; a guard is the only thing
  ;; that survives its failure.
  (rf/reg-event :acme.ui.typeahead/replied
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

  ;; A retry is a NEW request, so it takes a new token — which is exactly
  ;; what makes the failed one's late answer inert rather than a
  ;; resurrection.
  (rf/reg-event :acme.ui.typeahead/retried
    (fn [{:keys [db]} [_ k on-search]]
      (let [r     (record db k)
            token (inc (:token r 0))]
        (if (and r (not (str/blank? (:query r))))
          {:db (update-in db [records-root k] assoc :token token :error nil)
           :fx [[:dispatch [:acme.ui.typeahead/due k token on-search]]]}
          {}))))

  ;; Choosing an option commits it to the caller and RETIRES the record —
  ;; one semantic event with its own effects, so the retirement and the
  ;; domain event it causes settle as one epoch. Fenced on the generation:
  ;; a choice made from a render the caller has already moved past speaks
  ;; for a generation nobody is displaying.
  (rf/reg-event :acme.ui.typeahead/chosen
    (fn [{:keys [db]} [_ k revision i on-select]]
      (let [r (record db k)]
        (if (v/controller-current? (:reset-key r) revision)
          (let [opt (get (visible-results r) i)]
            (cond-> {:db (update db records-root dissoc k)}
              opt (assoc :fx [[:dispatch (conj (vec on-select) (:value opt))]])))
          {}))))

  ;; The keyboard grammar as DATA, exactly as the dropdown's is.
  (rf/reg-event :acme.ui.typeahead/key-pressed
    (fn [{:keys [db]} [_ k revision on-select pressed]]
      (let [r      (record db k)
            n      (count (visible-results r))
            active (:active r 0)
            move   (fn [i] {:db (assoc-in db [records-root k :active]
                                          (max 0 (min (dec (max n 1)) i)))})]
        (cond
          (nil? r)              {}
          (= "ArrowDown" pressed) (move (inc active))
          (= "ArrowUp" pressed)   (move (dec active))
          (= "Enter" pressed)     (if (pos? n)
                                    {:db      (update db records-root dissoc k)
                                     :fx      [[:dispatch (conj (vec on-select)
                                                                (:value (get (visible-results r) active)))]]}
                                    {})
          ;; Escape closes the list and keeps the draft. The user is still
          ;; editing; only the suggestions went away.
          (= "Escape" pressed)    {:db (assoc-in db [records-root k :open?] false)}
          :else                   {}))))

  ;; THE END EVENT. One event, dispatched from every exit path there is —
  ;; and after it a scheduled search finds no record, an in-flight reply
  ;; finds no record, and the control holds nothing. Naming hypothetical
  ;; future end events is not completion; this is the actual one.
  (rf/reg-event :acme.ui.typeahead/released
    (fn [{:keys [db]} [_ k]]
      {:db (update db records-root dissoc k)})))

;; ---------------------------------------------------------------------------
;; The view — one instance identity across input, list and status
;; ---------------------------------------------------------------------------

(defn- option-id [nm i] (str "acme-typeahead-" nm "-option-" i))
(defn- list-id [nm] (str "acme-typeahead-" nm "-list"))

(v/defview typeahead
  "A composed asynchronous control: a controlled input, a suggestion list,
  and a status region — all under ONE instance identity.

  `:control` is the domain identity that owns the state; `:reset-key` is
  the caller's baseline generation and is REQUIRED, because this control
  holds a draft the caller can reject and a rejection is often spelled by
  reasserting the same value. `:on-search` is the caller's request
  intent — the library hands it the query and a reply prefix — and
  `:on-select` is the caller's commit intent.

  The input's `:on-input` is a LITERAL event vector at a controlled
  native, which is what keeps this site inside the substrate's
  synchronous door: the keystroke's state change lands before the listener
  returns, so React's end-of-event value restore finds the value it just
  rendered rather than clobbering the caret. The library owns that site
  and carries the caller's intent as an ARGUMENT inside it, so the site's
  proof stays static however the caller spells its own event."
  {:props [:map
           [:name :string]
           [:label :string]
           [:control :some]
           [:reset-key :some]
           [:value :string]
           [:on-search :vector]
           [:on-select :vector]
           [:debounce-ms {:optional true} :int]]}
  [{:keys [name label value on-search on-select debounce-ms] :as props}]
  (let [k    (v/controller-key typeahead-kind props)
        g    (v/controller-revision typeahead-kind props)
        st   (v/sub [:acme.ui.typeahead/status k g])
        text (v/sub [:acme.ui.typeahead/text k g value])
        ms   (or debounce-ms default-debounce-ms)]
    [:div {:data-component "acme/typeahead"
           :data-part      "root"
           :data-field     name}
     [:label {:data-part "label" :for (str "acme-typeahead-" name)}  label]
     [:input {:data-part             "input"
              :id                    (str "acme-typeahead-" name)
              :type                  "text"
              :role                  "combobox"
              :autocomplete          "off"
              :value                 text
              :aria-expanded         (if (:open? st) "true" "false")
              :aria-controls         (list-id name)
              :aria-activedescendant (when (and (:open? st) (seq (:results st)))
                                       (option-id name (:active st)))
              :on-input              [:acme.ui.typeahead/typed k g ms on-search ::v/value]
              :on-key-down           [:acme.ui.typeahead/key-pressed k g on-select ::v/key]}]
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
                 :on-click  [:acme.ui.typeahead/retried k on-search]}
        "Retry"])
     (when (:open? st)
       [:ul {:data-part "list" :id (list-id name) :role "listbox"}
        (for [[i opt] (map-indexed vector (:results st))]
          [:li {:key           i
                :id            (option-id name i)
                :data-part     "option"
                :role          "option"
                :aria-selected (if (= i (:active st)) "true" "false")
                :on-click      [:acme.ui.typeahead/chosen k g i on-select]}
           (:label opt)])])]))

;; ===========================================================================
;; THE APPLICATION — assigning a reviewer to a document
;; ===========================================================================
;;
;; The application owns the request, the committed value, the revision it
;; advances when it makes a new baseline decision, and the navigation guard.

(defn register-app!
  "The application's own registrations.

  `:app/search-requested` deliberately performs NO transport. It records
  the request and its reply prefix, and the tests dispatch the reply by
  hand — which is what makes latency a variable rather than a wait, and
  what lets the six race rows below be deterministic instead of timed."
  []
  (rf/reg-sub :app/reviewer          (fn [db [_ id]] (get-in db [:doc id :reviewer] "")))
  (rf/reg-sub :app/reviewer-revision (fn [db [_ id]] (get-in db [:doc id :reviewer-revision] 0)))
  (rf/reg-sub :app/reviewer-confirmed?
    (fn [db [_ id]] (boolean (get-in db [:doc id :reviewer-confirmed?]))))
  (rf/reg-sub :app/requests          (fn [db _] (get db ::requests [])))
  (rf/reg-sub :app/leaving?          (fn [db _] (boolean (get db ::leaving?))))

  ;; R-C9's derived gate: dirty is a fact about state, computed once and
  ;; read by BOTH the view and the guard handler.
  (rf/reg-sub :app/dirty?
    (fn [db [_ id]] (not (get-in db [:doc id :reviewer-confirmed?] true))))

  (rf/reg-event :app/search-requested
    (fn [{:keys [db]} [_ query reply-prefix]]
      {:db (update db ::requests (fnil conj []) {:query query :reply-to reply-prefix})}))

  ;; R-C6, the optimistic half: the chosen value shows AT ONCE, marked
  ;; unconfirmed, and the control is not disabled while the write is out.
  ;; The revision advances because this is a new baseline decision.
  (rf/reg-event :app/reviewer-chosen
    (fn [{:keys [db]} [_ id value]]
      {:db (-> db
               (assoc-in [:doc id :reviewer] value)
               (assoc-in [:doc id :reviewer-confirmed?] false)
               (update-in [:doc id :reviewer-revision] (fnil inc 0)))}))

  (rf/reg-event :app/reviewer-confirmed
    (fn [{:keys [db]} [_ id]]
      {:db (assoc-in db [:doc id :reviewer-confirmed?] true)}))

  ;; R-C6, the rollback half — and R-A3's case, in a different costume:
  ;; the server refuses, the application stands by the value it had, and
  ;; the two values may be EQUAL. Value-equality is blind to that; the
  ;; advanced revision is not.
  (rf/reg-event :app/reviewer-refused
    (fn [{:keys [db]} [_ id previous]]
      {:db (-> db
               (assoc-in [:doc id :reviewer] previous)
               (assoc-in [:doc id :reviewer-confirmed?] true)
               (update-in [:doc id :reviewer-revision] (fnil inc 0)))}))

  ;; R-C3: a route leave is an exit path, so it releases the control.
  (rf/reg-event :app/left
    (fn [{:keys [db]} [_ id k]]
      {:db (assoc db ::left id)
       :fx [[:dispatch [:acme.ui.typeahead/released k]]]}))

  ;; R-C9: leaving dirty blocks, and the confirmation is ordinary
  ;; state-driven view code rather than a framework dialog.
  (rf/reg-event :app/leave-attempted
    (fn [{:keys [db]} [_ id k]]
      (if (not (get-in db [:doc id :reviewer-confirmed?] true))
        {:db (assoc db ::leaving? true)}
        {:fx [[:dispatch [:app/left id k]]]}))))

(v/defview reviewer-form
  "One document's reviewer field."
  {:props [:map [:id :any]]}
  [{:keys [id]}]
  (let [value (v/sub [:app/reviewer id])
        rev   (v/sub [:app/reviewer-revision id])
        dirty (v/sub [:app/dirty? id])]
    [:form {:data-component "acme/reviewer-form" :data-doc (str id)}
     [typeahead {:name        (str "reviewer-" (clojure.core/name id))
                 :label       "Reviewer"
                 :control     [:doc id :reviewer]
                 :reset-key   rev
                 :value       value
                 :debounce-ms 20
                 :on-search   [:app/search-requested]
                 :on-select   [:app/reviewer-chosen id]}]
     [:output {:data-part "dirty"} (if dirty "unsaved" "saved")]]))

(v/defview reviewer-forms
  "Two documents on one page — two instances, two records, two independent
  async statuses, and no coordination between them."
  {:props [:map [:ids [:vector :any]]]}
  [{:keys [ids]}]
  [:div {:data-component "acme/reviewer-forms"}
   (for [id ids]
     [reviewer-form {:key id :id id}])])
