# Testing SSR

Here's the pleasant surprise: **your server tests are just JVM tests.** The whole point of re-frame2's SSR is that the same handlers, subscriptions, and views run on the JVM against a per-request frame — and that is *exactly* what your unit suite already does. Every [handler test](../core/testing/event-handlers.md), [subscription test](../core/testing/subscriptions.md), and [cascade test](../core/testing/cascades.md) you've written is already exercising the code path a server render takes. What's left to test is the thin layer that is genuinely SSR's own: the rendered string, the boot-time guards, the pure projectors, and platform gating.

> **A JVM test drain *is* a server-side drain — most of SSR was tested before you wrote an SSR test.**

## 1. Render a request to a string

`render-to-string` is a pure function — hiccup in, HTML string out — and the test shape is the request lifecycle in miniature: a fresh frame booted through `:initial-events`, render, assert on the markup. That's not just tidiness — `:initial-events` is the *same key* `ssr-handler` uses to seed every per-request frame, so a test frame built this way boots through exactly the path a production request's frame does:

```clojure
(ns my-app.ssr-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.ssr.ring :as ssr-ring]
            [re-frame.test-support :as ts]
            [my-app.views]))

(use-fixtures :each (ts/make-reset-runtime-fixture {}))

(deftest article-page-renders-its-title
  (rf/with-new-frame [f (rf/make-frame
                          {:initial-events
                           [[:rf/set-db {:articles {"intro" {:title "Welcome"}}}]
                            [:rf.route/handle-url-change "/articles/intro"]]})]
    (let [html (ssr/render-to-string [app-root] {:frame f})]
      (is (clojure.string/includes? html "Welcome")))))
```

When the assertion is about *structure* rather than the serialised string, the hiccup walk from [Test a view](../core/testing/views.md) is the sharper tool — `render-to-string` earns its place when the string itself is the contract (markup a crawler reads, attribute serialisation, the head).

## 2. The Ring handler is an ordinary function

`ssr-handler` returns a plain Ring handler, so an end-to-end server test is a function call with a request map — no Jetty, no port:

```clojure
(deftest the-server-answers
  (let [handler (ssr-ring/ssr-handler {:initial-events [[:rf/server-init]]
                                       :root-view      [:app/root]
                                       :payload        [:articles]})
        response (handler {:request-method :get :uri "/articles/intro"})]
    (is (= 200 (:status response)))
    (is (clojure.string/includes? (:body response) "Welcome"))))
```

The response `:body` also carries the `__rf_payload` script, so "did the allowlist ship what I meant?" is a substring (or parse) assertion on the same response. A handler whose drain hits a `:rf.server/redirect` answers with the status and `Location` and *no* body — one more assertion-friendly contract.

## 3. Boot guards throw; projectors are pure

Two SSR surfaces are deliberately test-shaped:

- **Construction fails loud.** A missing `:payload` policy throws at *boot*, not on the first request — and thrown framework errors carry their category in `ex-data`, so the test [pins the discriminator, never the message](../core/concepts/errors.md#the-errors-that-throw-not-trace):

    ```clojure
    (deftest payload-policy-is-mandatory
      (is (= :rf.error/ssr-missing-payload-policy
             (try (ssr-ring/ssr-handler {:root-view [:app/root]})
                  (catch Exception e (:rf.error/id (ex-data e)))))))
    ```

- **The error projector and the head fn are pure functions** — registered, but callable as the plain fns they are. A projector test hands in a trace-event map and asserts the public shape (`:status` / `:code`, and *no* internal detail in prod shape); a head test hands in `(db, route)` and asserts the head model (`:title`, the `og:` meta rows). Neither needs a frame at all.

## 4. Platform gating comes for free

A JVM drain **is** a server-side drain. So when a handler under test returns a `#{:client}` effect — a `localStorage` write, a focus call — the resolver skips it in your test exactly as it will in production SSR, emitting the `:rf.fx/skipped-on-platform` trace instead of exploding on a missing `js/localStorage`. That means your existing cascade tests already prove your handlers are server-safe; a handler that *would* crash a server render crashes the JVM test first, which is precisely where you want to hear about it.

## What stays in the browser

Two SSR behaviours can't run on the JVM, and the honest move is to test them where they live:

- **Hydration** — `hydrate!`, the payload install, and the deploy-drift checks run client-side. The lever you own is per-frame strict mode, `{:ssr {:on-mismatch :hard-error}}`, which escalates a [hydration mismatch](concepts.md#when-the-renders-disagree) from warn-and-replace to a thrown structured exception — turn it on in dev and CI browser runs so a mismatch is a red build, not a console warning nobody reads. (The [tutorial's Step 5](tutorial.md) trips one on purpose.)
- **Determinism, indirectly.** The mismatch detector is itself the test for "views are deterministic given the state" — a view that reads the clock or renders host-dependent output gets caught by the hash comparison — a red build under strict mode, instead of silent drift.

Everything else on the [SSR concepts page](concepts.md) — the request lifecycle, `:rf.server/*` response effects, the payload allowlist — is handler-and-effect territory your JVM suite covers with the moves above.
