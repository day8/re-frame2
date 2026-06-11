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
   instance, not a `:status` field.

   SUCCESS CONTINUATION (off the render path). A mutation has NO reply-side
   `:on-success` / `:on-settled` hook (unlike `:rf.http/managed`, Spec 014) and
   the runtime emits no app-observable settle event — a view observes the
   instance passively through `[:rf.mutation/state {:instance …}]`. So the
   \"on save success: fold the saved User into auth + navigate\" continuation has
   to be triggered by *observing* the instance settle, and re-frame2 forbids
   dispatching from a render body (Spec 004 §View antipatterns: a render body
   computes hiccup, it does not advance state — and in Reagent a render-time
   dispatch can loop). The blessed escape hatch for an after-commit reaction to
   async settlement is the substrate lifecycle hook (Spec 004 §Views MUST NOT
   own imperative library lifecycles directly → Form-3 via `reg-view*` /
   `create-class`): `settings-page` is a Form-3 component whose mount hook
   installs a single `reagent.ratom/run!` reaction that watches the
   `:settings/save` instance and dispatches `:settings/saved` exactly once when
   it first settles success. The frame is captured at render (the `dispatch`
   the reaction closes over is the frame-bound one), so the reaction's dispatch
   carries the frame correctly even though it fires outside render; the hook
   disposes the reaction on unmount. The dispatch site is a named, registered
   reaction — visible to the trace — not a hidden render-body side-effect.

   Compare the `:rf.http/managed` sibling (`examples/reagent/realworld/`): its
   settings submit gets the continuation for free via the request's
   `:on-success [:settings/submit-success]`, because managed HTTP carries
   reply-side dispatch. The mutation deliberately gives that up (the runtime
   owns reply addressing for stale-suppression); this lifecycle reaction is the
   resources-variant equivalent."
  (:require [re-frame.core :as rf]
            [re-frame.resources]
            [reagent.core :as r]
            [reagent.ratom :as ratom]
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
         and navigate to the user's profile. Dispatched by the settings page's
         mount-time settle reaction when the `:settings/save` instance first
         transitions to `:success` (NOT from a render body — see the ns
         docstring). Clearing the instance here also makes the dispatch
         idempotent: a re-fire finds a now-idle instance."}
  (fn [_ [_ user]]
    {:fx [[:dispatch [:auth/store-session user]]
          [:dispatch [:rf.mutation/clear {:instance settings-instance}]]
          [:dispatch [:rf.route/navigate :realworld.profile/show {:username (:username user)}]]]}))

(rf/reg-sub :settings/draft (fn [db _] (get-in db [:settings-form :draft])))

;; ============================================================================
;; VIEW  (pure Form-1 render + a Form-3 wrapper holding the settle reaction)
;; ============================================================================
;;
;; The render is a normal registered `reg-view` (Form-1): a pure function of
;; subs that NEVER dispatches (Spec 004 §View antipatterns). The success
;; continuation lives off the render path in `settings-page` — a Form-3
;; `create-class` wrapper whose `:component-did-mount` installs a single
;; `reagent.ratom/run!` reaction that watches the `:settings/save` instance and
;; dispatches `:settings/saved` the first time it settles success. A `fired?`
;; latch makes it strictly once-per-mount (the instance is also cleared by
;; `:settings/saved`, so the reaction would not re-fire anyway). The reaction
;; closes over the frame-bound `dispatch` / `subscribe` captured at render-time
;; via `rf/frame-handle`, so its out-of-render dispatch carries the frame
;; correctly; `:component-will-unmount` disposes it. (Form-3 is the escape hatch
;; Spec 004 names for after-commit reactions to async settlement.)

(reg-view ^{:doc "Pure settings form — a function of subs, never dispatches.
                   The save-success continuation lives in the `settings-page`
                   Form-3 wrapper, not here."}
          settings-form-view []
  (let [draft     @(subscribe [:settings/draft])
        save      @(subscribe [:rf.mutation/state {:instance settings-instance}])
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

(defn settings-page
  "The settings page. A Reagent Form-3 component (an outer fn that captures the
   render frame and per-mount state, returning a `create-class`): the inner
   `:reagent-render` is the pure registered `settings-form-view`; the mount hook
   installs the save-success settle reaction; the unmount hook disposes it.

   `rf/frame-handle` is called here in the outer body — which Reagent invokes
   during render, under the frame-provider — so the `dispatch` the reaction
   closes over is frame-bound and its later out-of-render call carries the
   frame (Spec 002 §Async fx capture the frame in a closure; Spec 004 §Form-3)."
  []
  (let [{:keys [dispatch subscribe]} (rf/frame-handle)
        fired?  (atom false)
        watcher (atom nil)]
    (r/create-class
     {:display-name "realworld-resources.settings/settings-page"
      :component-did-mount
      (fn [_this]
        ;; One auto-running reaction watching the save instance. The first time
        ;; it settles success, dispatch the continuation exactly once. This is
        ;; the after-commit escape hatch Spec 004 sanctions for reacting to
        ;; async settlement — NOT a render-body dispatch.
        (reset! watcher
                (ratom/run!
                 (let [save @(subscribe [:rf.mutation/state {:instance settings-instance}])]
                   (when (and (:success? save) (not @fired?))
                     (when-let [user (:user (:result save))]
                       (reset! fired? true)
                       (dispatch [:settings/saved user])))))))
      :component-will-unmount
      (fn [_this]
        (when-let [w @watcher] (ratom/dispose! w) (reset! watcher nil)))
      :reagent-render
      (fn [] [settings-form-view])})))
