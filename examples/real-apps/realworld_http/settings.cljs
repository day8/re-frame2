(ns realworld-http.settings
  "User settings page for the RealWorld (Conduit) example.

   This is the form-as-a-machine case. The settings form's whole lifecycle
   lives inside a state machine, `:settings/form`, whose state-keyword IS the
   lifecycle (`:neutral` / `:incorrect` / `:correct` / `:submitting`). The
   comment form (`:comment-form`) sticks with the plain
   `{:draft :submitted :status :errors :touched :submit-error}` slice, so the
   two approaches sit side by side for comparison. See the forms how-to:
   ../../../docs/core/how-to/build-a-form.md

   What moving the form into a machine buys (and costs):

   - The lifecycle (`:neutral` / `:incorrect` / `:correct` + `:submitting`)
     becomes machine states, one for one — the slice's `:status` field is gone,
     because the state-keyword now IS the status.
   - The draft, errors, touched, submit-error, submitted, and loaded-at all
     move into the machine's `:data` map; there's no app-db slice.
   - The slice's `:submitting?` boolean turns into a per-state
     `:settings/in-flight` tag, asked with the `:rf.machine/has-tag?` sub.

   Logout stays where it belongs, on the auth machine (`:auth/flow`).

   `:settings/submit` checks the draft before anything goes on the wire. A
   blank username or email broadcasts `:submit-invalid`, which lands the form
   in `:incorrect` with per-field messages — the same state a server rejection
   reaches through `:submit-failed`."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ;; State machines live in their own artefact; we require it to load
            ;; it, which registers the hooks that make `rf/reg-machine` (below)
            ;; and the `:rf/machine` / `:rf.machine/has-tag?` subs resolve. See
            ;; the machines guide: ../../../docs/machines/index.md
            [re-frame.machines]
            ;; Wire contract (UserResponse) from the shared ns; the machine's
            ;; own snapshot `:data` schema (SettingsFormData) is app-local.
            [realworld-shared.schema :as schema]
            [realworld-http.schema :as app-schema]
            [realworld-http.http :as rh]
            ;; `store-session-db` — the shared session write both auth.cljs's
            ;; classified reply events and :settings/submit-success (below)
            ;; fold into their own `:db`. See the comment above it.
            [realworld-http.auth :as auth])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn draft-from-user [user]
  {:image    (or (:image user) "")
   :username (or (:username user) "")
   :bio      (or (:bio user) "")
   :email    (or (:email user) "")
   :password ""})

(defn validate-draft
  "Per-field problems with a settings draft, as `{field [message]}` — empty when
   the draft can be sent. Conduit names a profile by its username and signs in
   by email, so neither may be blank."
  [{:keys [username email]}]
  (cond-> {}
    (str/blank? username) (assoc :username ["Username is required."])
    (str/blank? email)    (assoc :email ["Email is required."])))

(def initial-data
  {:draft         (draft-from-user nil)
   :submitted     nil
   :errors        {}
   :touched       #{}
   :submit-error  nil
   :loaded-at     nil
   ;; The save the form is currently waiting on — `{:owner … :request-id …}`,
   ;; or nil when nothing is in flight. `:owner` is whose session issued it;
   ;; `:request-id` is the id the PUT carries, which its reply echoes.
   ;; Recorded by :begin-submit, read back by the two reply handlers. See the
   ;; SESSION OWNERSHIP note above :settings/submit-success.
   :pending       nil})

;; ----------------------------------------------------------------------------
;; SAVE IDS — which save a reply answers
;; ----------------------------------------------------------------------------
;;
;; Every settings PUT carries a `:request-id` no other save has carried,
;; `[:settings/save n]`, and the managed-HTTP reply echoes it back at
;; `[:correlation :request-id]`, on success and failure alike (Spec 014
;; §Reply payload shape). So "is this reply the save the form awaits?" is one
;; equality check.
;;
;; `n` comes off a counter at the TOP LEVEL of app-db rather than out of the
;; machine's `:data`, because logout resets the machine (auth.cljs
;; `:clear-session`) and `:settings/load` re-seeds it on every route entry, and
;; an id issued after either must still differ from every id on the wire. The
;; counter is a bare number about no account, so outliving the session is
;; harmless, and being app-db state it replays deterministically.
;;
;; An id no other request carries supersedes nothing: managed HTTP aborts an
;; earlier request only for a later one under the SAME id (Spec 014
;; §`:request-id` (internal)), so this id buys correlation and nothing else.

(def ^:private save-seq-key
  "The app-db key counting the settings saves issued. Named once."
  :settings.save-seq)

(defn- awaited-reply?
  "Is `reply` the answer to `awaited`, the save the form is waiting on? True
   exactly when the request id the reply echoes is the one recorded at submit;
   false while nothing is awaited."
  [awaited reply]
  (and (some? awaited)
       (= (:request-id awaited) (get-in reply [:correlation :request-id]))))

;; ============================================================================
;; THE MACHINE — :settings/form  (one region; the form lifecycle)
;; ============================================================================
;;
;; The form lifecycle becomes machine states, one for one. Hold the two shapes
;; up next to each other — the slice the other four forms use, and the machine
;; this one uses:
;;
;;     ;; SLICE FORM (:comment-form)
;;     ;; A plain map with an explicit :status keyword.
;;     {:draft        {...}
;;      :submitted    nil
;;      :status       :idle | :submitting
;;      :errors       {}
;;      :touched      #{}
;;      :submit-error nil}
;;
;;     ;; MACHINE FORM (this file)
;;     ;; The state-keyword IS the lifecycle; the rest moves into :data.
;;     {:state :neutral | :incorrect | :correct | :submitting
;;      :data  {:draft {...} :errors {} :touched #{} :submit-error nil
;;              :submitted nil :loaded-at nil}
;;      :tags  #{...}}
;;
;; And the form's one load-bearing boolean, `:submitting?`, becomes a tag
;; question about the current state:
;;
;;     :submitting?  = (= :submitting status)            ;; slice form
;;     :submitting?  = @(subscribe [:rf.machine/has-tag? :settings/form :settings/in-flight])
;;
;; Same upside as the tags machine: the view never has to know WHICH state
;; means \"in-flight\". It asks the tag and moves on.

(rf/defmachine settings-form-machine
  {:initial :neutral
   :data    initial-data
   ;; The snapshot lives in runtime-db (read it with [:rf/machine
   ;; :settings/form]), not app-db, so its :data shape is validated right here
   ;; via [:schemas :data] — an app-schema only sees the app-db partition.
   :schemas {:data app-schema/SettingsFormData}
   ;; The password the user types to change their credentials lands in this
   ;; machine's :data — the live draft at [:data :draft :password] and, once
   ;; a submit is in flight, the snapshot at [:data :submitted :password].
   ;; Both are credentials, so classify them :sensitive relative to the
   ;; snapshot's :data (EP-0025 projection-relative machine classification,
   ;; applied at egress to each actor present). The snapshot then reads :rf/redacted at
   ;; every egress — machine-transition traces, epoch, off-box shipper, SSR —
   ;; while the action bodies still read the live value to send it.
   :sensitive [[:data :draft :password]
               [:data :submitted :password]]

   :actions
   {:seed-from-user
    ;; :load carries the current authenticated user under :user.
    ;;
    ;; It rebuilds :data from initial-data, so a re-entry starts clean — but it
    ;; CARRIES :pending across, because that field records a REQUEST that may
    ;; still be in flight rather than anything about the form on screen. Drop
    ;; it and the two reply handlers below would refuse the very submission
    ;; they were issued for, and discard a save the server had already
    ;; performed. A carried record never admits the wrong reply: the handlers
    ;; match a reply by the request id it echoes, and a later submit replaces
    ;; the record with its own id. See SESSION OWNERSHIP below.
    (fn action-seed-from-user [{data :data [_ {:keys [user now]}] :event}]
      {:data (-> initial-data
                 (assoc :draft (draft-from-user user))
                 (assoc :loaded-at now)
                 (assoc :pending (:pending data)))})

    :edit-field
    ;; :edit carries {field value} for a NON-SECRET field. It writes the
    ;; value, marks the field touched, clears any leftover submit-error (so an
    ;; old banner doesn't linger while you're fixing things), and drops THIS
    ;; field's inline error — the red text fades as you type, which is how
    ;; forms should feel.
    (fn action-edit-field [{data :data [_ {:keys [field value]}] :event}]
      {:data (-> data
                 (assoc-in [:draft field] value)
                 (update :touched (fnil conj #{}) field)
                 (update :errors  dissoc field)
                 (assoc :submit-error nil))})

    :edit-password
    ;; :edit-password carries {:password value} — its OWN sub-event, keyed by
    ;; :password rather than the generic {:field :value} shape :edit-field
    ;; uses, so the reg-machine call below can classify it precisely
    ;; ([[1 :password]]) without also catching :edit-field's non-secret
    ;; :value key. Same bookkeeping as :edit-field otherwise.
    (fn action-edit-password [{data :data [_ {:keys [password]}] :event}]
      {:data (-> data
                 (assoc-in [:draft :password] password)
                 (update :touched (fnil conj #{}) :password)
                 (update :errors  dissoc :password)
                 (assoc :submit-error nil))})

    :set-errors
    ;; :submit-invalid carries the per-field error map. We also mark every
    ;; errored field touched, so the inline messages appear even on fields the
    ;; user never got around to filling in.
    (fn action-set-errors [{data :data [_ {:keys [errors]}] :event}]
      {:data (-> data
                 (assoc :errors errors)
                 (update :touched (fnil into #{}) (keys errors))
                 (assoc :submit-error nil))})

    :begin-submit
    ;; :submit-valid carries the draft snapshot we just sent to the server —
    ;; :submitted keeps it (durably classified via [:data :submitted
    ;; :password] above) so a later refresh of the page can see what was last
    ;; sent, but the LIVE draft's password is blanked right here — secret-field
    ;; hygiene once the request is in flight, mirroring
    ;; examples/core/login's submit-form. Wipe :errors and :submit-error so
    ;; nothing lingers from a previous failed attempt while this one's in
    ;; flight.
    ;;
    ;; It also carries :pending — who the PUT is being sent as, and the request
    ;; id it carries. THIS is the recording point rather than :load: a record
    ;; written when the form was LOADED names whoever opened the page, which is
    ;; not necessarily who the request eventually goes out as. A submit can only
    ;; be made from :neutral or :incorrect, and it is the moment the request
    ;; goes out. The record is the CURRENTLY-AWAITED save and replaces any
    ;; earlier one, so it answers "what are we waiting for?"; the reply's echoed
    ;; id answers "which save is this?".
    (fn action-begin-submit [{data :data [_ {:keys [submitted pending]}] :event}]
      {:data (-> data
                 (assoc :submitted submitted)
                 (assoc :pending pending)
                 (assoc-in [:draft :password] "")
                 (assoc :errors {})
                 (assoc :submit-error nil))})

    :store-user
    ;; :submit-succeeded carries the user the server saved. Re-seed the draft
    ;; from it, so the next edit starts from the freshly-saved values rather
    ;; than whatever was typed before the save.
    (fn action-store-user [{data :data [_ {:keys [user]}] :event}]
      {:data (-> data
                 (assoc :draft (draft-from-user user))
                 (assoc :errors {})
                 (assoc :submit-error nil))})

    :set-submit-error
    ;; :submit-failed carries a projected human-readable failure
    ;; message under :submit-error.
    (fn action-set-submit-error [{data :data [_ {:keys [submit-error]}] :event}]
      {:data (-> data
                 (assoc :submit-error submit-error))})

    :reset-data
    (fn action-reset-data [_]
      {:data initial-data})}

   ;; :load is accepted in EVERY state below, not only :neutral. It is the
   ;; route's "I am here now" signal (the :realworld.user/settings :on-match
   ;; fires it — routing.cljs), and a visit that ended in a save (:correct) or
   ;; in a rejection (:incorrect) leaves the region parked there. A machine that
   ;; ignored :load in those states would hand the NEXT account to sign in the
   ;; departed one's username / email / bio / image, pre-filled and one click
   ;; from being PUT onto its own account. Re-seeding is idempotent, so
   ;; accepting it everywhere costs nothing and closes that leak; logout scrubs
   ;; the snapshot from the other end too (:clear-session, auth.cljs).
   :states
   {:neutral
    ;; The resting state — form open, nothing to report. Either the user hasn't
    ;; hit a validation error or a success yet, or they did and a later :edit
    ;; brought things back here. The calm default.
    {:tags #{:settings/neutral}
     :on   {:load           {:target :neutral    :action :seed-from-user}
            :edit           {:target :neutral    :action :edit-field}
            :edit-password  {:target :neutral    :action :edit-password}
            :submit-invalid {:target :incorrect  :action :set-errors}
            :submit-valid   {:target :submitting :action :begin-submit}
            :reset          {:target :neutral    :action :reset-data}}}

    :incorrect
    ;; Something's wrong and showing: either a per-field validation error on a
    ;; touched field, or a server submit-error from the last attempt. The first
    ;; :edit clears the errors and drops back to :neutral — start fixing and the
    ;; complaints go away.
    {:tags #{:settings/incorrect :form/invalid}
     :on   {:load           {:target :neutral    :action :seed-from-user}
            :edit           {:target :neutral    :action :edit-field}
            :edit-password  {:target :neutral    :action :edit-password}
            :submit-invalid {:target :incorrect  :action :set-errors}
            :submit-valid   {:target :submitting :action :begin-submit}
            :reset          {:target :neutral    :action :reset-data}}}

    :submitting
    ;; Request in flight. The :settings/in-flight tag is what greys out every
    ;; input and the submit button while we wait. The :form/transient tag is a
    ;; convenience: a view that wants to overlay any passing acknowledgement
    ;; (in-flight, success, error) can watch that one tag instead of OR-ing
    ;; three state-keywords.
    {:tags #{:settings/submitting :settings/in-flight :form/transient}
     :on   {:load             {:target :neutral   :action :seed-from-user}
            :submit-succeeded {:target :correct   :action :store-user}
            :submit-failed    {:target :incorrect :action :set-submit-error}
            :reset            {:target :neutral   :action :reset-data}}}

    :correct
    ;; The happy-path "saved!" beat. Transient — the next :edit returns to
    ;; :neutral. In slice form this is `:status :submitted`, where the view
    ;; usually navigates away on success; this one does too, see
    ;; :settings/submit-success below.
    {:tags #{:settings/correct :form/success :form/transient}
     :on   {:load          {:target :neutral :action :seed-from-user}
            :edit          {:target :neutral :action :edit-field}
            :edit-password {:target :neutral :action :edit-password}
            :reset         {:target :neutral :action :reset-data}}}}})

;; The OPTS map (the SECOND arg here, distinct from the SPEC's own :sensitive
;; above) is the machine's EVENT-rooted classification — it redacts the
;; ROUTED sub-event echoed into the machine trace's
;; :event / [:input :event] slots, which the SPEC's :data-rooted :sensitive
;; above does NOT reach (that one only protects the durable snapshot). Rooted
;; at the routed sub-event vector itself: `[1 :password]` catches
;; `[:edit-password {:password …}]`, `[1 :submitted :password]` catches
;; `[:submit-valid {:submitted {…:password …}}]`. Both are keyed by
;; :password specifically (not the generic :edit event's :value), so neither
;; path ever touches a non-secret field edit — the two path roots (event-index
;; vs :data-prefixed) are disjoint from the SPEC's paths too, so this union is
;; safe alongside it.
(rf/reg-machine :settings/form
  {:sensitive [[1 :password] [1 :submitted :password]]}
  settings-form-machine)

;; ============================================================================
;; PUBLIC EVENT API
;; ============================================================================
;;
;; These are the front door. Each event translates into one or more machine
;; broadcasts, and views and sibling namespaces dispatch THESE names — they
;; never reach past them to poke the machine directly. The machine stays an
;; implementation detail.

(rf/reg-event :settings/initialise
  {:doc "Reset the settings-form machine to its initial state. Dispatched from
         :app/initialise at boot."}
  (fn handler-settings-initialise [_ _]
    {:fx [[:dispatch [:settings/form [:reset]]]]}))

(rf/reg-event :settings/load
  {:doc "Fill the form draft from the currently signed-in user, so the page
         opens pre-populated rather than blank. Dispatched by the
         :realworld.user/settings :on-match (routing.cljs), and by tests after
         an :auth/store-session."
   :rf.cofx/requires [:rf/time-ms]}
  (fn handler-settings-load [{:keys [db rf/time-ms]} _]
    (let [user (get-in db [:auth :user])]
      {:fx [[:dispatch [:settings/form
                        [:load {:user user
                                :now  time-ms}]]]]})))

(rf/reg-event :settings/edit-field
  {:doc  "The user changed a NON-SECRET field (image / username / bio /
          email). Broadcasts :edit into the machine, which brings the region
          back from :correct / :incorrect to :neutral and updates the draft +
          :touched. Typing settles the form down. The password field uses
          :settings/edit-password instead — never route a secret through
          this event."
   :schema [:cat [:= :settings/edit-field] :keyword :string]}
  (fn handler-settings-edit-field [_ [_ field value]]
    {:fx [[:dispatch [:settings/form
                      [:edit {:field field :value value}]]]]}))

;; The password's keystrokes get their OWN event and a MAP payload, exactly
;; the split :auth.login-form/edit-password uses (auth.cljs) — a positional
;; arg isn't path-addressable, so `:sensitive [[:value]]` on THIS event
;; classifies the dispatched-event trace, while the machine-side echo is
;; separately covered by the reg-machine OPTS `:sensitive` above.
(rf/reg-event :settings/edit-password
  {:doc       "The user changed the password field. Broadcasts :edit-password
               into the machine. The value rides a map payload classified
               :sensitive so this event's own dispatched-event trace redacts
               it."
   :sensitive [[:value]]
   :schema    [:cat [:= :settings/edit-password] [:map [:value :string]]]}
  (fn handler-settings-edit-password [_ [_ {:keys [value]}]]
    {:fx [[:dispatch [:settings/form
                      [:edit-password {:password value}]]]]}))

(rf/reg-event :settings/submit
  {:doc "Save the settings draft. No retry — one submission per click. A draft
         `validate-draft` rejects broadcasts :submit-invalid and sends nothing.
         Otherwise broadcasts :submit-valid into the machine (which moves it to
         :submitting and clears any prior errors); when the reply lands,
         :settings/submit-success / :settings/submit-error broadcast
         :submit-succeeded / :submit-failed in turn."}
  ;; The machine snapshot lives in runtime-db; the session identity lives in
  ;; app-db, so this handler reads both partitions.
  (fn handler-settings-submit [{:keys [db] rt :rf.db/runtime} _]
    (let [draft  (get-in (rf/compute-sub [:rf/machine :settings/form] rt) [:data :draft])
          errors (validate-draft draft)]
      (if (seq errors)
        ;; A draft that fails validation sends nothing, so it takes no id.
        {:fx [[:dispatch [:settings/form [:submit-invalid {:errors errors}]]]]}
        ;; The issuance — who is sending it, and the id its reply will echo —
        ;; fixed HERE, at the moment the request goes out (SAVE IDS above).
        (let [n       (inc (get db save-seq-key 0))
              pending {:owner      (auth/session-owner db)
                       :request-id [:settings/save n]}]
          {:db (assoc db save-seq-key n)
           :fx [[:dispatch [:settings/form
                            [:submit-valid {:submitted draft
                                            :pending   pending}]]]
                [:rf.http/managed
                 (rh/request {:method     :put
                              :path       "/user"
                              :body       {:user (cond-> (select-keys draft [:image :username :bio :email])
                                                   (seq (:password draft))
                                                   (assoc :password (:password draft)))}
                              :sensitive? true
                              :decode     schema/UserResponse
                              :request-id (:request-id pending)
                              :on-success [:settings/submit-success]
                              :on-failure [:settings/submit-error]})]]})))))

;; ----------------------------------------------------------------------------
;; SESSION OWNERSHIP — the two reply handlers below ask TWO questions, in order
;; ----------------------------------------------------------------------------
;;
;; Both Logout buttons — the one on this page and the one in the navbar — stay
;; live while a save is in flight, and they should: waiting is not what a user
;; who wants out is asking for. But the PUT is already on the wire, and logging
;; out does not unsend it. So either reply can land on a signed-out app — or,
;; worse, on an app somebody ELSE has since signed into and saved from.
;;
;; That second case is why there are two questions rather than one. Asking only
;; "is the recorded owner still signed in?" is not enough, because the record
;; is overwritten by whoever submits next (`:begin-submit`). Park alice's PUT,
;; log out, sign bob in, let bob save, and the recorded owner is BOB — so
;; alice's late reply would be compared bob-against-bob, pass, and write
;; alice's User and token into bob's session, then navigate to alice's profile.
;;
;;   1. IS THIS REPLY THE SAVE WE ARE WAITING ON? `awaited-reply?`: the id the
;;      reply echoes against the one the form recorded (SAVE IDS above).
;;      Alice's reply echoes her own id, which is not bob's. A reply that is
;;      NOT the awaited save is left strictly alone: no auth write, no form
;;      change, no navigation, and above all no :reset, because the form and
;;      the request it would settle now belong to somebody else. Refusing must
;;      not trade a wrong write for a wrong wipe.
;;
;;   2. IS THE SESSION THAT ISSUED IT STILL SIGNED IN? auth.cljs's
;;      `owns-session?`, which carries the full why. Only once question 1 has
;;      established that the reply IS the awaited save does refusing here mean
;;      "the issuer has gone and nobody newer is waiting" — and THAT refusal
;;      does broadcast :reset, which settles the form out of :submitting and
;;      scrubs the departed user's draft out of the snapshot on the way past.
;;
;; Neither question reads the User the reply carries. Its username is one the
;; form typed, and typing a name that belongs to somebody else is ordinary
;; input, so it identifies no request; the echoed id does.
;;
;; Both reply targets stay ONE element long, and for the success reply that is
;; a hard constraint: it carries a fresh JWT, and only `(second event)` is
;; path-redactable (Spec 015 §Registration-owned transient classification —
;; the positional fail-open). Managed HTTP APPENDS its reply as the last arg,
;; so a positional arg added to the target pushes the reply to an
;; unaddressable slot and `:sensitive [[:value :user :token]]` below silently
;; stops reaching the token — shipping it raw into every trace and error sink.
;; auth.cljs's session-establishing events exist for exactly this reason; see
;; the note above them.
;;
;; Both questions above are about the SAVE and the SESSION. The navigation to
;; the profile is a different outcome with a different owner: a NAVIGATION is
;; the ROUTE's (TWO QUESTIONS, comments.cljs). Nothing blocks leaving /settings
;; mid-save, so a reply that lands after the reader walked to an article or the
;; editor must not drag them to their profile. The session store still happens,
;; because the session is the account's wherever the reader is; only the
;; navigate asks whether the committed route is still /settings.
(defn- settings-route-current?
  "Is the ACTIVE ROUTE still the settings page? Reads RUNTIME-db, where the
   route slice lives. The resources twin carries the same predicate."
  [rt]
  (= :realworld.user/settings (:route-id (rf/compute-sub [:rf/route] rt))))

(rf/reg-event :settings/submit-success
  {:doc "Server said yes. Three things follow: fold the returned user into the
         machine's :data via :store-user (region lands in :correct), store the
         session (durable [:auth :token] + [:auth :user]) so the rest of the
         app sees the update, and navigate off to the user's profile page
         (only while the reader is still on /settings) — unless the reply is
         not the save this form is waiting on (left strictly alone — a newer
         save owns the form), or the session that issued it is gone (the form
         resets and none of it happens, since storing the reply would restore
         the logged-out user's credentials). See SESSION OWNERSHIP above for
         both questions and for why THIS target must stay one-element. The
         reply rides a map payload classified :sensitive — RealWorld's PUT
         /user reply carries a fresh User (and therefore a fresh token, just
         like login/register)."
   :sensitive [[:value :user :token]]}
  (fn handler-settings-submit-success [{:keys [db] rt :rf.db/runtime} [_ {:keys [value] :as reply}]]
    (let [awaited (get-in (rf/compute-sub [:rf/machine :settings/form] rt) [:data :pending])]
      (cond
        ;; Q1 — not the save we are waiting on.
        (not (awaited-reply? awaited reply))
        nil

        ;; Q2 — it IS our save, but the session that issued it has gone.
        (not (auth/owns-session? db (:owner awaited)))
        {:fx [[:dispatch [:settings/form [:reset]]]]}

        :else
        (let [user (:user value)]
          ;; `store-session-db` is called DIRECTLY (not via a nested
          ;; `[:dispatch [:auth/store-session user]]`), so the session lands in
          ;; this reply's own `:db` commit, as in auth.cljs's own reply events —
          ;; see the comment above that fn. The
          ;; machine-routed :submit-succeeded sub-event never needs the token at
          ;; all (:store-user's `draft-from-user` never reads it), so it is
          ;; `dissoc`'d before crossing into the machine — the token is never
          ;; even offered to that nested dispatch, not merely classified after
          ;; the fact.
          {:db (auth/store-session-db db user)
           :fx (cond-> [[:auth.session/persist {:token (:token user)}]
                        [:dispatch [:settings/form
                                    [:submit-succeeded {:user (dissoc user :token)}]]]]
                 ;; The route's question, not the session's: only a reader
                 ;; still on /settings is taken to the profile (see
                 ;; `settings-route-current?`).
                 (settings-route-current? rt)
                 (conj [:dispatch [:rf.route/navigate {:to :realworld.profile/show :params {:username (:username user)}}]]))})))))

(rf/reg-event :settings/submit-error
  {:doc "Server said no. Folds a readable error message into the machine's
         :data via :set-submit-error; the region lands in :incorrect — the very
         same surface the client-side validation path uses, since both show up
         through :submit-error / :errors. One place to render \"something's
         wrong\", however it went wrong. Unless this is not the save the form is
         waiting on, in which case it is left strictly alone — bannering it
         would put one account's error on another's form and settle a request
         that is still in flight. Or unless the session that issued the save is
         gone: there is nobody left to show it to, and the message would sit in
         the snapshot waiting for the next user, so the form resets instead.
         Matched to its save by the request id it echoes, exactly as the
         success reply is — see SESSION OWNERSHIP above."}
  (fn handler-settings-submit-error [{:keys [db] rt :rf.db/runtime} [_ {:keys [error] :as reply}]]
    (let [awaited (get-in (rf/compute-sub [:rf/machine :settings/form] rt) [:data :pending])]
      (cond
        ;; Q1 — not the save we are waiting on; somebody else's save owns this
        ;; form and this in-flight request now.
        (not (awaited-reply? awaited reply))
        nil

        ;; Q2 — it IS our save, but the session that issued it has gone.
        (not (auth/owns-session? db (:owner awaited)))
        {:fx [[:dispatch [:settings/form [:reset]]]]}

        :else
        {:fx [[:dispatch [:settings/form
                          [:submit-failed {:submit-error (rh/failure->message error)}]]]]}))))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The view sees plain, ordinary names (`:settings/draft`,
;; `:settings/submit-error`, `:settings/field-error`), all sourced from the machine's `:data`. And
;; `:settings/submitting?` is a `[:rf.machine/has-tag? …]` query underneath, handed
;; to the view as a plain boolean — the machine-ness stays behind the curtain.

(rf/reg-sub :settings/draft
  {:doc       "The settings-form draft, read out of the machine's :data."
   :inputs    [[:rf/machine :settings/form]]
   ;; The machine's own `:sensitive` covers its snapshot, not this output.
   :sensitive [[:password]]}
  (fn sub-settings-draft [[snap] _]
    (get-in snap [:data :draft])))

(rf/reg-sub :settings/submit-error
  {:doc "The latest settings-submit error, read out of the machine's :data."
   :inputs [[:rf/machine :settings/form]]}
  (fn sub-settings-submit-error [[snap] _]
    (get-in snap [:data :submit-error])))

(rf/reg-sub :settings/field-error
  {:doc "The validation message for one settings field, or nil. Same courtesy as
         the app's other forms: nothing shows until the field is touched, and
         `:set-errors` marks every errored field touched."
   :inputs [[:rf/machine :settings/form]]}
  (fn sub-settings-field-error [[snap] [_ field]]
    (let [{:keys [errors touched]} (:data snap)]
      (when (contains? touched field)
        (first (get errors field))))))

(rf/reg-sub :settings/submitting?
  {:doc "Is a save in flight? A tag-shaped read of the form's in-flight intent —
         the machine-form stand-in for a slice's `(= :submitting status)`. Views
         just see a boolean."
   :inputs [[:rf.machine/has-tag? :settings/form :settings/in-flight]]}
  (fn sub-settings-submitting? [[in-flight?] _]
    (boolean in-flight?)))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view settings-page []
  (let [draft        @(subscribe [:settings/draft])
        submitting?  @(subscribe [:settings/submitting?])
        submit-error @(subscribe [:settings/submit-error])
        username-err @(subscribe [:settings/field-error :username])
        email-err    @(subscribe [:settings/field-error :email])]
    [:div.settings-page
     [:div.container.page
      [:div.row
       [:div.col-md-6.offset-md-3.col-xs-12
        [:h1.text-xs-center "Your Settings"]
        (when submit-error
          [:ul.error-messages [:li submit-error]])
        [:form
         {:on-submit (fn [e]
                       (.preventDefault e)
                       (dispatch [:settings/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control
            {:type "text"
             :name "image"
             :placeholder "URL of profile picture"
             :value (:image draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :image (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text"
             :name "username"
             :placeholder "Username"
             :value (:username draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :username (.. % -target -value)])}]
           (when username-err
             [:div.error-messages username-err])]
          [:fieldset.form-group
           [:textarea.form-control.form-control-lg
            {:rows 8
             :name "bio"
             :placeholder "Short bio about you"
             :value (:bio draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :bio (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "email"
             :name "email"
             :placeholder "Email"
             :value (:email draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :email (.. % -target -value)])}]
           (when email-err
             [:div.error-messages email-err])]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "password"
             :name "password"
             :placeholder "New Password"
             :value (:password draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-password {:value (.. % -target -value)}])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right
           {:type "submit" :disabled submitting?}
           (if submitting? "Updating…" "Update Settings")]]]
        [:hr]
        [:button.btn.btn-outline-danger
         {:type "button"
          :on-click #(dispatch [:auth/flow [:auth/logout]])}
         "Or click here to logout"]]]]]))
