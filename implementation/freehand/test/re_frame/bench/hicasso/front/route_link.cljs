(ns re-frame.bench.hicasso.front.route-link
  "`route-link` — the fifth tier-1 shape's Hicasso spelling
  (rf2-2rtt6.54; the census counts **106** route-links across 85
  idiomatic files and the charter names the form tier-1).

  The census author writes

      (route-link {:to :conduit.profile/show :params {:username u}} u)

  and never sees a URL. What comes back is ONE real anchor, as data:

      [:a {:href     \"/profile/jane\"          ; routing-owned
           :on-click [::h/navigate {…}]}       ; the click decision, as data
       \"jane\"]

  ## A plain function, deliberately — not a boundary

  Freehand's `v/route-link` is a `defview`; here that would be the wrong
  citation. A Hicasso boundary costs two hooks and a row in every
  boundary count, and the census's 106 links live INSIDE rows that are
  already boundaries — an author byline is not a unit of re-render. So
  `route-link` is a plain function like
  [[re-frame.bench.hicasso.shapes.card/card]]: called from a body it
  inlines, mints no boundary, adds no hook, and reads no subscription at
  all (the link model is a pure calculation), so the ≤2-hook budget and
  every page's boundary arithmetic are untouched by links.

  ## What is taken from whom, and what is declined

  - **From routing (taken whole): the law.** The href, the dispatch
    payload and the click decision come from the substrate-neutral
    late-bound seams routing publishes for exactly this consumer class —
    `:routing/link-model` here at render, `:routing/activate-link!` in
    the lowered closure ([[re-frame.bench.hicasso.front.intent]]). This
    file restates NO routing law: `rf/route-link`, `ui/route-link`,
    Freehand's `v/route-link` and this one all run the same two
    definitions, and the packaging graph stays
    `hicasso -> core late-bind <- routing` (Conventions §Packaging).
  - **From Freehand (taken): render-time capture and render-time
    refusal.** The frame is captured at RENDER (a click fires after the
    render scope has unwound); a missing routing artefact fails at the
    LINK SITE with `:rf.error/routing-artefact-missing`; and the
    route-click one-intent law is kept — see [[on-click-roster!]].
    **(Declined:** the fn-only `:on-click` roster. Freehand narrows to
    fn / `v/handler` because it has no in-band spelling for
    \"cancel-and-replace\"; Hicasso does — `[::h/prevent [:app/event]]`
    is the declarative veto, and it is admitted.**)**
  - **From Replicant (taken, via HD-026): behaviour as a
    namespaced-keyword-headed vector.** The anchor's click carries a
    `[::h/navigate {…}]` vector `=` can see, so two renders of one link
    are equal and a structural test reads the click decision off the
    tree. **(Declined:** Replicant's routing posture of a GLOBAL
    body-level click interceptor — ambient behaviour no vector carries is
    exactly what the in-band school exists to avoid.**)**
  - **Declined for v0: the `:prefetch :intent` trio.** Routing publishes
    it and Freehand consumes it; the census counts no prefetch site, and
    the opt-in is sugar over an event a Hicasso author can already spell
    (`:on-mouse-enter [:rf.route/prefetch {…}]`). A tier-1 roster takes
    the tier-1 surface.

  ## Composition with `::h/prevent`

  A route-link's click is the cancelable-navigation case the prevent head
  was built for, and the composition is the existing grammar: the
  caller's `:on-click` rides the navigate vector's `:veto` slot; a
  `[::h/prevent [:app/event]]` there lowers to the ordinary prevent
  closure; routing runs it FIRST and stands down on `defaultPrevented`.
  One click, one semantic event — the navigation, or the app intent that
  cancelled it. See intent.cljs §The navigate head for the click-time
  half."
  (:require [re-frame.bench.hicasso.front.intent :as intent]
            [re-frame.late-bind :as late-bind]))

(def ^:private routing-artefact
  "The optional-artefact identity the fail-loud missing-hook error
  reports, carried here so the diagnostic names the missing artefact AT
  THE LINK SITE (the same private copy Freehand and re-frame.ui each
  keep, for the same reason)."
  {:error-keyword :rf.error/routing-artefact-missing
   :maven         "day8/re-frame2-routing"
   :require-ns    "re-frame.routing"})

(defn- fail! [id where reason recovery extra]
  (throw (ex-info (str reason " [" id "]")
                  (merge {:rf.error/id id :where where
                          :reason reason :recovery recovery}
                         extra))))

(def control-keys
  "The keys `route-link` owns. Everything else on the props map is a
  passthrough HTML attribute and reaches the `<a>` untouched — `:class`,
  `:title`, `:data-testid`, `:aria-label`, and the native-handling
  `:target` / `:download` pair the link model reads for its `:native?`
  verdict."
  [:to :params :query :fragment :on-click])

(defn on-click-roster!
  "Refuse, AT RENDER, an `:on-click` outside the route-click roster: nil,
  `[::h/prevent [:app/event]]` (the declarative veto), `h/fn`, or a plain
  function. The same roster [[intent/lower-veto]] enforces at lowering;
  stated twice because the two failures land differently — this one fails
  at the site that wrote the link, with the render stack, before any
  anchor exists. A BARE intent vector is the taught mistake and gets the
  teaching diagnostic: the click already produces the one routing intent
  (Freehand's route-link law, kept)."
  [on-click]
  (when-not (or (nil? on-click)
                (intent/prevent-head? on-click)
                (intent/callback? on-click)
                (fn? on-click))
    (fail! :rf.error/hicasso-route-link-bad-on-click
           'front.route-link/route-link
           (str "route-link's :on-click is the pre-navigation veto; it takes nil, "
                "[" (pr-str intent/prevent-head) " [:my-event …]] (cancel the "
                "navigation and dispatch this instead), h/fn, or a plain function — "
                "never " (pr-str on-click) ". A bare intent vector is refused "
                "because the click already produces the one routing intent; an "
                "application reaction belongs behind the routing event, or inside "
                (pr-str intent/prevent-head) " if it replaces the navigation.")
           :veto-with-prevent-a-callback-or-nothing
           {:on-click on-click}))
  on-click)

(defn- require-frame! [to]
  (or intent/*frame*
      (fail! :rf.error/hicasso-route-link-outside-boundary
             'front.route-link/route-link
             (str "route-link {:to " (pr-str to) "} was rendered with no ambient "
                  "frame; a link is only legal inside a boundary's render, because "
                  "the navigation must be pinned to the frame that rendered it.")
             :render-route-links-inside-a-boundary
             {:to to})))

(defn route-link
  "One census route-link: compute the routing-owned link model for
  `props`' address (`:to` / `:params` / `:query` / `:fragment`), and
  answer a real `[:a …]` whose `:href` is routing's and whose `:on-click`
  is the `[::h/navigate {…}]` data form. `children` land inside the
  anchor. A plain function — call it: `(route-link {:to …} \"jane\")`.

  Fails loud at render when routing is absent
  (`:rf.error/routing-artefact-missing`, naming the `:to`), when rendered
  outside a boundary, or when `:on-click` is outside the roster
  ([[on-click-roster!]])."
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
