# Configure browser URLs

Choose how routes appear in the address bar when deploying the articles app.
The default uses paths such as `/articles/intro`. A static host can use
`#/articles/intro`, and an app mounted under `/reader` can include that prefix.

Add the routing alias to the [tutorial](../tutorial.md)'s namespace:

```clojure
(:require [re-frame.core :as rf]
          [re-frame.routing :as rf.routing])
```

Set the strategy where the tutorial mounts its frame. For example, this serves
the app under `/reader`:

```clojure
[rf/frame-root
 {:id           :app
  :url-bound?   true
  :url-strategy (rf.routing/with-base-path
                  rf.routing/history-url-strategy "/reader")}
 [root-view]]
```

The same options work on `rf/make-frame` if your app creates the frame separately.
Keep one URL-bound frame: it handles the initial URL, link clicks and Back/Forward.

## Keep path URLs when the server can serve them

With the default `rf.routing/history-url-strategy`, `/articles/intro` is both the
app path and the browser path. Omitting `:url-strategy` selects this default.

Configure your host to serve the app's HTML at every app path, including direct
requests to `/articles/intro`. Keep static assets and API requests outside that
fallback. Routing can change an already-loaded page without a server request,
but a reload or a pasted URL must load the app first.

For a server-rendered app, send each request's app path through the
[SSR request setup](../../ssr/concepts.md#reading-the-request) instead of serving
the same static shell.

## Use hash routing on a static host

When the host cannot rewrite app paths, use:

```clojure
[rf/frame-root {:id           :app
                :url-bound?   true
                :url-strategy rf.routing/hash-url-strategy}
 [root-view]]
```

The link to an article becomes `#/articles/intro`. The browser sends only the part
before `#` to the host, so a refresh loads the app's entry page and routing reads
the article path from the fragment. Register the route as `/articles/:slug` as
before; do not add `#` to route patterns or navigation requests.

An article anchor still works: `:fragment "comments"` produces
`#/articles/intro#comments`. Hash routing cannot select a different article during
server rendering because the HTTP request contains no fragment; use path URLs
when the server must render the requested article.

## Deploy under a subpath

Wrap the chosen strategy with `rf.routing/with-base-path`. For the same app route
`/articles/intro`, the resulting hrefs are:

| Strategy | `route-link` href |
|---|---|
| `rf.routing/history-url-strategy` | `/articles/intro` |
| `rf.routing/hash-url-strategy` | `#/articles/intro` |
| History wrapped with base `/reader` | `/reader/articles/intro` |
| Hash wrapped with base `/reader` | `/reader#/articles/intro` |

The base names the host path at which the entry page is served. Configure the host
to serve that path too. With history routing, its fallback must cover the app
paths beneath it. With hash routing, the base sits before `#`.

Routes and app-authored addresses stay independent of the deployment path:

```clojure
(rf/reg-route :app/article {:params [:map [:slug :string]]} "/articles/:slug")

(rf.routing/route-url {:to :app/article :params {:slug "intro"}})
;; => "/articles/intro", whichever strategy the frame uses

;; Dispatch from a view, or return it through :fx from a handler.
[:rf.route/navigate {:to :app/article :params {:slug "intro"}}]
[:rf.route/navigate {:url "/articles/intro"}]
```

`route-url` and `match-url` use app paths. The frame's strategy converts those paths
to browser hrefs and decodes browser changes back. On the client, an absolute
same-origin URL passed to navigation is a browser address and is decoded through
the strategy; an app-authored `{:url "/articles/intro"}` is already an app path.
Named destinations avoid that distinction in ordinary application code.

For SSR, use the same strategy when rendering the server frame and the client
frame, so their `route-link` hrefs agree. Pass the decoded app path to
`:rf.route/handle-url-change` on the server. Strategy configuration changes route
hrefs; configure script, stylesheet and image URLs for the deployment path in your
build and HTML as well.

## Verify the setup

The strategy's two pure functions can be checked without a browser:

```clojure
(let [strategy (rf.routing/with-base-path
                 rf.routing/history-url-strategy "/reader")
      path     "/articles/intro?tag=ssr#comments"
      href     ((:encode strategy) path)]
  (assert (= "/reader/articles/intro?tag=ssr#comments" href))
  (assert (= path ((:decode strategy) href))))
```

On the deployed site, follow an article link, refresh, then paste that URL into a
new tab. All three should show the same article. Navigate to another page and
check Back and Forward. These browser checks exercise the host fallback and
listener behaviour that a pure encode/decode check cannot cover.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Links work, but a refresh returns the host's 404 | The host serves only the entry path | Add a history fallback or use hash routing |
| A deployed link leaves `/reader` | The frame uses the default strategy without a base | Wrap the strategy with `with-base-path` |
| `/reader` appears twice, or a URL lands on not-found | A route pattern or app address already includes the deployment prefix | Keep route patterns and app addresses in app-path form |
| The page renders but scripts or styles return 404 | Asset URLs are relative to the wrong directory | Set the asset base in the build or HTML |
| The address bar does not change | The frame is not the URL owner | Set `:url-bound? true` on the app frame and resolve any `:rf.error/duplicate-url-binding` |
| `make-frame` throws `:rf.error/invalid-url-strategy` | A declared strategy is `nil` or lacks required functions | Omit the key for the default, or use a shipped strategy; custom contracts are in the [reference](../../api/re-frame.routing.md#a-custom-strategy) |
