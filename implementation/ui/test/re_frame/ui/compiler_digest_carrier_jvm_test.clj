(ns re-frame.ui.compiler-digest-carrier-jvm-test
  "Pure carrier projection plus functional accepted-snapshot transaction tests."
  (:require [cljs.env :as cljs-env]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]))

(def ^:private carrier-rid [:cljs "re_frame/ui/digest_carrier.cljs"])
(def ^:private client-rid [:cljs "re_frame/ui/client.cljs"])
(def ^:private app-rid [:cljs "app/view.cljs"])
(def ^:private app-a-rid [:cljs "app/a.cljs"])
(def ^:private app-b-rid [:cljs "app/b.cljs"])

(defn- fresh-js
  "A DISTINCT String instance carrying `s`, modelling Shadow's
  `(.toString StringWriter)`: every real `do-compile-cljs-resource` allocates a
  brand-new `:js` object — never an interned literal — even when the emitted
  bytes are byte-identical to a prior compile. Use this for the `:js` of any
  output that models a fresh compile.

  NOTE: `:js` object identity is deliberately NOT the recompile signal any more
  (rf2-v7wqk) — a non-scheduling hook can swap `:js` for a fresh String without
  scheduling compilation, so a `:js`-identity test misclassifies. Compile
  membership is now re-frame.ui's per-pass provenance MARKER: Shadow's compile
  path replaces the whole output MAP (dropping the marker `:compile-prepare`
  stamped), while an assoc/update-in on a retained output preserves it. `fresh-js`
  here only makes a modelled compile carry a realistic distinct `:js`."
  ^String [^String s]
  (String. s))

(defn- shadow-state [build-id js]
  {:shadow.build/build-id build-id
   :shadow.build/stage :compile-prepare
   :compiler-env {}
   :executor (Object.)
   :build-options {:cache-blockers '#{re-frame.ui}}
   :build-sources [carrier-rid app-rid]
   :sources {carrier-rid {:ns 're-frame.ui.digest-carrier
                          :provides #{'re-frame.ui.digest-carrier}
                          :type :cljs}
             app-rid {:ns 'app.view
                      :provides #{'app.view}
                      :requires '#{re-frame.ui}
                      :type :cljs}}
   :output {carrier-rid {:resource-id carrier-rid :js js :cached true}
            app-rid {:resource-id app-rid :js "app.view = {};"}}
   :build-modules [{:module-id :base :sources [carrier-rid]}
                   {:module-id :lazy :sources [app-rid]
                    :depends-on #{:base}}]})

(defn- two-source-state
  "A UI-client build graph with two app sources (app.a, app.b), each declaring a
  view, plus the digest carrier. `parallel?` toggles Shadow's parallel vs
  sequential compile-schedule detection (an executor present + parallel build)."
  [build-id parallel?]
  (cond-> {:shadow.build/build-id build-id
           :shadow.build/stage :compile-prepare
           :compiler-env {}
           :build-options {:cache-blockers '#{re-frame.ui}}
           :build-sources [carrier-rid app-a-rid app-b-rid]
           :sources {carrier-rid {:ns 're-frame.ui.digest-carrier
                                  :provides #{'re-frame.ui.digest-carrier}
                                  :type :cljs}
                     app-a-rid {:ns 'app.a :provides #{'app.a}
                                :requires '#{re-frame.ui} :type :cljs}
                     app-b-rid {:ns 'app.b :provides #{'app.b}
                                :requires '#{re-frame.ui} :type :cljs}}
           :output {carrier-rid {:resource-id carrier-rid
                                 :js build-hook/digest-sentinel :cached true}
                    app-a-rid {:resource-id app-a-rid :js "app.a = {};"}
                    app-b-rid {:resource-id app-b-rid :js "app.b = {};"}}
           :build-modules [{:module-id :base
                            :sources [carrier-rid app-a-rid app-b-rid]}]}
    parallel? (assoc :executor (Object.))))

(defn- prepare [state]
  (build-hook/hook (assoc state :shadow.build/stage :compile-prepare)))

(defn- declare [state source id digest]
  (let [compiler (atom (assoc (:compiler-env state)
                              :shadow.build.cljs-bridge/state state))]
    (binding [cljs-env/*compiler* compiler]
      (build/contribute! build/views source id digest))
    (assoc state :compiler-env
           (dissoc @compiler :shadow.build.cljs-bridge/state))))

(defn- compiled-output
  "A FRESH Shadow-shaped compiled output map: marker-absent, `:cached false`.
  Modelling `do-compile-cljs-resource`, which replaces the whole output map."
  [rid js]
  {:resource-id rid :js js :cached false})

(defn- finish
  "Drive `:compile-finish`. Models Shadow's real compile phase: every CLJS build
  member whose output is ABSENT (Shadow scheduled it — e.g. a version-0 UI
  consumer whose retained output prepare invalidated) is given a FRESH compiled
  output map and recorded in Shadow's OWN `[:shadow.build/build-info :compiled]`
  set. Members that already carry an output keep it (a warm hit's marker-stamped
  map, or a map the test set to model a recompile / a hook replacement); such a
  test names its own recompiled members via `extra-compiled`. The prior helper
  left version-0-invalidated consumers with NO finish output, which real Shadow
  never does."
  ([state] (finish state #{}))
  ([state extra-compiled]
   (let [cljs-members (filter #(= :cljs (get-in state [:sources % :type]))
                              (:build-sources state))
         scheduled (into (set extra-compiled)
                         (remove #(map? (get-in state [:output %])))
                         cljs-members)
         state (reduce (fn [s rid]
                         (cond-> s
                           (not (map? (get-in s [:output rid])))
                           (assoc-in [:output rid]
                                     (compiled-output rid "compiled"))))
                       state scheduled)
         ;; Shadow RESETS `:compiled` every pass (extract-build-info recomputes
         ;; it), so set — never accumulate — this pass's compiled set.
         state (assoc-in state [:shadow.build/build-info :compiled] scheduled)]
     (build-hook/hook (assoc state :shadow.build/stage :compile-finish)))))

(deftest ui-build-requires-the-load-bearing-cache-blocker
  (doseq [configured [nil [] '#{other.library}]]
    (let [input (assoc-in (shadow-state :bad-config build-hook/digest-sentinel)
                          [:build-options :cache-blockers]
                          configured)
          ex (try (prepare input) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (= :re-frame.ui.compiler.build-hook/cache-blocker-missing
             (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
      (is (nil? (get-in input [:compiler-env build/scratch-key]))))))

(deftest version-zero-prepare-invalidates-retained-ui-consumer-output
  (let [input (shadow-state :warm-start build-hook/digest-sentinel)
        prepared (prepare input)]
    (is (nil? (get-in prepared [:output app-rid]))
        "retained output cannot bypass registry macro expansion on daemon start")
    (is (contains? (get-in prepared [:compiler-env build/scratch-key :touched])
                   'app.view))
    (is (= build-hook/digest-sentinel
           (get-in prepared [:output carrier-rid :js])))))

(deftest accepted-warm-prepare-preserves-output-present-ui-consumer
  (let [good (-> (shadow-state :warm-incremental build-hook/digest-sentinel)
                 prepare
                 (declare 'app.view :app/view ["tf1-good" "hs1-good"])
                 finish)
        warm-input (-> good
                       (assoc-in [:output carrier-rid :js]
                                 build-hook/digest-sentinel)
                       (assoc-in [:output app-rid]
                                 {:resource-id app-rid
                                  :js "app.view = {};"
                                  :cached true}))
        prepared (prepare warm-input)]
    (is (= "app.view = {};" (get-in prepared [:output app-rid :js])))
    (is (not (contains?
              (get-in prepared [:compiler-env build/scratch-key :touched])
              'app.view))
        "accepted output-present cache hits do not become whole-UI recompiles")))

(deftest compile-finish-patches-carrier-and-carries-one-snapshot
  (let [sentinel build-hook/digest-sentinel
        input (shadow-state :app (str "before:" sentinel ":after"))
        prepared (prepare input)
        compiled (declare prepared 'app.view :app/view ["tf1-a" "hs1-a"])
        out (finish compiled)
        digest (build/accepted-build-digest out)
        js (get-in out [:output carrier-rid :js])]
    (is (str/starts-with? digest "bd1-"))
    (is (= (count sentinel) (count digest)))
    (is (not (str/includes? js sentinel)))
    (is (= 1 (count (re-seq (re-pattern digest) js))))
    (is (false? (:cached (get-in out [:output app-rid])))
        "version-zero prepare invalidated the retained UI-consumer output; Shadow recompiled it fresh (:cached false), not a warm-cache hit")
    (is (= (:build-modules input) (:build-modules out)))
    (is (= {:app/view ["tf1-a" "hs1-a"]}
           (build/accepted-aggregate build/views out)))
    (is (= 1 (:version (build/accepted-snapshot out))))
    (is (nil? (get-in out [:compiler-env build/scratch-key])))))

(deftest malformed-carrier-never-produces-an-accepted-candidate
  (doseq [[label js]
          [[:missing "no marker"]
           [:duplicate (str build-hook/digest-sentinel
                            build-hook/digest-sentinel)]]]
    (testing (name label)
      (let [input (shadow-state (keyword "bad" (name label)) js)
            compiled (-> input prepare
                         (declare 'app.view :app/view ["tf1-new" "hs1-new"]))
            ex (try (finish compiled) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (= :re-frame.ui.compiler.build-hook/carrier-output-invalid
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= {} (build/accepted-aggregate build/views input)))
        (is (= 0 (:version (build/accepted-snapshot input))))
        (is (some? (get-in compiled [:compiler-env build/scratch-key])))))))

(deftest duplicate-carrier-resource-is-loud-before-candidate-carriage
  (let [rid2 [:cljs "other/digest_carrier.cljs"]
        input (-> (shadow-state :duplicate-carrier build-hook/digest-sentinel)
                  (update :build-sources conj rid2)
                  (assoc-in [:sources rid2]
                            {:ns 'other.carrier
                             :provides #{'re-frame.ui.digest-carrier}})
                  (assoc-in [:output rid2]
                            {:resource-id rid2 :js build-hook/digest-sentinel}))
        compiled (-> input prepare
                     (declare 'app.view :app/view ["tf1-new" "hs1-new"]))]
    (is (thrown? clojure.lang.ExceptionInfo (finish compiled)))
    (is (= {} (build/accepted-aggregate build/views input)))))

(deftest zero-carrier-resource-preserves-the-last-accepted-snapshot
  (let [good (-> (shadow-state :zero-carrier build-hook/digest-sentinel)
                 prepare
                 (declare 'app.view :app/view ["tf1-good" "hs1-good"])
                 finish)
        accepted (build/accepted-snapshot good)
        zero-carrier-input
        (-> good
            ;; Keep a real UI-client graph member while removing the carrier
            ;; resource itself. This distinguishes count zero from a non-UI
            ;; build, which is correctly allowed to have no carrier.
            (assoc :build-sources [client-rid app-rid])
            (update :sources dissoc carrier-rid)
            (assoc-in [:sources client-rid]
                      {:ns 're-frame.ui.client
                       :provides #{'re-frame.ui.client}
                       :requires '#{re-frame.ui.digest-carrier}
                       :type :cljs})
            (update :output dissoc carrier-rid)
            (assoc-in [:output client-rid]
                      {:resource-id client-rid
                       :js "re_frame.ui.client = {};"
                       :cached true})
            (assoc :build-modules
                   [{:module-id :base :sources [client-rid]}
                    {:module-id :lazy :sources [app-rid]
                     :depends-on #{:base}}]))
        compiled (-> zero-carrier-input
                     prepare
                     (declare 'app.view :app/view ["tf1-doomed" "hs1-doomed"]))
        ex (try (finish compiled) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (= :re-frame.ui.compiler.build-hook/carrier-output-invalid
           (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
    (is (= "re-frame.ui expected exactly one compiled digest carrier output"
           (ex-message ex)))
    (is (= [] (:carrier-resource-ids (ex-data ex))))
    (is (= 0 (:count (ex-data ex))))
    (is (= accepted (build/accepted-snapshot zero-carrier-input))
        "the incoming accepted value remains the last-known-good authority")
    (is (= accepted (build/accepted-snapshot compiled))
        "opening and populating scratch does not publish the candidate")
    (is (= {:app/view ["tf1-good" "hs1-good"]}
           (build/accepted-aggregate build/views compiled)))
    (is (some? (get-in compiled [:compiler-env build/scratch-key]))
        "the rejected candidate remains disposable scratch")
    (is (nil? (get-in compiled [:output carrier-rid]))
        "zero carrier output cannot be mistaken for an accepted publication")))

(deftest later-prepare-hook-forced-recompile-evicts-removed-view
  ;; rf2-vxgfnd.194 / rf2-ialoij: a build-local :compile-prepare hook running
  ;; AFTER the inherited re-frame.ui hook removes a source's retained output,
  ;; forcing Shadow to recompile it after re-frame.ui observed the schedule. If
  ;; that source removed its final ui/defview it contributes nothing, and —
  ;; pre-fix — its accepted row survived as a ghost view because it was never
  ;; pre-touched. The :compile-finish reconcile against Shadow's CAUSAL per-source
  ;; compile evidence (a `:cached false` finish output whose `:js` artifact is a
  ;; fresh object) evicts it.
  ;;
  ;; The forced recompile here carries a `:compiled-at` stamp EQUAL to the prepare
  ;; snapshot (a same-millisecond `System/currentTimeMillis`) and byte-identical
  ;; `:js`, so nothing but Shadow's fresh-object provenance distinguishes it from a
  ;; warm hit. A `(> now then)` stamp test would read it as untouched and leave the
  ;; ghost; the causal test evicts it.
  (doseq [parallel? [true false]]
    (testing (str (if parallel? "parallel" "sequential") " compilation")
      (let [good (-> (two-source-state :ghost parallel?)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                     finish)]
        (is (= {:app/a-view ["tfa-1" "hsa-1"] :app/b-view ["tfb-1" "hsb-1"]}
               (build/accepted-aggregate build/views good))
            "the accepted build declares both sources' views")
        ;; Warm pass: app.a + app.b both retain a stamped output, so re-frame.ui
        ;; pre-touches neither. A later prepare hook then removes app.a's output;
        ;; app.a recompiles this pass — Shadow allocates a FRESH `:js` object —
        ;; contributing NO declaration, at the SAME `:compiled-at` stamp (1000).
        ;; app.b stays a cache hit: its EXACT output object (same `:js`) is
        ;; retained unchanged across the pass.
        (let [warm-b-output {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                             :compiled-at 1000 :cached false}
              warm-input (-> good
                             (assoc-in [:output carrier-rid :js]
                                       build-hook/digest-sentinel)
                             (assoc-in [:output app-a-rid]
                                       {:resource-id app-a-rid
                                        :js (fresh-js "app.a = {};")
                                        :compiled-at 1000 :cached false})
                             (assoc-in [:output app-b-rid] warm-b-output))
              prepared (prepare warm-input)
              finished (-> prepared
                           (assoc-in [:output app-a-rid]
                                     {:resource-id app-a-rid
                                      :js (fresh-js "app.a = {};")
                                      :compiled-at 1000 :cached false})
                           ;; Shadow genuinely recompiled app-a this pass, so it is
                           ;; in Shadow's causal `::build-info :compiled` record.
                           (finish #{app-a-rid}))]
          (is (not (contains?
                    (get-in prepared [:compiler-env build/scratch-key :touched])
                    'app.a))
              "re-frame.ui does not pre-touch an output-present source at prepare")
          (is (= {:app/b-view ["tfb-1" "hsb-1"]}
                 (build/accepted-aggregate build/views finished))
              "the forced-recompile ghost is evicted; the cache-hit sibling stays")
          (is (not (contains? (build/accepted-aggregate build/views finished)
                              :app/a-view))
              "no ghost view survives the source's same-stamp zero-declaration recompile")
          (is (not= (build/accepted-build-digest good)
                    (build/accepted-build-digest finished))
              "evicting the ghost changes the accepted whole-build digest"))))))

(deftest causal-membership-survives-colliding-and-backwards-stamps
  ;; rf2-vxgfnd.255 / rf2-ialoij / rf2-v7wqk: final-schedule membership is decided
  ;; by re-frame.ui's per-pass provenance MARKER — never a `:compiled-at`
  ;; wall-clock relationship and never Shadow's `:js` object identity. Shadow
  ;; stamps `:compiled-at` with `System/currentTimeMillis`, which can EQUAL or
  ;; DECREASE across genuine compiles (same-ms, clock skew, a non-monotonic clock),
  ;; so any `>`/`>=`/`!=` stamp test misclassifies. In ONE warm pass, under the
  ;; SAME optimizer/compile controls (sequential + parallel):
  ;;   * app.b is a genuine cache hit whose stamped retained output flows through
  ;;     prepare -> finish unchanged, yet carries the maximal `:compiled-at` — it
  ;;     must stay accepted and digest-stable;
  ;;   * app.a is genuinely recompiled (Shadow replaces its whole output map,
  ;;     dropping the marker) and removed its final view, but its stamp went
  ;;     BACKWARDS (Long/MAX_VALUE snapshot -> 1 finish) — it must still be evicted.
  ;; A `(> now then)` test reads app.a's backwards stamp as untouched (ghost
  ;; survives) and would evict the max-stamp app.b; the marker test does neither.
  (doseq [parallel? [true false]]
    (testing (str (if parallel? "parallel" "sequential") " compilation")
      (let [good (-> (two-source-state :collide parallel?)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                     finish)
            b-before (get (build/accepted-aggregate build/views good) :app/b-view)]
        (is (= {:app/a-view ["tfa-1" "hsa-1"] :app/b-view ["tfb-1" "hsb-1"]}
               (build/accepted-aggregate build/views good))
            "the accepted build declares both sources' views")
        ;; app.b's stamped retained output flows through prepare -> finish (a true
        ;; cache hit at the maximal stamp): it is NOT re-set at finish, so it keeps
        ;; the marker prepare stamped. app.a is replaced with a fresh output map
        ;; (no marker) whose stamp is LOWER than its snapshot.
        (let [warm-b-output {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                             :compiled-at Long/MAX_VALUE :cached false}
              warm-input (-> good
                             (assoc-in [:output carrier-rid :js]
                                       build-hook/digest-sentinel)
                             (assoc-in [:output app-a-rid]
                                       {:resource-id app-a-rid
                                        :js (fresh-js "app.a = {};")
                                        :compiled-at Long/MAX_VALUE :cached true})
                             (assoc-in [:output app-b-rid] warm-b-output))
              prepared (prepare warm-input)
              finished (-> prepared
                           (assoc-in [:output app-a-rid]
                                     {:resource-id app-a-rid
                                      :js (fresh-js "app.a = {};")
                                      :compiled-at 1 :cached false})
                           ;; app-a genuinely recompiled (backwards stamp) — in
                           ;; Shadow's causal `::build-info :compiled` record; app-b
                           ;; is a warm hit (marker preserved), never in it.
                           (finish #{app-a-rid}))
              views-after (build/accepted-aggregate build/views finished)]
          (is (not (contains?
                    (get-in prepared [:compiler-env build/scratch-key :touched])
                    'app.a)))
          (is (not (contains?
                    (get-in prepared [:compiler-env build/scratch-key :touched])
                    'app.b))
              "neither output-present warm source is pre-touched at prepare")
          (is (= {:app/b-view ["tfb-1" "hsb-1"]} views-after)
              "the max-stamp warm hit survives; only the real recompile is evicted")
          (is (= b-before (get views-after :app/b-view))
              "app.b's accepted row is digest-stable across the warm pass")
          (is (not (contains? views-after :app/a-view))
              "the genuinely recompiled zero-declaration source is evicted despite its backwards stamp"))))))

(deftest metadata-only-output-mutation-is-not-a-recompile
  ;; rf2-vxgfnd.282 / rf2-ialoij / rf2-v7wqk: a fresh output MAP is NOT compile
  ;; provenance. Shadow deep-merges build-local hooks after the inherited
  ;; re-frame.ui hook, so a later :compile-prepare hook can annotate or normalize a
  ;; source's STILL-VALID retained output with assoc/update-in — producing a NEW
  ;; output MAP WITHOUT scheduling any compilation. Under raw whole-output
  ;; `identical?` provenance re-frame.ui misread that fresh map as a recompile,
  ;; pre-touched the source, saw no registry macro, and silently EVICTED its
  ;; accepted view. The per-pass MARKER is preserved by any assoc/update-in on a
  ;; retained output (a real recompile instead REPLACES the whole map, dropping
  ;; it), so a metadata annotation survives and a real recompile evicts — the two
  ;; arms below are distinguished with the `:compiled-at` stamp held EQUAL and,
  ;; deliberately, WITHOUT relying on `:js` identity. Both arms run in sequential
  ;; and parallel compile modes.
  (doseq [parallel? [true false]]
    (let [mode (if parallel? "parallel" "sequential")
          good (-> (two-source-state :meta parallel?)
                   prepare
                   (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                   (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                   finish)
          digest-before (build/accepted-build-digest good)
          views-before  (build/accepted-aggregate build/views good)
          ;; A warm accepted generation: both app.a and app.b retain a compiled
          ;; output object (each with its own fresh `:js`); the carrier sentinel is
          ;; reset so finish re-projects.
          warm-a {:resource-id app-a-rid :js (fresh-js "app.a = {};")
                  :compiled-at 1000 :cached false}
          warm-b {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                  :compiled-at 1000 :cached false}
          warm-input (-> good
                         (assoc-in [:output carrier-rid :js]
                                   build-hook/digest-sentinel)
                         (assoc-in [:output app-a-rid] warm-a)
                         (assoc-in [:output app-b-rid] warm-b))]
      (is (= {:app/a-view ["tfa-1" "hsa-1"] :app/b-view ["tfb-1" "hsb-1"]}
             views-before)
          (str mode ": the accepted build declares both sources' views"))

      (testing (str mode " — later hook only annotates app.b's retained output")
        ;; No source is scheduled this warm pass. A later build-local prepare hook
        ;; receives the build-state AFTER re-frame.ui's prepare (its output already
        ;; marker-stamped) and replaces app.b's still-valid output with a NEW map
        ;; that ONLY adds metadata; assoc preserves the marker, so Shadow skips
        ;; app.b. app.b must survive byte-identical and the digest must not move.
        (let [prepared    (prepare warm-input)
              prepared-b  (get-in prepared [:output app-b-rid])
              mutated-b   (assoc prepared-b :shadow.build/annotation :normalized)
              finished    (-> prepared
                              (assoc-in [:output app-b-rid] mutated-b)
                              finish)]
          (is (not (identical? mutated-b prepared-b))
              "the later hook produced a fresh output MAP for app.b")
          (is (= (get prepared-b :re-frame.ui.compiler.build-hook/pass-marker)
                 (get mutated-b :re-frame.ui.compiler.build-hook/pass-marker))
              "but the metadata-only mutation preserved re-frame.ui's per-pass marker")
          (is (identical? (:js warm-b) (:js mutated-b))
              "and (incidentally) still the SAME `:js` object — but that is no longer the signal")
          (is (not (contains?
                    (get-in prepared [:compiler-env build/scratch-key :touched])
                    'app.b))
              "re-frame.ui does not pre-touch an output-present source at prepare")
          (is (= views-before (build/accepted-aggregate build/views finished))
              "a metadata-only mutation is not a recompile; both accepted rows survive")
          (is (= (get views-before :app/b-view)
                 (get (build/accepted-aggregate build/views finished) :app/b-view))
              "app.b's accepted row is byte-identical across the pass")
          (is (= digest-before (build/accepted-build-digest finished))
              "the whole-build digest is unchanged")))

      (testing (str mode " — companion: app.b output removed and recompiled")
        ;; Same stamp (1000), opposite verdict: the later hook instead REMOVES
        ;; app.b's output and app.b genuinely recompiles — a FRESH output map at
        ;; the SAME `:compiled-at` — after its final defview was removed,
        ;; contributing nothing. app.b is evicted; app.a stays a cache hit. The
        ;; marker (dropped by the whole-map replacement) plus Shadow's causal
        ;; `::build-info :compiled` record distinguish this genuine recompile from
        ;; arm 1's marker-preserving annotation — with no `:compiled-at` compare.
        (let [prepared (prepare warm-input)
              finished (-> prepared
                           (assoc-in [:output app-b-rid]
                                     {:resource-id app-b-rid
                                      :js (fresh-js "app.b = {};")
                                      :compiled-at 1000 :cached false})
                           (finish #{app-b-rid}))]
          (is (= {:app/a-view ["tfa-1" "hsa-1"]}
                 (build/accepted-aggregate build/views finished))
              "the genuinely recompiled zero-declaration source is evicted")
          (is (not= digest-before (build/accepted-build-digest finished))
              "evicting the recompiled ghost changes the whole-build digest"))))))

(deftest missing-per-pass-provenance-fails-loud-not-empty-schedule
  ;; rf2-vxgfnd.255 / rf2-v7wqk: an open pass whose prepare-time provenance token
  ;; is unobservable must fail loudly at finish rather than silently reconcile
  ;; against an assumed-empty compile schedule (which would leak ghost rows).
  (let [prepared (-> (two-source-state :no-prov false)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"]))
        blinded (update-in prepared [:compiler-env build/scratch-key]
                           dissoc :pass-token)
        ex (try (finish blinded) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? (get-in prepared [:compiler-env build/scratch-key :pass-token]))
        "an ordinary open pass records the per-pass provenance token")
    (is (some? ex) "finish with open scratch but no :pass-token must throw")
    (is (= :re-frame.ui.compiler.build-hook/missing-pass-provenance
           (:re-frame.ui.compiler.build-hook/error (ex-data ex))))))

(deftest non-scheduling-js-transform-is-not-a-recompile
  ;; rf2-v7wqk — the P1 the per-pass marker fixes (red-before / green-after).
  ;; `:cached` is STICKY on retained outputs, so a later non-scheduling
  ;; :compile-prepare hook that replaces ONLY `[:output rid :js]` with a fresh
  ;; String (equal bytes OR changed bytes) — scheduling NO compilation — leaves
  ;; `:cached false` and a DIFFERENT `:js` object. The pre-fix `:cached false` +
  ;; `:js`-identity test misread that as a recompile, pre-touched app.b, saw no
  ;; registry macro, and EVICTED its valid accepted view — changing the whole-
  ;; build digest. assoc preserves re-frame.ui's per-pass marker, so the marker
  ;; test sees no compilation: the view and digest are retained. The metadata test
  ;; above cannot catch this ordinary output-transforming shape because it
  ;; deliberately preserves the nested `:js` object. Both equal-byte and
  ;; changed-byte transforms; both compile schedules.
  (doseq [parallel? [true false]
          [byte-label new-js] [[:equal-bytes "app.b = {};"]
                               [:changed-bytes "app.b = {/* transformed */};"]]]
    (testing (str (if parallel? "parallel" "sequential") " / " (name byte-label))
      (let [good (-> (two-source-state (keyword "jsx" (name byte-label)) parallel?)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                     finish)
            digest-before (build/accepted-build-digest good)
            views-before  (build/accepted-aggregate build/views good)
            warm-a {:resource-id app-a-rid :js (fresh-js "app.a = {};")
                    :compiled-at 1000 :cached false}
            warm-b {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                    :compiled-at 1000 :cached false}
            warm-input (-> good
                           (assoc-in [:output carrier-rid :js]
                                     build-hook/digest-sentinel)
                           (assoc-in [:output app-a-rid] warm-a)
                           (assoc-in [:output app-b-rid] warm-b))
            prepared (prepare warm-input)
            ;; A later non-scheduling hook swaps ONLY app.b's :js for a fresh
            ;; String — no compilation. :cached stays false (sticky). assoc keeps
            ;; every other key, including re-frame.ui's per-pass marker.
            transformed-b (assoc (get-in prepared [:output app-b-rid])
                                 :js (fresh-js new-js))
            finished (-> prepared
                         (assoc-in [:output app-b-rid] transformed-b)
                         finish)]
        (is (= {:app/a-view ["tfa-1" "hsa-1"] :app/b-view ["tfb-1" "hsb-1"]}
               views-before)
            "the accepted build declares both sources' views")
        (is (false? (:cached transformed-b))
            "a non-scheduling :js transform leaves :cached false (sticky)")
        (is (not (identical? (:js transformed-b) (:js warm-b)))
            "and yields a DIFFERENT :js object — the shape a :js-identity test misreads")
        (is (not (contains?
                  (get-in prepared [:compiler-env build/scratch-key :touched])
                  'app.b))
            "re-frame.ui does not pre-touch an output-present source at prepare")
        (is (= views-before (build/accepted-aggregate build/views finished))
            "an output :js transform is NOT a recompile; both accepted views survive")
        (is (contains? (build/accepted-aggregate build/views finished) :app/b-view)
            "app.b's accepted view is retained, not evicted as a ghost")
        (is (= digest-before (build/accepted-build-digest finished))
            "the whole-build digest is unchanged")))))

(deftest ambiguous-per-source-provenance-fails-loud
  ;; rf2-v7wqk / rf2-ialoij: per-source provenance that is MISSING/malformed (an
  ;; unmarked output with no usable `:cached` flag) or CONTRADICTORY (an output
  ;; carrying a STALE marker that is not this pass's token) must fail with a typed
  ;; compiler error BEFORE any accepted candidate is published — never silently
  ;; classified as untouched.
  (let [marker-key :re-frame.ui.compiler.build-hook/pass-marker
        warm-b {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                :compiled-at 1000 :cached false}
        open (fn [build-id]
               (let [good (-> (two-source-state build-id false)
                              prepare
                              (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                              (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                              finish)]
                 (-> good
                     (assoc-in [:output carrier-rid :js] build-hook/digest-sentinel)
                     (assoc-in [:output app-a-rid]
                               {:resource-id app-a-rid :js (fresh-js "app.a = {};")
                                :compiled-at 1000 :cached false})
                     (assoc-in [:output app-b-rid] warm-b)
                     prepare)))
        finish-ex (fn [prepared bad-b]
                    (try (-> prepared (assoc-in [:output app-b-rid] bad-b) finish)
                         nil
                         (catch clojure.lang.ExceptionInfo e e)))]
    (testing "unmarked output with no usable :cached flag"
      (let [prepared (open :ambig-unmarked)
            ex (finish-ex prepared {:resource-id app-b-rid :js (fresh-js "x")})]
        (is (some? ex) "an unmarked output with no :cached must throw")
        (is (= :re-frame.ui.compiler.build-hook/ambiguous-compile-evidence
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= :unmarked-output-missing-cached-flag (:reason (ex-data ex))))))
    (testing "output carrying a stale (wrong-token) marker"
      (let [prepared (open :ambig-stale)
            stale-b (assoc (get-in prepared [:output app-b-rid])
                           marker-key "not-this-pass-token")
            ex (finish-ex prepared stale-b)]
        (is (some? ex) "a stale provenance marker must throw")
        (is (= :re-frame.ui.compiler.build-hook/ambiguous-compile-evidence
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= :stale-provenance-marker (:reason (ex-data ex))))))))

(deftest whole-map-replacement-cannot-forge-compilation
  ;; rf2-8nn5k (violation 2 — UNFORGEABLE): a later NON-scheduling hook replaces an
  ;; ENTIRE retained output map, copying Shadow's sticky `:cached false` and output
  ;; data into a fresh map while dropping re-frame.ui's unknown private marker — and
  ;; schedules NO compilation, so the source is NOT in Shadow's own
  ;; `[:shadow.build/build-info :compiled]` record. Pre-fix, finish trusted the
  ;; output map's forgeable `:cached false` and classified the replacement a compile,
  ;; pre-touched the warm source, and EVICTED its valid accepted view (changing the
  ;; whole-build digest) on a pass that compiled nothing. Now the marker-absent
  ;; verdict consults Shadow's causal compile record rather than `:cached`, so the
  ;; forged whole-map replacement fails loud BEFORE publication and the accepted
  ;; view/digest survive. Both equal-byte and changed-byte forgeries; both schedules.
  (doseq [parallel? [true false]
          [byte-label forged-js] [[:equal-bytes "app.b = {};"]
                                  [:changed-bytes "app.b = {/* forged */};"]]]
    (testing (str (if parallel? "parallel" "sequential") " / " (name byte-label))
      (let [build-id (keyword "forge" (str (name byte-label) (if parallel? "-p" "-s")))
            good (-> (two-source-state build-id parallel?)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                     finish)
            digest-before (build/accepted-build-digest good)
            views-before  (build/accepted-aggregate build/views good)
            warm-a {:resource-id app-a-rid :js (fresh-js "app.a = {};")
                    :compiled-at 1000 :cached false}
            warm-b {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                    :compiled-at 1000 :cached false}
            warm-input (-> good
                           (assoc-in [:output carrier-rid :js]
                                     build-hook/digest-sentinel)
                           (assoc-in [:output app-a-rid] warm-a)
                           (assoc-in [:output app-b-rid] warm-b))
            prepared (prepare warm-input)
            ;; The forgery: a fresh output MAP built from Shadow's public fields
            ;; (no re-frame.ui marker), copying the sticky :cached false. `finish`
            ;; is NOT told app.b compiled (it is not in ::build-info :compiled).
            forged-b {:resource-id app-b-rid :js (fresh-js forged-js)
                      :compiled-at 1000 :cached false}
            ex (try (-> prepared (assoc-in [:output app-b-rid] forged-b) finish)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (false? (:cached forged-b))
            "the forgery copies Shadow's sticky :cached false")
        (is (nil? (get forged-b :re-frame.ui.compiler.build-hook/pass-marker))
            "and drops re-frame.ui's private per-pass marker")
        (is (some? ex)
            "a whole-map replacement Shadow did not compile cannot be silently classified as a compile")
        (is (= :re-frame.ui.compiler.build-hook/ambiguous-compile-evidence
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= :marker-dropped-without-compile (:reason (ex-data ex))))
        (is (= app-b-rid (:resource-id (ex-data ex)))
            "the diagnostic names the offending resource, not just the hook")
        (is (= 'app.b (:ns (ex-data ex))))
        (is (= build-id (:build-id (ex-data ex))))
        (is (not= :configure-ui-build-hook-once (:recovery (ex-data ex)))
            "a later-hook evidence loss reports an accurate recovery, not hook-config boilerplate")
        (is (= views-before (build/accepted-aggregate build/views warm-input))
            "the forged replacement never evicted app.b's valid accepted view")
        (is (= digest-before (build/accepted-build-digest warm-input))
            "the whole-build digest is unchanged")))))

(deftest absent-cljs-member-output-fails-loud-not-silent
  ;; rf2-8nn5k (violation 1 — TOTAL): a CLJS graph member with a missing/nil/non-map
  ;; final output must fail loud, naming the resource, rather than be silently
  ;; skipped — pre-fix, a missing `[:output rid]` was skipped, so an accepted
  ;; row/digest could publish with the member's per-resource compile evidence absent.
  ;; Bypasses the realistic `finish` helper (which would repopulate an absent output)
  ;; to drive the guard directly, modelling a hook that drops/corrupts an output.
  (doseq [parallel? [true false]
          [label bad] [[:absent ::absent] [:non-map "not-a-map"]]]
    (testing (str (if parallel? "parallel" "sequential") " / " (name label))
      (let [good (-> (two-source-state (keyword "total" (name label)) parallel?)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"])
                     finish)
            views-before (build/accepted-aggregate build/views good)
            warm-a {:resource-id app-a-rid :js (fresh-js "app.a = {};")
                    :compiled-at 1000 :cached false}
            warm-b {:resource-id app-b-rid :js (fresh-js "app.b = {};")
                    :compiled-at 1000 :cached false}
            warm-input (-> good
                           (assoc-in [:output carrier-rid :js]
                                     build-hook/digest-sentinel)
                           (assoc-in [:output app-a-rid] warm-a)
                           (assoc-in [:output app-b-rid] warm-b))
            prepared (prepare warm-input)
            broken (cond-> (assoc prepared :shadow.build/stage :compile-finish)
                     (= bad ::absent) (update :output dissoc app-b-rid)
                     (not= bad ::absent) (assoc-in [:output app-b-rid] bad))
            ex (try (build-hook/hook broken) nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex)
            "a missing/non-map CLJS member output must fail loud, never be skipped")
        (is (= :re-frame.ui.compiler.build-hook/missing-compile-output
               (:re-frame.ui.compiler.build-hook/error (ex-data ex))))
        (is (= app-b-rid (:resource-id (ex-data ex))))
        (is (= 'app.b (:ns (ex-data ex))))
        (is (= :absent-or-non-map-final-output (:reason (ex-data ex))))
        (is (= views-before (build/accepted-aggregate build/views warm-input))
            "the accepted view aggregate is left last-known-good on the fail-loud path")))))

(deftest interleaved-build-values-carry-isolated-digests
  (let [a (-> (shadow-state :a build-hook/digest-sentinel)
              prepare
              (declare 'app.view :app/view ["tf1-a" "hs1-a"])
              finish)
        b (-> (shadow-state :b build-hook/digest-sentinel)
              prepare
              (declare 'app.view :app/view ["tf1-b" "hs1-b"])
              finish)
        da (build/accepted-build-digest a)
        db (build/accepted-build-digest b)]
    (is (not= da db))
    (is (str/includes? (get-in a [:output carrier-rid :js]) da))
    (is (str/includes? (get-in b [:output carrier-rid :js]) db))))

(deftest downstream-failure-is-discard-not-rollback
  (let [seed (shadow-state :app build-hook/digest-sentinel)
        good (-> seed prepare
                 (declare 'app.view :app/view ["tf1-good" "hs1-good"])
                 finish)
        doomed-input (-> good
                         ;; A recompiled ^:dev/always carrier contains the
                         ;; sentinel again before compile-finish projection.
                         (assoc-in [:output carrier-rid :js]
                                   build-hook/digest-sentinel))
        doomed (-> doomed-input prepare
                   (declare 'app.view :app/view ["tf1-doomed" "hs1-doomed"])
                   finish)]
    (is (not= (build/accepted-build-digest good)
              (build/accepted-build-digest doomed)))
    ;; Model a failure after compile-finish: Shadow retains `good`, not
    ;; `doomed`. No external atom needs rollback and a retry seeds from good.
    (let [retry-input (assoc-in good [:output carrier-rid :js]
                                build-hook/digest-sentinel)
          retry (-> retry-input prepare
                    (declare 'app.view :app/view ["tf1-retry" "hs1-retry"])
                    finish)]
      (is (= 2 (:version (build/accepted-snapshot retry))))
      (is (= {:app/view ["tf1-retry" "hs1-retry"]}
             (build/accepted-aggregate build/views retry))))))
