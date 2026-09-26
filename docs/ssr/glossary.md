# SSR glossary

Terms used by re-frame2's server-side rendering, one entry each, with a link to the
page that teaches it.

### **SSR**

Server-side rendering: rendering your app to an HTML string on the server, per
request, so the first paint arrives before the client bundle runs. In re-frame2 the
same events, subscriptions and views run on both sides; code that belongs to one
side is declared with [platform gating](#platform-gating).
See [The model](concepts.md).

### **per-request frame**

The [frame](../core/glossary.md#frame) the host creates for one HTTP request and
destroys when the response is written. Its `:initial-events` boot the page's state,
and the runtime drains them to a settled app-db before rendering. Concurrent
requests each have their own frame, so they cannot see each other's state.
See [Tutorial Step 2](tutorial.md#step-2--a-frame-per-request).

### **`:rf/server-init`**

The conventional boot event for a per-request frame, named in `:initial-events`.
The name is reserved and your app registers the handler, usually with
`:platforms #{:server}` and `:rf.cofx/requires [:rf.server/request]` so it can read
the request. It dispatches whatever the page needs, typically the route change for
the request's URL. See [Reading the request](concepts.md#reading-the-request).

### **render-to-string**

`ssr/render-to-string`: a pure function from hiccup to an HTML string, with no
browser or DOM, runnable on the JVM. It lives only in `re-frame.ssr`; there is no
copy on the `rf/` facade. See [`render-to-string`](../api/re-frame.ssr.md#render-to-string).

### **blocking resource**

A [resource](../resources/glossary.md#resource) a route declares with
`:blocking? true` in its `:resources`. The Ring handler waits for a route's blocking
resources to settle before it renders, within a 5-second render budget; one that has
not settled by then enters `:error`, and the page renders its error state. Nothing
else is waited for: a fetch an `:initial-events` handler starts itself is usually
still in flight when the render begins.
See [Data the first render needs](concepts.md#data-the-first-render-needs).

### **Ring handler**

What `day8/re-frame2-ssr-ring` provides. `ssr-handler` returns a plain Ring handler
that runs one request through a per-request frame and returns a Ring response map;
`stream-handler` is the same with a streamed body; `ssr-middleware` sends the
requests its `:match?` predicate accepts (every GET by default) to an `ssr-handler`
and everything else to the handler it wraps, such as static assets and API routes.
Request keys that Ring middleware adds, such as `:form-params` (`wrap-params`),
`:session` (`wrap-session`) and `:cookies` (`wrap-cookies`), are on the request only
if that middleware runs in front. Options: [`re-frame.ssr.ring`](../api/re-frame.ssr.ring.md).

### **page shell**

The HTML document around the rendered body: doctype, `<head>`, the app element
(`id="app"`), the `__rf_payload` script and the bootstrap `<script src="/main.js">`.
`default-html-shell` builds it; the `:head`, `:body-end`, `:script-src` (`false` for
none) and `:app-element-id` options adjust it, and `:html-shell` replaces it for
`ssr-handler`. `:head` and `:body-end` are inserted without escaping, so never build
them from untrusted input.
See [`default-html-shell`](../api/re-frame.ssr.ring.md#default-html-shell).

### **payload allowlist**

The Ring handler's required `:payload` option: the top-level app-db keys allowed to
reach the client. Every key not named stays on the server, including keys added
later. Omitting the option throws `:rf.error/ssr-missing-payload-policy` when the
handler is constructed.
See [`:payload`](concepts.md#payload--the-fail-closed-allowlist).

### **hydration payload**

The server's finished state, written into the page as EDN in
`<script id="__rf_payload">`: `:rf/version`, `:rf/app-db` (filtered by the
[payload allowlist](#payload-allowlist)), `:rf/runtime-db` (the framework's
serialisable slice: route and machine snapshots) and `:rf/render-hash`. `hydrate!`
reads and installs it.

### **client frame id**

`ssr-handler`'s `:client-frame-id` option: a stable frame id, such as `:app`,
written into the payload as `:rf/frame-id` so the client can check it is hydrating
the frame the server meant. It is omitted by default, because the server renders
under a per-request frame the client never sees. Never set it to a per-request
value: a payload whose id differs from the client's `:frame` throws
`:rf.error/hydration-frame-id-mismatch`.
See [Hydrate, then verify](concepts.md#the-client-side-hydrate-then-verify).

### **hydration**

The client adopting the server's rendered page instead of discarding it and
rendering again. `ssr/hydrate!` installs the [hydration payload](#hydration-payload)
by dispatching the framework-owned `:rf/hydrate` before the first render, replacing
the client frame's state rather than merging into it; the adapter's `render!` with
`{:hydrate? true}` then attaches to the existing DOM.
See [Hydrate, then verify](concepts.md#the-client-side-hydrate-then-verify).

### **render hash**

A structural hash of the server's render tree, stamped as `data-rf-render-hash` on
the root element and as `:rf/render-hash` in the payload; `hydrate!` hashes the
client's first render and compares the two. It ships only for a fn-form `:root-view`
whose root view returns an element. See
[What the hash covers](concepts.md#what-the-hash-covers).

### **hydration mismatch**

The client's first render disagreeing with the server's HTML. For views that return
hiccup, `hydrate!` compares the two [render hashes](#render-hash) and emits a
`:rf.ssr/hydration-mismatch` trace carrying both. By default the client's render
replaces the server's; with `:ssr {:on-mismatch :hard-error}` on the client frame it
throws, which is how CI catches one.
See [When the renders disagree](concepts.md#when-the-renders-disagree).

### **deploy-drift checks**

Two checks `:rf/hydrate` runs on the client: the payload's `:rf/version` against the
client's SSR protocol version (`:rf.ssr/version-mismatch`), and, when the server was
given a `:schema-digest`, the payload's `:rf/schema-digest` against the client's
registered schemas (`:rf.ssr/schema-digest-mismatch`). Each emits a trace and lets
hydration proceed. They catch a server and a client bundle from different deploys.
See [Deploy-drift checks](concepts.md#deploy-drift-checks-come-along-for-free).

### **several roots on one page**

A page whose server markup is adopted by more than one client root, such as a
header and a cart, usually hydrating one frame. `ssr/hydrate-page!` boots them in
order, each inside its own failure boundary with its optional `:mount-fn`, so a
root that throws is reported (`:rf.error/root-boot-failed`) and the others still
boot. Installing the same payload twice into one frame does nothing; a different
payload throws `:rf.error/frame-payload-conflict`.
See [Several roots on one page](concepts.md#several-roots-on-one-page).

### **platform gating**

The `:platforms` declaration on an [effect](../core/glossary.md#effect) or
[coeffect](../core/glossary.md#coeffect). One registered as `#{:client}` (or
`#{:server}`) is skipped, with a `:rf.fx/skipped-on-platform` trace, when a drain
runs on the other side, so a handler runs on both platforms without branching on
the runtime. See [`:platforms`](concepts.md#platforms--one-handler-gated-per-runtime).

### **head model**

The data that becomes `<title>`, `<meta>`, OpenGraph and JSON-LD on the
server-rendered page: a pure `(db, route) → head-model` function registered with
`reg-head` and named by a route. The runtime ships no DOM-head reconciler and
compares only the body hash, so updating the live head after a client-side route
change, and checking the head's `:rf/head-hash`, are the app's or host's job.
See [Head metadata](head.md).

### **error projector**

The pure function that maps an internal trace to the client-safe `:rf/public-error`
shape when a server render throws. The error page receives only the projected
shape, so internal detail never reaches the response. The shape is closed: exactly
`:status`, `:code`, `:message` and `:retryable?`. A projector that returns anything
else is discarded in favour of the fixed generic 500.
See [When the server throws](concepts.md#when-the-server-throws) and
[`reg-error-projector`](../api/re-frame.ssr.md#reg-error-projector).

### **error view**

The page `ssr-handler` sends when a request's error projects to a 5xx:
`:error-view`, a registered view id or a `(fn [public-error] …)` returning hiccup,
rendered from the [public error](#error-projector) alone. A projected 4xx keeps the
app's own page. Failures the projector cannot see, such as the per-request frame
failing to set up or a header that cannot be written, go to `:on-error`, a
`(fn [request throwable] …)` returning a Ring response, whose default is a fixed
plain-text `500`. See [When the server throws](concepts.md#when-the-server-throws).

### **suspense boundary**

The streaming component `ssr/boundary`: an `:id`, a `:fallback` and a subtree. On
the server it defers the subtree to a later chunk; in the browser it renders the
subtree, so one view serves both. The server sends the page shell with fallbacks in
place, then each boundary's subtree as its data resolves. A boundary that throws
keeps its fallback and emits a trace; the rest of the page streams on.
`:rf/suspense-boundary` is internal wire syntax, never written by hand.
See [Streaming](streaming.md).

### **hydration delta**

In a streamed page, the app-db changes a [suspense boundary](#suspense-boundary)'s
drain made, sent with that boundary's chunk and filtered through the same
`:payload` allowlist, so the region's subscriptions see the right state when it
swaps in. The final chunk carries the full payload, and where the two disagree the
payload wins. See [Streaming](streaming.md).

### **Node renderer**

`re-frame.ssr.ring.node/renderer`, passed as `ssr-handler`'s `:renderer`, renders
the page body on a Node sidecar process (`implementation/ssr-node`) instead of on
the JVM. It is for a native view layer, such as Fresco, whose components are
JavaScript the JVM cannot call. The JVM keeps everything else: the frame, the drain,
the head, the payload and the response. See [Render on Node](concepts.md#render-on-node).

### **render-state policy**

The Node renderer's required `:render-state` option: which top-level app-db and
runtime-db keys the sidecar receives for rendering, or a `(fn [frame-id] …)` that
projects them. It is a separate allowlist from `:payload`, because what the render
needs and what the browser may see can differ, such as a server-only notice the
render reads but the browser never receives.
See [Two policies](concepts.md#4-two-policies-and-why-they-differ).
