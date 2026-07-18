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

;; rf2-bcjpq5 / rf2-wad2fl: `match-url` / `route-url` are NOT facade
;; exports. Per the czn2m0 D1 ruling the tiering rule is reg-* macros +
;; primary ergonomic verbs on `rf/`, advanced query/codec functions in
;; their owning namespace — so these two live only as
;; `re-frame.routing/match-url` / `re-frame.routing/route-url`
;; (consistent with resources / machines / schemas). The dormant
;; `defwrapper`s that used to sit here — and their `:routing/match-url`
;; / `:routing/route-url` late-bind hooks — are GONE; `re-frame.core`
;; never exported them, so nothing consumed them. A routing app already
;; requires `re-frame.routing` at boot, so there is no second public
;; home to justify. Pre-alpha, no back-compat shim.

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
  dispatch `:rf.route/url-requested`. Modifier-key clicks (cmd / ctrl / shift /
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
