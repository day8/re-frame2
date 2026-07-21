(ns re-frame.ui.compiler-build-hook-jvm-test
  "Functional Shadow build-state authority tests for Option C.

  These fixtures deliberately thread the build-state returned by the real hook.
  Nothing observes a process-global successful build: retaining the returned
  value models Shadow success; discarding it models any later
  optimize/check/flush/watch failure."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
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

(defn- declare-explicit-view [state source vname view-id template]
  (in-compiler
   state
   #(binding [*ns* (create-ns source)]
      (compiler/defview*
       (with-meta (list 'defview vname {:id view-id} [] template) {:line 1})
       {} vname (list {:id view-id} [] template)))))

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

(deftest live-id-collision-fails-but-nonmember-transfer-converges
  (let [seed (graph-state :app :compile-prepare '#{app.old})
        old (-> seed
                (prepare '#{app.old})
                (declare-explicit-view 'app.old 'card :shared/card [:div "old"])
                (finish '#{app.old}))
        ;; app.old is a live cache hit; app.new alone is scheduled and cannot
        ;; steal its accepted id.
        collision-state (prepare old '#{app.old app.new} '#{app.new})
        ex (try
             (declare-explicit-view collision-state 'app.new 'card
                                    :shared/card [:div "new"])
             nil
             (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.ui.compile/bad-view-id
           (:rf.ui.compile/error (ex-data ex))))
    (let [moved (-> old
                    (prepare '#{app.new})
                    (declare-explicit-view 'app.new 'card
                                           :shared/card [:div "new"])
                    (finish '#{app.new}))]
      (is (= #{:shared/card} (set (keys (views moved)))))
      (is (not= (get (views old) :shared/card)
                (get (views moved) :shared/card))))))

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
