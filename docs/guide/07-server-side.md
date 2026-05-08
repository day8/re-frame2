# 07 — The server side

For most of the SPA era, "server-side rendering" has been an awkward retrofit. You built your client app with React, then later — when SEO mattered, or first-paint was too slow, or social-media link previews didn't render — you bolted on Next.js or its equivalent. The server-rendered code path was different from the client-rendered one. Subtle bugs lived in the seam.

re-frame2 takes a different stance: **server-side rendering is a first-class concern from day one**. It's not a future addition. It shapes the architecture. The architecture happens to be fine for client-only apps too — but if you ever want SSR, the foundations are already in place.

This chapter explains how that works.

## The core idea

A server-side render is just *another instance of the app*, running in another runtime, producing a string instead of a DOM tree.

Concretely:

1. An HTTP request arrives at the server.
2. The server creates a frame for this request.
3. The frame's `:on-create` event fires; setup events dispatch (load user session, fetch initial data, set the route).
4. The runtime drains. State settles.
5. The server calls the registered root view fn, gets back a hiccup tree.
6. The server runs `render-to-string` on the hiccup, producing HTML.
7. The server ships the HTML *and* the serialised state down to the client.
8. The client boots, reads the serialised state, dispatches `:rf/hydrate` to seed its own frame, then renders. The client's first render produces the same HTML the server sent.
9. From there on, the app is interactive.

The crucial fact: **steps 2-5 are running the same handlers and views you've already written**. There's no separate "server code" you maintain in parallel. There's one app. It happens to run twice — once on the server, then again on the client — with a state-shipping handshake in between.

## Why this works at all

It works because the architecture has been *structurally* SSR-friendly from the start. Three properties make this possible:

**Pure event handlers.** A handler is `(state, event) → effects`. It's the same on server and client. There's no `window`. There's no `document`. There's no React-specific lifecycle. You can run it on the JVM.

**Pure subscriptions.** Same. State in, value out. JVM-runnable.

**Render-tree as serialisable data.** Hiccup is just nested vectors and maps. There's a pure function — `(rf/render-to-string hiccup-tree)` — that turns any hiccup into a string. No React. No DOM. No JavaScript runtime. It's a function from data to string, and it runs on the JVM.

These three properties aren't accidents. They're consequences of the broader pattern decisions ([chapter 09](09-the-dynamic-model.md) covers the philosophy). The same constraints that make handlers testable, debuggable, and AI-amenable also make them *runnable on a server without a browser*.

## The hydration handshake

The interesting question, when the client takes over, is: **how does the client get into the same state the server was in?**

The naive approach is to re-do all the work. The client hits the page, runs the same `:on-create` event, makes the same fetches, drains the same state changes, ends up in (presumably) the same state. But this defeats the point of SSR — you've made the user wait twice for the same data.

The re-frame2 approach: the server **serialises the final state** and ships it down with the HTML. The client reads that state and seeds its own frame.

```clojure
;; Server side
(let [final-db (rf/get-frame-db request-frame)
      hiccup   [(rf/view :app/root)]
      html     (rf/render-to-string hiccup {:frame request-frame})
      payload  {:rf/version    "1.0"
                :rf/frame-id   :app/main
                :rf/app-db     final-db
                :rf/render-hash (rf/render-tree-hash hiccup)}]
  ;; ... ship html + payload to the client
  )

;; Client side
(defn ^:export run []
  (let [payload (read-server-payload)]
    (when payload
      (rf/dispatch-sync [:rf/hydrate payload] {:frame :app/main}))
    (rdc/render root [(rf/view :app/root)])))
```

`get-frame-db` returns the current `app-db` *value* — a plain map, not a deref-able container — so there's no `@` in front. `(rf/view :id)` looks up the registered render fn by id; the canonical hiccup head is the looked-up fn placed inside a vector (`[(rf/view :app/root)]`), so Reagent / the SSR emitter treats the call as a component. `render-tree-hash` is the framework's stable structural hash — both server and client compute it from the same canonical-EDN representation, which is what makes the mismatch detection below reliable.

The framework's default `:rf/hydrate` handler is **`:replace-app-db`**: the server's serialised slice replaces whatever the client bootstrap had pre-seeded. This is locked. The reasoning: the server is authoritative for the initial app-db, and a defaulting merge policy would leave subtle ordering bugs (which slice wins?) under the rug.

If you do need client-only transient state to survive hydration — a `:browser/window-size` populated by a `:rf/window-resize` listener firing before hydration arrived — the customisation point is *re-registering* `:rf/hydrate` with your own handler that performs an explicit merge in the order you intend. The default is replace; opt-in merge is the user's choice and the user owns the semantics.

After this, the client renders. Because it's running with the server-supplied state, its first render produces the same hiccup tree the server rendered, which produces the same HTML — and the existing DOM (from the server-shipped HTML) is matched, not replaced. The transition from server-rendered to interactive is invisible to the user.

## Hydration mismatches

Sometimes the first client render doesn't match the server's HTML. This is *the* SSR bug. The causes are usually mundane:

- The server and client have different timezones; a date renders differently.
- The server didn't read a piece of state that the client did.
- The hash of the rendered output drifted between server and client because the data was an ordered map vs an unordered map.

re-frame2 detects mismatches because the server ships a hash of its render-tree alongside the state. The client computes the same hash on its first render and compares. On mismatch, the runtime emits a structured trace event:

```clojure
{:operation       :rf.ssr/hydration-mismatch
 :op-type         :error
 :tags            {:server-hash    "abc123"
                   :client-hash    "def456"
                   :first-diff-path [:articles 0 :date]}
 :recovery        :warned-and-replaced}
```

The default recovery is "warn and replace" — log the mismatch, render the client's view, replace the server's HTML. This avoids a broken page; the cost is a flash. In a strict mode (per-app config), the mismatch escalates to a hard error.

Either way, **the mismatch is detectable**. You don't have to wait for a user to file a "the page flashed weirdly" bug. You see the trace event in development and you can wire it into your production monitoring. This is the kind of thing the structured trace stream buys you.

## Effects on the server

A real per-request server flow does work — fetches initial data, reads sessions, sets the route. Some of that work uses fx (registered effects). But not every effect makes sense on the server: writing to localStorage, for instance, is a browser-only thing.

re-frame2 handles this with **`:platforms` metadata** on `reg-fx`:

```clojure
(rf/reg-fx :http
  {:platforms #{:server :client}}     ;; runs in both contexts
  (fn [m args] ...))

(rf/reg-fx :localstorage/set
  {:platforms #{:client}}              ;; client-only
  (fn [_ args] ...))

(rf/reg-fx :rf.server/set-status
  {:platforms #{:server}}              ;; server-only
  (fn [_ args] ...))
```

When the runtime is in server-platform mode and a handler returns an effect map containing `[:localstorage/set ...]`, the resolver skips it and emits a `:rf.fx/skipped-on-platform` trace event. The handler doesn't need to know it's running on the server — it just describes the effects it wants, and the runtime gates them.

This means **the same event handler works in both contexts**. A login flow's `:auth/login-success` handler dispatches `[:auth.session/store {:token ...}]`, which fires `[:localstorage/set ...]` as an fx, which gets silently skipped on the server. The server doesn't have a localStorage; the skip is the right behaviour. The handler stays single-purpose, no branching, no runtime checks.

## The server response is more than HTML

A real HTTP response is HTML *plus* a status code, plus headers, plus cookies, plus the occasional redirect. Treating SSR as "render a string" misses everything except the body. re-frame2's SSR substrate owns the whole response: the runtime carries a per-request response accumulator, and event handlers populate it with first-class effects.

```clojure
(rf/reg-event-fx :auth/server-init
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ request]]
    (if (valid-session? request)
      {:db (assoc db :session (:session request))}
      {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]})))

(rf/reg-event-fx :rf.route/handle-url-change
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ url]]
    (if-let [route (rf/match-url url)]
      {:db (assoc db :route route)}
      {:fx [[:rf.server/set-status 404]
            [:rf.server/set-header {:name "Cache-Control" :value "no-store"}]]})))
```

The standard server fxs (`:rf.server/set-status`, `:rf.server/set-header`, `:rf.server/append-header`, `:rf.server/set-cookie`, `:rf.server/redirect`) are all `:platforms #{:server}` and write to a structured accumulator the host adapter consumes. Cookies are structured maps, not raw strings — the adapter does the wire serialisation, so you never write `Set-Cookie:` by hand and you never trip on per-attribute quoting bugs. Redirects short-circuit the render — a `302` skips body rendering and the hydration-payload serialisation; the host adapter sees `{:redirect {:status 302 :location ...}}` and emits the right wire response. Multiple `set-status` calls? The runtime takes the last write and emits a `:rf.warning/multiple-status-set` trace event so tooling can find the conflict.

The full contract — every fx, every default, the full request-handler return shape — lives in [Spec 011 §HTTP response contract](../../spec/011-SSR.md#http-response-contract).

## Head and meta — `<title>`, `<meta>`, JSON-LD

The server-rendered HTML must carry head metadata on first byte, because crawlers and link-unfurlers don't run JS. The pattern's commitment: **the head model is data derived from app-db**, not an imperative DOM API.

A registered head function plus per-route `:head` metadata is what produces the head model:

```clojure
(rf/reg-route :article/show
  {:path     "/articles/:slug"
   :on-match [[:article/load]]
   :head     :article/head})

(rf/reg-head :article/head
  (fn [db]
    (let [article (get-in db [:articles :data])]
      {:title (:title article)
       :meta  [{:name "description" :content (:summary article)}
               {:property "og:image" :content (:hero article)}]
       :link  [{:rel "canonical" :href (canonical-url article)}]})))
```

The runtime renders the head model to HTML, ships it in the document, and on the client side the same head function runs and the runtime hashes both server and client head models for mismatch detection. Same idiom as the body: data → render-tree → HTML, with structural-equivalence hashing as the lock. See [Spec 011 §Head/meta contract](../../spec/011-SSR.md#headmeta-contract).

## Server errors are sanitised

A handler exception, a schema-validation failure, or a missing-route on the server has two audiences: the operator (who needs full detail to diagnose) and the user (who should see a friendly error page, not a stack trace). re-frame2 separates the two surfaces.

The trace stream carries the **internal** error — full structured detail, stack trace, all the data the operator needs. The HTTP response carries the **public** projection — a sanitised, locked-shape map (`{:status :code :message :retryable?}`) registered as `:rf/public-error`. A registered error projector maps trace events to public shapes; the runtime renders an error-page view receiving only the public shape as a prop. The error-page view *cannot accidentally leak the internal detail* — it never sees it.

```clojure
(rf/reg-error-projector :myapp/public-error
  (fn [trace-event]
    (case (:operation trace-event)
      :rf.error/no-such-handler            {:status 404 :code :not-found
                                             :message "Page not found." :retryable? false}
      :rf.error/schema-validation-failure  {:status 400 :code :bad-request
                                             :message "Invalid request." :retryable? false}
      {:status 500 :code :internal-error :message "Something went wrong." :retryable? true})))
```

In dev, the projector's output also carries `:details` so the developer can see the trace. In prod, `:details` is absent — the public shape is exactly the four locked keys, and the security boundary is unconditional. The framework ships a default projector (`:rf.ssr/default-error-projector`) with the canonical mapping, so you only register your own when you want different status codes or messages. See [Spec 011 §Server error projection](../../spec/011-SSR.md#server-error-projection).

## The server's per-request frame

A server handling concurrent requests can't have one global frame — each request needs its own state. re-frame2 makes this explicit: each request creates a frame, runs setup, renders, destroys the frame.

```clojure
(defn handle-request [request]
  (rf/with-frame [f (rf/make-frame {:on-create [:rf/server-init request]})]
    ;; make-frame returns the gensym'd frame id (a keyword under :rf.frame/);
    ;; with-frame binds *current-frame* to it for the body. The frame's
    ;; :on-create dispatches synchronously when the frame is created, so by
    ;; the time the let-body runs the frame is already initialised.
    (let [final-db (rf/get-frame-db f)
          hiccup   [(rf/view :app/root)]
          html     (rf/render-to-string hiccup {:frame f})]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (page-template html (pr-str (build-payload f hiccup)))})))

;; Test fixtures destroy the frame between runs via (rf/destroy-frame f);
;; long-lived servers typically destroy after the response is shipped to free
;; the per-frame caches.
```

The pattern from [chapter 04](04-views-and-frames.md) — frames as isolated runtime boundaries — is what makes this clean. Each request lives in its own frame; the frames don't share state. Concurrent requests don't pollute each other.

The `:on-create` event for the per-request frame typically dispatches setup via something like:

```clojure
(rf/reg-event-fx :rf/server-init
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ request]]
    {:db (-> db
             (assoc :session (:session request))
             (assoc :route (parse-url (:uri request))))
     :fx [[:rf.http/managed
           {:request    {:method :get :url "/api/initial-data"}
            :on-success [:initial-data/loaded]}]]}))
```

The drain settles before `with-frame` returns: the managed-HTTP request runs (on the JVM the fx uses `java.net.http.HttpClient`; the per-spec contract is a single drain that completes before render), the reply lands, the `:initial-data/loaded` handler runs, state is final. *Then* `render-to-string` runs, against the now-stable state.

This is why run-to-completion drain matters for SSR: the server can't render half-resolved state. Drain ensures it doesn't.

## Routing and SSR

Routing on the server is the same as routing on the client. The route slice in `app-db` is set by `:rf.route/handle-url-change` (per [chapter 04](04-views-and-frames.md) and [Spec 012](../../spec/012-Routing.md)). The same handler runs server-side, fed the request URL.

```clojure
(rf/reg-event-fx :rf/server-init
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ request]]
    {:db (assoc db :session (:session request))
     :fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]
          ;; ... per-route data fetches
          ]}))
```

The view dispatches on the route id (case-on-`:rf.route/id` at the root, per the [routing example](../../examples/routing/core.cljs)) — and because the server-rendered route is the same data shape as the client route, the view code is identical.

The routing substrate has more to it than fits in this chapter — deterministic route ranking, navigation tokens (an epoch carried through async work so stale fetch-results from the previous route get suppressed cleanly), fragment as a first-class slice, `:can-leave` guards that pause navigation through `:rf/pending-navigation` for "unsaved changes?" prompts. The full contract is in [Spec 012](../../spec/012-Routing.md). The server-side relevance of all this: it's the same code on both sides; whichever affordance you reach for client-side has the same shape on the server.

## What you give up

A fully server-side-rendered SPA has constraints:

**Views must be deterministic given the state.** A view that reads `(js/Date.)` will produce different output on server and client. Fix: read time from `app-db`, populated by the `:rf/server-init` cofx.

**Views must not have render-time side-effects.** No `js/setTimeout` in a render fn. No `js/console.log`. No `.preventDefault` calls outside event handlers. The render-tree is a function of state; if it isn't, hydration mismatches.

**Some browser-specific UI work has to wait for hydration.** A focus-trap, a scroll-restoration, an `IntersectionObserver` setup — these are all `:platforms #{:client}` fx, dispatched after hydration completes. The server-rendered DOM doesn't have them; that's fine, the user can't interact with the page until JS is loaded anyway.

These constraints are tight. They're also constraints that good React/Vue/Svelte developers already follow, often instinctively. re-frame2 makes them part of the architecture rather than guidelines.

## Streaming SSR (and why it isn't in v1)

Modern frameworks like Next.js with React Server Components or SvelteKit ship parts of the page before the full state has computed. The shell renders quickly; data-bound regions render later, in chunks, as their data resolves. This is "streaming SSR."

re-frame2 doesn't ship streaming SSR in v1. The pattern provides the *primitives* — frames per request, run-to-completion drain, pure render-tree — and a host implementation can layer streaming on top, but the framework's commitment is just "non-streaming render-to-string."

This is a deliberate scope decision. Streaming SSR adds complexity (chunked HTTP responses, suspense-equivalent boundaries, ordered payload delivery) that we'd rather not commit to before the simpler case is solid. If you need streaming, you'll need a small extension. If you don't, the basic SSR is enough.

## A note on hosting

re-frame2's CLJS reference uses the JVM for the server side: a Clojure server (Ring, Pedestal, or your choice) calls `handle-request`, which uses re-frame2's pure functions to render. The `app-db` lives in a request-scoped atom; the rendering is JVM-only and synchronous.

For non-JVM hosts (Bun, Node, Deno), the same pattern applies but the implementation differs — the runtime would compile to JS and run in the host's runtime. Several CLJS projects already do this for Node-flavoured server-side. re-frame2's reference doesn't currently provide a Node-side runtime, but the pattern doesn't preclude it.

For non-Clojure hosts entirely (a TypeScript port of re-frame2), the exact same architecture applies. Your event handlers and subs and views are TypeScript functions; your server is whatever Node-shaped runtime you prefer; the render-tree → string emitter is a TypeScript function. The pattern survives. The host changes.

## What we covered

- SSR is a first-class concern; re-frame2's architecture supports it from day one.
- The core idea: run the same app on the server, render to a string, ship state to the client, hydrate.
- Pure handlers + pure subs + serialisable render-tree make this possible structurally.
- `:platforms` metadata gates which fx run server-side.
- The HTTP response is owned by the substrate — status, headers, cookies, redirects flow through registered server fxs.
- Head/meta is data, derived from app-db via a registered head function; head mismatches are detected the same way body mismatches are.
- Server errors are sanitised before they reach the response — internal detail rides the trace stream, the public projection is a locked four-key shape.
- Hydration is locked at `:replace-app-db`; opt-in merge is the user's customisation.
- Per-request frames isolate concurrent requests.
- Hydration mismatches are detectable via structured trace events.
- Streaming SSR is post-v1; the primitives are in place.

## Next

- [08 — From re-frame v1](08-from-re-frame-v1.md) — what changes (and what doesn't) if you're already a re-frame user.
