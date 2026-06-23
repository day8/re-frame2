# Server-side rendering

You want real HTML on the wire before a single byte of JavaScript loads — so crawlers and link unfurlers can read it, and the first paint lands fast. Then you want it to hydrate into the live app without a flash. And you want all of that *without* maintaining a second, server-flavoured copy of your application. That last clause is where most stacks quietly buckle.

re-frame2 gets you there by running the same handlers (the functions that respond to events), the same subscriptions (the queries that read state), and the same views (the functions that return your markup) on the JVM, against a per-request [frame](frames.md) — one isolated instance of your app. The only difference is the output: a string instead of a DOM. **There's one app. It runs twice.**

This page builds that up one step at a time: first *why* the same code can run on a server at all, then the smallest server you can wire, then the client that hydrates it, and finally the richer pieces — response control, head metadata, error handling, and streaming — each in its own section. A beginner can read straight down the main text and stop whenever they have enough.

> **Coming from Next.js or Remix?** You keep the capabilities — first-paint HTML, loaders, form actions, React-18-style streaming. There's no separate server layer to learn. A "loader" is your ordinary event handlers running in a per-request frame. Streaming is one hiccup marker, not a component API.

## Why the same code runs on a JVM

SSR is hard in most stacks because the app is entangled with the browser: `window`, `document`, side-effects firing in the middle of render. re-frame2 sidesteps all three, and the reason is the quiet punchline of the whole page — *it didn't do this for SSR's sake*. The framework committed to these properties for testing, replay, and observability long before anyone asked it to render on a server. SSR just falls out of them, the way a free dessert falls out of ordering the prix fixe:

- **Event handlers are pure.** An event is a "something happened" message; its handler is `(state, event) → effects`. No `window`, no lifecycle. A JVM runs them fine.
- **Subscriptions are pure.** State in, value out.
- **The render-tree is data.** Hiccup is nested vectors and maps, and `rf/render-to-string` is a pure function from hiccup to an HTML string — no React, no DOM, no JS runtime.

None of these three were bolted on *for* SSR. They're the constraints the rest of the framework already lives by, which means turning SSR on costs you nothing structurally new to learn.

The SSR surface ships as its own artefact (`day8/re-frame2-ssr`, plus `day8/re-frame2-ssr-ring` for the Ring host adapter), so apps that never render server-side carry not one byte of it in their client bundle.

> **For JavaScript developers.** In a typical Next.js codebase the same component renders on both sides, but you litter it with `typeof window === 'undefined'` guards because the *component itself* is entangled with the browser. Here the entanglement was never there — handlers and views are pure by construction, so the "does this run on the server?" question is answered once, declaratively (you'll see `:platforms` below), instead of at every call site.

## A request, start to finish

Before wiring anything, here's the shape of a single request. The host adapter runs all of it for you; knowing the steps makes the code below read as narration rather than magic:

1. An HTTP request arrives. The host adapter creates a **frame for this request** and stashes the request map where handlers can read it.
2. The frame's `:initial-events` fire in order: read the session, set the route, start data fetches.
3. The runtime drains to a fixed point. State settles.
4. The root view renders to hiccup; `render-to-string` turns it into HTML.
5. The server ships the HTML **plus** a serialised state payload.
6. The client boots, dispatches `:rf/hydrate` with that payload, and renders. Its first render matches the server's HTML, so the existing DOM is adopted, not replaced.
7. The per-request frame is destroyed (in a `finally` — every exit path).

Steps 2–4 run the handlers, subs, and views you already wrote, so there's no separate "server code" to keep in sync with the client. The per-request frame is *exactly* the frame from [Frames: isolated worlds](frames.md) — nothing special, no SSR-only variant.

> **Why this matters.** Because each request gets its own frame, a hundred concurrent requests are a hundred isolated app-dbs (each frame's single state map) that cannot see, race, or corrupt one another. The thing that made [frames](frames.md) good for testing N apps in one process is the same thing that makes them safe under server load — you get request isolation for free, not as a bolted-on server feature.

## The simplest server

Wire the Ring adapter once. It owns the whole lifecycle — frame create, drain, render, payload, response, teardown — and runs the seven steps above for you:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[re-frame.ssr.ring  :as ssr-ring])

(def handler
  (ssr-ring/ssr-handler
    {:initial-events [[:rf/server-init]]
     :root-view      [:app/root]
     :payload        [:articles :session-user]}))   ;; allowlist of app-db keys to ship

(jetty/run-jetty handler {:port 3000 :join? false})
```

Three opts do the work:

- **`:initial-events`** is the per-request setup vector — the same setup vector you write on a [frame](frames.md). The adapter lowers it verbatim into the per-request frame's `:initial-events` for you. It accepts a vector of events directly, or a `(fn [request] -> initial-events-vector)` when you need to derive the setup from the Ring request.
- **`:root-view`** is the view ref the adapter renders to HTML once the frame settles.
- **`:payload`** is the allowlist of top-level app-db keys to serialise onto the wire for the client to hydrate from. It's a security boundary; we'll come back to it.

That's a working SSR server. Everything below refines it.

> **From re-frame v1.** v1 had no first-class SSR story — you reached for community libraries and hand-rolled the server/client split. Here it's one handler constructor over the *same* events and views the client runs. Also note: the old frame keys `:on-create` / `:initial-db` are retired; per-request setup is `:initial-events`, an ordered vector of events resolved at frame creation. Supplying `:on-create` now fails loud (`:rf.error/on-create-retired`).

### Reading the request

Handlers read the request the way they read any outside fact — through a declared coeffect, which is the framework's name for an input a handler pulls in rather than receives on the event:

```clojure
;; Adapted from examples/reagent/ssr/core.cljc
(rf/reg-event :rf/server-init
  {:platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [db rf.server/request]} _]
    {:db (assoc db :session-user (-> request :session :user))
     :fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]
          [:rf.http/managed {:request    {:method :get :url "/api/articles"}
                             :decode     :json
                             :on-success [:articles/loaded]}]]}))
```

Declare `:rf.cofx/requires [:rf.server/request]` once, and the request map arrives flat under `:rf.server/request` in the coeffects. It carries `:uri`, `:request-method`, `:headers`, `:query-params`, `:form-params`, `:session`, `:cookies`. The `[:rf.route/handle-url-change ...]` dispatch hands the URL to the same [routing](routing.md) machinery the client uses, so the route resolves through the code you already trust.

!!! note "`:rf/server-init` is a reserved name you fill in"

    `:rf/server-init` is a pattern-reserved name the framework documents and you supply the body for. It is not licence to register your own events under the reserved `:rf/*` root.

> **Going deeper — the request read is *ambient*, and replay cares.** The request coeffect reads the per-request slot the host stashed, and — like reading `localStorage` or the wall clock — its value is **never recorded**. The framework calls this an *ambient* coeffect. The rule is precise: an ambient read is fine for a **non-durable** decision (branch on `:request-method`, peek at a header to pick a code path) but **not** for a value you fold into durable state. Replay re-runs the live supplier instead of re-presenting the value the recorded run saw — and after the per-request frame is torn down, that supplier reads `nil`. So `(assoc db :session-user (-> request :session :user))` directly off the ambient read makes a *durable write whose input was never recorded*, and a replay (or an [epoch](observability.md) restore) reconstructs a different app-db.
>
> The fix is to make the durable fact **recordable** — move the sanitised projection (never the whole request map, which carries `Cookie` / `Authorization` / raw bodies) onto the dispatch itself. Two shapes are legal:
>
> ```clojure
> ;; (a) ride the derived fact in on the event payload — recorded as part of :event.
> ;;     The host computes the projection at the boundary and dispatches it:
> ;;     :initial-events [[:auth/server-init {:user (extract-user request)}]]
> (rf/reg-event :auth/server-init
>   {:platforms #{:server}}
>   (fn [{:keys [db]} [_ {:keys [user]}]]
>     {:db (assoc db :auth/user user)}))           ;; durable write, recorded input
>
> ;; (b) declare a provided recordable cofx the host stamps onto the boot token.
> ;;     A record missing it fails LOUD with :rf.error/missing-required-cofx,
> ;;     never a silent re-read of the host.
> (rf/reg-cofx :auth.session/user {:recordable? true :provided? true} ...)
> ```
>
> The whole request map must **never** ride the causal token — recording a secret makes it durable, not safe. Stamp only the sanitised derived projection. Keep `:rf.server/request` for the reads that don't fold into durable state.

### `:payload` — the fail-closed allowlist

`:payload` decides what crosses the wire to the client, and — this is the important word — it **fails closed**. A vector is an allowlist of top-level app-db keys; everything else stays on the server, *including keys you haven't written yet*. Add a `:secrets/api-token` to app-db next year and forget to update the allowlist, and the worst that happens is it doesn't reach the client.

Forget to set `:payload` at all and you get a loud error at boot (`:rf.error/ssr-missing-payload-policy`) — not a quiet leak on the first request. If you genuinely want to ship the whole app-db, you say so out loud with the explicit keyword `:rf.ssr.payload/whole-app-db`.

> **Why this matters.** A *denylist* ("ship everything except these") was considered and rejected on purpose: it leaks every new server-only key the instant you introduce one, which is exactly the bug an allowlist exists to prevent. At a security boundary the framework would rather stop you cold at boot than surprise you in production.

> **Going deeper — the three distinct boot-time errors.** The allowlist accepts any sequential of keywords — a literal vector is canonical, but a computed `(filterv …)` or `(keep …)` works too. Three boot-time errors keep three different mistakes distinct, so you can tell them apart at construction time:
>
> - an **empty** allowlist (`[]`) falls into the missing-policy bucket (`:rf.error/ssr-missing-payload-policy`) — shipping zero keys is almost certainly a slip, not intent;
> - an **unknown** policy keyword surfaces as `:rf.error/ssr-unknown-payload-policy`;
> - a **string typo for a keyword** — `["public/articles"]` instead of `[:public/articles]` — fails loud as `:rf.error/ssr-malformed-payload-allowlist` (carrying the offending entries under `:bad-entries`) rather than silently shipping a wrong slice.
>
> A **set** is rejected on purpose; the allowlist is an *ordered* key selection, not a set. The selector is collection-vs-keyword — a sequential can never be confused with the whole-app-db keyword — so there's nothing to arbitrate.

The same constructor accepts the rest of the lifecycle opts: the two error opts (`:error-view`, `:on-error`, [covered below](#when-the-server-throws)) and the caller-trusted shell hooks for bespoke head/body fragments (`:head`, `:body-end`, `:script-src`, `:app-element-id`).

## The client side: hydrate, then verify

The client's job is to land in the state the server finished in, without redoing the work. `ssr/hydrate!` (from `re-frame.ssr`) does three steps in the mandated order: **read** the embedded payload, **dispatch** `[:rf/hydrate payload]` before the first render, **verify** the render-tree hash against the server's.

```clojure
;; Adapted from examples/reagent/ssr/core.cljc
;; requires [re-frame.ssr :as ssr] and the Reagent adapter
(defonce react-root
  (rdc/create-root (js/document.getElementById "app")))

(defn ^:export run []
  (rf/init! reagent-adapter/adapter)          ;; installs the adapter — creates no frame
  (rf/reg-frame :app {:platform :client})
  (let [payload (ssr/hydrate! {:frame          :app
                               :render-tree-fn (fn [] ((rf/view :app/root)))})]
    (when-not payload
      ;; No payload script — a client-only first load. Seed normally.
      (rf/dispatch-sync [:app/initialise] {:frame :app})))
  (rdc/render react-root
    [rf/frame-provider-existing {:frame :app}
     [(rf/view :app/root)]]))
```

Two things to hold onto:

- **The hydration target is carried, never guessed.** `:frame` is required, and the *same* frame goes to `hydrate!` and the root `frame-provider-existing`. That's the carried-frame rule from [Frames](frames.md), applied at boot. An absent `:frame` raises `:rf.error/no-frame-context`; the runtime never invents a default.
- **Hydration replaces; the server is authoritative.** `:rf/hydrate` installs the server's app-db *and* its serialisable runtime slice (machine snapshots, the route) in one atomic step, replacing whatever the client pre-seeded. A malformed payload is rejected wholesale (fail-closed); a missing one just means a normal client-only load — that's the `when-not` branch above.

> **Coming from React?** This is `hydrateRoot` with the gloss removed. React hydrates by walking the DOM and reattaching listeners, and trusts that your component re-renders the same tree. Here the server's *state* rides along explicitly in the payload, `:rf/hydrate` installs it before the first render, and then the substrate adapter attaches listeners to the existing DOM. You never re-fetch on the client to "catch up" — the state the server computed is already in app-db.

> **Going deeper — why replace, not merge.** The merge policy is locked to *replace the whole frame-state* (app-db **and** the serialisable runtime-db projection) because a defaulting merge would bury "which side won?" bugs at every key. If you need client-only state to survive hydration, the customisation point is *re-registering* `:rf/hydrate` with your own explicit merge — you own the order and the semantics. The payload is also treated as an untrusted transport input: a non-map payload, or a present-but-not-a-map app-db / runtime-db slice, is rejected wholesale (`:rf.error/malformed-hydration-payload`) and the client's existing state is left untouched. A *wholly absent* slice is not malformed — it's the documented client-only first-load fallback.

Server state declared as a [resource](server-state.md) (a value the framework fetches and caches for you) makes the round trip too. The server preloads it, the payload carries the entries, and a fresh hydrated entry renders immediately without firing a duplicate fetch. See the [resources SSR example](../../../examples/reagent/resources_ssr/).

### Deploy-drift checks come along for free

The payload doesn't only carry state — it carries a couple of stamps that catch the classic "the server and the client are running different code" bug. As part of `:rf/hydrate`'s effects, the framework fires two client-only compatibility checks, and both are **best-effort** — they emit a trace and let hydration proceed; they never throw and never block the page:

- **`:rf.ssr/check-version`** compares the payload's pattern-protocol version (`:rf/version`, an integer) against the client's. A mismatch emits a `:rf.ssr/version-mismatch` trace — your "the server bundle is a deploy ahead of the client" alarm.
- **`:rf.ssr/check-schema-digest`** (fired only when the payload carries a digest) hashes the client's registered `app-schema` set and compares it to the server's. A mismatch emits `:rf.ssr/schema-digest-mismatch` — the server is validating against a different schema set than the client's bundle.

If the runtime can't find the client-side value to compare against (no version hook registered, say), the check emits `:rf.ssr/compatibility-check-skipped` and no-ops rather than crashing. Degraded-but-running is the deliberate posture: a version stamp should never be what stops a page from loading. These three categories ride the dev trace surface, so wire an [observability](observability.md) listener on them if you want deploy-drift visibility in CI.

## When the renders disagree

Sometimes the client's first render *doesn't* match the server's HTML. This is the classic SSR bug — and in most stacks it produces a content flash, a console warning nobody reads, and a collective shrug. The causes are almost always mundane: a date rendered in two timezones, a bit of state the server set but the client never read, an unordered map that happens to serialise in two different orders.

re-frame2 refuses to shrug. The server embeds a structural hash of its render-tree; the client computes the same hash on its own first render and compares. On disagreement, a structured trace event fires — telling you not just *that* it diverged but *where*:

```clojure
{:operation :rf.ssr/hydration-mismatch
 :op-type   :error
 :tags      {:server-hash     "a3f29c01"
             :client-hash     "0b77e4d2"
             :first-diff-path [:articles 0 :date]}}   ;; where, not just whether
```

The default recovery is **warn and replace**: log it, render the client's view, so the user never sees a broken page. Per-frame strict mode (`:ssr {:on-mismatch :hard-error}`) escalates it to a thrown structured exception for dev and CI.

!!! note "The mismatch trace is dev-only — instrument deliberately for production"

    That trace rides the dev trace surface, so it's elided from production client builds like the rest of the trace stream. The hash comparison itself still runs (disable it with `:ssr {:detect-mismatch? false}` to reclaim the first-render work). To watch for drift in production you instrument deliberately: the strict-mode exception carries both hashes, so the boot site can catch it around `hydrate!` and ship it through your [observability](observability.md) sinks.

> **Going deeper — the hash is structural, not textual.** Byte-for-byte HTML equality is *not* required: different serialisers emit semantically-equivalent strings that differ in attribute order or whitespace. The contract is structural — the FNV-1a hash runs over a canonical-EDN traversal of the render-tree (depth-first, attribute maps in sorted-key order, nil pruned). FNV-1a is chosen for speed and zero platform dependencies (no `crypto`); cryptographic strength isn't needed, because the hash is a tamper-evident structural marker between *one* server and *one* client of the same build, not a security primitive.

## `:platforms` — one handler, gated per runtime

A real init flow mixes work that's fine on the server (fetching over HTTP) with work that's meaningless there (writing `localStorage`, which the JVM has never heard of). You don't branch in handler bodies. Instead, the effect itself declares where it's allowed to run:

```clojure
;; Adapted from examples/reagent/ssr/core.cljc
(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; server-side dispatches skip this
  (fn [_ {:keys [token]}]
    (.setItem js/localStorage "auth/token" token)))
```

The default is universal (`#{:server :client}`). When a server-side drain meets a `#{:client}` effect, the resolver skips it and emits a `:rf.fx/skipped-on-platform` trace, and the handler that returned it never learns which runtime it's on. One single-purpose handler, two platforms, zero `if (typeof window === 'undefined')`.

> **For JavaScript developers.** This is the declarative answer to the `typeof window === 'undefined'` guards scattered through a Next.js codebase. Instead of *branching inside* every component or handler that touches the browser, you tag the *effect* once with the platforms it's allowed on, and the resolver enforces it. The handler stays runtime-agnostic; there is no `if (isServer)` anywhere in your business logic.

## Controlling the response — `:rf.server/*`

A real response carries a status, headers, cookies, sometimes a redirect. Handlers control all of it with data, which keeps the response logic testable and pure. These are server-only effects that write to a per-request accumulator, and the adapter materialises it onto the wire:

| fx-id | args | does |
|---|---|---|
| `:rf.server/set-status` | `<int>` | set the HTTP status (last write wins; a conflict emits a `:rf.warning/multiple-status-set` trace) |
| `:rf.server/set-header` | `{:name :value}` | set a header, **replacing** any prior value (case-insensitive name) |
| `:rf.server/append-header` | `{:name :value}` | **append** another instance — for multi-value headers (`Set-Cookie`, `Vary`) |
| `:rf.server/set-cookie` | a structured cookie map | the adapter does the wire encoding |
| `:rf.server/delete-cookie` | `{:name :path}` | expire a cookie (sugar over `set-cookie` with `:max-age 0`) |
| `:rf.server/redirect` | `{:status :location}` | short-circuit the render with a redirect (`:status` defaults to `302`; use `303` for POST success) |
| `:rf.server/safe-redirect` | `{:location :relative-only? :allow}` | a validated redirect for user-supplied locations — the open-redirect guard |

A few things worth knowing before you reach for these.

**Cookies are structured maps, never hand-built header strings.** You hand the framework the attributes; the adapter does the RFC 6265 wire encoding, which is exactly where raw-string cookie APIs grow quoting bugs:

```clojure
{:fx [[:rf.server/set-cookie
       {:name      "session"
        :value     session-token
        :max-age   3600
        :secure    true
        :http-only true
        :same-site :lax            ;; one of :strict :lax :none
        :path      "/"}]]}
```

**`:rf.server/redirect` truncates the render.** If a redirect fires anywhere in the drain — a setup step, a route handler, a downstream cascade — the runtime sets `:redirect`, **skips the HTML render entirely** (no body), and skips the hydration payload (there's no client to hydrate). The host emits a status-and-`Location` response with no body. Last-write-wins on multiple redirects, with a `:rf.warning/multiple-redirects` trace.

> **Gotcha — header injection fails loud.** A `\r` or `\n` smuggled into a header value is a response-splitting attack, so the framework does **not** quietly strip it: `:rf.server/set-header` / `:rf.server/append-header` throw `:rf.error/header-invalid-value`, `:rf.server/redirect` throws `:rf.error/redirect-invalid-location` on CRLF/NUL in `:location`, and `:rf.server/set-cookie` CRLF-checks *every* attribute (`:name`, `:value`, `:domain`, `:path`, …) before the adapter serialises the line. Build a cookie from a partner-supplied tenant string and the check has your back. The policy is fail-fast over strip-and-warn — silent normalisation masks the bug and lets the downstream-encoded vector through.

`:rf.server/redirect` **trusts its caller**, which is fine for a location you control.

!!! warning "Use `:rf.server/safe-redirect` for user-supplied locations"

    For a `:location` built from user input (`?next=...`), use `:rf.server/safe-redirect` instead. It runs the gauntlet in order: the URL must parse (`:rf.error/safe-redirect-invalid-url`), `javascript:` / `data:` / `vbscript:` schemes are rejected (`:rf.error/safe-redirect-scheme-rejected`), and `:relative-only? true` or an `:allow ["app.example.com"]` allowlist gates the host (`:rf.error/safe-redirect-host-disallowed`). That's the open-redirect guard — an attacker-controlled `?next=…` cannot bounce a freshly-authed user off-origin to a phishing page.

## Head metadata — `<title>`, `<meta>`, OpenGraph, JSON-LD

Crawlers and link-unfurlers don't run JS, so the head metadata has to land on the first byte. The commitment is the same one views and subs already make: **the head model is data derived from app-db**, not an imperative DOM API. You register a head function and a route names it:

```clojure
;; reg-head is on the rf/ facade; route-url lives in re-frame.routing.
(rf/reg-head :head/article
  {:doc "Article-page head — derives title/meta/og from the article."}
  (fn [db {:keys [params] :as route}]
    (let [{:keys [title summary image]} (get-in db [:articles (:id params)])]
      {:title   (str title " — Example")
       :meta    [{:name "description" :content summary}
                 {:property "og:title" :content title}
                 {:property "og:image" :content image}]
       :link    [{:rel "canonical" :href (routing/route-url :route/article params)}]
       :json-ld [{"@context" "https://schema.org"
                  "@type"    "Article"
                  "headline" title}]})))

(rf/reg-route :route/article
  {:path "/articles/:id"
   :head :head/article})            ;; the route declares which head to use
```

The head fn has the exact shape and discipline of a sub — `(db, route) → head-model`, pure, subs inside it evaluate against the static app-db. The emitter writes the head in canonical order (`<title>`, then `<meta>`, `<link>`, `<script>`, JSON-LD), and `:html-attrs` / `:body-attrs` populate `<html>`/`<body>`. There's one head per route in v1 (no parent/child composition); routes share head logic by naming the same id. Routes with no `:head` get a sensible default (`<title>` from frame metadata, `charset` + `viewport`). The head rides the same render-tree hash as the body, so a head mismatch surfaces through the same detector — and on the client the head recomputes from the hydrated app-db plus the route slice, so an SPA that routes post-load keeps it current.

> **Gotcha — JSON-LD escaping is handled for you.** String values inlined into a `<script type="application/ld+json">` body have every `<` re-encoded so an attacker-supplied product title can't close the script tag and pivot into HTML. You write data; the emitter applies the position-correct escape at every leaf.

## When the server throws

A server-side exception must never reach the wire as a stack trace — crawlers and unauthenticated users would read your internals. So a thrown handler, fx, sub, or render-time view is run through a **registered error projector** that maps the rich internal trace to a sanitised, client-safe `:rf/public-error` shape:

```clojure
{:status 500 :code :internal-error :message "Something went wrong" :retryable? false}
```

The framework ships a default projector that maps the obvious cases — a routing miss to `404 :not-found`, a *client-surface* schema-validation failure to `400 :bad-request`, anything else to `500 :internal-error`. You register your own to add app conventions (`401`/`403` for auth):

```clojure
(rf/reg-error-projector :myapp/public-error
  {:doc "Project internal error traces to public response shapes."}
  (fn [trace-event]
    (case (:operation trace-event)
      :auth/unauthorised {:status 401 :code :unauthorised :message "Sign in"  :retryable? false}
      {:status 500 :code :internal-error :message "Something went wrong" :retryable? false})))
```

The projector you register is named in the frame's `:ssr {:public-error-id :myapp/public-error}` metadata (so a server-rendering frame and a dev-tooling frame in one process can run different ones). The error page is a registered view that receives the *public* shape — it physically cannot leak the internal trace, because the trace never reaches it. In dev (`:ssr {:dev-error-detail? true}`) the public shape carries an extra `:details` with the full trace for the developer; in prod that key is simply absent. The rich trace still flows to your monitoring sinks unchanged — projection only governs the HTTP boundary. The full error story lives in the [error dossier](errors.md).

> **Why this matters — two error opts, two jobs.** The Ring handler exposes `:error-view` *and* `:on-error`, and a robust deployment wires both. `:error-view` is the **projected page** — it fires for a failure the projector caught (drain or render), takes the sanitised `:rf/public-error` map, and renders hiccup. `:on-error` is the **transport net** — it fires for a Ring-layer failure the projector can't see (per-request frame setup throw, a header-materialise throw), takes the raw `(request throwable)`, and returns a verbatim Ring response. Both are bug-contained: a buggy `:error-view` falls back to the default template, a buggy `:on-error` falls back to the locked topology-safe 500 — neither can bypass the error boundary.

## Streaming: `:rf/suspense-boundary`

This is the advanced slice — the direct analogue of React 18 streaming and Next.js's `loading.js`. The idea is the same: don't make the whole page wait on its slowest query. Ship a usable shell on the first byte, with skeletons where the slow regions will be, then stream each region in as its data resolves. In React that's a `<Suspense>` boundary with a `fallback`. In re-frame2 it's one declarative hiccup marker that should look familiar:

```clojure
;; Adapted from examples/reagent/ssr_streaming/core.cljc
(rf/reg-view ^{:rf/id :dashboard/root} root-view []
  [:main.dashboard
   [:header [:h1 "Dashboard"]]
   [:section.cards
    [:rf/suspense-boundary
     {:id :card.revenue :fallback [:dashboard/card-skeleton :revenue]}
     [:dashboard/card :revenue]]
    [:rf/suspense-boundary
     {:id :card.signups :fallback [:dashboard/card-skeleton :signups]}
     [:dashboard/card :signups]]]])
```

The streaming walker emits the shell with each `:fallback` in place and flushes it immediately — that's your first byte. Each boundary's subtree then renders and streams in as its own chunk, and that chunk carries a per-subtree app-db delta, so the subscriptions in that region see the right state the moment they land.

Failure isolation comes for free with the boundaries. If one boundary's render throws, *that card* keeps its fallback (with a `:rf.ssr/suspense-boundary-failed` trace) and the rest of the page streams on. A flaky comments service stops being able to 500 your entire page — the blast radius is exactly one boundary.

The wiring mirrors what you've already seen, with streaming counterparts: `stream-handler` (from `re-frame.ssr.ring.streaming`) in place of `ssr-handler`, and an opt-in client install (`ssr/streaming-install!`, same carried `:frame`) that swaps fallbacks for resolved chunks as they arrive.

> **Going deeper — the deltas are a speed prop; the final payload is the correctness lock.** Each streamed chunk carries a speculative per-subtree app-db delta so the region paints early. The *final* chunk is the canonical full payload, and that's the safety net: if the speculative deltas and the canonical payload ever disagree, the payload wins, every time. You get the latency of streaming with the correctness guarantee of a single authoritative `:rf/hydrate`.

!!! note "Don't reach for streaming by default"

    A page without independently-slow regions gains nothing over plain `ssr-handler`. And a `:rf/suspense-boundary` that reaches the non-streaming emitter fails loudly rather than rendering a phantom element.

## Two patterns, linked

Two compositions of these primitives are common enough to have canonical write-ups. They're conventions over what you already know, not new machinery:

- **[Pattern-SSR-Loaders](../../../spec/Pattern-SSR-Loaders.md)** — N parallel data fetches before render, the `Promise.all` of a Next.js loader. A [state machine](machines.md) spawned from the frame's `:initial-events` fans out HTTP-fetching children with `:spawn-all`, joins on all-complete, writes the slices. Wall-clock cost drops from the sum of the fetches to the max. The same machine drives client-side navigation fetch; only the spawn site moves.
- **[Pattern-FormAction](../../../spec/Pattern-FormAction.md)** — form POSTs that work before JS loads. The form renders with a real `method="POST"` and `action`. The server routes POST to the same domain event the client's `:on-submit` dispatches after hydration. Validation runs server-side via the event's schema, and success answers with `[:rf.server/redirect {:status 303 ...}]`. One handler tree, both entry points. Where the pattern reads the request, the spelling is the one you saw above: `:rf.cofx/requires [:rf.server/request]` on the registration, the value flat in the coeffects map.

## What you give up

SSR isn't free of rules, and pretending otherwise would just move the surprise downstream. So here are the constraints, plainly:

- **Views must be deterministic given the state.** A view that reads `(js/Date.)` renders differently on each side. Put time in app-db at init.
- **Views must have no render-time side effects.** The render-tree is a function of state. Anything else *is* a hydration mismatch waiting for the detector.
- **Browser-only work waits for hydration.** Focus traps, scroll restoration, observers — these are `:platforms #{:client}` effects, fired after the client takes over. The user can't interact before JS loads anyway.

Good React developers follow these by instinct. Here they're architecture: enforced by the platform gate and caught by the hash.

The full contract — the complete `:rf.server/*` schema set, the payload scope, and the hydration protocol — lives in [Spec 011 — SSR & Hydration](../../../spec/011-SSR.md).
