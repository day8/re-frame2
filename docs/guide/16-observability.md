# 16 - Observability

You want to know what happened after the fact, because production bugs do not politely reproduce while you watch. This chapter teaches the two observability surfaces: trace events for fine-grained facts, and epoch records for committed cascades you can compare, replay, and explain.

A trace event is a structured fact: a dispatch, handler run, schema failure, effect request, subscription run, render, or tool-visible issue. An epoch record is the assembled story of one committed event: db before, db after, effects, sub runs, renders, and trace events.

## Trace is not logging with better typography

Logs are strings. Trace events are data. The difference is whether tools can ask questions without regex archaeology.

```clojure
(rf/register-event-listener!
  :audit
  (fn [event]
    (when (= :rf.event/dispatch (:op event))
      (js/console.log "dispatch" (:event event)))))
```

The listener sees structured events. Xray sees the same kind of structure. Story sees the same structure. Pair tools see the same structure. That sameness is the whole point.

## Epochs are the debugging spine

An epoch says: this event committed this state change and emitted this evidence. That makes it the natural unit for time travel, deterministic replay, semantic diffs, Story test failures, and "show me what caused this assertion to fail."

## Redaction belongs at egress

Trace is powerful because it can carry real evidence. That also makes it dangerous. Sensitive and large values must be elided before they leave the trust boundary. Schema marks and runtime elision policy exist so tools can be useful without becoming data exfiltration machines.

## Pitfall: adding a second observability surface

Do not build a separate private logger for your feature unless you truly need one. If the runtime already emits the event you need, consume it. If it does not, consider whether the runtime should emit a structured trace event. Two diagnostic stories are how tools drift into telling different lies.
