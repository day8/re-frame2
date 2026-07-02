# SSR glossary

re-frame2's optional server-side-rendering capability — run your real app on the JVM to ship HTML before JavaScript loads, then hand state to the client to take over. "One app, runs twice." See [SSR](concepts.md).

### **SSR**

Server-side rendering: rendering your app to an HTML string on the server (per request, in its own [frame](../core/glossary.md#frame)) so the first paint arrives before the client bundle runs. The same events, subscriptions, and views run on both sides — you don't write a second app. Code that must run on only one side declares it once with [platform gating](#platform-gating), so no logic branches on `typeof window`.

### **render-to-string**

The pure function at the heart of SSR — `hiccup → HTML string`, no browser, no DOM, JVM-runnable. It runs your real [views](../core/glossary.md#view) against a [per-request frame](#per-request-frame), which is what makes "one app, runs twice" hold. Lives in `re-frame.ssr`; re-exported on the `rf/` facade.

### **per-request frame**

The [frame](../core/glossary.md#frame) the host creates for one HTTP request and destroys when the response is written. Its `:initial-events` boot the page's state, and the runtime drains to a settled app-db before rendering. Because every request gets its own, concurrent requests are isolated worlds that cannot see, race, or corrupt one another.

### **hydration**

The client picking up the server's already-painted HTML and *adopting* it — installing the [hydration payload](#hydration-payload) and attaching event listeners — instead of throwing it away and re-rendering. `ssr/hydrate!` dispatches the framework-owned `:rf/hydrate` before the first render; it **replaces** the client's frame-state rather than merging into it, because on this question the server is authoritative.

### **hydration payload**

The server's finished state, serialized into the page as an EDN `<script id="__rf_payload">`: `:rf/version`, `:rf/app-db` (filtered by the [payload allowlist](#payload-allowlist)), `:rf/runtime-db` (the framework's serializable slice — route, machine snapshots), and `:rf/render-hash`. What `hydrate!` reads and installs.

### **payload allowlist**

The Ring adapter's required `:payload` option — the top-level app-db keys allowed to cross the wire to the client. It fails closed: everything not named stays on the server, *including keys you haven't written yet*, and omitting the option is a boot error (`:rf.error/ssr-missing-payload-policy`), never a quiet leak.

### **hydration mismatch**

When the client's first render disagrees with the server's HTML. re-frame2 compares a structural hash of both render trees and fires a structured trace carrying both hashes — warn-and-replace by default, a thrown error under strict mode (`:ssr {:on-mismatch :hard-error}`) for CI. The classic silent SSR bug, made loud.

### **platform gating**

The `:platforms` declaration on an [effect](../core/glossary.md#effect) or [coeffect](../core/glossary.md#coeffect): registered as `#{:client}` (or `#{:server}`), it's skipped — with a trace — when a drain runs on the other side. One handler runs on both platforms with zero `typeof window` branching.

### **head model**

The data that becomes `<title>`, `<meta>`, OpenGraph, and JSON-LD on the server-rendered page: a pure `(db, route) → head-model` function registered with `reg-head` and named by a route. Derived from app-db like a sub, so it stays current on client-side route changes and rides the same mismatch detector as the body.

### **error projector**

The pure function that maps a rich internal trace to a sanitized, client-safe `:rf/public-error` shape (`:status`, `:code`, `:message`) when a server render throws. The error page only ever receives the projected shape, so internals physically cannot leak to the wire.

### **suspense boundary**

The streaming marker `:rf/suspense-boundary` — an `:id`, a `:fallback`, a subtree. The server flushes the page shell with fallbacks in place (fast first byte), then streams each boundary's subtree in as its data resolves. One boundary failing keeps its fallback and traces; the rest of the page streams on.
