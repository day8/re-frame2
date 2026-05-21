(ns realworld.settings
  "User settings page for the RealWorld (Conduit) example.

   This namespace demonstrates the **`:form-region` machine variant** of
   Pattern-Forms: the settings form's lifecycle is modelled as a single
   state machine — `:settings/form` — whose state-keyword IS the form
   lifecycle (`:neutral` / `:incorrect` / `:correct` / `:submitting`).
   The other four forms in realworld (`:auth :login-form`,
   `:auth :register-form`, `:editor`, `:comment-form`) stay in the
   original `{:draft :submitted :status :errors :touched :submit-error}`
   slice form, so the two shapes sit side-by-side and a reader can
   compare. The README's §'Pattern-Forms — two shapes side-by-side'
   has the worked comparison plus a 'when to choose each' note.

   The shape:

   - The Pattern-Forms lifecycle (`:neutral` / `:incorrect` / `:correct`
     + `:submitting`) maps **one-to-one** onto machine states; the
     slice's `:status` field disappears because the region's
     state-keyword IS the status.
   - The draft, errors, touched, submit-error, submitted, and loaded-at
     fields live in the machine's shared `:data` map (no separate
     app-db slice).
   - The slice's `:submitting?` derived boolean sub collapses into a
     per-state `:settings/in-flight` tag queried with `rf/machine-has-tag?`.

   Logout stays on the existing auth machine path (`:auth/flow`).

   ---
   Production-shape note. Realworld is a worked sketch, and this form
   keeps the example's existing eager-submit behaviour (the user
   pressing 'Update Settings' triggers a server roundtrip without a
   prior client-side validate step). The `:submit-invalid` /
   `:incorrect` transition exists in the machine so the lifecycle is
   complete, and the headless tests exercise it via direct broadcasts —
   in a real app you'd run a Malli validate against the draft inside
   `:settings/submit` and dispatch `:submit-invalid` when it returned
   errors, matching Pattern-Forms' §Standard events table."
  (:require [re-frame.core :as rf]
            ;; The Spec 005 state-machine ns lives in the
            ;; day8/re-frame2-machines artefact. Loading the ns here
            ;; registers its late-bind hooks so rf/reg-machine (called
            ;; below at ns-load) and the `:rf/machine` /
            ;; `:rf/machine-has-tag?` framework subs resolve.
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
;; THE MACHINE — :settings/form  (one region; Pattern-Forms lifecycle)
;; ============================================================================
;;
;; The Pattern-Forms lifecycle maps one-to-one onto machine states.
;; Compare with the slice form used by the other four realworld forms:
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
;; Pattern-Forms' load-bearing boolean — `:submitting?` — becomes a
;; tag-shaped query against the active state:
;;
;;     :submitting?  = (= :submitting status)            ;; slice form
;;     :submitting?  = @(rf/machine-has-tag? :settings/form :settings/in-flight)
;;
;; The view doesn't need to know which state-keyword carries the
;; "in-flight" intent; the tag does. That is the load-bearing
;; pedagogical move.

(def settings-form-machine
  {:initial :neutral
   :data    initial-data

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
    ;; :submit-invalid carries the per-field error map. We also touch
    ;; every error field so the inline error shows even on fields the
    ;; user hasn't yet interacted with (per Pattern-Forms §Error
    ;; visibility — submit-attempted reveals all errors).
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
;; Event names stay the same as the slice-form version so views and
;; sibling namespaces (auth.cljs) need no change. Each one fans out to
;; one or more machine broadcasts.

(rf/reg-event-fx :settings/initialise
  {:doc "Reset the settings-form machine to its initial state.
         Dispatched from :app/initialise."}
  (fn handler-settings-initialise [_ _]
    {:fx [[:dispatch [:settings/form [:reset]]]]}))

(rf/reg-event-fx :settings/load
  {:doc "Seed the form draft from the currently-authenticated user.
         Dispatched by the :route/settings :on-match (see routing.cljs)
         and by tests after :auth/store-session."}
  [(rf/inject-cofx :realworld/now)]
  (fn handler-settings-load [{:keys [db realworld/now]} _]
    (let [user (get-in db [:auth :user])]
      {:fx [[:dispatch [:settings/form
                        [:load {:user user
                                :now  now}]]]]})))

(rf/reg-event-fx :settings/edit-field
  {:doc  "User edited a form field. Broadcasts :edit into the machine —
          the :form region returns from :correct / :incorrect to
          :neutral and updates the draft + :touched."
   :schema [:cat [:= :settings/edit-field] :keyword :string]}
  (fn handler-settings-edit-field [_ [_ field value]]
    {:fx [[:dispatch [:settings/form
                      [:edit {:field field :value value}]]]]}))

(rf/reg-event-fx :settings/submit
  {:doc "Save the user-settings draft. NO retry — single user-initiated
         submission per click (Spec 014). Broadcasts :submit-valid into
         the machine (which transitions to :submitting and clears
         prior errors); on reply, :settings/submit-success /
         :settings/submit-error broadcast :submit-succeeded /
         :submit-failed."
   :rf.http/decode-schemas [schema/UserResponse]}
  (fn handler-settings-submit [{:keys [db]} _]
    (let [draft (get-in db [:rf/machines :settings/form :data :draft])]
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

(rf/reg-event-fx :settings/submit-success
  {:doc "Server accepted. Folds the new user into the machine's :data
         via the :store-user action (region lands in :correct), pushes
         the same user through :auth/store-session, and navigates to
         the user's profile page."}
  (fn handler-settings-submit-success [_ [_ {:keys [value]}]]
    (let [user (:user value)]
      {:fx [[:dispatch [:settings/form
                        [:submit-succeeded {:user user}]]]
            [:dispatch [:auth/store-session user]]
            [:dispatch [:rf.route/navigate :route/profile {:username (:username user)}]]]})))

(rf/reg-event-fx :settings/submit-error
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
;; The view consumes the same names a slice-form reader would
;; (`:settings/draft`, `:settings/submit-error`); only the source
;; changed. The `:settings/submitting?` boolean stays for backward
;; compatibility with the view and tests — internally it's now an
;; `rf/machine-has-tag?` query (a tag-shaped read) instead of a slice-field
;; comparison.

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
  {:doc "Tag-shaped read of the form's in-flight intent. Replaces the
         slice-form `(= :submitting status)` comparison; views still
         see a plain boolean."}
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
             :placeholder "URL of profile picture"
             :value (:image draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :image (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text"
             :placeholder "Username"
             :value (:username draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :username (.. % -target -value)])}]]
          [:fieldset.form-group
           [:textarea.form-control.form-control-lg
            {:rows 8
             :placeholder "Short bio about you"
             :value (:bio draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :bio (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "email"
             :placeholder "Email"
             :value (:email draft)
             :disabled submitting?
             :on-change #(dispatch [:settings/edit-field :email (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "password"
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
