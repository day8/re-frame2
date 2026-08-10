(ns hicasso-testbed.core
  "THE CONTROLLED-INPUT TESTBED — the surface invariant I15 is driven
  against in three engines (rf2-hic-016).

  Every field below is an ORDINARY Hicasso field: a `:value` off a
  subscription and an intent vector at `:on-input`, written exactly as
  authoring.md tells a consumer to write one. There is no probe, no ref,
  no escape hatch and no test-only prop on any of them — which is the
  point. A testbed that reached past the authoring surface to make its
  assertions pass would be measuring the reach rather than the runtime.

  ## What is here, and why each field exists

  I15 says an interpreted controlled field converges within the turn that
  edited it, echoes only committed state, and preserves caret, selection
  and in-flight composition across that echo; that a rejected or
  normalised value echoes as the COMMITTED one; and that reset is an
  explicit revision preserving element identity. Those are properties of
  the relationship between a field and its model, so the fields differ
  only in the MODEL POLICY behind them — one field per policy, so a red
  row names its policy:

  | field | policy | what it can prove that the others cannot |
  |---|---|---|
  | `plain` | takes what is typed | the accepted keystroke SURVIVES — the converge's own trap (`converge-to!`'s docstring), where writing the handler's stale closure value back would wipe the character just typed |
  | `digits` | refuses any value containing a non-digit; the model does not move | rejection echoes the COMMITTED value in-turn, rather than the field silently keeping what was typed |
  | `empty` | refuses everything; the model is `\"\"` forever | owned `:value` wins by PRESENCE, not truthiness — `\"\"` is falsy, and a truthiness test here would leave the field uncontrolled and the typed character on screen |
  | `grouped` | digits only, comma-grouped in threes | caret preservation where the normalisation CHANGES THE LENGTH, which is the only case that distinguishes offset-from-the-end from an absolute position |
  | `upper` | upper-cases | caret preservation at a length the normalisation preserves, so a caret failure cannot hide behind a length change |
  | `notes` (`<textarea>`) | collapses whitespace runs | the same law on the other convergeable tag |
  | `revision` | takes what is typed | `::h/revision` re-baselines the field to the model, on the SAME DOM node |
  | `revision-strict` | refuses any value containing a non-digit | the same trigger with an OBSERVABLY DISTINCT target: mid-composition the model holds `\"42\"` while the field shows a kana draft, so a reset that wrote immediately would be visible as the draft disappearing. On the accepting `revision` field the model has already taken the draft, so an immediate write and a deferred one put the same string on screen and the row cannot red on the defect it names (#7815 audit) |
  | `flag` (checkbox) | toggles | the owned `::h/checked` pair, whose `false` is likewise a presence rather than a truth |

  `form-a` / `form-b` sit in a real `<form>` with a real reset button,
  because `form.reset()` returns a control to its `defaultValue` — and
  `defaultValue` is precisely the per-instance record
  `re-frame.hicasso.impl.controlled/last-rendered` reads. A form reset is
  therefore the one ordinary browser action that touches the converge's
  own bookkeeping, and rf2-hic-016 records its conduct (the full
  conformance matrix is hic-040's).

  `mountable` is rendered behind a flag so the driver can unmount a field
  mid-composition: the carve-out's shadow is one `useState` on a fiber and
  cannot outlive it, and \"cannot strand\" is worth witnessing rather than
  reasoning about.

  ## The trace, and the two instruments the OPERATOR needs

  Everything above is enough for a driver that can call
  `window.__RF2_HIC_TB__.model()`. The bounded native-IME session
  (`docs/design/hicasso/native-ime-manual-witness.md`) has no such
  luxury — Playwright's WebKit build on Windows is a browser shell with no
  devtools — and the #7787 audit found two of its checks claiming more
  than a screen full of `<input>`s can show:

  - **\"app-db clean until commit\"** cannot be read off a field. On a
    REFUSING field the snap-back looks identical whether the composing
    updates were dispatched or not, and on an ACCEPTING one a progressive
    write is visually idempotent. So the store is now on screen: one row
    per field carrying the committed value and the number of `:tb/edit`
    intents that have ARRIVED for it, arrivals counted whether the policy
    accepted or refused them. The count is the instrument — it moves when
    a composing update reaches the model, and a field that stays put while
    its counter climbs is the difference the checklist could not see.
  - **The mid-exchange edges** (a revision reset or an unmount arriving
    while a composition is live) cannot be triggered by clicking a button:
    the pointer-down closes the composition before the action lands. So
    the two edges are also reachable ARMED — click, return to the field,
    start composing, and the deferred dispatch arrives mid-exchange. The
    driver has no need of them (a programmatic click never moves focus),
    which is why they are the operator's instruments rather than the
    gate's.

    The arm RESOLVES the event it will fire at the moment it is armed,
    puts it in the readout, and carries it in the `:dispatch-later`
    payload. That is not decoration: it is the only way a driver can
    witness both arms without spending the five seconds, and the #7815
    audit found the gate pinning one arm while claiming both. An arm whose
    event is missing or wrong now reads wrong on screen immediately,
    which is also what the operator wants — the readout says what is
    queued rather than merely that something is.

  Both are ordinary re-frame2: a counter in the reducer, and
  `:dispatch-later`. Neither reaches past the authoring surface, and
  nothing in the app knows a session is happening.

  ## The harness door

  One `window.__RF2_HIC_TB__`, and it reads the model only. The DOM is the
  driver's to read through Playwright; what the driver cannot see from
  outside is what the STORE holds, and \"echoes only committed state\" is
  a claim about both halves. Nothing here mutates anything the app could
  not reach through an ordinary dispatch."
  (:require [clojure.string :as str]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]))

(def ^:private frame-id ::testbed)

;; ---------------------------------------------------------------------------
;; The model policies — one function, the whole of the difference between
;; the fields
;; ---------------------------------------------------------------------------

(defn- group-digits
  "`\"12345\"` -> `\"12,345\"`. Length-changing by construction, which is
  what makes the caret row on this field a real one."
  [s]
  (let [digits (str/replace s #"[^0-9]" "")]
    (if (<= (count digits) 3)
      digits
      (->> (reverse digits)
           (partition-all 3)
           (map (comp str/join reverse))
           reverse
           (str/join ",")))))

(defn- apply-policy
  "`old` is the COMMITTED value and `typed` is what the field now shows.
  A policy that returns `old` is a refusal; one that returns something
  else is a normalisation; one that returns `typed` accepts."
  [field old typed]
  (case field
    :plain    typed
    :revision typed
    :form-b   typed
    :digits   (if (re-matches #"[0-9]*" typed) typed old)
    ;; Refusing, and the refusal is the whole point: it is what makes the
    ;; reset's TARGET differ from the draft the field is showing while a
    ;; composition is live. See the table in the namespace docstring.
    :revision-strict (if (re-matches #"[0-9]*" typed) typed old)
    ;; Refusing, like `digits`, and deliberately: the unmount row needs the
    ;; field to be showing a draft the MODEL NEVER TOOK, or "no stranded
    ;; draft after a remount" is satisfied by the model having accepted it.
    :mountable (if (re-matches #"[0-9]*" typed) typed old)
    :empty    old
    :grouped  (group-digits typed)
    :upper    (str/upper-case typed)
    :form-a   (str/upper-case typed)
    :notes    (str/replace typed #"\s{2,}" " ")
    typed))

(def ^:private seed
  {:plain    "abc"
   :digits   "123"
   :empty    ""
   :grouped  "1,234"
   :upper    "ABC"
   :notes    "one two"
   :revision "keep"
   :revision-strict "42"
   :form-a   "FORM"
   :form-b   "form"
   :mountable "9"})

;; ---------------------------------------------------------------------------
;; Events and subscriptions — an ordinary re-frame2 app, nothing else
;; ---------------------------------------------------------------------------

(rf/reg-sub :tb/field (fn [db [_ field]] (get-in db [:fields field] "")))
(rf/reg-sub :tb/edits (fn [db [_ field]] (get-in db [:edits field] 0)))
(rf/reg-sub :tb/flag (fn [db _] (:flag db)))
(rf/reg-sub :tb/revision (fn [db _] (:revision db)))
(rf/reg-sub :tb/mounted? (fn [db _] (:mounted? db)))
(rf/reg-sub :tb/armed (fn [db _] (:armed db)))

(rf/reg-event :tb/seed
  (fn [_ _] {:db {:fields seed :edits {} :flag false :revision 0
                  :mounted? true :armed nil}}))

;; The arrival counter is bumped on EVERY intent, before the policy is
;; consulted — a refusal is an arrival too. That is the whole of what the
;; trace measures: "did this edit reach the store", asked separately from
;; "did the store take it".
(rf/reg-event :tb/edit
  (fn [{:keys [db]} [_ field typed]]
    {:db (-> db
             (assoc-in [:fields field]
                       (apply-policy field (get-in db [:fields field] "") typed))
             (update-in [:edits field] (fnil inc 0)))}))

(rf/reg-event :tb/toggle-flag
  (fn [{:keys [db]} [_ checked]] {:db (assoc db :flag (boolean checked))}))

;; The reset trigger, and the ONLY thing that fires it: an explicit caller
;; revision change, never a value comparison. HD-019's reset law.
(rf/reg-event :tb/bump-revision
  (fn [{:keys [db]} _] {:db (update db :revision inc)}))

;; An OUT-OF-BAND correction — a write that no keystroke caused, which is
;; the path `converge!` is deliberately not on (rf2-n3dxw). The driver uses
;; it to record what a range selection does across such a write.
(rf/reg-event :tb/correct
  (fn [{:keys [db]} [_ field value]] {:db (assoc-in db [:fields field] value)}))

(rf/reg-event :tb/toggle-mounted
  (fn [{:keys [db]} _] {:db (update db :mounted? not)}))

(rf/reg-event :tb/noop (fn [{:keys [db]} _] {:db db}))

;; ---------------------------------------------------------------------------
;; The armed edges — the operator's way of reaching mid-composition
;; ---------------------------------------------------------------------------

(def ^:private arm-delay-ms
  "Long enough to click the button, click back into the field and get a
  composition started. A human doing three things, not a machine doing
  one."
  5000)

(def ^:private armed-events
  "What each arm fires. The SAME events the immediate buttons dispatch —
  the deferral is the only difference, so an armed edge cannot drift from
  the immediate one — and a roster rather than a `case` inside the firing
  handler, because a lookup can be RESOLVED at arm time and a branch taken
  five seconds later cannot."
  {:bump    [:tb/bump-revision]
   :unmount [:tb/toggle-mounted]})

;; The arm resolves its event NOW and carries it both ways: into `:armed`
;; so the readout can say what is queued, and into the `:dispatch-later`
;; payload so two arms in flight cannot fire each other's event. Resolving
;; at arm time is what lets a driver witness both arms in the turn it
;; clicks them; before it, only the arm that was waited out was witnessed
;; at all, and the other could have been wired to nothing (#7815 audit).
(rf/reg-event :tb/arm
  (fn [{:keys [db]} [_ what]]
    (let [event (armed-events what)]
      {:db             (assoc db :armed {:what what :event event})
       :dispatch-later {:ms arm-delay-ms :event [:tb/fire-armed event]}})))

(rf/reg-event :tb/fire-armed
  (fn [{:keys [db]} [_ event]]
    {:db       (assoc db :armed nil)
     :dispatch event}))

;; ---------------------------------------------------------------------------
;; The views — every field written the way authoring.md writes one
;; ---------------------------------------------------------------------------

(h/defview text-field
  "One controlled `<input>`. `:value` off the subscription, an intent
  vector at `:on-input`, and nothing else."
  [{:keys [field]}]
  (let [id (name field)]
    [:input {:data-testid id
             :id          id
             :type        "text"
             :value       (h/sub [:tb/field field])
             :on-input    [:tb/edit field ::h/value]}]))

(h/defview notes-field
  "The same law on the other convergeable tag."
  [_]
  [:textarea {:data-testid "notes"
              :id          "notes"
              :value       (h/sub [:tb/field :notes])
              :on-input    [:tb/edit :notes ::h/value]}])

(h/defview revision-field
  "The reset trigger, carried as the author carries it: one `::h/revision`
  on the element's own attribute map. It is never an attribute, and the
  field is otherwise an ordinary controlled field.

  Two instances, on the one counter, differing only in the MODEL POLICY
  behind them — which is the difference the mid-composition witness turns
  on. `revision` accepts, so a reset arriving mid-exchange has nothing
  different to write; `revision-strict` refuses, so it has."
  [{:keys [field]}]
  (let [id (name field)]
    [:input {:data-testid   id
             :id            id
             :type          "text"
             ::h/revision   (h/sub [:tb/revision])
             :value         (h/sub [:tb/field field])
             :on-input      [:tb/edit field ::h/value]}]))

(h/defview flag-box
  "The owned `::h/checked` pair. `false` is a presence here, not a
  falsehood."
  [_]
  [:input {:data-testid "flag"
           :id          "flag"
           :type        "checkbox"
           :checked     (h/sub [:tb/flag])
           :on-change   [:tb/toggle-flag ::h/checked]}])

(h/defview reset-form
  "A real form with a real reset button, so `form.reset()` is the
  browser's own and not a simulation of it."
  [_]
  [:form {:data-testid "form" :id "form" :on-submit [:tb/noop]}
   [:input {:data-testid "form-a"
            :id          "form-a"
            :type        "text"
            :value       (h/sub [:tb/field :form-a])
            :on-input    [:tb/edit :form-a ::h/value]}]
   [:input {:data-testid "form-b"
            :id          "form-b"
            :type        "text"
            :value       (h/sub [:tb/field :form-b])
            :on-input    [:tb/edit :form-b ::h/value]}]
   [:button {:data-testid "form-reset" :type "reset"} "reset"]])

(h/defview mountable-field
  "Rendered behind a flag, so the driver can take the fiber away from
  under a live composition."
  [_]
  (if (h/sub [:tb/mounted?])
    [:input {:data-testid "mountable"
             :id          "mountable"
             :type        "text"
             :value       (h/sub [:tb/field :mountable])
             :on-input    [:tb/edit :mountable ::h/value]}]
    [:p {:data-testid "mountable-gone"} "unmounted"]))

(def ^:private traced-fields
  "Every field, in a stable order, so the trace is a complete reading of
  the store rather than a curated one."
  [:plain :digits :empty :grouped :upper :notes :revision :revision-strict
   :form-a :form-b :mountable])

(h/defview trace-row
  "One field's committed value and the number of intents that have reached
  the store for it. `pr-str` rather than the bare string, so `\"\"` and a
  trailing space are visible — a trace that renders an empty model as an
  empty cell is not a trace."
  [{:keys [field]}]
  (let [id (name field)]
    [:tr
     [:td id]
     [:td {:data-testid (str "trace-" id "-value")} (pr-str (h/sub [:tb/field field]))]
     [:td {:data-testid (str "trace-" id "-edits")} (str (h/sub [:tb/edits field]))]]))

(h/defview trace
  "The store, on screen. The manual native-IME session reads its
  `app-db clean until commit` and `arrives exactly once` checks off this
  table, because a browser shell with no devtools has nowhere else to read
  them."
  [_]
  [:table {:data-testid "trace"}
   [:thead [:tr [:th "field"] [:th "committed"] [:th "intents arrived"]]]
   [:tbody
    (for [field traced-fields]
      [trace-row {:key (name field) :field field}])]])

(h/defview armed-edges
  "The two mid-composition edges, reachable without a pointer-down that
  would close the composition first.

  The readout names the EVENT that is queued, not merely that something
  is. It is the operator's confirmation that they armed the edge they
  meant to, and it is the only thing a driver can read about an arm
  without waiting five seconds for it to fire."
  [_]
  (let [armed (h/sub [:tb/armed])
        what  (:what armed)
        event (:event armed)]
    [:p
     [:button {:data-testid "arm-bump" :on-click [:tb/arm :bump]}
      "arm bump (5s)"]
     [:button {:data-testid "arm-unmount" :on-click [:tb/arm :unmount]}
      "arm unmount (5s)"]
     [:span {:data-testid "armed"}
      (if what
        (str "armed: " (name what) " -> " (pr-str event) " fires in 5s")
        "idle")]]))

(h/defview app
  [_]
  [:main {:data-testid "hicasso-controlled-testbed"}
   [:h1 "Hicasso controlled-input testbed"]
   [text-field {:field :plain}]
   [text-field {:field :digits}]
   [text-field {:field :empty}]
   [text-field {:field :grouped}]
   [text-field {:field :upper}]
   [notes-field {}]
   [revision-field {:field :revision}]
   [revision-field {:field :revision-strict}]
   [flag-box {}]
   [reset-form {}]
   [mountable-field {}]
   [:button {:data-testid "bump-revision" :on-click [:tb/bump-revision]} "bump"]
   [:button {:data-testid "toggle-mounted" :on-click [:tb/toggle-mounted]} "toggle"]
   [:button {:data-testid "correct-upper" :on-click [:tb/correct :upper "ZZZZZZ"]}
    "correct upper"]
   [armed-edges {}]
   [trace {}]])

;; ---------------------------------------------------------------------------
;; The harness door — the model, and only the model
;; ---------------------------------------------------------------------------

(defn- model-json
  []
  (let [db (rf/app-db-value frame-id)]
    (js/JSON.stringify
     (clj->js {:fields   (:fields db)
               ;; Arrivals per field — how the driver asks whether an edit
               ;; REACHED the store, separately from whether the store took
               ;; it. The two questions look identical on a refusing field
               ;; and on an accepting one they look identical too, for
               ;; opposite reasons.
               :edits    (:edits db)
               :flag     (:flag db)
               :revision (:revision db)}))))

(defn ^:export init
  []
  (rf/init! uix-adapter/adapter)
  (rf/make-frame {:id frame-id :initial-events [[:tb/seed]]})
  (h/root! (js/document.getElementById "app") frame-id [app {}])
  (unchecked-set js/window "__RF2_HIC_TB__" #js {:model model-json})
  nil)
