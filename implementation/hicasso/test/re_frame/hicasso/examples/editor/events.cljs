(ns re-frame.hicasso.examples.editor.events
  "THE FOUR-FIELD EDITOR'S MODEL — ordinary re-frame2, and nothing else
  (rf2-hic-078).

  Four fields, one address each, and every handler a plain
  `(fn [coeffects event-v] → effect-map)`. This namespace requires
  `re-frame.core` and `clojure.string`; it does not require the Hicasso
  door, and could not tell you a view substrate existed. That is the
  tier, and `editor.l0-cljs-test` spends its whole length proving the
  consequence: the model is testable with `=` and a map.

  ## The four policies, and why there are four

  Specification §7 buys *forms and controlled fields* with the core
  controlled law plus caller revision, and names the four-field editor as
  the proof. The fields therefore differ **only in model policy**, so
  that a red row names a policy rather than a field:

  | field | tag | policy | what only this field can show |
  |---|---|---|---|
  | `:title` | `<input>` | takes what is typed | the accepted keystroke SURVIVES the converge |
  | `:slug` | `<input>` | lower-cases, collapses non-alphanumeric runs to `-` | a NORMALISED value echoes as the committed one, at a length the typing did not have |
  | `:body` | `<textarea>` | takes what is typed | the same law on the other convergeable tag |
  | `:published?` | `<input type=checkbox>` | boolean | the `::h/checked` pair, whose `false` is a presence and not a falsehood |

  No field here REFUSES, and that is a division of labour rather than an
  omission: refusal-echo is the 100-cell grid's row
  (`examples.grid.events/digits-only`), and witnessing it twice would buy
  a second copy of one claim.

  ## `::h/value` cannot ride the canonical event-vector shape

  Confirmed here from a second application, and the finding is
  rf2-hic-025's first
  (`docs/design/hicasso/product/authoring-report-slice.md` §1).
  `spec/Conventions.md` §Canonical event-vector shape asks for
  `[<id> {<k> <v>}]`; Hicasso substitutes its markers with `mapv` over
  the intent vector's **top level**, deliberately and for a stated cost
  reason. So the shape the convention asks for cannot carry a marker:

      ;; WRONG, and silent. `:value` arrives as the keyword
      ;; :re-frame.hicasso/value and renders as text.
      [::edit {:field :title :value ::h/value}]

      ;; The only spelling that works — the one the linter nudges away from.
      [::edit :title ::h/value]

  [[::edit]] and [[::set-published]] are therefore POSITIONAL and the
  other three handlers are not, which is the whole shape of the
  collision: a payload map is available to every handler that does not
  carry a marker, and to none that does. `editor.l1-marker-cljs-test`
  asserts both spellings through the runtime's own substitution rather
  than describing them.

  ## The revision counter is the author's to invent

  [[::discard]] bumps `:revision` with `(fnil inc 0)`, which is
  rf2-hic-025's finding 5. What this application adds is the measurement
  the report could not make: `editor.flow-dom-cljs-test`
  §the-discard-row states, at its own assertion, which of the four fields
  can drift far enough for the bump to be load-bearing and which cannot.
  The `fnil` is not optional in either case — the seed carries no
  `:revision` key, so a first discard on a plain `inc` is a
  `nil` arithmetic error rather than a reset."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; The policies — pure functions, one per field, and the whole of the
;; difference between the four
;; ---------------------------------------------------------------------------

(defn slugify
  "`\"Hello, World\"` -> `\"hello-world\"`. Lower-cases and collapses every
  run of non-alphanumerics to one `-`.

  Length-CHANGING by construction, which is what makes the slug field's
  echo row a real one: a normalisation that preserved length could be
  satisfied by a field that echoed nothing at all."
  [s]
  (-> (str s) str/lower-case (str/replace #"[^a-z0-9]+" "-")))

(def field-policy
  "What each editable field does with what was typed, as data.

  A map rather than a `case` so the roster is readable, and so
  [[editable-fields]] below is derived from it rather than maintained
  beside it — a field added without a policy would answer `nil` and take
  nothing, which is a failure no test would have to be remembered."
  {:title      identity
   :slug       slugify
   :body       identity})

(def editable-fields
  "The three TEXT fields, in display order. `:published?` is not among
  them: it is a checkbox, it carries `::h/checked` rather than
  `::h/value`, and it has its own handler."
  [:title :slug :body])

(def article-fields
  "Every field of the article, text and boolean alike — the addresses
  [[::save]] and [[::discard]] move as a unit."
  (conj editable-fields :published?))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(def seed
  "The starting `app-db`. `:draft` is what the fields show and
  `:article` is what has been committed; they begin equal, and the
  distance between them is what \"echoes only committed state\" is a
  claim about.

  Note what is ABSENT: no `:revision` key. A consumer copying a seed from
  a guide would not think to put one there either, which is why
  [[::discard]]'s `fnil` is load-bearing on the very first discard."
  (let [article {:title "Intents are data" :slug "intents-are-data"
                 :body "An intent is a value, and two renders of one intent are `=`."
                 :published? false}]
    {:article article
     :draft   article}))

(rf/reg-event ::seed
  {:doc "Install the starting app-db. The frame's `:initial-events` step."}
  (fn [_ _] {:db seed}))

;; ---------------------------------------------------------------------------
;; Editing — POSITIONAL, because the payload carries a marker
;; ---------------------------------------------------------------------------

(rf/reg-event ::edit
  {:doc "Write one text field of the draft, through that field's policy."}
  ;; `[_ field typed]` and not `[_ {:keys [field value]}]`. See the
  ;; namespace docstring: a marker below the intent vector's top level is
  ;; not substituted, not refused and not linted.
  (fn [{:keys [db]} [_ field typed]]
    (let [policy (get field-policy field)]
      {:db (assoc-in db [:draft field] (policy typed))})))

(rf/reg-event ::set-published
  {:doc "Take the checkbox's state. Positional because it carries `::h/checked`."}
  (fn [{:keys [db]} [_ checked]]
    {:db (assoc-in db [:draft :published?] (boolean checked))}))

;; ---------------------------------------------------------------------------
;; Commit and reset — no marker, so the canonical payload map is available
;; ---------------------------------------------------------------------------

(rf/reg-event ::save
  {:doc "Commit the draft. The one place `:article` moves."}
  ;; The canonical `[<id> {<k> <v>}]` shape, and it is written here to
  ;; make the contrast in the namespace docstring visible in the source
  ;; rather than only described: this handler may have it because its
  ;; payload carries no marker, and [[::edit]] may not because its does.
  (fn [{:keys [db]} [_ {:keys [at]}]]
    {:db (-> db
             (assoc :article (:draft db))
             (assoc :saved-at at))}))

(rf/reg-event ::discard
  {:doc "Throw the draft away and re-baseline the fields — the reset."}
  (fn [{:keys [db]} [_ _]]
    ;; TWO moves, and the second is the one nothing on the door asks for.
    ;; Restoring `:draft` moves the MODEL back; bumping `:revision` is
    ;; what tells a controlled field to take it even where React's own
    ;; value diff would see nothing to do (HD-019's reset law).
    ;;
    ;; `fnil` because `seed` has no `:revision` key — see its docstring.
    {:db (-> db
             (assoc :draft (:article db))
             (update :revision (fnil inc 0)))}))
