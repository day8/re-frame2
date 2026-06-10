(ns re-frame.resources.route
  "LATE-BOUND routing integration for resources. Per Spec 016 §Route
  integration.

  Routing rejects unknown bare route-metadata keys at registration (Spec
  012 §Reserved route-metadata keys). The Resources artefact therefore
  extends the routing accepted-key set — via a LATE-BOUND framework
  extension — so `:resources` is treated like the existing cross-feature
  `:head` key. Resources publishes the `:routing/extra-route-keys` hook
  (returning `#{:resources}`); routing's `accepted-route-keys` consults
  it at registration. The wiring is late-bound BOTH ways: resources never
  statically `:require`s routing, and routing never statically `:require`s
  resources — an app that loads resources but not routing carries no
  route code, and a routing-only app sees no resources behaviour. (And a
  route containing `:resources` in an app that omits the Resources
  artefact is correctly rejected by routing, per Spec 016.)

  On route entry, routing resolves the route + nav-token, evaluates
  `:when` predicates, computes + validates scopes and params, marks each
  resource active with owner `[:route route-id nav-token]`, ensures each
  with cause `[:route-entry route-id nav-token]`, tracks blocking
  resources under the nav-token, and lets non-blocking ones fetch in the
  background; route leave / supersession releases route-owned resources by
  owner token. Per Spec 016 §Route integration.

  SKELETON slice (rf2-p10npe): the accepted-key extension is REAL (so an
  app can `(reg-route … {:resources [...]})` once both artefacts are
  loaded); the on-route-entry resource-plan execution + route-leave
  release land with the route slice (a later EP-0003 slice). The hook
  publication lives in the `re-frame.resources` façade (see
  `install-routing-integration!`)."
  (:require [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def resources-route-key
  "The route-metadata key the Resources artefact owns (`:resources`). Per
  Spec 016 §Route integration."
  :resources)

(def extra-route-keys
  "The cross-feature route-metadata keys this artefact contributes to the
  routing accepted set (`#{:resources}`). Published under
  `:routing/extra-route-keys`; routing's `accepted-route-keys` unions it
  in. Per Spec 016 §Route integration."
  #{resources-route-key})

(defn install-routing-integration!
  "Publish the LATE-BOUND routing integration: register the
  `:routing/extra-route-keys` hook so routing accepts the `:resources`
  route-metadata key. Idempotent (the hook is a pure data thunk). No-op
  effect on an app that never loads routing — the hook simply sits unread.
  Per Spec 016 §Route integration.

  SKELETON: wires the accepted-key extension only. The on-route-entry
  resource-plan hook (`:routing/on-route-entry` analogue) + route-leave
  owner release land with the route slice."
  []
  (late-bind/set-fn! :routing/extra-route-keys (fn extra-route-keys-thunk [] extra-route-keys))
  nil)

(defn route-resource-plan
  "Build the resource ensure/release plan for a route's `:resources`
  metadata on entry (resolve scopes + params, evaluate `:when`, order by
  `:after` dependencies, classify blocking vs background). Per Spec 016
  §Route integration.

  SKELETON: lands with the route slice. Named so the façade's
  late-bind wiring and Xray's route/resource graph have a stable home."
  [_route _ctx]
  (throw (ex-info ":rf.error/resource-not-implemented"
                  {:rf.error/id :rf.error/resource-not-implemented
                   :where       're-frame.resources.route/route-resource-plan
                   :recovery    :no-recovery
                   :reason      (str "route :resources plan execution lands with "
                                     "the EP-0003 route slice. This is the "
                                     "rf2-p10npe artefact skeleton.")})))
