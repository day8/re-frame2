(ns re-frame.hicasso.examples.slice.events
  "THE SLICE'S EVENTS — ordinary re-frame2, and one stand-in server
  (rf2-hic-025).

  Every handler here is `(fn [coeffects event-v] → effect-map)`. Nothing
  in this namespace knows that a view substrate exists: it requires
  `re-frame.core` and [[re-frame.hicasso.examples.slice.db]], and not the
  Hicasso door. That is the point of the tier rather than an accident of
  it — the whole of this file is L0, testable with `=` and a map, and
  `re-frame.hicasso.examples.slice.l0-cljs-test` does exactly that.

  ## TWO event shapes, and the seam is not the author's choice
  (the rf2-hic-025 authoring report's first finding)

  `spec/Conventions.md` §Canonical event-vector shape asks for
  `[<id> {<k> <v>}]` — one trailing map — and the linter nudges new code
  toward it. Most handlers below take that shape.

  [[::edit]] and [[::toggle-published]] do NOT, and cannot.
  `re-frame.hicasso.impl.intent/materialize` substitutes `::h/value` and
  `::h/checked` with **`mapv` over the intent vector's top level**, by
  design and for a stated cost reason (a deep walk would be paid on every
  keystroke). So a marker written inside the canonical payload map —

      ;; WRONG, and silent: `:value` arrives as the keyword
      ;; :re-frame.hicasso/value, not as what the user typed.
      [::edit {:slug slug :field :title :value ::h/value}]

  — is not substituted, is not refused, and is not linted. The keyword
  reaches `app-db` and renders as text. The only spelling that works is
  the positional one:

      [::edit slug :title ::h/value]

  The escape hatch exists and is not free: `(h/fn [e] [::edit {… :value
  (.. e -target -value)}])` restores the map payload at the cost of a
  closure per field per render and the loss of the property route-links
  are sold on — that two renders of one intent are `=`.

  It is worth being plain about what the positional shape gives up,
  because Conventions §Canonical event-vector shape lists it: a
  positional argument is not path-addressable, so trace/error redaction
  cannot classify it. A controlled field carrying a password is
  therefore the one place this collision is more than a style
  disagreement.

  ## The async mutation, and why it is a `reg-fx` with a timer

  A save has to be able to be IN FLIGHT, and it has to be able to FAIL
  for a reason the client could not have known. So [[::persist]] is a
  stand-in server: it waits, applies the one rule a client cannot check
  (a title another article already carries), and dispatches a reply back
  into the frame it came from. A real application puts
  `day8/re-frame2-http` here and changes nothing else — the handler
  above it emits an effect and the two replies arrive as events either
  way.

  It is a `setTimeout` rather than a resolved promise for a reason the
  test tier decides: `re-frame.hicasso.test.mounted`'s virtual clock
  drives `setTimeout`, `setInterval` and `Date.now` in lockstep and
  deliberately does NOT drive microtasks, so a promise-based stub would
  make the mounted witness wait on the wall clock — the flake this
  repository keeps out of its gates. The `:delay` is data on the effect,
  so the test names it and then advances exactly that far.

  ## The reply carries the SLUG, and the handler checks it

  `[::saved {:slug …}]` rather than `[::saved]`: a reply that lands after
  the user has navigated to another article must not overwrite the save
  status of the article they are now looking at. The handler compares the
  slug the reply carries against the slug the request recorded and drops
  a stale one. That is the smallest honest form of the stale-reply
  problem, and leaving it out would have made the slice evidence for an
  API that had never met one."
  (:require [re-frame.core :as rf]
            [re-frame.hicasso.examples.slice.db :as db]))

;; ---------------------------------------------------------------------------
;; Boot
;; ---------------------------------------------------------------------------

(rf/reg-event ::seed
  {:doc "Install the starting app-db. The frame's `:initial-events` step."}
  (fn [_ _] {:db db/seed}))

;; ---------------------------------------------------------------------------
;; Editing — the controlled fields write straight into the draft
;;
;; POSITIONAL, not the canonical map payload. See the namespace docstring
;; §TWO event shapes: `::h/value` and `::h/checked` are substituted at the
;; intent vector's TOP LEVEL only, so a marker inside a payload map is a
;; silent wrong value rather than an error.
;; ---------------------------------------------------------------------------

(rf/reg-event ::edit
  {:doc "Write one field of a draft. Positional because it carries `::h/value`."}
  (fn [{:keys [db]} [_ slug field value]]
    ;; `draft-for` is what makes the first keystroke work: there is no
    ;; draft yet, so the article's own fields are what the edit lands on
    ;; top of. Without it the first character would arrive into an empty
    ;; map and the other fields would blank themselves.
    {:db (assoc-in db [:drafts slug] (assoc (db/draft-for db slug) field value))}))

(rf/reg-event ::toggle-published
  {:doc "Take the checkbox's state. Positional because it carries `::h/checked`."}
  (fn [{:keys [db]} [_ slug published?]]
    {:db (assoc-in db [:drafts slug]
                   (assoc (db/draft-for db slug) :published? (boolean published?)))}))

(rf/reg-event ::discard
  {:doc "Throw a draft away — the reset, and it is one move."}
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    ;; ONE move on the model, and no `::h/revision` counter beside it.
    ;; This handler carried one until rf2-36bd deleted the bump and the
    ;; browser lane did not move: dropping the draft is already three
    ;; changes the editor's body reads, so the body re-runs and the commit
    ;; re-asserts the model over the fields without being told to. See
    ;; `db`'s namespace docstring for the measurement and for the shape
    ;; that DOES need a counter.
    {:db (-> db
             (update :drafts dissoc slug)
             (assoc :save {:status :idle}))}))

;; ---------------------------------------------------------------------------
;; Saving — validate here, then ask the server
;; ---------------------------------------------------------------------------

(def save-delay-ms
  "How long the stand-in server takes. Small, because the mounted witness
  waits for the REPLY rather than for this number — see the namespace
  docstring — and every millisecond here is one the browser lane spends."
  40)

(rf/reg-event ::save
  {:doc "Validate a draft locally; if it passes, ask the server."}
  (fn [{:keys [db]} [_ {:keys [slug]}]]
    (let [draft    (db/draft-for db slug)
          problems (db/problems draft)]
      (if (seq problems)
        ;; The local half. No request is made, so there is nothing in
        ;; flight and nothing to arrive late: the status goes straight to
        ;; :failed with the first problem, and the error region below the
        ;; form reads it like any other value.
        {:db (assoc db :save {:status :failed :slug slug :problem (first problems)})}
        {:db (assoc db :save {:status :saving :slug slug})
         :fx [[::persist {:slug    slug
                          :draft   draft
                          :taken?  (db/title-taken? db slug (:title draft))
                          :delay   save-delay-ms
                          :on-ok   ::saved
                          :on-fail ::save-failed}]]}))))

(rf/reg-event ::saved
  {:doc "The server accepted a draft. Fold it in."}
  (fn [{:keys [db]} [_ {:keys [slug draft]}]]
    (if (= slug (get-in db [:save :slug]))
      {:db (-> (db/commit db slug draft)
               (assoc :save {:status :saved :slug slug}))}
      ;; A reply for an article this frame is no longer saving. Dropping
      ;; it is the whole of stale-reply suppression at this size, and it
      ;; is asserted rather than assumed — see the L0 suite.
      {})))

(rf/reg-event ::save-failed
  {:doc "The server refused a draft. Keep the draft; show why."}
  (fn [{:keys [db]} [_ {:keys [slug problem]}]]
    (if (= slug (get-in db [:save :slug]))
      {:db (assoc db :save {:status :failed :slug slug :problem problem})}
      {})))

(rf/reg-fx ::persist
  {:doc       "The stand-in server: waits, applies the one server-side rule, replies."
   :platforms #{:client}}
  (fn [ctx {:keys [slug draft taken? delay on-ok on-fail]}]
    ;; `ctx` carries `:frame`, so the reply goes back to the frame the
    ;; request came from and to no other. That is the whole of what an
    ;; async effect needs from the runtime, and it is why this stub holds
    ;; no captured frame, no closure over a root and no global.
    (js/setTimeout
      (fn []
        (if taken?
          (rf/dispatch [on-fail {:slug slug :problem :problem/title-taken}]
                       {:frame (:frame ctx)})
          (rf/dispatch [on-ok {:slug slug :draft draft}]
                       {:frame (:frame ctx)})))
      delay)))

;; ---------------------------------------------------------------------------
;; The digest — a second async source, and the retry an error region offers
;; (rf2-hic-074)
;;
;; There is no stale-reply check on this pair, and its absence is deliberate
;; rather than an oversight the save path caught and this one missed. A save
;; is per-ARTICLE, so two of them can be in flight for different subjects and
;; the late one must be told apart from the current one; the digest is one
;; region with one content source, so every reply carries the same answer and
;; there is nothing for a later one to clobber. A slug check written here
;; would be ceremony copied from a neighbour rather than a rule this handler
;; needs.
;; ---------------------------------------------------------------------------

(def digest-delay-ms
  "How long the stand-in content server takes. Its own number rather than
  a shared one: the witness that drives it waits on the REPLY, and a
  constant two regions share is a constant one of them will eventually be
  tuned for at the other's expense."
  30)

(rf/reg-event ::reload-digest
  {:doc "Ask the content server for the digest again — the intent behind
  the retry control in the digest region's error fallback."}
  (fn [{:keys [db]} _]
    ;; The BLOCKS are left exactly as they are. A reload that emptied them
    ;; first would clear the region's error boundary by moving its reset
    ;; key to `[]`, so the failed region would blink through an empty
    ;; success on its way back — and if the reply never came, the user
    ;; would be left looking at nothing at all instead of at the failure
    ;; and the button that retries it.
    {:db (assoc-in db [:digest :status] :loading)
     :fx [[::fetch-digest {:delay digest-delay-ms :on-ok ::digest-arrived}]]}))

(rf/reg-event ::digest-arrived
  {:doc "The content server answered. Take the payload as it came."}
  (fn [{:keys [db]} [_ {:keys [blocks]}]]
    ;; Taken WITHOUT inspection, and that is the honest shape: a client
    ;; that validated every block before rendering it would need a second
    ;; schema for every content type it will ever be sent, and the region's
    ;; error boundary exists precisely so it does not. A payload that
    ;; breaks a renderer's contract is caught where it breaks — see
    ;; `views/list-block` and the region around it.
    {:db (assoc db :digest {:status :ready :blocks blocks})}))

(rf/reg-fx ::fetch-digest
  {:doc       "The stand-in content server: waits, then sends the digest whole."
   :platforms #{:client}}
  (fn [ctx {:keys [delay on-ok]}]
    (js/setTimeout
      (fn []
        (rf/dispatch [on-ok {:blocks db/digest}] {:frame (:frame ctx)}))
      delay)))

;; ---------------------------------------------------------------------------
;; Presentation state — a locale and a theme are ordinary values
;; ---------------------------------------------------------------------------

(rf/reg-event ::set-locale
  {:doc "Switch the display language. One assoc; §7's whole i18n mechanism."}
  ;; Positional and string-valued, both forced by the `<select>` it is
  ;; written at: `::h/value` substitutes at the top level only (see the
  ;; namespace docstring), and what it reads off a `<select>` is
  ;; `.-value`, which is always a string. The keyword-ing happens here
  ;; rather than in the view because the view has no event to read from —
  ;; it writes an intent, and an intent is data.
  (fn [{:keys [db]} [_ locale]]
    {:db (assoc db :locale (keyword locale))}))

(rf/reg-event ::set-theme
  {:doc "Switch the theme. One assoc; §7's whole theming mechanism."}
  (fn [{:keys [db]} [_ {:keys [theme]}]]
    {:db (assoc db :theme theme)}))
