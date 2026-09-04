(ns re-frame.story-test
  "JVM tests for re-frame2-story Stage 2 (rf2-32dk).

  Covers:

  - Macro expansion → registry write round-trip.
  - Body shape validation (`:rf.error/<kind>-shape`).
  - Tag membership (`:rf.error/unknown-tag`).
  - `:extends` raw storage at registration + unknown-parent detection at
    plan-compile (the compiler is the merge authority; cycle and depth-cap
    detection live with it in `re-frame.story.plan-test`).
  - Form-B `:variants` desugaring.
  - Source-coord stamping.
  - Query API (`registrations`, `handler-meta`, `variants-with-tags`,
    `variants-of`).
  - EDN-round-trip of variant bodies (no fn-valued slots).
  - Canonical-id-grammar enforcement.

  JVM-runnable because the registration surface is pure data — no
  Reagent / DOM / shadow-cljs required. Per `001-Authoring.md`
  §Registration macros + the
  `jvm_interop_must_work` user-feedback rule, every artefact that can
  run on the JVM should."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
  ;; rf2-tag30h — the STRING-level variant-id grammar that the MCP write
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

(deftest canonical-tags-installed
  (testing "the seven canonical inclusion tags + five canonical :state/* magnitude tags are registered after boot"
    (let [expected (into rf.story.schemas/canonical-tags rf.story.schemas/canonical-state-tags)]
      (is (= expected (rf.story/list-tags)))
      (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-tags))
      (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-state-tags)))))

(deftest canonical-state-axis-installed
  (testing "the five :state/* tags carry the :state axis (rf2-k1k87)"
    (let [by-axis (rf.story/tags-by-axis :state)]
      (is (= rf.story.schemas/canonical-state-tags by-axis)))))

;; ---- auto-install on first reg-* call (rf2-p1ydc) ----------------------
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
    ;; seven first, so subsequent variants tagged `:dev` still validate.
    (rf.story/reg-tag :auth/regression-set {:doc "Auth regression-suite."})
    (is (rf.story/registered? :tag :auth/regression-set))
    (is (every? #(rf.story/registered? :tag %) rf.story.schemas/canonical-tags)
        "canonical tags ride along with the first reg-tag too")))

(deftest auto-install-is-idempotent
  (testing "subsequent reg-* calls do NOT re-trigger the installer chain"
    (rf.story/clear-all!)
    (rf.story/reg-story :story.idem.a {:tags #{:dev}})
    ;; A subsequent registration must not break, must not re-install
    ;; (we observe idempotency indirectly: the side-table stays
    ;; consistent and no exception fires).
    (let [tags-after-first (rf.story/list-tags)]
      (rf.story/reg-story :story.idem.b {:tags #{:docs}})
      (rf.story/reg-variant :story.idem.a/v {:tags #{:dev} :setup []})
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
    ;; Author who DOES make the explicit call (the v1 documented path)
    ;; still works: the gate is true when the first reg-* fires, so
    ;; the auto-install hook hits the early-return branch.
    (rf.story/clear-all!)
    (rf.story/install-canonical-vocabulary!)
    (is (true? @rf.story.canonical/installed?))
    ;; The first reg-* must NOT throw and must NOT recompute the
    ;; installer chain (no easy direct probe — but no exception +
    ;; correct registry shape is the contract).
    (rf.story/reg-story :story.explicit-boot.probe {:tags #{:dev}})
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

(deftest reg-story-basic
  (testing "reg-story writes to the side-table under :story kind"
    (rf.story/reg-story :story.ui.button
      {:doc       "Primary action button."
       :component :app.ui/button
       :args      {:label "Click me"}
       :tags      #{:dev :docs}})
    (is (= #{:story.ui.button} (rf.story/ids :story)))
    (let [body (rf.story/handler-meta :story :story.ui.button)]
      (is (= "Primary action button." (:doc body)))
      (is (= :app.ui/button (:component body)))
      (is (= {:label "Click me"} (:args body)))
      (is (= #{:dev :docs} (:tags body)))))

  (testing "source-coord is stamped onto the registered body"
    (rf.story/reg-story :story.ui.icon {:doc "Icon."})
    (let [body (rf.story/handler-meta :story :story.ui.icon)]
      (is (map? (:source body)))
      (is (= 're-frame.story-test (:ns (:source body))))
      (is (integer? (:line (:source body)))))))

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

;; ---- reg-variant basic -------------------------------------------------

(deftest reg-variant-basic
  (testing "reg-variant writes a variant under :variant kind"
    (rf.story/reg-variant :story.ui.button/default
      {:doc    "Default state."
       :setup [[:button/init]]
       :tags   #{:dev :docs}})
    (let [body (rf.story/handler-meta :variant :story.ui.button/default)]
      (is (= "Default state." (:doc body)))
      (is (= [[:button/init]] (:setup body)))
      (is (= #{:dev :docs} (:tags body)))))

  (testing "the variant body is EDN-round-trippable (no fn slots)"
    (rf.story/reg-variant :story.ui.button/edn-test
      {:doc    "EDN check."
       :setup [[:button/init]]
       :script [[:dispatch-sync [:button/click]]
                [:dispatch-sync [:rf.assert/path-equals [:click] true]]]
       :args   {:label "Hi"}
       :tags   #{:dev}})
    (let [body (rf.story/handler-meta :variant :story.ui.button/edn-test)
          body (dissoc body :source)            ; :source is environment-derived
          edn  (pr-str body)
          round-tripped (read-string edn)]
      (is (= body round-tripped)))))

;; ---- :extends resolution -----------------------------------------------

(deftest extends-stored-raw-at-registration-resolved-by-compiler
  (testing ":extends is stored RAW at registration (`:extends` intact,
            parent NOT merged); the PLAN COMPILER is the single merge
            authority (rf2-f6z88, spec/017 §305-306). The side-table body
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

(deftest extends-unknown-parent
  (testing ":extends to an unregistered parent no longer throws at
            REGISTRATION (rf2-f6z88 — the raw body is stored with
            `:extends` intact); the error surfaces at PLAN-COMPILE, where
            the compiler is the merge authority and walks the chain
            (spec/017 §305-306)."
    ;; Registration succeeds — the raw body is stored, :extends intact.
    (rf.story/reg-variant :story.auth.login/child
      {:extends :story.auth.login/no-such-parent
       :setup  []})
    (is (= :story.auth.login/no-such-parent
           (:extends (rf.story/handler-meta :variant :story.auth.login/child)))
        "registration stores the raw body with the unknown parent intact")
    ;; Plan compile is where the unknown parent FAILS.
    (try
      (rf.story.plan/variant-plan :story.auth.login/child)
      (is false "expected a plan-compile exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-extends-unknown (:rf.error/id (ex-data e))))))))

;; Cycle detection and the depth cap were witnessed here against the
;; standalone `re-frame.story.extends` resolver, which was retired
;; (rf2-6r9j.11) — it had drifted from the compiled-plan merge semantics, so a
;; green witness there proved nothing about the shipped runtime. Both now sit
;; on the merge authority in `re-frame.story.plan-test`
;; (§`extends-cycle-fails`, §`extends-depth-cap-fails`). Being `.cljc` they are
;; host-free, but the gate that RUNS them is the JVM one (`jvm-tools-story` /
;; `clojure -M:test` here) — the CLJS `:node-test` build selects on
;; `cljs-test$`, which a plain `-test` namespace does not match. Same reach as
;; the JVM-only witnesses they replaced.

;; ---- Form-B desugaring -------------------------------------------------

(deftest form-b-variants-desugar
  (testing "reg-story with :variants emits N reg-variant calls at expansion"
    (rf.story/reg-story :story.auth.login-form
      {:doc       "Login form."
       :component :app.auth/login-form
       :args      {:placeholder "you@example.com"}
       :tags      #{:dev :docs}
       :variants  {:empty            {:setup [[:auth/initialise]]
                                      :tags   #{:dev :docs}}
                   :validation-error {:setup [[:auth/initialise]
                                               [:auth/email-changed "x"]
                                               [:auth/login-pressed]]
                                      :tags   #{:dev :docs :test}}}})
    (is (rf.story/registered? :story :story.auth.login-form))
    (is (rf.story/registered? :variant :story.auth.login-form/empty))
    (is (rf.story/registered? :variant :story.auth.login-form/validation-error))
    ;; :variants key is stripped from the parent body
    (is (nil? (:variants (rf.story/handler-meta :story :story.auth.login-form))))
    ;; The two variants are independent registrations
    (is (= 2 (count (rf.story/variants-of :story.auth.login-form))))))

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

(deftest reg-workspace-grid
  (testing ":grid workspace requires :variants"
    (rf.story/reg-workspace :Workspace.Auth/all-states
      {:doc      "Auth states."
       :layout   :grid
       :variants [:story.auth.login/empty
                  :story.auth.login/loading]})
    (is (rf.story/registered? :workspace :Workspace.Auth/all-states))))

(deftest reg-workspace-prose
  (testing ":prose workspace requires :content"
    (rf.story/reg-workspace :Workspace.Auth/docs
      {:doc     "Auth docs."
       :layout  :prose
       :content [{:type :prose   :body "## Auth flow"}
                 {:type :variant :id   :story.auth.login/empty}]})
    (is (rf.story/registered? :workspace :Workspace.Auth/docs))))

(deftest reg-workspace-bad-layout
  (testing "a :grid workspace without :variants fails validation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/workspace-shape"
                          (rf.story/reg-workspace :Workspace.bad/empty
                            {:layout :grid})))))

;; ---- mode --------------------------------------------------------------

(deftest reg-mode-saved-tuple
  (testing "reg-mode stores an args tuple"
    (rf.story/reg-mode :Mode.app/dark-mobile
      {:doc  "Dark theme on mobile."
       :args {:theme :dark :viewport :mobile}})
    (is (= {:theme :dark :viewport :mobile}
           (:args (rf.story/handler-meta :mode :Mode.app/dark-mobile))))
    (is (contains? (rf.story/list-modes) :Mode.app/dark-mobile))))

;; ---- story-panel -------------------------------------------------------

(deftest reg-story-panel-xray-shape
  (testing "the canonical Xray embed registration (per 005-SOTA-Features.md §Xray epoch panel embed)"
    (rf.story/reg-story-panel :rf.story/xray-epoch
      {:doc       "Xray's epoch buffer."
       :title     "Epochs (Xray)"
       :placement :bottom
       :render    :day8.re-frame2-xray.panels.time-travel/Panel})
    (let [body (rf.story/handler-meta :story-panel :rf.story/xray-epoch)]
      (is (= "Epochs (Xray)" (:title body)))
      (is (= :bottom (:placement body)))
      (is (= :day8.re-frame2-xray.panels.time-travel/Panel (:render body))))))

;; ---- decorator (per-kind) ---------------------------------------------

(deftest reg-decorator-hiccup
  (testing ":hiccup decorator accepts a fn :wrap (only legal fn-slot)"
    (rf.story/reg-decorator :centered-layout
      {:doc  "Centre the rendered content."
       :kind :hiccup
       :wrap (fn [body _args] [:div.centered body])})
    (let [body (rf.story/handler-meta :decorator :centered-layout)]
      (is (= :hiccup (:kind body)))
      (is (fn? (:wrap body))))))

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

(deftest reg-decorator-fx-override
  (testing ":fx-override decorator names the fx-id + canned response"
    (rf.story/reg-decorator :force-fx-stub
      {:doc      "Stub :http for the variant's frame."
       :kind     :fx-override
       :fx-id    :http
       :response {:status :pending}})
    (let [body (rf.story/handler-meta :decorator :force-fx-stub)]
      (is (= :fx-override (:kind body)))
      (is (= :http (:fx-id body))))))

(deftest reg-decorator-unknown-kind
  (testing "decorator with an unknown :kind fails the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/decorator-shape"
                          (rf.story/reg-decorator :bad-kind
                            {:kind :no-such-kind})))))

;; ---- tag ---------------------------------------------------------------

(deftest reg-tag-project-tag
  (testing "project tags can be registered and then used on variants"
    (rf.story/reg-tag :auth/regression-set
      {:doc "Auth regression-suite variants."})
    (is (rf.story/registered? :tag :auth/regression-set))
    ;; Now a variant tagged with it should validate
    (rf.story/reg-variant :story.auth.login/regression-empty
      {:setup [[:auth/initialise]]
       :tags   #{:dev :auth/regression-set}})
    (is (rf.story/registered? :variant :story.auth.login/regression-empty))))

;; ---- :axis + :default-filter slots (rf2-frtec / SB9 parity) ------------

(deftest reg-tag-stores-axis
  (testing ":axis is stored on the registered tag body"
    (rf.story/reg-tag :auth/regression-set
      {:doc  "Auth regression-suite variants."
       :axis :team})
    (is (= :team (:axis (rf.story/handler-meta :tag :auth/regression-set))))))

(deftest reg-tag-stores-default-filter
  (testing ":default-filter is stored on the registered tag body"
    (rf.story/reg-tag :status/alpha
      {:doc            "Pre-release status."
       :axis           :status
       :default-filter :exclude})
    (let [body (rf.story/handler-meta :tag :status/alpha)]
      (is (= :status (:axis body)))
      (is (= :exclude (:default-filter body))))))

(deftest reg-tag-without-axis-defaults-sanely
  (testing "tags without :axis / :default-filter remain valid and queryable"
    ;; Canonical inclusion tags carry neither slot — they're pre-installed
    ;; by the fixture's `install-canonical-vocabulary!`. Confirm they're
    ;; absent from every non-:state axis-keyed lookup and the
    ;; default-excluded set. (The :state axis is populated by the rf2-k1k87
    ;; install-canonical-tags! extension and is covered separately.)
    (is (= #{} (rf.story/tags-by-axis :status)))
    (is (= #{} (rf.story/tags-by-axis :role)))
    (is (= #{} (rf.story/tags-default-excluded)))
    ;; The seven canonical INCLUSION tags live in the un-axis-grouped bucket.
    ;; (The :state/* tags carry :axis :state so they're NOT in tags-without-axis.)
    (is (= rf.story.schemas/canonical-tags (rf.story/tags-without-axis)))))

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
    ;; un-axis-grouped tag sits in tags-without-axis alongside the canonical seven
    (is (contains? (rf.story/tags-without-axis) :no-axis/freeform))
    (is (not (contains? (rf.story/tags-by-axis :status) :no-axis/freeform)))))

(deftest tags-default-excluded-filters-correctly
  (testing "tags-default-excluded returns only tags with :default-filter :exclude"
    (rf.story/reg-tag :status/alpha    {:axis :status :default-filter :exclude})
    (rf.story/reg-tag :status/beta     {:axis :status :default-filter :include})
    (rf.story/reg-tag :status/stable   {:axis :status})                ; no slot — defaults to include
    (rf.story/reg-tag :hidden/internal {:default-filter :exclude})
    (is (= #{:status/alpha :hidden/internal} (rf.story/tags-default-excluded)))))

(deftest reg-tag-rejects-bad-default-filter
  (testing ":default-filter must be :include or :exclude"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/tag-shape"
                          (rf.story/reg-tag :bad/df
                            {:default-filter :sometimes})))))

(deftest reg-tag-rejects-non-keyword-axis
  (testing ":axis must be a keyword"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/tag-shape"
                          (rf.story/reg-tag :bad/axis
                            {:axis "status"})))))

;; ---- !-prefix removal syntax -------------------------------------------

(deftest tags-with-bang-prefix-validate
  (testing "the !-prefix removal syntax passes tag validation"
    ;; A variant body's :tags may carry :!dev to remove :dev from the
    ;; inherited tag set. The registrar accepts these as long as the
    ;; base (un-prefixed) tag is registered.
    (rf.story/reg-variant :story.bang/test
      {:setup []
       :tags   #{:!dev :docs}})
    (is (rf.story/registered? :variant :story.bang/test))))

(deftest tags-with-bang-prefix-rejects-unknown
  (testing "the !-prefix variant rejects unknown base tags"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #":rf\.error/unknown-tag"
                          (rf.story/reg-variant :story.bang/bad
                            {:setup []
                             :tags   #{:!totally-unknown}})))))

;; ---- query API ---------------------------------------------------------

(deftest variants-of-finds-children
  (testing "variants-of returns only the variants of the requested story"
    (rf.story/reg-variant :story.foo/a {:setup []})
    (rf.story/reg-variant :story.foo/b {:setup []})
    (rf.story/reg-variant :story.bar/c {:setup []})
    (is (= #{:story.foo/a :story.foo/b} (rf.story/variants-of :story.foo)))
    (is (= #{:story.bar/c}              (rf.story/variants-of :story.bar)))))

(deftest variants-of-empty-when-no-children
  (testing "variants-of returns empty set when the story has no registered variants"
    (is (= #{} (rf.story/variants-of :story.no-variants)))
    (rf.story/reg-variant :story.other/x {:setup []})
    (is (= #{} (rf.story/variants-of :story.no-variants)))))

(deftest variants-of-rejects-nested-namespace
  (testing "variants-of must NOT return variants of a deeper-namespaced story
            — guards against the old string-prefix shape where
            `:story.foo.bar/x` was a structurally-suspect 'prefix match' of
            `:story.foo`. The namespace-equality check rules it out by
            construction."
    (rf.story/reg-variant :story.foo/a     {:setup []})
    (rf.story/reg-variant :story.foo.bar/x {:setup []})
    (rf.story/reg-variant :story.foo.bar/y {:setup []})
    (is (= #{:story.foo/a}                       (rf.story/variants-of :story.foo)))
    (is (= #{:story.foo.bar/x :story.foo.bar/y}  (rf.story/variants-of :story.foo.bar)))))

(deftest variants-of-short-and-bare-story
  (testing "variants-of works for the bare `:story` root and short names"
    (rf.story/reg-variant :story/root {:setup []})
    (rf.story/reg-variant :story.a/v  {:setup []})
    (is (= #{:story/root} (rf.story/variants-of :story)))
    (is (= #{:story.a/v}  (rf.story/variants-of :story.a)))))

(deftest variants-by-story-single-pass-index
  (testing "variants-by-story builds a {story-id #{variant-ids}} index in one pass (rf2-d3iso)"
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

(deftest variants-by-story-matches-variants-of
  (testing "variants-by-story's per-story slot matches `variants-of`'s output (rf2-d3iso)"
    (rf.story/reg-story   :story.aa {})
    (rf.story/reg-story   :story.bb {})
    (rf.story/reg-variant :story.aa/one   {:setup []})
    (rf.story/reg-variant :story.aa/two   {:setup []})
    (rf.story/reg-variant :story.bb/three {:setup []})
    (let [idx (rf.story/variants-by-story)]
      (doseq [sid [:story.aa :story.bb]]
        (is (= (rf.story/variants-of sid) (get idx sid))
            (str sid " — single-pass index must match the per-story scan"))))))

(deftest variants-with-tags-intersection
  (testing "variants-with-tags returns variants whose :tags intersects the query"
    (rf.story/reg-variant :story.tag/a {:setup [] :tags #{:dev :test}})
    (rf.story/reg-variant :story.tag/b {:setup [] :tags #{:dev :docs}})
    (rf.story/reg-variant :story.tag/c {:setup [] :tags #{:test}})
    (is (= #{:story.tag/a :story.tag/c} (rf.story/variants-with-tags #{:test})))
    (is (= #{:story.tag/a :story.tag/b} (rf.story/variants-with-tags #{:docs :dev})))))

(deftest variants-with-tags-excludes-marker-removed-inherited-tag
  (testing "rf2-n0vmq2 — a child that :extends a :dev-tagged parent and
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
  (testing "rf2-n0vmq2 — a variant that declares no tags inherits its parent
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

;; ---- variant->edn ----------------------------------------------------

(deftest variant->edn-returns-body
  (testing "variant->edn returns the registered body verbatim"
    (rf.story/reg-variant :story.edn/x
      {:setup [[:init]]
       :tags   #{:dev}})
    (let [edn (rf.story/variant->edn :story.edn/x)]
      (is (= [[:init]] (:setup edn)))
      (is (= #{:dev}    (:tags edn))))))

;; ---- elision sentinel ------------------------------------------------

(deftest config-flag-controls-expansion
  (testing "re-frame.story.config/enabled? is true at JVM-test time"
    (is (true? rf.story.config/enabled?))))

;; ---- static-mode? (rf2-8wgpm) ----------------------------------------

(deftest static-mode-defaults-false-on-jvm
  (testing "re-frame.story.config/static-mode? defaults to false on the JVM"
    ;; Per tools/story/spec/013-Static-Build.md the JVM-side def is a
    ;; plain const false — JVM consumers never operate in static mode
    ;; (the flag exists for CLJS :advanced builds via :closure-defines).
    (is (false? rf.story.config/static-mode?)))
  (testing "the public probe (re-frame.story/static-mode?) reflects the flag"
    (is (false? (re-frame.story/static-mode?)))))

;; ---- registrar mutation tick (rf2-zrswb) ----------------------------

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

(deftest mutation-tick-is-monotonic
  (testing "the tick only ever advances — never resets to a smaller value"
    (let [t0 (rf.story.registrar/current-mutation-tick)]
      (dotimes [i 5]
        (rf.story/reg-variant (keyword (str "story.tick.mono/v" i))
                           {:setup [[:init]]}))
      (is (>= (rf.story.registrar/current-mutation-tick) (+ t0 5))))))

(deftest variants-with-tags-memoised-on-mutation-tick
  (testing "variants-with-tags returns cached results between two registrar writes (rf2-c5nwl)"
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
without :axis (rf2-jlsvj — lock the public-API contract)"
    (rf.story/reg-tag :status/stable  {:axis :status})
    (rf.story/reg-tag :role/dev       {:axis :role})
    (rf.story/reg-tag :loose/freeform {:doc "no axis on this tag"})
    (let [idx (rf.story/tag->axis-index)]
      (is (map? idx))
      (testing "axis-bearing tags map to their axis"
        (is (= :status (get idx :status/stable)))
        (is (= :role   (get idx :role/dev))))
      (testing "tags registered without :axis map to the rf.story.registrar/no-axis sentinel"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :loose/freeform))))
      (testing "canonical tags are pre-registered without :axis and bucket to no-axis"
        (is (= :re-frame.story.registrar/no-axis
               (get idx :dev)))))))

