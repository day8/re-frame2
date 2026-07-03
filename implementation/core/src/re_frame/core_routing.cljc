(ns re-frame.core-routing
  "Public-API wrappers for the optional routing artefact (Spec 012).
  Implementation ships in `day8/re-frame2-routing` (`re-frame.routing`).
  See [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention)."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

#?(:clj (set! *warn-on-reflection* true))

(def ^:private routing-artefact
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

(defwrapper match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Match a URL against
  registered routes; return `{:route-id :params :query :fragment
  :validation-failed?}` for the first match, or `nil` if no route
  matches. The URL's `#fragment` portion (per Spec 012 §Fragments) is
  parsed off the front and surfaced as `:fragment` (string or `nil`).
  Late-bound via :routing/match-url."
  {:hook :routing/match-url :artefact routing-artefact :on-absent :throw}
  ([url] :delegate))

(defwrapper route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Inverse of `match-url` —
  build a URL string from a route-id + path-params (+ optional
  query-params + optional fragment). The 4-arity form appends
  `#fragment` to the URL when `fragment` is non-nil and non-empty
  (per Spec 012 §Fragments §Programmatic navigation with fragments).
  Late-bound via :routing/route-url."
  {:hook :routing/route-url :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id route-id}}
  ([route-id path-params]                       [route-id path-params {} nil])
  ([route-id path-params query-params]          [route-id path-params query-params nil])
  ([route-id path-params query-params fragment] :delegate))

(defwrapper reg-route
  "Fn-form delegate that performs the late-bind lookup for `reg-route`.
  The `re-frame.core/reg-route` macro (JVM) and the CLJS `def`-alias
  both route here, so the late-bind logic and the missing-artefact
  error message live in one place.

  Per rf2-wvh95f F1 the grammar is the canonical 3-slot
  `(reg-route id metadata path)` — the path-pattern VALUE is the third slot."
  {:hook :routing/reg-route :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id id}}
  ([id metadata path] :delegate))

(defwrapper clear-route
  "Per Spec 012 §Trace events and rf2-dn26r. Remove a registered
  route; emits `:rf.route/cleared` so tools subscribing to route
  lifecycle observe the removal. Symmetric with `:rf.flow/cleared`
  (per [013-Flows.md §Flow tracing](../../../../../spec/013-Flows.md#flow-tracing)).
  No-op when the route id is not registered. Late-bound via
  `:routing/clear-route`."
  {:hook :routing/clear-route :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id id}}
  ([id] :delegate))

(defwrapper current-url
  "Per Spec 012 §URL changes are events. Read the current browser URL as
  an app-relative string `pathname + search + hash` (CLJS), or `\"/\"`
  when no `window.location` is available (SSR / node). Late-bound via
  `:routing/current-url`."
  {:hook :routing/current-url :artefact routing-artefact :on-absent :throw}
  ([] :delegate))

(defwrapper install-url-listener!
  "Per Spec 012 §URL strategies + §Multi-frame routing (rf2-aerrz5). Install
  the URL-owning frame's browser URL-change listener — `popstate` for a
  HISTORY-strategy app, `hashchange` for a HASH-strategy app, per the owner's
  `:url-strategy` (default `history-url-strategy`). Each browser-driven change
  is DECODED to a PATH-FORM URL and dispatched as `:rf.route/handle-url-change`
  to the current URL-owning frame (`url-owner-frame-id`, resolved at fire
  time), then the current URL is synced into that frame's route slice at
  `[:rf.runtime/routing :current]`. This is the inbound (browser → app)
  counterpart of the outbound `:rf.nav/push-url` gate: Back/Forward restores
  the owner frame's route. Idempotent (re-install tears down the prior
  listener — hot-reload safe). CLJS-only. Late-bound via
  `:routing/install-url-listener!`.

  The 0-arity resolves the strategy from the current URL owner; the 1-arity
  takes an EXPLICIT strategy map (e.g. `rf/hash-url-strategy`) for apps whose
  URL-owning frame is created asynchronously, so the listener kind does not
  depend on frame-registration timing."
  {:hook :routing/install-url-listener! :artefact routing-artefact :on-absent :throw}
  ([]      :delegate)
  ([strat] :delegate))

(defwrapper remove-url-listener!
  "Per Spec 012 §URL strategies. Tear down the browser URL-change listener
  installed by `install-url-listener!` (whichever kind the strategy wired).
  No-op when none is installed. CLJS-only. Late-bound via
  `:routing/remove-url-listener!`."
  {:hook :routing/remove-url-listener! :artefact routing-artefact :on-absent :throw}
  ([] :delegate))

(defwrapper install-history-listener!
  "Alias for `install-url-listener!` (rf2-aerrz5) — retained as the
  established boot-seam name for HISTORY-strategy apps. Since the default
  strategy is history, this behaves identically to `install-url-listener!`
  for a path-form app; a hash app can call either name (the owner's
  `:url-strategy` decides the listener kind). Install a browser URL-change
  listener that dispatches `:rf.route/handle-url-change` to the current
  URL-owning frame (`url-owner-frame-id`, resolved at fire time), then sync
  the current URL into that frame's route slice at
  `[:rf.runtime/routing :current]`. Idempotent (hot-reload safe). CLJS-only.
  Late-bound via `:routing/install-history-listener!`. New code should prefer
  `install-url-listener!`. The 1-arity takes an explicit strategy map, same as
  `install-url-listener!`."
  {:hook :routing/install-history-listener! :artefact routing-artefact :on-absent :throw}
  ([]      :delegate)
  ([strat] :delegate))

(defwrapper remove-history-listener!
  "Alias for `remove-url-listener!` (rf2-aerrz5). Tear down the browser
  URL-change listener installed by `install-history-listener!` /
  `install-url-listener!`. No-op when none is installed. CLJS-only.
  Late-bound via `:routing/remove-history-listener!`."
  {:hook :routing/remove-history-listener! :artefact routing-artefact :on-absent :throw}
  ([] :delegate))

(defwrapper route-link
  "Per Spec 012 §Linking from views and API.md `route-link` row.
  Registered view at `:route/link` — renders an `<a href=...>` from a
  registered route id and intercepts plain primary-button clicks to
  dispatch `:rf/url-requested`. Modifier-key clicks (cmd / ctrl / shift /
  alt) and middle-click defer to the browser. Late-bound via
  `:routing/route-link`.

  Shape:
    [rf/route-link {:to :route-id
                    :params {...}
                    :query {...}
                    :fragment \"...\"
                    & passthrough-html-attrs} & children]

  The CLJS hook publishes the Reagent-wrapped render fn (returned by
  `reg-view*`); the JVM hook publishes the SSR-side render fn. Either
  way `[rf/route-link ...]` in a `.cljc` render tree renders correctly
  on both platforms."
  {:hook :routing/route-link :artefact routing-artefact :on-absent :throw
   :arglists '([props & children])}
  ([& args] :apply))
