# Async (HTTP)

Sooner or later your app must talk to a server, and the moment it does you inherit a family of problems: errors, timeouts, retries, loading states, stale replies racing each other.

re-frame2's answer is the **managed request**. You describe the request as data, return it from a pure [event handler](../core/concepts/effects-and-coeffects.md), and finish. The runtime performs it. The reply arrives later as an **ordinary [event](../core/concepts/events-and-the-pipeline.md)** — success and failure each named:

```clojure
{:fx [[:rf.http/managed {:request    {:url "/api/articles/intro"}
                         :on-success [:article/loaded]
                         :on-failure [:article/load-error]}]]}
```

No `await`, no callback nesting, no resumed stack frame. The answer comes back on the same wire as everything else in your app.

## In this section

- **[Tutorial: talk to a server](tutorial.md)** — build up from the smallest request that works to schema validation, retries, a cured search-box race, and a network-free test. Start here.
- **[Managed HTTP reference](http.md)** — every key of `:rf.http/managed`: the request envelope, the reply contract, the closed set of failure categories, retry, cancellation, testing.
- **[Interceptors and secrets](http-going-further.md)** — production cross-cutting concerns: stamp auth on every request once; keep tokens and passwords out of traces.
- **[Your own async effect](custom-effects.md)** — wrap a promise-returning SDK, a callback API, or a worker message in the same pattern.
- **[Why no await](continuations-are-data.md)** — the idea underneath it all: a continuation is data, not a closure. Read it once and every async surface in re-frame2 looks the same.
- **[Examples](examples.md)** — complete apps built on managed HTTP.

## Scope

Two boundaries worth knowing before you dive in:

- This section covers *managed* async — effects whose completion returns as a reply event. That's narrower than "async" in general: the event loop, `dispatch-later`, and the event pipeline are async too, and they live in [Effects and coeffects](../core/concepts/effects-and-coeffects.md).
- For the *cache* over server reads — staleness, invalidation, scope — see [Resources](../resources/index.md), which rides on managed HTTP underneath. This section is the transport.

!!! note "Setup"

    Managed HTTP ships as its own artefact, `day8/re-frame2-http`, so apps that never issue a request build clean of it. Add the dep and require `re-frame.http.managed` once at boot — that registers `:rf.http/managed` and family. The [tutorial](tutorial.md) walks it.
