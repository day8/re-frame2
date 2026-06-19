(ns re-frame.features
  "Feature-inspection front-porch (rf2-3nbl5.5, API-governance G5).

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
  self-explaining:

    `(features)`              — the optional features + their `loaded?`
                                status, as a map.
    `(feature-loaded? :epoch)` — boolean: is the feature's impl artefact
                                on the classpath?
    `(require-feature! :epoch)` — assert a feature is loaded; throw the
                                EXACT copy-pasteable Maven coordinate +
                                require form when it isn't.

  ## These three fns SHIP to production

  They are runtime queries, NOT dev-time instrumentation, so they are
  deliberately NOT gated on `interop/debug-enabled?` and do NOT elide
  under `:advanced` + `goog.DEBUG=false`. A production caller may
  legitimately probe `(feature-loaded? :routing)` before taking a
  routing-dependent code path.

  ## The coordinate table is STATIC DATA — never a live require

  `feature-registry` is a plain data table mapping each feature keyword
  to its Maven coordinate, its app-boot require namespace, and a single
  representative late-bind `:probe-key` the impl publishes at ns-load.
  It is HARD-CONSTRAINED to be static data living entirely in this
  always-loaded facade ns: it MUST NOT `:require` any optional impl
  namespace. A live require would create a hard facade→optionals edge
  that pulls epoch / machines / schemas / flows / routing / http / ssr
  into EVERY production bundle, breaking the bundle-isolation contract.

  `feature-loaded?` therefore detects presence by a pure keyword lookup
  in the always-loaded `late-bind/hooks` atom — `(some? (get-fn
  probe-key))` — which the impl artefact populates from its own ns-load.
  No reach into the optional namespace; just an atom read.

  The coordinate strings here are the single source of truth the per-
  feature `re-frame.core-<feature>` wrappers' `:reason` throws mirror
  (via `late-bind/require-fn!`); every artefact-missing error in the
  framework carries the same copy-pasteable require/coordinate this
  table holds."
  (:require [re-frame.error :as error]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

(def feature-registry
  "Static coordinate table — one entry per optional feature artefact.

  Plain data, NEVER a live `:require` into the optional impls (that would
  pull every optional namespace into every production bundle and break
  bundle-isolation — see the ns docstring). Each entry:

    {:maven     Maven coordinate to add to deps    (string)
     :require   namespace to require at app boot    (string)
     :spec      owning spec topic                   (string, doc-only)
     :probe-key a late-bind hook key the impl publishes at ns-load,
                read to detect the artefact's presence without a
                static require (keyword)}

  The `:probe-key` for each feature is a stable, always-published hook —
  the artefact registers it unconditionally from its ns-load `set-fn!` /
  `set-fns!` block, so its presence in `late-bind/hooks` is a faithful
  loaded?-signal. Renaming a probe key here without keeping it in step
  with the producing artefact's publication would silently report a
  loaded feature as absent; the keys are chosen from the feature's
  registration surface (`reg-*`) precisely because those never go away."
  {:schemas  {:maven     "day8/re-frame2-schemas"
              :require   "re-frame.schemas"
              :spec      "Spec 010 (Schemas)"
              :probe-key :schemas/validate-app-schema!}
   :machines {:maven     "day8/re-frame2-machines"
              :require   "re-frame.machines"
              :spec      "Spec 005 (State machines)"
              :probe-key :machines/reg-machine}
   :routing  {:maven     "day8/re-frame2-routing"
              :require   "re-frame.routing"
              :spec      "Spec 012 (Routing)"
              :probe-key :routing/reg-route}
   :flows    {:maven     "day8/re-frame2-flows"
              :require   "re-frame.flows"
              :spec      "Spec 013 (Flows)"
              :probe-key :flows/reg-flow}
   :http     {:maven     "day8/re-frame2-http"
              :require   "re-frame.http.managed"
              :spec      "Spec 014 (Managed HTTP)"
              :probe-key :http/abort-on-actor-destroy}
   :ssr      {:maven     "day8/re-frame2-ssr"
              :require   "re-frame.ssr"
              :spec      "Spec 011 (SSR & hydration)"
              :probe-key :ssr/render-to-string}
   :epoch    {:maven     "day8/re-frame2-epoch"
              :require   "re-frame.epoch"
              :spec      "Tool-Pair (Time-travel / epoch)"
              :probe-key :epoch/settle!}
   :resources {:maven     "day8/re-frame2-resources"
               :require   "re-frame.resources"
               :spec      "Spec 016 (Resources)"
               :probe-key :resources/reg-resource}})

(defn feature-loaded?
  "Return `true` when the optional feature `feature`'s implementation
  artefact is on the classpath, `false` otherwise (including when
  `feature` is not a known optional feature keyword).

  Detection is a pure keyword lookup in the always-loaded
  `late-bind/hooks` atom against the feature's representative
  `:probe-key` — the artefact publishes that key from its own ns-load,
  so `(some? (late-bind/get-fn probe-key))` faithfully signals presence
  WITHOUT a static require into the optional namespace (which would
  break bundle-isolation). Ships to production — NOT elided.

  Known feature keywords are the keys of `feature-registry`: `:schemas`,
  `:machines`, `:routing`, `:flows`, `:http`, `:ssr`, `:epoch`,
  `:resources`. Per spec/API.md §Feature inspection."
  [feature]
  (if-let [{:keys [probe-key]} (get feature-registry feature)]
    (some? (late-bind/get-fn probe-key))
    false))

(defn features
  "Return a map of every optional feature keyword to its inspection
  entry: the feature's static coordinate data (`:maven` / `:require` /
  `:spec`) merged with its live `:loaded?` status.

      (rf/features)
      ;=> {:epoch {:maven \"day8/re-frame2-epoch\"
      ;            :require \"re-frame.epoch\"
      ;            :spec \"Tool-Pair (Time-travel / epoch)\"
      ;            :loaded? true}
      ;    :routing {... :loaded? false}
      ;    ...}

  The `:probe-key` is internal plumbing and is dropped from the public
  shape. Ships to production — NOT elided. Per spec/API.md §Feature
  inspection."
  []
  (reduce-kv
    (fn [acc feature {:keys [maven require spec]}]
      (assoc acc feature
             {:maven    maven
              :require  require
              :spec     spec
              :loaded?  (feature-loaded? feature)}))
    {}
    feature-registry))

(defn require-feature!
  "Assert the optional feature `feature` is loaded. Returns `true` when
  the feature's implementation artefact is on the classpath; throws a
  structured `:rf.error/feature-not-loaded` ex-info carrying the EXACT
  copy-pasteable Maven coordinate + require form when it is not.

      (rf/require-feature! :epoch)
      ;; absent =>
      ;; ExceptionInfo :rf.error/feature-not-loaded
      ;;   {:rf.error/id :rf.error/feature-not-loaded
      ;;    :where 'rf/require-feature!
      ;;    :feature :epoch
      ;;    :maven \"day8/re-frame2-epoch\"
      ;;    :require-ns \"re-frame.epoch\"
      ;;    :recovery :no-recovery
      ;;    :reason \"The :epoch feature ... add day8/re-frame2-epoch to
      ;;             deps and (require 're-frame.epoch) at app boot.\"}

  Use as an early, self-explaining guard at the top of code that depends
  on an optional feature, so the failure names the fix rather than
  surfacing an opaque late-bind miss deep in a call chain. An unknown
  feature keyword throws `:rf.error/unknown-feature` listing the known
  set. Ships to production — NOT elided. Per spec/API.md §Feature
  inspection."
  [feature]
  (let [entry (get feature-registry feature)]
    (cond
      (nil? entry)
      (error/throw-error!
        :rf.error/unknown-feature
        'rf/require-feature!
        (str feature " is not a known optional feature. "
             "Known features: "
             (vec (sort (keys feature-registry))) ".")
        {:recovery :no-recovery
         :extra    {:feature feature
                    :known   (vec (sort (keys feature-registry)))}})

      (feature-loaded? feature)
      true

      :else
      (let [{:keys [maven require]} entry]
        (error/throw-error!
          :rf.error/feature-not-loaded
          'rf/require-feature!
          (str "The " feature " feature's implementation artefact "
               "is not on the classpath. Add " maven
               " to deps and (require '" require ") at app boot.")
          {:recovery :no-recovery
           :extra    {:feature    feature
                      :maven      maven
                      :require-ns require}})))))
