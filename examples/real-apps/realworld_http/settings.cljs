(ns realworld-http.settings
  "User settings page for the RealWorld (Conduit) example.

   This is the form-as-a-machine case. The settings form's whole lifecycle
   lives inside a state machine, `:settings/form`, whose state-keyword IS the
   lifecycle (`:neutral` / `:incorrect` / `:correct` / `:submitting`). The
   other four forms in realworld (`:auth :login-form`, `:auth :register-form`,
   `:editor`, `:comment-form`) stick with the plain
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

   One honest simplification: this form submits eagerly. Hitting 'Update
   Settings' goes straight to a server round-trip, with no client-side validate
   step first. The `:submit-invalid` → `:incorrect` transition is wired up
   anyway, so the lifecycle is complete and you can see where validation WOULD
   slot in — a real app would run a Malli validate against the draft inside
   `:settings/submit` and dispatch `:submit-invalid` when it found problems."
  (:require [re-frame.core :as rf]
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
            ;; `store-session-db` — the shared, nested-dispatch-avoiding
            ;; session write both auth.cljs's classified reply events and
            ;; :settings/submit-success (below) call directly. See its doc.
            [realworld-http.auth :as auth])
  (:require-macros [re-frame.core :refer [reg-view]]))

(defn draft-from-user [user]
  {:image    (or (:image user) "")
   :username (or (:username user) "")
   :bio      (or (:bio user) "")
   :email    (or (:email user) "")
   :password ""})

(def initial-data
  {:draft        (draft-from-user nil)
   :submitted    nil
   :errors       {}
   :touched      #{}
   :submit-error nil
   :loaded-at    nil})

;; ============================================================================
;; THE MACHINE — :settings/form  (one region; the form lifecycle)
;; ============================================================================
;;
;; The form lifecycle becomes machine states, one for one. Hold the two shapes
;; up next to each other — the slice the other four forms use, and the machine
;; this one uses:
;;
;;     ;; SLICE FORM (:auth :login-form, :auth :register-form, :editor, :comment-form)
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
;;     :submitting?  = @(rf/subscribe [:rf.machine/has-tag? :settings/form :settings/in-flight])
;;
;; Same upside as the tags machine: the view never has to know WHICH state
;; means \"in-flight\". It asks the tag and moves on.

(rf/defmachine settings-form-machine
  {:initial :neutral
   :data    initial-data
   ;; The snapshot lives in runtime-db
   ;; ([:rf.runtime/machines :snapshots :settings/form]), not app-db, so its
   ;; :data shape is validated right here via [:schemas :data] — an app-schema
   ;; only sees the app-db partition.
   :schemas {:data app-schema/SettingsFormData}
   ;; The password the user types to change their credentials lands in this
   ;; machine's :data — the live draft at [:data :draft :password] and, once
   ;; a submit is in flight, the snapshot at [:data :submitted :password].
   ;; Both are credentials, so classify them :sensitive relative to the
   ;; snapshot's :data (EP-0025 projection-relative machine classification,
   ;; lowered per actor at spawn). The snapshot then reads :rf/redacted at
   ;; every egress — machine-transition traces, epoch, off-box shipper, SSR —
   ;; while the action bodies still read the live value to send it.
   :sensitive [[:data :draft :password]
               [:data :submitted :password]]

   :actions
   {:seed-from-user
    ;; :load carries the current authenticated user under :user.
    (fn action-seed-from-user [{[_ {:keys [user now]}] :event}]
      {:data (-> initial-data
                 (assoc :draft (draft-from-user user))
                 (assoc :loaded-at now))})

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
    (fn action-begin-submit [{data :data [_ {:keys [submitted]}] :event}]
      {:data (-> data
                 (assoc :submitted submitted)
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
     :on   {:edit           {:target :neutral    :action :edit-field}
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
     :on   {:submit-succeeded {:target :correct   :action :store-user}
            :submit-failed    {:target :incorrect :action :set-submit-error}
            :reset            {:target :neutral   :action :reset-data}}}

    :correct
    ;; The happy-path "saved!" beat. Transient — the next :edit returns to
    ;; :neutral. In slice form this is `:status :submitted`, where the view
    ;; usually navigates away on success; this one does too, see
    ;; :settings/submit-success below.
    {:tags #{:settings/correct :form/success :form/transient}
     :on   {:edit          {:target :neutral :action :edit-field}
            :edit-password {:target :neutral :action :edit-password}
            :reset         {:target :neutral :action :reset-data}}}}})

;; The OPTS map (the SECOND arg here, distinct from the SPEC's own :sensitive
;; above) is the machine's EVENT-rooted classification (rf2-ghgbqi, agb5jk
;; item 2) — it redacts the ROUTED sub-event echoed into the machine trace's
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
  {:doc "Save the settings draft. No retry — one submission per click.
         Broadcasts :submit-valid into the machine (which moves it to
         :submitting and clears any prior errors); when the reply lands,
         :settings/submit-success / :settings/submit-error broadcast
         :submit-succeeded / :submit-failed in turn."
   :rf.http/decode-schemas [schema/UserResponse]}
  ;; The machine snapshot lives in runtime-db.
  (fn handler-settings-submit [{rt :rf.db/runtime} _]
    (let [draft (get-in rt [:rf.runtime/machines :snapshots :settings/form :data :draft])]
      {:fx [[:dispatch [:settings/form
                        [:submit-valid {:submitted draft}]]]
            [:rf.http/managed
             (rh/request {:method     :put
                          :path       "/user"
                          :body       {:user (cond-> (select-keys draft [:image :username :bio :email])
                                               (seq (:password draft))
                                               (assoc :password (:password draft)))}
                          :sensitive? true
                          :decode     schema/UserResponse
                          :on-success [:settings/submit-success]
                          :on-failure [:settings/submit-error]})]]})))

(rf/reg-event :settings/submit-success
  {:doc "Server said yes. Three things follow: fold the returned user into the
         machine's :data via :store-user (region lands in :correct), store the
         session (durable [:auth :token] + [:auth :user]) so the rest of the
         app sees the update, and navigate off to the user's profile page.
         The reply rides a map payload classified :sensitive — RealWorld's
         PUT /user reply carries a fresh User (and therefore a fresh token,
         just like login/register)."
   :sensitive [[:value :user :token]]}
  (fn handler-settings-submit-success [{:keys [db]} [_ {:keys [value]}]]
    (let [user (:user value)]
      ;; `store-session-db` is called DIRECTLY (not via a nested
      ;; `[:dispatch [:auth/store-session user]]`) for the same reason
      ;; auth.cljs's own reply events do — see that fn's doc. The
      ;; machine-routed :submit-succeeded sub-event never needs the token at
      ;; all (:store-user's `draft-from-user` never reads it), so it is
      ;; `dissoc`'d before crossing into the machine — the token is never
      ;; even offered to that nested dispatch, not merely classified after
      ;; the fact.
      {:db (auth/store-session-db db user)
       :fx [[:dispatch [:settings/form
                        [:submit-succeeded {:user (dissoc user :token)}]]]
            [:dispatch [:rf.route/navigate {:to :realworld.profile/show :params {:username (:username user)}}]]]})))

(rf/reg-event :settings/submit-error
  {:doc "Server said no. Folds a readable error message into the machine's
         :data via :set-submit-error; the region lands in :incorrect — the very
         same surface the client-side validation path uses, since both show up
         through :submit-error / :errors. One place to render \"something's
         wrong\", however it went wrong."}
  (fn handler-settings-submit-error [_ [_ {:keys [error]}]]
    {:fx [[:dispatch [:settings/form
                      [:submit-failed {:submit-error (rh/failure->message error)}]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The view sees plain, ordinary names (`:settings/draft`,
;; `:settings/submit-error`), all sourced from the machine's `:data`. And
;; `:settings/submitting?` is a `[:rf.machine/has-tag? …]` query underneath, handed
;; to the view as a plain boolean — the machine-ness stays behind the curtain.

(rf/reg-sub :settings/draft
  {:doc "The settings-form draft, read out of the machine's :data."
   :inputs [[:rf/machine :settings/form]]}
  (fn sub-settings-draft [[snap] _]
    (get-in snap [:data :draft])))

(rf/reg-sub :settings/submit-error
  {:doc "The latest settings-submit error, read out of the machine's :data."
   :inputs [[:rf/machine :settings/form]]}
  (fn sub-settings-submit-error [[snap] _]
    (get-in snap [:data :submit-error])))

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
        submit-error @(subscribe [:settings/submit-error])]
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
             :on-change #(dispatch [:settings/edit-field :username (.. % -target -value)])}]]
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
             :on-change #(dispatch [:settings/edit-field :email (.. % -target -value)])}]]
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
