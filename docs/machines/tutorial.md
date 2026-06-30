# Tutorial: build a login machine

The fastest way to understand a re-frame2 state machine is to build one and watch each piece arrive on its own. We'll make a **login flow** — idle, then submitting, then either signed-in or showing an error, and locked out after too many tries — adding one idea at a time: a guard, an action, a real server call, a view, and a test.

This page assumes you've done the [core Quickstart](../core/quickstart.md) — you know what an [event](../core/concepts/events-and-the-cascade.md), a [subscription](../core/concepts/subscriptions.md), and a [view](../core/concepts/views.md) are. If you'd rather see the whole model at once, read [Concepts](concepts.md).

> **One idea to carry through.** A machine is just an event handler. You *read* its current state — a small map called the [snapshot](glossary.md#snapshot) — through a subscription, and you *move* it by dispatching an event. There's no actor object to hold and no second runtime to learn. Keep that in mind and every step below is a small variation on the `dispatch` / `subscribe` loop you already know.

## Step 0 — turn machines on

Machines ship as their own package, `day8/re-frame2-machines`, so an app that has none builds a bundle clean of them. Add the dependency, then require the namespace once at boot:

```clojure
(ns app.login
  (:require [re-frame.core :as rf]
            [re-frame.machines]))   ;; ← requiring it is what turns machines on
```

That bare `[re-frame.machines]` require has a side effect: it wires up `reg-machine`, the `:rf/machine` subscription, and the rest of the machine runtime. Forget it and your first `reg-machine` throws `:rf.error/machines-artefact-missing` — a loud error that names the artefact to add, not a silent no-op. `reg-machine` itself lives on the `rf/` facade, alongside `dispatch` and `subscribe`.

## Step 1 — your first machine

A machine is a **transition table**: a set of named states, and for each one, which events move it where. Here's a login as that table — four states, and the events that connect them.

```clojure
(def login-flow
  {:initial :idle
   :data    {:attempts 0 :error nil}     ;; the machine's private memory; Step 3 puts it to work

   :states
   {:idle        {:on {:auth.login/submit  {:target :submitting}}}
    :submitting  {:on {:auth.login/success {:target :authed}
                       :auth.login/failure {:target :error-shown}}}
    :error-shown {:on {:auth.login/dismiss {:target :idle}
                       :auth.login/submit  {:target :submitting}}}
    :authed      {}}})                    ;; a resting state — no outgoing transitions
```

Register it — one line:

```clojure
(rf/reg-machine :auth.login/flow login-flow)
```

Now drive it. A machine is addressed by its id, and the event rides *inside* a wrapper:

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit]])
```

**What you see:** read the snapshot through a subscription and it has moved.

```clojure
@(rf/sub-machine :auth.login/flow)
;; => {:state :submitting :data {:attempts 0 :error nil}}
;;    (nil before the very first event — the machine starts itself on its first dispatch)
```

Dispatch `[:auth.login/flow [:auth.login/success]]` and the snapshot reads `{:state :authed …}`. Dispatch an event the current state has no transition for — `[:auth.login/flow [:auth.login/dismiss]]` while `:submitting` — and nothing happens: an unhandled event is a silent no-op, not a crash.

**Why this works.** A machine *is* an [event handler](../core/glossary.md#event-handler). `reg-machine` is sugar over `reg-event` whose body reads the snapshot, computes the transition from the table, and writes the new snapshot back. So the snapshot rides the [frame](../core/concepts/frames.md) like everything else, and you read it with the ordinary `[:rf/machine <id>]` subscription — same `dispatch`, same `subscribe`, no second runtime to learn.

## Step 2 — a guard: refuse an invalid submit

Right now *any* `:auth.login/submit` moves you to `:submitting`, even with an empty form. A **guard** is a yes/no test that gates a transition. Add one named guard and reference it from the arrow:

```clojure
(def login-flow
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

You name the guard once in `:guards`, then point at it by id — `:guard :form-valid?` — from every arrow that needs it.

**What you see:** submit empty credentials and the machine stays put; submit real ones and it moves.

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "" :password ""}]])
@(rf/sub-machine :auth.login/flow)     ;; => {:state :idle …}        (guard said no)

(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "secret"}]])
@(rf/sub-machine :auth.login/flow)     ;; => {:state :submitting …}
```

**Notice what the guard read.** It pulled the credentials out of `:event`, not out of [app-db](../core/concepts/app-db.md). A machine callback can't see app-db at all — only its own `:data` plus the event that woke it. A fact from the outside world arrives *in the event*; the view that has the form hands it over in the dispatch. That boundary is load-bearing — [Concepts](concepts.md) has the why.

## Step 3 — an action, and the `{:data :fx}` it returns

A guard decides *whether*; an **action** does the *work* a transition performs. But an action never performs a side-effect itself — it **returns a description**, the same `{:data :fx}` shape a `reg-event` handler returns:

- `:data` — updates *merged* into the machine's own `:data`, key by key (mention only what changes).
- `:fx` — the ordinary effects vector (`:dispatch`, HTTP, your own effects).

Add three actions and a second guard, and wire them in. `:clear-error` wipes the last error on submit; `:record-error` counts a failure into `:data`; `:store-session` fires an effect on success:

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

Two things arrived with the actions. The `:auth.login/failure` arrow is now a **list of candidates** — the runtime tries them top to bottom and takes the first whose guard passes. While under the retry limit, a failure records the error and shows it; once `:under-retry-limit` says no, the next candidate (no guard) wins and the machine locks out. And `:store-session` returns an `:fx` rather than calling `localStorage` itself — effects are data the runtime actions, exactly like in an event handler.

**What you see:** drive a failure and watch `:data` change.

```clojure
(rf/dispatch [:auth.login/flow [:auth.login/submit {:email "a@b.com" :password "x"}]])
(rf/dispatch [:auth.login/flow [:auth.login/failure {:failure {:message "nope"}}]])
@(rf/sub-machine :auth.login/flow)
;; => {:state :error-shown :data {:attempts 1 :error "nope"}}
```

Keep submitting and failing. The first three failures each land in `:error-shown` with `:attempts` climbing `1 → 2 → 3`. On the fourth, `:under-retry-limit` returns false, the guarded candidate is skipped, and the machine settles in `:locked-out`.

> **`:data` is a merge, not a replace.** `:clear-error` returns `{:data {:error nil}}` and only `:error` changes — `:attempts` is left alone. [Concepts](concepts.md) covers the sharp edges: an explicit `nil` *sets* a key (the merge never removes keys), and an action's `:fx` can't read the `:data` it's writing in the same breath.

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

> **Do, then observe.** Dispatch a submit with [Xray](../core/how-to/debug-with-xray.md) open. The request leaves; when the reply returns, the transition shows up as an ordinary event row — snapshot before and after — riding the same trace stream as everything else.

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

> **Why a tag, not a state name?** Add a sixth state that's also "busy" later — `:retrying`, say — and a view that branches on the `:auth/busy` tag picks it up with zero changes. Asking *what's true* scales; enumerating state names doesn't. [Tags](tags.md) is the whole pattern.

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

Feed in a snapshot and an event; assert on the snapshot that comes back. The result is a value — destructure `::result/snap` and `::result/fx`, or discriminate with `result/ok?` / `result/fail?` (a throwing action surfaces as a *failure value*, not an exception out of your test). These run on the JVM in microseconds, which is exactly the testing experience you want for the flows where testing usually gets hard.
