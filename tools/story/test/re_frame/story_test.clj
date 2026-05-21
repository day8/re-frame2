(ns re-frame.story-test
  "JVM tests for re-frame2-story Stage 2 (rf2-32dk).

  Covers:

  - Macro expansion → registry write round-trip.
  - Body shape validation (`:rf.error/<kind>-shape`).
  - Tag membership (`:rf.error/unknown-tag`).
  - `:extends` resolution + cycle / unknown-parent detection.
  - Form-B `:variants` desugaring.
  - Source-coord stamping.
  - Query API (`registrations`, `handler-meta`, `variants-with-tags`,
    `variants-of`).
  - EDN-round-trip of variant bodies (no fn-valued slots).
  - Canonical-id-grammar enforcement.

  JVM-runnable because the registration surface is pure data — no
  Reagent / DOM / shadow-cljs required. Per IMPL-SPEC §1.1 + the
  `jvm_interop_must_work` user-feedback rule, every artefact that can
  run on the JVM should."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
            [re-frame.story.canonical :as canonical]
            [re-frame.story.config :as story-config]
            [re-frame.story.extends :as extends]
            [re-frame.story.registrar :as registrar]
            [re-frame.story.schemas :as schemas]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (story/clear-all!)
  (story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ---- canonical-vocabulary install ---------------------------------------

(deftest canonical-tags-installed
  (testing "the seven canonical tags are registered after boot"
    (is (= schemas/canonical-tags (story/list-tags)))
    (is (every? #(story/registered? :tag %) schemas/canonical-tags))))

;; ---- auto-install on first reg-* call (rf2-p1ydc) ----------------------
;;
;; The canonical vocabulary auto-installs on the first `reg-*` runtime
;; call so authors don't need a separate `(story/install-canonical-vocabulary!)`
;; boot step. Spec: tools/story/spec/001-Authoring.md §Boot — auto-install
;; of the canonical vocabulary.
;;
;; These tests deliberately call `clear-all!` to wipe the registrar +
;; the auto-install gate so the first `reg-*` below is genuinely the
;; first one in this generation.

(deftest auto-install-fires-on-first-reg-story
  (testing "the first reg-story after clear-all! auto-installs the canonical vocabulary"
    (story/clear-all!)
    (is (false? @canonical/installed?) "the gate is reset by clear-all!")
    (is (empty? (story/list-tags))     "the side-table is wiped")
    ;; The story body uses :tags #{:dev :docs} — without auto-install
    ;; this would raise :rf.error/unknown-tag. With auto-install, the
    ;; canonical tags are registered on the first reg-story call.
    (story/reg-story :story.auto-install.probe
      {:doc  "Auto-install probe."
       :tags #{:dev :docs}})
    (is (true? @canonical/installed?) "the gate flips true after auto-install")
    (is (= schemas/canonical-tags (story/list-tags))
        "all seven canonical tags are registered post-auto-install")
    (is (story/registered? :story :story.auto-install.probe))))

(deftest auto-install-fires-on-first-reg-variant
  (testing "the first reg-variant after clear-all! also triggers auto-install"
    (story/clear-all!)
    (story/reg-variant :story.auto-install/v
      {:events []
       :tags   #{:dev}})
    (is (true? @canonical/installed?))
    (is (story/registered? :variant :story.auto-install/v))
    (is (every? #(story/registered? :tag %) schemas/canonical-tags))))

(deftest auto-install-fires-on-first-reg-tag
  (testing "the first reg-tag (project-tag) after clear-all! also triggers auto-install"
    (story/clear-all!)
    ;; A project tag — registering it should ALSO install the canonical
    ;; seven first, so subsequent variants tagged `:dev` still validate.
    (story/reg-tag :auth/regression-set {:doc "Auth regression-suite."})
    (is (story/registered? :tag :auth/regression-set))
    (is (every? #(story/registered? :tag %) schemas/canonical-tags)
        "canonical tags ride along with the first reg-tag too")))

(deftest auto-install-is-idempotent
  (testing "subsequent reg-* calls do NOT re-trigger the installer chain"
    (story/clear-all!)
    (story/reg-story :story.idem.a {:tags #{:dev}})
    ;; A subsequent registration must not break, must not re-install
    ;; (we observe idempotency indirectly: the side-table stays
    ;; consistent and no exception fires).
    (let [tags-after-first (story/list-tags)]
      (story/reg-story :story.idem.b {:tags #{:docs}})
      (story/reg-variant :story.idem.a/v {:tags #{:dev} :events []})
      (is (= tags-after-first (story/list-tags))
          "canonical tag set is stable across subsequent reg-* calls"))))

(deftest explicit-install-after-auto-install-is-noop
  (testing "calling install-canonical-vocabulary! explicitly after auto-install fired is a no-op"
    (story/clear-all!)
    (story/reg-story :story.explicit.probe {:tags #{:dev}})
    (let [tags-after-auto-install (story/list-tags)
          decorators-after        (story/ids :decorator)]
      ;; Explicit call lands on the already-true gate; install! flips
      ;; it true (already true) and re-runs the installer chain. Every
      ;; installer is documented idempotent, so the side-table snapshot
      ;; should be unchanged.
      (story/install-canonical-vocabulary!)
      (is (= tags-after-auto-install (story/list-tags)))
      (is (= decorators-after (story/ids :decorator))))))

(deftest explicit-install-before-reg-suppresses-auto-install
  (testing "calling install-canonical-vocabulary! at boot suppresses the auto-install path"
    ;; Author who DOES make the explicit call (the v1 documented path)
    ;; still works: the gate is true when the first reg-* fires, so
    ;; the auto-install hook hits the early-return branch.
    (story/clear-all!)
    (story/install-canonical-vocabulary!)
    (is (true? @canonical/installed?))
    ;; The first reg-* must NOT throw and must NOT recompute the
    ;; installer chain (no easy direct probe — but no exception +
    ;; correct registry shape is the contract).
    (story/reg-story :story.explicit-boot.probe {:tags #{:dev}})
    (is (story/registered? :story :story.explicit-boot.probe))))

(deftest clear-all-resets-auto-install-gate
  (testing "clear-all! resets the auto-install gate so the cycle can fire again"
    (story/clear-all!)
    (story/reg-story :story.gate-cycle.a {:tags #{:dev}})
    (is (true? @canonical/installed?))
    (story/clear-all!)
    (is (false? @canonical/installed?))
    ;; A second cycle works exactly like the first.
    (story/reg-story :story.gate-cycle.b {:tags #{:docs}})
    (is (true? @canonical/installed?))
    (is (story/registered? :story :story.gate-cycle.b))
    (is (every? #(story/registered? :tag %) schemas/canonical-tags))))

;; ---- reg-story basic ----------------------------------------------------

(deftest reg-story-basic
  (testing "reg-story writes to the side-table under :story kind"
    (story/reg-story :story.ui.button
      {:doc       "Primary action button."
       :component :app.ui/button
       :args      {:label "Click me"}
       :tags      #{:dev :docs}})
    (is (= #{:story.ui.button} (story/ids :story)))
    (let [body (story/handler-meta :story :story.ui.button)]
      (is (= "Primary action button." (:doc body)))
      (is (= :app.ui/button (:component body)))
      (is (= {:label "Click me"} (:args body)))
      (is (= #{:dev :docs} (:tags body)))))

  (testing "source-coord is stamped onto the registered body"
    (story/reg-story :story.ui.icon {:doc "Icon."})
    (let [body (story/handler-meta :story :story.ui.icon)]
      (is (map? (:source body)))
      (is (= 're-frame.story-test (:ns (:source body))))
      (is (integer? (:line (:source body)))))))

(deftest reg-story-id-shape
  (testing "reg-story rejects ids outside the :story.<path> grammar"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-id-shape"
                          (story/reg-story* :NotAStoryId {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-id-shape"
                          (story/reg-story* :foo.bar {})))))

(deftest reg-story-bad-shape
  (testing "reg-story rejects a body that violates the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/story-shape"
                          (story/reg-story :story.ui.bad
                            {:tags "not-a-set"})))))

(deftest reg-story-unknown-tag
  (testing "reg-story raises :rf.error/unknown-tag on an unregistered tag"
    (try
      (story/reg-story :story.ui.bad {:tags #{:dev :totally-made-up}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/unknown-tag (:rf.error/id (ex-data e))))
        (is (= [:totally-made-up] (:unknown (ex-data e))))))))

;; ---- reg-variant basic -------------------------------------------------

(deftest reg-variant-basic
  (testing "reg-variant writes a variant under :variant kind"
    (story/reg-variant :story.ui.button/default
      {:doc    "Default state."
       :events [[:button/init]]
       :tags   #{:dev :docs}})
    (let [body (story/handler-meta :variant :story.ui.button/default)]
      (is (= "Default state." (:doc body)))
      (is (= [[:button/init]] (:events body)))
      (is (= #{:dev :docs} (:tags body)))))

  (testing "the variant body is EDN-round-trippable (no fn slots)"
    (story/reg-variant :story.ui.button/edn-test
      {:doc    "EDN check."
       :events [[:button/init]]
       :play-script [[:dispatch-sync [:button/click]]
                [:dispatch-sync [:rf.assert/path-equals [:click] true]]]
       :args   {:label "Hi"}
       :tags   #{:dev}})
    (let [body (story/handler-meta :variant :story.ui.button/edn-test)
          body (dissoc body :source)            ; :source is environment-derived
          edn  (pr-str body)
          round-tripped (read-string edn)]
      (is (= body round-tripped)))))

;; ---- :extends resolution -----------------------------------------------

(deftest extends-resolves-at-registration-time
  (testing ":extends merges parent into child, child wins"
    (story/reg-variant :story.auth.login/loading
      {:events     [[:auth/initialise]
                    [:auth/email-changed "alice@example.com"]
                    [:auth/login-pressed]]
       :decorators [[:force-fx-stub :http {:status :pending}]]
       :tags       #{:dev}})
    (story/reg-variant :story.auth.login/loading-with-prefill
      {:extends :story.auth.login/loading
       :events  [[:auth/initialise]
                 [:auth/email-changed "alice@example.com"]
                 [:auth/password-changed "hunter2"]
                 [:auth/login-pressed]]
       :tags    #{:dev :docs}})
    (let [body (story/handler-meta :variant :story.auth.login/loading-with-prefill)]
      (is (nil? (:extends body)) ":extends is stripped from the resolved body")
      (is (= 4 (count (:events body))) "child :events wins")
      (is (= [[:force-fx-stub :http {:status :pending}]] (:decorators body))
          ":decorators inherited from parent")
      (is (= #{:dev :docs} (:tags body)) "child :tags wins"))))

(deftest extends-unknown-parent
  (testing ":extends to an unregistered variant raises :rf.error/extends-unknown"
    (try
      (story/reg-variant :story.auth.login/child
        {:extends :story.auth.login/no-such-parent
         :events  []})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-extends-unknown (:rf.error/id (ex-data e))))))))

(deftest extends-cycle-detection
  (testing ":extends cycle raises :rf.error/extends-cycle"
    ;; Set up a cycle: a → b → a. We have to use the raw lookup because
    ;; reg-variant would reject the second registration on unknown-parent.
    (let [lookup {:story.a/first  {:extends :story.a/second :events []}
                  :story.a/second {:extends :story.a/first  :events []}}]
      (try
        (extends/resolve-extends (get lookup :story.a/first)
                                 #(get lookup %))
        (is false "expected an exception")
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/story-extends-cycle (:rf.error/id (ex-data e)))))))))

(deftest extends-depth-cap
  (testing ":extends depth cap fires at *max-extends-depth*"
    (let [;; A chain of 50 IDs each pointing to the next; n=49 names the
          ;; deepest, n=0 names the leaf. Resolution from leaf has to
          ;; walk every parent.
          chain-len 50
          chain     (into {}
                          (for [i (range chain-len)]
                            (let [this   (keyword "story.chain" (str "n" i))
                                  parent (when (< (inc i) chain-len)
                                           (keyword "story.chain" (str "n" (inc i))))]
                              [this (cond-> {:events []}
                                      parent (assoc :extends parent))])))]
      (binding [extends/*max-extends-depth* 32]
        (try
          (extends/resolve-extends (get chain :story.chain/n0)
                                   #(get chain %))
          (is false "expected an exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= :rf.error/story-extends-chain-too-long (:rf.error/id (ex-data e))))))))))

;; ---- Form-B desugaring -------------------------------------------------

(deftest form-b-variants-desugar
  (testing "reg-story with :variants emits N reg-variant calls at expansion"
    (story/reg-story :story.auth.login-form
      {:doc       "Login form."
       :component :app.auth/login-form
       :args      {:placeholder "you@example.com"}
       :tags      #{:dev :docs}
       :variants  {:empty            {:events [[:auth/initialise]]
                                      :tags   #{:dev :docs}}
                   :validation-error {:events [[:auth/initialise]
                                               [:auth/email-changed "x"]
                                               [:auth/login-pressed]]
                                      :tags   #{:dev :docs :test}}}})
    (is (story/registered? :story :story.auth.login-form))
    (is (story/registered? :variant :story.auth.login-form/empty))
    (is (story/registered? :variant :story.auth.login-form/validation-error))
    ;; :variants key is stripped from the parent body
    (is (nil? (:variants (story/handler-meta :story :story.auth.login-form))))
    ;; The two variants are independent registrations
    (is (= 2 (count (story/variants-of :story.auth.login-form))))))

(deftest form-b-desugars-to-separate-form-shape
  (testing "Form-B combined authoring produces the same registry bodies as explicit separate forms"
    (story/reg-story :story.formb.combined
      {:doc       "Combined story."
       :component :app.formb/view
       :args      {:label "parent"}
       :tags      #{:dev}
       :variants  {:idle {:events [[:formb/init]]
                           :args   {:state :idle}
                           :tags   #{:dev :test}}
                   :busy {:events [[:formb/init] [:formb/load]]
                          :args   {:state :busy}
                          :tags   #{:dev}}}})
    (let [combined-story (dissoc (story/handler-meta :story :story.formb.combined)
                                 :source)
          combined-idle  (dissoc (story/handler-meta :variant :story.formb.combined/idle)
                                 :source)
          combined-busy  (dissoc (story/handler-meta :variant :story.formb.combined/busy)
                                 :source)]
      (story/clear-all!)
      (story/install-canonical-vocabulary!)
      (story/reg-story :story.formb.separate
        {:doc       "Combined story."
         :component :app.formb/view
         :args      {:label "parent"}
         :tags      #{:dev}})
      (story/reg-variant :story.formb.separate/idle
        {:events [[:formb/init]]
         :args   {:state :idle}
         :tags   #{:dev :test}})
      (story/reg-variant :story.formb.separate/busy
        {:events [[:formb/init] [:formb/load]]
         :args   {:state :busy}
         :tags   #{:dev}})
      (is (= combined-story
             (dissoc (story/handler-meta :story :story.formb.separate) :source)))
      (is (= combined-idle
             (dissoc (story/handler-meta :variant :story.formb.separate/idle) :source)))
      (is (= combined-busy
             (dissoc (story/handler-meta :variant :story.formb.separate/busy) :source)))
      (is (= #{:story.formb.separate/idle :story.formb.separate/busy}
             (story/variants-of :story.formb.separate))))))

;; ---- workspace ---------------------------------------------------------

(deftest reg-workspace-grid
  (testing ":grid workspace requires :variants"
    (story/reg-workspace :Workspace.Auth/all-states
      {:doc      "Auth states."
       :layout   :grid
       :variants [:story.auth.login/empty
                  :story.auth.login/loading]})
    (is (story/registered? :workspace :Workspace.Auth/all-states))))

(deftest reg-workspace-prose
  (testing ":prose workspace requires :content"
    (story/reg-workspace :Workspace.Auth/docs
      {:doc     "Auth docs."
       :layout  :prose
       :content [{:type :prose   :body "## Auth flow"}
                 {:type :variant :id   :story.auth.login/empty}]})
    (is (story/registered? :workspace :Workspace.Auth/docs))))

(deftest reg-workspace-bad-layout
  (testing "a :grid workspace without :variants fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/workspace-shape"
                          (story/reg-workspace :Workspace.bad/empty
                            {:layout :grid})))))

;; ---- mode --------------------------------------------------------------

(deftest reg-mode-saved-tuple
  (testing "reg-mode stores an args tuple"
    (story/reg-mode :Mode.app/dark-mobile
      {:doc  "Dark theme on mobile."
       :args {:theme :dark :viewport :mobile}})
    (is (= {:theme :dark :viewport :mobile}
           (:args (story/handler-meta :mode :Mode.app/dark-mobile))))
    (is (contains? (story/list-modes) :Mode.app/dark-mobile))))

;; ---- story-panel -------------------------------------------------------

(deftest reg-story-panel-causa-shape
  (testing "the canonical Causa embed registration (per 005-SOTA-Features.md §Causa epoch panel embed)"
    (story/reg-story-panel :rf.story/causa-epoch
      {:doc       "Causa's epoch buffer."
       :title     "Epochs (Causa)"
       :placement :bottom
       :render    :day8.re-frame2-causa.panels.time-travel/Panel})
    (let [body (story/handler-meta :story-panel :rf.story/causa-epoch)]
      (is (= "Epochs (Causa)" (:title body)))
      (is (= :bottom (:placement body)))
      (is (= :day8.re-frame2-causa.panels.time-travel/Panel (:render body))))))

;; ---- decorator (per-kind) ---------------------------------------------

(deftest reg-decorator-hiccup
  (testing ":hiccup decorator accepts a fn :wrap (only legal fn-slot)"
    (story/reg-decorator :centered-layout
      {:doc  "Centre the rendered content."
       :kind :hiccup
       :wrap (fn [body _args] [:div.centered body])})
    (let [body (story/handler-meta :decorator :centered-layout)]
      (is (= :hiccup (:kind body)))
      (is (fn? (:wrap body))))))

(deftest reg-decorator-frame-setup
  (testing ":frame-setup decorator requires :init or :app-db-patch"
    (story/reg-decorator :mock-auth
      {:doc  "Inject a mock auth user."
       :kind :frame-setup
       :init [[:auth/restore-session {:user "alice"}]]})
    (is (= :frame-setup (:kind (story/handler-meta :decorator :mock-auth))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/decorator-shape"
                          (story/reg-decorator :mock-empty
                            {:kind :frame-setup})))))

(deftest reg-decorator-fx-override
  (testing ":fx-override decorator names the fx-id + canned response"
    (story/reg-decorator :force-fx-stub
      {:doc      "Stub :http for the variant's frame."
       :kind     :fx-override
       :fx-id    :http
       :response {:status :pending}})
    (let [body (story/handler-meta :decorator :force-fx-stub)]
      (is (= :fx-override (:kind body)))
      (is (= :http (:fx-id body))))))

(deftest reg-decorator-unknown-kind
  (testing "decorator with an unknown :kind fails the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/decorator-shape"
                          (story/reg-decorator :bad-kind
                            {:kind :no-such-kind})))))

;; ---- tag ---------------------------------------------------------------

(deftest reg-tag-project-tag
  (testing "project tags can be registered and then used on variants"
    (story/reg-tag :auth/regression-set
      {:doc "Auth regression-suite variants."})
    (is (story/registered? :tag :auth/regression-set))
    ;; Now a variant tagged with it should validate
    (story/reg-variant :story.auth.login/regression-empty
      {:events [[:auth/initialise]]
       :tags   #{:dev :auth/regression-set}})
    (is (story/registered? :variant :story.auth.login/regression-empty))))

;; ---- :axis + :default-filter slots (rf2-frtec / SB9 parity) ------------

(deftest reg-tag-stores-axis
  (testing ":axis is stored on the registered tag body"
    (story/reg-tag :auth/regression-set
      {:doc  "Auth regression-suite variants."
       :axis :team})
    (is (= :team (:axis (story/handler-meta :tag :auth/regression-set))))))

(deftest reg-tag-stores-default-filter
  (testing ":default-filter is stored on the registered tag body"
    (story/reg-tag :status/alpha
      {:doc            "Pre-release status."
       :axis           :status
       :default-filter :exclude})
    (let [body (story/handler-meta :tag :status/alpha)]
      (is (= :status (:axis body)))
      (is (= :exclude (:default-filter body))))))

(deftest reg-tag-without-axis-defaults-sanely
  (testing "tags without :axis / :default-filter remain valid and queryable"
    ;; Canonical tags carry neither slot — they're pre-installed by the
    ;; fixture's `install-canonical-vocabulary!`. Confirm they're absent
    ;; from every axis-keyed lookup and the default-excluded set.
    (is (= #{} (story/tags-by-axis :status)))
    (is (= #{} (story/tags-by-axis :role)))
    (is (= #{} (story/tags-default-excluded)))
    ;; And the canonical seven all live in the un-axis-grouped bucket.
    (is (= schemas/canonical-tags (story/tags-without-axis)))))

(deftest tags-by-axis-filters-correctly
  (testing "tags-by-axis returns only tags registered on the requested axis"
    (story/reg-tag :status/alpha       {:axis :status :default-filter :exclude})
    (story/reg-tag :status/beta        {:axis :status})
    (story/reg-tag :role/dev           {:axis :role})
    (story/reg-tag :auth/regression    {:axis :team})
    (story/reg-tag :no-axis/freeform   {:doc "no axis here"})
    (is (= #{:status/alpha :status/beta} (story/tags-by-axis :status)))
    (is (= #{:role/dev}                  (story/tags-by-axis :role)))
    (is (= #{:auth/regression}           (story/tags-by-axis :team)))
    (is (= #{} (story/tags-by-axis :nonexistent)))
    ;; un-axis-grouped tag sits in tags-without-axis alongside the canonical seven
    (is (contains? (story/tags-without-axis) :no-axis/freeform))
    (is (not (contains? (story/tags-by-axis :status) :no-axis/freeform)))))

(deftest tags-default-excluded-filters-correctly
  (testing "tags-default-excluded returns only tags with :default-filter :exclude"
    (story/reg-tag :status/alpha    {:axis :status :default-filter :exclude})
    (story/reg-tag :status/beta     {:axis :status :default-filter :include})
    (story/reg-tag :status/stable   {:axis :status})                ; no slot — defaults to include
    (story/reg-tag :hidden/internal {:default-filter :exclude})
    (is (= #{:status/alpha :hidden/internal} (story/tags-default-excluded)))))

(deftest reg-tag-rejects-bad-default-filter
  (testing ":default-filter must be :include or :exclude"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/tag-shape"
                          (story/reg-tag :bad/df
                            {:default-filter :sometimes})))))

(deftest reg-tag-rejects-non-keyword-axis
  (testing ":axis must be a keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/tag-shape"
                          (story/reg-tag :bad/axis
                            {:axis "status"})))))

;; ---- !-prefix removal syntax -------------------------------------------

(deftest tags-with-bang-prefix-validate
  (testing "the !-prefix removal syntax passes tag validation"
    ;; A variant body's :tags may carry :!dev to remove :dev from the
    ;; inherited tag set. The registrar accepts these as long as the
    ;; base (un-prefixed) tag is registered.
    (story/reg-variant :story.bang/test
      {:events []
       :tags   #{:!dev :docs}})
    (is (story/registered? :variant :story.bang/test))))

(deftest tags-with-bang-prefix-rejects-unknown
  (testing "the !-prefix variant rejects unknown base tags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/unknown-tag"
                          (story/reg-variant :story.bang/bad
                            {:events []
                             :tags   #{:!totally-unknown}})))))

;; ---- query API ---------------------------------------------------------

(deftest variants-of-finds-children
  (testing "variants-of returns only the variants of the requested story"
    (story/reg-variant :story.foo/a {:events []})
    (story/reg-variant :story.foo/b {:events []})
    (story/reg-variant :story.bar/c {:events []})
    (is (= #{:story.foo/a :story.foo/b} (story/variants-of :story.foo)))
    (is (= #{:story.bar/c}              (story/variants-of :story.bar)))))

(deftest variants-of-empty-when-no-children
  (testing "variants-of returns empty set when the story has no registered variants"
    (is (= #{} (story/variants-of :story.no-variants)))
    (story/reg-variant :story.other/x {:events []})
    (is (= #{} (story/variants-of :story.no-variants)))))

(deftest variants-of-rejects-nested-namespace
  (testing "variants-of must NOT return variants of a deeper-namespaced story
            — guards against the old string-prefix shape where
            `:story.foo.bar/x` was a structurally-suspect 'prefix match' of
            `:story.foo`. The namespace-equality check rules it out by
            construction."
    (story/reg-variant :story.foo/a     {:events []})
    (story/reg-variant :story.foo.bar/x {:events []})
    (story/reg-variant :story.foo.bar/y {:events []})
    (is (= #{:story.foo/a}                       (story/variants-of :story.foo)))
    (is (= #{:story.foo.bar/x :story.foo.bar/y}  (story/variants-of :story.foo.bar)))))

(deftest variants-of-short-and-bare-story
  (testing "variants-of works for the bare `:story` root and short names"
    (story/reg-variant :story/root {:events []})
    (story/reg-variant :story.a/v  {:events []})
    (is (= #{:story/root} (story/variants-of :story)))
    (is (= #{:story.a/v}  (story/variants-of :story.a)))))

(deftest variants-by-story-single-pass-index
  (testing "variants-by-story builds a {story-id #{variant-ids}} index in one pass (rf2-d3iso)"
    (story/reg-story   :story.foo {})
    (story/reg-story   :story.bar {})
    (story/reg-story   :story.empty {})
    (story/reg-variant :story.foo/a {:events []})
    (story/reg-variant :story.foo/b {:events []})
    (story/reg-variant :story.bar/c {:events []})
    (let [idx (story/variants-by-story)]
      (is (= #{:story.foo/a :story.foo/b} (get idx :story.foo)))
      (is (= #{:story.bar/c}              (get idx :story.bar)))
      (is (= #{}                          (get idx :story.empty))
          "stories with zero variants land with an empty set"))))

(deftest variants-by-story-matches-variants-of
  (testing "variants-by-story's per-story slot matches `variants-of`'s output (rf2-d3iso)"
    (story/reg-story   :story.aa {})
    (story/reg-story   :story.bb {})
    (story/reg-variant :story.aa/one   {:events []})
    (story/reg-variant :story.aa/two   {:events []})
    (story/reg-variant :story.bb/three {:events []})
    (let [idx (story/variants-by-story)]
      (doseq [sid [:story.aa :story.bb]]
        (is (= (story/variants-of sid) (get idx sid))
            (str sid " — single-pass index must match the per-story scan"))))))

(deftest variants-with-tags-intersection
  (testing "variants-with-tags returns variants whose :tags intersects the query"
    (story/reg-variant :story.tag/a {:events [] :tags #{:dev :test}})
    (story/reg-variant :story.tag/b {:events [] :tags #{:dev :docs}})
    (story/reg-variant :story.tag/c {:events [] :tags #{:test}})
    (is (= #{:story.tag/a :story.tag/c} (story/variants-with-tags #{:test})))
    (is (= #{:story.tag/a :story.tag/b} (story/variants-with-tags #{:docs :dev})))))

(deftest all-kinds-with-counts-reflects-state
  (testing "all-kinds-with-counts mirrors the side-table"
    (story/reg-story   :story.x   {:doc "x"})
    (story/reg-variant :story.x/v {:events []})
    (let [counts (story/all-kinds-with-counts)]
      (is (= 1 (:story   counts)))
      (is (= 1 (:variant counts)))
      (is (= (count schemas/canonical-tags) (:tag counts))))))

;; ---- variant->edn ----------------------------------------------------

(deftest variant->edn-returns-body
  (testing "variant->edn returns the registered body verbatim"
    (story/reg-variant :story.edn/x
      {:events [[:init]]
       :tags   #{:dev}})
    (let [edn (story/variant->edn :story.edn/x)]
      (is (= [[:init]] (:events edn)))
      (is (= #{:dev}    (:tags edn))))))

;; ---- elision sentinel ------------------------------------------------

(deftest config-flag-controls-expansion
  (testing "re-frame.story.config/enabled? is true at JVM-test time"
    (is (true? story-config/enabled?))))

;; ---- static-mode? (rf2-8wgpm) ----------------------------------------

(deftest static-mode-defaults-false-on-jvm
  (testing "re-frame.story.config/static-mode? defaults to false on the JVM"
    ;; Per tools/story/spec/013-Static-Build.md the JVM-side def is a
    ;; plain const false — JVM consumers never operate in static mode
    ;; (the flag exists for CLJS :advanced builds via :closure-defines).
    (is (false? story-config/static-mode?)))
  (testing "the public probe (re-frame.story/static-mode?) reflects the flag"
    (is (false? (re-frame.story/static-mode?)))))

;; ---- registrar mutation tick (rf2-zrswb) ----------------------------

(deftest mutation-tick-bumps-on-every-write
  (testing "every reg-* / unregister! / clear-* call bumps the tick;
            consumers caching registry-derived work key off this counter"
    (let [t0 (registrar/current-mutation-tick)]
      (story/reg-story :story.ui.tick {:doc "tick test"})
      (is (> (registrar/current-mutation-tick) t0))
      (let [t1 (registrar/current-mutation-tick)]
        (story/reg-variant :story.ui.tick/v {:events [[:init]]})
        (is (> (registrar/current-mutation-tick) t1))
        (let [t2 (registrar/current-mutation-tick)]
          (registrar/unregister! :variant :story.ui.tick/v)
          (is (> (registrar/current-mutation-tick) t2))
          (let [t3 (registrar/current-mutation-tick)]
            (registrar/clear-kind! :variant)
            (is (> (registrar/current-mutation-tick) t3))))))))

(deftest mutation-tick-is-monotonic
  (testing "the tick only ever advances — never resets to a smaller value"
    (let [t0 (registrar/current-mutation-tick)]
      (dotimes [i 5]
        (story/reg-variant (keyword (str "story.tick.mono/v" i))
                           {:events [[:init]]}))
      (is (>= (registrar/current-mutation-tick) (+ t0 5))))))

(deftest variants-with-tags-memoised-on-mutation-tick
  (testing "variants-with-tags returns cached results between two registrar writes (rf2-c5nwl)"
    (story/reg-tag :status/stable {:axis :status})
    (story/reg-tag :role/dev      {:axis :role})
    (story/reg-variant :story.memo/a {:tags #{:status/stable} :events []})
    (story/reg-variant :story.memo/b {:tags #{:role/dev}     :events []})
    (story/reg-variant :story.memo/c {:tags #{:status/stable :role/dev} :events []})
    (let [r1 (registrar/variants-with-tags #{:status/stable})
          r2 (registrar/variants-with-tags #{:status/stable})]
      (testing "same query between writes returns identical (cache-hit) set"
        (is (identical? r1 r2))
        (is (= #{:story.memo/a :story.memo/c} r1))))
    (testing "different query in same tick is also cached + correct"
      (let [r-role (registrar/variants-with-tags #{:role/dev})]
        (is (= #{:story.memo/b :story.memo/c} r-role))))
    (testing "registrar mutation invalidates the cache"
      (story/reg-variant :story.memo/d {:tags #{:status/stable} :events []})
      (let [r3 (registrar/variants-with-tags #{:status/stable})]
        (is (= #{:story.memo/a :story.memo/c :story.memo/d} r3))))))

;; ---- Public tag->axis-index API -------------------------------------

(deftest public-tag-axis-index-no-axis-sentinel
  (testing "story/tag->axis-index returns the ::no-axis sentinel for tags
without :axis (rf2-jlsvj — lock the public-API contract)"
    (story/reg-tag :status/stable  {:axis :status})
    (story/reg-tag :role/dev       {:axis :role})
    (story/reg-tag :loose/freeform {:doc "no axis on this tag"})
    (let [idx (story/tag->axis-index)]
      (is (map? idx))
      (testing "axis-bearing tags map to their axis"
        (is (= :status (get idx :status/stable)))
        (is (= :role   (get idx :role/dev))))
      (testing "tags registered without :axis map to the registrar/no-axis sentinel"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :loose/freeform))))
      (testing "canonical tags are pre-registered without :axis and bucket to no-axis"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :dev)))))))

;; ---- Stage 6 contract check -----------------------------------------

(deftest stage-marker
  (testing "the loaded surface advertises Stage 6 (sota-features)"
    (is (= :sota-features re-frame.story/stage))))
