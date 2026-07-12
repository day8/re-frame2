(ns re-frame.ui.compiler-build-jvm-test
  "The ONE build-scoped compiler-state authority (rf2-vxgfnd.16 → rf2-df9873):
  the S1 compiler's build registries are a PURE FUNCTION of the current build
  inputs, keyed PER shadow build id, with explicit begin/commit/abort pass
  boundaries and last-known-good publication.

  The adversarial cruces:

    - MULTI-BUILD ISOLATION (the headline regression): one long-lived JVM
      compiles `re-frame.ui` for several builds at once; two build ids must
      NOT wipe each other's registries (the prior global-build-id +
      wipe-on-switch model did — regressing cross-file duplicate detection).
    - PASS BOUNDARIES: an incremental commit replaces only the touched
      source's contribution and keeps untouched sources; a WHOLE-build
      reconcile drops sources that did not re-declare; an ABORTED pass
      republishes the last-known-good, not a partial.
    - ATOMICITY: `register-root-site!` / `register-plan-site!` check-then-write
      inside ONE transition, so parallel compilation neither drops a
      concurrent write nor misses a genuine duplicate.
    - REPL UPSERT: with no pass open a contribution upserts one key straight
      into committed (no begin/commit cycle, no sibling eviction).

  The mount-surface macros are client entry points (JVM-host expansion is a
  compile error), so the Layer-1 / descriptor writes are driven the way
  `re-frame.ui.compiler.root/mount-form` drives them — `mount-site!` runs the
  REAL assembly path. `defview` and `ui/custom-element` run their real macro
  bodies under a bound `*ns*` so distinct declaring namespaces (the ledger's
  source key, rf2-df9873) are addressable."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.analyze :as ana]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]))

;; Every test starts from a clean authority; correctness across a watch
;; session comes from per-source replacement on the pass boundaries, but
;; tests want independence, so the fixture uses the hard boundary.
(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — drive the five registries the way real compilation does, with a
;; controllable declaring namespace (the ledger source key).
;; ---------------------------------------------------------------------------

(defn- declare-view!
  "Run the real `defview` macro body (JVM emitter path) with `ns-sym` bound as
  the declaring namespace — contributes [template-fp hook-sig] under the
  derived view-id, owned by `ns-sym`."
  [ns-sym vname template]
  (binding [*ns* (create-ns ns-sym)]
    (compiler/defview* (with-meta (list 'defview vname [] template) {:line 1})
                       {} vname (list [] template))))

(defn- declare-element!
  "Run the real `ui/custom-element` macro body with `ns-sym` bound as the
  declaring namespace — contributes the compile-time property classification
  for `tag`, owned by `ns-sym`."
  [ns-sym tag properties]
  (binding [*ns* (create-ns ns-sym)]
    (compiler/custom-element* (with-meta (list 'custom-element tag
                                               {:properties properties})
                                         {:line 1})
                              {} tag {:properties properties})))

(def ^:private resolver
  "Injected Q5 resolution stub for the root-form analyzer driven by
  `mount-site!` (mirrors root-analysis-cljs-test's stub)."
  (fn [sym]
    (case sym
      frame-root {:fqn 're-frame.ui/frame-root :meta {}}
      app-view   {:fqn 'app.views/app-view
                  :meta {:rf.ui/view true :rf.ui/view-id :app.views/app-view}}
      nil)))

(defn- mount-site!
  "Drive the REAL `mount-form` descriptor path for one root site owned by
  `ns-sym` (file only feeds the error coords): analyze the LITERAL root form,
  resolve identity, index the root-id + each EXTRACTED frame plan, and
  contribute the Root Descriptor built by the actual `root/root-descriptor`
  assembly — exactly the sequence a `ui/mount` expansion performs."
  [ns-sym file root-form opts]
  (binding [*file* file]
    (let [coords {:file file :line 1}
          e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
          {:keys [ast views plans]} (root/analyze-root e 'ui/mount root-form)
          {:keys [root-id provenance]} (root/resolve-root-identity
                                        'ui/mount opts views)]
      (root/register-root-site! 'ui/mount root-id provenance ns-sym coords)
      (doseq [p plans] (root/register-plan-site! 'ui/mount p ns-sym coords))
      (build/contribute! build/descriptors ns-sym root-id
                         (root/root-descriptor
                          {:root-id root-id :provenance provenance
                           :views views :plans plans :ast ast})))))

(defn- snapshot
  "The observable state of all five build registries + the build digest for
  the ambient build. The descriptor index is read through
  `root/descriptor-index` so its read-time `:build-digest` projection is part
  of what clean-vs-incremental compares."
  []
  {:views       (build/aggregate build/views)
   :digest      (compiler/current-build-digest)
   :roots       (root/build-roots)
   :plans       (root/build-plans)
   :descriptors (root/descriptor-index)
   :elements    (build/aggregate build/elements)})

;; ---------------------------------------------------------------------------
;; The pure-transition model itself (re-frame.ui.compiler.build)
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

;; ---------------------------------------------------------------------------
;; MULTI-BUILD ISOLATION — the headline regression (rf2-df9873)
;; ---------------------------------------------------------------------------

(deftest interleaved-builds-do-not-wipe-each-others-registries
  ;; ONE JVM compiling `re-frame.ui` for two builds. The prior model held a
  ;; single GLOBAL build-id and WIPED on switch, so opening the node-test
  ;; pass BETWEEN app's two root registrations would erase app's first root —
  ;; regressing app's own cross-file duplicate detection. Per-build slices
  ;; make the builds independent by construction.
  (build/begin-build! :app)
  (build/begin-build! :node-test)
  (binding [build/*build-id* :app]
    (root/register-root-site! 'ui/mount :page/one :authored 'app.a
                              {:file "app/a.cljs" :line 1}))
  ;; the node-test build compiles the SAME namespaces — its :page/one is a
  ;; different build's row, must not touch app's slice
  (binding [build/*build-id* :node-test]
    (root/register-root-site! 'ui/mount :page/one :authored 'ntest.x
                              {:file "app/a.cljs" :line 1}))
  (binding [build/*build-id* :app]
    (root/register-root-site! 'ui/mount :page/two :authored 'app.b
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
                   (root/register-root-site! 'ui/mount :page/one :authored 'app.c
                                             {:file "app/c.cljs" :line 1})))
        "a genuine duplicate in app fires — the interleaved build did not erase :page/one")))

(deftest real-defview-contributions-are-isolated-per-build
  ;; drive the REAL defview macro body under two build ids
  (binding [build/*build-id* :app]       (declare-view! 'app.a 'foo [:div "foo"]))
  (binding [build/*build-id* :node-test] (declare-view! 'app.a 'bar [:section "bar"]))
  (is (contains? (build/aggregate build/views :app) :app.a/foo)
      "the app build keeps its view")
  (is (contains? (build/aggregate build/views :node-test) :app.a/bar)
      "the node-test build keeps its view")
  (is (not (contains? (build/aggregate build/views :app) :app.a/bar))
      "the node-test build's view never reaches the app slice")
  (is (not (contains? (build/aggregate build/views :node-test) :app.a/foo))
      "and vice versa"))

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
                     'ui/mount (keyword "page" (str (name b) "-" i))
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
                     (root/register-root-site! 'ui/mount :page/dup :authored
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
;; Integration — clean-vs-incremental parity across all five registries
;; ---------------------------------------------------------------------------

(defn- declare-edited-source! []
  ;; the EDITED namespace app.a: a renamed view, a different root + frame
  ;; plan, a renamed custom-element (originally foo / :page/shop / :shop / :x-el)
  (declare-view!   'app.a 'bar [:section "bar"])
  (mount-site!     'app.a "app/a.cljs"
                   '[frame-root {:id :cart :initial-events [[:cart/boot]]}
                     [app-view {:promo :winter}]]
                   {:root-id :page/cart})
  (declare-element! 'app.a :y-el #{:label}))

(deftest incremental-edit-equals-clean-build
  ;; 1) the ORIGINAL namespace app.a, built in this JVM
  (build/begin-build! :app)
  (declare-view!   'app.a 'foo [:div "foo"])
  (mount-site!     'app.a "app/a.cljs"
                   '[frame-root {:id :shop :initial-events [[:shop/boot]]}
                     [app-view {:promo :spring}]]
                   {:root-id :page/shop})
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
        (is (= #{:cart} (set (keys (:plans incremental))))            "plan :shop gone")
        (is (= #{:page/cart} (set (keys (:descriptors incremental)))) "descriptor :page/shop gone")
        (is (contains? (:elements incremental) :y-el))
        (is (not (contains? (:elements incremental) :x-el))          "custom-element :x-el gone")
        (is (= 1 (count (:views incremental)))                        "view foo gone, only bar"))
      (testing "the digest is a function of current views only"
        (is (= (:digest clean) (:digest incremental)))))))

;; ---------------------------------------------------------------------------
;; The descriptor :build-digest is a READ-TIME projection (rf2-vxgfnd.47) —
;; compile-order independent + never stale under an incremental view edit.
;; ---------------------------------------------------------------------------

(deftest descriptor-digest-is-compile-order-independent-and-covers-all-views
  ;; ORDER 1: the mount site expands BEFORE the rest of the build's views
  (build/begin-build! :app)
  (declare-view! 'app.a 'a-view [:div "a"])
  (mount-site!   'app.m "app/m.cljs" '[app-view {}] {:root-id :page/m})
  (declare-view! 'app.b 'b-view [:section "b"])
  (build/commit-build! :app)
  (let [digest-1 (get-in (root/descriptor-index) [:page/m :build-digest])]
    (is (= (compiler/current-build-digest) digest-1)
        "the descriptor digest reflects ALL build views (a-view AND b-view)")
    ;; ORDER 2: same two views, but the mount site expands LAST
    (build/reset-build!)
    (build/begin-build! :app)
    (declare-view! 'app.b 'b-view [:section "b"])
    (declare-view! 'app.a 'a-view [:div "a"])
    (mount-site!   'app.m "app/m.cljs" '[app-view {}] {:root-id :page/m})
    (build/commit-build! :app)
    (is (= digest-1 (get-in (root/descriptor-index) [:page/m :build-digest]))
        "same whole-build digest whether the mount site expands first or last")))

(deftest descriptor-digest-tracks-an-incremental-view-only-edit
  (build/begin-build! :app)
  (mount-site!   'app.m "app/m.cljs" '[app-view {}] {:root-id :page/m})
  (declare-view! 'app.v 'v-view [:div "one"])
  (build/commit-build! :app)
  (let [d1 (get-in (root/descriptor-index) [:page/m :build-digest])]
    (is (= (compiler/current-build-digest) d1))
    ;; INCREMENTAL pass: recompile ONLY the view file. The mount site is NOT
    ;; re-expanded — under baked-at-expansion its descriptor would keep the OLD
    ;; digest; the read-time projection tracks the new whole-build digest.
    (build/begin-build! :app)
    (declare-view! 'app.v 'v-view [:div "two"])
    (build/commit-build! :app)
    (let [d2 (get-in (root/descriptor-index) [:page/m :build-digest])]
      (is (not= d1 d2) "the view edit changed the whole-build digest")
      (is (= (compiler/current-build-digest) d2)
          "the un-re-expanded mount-site descriptor reads the CURRENT digest (rf2-vxgfnd.47)"))))

(deftest descriptor-digest-is-finalized-at-the-commit-boundary
  ;; rf2-vxgfnd.68: the published digest reads the COMMITTED (last-known-good)
  ;; view aggregate, so a read DURING an open / incomplete pass returns the last
  ;; finalized identity — never a partial mid-pass mix of the staged edit. The
  ;; digest advances only when the pass COMMITS.
  (build/begin-build! :app)
  (mount-site!   'app.m "app/m.cljs" '[app-view {}] {:root-id :page/m})
  (declare-view! 'app.v 'v-view [:div "one"])
  (build/commit-build! :app)
  (let [committed-1 (get-in (root/descriptor-index) [:page/m :build-digest])]
    ;; open an incremental pass and STAGE a view edit — do NOT commit yet.
    (build/begin-build! :app)
    (declare-view! 'app.v 'v-view [:div "two"])
    (is (= committed-1 (get-in (root/descriptor-index) [:page/m :build-digest]))
        "mid-pass read holds the last-known-good digest — the staged edit is not published")
    (is (= committed-1 (compiler/current-build-digest))
        "current-build-digest is the finalized (committed) identity, not the staged view")
    ;; commit: NOW the finalized identity advances.
    (build/commit-build! :app)
    (is (not= committed-1 (get-in (root/descriptor-index) [:page/m :build-digest]))
        "the digest advances only at the successful commit boundary")))

(deftest repeated-rebuilds-stay-bounded
  ;; correctness across a watch session — re-declaring the same namespace N
  ;; times replaces (never grows).
  (dotimes [_ 25]
    (build/begin-build! :app)
    (declare-view! 'app.a 'only [:div "only"])
    (mount-site!   'app.a "app/a.cljs" '[app-view {}] {:root-id :page/shop})
    (build/commit-build! :app))
  (is (= 1 (count (build/aggregate build/views :app))) "views bounded across repeated rebuilds")
  (is (= #{:page/shop} (set (keys (root/build-roots)))) "roots bounded")
  (is (= #{:page/shop} (set (keys (build/aggregate build/descriptors :app)))) "descriptors bounded"))

;; ---------------------------------------------------------------------------
;; The false cross-file duplicate/conflict from a DELETED site
;; ---------------------------------------------------------------------------

(deftest deleted-root-site-does-not-falsely-duplicate-a-later-file
  ;; original: app.a mounts :page/shop
  (build/begin-build! :app)
  (root/register-root-site! 'ui/mount :page/shop :authored 'app.a {:file "a.cljs" :line 1})
  (build/commit-build! :app)
  ;; edit app.a: the :page/shop site is DELETED (app.a now mounts :page/cart)
  (build/begin-build! :app)
  (root/register-root-site! 'ui/mount :page/cart :authored 'app.a {:file "a.cljs" :line 1})
  ;; app.b legitimately takes over the now-free :page/shop — the deleted
  ;; app.a site must NOT raise a false :rf.error/duplicate-root-id
  (is (nil? (root/register-root-site! 'ui/mount :page/shop :authored 'app.b
                                      {:file "b.cljs" :line 1}))
      "a deleted root site cannot fail a later file (rf2-df9873)")
  (build/commit-build! :app)
  (is (= #{:page/cart :page/shop} (set (keys (root/build-roots))))))

(deftest genuine-current-cross-file-duplicate-still-fails
  (build/begin-build! :app)
  (root/register-root-site! 'ui/mount :page/dup :authored 'app.a {:file "a.cljs" :line 1})
  (let [ex (try (root/register-root-site! 'ui/mount :page/dup :authored 'app.b
                                          {:file "b.cljs" :line 2})
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two LIVE sites for one root-id still fail the build")
    (is (= :rf.error/duplicate-root-id (:rf.error/id (ex-data ex))))
    (is (= 2 (count (:sites (ex-data ex)))) "both current sites named with coords")))

(deftest deleted-frame-plan-does-not-falsely-conflict-a-later-file
  ;; original: app.a carries a :shop plan
  (build/begin-build! :app)
  (root/register-plan-site! 'ui/mount {:frame-id :shop :config-fingerprint "cf1-a"}
                            'app.a {:file "a.cljs" :line 1})
  (is (contains? (root/build-plans) :shop))
  (build/commit-build! :app)
  ;; edit app.a: the :shop plan is DELETED — app.a now declares only a root
  ;; (it no longer touches the plan registry at all). Re-touching app.a must
  ;; still evict its stale :shop plan (cross-registry sweep at commit).
  (build/begin-build! :app)
  (root/register-root-site! 'ui/mount :page/a :authored 'app.a {:file "a.cljs" :line 1})
  (is (not (contains? (root/build-plans) :shop))
      "re-touching the recompiled app.a evicted its dropped :shop plan")
  ;; app.b now carries :shop with a DIFFERENT fingerprint — no false conflict
  (is (nil? (root/register-plan-site! 'ui/mount
                                      {:frame-id :shop :config-fingerprint "cf1-b"}
                                      'app.b {:file "b.cljs" :line 1}))
      "a deleted plan cannot fail a later file's differing plan (rf2-df9873)")
  (build/commit-build! :app)
  (is (= "cf1-b" (:config-fingerprint (get (root/build-plans) :shop)))))

(deftest genuine-current-cross-file-plan-conflict-still-fails
  (build/begin-build! :app)
  (root/register-plan-site! 'ui/mount {:frame-id :shop :config-fingerprint "cf1-a"}
                            'app.a {:file "a.cljs" :line 1})
  (let [ex (try (root/register-plan-site! 'ui/mount
                                          {:frame-id :shop :config-fingerprint "cf1-b"}
                                          'app.b {:file "b.cljs" :line 2})
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two LIVE differing plans for one frame still fail the build")
    (is (= :rf.error/frame-payload-conflict (:rf.error/id (ex-data ex))))))

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
