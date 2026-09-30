# Your own async effect

Wrap a non-HTTP host API in a registered effect. The event handler describes the
work; the effect calls the host API and dispatches its result as another event.
For example, an article screen can copy its text to the browser clipboard.

## Wrapping a promise

```clojure
;; src/app/article_effects.cljs
(ns app.article-effects
  (:require [re-frame.core :as rf]))

(rf/reg-fx :clipboard/write
  {:doc "Copy text and dispatch the result."
   :platforms #{:client}}
  (fn [{:keys [frame]} {:keys [text reply-to]}]
    (-> (js/Promise.resolve nil)
        ;; A missing clipboard API can throw synchronously. Starting inside
        ;; .then sends that throw through the same path as a rejected promise.
        (.then (fn [_] (.writeText (.-clipboard js/navigator) text)))
        (.then (fn [_]
                 (rf/dispatch (conj reply-to {:status :ok}) {:frame frame}))
               (fn [error]
                 (rf/dispatch
                   (conj reply-to {:status :error
                                   :error {:message (.-message error)}})
                   {:frame frame}))))))

(rf/reg-event :article/copy
  (fn [{:keys [db]} _]
    (let [token (inc (get db :article/copy-token 0))]
      {:db (assoc db :article/copy-token token :article/copy-status :copying)
       :fx [[:clipboard/write
             {:text (get-in db [:article :data :body])
              :reply-to [:article/copied token]}]]})))

(rf/reg-event :article/copied
  (fn [{:keys [db]} [_ token reply]]
    (if (= token (:article/copy-token db))
      {:db (assoc db
                  :article/copy-status (if (= :ok (:status reply)) :copied :error)
                  :article/copy-error (get-in reply [:error :message]))}
      {})))
```

The effect captures `:frame` before its promise callbacks run. Each callback
passes that frame to `rf/dispatch`; a bare deferred dispatch has no frame context
and reports `:rf.error/no-frame-context`. The reply handler receives the current
app-db and updates it through `:db`, as any other event handler does.

Here `:reply-to` and the reply map are our effect's own protocol. `reg-fx` does not
add managed HTTP's decoding, retries, cancellation or stale-reply suppression.
Using the familiar `:status` shape makes the handler easy to read without implying
that those behaviors come with it.

## Guarding replies and handling failure

Each copy increments a token. Both success and failure carry it back, and the
reply handler ignores an obsolete token. If leaving the article should also
invalidate a pending copy, increment the token in that navigation event. This
protects app state; it cannot undo a clipboard write that the browser accepted.

The [clipboard API](https://www.w3.org/TR/clipboard-apis/#async-clipboard-api)
needs a secure browser context, and the browser may reject the write because
permission or user activation is missing. Dispatch the copy event
from the user's click and display the failure state if the write is refused.
Do not retry it automatically.

Use this pattern for a promise-based SDK too. For a callback API, dispatch from
its completion callback with the same frame and token. Translate host objects
into the plain data your reply handler needs before dispatching them.

## Testing it

A [handler test](../core/testing/event-handlers.md) can assert on the issuing
handler's `:fx`. A [pipeline test](../core/testing/pipeline-runs.md#redirect-any-effect-fx-overrides)
can replace the clipboard effect and exercise the reply without accessing the
browser clipboard:

```clojure
;; test/app/article_effects_test.cljs
(ns app.article-effects-test
  (:require [cljs.test :refer-macros [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as ts]
            [app.article-effects]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest copies-and-ignores-an-old-reply
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/dispatch-sync
      [:article/copy]
      {:fx-overrides
       {:clipboard/write
        (fn [{:keys [frame]} {:keys [reply-to]}]
          (rf/dispatch (conj reply-to {:status :ok}) {:frame frame}))}})
    (is (= :copied (:article/copy-status (rf/app-db-value f))))
    ;; The current token is 1; a late failure for token 0 must not replace it.
    (rf/dispatch-sync [:article/copied 0
                       {:status :error :error {:message "Old failure"}}])
    (is (= :copied (:article/copy-status (rf/app-db-value f))))))
```

The override tests your event protocol and token guard. Exercise the actual
clipboard call in a browser to check permission and user-activation behavior.

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `:rf.error/no-such-fx` naming your effect | Its registration namespace was not loaded. Require it at boot. |
| `:rf.error/no-frame-context` from a callback | Pass the captured `:frame` to every deferred dispatch. |
| `:rf.error/fx-handler-exception` | The effect threw before arranging its reply. No reply is dispatched; the event's `:db` write and other effects still apply. Catch expected host failures and dispatch an error reply. |
| `:rf.fx/skipped-on-platform` | `:platforms` excludes the current host, so no work or reply occurs. Start this browser-only effect from browser interaction. |
| `:rf.error/frame-destroyed` | A callback dispatched after its frame was destroyed. Its state update is dropped; custom effects must arrange any host cleanup themselves. |

## When to use another mechanism

Use [managed HTTP](http.md) for HTTP requests. For a long-lived WebSocket, SSE or
WebRTC connection, model connection setup, reconnect and teardown with a
[machine](../machines/concepts.md). Individual messages can still return through
events; the [WebSocket example](../../examples/patterns/websocket) shows the
connection lifecycle.
