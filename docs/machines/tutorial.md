# First machine

<a id="1-first-machine"></a>

<a id="tutorial"></a>
<a id="tutorial-build-a-login-machine"></a>

Build a login flow that validates a submit, retries failed requests and shows
the result. The final example includes the complete table and a pure test.

## Step 0 — turn machines on

Add `day8/re-frame2-machines` alongside the core dependency, using the same
version. Require its namespace once from a boot or feature namespace:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))
```

Skip this and the first `reg-machine` throws `:rf.error/machines-artefact-missing`.

If you are coming from the [Core tutorial](../core/introduction.md), keep
its installed view adapter and skip the next block. In a **fresh standalone
JVM or Node REPL**, install the shipped headless adapter once:

```clojure
(require '[re-frame.substrate.plain-atom :as plain-atom])
(rf/init! plain-atom/adapter)
```

Both paths create the same demo frame in Step 1. Step 5 renders it in a
browser app using that app's view adapter.

## Step 1 — write the transition table

<a id="step-1--your-first-machine"></a>

A machine is a map. It names an initial state, some private `:data`, and the states plus transitions. Define it with `defmachine`, then register it:

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

    :authed
    {:meta {:terminal? true}}}})

(rf/reg-machine :auth.login/flow login-flow)
```

Targets here are bare keywords. The next two steps turn those into maps, then into candidate vectors.

Create one frame to hold the live snapshot:

```clojure
(def login-frame (rf/make-frame {:id :auth.login/demo}))
```

Drive the machine with `dispatch`, as you would any other handler. These
REPL samples use `dispatch-sync`, so the next read sees the result, and pass
`{:frame login-frame}` because no view or handler supplies a current frame.
For detached snippets on later Machines pages, wrap their calls in
`(rf/with-frame login-frame ...)` to supply that same scope:

```clojure
(rf/dispatch-sync
  [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]]
  {:frame login-frame})
```

The outer vector is a re-frame2 **event**. `:auth.login/flow` is the event id.
It is also the machine id you registered, so the handler that runs is the table.
This id is a **singleton**: one registered id, one live instance per
[frame](../core/glossary.md#frame). The snapshot lives in that frame's
runtime-db and is `nil` until this first dispatch.

`[:auth.login/submit {…}]` is the **trigger** — the thing the table matches.
`:auth.login/submit` is the `:on` key. The map is payload; a guard or action
reads it from `:event`.

Read the live [snapshot](glossary.md#snapshot) once at the REPL. The examples
show application fields; the runtime also includes `:rf/*` metadata:

```clojure
(rf/subscribe-once [:rf/machine :auth.login/flow] {:frame login-frame})
;; => {:state :submitting :data {:attempts 0 :error nil}}
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

(rf/reg-machine :auth.login/flow login-flow)
```

The guard reads credentials from `:event`, not from app-db. Machine callbacks see `{:data :event :state :meta}`. They do not see app-db. ([Encapsulation](concepts.md#strict-encapsulation).)

Registering an id again replaces its table and keeps its live snapshot, so the machine is still in `:submitting` from Step 1. The first two lines take it back to `:idle`:

```clojure
(rf/dispatch-sync [:auth.login/flow [:auth.login/failure]] {:frame login-frame}) ;; → :error-shown
(rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame login-frame}) ;; → :idle

(rf/dispatch-sync
  [:auth.login/flow [:auth.login/submit {:email "" :password ""}]]
  {:frame login-frame})
(:state (rf/subscribe-once [:rf/machine :auth.login/flow] {:frame login-frame}))
;; => :idle — the guard refused the submit

(rf/dispatch-sync
  [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "secret"}]]
  {:frame login-frame})
(:state (rf/subscribe-once [:rf/machine :auth.login/flow] {:frame login-frame}))
;; => :submitting
```

## Step 3 — actions and candidate vectors

<a id="step-3--an-action-and-the-data-fx-it-returns"></a>

A guard decides whether a transition may fire. An action describes what else should happen. It returns the same shape as a re-frame2 event handler, scoped to the machine:

```clojure
{:data {...}        ;; merged into this machine's private :data
 :fx   [[id args]]} ;; ordinary effects vector
```

The session belongs to the application, so give it an ordinary event handler:

```clojure
(rf/reg-event :auth.session/store
  (fn [{:keys [db]} [_ {:keys [token]}]]
    {:db (assoc-in db [:auth :session :token] token)}))
```

The machine describes a dispatch to that handler. Its own `:data` keeps only
the attempt count and the error. Add the following guards, actions and states
to `login-flow`, then run `(rf/reg-machine :auth.login/flow login-flow)` again.
On failure, the **candidate vector** takes the first guard that passes.

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
   {:fx [[:dispatch [:auth.session/store {:token (:token value)}]]]})}

;; under :states
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
    Details: [The table → effect map](concepts.md#the-effect-map-data-fx).

```clojure
(rf/dispatch-sync
  [:auth.login/flow [:auth.login/failure {:error {:message "nope"}}]]
  {:frame login-frame})
(rf/subscribe-once [:rf/machine :auth.login/flow] {:frame login-frame})
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

## Step 4 — talk to a real server

The machine should issue the login request when it enters `:submitting`. Put that work in an `:entry` action, arm an `:after` deadline if the server stalls, and tag the state so a view can ask "busy?" without naming it.

Managed HTTP is its own artefact. Add `day8/re-frame2-http` at the same
version and require `[re-frame.http.managed]` at boot (it registers
`:rf.http/managed`), or the effect resolves to `:rf.error/no-such-fx`.

```clojure
;; under :actions
:issue-request
(fn [{[_ creds] :event}]
  {:fx [[:rf.http/managed
         {:request    {:method :post
                       :url    "/api/login"
                       :body   creds
                       :request-content-type :json}
          :request-id :auth.login/request
          :decode     :json
          :on-success [:auth.login/flow [:auth.login/success]]
          :on-failure [:auth.login/flow [:auth.login/failure]]}]]})

:record-timeout
(fn [{data :data}]
  {:data (-> data
             (update :attempts inc)
             (assoc  :error "Server took too long."))})

;; under :states
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

The stable `:request-id` makes each new attempt supersede the previous HTTP
request. A late reply from an older attempt is then suppressed. The machine's
`:after` changes state; it does not itself abort a singleton's request. While
the machine sits in `:error-shown`, an old reply is unhandled. If the user
retries, supersession prevents that reply from completing the new attempt.
For work that must stop immediately on state exit, use a
[state-bound request actor](actors.md#state-bound-spawn).

The timeout uses the **same guarded candidate vector** as failure (an `:after` value takes the same shape as an `:on` clause), so the third stall — or the third failure — records its error and locks out.

`:on-success [:auth.login/flow [:auth.login/success]]` is written one element short on purpose. The outer vector is the event that addresses the singleton. The inner vector is the trigger the table handles. Managed HTTP **appends** the reply envelope to the event, and the machine moves anything after the trigger onto it, so the table sees:

```clojure
[:auth.login/success {:status :ok    :value {:token "…"} …}]
[:auth.login/failure {:status :error :error {:message "…"} …}]
```

So `:store-session` reads `:value` and `:record-error` reads `:error`. Deeper timer grammar: [Automatic transitions](automatic-transitions.md). Full HTTP: [Managed HTTP](../async/http.md).

## Step 5 — render the states

Project the snapshot. Ask **tags** for shared intent. The credential draft
is ordinary app-db form state at `[:auth :login :draft]`. Reuse the initializer
and keystroke handlers from [Build a form](../core/how-to/build-a-form.md),
then initialise the form in the same frame:

```clojure
(rf/dispatch-sync [:form.login/initialise] {:frame login-frame})
```

The view below belongs in a browser app with its view adapter installed
([Boot and mount an app](../core/how-to/boot-and-mount-an-app.md)). Its local
`subscribe` and `dispatch` functions use the frame supplied by its parent.

```clojure
(rf/reg-sub :auth.login/state {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[m] _] (:state m)))

(rf/reg-sub :auth.login/error {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[m] _] (get-in m [:data :error])))

(rf/reg-sub :auth.login/draft
  (fn [db _] (get-in db [:auth :login :draft])))

(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit credentials]]]]}))

(rf/reg-view login-view []
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])
        draft @(subscribe [:auth.login/draft])
        busy? @(subscribe [:rf.machine/has-tag? :auth.login/flow :auth/busy])]
    (case state
      :error-shown [:div
                    [:p error]
                    [:button {:on-click #(dispatch [:auth.login/flow [:auth.login/dismiss]])}
                     "Try again"]]
      :authed      [:h1 "Welcome back"]
      :locked-out  [:h1 "Account locked"]
      ;; nil before the first dispatch, :idle, :submitting
      [:div
       [:label "Email"
        [:input {:type "email" :value (or (:email draft) "")
                 :disabled busy?
                 :on-change #(dispatch [:form.login/edit-field :email
                                        (.. % -target -value)])}]]
       [:label "Password"
        [:input {:type "password" :value (or (:password draft) "")
                 :disabled busy?
                 :on-change #(dispatch [:form.login/edit-password
                                        {:value (.. % -target -value)}])}]]
       [:button {:disabled busy?
                 :on-click #(dispatch [:login/submit draft])}
        (if busy? "Signing in…" "Sign in")]])))
```

Mount this tree with your app's adapter so the view reads the demo snapshot
and the form's input handlers write into that same frame:

```clojure
[rf/frame-provider {:frame login-frame}
 [login-view]]
```

The button asks for the `:auth/busy` tag rather than checking for `:submitting`. Add another in-flight state later with the same tag and the view keeps working. Pattern: [Tags](tags.md). The session handler above makes the token available to the rest of the app.

## Step 6 — test the transition table

<a id="step-6--test-it-a-transition-is-a-pure-function"></a>

A transition is a pure function of *(definition, snapshot, trigger)*. No browser, frame, router, HTTP client, or clock.

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as rf.machines]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  (let [{:keys [status snapshot fx]}
        (rf.machines/machine-transition
          login-flow
          {:state :idle :data {:attempts 0 :error nil}}
          [:auth.login/submit {:email "a@b.com"
                               :password "secret"}])]
    (is (= :ok status))
    (is (= :submitting (:state snapshot)))
    (is (= :rf.http/managed (ffirst fx))))   ;; :entry ran :issue-request

  ;; Two failures already recorded (:attempts 2); the third is terminal.
  (let [{:keys [status snapshot]}
        (rf.machines/machine-transition
          login-flow
          {:state :submitting :data {:attempts 2 :error nil}}
          ;; Pure-table test: invent the failure shape the action expects.
          ;; Live HTTP would append {:status :error :error …} instead.
          [:auth.login/failure {:error {:message "bad creds"}}])]
    (is (= :ok status))
    (is (= :locked-out (:state snapshot)))
    (is (= 3 (get-in snapshot [:data :attempts])))
    (is (= "bad creds" (get-in snapshot [:data :error])))))
```

The result is a plain map: `:status` is `:ok` or `:error`, `:snapshot` is the next snapshot, `:fx` the effects vector. Nothing beyond `re-frame.machines` is required. More on the result, failures and Xray: [Inspecting and testing](inspecting-machines.md).

## The complete machine

The table and the event handlers it needs. The view above supplies credentials;
`/api/login` returns a JSON object with a `token` on success:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))   ;; registers :rf.http/managed — the :issue-request fx

;; cf. examples/core/login/model.cljc

(rf/reg-event :auth.session/store
  (fn [{:keys [db]} [_ {:keys [token]}]]
    {:db (assoc-in db [:auth :session :token] token)}))

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
      {:fx [[:dispatch [:auth.session/store {:token (:token value)}]]]})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :request-id :auth.login/request
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

## Run it

The cell registers the complete machine and mounts a cut-down Step 5 view,
with fixed credentials in place of the form, in two frames. Neither frame
sends a request over the network: the first answers `/api/login` with a
token, and the second fails every request. **Sign in** in the first reaches
`:authed` and stores the token in app-db. In the second, each failure shows
the error; **Try again** and sign in twice more, and the third failure locks
the account. **Sign in with empty fields** leaves the machine in `:idle`,
because `:form-valid?` refuses the submit.

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])

;; The first frame's server: every login succeeds with a token.
(http-test-support/install-managed-request-stubs!
  {[:post "/api/login"] {:reply {:ok {:token "demo-token"}}}})

(rf/reg-event :auth.session/store
  (fn [{:keys [db]} [_ {:keys [token]}]]
    {:db (assoc-in db [:auth :session :token] token)}))

(rf/reg-machine :auth.login/flow
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
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:dispatch [:auth.session/store {:token (:token value)}]]]})

    :issue-request
    (fn [{[_ creds] :event}]
      {:fx [[:rf.http/managed
             {:request    {:method :post :url "/api/login" :body creds
                           :request-content-type :json}
              :request-id :auth.login/request
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

    :authed     {:meta {:terminal? true}}
    :locked-out {:meta {:terminal? true}}}})

(rf/reg-event :login/submit
  (fn [_ [_ credentials]]
    {:fx [[:dispatch [:auth.login/flow [:auth.login/submit credentials]]]]}))

(rf/reg-sub :auth.login/state {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[m] _] (:state m)))

(rf/reg-sub :auth.login/error {:inputs [[:rf/machine :auth.login/flow]]}
  (fn [[m] _] (get-in m [:data :error])))

(rf/reg-sub :auth.session/token
  (fn [db _] (get-in db [:auth :session :token])))

;; Step 5's view, with fixed credentials in place of the form.
(rf/reg-view login-view [server]
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])]
    [:div
     [:p [:strong server] " · state: " (pr-str state)]
     (case state
       :error-shown [:div
                     [:p error]
                     [:button {:on-click #(dispatch [:auth.login/flow [:auth.login/dismiss]])}
                      "Try again"]]
       :authed      [:p "Welcome back. Session token: " @(subscribe [:auth.session/token])]
       :locked-out  [:p "Account locked"]
       [:div
        [:button {:on-click #(dispatch [:login/submit {:email "a@b.com" :password "secret"}])}
         "Sign in"]
        [:button {:on-click #(dispatch [:login/submit {:email "" :password ""}])}
         "Sign in with empty fields"]])]))

;; :fx-overrides answers each frame's requests without a network.
;; A real app leaves it out.
[:div
 [rf/frame-root {:id :auth.login/accepting
                 :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
  [login-view "Server accepts"]]
 [rf/frame-root {:id :auth.login/rejecting
                 :fx-overrides {:rf.http/managed :rf.http/managed-canned-failure}}
  [login-view "Server rejects"]]]
```

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| REPL dispatch or read throws `:rf.error/no-frame-context` | No view or handler supplies a frame | Pass `{:frame login-frame}` as above |
| First `reg-machine` throws `:rf.error/machines-artefact-missing` | `[re-frame.machines]` not required | Require it once at boot |
| `:rf.error/no-such-fx` on `:rf.http/managed` | HTTP artefact not loaded | Require `[re-frame.http.managed]` |
| Success reports `:rf.error/no-such-handler` for `:auth.session/store` | The application session handler was not registered | Register the handler from Step 3 or the complete example |
| Submit stays on `:idle` | `:form-valid?` saw empty credentials | Put email and password on the event |
| Snapshot is `nil` | No event has addressed the machine yet | Dispatch first, or fall back to `:initial` in the view |
| Third failure does not lock out | Guard compared the post-action count, or lockout skipped `:record-error` | Guard sees pre-action `:attempts`; use `(< n 2)` and record on the default candidate |
