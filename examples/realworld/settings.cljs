(ns example.realworld.settings
  "User settings page for the RealWorld (Conduit) example.

   This is a small Pattern-Forms sketch:
   - seed the draft from the current authenticated user
   - save via `PUT /user`
   - write the returned user back through `:auth/store-session`
   - keep logout on the existing auth machine path"
  (:require [re-frame.core :as rf]
            [example.realworld.schema]
            [example.realworld.http]
            [example.realworld.routing :as routing])
  (:require-macros [re-frame.views-macros :refer [reg-view with-frame]]))

(defn draft-from-user [user]
  {:image    (or (:image user) "")
   :username (or (:username user) "")
   :bio      (or (:bio user) "")
   :email    (or (:email user) "")
   :password ""})

(defn settings-slice [user]
  {:draft        (draft-from-user user)
   :submitted    nil
   :status       :idle
   :errors       {}
   :touched      #{}
   :submit-error nil})

(rf/reg-event-db :settings/load
  (fn [db _]
    (assoc db :settings (settings-slice (get-in db [:auth :user])))))

(rf/reg-event-db :settings/edit-field
  (fn [db [_ field value]]
    (-> db
        (assoc-in [:settings :draft field] value)
        (update-in [:settings :touched] (fnil conj #{}) field))))

(rf/reg-event-fx :settings/submit
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:settings :draft])]
      {:db (-> db
               (assoc-in [:settings :status] :submitting)
               (assoc-in [:settings :submitted] draft)
               (assoc-in [:settings :submit-error] nil))
       :fx [[:http {:method     :put
                    :url        "/user"
                    :body       {:user (cond-> (select-keys draft [:image :username :bio :email])
                                         (seq (:password draft))
                                         (assoc :password (:password draft)))}
                    :on-success [:settings/submit-success]
                    :on-error   [:settings/submit-error]}]]})))

(rf/reg-event-fx :settings/submit-success
  (fn [{:keys [db]} [_ resp]]
    (let [user (:user resp)]
      {:db (assoc db :settings (settings-slice user))
       :fx [[:dispatch [:auth/store-session user]]
            [:dispatch [:rf.route/navigate :route/profile {:username (:username user)}]]]})))

(rf/reg-event-db :settings/submit-error
  (fn [db [_ err]]
    (-> db
        (assoc-in [:settings :status] :idle)
        (assoc-in [:settings :submit-error]
                  (or (some-> err :errors :body first)
                      (:message err)
                      "Couldn't save settings.")))))

(rf/reg-sub :settings/draft
  (fn [db _] (get-in db [:settings :draft])))

(rf/reg-sub :settings/submitting?
  (fn [db _] (= :submitting (get-in db [:settings :status]))))

(rf/reg-sub :settings/submit-error
  (fn [db _] (get-in db [:settings :submit-error])))

(def settings-page
  (reg-view :pages/settings
    (fn render-settings-page []
      (let [d            (rf/dispatcher)
            s            (rf/subscriber)
            draft        @(s [:settings/draft])
            submitting?  @(s [:settings/submitting?])
            submit-error @(s [:settings/submit-error])]
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
                           (d [:settings/submit]))}
             [:fieldset
              [:fieldset.form-group
               [:input.form-control
                {:type "text"
                 :placeholder "URL of profile picture"
                 :value (:image draft)
                 :disabled submitting?
                 :on-change #(d [:settings/edit-field :image (.. % -target -value)])}]]
              [:fieldset.form-group
               [:input.form-control.form-control-lg
                {:type "text"
                 :placeholder "Username"
                 :value (:username draft)
                 :disabled submitting?
                 :on-change #(d [:settings/edit-field :username (.. % -target -value)])}]]
              [:fieldset.form-group
               [:textarea.form-control.form-control-lg
                {:rows 8
                 :placeholder "Short bio about you"
                 :value (:bio draft)
                 :disabled submitting?
                 :on-change #(d [:settings/edit-field :bio (.. % -target -value)])}]]
              [:fieldset.form-group
               [:input.form-control.form-control-lg
                {:type "email"
                 :placeholder "Email"
                 :value (:email draft)
                 :disabled submitting?
                 :on-change #(d [:settings/edit-field :email (.. % -target -value)])}]]
              [:fieldset.form-group
               [:input.form-control.form-control-lg
                {:type "password"
                 :placeholder "New Password"
                 :value (:password draft)
                 :disabled submitting?
                 :on-change #(d [:settings/edit-field :password (.. % -target -value)])}]]
              [:button.btn.btn-lg.btn-primary.pull-xs-right
               {:type "submit" :disabled submitting?}
               (if submitting? "Updating…" "Update Settings")]]]
            [:hr]
            [:button.btn.btn-outline-danger
             {:type "button"
              :on-click #(d [:auth/flow [:auth/logout]])}
             "Or click here to logout"]]]]]))))

(defn settings-test []
  (rf/reg-fx :http.canned-settings-save
    {:platforms #{:client :server}}
    (fn [{:keys [frame]} {:keys [on-success]}]
      (when on-success
        (rf/dispatch
          (conj on-success {:user {:email "alice@example.com"
                                   :token "jwt-2"
                                   :username "alice"
                                   :bio "New bio"
                                   :image nil}})
          {:frame frame}))))

  (with-frame [f (rf/make-frame {:on-create [:app/initialise]
                                 :fx-overrides {:http :http.canned-settings-save}})]
    (rf/dispatch-sync [:auth/store-session {:email "alice@example.com"
                                            :token "jwt-1"
                                            :username "alice"
                                            :bio nil
                                            :image nil}]
                      {:frame f})
    (rf/dispatch-sync [:settings/load] {:frame f})
    (rf/dispatch-sync [:settings/edit-field :bio "New bio"] {:frame f})
    (rf/dispatch-sync [:settings/submit] {:frame f})
    (assert (= "New bio" (get-in (rf/get-frame-db f) [:auth :user :bio])))))
