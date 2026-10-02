# Automatic transitions

<a id="4-automatic-transitions"></a>

<a id="automatic-transitions"></a>

Give a request a deadline with `:after`, or let the machine continue without
another event when a guard already has enough information. Both forms use
the same transition maps and candidate vectors as `:on`.

## Delayed `:after`

The login flow should leave `:submitting` if the server has not replied after
eight seconds. Its deadline takes the same guarded candidates as a failure:

```clojure
;; The :after slot on the tutorial's :submitting state.
;; Keep that state's existing :entry, :tags and :on slots.
:after {8000 [{:target :error-shown
               :guard :under-retry-limit
               :action :record-timeout}
              {:target :locked-out
               :action :record-timeout}]}
```

Entering the state arms the timer. Leaving it cancels the timer, so a reply
that reaches `:authed` or `:error-shown` first prevents the deadline from
moving the machine later. You do not cancel it yourself.

A delay is normally a positive integer in milliseconds; `"PT8S"` is an
ISO-8601 spelling of the same duration. If a transition's guard is false
when the timer fires, that firing is discarded and the state waits for an
event. [The first machine](tutorial.md#step-4--talk-to-a-real-server) includes
the request, timeout action and retry guard.

## `:timeout` and `:on-timeout`

Use this pair when a named deadline reads better than an `:after` entry:

```clojure
;; Alternative deadline slots on :submitting.
:timeout "PT8S"
:on-timeout [{:target :error-shown
              :guard :under-retry-limit
              :action :record-timeout}
             {:target :locked-out
              :action :record-timeout}]
```

The pair uses the same timer mechanism. Supply both keys, and choose either
this pair or the equivalent `:after` entry. A duplicate delay on the same
state throws `:rf.error/machine-timeout-after-collision`. A
[spawn deadline](actors.md#timeouts) uses this pair to bound a child's whole
lifetime.

## Eventless `:always`

`:always` is checked after a state is entered and after transitions that remain in, or land in, that state.

Login can skip the form when startup has established that the saved session
is valid. Put that fact in the machine's initial `:data` as
`:session-valid? true`, and let a guard read it:

```clojure
:guards
{:has-session?
 (fn [{data :data}]
   (true? (:session-valid? data)))}

:idle
{:always [{:guard  :has-session?
           :target :authed}]
 :on     {:auth.login/submit {:target :submitting
                              :guard  :form-valid?
                              :action :clear-error}}}
```

At birth, if the initial `:data` says the session is valid, `:always` moves from
`:idle` to `:authed` before the snapshot commits. Observers see the settled
result. Installing a persisted snapshot does not run `:always`; send a normal
event afterward if the restored machine needs to make a new decision.

The machine settles these transitions before publishing the snapshot. The
view sees `:authed`, with no intermediate `:idle` render. Guards are checked
when the machine handles a trigger; nothing watches them between events.

## Choice states

A choice state is a named decision node. The machine enters it and immediately leaves through the first passing candidate.

The first machine's failure candidate vector can be written as a choice instead. `:record-error` stays on the way in, where it can still read the failure message off the event, so the choice reads the *incremented* `:attempts`:

```clojure
:guards
{:retries-left?
 (fn [{data :data}]
   (< (:attempts data) 3))}

:submitting
{:on {:auth.login/failure {:target :decide-failure
                           :action :record-error}}}

:decide-failure
{:type   :choice
 :choice [{:guard  :retries-left?
           :target :error-shown}
          {:target :locked-out}]}
```

The tutorial's `:under-retry-limit` guard is `(< (:attempts data) 2)` because it runs *before* `:record-error`. `:retries-left?` runs after, so it compares against 3. Both lock out on the third failure.

Use a named choice when that decision deserves its own node in the table.
A choice takes a non-empty candidate vector with an unguarded default; it
routes immediately and does not also handle events or perform entry work.
The [transition reference](../api/re-frame.machines.md#transitions) records
the exact forms.

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| Registration throws `:rf.error/machine-always-self-loop` | `:always` targets its own declaring state | Use a targetless `:always` with an action that flips the guard, or target a different state |
| Registration throws `:rf.error/machine-bad-choice` | `:choice` is a function, empty, or otherwise not a candidate vector | Declarative non-empty vector of candidate maps |
| Registration throws `:rf.error/machine-choice-no-default` | Every `:choice` candidate is guarded | End the vector with an unguarded candidate |
| A choice or `:always` candidate read `nil` where the payload should be | An eventless step runs with no event | Read the payload on the event-driven transition and store it in `:data` |
| A retry limit trips one failure early after the count moved into a choice | The entering action already incremented the count the choice's guard reads | Compare against the post-action number |
| Timer fired but the snapshot did not move | Guard was false at expiry, or the state had already been left | Expected. A late timer is stale; a false guard discards that firing |
| Registration throws `:rf.error/machine-bad-timeout-duration` | `"5s"` shorthand, or a non-positive / malformed duration | Integer milliseconds or ISO-8601 (`"PT5S"`) |
| Registration throws `:rf.error/spawn-timeout-ms-removed` | `:timeout-ms` on a `:spawn` or `:spawn-all` | Use `:timeout` + `:on-timeout`, or `:after` on the parent state |

An `:always` loop that never settles fails with
`:rf.error/machine-always-depth-exceeded`; see
[The table → Troubleshooting](concepts.md#troubleshooting).
## Advanced

### Run to completion

A machine processes one event to a stable configuration before the next event is observed.

Inside that one macrostep, the runtime:

1. takes the event-driven transition;
2. calls exit, transition and entry actions, accumulating their data updates
   and effect descriptions;
3. checks `:always`;
4. handles the next raised internal event, then settles `:always` again;
5. repeats until no `:always` is enabled and no raised event remains;
6. commits the final snapshot once, then executes the accumulated ordinary
   effects in order.

Raised events form a FIFO queue. An HTTP request or dispatched event described
by an action does not run in the middle of this calculation.

The loop is bounded. The default depth limit is 16. A runaway cycle raises `:rf.error/machine-always-depth-exceeded` (eventless) or `:rf.error/machine-raise-depth-exceeded` (`:raise`) and aborts the macrostep atomically; the previous snapshot remains visible.

### `:always` rules

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

This is the safe "loop until done" pattern. The action changes `:data`; once the guard becomes false, the loop settles. The guard is what ends it: an `:always` with neither `:guard` nor `:target` never settles, and `reg-machine` refuses it (`:rf.error/machine-always-unguarded-targetless`). Run-once work belongs in `:entry`.

An `:always` transition may not target its own declaring state. That shape either loops forever or does nothing useful, so `reg-machine` throws `:rf.error/machine-always-self-loop`.

An `:always` step runs with no event, so its guards and actions receive `:event` as `nil`. Anything that reads a trigger payload belongs on the event-driven transition; put the result in `:data` and let the `:always` guard read that.

Nothing watches the guard between macrosteps. A `:data` change made outside one — a [`:spawn-all` child's `:on-done` fold](fan-out-and-join.md#the-resolution-event) is the case to know — moves nothing until the next event.

### Raise and internal events

Raise an event when an action has produced information that another part of
the table should handle before the snapshot becomes visible:

```clojure
(rf/reg-machine :auth.session/check
  {:initial :idle
   :data {:session-valid? false}
   :internal-events #{:auth.session/check-session}
   :guards {:has-session? (fn [{:keys [data]}] (true? (:session-valid? data)))}
   :actions
   {:remember-session
    (fn [{[_ valid?] :event}]
      {:data {:session-valid? valid?}
       :fx [[:raise [:auth.session/check-session]]]})}
   :states
   {:idle {:on {:auth/restore {:action :remember-session}
                :auth.session/check-session [{:guard :has-session? :target :authed}]}}
    :authed {}}})
```

Dispatching `[:auth.session/check [:auth/restore true]]` first stores the
session fact, then handles `:auth.session/check-session` against the updated data. The view
sees `:authed`. With `:dispatch` instead, those would be separate events and
separate commits. In a parallel machine, a raised event reaches every region.

`:internal-events` is optional: it restricts which triggers outsiders may
send, not which ones an action may raise. An external dispatch of a listed id
reports `:rf.error/machine-internal-event-external-dispatch` and changes
nothing. A raise is exactly `[:raise event-vec]`; a third options element
throws `:rf.error/machine-bad-raise`. Use `:after` for a delayed trigger.

### Delay forms

An `:after` delay can be:

```clojure
30000
```

A positive integer, in milliseconds, or an ISO-8601 duration string such as `"PT30S"`. A `"5s"` shorthand is refused (`:rf.error/machine-bad-after-delay`).

```clojure
[:settings/login-timeout-ms]
```

A subscription vector. The delay re-resolves while the state is active. If the subscription value changes, the timer restarts from now.

```clojure
(fn [{:keys [snapshot]}]
  (* 1000 (-> snapshot :data :retry-count)))
```

A function, evaluated once when the state is entered. It does not re-resolve. Delay functions receive `{:snapshot …}`, not the usual guard/action context (`{:data :event :state :meta}`).

A subscription or function delay that throws, or resolves to anything but a positive number, arms no timer, so the state waits for an event instead. A throw is reported as `:rf.error/machine-after-sub-threw` or `:rf.error/machine-after-fn-threw`, and every skipped timer as `:rf.error/machine-bad-after-delay` with `:recovery :skipped`.

### Timer staleness

You do not cancel `:after` timers yourself.

Every timer carries the state-entry epoch that armed it. When it fires, the runtime checks whether that epoch is still current. If the state has been exited or re-entered, the timer is stale and ignored.

This avoids the usual `setTimeout` plus cancel-flag bug. A late timer from a previous visit cannot move the current state.

### Several timers can race

```clojure
:loading
{:after {5000  :slow-warning
         30000 :timeout}
 :on    {:loaded :ready}}
```

Both timers count from state entry. If `:loaded` arrives before either, leaving the state cancels both. If the 5 second timer fires, it takes its transition; if that transition exits the state, the 30 second timer is cancelled.

Do not rely on declaration order to break a same-tick tie. Host scheduling decides which timer event arrives first.

### Exponential backoff

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

### SSR

On the server, `:after` does not run wall-clock timers. The server renders the current state. The client re-arms timers after hydration. Each re-armed timer gets its **full** delay, counted from the moment the client arms it — never the remainder of a countdown, because no countdown ever started on the server.

Design SSR-visible states so they are meaningful without depending on a timer firing server-side.

### Timeout durations

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
