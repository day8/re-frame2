(ns realworld-resources.ui-auth
  "Login / register pages for the RealWorld (Conduit) example, rendered in NATIVE
   re-frame.ui (`ui/defview`) — the compiled-view counterpart of the Reagent
   `login-page` / `register-page` in `realworld-resources.auth`.

   This file is ONLY the rendering tier. The auth DATAFLOW — the `:auth/flow`
   machine, session restore, the credential-owning form `:submit` events, the
   scope teardown, and every `:auth/*` / `:auth.login-form/*` /
   `:auth.register-form/*` event + sub — lives in `realworld-resources.auth` and
   is UNCHANGED and shared. These views reach it by keyword, requiring nothing but
   `re-frame.ui`.

   Two compiled-view idioms carry the forms:

   - **Controlled inputs, the synchronous door.** A non-secret field is a literal
     `:value` co-present with an `:on-input` event vector carrying the
     `:rf.ui/value` placeholder, which projects the live DOM value at dispatch and
     drains synchronously (the caret never jumps).

   - **The password's map payload, via `ui/event`.** The password edit event takes
     a MAP payload (`[:…/edit-password {:value s}]`) so its `:value` is
     path-addressable and classifiable `:sensitive` — but placeholders splice only
     at an event vector's top level, never nested in a map. So the password field's
     controlled handler is a synchronous `ui/event` that reads the native value and
     returns the map-payload vector; at a controlled site a `ui/event` returning a
     vector rides the same synchronous door as a literal vector handler."
  (:require [re-frame.ui :as ui :refer [defview sub]]))

(defview login-page
  "Login page — a pure function of subs. Submit fires the credential-owning
   `:auth.login-form/submit`; the machine + reply events own everything after."
  []
  (let [draft       (sub [:auth.login-form/draft])
        submitting? (sub [:auth/submitting?])
        err         (sub [:auth/error])]
    [:div.auth-page {:data-testid "login-page"}
     [:div.container.page
      [:div.row
       [:div.col-md-6.offset-md-3.col-xs-12
        [:h1.text-xs-center "Sign in"]
        [:p.text-xs-center
         [ui/route-link {:to :realworld.auth/register} "Need an account?"]]
        (when err [:ul.error-messages [:li err]])
        [:form
         {:data-testid "login-form"
          :on-submit {:event [:auth.login-form/submit] :prevent-default true}}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "email" :name "email" :data-testid "login-email" :placeholder "Email"
             :value (:email draft) :disabled submitting?
             :on-input [:auth.login-form/edit-field :email :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "password" :name "password" :data-testid "login-password" :placeholder "Password"
             :value (:password draft) :disabled submitting?
             ;; Map-payload password edit: read the live value in a ui/event and
             ;; return the classified map vector; the co-present literal :value
             ;; keeps it on the synchronous controlled door.
             :on-input (ui/event [e] [:auth.login-form/edit-password {:value (.. e -target -value)}])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right
           {:type "submit" :data-testid "login-submit" :disabled submitting?}
           (if submitting? "Signing in…" "Sign in")]]]]]]]))

(defview register-page
  "Register page — a pure function of subs. Shares `:auth/session-established`
   with login: both successes store the session and bounce the same way."
  []
  (let [draft       (sub [:auth.register-form/draft])
        submitting? (sub [:auth/submitting?])
        err         (sub [:auth/error])]
    [:div.auth-page {:data-testid "register-page"}
     [:div.container.page
      [:div.row
       [:div.col-md-6.offset-md-3.col-xs-12
        [:h1.text-xs-center "Sign up"]
        [:p.text-xs-center
         [ui/route-link {:to :realworld.auth/login} "Have an account?"]]
        (when err [:ul.error-messages [:li err]])
        [:form
         {:data-testid "register-form"
          :on-submit {:event [:auth.register-form/submit] :prevent-default true}}
         [:fieldset
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "text" :name "username" :data-testid "register-username" :placeholder "Username"
             :value (:username draft) :disabled submitting?
             :on-input [:auth.register-form/edit-field :username :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "email" :name "email" :data-testid "register-email" :placeholder "Email"
             :value (:email draft) :disabled submitting?
             :on-input [:auth.register-form/edit-field :email :rf.ui/value]}]]
          [:fieldset.form-group
           [:input.form-control.form-control-lg
            {:type "password" :name "password" :data-testid "register-password" :placeholder "Password"
             :value (:password draft) :disabled submitting?
             :on-input (ui/event [e] [:auth.register-form/edit-password {:value (.. e -target -value)}])}]]
          [:button.btn.btn-lg.btn-primary.pull-xs-right
           {:type "submit" :data-testid "register-submit" :disabled submitting?}
           (if submitting? "Signing up…" "Sign up")]]]]]]]))
