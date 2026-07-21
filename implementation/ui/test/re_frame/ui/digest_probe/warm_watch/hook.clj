(ns re-frame.ui.digest-probe.warm-watch.hook
  "Test-only OBSERVE hook for the real Shadow warm-watch/file-edit fixture
  (rf2-tm1xi, Arm 2 of rf2-4vm19).

  Deep-merged AFTER the inherited re-frame.ui compile hook (configured in
  :build-defaults), so at every stage Shadow runs re-frame.ui's hook FIRST and
  this one SECOND — the real inherited-then-build-local deep-merge/order a real
  consumer build produces, proved by config rather than by a hand-built
  build-state. It NEVER mutates build state: it only reads re-frame.ui's accepted
  compiler topology and appends it to a JSONL the runner parses.

  What it records is the observable build-topology the runner asserts survives
  each real warm edit, per the rf2-4vm19 Arm-1 ruling — proving ONE real dev
  build's identity at the runtime seam, NOT two-build isolation:

  * :accepted-build-id — the build id re-frame.ui stamped onto the accepted
    snapshot. For this real dev build it is `:ui-warm-watch`, never `::default`;
    a fallback to `::default` (or a bare name mismatch) means the inherited hook
    never ran or its stage annotation was dropped — the annotation load-bearing
    proof.
  * the accepted custom-element manifest (:probe-card / :probe-leaf properties)
    — a pure function of the build's declarations after the harvest, so a shrink
    that the warm rebuild failed to re-harvest, or an eviction that a
    `:build-sources` delta failed to apply, is directly visible.
  * `:build-sources` membership for each probe namespace — the compile-side
    Shadow authority whose delta must evict a removed/renamed owner.
  * whether the no-`:require`-edge consumer view is recompiling THIS pass — the
    coarse manifest-change invalidation witness.

  Pure JVM dev-build tooling; never part of any CLJS bundle."
  (:require [clojure.java.io :as io]
            [re-frame.ui.compiler.build :as build]))

;; Shadow runs with CWD = this fixture directory (the runner spawns it there),
;; so climb back to implementation/target — the same gitignored tree the
;; :cache-root uses — rather than littering target under the test source tree.
(def ^:private out-dir
  (io/file "../../../../../../target" "ui-warm-watch"))

(def ^:private card-ns 're-frame.ui.digest-probe.warm-watch.card)
(def ^:private view-ns 're-frame.ui.digest-probe.warm-watch.view)
(def ^:private leaf-ns 're-frame.ui.digest-probe.warm-watch.leaf)
(def ^:private leaf-renamed-ns 're-frame.ui.digest-probe.warm-watch.leaf-renamed)
(def ^:private card-tag :probe-card)
(def ^:private leaf-tag :probe-leaf)
(def ^:private view-id :re-frame.ui.digest-probe.warm-watch.view/probe-view)

(defn- observe-file [build-id]
  (io/file out-dir (str "observe-" (name build-id) ".jsonl")))

(defn- record! [build-id m]
  (let [f (observe-file build-id)]
    (io/make-parents f)
    (spit f (str (pr-str (assoc m :build-id (name build-id))) "\n") :append true)))

(defn- source-rid
  "The build resource-id whose declaring namespace is `ns-sym`, or nil."
  [{:keys [build-sources sources]} ns-sym]
  (some (fn [rid] (when (= ns-sym (get-in sources [rid :ns])) rid))
        build-sources))

(defn- member-nss
  "The declaring namespaces of Shadow's authoritative `:build-sources` — the
  compile-side membership whose delta re-frame.ui's eviction is keyed on."
  [{:keys [build-sources sources]}]
  (reduce (fn [acc rid]
            (let [rc (get sources rid)]
              (into acc (or (:provides rc)
                            (when-let [n (:ns rc)] #{n})))))
          #{}
          build-sources))

(defn- props-image
  "A stable, runner-parseable image of `tag`'s accepted :properties (a sorted
  vector), or \"nil\" when the tag has no accepted declaration."
  [manifest tag]
  (if-let [decl (get manifest tag)]
    (pr-str (vec (sort (:properties decl))))
    "nil"))

(defn hook
  {:shadow.build/stages #{:compile-prepare :compile-finish}}
  [{build-id :shadow.build/build-id
    stage    :shadow.build/stage
    :as      build-state}]
  (case stage
    :compile-prepare
    ;; re-frame.ui's inherited hook has already run its coarse manifest-change
    ;; invalidation, so a view scheduled to recompile has had its retained output
    ;; removed. Record whether the no-require-edge consumer view is recompiling
    ;; this pass: present output = warm cache hit; absent = coarse invalidation
    ;; forced a re-bake.
    (let [view-rid (source-rid build-state view-ns)]
      (record! build-id
               {:stage :prepare
                :view-output-present (some? (get-in build-state [:output view-rid]))})
      build-state)

    :compile-finish
    (let [snap     (build/accepted-snapshot build-state)
          manifest (build/accepted-element-manifest build-state)
          views    (build/accepted-aggregate build/views build-state)
          members  (member-nss build-state)]
      (record! build-id
               {:stage :finish
                :accepted-build-id (pr-str (:build-id snap))
                :version (:version snap)
                :card-props (props-image manifest card-tag)
                :leaf-props (props-image manifest leaf-tag)
                :leaf-tag-present (contains? manifest leaf-tag)
                :member-card (contains? members card-ns)
                :member-leaf (contains? members leaf-ns)
                :member-leaf-renamed (contains? members leaf-renamed-ns)
                :member-view (contains? members view-ns)
                :view-present (contains? views view-id)})
      build-state)

    build-state))
