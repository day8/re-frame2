(ns re-frame.ui.compiler-build-hook-jvm-test
  "Functional Shadow build-state authority tests for Option C.

  These fixtures deliberately thread the build-state returned by the real hook.
  Nothing observes a process-global successful build: retaining the returned
  value models Shadow success; discarding it models any later
  optimize/check/flush/watch failure."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.build-hook :as build-hook]
            [re-frame.ui.compiler.root :as root]
            [re-frame.ui.shadow-compile-model :as shadow]))

(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

(defn- graph-state [build-id stage member-nss]
  {:shadow.build/build-id build-id
   :shadow.build/stage stage
   :compiler-env {}
   ;; Truthy executor + default parallel-build mirrors Shadow watch. In that
   ;; branch output presence is the exact cache-hit / compile-schedule signal.
   :executor (Object.)
   ;; As `shadow.build.api/init` seeds it: the seam re-frame.ui installs its
   ;; per-pass compile witness into.
   :analyzer-passes []
   :build-sources (vec member-nss)
   :sources (into {} (map (fn [n] [n {:ns n :provides #{n} :type :cljs}]))
                  member-nss)})

(defn- at-stage [state stage member-nss]
  (merge state (select-keys (graph-state (:shadow.build/build-id state)
                                        stage member-nss)
                           [:shadow.build/stage :build-sources :sources])))

(defn- prepare
  ([state member-nss]
   (prepare state member-nss (set member-nss)))
  ([state member-nss recompiled-nss]
   (let [recompiled-nss (set recompiled-nss)
         prepared (at-stage state :compile-prepare member-nss)
         ;; Reset output for scheduled sources; retain (or synthesize) a map
         ;; for authoritative warm-cache members which Shadow will not compile.
         output (into {}
                      (keep (fn [n]
                              (when-not (contains? recompiled-nss n)
                                [n (or (get-in state [:output n])
                                       {:resource-id n :js "cached"})])))
                      member-nss)]
     (build-hook/hook (assoc prepared :output output)))))

(defn- in-compiler
  "Run `f` as Shadow runs macros, then copy the mutated compiler-env back into
  the persistent build-state."
  [state f]
  (let [compiler (atom (assoc (:compiler-env state)
                              :shadow.build.cljs-bridge/state state))]
    (binding [cljs-env/*compiler* compiler]
      (f))
    (assoc state :compiler-env
           (dissoc @compiler :shadow.build.cljs-bridge/state))))

(defn- read-in-compiler [state f]
  (let [compiler (atom (assoc (:compiler-env state)
                              :shadow.build.cljs-bridge/state state))]
    (binding [cljs-env/*compiler* compiler]
      (f))))

(defn- declare-view [state source view-id fp]
  (in-compiler state #(build/contribute! build/views source view-id fp)))

(defn- declare-descriptor [state source root-id label]
  (in-compiler
   state
   #(build/contribute! build/descriptors source root-id
                       {:rf.root/schema-version 1 :label label})))

(defn- finish
  ([state member-nss] (finish state member-nss (set member-nss)))
  ([state member-nss recompiled-nss]
   ;; Model Shadow's real compile phase: every source Shadow (re)compiled this
   ;; pass gets a FRESH output map (marker-absent, `:cached false`) AND has its
   ;; forms analyzed — the causal event re-frame.ui's per-pass compile witness
   ;; records (rf2-suz5b; never a `(> compiled-at compile-start)` set). Warm
   ;; cache-hit members keep the marker-stamped output prepare left them. This is
   ;; what a real build hands `:compile-finish`.
   (let [recompiled-nss (set recompiled-nss)
         with-outputs (reduce (fn [s n]
                                (assoc-in s [:output n]
                                          {:resource-id n :js "compiled"
                                           :cached false}))
                              state recompiled-nss)
         finishing (at-stage with-outputs :compile-finish member-nss)]
     (doseq [n recompiled-nss]
       (shadow/compile-ns! finishing (get-in finishing [:sources n :ns])))
     (build-hook/hook finishing))))

(defn- pass
  ([state member-nss declarations]
   (pass state member-nss (set member-nss) declarations))
  ([state member-nss recompiled-nss declarations]
  (let [prepared (prepare state member-nss recompiled-nss)
        compiled (reduce (fn [s [source id fp]]
                           (declare-view s source id fp))
                         prepared declarations)]
    (finish compiled member-nss recompiled-nss))))

(defn- views [state]
  (build/accepted-aggregate build/views state))

;; --- rf2-u53yy.1 S2: views ride the analyzer-map descriptor carrier ---------
;;
;; On a real Shadow build PASS the `defview` macro contributes NO view row to the
;; per-source slice (compiler.cljc gates it on `build/shadow-build-pass?`): a
;; compiled view's descriptor rides its generated def's analyzer metadata, which
;; Shadow persists to its disk cache and RESTORES under
;; [:compiler-env :cljs.analyzer/namespaces <ns> :defs <var> :meta] on a warm hit
;; (the S0 proof, rf2-u53yy.1.1). `seed-analyzer-view` stamps that carrier exactly
;; where the analyzer stores it, so the build hook harvests the views registry
;; from it at compile-finish — for a cache-hit member the macro never re-runs.

(defn- seed-analyzer-view
  "Stamp a compiled view's descriptor onto `state`'s analyzer map the way Shadow's
  analyzer stores a `defview`'s generated def metadata (and restores it from the
  disk cache on a warm hit). `var` is the def name; the ruled declaration is
  `[source var]`; `digest` is the `[template-fingerprint hook-signature]` vector
  the emitter stamps."
  [state source var view-id digest]
  (assoc-in state
            [:compiler-env :cljs.analyzer/namespaces source :defs var :meta]
            {:rf.ui/view true :rf.ui/view-id view-id :rf.ui/view-digest digest}))

(defn- forget-analyzer-ns
  "Model Shadow re-analyzing `source` from scratch on a recompile: its analyzer
  `:defs` are rebuilt, so a view removed from the source vanishes from the carrier."
  [state source]
  (update-in state [:compiler-env :cljs.analyzer/namespaces] dissoc source))

(deftest hook-declares-only-the-two-compiler-stages
  (is (= #{:compile-prepare :compile-finish}
         (:shadow.build/stages (meta #'build-hook/hook)))))

(deftest returned-build-state-is-the-success-transaction
  (let [seed (graph-state :app :compile-prepare '#{app.a})
        good-1 (pass seed '#{app.a} [['app.a :app/view ["tf1-a" "hs1-a"]]])
        prepared-bad (prepare good-1 '#{app.a app.partial})
        compiled-bad (-> prepared-bad
                         (declare-view 'app.a :app/view ["tf1-bad" "hs1-bad"])
                         (declare-view 'app.partial :app/partial
                                       ["tf1-partial" "hs1-partial"]))
        candidate-bad (finish compiled-bad '#{app.a app.partial})]
    (is (= {:app/view ["tf1-a" "hs1-a"]} (views good-1)))
    (is (= #{:app/view :app/partial} (set (keys (views candidate-bad))))
        "compile-finish returns a candidate; it does not mutate good-1")

    ;; Model a downstream optimize/check/flush/hook failure by discarding the
    ;; candidate and starting the retry from Shadow's retained good-1 state.
    (let [good-2 (pass good-1 '#{app.a}
                       [['app.a :app/view ["tf1-c" "hs1-c"]]])]
      (is (= {:app/view ["tf1-c" "hs1-c"]} (views good-2)))
      (is (= 2 (:version (build/accepted-snapshot good-2))))
      (is (= 1 (:version (build/accepted-snapshot good-1)))))))

(deftest prepare-overwrites-dirty-scratch-and-clears-repl-overlay
  (let [seed (graph-state :app :compile-prepare '#{app.a})
        good (pass seed '#{app.a} [['app.a :app/view ["tf1-good" "hs1-good"]]])
        dirty (-> (prepare good '#{app.a})
                  (declare-view 'app.a :app/view ["tf1-ghost" "hs1-ghost"])
                  ;; No finish: model compile failure.
                  )
        retry (prepare (assoc good :compiler-env (:compiler-env dirty)) '#{app.a})]
    (is (= {:app/view ["tf1-good" "hs1-good"]}
           (build/accepted-aggregate build/views retry)))
    (is (= {}
           (read-in-compiler retry #(build/aggregate build/views)))
        "the scheduled source is pre-touched, so effective scratch hides its accepted row until its macros rerun")
    (is (= {:app/view ["tf1-good" "hs1-good"]}
           (build/accepted-aggregate build/views retry))
        "prepare seeds from accepted state, never abandoned scratch")))

(deftest no-pass-repl-is-an-isolated-non-publishing-overlay
  (let [seed (graph-state :app :compile-prepare '#{app.a})
        good (pass seed '#{app.a} [['app.a :app/view ["tf1-good" "hs1-good"]]])
        ;; Macroexpand-only / never-evaluated / runtime-failed forms all have
        ;; the same compiler-side shape. They may accumulate in the disposable
        ;; overlay, but cannot enter the accepted snapshot.
        repl-state (-> good
                       (declare-view 'repl.ghost :repl/ghost
                                     ["tf1-ghost" "hs1-ghost"])
                       (declare-view 'repl.success :repl/success
                                     ["tf1-success" "hs1-success"]))]
    (is (= {:app/view ["tf1-good" "hs1-good"]} (views repl-state)))
    (is (= #{:app/view :repl/ghost :repl/success}
           (set (keys (read-in-compiler repl-state
                                        #(build/aggregate build/views))))))
    (let [prepared (prepare repl-state '#{app.a})]
      (is (nil? (get-in prepared [:compiler-env build/repl-overlay-key])))
      (is (= {}
             (read-in-compiler prepared #(build/aggregate build/views)))
          "scheduled app.a is hidden in effective scratch until macro expansion")
      (is (= {:app/view ["tf1-good" "hs1-good"]}
             (build/accepted-aggregate build/views prepared))))))

(deftest deleted-source-eviction-and-live-cache-hit
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (pass seed '#{app.a app.b}
                   [['app.a :app.a/view ["tf1-a" "hs1-a"]]
                    ['app.b :app.b/view ["tf1-b" "hs1-b"]]])
        only-a (pass both '#{app.a}
                     [['app.a :app.a/view ["tf1-a2" "hs1-a"]]])]
    (is (= #{:app.a/view :app.b/view} (set (keys (views both)))))
    (is (= {:app.a/view ["tf1-a2" "hs1-a"]} (views only-a)))))

(deftest authoritative-live-cache-hit-member-survives
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (pass seed '#{app.a app.b}
                   [['app.a :app.a/view ["tf1-a" "hs1-a"]]
                    ['app.b :app.b/view ["tf1-b" "hs1-b"]]])
        ;; app.b remains in the resolved graph but its macro is a cache hit and
        ;; does not run. Membership, not touched, distinguishes it from delete.
        incremental (pass both '#{app.a app.b} '#{app.a}
                          [['app.a :app.a/view ["tf1-a2" "hs1-a"]]])]
    (is (= {:app.a/view ["tf1-a2" "hs1-a"]
            :app.b/view ["tf1-b" "hs1-b"]}
           (views incremental)))))

(deftest recompiled-source-which-removes-final-declaration-is-evicted
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (pass seed '#{app.a app.b}
                   [['app.a :app.a/view ["tf1-a" "hs1-a"]]
                    ['app.b :app.b/view ["tf1-b" "hs1-b"]]])
        ;; app.a remains an authoritative build member but was reset and
        ;; recompiled after removing its final re-frame.ui declaration. No
        ;; macro call can mark it touched, so prepare must do so from Shadow's
        ;; missing-output schedule. app.b is an output-present cache hit.
        without-a (pass both '#{app.a app.b} '#{app.a} [])]
    (is (= {:app.b/view ["tf1-b" "hs1-b"]}
           (views without-a)))))

(deftest view-descriptor-survives-a-cache-hit-via-the-analyzer-map-carrier
  ;; The S2 analog of the S0 carrier proof (rf2-u53yy.1.1), for REAL view
  ;; descriptors: a warm cache-hit source's macro never re-runs, yet its view
  ;; survives because Shadow RESTORED the descriptor onto the analyzer map and the
  ;; hook harvests the views registry from there — not from a macro side effect.
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        ;; COLD: both compiled; each stamps its view descriptor onto its def meta.
        ;; Nothing is contributed to the slice (the macro is gated on the Shadow
        ;; path), so a harvested row here proves the analyzer-map carrier is the
        ;; sole source of the views registry.
        cold (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-view 'app.a 'view :app.a/view ["tf-a" "hs-a"])
                 (seed-analyzer-view 'app.b 'view :app.b/view ["tf-b" "hs-b"])
                 (finish '#{app.a app.b}))]
    (is (= {:app.a/view ["tf-a" "hs-a"] :app.b/view ["tf-b" "hs-b"]} (views cold))
        "cold: both views are harvested from the analyzer-map carrier")
    ;; WARM: only app.b recompiles. app.a is a cache HIT — its macro does NOT run
    ;; (no seed this pass), but Shadow RESTORED its analyzer descriptor, which the
    ;; threaded build-state carries unchanged. app.a's view must survive from the
    ;; carrier alone; app.b re-stamps a fresh digest.
    (let [warm (-> (prepare cold '#{app.a app.b} '#{app.b})
                   (seed-analyzer-view 'app.b 'view :app.b/view ["tf-b2" "hs-b"])
                   (finish '#{app.a app.b} '#{app.b}))]
      (is (= {:app.a/view ["tf-a" "hs-a"]
              :app.b/view ["tf-b2" "hs-b"]}
             (views warm))
          (str "warm: the cache-hit source's view is RESTORED from the analyzer "
               "map without the macro re-running")))))

(deftest a-removed-view-is-evicted-from-the-analyzer-map-carrier
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-view 'app.a 'view :app.a/view ["tf-a" "hs-a"])
                 (seed-analyzer-view 'app.b 'view :app.b/view ["tf-b" "hs-b"])
                 (finish '#{app.a app.b}))
        ;; app.a is recompiled with its defview removed: Shadow re-analyzes it
        ;; fresh, so its analyzer :defs no longer carry the descriptor. app.b is
        ;; an untouched cache hit whose descriptor is restored.
        removed (-> (prepare both '#{app.a app.b} '#{app.a})
                    (forget-analyzer-ns 'app.a)
                    (finish '#{app.a app.b} '#{app.a}))]
    (is (= #{:app.a/view :app.b/view} (set (keys (views both)))))
    (is (= {:app.b/view ["tf-b" "hs-b"]} (views removed))
        "a view removed from its recompiled source is evicted from the carrier")))

(deftest cross-source-view-id-collision-fails-at-compile-finish
  ;; Two DISTINCT defview declarations claiming one view-id fail when the hook
  ;; harvests the carrier at compile-finish — the cross-source uniqueness law moved
  ;; there from macro expansion (rf2-u53yy.1 S2); a compile-finish error is still a
  ;; build-time error.
  (let [seed (graph-state :app :compile-prepare '#{app.old app.new})
        collision (-> (prepare seed '#{app.old app.new})
                      (seed-analyzer-view 'app.old 'card :shared/card ["tf-old" "hs"])
                      (seed-analyzer-view 'app.new 'card :shared/card ["tf-new" "hs"]))
        ex (try (finish collision '#{app.old app.new})
                nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "distinct declarations sharing a view-id must fail the build")
    (is (= :re-frame.ui.compiler.build/view-id-conflict
           (:re-frame.ui.compiler.build/error (ex-data ex))))
    (is (= :shared/card (:view-id (ex-data ex))))
    (is (= [['app.new 'card] ['app.old 'card]]
           (:declarations (ex-data ex)))
        "the collision evidence anchors both declarations, sorted deterministically")))

(deftest a-view-id-transfers-cleanly-to-a-new-owner
  ;; app.old owned :shared/card; it is deleted and app.new takes the id over. With
  ;; app.old absent from :build-sources the carrier fold sees only app.new's
  ;; descriptor, so no collision fires and the new owner publishes its own view
  ;; identity.
  (let [old (-> (graph-state :app :compile-prepare '#{app.old})
                (prepare '#{app.old})
                (seed-analyzer-view 'app.old 'card :shared/card ["tf-old" "hs"])
                (finish '#{app.old}))
        moved (-> (graph-state :app :compile-prepare '#{app.new})
                  (prepare '#{app.new})
                  (seed-analyzer-view 'app.new 'card :shared/card ["tf-new" "hs"])
                  (finish '#{app.new}))]
    (is (= #{:shared/card} (set (keys (views old)))))
    (is (= #{:shared/card} (set (keys (views moved)))))
    (is (not= (get (views old) :shared/card)
              (get (views moved) :shared/card))
        "the new owner publishes its own view identity")))

;; --- rf2-u53yy.1 S4: roots + plans ride the SYNTHETIC analyzer-map carrier -----
;;
;; Root/plan CALL SITES carry no def (unlike views, S2), so on a real Shadow build
;; PASS `register-root-site!` / `register-plan-site!` stamp a SYNTHETIC per-namespace
;; descriptor `[::namespaces <ns> :rf.ui/root-plan-descriptor {:roots [..] :plans [..]}]`
;; into the analyzer map (compiler.cljc-style gating on `build/shadow-build-pass?`),
;; which Shadow persists to its disk cache and RESTORES on a warm hit (the S0 proof
;; variant B, rf2-u53yy.1.1). `seed-analyzer-root`/`seed-analyzer-plan` stamp that
;; carrier exactly where the analyzer stores it, so the build hook harvests the
;; roots/plans registries from it at compile-finish — for a cache-hit member the
;; macro never re-runs. Eviction is automatic: Shadow's watch reset dissocs the whole
;; ns entry (`forget-analyzer-ns` models it), so a removed site vanishes.

(defn- roots [state] (build/accepted-aggregate build/roots state))
(defn- plans [state] (build/accepted-aggregate build/plans state))

(defn- seed-analyzer-roots
  "Stamp `source`'s root sites onto `state`'s analyzer map the way the
  register-root-site! macro does on a Shadow build pass (and Shadow restores from
  the disk cache on a warm hit). `sites` is a coll of `[root-id provenance]`."
  [state source sites]
  (assoc-in state
            [:compiler-env :cljs.analyzer/namespaces source
             :rf.ui/root-plan-descriptor :roots]
            (mapv (fn [[root-id provenance]]
                    {:root-id root-id
                     :row {:file (str source ".cljs") :line 1 :provenance provenance}})
                  sites)))

(defn- seed-analyzer-plans
  "Stamp `source`'s frame-plan sites onto `state`'s analyzer map. `sites` is a coll
  of `[frame-id config-fingerprint]`."
  [state source sites]
  (assoc-in state
            [:compiler-env :cljs.analyzer/namespaces source
             :rf.ui/root-plan-descriptor :plans]
            (mapv (fn [[frame-id cf]]
                    {:frame-id frame-id
                     :row {:config-fingerprint cf
                           :file (str source ".cljs") :line 1}})
                  sites)))

(defn- seed-analyzer-descriptors
  "Stamp `source`'s Root Descriptor sites onto `state`'s analyzer map the way the
  register-descriptor! macro does on a Shadow build pass (rf2-u53yy.1 S5 — the
  descriptors ride the SAME synthetic carrier as roots/plans). `sites` is a coll of
  `[root-id label]`; the row is a minimal Root Descriptor v1."
  [state source sites]
  (assoc-in state
            [:compiler-env :cljs.analyzer/namespaces source
             :rf.ui/root-plan-descriptor :descriptors]
            (mapv (fn [[root-id label]]
                    {:root-id root-id
                     :row {:rf.root/schema-version 1 :root-id root-id :label label}})
                  sites)))

(deftest root-and-plan-descriptors-survive-a-cache-hit-via-the-analyzer-carrier
  ;; The S4 analog of the S0 carrier proof (rf2-u53yy.1.1) for REAL root/plan sites:
  ;; a warm cache-hit source's macro never re-runs, yet its root and plan survive
  ;; because Shadow RESTORED the synthetic descriptor onto the analyzer map and the
  ;; hook harvests the registries from there — not from a macro side effect.
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        ;; COLD: both compiled; each stamps its root/plan descriptor. Nothing is
        ;; contributed to the slice (the macro is gated on the Shadow path), so a
        ;; harvested row here proves the analyzer-map carrier is the sole source.
        cold (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-roots 'app.a [[:page/a :authored]])
                 (seed-analyzer-plans 'app.a [[:shop "cf-a"]])
                 (seed-analyzer-roots 'app.b [[:page/b :derived]])
                 (finish '#{app.a app.b}))]
    (is (= #{:page/a :page/b} (set (keys (roots cold))))
        "cold: both roots are harvested from the analyzer-map carrier")
    (is (= :authored (:provenance (get (roots cold) :page/a))))
    (is (= {:shop "cf-a"}
           (update-vals (plans cold) :config-fingerprint))
        "cold: the plan is harvested from the analyzer-map carrier")
    ;; WARM: only app.b recompiles. app.a is a cache HIT — its macro does NOT run
    ;; (no re-seed this pass), but Shadow RESTORED its analyzer descriptor, which the
    ;; threaded build-state carries unchanged.
    (let [warm (-> (prepare cold '#{app.a app.b} '#{app.b})
                   (seed-analyzer-roots 'app.b [[:page/b :derived]])
                   (finish '#{app.a app.b} '#{app.b}))]
      (is (= #{:page/a :page/b} (set (keys (roots warm))))
          "warm: the cache-hit source's root is RESTORED without the macro re-running")
      (is (= {:shop "cf-a"} (update-vals (plans warm) :config-fingerprint))
          "warm: the cache-hit source's plan is RESTORED without the macro re-running"))))

(deftest a-removed-root-or-plan-is-evicted-from-the-analyzer-carrier
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-roots 'app.a [[:page/a :authored]])
                 (seed-analyzer-plans 'app.a [[:shop "cf-a"]])
                 (seed-analyzer-roots 'app.b [[:page/b :authored]])
                 (finish '#{app.a app.b}))
        ;; app.a is recompiled with its mount removed: Shadow's watch reset dissocs
        ;; the whole ns entry, so its synthetic descriptor is gone. app.b is an
        ;; untouched cache hit whose descriptor is restored.
        removed (-> (prepare both '#{app.a app.b} '#{app.a})
                    (forget-analyzer-ns 'app.a)
                    (finish '#{app.a app.b} '#{app.a}))]
    (is (= #{:page/a :page/b} (set (keys (roots both)))))
    (is (= #{:shop} (set (keys (plans both)))))
    (is (= #{:page/b} (set (keys (roots removed))))
        "a root removed from its recompiled source is evicted from the carrier")
    (is (= {} (plans removed))
        "the removed source's plan is evicted from the carrier")))

(deftest cross-source-duplicate-root-id-fails-at-compile-finish
  ;; Two root sites in DIFFERENT namespaces resolving to one root-id fail when the
  ;; hook harvests the carrier at compile-finish — the cross-namespace Layer-1 law
  ;; moved there from macro expansion (rf2-u53yy.1 S4); a compile-finish error is
  ;; still a build-time error.
  (let [seed (graph-state :app :compile-prepare '#{app.old app.new})
        collision (-> (prepare seed '#{app.old app.new})
                      (seed-analyzer-roots 'app.old [[:shared/root :authored]])
                      (seed-analyzer-roots 'app.new [[:shared/root :authored]]))
        ex (try (finish collision '#{app.old app.new}) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two live sites for one root-id must fail the build")
    (is (= :re-frame.ui.compiler.build/duplicate-root-id
           (:re-frame.ui.compiler.build/error (ex-data ex))))
    (is (= :shared/root (:root-id (ex-data ex))))
    (is (= 2 (count (:sites (ex-data ex)))) "both sites named with coords")))

(deftest cross-source-frame-payload-conflict-fails-at-compile-finish
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        collision (-> (prepare seed '#{app.a app.b})
                      (seed-analyzer-plans 'app.a [[:shop "cf-a"]])
                      (seed-analyzer-plans 'app.b [[:shop "cf-b"]]))
        ex (try (finish collision '#{app.a app.b}) nil
                (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "differing fingerprints for one frame-id must fail the build")
    (is (= :re-frame.ui.compiler.build/frame-payload-conflict
           (:re-frame.ui.compiler.build/error (ex-data ex))))
    (is (= :shop (:frame-id (ex-data ex))))
    (is (= ["cf-a" "cf-b"] (:fingerprints (ex-data ex))))))

(deftest cross-source-matching-plan-fingerprints-are-the-idempotent-no-op
  ;; Two namespaces legitimately declaring the same frame plan (matching
  ;; fingerprint) co-exist — the ratified idempotent no-op, re-derived at finish.
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        ok (-> (prepare seed '#{app.a app.b})
               (seed-analyzer-plans 'app.a [[:shop "cf-same"]])
               (seed-analyzer-plans 'app.b [[:shop "cf-same"]])
               (finish '#{app.a app.b}))]
    (is (= {:shop "cf-same"} (update-vals (plans ok) :config-fingerprint)))))

(deftest a-root-id-transfers-cleanly-to-a-new-owner-via-the-carrier
  ;; app.old owned :shared/root; it is deleted and app.new takes it over. With
  ;; app.old absent from :build-sources the carrier fold sees only app.new's site,
  ;; so no collision fires.
  (let [old (-> (graph-state :app :compile-prepare '#{app.old})
                (prepare '#{app.old})
                (seed-analyzer-roots 'app.old [[:shared/root :authored]])
                (finish '#{app.old}))
        moved (-> (graph-state :app :compile-prepare '#{app.new})
                  (prepare '#{app.new})
                  (seed-analyzer-roots 'app.new [[:shared/root :authored]])
                  (finish '#{app.new}))]
    (is (= #{:shared/root} (set (keys (roots old)))))
    (is (= #{:shared/root} (set (keys (roots moved)))))))

;; --- rf2-u53yy.1 S5: Root Descriptors ride the SAME synthetic carrier ----------
;;
;; The Root Descriptor index is the last registry folded onto the analyzer-map
;; carrier (S5). `register-descriptor!` stamps `[::namespaces <ns>
;; :rf.ui/root-plan-descriptor :descriptors]` at the same mount / hydrate site that
;; stamps a `:roots` site, so the hook harvests the descriptor index from the carrier
;; at compile-finish — for a cache-hit member the macro never re-runs, yet Shadow
;; RESTORED the descriptor from disk, so `descriptor-index` reads it back.

(deftest root-descriptors-survive-a-cache-hit-via-the-analyzer-carrier
  ;; The S5 analog of the S4 root/plan carrier proof for the Root Descriptor index:
  ;; a warm cache-hit source's macro never re-runs, yet its descriptor survives
  ;; because Shadow RESTORED the synthetic carrier and the hook harvests the index
  ;; from there — not from a macro side effect / the per-source slice.
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        ;; COLD: both compiled; each stamps its root + descriptor. Nothing is
        ;; contributed to the slice (the macro is gated on the Shadow path), so a
        ;; harvested descriptor here proves the analyzer-map carrier is the sole
        ;; source.
        cold (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-roots 'app.a [[:page/a :authored]])
                 (seed-analyzer-descriptors 'app.a [[:page/a :a]])
                 (seed-analyzer-roots 'app.b [[:page/b :derived]])
                 (seed-analyzer-descriptors 'app.b [[:page/b :b]])
                 (finish '#{app.a app.b}))]
    (is (= #{:page/a :page/b} (set (keys (root/descriptor-index cold))))
        "cold: both descriptors are harvested from the analyzer-map carrier")
    (is (= :a (:label (get (root/descriptor-index cold) :page/a)))
        "cold: the harvested descriptor is the stamped Root Descriptor v1")
    ;; WARM: only app.b recompiles. app.a is a cache HIT — its macro does NOT run
    ;; (no re-seed this pass), but Shadow RESTORED its analyzer descriptor, which the
    ;; threaded build-state carries unchanged.
    (let [warm (-> (prepare cold '#{app.a app.b} '#{app.b})
                   (seed-analyzer-roots 'app.b [[:page/b :derived]])
                   (seed-analyzer-descriptors 'app.b [[:page/b :b]])
                   (finish '#{app.a app.b} '#{app.b}))]
      (is (= #{:page/a :page/b} (set (keys (root/descriptor-index warm))))
          "warm: the cache-hit source's descriptor is RESTORED without the macro re-running")
      (is (= :a (:label (get (root/descriptor-index warm) :page/a)))
          "warm: the RESTORED descriptor is intact, byte-for-byte"))))

(deftest a-removed-root-descriptor-is-evicted-from-the-analyzer-carrier
  (let [seed (graph-state :app :compile-prepare '#{app.a app.b})
        both (-> (prepare seed '#{app.a app.b})
                 (seed-analyzer-roots 'app.a [[:page/a :authored]])
                 (seed-analyzer-descriptors 'app.a [[:page/a :a]])
                 (seed-analyzer-roots 'app.b [[:page/b :authored]])
                 (seed-analyzer-descriptors 'app.b [[:page/b :b]])
                 (finish '#{app.a app.b}))
        ;; app.a is recompiled with its mount removed: Shadow's watch reset dissocs
        ;; the whole ns entry, so its synthetic descriptor is gone. app.b is an
        ;; untouched cache hit whose descriptor is restored.
        removed (-> (prepare both '#{app.a app.b} '#{app.a})
                    (forget-analyzer-ns 'app.a)
                    (finish '#{app.a app.b} '#{app.a}))]
    (is (= #{:page/a :page/b} (set (keys (root/descriptor-index both)))))
    (is (= #{:page/b} (set (keys (root/descriptor-index removed))))
        "a descriptor removed from its recompiled source is evicted from the carrier")))

(deftest independent-build-states-never-cross-contribute
  (let [a (pass (graph-state :a :compile-prepare '#{app.view})
                '#{app.view} [['app.view :app/view ["tf1-a" "hs1-a"]]])
        b (pass (graph-state :b :compile-prepare '#{app.view})
                '#{app.view} [['app.view :app/view ["tf1-b" "hs1-b"]]])]
    (is (= {:app/view ["tf1-a" "hs1-a"]} (views a)))
    (is (= {:app/view ["tf1-b" "hs1-b"]} (views b)))))

(deftest descriptor-index-reads-one-coherent-explicit-accepted-snapshot
  (let [a (-> (graph-state :a :compile-prepare '#{app.a})
              (prepare '#{app.a})
              (declare-view 'app.a :app.a/view ["tf1-a" "hs1-a"])
              (declare-descriptor 'app.a :page/a :a)
              (finish '#{app.a}))
        b (-> (graph-state :b :compile-prepare '#{app.b})
              (prepare '#{app.b})
              (declare-view 'app.b :app.b/view ["tf1-b" "hs1-b"])
              (declare-descriptor 'app.b :page/b :b)
              (finish '#{app.b}))
        a-index (root/descriptor-index a)
        b-index (root/descriptor-index b)]
    (is (= #{:page/a} (set (keys a-index))))
    (is (= #{:page/b} (set (keys b-index))))

    ;; A process-global fallback can contain unrelated data, but explicit
    ;; retained-state reads remain build-local.
    (build/begin-build! :fallback)
    (build/contribute! build/descriptors 'fallback.ns :page/fallback
                       {:rf.root/schema-version 1 :label :fallback})
    (build/commit-build! :fallback)
    (is (= #{:page/a} (set (keys (root/descriptor-index a)))))
    (is (= #{:page/b} (set (keys (root/descriptor-index b)))))

    ;; Stage a descriptor in disposable scratch. The ambient read must expose
    ;; only accepted rows, never the candidate row from open scratch.
    (let [open-a (-> a
                     (prepare '#{app.a app.pending} '#{app.pending})
                     (declare-descriptor 'app.pending :page/pending :pending))
          ambient-index (read-in-compiler open-a #(root/descriptor-index))]
      (is (= #{:page/a} (set (keys ambient-index)))))))
