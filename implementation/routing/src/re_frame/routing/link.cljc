(ns re-frame.routing.link
  "`:route/link` registered view for re-frame2 routing.

  Per Spec 012 §Linking from views. Plain left-click → preventDefault
  + dispatch `:rf/url-requested`; modifier-key / middle-click defers to
  the browser. CLJS-only render; JVM gets an SSR shell (no DOM events
  to intercept).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `views/reg-view*` (CLJS) / `registrar/register!`
  (JVM/SSR) wiring so a `:reload` re-wires both on a fresh registrar.
  Per the rf2-2yabr cohesion split: ROUTE-LINK seam."
  (:require [re-frame.router :as router]
            [re-frame.routing.registry :as registry]))

(defn- href-attrs
  "Synthesise the `<a>` href from the link control keys (`:to` /
  `:params` / `:query` / `:fragment`), strip those control keys (plus
  `:on-click`, which the CLJS path replaces and the SSR path drops) off
  `props`, and return `[url base-attrs]`. The base attrs carry the
  synthesised `:href` plus every passthrough HTML attr; both the CLJS
  and SSR render fns build on it so the control-key list lives in ONE
  place and cannot drift between the two halves of the Spec 011 render
  contract."
  [{:keys [to params query fragment] :as props}]
  (let [url (registry/route-url to (or params {}) (or query {}) fragment)]
    [url (-> props
             (dissoc :to :params :query :fragment :on-click)
             (assoc :href url))]))

#?(:cljs
   (defn- plain-left-click?
     "Return true when the click event is a plain primary-button click with
     no modifier keys. Modifier-key or auxiliary-button clicks defer to
     the browser so users keep open-in-new-tab / open-in-new-window
     affordances."
     [e]
     (and (zero? (.-button e))
          (not (.-metaKey e))
          (not (.-ctrlKey e))
          (not (.-shiftKey e))
          (not (.-altKey e)))))

#?(:cljs
   (defn route-link-render
     "Render fn for the `:route/link` registered view. Exposed (without
     the registry wrap) so tests can call it directly without going
     through Reagent's component pipeline.

     Shape:
       [rf/route-link {:to :route-id
                       :params {...}
                       :query {...}
                       :fragment \"...\"
                       :on-click <opt user fn>
                       & passthrough-html-attrs}
        & children]

     `:to` is the only required key. `:params`, `:query`, and `:fragment`
     are forwarded to `route-url` for href synthesis. Any other key on the
     props map is passed through to the underlying `<a>` element (e.g.
     `:class`, `:title`, `:id`, `:aria-label`).

     If the caller supplies an `:on-click` fn, it is invoked first; when
     it calls `.preventDefault` (or otherwise the event's
     `defaultPrevented` is true after it returns) the framework's
     plain-left-click interception is skipped — the caller has taken
     responsibility for the navigation. Otherwise the standard rules
     apply: plain left-click → `preventDefault` + dispatch
     `:rf/url-requested`; modifier-key or middle-click → no interception.

     Performance (rf2-r1in4): this is render-path code — every
     `[rf/route-link ...]` re-render walks `route-url` for the href.
     Large nav menus re-rendering frequently amortise the cost over many
     calls; see `route-url`'s perf note for the precompute follow-on
     should it become a bottleneck."
     [{:keys [to params query fragment on-click] :as props} & children]
     (let [[url base-attrs] (href-attrs props)
           attrs (assoc base-attrs
                        :on-click
                        (fn [e]
                          (when on-click (on-click e))
                          (when (and (not (.-defaultPrevented e))
                                     (plain-left-click? e))
                            (.preventDefault e)
                            ;; Per rf2-t1lxr: route-link click → :router
                            ;; origin so the L2 epoch timeline tags the
                            ;; resulting :rf/url-requested cascade as a
                            ;; routing-substrate dispatch (not :ui). Per
                            ;; rf2-1ve9h the single closed-enum
                            ;; functional-origin axis is `:source` —
                            ;; routing-internal dispatches stamp
                            ;; `:source :router`.
                            (router/dispatch!
                              [:rf/url-requested
                               (cond-> {:url url :to to}
                                 (seq params)   (assoc :params params)
                                 (seq query)    (assoc :query  query)
                                 fragment       (assoc :fragment fragment))]
                              {:source :router}))))]
       (into [:a attrs] children))))

(defn route-link-render-ssr
  "JVM render fn for `:route/link`. Renders the `<a href=...>` shell
  without the click-interception logic — server-side rendering has no
  DOM events to intercept, so the anchor is emitted as-is and clicks
  on the hydrated page run the CLJS render fn's on-click path. Per
  Spec 011 the render tree is the contract; this is the JVM half of
  that contract for the `:route/link` view."
  [props & children]
  (let [[_url attrs] (href-attrs props)]
    (into [:a attrs] children)))

;; The façade owns the `:route/link` registration:
;;
;;   #?(:cljs (def route-link (views/reg-view* :route/link {} route-link-render))
;;      :clj  (registrar/register! :view :route/link {:handler-fn route-link-render-ssr}))
;;
;; Keeping the registration in the façade means a `(require 're-frame.routing
;; :reload)` on a fresh registrar (the `clear-all!` test-fixture path)
;; re-installs the view.
