# Tutorial: talk to a server

Let's make an app load an article from a server — adding one idea at a time: the request, the failure path, a view, schema validation, a retry policy, a cured race, and a test.

By the end you'll have used every part of `:rf.http/managed` that everyday work needs. The [reference](http.md) has the rest.

!!! note "Before you start"

    You've read [Effects and coeffects](../core/concepts/effects-and-coeffects.md) and can return an effect map from a handler. That's the only prerequisite.

## Step 0 — turn managed HTTP on

Managed HTTP ships in its own artefact, `day8/re-frame2-http`, so apps that never issue a request don't carry it. Add the dependency, then require `re-frame.http.managed` once in your boot namespace:

```clojure
(ns app.boot
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))   ;; ← registers :rf.http/managed and family
```

Forget the require and your first `:rf.http/managed` fails loud with a named "HTTP artefact missing" error — not a silent miss.

## Step 1 — the smallest request that works

Issuing a request takes two kinds of handler: one to send, one to receive. Here is the whole thing — three registrations:

```clojure
;; Send: return the request as data. The handler finishes immediately.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))

;; Receive: each reply lands as an ordinary event.
(rf/reg-event :article/loaded
  (fn [{:keys [db]} [_ {:keys [value]}]]
    {:db (-> db
             (assoc-in [:article :status] :loaded)
             (assoc-in [:article :data]   value))}))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  failure))}))
```

Walk the send handler first. It wrote a `:loading` status into [app-db](../core/concepts/app-db.md), returned an effect *describing* the request, and **finished**. It never paused to wait for the server. Inside `:request`, `:url` is the only required key; `:method` defaults to `:get`.

When the response lands — milliseconds or seconds later — the runtime dispatches a **new event**: `:article/loaded` on success, `:article/load-error` on failure. The reply rides as one more argument appended to the event vector, so what actually arrives is:

```clojure
[:article/loaded     {:kind :success :value <decoded-body>}]     ;; success
[:article/load-error {:kind :failure :failure <failure-map>}]    ;; failure
```

That's why the receive handlers destructure `[_ {:keys [value]}]` — skip the event id, pull the reply apart. The body has already been decoded for you (JSON by default, sniffed from the Content-Type).

**What you see:** dispatch `[:article/load "intro"]` and `[:article :status]` goes `:loading`, then `:loaded` with the data — or `:error` with a failure map.

**Notice:** the failure path has its own name. It isn't bolted onto the success path as an afterthought — it's a first-class event with its own handler.

??? info "Coming from `js/fetch`?"

    The version you'd write by hand — `(-> (js/fetch url) (.then #(.json %)) (.then #(rf/dispatch [:article/loaded %])))` — is three lines and missing almost everything: no error path, no timeout, no retry, no way to test without a network. In re-frame2 it also fails outright: a bare `dispatch` inside a `.then` runs on a fresh stack with no [frame](../core/concepts/frames.md) in scope and raises `:rf.error/no-frame-context`. Describe the request as data instead; the runtime carries the frame through and does the rest.

!!! note "Why an event, not an `await`?"

    Your app's state is the running total of every event ever dispatched. An awaited value slips in through the call stack and leaves no record; a reply *event* lands in the ledger — traceable, replayable, safe under races. The full argument is [Why no await](continuations-are-data.md). You don't need it to keep going.

## Step 2 — turn the failure into something a user can read

The failure map always carries a `:kind` — a keyword from a closed, framework-reserved set of eight categories (`:rf.http/timeout`, `:rf.http/transport`, `:rf.http/http-4xx`, …). Never a stringified exception. Because [the set is closed](http.md#failures-are-a-closed-set), your handler can branch with a plain `case`:

```clojure
(defn failure->message [failure]
  (case (:kind failure)
    :rf.http/timeout    "The server took too long. Try again."
    :rf.http/transport  "You appear to be offline."
    :rf.http/http-5xx   "Something went wrong on our end."
    (:rf.http/http-4xx
     :rf.http/decode-failure
     :rf.http/accept-failure) "We couldn't load that."
    "Something unexpected happened."))

(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [failure]}]]
    {:db (-> db
             (assoc-in [:article :status]  :error)
             (assoc-in [:article :message] (failure->message failure)))}))
```

And a view reads the status like any other state:

```clojure
(rf/reg-sub :article/view-state
  (fn [db _] (:article db)))

(rf/reg-view article-view []
  (let [{:keys [status data message]} @(subscribe [:article/view-state])]
    (case status
      :loading [:p "Loading…"]
      :loaded  [:article [:h1 (:title data)] [:p (:body data)]]
      :error   [:p.error message]
      [:p "…"])))
```

**What you see:** kill the network in devtools and you get "You appear to be offline." Ask for an article the server 500s on and you get "Something went wrong on our end." The spinner clears either way, because the failure handler writes `:status` exactly the way the success handler does.

**Notice:** write `failure->message` once and every screen renders the same vocabulary. (The [RealWorld example](../../examples/real-apps/realworld_http) does exactly this.)

## Step 3 — validate the body with a schema

By default the body is parsed by sniffing the Content-Type (`:decode :auto`). But the 2xx body is exactly where a [schema](../core/glossary.md#schema) earns its keep. Hand `:decode` a Malli schema and a malformed body becomes a clean `:rf.http/decode-failure` — routed to the failure handler you already wrote — instead of a surprise `nil` three handlers later:

```clojure
(def ArticleResponse
  "Validates and coerces the JSON body of GET /api/articles/:slug."
  [:map
   [:slug  :string]
   [:title :string]
   [:body  :string]])

{:fx [[:rf.http/managed
       {:request    {:url (str "/api/articles/" slug)}
        :decode     ArticleResponse
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

**Notice:** decode runs **only on 2xx responses** — status is classified first. A 404 that answers with an HTML error page is `:rf.http/http-4xx` with the raw HTML at `:body`, *not* a decode failure, because the decoder never ran. "The server said no" matters more than what shape the no was.

`:decode` also takes a keyword (`:json` / `:text` / `:blob` / …) or a plain function when you need full control — see [the reference](http.md#validating-the-body-with-decode).

## Step 4 — retry reads, not writes

A read-only GET that hits a transient network blip should just try again. `:retry` is a policy — itself plain data:

```clojure
(def data-fetch-retry
  "Retry read-only fetches on transport blips, 5xx, and timeouts."
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})

{:fx [[:rf.http/managed
       {:request    {:url (str "/api/articles/" slug)}
        :decode     ArticleResponse
        :retry      data-fetch-retry
        :on-success [:article/loaded]
        :on-failure [:article/load-error]}]]}
```

`:on` names the failure categories that re-issue the request. `:max-attempts` counts the first try. Backoff is exponential, with optional jitter so a thousand clients don't retry in lockstep. Your failure handler only ever sees the final, exhausted failure — intermediate attempts are trace rows, not events.

**Notice:** the policy is on a *read*. Don't put `:retry` on a submit or a payment — retrying a write risks doing it twice. The production shape is one shared policy for fetches and conspicuously none on writes. ([Full policy rules](http.md#retry-transport-retry-as-data).)

## Step 5 — cure the search-box race

The user types five letters into a search box. Five requests race. Whichever lands *last* wins — often not the one for the last letter. The fix is one key.

Give the request a stable `:request-id`. Issuing a new request with the same id **supersedes** the old one: the old reply is suppressed, and your handler never sees it.

```clojure
(rf/reg-event :search/query-changed
  (fn [{:keys [db]} [_ q]]
    {:db (assoc db :search/q q)
     :fx [[:rf.http/managed
           {:request    {:url "/api/search" :params {:q q}}
            :request-id :search/in-flight       ;; stable across keystrokes
            :on-success [:search/results]
            :on-failure [:search/error]}]]}))
```

**What you see:** type fast against a slow API and the results always match the last keystroke. Zero lines of race-handling code.

**Notice:** this isn't best-effort cancellation. The runtime classifies the superseded reply as stale *before delivery*, so it cannot clobber fresh data even if it arrives late. The same id is also your cancel handle — `[:rf.http/managed-abort :search/in-flight]` aborts the in-flight request explicitly. ([Cancellation in full](http.md#cancellation-supersession-and-abort).)

## Step 6 — test it without a network

The request goes out as data and the reply comes back as data, so a test needs no HTTP server and no mock library. Stub the route, dispatch, assert on app-db:

```clojure
(ns app.article-test
  (:require [clojure.test :refer [deftest is]]
            [re-frame.core :as rf]
            [re-frame.http.test-support]   ;; test-only: canned replies + stubs
            [app.article]))                ;; loads the registrations

(deftest article-loads
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:ok {:slug "intro" :title "Welcome" :body "…"}}}}
      (rf/dispatch-sync [:article/load "intro"])
      (is (= :loaded (get-in (rf/app-db-value f) [:article :status]))))))

(deftest article-load-fails
  (rf/with-new-frame [f (rf/make-frame {})]
    (rf/with-managed-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}
      (rf/dispatch-sync [:article/load "intro"])
      (is (= :error (get-in (rf/app-db-value f) [:article :status]))))))
```

The stubbed reply has the exact envelope a live request produces, so both tests cover the full chain — request out, reply in, handler folds the result — and run on the JVM in about a millisecond. [Test a pipeline run](../core/testing/pipeline-runs.md) is the full recipe.

!!! note "Do, observe"

    Run the app with [Xray](../core/how-to/debug-with-xray.md) open. Dispatch `[:article/load "intro"]`: you'll see the issuing event row, the request going out on the [trace stream](../core/glossary.md#trace-stream), and the reply arriving as an ordinary event row of its own — two ledger entries, one round trip. Then re-fire a `:request-id` request before its reply lands and watch the superseded completion get recorded as stale, never dispatched.

That's the everyday surface. The [Managed HTTP reference](http.md) has the rest — the full request envelope, `:accept`, timeouts, verb helpers, manual abort — and [Interceptors and secrets](http-going-further.md) covers stamping auth on every request once and keeping credentials out of traces.
