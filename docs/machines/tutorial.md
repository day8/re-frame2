# Tutorial: build a login machine

Build one machine end to end — idle → submitting → authed / error / locked-out —
adding one idea at a time: a guard, an action, a server call, a view, and a pure
test.

You need the [Core loop](../core/introduction.md) (events, app-db, effects). Machine
theory beyond this walk-through is [The model](concepts.md).

## Step 0 — turn machines on

Machines are an optional artefact. Require them once in a boot or feature namespace:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))   ;; loads the capability
```

Forget this and the first `reg-machine` throws
`:rf.error/machines-artefact-missing`.

## Step 1 — a table and a registration

A machine is data: named states and which triggers move where.

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :states
   {:idle        {:on {:auth.login/submit  {:target :submitting}}}
    :submitting  {:on {:auth.login/success {:target :authed}
                       :auth.login/failure {:target :error-shown}}}
    :error-shown {:on {:auth.login/dismiss {:target :idle}
                       :auth.login/submit  {:target :submitting}}}
    :authed      {}}})   ;; resting — no outgoing transitions
```

Register a **singleton** under an event id (`reg-machine` ≈ specialised `reg-event`):

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Drive it by dispatching to that id; the **inner** vector is the machine event:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit]])
```

Read the live [snapshot](glossary.md#snapshot):

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil}}
;;    nil before the first event — the machine boots on first dispatch
```

## Step 2 — a guard

Refuse empty credentials. Name the guard once; reference it from every arrow that
needs it:

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))}

   :states
   {:idle        {:on {:auth.login/submit  {:target :submitting
                                            :guard  :form-valid?}}}
    :submitting  {:on {:auth.login/success {:target :authed}
                       :auth.login/failure {:target :error-shown}}}
    :error-shown {:on {:auth.login/dismiss {:target :idle}
                       :auth.login/submit  {:target :submitting :guard :form-valid?}}}
    :authed      {}}})
```

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "" :password ""}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => still :idle

(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "secret"}]])
;; => :submitting
```

The guard reads credentials from **`:event`**, not app-db — machines are
[encapsulated](concepts.md#strict-encapsulation).

## Step 3 — actions and candidate lists

An **action** returns `{:data … :fx …}` like a pure event handler — it never calls
HTTP itself. Below: clear error on submit, record failures, store session on success,
and **lock out** after three failures via a candidate list:

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))
    :under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})
    :record-error
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error (or (:message error) "Login failed.")))})
    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :guard  :form-valid?
                              :action :clear-error}}}
    :submitting
    {:on {:auth.login/success {:target :authed :action :store-session}
          :auth.login/failure [{:target :error-shown
                                :guard  :under-retry-limit
                                :action :record-error}
                               {:target :locked-out}]}}
    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting :guard :form-valid?}}}
    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})
```

`:auth.login/failure` is a **vector of candidates** — first guard that passes wins.
After three failures, `:under-retry-limit` fails and the unguarded candidate locks
out.

!!! note "`:data` merges"

    `{:data {:error nil}}` changes only `:error`. It does not replace the whole map.
    Details: [The model → effect map](concepts.md#the-effect-map-data-fx).

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]])
(rf/dispatch [:auth.login/flow [:auth.login/failure {:error {:message "nope"}}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

## Step 4 — talk to a real server

Add an **`:entry`** action on `:submitting` that fires [managed HTTP](../async/http.md),
and an **`:after`** deadline if the server stalls:

```clojure
:actions
{;; … clear-error, record-error, store-session …

 :issue-request
 (fn [{[_ creds] :event}]
   {:fx [[:rf.http/managed
          {:request    {:method :post :url "/api/login" :body creds
                        :request-content-type :json}
           :decode     :json
           :on-success [:auth.login/flow [:auth.login/success]]
           :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

 :record-timeout
 (fn [{data :data}]
   {:data (-> data (update :attempts inc) (assoc :error "Server took too long."))})}
```

```clojure
:submitting
{:tags  #{:auth/busy}
 :entry :issue-request
 :after {8000 {:target :error-shown :action :record-timeout}}
 :on    {:auth.login/success {:target :authed :action :store-session}
         :auth.login/failure [{:target :error-shown
                               :guard  :under-retry-limit
                               :action :record-error}
                              {:target :locked-out}]}}
```

`:on-success [:auth.login/flow [:auth.login/success]]` is written one element short
on purpose — managed HTTP **appends** the reply map onto the inner event, so
`:store-session` sees `[:auth.login/success {:value {:token "…"}}]`.

`:after` arms on entry and cancels on exit. No `setTimeout`, no cancel flag.
Deeper timer grammar: [Automatic transitions](automatic-transitions.md).

## Step 5 — render every state

Project the snapshot; ask **tags** for shared intent ("busy?") instead of listing
state names:

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (:state m)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (get-in m [:data :error])))

(rf/reg-view login-view []
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])
        busy? @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])]
    (case state
      :idle        [:button {:disabled busy?} "Sign in"]
      :submitting  [:p "Signing in…"]
      :error-shown [:div [:p error] [:button "Try again"]]
      :authed      [:h1 "Welcome back"]
      :locked-out  [:h1 "Account locked"]
      [:p "…"])))   ;; nil before first event
```

The `:auth/busy` tag on `:submitting` is why `busy?` works — add another in-flight
state later and the view keeps working. Pattern: [Tags](tags.md).

## Step 6 — test a transition as a pure function

No frame, no browser, no network:

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  (let [{snap ::result/snap fx ::result/fx}
        (machines/machine-transition login-flow
          {:state :idle :data {:attempts 0 :error nil}}
          [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (= :submitting (:state snap)))
    (is (= :rf.http/managed (ffirst fx))))   ;; :entry ran :issue-request

  (let [{snap ::result/snap}
        (machines/machine-transition login-flow
          {:state :submitting :data {:attempts 3 :error nil}}
          [:auth.login/failure {:error {:message "bad creds"}}])]
    (is (= :locked-out (:state snap)))))
```

More on Result accessors and Xray: [Inspecting and testing](inspecting-machines.md).

## What you built

A single data value that owns login lifecycle, pure enough to unit-test, wired to
HTTP and views through ordinary re-frame2 events and subscriptions. The rest of this
section deepens the grammar when a flat table is no longer enough — start with
[The model](concepts.md) if you want vocabulary, or [Hierarchical states](hierarchical-states.md)
when a state needs sub-states.
