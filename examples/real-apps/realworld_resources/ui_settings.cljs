(ns realworld-resources.ui-settings
  "The user-settings page, rendered in NATIVE re-frame.ui (`ui/defview`) — the
   compiled-view counterpart of the Reagent `settings-page` in
   `realworld-resources.settings`.

   Rendering tier only. Updating settings is a WRITE, so it is the
   `:realworld/update-settings` mutation; the form fires `:rf.mutation/execute`
   and watches its instance through `[:rf/mutation {:instance …}]`
   (`:pending?` / `:error?`), and the success continuation is the mutation's
   `:reply-to [:settings/replied]` target. All of that dataflow lives in
   `realworld-resources.settings` and is UNCHANGED and shared; this view reaches
   it by keyword. The password field carries its keystrokes on a classified map
   payload, so its controlled handler is a synchronous `ui/event` (placeholders
   never splice into a map); the other fields ride the `:rf.ui/value` placeholder."
  (:require [re-frame.ui :as ui :refer [defview sub]]
            [realworld-resources.http :as rh]))

;; The one stable instance id the settings submission watches — the same value
;; `realworld-resources.settings` declares (`settings-instance`). Kept as a local
;; literal so this view requires nothing from the `.cljs` dataflow tier.
(def ^:private settings-instance :settings/save)

(defview settings-page
  "The settings form — a pure function of subs that never dispatches out of band.
   Submit fires the update-settings mutation; the success continuation is the
   mutation's `:reply-to` target, so this view needs no off-render reaction."
  []
  (let [draft    (sub [:settings/draft])
        save     (sub [:rf/mutation {:instance settings-instance}])
        pending? (:pending? save)]
    [:div.settings-page
     [:div.container.page
      [:div.row
       [:div.col-md-6.offset-md-3.col-xs-12
        [:h1.text-xs-center "Your Settings"]
        (when (:error? save)
          [:ul.error-messages {:data-testid "settings-error"}
           [:li (rh/failure->message (:error save))]])
        [:form
         {:data-testid "settings-form"
          :on-submit {:event [:settings/submit] :prevent-default true}}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control
            {:type "text" :name "image" :placeholder "URL of profile picture"
             :value (:image draft) :disabled pending?
             :on-input [:settings/edit-field :image :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text" :name "username" :placeholder "Username" :data-testid "settings-username"
             :value (:username draft) :disabled pending?
             :on-input [:settings/edit-field :username :rf.ui/value]}]]
          [:fieldset.form-group
           [:textarea.form-control.form-control-lg
            {:rows 8 :name "bio" :placeholder "Short bio about you" :data-testid "settings-bio"
             :value (:bio draft) :disabled pending?
             :on-input [:settings/edit-field :bio :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "email" :name "email" :placeholder "Email"
             :value (:email draft) :disabled pending?
             :on-input [:settings/edit-field :email :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "password" :name "password" :placeholder "New Password"
             :value (:password draft) :disabled pending?
             :on-input (ui/event [e] [:settings/edit-password {:value (.. e -target -value)}])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right
           {:type "submit" :data-testid "settings-submit" :disabled pending?}
           (if pending? "Updating…" "Update Settings")]]]
        [:hr]
        [:button.btn.btn-outline-danger
         {:type "button" :data-testid "logout-button"
          :on-click [:auth/flow [:auth/logout]]}
         "Or click here to logout"]]]]]))
