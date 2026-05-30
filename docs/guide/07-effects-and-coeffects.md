# 07 - Effects and coeffects

You want to talk to the outside world without letting the outside world move into your event handlers and start rearranging the furniture. This chapter teaches effects and coeffects: named outputs and named inputs that keep impurity at the boundary while the handler remains explainable.

A `reg-event-fx` handler receives coeffects and returns effects.

```clojure
(rf/reg-event-fx :session/start
  [(rf/inject-cofx :app/now)]
  (fn [{:keys [db app/now]} [_ user-id]]
    {:db (assoc db :session {:user-id user-id
                             :started-at app/now})
     :fx [[:analytics/track {:event :session/start
                             :user-id user-id}]]}))
```

The handler did not call analytics. It described an analytics effect.

## Register effects

```clojure
(rf/reg-fx :analytics/track
  (fn [{:keys [event user-id]}]
    (.log js/console "track" (name event) user-id)))
```

Every side effect gets a name. Tests can override that name. Story can stub that name. Xray can show that name. The network is not special; it is just one effect among others.

## Register coeffects

```clojure
(rf/reg-cofx :app/now
  (fn [ctx]
    (assoc-in ctx [:coeffects :app/now] (js/Date.now))))
```

Coeffects are inputs from the world: current time, local storage, request context on the server, maybe a seeded test value. Inject them through interceptors instead of calling the world from the handler body.

## Override effects in tests

```clojure
(rf/with-fx-overrides {:analytics/track (fn [args] (swap! seen conj args))}
  (rf/dispatch-sync [:session/start 42]))
```

That is not a fake framework. It is the same runtime, with one named effect swapped for the duration of the body.

## Reading a subscription from a handler

Handlers should usually read `db` directly. Occasionally a handler needs the value of a registered subscription because that subscription already expresses the canonical derivation and duplicating it would be worse.

The disciplined way is cofx-wrapping: register or use a cofx that computes the subscription value and injects it into the handler's coeffects map. The handler still receives inputs; it does not secretly subscribe from the middle of its body.

```clojure
(rf/reg-event-fx :invoice/send
  [(rf/inject-cofx :invoice/total)]
  (fn [{:keys [db invoice/total]} _]
    {:db (assoc db :invoice/sending? true)
     :fx [[:invoice.api/send {:total invoice/total}]]}))
```

The rule is simple: if the handler needs an input other than `db` and the event vector, make that input explicit in coeffects.

## When the ceremony isnt worth it--the inline escape hatch

The registry is the canonical path because names make code searchable, testable, and overrideable. But sometimes a value is local, one-off, and never worth registering.

In that case an inline interceptor is acceptable:

```clojure
(rf/reg-event-fx :clock/demo
  [{:id :clock/inline-now
    :before (fn [ctx]
              (assoc-in ctx [:coeffects :clock/now] (js/Date.now)))}]
  (fn [{:keys [clock/now]} _]
    {:fx [[:console/log clock/now]]}))
```

Use this sparingly. The moment another handler wants the same value, promote it to `reg-cofx` and give it a real id.

## Pitfall: doing side effects inline

Inline `js/fetch` inside a handler is the beginning of a debugging folklore tradition. The callback closes over stale state, the test has to mock globals, Story cannot replay it honestly, and Xray sees less than it should.

Return an effect map. Make the runtime do the dirty work in the one place where dirty work is allowed.
