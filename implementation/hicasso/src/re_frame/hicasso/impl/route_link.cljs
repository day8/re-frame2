(ns re-frame.hicasso.impl.route-link
  "`route-link` — the fifth tier-1 shape's Hicasso spelling (the census
  counts **106** route-links across 85 idiomatic files and the charter
  names the form tier-1).

  The census author writes

      (route-link {:to :conduit.profile/show :params {:username u}} u)

  and never sees a URL. What comes back is ONE real anchor, as data:

      [:a {:href     \"/profile/jane\"               ; routing-owned
           :on-click [intent/navigate-head {…}]}    ; the click decision, as data
       \"jane\"]

  ## A plain function, deliberately — not a boundary

  A Hicasso boundary costs two hooks and a row in every
  boundary count, and the census's 106 links live INSIDE rows that are
  already boundaries — an author byline is not a unit of re-render. So
  `route-link` is a plain function like
  `re-frame.bench.hicasso.shapes.card/card`: called from a body it
  inlines, mints no boundary, adds no hook, and reads no subscription at
  all (the link model is a pure calculation), so the ≤2-hook budget and
  every page's boundary arithmetic are untouched by links.

  ## What is taken from whom, and what is declined

  - **From routing (taken whole): the law.** The href, the dispatch
    payload and the click decision come from the substrate-neutral
    late-bound seams routing publishes for exactly this consumer class —
    `:routing/link-model` here at render, `:routing/activate-link!` in
    the lowered closure (`re-frame.hicasso.impl.intent`). This
    file restates NO routing law: `rf/route-link` and this one both run
    the same two
    definitions, and the packaging graph stays
    `hicasso -> core late-bind <- routing` (Conventions §Packaging).
  - **Owned here: render-time capture and render-time
    refusal.** The frame is captured at RENDER (a click fires after the
    render scope has unwound); a missing routing artefact fails at the
    LINK SITE with `:rf.error/routing-artefact-missing`; and the
    route-click one-intent law holds — the click produces the one
    routing intent, see `on-click-roster!`.
    **(Declined:** a fn-only `:on-click` roster. That narrowing is only
    forced on a surface with no in-band spelling for
    \"cancel-and-replace\"; Hicasso has one — `[::h/prevent [:app/event]]`
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
  - **Not wired in v0: the `:prefetch :intent` trio.** The census counts
    no prefetch site, and the opt-in is sugar over an event a Hicasso
    author can already spell (`:on-mouse-enter [:rf.route/prefetch
    {…}]`). Routing publishes no late-bind seam for it either — the trio
    was retired as orphaned in rf2-6r9j.15, since nothing read it — so
    `rf/route-link` reaches the prefetch law by direct call. `:prefetch`
    is still one of the keys the link owns (`control-keys`), so it never
    reaches the anchor as a stray attribute.

  ## Composition with `::h/prevent`

  A route-link's click is the cancelable-navigation case the prevent head
  was built for, and the composition is the existing grammar: the
  caller's `:on-click` rides the navigate vector's `:veto` slot; a
  `[::h/prevent [:app/event]]` there lowers to the ordinary prevent
  closure; routing runs it FIRST and stands down on `defaultPrevented`.
  One click, one semantic event — the navigation, or the app intent that
  cancelled it. See intent.cljs §The navigate head for the click-time
  half."
  (:require [re-frame.hicasso.impl.error :refer [fail!]]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.late-bind :as late-bind]))

(def ^:private routing-artefact
  "The optional-artefact identity the fail-loud missing-hook error
  reports, carried here so the diagnostic names the missing artefact AT
  THE LINK SITE. Every link surface that consumes the seam keeps its own
  private copy of this map, for the same reason — see HD-027's prior-art
  ledger in docs/design/hicasso/decisions.md for the roster."
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

;; `fail!` is `re-frame.hicasso.impl.error`'s — one constructor for the whole
;; package, and the ambient view and source coordinate come with it.

(def control-keys
  "The keys `route-link` owns. Everything else on the props map is a
  passthrough HTML attribute and reaches the `<a>` untouched — `:class`,
  `:title`, `:data-testid`, `:aria-label`, and the native-handling
  `:target` / `:download` pair the link model reads for its `:native?`
  verdict.

  `:prefetch` is owned too: it is a routing props key, not an HTML
  attribute, and Hicasso wires none of the prefetch handlers in v0, so it
  is kept off the anchor rather than emitted as a stray attribute."
  [:to :params :query :fragment :on-click :prefetch])

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
                (intent/prevent-head? on-click)
                (intent/callback? on-click)
                (fn? on-click))
    (fail! :rf.error/hicasso-route-link-bad-on-click
           're-frame.hicasso.impl.route-link/route-link
           (str "route-link's :on-click is the pre-navigation veto; it takes nil, "
                "[" (pr-str intent/prevent-head) " [:my-event …]] (cancel the "
                "navigation and dispatch this instead), h/event, or a plain function — "
                "never " (pr-str on-click) ". A bare intent vector is refused "
                "because the click already produces the one routing intent; an "
                "application reaction belongs behind the routing event, or inside "
                (pr-str intent/prevent-head) " if it replaces the navigation.")
           {:on-click on-click}))
  on-click)

(defn- require-frame! [to]
  (or intent/*frame*
      (fail! :rf.error/hicasso-route-link-outside-boundary
             're-frame.hicasso.impl.route-link/route-link
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

  Fails loud at render when routing is absent
  (`:rf.error/routing-artefact-missing`, naming the `:to`), when rendered
  outside a boundary, or when `:on-click` is outside the roster
  (`on-click-roster!`) — the roster check runs BEFORE the link model is
  consulted, so the author's own mistake is what the stack names."
  [{:keys [to on-click] :as props} & children]
  (let [frame-kw (require-frame! to)
        _        (on-click-roster! on-click)
        {:keys [href payload native?]}
        ((late-bind/require-fn! :routing/link-model
                                'route-link
                                routing-artefact
                                {:to to})
         (dissoc props :on-click) frame-kw)
        attrs    (-> (apply dissoc props control-keys)
                     (assoc :href href
                            :on-click [intent/navigate-head
                                       {:frame    frame-kw
                                        :payload  payload
                                        :native?  native?
                                        :veto     on-click}]))]
    (into [:a attrs] children)))
