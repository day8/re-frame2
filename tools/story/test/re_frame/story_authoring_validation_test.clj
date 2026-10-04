(ns re-frame.story-authoring-validation-test
  "JVM tests closing the docs-promised gap on macro-time validation +
  source-coordinate stamping at the authoring surface.

  Spec coverage: `tools/story/spec/001-Authoring.md` §
  Source-coord stamping and § Registration macros.

  Two surfaces are exercised here that the browser smoke does not:

  - **Macro-time validation.** The `reg-story` / `reg-variant` /
    `reg-workspace` / `reg-decorator` / `reg-mode` / `reg-story-panel`
    / `reg-tag` macros all funnel through `re-frame.story.macros/
    gen-reg-call` and `expand-reg-story`. The expansion form binds
    `*pending-coords*` from `(meta &form)` and calls the runtime
    `reg-*!` helper, which runs the malli schema check + tag-vocab
    cross-check. (`:extends` is NOT resolved at registration:
    the raw body is stored, `:extends` intact, and the plan compiler is
    the single merge authority; the `:rf.error/story-extends-unknown`
    error surfaces at plan-compile, not registration.) The cross-cutting
    contract is: an invalid body raises `:rf.error/<kind>-shape` /
    `:rf.error/unknown-tag` with a clear message and an `ex-data` map
    carrying the error key. We assert the error shape for each kind so a
    future schema change that drops a key from `ex-data` is caught.

  - **Source-coord stamping.** Every `reg-*` macro stamps `:file` +
    `:line` + `:ns` + `:column` from `&form` meta into the registered
    body's `:source` slot. The `story_source_coords_test` covers the
    `coords-form` helper in isolation; here we cover the end-to-end
    path through `expand-reg-story` for the Form-B `:variants` sugar
    — the parent story AND each generated child variant must carry
    the same source-coord stamp because both originate from the
    same `&form`.

  Per spec/001 §Source-coord stamping: variants generated from the
  combined `reg-story` form inherit the parent's `&form` meta, since
  the macro expands them at the parent's expansion site. A consumer
  authoring the combined form expects every generated variant's
  `:source` to point back at the same `(reg-story ...)` call."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [re-frame.story :as rf.story]
            [re-frame.story.macros :as rf.story.macros]
            [re-frame.story.plan :as rf.story.plan]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

(defn- shape-data
  "Call `f` and return the thrown ex-data, or nil when it did not throw."
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

;; ===========================================================================
;; ERROR-SHAPE CONTRACT — every registration raises with structured ex-data
;; ===========================================================================

(deftest every-kind-bad-shape-carries-its-error-key
  (doseq [[error-id kind register!]
          [[:rf.error/variant-shape "variant"
            #(rf.story/reg-variant :story.auth.bad/v
               {:tags "not-a-set"})]                     ; :tags must be a set
           [:rf.error/workspace-shape "workspace"
            #(rf.story/reg-workspace :Workspace.bad/empty
               {:layout :unknown-layout})]               ; :layout must be one of four
           [:rf.error/decorator-shape "decorator"
            #(rf.story/reg-decorator :bad-decorator
               {:kind :not-a-decorator-kind})]
           [:rf.error/tag-shape "tag"
            #(rf.story/reg-tag :bad/default-filter
               {:default-filter :sometimes})]            ; only :include / :exclude
           [:rf.error/mode-shape "mode"
            #(rf.story/reg-mode :Mode.bad/missing-args
               {:args "not-a-map"})]                     ; :args must be a map
           [:rf.error/story-panel-shape "story-panel"
            #(rf.story/reg-story-panel :rf.story/bad-panel
               {:placement :nowhere                      ; not one of the five
                :render    :some/view})]]]
    (testing (str "an invalid " kind " body")
      (let [data (shape-data register!)]
        (is (= error-id (:rf.error/id data))
            "ex-data carries the kind's :rf.error/<kind>-shape id")
        (is (str/includes? (str (:reason data)) (str kind " schema"))
            ":reason names the failing kind")))))

(deftest reg-workspace-refuses-a-custom-layout
  (testing "there is no :custom layout, so registration refuses it"
    (try
      (rf.story/reg-workspace :Workspace.bad/custom {:layout :custom})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error/id (ex-data e))))))))

(deftest reg-workspace-isolation-slot-accepts-both-values
  ;; The optional `:isolation` slot accepts `:isolated`
  ;; (default) and `:shared`. Other values reject with
  ;; :rf.error/workspace-shape.
  (testing ":isolation :isolated registers cleanly"
    (is (some? (rf.story/reg-workspace :Workspace.iso-ok/isolated
                 {:layout :variants-grid :isolation :isolated}))))
  (testing ":isolation :shared registers cleanly"
    (is (some? (rf.story/reg-workspace :Workspace.iso-ok/shared
                 {:layout :variants-grid :isolation :shared}))))
  (testing "an unknown :isolation value rejects with :rf.error/workspace-shape"
    (try
      (rf.story/reg-workspace :Workspace.iso-bad/sandboxed
        {:layout :variants-grid :isolation :sandboxed})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error/id (ex-data e))))))))

;; ---- fragments + checks ---------------------------------------------------

(deftest reg-fragment-registers-and-is-queryable
  (testing "reg-fragment lands a body in the :fragment side-table"
    (rf.story/reg-fragment :fragment.cart/with-sku
      {:args  {:sku "A"}
       :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]})
    (is (rf.story/registered? :fragment :fragment.cart/with-sku))
    (is (= {:sku "A"}
           (:args (rf.story/handler-meta :fragment :fragment.cart/with-sku))))))

(deftest reg-fragment-rejects-nested-compose
  (testing "a fragment carrying :compose is rejected at registration (flat fragments)"
    (try
      (rf.story/reg-fragment :fragment.bad/nested
        {:compose [:fragment.cart/with-sku]
         :setup   [[:dispatch [:x]]]})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/fragment-shape (:rf.error/id (ex-data e))))))))

(deftest reg-fragment-rejects-judgement-slots
  (testing "a fragment carrying :assertions is rejected (fragments carry no judgement)"
    (try
      (rf.story/reg-fragment :fragment.bad/judges
        {:assertions [[:rf.assert/no-warnings]]})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/fragment-shape (:rf.error/id (ex-data e))))))))

(deftest reg-check-requires-assertions
  (testing "a check body missing :assertions is rejected"
    (try
      (rf.story/reg-check :check/empty {:doc "no assertions"})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/check-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-rejects-resolve-conflicts
  (testing ":resolve-conflicts on a variant body is rejected (no P1 escape hatch)"
    (try
      (rf.story/reg-variant :story.bad/resolve
        {:resolve-conflicts {[:fx-overrides :rf.http/fetch] :stub}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

;; ===========================================================================
;; CLOSED authoring-body schemas
;; ===========================================================================
;;
;; The Variant + Workspace (and sibling) authoring-body `:map`s are
;; `{:closed true}`: an undeclared slot (such as `:play`) or a typo is
;; REJECTED at `reg-*` call-time with an actionable
;; `:rf.error/<kind>-shape` that NAMES the unknown key + nearest declared
;; slot — rather than silently swallowed and dropped at runtime, which
;; would ship a dead Story scaffold.

(deftest reg-variant-rejects-removed-play-slot
  (testing "the undeclared :play slot is rejected at reg-time —
            the closed Variant schema does not silently swallow it"
    (try
      (rf.story/reg-variant :story.swallow/play
        {:setup []
         :play   [[:rf.assert/path-equals [:counter/value] 0]]})
      (is false "expected an exception — :play must NOT be silently accepted")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e)))
            "ex-data carries the :rf.error/variant-shape sentinel")
        (let [reason (:reason (ex-data e))]
          (is (re-find #":play" reason)
              ":reason names the offending :play key")
          (is (re-find #"did you mean :plays\?" reason)
              ":reason suggests the nearest declared slot")
          (is (re-find #"closed" reason)
              ":reason explains the body is closed"))))))

;; ---- there are no body-level props-schema slots --------------------------
;;
;; The view-args (props) schema lives ONLY on the registered `:component`
;; view's `reg-view` metadata (first-match `[:rf/props :schema]`, resolved
;; off the view-meta by the plan compiler). There is no `:rf/props` /
;; `:schema` slot on a STORY or VARIANT body: nothing would read one, so
;; accepting it would make an accepted-but-dead authoring surface. A props
;; schema authored on the body REJECTS at reg-time with the dead key named,
;; rather than being swallowed with no effect.

(deftest reg-story-rejects-body-level-props-schema-slots
  (testing ":schema on a STORY body is rejected — props schema lives on the
            registered :component view's metadata, not the story body"
    (try
      (rf.story/reg-story :story.deadprops
        {:component :views/widget
         :schema    [:map [:label :string]]})
      (is false "expected an exception — body :schema must NOT be accepted")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-shape (:rf.error/id (ex-data e)))
            "ex-data carries the :rf.error/story-shape sentinel")
        (is (re-find #":schema" (:reason (ex-data e)))
            ":reason names the dead :schema key"))))
  (testing ":rf/props on a STORY body is rejected too"
    (try
      (rf.story/reg-story :story.deadrfprops
        {:component :views/widget
         :rf/props  [:map [:label :string]]})
      (is false "expected an exception — body :rf/props must NOT be accepted")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-rejects-body-level-props-schema-slots
  (testing ":schema on a VARIANT body is rejected — a variant narrows its
            component's props schema on the view metadata, not the body"
    (try
      (rf.story/reg-variant :story.dead/schema
        {:setup []
         :schema [:map [:label :string]]})
      (is false "expected an exception — body :schema must NOT be accepted")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e)))
            "ex-data carries the :rf.error/variant-shape sentinel")
        (is (re-find #":schema" (:reason (ex-data e)))
            ":reason names the dead :schema key"))))
  (testing ":rf/props on a VARIANT body is rejected too"
    (try
      (rf.story/reg-variant :story.dead/rfprops
        {:setup   []
         :rf/props [:map [:label :string]]})
      (is false "expected an exception — body :rf/props must NOT be accepted")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-template-scaffold-bodies-validate
  (testing "the template scaffold's authoring shape
            — :setup preconditions + :script with [:assert [:rf.assert/…]]
            checkpoints + dispatch-sync increments — VALIDATES cleanly"
    ;; Bodies in the template scaffold's authoring shape.
    (is (some? (rf.story/reg-variant :story.migrated/empty
                 {:doc    "Fresh counter at zero."
                  :setup  [[:counter/initialise]]
                  :script [[:assert [:rf.assert/path-equals [:counter/value] 0]]]
                  :tags   #{:dev :docs :test}
                  :substrates #{:reagent}}))
        "empty-variant scaffold-shaped body validates")
    (is (some? (rf.story/reg-variant :story.migrated/incremented
                 {:doc    "three increments dispatched from :script."
                  :setup  [[:counter/initialise]]
                  :script [[:dispatch-sync [:counter/increment]]
                           [:dispatch-sync [:counter/increment]]
                           [:dispatch-sync [:counter/increment]]
                           [:assert [:rf.assert/path-equals [:counter/value] 3]]
                           [:assert [:rf.assert/sub-equals  [:counter/value] 3]]
                           [:assert [:rf.assert/dispatched? [:counter/increment]]]]
                  :tags   #{:dev :docs :test}
                  :substrates #{:reagent}}))
        "incremented-variant scaffold-shaped body validates")))

(deftest reg-variant-accepts-mcp-origin-stamp
  (testing "the story-mcp write surface stamps :origin onto the variant
            body (spec/Cross-Cutting-Designs.md §5) — the closed schema
            MUST accept it or the MCP register-variant path breaks"
    (is (some? (rf.story/reg-variant* :story.mcp/written
                 {:setup [] :origin :story-mcp}))
        ":origin is a declared optional slot")))

(deftest reg-workspace-rejects-typoed-slot
  (testing "a typo'd workspace slot is rejected at reg-time (closed schema)"
    (try
      (rf.story/reg-workspace :Workspace.swallow/typo
        {:layout :grid :variants [:a] :collumns 2})   ; typo for :columns
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error/id (ex-data e))))
        (is (re-find #"did you mean :columns\?" (:reason (ex-data e)))
            "the nearest-key suggestion points at :columns")))))

(deftest reg-workspace-accepts-documented-grid-slots
  (testing "the documented :variants-grid / :grid slots the canonical
            testbed + examples + spec/001-Authoring.md author — :columns,
            :for, :tags — VALIDATE on the closed schema (the closed schema
            must not drop intended authoring)"
    (is (some? (rf.story/reg-workspace :Workspace.docslots/auto
                 {:doc     "auto-enumerated grid"
                  :layout  :variants-grid
                  :for     :story.counter
                  :columns 2
                  :tags    #{:docs}}))
        ":variants-grid + :for + :columns + :tags validates")
    (is (some? (rf.story/reg-workspace :Workspace.docslots/grid
                 {:layout   :grid
                  :variants [:story.counter/empty]
                  :columns  3
                  :tags     #{:docs}}))
        ":grid + :variants + :columns + :tags validates")
    (is (some? (rf.story/reg-workspace :Workspace.docslots/anchored
                 {:layout :variants-grid
                  :for    :story.counter}))
        ":for (renderer-read anchor slot) validates")))

(deftest reg-workspace-rejects-both-variants-and-for
  (testing ":variants (explicit) and :for (auto-enumerate anchor) are
            mutually exclusive on a :variants-grid — declaring both raises
            :rf.error/workspace-shape (spec/001-Authoring.md
            §`:variants-grid`)"
    (try
      (rf.story/reg-workspace :Workspace.both/variants-and-for
        {:layout   :variants-grid
         :variants [:story.counter/empty]
         :for      :story.counter})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error/id (ex-data e))))))))

;; ===========================================================================
;; :plays multi-play schema contract
;; ===========================================================================

(deftest reg-variant-rejects-malformed-plays
  (doseq [[label body]
          [[":plays must contain at least one entry"
            {:setup [] :plays []}]
           ["each :plays entry must carry a :name"
            {:setup [] :plays [{:script [[:dispatch [:foo]]]}]}]
           [":plays entries must have unique :name values"
            {:setup [] :plays [{:name "p" :script [[:dispatch [:a]]]}
                               {:name "p" :script [[:dispatch [:b]]]}]}]
           ["a variant may not declare BOTH :script and :plays"
            {:setup  []
             :script [[:dispatch [:legacy]]]
             :plays  [{:name "p" :script [[:dispatch [:plays]]]}]}]]]
    (testing label
      (is (= :rf.error/variant-shape
             (:rf.error/id (shape-data #(rf.story/reg-variant* :story.multi/v body))))))))

;; ===========================================================================
;; ONE variant vocabulary: :setup / :script / :plays
;; ===========================================================================
;;
;; Per tools/story/spec/017-Testing-Story.md §Public vocabulary, `:setup`
;; and `:script` (and named `:plays`) are the variant/fragment body keys,
;; and the registrar stores them VERBATIM: `handler-meta` / `variant->edn`
;; hand back exactly what was authored. `:events` and `:play-script` are
;; not slots — the closed schema rejects them like any unknown key (a red
;; witness per key below).

(deftest reg-variant-rejects-retired-events-slot
  (testing "a variant body carrying :events (the retired setup spelling)
            fails shape validation, naming the key and the slot it means"
    (try
      (rf.story/reg-variant* :story.vocab/legacy-events
        {:events [[:counter/initialise 3]]})
      (is false "expected an exception — :events is not a slot")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))
        (is (re-find #":events" (:reason (ex-data e)))
            ":reason names the offending :events key")))))

(deftest reg-variant-rejects-retired-play-script-slot
  (testing "a variant body carrying :play-script (the retired play spelling)
            fails shape validation and points at :script"
    (try
      (rf.story/reg-variant* :story.vocab/legacy-play-script
        {:setup       []
         :play-script [[:dispatch [:a]]]})
      (is false "expected an exception — :play-script is not a slot")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))
        (is (re-find #":play-script \(did you mean :script\?\)" (:reason (ex-data e)))
            ":reason names :play-script and suggests :script")))))

(deftest reg-fragment-rejects-retired-slots
  (testing "the fragment body is held to the same vocabulary"
    (doseq [[label body] [[":events"      {:events [[:a]]}]
                          [":play-script" {:play-script [[:dispatch [:a]]]}]]]
      (try
        (rf.story/reg-fragment* :fragment.vocab/legacy body)
        (is false (str "expected an exception — " label " is not a fragment slot"))
        (catch clojure.lang.ExceptionInfo e
          (is (= :rf.error/fragment-shape (:rf.error/id (ex-data e)))
              (str label " rejects with :rf.error/fragment-shape")))))))

;; ---- play maps are closed -------------------------------------------------
;;
;; An absent `:auto-run?` means auto-run (`play.runner/default-auto-run?`),
;; so on an open map a misspelt opt-out would RUN the script with no error.
;; The `PlaySpec` / `NamedPlaySpec` map branches are closed: the typo rejects
;; at reg-time, naming the key, WHERE it sits, and the nearest declared key.

(deftest reg-variant-rejects-misspelt-play-map-key
  (doseq [[label body at]
          [[":script map, :autorun?"
            {:setup [] :script {:script [[:dispatch [:x]]] :autorun? false}}
            "[:script]"]
           [":script map, :auto-run (no ?)"
            {:setup [] :script {:script [[:dispatch [:x]]] :auto-run false}}
            "[:script]"]
           [":plays entry, :autorun?"
            {:setup [] :plays [{:name "a" :script [[:dispatch [:x]]]}
                               {:name "b" :script [] :autorun? false}]}
            "[:plays 1]"]]]
    (testing label
      (let [data   (shape-data #(rf.story/reg-variant* :story.play-typo/v body))
            reason (str (:reason data))]
        (is (= :rf.error/variant-shape (:rf.error/id data))
            "a misspelt play key is a shape error, not a silent auto-run")
        (is (str/includes? reason (str " in " at " (did you mean :auto-run??)"))
            ":reason names where the key sits and the key it meant")
        (is (str/includes? reason (str "Allowed keys in " at ": [:auto-run? :name :script]"))
            ":reason lists the PLAY map's keys, not the variant body's")
        (is (not (rf.story/registered? :variant :story.play-typo/v))
            "nothing is registered")))))

(deftest reg-fragment-rejects-misspelt-play-map-key
  (testing "a fragment's :script map is held to the same closed shape"
    (let [data (shape-data #(rf.story/reg-fragment* :fragment.play-typo/f
                              {:script {:script [] :autorun? false}}))]
      (is (= :rf.error/fragment-shape (:rf.error/id data)))
      (is (str/includes? (str (:reason data)) ":autorun? in [:script]")))))

(deftest unknown-key-reason-locates-each-offender
  (testing "a TOP-level typo keeps the unlocated form — no \" in [\" clause"
    (let [reason (str (:reason (shape-data #(rf.story/reg-variant* :story.play-typo/top
                                              {:scripts [[:dispatch [:x]]]}))))]
      (is (str/includes? reason ":scripts (did you mean :script?)"))
      (is (str/includes? reason "Allowed keys: ["))
      (is (not (str/includes? reason " in [")))))
  (testing "a top-level AND a nested typo are each named against their own map"
    (let [reason (str (:reason (shape-data #(rf.story/reg-variant* :story.play-typo/both
                                              {:scriptz []
                                               :script  {:script [] :autorun? false}}))))]
      (is (str/includes? reason ":scriptz (did you mean :script?), :autorun? in [:script] (did you mean :auto-run??)"))
      (is (str/includes? reason "Allowed keys: ["))
      (is (str/includes? reason "Allowed keys in [:script]: [:auto-run? :name :script]")))))

(deftest reg-variant-canonical-body-round-trips-verbatim
  (testing ":setup / :script (bare vector) register and read back under the
            same keys — the write/read round trip is an identity"
    (let [authored {:setup  [[:counter/initialise 3]]
                    :script [[:dispatch-sync [:rf.assert/path-equals [:count] 3]]]}]
      (rf.story/reg-variant* :story.vocab/public authored)
      (let [body (rf.story/variant->edn :story.vocab/public)]
        (is (= authored (dissoc body :source))
            "the stored body is the authored body plus the :source stamp, so
             no :events or :play-script key appears on read-back"))))
  (testing "the :script map form (with :name / :auto-run?) round-trips intact"
    (let [authored {:setup  []
                    :script {:name "named" :auto-run? false
                             :script [[:dispatch-sync [:counter/initialise 1]]]}}]
      (rf.story/reg-variant* :story.vocab/public-map authored)
      (is (= authored (dissoc (rf.story/variant->edn :story.vocab/public-map) :source)))))
  (testing "named :plays round-trip intact alongside :setup"
    (let [authored {:setup []
                    :plays [{:name "happy" :script [[:dispatch [:foo]]]}
                            {:name "error" :script [[:dispatch [:bar]]]}]}]
      (rf.story/reg-variant* :story.vocab/named-plays authored)
      (is (= authored (dissoc (rf.story/variant->edn :story.vocab/named-plays) :source))
          ":plays is the only play surface — no :script key appears"))))

(deftest reg-variant-script-map-form-drives-the-plan
  (testing "a map-form :script's :auto-run? / :name reach the compiled plan
            (the plan reads :script through the runner's coercion)"
    (rf.story/reg-variant* :story.vocab/map-plan
      {:setup  []
       :script {:name "named" :auto-run? false
                :script [[:dispatch [:counter/initialise 1]]]}})
    (let [p (rf.story.plan/variant-plan :story.vocab/map-plan)]
      (is (= [[:dispatch [:counter/initialise 1]]] (:script p)))
      (is (= [{:name "named" :auto-run? false
               :script [[:dispatch [:counter/initialise 1]]]}]
             (get-in p [:world :scripts]))))))

(deftest reg-variant-child-script-is-stored-verbatim-over-parent
  (testing "a child's :script is stored as authored; the parent's play
            surface is not merged into the stored body (:extends is raw)"
    (rf.story/reg-variant :story.vocab/parent
      {:setup  []
       :script {:script [[:dispatch [:p/old]]]}})
    (rf.story/reg-variant :story.vocab/child
      {:extends :story.vocab/parent
       :script  {:script [[:dispatch [:c/new]]]}})
    (let [body (rf.story/handler-meta :variant :story.vocab/child)]
      (is (= [[:dispatch [:c/new]]] (get-in body [:script :script]))
          "the child's own play surface is what is stored"))))

;; ===========================================================================
;; UNKNOWN-TAG CONTRACT — tag-vocab cross-check error carries the offending set
;; ===========================================================================

(deftest reg-variant-unknown-tag-lists-offenders
  (testing "an unregistered tag on a variant raises with the offender list"
    (try
      (rf.story/reg-variant :story.tag/v
        {:setup []
         :tags   #{:dev :totally-made-up :also-fake}})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/unknown-tag (:rf.error/id (ex-data e))))
        (let [unknown (set (:unknown (ex-data e)))]
          (is (contains? unknown :totally-made-up))
          (is (contains? unknown :also-fake))
          (is (not (contains? unknown :dev))
              "registered canonical tags are not offenders"))))))

;; ===========================================================================
;; EXTENDS ERROR-SHAPE
;; ===========================================================================

(deftest reg-variant-extends-unknown-carries-parent-id
  (testing ":extends to an unregistered parent does not throw at
            REGISTRATION (the raw body is stored, `:extends`
            intact); the error surfaces at PLAN-COMPILE (the merge
            authority) and carries the missing parent id."
    ;; Registration succeeds — raw body stored with the unknown parent.
    (rf.story/reg-variant :story.x/child
      {:extends :story.x/no-such-parent
       :setup  []})
    ;; Plan compile is where the unknown parent FAILS, carrying the id.
    (try
      (rf.story.plan/variant-plan :story.x/child)
      (is false "expected a plan-compile exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-extends-unknown (:rf.error/id (ex-data e))))
        (is (= :story.x/no-such-parent (:parent (ex-data e))))))))

;; ===========================================================================
;; EXTENDS — PLAY-SURFACE OVERRIDE
;; ===========================================================================
;;
;; :script and :plays are mutually-exclusive sibling encodings of
;; the play surface. A child overriding a parent's :script with
;; :plays (or vice versa) must not FAIL: a straight merge would carry BOTH
;; keys and the schema's mutual-exclusion :fn would reject a body the author
;; never wrote with both. Registration stores the child's raw body as
;; authored — the parent is never merged in at this layer — and the plan
;; compiler lowers both encodings to `:script` (see the `:script` / `:plays`
;; lowering in `plan.cljc`), so the child's own encoding wins and the
;; schema never sees both keys.

(deftest reg-variant-extends-child-plays-overrides-parent-play-script
  (testing "a child declaring :plays while inheriting :script from
            its parent resolves to ONLY :plays (no mutual-exclusion error)"
    (rf.story/reg-variant :story.extplay/parent
      {:setup      []
       :script {:script [[:dispatch [:p/legacy]]]}})
    ;; A straight merge would throw :rf.error/variant-shape here (both keys present).
    (rf.story/reg-variant :story.extplay/child
      {:extends :story.extplay/parent
       :plays   [{:name "happy" :script [[:dispatch [:c/happy]]]}]})
    (let [body (rf.story/handler-meta :variant :story.extplay/child)]
      (is (contains? body :plays)      "child's :plays survived")
      (is (not (contains? body :script))
          "the inherited :script was dropped — no double-encoding"))))

(deftest reg-variant-extends-does-not-inherit-play-surface
  (testing "SCRIPT IS NOT INHERITED through :extends (spec/017 §942-945:
            context flows down, behaviour/judgement is local). A child
            that declares NEITHER play encoding does NOT silently run the
            parent's :script. The registrar stores the RAW body
            — `:extends` intact, parent NOT merged — so the
            child's body carries no play surface, and the plan compiler
            (the merge authority) takes script from the CHILD ONLY."
    (rf.story/reg-variant :story.extplay/parent3
      {:setup      []
       :script {:script [[:dispatch [:p/keep]]]}})
    (rf.story/reg-variant :story.extplay/child3
      {:extends :story.extplay/parent3
       :doc     "no play override"})
    (let [body (rf.story/handler-meta :variant :story.extplay/child3)]
      (is (= :story.extplay/parent3 (:extends body))
          ":extends stored raw — the parent body is NOT merged in")
      (is (not (contains? body :script))
          "the parent's :script is NOT inherited — script is local")
      (is (not (contains? body :plays))
          "no play surface inherited at all"))
    ;; The compiler confirms it: the silent child's compiled script is empty.
    (is (= [] (:script (rf.story.plan/variant-plan :story.extplay/child3)))
        "compiled plan takes script CHILD-ONLY — the parent's is not run")))

;; ===========================================================================
;; CANONICAL-ID-GRAMMAR ERROR
;; ===========================================================================

(deftest reg-variant-non-keyword-id-rejected
  (testing "a non-keyword variant id is rejected — the id-grammar check
            fires first and complains, carrying :rf.error/variant-id-shape"
    (try
      (rf.story/reg-variant* "not-a-keyword" {:setup []})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-id-shape (:rf.error/id (ex-data e))))
        (is (re-find #"canonical id grammar" (:reason (ex-data e))))))))

(deftest reg-workspace-bad-id-grammar-rejected
  (testing "a workspace id outside :Workspace.<path>/<name> is rejected"
    (let [e (try (rf.story/reg-workspace* :NotAWorkspaceId {:layout :variants-grid})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (= :rf.error/workspace-id-shape (:rf.error/id (ex-data e))))
      (is (re-find #"canonical id grammar" (:reason (ex-data e)))))))

;; ===========================================================================
;; SOURCE-COORD STAMPING — END-TO-END VIA THE FORM-B EXPANSION
;; ===========================================================================
;;
;; The story_source_coords_test covers `coords-form` in isolation. Here
;; we exercise the path the *combined form* takes — `expand-reg-story`
;; emits N independent `reg-variant*` calls, each of which must inherit
;; the parent's source-coord stamp because all the expansions originate
;; from the same `&form` meta.

(deftest combined-form-stamps-one-source-on-parent-and-generated-variants
  (testing "a Form-B (:variants sugar) reg-story stamps :source on the parent
            and on each generated child variant — the variants are expanded
            at the parent's macro site, so each child inherits the same
            `&form` line/file/ns. Per spec/001 §Source-coord stamping the IDE
            'Open in editor' affordance reads this slot for both stories and
            variants generated from sugar."
    (rf.story/reg-story :story.combined.gen
      {:doc      "parent with two generated variants."
       :variants {:a {:setup [[:init-a]]}
                  :b {:setup [[:init-b]]}}})
    (let [parent (:source (rf.story/handler-meta :story :story.combined.gen))]
      (is (= 're-frame.story-authoring-validation-test (:ns parent)))
      (is (integer? (:line parent)))
      (doseq [vid [:story.combined.gen/a :story.combined.gen/b]
              :let [child (:source (rf.story/handler-meta :variant vid))]]
        (is (= 're-frame.story-authoring-validation-test (:ns child))
            (str vid " carries the parent's :ns"))
        (is (= (:line parent) (:line child))
            (str vid " shares the parent's expansion line"))))))

;; ---- a NON-literal :variants map is desugared at runtime -----------------
;;
;; The macro peels only a LITERAL `:variants` map. A def'd or merged map —
;; and every programmatic `reg-story*` call — reaches the runtime helper
;; with `:variants` still on the body. The helper desugars it through the
;; same `reg-variant*` rail; dropping it would register the story with
;; zero variants and return success.

(def ^:private formb-variants
  {:a {:setup [[:init-a]]}
   :b {:setup [[:init-b]] :tags #{:dev}}})

(deftest reg-story*-desugars-programmatic-variants
  (testing "the programmatic twin registers each :variants entry as <story-id>/<name>"
    (is (= :story.prog.formb
           (rf.story/reg-story* :story.prog.formb {:doc "p" :variants formb-variants})))
    (is (rf.story/registered? :story :story.prog.formb))
    (is (= {:setup [[:init-a]]}
           (dissoc (rf.story/handler-meta :variant :story.prog.formb/a) :source)))
    (is (= {:setup [[:init-b]] :tags #{:dev}}
           (dissoc (rf.story/handler-meta :variant :story.prog.formb/b) :source)))
    (is (= {:doc "p"} (dissoc (rf.story/handler-meta :story :story.prog.formb) :source))
        "the stored story body never carries :variants")))

(deftest reg-story-macro-desugars-non-literal-variants
  (testing "a computed :variants map handed to the reg-story MACRO registers its
            variants, each stamped with the macro site's source coords"
    (rf.story/reg-story :story.prog.macro (assoc {:doc "m"} :variants formb-variants))
    (let [story (rf.story/handler-meta :story :story.prog.macro)
          a     (rf.story/handler-meta :variant :story.prog.macro/a)]
      (is (= [[:init-a]] (:setup a)))
      (is (rf.story/registered? :variant :story.prog.macro/b))
      (is (= 're-frame.story-authoring-validation-test (:ns (:source a))))
      (is (= (:line (:source story)) (:line (:source a)))
          "a desugared variant shares the parent's expansion line, as on the literal path"))))

(deftest reg-story*-rejects-malformed-variants
  (testing "a non-keyword variant name is a :rf.error/story-shape, not a silent drop"
    (is (= :rf.error/story-shape
           (:rf.error/id (shape-data #(rf.story/reg-story* :story.prog.badname
                                        {:variants {"a" {:setup []}}})))))
    (is (not (rf.story/registered? :story :story.prog.badname))))
  (testing "a desugared variant body is validated like any reg-variant* body"
    (let [data (shape-data #(rf.story/reg-story* :story.prog.badbody
                              {:variants {:a {:scripts [[:dispatch [:x]]]}}}))]
      (is (= :rf.error/variant-shape (:rf.error/id data)))
      (is (str/includes? (str (:reason data)) ":scripts (did you mean :script?)")))))

;; ---- a computed :variants inside a LITERAL body --------------------------
;;
;; The commonest shape is a literal outer body whose `:variants` is a symbol
;; or an expression. Only a literal `:variants` MAP is peeled; every other
;; `:variants` form reaches `reg-story*` unchanged, which desugars it at
;; runtime. A macro that classified only the OUTER map as literal and walked
;; whatever `:variants` held with `for` would fail expansion:
;; `{:variants vs}` with "Don't know how to create ISeq from:
;; clojure.lang.Symbol" and `{:variants (merge vs ...)}` with "nth not
;; supported on this type: Symbol".
;;
;; The public-macro rows expand at TEST time (`eval-here`), not load time, so
;; a broken expansion fails its own row instead of the whole namespace.

(defn- expand
  "`expand-reg-story` for `metadata` at a fixed call site in this ns."
  [id metadata]
  (rf.story.macros/expand-reg-story {:line 7 :column 3} "stories.clj"
                                    're-frame.story-authoring-validation-test
                                    id metadata))

(defn- eval-here
  "Expand and run `form` in this ns, at test time."
  [form]
  (binding [*ns* (the-ns 're-frame.story-authoring-validation-test)]
    (eval form)))

(defn- gated?
  "Is `form` one registration under the production-elision gate?"
  [form]
  (and (seq? form)
       (= 'clojure.core/when (first form))
       (= 're-frame.story.config/enabled? (second form))))

(defn- reg-call
  "The registrar call inside one gated registration:
  `(when enabled? (binding [...] <call>))` → `<call>`."
  [gated-form]
  (last (last gated-form)))

(deftest expand-reg-story-still-peels-a-literal-variants-map
  (testing "a literal :variants map — even one whose VALUES are symbols — still
            expands into the parent plus one independent registration per variant"
    (let [exp (expand :story.g2k4.lit '{:doc "l" :variants {:a {:setup []} :b vb}})]
      (is (= 'do (first exp)))
      (is (every? gated? (rest exp)) "each registration is its own gated form")
      (is (= [(list 're-frame.story.registrar/reg-story*   :story.g2k4.lit {:doc "l"})
              (list 're-frame.story.registrar/reg-variant* :story.g2k4.lit/a {:setup []})
              (list 're-frame.story.registrar/reg-variant* :story.g2k4.lit/b 'vb)]
             (map reg-call (rest exp)))))))

(deftest reg-story-macro-registers-computed-variants-in-a-literal-body
  (doseq [[id form want]
          [[:story.g2k4.sym
            '(rf.story/reg-story :story.g2k4.sym {:doc "s" :variants formb-variants})
            formb-variants]
           [:story.g2k4.let
            '(let [vs formb-variants]
               (rf.story/reg-story :story.g2k4.let {:doc "s" :variants vs}))
            formb-variants]
           [:story.g2k4.merge
            '(rf.story/reg-story :story.g2k4.merge
               {:doc "s" :variants (merge formb-variants {:c {:setup [[:init-c]]}})})
            (assoc formb-variants :c {:setup [[:init-c]]})]]]
    (testing (name id)
      (is (= id (eval-here form)))
      (let [story (rf.story/handler-meta :story id)]
        (is (= {:doc "s"} (dissoc story :source))
            "the stored story body never carries :variants")
        (doseq [[v-name v-body] want
                :let [v (rf.story/handler-meta :variant
                                               (rf.story.macros/variant-id-for id v-name))]]
          (is (= v-body (dissoc v :source)) (str "variant " v-name))
          (is (= 're-frame.story-authoring-validation-test (:ns (:source v))))
          (is (integer? (:line (:source v))))
          (is (= (:line (:source story)) (:line (:source v)))
              "each variant carries the macro site's coords, as on the literal path"))))))

(def ^:private evaluations
  "How many times a forwarded `:variants` expression was evaluated."
  (atom 0))

(deftest reg-story-expansion-elides-both-forms
  ;; `enabled?` is a JVM `^:const true`, so a production build is simulated by
  ;; writing `false` over the gate in the expansion before running it.
  (let [run     (fn [enabled? exp]
                  (eval-here (walk/postwalk-replace
                              {'re-frame.story.config/enabled? enabled?} exp)))
        literal '{:variants {:a {:setup []}}}
        counted '{:variants (do (swap! evaluations inc) formb-variants)}]
    (reset! evaluations 0)
    (testing "gate off: neither form registers, and the forwarded :variants is never evaluated"
      (run false (expand :story.g2k4.gate-lit literal))
      (run false (expand :story.g2k4.gate-fwd counted))
      (is (not (rf.story/registered? :story :story.g2k4.gate-lit)))
      (is (not (rf.story/registered? :variant :story.g2k4.gate-lit/a)))
      (is (not (rf.story/registered? :story :story.g2k4.gate-fwd)))
      (is (zero? @evaluations)))
    (testing "control, gate on: the same expansions register, evaluating :variants once"
      (run true (expand :story.g2k4.gate-lit literal))
      (run true (expand :story.g2k4.gate-fwd counted))
      (is (rf.story/registered? :variant :story.g2k4.gate-lit/a))
      (is (rf.story/registered? :variant :story.g2k4.gate-fwd/a))
      (is (= 1 @evaluations)))))

;; ===========================================================================
;; MACRO HELPER — `variant-id-for` enforces keyword grammar
;; ===========================================================================
;;
;; The Form-B desugaring relies on `variant-id-for` to build the
;; per-variant id (e.g. `:story.foo` × `:a` → `:story.foo/a`). The helper
;; rejects non-keyword arguments synchronously so a typo in a `:variants`
;; map key (`"a"` vs `:a`) trips at macro-expansion rather than producing
;; a malformed registry id.

(deftest variant-id-for-rejects-non-keyword-story-id
  (let [e (try (rf.story.macros/variant-id-for "story.foo" :a)
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/story-bad-id (:rf.error/id (ex-data e))))
    (is (re-find #"story id must be a keyword" (:reason (ex-data e))))))

(deftest variant-id-for-rejects-non-keyword-variant-name
  (let [e (try (rf.story.macros/variant-id-for :story.foo "a")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
    (is (= :rf.error/story-bad-variant-name (:rf.error/id (ex-data e))))
    (is (re-find #"variant-name in :variants map" (:reason (ex-data e))))))

;; ===========================================================================
;; configure! key validation
;;
;; Pre-alpha posture: configure! validates its keys and throws on any key
;; outside the closed known-set. A misspelled or unknown key must fail
;; loudly at boot rather than silently no-op — see story.cljc :configure!
;; docstring.

(deftest configure-bang-rejects-unknown-keys
  (testing "an unknown top-level key raises :rf.error/unknown-story-config-key"
    (let [e (try (rf.story/configure! {:rf.story/edtior :cursor}) ; typo
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e) "expected ex-info to be thrown")
      ;; Canonical thrown-error shape: a human sentence carrying the
      ;; offending key(s) + the trailing `[:rf.error/<id>]` token — NOT
      ;; the bare keyword string.
      (let [msg (.getMessage ^Exception e)]
        (is (re-find #":rf.story/edtior" msg)
            "the message names the unknown key the author typed")
        (is (re-find #"known keys are" msg)
            "the message enumerates the known keys")
        (is (re-find #"\[:rf.error/unknown-story-config-key\]" msg)
            "the message ends with the machine-readable error token"))
      (let [data (ex-data e)]
        (is (= :rf.error/unknown-story-config-key (:rf.error/id data)))
        (is (= 'rf.story/configure! (:where data)))
        (is (= :fix-call-site (:recovery data)))
        (is (= [:rf.story/edtior] (:unknown data)))
        (is (contains? (:known data) :rf.story/editor))))))

(deftest configure-bang-accepts-every-known-key
  (testing "the closed known-set passes through without error"
    (is (nil? (rf.story/configure! {})) "empty map is a no-op")
    (is (nil? (rf.story/configure! {:rf.story/global-args        {:theme :light}
                                 :rf.story/global-decorators  []
                                 :rf.story/editor             :vscode
                                 :rf.story/project-root       nil
                                 :rf.story/egress-profile     :rf.egress/local-redacted})))))

(deftest configure-bang-error-lists-every-unknown
  (testing "multiple unknown keys all surface in :unknown so the author
            fixes the whole call site in one pass"
    (let [e (try (rf.story/configure! {:rf.story/edtior :cursor
                                    :foo             1})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= #{:rf.story/edtior :foo} (set (:unknown (ex-data e))))))))
