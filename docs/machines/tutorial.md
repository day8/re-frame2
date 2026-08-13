# Tutorial: build a login machine

This tutorial builds one login flow, adding one idea at a time.

By the end you will have:

- a registered machine;
- a guard that refuses an empty form;
- actions that update machine `:data` and return effects;
- an HTTP request started on state entry, with a timeout that cancels on exit;
- a view that reads the snapshot and a tag;
- a pure unit test for the transition table.

The example is small on purpose. The goal is the shape, not a production auth system.

**Prerequisites.** [Core introduction](../core/introduction.md) (events, app-db, effects). Vocabulary for later pages: [The model](concepts.md).

## Step 0 — turn machines on

Machines are an optional artefact. Require the namespace once from a boot or feature namespace:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))
```

Skip this and the first `reg-machine` throws `:rf.error/machines-artefact-missing`.

## Step 1 — write the transition table

<a id="step-1--your-first-machine"></a>

A machine is a map. It names an initial state, some private `:data`, and the states plus transitions. Define it with `defmachine` (not `def` — a plain `def` leaves source stamps empty and warns `:rf.warning/machine-source-unstamped`), then register it:

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :states
   {:idle
    {:on {:auth.login/submit :submitting}}

    :submitting
    {:on {:auth.login/success :authed
          :auth.login/failure :error-shown}}

    :error-shown
    {:on {:auth.login/dismiss :idle
          :auth.login/submit  :submitting}}

    ;; Resting leaf. Do not set :final? — that destroys the machine.
    :authed
    {:meta {:terminal? true}}}})

(rf/reg-machine :auth.login/flow login-flow)
```

Targets here are bare keywords. The next two steps turn those into maps, then into candidate vectors.

`reg-machine` is machine-shaped `reg-event`. The machine id is the event id you dispatch to; the **inner** vector is the machine event:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]])
```

Read the live [snapshot](glossary.md#snapshot):

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil}}
;;    nil before the first event — the machine boots on first dispatch
```

## Step 2 — add a guard

<a id="step-2--a-guard-refuse-an-invalid-submit"></a>

Right now any submit moves to `:submitting`, even with empty credentials.

A guard is a predicate that gates a transition. It receives one context map and returns truthy or falsey. Once a transition needs more than a target, write it as a map:

```clojure
(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds))
           (seq (:password creds))))}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :guard  :form-valid?}}}

    :submitting
    {:on {:auth.login/success :authed
          :auth.login/failure :error-shown}}

    :error-shown
    {:on {:auth.login/dismiss :idle
          :auth.login/submit  {:target :submitting
                               :guard  :form-valid?}}}

    :authed
    {:meta {:terminal? true}}}})
```

The guard reads credentials from `:event`, not from app-db. Machine callbacks see `{:data :event :state :meta}`. They do not see app-db. ([Encapsulation](concepts.md#strict-encapsulation).)

Try it:

```clojure
(rf/dispatch-sync [:auth.login/flow [:auth.login/submit {:email "" :password ""}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => still :idle

(rf/dispatch-sync [:auth.login/flow [:auth.login/submit {:email "a@b.com"
                                                         :password "secret"}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => :submitting
```

## Step 3 — actions and candidate lists

<a id="step-3--an-action-and-the-data-fx-it-returns"></a>

A guard decides whether a transition may fire. An action describes what else should happen. It returns the same shape as a re-frame2 event handler, scoped to the machine:

```clojure
{:data {...}        ;; merged into this machine's private :data
 :fx   [[id args]]} ;; ordinary effects vector
```

Add actions for clearing an old error, recording a failed attempt, and storing a session token. On failure, write a **vector of candidates** — first guard that passes wins.

```clojure
:guards
{:form-valid?
 (fn [{[_ creds] :event}]
   (and (seq (:email creds)) (seq (:password creds))))

 :under-retry-limit
 (fn [{data :data}]
   (< (:attempts data) 2))}

:actions
{:clear-error
 (fn [_] {:data {:error nil}})

 :record-error
 ;; Live HTTP appends {:status :error :error …}; pull the failure map from :error.
 (fn [{data :data [_ {:keys [error]}] :event}]
   {:data (-> data
              (update :attempts inc)
              (assoc  :error (or (:message error) "Login failed.")))})

 :store-session
 ;; Live HTTP appends {:status :ok :value …}; pull the decoded body from :value.
 (fn [{[_ {:keys [value]}] :event}]
   {:fx [[:auth.session/store {:token (:token value)}]]})}

:idle
{:on {:auth.login/submit {:target :submitting
                          :guard  :form-valid?
                          :action :clear-error}}}

:submitting
{:on {:auth.login/success {:target :authed
                           :action :store-session}
      :auth.login/failure [{:target :error-shown
                            :guard  :under-retry-limit
                            :action :record-error}
                           {:target :locked-out
                            :action :record-error}]}}

:error-shown
{:on {:auth.login/dismiss :idle
      :auth.login/submit  {:target :submitting
                           :guard  :form-valid?
                           :action :clear-error}}}

:authed     {:meta {:terminal? true}}
:locked-out {:meta {:terminal? true}}
```

`:under-retry-limit` reads the *pre-action* `:attempts`, so it passes for the first two failures. On the third it fails and the unguarded default records that error too, then locks out — three attempts total, and the terminal failure is counted.

!!! note "`:data` merges"

    `{:data {:error nil}}` changes only `:error`. It does not replace the whole map.
    Details: [The model → effect map](concepts.md#the-effect-map-data-fx).

```clojure
(rf/dispatch-sync [:auth.login/flow [:auth.login/failure {:error {:message "nope"}}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

## Step 4 — talk to a real server

The machine should issue the login request when it enters `:submitting`. Put that work in an `:entry` action, arm an `:after` deadline if the server stalls, and tag the state so a view can ask "busy?" without naming it.

Managed HTTP is its own artefact. Require `[re-frame.http.managed]` at boot (it registers `:rf.http/managed`), or the effect resolves to `:rf.error/no-such-fx`.

```clojure
:issue-request
(fn [{[_ creds] :event}]
  {:fx [[:rf.http/managed
         {:request    {:method :post
                       :url    "/api/login"
                       :body   creds
                       :request-content-type :json}
          :decode     :json
          :on-success [:auth.login/flow [:auth.login/success]]
          :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

:record-timeout
(fn [{data :data}]
  {:data (-> data
             (update :attempts inc)
             (assoc  :error "Server took too long."))})

:submitting
{:tags  #{:auth/busy}
 :entry :issue-request
 :after {8000 [{:target :error-shown
                :guard  :under-retry-limit
                :action :record-timeout}
               {:target :locked-out
                :action :record-timeout}]}
 :on    {:auth.login/success {:target :authed :action :store-session}
         :auth.login/failure [{:target :error-shown
                               :guard  :under-retry-limit
                               :action :record-error}
                              {:target :locked-out
                               :action :record-error}]}}
```

`:entry :issue-request` runs when the machine enters `:submitting`. `:after` arms an 8-second timer and cancels it automatically when the state exits. If the server replies first, the machine leaves `:submitting` and the timeout becomes stale.

The timeout uses the **same guarded candidate list** as failure (an `:after` value takes the same shape as an `:on` clause), so the third stall — or the third failure — records its error and locks out.

`:on-success [:auth.login/flow [:auth.login/success]]` is written one element short on purpose. The outer vector routes to the machine; the inner event is what the machine handles. Managed HTTP **appends** the reply envelope onto that inner event:

```clojure
[:auth.login/success {:status :ok    :value {:token "…"} …}]
[:auth.login/failure {:status :error :error {:message "…"} …}]
```

So `:store-session` reads `:value` and `:record-error` reads `:error`. Deeper timer grammar: [Automatic transitions](automatic-transitions.md). Full HTTP: [Managed HTTP](../async/http.md).

## Step 5 — render the states

Project the snapshot. Ask **tags** for shared intent. The credential draft is ordinary app-db form state — read it through a plain sub, not out of the machine.

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (:state m)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (get-in m [:data :error])))

(rf/reg-sub :auth.login/draft
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit credentials]]]]}))

(rf/reg-view login-view []
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])
        draft @(subscribe [:auth.login/draft])
        busy? @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])]
    (case state
      nil          [:button {:on-click #(dispatch [:login/submit draft])}
                    "Sign in"]          ;; before the first machine event
      :idle        [:button {:disabled busy?
                             :on-click #(dispatch [:login/submit draft])}
                    "Sign in"]
      :submitting  [:p "Signing in…"]
      :error-shown [:div
                    [:p error]
                    [:button {:on-click #(dispatch [:auth.login/flow [:auth.login/dismiss]])}
                     "Try again"]]
      :authed      [:h1 "Welcome back"]
      :locked-out  [:h1 "Account locked"]
      [:p "Unknown login state"])))
```

The busy decision asks for the `:auth/busy` tag, not "is state exactly `:submitting`?". Add another in-flight state later with the same tag and this view keeps working. Pattern: [Tags](tags.md). The inputs that write the draft are a form-slice concern — [Build a form](../core/how-to/build-a-form.md).

## Step 6 — test the transition table

<a id="step-6--test-it-a-transition-is-a-pure-function"></a>

A transition is a pure function of *(definition, snapshot, event)*. No browser, frame, router, HTTP client, or clock.

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  (let [r (machines/machine-transition
            login-flow
            {:state :idle :data {:attempts 0 :error nil}}
            [:auth.login/submit {:email "a@b.com"
                                 :password "secret"}])]
    (is (result/ok? r))
    (is (= :submitting (:state (result/snap r))))
    (is (= :rf.http/managed (ffirst (result/fx r)))))   ;; :entry ran :issue-request

  ;; Two failures already recorded (:attempts 2); the third is terminal.
  (let [r (machines/machine-transition
            login-flow
            {:state :submitting :data {:attempts 2 :error nil}}
            ;; Pure-table test: invent the failure shape the action expects.
            ;; Live HTTP would append {:status :error :error …} instead.
            [:auth.login/failure {:error {:message "bad creds"}}])]
    (is (result/ok? r))
    (is (= :locked-out (:state (result/snap r))))
    (is (= 3 (get-in (result/snap r) [:data :attempts])))
    (is (= "bad creds" (get-in (result/snap r) [:data :error])))))
```

Discriminate with `result/ok?`. Read the next snapshot and the effects vector with `result/snap` and `result/fx`. More on Result accessors and Xray: [Inspecting and testing](inspecting-machines.md).

## The complete machine

Everything above in one registration — the form you copy into a real app:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))   ;; registers :rf.http/managed — the :issue-request fx

;; cf. examples/capabilities/machines/state_machine_walkthrough

(rf/defmachine login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))
    :under-retry-limit
    (fn [{data :data}] (< (:attempts data) 2))}

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :record-error
    (fn [{data :data [_ {:keys [error]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error (or (:message error) "Login failed.")))})

    :store-session
    ;; Managed HTTP appends {:status :ok :value <decoded> …}; :value is the body.
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})

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
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error "Server took too long."))})}

   :states
   {:idle
    {:on {:auth.login/submit {:target :submitting
                              :guard  :form-valid?
                              :action :clear-error}}}

    :submitting
    {:tags  #{:auth/busy}
     :entry :issue-request
     :after {8000 [{:target :error-shown
                    :guard  :under-retry-limit
                    :action :record-timeout}
                   {:target :locked-out
                    :action :record-timeout}]}
     :on    {:auth.login/success {:target :authed :action :store-session}
             :auth.login/failure [{:target :error-shown
                                   :guard  :under-retry-limit
                                   :action :record-error}
                                  {:target :locked-out
                                   :action :record-error}]}}

    :error-shown
    {:on {:auth.login/dismiss {:target :idle}
          :auth.login/submit  {:target :submitting
                               :guard  :form-valid?
                               :action :clear-error}}}

    ;; Resting leaves — omit :final? so the machine persists for the session.
    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})

(rf/reg-machine :auth.login/flow login-flow)

(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit credentials]]]]}))
```

When a flat table is no longer enough — nested checkout under auth, parallel form axes, spawned workers — open [The model](concepts.md) for vocabulary, then the matching growth page.
