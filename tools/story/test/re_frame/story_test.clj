(ns re-frame.story-test
  "The Story registration surface on the JVM: id grammar, body validation,
  tag membership, auto-install of the canonical vocabulary, `:extends` raw
  storage, Form-B desugaring and the query API."
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
  (testing "the string-level grammar the MCP write paths check before interning"
    (doseq [[parts expected] [[["story.button" "primary"] true]
                              [["story" "primary"] true]
                              [["not-story" "primary"] false]
                              [[nil "primary"] false]
                              [["story.button" ""] false]]]
      (is (= expected (rf.story.schemas/variant-id-shape? parts)) (pr-str parts))))
  (testing "variant-id? delegates to it"
    (is (= [true false] (mapv (comp boolean rf.story.schemas/variant-id?)
                              [:story.button/primary :not-story/primary])))))

;; The canonical vocabulary auto-installs on the first `reg-*` after
;; `clear-all!` (001-Authoring §Boot), so authors need no boot step.

(deftest auto-install-fires-on-first-reg-story
  (rf.story/clear-all!)
  (is (= [false #{}] [@rf.story.canonical/installed? (rf.story/list-tags)]))
  ;; Without auto-install the canonical tags would be unknown here.
  (rf.story/reg-story :story.auto-install.probe {:tags #{:dev :docs}})
  (is (true? @rf.story.canonical/installed?))
  (is (= (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)
         (rf.story/list-tags))))

(deftest auto-install-fires-on-first-reg-variant
  (rf.story/clear-all!)
  (rf.story/reg-variant :story.auto-install/v {:setup [] :tags #{:dev}})
  (is (true? @rf.story.canonical/installed?)))

(deftest auto-install-fires-on-first-reg-tag
  (rf.story/clear-all!)
  (rf.story/reg-tag :auth/regression-set {:doc "Auth regression-suite."})
  (is (every? #(rf.story/registered? :tag %) (conj rf.story.schemas/canonical-tags
                                                   :auth/regression-set))))

(defn- counting-installs
  "Calls of `rf.story.canonical/install!` while `f` runs. Every installer is
  idempotent, so counting calls is the only way to see a re-run."
  [f]
  (let [calls    (atom 0)
        install! rf.story.canonical/install!]
    (with-redefs [rf.story.canonical/install! (fn [] (swap! calls inc) (install!))]
      (f))
    @calls))

(deftest auto-install-is-idempotent
  (rf.story/clear-all!)
  (rf.story/reg-story :story.idem.a {:tags #{:dev}})
  (is (zero? (counting-installs
               #(do (rf.story/reg-story :story.idem.b {:tags #{:docs}})
                    (rf.story/reg-variant :story.idem.a/v {:tags #{:dev} :setup []}))))))

(deftest explicit-install-after-auto-install-is-noop
  (rf.story/clear-all!)
  (rf.story/reg-story :story.explicit.probe {:tags #{:dev}})
  (let [snapshot #(vector (rf.story/list-tags) (rf.story/ids :decorator))
        before   (snapshot)]
    (rf.story/install-canonical-vocabulary!)
    (is (= before (snapshot)))))

(deftest explicit-install-before-reg-suppresses-auto-install
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (is (zero? (counting-installs
               #(rf.story/reg-story :story.explicit-boot.probe {:tags #{:dev}})))))

(deftest clear-all-resets-auto-install-gate
  (rf.story/clear-all!)
  (rf.story/reg-story :story.gate-cycle.a {:tags #{:dev}})
  (rf.story/clear-all!)
  (is (false? @rf.story.canonical/installed?))
  (rf.story/reg-story :story.gate-cycle.b {:tags #{:docs}})
  (is (true? @rf.story.canonical/installed?)))

;; ---- reg-story basic ----------------------------------------------------

(deftest reg-story-id-shape
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/story-id-shape"
                        (rf.story/reg-story* :foo.bar {}))))

(deftest reg-story-bad-shape
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/story-shape"
                        (rf.story/reg-story :story.ui.bad {:tags "not-a-set"}))))

(deftest reg-story-unknown-tag
  (let [data (try (rf.story/reg-story :story.ui.bad {:tags #{:dev :totally-made-up}})
                  nil
                  (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= [:rf.error/unknown-tag [:totally-made-up]] ((juxt :rf.error/id :unknown) data)))))

;; ---- :extends resolution -----------------------------------------------

(deftest extends-stored-raw-at-registration-resolved-by-compiler
  (testing "the registrar stores :extends raw; the plan compiler is the one
            merge authority (spec/017), so the compiled plan inherits the
            parent's :decorators. Cycles and the depth cap are pinned on the
            compiler in `re-frame.story.plan-cljs-test`."
    (rf.story/reg-variant :story.auth.login/loading
      {:setup      [[:auth/initialise]]
       :decorators [[:force-fx-stub :http {:status :pending}]]
       :tags       #{:dev}})
    (rf.story/reg-variant :story.auth.login/loading-with-prefill
      {:extends :story.auth.login/loading
       :setup   [[:auth/initialise] [:auth/password-changed "hunter2"]]
       :tags    #{:dev :docs}})
    (let [body (rf.story/handler-meta :variant :story.auth.login/loading-with-prefill)]
      (is (= [:story.auth.login/loading nil] ((juxt :extends :decorators) body))))
    (is (= [[:force-fx-stub :http {:status :pending}]]
           (get-in (rf.story.plan/variant-plan :story.auth.login/loading-with-prefill)
                   [:world :decorators])))))

;; ---- Form-B desugaring -------------------------------------------------

(deftest form-b-desugars-to-separate-form-shape
  (testing "Form-B :variants register the same bodies as separate forms"
    (let [story {:doc "Combined story." :component :app.formb/view
                 :args {:label "parent"} :tags #{:dev}}
          idle  {:setup [[:formb/init]] :args {:state :idle} :tags #{:dev :test}}
          busy  {:setup [[:formb/init] [:formb/load]] :args {:state :busy} :tags #{:dev}}
          read  (fn [sid]
                  [(dissoc (rf.story/handler-meta :story sid) :source)
                   (dissoc (rf.story/handler-meta :variant (keyword (name sid) "idle")) :source)
                   (dissoc (rf.story/handler-meta :variant (keyword (name sid) "busy")) :source)
                   (count (rf.story/variants-of sid))])]
      (rf.story/reg-story :story.formb.combined (assoc story :variants {:idle idle :busy busy}))
      (let [combined (read :story.formb.combined)]
        (rf.story/clear-all!)
        (rf.story/install-canonical-vocabulary!)
        (rf.story/reg-story :story.formb.separate story)
        (rf.story/reg-variant :story.formb.separate/idle idle)
        (rf.story/reg-variant :story.formb.separate/busy busy)
        (is (= combined (read :story.formb.separate)))))))

;; ---- workspace ---------------------------------------------------------

(deftest reg-workspace-grid-without-variants-rejected
  (testing "a :grid workspace without :variants fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/workspace-shape"
                          (rf.story/reg-workspace :Workspace.bad/empty
                            {:layout :grid})))))

;; ---- decorator (per-kind) ---------------------------------------------

(deftest reg-decorator-frame-setup
  (testing ":frame-setup requires :init, :app-db-patch or :teardown"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/decorator-shape"
                          (rf.story/reg-decorator :mock-empty {:kind :frame-setup})))))

;; ---- tags: the :axis + :default-filter slots ---------------------------

(deftest tags-by-axis-filters-correctly
  (rf.story/reg-tag :status/alpha     {:axis :status :default-filter :exclude})
  (rf.story/reg-tag :status/beta      {:axis :status})
  (rf.story/reg-tag :role/dev         {:axis :role})
  (rf.story/reg-tag :no-axis/freeform {:doc "no axis here"})
  (is (= [#{:status/alpha :status/beta} #{:role/dev} #{}]
         (mapv rf.story/tags-by-axis [:status :role :nonexistent])))
  (testing "the :state/* tags carry an axis; the canonical seven do not"
    (is (= rf.story.schemas/canonical-state-tags (rf.story/tags-by-axis :state)))
    (is (= (conj rf.story.schemas/canonical-tags :no-axis/freeform)
           (rf.story/tags-without-axis)))))

(deftest tags-default-excluded-filters-correctly
  (rf.story/reg-tag :status/alpha    {:axis :status :default-filter :exclude})
  (rf.story/reg-tag :status/beta     {:axis :status :default-filter :include})
  (rf.story/reg-tag :status/stable   {:axis :status})
  (rf.story/reg-tag :hidden/internal {:default-filter :exclude})
  (is (= #{:status/alpha :hidden/internal} (rf.story/tags-default-excluded))))

(deftest reg-tag-rejects-non-keyword-axis
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/tag-shape"
                        (rf.story/reg-tag :bad/axis {:axis "status"}))))

;; ---- !-prefix removal syntax -------------------------------------------

(deftest tags-with-bang-prefix-rejects-unknown
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":rf\.error/unknown-tag"
                        (rf.story/reg-variant :story.bang/bad {:setup [] :tags #{:!totally-unknown}}))))

;; ---- query API ---------------------------------------------------------

(deftest variants-of-returns-only-the-storys-own-variants
  (testing "variants-of matches a variant id's namespace exactly, never as a
            string prefix"
    (doseq [vid [:story.foo/a :story.foo/b :story.bar/c
                 :story.foo.bar/x :story.foo.bar/y
                 :story/root :story.a/v]]
      (rf.story/reg-variant* vid {:setup []}))
    (are [story-id expected] (= expected (rf.story/variants-of story-id))
      :story.foo         #{:story.foo/a :story.foo/b}
      :story.foo.bar     #{:story.foo.bar/x :story.foo.bar/y}
      :story             #{:story/root}
      :story.a           #{:story.a/v}
      :story.no-variants #{})))

(deftest variants-by-story-single-pass-index
  (doseq [sid [:story.foo :story.bar :story.empty]] (rf.story/reg-story sid {}))
  (doseq [vid [:story.foo/a :story.foo/b :story.bar/c]] (rf.story/reg-variant vid {:setup []}))
  (is (= {:story.foo #{:story.foo/a :story.foo/b} :story.bar #{:story.bar/c} :story.empty #{}}
         (select-keys (rf.story/variants-by-story) [:story.foo :story.bar :story.empty]))))

(deftest variants-with-tags-intersection
  (rf.story/reg-variant :story.tag/a {:setup [] :tags #{:dev :test}})
  (rf.story/reg-variant :story.tag/b {:setup [] :tags #{:dev :docs}})
  (rf.story/reg-variant :story.tag/c {:setup [] :tags #{:test}})
  (is (= [#{:story.tag/a :story.tag/c} #{:story.tag/a :story.tag/b}]
         (mapv rf.story/variants-with-tags [#{:test} #{:docs :dev}]))))

(deftest variants-with-tags-excludes-marker-removed-inherited-tag
  (testing "a child cancelling an inherited :dev with :!dev is not a #{:dev} hit"
    (rf.story/reg-variant :story.rm/base  {:setup [] :tags #{:dev}})
    (rf.story/reg-variant :story.rm/child {:setup [] :extends :story.rm/base :tags #{:!dev}})
    (rf.story/reg-variant :story.rm/keeps {:setup [] :tags #{:dev}})
    (is (= #{:story.rm/base :story.rm/keeps} (rf.story/variants-with-tags #{:dev})))))

(deftest variants-with-tags-matches-inherited-story-tag
  (rf.story/reg-story   :story.inh {:tags #{:dev}})
  (rf.story/reg-variant :story.inh/v {:setup []})
  (is (contains? (rf.story/variants-with-tags #{:dev}) :story.inh/v)))

(deftest all-kinds-with-counts-reflects-state
  (rf.story/reg-story   :story.x   {:doc "x"})
  (rf.story/reg-variant :story.x/v {:setup []})
  (is (= [1 1 (+ (count rf.story.schemas/canonical-tags)
                 (count rf.story.schemas/canonical-state-tags))]
         ((juxt :story :variant :tag) (rf.story/all-kinds-with-counts)))))

;; ---- static-mode? ----------------------------------------------------

(deftest static-mode-defaults-false-on-jvm
  ;; 013-Static-Build: the flag exists for CLJS :advanced builds only.
  (is (= [false false] [rf.story.config/static-mode? (rf.story/static-mode?)])))

;; ---- registrar mutation tick ----------------------------------------

(deftest mutation-tick-bumps-on-every-write
  (testing "every write bumps the tick that registry-derived caches key off"
    (doseq [[label write!] [["reg-story" #(rf.story/reg-story :story.ui.tick {:doc "tick"})]
                            ["reg-variant" #(rf.story/reg-variant :story.ui.tick/v {:setup [[:init]]})]
                            ["unregister!" #(rf.story.registrar/unregister! :variant :story.ui.tick/v)]
                            ["clear-kind!" #(rf.story.registrar/clear-kind! :variant)]]]
      (let [t (rf.story.registrar/current-mutation-tick)]
        (write!)
        (is (> (rf.story.registrar/current-mutation-tick) t) label)))))

(deftest variants-with-tags-memoised-on-mutation-tick
  (testing "a registrar write invalidates the memoised query"
    (rf.story/reg-variant :story.memo/a {:tags #{:dev} :setup []})
    (is (= #{:story.memo/a} (rf.story.registrar/variants-with-tags #{:dev})))
    (rf.story/reg-variant :story.memo/d {:tags #{:dev} :setup []})
    (is (= #{:story.memo/a :story.memo/d} (rf.story.registrar/variants-with-tags #{:dev})))))

;; ---- Public tag->axis-index API -------------------------------------

(deftest public-tag-axis-index-no-axis-sentinel
  (rf.story/reg-tag :status/stable  {:axis :status})
  (rf.story/reg-tag :loose/freeform {:doc "no axis on this tag"})
  (is (= {:status/stable  :status
          :loose/freeform :re-frame.story.registrar/no-axis
          :dev            :re-frame.story.registrar/no-axis}
         (select-keys (rf.story/tag->axis-index) [:status/stable :loose/freeform :dev]))))
