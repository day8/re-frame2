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

  ## The rest of the control roster (rf2-hic-040)

  Everything above is one shape of control — a text field, in seven model
  policies — because I15 is a law about text. The CONFORMANCE matrix is a
  different question: does every control type named in
  [specification 4.2] have a support-or-refusal policy, in three engines,
  with none of them silently unsupported. That roster is
  `docs/design/hicasso/product/dispositions.md` section 2.3, and these
  are the controls it needs on screen:

  | control | policy behind it | what it is here to settle |
  |---|---|---|
  | `radio-a/b/c` | one model slot; the group refuses `\"c\"` | owned `:checked` on a group, and a committed echo where the clicked element is not the one that carries the model |
  | `pick` (`<select>`) | refuses `\"banned\"` | `impl.controlled` does not apply to a select at all — `convergeable-tag?` says so — so what converges it is React's own restore |
  | `picks` (`<select multiple>`) | drops `\"banned\"` | the SUPPORTED spelling: `h/event`, because `::h/value` reads one option |
  | `picks-marker` (`<select multiple>`) | takes the one string it is handed | the NAIVE spelling, on screen so the cost of the reserved marker on this control is measured rather than described |
  | `file` | uncontrolled; `h/event` reads `.files` | the only policy a file input can have — `value` refuses every assignment but `\"\"` |
  | `count` (`number`) | clamps above ten | a controlled type with NO caret: `caret-type?` refuses it, so the converge declines and React's restore is the echo |
  | `day` (`date`) | refuses any year but 2026 | the same, on a type whose value has a format |
  | `level` (`range`) | snaps to a multiple of ten | the same, with a normalisation the control's own stepping cannot produce |
  | `prose` (`contenteditable`) | records what the handler read | NOT a controlled field, and this testbed does not pretend it is |
  | `blur-probe` | shares `async`'s model | blur after unmount, which is an absence: React synthesises no `blur` for a node it removes |
  | `async` | accepts; corrected out of band later | async normalization, on the path the keystroke converge is deliberately NOT on |
  | `svg` / `custom` | none | attribute conformance, read off the live DOM rather than a string |

  The form gained a name on every control, plus a checkbox and a select,
  because `FormData` reads NAMED controls and a nameless form would make
  the extraction row pass by reading nothing.

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
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.hicasso :as rf.hicasso]))

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
    :async    typed
    :digits   (if (re-matches #"[0-9]*" typed) typed old)
    ;; --- rf2-hic-040's controls ---------------------------------------------
    ;; Every one of these REFUSES or NORMALISES, because a control whose
    ;; policy accepts everything cannot witness "echoes only committed
    ;; state" — the field would show the same string under a working
    ;; converge and a broken one.
    ;;
    ;; `pick` (select, single) refuses one option, so choosing it must
    ;; leave the select showing the previous choice.
    :pick      (if (= typed "banned") old typed)
    :form-pick typed
    ;; `count` (number) clamps, so the committed value differs from the
    ;; typed one at every value over ten.
    :count    (let [n (js/Number typed)]
                (cond
                  (or (= typed "") (js/isNaN n)) old
                  (> n 10)                       "10"
                  :else                          typed))
    ;; `day` (date) refuses any year but 2026. A date field's value is
    ;; `yyyy-mm-dd` in every engine, which is what makes a string policy
    ;; legitimate here rather than a simplification.
    :day      (if (re-matches #"2026-\d{2}-\d{2}" typed) typed old)
    ;; `level` (range) snaps to a multiple of ten — a normalisation the
    ;; browser's own stepping cannot produce, so the echo is this
    ;; runtime's rather than the control's.
    :level    (let [n (js/Number typed)]
                (if (js/isNaN n) old (str (* 10 (js/Math.round (/ n 10))))))
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
   :mountable "9"
   ;; rf2-hic-040's controls
   :pick     "one"
   :form-pick "one"
   :count    "5"
   :day      "2026-01-15"
   :level    "30"
   :async    "start"})

(def ^:private radio-seed
  "The radio group's committed choice, and the option its policy refuses.
  A radio group is one model slot and N elements, so it does not live in
  `:fields` — the `apply-policy` table is keyed per field, and a refusal
  here has to be expressed against the group rather than against the
  element that was clicked."
  {:choice "b" :refused "c"})

;; ---------------------------------------------------------------------------
;; Events and subscriptions — an ordinary re-frame2 app, nothing else
;; ---------------------------------------------------------------------------

(rf/reg-sub :tb/field (fn [db [_ field]] (get-in db [:fields field] "")))
(rf/reg-sub :tb/edits (fn [db [_ field]] (get-in db [:edits field] 0)))
(rf/reg-sub :tb/flag (fn [db _] (:flag db)))
(rf/reg-sub :tb/revision (fn [db _] (:revision db)))
(rf/reg-sub :tb/mounted? (fn [db _] (:mounted? db)))
(rf/reg-sub :tb/armed (fn [db _] (:armed db)))
(rf/reg-sub :tb/radio (fn [db _] (:radio db)))
(rf/reg-sub :tb/picks (fn [db _] (:picks db)))
(rf/reg-sub :tb/picks-marker (fn [db _] (:picks-marker db)))
(rf/reg-sub :tb/files (fn [db _] (:files db)))
(rf/reg-sub :tb/prose (fn [db _] (:prose db)))
(rf/reg-sub :tb/focus-log (fn [db _] (:focus-log db)))
(rf/reg-sub :tb/probe-mounted? (fn [db _] (:probe-mounted? db)))

(rf/reg-event :tb/seed
  (fn [_ _] {:db {:fields seed :edits {} :flag false :revision 0
                  :mounted? true :armed nil
                  :radio (:choice radio-seed)
                  :picks ["a"]
                  :picks-marker ["a"]
                  :files []
                  :prose "hand-written"
                  :focus-log []
                  :probe-mounted? true}}))

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
;; rf2-hic-040's controls — the events behind the rest of the roster
;; ---------------------------------------------------------------------------

;; A radio group is ONE model slot, so its refusal is expressed against the
;; group. Choosing the refused option leaves the committed choice where it
;; was, which is what makes "echoes only committed state" observable on a
;; radio at all: a group whose policy accepts everything shows the clicked
;; button checked under a working echo and under no echo whatsoever.
(rf/reg-event :tb/pick-radio
  (fn [{:keys [db]} [_ choice]]
    {:db (-> db
             (cond-> (not= choice (:refused radio-seed)) (assoc :radio choice))
             (update-in [:edits :radio] (fnil inc 0)))}))

;; The multiple-select, written the SUPPORTED way: `h/event`, because the
;; reserved `::h/value` marker reads `select.value` and that is one option.
;; The refusal is the same shape as everywhere else — one option the model
;; will not take, dropped from whatever arrives.
(rf/reg-event :tb/pick-many
  (fn [{:keys [db]} [_ chosen]]
    {:db (-> db
             (assoc :picks (vec (remove #(= % "banned") chosen)))
             (update-in [:edits :picks] (fnil inc 0)))}))

;; The SAME control, written the way an author reaches for first: the
;; reserved marker at the change position. What arrives is the SELECTION —
;; a list, `[]` when nothing is picked (rf2-42vlw, and
;; `spec/004B-UI-Tree-and-Conversion.md` rules the same shape for the same
;; DOM control on the sibling substrate) — so the obvious thing to do with
;; it is to hold it as it came. The witness measures that the naive
;; spelling now costs the user nothing.
(rf/reg-event :tb/pick-many-marker
  (fn [{:keys [db]} [_ marked]]
    {:db (-> db
             (assoc :picks-marker marked)
             (assoc :picks-marker-raw marked)
             (update-in [:edits :picks-marker] (fnil inc 0)))}))

;; A file input is never value-controlled — `HTMLInputElement.value` is
;; not settable to anything but `""` from script, by design — so the model
;; holds what was CHOSEN rather than what is displayed, and `h/event` is the
;; door the facade's own docstring names for it.
(rf/reg-event :tb/take-files
  (fn [{:keys [db]} [_ names]]
    {:db (-> db (assoc :files (vec names))
             (update-in [:edits :files] (fnil inc 0)))}))

;; Contenteditable: the element's content is the BROWSER's, and the model
;; records what the author's own handler read off it. There is no owned
;; slot for a contenteditable region and no converge on it.
(rf/reg-event :tb/set-prose
  (fn [{:keys [db]} [_ text]]
    {:db (-> db (assoc :prose text)
             (update-in [:edits :prose] (fnil inc 0)))}))

;; Blur after unmount — the model records the focus and blur edges the
;; browser reports, so "the field that was focused went away" is a reading
;; of events rather than of a screen.
(rf/reg-event :tb/focus-edge
  (fn [{:keys [db]} [_ edge]]
    {:db (update db :focus-log (fnil conj []) edge)}))

(rf/reg-event :tb/toggle-probe
  (fn [{:keys [db]} _] {:db (update db :probe-mounted? not)}))

;; ASYNC NORMALIZATION — the correction that arrives a turn later, which
;; is the shape a server or a debounced validator has. It is deliberately
;; `:dispatch-later` rather than a synchronous correction: the point of
;; the row is that the field converges on a path the keystroke converge is
;; NOT on, so the model has to move outside the discrete event.
(rf/reg-event :tb/normalise-later
  (fn [{:keys [db]} [_ field ms]]
    {:db db
     :fx [[:dispatch-later {:ms ms :event [:tb/normalise field]}]]}))

;; The correction itself reads the model at FIRE time, not at arm time.
;; Armed-time capture would make the row pass on a runtime that ignored
;; every keystroke between the arm and the fire.
(rf/reg-event :tb/normalise
  (fn [{:keys [db]} [_ field]]
    {:db (-> db
             (assoc-in [:fields field]
                       (str/upper-case (get-in db [:fields field] "")))
             (update :normalisations (fnil inc 0)))}))

;; ---------------------------------------------------------------------------
;; The armed edges — the operator's way of reaching mid-composition
;; ---------------------------------------------------------------------------

(def ^:private default-arm-delay-ms
  "Long enough to click the button, click back into the field and get a
  composition started. A human doing three things, not a machine doing
  one."
  5000)

(defn- arm-delay-ms
  "The armed delay, overridable per page load with `?arm-ms=<n>`.

  The default is the OPERATOR's number and does not move. The override
  exists for the gate, and it is what makes `armed-edges-are-wired` able
  to fail: an arm that never arms is indistinguishable from a correctly
  deferred one until something WAITS FOR THE FIRE, and waiting five
  seconds twice in each of three engines to learn that is thirty seconds
  on a required job. At 300ms it is under a second, so the section can
  assert the thing its name claims.

  A bad value falls back rather than throwing — a query string is user
  input, and the testbed's job is to be open, not to be strict."
  []
  (let [raw (some-> js/window .-location .-search
                    (->> (new js/URLSearchParams))
                    (.get "arm-ms"))
        n   (some-> raw js/Number)]
    (if (and n (not (js/isNaN n)) (pos? n)) (long n) default-arm-delay-ms)))

(defn- humanise-ms
  "`5000` -> `\"5s\"`, `300` -> `\"300ms\"`. Whole seconds read as seconds so
  the operator's default readout is the sentence it always was."
  [ms]
  (if (zero? (mod ms 1000)) (str (quot ms 1000) "s") (str ms "ms")))

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
;; The effects ride in `:fx`, and that is not a style choice. re-frame2's
;; effect-map is a CLOSED shape — seven top-level keys, `#{:db :rf.db/runtime
;; :fx}` plus the four EP-0025 commit-plane classification effects (migration
;; M-8 / EP-0001) — so the v1 spelling these two handlers shipped with, a
;; top-level `:dispatch-later` beside `:db`, ARMED NOTHING.
;; Both arms were dead from the day they landed: the readout said
;; `armed: bump -> [:tb/bump-revision] fires in 5s` and no timer existed
;; behind it. THAT ACCOUNT IS THE PRE-rf2-04tx CONTRACT, and the history is
;; the point: back then the foreign top-level key was DROPPED while the `:db`
;; write committed anyway, so the label rendered with nothing queued behind
;; it. Today the same spelling REFUSES the event pre-commit — no timer and no
;; label — which is what turns this silent failure into a loud one.
;; Measured 2026-08-11 while building the scripted native-IME
;; witness — 15s after the click the readout still read `armed`, while a
;; plain `setTimeout(5000)` in the same page returned in 5006ms, so it was
;; the effect and not the clock.
;;
;; `armed-edges-are-wired` could not see it: it reads the label and asserts
;; "nothing has happened yet", which is true of a correctly deferred arm and
;; equally true of one that never armed. That section now waits for the fire.
(rf/reg-event :tb/arm
  (fn [{:keys [db]} [_ what]]
    (let [event (armed-events what)
          ms    (arm-delay-ms)]
      {:db (assoc db :armed {:what what :event event :ms ms})
       :fx [[:dispatch-later {:ms ms :event [:tb/fire-armed event]}]]})))

(rf/reg-event :tb/fire-armed
  (fn [{:keys [db]} [_ event]]
    {:db (assoc db :armed nil)
     :fx [[:dispatch event]]}))

;; ---------------------------------------------------------------------------
;; The views — every field written the way authoring.md writes one
;; ---------------------------------------------------------------------------

(rf.hicasso/defview text-field
  "One controlled `<input>`. `:value` off the subscription, an intent
  vector at `:on-input`, and nothing else."
  [{:keys [field]}]
  (let [id (name field)]
    [:input {:data-testid id
             :id          id
             :type        "text"
             :value       (rf.hicasso/sub [:tb/field field])
             :on-input    [:tb/edit field ::rf.hicasso/value]}]))

(rf.hicasso/defview notes-field
  "The same law on the other convergeable tag."
  [_]
  [:textarea {:data-testid "notes"
              :id          "notes"
              :value       (rf.hicasso/sub [:tb/field :notes])
              :on-input    [:tb/edit :notes ::rf.hicasso/value]}])

(rf.hicasso/defview revision-field
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
             ::rf.hicasso/revision   (rf.hicasso/sub [:tb/revision])
             :value         (rf.hicasso/sub [:tb/field field])
             :on-input      [:tb/edit field ::rf.hicasso/value]}]))

(rf.hicasso/defview flag-box
  "The owned `::h/checked` pair. `false` is a presence here, not a
  falsehood."
  [_]
  [:input {:data-testid "flag"
           :id          "flag"
           :type        "checkbox"
           :checked     (rf.hicasso/sub [:tb/flag])
           :on-change   [:tb/toggle-flag ::rf.hicasso/checked]}])

(rf.hicasso/defview reset-form
  "A real form with a real reset button, so `form.reset()` is the
  browser's own and not a simulation of it.

  Every control carries a `:name`, which is not decoration: `FormData`
  reads NAMED controls and skips the rest, so a nameless form is a form
  whose extraction row would pass by reading nothing. rf2-hic-040 owns
  the FormData row and added the names, the checkbox and the select; the
  two text fields and the reset button are rf2-hic-016's and are
  untouched apart from gaining a name."
  [_]
  [:form {:data-testid "form" :id "form" :on-submit [:tb/noop]}
   [:input {:data-testid "form-a"
            :id          "form-a"
            :name        "form-a"
            :type        "text"
            :value       (rf.hicasso/sub [:tb/field :form-a])
            :on-input    [:tb/edit :form-a ::rf.hicasso/value]}]
   [:input {:data-testid "form-b"
            :id          "form-b"
            :name        "form-b"
            :type        "text"
            :value       (rf.hicasso/sub [:tb/field :form-b])
            :on-input    [:tb/edit :form-b ::rf.hicasso/value]}]
   ;; A controlled checkbox INSIDE the form, so the extraction row reads a
   ;; control whose owned slot is `::h/checked` rather than a value, and
   ;; the reset row has a `defaultChecked` mirror to act on.
   [:input {:data-testid "form-flag"
            :id          "form-flag"
            :name        "form-flag"
            :type        "checkbox"
            :value       "yes"
            :checked     (rf.hicasso/sub [:tb/flag])
            :on-change   [:tb/toggle-flag ::rf.hicasso/checked]}]
   ;; …and a controlled select, whose extraction is `selected` rather than
   ;; an attribute.
   [:select {:data-testid "form-pick"
             :id          "form-pick"
             :name        "form-pick"
             :value       (rf.hicasso/sub [:tb/field :form-pick])
             :on-change   [:tb/edit :form-pick ::rf.hicasso/value]}
    [:option {:value "one"} "one"]
    [:option {:value "two"} "two"]]
   [:button {:data-testid "form-reset" :type "reset"} "reset"]])

(rf.hicasso/defview mountable-field
  "Rendered behind a flag, so the driver can take the fiber away from
  under a live composition."
  [_]
  (if (rf.hicasso/sub [:tb/mounted?])
    [:input {:data-testid "mountable"
             :id          "mountable"
             :type        "text"
             :value       (rf.hicasso/sub [:tb/field :mountable])
             :on-input    [:tb/edit :mountable ::rf.hicasso/value]}]
    [:p {:data-testid "mountable-gone"} "unmounted"]))

;; ---------------------------------------------------------------------------
;; rf2-hic-040's controls — the rest of the roster, each written the way
;; authoring.md writes it and NOT the way a harness would find convenient
;; ---------------------------------------------------------------------------

(rf.hicasso/defview radio-group
  "Three radios on one model slot. `:checked` is owned off the
  subscription and the intent carries the option as a constant, because a
  radio's `::h/checked` is always `true` at the moment it fires — the
  information is WHICH button, and that is in the hiccup rather than on
  the event.

  Each element also carries a `:value`, which is what a form submission
  needs. That is not decoration for this witness: `:value` present on an
  `input` is exactly what makes
  `impl.controlled/controlled-text-tag?` answer yes, so these radios go
  through the shadow component and its `convergeable?` re-ask — the inert
  path, on a type with no caret."
  [_]
  (let [choice (rf.hicasso/sub [:tb/radio])]
    [:fieldset {:data-testid "radios"}
     (for [option ["a" "b" "c"]]
       [:label {:key option}
        [:input {:data-testid (str "radio-" option)
                 :type        "radio"
                 :name        "radio-group"
                 :value       option
                 :checked     (= option choice)
                 :on-change   [:tb/pick-radio option]}]
        option])]))

(rf.hicasso/defview select-single
  "A controlled `<select>`. Nothing in `impl.controlled` applies to it —
  `convergeable-tag?` answers false for `select` and the namespace
  docstring says why (no text cursor, no `defaultValue` mirror) — so what
  converges this is React's own controlled restore, and the row measures
  that rather than assuming it."
  [_]
  [:select {:data-testid "pick"
            :id          "pick"
            :value       (rf.hicasso/sub [:tb/field :pick])
            :on-change   [:tb/edit :pick ::rf.hicasso/value]}
   [:option {:value "one"} "one"]
   [:option {:value "two"} "two"]
   [:option {:value "banned"} "banned"]])

(rf.hicasso/defview select-multiple
  "The same control with `:multiple`, written the SUPPORTED way.

  `h/event` rather than `::h/value`, and that is the whole content of this
  control's policy row: the reserved marker reads `(.-value target)`,
  which on a multiple select is the FIRST selected option and never the
  selection. The facade's own docstring names `event` as the form for when
  the event itself is wanted, and a multi-select is that case."
  [_]
  [:select {:data-testid "picks"
            :id          "picks"
            :multiple    true
            :value       (rf.hicasso/sub [:tb/picks])
            :on-change   (rf.hicasso/event [e]
                           [:tb/pick-many
                            (mapv #(.-value %)
                                  (array-seq (.. e -target -selectedOptions)))])}
   [:option {:value "a"} "a"]
   [:option {:value "b"} "b"]
   [:option {:value "banned"} "banned"]
   [:option {:value "c"} "c"]])

(rf.hicasso/defview select-multiple-marker
  "The same control again, written the way an author reaches for FIRST —
  the reserved marker at the change position — so that what it costs is
  measured rather than asserted in prose. The handler does the obvious
  thing with the one string it is handed."
  [_]
  [:select {:data-testid "picks-marker"
            :id          "picks-marker"
            :multiple    true
            :value       (rf.hicasso/sub [:tb/picks-marker])
            :on-change   [:tb/pick-many-marker ::rf.hicasso/value]}
   [:option {:value "a"} "a"]
   [:option {:value "b"} "b"]
   [:option {:value "c"} "c"]])

(rf.hicasso/defview file-field
  "A file input, UNCONTROLLED, which is the only thing it can be:
  `HTMLInputElement.value` refuses every assignment but `\"\"`, so a
  `:value` off a subscription would be a promise the platform cannot
  keep. The chosen files reach the model through `h/event` — the facade's
  docstring uses this exact case as its example."
  [_]
  [:input {:data-testid "file"
           :id          "file"
           :type        "file"
           :multiple    true
           :on-change   (rf.hicasso/event [e]
                          [:tb/take-files
                           (mapv #(.-name %)
                                 (array-seq (.. e -target -files)))])}])

(rf.hicasso/defview typed-field
  "One `<input>` of a type with no text cursor — `number`, `date` or
  `range`. Written identically to the text fields: `:value` off a
  subscription, an intent at `:on-input`.

  These are the roster's honest middle. `impl.controlled/install!` wraps
  them for the shadow component (they carry a `:value`) and then declines
  to install the converge (`caret-type?` answers false), so the echo they
  get is React's own end-of-event restore and no caret is preserved
  because there was never one to preserve."
  [{:keys [field kind extra]}]
  (let [id (name field)]
    [:input (merge {:data-testid id
                    :id          id
                    :type        kind
                    :value       (rf.hicasso/sub [:tb/field field])
                    :on-input    [:tb/edit field ::rf.hicasso/value]}
                   extra)]))

(rf.hicasso/defview editable-region
  "A `contenteditable` region. It is NOT a controlled field and this
  testbed does not pretend otherwise: there is no owned `:value` slot for
  one, the content is the browser's, and the author's handler reads it
  back. The model row exists so the read is visible; the region itself is
  written the way any author would write it."
  [_]
  [:div {:data-testid      "prose"
         :id               "prose"
         :content-editable "plaintext-only"
         :suppress-content-editable-warning true
         :on-input         (rf.hicasso/event [e] [:tb/set-prose (.. e -target -textContent)])}
   (rf.hicasso/sub [:tb/prose])])

(rf.hicasso/defview blur-probe
  "A controlled field behind a mount flag, carrying focus and blur
  handlers that write to the model.

  BLUR AFTER UNMOUNT is the roster row this exists for, and the row is
  about an ABSENCE: React does not synthesise a `blur` for a node it
  removes, so a field taken away while focused reports `focus` and never
  reports `blur`. An application that hangs commit-on-blur off that
  handler loses the edit, and the converge must not care either way — the
  shadow is one `useState` on the fiber and goes with it. What this
  witnesses is that the runtime does not strand anything on the way out,
  and what it RECORDS is where the focus lands afterwards, which is the
  engines' to differ on."
  [_]
  (if (rf.hicasso/sub [:tb/probe-mounted?])
    [:input {:data-testid "blur-probe"
             :id          "blur-probe"
             :type        "text"
             :value       (rf.hicasso/sub [:tb/field :async])
             :on-input    [:tb/edit :async ::rf.hicasso/value]
             :on-focus    [:tb/focus-edge "focus"]
             :on-blur     [:tb/focus-edge "blur"]}]
    [:p {:data-testid "blur-probe-gone"} "unmounted"]))

(rf.hicasso/defview svg-figure
  "SVG, written in the two spellings an author uses: a camel attribute
  React renames nothing about (`:view-box` → `viewBox`) and kebab
  presentation attributes (`:stroke-width`, `:stroke-linecap`). The
  witness reads them off the LIVE DOM, where an SVG element's attributes
  are case-sensitive and namespaced, rather than out of a string."
  [_]
  [:svg {:data-testid "svg" :id "svg" :view-box "0 0 20 20" :width 20 :height 20}
   [:circle {:data-testid    "svg-circle"
             :cx             10 :cy 10 :r 6
             :stroke-width   2
             :stroke-linecap "round"
             :fill           "none"
             :stroke         "black"}]
   [:text {:data-testid "svg-text" :x 2 :y 18 :font-size 4} "hi"]])

(rf.hicasso/defview custom-element-figure
  "A custom element. React 19 hands an unknown element's props through as
  ATTRIBUTES under the name it was given, so the slot rule decides what
  the DOM ends up with — and the slot rule camelCases a kebab keyword.
  Both spellings are on the element so the witness can say what each one
  does rather than assert what one of them ought to do."
  [_]
  [:x-widget {:data-testid "custom"
              :id          "custom"
              ;; a STRING key is already a React name and passes verbatim
              "my-attr"    "from-string"
              ;; a kebab KEYWORD goes through the camelCasing slot rule
              :my-other-attr "from-keyword"
              ;; `data-*` is exempt from camelCasing by the slot rule
              :data-kebab-attr "from-data"}
   "widget"])

(def ^:private traced-fields
  "Every field, in a stable order, so the trace is a complete reading of
  the store rather than a curated one."
  [:plain :digits :empty :grouped :upper :notes :revision :revision-strict
   :form-a :form-b :mountable :pick :count :day :level :async])

(rf.hicasso/defview trace-row
  "One field's committed value and the number of intents that have reached
  the store for it. `pr-str` rather than the bare string, so `\"\"` and a
  trailing space are visible — a trace that renders an empty model as an
  empty cell is not a trace."
  [{:keys [field]}]
  (let [id (name field)]
    [:tr
     [:td id]
     [:td {:data-testid (str "trace-" id "-value")} (pr-str (rf.hicasso/sub [:tb/field field]))]
     [:td {:data-testid (str "trace-" id "-edits")} (str (rf.hicasso/sub [:tb/edits field]))]]))

(rf.hicasso/defview trace
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

(rf.hicasso/defview armed-edges
  "The two mid-composition edges, reachable without a pointer-down that
  would close the composition first.

  The readout names the EVENT that is queued, not merely that something
  is. It is the operator's confirmation that they armed the edge they
  meant to, and it is the only thing a driver can read about an arm
  without waiting five seconds for it to fire."
  [_]
  (let [armed (rf.hicasso/sub [:tb/armed])
        what  (:what armed)
        event (:event armed)]
    [:p
     [:button {:data-testid "arm-bump" :on-click [:tb/arm :bump]}
      "arm bump (5s)"]
     [:button {:data-testid "arm-unmount" :on-click [:tb/arm :unmount]}
      "arm unmount (5s)"]
     [:span {:data-testid "armed"}
      (if what
        (str "armed: " (name what) " -> " (pr-str event)
             " fires in " (humanise-ms (:ms armed)))
        "idle")]]))

(rf.hicasso/defview app
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
   ;; --- rf2-hic-040's controls ---------------------------------------------
   [radio-group {}]
   [select-single {}]
   [select-multiple {}]
   [select-multiple-marker {}]
   [file-field {}]
   [typed-field {:field :count :kind "number" :extra {:min 0 :max 100}}]
   [typed-field {:field :day :kind "date"}]
   [typed-field {:field :level :kind "range" :extra {:min 0 :max 100 :step 1}}]
   [editable-region {}]
   ;; The out-of-band half of the contenteditable row: a model change no
   ;; edit caused, which a controlled field would converge and this one
   ;; re-renders as ordinary children.
   [:button {:data-testid "prose-correct" :on-click [:tb/set-prose "CORRECTED"]}
    "correct prose"]
   [blur-probe {}]
   [:button {:data-testid "toggle-probe" :on-click [:tb/toggle-probe]} "toggle probe"]
   [:button {:data-testid "normalise-async"
             :on-click [:tb/normalise-later :async 30]}
    "normalise async"]
   [svg-figure {}]
   [custom-element-figure {}]
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
               :revision (:revision db)
               ;; rf2-hic-040's controls. Each is model state a driver
               ;; cannot read off the screen: which radio the GROUP holds
               ;; (three elements, one slot), the full multi-selection
               ;; beside the one string the reserved marker delivered,
               ;; the chosen file names, the contenteditable text the
               ;; author's handler read back, the focus edges the browser
               ;; reported, and the async normalisation count.
               :radio           (:radio db)
               :picks           (:picks db)
               :picks-marker    (:picks-marker db)
               :picks-marker-raw (:picks-marker-raw db)
               :files           (:files db)
               :prose           (:prose db)
               :focus-log       (:focus-log db)
               :normalisations  (or (:normalisations db) 0)}))))

(defn ^:export init
  []
  (rf/init! rf.adapter.uix/adapter)
  (rf/make-frame {:id frame-id :initial-events [[:tb/seed]]})
  (rf.hicasso/mount! (js/document.getElementById "app") {:frame frame-id} [app {}])
  (unchecked-set js/window "__RF2_HIC_TB__" #js {:model model-json})
  nil)
