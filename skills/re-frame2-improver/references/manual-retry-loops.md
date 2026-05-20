# Anti-pattern — Manual HTTP retry loops

Hand-rolled retry logic for HTTP calls — `setTimeout` re-dispatching the same fetch, retry counters in `app-db`, ad-hoc back-off arithmetic inside event handlers. Re-frame2 ships Managed HTTP precisely so this code does not have to exist in application files.

## Detection rules

Greppable signals (run inside any `.cljs` / `.cljc` source under review):

- `setTimeout` *and* `dispatch` in the same handler body — `(js/setTimeout #(rf/dispatch [...]) ...)`.
- A retry counter in `app-db`: keys named `:*/retries`, `:*/attempts`, `:*/retry-count`, or `(update db :*/attempts inc)` paired with a re-dispatch.
- A `cljs-http`, `js/fetch`, `goog.net.XhrIo`, or `axios` call inside `reg-event-fx` whose `:on-failure` (or shaped equivalent) re-dispatches the same originating event id.
- Exponential-back-off arithmetic inline in a handler — `(* base (Math/pow 2 attempt))`, `js/Math.pow`, `(min max-ms (* ...))`.

Structural signal: the originating event id appears in **both** the `:dispatch` of the failure branch and the initial-dispatch trigger.

## Why it's an anti-pattern

Retry policy is a **transport concern**, not a domain concern. Hand-rolled loops bake transport semantics (when to retry, how long to wait, when to give up, how to abort an in-flight attempt) into application event handlers — where they cannot be observed, tested, or composed with state-machine lifetimes. They also obscure the eight failure categories the Managed HTTP taxonomy distinguishes (`:rf.http/transport`, `:rf.http/cors`, `:rf.http/timeout`, `:rf.http/aborted`, `:rf.http/http-4xx`, `:rf.http/http-5xx`, `:rf.http/decode-failure`, `:rf.http/payload`), collapsing them into a single "it failed, try again" branch that retries on `:rf.http/http-4xx` (which is almost never the right move).

## The canonical fix

[`skills/re-frame2/patterns/managed-http.md`](../../re-frame2/patterns/managed-http.md) — the `:rf.http/managed` fx. Pass a `:retry` map declaring the failure categories that warrant retry, the maximum attempts, and the back-off curve. The runtime handles scheduling, abort, and reply-addressing.

Spec source: [`spec/014-HTTPRequests.md`](../../../spec/014-HTTPRequests.md) and [`spec/Pattern-RemoteData.md`](../../../spec/Pattern-RemoteData.md).

## Worked example

**Before** — hand-rolled retry in a handler:

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ slug attempt]]
    (let [attempt (or attempt 0)]
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:http-xhrio
             {:method :get :uri (str "/articles/" slug)
              :on-success [:article/loaded]
              :on-failure [:article/retry slug attempt]}]]})))

(rf/reg-event-fx :article/retry
  (fn [_ [_ slug attempt]]
    (when (< attempt 4)
      {:fx [[:dispatch-later
             {:ms    (min 5000 (* 250 (Math/pow 2 attempt)))
              :event [:article/load slug (inc attempt)]}]]})))
```

**After** — Managed HTTP:

```clojure
(rf/reg-event-fx :article/load
  (fn [{:keys [db]} [_ {:keys [slug] :as msg}]]
    (if-let [reply (:rf/reply msg)]
      (case (:kind reply)
        :success {:db (assoc-in db [:article] {:status :loaded :data (:value reply)})}
        :failure {:db (assoc-in db [:article] {:status :error  :error (:failure reply)})})
      {:db (assoc-in db [:article :status] :loading)
       :fx [[:rf.http/managed
             {:request {:method :get :url (str "/articles/" slug)}
              :retry   {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
                        :max-attempts 4
                        :backoff      {:base-ms 250 :factor 2 :max-ms 5000 :jitter true}}}]]})))
```

## Edge cases — when manual is fine

- **Domain-level retry** (e.g. "poll the job every 5s until it reports `:complete`") is semantic, not transport — model it in a state machine (`:spawn` with `:after`) rather than in `:retry`. The presence of `setTimeout` + `dispatch` is only an anti-pattern when it's compensating for transport failure.
- **One-shot pages** that explicitly want no retry — `:retry` simply omitted from the Managed HTTP args. The anti-pattern is the manual loop, not the absence of retry.
- **Non-HTTP transports** (a custom WebSocket protocol, IndexedDB, postMessage) — Managed HTTP doesn't apply; manual scheduling is the correct shape until a dedicated managed fx exists.
