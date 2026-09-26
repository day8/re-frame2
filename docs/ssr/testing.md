# Testing SSR

You can [author](tutorial.md) and [understand](concepts.md) SSR. This page is how you
**prove** the server half.

**Your server tests are JVM tests.** The same handlers, subs, and views run on the
JVM against a per-request frame — exactly what your
[handler](../core/testing/event-handlers.md), [subscription](../core/testing/subscriptions.md),
and [pipeline-run](../core/testing/pipeline-runs.md) suites already exercise. What's
left is the thin SSR-owned layer: rendered string, boot guards, pure projectors,
platform gating.

> **A JVM test drain *is* a server-side drain — most of SSR was tested before you wrote an SSR test.**

## 1. Render a request to a string

The tests on this page exercise one small app: an article page, routed by URL, whose server boot seeds the articles and routes the request:

```clojure
(ns my-app.views
  (:require [re-frame.core :as rf]
            [re-frame.routing]))

(rf/reg-route :app/article {:params [:map [:id :string]]} "/articles/:id")

(rf/reg-event :rf/server-init
  {:platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [db rf.server/request]} _]
    {:db (assoc db :articles {"intro" {:title "Welcome"}})
     :fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]]}))

(rf/reg-sub :article/by-id (fn [db [_ id]] (get-in db [:articles id])))

;; The root returns the page's markup itself rather than [(rf/view :pages/article)]:
;; the render hash covers the tree the root returns, and a root that only wraps
;; another view hashes to the same constant for every page, so it carries none.
(rf/reg-view ^{:rf/id :app/root} app-root []
  (let [{:keys [id]} @(subscribe [:rf.route/params])
        article      @(subscribe [:article/by-id id])]
    [:main
     [:h1 (:title article)]]))
```

`render-to-string` is a pure function — hiccup in, HTML string out — and the test shape is the request lifecycle in miniature: a fresh frame booted through `:initial-events`, render, assert on the markup. `:initial-events` is the *same key* `ssr-handler` uses to seed every per-request frame, so a test frame built this way boots through the same path a production request's frame does:

```clojure
(ns my-app.ssr-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.test-support :as ts]
            [my-app.views]))

(use-fixtures :each
  (ts/make-reset-runtime-fixture {:adapter       ssr/adapter     ;; the server-side substrate a frame needs
                                  :ambient-frame nil}))          ;; nil: each test makes its own frame

(deftest article-page-renders-its-title
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events
                           [[:rf/set-db {:articles {"intro" {:title "Welcome"}}}]
                            [:rf.route/handle-url-change "/articles/intro"]]})]
    (let [html (ssr/render-to-string [(rf/view :app/root)] {})]   ;; renders against the with-new-frame scope
      (is (str/includes? html "Welcome")))))
```

When the assertion is about *structure* rather than the serialised string, the hiccup walk from [Test a view](../core/testing/views.md) is the sharper tool — `render-to-string` earns its place when the string itself is the contract (markup a crawler reads, attribute serialisation, the head).

## 2. The Ring handler is an ordinary function

`ssr-handler` returns a plain Ring handler, so an end-to-end server test is a function call with a request map — no Jetty, no port:

```clojure
(def handler
  (ssr-ring/ssr-handler {:initial-events [[:rf/server-init]]
                         :root-view      (fn [] ((rf/view :app/root)))
                         :payload        [:articles]}))

(deftest the-server-answers
  (let [response (handler {:request-method :get :uri "/articles/intro"})]
    (is (= 200 (:status response)))
    (is (str/includes? (:body response) "Welcome"))))
```

### What crossed the wire

The body also carries the payload, as EDN inside `<script id="__rf_payload" type="application/edn">`. Pull it out and read it, and "did the allowlist ship what I meant?" becomes an assertion on data rather than on markup:

```clojure
(defn read-payload [body]
  (some->> body
           (re-find #"<script id=\"__rf_payload\"[^>]*>(.*?)</script>")
           second
           edn/read-string))

(deftest only-the-allowlist-ships
  (let [payload (read-payload (:body (handler {:request-method :get :uri "/articles/intro"})))]
    (is (every? #{:articles} (keys (:rf/app-db payload))))   ;; nothing off the allowlist
    (is (string? (:rf/render-hash payload)))))   ;; present: a fn-form :root-view whose view returns an element
```

The payload's escaping only rewrites `<` inside strings as `\u003c`, which the EDN reader decodes, so the round trip is exact.

### Redirects and cookies

The `:rf.server/*` effects end up as an ordinary Ring response. A redirect answers with its status, a `Location` header and an empty body; each cookie becomes one `Set-Cookie` header — a string for one cookie, a vector of strings for several:

```clojure
(deftest a-moved-page-redirects
  (rf/reg-event :test/moved
    {:platforms #{:server}}
    (fn [_ _]
      {:fx [[:rf.server/set-cookie {:name "flash" :value "moved" :path "/"}]
            [:rf.server/redirect   {:location "/articles" :status 301}]]}))
  (let [response ((ssr-ring/ssr-handler {:initial-events [[:test/moved]]
                                         :root-view      (fn [] ((rf/view :app/root)))
                                         :payload        [:articles]})
                  {:request-method :get :uri "/old-articles"})]
    (is (= 301 (:status response)))
    (is (= "/articles" (get-in response [:headers "Location"])))
    (is (str/starts-with? (get-in response [:headers "Set-Cookie"]) "flash=moved"))
    (is (= "" (:body response)))))
```

The registration inside the test is rolled back after it by the reset fixture from §1. [Control the response](response.md) lists every effect and its arguments.

### Keep the network out

`:fx-overrides` on the handler is passed to every per-request frame, so it stubs an effect for the whole server render. Redirect `:rf.http/managed` to a function and the test sees each request the render fires, without sending it:

```clojure
(deftest the-server-render-sends-no-requests
  (let [sent     (atom [])
        handler  (ssr-ring/ssr-handler
                   {:initial-events [[:rf/server-init]]
                    :root-view      (fn [] ((rf/view :app/root)))
                    :payload        [:articles]
                    :fx-overrides   {:rf.http/managed (fn [_frame-ctx args] (swap! sent conj args))}})]
    (handler {:request-method :get :uri "/articles/intro"})
    (is (empty? @sent))))
```

An override value is a function `(fn [frame-ctx args] …)` or another registered fx id, exactly as in [Redirect any effect](../core/testing/pipeline-runs.md#redirect-any-effect-fx-overrides). Remember that the handler does not wait for a fetch started from `:initial-events`; data the first render needs belongs in a route resource declared `:blocking? true`, which it does wait for.

## 3. Boot guards throw; projectors are pure

Two SSR surfaces are deliberately test-shaped:

- **Construction fails loud.** A missing `:payload` policy throws at *boot*, not on the first request — and thrown framework errors carry their category in `ex-data`, so the test [pins the discriminator, never the message](../core/errors.md#the-errors-that-throw-not-trace):

    ```clojure
    (deftest payload-policy-is-mandatory
      (is (= :rf.error/ssr-missing-payload-policy
             (try (ssr-ring/ssr-handler {:initial-events [[:rf/server-init]]   ;; every required opt but :payload
                                         :root-view      [(rf/view :app/root)]})
                  (catch Exception e (:rf.error/id (ex-data e)))))))
    ```

- **The error projector and the head fn are pure functions.** `rf/reg-error-projector` and `rf/reg-head` return the id they registered, not the fn, so give the fn a name and test that. A projector test hands in a trace-event map and asserts the public shape (`:status` / `:code`, and *no* internal detail in prod shape); a head test hands in `(db, route)` and asserts the head model (`:title`, the `og:` meta rows). Neither needs a frame at all:

    ```clojure
    (defn public-error [trace-event]
      (if (and (= :rf.error/no-such-handler (:operation trace-event))
               (= :route (get-in trace-event [:tags :kind])))
        {:status 404 :code :not-found :message "We couldn't find that page." :retryable? false}
        (ssr/default-error-projector-fn trace-event)))

    (rf/reg-error-projector :app/public-error public-error)

    (deftest a-route-miss-is-a-404
      (is (= 404 (:status (public-error {:operation :rf.error/no-such-handler
                                         :op-type   :error
                                         :tags      {:kind :route}}))))
      (is (= 500 (:status (public-error {:operation :rf.error/no-such-handler   ;; an unregistered event:
                                         :op-type   :error                     ;; a server defect, not a 404
                                         :tags      {:kind :event}})))))
    ```

## 4. Platform gating comes for free

A JVM drain **is** a server-side drain. So when a handler under test returns a `#{:client}` effect — a `localStorage` write, a focus call — the resolver skips it in your test exactly as it will in production SSR, emitting the `:rf.fx/skipped-on-platform` trace instead of exploding on a missing `js/localStorage`. That means your existing pipeline-run tests already prove your handlers are server-safe; a handler that *would* crash a server render crashes the JVM test first, which is precisely where you want to hear about it.

## 5. The Node renderer is a fn, so most of it tests on the JVM

If you render on a [Node sidecar](concepts.md#render-on-node), the seam that
gets you there is one construction opt holding a plain fn — so the two things
most likely to be wrong are both reachable without starting a second process.

**Renderer selection.** `:renderer` takes any
`(fn [{:keys [frame-id request opts]}] → {:body-html … :render-hash …})`, so a
handler test can hand it a stub that returns a fixed string and assert that the
JVM's half of the page — the `<head>`, `__rf_payload`, the shell, the status and
the cookies — is assembled around it correctly. That is the whole ownership line
under test, with no sidecar in the room.

**Construction validation.** `re-frame.ssr.ring.node/renderer` validates its
opts at construction, so a malformed endpoint, entry, build id or timeout throws
`:rf.error/ssr-node-renderer-opt-invalid` at boot — pinned by the discriminator,
exactly as in §3 above.

What genuinely needs both runtimes is the crossing itself, and re-frame2's own
test suite covers it rather than asking you to: it launches the real sidecar
against a plain fixture render module and drives `JVM → Node → JVM` requests end
to end, through the refusal and deadline arms as well as the success arm. Your
own suite is better spent on the stub above.

## 6. A streaming handler returns a stream

`stream-handler`'s response `:body` is a `java.io.InputStream` that a writer thread fills. `slurp` blocks until the writer closes it, so the whole streamed page arrives as one string, and the test asserts the order the chunks came in:

```clojure
;; The streaming page from streaming.md: :article/page with a :region.comments boundary.
(deftest the-comments-stream-in-after-the-shell
  (let [handler  (ssr-ring/stream-handler {:initial-events [[:rf/server-init]]
                                           :root-view      (fn [] ((rf/view :article/page)))
                                           :payload        [:articles :comments]})
        response (handler {:request-method :get :uri "/articles/intro"})
        body     (slurp (:body response))
        at       #(str/index-of body %)]
    (is (= 200 (:status response)))
    (is (< (at "data-rf2-suspense-fallback=\"1\"")    ;; the shell, with the fallback in place
           (at "data-rf2-suspense-resolved=\"1\"")    ;; then the resolved region
           (at "__rf_payload")))                       ;; then the final payload
    (is (some? (read-payload body)))))                 ;; which reads like any other
```

A redirect set during the drain still comes back as a plain bodiless response, before any chunk is written.

## 7. Replay the hydration on the JVM

`hydrate!` runs on the JVM too, given the payload explicitly. Feed it the payload your handler actually shipped, into a client frame in strict mode, and the test checks that the payload is enough to reproduce the server's render: a root whose markup reads a key the allowlist left out, or reads the clock, renders differently and throws.

```clojure
(deftest the-payload-rebuilds-the-page
  (let [payload (read-payload (:body (handler {:request-method :get :uri "/articles/intro"})))]
    (rf/make-frame {:id :app :platform :client :ssr {:on-mismatch :hard-error}})
    (is (= payload (ssr/hydrate! {:frame          :app
                                  :payload        payload
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})))
    (is (= "Welcome" (get-in (rf/app-db-value :app) [:articles "intro" :title])))))
```

The comparison needs the payload's `:rf/render-hash`, which the handler writes only for the fn form of `:root-view`, and only when the root view returns an element: with the vector form, or a root whose body is just another view, this test passes without checking anything. The hash covers the markup the root spells out and the arguments it passes to child views, not what those child views render, so a difference inside a child view goes unseen. It applies to views that return hiccup (Reagent, reagent-slim) — a UIx or Fresco root reports mismatches through React's hydration instead.

## What stays in the browser

The JVM replay renders both sides with the JVM's emitter. What it can't see is anything that differs between the JVM and the browser, so keep a browser run for:

- **Adopting the DOM** — the adapter's `render!` with `{:hydrate? true}` reconciling against the server markup, and React's own hydration check for UIx and Fresco roots.
- **Cross-runtime drift** — a view whose `#?(:clj …)` / `#?(:cljs …)` branches render differently, or host-dependent output such as number and date formatting.

Turn on the same strict mode, `{:ssr {:on-mismatch :hard-error}}`, on the client frame in dev and CI browser runs, so a [hydration mismatch](concepts.md#when-the-renders-disagree) is a red build rather than a console warning nobody reads. (The [tutorial's Step 5](tutorial.md) trips one on purpose.)

Everything else on [The model](concepts.md) — the request lifecycle, the payload
allowlist — and [response control](response.md) is handler-and-effect territory your
JVM suite covers with the moves above.
