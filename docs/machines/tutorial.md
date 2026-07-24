# Tutorial: build a login machine

Build one machine end to end — idle → submitting → authed / error / locked-out —
one idea per step. By the end you have a **complete, copy-pasteable** table
(guards, actions, HTTP, timeout, tags, lock-out) plus a view and a pure test.

**Prerequisites.** [Core introduction](../core/introduction.md) (events, app-db,
effects). Vocabulary for later pages: [The model](concepts.md).

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

<a id="step-1--your-first-machine"></a>

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
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]])
```

Read the live [snapshot](glossary.md#snapshot):

```clojure
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :submitting :data {:attempts 0 :error nil}}
;;    nil before the first event — the machine boots on first dispatch
```

Outer handlers stay ordinary. They only wrap the machine address:

```clojure
(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit credentials]]]]}))
```

## Step 2 — a guard

<a id="step-2--a-guard-refuse-an-invalid-submit"></a>

Refuse empty credentials. Name the guard once; reference it from every arrow that
needs it:

```clojure
:guards
{:form-valid?
 (fn [{[_ creds] :event}]
   (and (seq (:email creds)) (seq (:password creds))))}

;; on the arrows:
:idle        {:on {:auth.login/submit {:target :submitting :guard :form-valid?}}}
:error-shown {:on {:auth.login/submit {:target :submitting :guard :form-valid?}
                   :auth.login/dismiss {:target :idle}}}
```

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "" :password ""}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => still :idle  (guard said no)
```

The guard reads credentials from **`:event`**, not app-db —
[encapsulation](concepts.md#strict-encapsulation).

## Step 3 — actions and candidate lists

<a id="step-3--an-action-and-the-data-fx-it-returns"></a>

An **action** returns `{:data … :fx …}` like a pure event handler — it never calls
HTTP itself.

- `:clear-error` — wipe the last error on a fresh submit
- `:record-error` — bump `:attempts` and store a message
- `:store-session` — dispatch an ordinary effect on success
- **Candidate list** on failure — the first two failures show the error; the third
  records it and locks out (three attempts total)

```clojure
:guards
{:form-valid?       …
 :under-retry-limit (fn [{data :data}] (< (:attempts data) 2))}

:actions
{:clear-error
 (fn [_] {:data {:error nil}})
 :record-error
 (fn [{data :data [_ {:keys [error]}] :event}]
   {:data (-> data
              (update :attempts inc)
              (assoc  :error (or (:message error) "Login failed.")))})
 :store-session
 ;; HTTP appends {:status :ok :value …}; pull the decoded body from :value.
 (fn [{[_ {:keys [value]}] :event}]
   {:fx [[:auth.session/store {:token (:token value)}]]})}

:submitting
{:on {:auth.login/success {:target :authed :action :store-session}
      :auth.login/failure [{:target :error-shown
                            :guard  :under-retry-limit
                            :action :record-error}
                           {:target :locked-out
                            :action :record-error}]}}
```

A **vector of candidates** is tried in order; first guard that passes wins. The
guard reads the *pre-action* `:attempts`, so it passes for the first two failures;
on the third it fails and the fallback candidate records that final error before
locking out — so the terminal failure is counted, not discarded.

!!! note "`:data` merges"

    `{:data {:error nil}}` changes only `:error`. It does not replace the whole map.
    Details: [The model → effect map](concepts.md#the-effect-map-data-fx).

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/failure {:error {:message "nope"}}]])
@(rf/subscribe [:rf/machine :auth.login/flow])
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

## Step 4 — talk to a real server

Add an **`:entry`** action on `:submitting` that fires [managed HTTP](../async/http.md),
and an **`:after`** deadline if the server stalls. Tag the state so views can ask
"busy?" without naming it. Managed HTTP is its own artefact — require
`[re-frame.http.managed]` at boot (it registers `:rf.http/managed`), or the effect
resolves to `:rf.error/no-such-fx`:

```clojure
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
  {:data (-> data (update :attempts inc) (assoc :error "Server took too long."))})

:submitting
{:tags  #{:auth/busy}
 :entry :issue-request
 :after {8000 [{:target :error-shown
                :guard  :under-retry-limit
                :action :record-timeout}
               {:target :locked-out
                :action :record-timeout}]}
 :on    {…}}   ;; success / failure as in step 3
```

`:on-success [:auth.login/flow [:auth.login/success]]` is written one element short
on purpose — managed HTTP **appends** the [canonical reply envelope](../async/http.md)
onto the inner event, so `:store-session` sees
`[:auth.login/success {:status :ok :value {:token "…"} …}]`.

`:after` arms on entry and cancels on exit. A stall counts as an attempt too: the
timeout carries the **same guarded candidate list** as a rejected login (an `:after`
value takes the same shape as an `:on` clause), so the third stall — or the third
failure — records its error and locks out. Deeper timer grammar:
[Automatic transitions](automatic-transitions.md).

## Step 5 — render every state

Project the snapshot; ask **tags** for shared intent:

```clojure
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (:state m)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (get-in m [:data :error])))

;; The credential draft is ordinary app-db form state — read through a plain
;; sub, never reached out of the view. The inputs that WRITE it are a form-slice
;; concern; build one in [Build a form](../core/how-to/build-a-form.md).
(rf/reg-sub :auth.login/draft
  (fn [db _] (get-in db [:auth :login-form :draft])))

(rf/reg-view login-view []
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])
        draft @(subscribe [:auth.login/draft])
        busy? @(rf/subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])]
    (case state
      :idle        [:button {:disabled busy?
                             :on-click #(dispatch [:login/submit draft])}
                    "Sign in"]
      :submitting  [:p "Signing in…"]
      :error-shown [:div [:p error]
                    [:button {:on-click #(dispatch [:auth.login/flow [:auth.login/dismiss]])}
                     "Try again"]]
      :authed      [:h1 "Welcome back"]
      :locked-out  [:h1 "Account locked"]
      [:p "…"])))   ;; nil before first event
```

Add another in-flight state later with the same `:auth/busy` tag and this view keeps
working. Pattern: [Tags](tags.md).

## Step 6 — test a transition as a pure function

<a id="step-6--test-it-a-transition-is-a-pure-function"></a>

No frame, no browser, no network:

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
            [:auth.login/submit {:email "a@b.com" :password "secret"}])]
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
    ;; the terminal failure is still counted — attempts bumped, message stored
    (is (= 3 (get-in (result/snap r) [:data :attempts])))
    (is (= "bad creds" (get-in (result/snap r) [:data :error])))))
```

More on Result accessors and Xray: [Inspecting and testing](inspecting-machines.md).

## The complete machine

Everything above in one registration — the form you copy into a real app:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))   ;; registers :rf.http/managed — the :issue-request fx

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

When a flat table is no longer enough — nested checkout under auth, parallel form
axes, spawned workers — open [The model](concepts.md) for vocabulary, then the
matching growth page (hierarchy, parallel, actors, …).
