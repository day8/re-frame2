(ns re-frame.story-test
  "JVM tests for re-frame2-story Stage 2 (rf2-32dk).

  Covers:

  - Macro expansion → registry write round-trip.
  - Body shape validation (`:rf.error/<kind>-shape`).
  - Tag membership (`:rf.error/unknown-tag`).
  - `:extends` resolution + cycle / unknown-parent detection.
  - Form-B `:variants` desugaring.
  - Source-coord stamping.
  - Query API (`handlers`, `handler-meta`, `variants-with-tags`,
    `variants-of`).
  - EDN-round-trip of variant bodies (no fn-valued slots).
  - Canonical-id-grammar enforcement.

  JVM-runnable because the registration surface is pure data — no
  Reagent / DOM / shadow-cljs required. Per IMPL-SPEC §1.1 + the
  `jvm_interop_must_work` user-feedback rule, every artefact that can
  run on the JVM should."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as story]
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
                          #"does not match the canonical id grammar"
                          (story/reg-story* :NotAStoryId {})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match the canonical id grammar"
                          (story/reg-story* :foo.bar {})))))

(deftest reg-story-bad-shape
  (testing "reg-story rejects a body that violates the schema"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not match story schema"
                          (story/reg-story :story.ui.bad
                            {:tags "not-a-set"})))))

(deftest reg-story-unknown-tag
  (testing "reg-story raises :rf.error/unknown-tag on an unregistered tag"
    (try
      (story/reg-story :story.ui.bad {:tags #{:dev :totally-made-up}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/unknown-tag (:rf.error (ex-data e))))
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
       :play   [[:button/click] [:rf.assert/path-equals [:click] true]]
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
        (is (= :rf.error/extends-unknown (:rf.error (ex-data e))))))))

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
          (is (= :rf.error/extends-cycle (:rf.error (ex-data e)))))))))

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
            (is (= :rf.error/extends-cycle (:rf.error (ex-data e))))))))))

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
                          #"does not match workspace schema"
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

(deftest reg-story-panel-10x-shape
  (testing "the canonical re-frame-10x embed registration"
    (story/reg-story-panel :rf.story/epoch-10x
      {:doc       "re-frame-10x's epoch buffer."
       :title     "Epochs (10x)"
       :placement :bottom
       :render    :re-frame-10x.epoch-panel/view})
    (let [body (story/handler-meta :story-panel :rf.story/epoch-10x)]
      (is (= "Epochs (10x)" (:title body)))
      (is (= :bottom (:placement body)))
      (is (= :re-frame-10x.epoch-panel/view (:render body))))))

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
                          #"does not match decorator schema"
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
                          #"does not match decorator schema"
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
                          #"unregistered tag"
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

;; ---- Stage 6 contract check -----------------------------------------

(deftest stage-marker
  (testing "the loaded surface advertises Stage 6 (sota-features)"
    (is (= :sota-features re-frame.story/stage))))
