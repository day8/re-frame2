(ns re-frame.freehand.compiler-build-jvm-test
  "The ONE build-scoped compiler-state authority (rf2-vxgfnd.16 → rf2-df9873):
  the S1 compiler's build registries are a PURE FUNCTION of the current build
  inputs, keyed PER shadow build id, with explicit begin/commit/abort pass
  boundaries and last-known-good publication.

  The adversarial cruces:

    - MULTI-BUILD ISOLATION (the headline regression): one long-lived JVM
      compiles `re-frame.freehand` for several builds at once; two build ids must
      NOT wipe each other's registries (the prior global-build-id +
      wipe-on-switch model did — regressing cross-file duplicate detection).
    - PASS BOUNDARIES: an incremental commit replaces only the touched
      source's contribution and keeps untouched sources; a WHOLE-build
      reconcile drops sources that did not re-declare; an ABORTED pass
      republishes the last-known-good, not a partial.
    - ATOMICITY: `register-root-site!` check-then-write inside ONE transition,
      so parallel compilation neither drops a concurrent write nor misses a
      genuine duplicate.
    - REPL UPSERT: with no pass open a contribution upserts one key straight
      into committed (no begin/commit cycle, no sibling eviction).

  On the Freehand door the client mount verbs (`v/mount` / `v/hydrate-root` /
  `v/unmount!`) are runtime fns, so `v/render-static` is the only entry point
  that indexes a root site — `register-root-site!` is driven directly here, the
  way render-static drives it. `v/custom-element` runs its real macro body
  under a bound `*ns*` so distinct declaring namespaces (the ledger's source
  key, rf2-df9873) are addressable. The view-registry arm of these tests
  retired with the dead `compiler/defview*` chain (rf2-drpa3.177): the LIVE
  cross-source view-id uniqueness law runs at compile-finish
  (`build/harvest-view-registries`) and is covered by
  compiler_build_hook_jvm_test; the generic pass/isolation machinery is
  exercised here through `::probe` and the custom-element / root registries."
  (:require [cljs.env :as cljs-env]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.freehand.compiler :as compiler]
            [re-frame.freehand.compiler.analyze :as ana]
            [re-frame.freehand.compiler.build :as build]
            [re-frame.freehand.compiler.env :as env]
            [re-frame.freehand.compiler.root :as root]))

;; Every test starts from a clean authority; correctness across a watch
;; session comes from per-source replacement on the pass boundaries, but
;; tests want independence, so the fixture uses the hard boundary.
(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — drive the five registries the way real compilation does, with a
;; controllable declaring namespace (the ledger source key).
;; ---------------------------------------------------------------------------

;; `declare-view!` / `declare-explicit-view!` retired with the dead
;; `compiler/defview*` chain (rf2-drpa3.177). They drove `defview*` purely to
;; exercise the plain-JVM view-registry uniqueness (`build/contribute-view-checked!`),
;; whose sole producer was the dead `defview**`; the LIVE cross-source
;; view-id uniqueness law runs at compile-finish in `build/harvest-view-registries`
;; and is covered end-to-end by compiler_build_hook_jvm_test
;; (cross-source-view-id-collision, cache-hit survival, eviction, ownership
;; transfer). The generic slice machinery those tests also touched is covered
;; here through `::probe` and the live `custom-element` / root registries.

(defn- declare-element!
  "Run the real `v/custom-element` macro body with `ns-sym` bound as the
  declaring namespace — contributes the compile-time property classification
  for `tag`, owned by `ns-sym`."
  [ns-sym tag properties]
  (binding [*ns* (create-ns ns-sym)]
    (compiler/custom-element* (with-meta (list 'custom-element tag
                                               {:properties properties})
                                         {:line 1})
                              {} tag {:properties properties})))

(defn- register-root!
  "Index one root site owned by `ns-sym` the way `v/render-static` does — the
  ONLY live Freehand entry point that indexes a root site (the client mount
  verbs are runtime fns). `file` feeds the error coords."
  [ns-sym file root-id provenance]
  (root/register-root-site! 'v/render-static root-id provenance ns-sym
                            {:file file :line 1}))

(defn- snapshot
  "The observable state of the build registries a live Freehand writer feeds:
  views (`defview`), the Layer-1 root-site index (`v/render-static`), and the
  custom-element property classification (`v/custom-element`)."
  []
  {:views    (build/aggregate build/views)
   :roots    (root/build-roots)
   :elements (build/aggregate build/elements)})

;; ---------------------------------------------------------------------------
;; The pure-transition model itself (re-frame.freehand.compiler.build)
;; ---------------------------------------------------------------------------

(deftest per-source-contribution-replaces-atomically
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :x 1)
  (build/contribute! ::probe 'ns.a :y 2)
  (is (= {:x 1 :y 2} (build/aggregate ::probe :app))
      "multiple declarations in one source accumulate — never clear-on-first-macro")
  (build/commit-build! :app)
  (build/begin-build! :app)                      ; a fresh pass
  (build/contribute! ::probe 'ns.a :x 10)        ; the source is edited: :y removed
  (is (= {:x 10} (build/aggregate ::probe :app))
      "recompiling a source replaces its WHOLE prior contribution — :y is gone"))

(deftest open-source-evicts-across-every-registry
  ;; a source contributing to TWO registries, then re-declaring in only one,
  ;; must drop its stale rows from the registry it no longer touches.
  (build/begin-build! :app)
  (build/contribute! ::probe  'ns.a :p 1)
  (build/contribute! ::probe2 'ns.a :q 2)
  (is (= {:p 1} (build/aggregate ::probe :app)))
  (is (= {:q 2} (build/aggregate ::probe2 :app)))
  (build/commit-build! :app)
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :p 11)        ; ns.a re-declares only in ::probe
  (is (= {:p 11} (build/aggregate ::probe :app)))
  (is (= {} (build/aggregate ::probe2 :app))
      "re-touching ns.a evicted its ::probe2 row even though it did not re-touch it")
  (build/commit-build! :app)
  (is (= {:p 11} (build/aggregate ::probe :app)))
  (is (= {} (build/aggregate ::probe2 :app)) "the eviction survives commit"))

(deftest incremental-commit-preserves-untouched-sources
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :a 1)
  (build/contribute! ::probe 'ns.b :b 2)
  (is (= {:a 1 :b 2} (build/aggregate ::probe :app)))
  (build/commit-build! :app)
  (build/begin-build! :app)                       ; incremental: only ns.a recompiles
  (build/contribute! ::probe 'ns.a :a 11)
  (is (= {:a 11 :b 2} (build/aggregate ::probe :app))
      "an incremental pass recompiles a SUBSET — ns.b's row is untouched")
  (build/commit-build! :app)
  (is (= {:a 11 :b 2} (build/aggregate ::probe :app))
      "an incremental commit keeps the untouched source"))

(deftest whole-build-reconcile-drops-deleted-sources
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :a 1)
  (build/contribute! ::probe 'ns.b :b 2)
  (build/commit-build! :app)
  (build/begin-build! :app)                       ; WHOLE-build pass: ns.b is gone
  (build/contribute! ::probe 'ns.a :a 11)
  (build/reconcile! :app)
  (is (= {:a 11} (build/aggregate ::probe :app))
      "a whole-build reconcile drops ns.b — a deleted/renamed FILE that did not re-declare"))

(deftest finish-build-uses-authoritative-membership
  ;; The shadow-hook primitive: commit + drop committed sources absent from
  ;; the authoritative :build-sources — safe on EVERY pass (macro silence is
  ;; never the deletion signal).
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :a 1)
  (build/contribute! ::probe 'ns.b :b 2)
  (build/finish-build! :app '#{ns.a ns.b})
  (is (= {:a 1 :b 2} (build/aggregate ::probe :app)) "both members retained")
  ;; incremental pass recompiles ONLY ns.a; ns.b is untouched but still a member
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :a 11)
  (build/finish-build! :app '#{ns.a ns.b})
  (is (= {:a 11 :b 2} (build/aggregate ::probe :app))
      "incremental finish keeps the untouched MEMBER ns.b — silence is not deletion")
  ;; ns.b's file is deleted → authoritative membership drops it
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :a 111)
  (build/finish-build! :app '#{ns.a})
  (is (= {:a 111} (build/aggregate ::probe :app))
      "the deleted ns.b is evicted by authoritative membership, on any pass"))

(deftest aborted-pass-publishes-last-known-good
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :good 1)
  (build/commit-build! :app)
  (is (= {:good 1} (build/aggregate ::probe :app)))
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :bad 2)         ; a doomed pass
  (build/contribute! ::probe 'ns.b :partial 3)
  (is (= {:bad 2 :partial 3} (build/aggregate ::probe :app))
      "the open pass's staging is visible mid-pass")
  (build/abort-build! :app)                        ; compile failed → skip commit
  (is (= {:good 1} (build/aggregate ::probe :app))
      "abort discards the partial staging and republishes last-known-good"))

(deftest begin-discards-a-failed-passes-staging
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :ok 1)
  (build/commit-build! :app)
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :doomed 2)      ; compile throws — no commit
  (build/begin-build! :app)                        ; shadow ran no :compile-finish
  (build/contribute! ::probe 'ns.a :retry 1)
  (build/commit-build! :app)
  (is (= {:retry 1} (build/aggregate ::probe :app))
      "the failed pass's staging was discarded at the next begin; the retry converges"))

(deftest repl-eval-upserts-committed-without-a-pass
  ;; No begin-build! → no pass → each contribution UPSERTS one key straight
  ;; into committed, with NO sibling eviction (the RULED REPL posture).
  (build/contribute! ::probe 'ns.a :x 1)
  (build/contribute! ::probe 'ns.a :y 2)
  (is (= {:x 1 :y 2} (build/aggregate ::probe)) "both keys upserted (no pass, ::default build)")
  (build/contribute! ::probe 'ns.a :x 10)          ; a bare REPL re-eval of one form
  (is (= {:x 10 :y 2} (build/aggregate ::probe))
      "a REPL re-eval upserts the one key — :y is NOT evicted (no begin/commit cycle)")
  (is (false? (build/pass-open?)) "no pass was ever opened"))

(deftest hook-open-shadow-compile-without-build-id-fails-loud
  ;; Exact shadow-contract mutation: keep the bridge state that marks a real
  ;; shadow compiler env, but remove :shadow.build/build-id as a shadow upgrade
  ;; could. A hook-opened pass makes session fallback actively unsafe: another
  ;; build may have opened most recently, so this contribution must stop here.
  (build/begin-build! :node-test-ui)
  (binding [cljs-env/*compiler*
            (atom {:shadow.build.cljs-bridge/state {}})]
    (let [ex (try
               (build/current-build-id)
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex)
          "a shadow compiler env with no build id must not use session-build")
      (is (= :re-frame.freehand.compiler.build/shadow-build-id-unresolved
             (:re-frame.freehand.compiler.build/error (ex-data ex)))))))

(deftest hook-open-plain-cljs-compiler-uses-session-fallback
  ;; A bound cljs compiler atom is not necessarily shadow. An unrelated/open
  ;; shadow slice must not turn the documented plain-compiler/REPL fallback
  ;; into an error when THIS env carries no shadow bridge marker at all.
  (build/begin-build! :plain-cljs)
  (binding [cljs-env/*compiler* (atom {:cljs.analyzer/namespaces {}})]
    (is (= :plain-cljs (build/current-build-id))
        "a non-shadow compiler env uses the intended session build fallback")))

;; ---------------------------------------------------------------------------
;; Shadow OUTER-marker drift (rf2-vxgfnd.192): compiler authority must follow
;; re-frame.freehand's OWN private build carrier, not Shadow's implementation-detail
;; outer `:shadow.build.cljs-bridge/state` bridge key. A Shadow rename/removal
;; of that outer key while the private carrier stays intact must NOT downgrade
;; identity, reads, or writes to the process/session fallback.
;; ---------------------------------------------------------------------------

(defn- drifted-env
  "A compiler-env atom carrying re-frame.freehand's private accepted + scratch carrier
  for `build-id` but WITHOUT Shadow's outer bridge marker — the exact state a
  Shadow rename/removal of that outer key produces while re-frame.freehand's own
  carrier stays intact."
  [build-id members]
  (atom (:compiler-env (build/prepare-shadow-build {} build-id (set members)
                                                   (set members)))))

(deftest drifted-outer-marker-routes-identity-through-private-carrier
  ;; session-build names a DIFFERENT build, so a downgrade to the session
  ;; fallback would return the wrong id.
  (build/begin-build! :session-other)
  (binding [cljs-env/*compiler* (drifted-env :app-a '#{app.a})]
    (is (= :app-a (build/current-build-id))
        "the private accepted carrier is authoritative once the outer marker drifts")))

(deftest drifted-private-carrier-with-missing-build-id-fails-loud
  (binding [cljs-env/*compiler*
            (atom {build/accepted-snapshot-key {:registries {} :version 0}})]
    (let [ex (try (build/current-build-id) nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex) "a private carrier missing its build id must not downgrade")
      (is (= :re-frame.freehand.compiler.build/private-build-id-unresolved
             (:re-frame.freehand.compiler.build/error (ex-data ex)))))))

(deftest private-carrier-disagreeing-with-present-shadow-id-fails-loud
  (binding [cljs-env/*compiler*
            (atom {build/accepted-snapshot-key {:build-id :app-a :registries {}
                                                :version 1}
                   :shadow.build.cljs-bridge/state {:shadow.build/build-id :app-b}})]
    (let [ex (try (build/current-build-id) nil
                  (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? ex) "a private/shadow build-id disagreement must fail, not guess")
      (is (= :re-frame.freehand.compiler.build/private-build-id-shadow-disagreement
             (:re-frame.freehand.compiler.build/error (ex-data ex)))))))

(deftest canonical-shadow-marker-still-resolves-to-the-private-build-id
  ;; The current (non-drifted) path: bridge marker present with an AGREEING id.
  (binding [cljs-env/*compiler*
            (atom {build/accepted-snapshot-key {:build-id :app-a :registries {}
                                                :version 1}
                   :shadow.build.cljs-bridge/state {:shadow.build/build-id :app-a}})]
    (is (= :app-a (build/current-build-id))
        "canonical marker + agreeing private id resolves to that id")))

(deftest drifted-outer-marker-keeps-interleaved-builds-isolated
  ;; The headline regression: two builds A and B, each drifted, interleaved.
  ;; Rows must not cross and the session/global fallback must stay empty.
  (build/begin-build! :session-global)
  (let [env-a (drifted-env :A '#{app.a})
        env-b (drifted-env :B '#{app.b})]
    (binding [cljs-env/*compiler* env-a]
      (build/contribute! build/views 'app.a :a/view ["tfa" "hsa"]))
    (binding [cljs-env/*compiler* env-b]
      (build/contribute! build/views 'app.b :b/view ["tfb" "hsb"]))
    (binding [cljs-env/*compiler* env-a]
      (build/contribute! build/views 'app.a2 :a/view2 ["tfa2" "hsa2"]))
    (binding [cljs-env/*compiler* env-a]
      (is (= #{:a/view :a/view2} (set (keys (build/aggregate build/views))))
          "build A sees only its own rows"))
    (binding [cljs-env/*compiler* env-b]
      (is (= #{:b/view} (set (keys (build/aggregate build/views))))
          "build B sees only its own rows"))
    (is (= {} (build/aggregate build/views :session-global))
        "the interleaved drifted writes never touched the session/global fallback")))

(deftest private-resolution-distinct-from-a-same-valued-session-fallback
  ;; The private carrier's build-id EQUALS the session-build value, yet routing
  ;; must read the PRIVATE carrier — a test comparing only ids would pass wrongly.
  (build/begin-build! :app)
  (build/contribute! build/views 'fallback.ns :fallback/row ["tf-fb" "hs-fb"])
  (build/commit-build! :app)
  (binding [cljs-env/*compiler* (drifted-env :app '#{app.a})]
    (build/contribute! build/views 'app.a :app/row ["tf-app" "hs-app"])
    (is (= #{:app/row} (set (keys (build/aggregate build/views))))
        "reads the private carrier's rows, not the coincidentally same-keyed session slice"))
  (is (= #{:fallback/row} (set (keys (build/aggregate build/views :app))))
      "the same-keyed session slice is untouched by the private-carrier writes"))

;; ---------------------------------------------------------------------------
;; Carrier identity is enforced at EVERY read, write and finish boundary
;; (rf2-qbjdu). #5970 made `current-build-id` fail-closed on a private carrier
;; that is missing its id or disagrees with a still-present Shadow bridge, but
;; the registry paths did not traverse that check: ambient reads, `contribute!`,
;; and `shadow-finish-candidate` accepted a mismatched carrier. A carrier for
;; build :A carrying a still-present Shadow bridge id :B must now be rejected
;; fail-closed at each boundary — never observed, mutated, or finalized — while
;; a genuinely agreeing carrier still passes.
;; ---------------------------------------------------------------------------

(defn- ab-carrier
  "A compiler-env atom carrying re-frame.freehand's PRIVATE accepted+scratch carrier
  for `private-build` AND a still-present Shadow outer bridge naming a DIFFERENT
  `shadow-build` — the exact A/B contradiction the boundary guards reject."
  [private-build shadow-build members]
  (atom (assoc (:compiler-env (build/prepare-shadow-build {} private-build
                                                          (set members)
                                                          (set members)))
               :shadow.build.cljs-bridge/state
               {:shadow.build/build-id shadow-build})))

(deftest read-through-an-a-carrier-with-a-b-bridge-is-rejected
  ;; Pre-seed an :a/view row in the private accepted carrier for :app-a and
  ;; attach a still-present Shadow bridge naming :app-b. Pre-fix, every ambient
  ;; read EXPOSED the row (the ui-owned recognizer skipped identity); now each
  ;; read fails closed before observing the malformed carrier.
  (binding [cljs-env/*compiler*
            (atom {build/accepted-snapshot-key
                   {:build-id :app-a
                    :registries {'app.a {build/views {:a/view ["tfa" "hsa"]}}}
                    :version 1}
                   :shadow.build.cljs-bridge/state {:shadow.build/build-id :app-b}})]
    (doseq [[label read-fn]
            [[:aggregate          #(build/aggregate build/views)]
             [:committed-aggregate #(build/committed-aggregate build/views)]
             [:element-properties #(build/element-properties :x-el)]
             [:pass-open?         #(build/pass-open?)]]]
      (testing (name label)
        (let [ex (try (read-fn) nil (catch clojure.lang.ExceptionInfo e e))]
          (is (some? ex)
              (str (name label) " must reject the A-carrier + B-bridge read"))
          (is (= :re-frame.freehand.compiler.build/private-build-id-shadow-disagreement
                 (:re-frame.freehand.compiler.build/error (ex-data ex)))))))))

(deftest write-through-an-a-carrier-with-a-b-bridge-is-rejected-before-mutation
  ;; contribute! (the sole write boundary) must reject the mismatched carrier
  ;; before it stages/upserts anything — pre-fix it created an overlay row and
  ;; aggregate exposed it.
  (let [carrier (ab-carrier :app-a :app-b '#{app.a})]
    (binding [cljs-env/*compiler* carrier]
      (let [ex (try (build/contribute! build/views 'app.a :a/view ["tfa" "hsa"])
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex)
            "contribute! must reject a private-A / shadow-B carrier")
        (is (= :re-frame.freehand.compiler.build/private-build-id-shadow-disagreement
               (:re-frame.freehand.compiler.build/error (ex-data ex))))))
    (is (nil? (get-in @carrier [build/scratch-key :staged 'app.a]))
        "the rejected write staged nothing — fail BEFORE mutation")))

(deftest read-and-write-through-a-private-carrier-missing-its-id-are-rejected
  ;; A private carrier present but with NO build id in its accepted snapshot must
  ;; reject reads and writes too, not only `current-build-id`.
  (binding [cljs-env/*compiler*
            (atom {build/accepted-snapshot-key
                   {:registries {'app.a {build/views {:a/view ["tfa" "hsa"]}}}
                    :version 0}})]        ; NO :build-id
    (let [read-ex (try (build/aggregate build/views) nil
                       (catch clojure.lang.ExceptionInfo e e))]
      (is (= :re-frame.freehand.compiler.build/private-build-id-unresolved
             (:re-frame.freehand.compiler.build/error (ex-data read-ex)))
          "aggregate must reject a private carrier missing its build id"))
    (let [write-ex (try (build/contribute! build/views 'app.a :b/view ["tfb" "hsb"])
                        nil
                        (catch clojure.lang.ExceptionInfo e e))]
      (is (= :re-frame.freehand.compiler.build/private-build-id-unresolved
             (:re-frame.freehand.compiler.build/error (ex-data write-ex)))
          "contribute! must reject the same carrier before mutation"))))

(deftest recognized-bridge-without-build-id-fails-loud-even-with-no-pass-open
  ;; The bead's third case: a recognized Shadow bridge carrying no leaf id must
  ;; NOT downgrade to the session fallback merely because no pass is marked open
  ;; — the fallback is reserved for a genuinely plain env with NEITHER marker NOR
  ;; carrier. (`hook-open-shadow-compile-without-build-id-fails-loud` covers the
  ;; pass-open variant; this covers the no-pass variant that pre-fix downgraded.)
  (binding [cljs-env/*compiler* (atom {:shadow.build.cljs-bridge/state {}})]
    (let [ex (try (build/current-build-id) nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex)
          "a recognized bridge with no id must fail even with no pass open")
      (is (= :re-frame.freehand.compiler.build/shadow-build-id-unresolved
             (:re-frame.freehand.compiler.build/error (ex-data ex)))))
    (is (thrown? clojure.lang.ExceptionInfo (build/aggregate build/views))
        "and the ambient read fails closed the same way")))

(deftest finish-with-supplied-id-contradicting-the-carrier-is-rejected
  ;; The finish boundary: pre-fix `shadow-finish-candidate` trusted its explicit
  ;; build-id argument and would stamp a :B snapshot from an :A carrier's scratch.
  ;; Both a still-present disagreeing Shadow bridge and a bare supplied-id
  ;; mismatch must now fail before a candidate is derived.
  (doseq [{:keys [label attach-bridge?]}
          [{:label "A-carrier + still-present B-bridge" :attach-bridge? true}
           {:label "A-carrier, no bridge, supplied B"    :attach-bridge? false}]]
    (testing label
      (let [prepared (build/prepare-shadow-build {} :app-a '#{app.a} '#{app.a})
            build-state (cond-> prepared
                          attach-bridge?
                          (assoc-in [:compiler-env
                                     :shadow.build.cljs-bridge/state]
                                    {:shadow.build/build-id :app-b}))
            ex (try (build/shadow-finish-candidate build-state :app-b '#{app.a})
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (is (some? ex)
            "finish must reject a supplied id contradicting the accepted carrier")
        (is (= :re-frame.freehand.compiler.build/private-build-id-shadow-disagreement
               (:re-frame.freehand.compiler.build/error (ex-data ex))))
        (is (= :app-a (:private-build-id (ex-data ex))))
        (is (= :app-b (:shadow-build-id (ex-data ex))))))))

(deftest matched-carrier-passes-reads-writes-and-finish
  ;; The control: a genuinely agreeing carrier (private :app == bridge :app) must
  ;; still pass at EVERY boundary — the guards reject only contradictions.
  (let [carrier (atom (assoc (:compiler-env
                              (build/prepare-shadow-build {} :app '#{app.a}
                                                          '#{app.a}))
                             :shadow.build.cljs-bridge/state
                             {:shadow.build/build-id :app}))]
    (binding [cljs-env/*compiler* carrier]
      (is (nil? (build/contribute! build/views 'app.a :a/view ["tfa" "hsa"]))
          "a matched carrier accepts the write")
      (is (= {:a/view ["tfa" "hsa"]} (build/aggregate build/views))
          "and the read exposes the staged row"))
    (let [finished (build/shadow-finish-candidate {:compiler-env @carrier}
                                                  :app '#{app.a})]
      (is (= :app (get-in finished [:snapshot :build-id]))
          "finish with the agreeing supplied id publishes an :app-stamped snapshot")
      (is (= {:a/view ["tfa" "hsa"]}
             (get-in finished [:snapshot :registries 'app.a build/views]))
          "carrying app.a's own committed row"))))

;; ---------------------------------------------------------------------------
;; MULTI-BUILD ISOLATION — the headline regression (rf2-df9873)
;; ---------------------------------------------------------------------------

(deftest interleaved-builds-do-not-wipe-each-others-registries
  ;; ONE JVM compiling `re-frame.freehand` for two builds. The prior model held a
  ;; single GLOBAL build-id and WIPED on switch, so opening the node-test
  ;; pass BETWEEN app's two root registrations would erase app's first root —
  ;; regressing app's own cross-file duplicate detection. Per-build slices
  ;; make the builds independent by construction.
  (build/begin-build! :app)
  (build/begin-build! :node-test)
  (binding [build/*build-id* :app]
    (root/register-root-site! 'v/render-static :page/one :authored 'app.a
                              {:file "app/a.cljs" :line 1}))
  ;; the node-test build compiles the SAME namespaces — its :page/one is a
  ;; different build's row, must not touch app's slice
  (binding [build/*build-id* :node-test]
    (root/register-root-site! 'v/render-static :page/one :authored 'ntest.x
                              {:file "app/a.cljs" :line 1}))
  (binding [build/*build-id* :app]
    (root/register-root-site! 'v/render-static :page/two :authored 'app.b
                              {:file "app/b.cljs" :line 1}))
  (is (= #{:page/one :page/two}
         (set (keys (build/aggregate build/roots :app))))
      "app keeps BOTH roots despite the interleaved node-test build (no wipe)")
  (is (= #{:page/one}
         (set (keys (build/aggregate build/roots :node-test))))
      "the node-test slice is isolated — app's rows never reach it")
  (testing "app STILL detects its own cross-namespace duplicate"
    (is (thrown? clojure.lang.ExceptionInfo
                 (binding [build/*build-id* :app]
                   (root/register-root-site! 'v/render-static :page/one :authored 'app.c
                                             {:file "app/c.cljs" :line 1})))
        "a genuine duplicate in app fires — the interleaved build did not erase :page/one")))

(deftest builds-are-isolated-per-build-id
  (build/begin-build! :app)
  (build/contribute! ::probe 'ns.a :x 1)
  (is (= {:x 1} (build/aggregate ::probe :app)))
  (build/begin-build! :node-test)
  (is (= {} (build/aggregate ::probe :node-test))
      "opening the node-test slice does NOT wipe :app")
  (build/contribute! ::probe 'ns.a :y 2)          ; ambient = session-build = :node-test
  (is (= {:y 2} (build/aggregate ::probe :node-test)))
  (is (= {:x 1} (build/aggregate ::probe :app))
      "the :app slice is untouched by the node-test build"))

;; ---------------------------------------------------------------------------
;; ATOMICITY under simulated parallel compilation
;; ---------------------------------------------------------------------------

(deftest register-root-site-is-atomic-under-parallel-writes
  ;; No lost writes: two builds × N distinct roots, hammered concurrently,
  ;; ALL land — the one-atom transition has no check-then-assoc gap that a
  ;; separate-atoms model would drop under contention.
  (build/begin-build! :app)
  (build/begin-build! :node-test)
  (let [n 150
        futs (doall
              (for [b [:app :node-test], i (range n)]
                (future
                  (binding [build/*build-id* b]
                    (root/register-root-site!
                     'v/render-static (keyword "page" (str (name b) "-" i))
                     :authored (symbol (str "ns." (name b) "." i))
                     {:file (str (name b) "-" i ".cljs") :line 1})))))]
    (run! deref futs)
    (build/commit-build! :app)
    (build/commit-build! :node-test)
    (is (= n (count (build/aggregate build/roots :app)))
        "every concurrent :app write landed — no TOCTOU drop")
    (is (= n (count (build/aggregate build/roots :node-test)))
        "every concurrent :node-test write landed, isolated from :app")))

(deftest concurrent-duplicate-root-resolves-to-exactly-one
  ;; N threads race to register the SAME root-id from distinct namespaces into
  ;; one build. The atomic transition guarantees exactly one winner and every
  ;; other thread sees the duplicate — no interleaving lets two through.
  (build/begin-build! :app)
  (let [n 40
        outcomes
        (->> (for [i (range n)]
               (future
                 (binding [build/*build-id* :app]
                   (try
                     (root/register-root-site! 'v/render-static :page/dup :authored
                                               (symbol (str "ns.dup." i))
                                               {:file (str "dup-" i ".cljs") :line 1})
                     :ok
                     (catch clojure.lang.ExceptionInfo e
                       (:rf.error/id (ex-data e)))))))
             doall
             (map deref))]
    (build/commit-build! :app)
    (is (= 1 (count (filter #{:ok} outcomes)))
        "exactly one concurrent registrant wins")
    (is (= (dec n) (count (filter #{:rf.error/duplicate-root-id} outcomes)))
        "every other thread sees the atomic duplicate")
    (is (= 1 (count (build/aggregate build/roots :app)))
        "exactly one root is committed")))

;; ---------------------------------------------------------------------------
;; Integration — clean-vs-incremental parity across views, the root-site index,
;; and custom-elements (the registries a live Freehand writer feeds)
;; ---------------------------------------------------------------------------

(defn- declare-edited-source! []
  ;; the EDITED namespace app.a: a different root and a renamed custom-element
  ;; (originally :page/shop / :x-el). The defview arm retired with the dead
  ;; compiler/defview* chain (rf2-drpa3.177) — the live view registry's
  ;; incremental==clean parity is covered end-to-end by
  ;; compiler_build_hook_jvm_test (cache-hit survival, eviction, ownership).
  (register-root!  'app.a "app/a.cljs" :page/cart :authored)
  (declare-element! 'app.a :y-el #{:label}))

(deftest incremental-edit-equals-clean-build
  ;; 1) the ORIGINAL namespace app.a, built in this JVM
  (build/begin-build! :app)
  (register-root!  'app.a "app/a.cljs" :page/shop :authored)
  (declare-element! 'app.a :x-el #{:help-text})
  (build/commit-build! :app)
  ;; 2) EDIT app.a in the SAME JVM (no restart) — incremental rebuild
  (build/begin-build! :app)
  (declare-edited-source!)
  (build/commit-build! :app)
  (let [incremental (snapshot)]
    ;; 3) a CLEAN-process build of the edited namespace (fresh authority)
    (build/reset-build!)
    (build/begin-build! :app)
    (declare-edited-source!)
    (build/commit-build! :app)
    (let [clean (snapshot)]
      (is (= clean incremental)
          "incremental output after edit/rename/delete EQUALS a clean build")
      (testing "no ghost of the pre-edit source survives"
        (is (= #{:page/cart} (set (keys (:roots incremental))))       "root :page/shop gone")
        (is (contains? (:elements incremental) :y-el))
        (is (not (contains? (:elements incremental) :x-el))          "custom-element :x-el gone")))))

(deftest repeated-rebuilds-stay-bounded
  ;; correctness across a watch session — re-declaring the same namespace N
  ;; times replaces (never grows).
  (dotimes [_ 25]
    (build/begin-build! :app)
    (register-root! 'app.a "app/a.cljs" :page/shop :authored)
    (build/commit-build! :app))
  (is (= #{:page/shop} (set (keys (root/build-roots)))) "roots bounded"))

;; ---------------------------------------------------------------------------
;; The false cross-file duplicate from a DELETED root site
;; ---------------------------------------------------------------------------

(deftest deleted-root-site-does-not-falsely-duplicate-a-later-file
  ;; original: app.a registers :page/shop
  (build/begin-build! :app)
  (register-root! 'app.a "a.cljs" :page/shop :authored)
  (build/commit-build! :app)
  ;; edit app.a: the :page/shop site is DELETED (app.a now registers :page/cart)
  (build/begin-build! :app)
  (register-root! 'app.a "a.cljs" :page/cart :authored)
  ;; app.b legitimately takes over the now-free :page/shop — the deleted
  ;; app.a site must NOT raise a false :rf.error/duplicate-root-id
  (is (nil? (register-root! 'app.b "b.cljs" :page/shop :authored))
      "a deleted root site cannot fail a later file (rf2-df9873)")
  (build/commit-build! :app)
  (is (= #{:page/cart :page/shop} (set (keys (root/build-roots))))))

(deftest genuine-current-cross-file-duplicate-still-fails
  (build/begin-build! :app)
  (register-root! 'app.a "a.cljs" :page/dup :authored)
  (let [ex (try (register-root! 'app.b "b.cljs" :page/dup :authored)
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two LIVE sites for one root-id still fail the build")
    (is (= :rf.error/duplicate-root-id (:rf.error/id (ex-data ex))))
    (is (= 2 (count (:sites (ex-data ex)))) "both current sites named with coords")))

;; ---------------------------------------------------------------------------
;; The compile-time frame-plan surface is GONE (rf2-h1ae3). The cross-FILE plan
;; registry retired with the compiled mount door, and the intra-form
;; `check-plan-set!` went with the `frame-root` / `frame-provider` analyzer arms
;; that were its only producer: neither authoring var is published on either
;; host, so no root form could carry a plan, and both lowered to a
;; `re-frame.freehand.frames/*` namespace that exists nowhere. The
;; one-frame-one-plan law lives where the plan does — `re-frame.freehand.root`'s
;; mount preflight, which reads the mount call's `:frame` opt
;; (root_preflight_dom_cljs_test FH-ROOT-004).
;; ---------------------------------------------------------------------------

(deftest a-root-form-carries-no-static-frame-plan
  (testing "the top-region scan answers views alone: there is no analyzer arm
            left that could mint a plan, so the compile tier has no plan set to
            check and cannot disagree with the runtime tier that does."
    (let [scan (root/scan-root-ast {:op :element :children []})]
      (is (= [:views] (keys scan))
          "the scan's whole answer is the top-region mounted-view roster")
      (is (nil? (resolve 're-frame.freehand.compiler.root/check-plan-set!))
          "and the intra-form plan check went with its only producer"))))

;; ---------------------------------------------------------------------------
;; Custom-element declaration removal clears stale property classification
;; (AC5, compile side — read through the per-build `build/element-properties`
;; slice, the compile-path reader that replaced the process-global mirror,
;; rf2-vxgfnd.91)
;; ---------------------------------------------------------------------------

(deftest removed-custom-element-clears-stale-property-classification
  (build/begin-build! :app)
  (declare-element! 'app.a :x-el #{:help-text})
  (build/commit-build! :app)
  (is (= #{:help-text} (build/element-properties :x-el :app))
      "the declaration classifies :help-text as a property")
  ;; edit app.a: :x-el renamed to :y-el (its declaration deleted)
  (build/begin-build! :app)
  (declare-element! 'app.a :y-el #{:label})
  (build/commit-build! :app)
  (is (= #{} (build/element-properties :x-el :app))
      "the removed :x-el no longer classifies any property (was leaking)")
  (is (= #{:label} (build/element-properties :y-el :app))
      "the current :y-el declaration classifies its property"))

;; ---------------------------------------------------------------------------
;; The cross-build interleave the per-build-isolation tests above miss: a
;; custom-element contribution in ONE build BETWEEN another build's declaration
;; and its property-classification READ. Under the former process-global mirror
;; (`build/register! build/elements rules/custom-elements` + `sync-mirror!`),
;; every contribution reset the single mirror atom to whichever build wrote
;; last, so the analyzer's classification read flipped with parallel scheduling
;; — the exact fifth registry #5770 claimed to isolate (rf2-vxgfnd.91).
;; ---------------------------------------------------------------------------

(defn- analyze-property-props
  "Drive the REAL compile-path consumer (`analyze-element-props`) for a literal
  custom-element props map under `build-id`, returning the set of props it
  lowered as PROPERTIES (vs attributes)."
  [build-id tag propmap]
  (binding [build/*build-id* build-id]
    (:property-props
     (ana/analyze-element-props
      (env/make-env {:host :clj :ns-sym 'app.test})
      {:tag tag :classes [] :id nil}
      true
      propmap))))

(deftest custom-element-classification-is-build-scoped-under-interleave
  ;; build :app declares :x-card's :model property
  (build/begin-build! :app)
  (binding [build/*build-id* :app]
    (declare-element! 'app.card :x-card #{:model}))
  ;; build :node-test then contributes a DIFFERENT table — the write that, under
  ;; the old global mirror, reset the single mirror atom to node-test's
  ;; aggregate (no :x-card), between app's declaration and app's read below
  (build/begin-build! :node-test)
  (binding [build/*build-id* :node-test]
    (declare-element! 'ntest.card :y-card #{:flavour}))
  ;; app's compile thread classifies :x-card {:model ...}: it must read APP's
  ;; slice and lower :model as a PROPERTY, deterministically — regardless of the
  ;; interleaved node-test contribution. (On the pre-fix mirror this read
  ;; returned node-test's clobbered table, lowering :model as an ATTRIBUTE.)
  (is (= #{:model} (analyze-property-props :app :x-card {:model "m"}))
      "app lowers :model as a PROPERTY from its own slice, not the mirror")
  (is (= #{} (analyze-property-props :node-test :x-card {:model "m"}))
      "node-test's slice has no :x-card — :model stays an attribute there")
  (is (= #{:flavour} (analyze-property-props :node-test :y-card {:flavour "f"}))
      "node-test reads its OWN table for its OWN tag")
  (is (= #{} (analyze-property-props :app :y-card {:flavour "f"}))
      "node-test's :y-card never leaks into app's classification")
  (testing "output is stable no matter which build wrote most recently"
    ;; a fresh node-test write (the 'last writer') cannot move app's read
    (build/begin-build! :node-test)
    (binding [build/*build-id* :node-test]
      (declare-element! 'ntest.card2 :z-card #{:zz}))
    (is (= #{:model} (analyze-property-props :app :x-card {:model "m"}))
        "app's classification is unchanged by a later node-test contribution")))
