(ns re-frame.routing.link
  "`:route/link` registered view for re-frame2 routing.

  Per Spec 012 §Linking from views. Plain left-click → preventDefault
  + dispatch `:rf.route/url-requested`; modifier-key / middle-click defers to
  the browser. CLJS-only render; JVM gets an SSR shell (no DOM events
  to intercept).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `views/reg-view*` (CLJS) / `registrar/register!`
  (JVM/SSR) wiring so a `:reload` re-wires both on a fresh registrar."
  (:require [re-frame.router :as router]
            [re-frame.frame :as frame]
            [re-frame.routing.address :as address]
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

  `route-url` builds the path-form URL
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
  [props encode]
  ;; EP-0037 R0b: select the address through the ONE shared extractor
  ;; (`address/extract-address`, the closed `:to`/`:params`/`:query`/`:fragment`
  ;; key class) rather than a bespoke destructuring, and strip the address +
  ;; behaviour keys via the shared key-class constants — so route-link cannot
  ;; drift from what an address IS, and a policy / DOM attr can never leak into
  ;; the synthesised href. The remaining props are the open DOM-attribute map
  ;; (route-link's deliberate exception — Spec 012 §The extraction law).
  (let [{:keys [to params query fragment]} (address/extract-address props)
        path-url (registry/route-url {:to to :params (or params {}) :query (or query {}) :fragment fragment})]
    [path-url (-> (apply dissoc props (concat address/address-keys address/link-behavior-keys))
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
  the framework must not override with same-document SPA navigation, even
  on a plain left-click. A `route-link` rendered with
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

     Anchors carrying native-handling attributes
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
           render-frame (frame/require-current-frame!
                          :route-link
                          {:where 're-frame.routing.link/route-link-render})
           ;; The rendered `:href` is encoded
           ;; through the RENDER-TIME frame's `:url-strategy` (default
           ;; path-form). A hash app renders `#/active`; a history app renders
           ;; `/active`. `url` is the PATH-FORM navigation identity carried on
           ;; the click dispatch — the cascade is path-form throughout, so the
           ;; encode touches ONLY the href. One of the four consult points.
           encode (:encode (strategy/url-strategy-for-frame-id render-frame))
           [url base-attrs] (href-attrs props encode)
           ;; Anchors carrying native-handling attributes
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

  SSR does not apply URL strategies: the server has no address bar,
  and a hash never reaches it. The href is emitted PATH-FORM (`encode` =
  `identity`); on hydration the CLJS `route-link-render` re-encodes it
  through the frame's strategy. So a hash app's server shell carries
  `/active` and the hydrated anchor carries `#/active`, both pointing at
  the same route."
  [props & children]
  (let [[_url attrs] (href-attrs props identity)]
    (into [:a attrs] children)))

;; ---------------------------------------------------------------------------
;; The substrate-neutral late-bound link seam (rf2-vxgfnd.95.5)
;;
;; The compiled `re-frame.ui/route-link` defview is an ORDINARY compiled view in
;; the OPTIONAL re-frame.ui artefact. It must not statically require routing
;; (the packaging-independence rule, Conventions §Packaging) yet must render an
;; anchor whose href, dispatch payload, and click law are the SAME ones this
;; namespace already owns for `rf/route-link`. Two late-bound `:routing/*` hooks
;; publish that calculation behind a small substrate-neutral seam routing owns
;; and ui consumes — so ui reimplements NONE of the routing link law and neither
;; artefact statically requires the other (`ui -> core late-bind <- routing`).
;;
;;   `link-model`     — PURE, both hosts. The whole routing calculation for one
;;                      link render: strategy-encoded href, the path-form
;;                      `:rf.route/url-requested` dispatch payload, and native?
;;                      (target≠_self / download → the browser owns the click).
;;   `activate-link!` — CLJS only. THE router-attributed click decision (run the
;;                      caller `:on-click`, defer on defaultPrevented / native? /
;;                      modifier / auxiliary-button, else preventDefault + dispatch
;;                      to the captured render frame with `:source :router`).
;;
;; The ui side captures its render frame (its compiled `(ui/frame)` bundle's
;; `:frame` id) and threads it through both hooks; it owns nothing but the
;; anchor's markup + passthrough attrs.

(defn link-model
  "The `:routing/link-model` seam (PURE, both hosts). Given a link `target`
  (the `:to` / `:params` / `:query` / `:fragment` control keys plus the
  native-handling HTML attrs `:target` / `:download`) and the ui view's
  captured `render-frame` id, compute the rendered link model:

    {:href    <strategy-encoded href — one of the four strategy consult
              points on CLJS; path-form (`identity` encode) on the JVM>
     :payload <the FULL `[:rf.route/url-requested {...}]` dispatch vector,
              path-form throughout — the navigation identity>
     :native? <true when the anchor carries native-handling attrs the
              framework must not intercept even on a plain left click>}

  Owns route-url synthesis + strategy encode + payload synthesis + native-attr
  detection, so the ui artefact reaches route-link semantics through this one
  seam without exposing routing internals (strategy encode, frame capture,
  source stamping) as separate hooks. The JVM branch encodes path-form (SSR has
  no address bar); on hydration the CLJS render re-encodes through the frame
  strategy. Per Spec 012 §Linking from views and the rf2-5yovjt ruling."
  [target render-frame]
  ;; EP-0037 R0b: select the address through the ONE shared extractor
  ;; (`address/extract-address`) — the same closed key class `rf/route-link`,
  ;; `route-url`, and `:rf.route/navigate` resolve through. `native-anchor?`
  ;; still reads the FULL `target` (the `:target` / `:download` DOM attrs live
  ;; outside the address class).
  (let [{:keys [to params query fragment]} (address/extract-address target)
        path-url (registry/route-url {:to to :params (or params {}) :query (or query {}) :fragment fragment})
        encode   #?(:cljs (:encode (strategy/url-strategy-for-frame-id render-frame))
                    :clj  identity)]
    {:href    (encode path-url)
     :payload [:rf.route/url-requested
               (cond-> {:url path-url :to to}
                 (seq params) (assoc :params params)
                 (seq query)  (assoc :query  query)
                 fragment     (assoc :fragment fragment))]
     :native? (native-anchor? target)}))

#?(:cljs
   (defn activate-link!
     "The `:routing/activate-link!` seam (CLJS only). THE router-attributed
     click decision for a compiled `ui/route-link` anchor — the SAME click law
     `route-link-render` applies to `rf/route-link`, kept inside routing so the
     ui artefact reimplements none of it.

     `e` is the native click event; `on-click` is the caller-supplied `:on-click`
     (or nil); `render-frame` is the ui view's captured render-frame id; `payload`
     and `native?` come from `link-model`.

     Runs the caller `:on-click` first; then, unless the anchor is native (new
     tab / download), the caller already prevented the default, or the click is
     not a plain primary-button click (a modifier-key or auxiliary-button click
     keeps the browser's open-in-new-tab affordance), calls `.preventDefault` and
     dispatches `payload` to the captured render frame with `:source :router` (so
     the L2 epoch timeline tags the cascade as a routing-substrate dispatch, per
     rf2-t1lxr / rf2-1ve9h). `:frame render-frame` is an explicit dispatch opt —
     the router targets the rendering frame verbatim even though the render scope
     has unwound by click time (rf2-o3nam4), so the dispatch always lands on the
     CURRENTLY-committed frame (retarget-safe)."
     [e on-click render-frame payload native?]
     (when on-click (on-click e))
     (when (and (not native?)
                (not (.-defaultPrevented e))
                (plain-left-click? e))
       (.preventDefault e)
       (router/dispatch! payload {:source :router :frame render-frame}))))

;; The façade owns the `:route/link` registration:
;;
;;   #?(:cljs (def route-link (views/reg-view* :route/link {} route-link-render))
;;      :clj  (registrar/register! :view :route/link {:handler-fn route-link-render-ssr}))
;;
;; Keeping the registration in the façade means a `(require 're-frame.routing
;; :reload)` on a fresh registrar (the `clear-all!` test-fixture path)
;; re-installs the view.
