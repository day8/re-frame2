# 04 - Events and the cascade

You clicked a button and now you want to know what actually happened, not in the vague "React updated" sense, but in the accountable, step-by-step sense. This chapter walks one event through the cascade so the rest of re-frame2 has somewhere solid to stand.

An event is a vector. The first element is the id; the rest is the payload.

```clojure
[:cart/add {:sku "A" :qty 1}]
```

Prefer one payload map once there is more than one argument. Your future self will thank you when the event grows a third field and you do not have to remember whether position two meant `sku` or `coupon`.

## The cascade

A normal dispatch follows this path:

1. A view calls `dispatch` with an event vector.
2. The event enters the frame queue.
3. The runtime looks up the registered handler.
4. Interceptors build coeffects, focus paths, validate, or observe.
5. The handler returns a new db or an effect map.
6. The runtime commits `:db`, runs named effects, invalidates subscriptions, and views re-render.

That list is the framework in miniature. If a bug appears, locate the step. Did the event get dispatched? Did the handler run? Did it return the state you expected? Did an effect fire? Did the subscription derive the right value? Did the view render it?

## `reg-event-db` and `reg-event-fx`

Use `reg-event-db` when the handler only transforms state.

```clojure
(rf/reg-event-db :cart/add
  (fn [db [_ {:keys [sku qty]}]]
    (update-in db [:cart :items sku :qty] (fnil + 0) qty)))
```

Use `reg-event-fx` when the handler must describe side effects.

```clojure
(rf/reg-event-fx :cart/save
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:cart :saving?] true)
     :fx [[:rf.http/managed {:request {:method :post
                                        :url "/api/cart"
                                        :body (:cart db)}
                            :on-success [:cart/save-ok]
                            :on-failure [:cart/save-failed]}]]}))
```

The handler still does not perform HTTP. It returns data saying what should happen. That distinction is the hinge of the architecture.

## `dispatch` versus `dispatch-sync`

Use `dispatch` for ordinary user work. It queues an event and lets the runtime order things.

Use `dispatch-sync` at boot and in tests, where you need the event fully settled before the next line runs. If you reach for it inside normal UI flow, pause. You are probably trying to use sequencing as a substitute for modelling state.

## Pitfall: doing work in the click handler

This is the smell:

```clojure
[:button {:on-click #(do
                       (swap! some-atom conj item)
                       (js/fetch "/api/cart"))}
 "Save"]
```

The click handler now owns state and effects. It has become a tiny unregistered framework. re-frame2's answer is deliberately dull: dispatch an event, register the handler, return effects as data. Dull is good. Dull lets tools see the program.
