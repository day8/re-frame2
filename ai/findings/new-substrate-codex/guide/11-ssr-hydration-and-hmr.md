# SSR, hydration, and hot reload

## Write shared views in `.cljc`

```clojure
(ns app.article-view
  (:require [re-frame.ui :as ui]))

(ui/defview article [{:keys [article]}]
  [:article
   [:h1 (:article/title article)]
   [:div (:article/body article)]])
```

The compiler normalizes one template AST:

- ClojureScript emitter: direct React JSX-runtime calls;
- Clojure/JVM emitter: serializable render tree for `day8/re-frame2-ssr`.

Application value logic must also be cross-host. Guard browser-only code with a declared client boundary rather than letting JVM namespace loading fail.

## Server flow

The existing re-frame2 SSR host remains responsible for:

1. create one frame per request;
2. install request facts/coeffects;
3. run server-init and route/resource loaders;
4. call the JVM root view under that frame;
5. render tree → HTML/head/response;
6. build the allowed hydration payload;
7. finish streaming if used;
8. destroy the request frame and host handles.

`ui/sub` computes from the request frame snapshot. It installs no reactive watchers. Effects and `ui/lease` do not run on JVM.

## Client hydration

```clojure
(defonce root
  (ui/hydrate-root
    (js/document.getElementById "app")
    app
    {}
    {:frame {:id :app/main}
     :identifier-prefix "app-"}))
```

The hydration helper:

1. locates/accepts the payload;
2. validates framework, schema, root view, and template build digests;
3. installs frame state before React renders;
4. calls React `hydrateRoot` with matching identifier prefix/error callbacks;
5. lets the first commit acquire live subscription/resource ownership.

Do not render an empty client frame and “load hydration later.” The first client output must match the server output.

## Determinism

Server and first client render must receive equal facts for:

- app/resource state;
- route and params;
- locale/time zone where output depends on them;
- feature flags;
- generated IDs/identifier prefix;
- random values;
- current time if rendered;
- template build.

Put request-derived durable facts in the hydration projection. Do not call `Date.now`, `random-uuid`, browser layout APIs, or locale defaults directly in shared render and expect equality.

## Client-only content

```clojure
(ui/client-only
  {:fallback
   [:div.map-placeholder
    {:style {:block-size 320}}
    "Interactive map"]}
  [MapView {:center center}])
```

The JVM renders the fallback. The first browser hydration render deliberately renders that same fallback through a small generated boundary. After hydration commits, one root phase update switches every client-only boundary to its client template. A non-hydrating root shows client content immediately. No mismatch is suppressed and no site installs its own effect.

Preserve fallback dimensions/layout where possible because post-hydration replacement can otherwise shift the page.

Keep the fallback capability-free: deterministic DOM or props-only internal views, with no subscriptions, leases, Hooks, refs, events, or foreign descendants. Put shared status/data outside the boundary and isolate only the browser-only leaf. The compiler rejects a placeholder that could attach work for one commit and immediately tear it down.

A JavaScript component without a `ui/client-only` fallback is a compile error on an SSR-reachable `.cljc` path. The initial library has no parallel registry of foreign server adapters.

React Activity is also a client boundary in this architecture. The JVM emitter cannot produce React DOM's internal selective-hydration/resume protocol, so shared SSR views put Activity behind `ui/client-only` rather than assuming visually similar HTML is hydratable.

## Browser-only effects

Effects do not run on SSR, so this is naturally client-only lifecycle:

```clojure
(react/use-effect []
  (fn []
    (install-browser-listener!)))
```

The rendered markup must not depend on the effect having run for the first paint. If it changes visible state, model the initial state explicitly so server and client agree.

## Events

On JVM, direct event vectors remain data in the render tree and the HTML emitter ignores them. On client hydration, the same event sites compile to stable React callbacks.

This makes a headless SSR test able to assert intended interaction even though no function crosses the wire.

## Resources

Server loaders/route plans cause blocking resource work. The view reads explicit state. The hydration payload carries the allowed resource projection.

Client view leases attach after commit. A fresh hydrated resource should not refetch solely because its owner attached; freshness/invalidation policy remains authoritative.

## Hydration mismatch

Treat every mismatch as a bug. Diagnostic order:

1. **Template digest:** client/server built from different source/compiler output.
2. **Frame payload digest/version:** state differs or was installed too late.
3. **First diff path:** tag, prop, text, child order, or fallback.
4. **Nondeterminism:** clock, random, locale, ID.
5. **Foreign component:** escaped/malformed client-only boundary.
6. **Emitter parity:** CLJS and JVM code generators disagree.

React may recover, but recovery is not proof the UI is correct. Never add `suppressHydrationWarning` as a generic escape hatch.

## Forms in SSR

Controlled `value`/`checked` and selected options must come from hydrated state. Uncontrolled `default-value` must also be deterministic.

Avoid a server controlled field becoming client uncontrolled or vice versa. Input parity fixtures cover value, checked, textarea, select, composition boundary, and disabled/error state.

## Keys and list order

Keys do not appear as ordinary DOM attrs, but the server/client collection and order must match. Stable semantic keys help React associate rows during hydration and later updates.

Do not filter using browser-only facts on first render. Put the fact in hydrated state or move the whole branch behind `ui/client-only` with a deterministic fallback.

## Streaming

re-frame2's existing streaming boundary protocol remains the authority. The initial UI library adds no public stream-boundary form; server orchestration can use the existing protocol outside `defview`. A future compiler spelling must first prove that it emits the canonical JVM marker and an equivalent client boundary from one AST.

Streaming is for server chunk/code/data assembly already described by the SSR contract. It does not change the rule that application loading status is explicit state.

## Hot reload

`defview` exports a stable shell and registers the latest implementation.

### Markup/body edit with same Hook signature

- existing component instance and ViewCell survive;
- local React state/refs survive;
- mounted instance token survives;
- cells are marked stale and render the new body;
- subscription/event/resource sites reconcile at commit.

### Hook signature edit

Adding, removing, reordering, or changing a React Hook site changes the compiler signature. The component remounts deliberately:

- old effects clean up;
- old subscription/resource ownership releases;
- local React state resets;
- new instance mounts with a new token.

This is a correctness choice. Preserving state across incompatible Hook topology would corrupt component state.

`ui/sub`, event, and `ui/lease` sites are not React Hooks; conditional changes to those sites reconcile without forcing a remount.

### Subscription implementation edit

Replacing a `reg-sub` body invalidates/rebuilds the canonical sub node under existing re-frame2 rules. A ViewCell never remains pinned to a disposed old subscription handle. Xray records HMR plus changed node/body generation.

## Stable site identity

The compiler derives event/read/lease site anchors from source and template structure. Simple sibling markup edits should preserve unaffected site identity. When it cannot prove identity, it releases/remounts rather than attaching an existing resource owner or callback to the wrong site.

Do not depend on site IDs in application code. They are tool/runtime facts.

## Static HTML export

The JVM render-tree emitter can support non-hydrated HTML export through the existing SSR/static markup path. JavaScript-only content still needs a static fallback. No React DOM server package is pulled into the browser entry.

## SSR checklist

- Shared view/value code is JVM-runnable.
- Per-request frame, never process-shared request state.
- All data loaded before according to explicit SSR policy.
- Hydration payload installed before client render.
- Template and schema digests match.
- `identifier-prefix` matches.
- Client-only nodes have deterministic fallbacks.
- Effects/leases are not required for server markup.
- No clock/random/locale nondeterminism.
- Mismatch callback connected to re-frame2 diagnostics.
- Request frame destroyed after response/stream completion.
