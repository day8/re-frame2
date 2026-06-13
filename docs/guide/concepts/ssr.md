# Server-side rendering

You want server-rendered HTML on the wire before any JavaScript loads — readable by crawlers and link unfurlers, hydrating into the live app without a flash — and you want it without maintaining a shadow copy of your app for the server. This page explains how re-frame2 gets you there: the same handlers, the same subs, the same views, executed on the JVM against a per-request frame, producing a string instead of a DOM. **There's one app. It runs twice.**

> **Coming from Next.js or Remix?** You keep the capabilities — first-paint HTML, loaders, form actions, React-18-style streaming — but there's no separate server layer to learn: a "loader" is your ordinary event handlers running in a per-request [frame](frames.md), and streaming is one hiccup marker, not a component API.

## Why the same code runs on a JVM

SSR is hard in most stacks because the app is entangled with the browser — `window`, `document`, effects firing during render. re-frame2 committed to three properties for entirely different reasons (testing, replay, observability), and SSR falls out of them:

- **Event handlers are pure.** `(state, event) → effects`. No `window`, no lifecycle. A JVM runs them fine.
- **Subscriptions are pure.** State in, value out.
- **The render-tree is data.** Hiccup is nested vectors and maps, and `rf/render-to-string` is a pure function from hiccup to an HTML string — no React, no DOM, no JS runtime.

None of these were added *for* SSR; they're the constraints the rest of the framework already lives by. The SSR surface ships as its own artefact (`day8/re-frame2-ssr`, plus `day8/re-frame2-ssr-ring` for the Ring host adapter), so apps that never render server-side carry none of it in their bundle. The full contract lives in [Spec 011 — SSR & Hydration](../../../spec/011-SSR.md).

## A request, start to finish

1. An HTTP request arrives. The host adapter creates a **frame for this request** and stashes the request map where handlers can read it.
2. The frame's `:on-create` event fires: read the session, set the route, start data fetches.
3. The runtime drains to a fixed point. State settles.
4. The root view renders to hiccup; `render-to-string` turns it into HTML.
5. The server ships the HTML **plus** a serialised state payload.
6. The client boots, dispatches `:rf/hydrate` with that payload, and renders. Its first render matches the server's HTML, so the existing DOM is adopted, not replaced.
7. The per-request frame is destroyed (in a `finally` — every exit path).

Steps 2–4 run the handlers, subs, and views you already wrote. There is no "server code." And the per-request frame is exactly the frame from [Frames: isolated worlds](frames.md): a hundred concurrent requests are a hundred isolated app-dbs that cannot see each other.

## The server side, wired

The Ring adapter ships one handler constructor that owns the whole lifecycle — frame create, drain, render, payload, response, teardown:

```clojure
(require '[ring.adapter.jetty :as jetty]
         '[re-frame.ssr.ring  :as ssr-ring])

(def handler
  (ssr-ring/ssr-handler
    {:on-create [:rf/server-init]
     :root-view [:app/root]
     :payload   [:articles :session-user]}))   ;; allowlist of app-db keys to ship

(jetty/run-jetty handler {:port 3000 :join? false})
```

`:payload` is a **security boundary**, and it fails closed. A vector is an allowlist of top-level app-db keys — everything else stays on the server, *including keys you add next year*. Omitting `:payload` throws at boot (`:rf.error/ssr-missing-payload-policy`), not on the first leaking request; shipping the whole app-db requires the explicit keyword `:rf.ssr.payload/whole-app-db`. A denylist was rejected deliberately: it would silently leak every new server-only key the moment one is introduced.

Handlers read the request the way handlers read any outside fact — a declared coeffect:

```clojure
;; Adapted from examples/reagent/ssr/core.cljc
(rf/reg-event-fx :rf/server-init
  {:platforms        #{:server}
   :rf.cofx/requires [:rf.server/request]}
  (fn [{:keys [db rf.server/request]} _]
    {:db (assoc db :session-user (-> request :session :user))
     :fx [[:dispatch [:rf.route/handle-url-change (:uri request)]]
          [:rf.http/managed {:request    {:method :get :url "/api/articles"}
                             :decode     :json
                             :on-success [:articles/loaded]}]]}))
```

The request map carries `:uri`, `:request-method`, `:headers`, `:query-params`, `:form-params`, `:session`, `:cookies`. Declare it once at `:rf/server-init` and thread values down through the events you dispatch; the `[:rf.route/handle-url-change ...]` dispatch hands the URL to the same [routing](routing.md) machinery the client uses. One naming note: `:rf/server-init` is the pattern-reserved name the framework documents and you supply the body for — it is not licence to register your own events under the reserved `:rf/*` root.

## The client side: hydrate, then verify

The client's job is to land in the state the server finished in — without redoing the work. `ssr/hydrate!` (from `re-frame.ssr`) fuses the three steps in the mandated order: **read** the embedded payload, **dispatch** `[:rf/hydrate payload]` before the first render, **verify** the render-tree hash against the server's.

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
    [rf/frame-provider {:frame :app}
     [(rf/view :app/root)]]))
```

Two things to hold onto:

- **The hydration target is carried, never guessed.** `:frame` is required, and the *same* frame goes to `hydrate!` and the root `frame-provider` — the carried-frame rule from [Frames](frames.md), applied at boot.
- **Hydration replaces; the server is authoritative.** `:rf/hydrate` installs the server's app-db *and* its serialisable runtime slice (machine snapshots, the route) in one atomic step, replacing whatever the client pre-seeded. This is locked — a defaulting merge would bury "which side won?" bugs at every key. If you need client-only state to survive, re-register `:rf/hydrate` with your own explicit merge and own its semantics. A malformed payload is rejected wholesale (fail-closed), and a missing one just means a normal client-only load — that's the `when-not` branch above.

Server state declared as a [resource](server-state.md) makes the round trip too: the server preloads it, the payload carries the entries, and a fresh hydrated entry renders immediately without firing a duplicate fetch — see the [resources SSR example](../../../examples/reagent/resources_ssr/).

## When the renders disagree

Sometimes the client's first render *doesn't* match the server's HTML — the classic SSR bug that, elsewhere, produces a flash and a shrug. The usual causes are mundane: a date rendered in two timezones, state the server set but the client didn't read, an unordered map serialising in two orders.

re-frame2 makes it detectable. The server embeds a structural hash of its render-tree; the client computes the same hash on its first render and compares. On disagreement, a structured trace event fires:

```clojure
{:operation :rf.ssr/hydration-mismatch
 :op-type   :error
 :tags      {:server-hash     "a3f29c01"
             :client-hash     "0b77e4d2"
             :first-diff-path [:articles 0 :date]}}   ;; where, not just whether
```

The default recovery is **warn and replace** — log it, render the client's view — so the user never sees a broken page; per-frame strict mode (`:ssr {:on-mismatch :hard-error}`) escalates it to a thrown structured exception for dev and CI. One honesty note: the trace rides the dev trace surface, elided from production client builds like the rest of the trace stream — the hash comparison itself still runs (disable it with `:ssr {:detect-mismatch? false}` to reclaim the first-render work). To watch for drift in production you instrument deliberately: the strict-mode exception carries both hashes, so the boot site can catch it around `hydrate!` and ship it through your [observability](observability.md) sinks.

## `:platforms` — one handler, gated per runtime

A real init flow mixes work that's fine on the server (HTTP) with work that's meaningless there (writing `localStorage`). You do not branch in handler bodies. Effects declare where they run:

```clojure
;; Adapted from examples/reagent/ssr/core.cljc
(rf/reg-fx :auth.session/store
  {:doc       "Persist a session token in localStorage."
   :platforms #{:client}}              ;; server-side dispatches skip this
  (fn [_ {:keys [token]}]
    (.setItem js/localStorage "auth/token" token)))
```

The default is universal (`#{:server :client}`). When a server-side drain meets a `#{:client}` effect, the resolver skips it and emits a `:rf.fx/skipped-on-platform` trace — the handler that returned it never learns which runtime it's on. One single-purpose handler, two platforms, zero `if (typeof window === 'undefined')`.

## The response is more than HTML — `:rf.server/*`

A real response carries a status, headers, cookies, sometimes a redirect. Handlers control all of it with data — server-only effects that write to a per-request accumulator the adapter materialises onto the wire:

| fx-id | does |
|---|---|
| `:rf.server/set-status` | set the HTTP status (last write wins; conflicts traced) |
| `:rf.server/set-header` | set a header, replacing any prior value |
| `:rf.server/append-header` | append to multi-value headers (`Set-Cookie`, `Vary`) |
| `:rf.server/set-cookie` | structured cookie map — the adapter does the wire encoding |
| `:rf.server/delete-cookie` | expire a cookie |
| `:rf.server/redirect` | short-circuit the render with a redirect (`303` for POST success) |

`:rf.server/redirect` trusts its caller; for a `:location` built from user input (`?next=...`), use `:rf.server/safe-redirect`, which parses, rejects schemes, and gates against an allowlist — the open-redirect guard. Two more surfaces round out the response and are worth recognising, not memorising: `<title>`/`<meta>`/JSON-LD come from `reg-head` (head content is derived data, hashed for mismatch like the body), and a server-side exception reaches the wire only as a sanitised `:rf/public-error` projection — full detail rides the [error dossier](errors.md), never the response. Details: [Spec 011](../../../spec/011-SSR.md).

## Streaming: `:rf/suspense-boundary`

The advanced slice, and the analogue of React 18 streaming / Next.js `loading.js`: ship a usable shell on the first byte, stream slow regions as their data resolves. In re-frame2 it is one declarative hiccup marker:

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

The streaming walker emits the shell with each `:fallback` in place and flushes it immediately; each boundary's subtree then renders and streams in as its own chunk, carrying a per-subtree app-db delta so subscriptions in that region see the right state as it lands. The final chunk is the canonical full payload — deltas are a speed prop, the final `:rf/hydrate` is the correctness lock. If one boundary's render throws, *that card* keeps its fallback (with a `:rf.ssr/suspense-boundary-failed` trace) and the rest of the page streams on — a flaky comments service no longer 500s the whole page.

Wiring is the streaming counterparts of what you've already seen: `stream-handler` (from `re-frame.ssr.ring.streaming`) in place of `ssr-handler`, and an opt-in client install (`ssr/streaming-install!`, same carried `:frame`) that swaps fallbacks for resolved chunks as they arrive. Don't reach for it by default: a page without independently-slow regions gains nothing over plain `ssr-handler`, and a `:rf/suspense-boundary` that reaches the non-streaming emitter fails loudly rather than rendering a phantom element.

## Two patterns, linked

Two compositions of these primitives are common enough to have canonical write-ups. They're conventions over what you already know, not new machinery:

- **[Pattern-SSR-Loaders](../../../spec/Pattern-SSR-Loaders.md)** — N parallel data fetches before render, the `Promise.all` of a Next.js loader: a [state machine](machines.md) spawned at `:on-create` fans out HTTP-fetching children with `:spawn-all`, joins on all-complete, writes the slices. Wall-clock cost drops from the sum of the fetches to the max. The same machine drives client-side navigation fetch; only the spawn site moves.
- **[Pattern-FormAction](../../../spec/Pattern-FormAction.md)** — form POSTs that work before JS loads: the form renders with a real `method="POST"` and `action`, the server routes POST → the same domain event the client's `:on-submit` dispatches after hydration, validation runs server-side via the event's schema, and success answers with `[:rf.server/redirect {:status 303 ...}]`. One handler tree, both entry points. Where the pattern reads the request, the spelling is the one you saw above — `:rf.cofx/requires [:rf.server/request]` on the registration, the value flat in the coeffects map.

## What you give up

The constraints are real, so here they are plainly:

- **Views must be deterministic given the state.** A view that reads `(js/Date.)` renders differently on each side. Put time in app-db at init.
- **Views must have no render-time side effects.** The render-tree is a function of state; anything else *is* a hydration mismatch waiting for the detector.
- **Browser-only work waits for hydration.** Focus traps, scroll restoration, observers — `:platforms #{:client}` effects, fired after the client takes over. The user can't interact before JS loads anyway.

Good React developers follow these by instinct; here they're architecture, enforced by the platform gate and caught by the hash.

---

**You can now:**

- say why SSR is structurally cheap here — pure handlers, pure subs, hiccup-as-data — and quote the whole model: *there's one app, it runs twice*,
- wire a server with `ssr-handler`, including the fail-closed `:payload` allowlist and the `:rf.cofx/requires [:rf.server/request]` read,
- boot a client with `ssr/hydrate!` — carried `:frame`, replace-policy hydration, the client-only fallback branch,
- read a `:rf.ssr/hydration-mismatch` trace down to its `:first-diff-path`, and say what warn-and-replace versus strict mode do,
- gate effects with `:platforms`, shape the response with `:rf.server/*`, and stream slow regions with `:rf/suspense-boundary`,
- know where the loader and form-action compositions live when you need them.

**Next:** [Errors: dossiers, not log lines](errors.md) — where the server's sanitised error projection fits the bigger error story. Or see the isolation machinery underneath every request in [Frames: isolated worlds](frames.md).
