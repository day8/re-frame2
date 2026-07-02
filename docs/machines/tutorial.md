# Tutorial: build a login machine

Let's build a machine to model a **login flow** — idle, then submitting, then either signed-in or showing an error, and locked out after too many tries — adding one idea at a time: a guard, an action, a real server call, a view, and a test.


## Step 0 — turn machines on

To reduce bundle size, re-frame2 doesn't include the machines capability by default, so if you want to use it, you have to explicitly require it into your boot/root namespace. Like this:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))   ;; ← requiring it is what turns machines on
```

If you forget to do this, your first `reg-machine` will loudly throw `:rf.error/machines-artefact-missing`.  


## Step 1 — your first machine

A machine is a data structure which defines a set of named states, and for each one, which triggers move it where. Here's a login as that data structure — four states, and the triggers that connect them.

```clojure
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}     ;; the machine's private state; Step 3 puts it to work

   :states
   {:idle        {:on {:auth.login/submit  {:target :submitting}}}
    :submitting  {:on {:auth.login/success {:target :authed}
                       :auth.login/failure {:target :error-shown}}}
    :error-shown {:on {:auth.login/dismiss {:target :idle}
                       :auth.login/submit  {:target :submitting}}}
    :authed      {}}})                    ;; a resting state — no outgoing transitions
```

Except, instead of triggers, we'll call them events. 

Register it — one line to create a singleton machine:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

This singleton machine is identifiable via `:auth.login/flow` because the code above literally wrapped it in an event handler. `reg-machine` is pretty much syntactic sugar over `reg-event`.

And that means, to drive this machine, we dispatch events. The event id is that of the machine, and the trigger rides *inside* an event wrapper, like this:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit]])
```

In a similar way, if a view needs to see what state the machine is in, it reads that snapshot through a subscription (a specialised subscription called sub-machine)

```clojure
@(rf/sub-machine :auth.login/flow)
;; => {:state :submitting :data {:attempts 0 :error nil}}
;;    (nil before the very first event — the machine starts itself on its first dispatch)
```


**Why it works:** `reg-machine` is sugar over `reg-event`, so the snapshot rides the [frame](../core/concepts/frames.md) and you read it with an ordinary subscription — same `dispatch`, same `subscribe`. [Concepts → Registering and running it](concepts.md#registering-and-running-it) walks the machinery.

## Step 2 — a guard: refuse an invalid submit

Right now *any* `:auth.login/submit` trigger moves you from `:idle` to `:submitting`, even with an empty form. A **guard** is a yes/no test that gates a transition. Below we add one named guard `:form-valid?`, and reference it from the arrow:

```clojure
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   ;; guards defined here
   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))}

   :states
   {:idle        {:on {:auth.login/submit  {:target :submitting
                                            :guard  :form-valid?}}}   ;; <- use here.
    :submitting  {:on {:auth.login/success {:target :authed}
                       :auth.login/failure {:target :error-shown}}}
    :error-shown {:on {:auth.login/dismiss {:target :idle}
                       :auth.login/submit  {:target :submitting :guard :form-valid?}}}
    :authed      {}}})
```

You name the guard once in `:guards`, then point at it by id — `:guard :form-valid?` — from every arrow that needs it.

**What you see:** submit empty credentials and the machine stays put; submit real ones and it moves.

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "" :password ""}]])
@(rf/sub-machine :auth.login/flow)     ;; => {:state :idle …}        (guard said no)

(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "secret"}]])
@(rf/sub-machine :auth.login/flow)     ;; => {:state :submitting …}
```

**Notice:** the guard read the credentials from `:event`, not [app-db](../core/concepts/app-db.md) — a machine callback sees only its own `:data` and the event that woke it. [Concepts → Strict encapsulation](concepts.md#strict-encapsulation--a-machine-sees-only-its-own-data) explains why that boundary matters.

## Step 3 — an action, and the `{:data :fx}` it returns

A guard decides *whether*; an **action** computes change. But it never performs a side-effect itself — it **returns** the same `{:data :fx}` map a `reg-event` does: `:data` is merged into the machine's own data, `:fx` is the ordinary effects vector. [Concepts → The action effect map](concepts.md#the-action-effect-map--data-fx) has the full shape.

Below, we add three actions and a second guard, and wire them in. `:clear-error` wipes the last error on submit; `:record-error` counts a failure into `:data`; `:store-session` fires an effect on success:

```clojure
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}

   :guards
   {:form-valid?
    (fn [{[_ creds] :event}]
      (and (seq (:email creds)) (seq (:password creds))))

    :under-retry-limit
    (fn [{data :data}] (< (:attempts data) 3))}        ;; reads its own :data

   :actions
   {:clear-error
    (fn [_] {:data {:error nil}})

    :record-error
    (fn [{data :data [_ {:keys [failure]}] :event}]
      {:data (-> data
                 (update :attempts inc)
                 (assoc  :error (or (:message failure) "Login failed.")))})

    :store-session
    (fn [{[_ {:keys [value]}] :event}]
      {:fx [[:auth.session/store {:token (:token value)}]]})}   ;; an fx, not a side-effect

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

    :authed     {:meta {:terminal? true}}      ;; resting states — :meta is a tooling hint,
    :locked-out {:meta {:terminal? true}}}})   ;; NOT :final? (which would auto-destroy the machine)
```

The `:auth.login/failure` arrow is now a **list of candidates** — the runtime takes the first whose guard passes. Under the retry limit, a failure records the error and shows it; past it, the unguarded candidate wins and the machine locks out.

**What you see:** drive a failure and watch `:data` change.

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]])
(rf/dispatch [:auth.login/flow [:auth.login/failure {:failure {:message "nope"}}]])
@(rf/sub-machine :auth.login/flow)
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

Keep submitting and failing. The first three failures each land in `:error-shown` with `:attempts` climbing `1 → 2 → 3`. On the fourth, `:under-retry-limit` returns false, the guarded candidate is skipped, and the machine settles in `:locked-out`.

!!! note "`:data` is a merge, not a replace"

    — `:clear-error` changes only `:error`, leaving `:attempts` alone. [Concepts → The action effect map](concepts.md#the-action-effect-map--data-fx) has the sharp edges.

## Step 4 — talk to a real server

So far you've moved the machine by hand-dispatching `:auth.login/success` / `:auth.login/failure`. Now make `:submitting` actually call the server and let the reply drive the machine.

Give `:submitting` an `:entry` action — it runs the moment the state is entered — that fires [managed HTTP](../async/http.md). Name the reply events as *machine-wrapped* events, and the reply loops straight back in. Add a `:record-timeout` action for the deadline case:

```clojure
:actions
{;; …clear-error, record-error, store-session as in Step 3…

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
 :entry :issue-request                                          ;; fire the request on entry
 :after {8000 {:target :error-shown :action :record-timeout}}  ;; …or give up after 8s
 :on    {:auth.login/success {:target :authed :action :store-session}
         :auth.login/failure [{:target :error-shown :guard :under-retry-limit :action :record-error}
                              {:target :locked-out}]}}
```

Look at `:on-success [:auth.login/flow [:auth.login/success]]` — the machine id outside, the inner event inside, written one element short on purpose. When the request returns, managed HTTP *appends* its reply to that inner event, so what actually arrives is `[:auth.login/success {:value {:token "…"}}]` — exactly what `:store-session` destructures. A machine and an async effect compose with no glue code in between.

The `:after` is a declarative timer: it arms when you enter `:submitting` and cancels the moment you leave. If the reply lands first, the timer is cancelled; if 8 seconds pass first, the machine records a timeout and shows the error. No `setTimeout`, no cancel flag.

!!! note "Do, then observe"

    Dispatch a submit with [Xray](../core/how-to/debug-with-xray.md) open. The request leaves; when the reply returns, the transition shows up as an ordinary event row — snapshot before and after — riding the same trace stream as everything else.

## Step 5 — render every state

A view reads the snapshot and shows the right thing for each state. Inside `reg-view` you call `subscribe` unprefixed — the macro binds it to this view's frame for you. (Outside a view, reach through the facade: `rf/subscribe`, `rf/dispatch`.) Read small projections off the snapshot, and ask the machine a *question* rather than naming a state:

```clojure
;; Two small projections off the snapshot — ordinary subscriptions.
(rf/reg-sub :auth.login/state
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (:state m)))

(rf/reg-sub :auth.login/error
  :<- [:rf/machine :auth.login/flow]
  (fn [m _] (get-in m [:data :error])))

(rf/reg-view login-view []
  (let [state @(subscribe [:auth.login/state])
        error @(subscribe [:auth.login/error])
        busy? @(rf/machine-has-tag? :auth.login/flow :auth/busy)]
    (case state
      :idle        [:button {:disabled busy?} "Sign in"]
      :submitting  [:p "Signing in…"]
      :error-shown [:div [:p error] [:button "Try again"]]
      :authed      [:h1 "Welcome back"]
      :locked-out  [:h1 "Account locked"]
      [:p "…"])))                          ;; nil state — before the first event
```

For that `busy?` read to work, tag the busy state. A **tag** is a label on a state node; the view asks "is this tag set?" rather than hard-coding which states count as busy:

```clojure
:submitting {:tags  #{:auth/busy}
             :entry :issue-request
             ;; …the rest as in Step 4…}
```

**What you see:** the page shows **Sign in**; a valid submit moves it to **Signing in…**; the reply lands you on **Welcome back** or the error; and a fourth failure swaps in **Account locked**.

!!! note "Why a tag, not a state name?"

    Add a sixth state that's also "busy" later — `:retrying`, say — and a view that branches on the `:auth/busy` tag picks it up with zero changes. Asking *what's true* scales; enumerating state names doesn't. [Tags](tags.md) is the whole pattern.

## Step 6 — test it: a transition is a pure function

Here's the payoff. A transition is a pure function of *(table, snapshot, event)* — no frame, no browser, no network. `machine-transition` runs exactly one, and hands back a result you destructure:

```clojure
(ns app.login-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [app.login :refer [login-flow]]))

(deftest login-flow-test
  ;; a valid submit enters :submitting and fires the request fx
  (let [{snap ::result/snap fx ::result/fx}
        (machines/machine-transition login-flow
                                     {:state :idle :data {:attempts 0 :error nil}}
                                     [:auth.login/submit {:email "a@b.com" :password "secret"}])]
    (is (= :submitting (:state snap)))
    (is (= :rf.http/managed (ffirst fx))))         ;; :entry ran :issue-request

  ;; at the retry limit, a failure routes to :locked-out instead of :error-shown
  (let [{snap ::result/snap}
        (machines/machine-transition login-flow
                                     {:state :submitting :data {:attempts 3 :error nil}}
                                     [:auth.login/failure {:failure {:message "bad creds"}}])]
    (is (= :locked-out (:state snap)))))
```

Feed in a snapshot and an event; assert on what comes back. The result is a value — `::result/snap` / `::result/fx`, or `result/ok?` / `result/fail?` (a throwing action is a *failure value*, not an exception out of your test). It runs on the JVM in microseconds — exactly the testing experience you want for the flows where testing usually gets hard. [Inspecting and testing machines](inspecting-machines.md) goes deeper: the Result accessors, asserting effects as data, and the three test levels.
