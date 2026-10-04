(ns re-frame.story-test
  "JVM tests for the re-frame2-story registration surface.

  Covers:

  - Macro expansion → registry write round-trip.
  - Body shape validation (`:rf.error/<kind>-shape`).
  - Tag membership (`:rf.error/unknown-tag`).
  - `:extends` raw storage at registration (the plan compiler is the
    merge authority, so unknown-parent, cycle and depth-cap detection
    live with it in `re-frame.story.plan-cljs-test`).
  - Form-B `:variants` desugaring.
  - Source-coord stamping.
  - Query API (`registrations`, `handler-meta`, `variants-with-tags`,
    `variants-of`).
  - EDN-round-trip of variant bodies (no fn-valued slots).
  - Canonical-id-grammar enforcement.

  JVM-runnable because the registration surface is pure data — no
  Reagent / DOM / shadow-cljs required. Per `001-Authoring.md`
  §Registration macros, every artefact that can run on the JVM
  should."
  (:require [clojure.test :refer [are deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.canonical :as rf.story.canonical]
            [re-frame.story.config :as rf.story.config]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ---- canonical-vocabulary install ---------------------------------------

(deftest variant-id-shape-string-grammar
  ;; The STRING-level variant-id grammar that the MCP write
  ;; paths validate against BEFORE interning. `variant-id?` delegates here,
  ;; so the keyword-level and string-level checks cannot drift.
  (testing "variant-id-shape? accepts a canonical :story.<path>/<name> decomposition"
    (is (true? (rf.story.schemas/variant-id-shape? ["story.button" "primary"])))
    (is (true? (rf.story.schemas/variant-id-shape? ["story" "primary"]))))
  (testing "variant-id-shape? rejects a non-story namespace, a bare name, and an empty name"
    (is (false? (rf.story.schemas/variant-id-shape? ["not-story" "primary"])))
    (is (false? (rf.story.schemas/variant-id-shape? [nil "primary"])))
    (is (false? (rf.story.schemas/variant-id-shape? ["story.button" ""]))))
  (testing "variant-id? delegates to the string-shape check — they cannot drift"
    (is (true?  (boolean (rf.story.schemas/variant-id? :story.button/primary))))
    (is (false? (boolean (rf.story.schemas/variant-id? :not-story/primary))))
    (is (= (rf.story.schemas/variant-id? :story.button/primary)
           (rf.story.schemas/variant-id-shape? ["story.button" "primary"])))))

(deftest canonical-state-axis-installed
  (testing "the five :state/* tags carry the :state axis"
    (let [by-axis (rf.story/tags-by-axis :state)]
      (is (= rf.story.schemas/canonical-state-tags by-axis)))))

;; ---- auto-install on first reg-* call ----------------------------------
;;
;; The canonical vocabulary auto-installs on the first `reg-*` runtime
;; call so authors don't need a separate `(rf.story/install-canonical-vocabulary!)`
;; boot step. Spec: tools/story/spec/001-Authoring.md §Boot — auto-install
;; of the canonical vocabulary.
;;
;; These tests deliberately call `clear-all!` to wipe the registrar +
;; the auto-install gate so the first `reg-*` below is genuinely the
;; first one in this generation.

(deftest auto-install-fires-on-first-reg-story
  (testing "the first reg-story after clear-all! auto-installs the canonical vocabulary"
    (rf.story/clear-all!)
    (is (false? @rf.story.canonical/installed?) "the gate is reset by clear-all!")
    (is (empty? (rf.story/list-tags))     "the side-table is wiped")
    ;; The story body uses :tags #{:dev :docs} — without auto-install
    ;; this would raise :rf.error/unknown-tag. With auto-install, the
    ;; canonical tags are registered on the first reg-story call.
    (rf.story/reg-story :story.auto-install.probe
      {:doc  "Auto-install probe."
       :tags #{:dev :docs}})
    (is (true? @rf.story.canonical/installed?) "the gate flips true after auto-install")
    (is (= (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
           (rf.story/list-tags))
        "all seven canonical inclusion tags + five :state/* magnitude tags are registered post-auto-install")
    (is (rf.story/registered? :story :story.auto-install.probe))))

(deftest auto-install-fires-on-first-reg-variant
  (testing "the first reg-variant after clear-all! also triggers auto-install"
    (rf.story/clear-all!)
    (rf.story/reg-variant :story.auto-install/v
      {:setup []
       :tags   #{:dev}})
    (is (true? @rf.story.canonical/installed?))
    (is (rf.story/registered? :variant :story.auto-install/v))
    (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-tags))))

(deftest auto-install-fires-on-first-reg-tag
  (testing "the first reg-tag (project-tag) after clear-all! also triggers auto-install"
    (rf.story/clear-all!)
    ;; A project tag — registering it should ALSO install the canonical
    ;; seven first, so subsequent variants tagged `:dev` validate.
    (rf.story/reg-tag :auth/regression-set {:doc "Auth regression-suite."})
    (is (rf.story/registered? :tag :auth/regression-set))
    (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-tags)
        "canonical tags ride along with the first reg-tag too")))

(defn- counting-installs
  "Run `f` with `rf.story.canonical/install!` wrapped to count its calls,
  and return that count. Every installer is idempotent, so a re-run of the
  chain leaves the registry unchanged: counting the calls is the only way
  to see one."
  [f]
  (let [calls    (atom 0)
        install! rf.story.canonical/install!]
    (with-redefs [rf.story.canonical/install! (fn [] (swap! calls inc) (install!))]
      (f))
    @calls))

(deftest auto-install-is-idempotent
  (testing "subsequent reg-* calls do NOT re-trigger the installer chain"
    (rf.story/clear-all!)
    (rf.story/reg-story :story.idem.a {:tags #{:dev}})
    (let [tags-after-first (rf.story/list-tags)]
      (is (zero? (counting-installs
                   #(do (rf.story/reg-story :story.idem.b {:tags #{:docs}})
                        (rf.story/reg-variant :story.idem.a/v {:tags #{:dev} :setup []}))))
          "the installer chain does not run again after the first reg-*")
      (is (= tags-after-first (rf.story/list-tags))
          "canonical tag set is stable across subsequent reg-* calls"))))

(deftest explicit-install-after-auto-install-is-noop
  (testing "calling install-canonical-vocabulary! explicitly after auto-install fired is a no-op"
    (rf.story/clear-all!)
    (rf.story/reg-story :story.explicit.probe {:tags #{:dev}})
    (let [tags-after-auto-install (rf.story/list-tags)
          decorators-after        (rf.story/ids :decorator)]
      ;; Explicit call lands on the already-true gate; install! flips
      ;; it true (already true) and re-runs the installer chain. Every
      ;; installer is documented idempotent, so the side-table snapshot
      ;; should be unchanged.
      (rf.story/install-canonical-vocabulary!)
      (is (= tags-after-auto-install (rf.story/list-tags)))
      (is (= decorators-after (rf.story/ids :decorator))))))

(deftest explicit-install-before-reg-suppresses-auto-install
  (testing "calling install-canonical-vocabulary! at boot suppresses the auto-install path"
    ;; An author who DOES make the explicit call is fine too: the gate
    ;; is true when the first reg-* fires, so
    ;; the auto-install hook hits the early-return branch.
    (rf.story/clear-all!)
    (rf.story/install-canonical-vocabulary!)
    (is (true? @rf.story.canonical/installed?))
    (is (zero? (counting-installs
                 #(rf.story/reg-story :story.explicit-boot.probe {:tags #{:dev}})))
        "the first reg-* does not run the installer chain again")
    (is (rf.story/registered? :story :story.explicit-boot.probe))))

(deftest clear-all-resets-auto-install-gate
  (testing "clear-all! resets the auto-install gate so the cycle can fire again"
    (rf.story/clear-all!)
    (rf.story/reg-story :story.gate-cycle.a {:tags #{:dev}})
    (is (true? @rf.story.canonical/installed?))
    (rf.story/clear-all!)
    (is (false? @rf.story.canonical/installed?))
    ;; A second cycle works exactly like the first.
    (rf.story/reg-story :story.gate-cycle.b {:tags #{:docs}})
    (is (true? @rf.story.canonical/installed?))
    (is (rf.story/registered? :story :story.gate-cycle.b))
    (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-tags))))

;; ---- reg-story basic ----------------------------------------------------

(deftest reg-story-id-shape
  (testing "reg-story rejects ids outside the :story.<path> grammar"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-id-shape"
                          (rf.story/reg-story* :NotAStoryId {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-id-shape"
                          (rf.story/reg-story* :foo.bar {})))))

(deftest reg-story-bad-shape
  (testing "reg-story rejects a body that violates the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-shape"
                          (rf.story/reg-story :story.ui.bad
                            {:tags "not-a-set"})))))

(deftest reg-story-unknown-tag
  (testing "reg-story raises :rf.error/unknown-tag on an unregistered tag"
    (try
      (rf.story/reg-story :story.ui.bad {:tags #{:dev :totally-made-up}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/unknown-tag (:rf.error/id (ex-data e))))
        (is (= [:totally-made-up] (:unknown (ex-data e))))))))

;; ---- :extends resolution -----------------------------------------------

(deftest extends-stored-raw-at-registration-resolved-by-compiler
  (testing ":extends is stored RAW at registration (`:extends` intact,
            parent NOT merged); the PLAN COMPILER is the single merge
            authority (spec/017 §305-306). The side-table body
            keeps the child's own slots verbatim; the compiled plan
            inherits the parent's :decorators via [:world :decorators]."
    (rf.story/reg-variant :story.auth.login/loading
      {:setup     [[:auth/initialise]
                    [:auth/email-changed "alice@example.com"]
                    [:auth/login-pressed]]
       :decorators [[:force-fx-stub :http {:status :pending}]]
       :tags       #{:dev}})
    (rf.story/reg-variant :story.auth.login/loading-with-prefill
      {:extends :story.auth.login/loading
       :setup  [[:auth/initialise]
                 [:auth/email-changed "alice@example.com"]
                 [:auth/password-changed "hunter2"]
                 [:auth/login-pressed]]
       :tags    #{:dev :docs}})
    (let [body (rf.story/handler-meta :variant :story.auth.login/loading-with-prefill)]
      (is (= :story.auth.login/loading (:extends body))
          ":extends is stored RAW — NOT stripped at registration")
      (is (= 4 (count (:setup body))) "child's own :setup stored verbatim")
      (is (nil? (:decorators body))
          "child declared no :decorators; the raw body carries none —
           inheritance is the compiler's job, not the registrar's")
      (is (= #{:dev :docs} (:tags body)) "child's own :tags stored verbatim"))
    ;; The plan compiler resolves the chain: setup APPENDS, :decorators
    ;; inherit child-wins. The child declared no decorators, so it
    ;; inherits the parent's.
    (let [plan (rf.story.plan/variant-plan :story.auth.login/loading-with-prefill)]
      (is (= [[:force-fx-stub :http {:status :pending}]]
             (get-in plan [:world :decorators]))
          "compiled plan INHERITS the parent's :decorators"))))

;; Cycle detection and the depth cap are witnessed on the merge authority in
;; `re-frame.story.plan-cljs-test` (§`extends-cycle-fails`,
;; §`extends-depth-cap-fails`). There is no standalone extends resolver: a
;; second resolver could drift from the compiled-plan merge semantics, and a
;; green witness on it would prove nothing about the shipped runtime. Being
;; `.cljc` those tests are host-free, and with that suite's `-cljs-test` name
;; both gates run them: `jvm-tools-story` (`clojure -M:test` here) and the CLJS
;; `:node-test` build, whose `cljs-test$` ns-regexp a plain `-test` namespace
;; does not match.

;; ---- Form-B desugaring -------------------------------------------------

(deftest form-b-desugars-to-separate-form-shape
  (testing "Form-B combined authoring produces the same registry bodies as explicit separate forms"
    (rf.story/reg-story :story.formb.combined
      {:doc       "Combined story."
       :component :app.formb/view
       :args      {:label "parent"}
       :tags      #{:dev}
       :variants  {:idle {:setup [[:formb/init]]
                           :args   {:state :idle}
                           :tags   #{:dev :test}}
                   :busy {:setup [[:formb/init] [:formb/load]]
                          :args   {:state :busy}
                          :tags   #{:dev}}}})
    (let [combined-story (dissoc (rf.story/handler-meta :story :story.formb.combined)
                                 :source)
          combined-idle  (dissoc (rf.story/handler-meta :variant :story.formb.combined/idle)
                                 :source)
          combined-busy  (dissoc (rf.story/handler-meta :variant :story.formb.combined/busy)
                                 :source)]
      (rf.story/clear-all!)
      (rf.story/install-canonical-vocabulary!)
      (rf.story/reg-story :story.formb.separate
        {:doc       "Combined story."
         :component :app.formb/view
         :args      {:label "parent"}
         :tags      #{:dev}})
      (rf.story/reg-variant :story.formb.separate/idle
        {:setup [[:formb/init]]
         :args   {:state :idle}
         :tags   #{:dev :test}})
      (rf.story/reg-variant :story.formb.separate/busy
        {:setup [[:formb/init] [:formb/load]]
         :args   {:state :busy}
         :tags   #{:dev}})
      (is (= combined-story
             (dissoc (rf.story/handler-meta :story :story.formb.separate) :source)))
      (is (= combined-idle
             (dissoc (rf.story/handler-meta :variant :story.formb.separate/idle) :source)))
      (is (= combined-busy
             (dissoc (rf.story/handler-meta :variant :story.formb.separate/busy) :source)))
      (is (= #{:story.formb.separate/idle :story.formb.separate/busy}
             (rf.story/variants-of :story.formb.separate))))))

;; ---- workspace ---------------------------------------------------------

(deftest reg-workspace-grid-without-variants-rejected
  (testing "a :grid workspace without :variants fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/workspace-shape"
                          (rf.story/reg-workspace :Workspace.bad/empty
                            {:layout :grid})))))

;; ---- decorator (per-kind) ---------------------------------------------

(deftest reg-decorator-frame-setup
  (testing ":frame-setup decorator requires :init or :app-db-patch"
    (rf.story/reg-decorator :mock-auth
      {:doc  "Inject a mock auth user."
       :kind :frame-setup
       :init [[:auth/restore-session {:user "alice"}]]})
    (is (= :frame-setup (:kind (rf.story/handler-meta :decorator :mock-auth))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/decorator-shape"
                          (rf.story/reg-decorator :mock-empty
                            {:kind :frame-setup})))))

;; ---- tags: the :axis + :default-filter slots ---------------------------

(deftest tags-by-axis-filters-correctly
  (testing "tags-by-axis returns only tags registered on the requested axis"
    (rf.story/reg-tag :status/alpha       {:axis :status :default-filter :exclude})
    (rf.story/reg-tag :status/beta        {:axis :status})
    (rf.story/reg-tag :role/dev           {:axis :role})
    (rf.story/reg-tag :auth/regression    {:axis :team})
    (rf.story/reg-tag :no-axis/freeform   {:doc "no axis here"})
    (is (= #{:status/alpha :status/beta} (rf.story/tags-by-axis :status)))
    (is (= #{:role/dev}                  (rf.story/tags-by-axis :role)))
    (is (= #{:auth/regression}           (rf.story/tags-by-axis :team)))
    (is (= #{} (rf.story/tags-by-axis :nonexistent)))
    ;; the un-axis-grouped tag joins exactly the canonical seven inclusion
    ;; tags (the :state/* tags carry the :state axis)
    (is (= (conj rf.story.schemas/canonical-tags :no-axis/freeform)
           (rf.story/tags-without-axis)))))

(deftest tags-default-excluded-filters-correctly
  (testing "tags-default-excluded returns only tags with :default-filter :exclude"
    (rf.story/reg-tag :status/alpha    {:axis :status :default-filter :exclude})
    (rf.story/reg-tag :status/beta     {:axis :status :default-filter :include})
    (rf.story/reg-tag :status/stable   {:axis :status})                ; no slot — defaults to include
    (rf.story/reg-tag :hidden/internal {:default-filter :exclude})
    (is (= #{:status/alpha :hidden/internal} (rf.story/tags-default-excluded)))))

(deftest reg-tag-rejects-non-keyword-axis
  (testing ":axis must be a keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/tag-shape"
                          (rf.story/reg-tag :bad/axis
                            {:axis "status"})))))

;; ---- !-prefix removal syntax -------------------------------------------

(deftest tags-with-bang-prefix-rejects-unknown
  (testing "the !-prefix variant rejects unknown base tags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/unknown-tag"
                          (rf.story/reg-variant :story.bang/bad
                            {:setup []
                             :tags   #{:!totally-unknown}})))))

;; ---- query API ---------------------------------------------------------

(deftest variants-of-returns-only-the-storys-own-variants
  (testing "variants-of matches a variant id's namespace EXACTLY: a deeper-
            namespaced story's variants are never a string-prefix match, the
            bare `:story` root works, and a story with none answers #{}"
    (is (= #{} (rf.story/variants-of :story.no-variants)) "empty registry")
    (doseq [vid [:story.foo/a :story.foo/b :story.bar/c
                 :story.foo.bar/x :story.foo.bar/y
                 :story/root :story.a/v]]
      (rf.story/reg-variant* vid {:setup []}))
    (are [story-id expected] (= expected (rf.story/variants-of story-id))
      :story.foo         #{:story.foo/a :story.foo/b}
      :story.bar         #{:story.bar/c}
      :story.foo.bar     #{:story.foo.bar/x :story.foo.bar/y}
      :story             #{:story/root}
      :story.a           #{:story.a/v}
      :story.no-variants #{})))

(deftest variants-by-story-single-pass-index
  (testing "variants-by-story builds a {story-id #{variant-ids}} index in one pass"
    (rf.story/reg-story   :story.foo {})
    (rf.story/reg-story   :story.bar {})
    (rf.story/reg-story   :story.empty {})
    (rf.story/reg-variant :story.foo/a {:setup []})
    (rf.story/reg-variant :story.foo/b {:setup []})
    (rf.story/reg-variant :story.bar/c {:setup []})
    (let [idx (rf.story/variants-by-story)]
      (is (= #{:story.foo/a :story.foo/b} (get idx :story.foo)))
      (is (= #{:story.bar/c}              (get idx :story.bar)))
      (is (= #{}                          (get idx :story.empty))
          "stories with zero variants land with an empty set"))))

(deftest variants-with-tags-intersection
  (testing "variants-with-tags returns variants whose :tags intersects the query"
    (rf.story/reg-variant :story.tag/a {:setup [] :tags #{:dev :test}})
    (rf.story/reg-variant :story.tag/b {:setup [] :tags #{:dev :docs}})
    (rf.story/reg-variant :story.tag/c {:setup [] :tags #{:test}})
    (is (= #{:story.tag/a :story.tag/c} (rf.story/variants-with-tags #{:test})))
    (is (= #{:story.tag/a :story.tag/b} (rf.story/variants-with-tags #{:docs :dev})))))

(deftest variants-with-tags-excludes-marker-removed-inherited-tag
  (testing "A child that :extends a :dev-tagged parent and
            declares :!dev is EXCLUDED from the #{:dev} query (the inherited
            :dev was cancelled), while a sibling that keeps :dev is returned"
    (rf.story/reg-variant :story.rm/base  {:setup [] :tags #{:dev}})
    (rf.story/reg-variant :story.rm/child {:setup [] :extends :story.rm/base :tags #{:!dev}})
    (rf.story/reg-variant :story.rm/keeps {:setup [] :tags #{:dev}})
    (let [hits (rf.story/variants-with-tags #{:dev})]
      (is (contains? hits :story.rm/base))
      (is (contains? hits :story.rm/keeps))
      (is (not (contains? hits :story.rm/child))
          ":!dev removed the inherited :dev, so the child is not a #{:dev} hit"))))

(deftest variants-with-tags-matches-inherited-story-tag
  (testing "A variant that declares no tags inherits its parent
            story's :tags and is returned for a query on the inherited tag"
    (rf.story/reg-story   :story.inh {:tags #{:dev}})
    (rf.story/reg-variant :story.inh/v {:setup []})
    (is (contains? (rf.story/variants-with-tags #{:dev}) :story.inh/v))))

(deftest all-kinds-with-counts-reflects-state
  (testing "all-kinds-with-counts mirrors the side-table"
    (rf.story/reg-story   :story.x   {:doc "x"})
    (rf.story/reg-variant :story.x/v {:setup []})
    (let [counts (rf.story/all-kinds-with-counts)]
      (is (= 1 (:story   counts)))
      (is (= 1 (:variant counts)))
      (is (= (+ (count rf.story.schemas/canonical-tags)
                (count rf.story.schemas/canonical-state-tags))
             (:tag counts))))))

;; ---- static-mode? ----------------------------------------------------

(deftest static-mode-defaults-false-on-jvm
  (testing "re-frame.story.config/static-mode? defaults to false on the JVM"
    ;; Per tools/story/spec/013-Static-Build.md the JVM-side def is a
    ;; plain const false — JVM consumers never operate in static mode
    ;; (the flag exists for CLJS :advanced builds via :closure-defines).
    (is (false? rf.story.config/static-mode?)))
  (testing "the public probe (re-frame.story/static-mode?) reflects the flag"
    (is (false? (re-frame.story/static-mode?)))))

;; ---- registrar mutation tick ----------------------------------------

(deftest mutation-tick-bumps-on-every-write
  (testing "every reg-* / unregister! / clear-* call bumps the tick;
            consumers caching registry-derived work key off this counter"
    (let [t0 (rf.story.registrar/current-mutation-tick)]
      (rf.story/reg-story :story.ui.tick {:doc "tick test"})
      (is (> (rf.story.registrar/current-mutation-tick) t0))
      (let [t1 (rf.story.registrar/current-mutation-tick)]
        (rf.story/reg-variant :story.ui.tick/v {:setup [[:init]]})
        (is (> (rf.story.registrar/current-mutation-tick) t1))
        (let [t2 (rf.story.registrar/current-mutation-tick)]
          (rf.story.registrar/unregister! :variant :story.ui.tick/v)
          (is (> (rf.story.registrar/current-mutation-tick) t2))
          (let [t3 (rf.story.registrar/current-mutation-tick)]
            (rf.story.registrar/clear-kind! :variant)
            (is (> (rf.story.registrar/current-mutation-tick) t3))))))))

(deftest variants-with-tags-memoised-on-mutation-tick
  (testing "variants-with-tags returns cached results between two registrar writes"
    (rf.story/reg-tag :status/stable {:axis :status})
    (rf.story/reg-tag :role/dev      {:axis :role})
    (rf.story/reg-variant :story.memo/a {:tags #{:status/stable} :setup []})
    (rf.story/reg-variant :story.memo/b {:tags #{:role/dev}     :setup []})
    (rf.story/reg-variant :story.memo/c {:tags #{:status/stable :role/dev} :setup []})
    (let [r1 (rf.story.registrar/variants-with-tags #{:status/stable})
          r2 (rf.story.registrar/variants-with-tags #{:status/stable})]
      (testing "same query between writes returns identical (cache-hit) set"
        (is (identical? r1 r2))
        (is (= #{:story.memo/a :story.memo/c} r1))))
    (testing "different query in same tick is also cached + correct"
      (let [r-role (rf.story.registrar/variants-with-tags #{:role/dev})]
        (is (= #{:story.memo/b :story.memo/c} r-role))))
    (testing "registrar mutation invalidates the cache"
      (rf.story/reg-variant :story.memo/d {:tags #{:status/stable} :setup []})
      (let [r3 (rf.story.registrar/variants-with-tags #{:status/stable})]
        (is (= #{:story.memo/a :story.memo/c :story.memo/d} r3))))))

;; ---- Public tag->axis-index API -------------------------------------

(deftest public-tag-axis-index-no-axis-sentinel
  (testing "rf.story/tag->axis-index returns the ::no-axis sentinel for tags
without :axis (the public-API contract)"
    (rf.story/reg-tag :status/stable  {:axis :status})
    (rf.story/reg-tag :role/dev       {:axis :role})
    (rf.story/reg-tag :loose/freeform {:doc "no axis on this tag"})
    (let [idx (rf.story/tag->axis-index)]
      (testing "axis-bearing tags map to their axis"
        (is (= :status (get idx :status/stable)))
        (is (= :role   (get idx :role/dev))))
      (testing "tags registered without :axis map to the rf.story.registrar/no-axis sentinel"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :loose/freeform))))
      (testing "canonical tags are pre-registered without :axis and bucket to no-axis"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :dev)))))))
