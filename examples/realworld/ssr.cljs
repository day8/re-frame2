(ns example.realworld.ssr
  "Server-side rendering for the RealWorld example.

   STATUS: stub. The full implementation is pending and tracked under
   bead rf2-kq2z. See examples/realworld/README.md for the scope of this
   feature.

   TODO — full implementation:
   - Convert this file to .cljc (server-side runs on the JVM with :clj
     branches, client-side hydrates with :cljs branches), per Spec 011.
   - :rf/server-init event (:platforms #{:server}) — per-request frame
     initialiser. Reads the request URL via cofx, dispatches
     [:route/handle-url-change url] to populate the :route slice, then
     waits for the route's :on-match events to drain.
   - JVM :http fx implementation — a clojure.core/slurp or
     org.httpkit.client equivalent that can be the :server platform's
     :http; identical event surface (:method, :url, :body, :on-success,
     :on-error) so the same Pattern-RemoteData lifecycle runs server-side.
   - Hydration payload assembly — after the server-side drain settles,
     serialise the populated app-db (minus secrets, per project policy)
     into a :rf/hydration-payload and embed it in the rendered HTML.
   - Pure-hiccup → HTML emission via rf/render-to-string against the
     same root-view as the client.
   - :rf/hydrate event on the client — restores the seeded app-db
     atomically; the first client render produces the same HTML the
     server rendered.
   - Headless test: once this file is converted to .cljc (per the bullet
     above), the test will be genuinely JVM-runnable — construct a server
     frame for URL '/article/hello'; assert that after the drain, the
     :article slice is :loaded and the route is :route/article; assert
     the rendered HTML contains the article title. Until the .cljc port
     happens, the test would be browserless-only (CLJS host).

   Pattern references:
   - docs/specification/011-SSR.md              — server lifecycle, hydrate
   - docs/specification/012-Routing.md          — :on-match SSR semantics
   - docs/specification/Pattern-RemoteData.md   — SSR considerations
   - examples/ssr/core.cljc                     — minimal SSR worked example")

(def ^:private stub :stub)
