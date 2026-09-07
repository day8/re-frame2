# MIG-23 — SSR-then-hydrate (D-tier)

> The one D-tier rule that is a **recipe** rather than a decision table, and the
> one a client-only migration never opens. It lives here rather than in
> [`catalog-judgment.md`](catalog-judgment.md) so a client-only SPA pass does
> not carry it; the D-tier roster and the decision's one-line summary stay
> there. Routed from [`../SKILL.md`](../SKILL.md) and from MIG-15's SSR pointer
> in [`catalog-mechanical.md`](catalog-mechanical.md).

```clojure
;; before — Reagent
(ns app.render (:require [reagent.dom.server :as s]))
(defn render-page [] (s/render-to-string [app]))
;; the client then calls reagent.dom.client/hydrate-root
```

**The pipeline ships, so this is a decision rather than a hold.**
`re-frame.hicasso.server` is Hicasso's optional server module and `render` is
its product door; both halves of the client boot exist too —
`re-frame.ssr/hydrate!` for state and `h/hydrate!` for the DOM. Read them at
`implementation/hicasso/src/re_frame/hicasso/server.cljs` and
`implementation/ssr/src/re_frame/ssr.cljc`.

**The decision is infrastructure, not spelling.** React renders the server
output and there is **no parallel JVM string emitter**, so the renderer runs on
Node. An app whose SSR is a JVM ring handler today is deciding whether to stand
up a Node rendering service — a deployment change no view rewrite can make for
it. Raise that with the author before converting a hydrating root; if the answer
is no, those roots stay on Reagent, which is still fully supported. The hold, if
there is one, is **per-root**: client-only roots in the same app convert
normally.

**Both halves boot cold, and a cold process has no adapter.** The Node
renderer is a **separate process** from the browser, and nothing installs an
adapter for either — there is no default-adapter registry (the MIG-15
invariant), and `server/render` mints a frame per request, so frame
construction raises `:rf.error/no-adapter-installed` in a never-initialized
process before any HTML is produced. Each process installs exactly one
appropriate adapter with `rf/init!` at startup, before its first frame:
`ssr/adapter` on the Node service, the app's existing Reagent adapter in the
browser.

**The server half is one `rf/init!` at process boot, then one render call per
request.**

```clojure
(ns app.server
  (:require [re-frame.core :as rf]
            [re-frame.ssr :as ssr]
            [re-frame.hicasso.server :as server]))

(rf/init! ssr/adapter)   ;; once, at process startup — never per request

(:document (server/render {:hiccup            [views/page {}]
                           :payload           payload-policy
                           :snapshot          {:catalog/items items}
                           :client-frame-id   :app/main
                           :identifier-prefix "main"}))
```

`:hiccup` and `:payload` are REQUIRED. `:payload` is the framework's fail-closed
hydration-payload policy — allowlist every top-level app-db key the page reads,
or the client hydrates against a hole. `render` mints and destroys its own
per-request frame, and answers `:html`, `:payload-script` and `:document` among
others. `ssr/adapter` (`re-frame.ssr/adapter`) is the headless server-side
adapter, and installing it is boot work, not request work — two requests after
one `rf/init!` are the normal shape.

**The client half is a boot precondition plus THREE ordered calls, and the
order is the contract.** The install is boot, not a fourth hydration step:
keep the migrating app's existing `(rf/init! reagent-adapter/adapter)` —
MIG-15's line — ahead of the three calls. Adapter selection is not part of
this migration, so do not silently switch a part-migrated app to another
adapter (a Hicasso-only app may deliberately choose
`re-frame.hicasso.substrate/adapter`, but that is its own decision). Skip the
install in a cold client entry and the first `rf/make-frame` raises the same
`:rf.error/no-adapter-installed`, so `ssr/hydrate!` and `h/hydrate!` never
run.

```clojure
(defn ^:export run []
  (rf/init! reagent-adapter/adapter)                   ;; 0. boot — the app's existing adapter
  (rf/make-frame {:id :app/main :platform :client})    ;; 1. the frame
  (ssr/hydrate! {:frame :app/main})                    ;; 2. state
  (h/hydrate! (js/document.getElementById "app")       ;; 3. DOM
              {:frame :app/main :identifier-prefix "main"}
              [views/page {}]))
```

- **The install is per process, not per load.** `rf/init!` is idempotent for the
  adapter it seated (a different adapter raises
  `:rf.error/adapter-already-installed`),
  `run` is the page's one boot entry, and a hot-reload pass re-renders through
  the root handle (MIG-15's `h/render!` shape) rather than re-running `run` —
  the hydration/HMR path never re-runs `rf/init!`.
- **`rf/make-frame` first of the three.** Unlike `h/mount!`, `h/hydrate!` does
  **not** ensure the frame — an adopting root takes its state from the payload.
  Skip it and the `:rf/hydrate` dispatch is a silent no-op: nothing throws and
  the page renders empty.
- **`ssr/hydrate!` before `h/hydrate!`.** It reads the `__rf_payload` script,
  replaces that frame's state and verifies, so the first client render sees the
  state the server rendered from.
- **`h/hydrate!` is `(node config view)`**, returns the handle `h/render!` and
  `h/unmount!` take, and returns *before* adoption finishes.

**Hand `:identifier-prefix` the same string on both sides.** React numbers
`useId` per root and prefixes it with this option, so a root hydrated under a
different prefix — or none, where the server had one — resolves every generated
id differently from the bytes it is adopting. A page with one root names none;
a page with several gives each a distinct prefix.

**Still out of scope:** streaming, React Server Components, islands and
no-JavaScript progressive enhancement. And a body that reads a clock, a random
value or a browser global mismatches on either substrate — that is determinism,
not a Hicasso limit; `:rf.ssr/hydration-mismatch` names the offending root.

(`h/portal` takes a `:fallback` for its tree position on a server render — a
portal's own policy, not a hydration path.)

