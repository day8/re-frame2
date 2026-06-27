(ns realworld.settings
  "User settings page for the RealWorld (Conduit) example.

   Here the form's lifecycle lives entirely in a state machine,
   `:settings/form`, whose state-keyword is the form lifecycle (`:neutral`
   / `:incorrect` / `:correct` / `:submitting`). The other four forms in
   realworld (`:auth :login-form`, `:auth :register-form`, `:editor`,
   `:comment-form`) keep the plain
   `{:draft :submitted :status :errors :touched :submit-error}` slice
   shape, so a reader can compare the two shapes side by side. See the
   forms how-to: ../../../docs/guide/how-to/build-a-form.md

   The shape:

   - The form lifecycle (`:neutral` / `:incorrect` / `:correct` +
     `:submitting`) maps one-to-one onto machine states; the slice's
     `:status` field is gone because the state-keyword is the status.
   - The draft, errors, touched, submit-error, submitted, and loaded-at
     fields live in the machine's `:data` map (no app-db slice).
   - The slice's `:submitting?` boolean becomes a per-state
     `:settings/in-flight` tag queried with `rf/machine-has-tag?`.

   Logout stays on the auth machine path (`:auth/flow`).

   This form submits eagerly: pressing 'Update Settings' triggers a server
   roundtrip with no prior client-side validate step. The `:submit-invalid`
   / `:incorrect` transition exists so the lifecycle is complete; a real
   app would run a Malli validate against the draft inside `:settings/submit`
   and dispatch `:submit-invalid` when it returned errors."
  (:require [re-frame.core :as rf]
            ;; State machines ship in the re-frame2-machines artefact.
            ;; Requiring the ns registers its hooks so `rf/reg-machine`
            ;; (called below) and the `:rf/machine` / `:rf/machine-has-tag?`
            ;; subs resolve. See the machines guide:
            ;; ../../../docs/machines/index.md
            [re-frame.machines]
            [realworld.schema :as schema]
            [realworld.http :as rh])
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
;; The form lifecycle maps one-to-one onto machine states. Compare with the
;; slice form used by the other four realworld forms:
;;
;;     ;; SLICE FORM (used by :auth :login-form, :auth :register-form, :editor, :comment-form)
;;     ;; The slice carries an explicit :status keyword.
;;     {:draft        {...}
;;      :submitted    nil
;;      :status       :idle | :submitting
;;      :errors       {}
;;      :touched      #{}
;;      :submit-error nil}
;;
;;     ;; MACHINE FORM (used here)
;;     ;; The state-keyword IS the lifecycle; the rest lives in :data.
;;     {:state :neutral | :incorrect | :correct | :submitting
;;      :data  {:draft {...} :errors {} :touched #{} :submit-error nil
;;              :submitted nil :loaded-at nil}
;;      :tags  #{...}}
;;
;; The form's load-bearing boolean — `:submitting?` — becomes a tag query
;; against the active state:
;;
;;     :submitting?  = (= :submitting status)            ;; slice form
;;     :submitting?  = @(rf/machine-has-tag? :settings/form :settings/in-flight)
;;
;; The view doesn't need to know which state-keyword carries the
;; "in-flight" intent; the tag does.

(def settings-form-machine
  {:initial :neutral
   :data    initial-data
   ;; The snapshot lives in runtime-db
   ;; ([:rf.runtime/machines :snapshots :settings/form]), so its :data shape
   ;; is validated here via [:schemas :data], not via an app-schema (app
   ;; schemas validate the app-db partition only).
   :schemas {:data schema/SettingsFormData}

   :actions
   {:seed-from-user
    ;; :load carries the current authenticated user under :user.
    (fn action-seed-from-user [{[_ {:keys [user now]}] :event}]
      {:data (-> initial-data
                 (assoc :draft (draft-from-user user))
                 (assoc :loaded-at now))})

    :edit-field
    ;; :edit carries [field value]; touches the field, clears any
    ;; prior submit-error so a fresh edit doesn't keep the old error
    ;; banner visible, and drops the per-field error entry for the
    ;; edited field so the inline error disappears as the user types.
    (fn action-edit-field [{data :data [_ {:keys [field value]}] :event}]
      {:data (-> data
                 (assoc-in [:draft field] value)
                 (update :touched (fnil conj #{}) field)
                 (update :errors  dissoc field)
                 (assoc :submit-error nil))})

    :set-errors
    ;; :submit-invalid carries the per-field error map. Touch every error
    ;; field too, so the inline error shows even on fields the user hasn't
    ;; interacted with yet.
    (fn action-set-errors [{data :data [_ {:keys [errors]}] :event}]
      {:data (-> data
                 (assoc :errors errors)
                 (update :touched (fnil into #{}) (keys errors))
                 (assoc :submit-error nil))})

    :begin-submit
    ;; :submit-valid carries the draft snapshot we just dispatched to
    ;; the server. Clear :errors and :submit-error so they don't
    ;; linger from a prior failed attempt.
    (fn action-begin-submit [{data :data [_ {:keys [submitted]}] :event}]
      {:data (-> data
                 (assoc :submitted submitted)
                 (assoc :errors {})
                 (assoc :submit-error nil))})

    :store-user
    ;; :submit-succeeded carries the server's returned user. Re-seed
    ;; the draft from the new user so a subsequent edit starts from
    ;; the freshly-saved state.
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
    ;; The resting state. The form is open; the user hasn't seen a
    ;; validation error or a success acknowledgement yet (or they
    ;; have, and a subsequent :edit reset the region to :neutral).
    {:tags #{:settings/neutral}
     :on   {:load           {:target :neutral    :action :seed-from-user}
            :edit           {:target :neutral    :action :edit-field}
            :submit-invalid {:target :incorrect  :action :set-errors}
            :submit-valid   {:target :submitting :action :begin-submit}
            :reset          {:target :neutral    :action :reset-data}}}

    :incorrect
    ;; Per-field validation error visible on a touched field, OR a
    ;; server submit-error from the previous attempt. The first :edit
    ;; clears errors and returns the region to :neutral.
    {:tags #{:settings/incorrect :form/invalid}
     :on   {:edit           {:target :neutral    :action :edit-field}
            :submit-invalid {:target :incorrect  :action :set-errors}
            :submit-valid   {:target :submitting :action :begin-submit}
            :reset          {:target :neutral    :action :reset-data}}}

    :submitting
    ;; Request in flight. The :settings/in-flight tag drives the
    ;; disabled state of every form input and the submit button.
    ;; The :form/transient tag exists so a view that wants to overlay
    ;; transient acknowledgements (in-flight, success, error) can
    ;; query one tag instead of three state-keywords.
    {:tags #{:settings/submitting :settings/in-flight :form/transient}
     :on   {:submit-succeeded {:target :correct   :action :store-user}
            :submit-failed    {:target :incorrect :action :set-submit-error}
            :reset            {:target :neutral   :action :reset-data}}}

    :correct
    ;; Happy-path acknowledgement. Transient; the next :edit returns
    ;; the region to :neutral. The slice-form equivalent is
    ;; `:status :submitted` — there the view typically navigates away
    ;; (and so does this one, see :settings/submit-succeeded below).
    {:tags #{:settings/correct :form/success :form/transient}
     :on   {:edit  {:target :neutral :action :edit-field}
            :reset {:target :neutral :action :reset-data}}}}})

(rf/reg-machine :settings/form settings-form-machine)

;; ============================================================================
;; PUBLIC EVENT API
;; ============================================================================
;;
;; Each event fans out to one or more machine broadcasts; views and sibling
;; namespaces dispatch these names and never touch the machine directly.

(rf/reg-event :settings/initialise
  {:doc "Reset the settings-form machine to its initial state.
         Dispatched from :app/initialise."}
  (fn handler-settings-initialise [_ _]
    {:fx [[:dispatch [:settings/form [:reset]]]]}))

(rf/reg-event :settings/load
  {:doc "Seed the form draft from the currently-authenticated user.
         Dispatched by the :realworld.user/settings :on-match (see routing.cljs)
         and by tests after :auth/store-session."
   :rf.cofx/requires [:rf/time-ms]}
  (fn handler-settings-load [{:keys [db rf/time-ms]} _]
    (let [user (get-in db [:auth :user])]
      {:fx [[:dispatch [:settings/form
                        [:load {:user user
                                :now  time-ms}]]]]})))

(rf/reg-event :settings/edit-field
  {:doc  "User edited a form field. Broadcasts :edit into the machine —
          the :form region returns from :correct / :incorrect to
          :neutral and updates the draft + :touched."
   :schema [:cat [:= :settings/edit-field] :keyword :string]}
  (fn handler-settings-edit-field [_ [_ field value]]
    {:fx [[:dispatch [:settings/form
                      [:edit {:field field :value value}]]]]}))

(rf/reg-event :settings/submit
  {:doc "Save the user-settings draft. No retry — one submission per click.
         Broadcasts :submit-valid into the machine (which transitions to
         :submitting and clears prior errors); on reply,
         :settings/submit-success / :settings/submit-error broadcast
         :submit-succeeded / :submit-failed."
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
                          :decode     schema/UserResponse
                          :on-success [:settings/submit-success]
                          :on-failure [:settings/submit-error]})]]})))

(rf/reg-event :settings/submit-success
  {:doc "Server accepted. Folds the new user into the machine's :data
         via the :store-user action (region lands in :correct), pushes
         the same user through :auth/store-session, and navigates to
         the user's profile page."}
  (fn handler-settings-submit-success [_ [_ {:keys [value]}]]
    (let [user (:user value)]
      {:fx [[:dispatch [:settings/form
                        [:submit-succeeded {:user user}]]]
            [:dispatch [:auth/store-session user]]
            [:dispatch [:rf.route/navigate :realworld.profile/show {:username (:username user)}]]]})))

(rf/reg-event :settings/submit-error
  {:doc "Server rejected. Folds a human-readable error message into the
         machine's :data via the :set-submit-error action; the region
         lands in :incorrect (the same surface the validation-error
         path uses, since both render via :submit-error / :errors)."}
  (fn handler-settings-submit-error [_ [_ {:keys [failure]}]]
    {:fx [[:dispatch [:settings/form
                      [:submit-failed {:submit-error (rh/failure->message failure)}]]]]}))

;; ============================================================================
;; SUBSCRIPTIONS
;; ============================================================================
;;
;; The view consumes plain names (`:settings/draft`,
;; `:settings/submit-error`); the source is the machine's `:data`.
;; `:settings/submitting?` is an `rf/machine-has-tag?` query under the hood,
;; presented to the view as a plain boolean.

(rf/reg-sub :settings/draft
  {:doc "The settings form draft, projected off the machine's :data."}
  :<- [:rf/machine :settings/form]
  (fn sub-settings-draft [snap _]
    (get-in snap [:data :draft])))

(rf/reg-sub :settings/submit-error
  {:doc "The most recent settings-submit error, projected off the
         machine's :data."}
  :<- [:rf/machine :settings/form]
  (fn sub-settings-submit-error [snap _]
    (get-in snap [:data :submit-error])))

(rf/reg-sub :settings/submitting?
  {:doc "Tag-shaped read of the form's in-flight intent — stands in for a
         slice-form `(= :submitting status)` comparison; views see a plain
         boolean."}
  :<- [:rf/machine-has-tag? :settings/form :settings/in-flight]
  (fn sub-settings-submitting? [in-flight? _]
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
             :on-change #(dispatch [:settings/edit-field :password (.. % -target -value)])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right
           {:type "submit" :disabled submitting?}
           (if submitting? "Updating…" "Update Settings")]]]
        [:hr]
        [:button.btn.btn-outline-danger
         {:type "button"
          :on-click #(dispatch [:auth/flow [:auth/logout]])}
         "Or click here to logout"]]]]]))
