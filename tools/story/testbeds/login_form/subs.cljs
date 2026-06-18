(ns login-form.subs
  "Login-form testbed subs (rf2-0sg12).

  Four named subs project out the convenient pieces of the machine's
  current snapshot; the view also uses two `rf/machine-has-tag?` framework
  subs for `:auth/busy` and `:auth/authenticated`. Per Spec 005 §State
  tags the tag queries replace the boolean-discriminator subs the
  pre-machines style would have used (`:submitting?` /
  `:authenticated?`).

  EP-0001 (rf2-vzld77 / rf2-ib5acl): machine snapshots are durable
  framework state and live in the frame's RUNTIME-DB partition at
  `[:rf.runtime/machines :snapshots <machine-id>]`, NOT the retired
  app-db `:rf/runtime` root. App code reads a machine's snapshot through
  the framework `:rf/machine` sub (`(rf/subscribe [:rf/machine :login/flow])`)
  rather than reaching into the runtime-db partition by raw path;
  the framework sub reads the right partition for us. Each projection sub
  below is a layer-2 sub deriving off `:rf/machine` via the `:<-` chain —
  it re-computes only when the snapshot's relevant slice changes."
  (:require [re-frame.core :as rf]))

(rf/reg-sub :login/state
  {:doc "Current state of the login flow — one of :idle :submitting
        :error :submitting-retry :authenticated."}
  :<- [:rf/machine :login/flow]
  (fn sub-login-state [snapshot _query]
    (:state snapshot)))

(rf/reg-sub :login/error
  {:doc "Current error message, if any."}
  :<- [:rf/machine :login/flow]
  (fn sub-login-error [snapshot _query]
    (get-in snapshot [:data :error])))

(rf/reg-sub :login/attempts
  {:doc "How many login attempts the flow has rejected so far. The
        retry-state UI labels itself with this count."}
  :<- [:rf/machine :login/flow]
  (fn sub-login-attempts [snapshot _query]
    (get-in snapshot [:data :attempts] 0)))

(rf/reg-sub :login/email
  {:doc "Email the user most recently submitted — surfaced in the
        :authenticated state's welcome banner."}
  :<- [:rf/machine :login/flow]
  (fn sub-login-email [snapshot _query]
    (get-in snapshot [:data :email])))
