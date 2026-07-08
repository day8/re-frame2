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

;; rf2-g8pbwg: `install-url-listener!` / `remove-url-listener!` (and their
;; retired `install-history-listener!` / `remove-history-listener!` aliases)
;; are GONE — a `:url-bound? true` frame installs its strategy listener on
;; create and removes it on destroy, automatically (the `:url-bound?` frame
;; lifecycle IS the wiring). See `re-frame.routing.history`'s ns docstring
;; THE FOLD note. Pre-alpha, no back-compat shim.

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
