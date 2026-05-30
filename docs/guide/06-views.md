# 06 - Views

You want to render UI without turning components into nervous little application controllers. This chapter shows the view contract: views read subscriptions, dispatch events, and return hiccup. Everything else is someone else's job, which is why the view can stay boring enough to trust.

A Reagent view is a function returning hiccup.

```clojure
(rf/reg-view cart-summary []
  (let [count @(subscribe [:cart/count])]
    [:section
     [:h2 "Cart"]
     [:p count " items"]
     [:button {:on-click #(dispatch [:cart/save])} "Save"]]))
```

`reg-view` defines and registers the view, then injects `dispatch` and `subscribe` as lexical bindings. If you prefer explicit calls, or you are in a live docs cell, a plain `defn` using `rf/dispatch` and `rf/subscribe` is the same underlying shape.

## Hiccup is data

```clojure
[:button {:type "button"
          :on-click #(dispatch [:counter/inc])}
 "+"]
```

That vector describes a DOM node. Because it is data, you can inspect it, walk it in tests, render it on the server, and hand it to tools. The view is not poking a node. It is describing the desired tree.

## Keep views thin

Views may format. Views may choose layout. Views may dispatch events. Views should not own business rules, HTTP calls, local caches, or long-running processes.

A useful smell test: if you want to write a unit test for a branch in the view, ask whether that branch is really a subscription, an event handler, or a machine state wearing a fake moustache.

## Stable handlers

For async callbacks, capture a frame-bound dispatcher.

```clojure
(rf/reg-view upload-button []
  (let [dispatch (rf/dispatcher)]
    [:button {:on-click
              (fn [_]
                (.then (js/fetch "/api/upload")
                       (fn [_] (dispatch [:upload/done]))))}
     "Upload"]))
```

The callback fires later, after the render stack has unwound. `dispatcher` captures the current frame now so the later call still lands in the right app instance.

## Pitfall: component-local truth

Local UI state is not evil. A hover flag, an uncontrolled input during IME composition, a third-party widget's private focus state: fine. But if the rest of the app needs to know it, test it, replay it, or explain it, it belongs in the re-frame loop.

The rule is not "never local state." The rule is "do not hide application facts in places the application cannot see."
