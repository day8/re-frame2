# SSR glossary

re-frame2's optional server-side-rendering capability — run your real app on the JVM to ship HTML before JavaScript loads, then hand state to the client to take over. "One app, runs twice." See [SSR](concepts.md).

### **SSR**

Server-side rendering: rendering your app to an HTML string on the server (per request, in its own [frame](../guide/glossary.md#frame)) so the first paint arrives before the client bundle runs. The same events, subscriptions, and views run on both sides — you don't write a second app. Code that must run on only one side declares `:platforms #{:client}` (or `#{:server}`), so a `localStorage` write never fires during a server render and no logic branches on `typeof window`.

### **render-to-string**

The pure function at the heart of SSR — `hiccup → HTML string`, no browser, no DOM, JVM-runnable. It runs your real [views](../guide/glossary.md#view) against a per-request [frame](../guide/glossary.md#frame), which is what makes "one app, runs twice" hold.

### **hydration**

The client picking up the server's already-painted HTML and *adopting* it — installing the serialized state (the **hydration payload**) and attaching event listeners — instead of throwing it away and re-rendering. The client dispatches `:rf/hydrate` with the payload before its first render.

### **hydration mismatch**

When the client's first render disagrees with the server's HTML. re-frame2 compares a structural hash of both and fires a trace naming *where* they diverged (default: warn-and-replace; a strict mode for CI) — turning the classic silent SSR bug into a located, debuggable one.
