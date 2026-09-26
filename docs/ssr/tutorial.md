# Tutorial: render on the server

This tutorial builds one small articles app and takes it from a string of HTML at a
JVM REPL to a Ring server whose pages the browser hydrates. Each step adds one piece
of the server/client lifecycle; Step 7 swaps the hand-written pieces for the
production adapter.

The app is adapted from [`examples/capabilities/ssr/ssr/`](../../examples/capabilities/ssr/ssr).
Steps 1–3 run at a JVM REPL, Steps 4–5 in the browser, and Steps 7–8 on a Jetty
server.

## Step 0 — turn SSR on

SSR ships in its own artefact, `day8/re-frame2-ssr`, so an app that never renders
server-side carries none of it. During alpha no re-frame2 artefact is published, so
they resolve with `:local/root` from a re-frame2 checkout cloned beside your project:

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps
 {day8/re-frame2     {:local/root "../re-frame2/implementation/core"}
  day8/re-frame2-ssr {:local/root "../re-frame2/implementation/ssr"}}}
```

The app is one file, `src/app/core.cljc`, compiled twice: by Clojure on the JVM for
the server and by shadow-cljs for the browser. Reader conditionals (`#?(:clj …)`,
`#?(:cljs …)`) mark the few lines that belong to one side.

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.ssr  :as ssr]))   ;; render-to-string, hydrate!, the :rf.server/* fx
```

Requiring `re-frame.ssr` is what installs SSR. Without it, the first `rf/reg-head` or
`rf/reg-error-projector` call throws `:rf.error/ssr-artefact-missing`.

## Step 1 — render a view to a string

A view returns [hiccup](../core/glossary.md#hiccup), which is plain data, and
`ssr/render-to-string` turns that data into an HTML string. It needs no browser,
DOM or React, so you can try it at a REPL before any HTTP exists.

Register an event, a subscription and a root view:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc (condensed)
(rf/reg-event :articles/seed
  (fn [{:keys [db]} [_ articles]]
    {:db (assoc db :articles articles)}))

(rf/reg-sub :articles/slice (fn [db _] (:articles db)))

(rf/reg-view ^{:rf/id :app/root} root-view []
  (let [arts @(subscribe [:articles/slice])]
    [:main.page
     [:h1 "Recent articles"]
     (if (seq arts)
       (into [:ul] (for [{:keys [id title]} arts]
                     ^{:key id} [:li [:h3 title]]))
       [:p "No articles."])]))
```

Install the headless SSR adapter, create a frame, seed it and render:

```clojure
(rf/init! ssr/adapter)                    ;; the server-side substrate; no React needed

(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:articles/seed [{:id "1" :title "Hello, server"}]] {:frame f})
  (ssr/render-to-string ((rf/view :app/root)) {}))
;; => "<main class=\"page\" …><h1>Recent articles</h1><ul><li><h3>Hello, server</h3></li></ul></main>"
;;    (a development build also stamps data-rf-view and source-coordinate attributes where the … sits)
```

**What you see:** HTML from your own view, on a JVM with no browser.

`(rf/view :app/root)` looks up the view, and calling it returns the hiccup it renders.
The call runs under the frame `with-new-frame` binds. `render-to-string` also accepts
the uncalled vector form `[(rf/view :app/root)]`; this tutorial calls the view
throughout because Step 3 hashes the tree, and the hash needs the called form.
`render-to-string` lives only on `re-frame.ssr`; there is no `rf/` facade copy.

None of the registrations changed to make this work: the handler, the subscription
and the view were already pure.

!!! note "Why is `subscribe` unqualified in the view?"

    `reg-view` injects `subscribe` and `dispatch` into the view body, already bound to
    the frame the view renders under. On the JVM a deref reads the current value; in
    the browser the same deref also registers a reaction so the view re-renders on
    change.

## Step 2 — a frame per request

Concurrent requests must not see each other's state. Give each request its own
[frame](../core/frames.md): an isolated app-db, created when the request arrives and
destroyed when the response is written.

`with-new-frame` from Step 1 destroys its frame when the block exits. The handler
below runs the same lifecycle long-hand, with `make-frame` and a `destroy-frame!` in a
`finally`, because the frame id is needed before the frame exists: the request is
stored against the id, and the frame's boot event reads it from there.

The boot event is `:rf/server-init`. The framework reserves the name; you supply the
body. It reads the request through a declared [coeffect](../core/glossary.md#coeffect):

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc
(def sample-articles                               ;; stand-in for a real data source
  [{:id "1" :title "Hello, server"}
   {:id "2" :title "Hydration, verified"}])

(rf/reg-event :rf/server-init
  {:platforms        #{:server}                    ;; server only; Step 6 explains
   :rf.cofx/requires [:rf.server/request]}         ;; hand me the request, as data
  (fn [{:keys [db rf.server/request]} _]
    (let [limit (or (some-> (get-in request [:query-params "limit"]) parse-long) 10)]
      {:db (assoc db :articles/limit limit)
       :fx [[:dispatch [:articles/seed (vec (take limit sample-articles))]]]})))
```

`:rf.server/request` is the request map exactly as the host stored it. For Ring that
is `:uri`, `:request-method`, `:headers`, plus whatever middleware adds:
`:query-params` from `wrap-params`, `:cookies` from `wrap-cookies`, `:session` from
`wrap-session`. A real page usually starts its HTTP fetches here instead of reading a
`def`. The worked example fires [`:rf.http/managed`](../async/http.md) here and,
because the reply lands asynchronously, its `handle-request` waits for it by hand
before rendering; Step 7 shows how the Ring adapter waits instead.

The per-request lifecycle:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — handle-request, condensed
#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))]
       (ssr/set-request! fid request)               ;; 1. store the request against the id
       (rf/make-frame                               ;; 2. fresh frame; :initial-events run
         {:id             fid                       ;;    synchronously as it comes up,
          :platform       :server                   ;;    so the request must be stored first
          :initial-events [[:rf/server-init]]})
       (try
         (rf/with-frame fid
           (let [hiccup ((rf/view :app/root))]      ;; 3. the settled state, as hiccup
             (ssr/render-to-string hiccup {})))     ;; 4. hiccup → HTML string
         (finally
           (rf/destroy-frame! fid))))))             ;; 5. torn down on every exit path
```

A handler is a function, so you can serve a request at the REPL:

```clojure
(handle-request {:request-method :get :uri "/" :query-params {"limit" "1"}})
;; => "<main class=\"page\" …><h1>Recent articles</h1><ul><li><h3>Hello, server</h3></li></ul></main>"
```

`make-frame` runs `:initial-events` synchronously and the runtime drains: it keeps
processing events, and the events those dispatch, until the queue is empty. By the
time the view renders, app-db holds the finished state. The `finally` matters on a
long-running server: without it, every request that throws leaks a frame.
`destroy-frame!` also clears the stored request.

## Step 3 — ship the state with the HTML

When the browser's JavaScript boots, it needs the state the server finished in, or it
would re-fetch everything. So the server ships the HTML and a serialized **payload**
of that state in the same page.

Add two JVM-only requires:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.ssr  :as ssr]
            #?(:clj [re-frame.ssr.html-helpers   :as html])              ;; the escaper
            #?(:clj [re-frame.ssr.payload-policy :as payload-policy])))  ;; the payload builder
```

Then replace the `rf/with-frame` block in `handle-request` with this one, which
returns a Ring response:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — the render + payload half
(rf/with-frame fid
  (let [hiccup  ((rf/view :app/root))
        rhash   (ssr/render-tree-hash hiccup)                ;; hash once …
        page    (ssr/render-to-string hiccup {:render-hash rhash})  ;; … stamp it here …
        payload (payload-policy/build-payload                ;; stamps :rf/version from the SSR-owned constant
                  nil                                        ;; no :rf/frame-id; the client names its own frame
                  (rf/app-db-value fid)                      ;; your state (all of it; see below)
                  rhash                                      ;; … and again here, for Step 5's check
                  {:runtime-db (payload-policy/project-runtime-db    ;; the framework's (route, machines),
                                 (:rf.db/runtime (rf/frame-state-value fid)) fid)})]  ;; projected, never raw
    {:status  200
     :headers {"Content-Type" "text/html"}
     :body    (str "<!DOCTYPE html><html><head><meta charset='utf-8'/></head><body>"
                   "<div id='app'>" page "</div>"
                   "<script id='__rf_payload' type='application/edn'>"
                   (html/escape-edn-script-body (pr-str payload))
                   "</script>"
                   "<script src='/main.js'></script>"
                   "</body></html>")}))
```

```clojure
(-> (handle-request {:request-method :get :uri "/" :query-params {"limit" "1"}})
    (select-keys [:status :headers]))
;; => {:status 200, :headers {"Content-Type" "text/html"}}
;; (:body …) is the whole document: rendered HTML and the payload script.
```

- **The payload is EDN in a `<script>` tag** with the fixed id `__rf_payload`, which
  is how the client finds it in Step 4.
- **It goes through an escaper.** If an article contained the text `</script>`,
  writing it raw would close the script element early. `escape-edn-script-body`
  rewrites `<` inside EDN strings, so the payload still reads back byte-for-byte.
- **`:rf/render-hash` is a structural fingerprint of the render tree.** You compute
  it once with `render-tree-hash` and use it twice: `:render-hash` stamps it on the
  root element as a `data-rf-render-hash` attribute, and the payload carries the same
  string, so the tree is walked only once. The hash covers only the root view's own
  markup, not the views it nests, which is why this root view returns its markup
  directly ([what the hash covers](concepts.md#what-the-hash-covers)).

This handler writes its own `<!DOCTYPE html>` envelope around a fragment. When your
root view renders the whole `[:html …]` document instead, pass `:doctype? true` to
`render-to-string` and it prefixes the doctype itself.

This version ships the whole app-db, which leaks any secret the app puts in state.
Step 7's adapter makes you declare an allowlist instead; see
[the fail-closed allowlist](concepts.md#payload--the-fail-closed-allowlist). The
runtime-db half is never shipped raw, even here: `project-runtime-db` keeps only the
durable route and machine slices, redacts any value the app classified `:sensitive`
(such as a reset token in a query string), and leaves out the frame's classification
registry. On this page the runtime-db is empty, so the projection is `nil` and
`build-payload` leaves `:rf/runtime-db` out. A present `nil` would be a malformed
slice that hydration rejects.

## Step 4 — hydrate on the client

The browser now has painted HTML and a payload. The client adopts both: it installs
the state and attaches to the existing DOM instead of re-rendering from scratch.

The client needs the Reagent adapter, React and a shadow-cljs build. Add the adapter
and a build alias to `deps.edn`:

```clojure
;; deps.edn
{:paths ["src" "resources"]
 :deps
 {day8/re-frame2         {:local/root "../re-frame2/implementation/core"}
  day8/re-frame2-ssr     {:local/root "../re-frame2/implementation/ssr"}
  day8/re-frame2-reagent {:local/root "../re-frame2/implementation/adapters/reagent"}}  ;; new
 :aliases
 {:shadow {:extra-deps {thheller/shadow-cljs {:mvn/version "3.4.10"}}}}}                ;; new
```

Add a `package.json` for React and shadow-cljs, and a `shadow-cljs.edn` whose build
writes `resources/public/main.js`, the file the page's `<script src='/main.js'>`
loads:

```json
{"dependencies":    {"react": "19.3.0", "react-dom": "19.3.0"},
 "devDependencies": {"shadow-cljs": "3.4.10"}}
```

```clojure
;; shadow-cljs.edn
{:deps   {:aliases [:shadow]}
 :builds {:app {:target     :browser
                :output-dir "resources/public"
                :asset-path "/"
                :modules    {:main {:init-fn app.core/run}}}}}
```

Add the adapter to the `ns` form as a ClojureScript-only require,
`#?(:cljs [re-frame.adapter.reagent :as reagent-adapter])`, then write the client
entry point:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — the client entry point
(rf/reg-event :app/client-bootstrap              ;; seeds a page nobody server-rendered
  (fn [{:keys [db]} _]
    {:db (assoc db :articles [])}))

#?(:cljs (defonce app-root (reagent-adapter/client-root)))

#?(:cljs
   (defn run []
     (rf/init! reagent-adapter/adapter)      ;; the browser substrate this time
     (rf/make-frame {:id :app :platform :client}) ;; the client's own, named frame
     (let [el      (js/document.getElementById "app")
           payload (ssr/hydrate! {:frame          :app
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})
           tree    [rf/frame-provider {:frame :app} [(rf/view :app/root)]]]
       (when-not payload
         ;; nil: nobody server-rendered this page, so seed before the first render
         (rf/dispatch-sync [:app/client-bootstrap] {:frame :app}))
       ;; payload: adopt the painted DOM; nil: mount a fresh root
       (reagent-adapter/render! app-root tree el {:hydrate? (some? payload)}))))
```

`hydrate!` does three things in a fixed order. It **reads** the `__rf_payload`
script, **hydrates** by dispatching `[:rf/hydrate payload]` before the first render
(installing the server's app-db and its runtime-db slice in one step), and
**verifies** the render hash (Step 5). It returns the payload it applied, or `nil`
when there was none, which is how `run` knows whether the page was server-rendered.

`hydrate!` handles state only; it never touches the DOM. Adopting the server's markup
is the separate `reagent-adapter/render!` call with `{:hydrate? true}` (React's
`hydrateRoot` underneath). Without that option the same call discards the markup and
mounts fresh, which is right only for the client-only branch.

Three details in that code:

- **The same frame id goes to `hydrate!` and to `frame-provider`.** Pass no `:frame`
  and `hydrate!` raises `:rf.error/no-frame-context`; the runtime never picks a frame
  for you. The name `:app` is yours. (The example file names its client frame
  `:rf/default`, which is also just a name.)
- **`:render-tree-fn` calls the view**, because the verify step must hash the same
  tree shape the server hashed in Step 3. The vector form would hash differently even
  though nothing on the page differs.
- **Hydration replaces the client's state; it does not merge.** Whatever the client
  pre-seeded is overwritten. You don't normally register a handler for `:rf/hydrate`:
  the framework provides it, and it rejects a malformed payload wholesale, leaving
  existing state untouched. To keep client-only state across hydration, re-register
  `:rf/hydrate` with your own explicit merge
  ([concepts](concepts.md#the-client-side-hydrate-then-verify)).

Compile the client with `npx shadow-cljs watch app`; Step 7 serves the page and
`main.js` together. To try hydration before that, the worked example ships a
hand-authored `index.html`, a frozen snapshot of what its `handle-request` serves,
and its `run` hydrates it the same way.

**What you see:** the page is painted before your JavaScript loads. When `run`
finishes nothing flashes: the client's first render matches the HTML, the articles
are in app-db without a re-fetch, and events dispatch as in any re-frame2 app.

## Step 5 — break it on purpose

A **hydration mismatch** is a client first render that disagrees with the server's
HTML, usually from something like a date rendered in two timezones or state the
server set that the client never read.

Steps 3 and 4 already wired the check: the server ships `:rf/render-hash`, and
`hydrate!` hashes the tree from `:render-tree-fn` and compares. To trip it, put
something non-deterministic in the root view:

```clojure
;; Don't do this: it exists to trip the check. It replaces Step 1's root view in app.core.
(rf/reg-view ^{:rf/id :app/root} root-view []
  (let [arts @(subscribe [:articles/slice])]
    [:main.page
     [:p (str "Rendered at " #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))]
     [:h1 "Recent articles"]
     (if (seq arts)
       (into [:ul] (for [{:keys [id title]} arts]
                     ^{:key id} [:li [:h3 title]]))
       [:p "No articles."])]))
```

The timestamp sits in the root view's own markup, so it is part of the hashed tree:
the server hashes one time, the client's first render another.

**What you see:** the page still works, because the default recovery is to warn and
render the client's view. The trace stream carries a structured error:

```clojure
{:operation :rf.ssr/hydration-mismatch
 :op-type   :error
 :tags      {:server-hash "a3f29c01"           ;; the tree the server shipped…
             :client-hash "0b77e4d2"           ;; …vs the client's first render
             :frame       :app
             :failing-id  :rf/hydrate
             :recovery    :warned-and-replaced}}
```

The trace does not say which node diverged
([why](concepts.md#which-node-and-which-substrate)), so look for the non-determinism:
clocks, locales, unordered collections.

For CI, create the client frame with `:ssr {:on-mismatch :hard-error}` and a mismatch
throws a structured exception, so it fails the build. The real fix is to put the
timestamp in app-db at init: it rides the payload and both sides render the same
value.

## Step 6 — gate the one-sided code: `:platforms`

Some work is meaningless on one side. The JVM has no `localStorage`; the browser has
no request coeffect. Instead of branching in handler bodies, declare where a
capability may run:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc
(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}                 ;; the server render skips this, with a trace
  (fn [_ {:keys [token]}]
    #?(:cljs (.setItem js/localStorage "auth/token" token))))
```

When a server-side drain reaches a `#{:client}` effect, it skips it and emits a
`:rf.fx/skipped-on-platform` trace. The handler that returned the effect does not
need to know which runtime it is on. Coeffects are gated the same way:
`:rf.server/request` is `#{:server}`, so a handler running client-side after
hydration does not receive it.

## Step 7 — swap in the Ring adapter

Steps 2–3 built the lifecycle by hand: store the request, create the frame, drain,
render, build the payload, respond, tear down. `day8/re-frame2-ssr-ring` packages
that sequence as one handler constructor. Add it and Jetty to `deps.edn`'s `:deps`:

```clojure
day8/re-frame2-ssr-ring {:local/root "../re-frame2/implementation/ssr-ring"}
ring/ring-jetty-adapter {:mvn/version "1.12.1"}
```

The server goes in its own JVM-only namespace, which loads `app.core`'s registrations:

```clojure
;; src/app/server.clj
(ns app.server
  (:require [app.core]
            [re-frame.core                :as rf]
            [re-frame.ssr                 :as ssr]
            [re-frame.ssr.ring            :as ssr.ring]
            [ring.adapter.jetty           :as jetty]
            [ring.middleware.params       :refer [wrap-params]]
            [ring.middleware.resource     :refer [wrap-resource]]))

(rf/init! ssr/adapter)                       ;; once per JVM, as in Step 1

(def handler
  (ssr.ring/ssr-handler
    {:initial-events [[:rf/server-init]]                ;; Step 2's boot event
     :root-view      (fn [] ((rf/view :app/root)))      ;; Step 2's called render target, hashed
     :payload        [:articles]}))                     ;; Step 3's payload, now an allowlist

(def app
  (-> handler
      (wrap-resource "public")               ;; /main.js from resources/public (Step 4's build)
      wrap-params))                          ;; fills :query-params, which Step 2 reads

(jetty/run-jetty app {:port 3000 :join? false})
```

Ordinary Ring middleware supplies the rest: `wrap-resource` serves `/main.js`, which
the handler's page shell loads by default, and `wrap-params` parses `?limit=1` into
the `:query-params` that `:rf/server-init` reads.

`:root-view` is a fn that calls the root view, as in Step 2, so the handler can hash
the page. The vector form `[(rf/view :app/root)]` renders the same HTML, but the
handler ships no hash for it and Step 5's check never runs
([why](concepts.md#what-the-hash-covers)).

**`:payload` is required, and it is an allowlist.** Name the top-level app-db keys
that may ship; every other key stays on the server, including keys added later. Omit
`:payload` and construction throws `:rf.error/ssr-missing-payload-policy` at boot. To
ship everything, say so explicitly with `:rf.ssr.payload/whole-app-db`.

**What you see:** `curl localhost:3000` returns the full document (rendered HTML, the
`__rf_payload` script, the hash on the root element) and the client from Step 4
hydrates it unchanged.

The handler renders once the boot events' synchronous work settles. It does not wait
for an `:rf.http/managed` fetch those events start, so a page that loads its data over
HTTP declares it as a route resource with `:blocking? true`, which the handler does
wait for ([Data the first render needs](concepts.md#data-the-first-render-needs)).

## Step 8 — shape the response

The Ring handler builds the status, headers and cookies from effects your server-side
handlers return. Add a cache header to the boot event from Step 2 (the new line is
the last `:fx` entry):

```clojure
(rf/reg-event :rf/server-init
  {:platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [db rf.server/request]} _]
    (let [limit (or (some-> (get-in request [:query-params "limit"]) parse-long) 10)]
      {:db (assoc db :articles/limit limit)
       :fx [[:dispatch [:articles/seed (vec (take limit sample-articles))]]
            [:rf.server/set-header {:name "Cache-Control" :value "public, max-age=60"}]]})))
```

**What you see:** `curl -i localhost:3000` now shows `Cache-Control: public, max-age=60`
beside the page. `:rf.server/set-status`, cookies and redirects work the same way;
every `:rf.server/*` effect is server-only.
[Controlling the response](response.md) lists them all. The hand-rolled handler from
Step 3 ignores these effects, because it writes its own response map.

## The complete shape

| Half | Surface | You supply |
|---|---|---|
| Server | `ssr.ring/ssr-handler` | `:initial-events`, `:root-view` that calls the root view, a `:payload` allowlist; `:rf.server/*` effects for status, headers and cookies |
| Client | `ssr/hydrate!` then `reagent-adapter/render!` with `{:hydrate? (some? payload)}` | The same `:frame` as `frame-provider`; `:render-tree-fn` that calls the root view |

The hand-rolled lifecycle from Steps 2–3 is what the adapter runs, which makes it the
model to reason with when something misbehaves. Both halves in one listing:
[A complete loop](concepts.md#a-complete-loop-server--client).

## Advanced

### Native UIx — adopt through the shared render path

Reagent views return a data render tree, so the server and client each hash it. A
native UIx app, whose views compile straight to React elements, has no such tree and
is verified by React adoption instead: on the client, call `hydrate!` without
`:render-tree-fn` ([which substrates hash](concepts.md#which-node-and-which-substrate)).

Hydrate the DOM through re-frame2's client mount entry,
`(re-frame.substrate.adapter/render tree el {:hydrate? true})` (the adapter's
`:render` slot), and not through `uix.dom/hydrate-root` or react-dom `hydrateRoot`
directly. Only that path installs the framework `onRecoverableError` reporter,
bounded to the adoption window and composed over any `:on-recoverable-error` you
pass, so a recoverable mismatch emits the same `:rf.ssr/hydration-mismatch` trace,
tagged `:where` `re-frame.substrate.spine/make-render`. Hydrating with the
substrate's own renderer bypasses it: React still recovers the DOM, but with no
framework trace.

Adoption reports only what React itself recovers from. An attribute-only mismatch (a
stale `class`, `style` or ARIA value on an element whose tag and text match) is not in
that set: React warns in development, makes
[no promise to patch it](https://react.dev/reference/react-dom/client/hydrateRoot),
and calls neither `onRecoverableError` nor any production equivalent. So a divergent
attribute hydrates silently on this tier, with no trace. The hiccup tier catches it
because it keeps a client render tree it can hash.

Fresco verifies by adoption for the same reason; see
[Fresco → SSR and hydration](../core/fresco/18-ssr-and-hydration.md).
