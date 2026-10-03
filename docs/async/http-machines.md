# HTTP from a machine

An article request may belong to a screen's loading state: leaving that state
should stop the request. Spawn `:rf.http/managed` from the state instead of
issuing a separate effect and arranging cancellation yourself.

The machine lives in an app namespace that requires both artefacts:

```clojure
;; src/app/article_request.cljc
(ns app.article-request
  (:require [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.http.managed]))
```

The cell registers the machine and runs it against a stubbed article URL.
**Load** sends the machine `[:article/load]`:

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])

(http-test-support/install-managed-request-stubs!
  {[:get "/api/articles/intro"] {:reply {:ok {:slug "intro" :title "Welcome"}}}})

(rf/reg-machine :article/request
  {:initial :idle
   :data    {:article nil}
   :states
   {:idle {:on {:article/load :loading}}

    :loading
    {:spawn {:machine-id :rf.http/managed
             :data       {:request {:url "/api/articles/intro"}
                          :decode :json}
             :on-error   :failed}
     :on    {:succeeded
             {:target :ready
              :action (fn [{data :data [_ value] :event}]
                        {:data (assoc data :article value)})}
             :failed         :failed
             :article/cancel :idle}}

    :ready  {:on {:article/load :loading}}
    :failed {:on {:article/load :loading}}}})

(rf/reg-view article-request-view []
  (let [{:keys [state data]} (or @(subscribe [:rf/machine :article/request])
                                 {:state :idle :data {:article nil}})]
    [:div
     [:button {:on-click #(dispatch [:article/request [:article/load]])} "Load"]
     [:p "state: " (str state)]
     [:p "article: " (pr-str (:article data))]]))

;; :fx-overrides sends the frame's requests, the child's included, to the stub.
;; A real app leaves it out.
[rf/frame-root {:id :app/articles :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [article-request-view]]
```

Add `day8/re-frame2-machines` and `day8/re-frame2-http` to the app's dependencies.
Requiring both namespaces registers the HTTP child machine. The parent's
[`:spawn`](../machines/actors.md#state-bound-spawn) starts it on entry to
`:loading`; a successful completion sends `[:succeeded value]`. Its action stores the
decoded article in the parent's `:data` and enters `:ready`.
Failure enters `:failed`; `:on-error` also handles a child that fails to start.

The stub answers at once, so the cell moves from `:idle` straight to `:ready`.
In the tutorial's mounted frame, start the machine from the REPL with:

```clojure
(rf/dispatch [:article/request [:article/load]] {:frame :app/articles})
```

Read its state and data through the ordinary machine subscription,
`[:rf/machine :article/request]`. The result stays in machine `:data`; it does
not update the tutorial's `[:article]` app-db slice automatically.

## The child addresses its own reply

The child's `:data` accepts the HTTP args map, including decoding, retries and
per-attempt timeout. Leave out `:reply-to`, `:on-success` and `:on-failure`: the
child routes its own reply and reports its decoded value or failure to the parent.
It rejects those keys even when their value is `nil`, with
`:rf.error/http-bad-reply-target`; the parent's `:spawn :on-error` handles that
startup failure.

A single child reports `[:succeeded value]` or `[:failed failure]`, rather than
the event-effect form's full reply map. A child's `:on-done` fold reads the same
successful `value` as `result`.

## Cancellation

While the request is loading, cancel it with:

```clojure
(rf/dispatch [:article/request [:article/cancel]] {:frame :app/articles})
```

Any transition out of `:loading` destroys its child and aborts the child's HTTP
work. That includes cancel, successful completion, failure and parent teardown.
The cancelled child's reply is suppressed; the transition to `:idle` clears the
loading state without waiting for another event.

This also applies when you issue an HTTP effect directly from a spawned actor.
A reply addressed back to the destroyed actor is suppressed. A reply addressed
to an ordinary event in the surviving frame receives `:status :cancelled` with
`:reason :actor-destroyed` under `:error`. Frame destruction or epoch restore
suppresses either address. Put cleanup that must run during teardown in the
actor's [exit action](../machines/actors.md#cancellation).

## Load the article and comments together

When the screen needs two independent responses before it can render, replace
`:loading` above with a `:spawn-all` state. Each child handles one HTTP request;
the parent becomes ready after both succeed:

```clojure
;; cf. examples/patterns/boot/boot.cljs — child result folds and a :join :all
:loading
{:spawn-all
 {:children
  [{:id :article
    :machine-id :rf.http/managed
    :data {:request {:url "/api/articles/intro"} :decode :json}
    :on-done (fn [{:keys [data result]}] (assoc data :article result))}
   {:id :comments
    :machine-id :rf.http/managed
    :data {:request {:url "/api/articles/intro/comments"} :decode :json}
    :on-done (fn [{:keys [data result]}] (assoc data :comments result))}]
  :join            :all
  :on-all-complete [:article/ready]
  :on-any-failed   [:article/load-failed]}
 :on {:article/ready       :ready
      :article/load-failed :failed
      :article/cancel      :idle}}
```

Each `:on-done` folds its child's result into the parent's `:data`. Under
`:spawn-all`, HTTP children finish through the join; they do not send the
single-child `:succeeded` or `:failed` events. One failure moves the parent to
`:failed` and cancels any remaining child. Leaving `:loading` also cancels them.
The [join guide](../machines/fan-out-and-join.md) covers other join policies.

## When to keep event handlers

Use the [effect form](http.md) for a request with a normal receiving event.
If one request needs another's result, its success handler can issue the next
request. Introduce a machine when explicit states, branching or cancellation
make that sequence easier to follow. Use [resources](../resources/index.md)
when several screens need the same cached article.
