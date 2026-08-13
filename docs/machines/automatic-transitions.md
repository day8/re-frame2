# 4. Automatic transitions

<a id="automatic-transitions"></a>

The [first machine](tutorial.md) already uses one automatic form: `:after`
on `:submitting` cancels if the server stalls. This page is the rest of the
family.

Most transitions wait for a trigger from `dispatch`. Some triggers are
produced by the machine itself:

- a guard becomes true;
- a decision node resolves immediately;
- a delay expires;
- a deadline is missed.

re-frame2 has four authoring forms for this, built on two engines.

| Intent | Form | Engine |
|---|---|---|
| "Whenever this condition holds, move." | `:always` | guard-driven microstep loop |
| "Enter a decision node and immediately route." | `:type :choice` + `:choice` | desugars to `:always` |
| "After N ms in this state, move." | `:after` | wall-clock timer |
| "This state or child must finish in time." | `:timeout` + `:on-timeout` | desugars to `:after` |

## Eventless `:always`

`:always` is checked after a state is entered and after transitions that remain in, or land in, that state.

Login can skip the form when a session token is already in `:data`:

```clojure
:guards
{:has-session?
 (fn [{data :data}]
   (some? (:token data)))}

:idle
{:always [{:guard  :has-session?
           :target :authed}]
 :on     {:auth.login/submit {:target :submitting
                              :guard  :form-valid?
                              :action :clear-error}}}
```

Birth lands on `:idle`. If `:data` already has a token (hydration, a restore
event), `:always` moves to `:authed` in the same macrostep. External
observers see the settled result, not the hop through `:idle`.

The same form works as a counter that trips a threshold. A targetless
`:on` updates `:data`; then `:always` is checked:

```clojure
:asking
{:always [{:guard :enough? :target :winner}]
 :on     {:answer-correct {:action :count-correct}
          :answer-wrong   :loser}}
```

## Run to completion

A machine processes one event to a stable configuration before the next event is observed.

Inside that one macrostep, the runtime:

1. takes the event-driven transition;
2. applies exit/action/entry effects;
3. checks `:always`;
4. drains any `:raise`d internal events;
5. repeats until no `:always` is enabled and no raised event remains;
6. commits the final snapshot once.

The loop is bounded. The default depth limit is 16. A runaway `:always` or `:raise` cycle raises `:rf.error/machine-always-depth-exceeded` and aborts the macrostep atomically; the previous snapshot remains visible.

## `:always` rules

`:always` takes a candidate vector:

```clojure
:resolving
{:always [{:guard :empty?    :target :empty}
          {:guard :too-many? :target :too-many}
          {:target :some}]}
```

The first candidate whose guard passes wins. Include an unguarded default when the state must always resolve.

An `:always` transition may be targetless:

```clojure
:draining
{:always [{:guard :has-more?
           :action :drain-one}]}
```

This is the safe "loop until done" pattern. The action changes `:data`; once the guard becomes false, the loop settles.

An `:always` transition may not target its own declaring state. That shape either loops forever or does nothing useful, so `reg-machine` throws `:rf.error/machine-always-self-loop`.

## Choice states

A choice state is a named decision node. The machine enters it and immediately leaves through the first passing candidate.

The first machine's failure candidate list can be written as a choice instead:

```clojure
:submitting
{:on {:auth.login/failure {:target :decide-failure
                           :action :record-error}}}

:decide-failure
{:type   :choice
 :choice [{:guard  :under-retry-limit
           :target :error-shown}
          {:target :locked-out}]}
```

A smaller decision node looks the same:

```clojure
:checking
{:type   :choice
 :choice [{:guard :valid? :target :accepted}
          {:target :rejected}]}
```

It is equivalent in behaviour to an `:always` decision, but it communicates intent to readers and diagram tools.

Choice rules:

- `:type :choice` and `:choice` must appear together.
- `:choice` is a non-empty vector of transition candidates.
- The vector must include an unguarded default.
- A choice state only routes; it does not also declare `:on`, `:entry`, `:after`, `:spawn`, and so on.
- The topology stays data. A function-valued `:choice` fails at registration with `:rf.error/machine-bad-choice`.

## Delayed `:after`

`:after` maps a delay to a transition. Entering the state arms the timer. Leaving the state cancels it.

The [first machine](tutorial.md#step-4--talk-to-a-real-server) uses an 8-second `:after` as a server deadline. The same key works for any wall-clock wait:

```clojure
(rf/reg-machine :boot
  {:initial :splash
   :states
   {:splash {:after {3000 :main}
             :on    {:skip :main}}
    :main   {}}})
```

The transition value uses the same grammar as `:on`:

```clojure
:loading
{:after {30000 {:target :timeout
                :guard  :still-loading?
                :action :record-timeout}}
 :on    {:loaded :ready
         :failed :error}}
```

If the guard is false when the timer fires, the timer is discarded and the snapshot does not move.

## Delay forms

An `:after` delay can be:

```clojure
30000
```

A positive integer, in milliseconds. Not an ISO-8601 string — those belong to `:timeout` below.

```clojure
[:settings/login-timeout-ms]
```

A subscription vector. The delay re-resolves while the state is active. If the subscription value changes, the timer restarts from now.

```clojure
(fn [{:keys [snapshot]}]
  (* 1000 (-> snapshot :data :retry-count)))
```

A function, evaluated once when the state is entered. It does not re-resolve. Delay functions receive `{:snapshot …}`, not the usual guard/action context (`{:data :event :state :meta}`).

## Timer staleness

You do not cancel `:after` timers yourself.

Every timer carries the state-entry epoch that armed it. When it fires, the runtime checks whether that epoch is still current. If the state has been exited or re-entered, the timer is stale and ignored.

This avoids the usual `setTimeout` plus cancel-flag bug. A late timer from a previous visit cannot move the current state.

## Several timers can race

```clojure
:loading
{:after {5000  :slow-warning
         30000 :timeout}
 :on    {:loaded :ready}}
```

Both timers count from state entry. If `:loaded` arrives before either, leaving the state cancels both. If the 5 second timer fires, it takes its transition; if that transition exits the state, the 30 second timer is cancelled.

Do not rely on declaration order to break a same-tick tie. Host scheduling decides which timer event arrives first.

## Exponential backoff

```clojure
:reconnecting
{:after {(fn [{:keys [snapshot]}]
           (let [{:keys [retries base-ms max-backoff-ms]} (:data snapshot)]
             (min (* base-ms (Math/pow 2 retries)) max-backoff-ms)))
         {:target :connecting}}   ;; cf. examples/patterns/websocket
 :on    {:give-up :failed}}
```

Each visit to `:reconnecting` computes a fresh delay from the current snapshot.

For recurring timers, re-enter the state. There is no separate recurring-timer primitive.

## SSR

On the server, `:after` does not run wall-clock timers. The server renders the current state. The client re-arms timers after hydration.

Design SSR-visible states so they are meaningful without depending on a timer firing server-side.

## `:timeout` and `:on-timeout`

Use `:timeout` when the intent is a deadline.

```clojure
:waiting
{:timeout    "PT5S"
 :on-timeout {:target :timed-out}}
```

The pair lowers onto the same timer mechanism as `:after`.

It also works on a [spawn](glossary.md#spawn) spec:

```clojure
:authenticating
{:spawn {:machine-id :auth/request
         :timeout    "PT10S"
         :on-timeout {:target :auth-failed}}}
```

`:timeout` on a spawn spec is valid. The retired `:timeout-ms` slot is not — `reg-machine` throws `:rf.error/spawn-timeout-ms-removed`.

When the timeout fires, the parent state exits, and the spawned child is destroyed as part of the normal exit cascade.

`:timeout` requires `:on-timeout`, and `:on-timeout` requires `:timeout`.

## Timeout durations

A timeout duration is one of:

```clojure
5000
```

Positive integer milliseconds.

```clojure
"PT5S"
"PT1H30M"
"PT0.5S"
```

An ISO-8601 duration string.

Readable shorthands such as `"5s"` or `"10ms"` are not accepted, nor are subscription vectors or delay functions. A bad duration fails at registration with `:rf.error/machine-bad-timeout-duration`. Use integer milliseconds or ISO-8601.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Registration throws `:rf.error/machine-always-self-loop` | `:always` targets its own declaring state | Use a targetless `:always` with an action that flips the guard, or target a different state |
| Macrostep fails `:rf.error/machine-always-depth-exceeded` | Eventless loop did not settle within 16 steps | Break the cycle; a targetless drain-until-false is the safe loop |
| Registration throws `:rf.error/machine-bad-choice` | `:choice` is a function, empty, or missing a default | Declarative non-empty vector with an unguarded last candidate |
| Timer fired but the snapshot did not move | Guard was false at expiry, or the state had already been left | Expected. A late timer is stale; a false guard discards that firing |
| Registration throws `:rf.error/machine-bad-timeout-duration` | `"5s"` shorthand, or a non-positive / malformed duration | Integer milliseconds or ISO-8601 (`"PT5S"`) |
| Registration throws `:rf.error/spawn-timeout-ms-removed` | `:timeout-ms` on a spawn spec | Use `:timeout` + `:on-timeout`, or `:after` on the parent state |
