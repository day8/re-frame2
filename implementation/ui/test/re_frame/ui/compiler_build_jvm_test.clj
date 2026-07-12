(ns re-frame.ui.compiler-build-jvm-test
  "rf2-vxgfnd.16 — the S1 compiler build registries are a PURE FUNCTION of
  the current build inputs, not of process history.

  The adversarial crux: in ONE long-lived JVM (never restarted), declare a
  source's contributions to all five build registries — a `defview`, a root
  site + static frame plan + descriptor, a `ui/custom-element` — then
  rebuild after edits / renames / deletions and assert the registries +
  `current-build-digest` equal a CLEAN-process build of the edited source.
  Before this model those registries were process-global `defonce` atoms
  that only ever `assoc`ed, so a deleted or renamed declaration lingered and
  a live file could take a FALSE cross-file duplicate-root /
  frame-payload-conflict from a deleted site.

  The mount-surface macros are client entry points (JVM-host expansion is a
  compile error), so the Layer-1 / descriptor writes are driven the way
  `re-frame.ui.compiler.root/mount-form` drives them — `mount-site!` runs the
  REAL assembly path (analyze-root + resolve-root-identity +
  register-root-site! + register-plan-site! + the `root/root-descriptor`
  contribution), NOT a hand-built descriptor, so the descriptor's
  `:build-digest` (read-time projected by `root/descriptor-index`) is
  actually exercised by the incremental-equals-clean assertion (rf2-vxgfnd.47
  — the prior hand-built descriptors carried no digest, masking the bug).
  `defview` and `ui/custom-element` ARE JVM-expandable and run their real
  macro bodies, with the source file bound so distinct 'files' are
  addressable in the ledger."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.ui.compiler :as compiler]
            [re-frame.ui.compiler.build :as build]
            [re-frame.ui.compiler.env :as env]
            [re-frame.ui.compiler.root :as root]
            [re-frame.ui.rules :as rules]))

;; Every test starts from a clean slice; correctness across a watch session
;; comes from per-source replacement on begin-build!, but tests want
;; independence, so the fixture uses the hard boundary.
(use-fixtures :each
  (fn [f] (build/reset-build!) (try (f) (finally (build/reset-build!)))))

;; ---------------------------------------------------------------------------
;; Harness — drive the five registries the way real compilation does, with a
;; controllable source-file key.
;; ---------------------------------------------------------------------------

(defn- declare-view!
  "Run the real `defview` macro body (JVM emitter path) with `file` bound as
  the source — contributes [template-fp hook-sig] under the derived view-id."
  [file vname template]
  (binding [*file* file]
    (compiler/defview* (with-meta (list 'defview vname [] template) {:line 1})
                       {} vname (list [] template))))

(defn- declare-element!
  "Run the real `ui/custom-element` macro body with `file` bound as the
  source — contributes the compile-time property classification for `tag`."
  [file tag properties]
  (binding [*file* file]
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
  "Drive the REAL `mount-form` descriptor path for one root site in `file`:
  analyze the LITERAL root form, resolve identity, index the root-id + each
  EXTRACTED frame plan, and contribute the Root Descriptor built by the
  actual `root/root-descriptor` assembly — exactly the sequence a `ui/mount`
  expansion performs (no hand-built descriptor, no baked digest). The
  descriptor's `:build-digest` is thus a read-time projection
  (`root/descriptor-index`), so the incremental-equals-clean assertion
  exercises it (rf2-vxgfnd.47)."
  [file root-form opts]
  (binding [*file* file]
    (let [coords {:file file :line 1}
          e (env/make-env {:host :clj :ns-sym 'app.test :resolver resolver})
          {:keys [ast views plans]} (root/analyze-root e 'ui/mount root-form)
          {:keys [root-id provenance]} (root/resolve-root-identity
                                        'ui/mount opts views)]
      (root/register-root-site! 'ui/mount root-id provenance coords)
      (doseq [p plans] (root/register-plan-site! 'ui/mount p coords))
      (build/contribute! build/descriptors file root-id
                         (root/root-descriptor
                          {:root-id root-id :provenance provenance
                           :views views :plans plans :ast ast})))))

(defn- snapshot
  "The observable state of all five build registries + the build digest. The
  descriptor index is read through `root/descriptor-index` so its read-time
  `:build-digest` projection is part of what clean-vs-incremental compares."
  []
  {:views       @compiler/build-views
   :digest      (compiler/current-build-digest)
   :roots       @root/build-roots
   :plans       @root/build-plans
   :descriptors (root/descriptor-index)
   :elements    @rules/custom-elements})

;; ---------------------------------------------------------------------------
;; The build-scoped model itself (re-frame.ui.compiler.build)
;; ---------------------------------------------------------------------------

(def ^:private probe (atom {}))
(def ^:private probe2 (atom {}))
(build/register! ::probe probe)
(build/register! ::probe2 probe2)

(deftest per-source-contribution-replaces-atomically
  (build/begin-build!)
  (build/contribute! ::probe "a.cljs" :x 1)
  (build/contribute! ::probe "a.cljs" :y 2)
  (is (= {:x 1 :y 2} @probe)
      "multiple declarations in one source accumulate — never clear-on-first-macro")
  (build/begin-build!)                       ; a fresh pass
  (build/contribute! ::probe "a.cljs" :x 10) ; the source is edited: :y removed, :x changed
  (is (= {:x 10} @probe)
      "recompiling a source replaces its WHOLE prior contribution — :y is gone"))

(deftest open-source-evicts-across-every-registry
  ;; a source contributing to TWO registries, then re-declaring in only one,
  ;; must drop its stale rows from the registry it no longer touches.
  (build/begin-build!)
  (build/contribute! ::probe  "a.cljs" :p 1)
  (build/contribute! ::probe2 "a.cljs" :q 2)
  (is (= {:p 1} @probe))
  (is (= {:q 2} @probe2))
  (build/begin-build!)
  (build/contribute! ::probe "a.cljs" :p 11) ; a.cljs re-declares only in ::probe
  (is (= {:p 11} @probe))
  (is (= {} @probe2)
      "opening a.cljs evicted its ::probe2 row even though it did not re-touch ::probe2"))

(deftest incremental-preserves-untouched-reconcile-drops-deleted-files
  (build/begin-build!)
  (build/contribute! ::probe "a.cljs" :a 1)
  (build/contribute! ::probe "b.cljs" :b 2)
  (is (= {:a 1 :b 2} @probe))
  (build/begin-build!)                       ; incremental: only a.cljs recompiles
  (build/contribute! ::probe "a.cljs" :a 11)
  (is (= {:a 11 :b 2} @probe)
      "an incremental pass recompiles a SUBSET — b.cljs's row is untouched")
  (build/reconcile!)
  (is (= {:a 11} @probe)
      "a whole-build reconcile drops b.cljs — a deleted/renamed FILE that did not re-declare"))

(deftest build-ids-are-isolated
  (build/begin-build! :app)
  (build/contribute! ::probe "a.cljs" :x 1)
  (is (= {:x 1} @probe))
  (build/begin-build! :node-test)
  (is (= {} @probe) "switching build-id starts an isolated (wiped) slice")
  (build/contribute! ::probe "a.cljs" :y 2)
  (is (= {:y 2} @probe) "the :app rows never reach the :node-test build"))

;; ---------------------------------------------------------------------------
;; Integration — clean-vs-incremental parity across all five registries
;; ---------------------------------------------------------------------------

(defn- declare-edited-source!
  "The EDITED file app/a.cljs: a renamed view, a different root + frame plan,
  a renamed custom-element (the original declared `foo` / :page/shop / :shop
  / :x-el)."
  []
  (declare-view! "app/a.cljs" 'bar [:section "bar"])
  (mount-site!   "app/a.cljs"
                 '[frame-root {:id :cart :initial-events [[:cart/boot]]}
                   [app-view {:promo :winter}]]
                 {:root-id :page/cart})
  (declare-element! "app/a.cljs" :y-el #{:label}))

(deftest incremental-edit-equals-clean-build
  ;; 1) the ORIGINAL source A, built in this JVM
  (build/begin-build! :app)
  (declare-view! "app/a.cljs" 'foo [:div "foo"])
  (mount-site!   "app/a.cljs"
                 '[frame-root {:id :shop :initial-events [[:shop/boot]]}
                   [app-view {:promo :spring}]]
                 {:root-id :page/shop})
  (declare-element! "app/a.cljs" :x-el #{:help-text})
  ;; 2) EDIT the same file in the SAME JVM (no restart) — incremental rebuild
  (build/begin-build! :app)
  (declare-edited-source!)
  (let [incremental (snapshot)]
    ;; 3) a CLEAN-process build of the edited source (fresh slice)
    (build/reset-build!)
    (build/begin-build! :app)
    (declare-edited-source!)
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
;; The prior harness hand-built digest-free descriptors, masking both.
;; ---------------------------------------------------------------------------

(deftest descriptor-digest-is-compile-order-independent-and-covers-all-views
  ;; ORDER 1: the mount site expands BEFORE the rest of the build's views
  ;; compile. Baked-at-expansion would freeze its descriptor digest over only
  ;; a-view; the read-time projection reads the WHOLE-build digest.
  (build/begin-build! :app)
  (declare-view! "app/a.cljs" 'a-view [:div "a"])
  (mount-site!   "app/m.cljs" '[app-view {}] {:root-id :page/m})
  (declare-view! "app/b.cljs" 'b-view [:section "b"])
  (let [digest-1 (get-in (root/descriptor-index) [:page/m :build-digest])]
    (is (= (compiler/current-build-digest) digest-1)
        "the descriptor digest reflects ALL build views (a-view AND b-view),
         not only those compiled before the mount site expanded")
    ;; ORDER 2: same two views, but the mount site expands LAST.
    (build/reset-build!)
    (build/begin-build! :app)
    (declare-view! "app/b.cljs" 'b-view [:section "b"])
    (declare-view! "app/a.cljs" 'a-view [:div "a"])
    (mount-site!   "app/m.cljs" '[app-view {}] {:root-id :page/m})
    (is (= digest-1 (get-in (root/descriptor-index) [:page/m :build-digest]))
        "same whole-build digest whether the mount site expands first or last
         (compile-order independent — .16 AC7)")))

(deftest descriptor-digest-tracks-an-incremental-view-only-edit
  ;; A mount site in m.cljs + a view in v.cljs.
  (build/begin-build! :app)
  (mount-site!   "app/m.cljs" '[app-view {}] {:root-id :page/m})
  (declare-view! "app/v.cljs" 'v-view [:div "one"])
  (let [d1 (get-in (root/descriptor-index) [:page/m :build-digest])]
    (is (= (compiler/current-build-digest) d1))
    ;; INCREMENTAL pass: recompile ONLY the view file (its template changes).
    ;; The mount site is NOT re-expanded — under baked-at-expansion its stored
    ;; descriptor would keep the OLD digest; the read-time projection tracks
    ;; the new whole-build digest.
    (build/begin-build! :app)
    (declare-view! "app/v.cljs" 'v-view [:div "two"])
    (let [d2 (get-in (root/descriptor-index) [:page/m :build-digest])]
      (is (not= d1 d2)
          "the view edit changed the whole-build digest")
      (is (= (compiler/current-build-digest) d2)
          "the un-re-expanded mount-site descriptor reads the CURRENT digest,
           not a stale expansion-time snapshot (rf2-vxgfnd.47)"))))

(deftest repeated-rebuilds-stay-bounded-without-a-reset
  ;; AC6: correctness across a watch session needs NO test-only global reset —
  ;; re-declaring the same file N times replaces (never grows).
  (dotimes [_ 25]
    (build/begin-build! :app)
    (declare-view! "app/a.cljs" 'only [:div "only"])
    (mount-site! "app/a.cljs" '[app-view {}] {:root-id :page/shop}))
  (is (= 1 (count @compiler/build-views)) "views bounded across repeated rebuilds")
  (is (= #{:page/shop} (set (keys @root/build-roots))) "roots bounded")
  (is (= #{:page/shop} (set (keys @root/build-descriptors))) "descriptors bounded"))

;; ---------------------------------------------------------------------------
;; The false cross-file duplicate/conflict from a DELETED site (bead §Steps)
;; ---------------------------------------------------------------------------

(deftest deleted-root-site-does-not-falsely-duplicate-a-later-file
  ;; original: a.cljs mounts :page/shop
  (build/begin-build!)
  (root/register-root-site! 'ui/mount :page/shop :authored {:file "a.cljs" :line 1})
  ;; edit a.cljs: the :page/shop site is DELETED (a.cljs now mounts :page/cart)
  (build/begin-build!)
  (root/register-root-site! 'ui/mount :page/cart :authored {:file "a.cljs" :line 1})
  ;; b.cljs legitimately takes over the now-free :page/shop — the deleted
  ;; a.cljs site must NOT raise a false :rf.error/duplicate-root-id
  (is (nil? (root/register-root-site! 'ui/mount :page/shop :authored
                                      {:file "b.cljs" :line 1}))
      "a deleted root site cannot fail a later file (rf2-vxgfnd.16)")
  (is (= #{:page/cart :page/shop} (set (keys @root/build-roots)))))

(deftest genuine-current-cross-file-duplicate-still-fails
  (build/begin-build!)
  (root/register-root-site! 'ui/mount :page/dup :authored {:file "a.cljs" :line 1})
  (let [ex (try (root/register-root-site! 'ui/mount :page/dup :authored
                                          {:file "b.cljs" :line 2})
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two LIVE sites for one root-id still fail the build")
    (is (= :rf.error/duplicate-root-id (:rf.error/id (ex-data ex))))
    (is (= 2 (count (:sites (ex-data ex)))) "both current sites named with coords")))

(deftest deleted-frame-plan-does-not-falsely-conflict-a-later-file
  ;; original: a.cljs carries a :shop plan
  (build/begin-build!)
  (root/register-plan-site! 'ui/mount {:frame-id :shop :config-fingerprint "cf1-a"}
                            {:file "a.cljs" :line 1})
  (is (contains? @root/build-plans :shop))
  ;; edit a.cljs: the :shop plan is DELETED — a.cljs now declares only a root
  ;; (it no longer touches the plan registry at all). Opening a.cljs must
  ;; still evict its stale :shop plan (cross-registry sweep).
  (build/begin-build!)
  (root/register-root-site! 'ui/mount :page/a :authored {:file "a.cljs" :line 1})
  (is (not (contains? @root/build-plans :shop))
      "opening the recompiled a.cljs evicted its dropped :shop plan")
  ;; b.cljs now carries :shop with a DIFFERENT fingerprint — no false conflict
  (is (nil? (root/register-plan-site! 'ui/mount
                                      {:frame-id :shop :config-fingerprint "cf1-b"}
                                      {:file "b.cljs" :line 1}))
      "a deleted plan cannot fail a later file's differing plan (rf2-vxgfnd.16)")
  (is (= "cf1-b" (:config-fingerprint (get @root/build-plans :shop)))))

(deftest genuine-current-cross-file-plan-conflict-still-fails
  (build/begin-build!)
  (root/register-plan-site! 'ui/mount {:frame-id :shop :config-fingerprint "cf1-a"}
                            {:file "a.cljs" :line 1})
  (let [ex (try (root/register-plan-site! 'ui/mount
                                          {:frame-id :shop :config-fingerprint "cf1-b"}
                                          {:file "b.cljs" :line 2})
                nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex) "two LIVE differing plans for one frame still fail the build")
    (is (= :rf.error/frame-payload-conflict (:rf.error/id (ex-data ex))))))

;; ---------------------------------------------------------------------------
;; Custom-element declaration removal clears stale property classification
;; (AC5, compile side)
;; ---------------------------------------------------------------------------

(deftest removed-custom-element-clears-stale-property-classification
  (build/begin-build! :app)
  (declare-element! "app/a.cljs" :x-el #{:help-text})
  (is (= #{:help-text} (rules/custom-element-properties :x-el))
      "the declaration classifies :help-text as a property")
  ;; edit a.cljs: :x-el renamed to :y-el (its declaration deleted)
  (build/begin-build! :app)
  (declare-element! "app/a.cljs" :y-el #{:label})
  (is (= #{} (rules/custom-element-properties :x-el))
      "the removed :x-el no longer classifies any property (was leaking)")
  (is (= #{:label} (rules/custom-element-properties :y-el))
      "the current :y-el declaration classifies its property"))
