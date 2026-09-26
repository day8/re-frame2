# SSR glossary

re-frame2's optional server-side-rendering capability — run your real app on the JVM to ship HTML before JavaScript loads, then hand state to the client to take over. "One app, runs twice." See [The model](concepts.md).

### **SSR**

Server-side rendering: rendering your app to an HTML string on the server (per request, in its own [frame](../core/glossary.md#frame)) so the first paint arrives before the client bundle runs. The same events, subscriptions, and views run on both sides — you don't write a second app. Code that must run on only one side declares it once with [platform gating](#platform-gating), so no logic branches on `typeof window`.

### **render-to-string**

Pure function from hiccup to an HTML string — no browser, no DOM, JVM-runnable. It runs your real [views](../core/glossary.md#view) against a [per-request frame](#per-request-frame), which is what makes "one app, runs twice" hold. Lives in `re-frame.ssr` (`ssr/render-to-string`); there is no copy on the `rf/` facade.

### **per-request frame**

The [frame](../core/glossary.md#frame) the host creates for one HTTP request and destroys when the response is written. Its `:initial-events` boot the page's state, and the runtime drains to a settled app-db before rendering. Because every request gets its own, concurrent requests are isolated worlds that cannot see, race, or corrupt one another.

### **`:rf/server-init`**

The conventional boot event for a per-request frame, named in `:initial-events`. The name is reserved; your app registers the handler, usually `:platforms #{:server}` with `:rf.cofx/requires [:rf.server/request]` so it can read the request, and has it dispatch whatever the page needs — typically the route change for the request's URL.

### **blocking resource**

A [resource](../resources/glossary.md#resource) a route declares with `:blocking? true` in its `:resources`. The Ring handler waits for a route's blocking resources to settle before it renders, within a 5-second render budget; one that has not settled by then enters `:error` and the page renders its error state. Nothing else is waited for: a fetch an `:initial-events` handler starts itself is usually still in flight when the render begins.

### **hydration**

The client picking up the server's already-painted HTML and *adopting* it — installing the [hydration payload](#hydration-payload) and attaching event listeners — instead of throwing it away and re-rendering. `ssr/hydrate!` dispatches the framework-owned `:rf/hydrate` before the first render; it **replaces** the client's frame-state rather than merging into it, because on this question the server is authoritative.

### **hydration payload**

The server's finished state, serialized into the page as an EDN `<script id="__rf_payload">`: `:rf/version`, `:rf/app-db` (filtered by the [payload allowlist](#payload-allowlist)), `:rf/runtime-db` (the framework's serializable slice — route, machine snapshots), and `:rf/render-hash`. What `hydrate!` reads and installs.

### **payload allowlist**

The Ring adapter's required `:payload` option — the top-level app-db keys allowed to cross the wire to the client. It fails closed: everything not named stays on the server, *including keys you haven't written yet*, and omitting the option is a boot error (`:rf.error/ssr-missing-payload-policy`), never a quiet leak.

### **client frame id**

`ssr-handler`'s `:client-frame-id` option: a stable frame id, such as `:app`, written into the payload as `:rf/frame-id` so the client can check it is hydrating the frame the server meant. Omitted by default, because the server renders under a per-request frame the client never sees. Never set it to a per-request value — a payload whose id differs from the client's `:frame` is refused with `:rf.error/hydration-frame-id-mismatch`.

### **hydration mismatch**

When the client's first render disagrees with the server's HTML. For views that return hiccup, re-frame2 compares the two sides' [render hashes](#render-hash) and fires a structured trace carrying both hashes — warn-and-replace by default, a thrown error under strict mode (`:ssr {:on-mismatch :hard-error}`) for CI. The classic silent SSR bug, made loud.

### **render hash**

A structural hash of the server's render tree, stamped as `data-rf-render-hash` on the root element and as `:rf/render-hash` in the payload. `hydrate!` hashes the client's first render and compares the two. The handler writes it only when `:root-view` is the fn form, `(fn [] ((rf/view :app/root)))`, and the root view returns an element; the vector form `[(rf/view :app/root)]`, or a root whose body is only another view, renders the same HTML with no hash, so nothing is compared. The hash covers the markup the root spells out and the arguments it passes to child views, not what those child views render. UIx and Fresco roots carry none by design — React's hydration checks them. The head has its own `:rf/head-hash`, which the runtime writes but does not compare.

### **deploy-drift checks**

Two best-effort checks `:rf/hydrate` runs on the client: the payload's `:rf/version` against the client's SSR protocol version (`:rf.ssr/version-mismatch`), and, when the server was given a `:schema-digest`, the payload's `:rf/schema-digest` against the client's registered schemas (`:rf.ssr/schema-digest-mismatch`). Either emits a trace and lets hydration proceed. They catch a server and a client bundle from different deploys.

### **several roots on one page**

A page whose server markup is adopted by more than one client root — a header and a cart, say — usually hydrating one frame. `ssr/hydrate-page!` boots them in order, each inside its own failure boundary with its optional `:mount-fn`, so a root that throws is reported (`:rf.error/root-boot-failed`) and the others still boot. Installing the same payload twice into one frame is a no-op; a *different* payload is refused with `:rf.error/frame-payload-conflict`. See [`hydrate-page!`](../api/re-frame.ssr.md#hydrate-page).

### **platform gating**

The `:platforms` declaration on an [effect](../core/glossary.md#effect) or [coeffect](../core/glossary.md#coeffect): registered as `#{:client}` (or `#{:server}`), it's skipped — with a trace — when a drain runs on the other side. One handler runs on both platforms with zero `typeof window` branching.

### **head model**

The data that becomes `<title>`, `<meta>`, OpenGraph, and JSON-LD on the server-rendered page: a pure `(db, route) → head-model` function registered with `reg-head` and named by a route. Derived from app-db like a sub, so the model is *reconstructible* from the hydrated state — but reconstructible is not automatic. v1 ships no DOM-head reconciler, and the runtime compares only the body hash, so keeping the live head current after a client-side route change (and checking the head's separate `:rf/head-hash`) are the app's or host's job. Both obligations are set out in the recipe: [Head metadata](head.md).

### **Ring handler**

What `day8/re-frame2-ssr-ring` gives you: `ssr-handler` returns a plain Ring handler that runs one request through a per-request frame and returns a Ring response map; `stream-handler` is the same with a streamed body; `ssr-middleware` routes the requests its `:match?` predicate accepts (every GET by default) to an `ssr-handler` and everything else to the handler it wraps — static assets and API routes, say. Request keys that Ring middleware adds — `:form-params` (`wrap-params`), `:session` (`wrap-session`), `:cookies` (`wrap-cookies`) — are only on the request if that middleware runs in front. Options: [`re-frame.ssr.ring`](../api/re-frame.ssr.ring.md).

### **page shell**

The HTML document around the rendered body: doctype, `<head>`, the app element (`id="app"`), the `__rf_payload` script and the bootstrap `<script src="/main.js">`. `default-html-shell` builds it; the `:head`, `:body-end`, `:script-src` (`false` for none) and `:app-element-id` options adjust it, and `:html-shell` replaces it for `ssr-handler`. `:head` and `:body-end` are inserted without escaping, so never build them from untrusted input. See [`default-html-shell`](../api/re-frame.ssr.ring.md#default-html-shell).

### **error projector**

The pure function that maps a rich internal trace to a sanitized, client-safe `:rf/public-error` shape when a server render throws. The error page only ever receives the projected shape, so internals physically cannot leak to the wire. That shape is **closed**: exactly `:status`, `:code`, `:message`, `:retryable?` — none missing, none extra. Return anything else and your projector is discarded in favour of the locked generic-500 ([`reg-error-projector`](../api/re-frame.ssr.md#reg-error-projector)).

### **error view**

The page `ssr-handler` ships when a request's error projects to a 5xx: `:error-view`, a registered view id or a `(fn [public-error] …)` returning hiccup, rendered from the [public error](#error-projector) alone. A projected 4xx keeps the app's own page instead. Failures the projector cannot see — the per-request frame failing to set up, a header that cannot be written — go to `:on-error`, a `(fn [request throwable] …)` returning a Ring response, whose default is a fixed plain-text `500`.

### **suspense boundary**

The streaming component `ssr/boundary` — an `:id`, a `:fallback`, a subtree. It defers the subtree on the server and renders it in the browser, so one view serves both hosts (`:rf/suspense-boundary` survives only as internal wire syntax). The server flushes the page shell with fallbacks in place (fast first byte), then streams each boundary's subtree in as its data resolves. One boundary failing keeps its fallback and traces; the rest of the page streams on. Recipe: [Streaming](streaming.md).

### **hydration delta**

In a streamed page, the app-db changes a [suspense boundary](#suspense-boundary)'s drain made, sent with that boundary's chunk and filtered through the same `:payload` allowlist, so the region's subscriptions see the right state when it swaps in. Speculative: the final chunk carries the full payload, and where the two disagree the payload wins.

### **Node renderer**

`re-frame.ssr.ring.node/renderer`, passed as `ssr-handler`'s `:renderer`, renders the page body on a Node sidecar process (`implementation/ssr-node`) instead of on the JVM — for a native view layer, such as Fresco, whose components are JavaScript the JVM cannot call. The JVM keeps everything else: the frame, the drain, the head, the payload, the response. See [Render on Node](concepts.md#render-on-node).

### **render-state policy**

The Node renderer's required `:render-state` option: which top-level app-db and runtime-db keys the sidecar is handed for rendering, or a `(fn [frame-id] …)` that projects them. A separate allowlist from `:payload`, because what the render needs and what the browser may see differ — a server-only notice the render reads but the browser never receives, say.
