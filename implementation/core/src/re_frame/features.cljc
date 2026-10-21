(ns re-frame.features
  "Feature-inspection front-porch.

  re-frame2's optional capabilities ship as separate Maven artefacts
  (`day8/re-frame2-<feature>`) whose implementation namespaces core
  reaches through the [late-bind hook registry](late_bind.cljc) at call
  time — see [Conventions §Facade re-export, artefact require](../../../../../spec/Conventions.md#facade-re-export-artefact-require).
  The upside is bundle-isolation: an app that omits a feature does not
  carry its code. The downside the front-porch closes: the late-binding
  is invisible — a developer who forgets to `:require` the impl artefact
  calls a re-exported fn that *exists* and is met with an opaque
  artefact-missing error.

  This ns surfaces the optional-feature inventory so the binding is
  self-explaining, through ONE fn — data before magic:

    `(features)`   — every optional feature + its coordinate data and
                     live `:loaded?` status, as a map.

    `(get-in (features) [:routing :loaded?])`   — the boolean. An unknown
                     feature keyword reads `nil` here (its entry is
                     simply absent from the map).

    ;; An app that wants boot-time failure rather than first-call
    ;; failure writes the guard itself — one line, no framework verb,
    ;; and NOT an `assert` (asserts are elidable):
    (when-not (get-in (features) [:routing :loaded?])
      (throw (ex-info \"re-frame.routing is not on the classpath\"
                      (get (features) :routing))))

  ## `features` SHIPS to production

  It is a runtime query, NOT dev-time instrumentation, so it is
  deliberately NOT gated on `interop/debug-enabled?` and does NOT elide
  under `:advanced` + `goog.DEBUG=false`. A production caller may
  legitimately read `(get-in (features) [:routing :loaded?])` before
  taking a routing-dependent code path.

  ## The coordinate table is STATIC DATA — never a live require

  `feature-registry` is a plain data table mapping each feature keyword
  to its Maven coordinate, its app-boot require namespace, its
  artefact-missing error id, and a single representative late-bind
  `:probe-key` the impl publishes at ns-load.
  It is HARD-CONSTRAINED to be static data living entirely in this
  always-loaded facade ns: it MUST NOT `:require` any optional impl
  namespace. A live require would create a hard facade→optionals edge
  that pulls machines / schemas / flows / routing / http / ssr
  into EVERY production bundle, breaking the bundle-isolation contract.

  `features` therefore detects presence by a pure keyword lookup in the
  always-loaded `rf.late-bind/hooks` atom — `(some? (get-fn probe-key))`
  — which the impl artefact populates from its own ns-load. No reach
  into the optional namespace; just an atom read.

  The coordinate strings here are the single source of truth for core:
  the per-feature `re-frame.core-<feature>` wrappers read their
  artefact-info from this table (`re-frame.core-artefact/artefact-info`,
  thrown through `rf.late-bind/require-fn!`), and every other
  artefact-missing message in core reads its coordinate and require from
  it, so each carries the same copy-pasteable require/coordinate this
  table holds."
  (:require [re-frame.late-bind :as rf.late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def feature-registry
  "Static coordinate table — one entry per optional feature artefact.

  Plain data, NEVER a live `:require` into the optional impls (that would
  pull every optional namespace into every production bundle and break
  bundle-isolation — see the ns docstring). Each entry:

    {:maven     Maven coordinate to add to deps    (string)
     :require   namespace to require at app boot    (string)
     :error-id  artefact-missing error id           (keyword)
     :spec      owning spec topic                   (string, doc-only)
     :probe-key a late-bind hook key the impl publishes at ns-load,
                read to detect the artefact's presence without a
                static require (keyword)}

  The `:probe-key` for each feature is a stable, always-published hook —
  the artefact registers it unconditionally from its ns-load `set-fn!` /
  `set-fns!` block, so its presence in `rf.late-bind/hooks` is a faithful
  loaded?-signal. Renaming a probe key here without keeping it in step
  with the producing artefact's publication would silently report a
  loaded feature as absent; the keys are chosen from each feature's
  always-published surface (its `reg-*` registration where it has one)
  precisely because those never go away."
  {:schemas  {:maven     "day8/re-frame2-schemas"
              :require   "re-frame.schemas"
              :error-id  :rf.error/schemas-artefact-missing
              :spec      "Spec 010 (Schemas)"
              :probe-key :schemas/validate-app-schema!}
   :machines {:maven     "day8/re-frame2-machines"
              :require   "re-frame.machines"
              :error-id  :rf.error/machines-artefact-missing
              :spec      "Spec 005 (State machines)"
              :probe-key :machines/reg-machine}
   :routing  {:maven     "day8/re-frame2-routing"
              :require   "re-frame.routing"
              :error-id  :rf.error/routing-artefact-missing
              :spec      "Spec 012 (Routing)"
              :probe-key :routing/reg-route}
   :flows    {:maven     "day8/re-frame2-flows"
              :require   "re-frame.flows"
              :error-id  :rf.error/flows-artefact-missing
              :spec      "Spec 013 (Flows)"
              :probe-key :flows/reg-flow}
   :http     {:maven     "day8/re-frame2-http"
              :require   "re-frame.http.managed"
              :error-id  :rf.error/http-artefact-missing
              :spec      "Spec 014 (Managed HTTP)"
              :probe-key :http/abort-on-actor-destroy}
   :ssr      {:maven     "day8/re-frame2-ssr"
              :require   "re-frame.ssr"
              :error-id  :rf.error/ssr-artefact-missing
              :spec      "Spec 011 (SSR & hydration)"
              :probe-key :ssr/reg-error-projector}
   :resources {:maven     "day8/re-frame2-resources"
               :require   "re-frame.resources"
               :error-id  :rf.error/resources-artefact-missing
               :spec      "Spec 016 (Resources)"
               :probe-key :resources/reg-resource}})

(defn- loaded?
  "Internal probe: is `probe-key` published in the always-loaded
  `rf.late-bind/hooks` atom? The artefact publishes that key from its own
  ns-load, so `(some? (rf.late-bind/get-fn probe-key))` faithfully signals
  presence WITHOUT a static require into the optional namespace (which
  would break bundle-isolation).

  Private: `features` is the one public inventory door, and the boolean
  is read from it as `(get-in (features) [:routing :loaded?])`."
  [probe-key]
  (some? (rf.late-bind/get-fn probe-key)))

(defn features
  "Return a map of every optional feature keyword to its inspection
  entry: the feature's static coordinate data (`:maven` / `:require` /
  `:spec`) merged with its live `:loaded?` status.

      (rf/features)
      ;=> {:routing {:maven \"day8/re-frame2-routing\"
      ;              :require \"re-frame.routing\"
      ;              :spec \"Spec 012 (Routing)\"
      ;              :loaded? true}
      ;    :flows {... :loaded? false}
      ;    ...}

  The `:probe-key` and `:error-id` are internal plumbing and are dropped
  from the public shape.

  This is the ONE feature-inspection door. The boolean is a lookup —
  `(get-in (features) [:routing :loaded?])` — and an unknown feature
  keyword reads `nil` there, because its entry is simply absent from
  the map. An app wanting boot-time rather than first-call failure
  writes the guard itself:

      (when-not (get-in (features) [:routing :loaded?])
        (throw (ex-info \"re-frame.routing is not on the classpath\"
                        (get (features) :routing))))

  Ships to production — NOT elided. Per spec/API.md §Feature
  inspection."
  []
  (reduce-kv
    (fn [acc feature {:keys [maven require spec probe-key]}]
      (assoc acc feature
             {:maven    maven
              :require  require
              :spec     spec
              :loaded?  (loaded? probe-key)}))
    {}
    feature-registry))
