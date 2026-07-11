(ns realworld-resources.settings
  "User-settings page for the RealWorld-on-resources example.

   Updating settings is a write, so it's a mutation (`:realworld/update-settings`
   over in realworld-resources.mutations). The form fires `:rf.mutation/execute`
   and watches the instance through `[:rf/mutation {:instance …}]`
   (`:pending?` / `:success?` / `:error?`), with no app-db submission-status slice
   to keep in sync. On success the saved User comes back as the reply value; the
   continuation pushes it into the auth slice and navigates to the profile, and
   the mutation's `:invalidates` clears the public profile read so a later visit
   re-reads the new bio.

   The only thing app-db holds is the editable draft — the live field values. The
   lifecycle (`:idle` / `:pending` / `:success` / `:error`) is the mutation
   instance, not a `:status` field you maintain.

   The success continuation is a call-site `:reply-to`, and it's worth a beat. The
   submit passes `:reply-to [:settings/replied]` to `:rf.mutation/execute`; when
   the runtime accepts the reply as current — frame, instance, work-id, and
   generation all matched — it dispatches `[:settings/replied reply]` exactly
   once, with the canonical reply map appended, AFTER the cache consequences and
   instance settlement have landed. The continuation reads `(:status reply)` to
   fold in the saved User on `:ok` and surface the error on `:error`. A stale or
   superseded reply never fires it.

   The why behind `:reply-to`: it's a causal event target — an ordinary event,
   flowing through the tape / interceptors / replay — not a callback. That's what
   lets the view stay a plain Form-1, a pure function of subs that never dispatches
   out of band. The alternative would be an off-render Form-3 `reagent.ratom/run!`
   reaction watching `[:rf/mutation …]` for settlement, which drags a
   side-effecting reaction into the view. `:reply-to` gives you the same settle
   hook as a declarative, replayable event instead. See the reply map:
   ../../../docs/resources/glossary.md#reply-map."
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [realworld-resources.http :as rh]
            ;; `store-session-db` — the shared, nested-dispatch-avoiding
            ;; session write both auth.cljs's classified reply events and
            ;; :settings/replied (below) call directly. See its doc.
            [realworld-resources.auth :as auth])
  (:require-macros [re-frame.core :refer [reg-view]]))

(def settings-instance
  "One stable instance id for the settings submission — the form watches exactly
   this instance, nothing else."
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

(rf/reg-event :settings/load
  {:doc "Seed the settings draft from the authenticated user. Dispatched once, on
         route entry, via the settings route's `:on-match` — the page is
         auth-guarded, so there's always a user here. Doing the seed on route
         entry rather than in the view's render is exactly what keeps the form a
         pure Form-1: a re-render never re-runs the load, so edits you've got in
         flight survive. Seeds `:touched` alongside `:draft` — the FormSlice
         schema (schema.cljs) requires both, so a slice with only `:draft`
         would fail its own schema until the first field edit.

         Also classifies the draft-password path :sensitive in this same seed
         write (classify-before-write, mirroring :auth/classify-token), so the
         new-password the user types reads :rf/redacted at every app-db egress
         while the submit handler still reads the live value. See keep-secrets:
         ../../../docs/core/how-to/keep-secrets-out-of-traces.md"}
  (fn [{:keys [db]} _]
    {:db        (assoc-in db [:settings-form] {:draft   (draft-from-user (get-in db [:auth :user]))
                                               :touched #{}})
     :sensitive [[:settings-form :draft :password]]}))

(rf/reg-event :settings/edit-field
  {:doc    "Controlled-input edit for a NON-SECRET field (image / username /
            bio / email). The password uses :settings/edit-password instead;
            never route a secret through this event."
   :schema [:cat [:= :settings/edit-field] :keyword :string]}
  (fn [{:keys [db]} [_ field value]]
    {:db (-> db
        (assoc-in [:settings-form :draft field] value)
        (update-in [:settings-form :touched] (fnil conj #{}) field))}))

;; The password's keystrokes get their OWN event and a MAP payload — the
;; whole point being that a map is path-addressable and a positional arg
;; isn't. `:sensitive [[:value]]` classifies that `:value` key, so the
;; dispatched-event trace redacts it while the handler still writes the real
;; keystroke into the draft. The mutation `:realworld/update-settings` itself
;; already classifies `[[:params :password]]` on its OWNER declaration
;; (mutations.cljs, rf2-825mzj), which covers the SUBMIT-time execute/trace/
;; instance/continuation projections; this event covers the per-keystroke
;; EDIT's own dispatched-event trace, which that mutation-owner declaration
;; can't reach.
(rf/reg-event :settings/edit-password
  {:doc       "Controlled-input edit for the PASSWORD field: write the value
               into the settings-form draft and mark it touched. The value
               rides a map payload classified :sensitive so the edit trace
               redacts it."
   :sensitive [[:value]]
   :schema    [:cat [:= :settings/edit-password] [:map [:value :string]]]}
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
        (assoc-in [:settings-form :draft :password] value)
        (update-in [:settings-form :touched] (fnil conj #{}) :password))}))

(rf/reg-event :settings/submit
  {:doc "Fire the update-settings mutation with the current draft, then blank
         the draft's live password (secret-field hygiene — the mutation's own
         `:sensitive [[:params :password]]` declaration already covers the
         :params it carries off to the server). The form watches the
         `:settings/save` instance for pending / error, and the success
         continuation is the call-site `:reply-to [:settings/replied]` target,
         dispatched once when the reply is accepted."}
  (fn [{:keys [db]} _]
    (let [draft (get-in db [:settings-form :draft])]
      {:db (assoc-in db [:settings-form :draft :password] "")
       :fx [[:dispatch [:rf.mutation/execute
                        {:mutation :realworld/update-settings
                         :params   (select-keys draft [:username :email :bio :image :password])
                         :instance settings-instance
                         :reply-to [:settings/replied]
                         :cause    [:submit :settings/save]}]]]})))

(rf/reg-event :settings/replied
  {:doc "The update-settings completion continuation (the `:reply-to` target). It
         receives the canonical reply map as its final arg, observed AFTER the
         mutation's `:invalidates` cleared the public profile read and the instance
         settled. On `:ok`, push the saved User (the reply `:value`) into the auth
         slice, clear the instance, and navigate to the user's profile. On
         `:error` there's nothing to do here — the form already shows it off the
         instance state. Clearing the instance also keeps the dispatch
         idempotent. The reply rides a map payload classified :sensitive — the
         RealWorld PUT /user reply carries a fresh User (and therefore a fresh
         token, just like login/register)."
   :sensitive [[:value :user :token]]}
  (fn [{:keys [db]} [_ {:keys [status value]}]]
    (if (= :ok status)
      (let [user (:user value)]
        ;; `store-session-db` is called DIRECTLY (not via a nested
        ;; `[:dispatch [:auth/store-session user]]`) — see that fn's doc
        ;; (auth.cljs) for why a nested dispatch would leak the raw token at
        ;; THIS handler's own trace regardless of :auth/store-session's own
        ;; classification.
        {:db (auth/store-session-db db user)
         :fx [[:dispatch [:rf.mutation/clear {:instance settings-instance}]]
              [:dispatch [:rf.route/navigate :realworld.profile/show {:username (:username user)}]]]})
      {})))

(rf/reg-sub :settings/draft (fn [db _] (get-in db [:settings-form :draft])))

;; ============================================================================
;; VIEW  (pure Form-1 render — never dispatches out of band)
;; ============================================================================
;;
;; The render is an ordinary registered `reg-view` (Form-1): a pure function of
;; subs that never dispatches. Because the success continuation is the mutation's
;; `:reply-to` target rather than an off-render reaction, the view ends up with no
;; lifecycle hooks at all — which is rather the point.

(reg-view ^{:doc "The settings form — a pure function of subs. Submit fires the
                   update-settings mutation, and the success continuation is the
                   mutation's `:reply-to` target, so this view never dispatches
                   out of band."}
          settings-page []
  (let [draft     @(subscribe [:settings/draft])
        save      @(subscribe [:rf/mutation {:instance settings-instance}])
        pending?  (:pending? save)]
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
           [:input.form-control {:type "text" :name "image" :placeholder "URL of profile picture"
                                 :value (:image draft) :disabled pending?
                                 :on-change #(dispatch [:settings/edit-field :image (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "text" :name "username" :placeholder "Username" :data-testid "settings-username"
                                                 :value (:username draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-field :username (.. % -target -value)])}]]
          [:fieldset.form-group
           [:textarea.form-control.form-control-lg {:rows 8 :name "bio" :placeholder "Short bio about you" :data-testid "settings-bio"
                                                    :value (:bio draft) :disabled pending?
                                                    :on-change #(dispatch [:settings/edit-field :bio (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "email" :name "email" :placeholder "Email"
                                                 :value (:email draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-field :email (.. % -target -value)])}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg {:type "password" :name "password" :placeholder "New Password"
                                                 :value (:password draft) :disabled pending?
                                                 :on-change #(dispatch [:settings/edit-password {:value (.. % -target -value)}])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right {:type "submit" :data-testid "settings-submit" :disabled pending?}
           (if pending? "Updating…" "Update Settings")]]]
        [:hr]
        [:button.btn.btn-outline-danger {:type "button" :data-testid "logout-button"
                                         :on-click #(dispatch [:auth/flow [:auth/logout]])}
         "Or click here to logout"]]]]]))
