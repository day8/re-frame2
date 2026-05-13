(ns re-frame.core-routing
  "Public-API wrappers for the optional routing artefact (Spec 012).
  Implementation ships in `day8/re-frame2-routing`
  (`re-frame.routing` ns) per rf2-k682.

  Per [Conventions §Optional-artefact wrapper convention](../../../../../spec/Conventions.md#optional-artefact-wrapper-convention) — wrappers
  look the producing fns up via the late-bind hook table at call time;
  consumers reach the surfaces through `re-frame.core` re-exports.

  Per-feature carve-out (relative to the canonical convention): the
  routing artefact pulls the route-rank / pattern-compile / nav-token
  machinery, the `:rf/route` reg-sub family, and every `:rf.route/*` /
  `:rf.nav/*` keyword string — none of which appear on a consumer's
  classpath when this wrapper's hooks are unregistered.

  Per rf2-h824v the wrappers below are emitted by the
  `re-frame.core-artefact/defwrapper` factory from a declarative table —
  one row per public surface."
  (:require [re-frame.core-artefact #?@(:clj  [:refer        [defwrapper]]
                                        :cljs [:refer-macros [defwrapper]])]))

(def ^:private routing-artefact
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

(defwrapper match-url
  "Per Spec 012 §Bidirectional URL ↔ params. Match a URL against
  registered routes; return `{:route-id :params :query
  :validation-failed?}` for the first match, or `nil` if no route
  matches. Late-bound via :routing/match-url."
  {:hook :routing/match-url :artefact routing-artefact :on-absent :throw}
  ([url] :delegate))

(defwrapper route-url
  "Per Spec 012 §Bidirectional URL ↔ params. Inverse of `match-url` —
  build a URL string from a route-id + path-params (and optional
  query-params). Late-bound via :routing/route-url."
  {:hook :routing/route-url :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id route-id}}
  ([route-id path-params]              [route-id path-params {}])
  ([route-id path-params query-params] :delegate))

(defwrapper reg-route
  "Fn-form delegate that performs the late-bind lookup for `reg-route`.
  The `re-frame.core/reg-route` macro (JVM) and the CLJS `def`-alias
  both route here, so the late-bind logic and the missing-artefact
  error message live in one place."
  {:hook :routing/reg-route :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id id}}
  ([id metadata] :delegate))

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
