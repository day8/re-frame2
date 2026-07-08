(ns re-frame.routing.link
  "`:route/link` registered view for re-frame2 routing.

  Per Spec 012 §Linking from views. Plain left-click → preventDefault
  + dispatch `:rf.route/url-requested`; modifier-key / middle-click defers to
  the browser. CLJS-only render; JVM gets an SSR shell (no DOM events
  to intercept).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `views/reg-view*` (CLJS) / `registrar/register!`
  (JVM/SSR) wiring so a `:reload` re-wires both on a fresh registrar.
  Per the rf2-2yabr cohesion split: ROUTE-LINK seam."
  (:require [re-frame.router :as router]
            [re-frame.frame :as frame]
            [re-frame.routing.registry :as registry]
            [re-frame.routing.strategy :as strategy]))

(defn- href-attrs
  "Synthesise the `<a>` href from the link control keys (`:to` /
  `:params` / `:query` / `:fragment`), strip those control keys (plus
  `:on-click`, which the CLJS path replaces and the SSR path drops) off
  `props`, and return `[path-url base-attrs]`. The base attrs carry the
  synthesised `:href` plus every passthrough HTML attr; both the CLJS
  and SSR render fns build on it so the control-key list lives in ONE
  place and cannot drift between the two halves of the Spec 011 render
  contract.

  rf2-aerrz5 (URL-strategy seam): `route-url` builds the PATH-FORM URL
  (`/active`); `encode` maps it to the rendered `:href` — one of the four
  strategy consult points. The first return value is the PATH-FORM url
  (unencoded) — it is the behavioural navigation identity carried on the
  `:rf.route/url-requested` dispatch (the click handler routes through the
  cascade, which is path-form throughout). The rendered `:href` is the
  ENCODED form so copy-link / open-in-new-tab land on the right address
  (`#/active` for a hash app). The CLJS render passes its captured frame's
  strategy `:encode`; the SSR render passes `identity` (SSR ignores
  strategies — the path-form href is the server shell, and the hydrated
  CLJS render fn re-encodes on the client)."
  [{:keys [to params query fragment] :as props} encode]
  (let [path-url (registry/route-url to (or params {}) (or query {}) fragment)]
    [path-url (-> props
                  (dissoc :to :params :query :fragment :on-click)
                  (assoc :href (encode path-url)))]))

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

(defn- native-anchor?
  "Return true when `props` carry HTML anchor attributes whose semantics
  the framework MUST NOT override with same-document SPA navigation, even
  on a plain left-click. Per rf2-fwz29i — a `route-link` rendered with
  `{:target \"_blank\"}` or `{:download …}` looks like a normal anchor in
  the DOM and the user expects native new-tab / new-window / download
  behaviour; intercepting it into a `:rf.route/url-requested` dispatch silently
  breaks that contract.

  Native-handling attributes recognised:
  - `:target` (or the string key `\"target\"`) set to anything other than
    `_self` — `_blank` / `_parent` / `_top` / a named frame all open the
    href outside the current document, which SPA interception would defeat.
    `_self` (and a blank/absent target) is the default same-document target
    and remains SPA-interceptable.
  - `:download` (or `\"download\"`) present and not `false`/`nil` — the
    browser must save the resource; SPA interception would suppress the
    download.

  Modifier-key and middle-button clicks already defer via
  `plain-left-click?`; this predicate adds the *attribute-driven* native
  cases the click-position checks cannot see."
  [props]
  (let [target            (or (:target props) (get props "target"))
        has-download-key? (or (contains? props :download)
                              (contains? props "download"))
        download          (if (contains? props :download)
                            (:download props)
                            (get props "download"))]
    (boolean
      (or (and (string? target) (not= "_self" target) (not= "" target))
          (and has-download-key?
               (not (false? download))
               (not (nil? download)))))))

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
     `:rf.route/url-requested`; modifier-key or middle-click → no interception.

     rf2-fwz29i: anchors carrying native-handling attributes
     (`:target` other than `_self`, or `:download`) are NOT intercepted on
     a plain left-click either — they look like a normal anchor in the DOM
     and the user expects native new-tab / download behaviour
     (see `native-anchor?`).

     Performance (rf2-r1in4): this is render-path code — every
     `[rf/route-link ...]` re-render walks `route-url` for the href.
     Large nav menus re-rendering frequently amortise the cost over many
     calls; see `route-url`'s perf note for the precompute follow-on
     should it become a bottleneck."
     [{:keys [to params query fragment on-click] :as props} & children]
     (let [;; rf2-o3nam4: CAPTURE the render-time frame ONCE, here at render —
           ;; NOT at click time. `:route/link` is registered via `reg-view*`
           ;; with this prebuilt fn, so it does NOT receive the `reg-view`
           ;; macro's injected `make-capture-frame` render-time capture
           ;; (core_reg_view_macro.cljc) and must capture for itself.
           ;; A real browser click fires LONG after render, by which point the
           ;; render-time `with-frame` / frame-provider dynamic scope has
           ;; unwound; resolving the frame ambiently at click time would raise
           ;; `:rf.error/no-frame-context` (no scope) or silently route to the
           ;; wrong ambient frame (router.cljc §build-envelope). Capturing now
           ;; pins the navigation to the frame that RENDERED the link and
           ;; survives the async boundary, exactly as `capture-frame` /
           ;; `make-capture-frame` do for view bodies. `require-current-frame!`
           ;; raises at the RENDER site if a link is rendered outside any frame
           ;; scope — fail with the render stack, not a detached click.
           ;; (rf2-afdlyr realm collapse: the former (realm, frame) address
           ;; capture is now just the frame id — the realm substrate is a single
           ;; default realm, so no `:realm` ever rode the envelope.)
           render-frame (frame/require-current-frame!
                          :route-link
                          {:where 're-frame.routing.link/route-link-render})
           ;; rf2-aerrz5 (URL-strategy seam): the rendered `:href` is encoded
           ;; through the RENDER-TIME frame's `:url-strategy` (default
           ;; path-form). A hash app renders `#/active`; a history app renders
           ;; `/active`. `url` is the PATH-FORM navigation identity carried on
           ;; the click dispatch — the cascade is path-form throughout, so the
           ;; encode touches ONLY the href. One of the four consult points.
           encode (:encode (strategy/url-strategy-for-frame-id render-frame))
           [url base-attrs] (href-attrs props encode)
           ;; rf2-fwz29i: anchors carrying native-handling attributes
           ;; (`target="_blank"`, `download`) must let the browser handle
           ;; the click — SPA interception would defeat new-tab / download.
           ;; Computed once at render against the resolved attrs (post
           ;; control-key strip) so the string/keyword attr forms are seen.
           native? (native-anchor? base-attrs)
           attrs (assoc base-attrs
                        :on-click
                        (fn [e]
                          (when on-click (on-click e))
                          (when (and (not native?)
                                     (not (.-defaultPrevented e))
                                     (plain-left-click? e))
                            (.preventDefault e)
                            ;; Per rf2-t1lxr: route-link click → :router
                            ;; origin so the L2 epoch timeline tags the
                            ;; resulting :rf.route/url-requested cascade as a
                            ;; routing-substrate dispatch (not :ui). Per
                            ;; rf2-1ve9h the single closed-enum
                            ;; functional-origin axis is `:source` —
                            ;; routing-internal dispatches stamp
                            ;; `:source :router`. rf2-o3nam4: carry the
                            ;; captured render-time frame so the dispatch
                            ;; targets the rendering frame even though the
                            ;; render scope has unwound by click time. `:frame`
                            ;; is an explicit dispatch opt, so the router uses
                            ;; it verbatim (its resolution order #1) — no
                            ;; ambient read.
                            (router/dispatch!
                              [:rf.route/url-requested
                               (cond-> {:url url :to to}
                                 (seq params)   (assoc :params params)
                                 (seq query)    (assoc :query  query)
                                 fragment       (assoc :fragment fragment))]
                              {:source :router :frame render-frame}))))]
       (into [:a attrs] children))))

(defn route-link-render-ssr
  "JVM render fn for `:route/link`. Renders the `<a href=...>` shell
  without the click-interception logic — server-side rendering has no
  DOM events to intercept, so the anchor is emitted as-is and clicks
  on the hydrated page run the CLJS render fn's on-click path. Per
  Spec 011 the render tree is the contract; this is the JVM half of
  that contract for the `:route/link` view.

  rf2-aerrz5: SSR IGNORES URL strategies — the server has no address bar,
  and a hash never reaches it. The href is emitted PATH-FORM (`encode` =
  `identity`); on hydration the CLJS `route-link-render` re-encodes it
  through the frame's strategy. So a hash app's server shell carries
  `/active` and the hydrated anchor carries `#/active`, both pointing at
  the same route."
  [props & children]
  (let [[_url attrs] (href-attrs props identity)]
    (into [:a attrs] children)))

;; The façade owns the `:route/link` registration:
;;
;;   #?(:cljs (def route-link (views/reg-view* :route/link {} route-link-render))
;;      :clj  (registrar/register! :view :route/link {:handler-fn route-link-render-ssr}))
;;
;; Keeping the registration in the façade means a `(require 're-frame.routing
;; :reload)` on a fresh registrar (the `clear-all!` test-fixture path)
;; re-installs the view.
