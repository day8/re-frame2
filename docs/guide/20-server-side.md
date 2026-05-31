# 20 - The server side

You want server-rendered HTML that's on the wire before any JavaScript loads, that a crawler and a link-unfurler can read, that hydrates cleanly into your live app, and — the part everyone dreads — that runs your *real* event handlers on the JVM instead of making you maintain a shadow copy of your app for the server. This chapter is SSR as the same six dominoes, server-side. No second mental model, no `if (typeof window === 'undefined')` branches salted through your code, no parallel "server views" file. There's one app. It runs twice.

## The whole idea, in one sentence

**A server-side render is just another instance of your app, running in another runtime, producing a string instead of a DOM tree.**

That's not a slogan I'm going to walk back over the next twenty paragraphs — it's the literal mechanism, and once you believe it the rest of the chapter is consequences. The handlers you wrote, the subs you wrote, the views you wrote: they run on the server, unmodified, against a frame whose `app-db` you seed with the same kind of setup events you'd dispatch in dev. The only thing that's different at the end is that instead of patching the DOM, the runtime serialises the hiccup to an HTML string.

It clears a real bar, too: SEO-ready first paint, social-media link previews, deep-link hydration, per-request response control — feature parity with Next.js, Remix, and SolidStart. The difference is that those frameworks make SSR a thing you *configure and reason about separately*. Here it falls out of the architecture you already have.

## Why this is even possible

It's worth understanding *why* SSR is cheap here, because the answer is the entire thesis of the framework cashing out. SSR is hard in most stacks because the app is tangled up with the browser — `window`, `document`, component lifecycle, effects that fire during render. re-frame2 has been structurally SSR-friendly since before it asked the SSR question, because of three properties it committed to for completely different reasons:

**Event handlers are pure.** A handler is `(state, event) → effects`. No `window`, no `document`, no React lifecycle. It's a function from data to data, and a JVM runs it just fine.

**Subscriptions are pure.** State in, value out. Same story. JVM-runnable.

**The render-tree is serialisable data.** Hiccup is nested vectors and maps — that's all it is. There's a pure function, `(rf/render-to-string hiccup-tree)`, that turns any hiccup into a string with no React, no DOM, no JS runtime in sight. Data to string, on the JVM.

None of those three are accidents, and none of them were added *for* SSR. They're the same constraints that make handlers testable ([chapter 13](13-testing.md)), make the app debuggable and replayable ([chapter 16](16-observability.md)), and make it AI-amenable. The constraints from the [dynamic-model chapter](21-dynamic-model.md) that bought you all of that *also*, as a corollary, make the app runnable on a server without a browser. SSR isn't a feature someone bolted on. It's a thing that was always going to work.

## The request, step by step

Here's a full server render, start to finish:

1. An HTTP request arrives.
2. The host adapter (e.g. `re-frame.ssr.ring`) creates a **frame for this request** and binds the request map so handlers can read it.
3. The frame's `:on-create` fires; setup events dispatch — load the session, fetch initial data, set the route.
4. The runtime drains. State settles.
5. The server calls your registered root view fn and gets back a hiccup tree.
6. `render-to-string` turns that hiccup into HTML.
7. The server ships the HTML **and** the serialised state to the client.
8. The client boots, reads the serialised state, dispatches `:rf/hydrate` to seed *its* frame, then renders — and its first render produces the same HTML the server sent.
9. From there the app is interactive. Subsequent form POSTs flow back through the same handler tree.

Steps 2 through 5 are running **the handlers and views you already wrote**. There is no separate "server code." There's one app, run twice — once on the server, once on the client — with a state-shipping handshake in between. The per-request frame in step 2 is exactly the frame from [chapter 18](18-frames.md): an isolated `app-db`, so a hundred concurrent requests are a hundred isolated states that can't pollute each other.

## The hydration handshake

The interesting question is step 8: when the client takes over, *how does it land in the same state the server was in?*

The naive answer is "re-do all the work" — the client runs the same `:on-create`, makes the same fetches, drains to the same state. But that makes the user wait twice for the same data and defeats the point of SSR entirely. The re-frame2 answer is: the server **serialises its final state** and ships it down with the HTML, and the client seeds its frame from that.

```clojure
;; ---- Server side ----
(let [final-db (rf/get-frame-db request-frame)
      hiccup   [(rf/view :app/root)]
      html     (rf/render-to-string hiccup {:frame request-frame})
      payload  {:rf/version     "1.0"
                :rf/frame-id    :app/main
                :rf/app-db      final-db
                :rf/render-hash (rf/render-tree-hash hiccup)}]
  ;; ... ship html + payload to the client ...
  )

;; ---- Client side ----
(defn ^:export run []
  (let [payload (read-server-payload)]
    (when payload
      (rf/dispatch-sync [:rf/hydrate payload] {:frame :app/main}))
    (rdc/render root [(rf/view :app/root)])))
```

A couple of shape notes so the code reads cleanly: `get-frame-db` returns the current `app-db` *value* — a plain map, not a deref-able container — so there's no `@`. `(rf/view :id)` looks up the registered render fn by id; the canonical hiccup head is that fn inside a vector (`[(rf/view :app/root)]`) so the emitter treats it as a component. `render-tree-hash` is a stable structural hash both sides compute from the same canonical-EDN representation — it's what makes mismatch detection (below) reliable.

The default `:rf/hydrate` behaviour is **`:replace-app-db`**: the server's serialised slice replaces whatever the client bootstrap pre-seeded. This is locked, and the reasoning is sound — the server is authoritative for the initial `app-db`, and a defaulting *merge* policy would bury "which slice wins?" ordering bugs. If you genuinely need client-only transient state to survive (a `:browser/window-size` set by a resize listener that fired before hydration arrived), the customisation point is to re-register `:rf/hydrate` with your own handler that performs an explicit merge in the order *you* intend. The default replaces; opt-in merge is your choice and you own its semantics.

After hydration the client renders, and because it's running with the server-supplied state its first render produces the same hiccup → the same HTML → and the existing server-shipped DOM is *matched, not replaced*. The hand-off from server-rendered to interactive is invisible.

## Notice what isn't there

Step back from the mechanics and look at the negative space, because that's where the value is.

There's no second codebase. No "server views" file. No `if (typeof window === 'undefined')` branches threaded through handlers. No parallel data-loading pipeline that exists only because SSR demanded one. The server render *is* your app — same handlers, same subs, same views, against a frame seeded by the same setup events you use in dev. The output happens to be a string. That's the only difference, and it's the whole difference.

**SSR is the same code as client render, in another runtime.** No carve-outs, no server-only layer. The seam vanishes because there was never a seam — the architecture committed to pure handlers, pure subs, and a serialisable render-tree before it ever asked the SSR question, and those commitments turned out to be exactly the ones that make the app JVM-runnable.

## Hydration mismatches — and why you'll see them coming

Sometimes the client's first render *doesn't* match the server's HTML. This is *the* SSR bug, the one that produces a flash and a shrug in stacks that don't instrument it. The causes are usually mundane:

- Server and client are in different timezones and a date renders differently.
- The server didn't read a piece of state the client did.
- The render hash drifted because the data was an ordered map on one side and unordered on the other.

re-frame2 catches these because the server ships a *hash* of its render-tree alongside the state. The client computes the same hash on its first render and compares. On mismatch, the runtime emits a structured trace event:

```clojure
{:operation       :rf.ssr/hydration-mismatch
 :op-type         :error
 :tags            {:server-hash    "abc123"
                   :client-hash    "def456"
                   :first-diff-path [:articles 0 :date]}
 :recovery        :warned-and-replaced}
```

Note `:first-diff-path` — it doesn't just tell you *that* the trees diverged, it tells you *where*. The default recovery is "warn and replace": log it, render the client's view, replace the server HTML. That avoids a broken page at the cost of a flash; a strict per-app mode escalates the same mismatch to a hard error. Either way the mismatch is **detectable** — you see the trace event in dev and you can wire it into production monitoring instead of waiting for a user to file a "the page flickered weirdly" bug. That's the structured trace stream from [chapter 16](16-observability.md) earning its keep.

## `:platforms` — the same handler, gated per runtime

A real per-request flow does work — fetches data, reads sessions, sets the route. Some of that work is fine on the server (HTTP) and some makes no sense there (writing to `localStorage`). re-frame2 handles this with `:platforms` metadata on `reg-fx`:

```clojure
(rf/reg-fx :http
  {:platforms #{:server :client}}     ;; both contexts
  (fn [m args] ...))

(rf/reg-fx :localstorage/set
  {:platforms #{:client}}             ;; client-only
  (fn [_ args] ...))

(rf/reg-fx :rf.server/set-status
  {:platforms #{:server}}             ;; server-only
  (fn [_ args] ...))
```

When the runtime is in server-platform mode and a handler returns an effect map containing `[:localstorage/set ...]`, the resolver simply *skips* it and emits a `:rf.fx/skipped-on-platform` trace. The handler never learns it's on the server — it describes the effects it wants, and the runtime gates them. So your `:auth/login-success` handler can dispatch `[:auth.session/store {:token ...}]`, which fires a client-only `[:localstorage/set ...]`, and the same handler runs in both contexts: on the client the localStorage write happens, on the server it's silently and correctly skipped. No branching, no runtime check, one single-purpose handler.

---

## Reference and advanced topics

The sections below are per-topic reference — independent of each other, reach for them when the topic comes up. The Ring host adapter is the canonical wiring from a real HTTP server; the request cofx is how handlers read request data; the server-response effects are the full HTTP-response surface; the two patterns cover parallel-fetch and form-POST; head/meta is data like the body; server errors are sanitised before they reach the wire; and a few scope notes close it out.

## The Ring host adapter

The SSR runtime owns the request lifecycle — frame create → drain → response read → frame destroy — and a structured response shape, but it never writes to a network socket. The **host adapter** wires that shape to a real HTTP server. The CLJS reference ships `re-frame.ssr.ring` as the canonical adapter for Ring, the standard Clojure HTTP-server abstraction that Pedestal, HttpKit, Reitit-ring, and Jetty all speak:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[re-frame.ssr.ring  :as ssr-ring])

(def handler
  (ssr-ring/ssr-handler
    {:on-create    [:rf/server-init]      ;; the Ring request is conj'd as the last arg
     :root-view    [(rf/view :app/root)]
     :html-shell   my-app/html-shell      ;; optional; a sensible default ships
     :payload-keys [:public/articles      ;; allowlist of top-level app-db keys
                    :public/user]}))      ;; to ship in the hydration payload

(jetty/run-jetty handler {:port 3000 :join? false})
```

`ssr-handler` gensyms a per-request frame-id, stashes the Ring request so the request cofx can read it during the drain, registers the frame with `:platform :server`, lets the drain settle synchronously, reads the response accumulator, renders the root view, materialises structured cookies and headers to wire shape, and destroys the per-request frame in a `finally` block. The per-frame teardown drops the request slot, so nothing leaks across requests; a redirect short-circuits the body render. It's also available as Ring middleware (`ssr-middleware`) when SSR is one handler among several in a stack.

The hydration-payload policy is **explicit and fail-closed** — and this is a security boundary, so it's worth caring about. Every handler MUST declare one of two opts:

- `:payload-keys [<keys>]` — an **allowlist**. Everything else is dropped, *including keys you add later as the app grows*. This is the recommended mechanism precisely because a denylist would silently leak every new server-only key the moment it's introduced; an allowlist forces a deliberate edit per new wire-bound key.
- `:payload-policy :rf.ssr.payload/whole-app-db` — explicit opt-in to ship the entire `app-db`. Only for apps whose `app-db` is structurally safe to expose end-to-end.

Absence of *both* throws `:rf.error/ssr-missing-payload-policy` at handler-construction time, so a misconfigured deployment fails at boot rather than leaking on its first request.

Non-Ring hosts implement the same contract: stash the request in the per-frame slot before the drain, drive the runtime through `make-frame` / `get-response` / `render-to-string`, materialise the resolved response, and rely on per-frame teardown to clear the slot. A dynamic `Var` or thread-local for request state is explicitly *forbidden* — frame-id-keyed storage is the canonical mechanism, so per-request state can never leak across concurrent requests under any scheduler.

## Reading the request — the `:rf.server/request` cofx

The adapter binds the request; `:rf.server/request` is the cofx your handlers use to read it. It's gated `:platforms #{:server}`, so a client dispatch no-ops (the same handler runs on both platforms; the read just returns `nil` on the client):

```clojure
(rf/reg-event-fx :rf/server-init
  {:platforms #{:server}}
  [(rf/inject-cofx :rf.server/request)]
  (fn [{:keys [db rf.server/request]} _]
    (let [{:keys [uri request-method headers session]} request]
      {:db (-> db
               (assoc :session session)
               (assoc :route   (parse-url uri)))
       :fx [[:dispatch [:rf.route/handle-url-change uri]]]})))
```

The request map carries `:uri`, `:request-method`, `:headers`, `:query-params`, `:form-params` (set by the adapter for POSTs), `:session`, `:cookies`. Read the cofx **once**, at `:rf/server-init`, and thread the values you need down into your loaders via the dispatched events' args. Don't read it from deep inside child machines — that would make those children server-only and break the "same machine for client navigation" property that makes the whole thing pull its weight. (The 2-arity form `(inject-cofx :rf.server/request {:uri "/articles" ...})` supplies an explicit override, handy in tests that drive the drain without a real adapter.)

## The server response is more than HTML

A real HTTP response is HTML *plus* a status code, headers, cookies, and the occasional redirect. Treating SSR as "render a string" misses everything but the body. re-frame2's SSR substrate owns the whole response: the runtime carries a per-request response accumulator, and handlers populate it with first-class effects.

```clojure
(rf/reg-event-fx :auth/server-init
  {:platforms #{:server}}
  (fn [{:keys [db]} [_ request]]
    (if (valid-session? request)
      {:db (assoc db :session (:session request))}
      {:fx [[:rf.server/redirect {:status 302 :location "/login"}]]})))
```

The standard server effects all carry `:platforms #{:server}` and write to the structured accumulator the adapter consumes:

| fx-id | semantics | notes |
|---|---|---|
| `:rf.server/set-status` | Set the HTTP status code. | Last write wins; a `:rf.warning/multiple-status-set` trace flags conflicts. |
| `:rf.server/set-header` | Set a response header (replacing prior value). | Name and value are strings. |
| `:rf.server/append-header` | Append a value (multi-value headers like `Set-Cookie`, `Vary`). | Preserves prior values. |
| `:rf.server/set-cookie` | Set a cookie via a structured map. | The adapter does the wire serialisation — you never write `Set-Cookie:` by hand or trip on per-attribute quoting. |
| `:rf.server/redirect` | Short-circuit the render with a redirect. | A 302 skips body render and payload serialisation; the adapter emits the right wire response. |

Each effect writes through the handler's return map, which the adapter ultimately serialises to the wire. The point: response control is *data your handlers emit*, not an imperative response object you mutate.

## Head and meta — `<title>`, `<meta>`, JSON-LD

The server HTML must carry head metadata on the first byte, because crawlers and unfurlers don't run JS. The commitment is the one you'd predict by now: **the head model is data derived from `app-db`**, not an imperative DOM API. A registered head fn plus per-route `:head` metadata produces it:

```clojure
(rf/reg-route :article/show
  {:path "/articles/:slug" :on-match [[:article/load]] :head :article/head})

(rf/reg-head :article/head
  (fn [db]
    (let [article (get-in db [:articles :data])]
      {:title (:title article)
       :meta  [{:name "description" :content (:summary article)}
               {:property "og:image" :content (:hero article)}]
       :link  [{:rel "canonical" :href (canonical-url article)}]})))
```

The runtime renders the head model to HTML, ships it in the document, and the same head fn runs on the client — with both head models hashed for mismatch detection, exactly like the body. Same idiom: data → render-tree → HTML, with structural hashing as the lock.

## Server errors are sanitised

A handler exception, a schema rejection, or a missing route on the server has two audiences: the operator who needs full detail to diagnose, and the user who should see a friendly page, not a stack trace. re-frame2 splits the two surfaces. The trace stream carries the **internal** error — full structured detail, stack trace, everything. The HTTP response carries only the **public** projection — a sanitised, locked-shape map registered as `:rf/public-error`:

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

The error-page view receives *only* the public shape as a prop — it **cannot** accidentally leak internal detail because it never sees it. In dev the projector's output also carries `:details` so you can see the trace; in prod `:details` is absent and the public shape is exactly the four locked keys. The framework ships a default projector with a sensible mapping, so you register your own only to change status codes or messages. ([Chapter 14 §Server-side errors](14-errors.md) goes deeper on the projection idiom.)

## Pattern-SSR-Loaders — parallel data fetch during the drain

A `:rf/server-init` that fires one HTTP request and lets the drain settle is fine for one fetch. But real pages need *several* independent fetches before render — the product, the related items, the recent reviews — and serialising three back-to-back managed-HTTP calls from one setup event adds their wall-clock costs together, because the drain runs to fixed point in a single thread and the JVM transport blocks on each call.

**Pattern-SSR-Loaders** is the canonical fan-out: a state machine spawned at `:on-create` uses `:spawn-all` (per [chapter 12](12-machines.md)) to launch N HTTP-fetching children in parallel, joins on all-complete, and writes the results from the join's `:entry`. Total wall-clock cost drops to `max(fetch-i) + overhead`, and a phase-level `:after` deadline guards against one hung fetch stalling the whole request. The same machine drives client-side navigation-fetch — only the spawn site moves (`:on-create` on the server, the route's `:on-match` on the client); the rest is identical. This is the re-frame2 answer to "how do I write Next.js's `Promise.all([...])`": the primitives (`:spawn-all`, managed HTTP, the request cofx) are framework-locked, and the Pattern names the composition.

## Pattern-FormAction — form POSTs that work before JS loads

GET requests render and hydrate. But SSR apps also handle **POSTs** — form submissions, especially in the no-JS window before hydration. A form must work without JavaScript (server processes the POST and re-renders) *and* the same code path should run client-side once JS arrives (intercept `:on-submit`, dispatch the same event, no full reload).

**Pattern-FormAction** is the shape. The HTML form renders with `method="POST" action="/<route>"` and a hidden CSRF token; the adapter parses the POST body and binds it as `:form-params`; `:rf/server-init` routes GET → page loader, POST → action event; the action handler validates against the registered schema (per [chapter 08](08-schemas.md)) and either emits `[:rf.server/redirect {:status 303 ...}]` on success (canonical POST-redirect-GET) or writes structured errors into the form slice and lets the normal re-render show them inline. The view's `:on-submit` interceptor — `(.preventDefault e); (dispatch [:cart/add-item ...])` — is purely additive once JS is alive; the *same domain event* runs in both contexts. It composes with the client-side form-slice convention from [chapter 11](11-forms.md) and with the error projector above; a page can use both Patterns — Loaders for the initial GET, FormAction for the POSTs.

## What you give up

A fully server-rendered SPA carries constraints. They're real, and they're worth saying plainly:

**Views must be deterministic given the state.** A view that reads `(js/Date.)` renders differently on server and client. Fix: read time from `app-db`, populated at `:rf/server-init`.

**Views must have no render-time side-effects.** No `js/setTimeout`, no `js/console.log`, no `.preventDefault` outside event handlers, inside a render fn. The render-tree is a function of state; if it isn't, you get hydration mismatches.

**Some browser-only work waits for hydration.** A focus-trap, scroll-restoration, an `IntersectionObserver` — all `:platforms #{:client}` effects, dispatched after hydration. The server DOM doesn't have them, which is fine: the user can't interact before JS loads anyway.

These are tight constraints. They're also constraints that good React/Vue/Svelte developers already follow, often by instinct. The difference is that re-frame2 makes them part of the architecture rather than a list of guidelines you hope everyone remembers.

## Two scope notes

**Streaming SSR is post-v1.** Frameworks like Next.js with React Server Components ship the shell fast and stream data-bound regions later in chunks. re-frame2 doesn't ship streaming in v1 — it provides the primitives (per-request frames, run-to-completion drain, pure render-tree) and a host can layer streaming on top, but the framework's commitment is non-streaming `render-to-string`. This is deliberate: chunked responses, suspense-equivalent boundaries, and ordered payload delivery add complexity we'd rather not commit to before the simpler case is rock-solid. Need streaming? You'll write a small extension. Don't? Basic SSR is enough.

**Hosting.** The CLJS reference runs the server side on the JVM — a Clojure server (Ring, Pedestal, your choice) drives `ssr-handler`, which uses re-frame2's pure functions to render, with the `app-db` in a request-scoped atom and rendering synchronous. Non-JVM hosts (Bun, Node, Deno) apply the same pattern with a JS-compiled runtime; the reference doesn't ship a Node-side runtime today, but nothing about the pattern precludes one. And for a non-Clojure port entirely — a TypeScript re-frame2 — the exact same architecture holds: handlers and subs and views are TS functions, the server is whatever Node-shaped runtime you like, the render-tree → string emitter is a TS function. The pattern survives the host change, because the pattern was never about the host.
