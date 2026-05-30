# 22 - Adapters

You want to know whether choosing Reagent, UIx, or Helix changes the re-frame2 app you write. This chapter's answer is mostly no: the event, subscription, effect, frame, schema, and tool model is shared; the adapter changes how views bind to React.

Reagent is the default teaching substrate in this guide. It works naturally with hiccup and `reg-view`.

```clojure
(rf/init! reagent/adapter)
```

UIx and Helix expose hooks-shaped React APIs. Their components usually call adapter-specific subscribe helpers and `rf/dispatcher`, while still dispatching the same events and reading the same subscriptions.

## What stays the same

| Surface | Same across adapters? |
|---|---|
| Events | Yes. |
| Subscriptions | Yes. |
| Effects/coeffects | Yes. |
| Frames | Yes. |
| Schemas | Yes. |
| Story and Xray evidence | Yes, because they observe the runtime substrate. |

The app model is above the rendering substrate. That is the point.

## What changes

View syntax and lifecycle idioms change. Reagent uses hiccup functions and `reg-view`. UIx and Helix use their component macros and hooks. When an async callback needs to dispatch later, capture `rf/dispatcher` in the render context so it keeps the right frame.

## Choosing

Use Reagent when you want the simplest path and the guide's examples to map directly. Use UIx or Helix when your team wants closer React hook ergonomics or already owns components in that style.

## Pitfall: adapter-specific business logic

Do not hide domain behaviour in adapter lifecycle hooks. The adapter should affect rendering mechanics, not the shape of your app. If changing adapters would change business behaviour, the behaviour is in the wrong place.
