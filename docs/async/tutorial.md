# Tutorial: talk to a server

This tutorial loads one article from a server and grows that request one step at a
time.

## Step 0 — turn managed HTTP on

Managed HTTP ships in its own artefact, `day8/re-frame2-http`. Add the dep, then
require `re-frame.http.managed` once, in the namespace that holds your handlers:

```clojure
;; src/app/article.cljc
(ns app.article
  (:require [re-frame.core :as rf]
            [re-frame.http.managed]))   ;; registers :rf.http/managed and family
```

The handlers, subscriptions and schemas go in `app.article`. Keeping that file
`.cljc` lets [the JVM tests below](#step-6--test-it-without-a-network) load it.
The browser view goes in `src/app/article_view.cljs`.

Without the require, using the effect reports `:rf.error/no-such-fx` and sends no request.

## Step 1 — the smallest request that works

Issuing a request takes two kinds of handler: one to send, one to receive. Here is the whole thing — three registrations.

This page has no server, so the cell starts by installing stubs for the
article URLs the tutorial uses, and ends with a button that dispatches the load
and a readout of `[:article]`:

```cljs-rf2
(require '[re-frame.core :as rf]
         '[re-frame.http.managed]
         '[re-frame.http.test-support :as http-test-support])

(http-test-support/install-managed-request-stubs!
  {[:get "/api/articles/intro"]  {:reply {:ok {:slug "intro" :title "Welcome" :body "Your first article."}}}
   [:get "/api/articles/broken"] {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}})

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
  (fn [{:keys [db]} [_ {:keys [error]}]]
    {:db (-> db
             (assoc-in [:article :status] :error)
             (assoc-in [:article :error]  error))}))

;; Show the [:article] slice, and dispatch the load from a button.
(rf/reg-sub :tutorial/article (fn [db _] (:article db)))

(rf/reg-view step-1-view []
  [:div
   [:button {:on-click #(dispatch [:article/load "intro"])} "Load intro"]
   [:pre (pr-str @(subscribe [:tutorial/article]))]])

;; :fx-overrides sends this frame's requests to the stubs. A real app leaves it out.
[rf/frame-root {:id :tutorial/step-1 :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [step-1-view]]
```

Walk the send handler first. It wrote a `:loading` status into [app-db](../core/app-db.md), returned an effect *describing* the request, and **finished**. It never paused to wait for the server. Inside `:request`, `:url` is the only required key; `:method` defaults to `:get`.

When the response lands — milliseconds or seconds later — the runtime dispatches a **new event**: `:article/loaded` on success, `:article/load-error` on failure. The reply rides as one more argument appended to the event vector, so what actually arrives is:

```clojure
[:article/loaded     {:status :ok    :value <decoded-body> …}]   ;; success
[:article/load-error {:status :error :error <failure-map> …}]    ;; failure
```

That map is the **reply map**, using the framework's [uniform reply](../core/glossary.md#the-uniform-reply) shape. That's why the receive handlers destructure `[_ {:keys [value]}]` (success) / `[_ {:keys [error]}]` (failure) — skip the event id, pull the reply apart. The body has already been decoded for you according to its Content-Type (JSON, for this API), and JSON object keys arrive as keywords.

**What you see:** dispatch `[:article/load "intro"]` and `[:article :status]` goes `:loading`, then `:loaded` with the data — or `:error` with a failure map. The stub answers at once, so the cell's readout goes straight to `:loaded`.

## Step 2 — turn the failure into something a user can read

The failure map (under the reply's `:error`) always carries a `:kind` — a keyword from a closed, framework-reserved set of eight categories (`:rf.http/timeout`, `:rf.http/transport`, `:rf.http/http-4xx`, …). Never a stringified exception. Because [the set is closed](http.md#failures-are-a-closed-set), your handler can branch with a plain `case`:

```cljs-rf2
(require '[re-frame.core :as rf])

(defn failure->message [failure]
  (case (:kind failure)
    :rf.http/timeout    "The server took too long. Try again."
    :rf.http/transport  "Could not connect. Check your connection."
    :rf.http/cors       "Could not reach this service. Try again later."
    :rf.http/http-5xx   "Something went wrong on our end."
    (:rf.http/http-4xx
     :rf.http/decode-failure
     :rf.http/accept-failure) "We couldn't load that."
    :rf.http/aborted    "Cancelled."
    "Something unexpected happened."))

;; Re-registering replaces Step 1's :article/load-error.
(rf/reg-event :article/load-error
  (fn [{:keys [db]} [_ {:keys [error]}]]        ;; the failure map rides under :error
    {:db (-> db
             (assoc-in [:article :status]  :error)
             (assoc-in [:article :message] (failure->message error)))}))

;; Try another :kind, then press Ctrl-Enter (Cmd-Enter on macOS).
[:p (failure->message {:kind :rf.http/http-5xx})]
```

Register a subscription alongside the handlers, and add a browser view that
displays the result and provides a way to load it. The view goes in its own
namespace, which requires `app.article` so the registrations load first:

```clojure
;; src/app/article_view.cljs
(ns app.article-view
  (:require [re-frame.core :as rf]
            [app.article]))
```

The cell runs both against Step 1's stubs. Its second button loads an article
whose stub answers 503:

```cljs-rf2
(require '[re-frame.core :as rf])

;; In app.article, beside the handlers:
(rf/reg-sub :article/view-state
  (fn [db _] (:article db)))

;; In app.article-view:
(rf/reg-view article-view []
  (let [{:keys [status data message]} @(subscribe [:article/view-state])]
    [:section
     [:button {:on-click #(dispatch [:article/load "intro"])} "Load article"]
     [:button {:on-click #(dispatch [:article/load "broken"])} "Load a broken article"]
     (case status
       :loading [:p "Loading…"]
       :loaded  [:article [:h1 (:title data)] [:p (:body data)]]
       :error   [:p.error message]
       [:p "Choose an article."])]))

;; Mount this tree with your app's adapter. The frame supplies dispatch context.
;; :fx-overrides sends its requests to Step 1's stubs; a real app leaves it out.
[rf/frame-root {:id :app/articles :fx-overrides {:rf.http/managed :rf.http/managed-test-stub}}
 [article-view]]
```

Against a real server, disconnect the network or return a 503 and the error
replaces the loading message. A cross-origin connection failure can be classified as
`:rf.http/cors` even when CORS configuration is correct: the browser does not
distinguish it from other cross-origin network failures.

## Step 3 — validate the body with a schema

By default the body is parsed by sniffing the Content-Type (`:decode :auto`). But the 2xx body is exactly where a [schema](../core/glossary.md#schema) earns its keep. Hand `:decode` a Malli schema and a malformed body becomes a clean `:rf.http/decode-failure` — routed to the failure handler you already wrote — instead of a surprise `nil` three handlers later:

```clojure
;; Add [re-frame.schemas] to app.article's :require and
;; day8/re-frame2-schemas to the project's dependencies.
(def ArticleResponse
  [:map
   [:slug  :string]
   [:title :string]
   [:body  :string]])

;; Replace the :article/load registration from Step 1.
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :decode     ArticleResponse
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))
```

Schema decoding uses Malli. Loading `re-frame.schemas` supplies it and enables
validation and JSON coercion, such as converting a string to a keyword or UUID
when the schema requires one. Without Malli, validation is skipped and a dev
trace, `:rf.warning/http-malli-absent`, reports it once.

Decoding runs **only on 2xx responses**. A 404 that answers with an HTML error page arrives as `:rf.http/http-4xx` with the raw HTML at `:body`, never as a decode failure ([how failures are classified](http.md#failures-are-a-closed-set)).

`:decode` also takes a keyword (`:json` / `:text` / `:blob` / …) or a plain function when you need full control — see [HTTP decoding](http.md#validating-the-body-with-decode).

## Step 4 — retry reads, not writes

For a read-only GET, a short retry policy can recover from a temporary failure:

```clojure
(def data-fetch-retry
  {:on           #{:rf.http/transport :rf.http/http-5xx :rf.http/timeout}
   :max-attempts 3
   :backoff      {:base-ms 200 :factor 2 :max-ms 2000 :jitter true}})
```

Add `:retry data-fetch-retry` beside `:decode` in the request args map.
`:max-attempts 3` allows the initial attempt and two retries. Only the final
failure reaches `:article/load-error`; a successful retry reaches
`:article/loaded`. The timeout is per attempt, so `:timeout-ms 5000` can bound
each try without promising that the whole retry sequence finishes in five seconds.

Leave automatic retry off writes unless your server provides an idempotency
contract: a lost reply does not tell you whether the write happened.
[Retry policies](http.md#retry-transport-retry-as-data) explain the available
categories and when a state machine should coordinate another attempt.

<a id="step-5--cure-the-search-box-race"></a>

## Step 5 — keep the latest article

The user selects a second article while the first is still loading. Give both
requests the same `:request-id`: the new request supersedes the old one, whose
reply is suppressed. A typeahead search uses the same pattern.

Here is the final replacement for `:article/load`, including the schema, retry
policy and timeout from the previous steps:

```clojure
(rf/reg-event :article/load
  (fn [{:keys [db]} [_ slug]]
    {:db (assoc-in db [:article :status] :loading)
     :fx [[:rf.http/managed
           {:request    {:url (str "/api/articles/" slug)}
            :decode     ArticleResponse
            :retry      data-fetch-retry
            :timeout-ms 5000
            :request-id :article/load
            :on-success [:article/loaded]
            :on-failure [:article/load-error]}]]}))

(rf/reg-event :article/cancel
  (fn [_ _]
    {:fx [[:rf.http/managed-abort :article/load]]}))
```

Keep the id stable across article selections. `[:article/load slug]` would name
separate requests, so different slugs would not supersede one another. Ids are
scoped to the issuing frame.

A manual cancel delivers `:status :cancelled` to `:article/load-error`, with
`:kind :rf.http/aborted` in its `:error` map. The message function already handles
that outcome. Supersession delivers no reply for the old request; the new request
now controls the loading state. [Cancellation](http.md#cancellation-supersession-and-abort)
also covers frame teardown and requests owned by machine actors.

## Step 6 — test it without a network

The request goes out as data and the reply comes back as data, so a test needs no HTTP server and no mock library. Stub the route, dispatch, assert on app-db:

```clojure
;; test/app/article_test.clj
(ns app.article-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.http.test-support :as http-test-support]   ;; test-only: canned replies + stubs
            [re-frame.substrate.plain-atom :as plain-atom]       ;; the headless JVM adapter
            [re-frame.test-support :as ts]
            [app.article]))                ;; loads the registrations

;; Installs the adapter every frame needs, and resets the runtime around each test.
(use-fixtures :each (ts/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(deftest article-loads
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:ok {:slug "intro" :title "Welcome" :body "…"}}}}
      (fn []
        (rf/dispatch-sync [:article/load "intro"])
        (is (= :loaded (get-in (rf/app-db-value f) [:article :status])))
        (is (= "Welcome" (get-in (rf/app-db-value f) [:article :data :title])))))))

(deftest article-load-fails
  (rf/with-new-frame [f (rf/make-frame {})]
    (http-test-support/with-request-stubs
      {[:get "/api/articles/intro"]
       {:reply {:failure {:kind :rf.http/http-5xx :status 503}}}}
      (fn []
        (rf/dispatch-sync [:article/load "intro"])
        (is (= :error (get-in (rf/app-db-value f) [:article :status])))))))
```

These tests exercise the issuing and receiving handlers with success and failure
replies. Stubs supply an already-decoded value: they do not test `ArticleResponse`,
`:accept`, retry timing, timeout or supersession. Verify those transport behaviors
against a controlled server; [the stub reference](../api/re-frame.http.md#testing-without-a-network)
explains the boundary. [Test a pipeline run](../core/testing/pipeline-runs.md)
covers the shared fixture and effect overrides.

!!! note "Do, observe"

    Run the app with [Xray](../xray/index.md) open. Dispatch `[:article/load "intro"]`: you'll see the issuing event row, the request going out on the [trace stream](../core/glossary.md#trace-stream), and the reply arriving as an ordinary event row of its own — two ledger entries, one round trip. Then re-fire a `:request-id` request before its reply lands and watch the superseded completion get recorded as stale, never dispatched.
