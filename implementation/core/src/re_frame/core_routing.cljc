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

;; rf2-bcjpq5 / rf2-sy7zr / rf2-wad2fl: `match-url`, `route-url` and
;; `clear-route` are NOT facade exports. Per the czn2m0
;; D1 ruling the tiering rule is reg-* macros + primary ergonomic verbs on
;; `rf/`, advanced query / codec / registry-lifecycle functions in their
;; owning namespace — so all three live only as `re-frame.routing/<name>`
;; (consistent with resources / machines / schemas). `current-url` was a
;; fourth until rf2-kuky.36 deleted it outright: it re-exported
;; `history-url-strategy`'s own `:decode` under a general name and had no
;; caller, so it is not on the facade OR on `re-frame.routing` — a caller
;; that wants the current path-form URL reads it off the frame's strategy,
;; or calls `re-frame.routing.history/current-url`. The dormant
;; `defwrapper`s that used to sit here — and their `:routing/match-url`,
;; `:routing/route-url`, `:routing/clear-route` and `:routing/current-url`
;; late-bind hooks — are GONE; `re-frame.core` never exported them, so
;; nothing consumed them. A routing app already requires `re-frame.routing`
;; at boot, so there is no second public home to justify.
;;
;; What remains below is exactly the two surfaces that still NEED a
;; core-side wrapper: `reg-route` (the façade registration macro's fn-form
;; delegate — source-coord capture, no owned-ns macro form) and
;; `route-link` (a view with no owned-namespace peer). Pre-alpha, no
;; back-compat shim.

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

  The CLJS hook publishes `re-frame.routing/route-link-element` — a fn that
  EMITS the hiccup element `[(views/view-head :route/link) props & children]`
  rather than one that renders. `defwrapper` CALLS whatever its hook holds
  (see `defwrapper`'s docstring §A hook value MUST be a FUNCTION, never a
  COMPONENT), and a view head that is called never becomes a component, so it
  reads its caller's React context instead of its own; publishing the
  registered head itself is what blanked every routed application in
  rf2-nvcp. Emitting the element hands the head to the substrate as an
  element TYPE, which componentizes it exactly as a `reg-view` view. The JVM
  hook publishes the SSR-side render fn directly — SSR has no React context
  to read. Either way `[rf/route-link ...]` in a `.cljc` render tree renders
  correctly on both platforms."
  {:hook :routing/route-link :artefact routing-artefact :on-absent :throw
   :arglists '([props & children])}
  ([& args] :apply))
