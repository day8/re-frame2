# Tutorial: render on the server

Let's take a small articles app and make it render on the JVM — one step at a time: a pure render, a frame per request, the state payload, hydration on the client, a deliberately broken render to see the safety net fire, and finally the production Ring adapter that owns the whole lifecycle for you.

Every step below is adapted from the worked example at [`examples/capabilities/ssr/ssr/`](../../examples/capabilities/ssr/ssr) — one `.cljc` file that runs on both sides. Keep it open beside this page if you like reading ahead; where this page simplifies or diverges, it says so.

!!! note "Before you start"

    You've done the [core quickstart](../core/first-app.md) — you know what an [event](../core/introduction.md), a [subscription](../core/subscriptions.md), and a [view](../core/views.md) are, and what a [frame](../core/frames.md) is. That's the only prerequisite: SSR adds no new kind of handler, which is rather the point.

    Steps 1–3 run at a plain **JVM REPL** — evaluate along as you read. Steps 4 and 5 are browser-side; the worked example ships a page you can open to see them live.

## Step 0 — turn SSR on

SSR ships in its own artefact, `day8/re-frame2-ssr`, so an app that never renders server-side carries none of it. (Step 7 adds `day8/re-frame2-ssr-ring`, the Ring host adapter.) Add the dep and require the namespace:

```clojure
(ns app.core
  (:require [re-frame.core :as rf]
            [re-frame.ssr  :as ssr]))   ;; render-to-string, hydrate!, the :rf.server/* fx
```

## Step 1 — render a view to a string, no server anywhere

Here's the whole trick of SSR in re-frame2, and you can see it at a JVM REPL before any HTTP exists. A view produces [hiccup](../core/glossary.md#hiccup) — nested vectors and maps, plain data — and `render-to-string` is a pure function from that data to an HTML string. No browser, no DOM, no React.

First, the app — three registrations you could have written on day one:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc (condensed)
(rf/reg-event :articles/seed
  (fn [{:keys [db]} [_ articles]]
    {:db (assoc db :articles articles)}))

(rf/reg-sub :articles/slice (fn [db _] (:articles db)))

(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  (let [arts @(subscribe [:articles/slice])]
    [:div.page
     [:h1 "Recent articles"]
     (if (seq arts)
       (into [:ul] (for [{:keys [id title]} arts]
                     ^{:key id} [:li [:h3 title]]))
       [:p "No articles."])]))

(rf/reg-view ^{:rf/id :app/root} root-view []
  [(rf/view :pages/articles)])
```

Now install the headless SSR adapter, stand up a frame, seed it, render:

```clojure
(rf/init! ssr/adapter)                    ;; the server-side substrate — no React needed

(rf/with-new-frame [f (rf/make-frame {})]
  (rf/dispatch-sync [:articles/seed [{:id "1" :title "Hello, server"}]] {:frame f})
  (ssr/render-to-string [(rf/view :app/root)] {}))   ;; renders against the with-new-frame scope
;; => "<div class=\"page\"><h1>Recent articles</h1><ul><li><h3>Hello, server</h3></li></ul></div>"
```

**What you see:** real HTML, from your real view, on a machine with no browser. (`render-to-string` is also re-exported on the `rf/` facade — the example file spells it `rf/render-to-string`; same function.)

**Notice:** nothing about the app changed to make this work. The event handler was already pure, the subscription was already a pure derivation, the view was already data-in-hiccup-out. `render-to-string` just walks the result. This is the fact the rest of the tutorial builds on: **the app was always able to run on a server — SSR is mostly deciding when to render and what to ship.**

!!! note "Why is `subscribe` unqualified in the view?"

    Because `reg-view` injects it (and `dispatch`) into the view body — no require, no `rf/` prefix — already bound to whichever frame the view renders under. That binding is what lets one view run twice: on the JVM, deref just reads the current value and returns; in the browser, the same deref registers a reaction so the view re-renders on change. Same code, two behaviours, picked from the context.

## Step 2 — a frame per request

A server handles many requests at once, and they must not see each other's state. re-frame2's answer is the tool you already have: give **each request its own frame** — its own isolated app-db, created when the request arrives and destroyed when the response is written.

Step 1 leaned on `with-new-frame`, which destroys its frame when the block exits — perfect for a REPL. The server code below runs the same lifecycle long-hand instead — `reg-frame`, then an explicit `destroy-frame!` in a `finally` — partly so you see every step once before an adapter hides them, and partly because the frame *id* has a job before the frame exists: the incoming request is stashed against it, and the frame's boot events read it from there.

That boot event comes first. `:rf/server-init` is a reserved name the framework documents and you supply the body for; it reads the incoming HTTP request through a declared [coeffect](../core/glossary.md#coeffect) and does whatever this page needs:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc
(def sample-articles                               ;; stand-in for a real data source
  [{:id "1" :title "Hello, server"}
   {:id "2" :title "Hydration, verified"}])

(rf/reg-event :rf/server-init
  {:platforms        #{:server}                    ;; server only — Step 6 explains
   :rf.cofx/requires [:rf.server/request]}         ;; hand me the request, as data
  (fn [{:keys [db rf.server/request]} _]
    (let [limit (or (some-> (get-in request [:query-params "limit"]) parse-long) 10)]
      {:db (assoc db :articles/limit limit)
       :fx [[:dispatch [:articles/seed (vec (take limit sample-articles))]]]})))
```

The request map arrives flat under `:rf.server/request` — `:uri`, `:request-method`, `:headers`, `:query-params`, `:session`, `:cookies`. (A real page usually starts its HTTP fetches here rather than reading a `def` — the worked example fires [`:rf.http/managed`](../async/http.md) and lets the reply land before render.)

Now the per-request lifecycle, by hand:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — handle-request, condensed
#?(:clj
   (defn handle-request [request]
     (let [fid (keyword "rf.frame" (str (gensym "f")))]
       (ssr/set-request! fid request)               ;; 1. stash the request against the id
       (rf/reg-frame fid                            ;; 2. fresh frame; :initial-events run
         {:platform       :server                   ;;    synchronously as it comes up —
          :initial-events [[:rf/server-init]]})     ;;    which is why the stash came first
       (try
         (rf/with-frame fid
           (let [hiccup ((rf/view :app/root))]      ;; 3. the settled state, as hiccup
             (ssr/render-to-string hiccup {})))     ;; 4. hiccup → HTML string
         (finally
           (rf/destroy-frame! fid))))))             ;; 5. ALWAYS torn down — even on a throw
```

And because a handler is just a function, you can serve a "request" right at the REPL — no Jetty, no port:

```clojure
(handle-request {:request-method :get :uri "/" :query-params {"limit" "1"}})
;; => "<div class=\"page\"><h1>Recent articles</h1><ul><li><h3>Hello, server</h3></li></ul></div>"
```

**Notice two load-bearing details.** `reg-frame` runs its `:initial-events` synchronously and the runtime **drains** — it keeps processing events (and the events those dispatch) until the queue settles — so by the time you render, app-db holds the finished state, never a half-loaded one. And `destroy-frame!` sits in a `finally`: on a server that runs for weeks, tearing the frame down on *every* exit path is what stops you leaking a frame per request. Teardown also clears the request slot for you.

One more thing you may have caught: Step 1 handed `render-to-string` the hiccup vector `[(rf/view :app/root)]`, while this code **calls** the view — `((rf/view :app/root))` — and passes the result. `rf/view` looks up the view fn, and calling it returns the hiccup it renders to. `render-to-string` accepts either shape; the reason to hold the called tree in a local shows up in Step 3, when the same tree gets hashed.

!!! note "Why this matters"

    A hundred concurrent requests are a hundred isolated app-dbs that cannot see, race, or corrupt one another. The isolation that made [frames](../core/frames.md) good for testing is exactly what makes them safe under server load.

## Step 3 — ship the state with the HTML

The HTML alone isn't enough. When the browser's JavaScript boots, it needs the **state the server finished in** — otherwise it would re-fetch everything just to catch up. So the server ships two things: the HTML, and a serialized **payload** of that final state, embedded in the page. Swap the render half of `handle-request` for this:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — the render + payload half
;; (one new require: #?(:clj [re-frame.ssr.html-helpers :as html]) — the escaper)
(rf/with-frame fid
  (let [hiccup  ((rf/view :app/root))
        page    (ssr/render-to-string hiccup {:emit-hash? true})
        payload {:rf/version     1
                 :rf/app-db      (rf/app-db-value fid)       ;; your state
                 :rf/runtime-db  (rf/runtime-db-value fid)   ;; the framework's (route, machines)
                 :rf/render-hash (ssr/render-tree-hash hiccup)}]  ;; Step 5's tripwire
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

Run it again and the handler now answers with a full Ring response — the same map a real server puts on the wire:

```clojure
(-> (handle-request {:request-method :get :uri "/" :query-params {"limit" "1"}})
    (select-keys [:status :headers]))
;; => {:status 200, :headers {"Content-Type" "text/html"}}
;; …and (:body …) is the whole document: rendered HTML, payload script, all of it.
```

Three things to note:

- **The payload is EDN in a `<script>` tag** with the pinned id `__rf_payload` — that id is how the client finds it in Step 4.
- **It goes through an escaper** (`re-frame.ssr.html-helpers/escape-edn-script-body`). If an article body contained the literal text `</script>`, writing it raw would close the script element early and eat the rest of your state; the escaper rewrites `<` inside EDN strings so the payload survives and still reads back byte-for-byte.
- **`:rf/render-hash` is a structural fingerprint of the render tree**, and `:emit-hash?` stamps the same hash onto the root element as a `data-rf-render-hash` attribute. Both fingerprint the *called* tree held in the `let` — the exact tree the client will be checked against. Hold that thought until Step 5.

(You may spot `:doctype? true` in the worked example's render call. That opt prefixes `<!DOCTYPE html>` onto the emitted string itself — for when your root view renders the whole `[:html …]` document. This handler wraps a fragment in its own envelope, doctype included, so the render call shouldn't add another.)

This hand-rolled version ships the *whole* app-db, which is fine for a demo and a leak the moment real apps put secrets in state. Step 7's adapter makes you declare an allowlist instead — [Concepts → the fail-closed allowlist](concepts.md#payload--the-fail-closed-allowlist) is the policy in full.

## Step 4 — wake it up: hydrate on the client

The browser now has painted HTML and a payload sitting in the page. The client's job is to **adopt** both — install the state, attach listeners to the existing DOM — instead of throwing the HTML away and re-rendering from scratch. This step runs in the browser, so it needs the client build's requires; after that, one call does the work:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc — the client entry point
;; client-side requires, alongside rf and ssr:
;;   #?(:cljs [reagent.dom.client :as rdc])
;;   #?(:cljs [re-frame.adapter.reagent :as reagent-adapter])
#?(:cljs (defonce react-root (rdc/create-root (js/document.getElementById "app"))))

#?(:cljs
   (defn run []
     (rf/init! reagent-adapter/adapter)      ;; the browser substrate this time
     (rf/reg-frame :app {:platform :client}) ;; the client's own, named frame
     (let [payload (ssr/hydrate! {:frame          :app
                                  :render-tree-fn (fn [] ((rf/view :app/root)))})]
       (when-not payload
         ;; nil ⇒ nobody server-rendered this page — a plain client-only load
         (rf/dispatch-sync [:app/client-bootstrap] {:frame :app})))
     (rdc/render react-root
                 [rf/frame-provider {:frame :app}
                  [(rf/view :app/root)]])))
```

`hydrate!` does three steps in a fixed order: **read** the `__rf_payload` script, **hydrate** — dispatch `[:rf/hydrate payload]` before the first render, installing the server's app-db *and* its runtime-db slice in one atomic move — and **verify** (Step 5). It returns the payload it applied, or `nil` when there wasn't one, which is how the code above answers "was this page server-rendered?" without sniffing the DOM.

**What you see:** the page was already painted before your JS loaded. When `run` finishes, nothing flashes — the client's first render matches the HTML, the articles are in app-db without any re-fetch, and clicking things dispatches events like any other re-frame2 app. (To see it live without wiring a build: the worked example ships a hand-authored `index.html` — a frozen snapshot of what its `handle-request` serves — and its `run` hydrates it exactly this way.)

**Notice three choices in that snippet:**

- **The same frame id goes to `hydrate!` and to `frame-provider`.** The hydration target is carried, never guessed — pass no `:frame` and it fails loud rather than picking one for you. The name `:app` is yours; nothing about it is special. (The example file calls its client frame `:rf/default` — also just a name. The runtime never invents or assumes a frame.)
- **`:render-tree-fn` *calls* the view** — `((rf/view :app/root))` — because the verify step must hash the *exact* tree shape the server hashed in Step 3, and the server hashed the called form. Hand it the vector form instead and the hashes would disagree over a difference that was never real.
- **Hydration replaces; it doesn't merge.** Whatever the client pre-seeded is overwritten — on this question the server is the single source of truth. (You never registered a handler for `:rf/hydrate`: it's framework-owned, and a malformed payload is rejected wholesale, your existing state left untouched.)

## Step 5 — break it on purpose

The classic SSR bug is a **hydration mismatch**: the client's first render disagrees with the server's HTML. The causes are mundane — a date rendered in two timezones, state the server set but the client never read — and in most stacks the result is a content flash and a console warning nobody reads.

You wired the detector already: the server's `:rf/render-hash` in Step 3, and the `:render-tree-fn` you handed `hydrate!` in Step 4. The client hashes its own first render and compares. Make them disagree on purpose — render something non-deterministic:

```clojure
;; DON'T ship this — it exists to trip the alarm.
(rf/reg-view ^{:rf/id :pages/articles} articles-page []
  [:div.page
   [:p (str "Rendered at " #?(:clj (System/currentTimeMillis) :cljs (js/Date.now)))]
   …])
```

**What you see:** the page still works — the default recovery is *warn and replace*, so the client's view wins and the user never sees a broken page. But the trace stream now carries a structured error instead of a shrug:

```clojure
{:operation :rf.ssr/hydration-mismatch
 :op-type   :error
 :tags      {:server-hash "a3f29c01"           ;; the tree the server shipped…
             :client-hash "0b77e4d2"           ;; …vs the client's first render
             :frame       :app
             :failing-id  :rf/hydrate
             :recovery    :warned-and-replaced}}
```

**And what you don't see:** *which node* diverged. The hash is a fingerprint of the whole render tree — cheap enough to leave on everywhere, and it proves the renders differ and records what the runtime did about it, but locating the divergent node is a tree-diff, and that's tooling's job. (The trace has an optional `:first-diff-path` tag — a path into the render tree — that a host running its own diff can attach via [`verify-hydration!`](../api/re-frame.ssr.md).) Out of the box, your debugging move is the time-honoured one: hunt the non-determinism — clocks, locales, unordered collections. Here, you planted it.

What the detector guarantees is that the bug is **never silent**. For CI, escalate it: a frame registered with `:ssr {:on-mismatch :hard-error}` throws a structured exception instead of warning, so a mismatch fails the build rather than shipping. Then fix the view the right way — put the timestamp in app-db at init, where it rides the payload and both sides render the same value.

## Step 6 — gate the one-sided code: `:platforms`

Some work is meaningless on one side. `localStorage` doesn't exist on the JVM; the request coeffect doesn't exist in the browser. You don't branch in handler bodies — you declare, once, where a capability is allowed to run:

```clojure
;; cf. examples/capabilities/ssr/ssr/core.cljc
(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}                 ;; the server render skips this, with a trace
  (fn [_ {:keys [token]}]
    #?(:cljs (.setItem js/localStorage "auth/token" token))))
```

When a server-side drain meets a `#{:client}` effect it skips it and emits a `:rf.fx/skipped-on-platform` trace — the handler that returned it never learns which runtime it's on. Coeffects carry the same gate in mirror: `:rf.server/request` is `#{:server}`, so the same handler running client-side after hydration simply doesn't receive it. One handler, two platforms, zero `typeof window` checks.

## Step 7 — swap in the Ring adapter

You've now built every step of the lifecycle by hand: stash the request, create the frame, drain, render, build the payload, respond, tear down. In production you don't hand-roll that — `day8/re-frame2-ssr-ring` packages it as one handler constructor, and because you built it yourself in Steps 2–3, every option below reads as narration rather than magic:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[re-frame.ssr.ring  :as ssr.ring])

(def handler
  (ssr.ring/ssr-handler
    {:initial-events [[:rf/server-init]]     ;; Step 2's boot event
     :root-view      [:app/root]             ;; Step 2's render target
     :payload        [:articles]}))          ;; Step 3's payload — now an allowlist

(jetty/run-jetty handler {:port 3000 :join? false})
```

One thing is new, and it's the important one: **`:payload` is required, and it's an allowlist.** Name the top-level app-db keys that may ship; everything else stays on the server — *including keys you haven't written yet*. Omit it entirely and construction throws `:rf.error/ssr-missing-payload-policy` at boot, not a quiet leak on the first request. (Shipping everything is still possible, but you say it out loud: `:rf.ssr.payload/whole-app-db`.)

**What you see:** `curl localhost:3000` returns the full document — rendered HTML, the `__rf_payload` script, the hash on the root element — and the client from Step 4 hydrates it unchanged.

That's the everyday surface, end to end. [Concepts](concepts.md) carries the rest: the request lifecycle in one diagram, response control (`:rf.server/*` — status, headers, cookies, redirects), head metadata for crawlers, what happens when the server throws, and streaming for pages with independently-slow regions.
