(ns login-form.subs
  "Login-form testbed subs (rf2-0sg12).

  Two named subs project out the convenient pieces of the machine's
  `:data` slot; the view also uses two `rf/has-tag?` framework subs
  for `:auth/busy` and `:auth/authenticated`. Per Spec 005 §State
  tags the tag queries replace the boolean-discriminator subs the
  pre-machines style would have used (`:submitting?` /
  `:authenticated?`)."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :login/state
  {:doc "Current state of the login flow — one of :idle :submitting
        :error :submitting-retry :authenticated."}
  (fn sub-login-state [db _query]
    (get-in db [:rf/machines :login/flow :state])))

(rf/reg-sub :login/error
  {:doc "Current error message, if any."}
  (fn sub-login-error [db _query]
    (get-in db [:rf/machines :login/flow :data :error])))

(rf/reg-sub :login/attempts
  {:doc "How many login attempts the flow has rejected so far. The
        retry-state UI labels itself with this count."}
  (fn sub-login-attempts [db _query]
    (get-in db [:rf/machines :login/flow :data :attempts] 0)))

(rf/reg-sub :login/email
  {:doc "Email the user most recently submitted — surfaced in the
        :authenticated state's welcome banner."}
  (fn sub-login-email [db _query]
    (get-in db [:rf/machines :login/flow :data :email])))
