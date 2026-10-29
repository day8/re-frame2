(ns re-frame.fresco.impl.route-link
  "`route-link` — the fifth tier-1 shape's Fresco spelling (the census
  counts **106** route-links across 85 idiomatic files and the charter
  names the form tier-1).

  The census author writes

      (route-link {:to :conduit.profile/show :params {:username u}} u)

  and never sees a URL. What comes back is ONE real anchor, as data:

      [:a {:href     \"/profile/jane\"               ; routing-owned
           :on-click [intent/navigate-head {…}]}    ; the click decision, as data
       \"jane\"]

  ## A plain function, deliberately — not a boundary

  A Fresco boundary costs two hooks and a row in every
  boundary count, and the census's 106 links live INSIDE rows that are
  already boundaries — an author byline is not a unit of re-render. So
  `route-link` is a plain function like
  `re-frame.bench.fresco.shapes.card/card`: called from a body it
  inlines, mints no boundary, adds no hook, and reads no subscription at
  all (the link model is a pure calculation), so the ≤2-hook budget and
  every page's boundary arithmetic are untouched by links.

  ## What is taken from whom, and what is declined

  - **From routing (taken whole): the law.** The href, the dispatch
    payload and the click decision come from the substrate-neutral
    late-bound seams routing publishes for exactly this consumer class —
    `:routing/link-model` here at render, `:routing/activate-link!` in
    the lowered closure (`re-frame.fresco.impl.intent`). This
    file restates NO routing law: `rf/route-link` and this one both run
    the same two
    definitions, and the packaging graph stays
    `fresco -> core late-bind <- routing` (Conventions §Packaging).
  - **Owned here: render-time capture and render-time
    refusal.** The frame is captured at RENDER (a click fires after the
    render scope has unwound), and the lowered click pins the incarnation
    that rendered it (intent.cljs, `navigate-handler`); a missing routing artefact fails at the
    LINK SITE with `:rf.error/routing-artefact-missing`; and the
    route-click one-intent law holds — the click produces the one
    routing intent, see `on-click-roster!`.
    **(Declined:** a fn-only `:on-click` roster. That narrowing is only
    forced on a surface with no in-band spelling for
    \"cancel-and-replace\"; Fresco has one — `[::h/prevent [:app/event]]`
    is the declarative veto, and it is admitted.**)**
  - **From Replicant (taken, via HD-026): behaviour as a
    namespaced-keyword-headed vector.** The anchor's click carries a
    `[intent/navigate-head {…}]` vector `=` can see, so two renders of
    one link are equal and a structural test reads the click decision
    off the tree (`intent/navigate-head?`). The head is the intent
    namespace's own keyword: this function mints it and no author writes
    it, so it is not a door marker. **(Declined:** Replicant's routing posture of a GLOBAL
    body-level click interceptor — ambient behaviour no vector carries is
    exactly what the in-band school exists to avoid.**)**
  - **From routing (taken whole): the prefetch warm-up.** `:prefetch
    :intent` fills the three credible-intent positions — `:on-mouse-enter`,
    `:on-focus`, `:on-touch-start` — with routing's own
    `[:rf.route/prefetch {…}]` vector, and those lower like any other
    in-band intent. Both halves come off the SAME `:routing/link-model`
    seam call the href does (`:prefetch` and `:prefetch-keys`): the
    positions are routing's list, not a copy of it here, so a position
    routing adds reaches this anchor without an edit in this file. The
    sugar is exactly the manual `:on-mouse-enter [:rf.route/prefetch {…}]`
    an author could already write, minted once from the address the link
    already carries — nothing is recomputed and no prefetch policy lives
    here. `:prefetch` itself stays one of the keys the link owns
    (`control-keys`), so it never reaches the anchor as a stray attribute.
    Absent `:prefetch`, none of the three positions is touched: passive by
    construction. See `claimed-intent-positions!` for the one refusal this
    adds.

  ## Composition with `::h/prevent`

  A route-link's click is the cancelable-navigation case the prevent head
  was built for, and the composition is the ordinary grammar: the
  caller's `:on-click` rides the navigate vector's `:veto` slot; a
  `[::h/prevent [:app/event]]` there lowers to the ordinary prevent
  closure; routing runs it FIRST and stands down on `defaultPrevented`.
  One click, one semantic event — the navigation, or the app intent that
  cancelled it. See intent.cljs, `navigate-head` and `navigate-handler`,
  for the click-time half."
  (:require [re-frame.features :as rf.features]
            ;; `canonical-slot` and `without-claimed-slots`: the slots this
            ;; link owns are React props, whichever spelling wrote them.
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [re-frame.fresco.impl.error :refer [fail!]]
            [re-frame.fresco.impl.intent :as rf.fresco.impl.intent]
            [re-frame.late-bind :as rf.late-bind]))

(def ^:private routing-artefact
  "The optional-artefact identity the fail-loud missing-hook error
  reports, carried here so the diagnostic names the missing artefact AT
  THE LINK SITE. The coordinate and require namespace come from routing's
  `re-frame.features/feature-registry` row, the one place each is written."
  (let [{maven :maven require-ns :require} (:routing rf.features/feature-registry)]
    {:error-keyword :rf.error/routing-artefact-missing
     :maven         maven
     :require-ns    require-ns}))

;; `fail!` is `re-frame.fresco.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it.

(def control-keys
  "The keys `route-link` owns. Everything else on the props map is a
  passthrough HTML attribute and reaches the `<a>` untouched — `:class`,
  `:title`, `:data-testid`, `:aria-label`, and the native-handling
  `:target` / `:download` pair the link model reads for its `:native?`
  verdict.

  `:prefetch` is owned too: it is a routing props key, not an HTML
  attribute, so it is stripped rather than emitted as a stray attribute.
  Owning it is not declining it — `route-link` honours `:prefetch :intent`
  by filling routing's credible-intent positions, and stripping the key is
  what keeps the request out of the DOM while the behaviour it asked for
  reaches it.

  The navigation policy keys — `:replace?`, `:scroll` and `:bypass-leave?`
  — are owned for the same reason. The link model reads them off the props
  and carries them on the click's `:rf.route/navigate` payload, which
  honours each, so they are navigation policy and never anchor
  attributes. They are spelled out here because this
  artefact cannot require routing's `policy-keys`; the route-link test
  checks this list against it."
  [:to :params :query :fragment :on-click :prefetch
   :replace? :scroll :bypass-leave?])

(defn on-click-roster!
  "Refuse, AT RENDER, an `:on-click` outside the route-click roster: nil,
  `[::h/prevent [:app/event]]` (the declarative veto), `h/event`, or a plain
  function. The same roster `intent/lower-veto` enforces at lowering;
  stated twice because the two failures land differently — this one fails
  at the site that wrote the link, with the render stack, before any
  anchor exists. A BARE intent vector is the taught mistake and gets the
  teaching diagnostic: the click already produces the one routing intent
  — the route-click one-intent law."
  [on-click]
  (when-not (or (nil? on-click)
                (rf.fresco.impl.intent/prevent-head? on-click)
                (rf.fresco.impl.intent/callback? on-click)
                (fn? on-click))
    (fail! :rf.error/fresco-route-link-bad-on-click
           're-frame.fresco.impl.route-link/route-link
           (str "route-link's :on-click is the pre-navigation veto; it takes nil, "
                "[" (pr-str rf.fresco.impl.intent/prevent-head) " [:my-event …]] (cancel the "
                "navigation and dispatch this instead), h/event, or a plain function — "
                "never " (pr-str on-click) ". A bare intent vector is refused "
                "because the click already produces the one routing intent; an "
                "application reaction belongs behind the routing event, or inside "
                (pr-str rf.fresco.impl.intent/prevent-head) " if it replaces the navigation.")
           {:on-click on-click}))
  on-click)

(def ^:private click-slot (rf.fresco.impl.codec/canonical-slot :on-click))

(defn- on-click-of
  "The value at the anchor's `onClick` slot in whichever spelling wrote it
  — `:on-click`, `:onClick`, `\"onClick\"` — the kebab key first. The link
  owns the React prop, not the key, so the roster and the veto read every
  spelling of it (fresco/spec/invariants.md, I15)."
  [props]
  (if (contains? props :on-click)
    (:on-click props)
    (reduce-kv (fn [found k v]
                 (if (= click-slot (rf.fresco.impl.codec/canonical-slot k)) (reduced v) found))
               nil
               props)))

(defn- claimed-intent-positions!
  "Refuse, AT RENDER, a caller value at a position `:prefetch :intent` has
  claimed, in any spelling of its React prop (`:onMouseEnter` as well as
  `:on-mouse-enter`). `prefetch-keys` is routing's own list of
  credible-intent positions, taken off the link model rather than restated
  here.

  The same law as `on-click-roster!`, one position along: the click already
  produces the one routing intent, and here the hover already produces the
  one prefetch intent — Fresco's in-band grammar carries ONE intent per
  position, so there is no composition to fall back on and nothing to
  merge. An author who needs a position to carry something else omits the
  sugar and spells the prefetch by hand at the positions they are not
  using: `:prefetch` is the shorthand, `:on-mouse-enter [:rf.route/prefetch
  {…}]` is the general form, and the general form is always available.

  All-or-nothing, deliberately. A silent skip would leave a link warming at
  two positions out of three — a partially-passive link, which is the
  failure `validate-prefetch!` calls the one a prefetch can least afford,
  since it looks identical to a working one until you measure. So either
  every claimed position carries the prefetch intent or the render throws.

  Unlike `route-link`'s two props refusals, this one runs AFTER the link
  model is consulted, because the positions it polices are routing's to
  name. That ordering is also what keeps the two prefetch diagnostics
  distinct: a BAD `:prefetch` value never reaches here at all — routing's
  `validate-prefetch!` has already thrown
  `:rf.error/route-link-bad-prefetch` from inside the seam — so this id
  means only \"the value is good and the position is taken\"."
  [props prefetch-keys]
  (when-let [claimed (seq (for [position prefetch-keys
                                :let     [slot (rf.fresco.impl.codec/canonical-slot position)]
                                k        (keys props)
                                :when    (= slot (rf.fresco.impl.codec/canonical-slot k))]
                            k))]
    (fail! :rf.error/fresco-route-link-claimed-intent-position
           're-frame.fresco.impl.route-link/route-link
           (str "route-link was given :prefetch :intent, which claims "
                (pr-str (vec prefetch-keys))
                ", and also a value at " (pr-str (vec claimed))
                ". One intent per position: the hover already produces the one "
                "prefetch intent, so there is nothing to compose with. Either drop "
                ":prefetch and spell the warm-up by hand at the positions you are "
                "not using (:on-mouse-enter [:rf.route/prefetch {:to … :params …}]), "
                "or move " (pr-str (vec claimed)) " elsewhere.")
           {:positions (vec claimed)
            :prefetch  :intent})))

(defn- prefetch-attrs
  "The prefetch half of the anchor's attrs: routing's `[:rf.route/prefetch
  {…}]` vector at every position routing claims, or nothing at all when the
  link is passive (`prefetch` nil — an absent `:prefetch` key).

  One vector, three positions, and it is the SAME vector at each: the three
  are independent triggers for one warm-up, so `=` sees the anchor's three
  intents as equal and a structural test can read the prefetch decision off
  the tree exactly as it reads the click decision."
  [prefetch prefetch-keys]
  (if prefetch
    (zipmap prefetch-keys (repeat prefetch))
    {}))

(defn- require-frame! [to]
  (or rf.fresco.impl.intent/*frame*
      (fail! :rf.error/fresco-route-link-outside-boundary
             're-frame.fresco.impl.route-link/route-link
             (str "route-link {:to " (pr-str to) "} was rendered with no ambient "
                  "frame; a link is only legal inside a boundary's render, because "
                  "the navigation must be pinned to the frame that rendered it.")
             {:to to})))

(defn route-link
  "One census route-link: compute the routing-owned link model for
  `props`' address (`:to` / `:params` / `:query` / `:fragment`), and
  answer a real `[:a …]` whose `:href` is routing's and whose `:on-click`
  is the `[intent/navigate-head {…}]` data form. `children` land inside
  the anchor. A plain function — call it: `(route-link {:to …} \"jane\")`.

  `:replace?`, `:scroll` and `:bypass-leave?` ride the click's navigation
  on the link model's payload, as they do on `rf/route-link`, and never
  reach the anchor.

  `:prefetch :intent` additionally fills routing's credible-intent
  positions with its `[:rf.route/prefetch {…}]` vector, which lowers like
  any other in-band intent; the positions and the vector both come off the
  one link-model call.

  Fails loud at render when routing is absent
  (`:rf.error/routing-artefact-missing`, naming the `:to`), when rendered
  outside a boundary, when `:on-click` is outside the roster
  (`on-click-roster!`) — those two run BEFORE the link model is consulted,
  so the author's own mistake is what the stack names — when `:prefetch`
  carries an unsupported value (routing's `:rf.error/route-link-bad-prefetch`,
  raised from inside the seam on BOTH hosts), and when a caller value sits
  at a position `:prefetch :intent` claims
  (`claimed-intent-positions!`, which necessarily runs after the model
  because the positions are routing's to name).

  Every slot the link writes on its anchor — the href, the click and each
  prefetch position — it holds at the React prop, as `forms/buffered-field`
  and the overlays hold theirs: a forwarded attribute in another spelling
  of one (`:onClick`, `:onMouseEnter`) is read by the refusals above and
  never reaches the anchor beside it (`codec/without-claimed-slots`)."
  [{:keys [to] :as props} & children]
  (let [frame-kw (require-frame! to)
        on-click (on-click-roster! (on-click-of props))
        {:keys [href payload native? prefetch prefetch-keys]}
        ((rf.late-bind/require-fn! :routing/link-model
                                'route-link
                                routing-artefact
                                {:to to})
         (rf.fresco.impl.codec/without-claimed-slots props {:on-click nil}) frame-kw)
        _        (when prefetch (claimed-intent-positions! props prefetch-keys))
        owned    (assoc (prefetch-attrs prefetch prefetch-keys)
                        :href href
                        :on-click [rf.fresco.impl.intent/navigate-head
                                   {:frame    frame-kw
                                    :payload  payload
                                    :native?  native?
                                    :veto     on-click}])
        attrs    (merge (rf.fresco.impl.codec/without-claimed-slots
                          (apply dissoc props control-keys) owned)
                        owned)]
    (into [:a attrs] children)))
