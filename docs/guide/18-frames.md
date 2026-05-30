# 18 - Frames

You want isolation: tests that do not leak, Story variants that do not contaminate each other, server requests that never share state, and embedded widgets that can run side by side. This chapter teaches frames, the re-frame2 unit of app instance isolation.

A frame owns an `app-db`, an event queue, subscription cache, runtime metadata, and cascade context. Most apps use `:rf/default` and never think about it. The moment you need more than one live instance, frames are the answer.

## Default frame

`init!` creates or installs the default runtime context. Ordinary `dispatch` and `subscribe` target the current frame, which is usually `:rf/default`.

```clojure
(rf/dispatch [:cart/add {:sku "A"}])
@(rf/subscribe [:cart/count])
```

## Explicit frames

```clojure
(rf/reg-frame :preview
  {:on-create [:preview/initialise]})

(rf/dispatch [:preview/edit {:field :title :value "Draft"}]
             {:frame :preview})
```

The opts map can target a frame explicitly. That is useful for tools, embedded panels, and tests that need to drive a non-current frame.

## Temporary frames

```clojure
(rf/with-new-frame [f (rf/make-frame {:on-create [:counter/initialise]})]
  (rf/dispatch-sync [:counter/inc] {:frame f})
  (rf/get-frame-db f))
```

The frame is created for the body and destroyed afterward. This is the shape you want in tests and many Story internals.

## Why frames beat global resets

A global reset asks every test, story, and tool to be disciplined. Frames make isolation structural. When each variant has its own frame, two stories can render the same app in different states at the same time without one stealing the other's state.

## Pitfall: frame as feature namespace

Do not create a frame for every feature. A frame is an app instance, not a folder. Use features and `app-db` paths inside a frame. Reach for multiple frames when you need multiple isolated instances of the cascade.
