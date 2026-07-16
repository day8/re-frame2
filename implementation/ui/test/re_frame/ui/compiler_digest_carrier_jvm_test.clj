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
  bytes are byte-identical to a prior compile. `identical?` on that object is
  precisely the causal recompile signal `compiled-this-pass?` keys on, so a test
  literal (interned, shared across every occurrence) would falsely read as a warm
  cache hit. Use this for the `:js` of any output that models a fresh compile."
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

(defn- finish [state]
  (build-hook/hook (assoc state :shadow.build/stage :compile-finish)))

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
    (is (nil? (get-in out [:output app-rid]))
        "version-zero prepare invalidates retained UI-consumer output before Shadow recompiles it")
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
                           finish)]
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
  ;; rf2-vxgfnd.255 / rf2-ialoij: final-schedule membership is decided by Shadow's
  ;; CAUSAL compile evidence — a `:cached false` finish output whose `:js` artifact
  ;; is a fresh object — never a `:compiled-at` wall-clock relationship. Shadow
  ;; stamps `:compiled-at` with `System/currentTimeMillis`, which can EQUAL or
  ;; DECREASE across genuine compiles (same-ms, clock skew, a non-monotonic clock),
  ;; so any `>`/`>=`/`!=` stamp test misclassifies. In ONE warm pass, under the
  ;; SAME optimizer/compile controls (sequential + parallel):
  ;;   * app.b is a genuine cache hit whose retained output object (same `:js`) is
  ;;     unchanged, yet carries the maximal `:compiled-at` — it must stay accepted
  ;;     and digest-stable;
  ;;   * app.a is genuinely recompiled (a fresh `:js` object) and removed its final
  ;;     view, but its stamp went BACKWARDS (Long/MAX_VALUE snapshot -> 1 finish) —
  ;;     it must still be evicted.
  ;; A `(> now then)` test reads app.a's backwards stamp as untouched (ghost
  ;; survives) and would evict the max-stamp app.b; the causal test does neither.
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
        ;; The EXACT same retained object stands in for app.b across prepare and
        ;; finish (a true cache hit at the maximal stamp). app.a is replaced with a
        ;; fresh output object whose stamp is LOWER than its snapshot.
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
                           (assoc-in [:output app-b-rid] warm-b-output)
                           finish)
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
  ;; rf2-vxgfnd.282 / rf2-ialoij: a fresh output MAP is NOT compile provenance.
  ;; Shadow deep-merges build-local hooks after the inherited re-frame.ui hook, so
  ;; a later :compile-prepare hook can annotate or normalize a source's STILL-VALID
  ;; retained output with assoc/update-in — producing a NEW output MAP (but, assoc
  ;; preserving nested values, the SAME `:js` object) WITHOUT scheduling any
  ;; compilation. Under raw whole-output `identical?` provenance re-frame.ui
  ;; misread that fresh map as a recompile, pre-touched the source, saw no registry
  ;; macro, and silently EVICTED its accepted view. Shadow's causal fresh-compile
  ;; evidence is the fresh `:js` artifact object its compile path allocates: a
  ;; metadata-only mutation preserves the SAME `:js` object, a real recompile
  ;; allocates a new one. Membership keys on `:cached false` plus that `:js` object,
  ;; NOT the whole-map reference and NOT the `:compiled-at` stamp — so the survival
  ;; and eviction arms below are distinguished with the stamp held EQUAL. Both arms
  ;; run in sequential and parallel compile modes.
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
        ;; replaces app.b's still-valid output with a NEW map that ONLY adds
        ;; metadata; assoc preserves the SAME `:js` object, so Shadow skips app.b.
        ;; app.b must survive byte-identical and the digest must not move.
        (let [prepared  (prepare warm-input)
              mutated-b (assoc warm-b :shadow.build/annotation :normalized)
              finished  (-> prepared
                            (assoc-in [:output app-b-rid] mutated-b)
                            finish)]
          (is (not (identical? mutated-b warm-b))
              "the later hook produced a fresh output MAP for app.b")
          (is (identical? (:js warm-b) (:js mutated-b))
              "but the metadata-only mutation preserved Shadow's `:js` artifact object")
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
        ;; app.b's output and app.b genuinely recompiles — a FRESH `:js` object at
        ;; the SAME `:compiled-at` — after its final defview was removed,
        ;; contributing nothing. app.b is evicted; app.a stays a cache hit. Whole-
        ;; map identity would have flagged BOTH arms' app.b as recompiled and a
        ;; stamp test would have missed this same-ms recompile; the fresh-`:js`
        ;; evidence distinguishes them.
        (let [prepared (prepare warm-input)
              finished (-> prepared
                           (assoc-in [:output app-b-rid]
                                     {:resource-id app-b-rid
                                      :js (fresh-js "app.b = {};")
                                      :compiled-at 1000 :cached false})
                           finish)]
          (is (= {:app/a-view ["tfa-1" "hsa-1"]}
                 (build/accepted-aggregate build/views finished))
              "the genuinely recompiled zero-declaration source is evicted")
          (is (not= digest-before (build/accepted-build-digest finished))
              "evicting the recompiled ghost changes the whole-build digest"))))))

(deftest missing-per-pass-provenance-fails-loud-not-empty-schedule
  ;; rf2-vxgfnd.255: an open pass whose prepare-time provenance snapshot is
  ;; unobservable must fail loudly at finish rather than silently reconcile
  ;; against an assumed-empty compile schedule (which would leak ghost rows).
  (let [prepared (-> (two-source-state :no-prov false)
                     prepare
                     (declare 'app.a :app/a-view ["tfa-1" "hsa-1"])
                     (declare 'app.b :app/b-view ["tfb-1" "hsb-1"]))
        blinded (update-in prepared [:compiler-env build/scratch-key]
                           dissoc :pass-output)
        ex (try (finish blinded) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? (get-in prepared [:compiler-env build/scratch-key :pass-output]))
        "an ordinary open pass records the provenance snapshot")
    (is (some? ex) "finish with open scratch but no :pass-output must throw")
    (is (= :re-frame.ui.compiler.build-hook/missing-pass-provenance
           (:re-frame.ui.compiler.build-hook/error (ex-data ex))))))

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
