# Redirect an old URL

When a page moves, keep its old URL working: a bookmark, a pasted link or Back to
`/posts/intro` should open `/articles/intro`, with the new URL in the address bar.

Keep the old path registered, and add an interceptor to the URL-bound frame that sends
any navigation to it on to the new address:

```clojure
(ns app.redirects
  (:require [re-frame.core :as rf]
            [re-frame.routing :as rf.routing]))   ;; match-url lives here

;; The old path stays registered, so its URLs still match a route.
(rf/reg-route :legacy/post {} "/posts/:slug")

(def moved
  "Old route id → a function from its params to the new address."
  {:legacy/post (fn [{:keys [slug]}] {:to :app/article :params {:slug slug}})})

(defn- redirect-for
  "The navigate request that replaces a navigation event, or nil."
  [[ev-id a]]
  (let [url (case ev-id
              :rf.route/handle-url-change a          ;; address bar, reload, Back/Forward
              :rf.route/url-requested     (:url a)   ;; a link click
              :rf.route/navigate          (:url a)   ;; a {:url …} navigate
              nil)]
    (when-let [{:keys [route-id params]} (some-> url rf.routing/match-url)]
      (when-let [new-address (moved route-id)]
        (assoc (new-address params)
               :replace? (or (= :rf.route/handle-url-change ev-id)
                             (true? (:replace? a))))))))

(rf/reg-interceptor :app/redirect-moved
  {:doc "Send navigations to a moved route on to its new address."}
  {:before
   (fn [ctx]
     (if-let [request (redirect-for (get-in ctx [:coeffects :event]))]
       (-> ctx
           (assoc :rf/skip-handler? true)
           (assoc-in [:effects :fx] [[:dispatch [:rf.route/navigate request]]]))
       ctx))})

(rf/make-frame
  {:id           :app
   :url-bound?   true
   :interceptors [:app/redirect-moved]})
```

The [tutorial](../tutorial.md#step-1--your-first-route-on-screen)'s app creates its
frame with `frame-root`, which takes the same options, so add
`:interceptors [:app/redirect-moved]` there instead.

## How it works

An old URL can arrive three ways, and the interceptor reads the URL from each:
`:rf.route/handle-url-change` for a typed or pasted address, a reload and Back or
Forward; `:rf.route/url-requested` for a `route-link` click; and a `{:url …}`
`:rf.route/navigate`. `match-url` resolves the URL to `:legacy/post`, and `moved`
turns its params into the new address.

Setting `:rf/skip-handler?` stops the original navigation before anything commits: no
route change, no guards, no `:on-match`. The `:fx` then starts a navigation to the
new address, which runs the new route's guards and activation as usual. The old
route is never active, so the root view needs no arm for it.

After a `:rf.route/handle-url-change` the address bar already shows the old URL, so
the redirect replaces that history entry, and Back does not return to it. A click or
a navigate has not changed the address bar yet, so the redirect keeps its own
`:replace?` and otherwise pushes, as the original navigation would have.

To move a whole section, give `moved` one entry per old route.

A `{:to :legacy/post}` navigate is not redirected, because it names no URL. That
spelling only appears in your own code, so point it at the new route instead.

Under server rendering, answer an old URL with a `301` from the server boot event, as
[Controlling the response](../../ssr/response.md) does for `/posts`, so search engines
and other clients learn the new address.

## Test it

```clojure
(deftest an-old-url-lands-on-the-article
  (rf/with-new-frame [f (rf/make-frame {:interceptors [:app/redirect-moved]})]
    (rf/dispatch-sync [:rf.route/handle-url-change "/posts/intro"])
    (is (= :app/article @(rf/subscribe [:rf.route/id])))
    (is (= {:slug "intro"} @(rf/subscribe [:rf.route/params])))))
```

The namespace setup and reset fixture are in [Testing routes](../testing.md).

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| An old URL lands on not-found | The old path is no longer registered | Keep its `reg-route` |
| An old URL throws `No matching clause` in the root view | The interceptor is not on the frame, so the old route committed | Add it to the URL-bound frame's `:interceptors` |
| A `{:to :legacy/post}` navigate still opens the old route | The interceptor reads URLs only | Navigate to the new route id |
