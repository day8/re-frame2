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

;; `match-url` and `route-url` are NOT
;; facade exports. The tiering rule is reg-* macros
;; + primary ergonomic verbs on `rf/`, advanced query / codec functions in
;; their owning namespace — so those two live only as
;; `re-frame.routing/<name>` (consistent with resources / machines /
;; schemas). There is no `current-url` on the facade OR on
;; `re-frame.routing`: it would only re-export `history-url-strategy`'s own
;; `:decode` under a general name — a caller that wants the current
;; path-form URL reads
;; it off the frame's strategy, or calls
;; `re-frame.routing.history/current-url`. None of the three has a
;; `defwrapper` here, and there are no `:routing/match-url`,
;; `:routing/route-url` or `:routing/current-url` late-bind hooks.
;;
;; `clear-route` is the EXCEPTION: it has a wrapper because `rf/clear`
;; consumes it. `(rf/clear :route id)` routes
;; here so the removal goes through the OWNING lifecycle fn, which emits
;; `:rf.route/cleared`, rather than short-cutting to
;; `rf.registrar/unregister!` and losing the trace event. The wrapper is not
;; a second public home: there is no `re-frame.routing/clear-route`, so
;; `rf/clear` is the only public door.
;;
;; What is below is therefore three surfaces that NEED a core-side
;; wrapper: `reg-route` (the façade registration macro's fn-form delegate —
;; source-coord capture, no owned-ns macro form), `clear-route` (above), and
;; `route-link` (a view with no owned-namespace peer).

(defwrapper clear-route
  "Per Spec 012 §Trace events: remove a registered route, emitting
  `:rf.route/cleared` so tools subscribing to route lifecycle observe the
  removal (symmetric with `:rf.flow/cleared`). A no-op when the route id was
  not registered. Late-bound via `:routing/clear-route`.

  Not a public name of its own — the public door is `(rf/clear :route id)`,
  which is why `:where` names it."
  {:hook :routing/clear-route :artefact routing-artefact :on-absent :throw
   :where 'rf/clear
   :ex-data {:route-id id}}
  ([id] :delegate))

(defwrapper reg-route
  "Fn-form delegate that performs the late-bind lookup for `reg-route`.
  The `re-frame.core/reg-route` macro (JVM) and the CLJS `def`-alias
  both route here, so the late-bind logic and the missing-artefact
  error message live in one place.

  The grammar is the canonical 3-slot
  `(reg-route id metadata path)` — the path-pattern VALUE is the third slot."
  {:hook :routing/reg-route :artefact routing-artefact :on-absent :throw
   :ex-data {:route-id id}}
  ([id metadata path] :delegate))

;; There is no `install-url-listener!` / `remove-url-listener!` — a
;; `:url-bound? true` frame installs its strategy listener on
;; create and removes it on destroy, automatically (the `:url-bound?` frame
;; lifecycle IS the wiring). See `re-frame.routing.history`'s ns docstring.

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

  The CLJS hook publishes `re-frame.routing/route-link-element` — a fn that
  EMITS the hiccup element `[(views/view-head :route/link) props & children]`
  rather than one that renders. `defwrapper` CALLS whatever its hook holds
  (see `defwrapper`'s docstring §A hook value MUST be a FUNCTION, never a
  COMPONENT), and a view head that is called never becomes a component, so it
  reads its caller's React context instead of its own; publishing the
  registered head itself would blank every routed application.
  Emitting the element hands the head to the substrate as an
  element TYPE, which componentizes it exactly as a `reg-view` view. The JVM
  hook publishes the SSR-side render fn directly — SSR has no React context
  to read. Either way `[rf/route-link ...]` in a `.cljc` render tree renders
  correctly on both platforms."
  {:hook :routing/route-link :artefact routing-artefact :on-absent :throw
   :arglists '([props & children])}
  ([& args] :apply))
