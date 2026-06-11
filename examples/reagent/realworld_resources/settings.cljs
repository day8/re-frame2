(ns realworld-resources.settings
  "User-settings page for the RealWorld-on-resources example.

   Settings update is a WRITE, so it is a MUTATION (`:realworld/update-settings`
   in realworld-resources.mutations): the form fires `:rf.mutation/execute` and
   watches the instance through `[:rf.mutation/state {:instance …}]`
   (`:pending?` / `:success?` / `:error?`), with NO app-db submission-status
   slice to maintain. On success the saved User comes back as the instance
   `:result`; this ns pushes it into the auth slice and navigates to the
   profile, and the mutation's `:invalidates` clears the public profile read so
   a later visit re-reads the new bio.

   What stays in app-db is only the editable DRAFT (the live field values) —
   the lifecycle (`:idle` / `:pending` / `:success` / `:error`) is the mutation
   instance, not a `:status` field."
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [realworld-resources.http :as rh])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def settings-instance
  "A single, stable instance id for the settings submission — the form watches
   exactly this instance."
  :settings/save)

(defn- draft-from-user [user]
  {:image    (or (:image user) "")
   :username (or (:username user) "")
   :bio      (or (:bio user) "")
   :email    (or (:email user) "")
   :password ""})

;; ============================================================================
;; DRAFT (app-db) + EVENTS
;; ============================================================================

(rf/reg-event-fx :settings/load
  {:doc "Seed the settings draft from the authenticated user. Dispatched from
         the settings view on mount (the page is auth-guarded, so a user is
         always present here)."}
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:settings-form :draft] (draft-from-user (get-in db [:auth :user])))}))

(rf/reg-event-db :settings/edit-field
  {:schema [:cat [:= :settings/edit-field] :keyword :string]}
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:settings-form :draft field] value)
        (update-in [:settings-form :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :settings/submit
  {:doc "Fire the update-settings mutation with the current draft. The form
         watches the `:settings/save` instance; success/failure settle there."}
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:settings-form :draft])]
      {:fx [[:dispatch [:rf.mutation/execute
                        {:mutation :realworld/update-settings
                         :params   (select-keys draft [:username :email :bio :image :password])
                         :instance settings-instance
                         :cause    [:submit :settings/save]}]]]})))

(rf/reg-event-fx :settings/saved
  {:doc "Push the saved User (the mutation instance result) into the auth slice
         and navigate to the user's profile. Dispatched by the view when the
         `:settings/save` instance transitions to `:success`."}
  (fn [_ [_ user]]
    {:fx [[:dispatch [:auth/store-session user]]
          [:dispatch [:rf.mutation/clear {:instance settings-instance}]]
          [:dispatch [:rf.route/navigate :realworld.profile/show {:username (:username user)}]]]}))

(rf/reg-sub :settings/draft (fn [db _] (get-in db [:settings-form :draft])))

;; ============================================================================
;; VIEW
;; ============================================================================

(reg-view settings-page []
  (let [draft     @(subscribe [:settings/draft])
        save      @(subscribe [:rf.mutation/state {:instance settings-instance}])
        pending?  (:pending? save)]
    ;; When the save instance settles success, fold the result into auth + go.
    (when (:success? save)
      (when-let [user (:user (:result save))]
        (dispatch [:settings/saved user])))
    [:div.settings-page
     [:div.container.page
      [:div.row
       [:div.col-md-6.offset-md-3.col-xs-12
        [:h1.text-xs-center "Your Settings"]
        (when (:error? save)
          [:ul.error-messages {:data-testid "settings-error"} [:li (rh/failure->message (:error save))]])
        [:form
         {:data-testid "settings-form"
          :on-submit (fn [e] (.preventDefault e) (dispatch [:settings/submit]))}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control {:type "text" :placeholder "URL of profile picture"
                                 :value (:image draft) :disabled pending?
                                 :on-change #(dispatch [:settings/edit-field :image (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "text" :placeholder "Username" :data-testid "settings-username"
                                                 :value (:username draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-field :username (.. % -target -value)])}]]
          [:fieldset.form-group
           [:textarea.form-control.form-control-lg {:rows 8 :placeholder "Short bio about you" :data-testid "settings-bio"
                                                    :value (:bio draft) :disabled pending?
                                                    :on-change #(dispatch [:settings/edit-field :bio (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "email" :placeholder "Email"
                                                 :value (:email draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-field :email (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "password" :placeholder "New Password"
                                                 :value (:password draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-field :password (.. % -target -value)])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right {:type "submit" :data-testid "settings-submit" :disabled pending?}
           (if pending? "Updating…" "Update Settings")]]]
        [:hr]
        [:button.btn.btn-outline-danger {:type "button" :data-testid "logout-button"
                                         :on-click #(dispatch [:auth/flow [:auth/logout]])}
         "Or click here to logout"]]]]]))
