(ns re-frame.ui.digest-probe.final-schedule.hook
  "Test-only build-local hook for the final-schedule reconciliation fixture
  (rf2-vxgfnd.195).

  This hook is deep-merged AFTER the inherited re-frame.ui compile hook (the one
  configured in :build-defaults), so at every stage Shadow runs re-frame.ui's
  hook first and this one second — the real deep-merge/order the production
  `reconcile-final-schedule` defends against, proven by config rather than by a
  hand-built build-state.

  Its behaviour at :compile-prepare is driven by runner-controlled marker files,
  so one warm Shadow watch daemon can be walked through several passes with no
  config edits:

    force-recompile-app-a — model a later prepare hook that MUTATES build state:
      remove app-a's retained output (so Shadow reschedules it to compile AFTER
      re-frame.ui already observed the schedule) and rewrite its in-memory source
      to a VIEWLESS namespace (its final ui/defview removed). re-frame.ui saw
      app-a as an output-present cache hit and did NOT pre-touch it; only
      `reconcile-final-schedule` at :compile-finish — reading re-frame.ui's OWN
      per-pass provenance marker (app-a's fresh compiled output map LACKS the
      token :compile-prepare stamped onto retained outputs) — can evict its
      now-absent accepted view.

    blind-provenance — model a hook that destroys the per-pass compile-schedule
      evidence: drop re-frame.ui's :pass-token from the open scratch.
      `reconcile-final-schedule` must then FAIL LOUD at :compile-finish rather
      than silently reconcile against an assumed-empty schedule.

  The :compile-finish arm records the accepted snapshot Shadow retained (version,
  digest, full accepted view aggregate) plus a prepare-time observation of
  whether re-frame.ui pre-touched app-a — the evidence the runner uses to prove
  the eviction came from FINAL-schedule reconciliation, not the prepare-time
  pre-touch. Pure JVM dev-build tooling; never part of any CLJS bundle."
  (:require [clojure.java.io :as io]
            [re-frame.ui.compiler.build :as build]))

;; Shadow runs with CWD = this fixture directory (the runner spawns it there),
;; so climb back to implementation/target — the same gitignored tree the
;; :cache-root uses — rather than littering target under the test source tree.
(def ^:private out-dir
  (io/file "../../../../../../target" "ui-final-schedule"))
(def ^:private force-marker (io/file out-dir "force-recompile-app-a"))
(def ^:private blind-marker (io/file out-dir "blind-provenance"))
(def ^:private forge-marker (io/file out-dir "forge-app-a-output"))

(def ^:private app-a-ns 're-frame.ui.digest-probe.final-schedule.app-a)
(def ^:private a-view-id :re-frame.ui.digest-probe.final-schedule.app-a/a-view)
(def ^:private b-view-id :re-frame.ui.digest-probe.final-schedule.app-b/b-view)

(defn- app-a-rid [{:keys [build-sources sources]}]
  (some (fn [rid] (when (= app-a-ns (get-in sources [rid :ns])) rid))
        build-sources))

(defn- observe-file [build-id]
  (io/file out-dir (str "observe-" (name build-id) ".jsonl")))

(defn- record! [build-id m]
  (let [f (observe-file build-id)]
    (io/make-parents f)
    (spit f (str (pr-str (assoc m :build-id (name build-id))) "\n") :append true)))

(defn hook
  {:shadow.build/stages #{:compile-prepare :compile-finish}}
  [{build-id :shadow.build/build-id
    stage    :shadow.build/stage
    :as      build-state}]
  (case stage
    :compile-prepare
    (let [rid     (app-a-rid build-state)
          scratch (get-in build-state [:compiler-env build/scratch-key])
          touched (:touched scratch)
          force?  (.exists force-marker)
          blind?  (.exists blind-marker)
          forge?  (.exists forge-marker)]
      (record! build-id
               {:stage :prepare
                :app-a-output-present (some? (get-in build-state [:output rid]))
                :app-a-pretouched (boolean (contains? (set touched) app-a-ns))
                :force force?
                :blind blind?
                :forge forge?})
      (cond-> build-state
        ;; A later prepare hook forcing a viewless recompile of an output-present
        ;; cache-hit source: remove its output AND remove its final ui/defview.
        (and rid force?)
        (-> (update :output dissoc rid)
            (assoc-in [:sources rid :source]
                      (str "(ns " app-a-ns
                           " (:require [re-frame.ui :as ui]))\n"
                           ";; final-schedule fixture: viewless forced recompile\n")))

        ;; A later NON-scheduling hook that REPLACES app-a's whole retained output
        ;; map, rebuilt from Shadow's PUBLIC fields (dropping re-frame.ui's private
        ;; marker) and copying the sticky :cached false, WITHOUT removing it — so
        ;; Shadow does not reschedule it and app-a is absent from Shadow's OWN
        ;; ::build-info :compiled record. This is the "unforgeable by output-map
        ;; replacement" adversary: :compile-finish must FAIL LOUD rather than trust
        ;; the forged :cached false and evict app-a's valid accepted view.
        (and rid forge?)
        (assoc-in [:output rid]
                  (-> (get-in build-state [:output rid])
                      (select-keys [:resource-id :js :source-map-compact
                                    :compiled-at :warnings :cached])
                      (assoc :cached false)))

        ;; A later hook that destroys the per-pass provenance re-frame.ui needs to
        ;; reconcile: drop the :pass-token from the open scratch.
        (and blind? scratch)
        (update-in [:compiler-env build/scratch-key] dissoc :pass-token)))

    :compile-finish
    (let [snap  (build/accepted-snapshot build-state)
          views (build/accepted-aggregate build/views build-state)
          ;; rf2-vxgfnd.95.5 shipped the FIRST framework-authored compiled view
          ;; (`re-frame.ui/route-link`), so re-frame.ui — which every probe app
          ;; source requires — now contributes its own view(s) to the WHOLE-build
          ;; accepted aggregate. Scope this fixture's lifecycle count to the
          ;; probe's own app namespaces so the declare/evict assertions stay
          ;; exact (and still redden on an app-a ghost of ANY id) instead of
          ;; drifting by one with every framework view re-frame.ui adds.
          probe-nss  #{(namespace a-view-id) (namespace b-view-id)}
          app-views  (filter #(contains? probe-nss (namespace %)) (keys views))]
      (record! build-id
               {:stage :finish
                :version (:version snap)
                :digest (:digest snap)
                :view-count (count app-views)
                :a-present (contains? views a-view-id)
                :b-present (contains? views b-view-id)
                ;; A stable string image of app-b's accepted row, so the runner
                ;; can prove the cache-hit sibling stayed byte-identical (output
                ;; marker retained) across app-a's forced recompile.
                :b-fp (pr-str (get views b-view-id))})
      build-state)

    build-state))
