# The re-frame2 Guide

This is the human tutorial for re-frame2. It is the place to build intuition: counters first, then the cascade, then subscriptions, views, effects, schemas, frames, HTTP, routing, tests, SSR, privacy, and the operational habits that keep a real app from becoming haunted furniture.

If you want signatures, use the [API reference](../api/README.md). If you want the normative contract, use the [spec](../../spec/README.md). If you want Story or Xray in depth, use their dedicated sections. This guide teaches the application model and hands off to those references when the topic stops being a tutorial.

## Who This Is For

JavaScript programmers should start here to understand why re-frame2 is not just another state library with a Clojure accent. The short answer is that events, effects, subscriptions, schemas, frames, and evidence are all data-shaped and tool-readable.

ClojureScript developers can move quickly through the first few chapters, but should not skip the v2 substrate: frames, schema boundaries, trace/epoch evidence, adapters, Story, Xray, and MCP change what the framework can explain.

re-frame v1 users should read the main guide, then [25 - From re-frame v1](25-from-re-frame-v1.md). The old instincts mostly survive. The old global assumptions do not.

## How To Read It

Read chapters 01-08 in order if you are new. They teach the core loop: event, app-db, subscription, view, effect, schema.

After that, jump by problem:

- Building real forms? Read [11 - Forms](11-forms.md).
- Talking to a server? Read [10 - HTTP](10-http.md).
- Modeling a process? Read [12 - State machines](12-machines.md).
- Adding tests? Read [13 - Testing](13-testing.md).
- Fighting routing? Read [19 - Routing](19-routing.md).
- Debugging weird behavior? Read [16 - Observability](16-observability.md) and [17 - Tooling](17-tooling.md), then use the Xray docs.

All code in the guide is ClojureScript. If you can read Clojure data structures, you are close enough to begin; if not, the [ClojureScript reading guide](../cljs/index.md) is the better first stop.

## What The Guide Believes

The guide is opinionated because the framework is opinionated. A single application state value is a good idea. Effects should be data. Views should be derivative. Runtime evidence should be structured enough that humans and tools can both read it. You can disagree with those claims, but re-frame2 is built around them, so the tutorial argues for them directly instead of pretending to be a neutral catalog.
