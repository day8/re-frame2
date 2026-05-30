# 10 - HTTP

You want to fetch data without building a tiny bespoke networking framework under every button. This chapter teaches the managed HTTP shape: requests are effects, replies are events, retries and failure states are modelled, and the handler stays a pure description of intent.

The basic shape is an effect row.

```clojure
:fx [[:rf.http/managed {:request {:method :get
                                  :url "/api/articles"}
                        :on-success [:articles/load-ok]
                        :on-failure [:articles/load-failed]}]]
```

The handler asks for a request. The HTTP effect executes it. The reply returns to the event loop as data.

## Model remote data explicitly

Do not model network state as `:loading? true` plus a nullable response plus some hope. Give it a shape.

```clojure
{:articles {:status :idle
            :items []
            :error nil}}
```

Then every event is obvious.

```clojure
(rf/reg-event-fx :articles/load
  (fn [{:keys [db]} _]
    {:db (assoc-in db [:articles :status] :loading)
     :fx [[:rf.http/managed {:request {:method :get :url "/api/articles"}
                             :on-success [:articles/load-ok]
                             :on-failure [:articles/load-failed]}]]}))

(rf/reg-event-db :articles/load-ok
  (fn [db [_ items]]
    (assoc db :articles {:status :success :items items :error nil})))

(rf/reg-event-db :articles/load-failed
  (fn [db [_ error]]
    (assoc db :articles {:status :failure :items [] :error error})))
```

The UI can now render the four honest states: idle, loading, success, failure. No psychic booleans.

## Decode at the edge

Attach schemas to response decoding where possible. The moment bytes become app data is the moment to prove they have the shape you think they have. If the server returns garbage, you want a structured failure at the HTTP boundary, not a broken table three render passes later.

## Stubbing HTTP

Because HTTP is an effect, tests and Story variants can override it. A unit test can record the outgoing request. A Story variant can make `/api/articles` succeed, fail, or hang without a custom browser mocking story.

## Pitfall: callback thinking

The callback style says: fetch, then mutate state from the callback. The re-frame2 style says: request as effect, reply as event. The second style is more typing on day one and dramatically less archaeology on day three hundred.
