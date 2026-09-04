(ns re-frame.routing.link
  "`:route/link` registered view for re-frame2 routing.

  Per Spec 012 §Linking from views. Plain left-click → preventDefault
  + dispatch `:rf.route/url-requested`; modifier-key / middle-click defers to
  the browser. CLJS-only render; JVM gets an SSR shell (no DOM events
  to intercept).

  Internal namespace; the public facade is `re-frame.routing`. The
  facade owns the `views/reg-view*` (CLJS) / `registrar/register!`
  (JVM/SSR) wiring so a `:reload` re-wires both on a fresh registrar."
  (:require [re-frame.router :as rf.router]
            [re-frame.frame :as rf.frame]
            [re-frame.routing.address :as rf.routing.address]
            [re-frame.routing.registry :as rf.routing.registry]
            [re-frame.routing.strategy :as rf.routing.strategy]))

(def prefetch-intent-value
  "The ONE accepted `:prefetch` behaviour value on a route-link (Spec 012
  §Route-plan prefetch). `:intent` warms on credible user intent (hover / focus
  / touch). There is no render mode, viewport mode, global default, or hover-
  delay knob — a passive render dispatches nothing (Governing Law 1)."
  :intent)

(def prefetch-intent-keys
  "The CLOSED class of DOM positions a `:prefetch :intent` link warms from —
  pointer hover, focus, touch-start, the credible-intent triggers Spec 012
  §Route-plan prefetch names. Held here, beside `prefetch-intent-value`, for the
  same reason `rf.routing.address/link-behavior-keys` is held in `address`: it is a closed
  key class of routing's law, and a link surface that writes its own copy can
  drift from it silently — a view artefact's route-link would lack a trigger
  `rf/route-link` installs, with nothing failing to say so.

  Routing's own `prefetch-intent-attrs` maps over it rather than writing the
  positions out again, so a position added here reaches every anchor
  `rf/route-link` renders without a second edit. There is deliberately no
  late-bind seam publishing the class: `:routing/prefetch-intent-keys` was
  retired for want of a reader (rf2-6r9j.15), and a view artefact that needs
  the positions lands a real reader first.

  Order carries no meaning — the positions are independent, and each warms the
  same destination — but it is stable, so the attrs a render emits are too."
  [:on-mouse-enter :on-focus :on-touch-start])

(defn validate-prefetch!
  "Validate the `:prefetch` link-behaviour value, the ONE definition every link
  surface reaches (Spec 012 §`:prefetch :intent` — the opt-in warm-mode
  trigger: \"`:prefetch` is a routing control key on every link surface: it is
  validated and stripped before DOM emission\").

  Three outcomes, and only three:

    - `:prefetch` ABSENT   → passive. The link warms nothing; returns nil.
    - `:prefetch :intent`  → the one accepted value; returns nil.
    - any other PRESENT value → a caller bug. Throws
      `:rf.error/route-link-bad-prefetch`.

  The third case used to be silent: an equality-or-nil test treated `:prefetch
  true` and `:prefetch :render` exactly like an absent key, so a typo or an
  unsupported mode borrowed from another router downgraded the link to a passive
  one with nothing on screen or in the log to say so — the failure mode a
  prefetch typo can least afford, because a passive link looks identical to a
  working one until you measure. `:prefetch nil` and `:prefetch false` are
  PRESENT values and rejected too: the way to not prefetch is to omit the key,
  not to pass a falsey mode. (A conditional call site writes
  `(cond-> props warm? (assoc :prefetch :intent))`.)

  Called from every link calculation that runs on BOTH hosts — `href-attrs` for
  `rf/route-link` (CLJS render + SSR shell), `link-model` for a view
  artefact's own route-link (likewise), and `prefetch-payload` itself — so the surfaces
  Spec 012 declares behaviourally identical agree, server-side included, and no
  surface can reach DOM emission having stripped an invalid value unexamined."
  [props]
  (when (contains? props :prefetch)
    (let [v (:prefetch props)]
      (when-not (= prefetch-intent-value v)
        (throw (rf.routing.registry/route-error
                 :rf.error/route-link-bad-prefetch
                 'rf/route-link
                 (str "route-link :prefetch accepts only " prefetch-intent-value
                      " but got " (pr-str v)
                      ". :intent warms the destination's resources on credible user"
                      " intent (hover / focus / touch); there is no render mode,"
                      " viewport mode, global default, or hover-delay knob. To make"
                      " the link passive, OMIT :prefetch rather than passing a"
                      " falsey or unsupported value")
                 {:slot :prefetch :value v :accepted prefetch-intent-value}))))))

(defn- href-attrs
  "Synthesise the `<a>` href from the link control keys (`:to` /
  `:params` / `:query` / `:fragment`), strip those control keys (plus
  `:on-click`, which the CLJS path replaces and the SSR path drops) off
  `props`, and return `[path-url base-attrs address]`. The base attrs carry the
  synthesised `:href` plus every passthrough HTML attr; both the CLJS
  and SSR render fns build on it so the control-key list lives in ONE
  place and cannot drift between the two halves of the Spec 011 render
  contract. `address` is the extracted address the href was built FROM, handed
  back so the CLJS render's click payload names the destination through the
  same single extraction rather than reading `props` a second time.

  `route-url` builds the path-form URL
  (`/active`); `encode` maps it to the rendered `:href` — one of the four
  strategy consult points. The first return value is the PATH-FORM url
  (unencoded) — it is the behavioural navigation identity carried on the
  `:rf.route/url-requested` dispatch (the click handler routes through the
  cascade, which is path-form throughout). The rendered `:href` is the
  ENCODED form so copy-link / open-in-new-tab land on the right address
  (`#/active` for a hash app). BOTH render fns pass the RENDERING frame's
  strategy `:encode` — the CLJS render its captured frame's, the SSR render
  the frame `rf/with-frame` pinned around the server walk — so the server
  shell and the hydrated client carry the same `:href` (Spec 011's
  structural-equivalence rule; rf2-skr1c). Only the strategy's browser side
  effects are CLJS-only; its pure `:encode` is not."
  [props encode]
  ;; EP-0037 R0b: select the address through the ONE shared extractor
  ;; (`rf.routing.address/extract-address`, the closed `:to`/`:params`/`:query`/`:fragment`
  ;; key class) rather than a bespoke destructuring, and strip the address +
  ;; behaviour keys via the shared key-class constants — so route-link cannot
  ;; drift from what an address IS, and a policy / DOM attr can never leak into
  ;; the synthesised href. The remaining props are the open DOM-attribute map
  ;; (route-link's deliberate exception — Spec 012 §The extraction law).
  ;;
  ;; EP-0037 R3: validate the `:prefetch` behaviour value HERE, in the one
  ;; calculation both halves of the render contract run, so `rf/route-link`
  ;; rejects an unsupported mode on the client AND in the SSR shell rather than
  ;; stripping it silently on the way to the `<a>`.
  ;;
  ;; DELIBERATE DUPLICATION — the `route-url` + `encode` pair below is also
  ;; derived by `link-model` (bottom of this file), and that is STRUCTURAL
  ;; rather than incidental (rf2-arenp). Neither can call the other: this fn
  ;; must ALSO strip the control keys and hand back route-link's open
  ;; DOM-attribute passthrough map (Spec 012 §The extraction law), which
  ;; `link-model` must NOT do — the consuming view artefact owns its own markup
  ;; — and this fn takes `encode` as an ARGUMENT (each render fn resolves its
  ;; frame's strategy `:encode` and hands it in) where `link-model` resolves it
  ;; from the `render-frame` it is handed. A shared helper would need a home both already
  ;; require, and there is none; a namespace invented to hold this calculation
  ;; would cost more than it saves. Do not spend an afternoon unifying them —
  ;; but if a shared home appears for another reason, collapse both onto it.
  ;; (The same wall rf2-wzqtu hit for the readiness projectors.)
  (validate-prefetch! props)
  (let [{:keys [to params query fragment] :as addr} (rf.routing.address/extract-address props)
        path-url (rf.routing.registry/route-url {:to to :params (or params {}) :query (or query {}) :fragment fragment})]
    [path-url
     (-> (apply dissoc props (concat rf.routing.address/address-keys rf.routing.address/link-behavior-keys))
         (assoc :href (encode path-url)))
     addr]))

(defn prefetch-payload
  "The `[:rf.route/prefetch {address}]` dispatch vector for a link whose props
  request `:prefetch :intent`, or nil when the link does not opt in. PURE, both
  hosts — the ONE prefetch calculation, called directly by `rf/route-link`
  (through `prefetch-intent-attrs`), so no link surface reimplements the
  prefetch law. The address is selected through the ONE
  shared extractor (`rf.routing.address/extract-address`), so it is byte-identical to the
  link's own destination (path `:params` + `:query`); `:fragment` is omitted — a
  prefetch is resource-only and a `#fragment` is never a resource input.

  A PRESENT-but-invalid `:prefetch` throws
  (`validate-prefetch!`) rather than returning nil: nil means \"this link is
  passive\", and only an ABSENT key may mean that."
  [props]
  (validate-prefetch! props)
  (when (= prefetch-intent-value (:prefetch props))
    (let [{:keys [to params query]} (rf.routing.address/extract-address props)]
      [:rf.route/prefetch
       (cond-> {:to to}
         (seq params) (assoc :params params)
         (seq query)  (assoc :query query))])))

(defn- url-requested-payload
  "The `[:rf.route/url-requested {…}]` dispatch vector a link click carries —
  the ONE definition, run by `rf/route-link` (`route-link-render`) and by the
  `link-model` seam a view artefact's route-link consumes, so the navigation
  identity the two surfaces dispatch cannot drift.

  `address` is the EXTRACTED address (`rf.routing.address/extract-address`), never a
  bespoke destructuring of the caller's props, so the payload names the same
  destination the rendered `:href` was built from; `path-url` is the PATH-FORM
  url `route-url` built from it (the cascade is path-form throughout, so a
  strategy's `:encode` touches only the `:href`). Empty `:params` / `:query`
  and an absent `:fragment` are omitted, not carried as empty values."
  [{:keys [to params query fragment]} path-url]
  [:rf.route/url-requested
   (cond-> {:url path-url :to to}
     (seq params) (assoc :params params)
     (seq query)  (assoc :query query)
     fragment     (assoc :fragment fragment))])

#?(:cljs
   (defn- compose-intent-handler
     "Build one intent-event handler that runs a caller-supplied handler of the
     same name FIRST (compose, not replace) and then dispatches `payload` to the
     `render-frame` with `:source :router` (rf2 routing-substrate attribution).
     Reused at every `prefetch-intent-keys` position so the credible-intent
     triggers share one composition + dispatch law."
     [caller-handler render-frame payload]
     (fn [e]
       (when caller-handler (caller-handler e))
       (rf.router/dispatch! payload {:source :router :frame render-frame}))))

#?(:cljs
   (defn- prefetch-intent-attrs
     "The `:prefetch :intent` DOM handlers for `rf/route-link`, or nil when the
     link does not opt in. Warms the destination on credible user intent — at
     every `prefetch-intent-keys` position, and NEVER on render / viewport
     (Governing Law 1). Each handler composes with a caller-supplied handler of
     the same name (read from `props`) and dispatches `[:rf.route/prefetch …]` to
     the render-time-captured `render-frame`, exactly as the delayed click
     handler targets its frame. Per Spec 012 §Route-plan prefetch."
     [props render-frame]
     (when-let [payload (prefetch-payload props)]
       ;; Map over the class rather than writing the positions out a second
       ;; time (rf2-7g4qn): `prefetch-intent-keys` is the ONE enumeration of the
       ;; credible-intent positions, so a position added there reaches this
       ;; anchor with no second edit here.
       (into {}
             (map (fn [k]
                    [k (compose-intent-handler (get props k) render-frame payload)]))
             prefetch-intent-keys))))

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
   (defn activate-link!
     "THE router-attributed click decision for a route-link anchor, in ONE
     place: `rf/route-link`'s own `:on-click` (`route-link-render`, below) and
     a view artefact's route-link both run this body, so neither surface states
     the click law for itself. Also PUBLISHED as the `:routing/activate-link!`
     late-bound seam, which is how an optional view artefact reaches it
     without statically requiring routing.

     `e` is the native click event; `on-click` is the caller-supplied
     `:on-click` (or nil); `render-frame` is the render-time-captured frame id;
     `payload` and `native?` come from `url-requested-payload` /
     `native-anchor?` (`link-model` bundles both for the view side).

     Runs the caller `:on-click` first; then, unless the anchor is native (new
     tab / download), the caller already prevented the default, or the click is
     not a plain primary-button click (a modifier-key or auxiliary-button click
     keeps the browser's open-in-new-tab affordance), calls `.preventDefault`
     and dispatches `payload` to the captured render frame with `:source
     :router` (so the L2 epoch timeline tags the cascade as a routing-substrate
     dispatch, per rf2-t1lxr / rf2-1ve9h). `:frame render-frame` is an explicit
     dispatch opt — the router targets the rendering frame verbatim even though
     the render scope has unwound by click time (rf2-o3nam4), so the dispatch
     always lands on the CURRENTLY-committed frame (retarget-safe)."
     [e on-click render-frame payload native?]
     (when on-click (on-click e))
     (when (and (not native?)
                (not (.-defaultPrevented e))
                (plain-left-click? e))
       (.preventDefault e)
       (rf.router/dispatch! payload {:source :router :frame render-frame}))))

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

     The click is handed to `activate-link!` and the payload to
     `url-requested-payload`, the two definitions a view artefact's route-link
     also runs — so this render fn states no part of the click law or the
     dispatch shape for itself, and the two link surfaces Spec 012 declares
     behaviourally identical are identical by construction rather than by
     inspection. Read `activate-link!` for the law (caller `:on-click` first,
     defer on `defaultPrevented` / a native anchor / a modifier or
     auxiliary-button click, else `preventDefault` + dispatch).

     Performance (rf2-r1in4): this is render-path code — every
     `[rf/route-link ...]` re-render walks `route-url` for the href.
     Large nav menus re-rendering frequently amortise the cost over many
     calls; see `route-url`'s perf note for the precompute follow-on
     should it become a bottleneck."
     [{:keys [on-click] :as props} & children]
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
           render-frame (rf.frame/require-current-frame!
                          :route-link
                          {:where 're-frame.routing.link/route-link-render})
           ;; The rendered `:href` is encoded
           ;; through the RENDER-TIME frame's `:url-strategy` (default
           ;; path-form). A hash app renders `#/active`; a history app renders
           ;; `/active`. `url` is the PATH-FORM navigation identity carried on
           ;; the click dispatch — the cascade is path-form throughout, so the
           ;; encode touches ONLY the href. One of the four consult points.
           encode (:encode (rf.routing.strategy/url-strategy-for-frame-id render-frame))
           [url base-attrs address] (href-attrs props encode)
           ;; The click payload comes from the ONE synthesiser
           ;; (`url-requested-payload`) over the address `href-attrs` already
           ;; extracted — the href and the payload therefore name the same
           ;; destination by construction, not by two readings of `props`.
           payload (url-requested-payload address url)
           ;; Anchors carrying native-handling attributes
           ;; (`target="_blank"`, `download`) must let the browser handle
           ;; the click — SPA interception would defeat new-tab / download.
           ;; Computed once at render, off the RAW props — the same side
           ;; `link-model` reads, so the two link surfaces cannot disagree.
           ;; (`:target` / `:download` are neither address nor behaviour keys,
           ;; so the strip does not touch them; reading pre-strip makes that
           ;; independence explicit rather than incidental.)
           native? (native-anchor? props)
           ;; EP-0037 R3: `:prefetch :intent` warms the destination's resources
           ;; on credible user intent (hover / focus / touch). The handlers
           ;; compose with caller-supplied ones of the same name and dispatch to
           ;; the SAME render-time-captured frame the click handler targets, so a
           ;; prefetch warms the frame that rendered the link, never a sibling.
           ;; `:prefetch` is stripped from `base-attrs` as a link-behaviour key
           ;; (`href-attrs` / `rf.routing.address/link-behavior-keys`), so it never reaches
           ;; the `<a>`. Per Spec 012 §Route-plan prefetch.
           intent-attrs (prefetch-intent-attrs props render-frame)
           attrs (merge
                   (assoc base-attrs
                          :on-click
                          (fn [e]
                            (activate-link! e on-click render-frame payload native?)))
                   ;; compose the prefetch intent handlers OVER the base attrs
                   ;; (they wrap any caller-supplied intent handler); nil when
                   ;; the link did not opt into `:prefetch :intent`.
                   intent-attrs)]
       (into [:a attrs] children))))

(defn route-link-render-ssr
  "JVM render fn for `:route/link`. Renders the `<a href=...>` shell
  without the click-interception logic — server-side rendering has no
  DOM events to intercept, so the anchor is emitted as-is and clicks
  on the hydrated page run the CLJS render fn's on-click path. Per
  Spec 011 the render tree is the contract; this is the JVM half of
  that contract for the `:route/link` view.

  The `:href` is encoded through the RENDERING frame's `:url-strategy`,
  exactly as the CLJS render fn encodes it — the pure one of the four
  consult points, so it runs on both hosts (Spec 012 §URL strategies). SSR
  skips only the strategy's browser side effects (`:decode`'s host read,
  `:push!` / `:replace!`, the listener); it does NOT skip `:encode`,
  because Spec 011 makes the server tree and the client's first render
  structurally equal and an `:href` that differed would be a hydration
  mismatch. A `/demos`-based server frame therefore emits `/demos/active`
  and a hash frame `#/active` (rf2-skr1c — this door used to hard-code
  `identity`, so a based server shell rendered `/active` and left its
  deployment mount when followed before hydration).

  The frame is READ, not required: `rf.frame/resolve-current-frame` answers
  the frame the SSR pipeline pins with `rf/with-frame` around its render
  walk (normalised to its id, so a `with-new-frame` VALUE resolves too),
  and nil outside any scope — which `url-strategy-for-frame-id` resolves to
  the history default, so a bare helper call keeps rendering the path form.
  (The CLJS render REQUIRES its frame because it must capture one for the
  click dispatch; the server shell dispatches nothing.)"
  [props & children]
  (let [render-frame          (rf.frame/resolve-current-frame)
        encode                (:encode (rf.routing.strategy/url-strategy-for-frame-id render-frame))
        [_url attrs _address] (href-attrs props encode)]
    (into [:a attrs] children)))

;; ---------------------------------------------------------------------------
;; The substrate-neutral late-bound link seam (rf2-vxgfnd.95.5)
;;
;; A view artefact's own route-link is an ORDINARY view in an OPTIONAL
;; artefact. It must not statically require routing
;; (the packaging-independence rule, Conventions §Packaging) yet must render an
;; anchor whose href, dispatch payload, and click law are the SAME ones this
;; namespace already owns for `rf/route-link`. Two late-bound `:routing/*` hooks
;; publish that calculation behind a small substrate-neutral seam routing owns
;; and the view artefact consumes — so the view reimplements NONE of the routing
;; link law and neither
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
;;                      DEFINED ABOVE, beside the click predicates it reads,
;;                      because `rf/route-link`'s own `:on-click` calls it too —
;;                      it is the shared click law first and a view seam second.
;;
;; The view side captures its render frame and threads it through both hooks;
;; it owns nothing but the anchor's markup + passthrough attrs.

(defn link-model
  "The `:routing/link-model` seam (PURE, both hosts). Given a link `target`
  (the `:to` / `:params` / `:query` / `:fragment` control keys plus the
  native-handling HTML attrs `:target` / `:download`) and the ui view's
  captured `render-frame` id, compute the rendered link model:

    {:href    <strategy-encoded href — one of the four strategy consult
              points, on BOTH hosts>
     :payload <the FULL `[:rf.route/url-requested {...}]` dispatch vector,
              path-form throughout — the navigation identity>
     :native? <true when the anchor carries native-handling attrs the
              framework must not intercept even on a plain left click>}

  Bundles route-url synthesis, strategy encode, the shared
  `url-requested-payload`, and native-attr detection, so the ui artefact reaches
  route-link semantics through this one seam without exposing routing internals
  (strategy encode, frame capture, source stamping) as separate hooks — and
  reaches the SAME payload synthesiser `rf/route-link` uses, not a copy of it.
  `render-frame`'s `:url-strategy` supplies `:encode` on both hosts (a nil
  `render-frame` resolves to the history default), so the server shell's href
  is the one the hydrated client renders — SSR skips the strategy's browser
  side effects, never its pure `:encode` (rf2-skr1c; the `:clj` arm used to
  hard-code `identity`). Per Spec 012 §Linking from views and the rf2-5yovjt
  ruling."
  [target render-frame]
  ;; EP-0037 R0b: select the address through the ONE shared extractor
  ;; (`rf.routing.address/extract-address`) — the same closed key class `rf/route-link`,
  ;; `route-url`, and `:rf.route/navigate` resolve through. `native-anchor?`
  ;; still reads the FULL `target` (the `:target` / `:download` DOM attrs live
  ;; outside the address class) — the same side `route-link-render` reads, so
  ;; the two surfaces classify a native anchor identically by construction.
  ;;
  ;; EP-0037 R3: `link-model` is the one link calculation a view artefact's
  ;; route-link runs on BOTH hosts, so the `:prefetch` value is validated
  ;; here too. Its `prefetch-payload` call is CLJS-only (the JVM/SSR shell
  ;; installs no intent handlers), which left the server render accepting an
  ;; unsupported mode the client rejected.
  ;;
  ;; DELIBERATE DUPLICATION — the `route-url` + `encode` pair below is also
  ;; derived by `href-attrs` (top of this file, the `rf/route-link` internal),
  ;; and that is STRUCTURAL rather than incidental (rf2-arenp). Neither can call
  ;; the other: `href-attrs` must ALSO strip the control keys and return
  ;; route-link's open DOM-attribute passthrough map (Spec 012 §The extraction
  ;; law), which this seam must NOT do — the consuming view artefact owns its
  ;; own markup and passthrough attrs — and `href-attrs` takes `encode` as an
  ;; ARGUMENT where this fn resolves it from the `render-frame` it is handed.
  ;; Calling `href-attrs` from here would also invert the seam: it is the
  ;; `rf/route-link` internal, this is the ui-facing surface. A shared helper
  ;; would need a home both already require, and there is none. Do not spend an
  ;; afternoon unifying them — but if a shared home appears for another reason,
  ;; collapse both onto it. (The same wall rf2-wzqtu hit for the readiness
  ;; projectors.)
  (validate-prefetch! target)
  (let [{:keys [to params query fragment] :as addr} (rf.routing.address/extract-address target)
        path-url (rf.routing.registry/route-url {:to to :params (or params {}) :query (or query {}) :fragment fragment})
        encode   (:encode (rf.routing.strategy/url-strategy-for-frame-id render-frame))]
    {:href    (encode path-url)
     :payload (url-requested-payload addr path-url)
     :native? (native-anchor? target)}))

#?(:cljs
   (defn prefetch-on-intent!
     "Dispatch a prefetch `payload` (the `[:rf.route/prefetch {address}]` vector
     from `prefetch-payload`, or nil) at one credible-intent position — the
     intent-position counterpart of `activate-link!` for the click. Runs the
     caller-supplied intent handler FIRST (compose, not replace), then
     dispatches to the render-time-captured `render-frame` with `:source
     :router` (routing-substrate attribution, retarget-safe by explicit
     `:frame`). A nil `payload` (the link did not opt into `:prefetch :intent`)
     still runs the caller handler and dispatches nothing — passive by
     construction.

     NO in-repo caller at present. This was published as the
     `:routing/prefetch-on-intent!` late-bind seam for a view artefact's own
     route-link, and that publication was retired for want of a reader
     (rf2-6r9j.15). `rf/route-link` does NOT route through here — its
     `prefetch-intent-attrs` composes the same behaviour from
     `compose-intent-handler` at every `prefetch-intent-keys` position. Kept as
     routing's one statement of the intent-dispatch law, for a view artefact
     that needs it to call directly."
     [e caller-handler render-frame payload]
     (when caller-handler (caller-handler e))
     (when payload
       (rf.router/dispatch! payload {:source :router :frame render-frame}))))

;; The façade owns the `:route/link` registration:
;;
;;   #?(:cljs (def route-link (views/reg-view* :route/link {} route-link-render))
;;      :clj  (registrar/register! :view :route/link {:handler-fn route-link-render-ssr}))
;;
;; Keeping the registration in the façade means a `(require 're-frame.routing
;; :reload)` on a fresh registrar (the `clear-all!` test-fixture path)
;; re-installs the view.
