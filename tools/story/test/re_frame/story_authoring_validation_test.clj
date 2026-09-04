(ns re-frame.story-authoring-validation-test
  "JVM tests closing the docs-promised gap on macro-time validation +
  source-coordinate stamping at the authoring surface.

  Spec coverage (rf2-ub1n4): `tools/story/spec/001-Authoring.md` §
  Source-coord stamping and § Registration macros.

  Two surfaces are exercised here that the browser smoke does not:

  - **Macro-time validation.** The `reg-story` / `reg-variant` /
    `reg-workspace` / `reg-decorator` / `reg-mode` / `reg-story-panel`
    / `reg-tag` macros all funnel through `re-frame.story.macros/
    gen-reg-call` and `expand-reg-story`. The expansion form binds
    `*pending-coords*` from `(meta &form)` and calls the runtime
    `reg-*!` helper, which runs the malli schema check + tag-vocab
    cross-check. (`:extends` is NOT resolved at registration — rf2-f6z88:
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
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.story :as rf.story]
            [re-frame.story.macros :as rf.story.macros]
            [re-frame.story.plan :as rf.story.plan]))

;; ---- fixtures -------------------------------------------------------------

(defn reset-story-registry [test-fn]
  (rf.story/clear-all!)
  (rf.story/install-canonical-vocabulary!)
  (test-fn))

(use-fixtures :each reset-story-registry)

;; ===========================================================================
;; ERROR-SHAPE CONTRACT — every registration raises with structured ex-data
;; ===========================================================================

(deftest reg-variant-bad-shape-carries-error-key
  (testing "an invalid variant body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-variant :story.auth.bad/v
        {:tags "not-a-set"})                         ; :tags must be a set
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e)))
            "ex-data carries the :rf.error/variant-shape sentinel")
        (is (re-find #"variant schema" (:reason (ex-data e)))
            ":reason names the failing kind")))))

(deftest reg-workspace-bad-shape-carries-error-key
  (testing "an invalid workspace body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-workspace :Workspace.bad/empty
        {:layout :unknown-layout})                   ; :layout must be one of five
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/workspace-shape (:rf.error/id (ex-data e))))
        (is (re-find #"workspace schema" (:reason (ex-data e))))))))

(deftest reg-workspace-isolation-slot-accepts-both-values
  ;; rf2-gqid4 — the optional `:isolation` slot accepts `:isolated`
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

(deftest reg-decorator-bad-shape-carries-error-key
  (testing "an invalid decorator body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-decorator :bad-decorator
        {:kind :not-a-decorator-kind})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/decorator-shape (:rf.error/id (ex-data e))))))))

(deftest reg-tag-bad-shape-carries-error-key
  (testing "an invalid tag body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-tag :bad/default-filter
        {:default-filter :sometimes})                ; only :include / :exclude
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/tag-shape (:rf.error/id (ex-data e))))))))

(deftest reg-mode-bad-shape-carries-error-key
  (testing "an invalid mode body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-mode :Mode.bad/missing-args
        {:args "not-a-map"})                         ; :args must be a map
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/mode-shape (:rf.error/id (ex-data e))))))))

(deftest reg-story-panel-bad-shape-carries-error-key
  (testing "an invalid story-panel body raises with :rf.error in ex-data"
    (try
      (rf.story/reg-story-panel :rf.story/bad-panel
        {:placement :nowhere                          ; not one of the five
         :render    :some/view})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/story-panel-shape (:rf.error/id (ex-data e))))))))

;; ---- fragments + checks (rf2-5x1wt.15) ------------------------------------

(deftest reg-fragment-registers-and-is-queryable
  (testing "reg-fragment lands a body in the :fragment side-table"
    (rf.story/reg-fragment :fragment.cart/with-sku
      {:args  {:sku "A"}
       :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]})
    (is (rf.story/registered? :fragment :fragment.cart/with-sku))
    (is (= {:sku "A"}
           (:args (rf.story/handler-meta :fragment :fragment.cart/with-sku))))))

(deftest reg-check-registers-and-is-queryable
  (testing "reg-check lands a body in the :check side-table"
    (rf.story/reg-check :check/no-runtime-errors
      {:assertions [[:rf.assert/no-warnings]]})
    (is (rf.story/registered? :check :check/no-runtime-errors))
    (is (= [[:rf.assert/no-warnings]]
           (:assertions (rf.story/handler-meta :check :check/no-runtime-errors))))))

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
;; rf2-mantt — CLOSED authoring-body schemas (swallow-class fix)
;; ===========================================================================
;;
;; The Variant + Workspace (and sibling) authoring-body `:map`s are
;; `{:closed true}`: a removed slot (the REMOVED `:play`, rf2-0wrud) or a
;; typo is REJECTED at `reg-*` call-time with an actionable
;; `:rf.error/<kind>-shape` that NAMES the unknown key + nearest declared
;; slot — rather than silently swallowed and dropped at runtime (the
;; failure mode that shipped a dead Story scaffold, rf2-1774m).

(deftest reg-variant-rejects-removed-play-slot
  (testing "the REMOVED :play slot (rf2-0wrud) is rejected at reg-time —
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

(deftest reg-variant-rejects-typoed-slot-with-nearest-suggestion
  (testing "a typo'd variant slot is rejected and the nearest declared
            slot is suggested (e.g. :scripts → :script)"
    (try
      (rf.story/reg-variant :story.swallow/typo
        {:setup   []
         :scripts [[:dispatch [:x]]]})              ; typo for :script
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))
        (is (re-find #"did you mean :script\?" (:reason (ex-data e)))
            "the nearest-key suggestion points at :script")))))

;; ---- rf2-hwcdh2 — body-level props-schema slots are GONE -----------------
;;
;; The view-args (props) schema lives ONLY on the registered `:component`
;; view's `reg-view` metadata (first-match `[:rf/props :schema]`, resolved
;; off the view-meta by the plan compiler). A `:rf/props` / `:schema` slot
;; on a STORY or VARIANT body used to pass closed-shape validation but was
;; silently UNREAD (an accepted-but-dead authoring surface). The slots are
;; removed, so a props schema authored on the body now REJECTS at reg-time
;; with the dead key named, rather than being swallowed with no effect.

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

(deftest props-schema-on-view-metadata-still-drives-validation
  (testing "the LIVE path is unchanged — a props schema on the registered
            :component view's metadata (NOT the body) still copies into the
            compiled plan and validates :effective-args (rf2-hwcdh2)"
    (rf.story/reg-variant :story.live/props
      {:component :views/widget
       :args      {:label "Hi"}})
    (let [view-schema [:map [:label :string]]
          p (rf.story.plan/variant-plan :story.live/props
                               {:view-lookup {:views/widget {:rf/props view-schema}}})]
      (is (= view-schema (get-in p [:world :view-args-schema]))
          "the view-meta props schema is copied to [:world :view-args-schema]")
      (is (= {:label "Hi"} (get-in p [:world :effective-args]))))))

(deftest reg-variant-migrated-setup-script-authoring-validates
  (testing "the migrated authoring shape (the template scaffold, rf2-1774m)
            — :setup preconditions + :script with [:assert [:rf.assert/…]]
            checkpoints + dispatch-sync increments — VALIDATES cleanly"
    ;; The exact bodies the migrated template scaffold ships.
    (is (some? (rf.story/reg-variant :story.migrated/empty
                 {:doc    "Fresh counter at zero."
                  :setup  [[:counter/initialise]]
                  :script [[:assert [:rf.assert/path-equals [:counter/value] 0]]]
                  :tags   #{:dev :docs :test}
                  :substrates #{:reagent}}))
        "empty-variant migrated body validates")
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
        "incremented-variant migrated body validates")
    ;; The body is stored under the keys the author wrote.
    (let [body (rf.story/handler-meta :variant :story.migrated/incremented)]
      (is (= [[:counter/initialise]] (:setup body)) ":setup stored verbatim")
      (is (= 6 (count (:script body))) ":script stored verbatim")
      (is (not (contains? body :play)) "no dead :play slot survives"))))

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
            :for, :tags — VALIDATE on the closed schema (closing must not
            drop intended authoring, rf2-mantt triage)"
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
;; rf2-tl7zk — :plays multi-play schema contract
;; ===========================================================================

(deftest reg-variant-plays-accepts-named-list
  (testing ":plays with valid named entries is accepted"
    (rf.story/reg-variant :story.multi/named
      {:setup []
       :plays  [{:name "happy path"
                 :script [[:dispatch [:foo]]]}
                {:name "error path"
                 :script [[:dispatch [:bar]]]}]})
    (let [body (rf.story/handler-meta :variant :story.multi/named)]
      (is (= 2 (count (:plays body)))))))

(deftest reg-variant-plays-empty-rejected
  (testing ":plays must contain at least one entry"
    (try
      (rf.story/reg-variant :story.multi/empty
        {:setup []
         :plays  []})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-plays-without-name-rejected
  (testing "each :plays entry must carry a :name"
    (try
      (rf.story/reg-variant :story.multi/no-name
        {:setup []
         :plays  [{:script [[:dispatch [:foo]]]}]})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-plays-duplicate-names-rejected
  (testing ":plays entries must have unique :name values"
    (try
      (rf.story/reg-variant :story.multi/dup
        {:setup []
         :plays  [{:name "p" :script [[:dispatch [:a]]]}
                  {:name "p" :script [[:dispatch [:b]]]}]})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

(deftest reg-variant-play-script-and-plays-mutually-exclusive
  (testing "a variant may not declare BOTH :script and :plays"
    (try
      (rf.story/reg-variant :story.multi/both
        {:setup      []
         :script [[:dispatch [:legacy]]]
         :plays       [{:name "p" :script [[:dispatch [:plays]]]}]})
      (is false "expected an exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= :rf.error/variant-shape (:rf.error/id (ex-data e))))))))

;; ===========================================================================
;; rf2-7dewo — ONE variant vocabulary: :setup / :script / :plays
;; ===========================================================================
;;
;; Per tools/story/spec/017-Testing-Story.md §Public vocabulary, `:setup`
;; and `:script` (and named `:plays`) are the variant/fragment body keys,
;; and the registrar stores them VERBATIM: `handler-meta` / `variant->edn`
;; hand back exactly what was authored. The retired `:events` /
;; `:play-script` spellings are not slots — the closed schema rejects
;; them like any unknown key (a red witness per removed key below).

(defn- shape-error
  "Register `body` under `id` and return the thrown `:rf.error/id`, or nil
  when registration succeeded."
  [id body]
  (try
    (rf.story/reg-variant* id body)
    nil
    (catch clojure.lang.ExceptionInfo e
      (:rf.error/id (ex-data e)))))

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

(deftest reg-variant-canonical-body-round-trips-verbatim
  (testing ":setup / :script (bare vector) register and read back under the
            same keys — the write/read round trip is an identity"
    (let [authored {:setup  [[:counter/initialise 3]]
                    :script [[:dispatch-sync [:rf.assert/path-equals [:count] 3]]]}]
      (rf.story/reg-variant* :story.vocab/public authored)
      (let [body (rf.story/variant->edn :story.vocab/public)]
        (is (= authored (dissoc body :source))
            "the stored body is the authored body plus the :source stamp")
        (is (not (contains? body :events))      "no :events key appears on read-back")
        (is (not (contains? body :play-script)) "no :play-script key appears on read-back"))))
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
      (let [body (rf.story/variant->edn :story.vocab/named-plays)]
        (is (= authored (dissoc body :source)))
        (is (not (contains? body :script)) ":plays is the only play surface")))))

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
  (testing ":extends to an unregistered parent no longer throws at
            REGISTRATION (rf2-f6z88 — the raw body is stored, `:extends`
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
;; EXTENDS — PLAY-SURFACE OVERRIDE (rf2-ee38b.3)
;; ===========================================================================
;;
;; :script and :plays are mutually-exclusive sibling encodings of
;; the play surface. A child overriding a parent's :script with
;; :plays (or vice versa) used to FAIL: the straight merge carried BOTH
;; keys and the schema's mutual-exclusion :fn rejected a body the author
;; never wrote with both. Registration now stores the child's raw body as
;; authored — the parent is never merged in at this layer — and the plan
;; compiler lowers both encodings to `:script` (see `plan.cljc` §Play surface),
;; so the child's own encoding wins and the schema never sees both keys.

(deftest reg-variant-extends-child-plays-overrides-parent-play-script
  (testing "a child declaring :plays while inheriting :script from
            its parent resolves to ONLY :plays (no mutual-exclusion error)"
    (rf.story/reg-variant :story.extplay/parent
      {:setup      []
       :script {:script [[:dispatch [:p/legacy]]]}})
    ;; This used to throw :rf.error/variant-shape (both keys present).
    (rf.story/reg-variant :story.extplay/child
      {:extends :story.extplay/parent
       :plays   [{:name "happy" :script [[:dispatch [:c/happy]]]}]})
    (let [body (rf.story/handler-meta :variant :story.extplay/child)]
      (is (contains? body :plays)      "child's :plays survived")
      (is (not (contains? body :script))
          "the inherited :script was dropped — no double-encoding"))))

(deftest reg-variant-extends-child-play-script-overrides-parent-plays
  (testing "the symmetric case — child :script overrides parent :plays"
    (rf.story/reg-variant :story.extplay/parent2
      {:setup []
       :plays  [{:name "p" :script [[:dispatch [:p/plays]]]}]})
    (rf.story/reg-variant :story.extplay/child2
      {:extends     :story.extplay/parent2
       :script {:script [[:dispatch [:c/legacy]]]}})
    (let [body (rf.story/handler-meta :variant :story.extplay/child2)]
      (is (contains? body :script) "child's :script survived")
      (is (not (contains? body :plays))
          "the inherited :plays was dropped"))))

(deftest reg-variant-extends-does-not-inherit-play-surface
  (testing "SCRIPT IS NOT INHERITED through :extends (spec/017 §942-945:
            context flows down, behaviour/judgement is local). A child
            that declares NEITHER play encoding does NOT silently run the
            parent's :script. The registrar stores the RAW body
            (rf2-f6z88) — `:extends` intact, parent NOT merged — so the
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

(deftest combined-form-stamps-source-on-parent-story
  (testing "a Form-B (:variants sugar) reg-story stamps :source on the parent"
    (rf.story/reg-story :story.combined.src
      {:doc      "parent."
       :variants {:a {:setup [[:init]]}}})
    (let [body (rf.story/handler-meta :story :story.combined.src)]
      (is (map? (:source body)))
      (is (= 're-frame.story-authoring-validation-test (:ns (:source body))))
      (is (integer? (:line (:source body)))))))

(deftest combined-form-stamps-source-on-generated-variants
  (testing "a Form-B reg-story stamps :source on each generated child variant
            — the variants are expanded at the parent's macro site, so each
            child inherits the same `&form` line/file/ns. Per spec/001
            §Source-coord stamping the IDE 'Open in editor' affordance reads
            this slot for both stories and variants generated from sugar."
    (rf.story/reg-story :story.combined.gen
      {:doc      "parent with two generated variants."
       :variants {:a {:setup [[:init-a]]}
                  :b {:setup [[:init-b]]}}})
    (let [body-a (rf.story/handler-meta :variant :story.combined.gen/a)
          body-b (rf.story/handler-meta :variant :story.combined.gen/b)]
      (is (map? (:source body-a)) "child :a carries :source")
      (is (map? (:source body-b)) "child :b carries :source")
      (is (= 're-frame.story-authoring-validation-test
             (:ns (:source body-a))))
      (is (= 're-frame.story-authoring-validation-test
             (:ns (:source body-b))))
      (is (= (:line (:source body-a))
             (:line (:source body-b)))
          "both generated variants share the parent's expansion line"))))

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

(deftest variant-id-for-builds-canonical-id
  (testing "the canonical :story.<path>/<variant> id shape"
    (is (= :story.auth.login-form/empty
           (rf.story.macros/variant-id-for :story.auth.login-form :empty)))
    (is (= :story.foo/bar
           (rf.story.macros/variant-id-for :story.foo :bar)))))

;; ===========================================================================
;; configure! key validation (rf2-xwr1d)
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
      ;; the bare keyword string (rf2-r2redv).
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
