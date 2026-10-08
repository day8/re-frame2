(ns re-frame.story-authoring-validation-test
  "Registration-time validation of every `reg-*` body (structured
  `:rf.error/<kind>-shape` ex-data, closed body schemas, the tag vocabulary),
  `:extends` and play-surface rules, the Form-B `:variants` desugaring and its
  source stamps, and `configure!` key validation (001-Authoring
  §Registration macros, §Source-coord stamping)."
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

(defn- error-id [f] (:rf.error/id (shape-data f)))

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

(deftest reg-workspace-isolation-slot-accepts-both-values
  (doseq [iso [:isolated :shared]]
    (is (some? (rf.story/reg-workspace* (keyword "Workspace.iso-ok" (name iso))
                                        {:layout :variants-grid :isolation iso}))))
  (is (= :rf.error/workspace-shape
         (error-id #(rf.story/reg-workspace :Workspace.iso-bad/sandboxed
                      {:layout :variants-grid :isolation :sandboxed})))))

;; ---- fragments + checks ---------------------------------------------------

;; ---- fragments + checks ---------------------------------------------------

(deftest reg-fragment-registers-and-is-queryable
  (rf.story/reg-fragment :fragment.cart/with-sku
    {:args {:sku "A"} :setup [[:dispatch [:cart/add {:sku [:arg :sku]}]]]})
  (is (= {:sku "A"} (:args (rf.story/handler-meta :fragment :fragment.cart/with-sku)))))

(deftest reg-fragment-rejects-nested-compose
  (is (= :rf.error/fragment-shape
         (error-id #(rf.story/reg-fragment :fragment.bad/nested
                      {:compose [:fragment.cart/with-sku] :setup [[:dispatch [:x]]]})))))

(deftest reg-fragment-rejects-judgement-slots
  (is (= :rf.error/fragment-shape
         (error-id #(rf.story/reg-fragment :fragment.bad/judges
                      {:assertions [[:rf.assert/no-warnings]]})))))

(deftest reg-check-requires-assertions
  (is (= :rf.error/check-shape (error-id #(rf.story/reg-check :check/empty {:doc "no assertions"})))))

;; ---- closed authoring-body schemas ----------------------------------------
;;
;; An undeclared slot or a typo rejects at reg-time, naming the key and the
;; nearest declared slot, rather than being dropped and shipping a dead
;; scaffold.

(deftest reg-variant-rejects-removed-play-slot
  (let [data (shape-data #(rf.story/reg-variant :story.swallow/play
                            {:setup [] :play [[:rf.assert/path-equals [:counter/value] 0]]}))]
    (is (= :rf.error/variant-shape (:rf.error/id data)))
    (is (re-find #":play \(did you mean :plays\?\)" (:reason data)))
    (is (re-find #"closed" (:reason data)))))

;; The props schema lives only on the `:component` view's metadata, so a
;; body-level `:schema` would be accepted and dead.

(deftest reg-story-rejects-body-level-props-schema-slots
  (let [data (shape-data #(rf.story/reg-story :story.deadprops
                            {:component :views/widget :schema [:map [:label :string]]}))]
    (is (= :rf.error/story-shape (:rf.error/id data)))
    (is (re-find #":schema" (:reason data)))))

(deftest reg-variant-template-scaffold-bodies-validate
  (testing "the template scaffold's authoring shape validates"
    (is (some? (rf.story/reg-variant :story.migrated/incremented
                 {:doc        "three increments dispatched from :script."
                  :setup      [[:counter/initialise]]
                  :script     [[:dispatch-sync [:counter/increment]]
                               [:assert [:rf.assert/path-equals [:counter/value] 3]]
                               [:assert [:rf.assert/sub-equals  [:counter/value] 3]]
                               [:assert [:rf.assert/dispatched? [:counter/increment]]]]
                  :tags       #{:dev :docs :test}
                  :substrates #{:reagent}})))))

(deftest reg-variant-accepts-mcp-origin-stamp
  (testing "the story-mcp write surface stamps :origin, so the closed schema
            must accept it"
    (is (some? (rf.story/reg-variant* :story.mcp/written {:setup [] :origin :story-mcp})))))

(deftest reg-workspace-rejects-typoed-slot
  (let [data (shape-data #(rf.story/reg-workspace :Workspace.swallow/typo
                            {:layout :grid :variants [:a] :collumns 2}))]
    (is (= :rf.error/workspace-shape (:rf.error/id data)))
    (is (re-find #"did you mean :columns\?" (:reason data)))))

(deftest reg-workspace-accepts-documented-grid-slots
  (testing "the documented :variants-grid and :grid slots validate on the
            closed schema"
    (is (some? (rf.story/reg-workspace :Workspace.docslots/auto
                 {:doc "auto" :layout :variants-grid :for :story.counter :columns 2 :tags #{:docs}})))
    (is (some? (rf.story/reg-workspace :Workspace.docslots/grid
                 {:layout :grid :variants [:story.counter/empty] :columns 3 :tags #{:docs}})))))

(deftest reg-workspace-rejects-both-variants-and-for
  (is (= :rf.error/workspace-shape
         (error-id #(rf.story/reg-workspace :Workspace.both/variants-and-for
                      {:layout :variants-grid :variants [:story.counter/empty] :for :story.counter})))))

;; ---- the play surface: :setup / :script / :plays --------------------------

(deftest reg-variant-rejects-malformed-plays
  (doseq [[label body]
          [["at least one entry" {:setup [] :plays []}]
           ["each entry carries a :name" {:setup [] :plays [{:script [[:dispatch [:foo]]]}]}]
           ["unique :name values" {:setup [] :plays [{:name "p" :script [[:dispatch [:a]]]}
                                                     {:name "p" :script [[:dispatch [:b]]]}]}]
           ["not both :script and :plays" {:setup  [] :script [[:dispatch [:legacy]]]
                                           :plays  [{:name "p" :script [[:dispatch [:plays]]]}]}]]]
    (is (= :rf.error/variant-shape (error-id #(rf.story/reg-variant* :story.multi/v body))) label)))

;; An absent `:auto-run?` means auto-run, so a misspelt opt-out on an open
;; play map would run the script; the play maps are closed instead.

(deftest reg-variant-rejects-misspelt-play-map-key
  (doseq [[body at] [[{:setup [] :script {:script [[:dispatch [:x]]] :autorun? false}} "[:script]"]
                     [{:setup [] :plays [{:name "a" :script [[:dispatch [:x]]]}
                                         {:name "b" :script [] :autorun? false}]}
                      "[:plays 1]"]]]
    (let [data   (shape-data #(rf.story/reg-variant* :story.play-typo/v body))
          reason (str (:reason data))]
      (is (= :rf.error/variant-shape (:rf.error/id data)) at)
      (is (str/includes? reason (str " in " at " (did you mean :auto-run??)")) at)
      (is (str/includes? reason (str "Allowed keys in " at ": [:auto-run? :name :script]")) at))))

(deftest reg-fragment-rejects-misspelt-play-map-key
  (let [data (shape-data #(rf.story/reg-fragment* :fragment.play-typo/f
                            {:script {:script [] :autorun? false}}))]
    (is (= :rf.error/fragment-shape (:rf.error/id data)))
    (is (str/includes? (str (:reason data)) ":autorun? in [:script]"))))

(deftest unknown-key-reason-locates-each-offender
  (testing "a top-level and a nested typo are each named against their own map"
    (let [reason (str (:reason (shape-data #(rf.story/reg-variant* :story.play-typo/both
                                              {:scriptz []
                                               :script  {:script [] :autorun? false}}))))]
      (is (str/includes? reason ":scriptz (did you mean :script?), :autorun? in [:script] (did you mean :auto-run??)"))
      (is (str/includes? reason "Allowed keys: ["))
      (is (str/includes? reason "Allowed keys in [:script]: [:auto-run? :name :script]")))))

(deftest reg-variant-canonical-body-round-trips-verbatim
  (testing "the registrar stores the play surface verbatim: read-back is the
            authored body plus :source"
    (let [authored {:setup  []
                    :script {:name "named" :auto-run? false
                             :script [[:dispatch-sync [:counter/initialise 1]]]}}]
      (rf.story/reg-variant* :story.vocab/public-map authored)
      (is (= authored (dissoc (rf.story/variant->edn :story.vocab/public-map) :source))))))

(deftest reg-variant-script-map-form-drives-the-plan
  (rf.story/reg-variant* :story.vocab/map-plan
    {:setup [] :script {:name "named" :auto-run? false :script [[:dispatch [:counter/initialise 1]]]}})
  (let [p (rf.story.plan/variant-plan :story.vocab/map-plan)]
    (is (= [[[:dispatch [:counter/initialise 1]]]
            [{:name "named" :auto-run? false :script [[:dispatch [:counter/initialise 1]]]}]]
           [(:script p) (get-in p [:world :scripts])]))))

(deftest reg-variant-unknown-tag-lists-offenders
  (let [data (shape-data #(rf.story/reg-variant :story.tag/v
                            {:setup [] :tags #{:dev :totally-made-up :also-fake}}))]
    (is (= [:rf.error/unknown-tag #{:totally-made-up :also-fake}]
           [(:rf.error/id data) (set (:unknown data))]))))

(deftest reg-variant-extends-unknown-carries-parent-id
  (testing "an unknown :extends parent registers raw and fails at plan
            compile, naming the parent"
    (rf.story/reg-variant :story.x/child {:extends :story.x/no-such-parent :setup []})
    (let [data (shape-data #(rf.story.plan/variant-plan :story.x/child))]
      (is (= [:rf.error/story-extends-unknown :story.x/no-such-parent]
             [(:rf.error/id data) (:parent data)])))))

;; `:script` and `:plays` are mutually exclusive encodings; storing the raw
;; child body means a child's `:plays` never meets its parent's `:script`.

(deftest reg-variant-extends-child-plays-overrides-parent-play-script
  (rf.story/reg-variant :story.extplay/parent {:setup [] :script {:script [[:dispatch [:p/legacy]]]}})
  (rf.story/reg-variant :story.extplay/child
    {:extends :story.extplay/parent
     :plays   [{:name "happy" :script [[:dispatch [:c/happy]]]}]})
  (is (= [true false] ((juxt #(contains? % :plays) #(contains? % :script))
                       (rf.story/handler-meta :variant :story.extplay/child)))))

(deftest reg-variant-extends-does-not-inherit-play-surface
  (testing "behaviour is local (spec/017): a child declaring no play surface
            does not run its parent's :script"
    (rf.story/reg-variant :story.extplay/parent3 {:setup [] :script {:script [[:dispatch [:p/keep]]]}})
    (rf.story/reg-variant :story.extplay/child3 {:extends :story.extplay/parent3 :doc "no play"})
    (is (= [] (:script (rf.story.plan/variant-plan :story.extplay/child3))))))

;; ---- canonical id grammar -------------------------------------------------

(deftest reg-variant-non-keyword-id-rejected
  (let [data (shape-data #(rf.story/reg-variant* "not-a-keyword" {:setup []}))]
    (is (= :rf.error/variant-id-shape (:rf.error/id data)))
    (is (re-find #"canonical id grammar" (:reason data)))))

(deftest reg-workspace-bad-id-grammar-rejected
  (let [data (shape-data #(rf.story/reg-workspace* :NotAWorkspaceId {:layout :variants-grid}))]
    (is (= :rf.error/workspace-id-shape (:rf.error/id data)))
    (is (re-find #"canonical id grammar" (:reason data)))))

;; ---- Form-B source stamps and desugaring ----------------------------------

(deftest combined-form-stamps-one-source-on-parent-and-generated-variants
  (testing "Form-B variants expand at the parent's macro site, so each carries
            the parent's :source"
    (rf.story/reg-story :story.combined.gen
      {:doc "parent" :variants {:a {:setup [[:init-a]]} :b {:setup [[:init-b]]}}})
    (let [parent (:source (rf.story/handler-meta :story :story.combined.gen))]
      (is (= 're-frame.story-authoring-validation-test (:ns parent)))
      (is (integer? (:line parent)))
      (doseq [vid [:story.combined.gen/a :story.combined.gen/b]]
        (is (= ((juxt :ns :line) parent)
               ((juxt :ns :line) (:source (rf.story/handler-meta :variant vid))))
            (str vid))))))

;; The macro peels only a literal `:variants` map; anything else reaches
;; `reg-story*` with `:variants` on the body, which desugars it at runtime.

(def ^:private formb-variants
  {:a {:setup [[:init-a]]}
   :b {:setup [[:init-b]] :tags #{:dev}}})

(deftest reg-story*-desugars-programmatic-variants
  (is (= :story.prog.formb (rf.story/reg-story* :story.prog.formb {:doc "p" :variants formb-variants})))
  (is (= [{:doc "p"} {:setup [[:init-a]]} {:setup [[:init-b]] :tags #{:dev}}]
         (mapv #(dissoc % :source)
               [(rf.story/handler-meta :story :story.prog.formb)
                (rf.story/handler-meta :variant :story.prog.formb/a)
                (rf.story/handler-meta :variant :story.prog.formb/b)]))
      "each :variants entry registers as <story-id>/<name>; the story body loses :variants"))

(deftest reg-story-macro-desugars-non-literal-variants
  (testing "a computed body handed to the reg-story macro registers its
            variants, stamped with the macro site's coords"
    (rf.story/reg-story :story.prog.macro (assoc {:doc "m"} :variants formb-variants))
    (let [story (rf.story/handler-meta :story :story.prog.macro)
          a     (rf.story/handler-meta :variant :story.prog.macro/a)]
      (is (= [[[:init-a]] true]
             [(:setup a) (rf.story/registered? :variant :story.prog.macro/b)]))
      (is (= ['re-frame.story-authoring-validation-test (:line (:source story))]
             ((juxt :ns :line) (:source a)))))))

(deftest reg-story*-rejects-malformed-variants
  (testing "a non-keyword variant name is a shape error, not a silent drop"
    (is (= :rf.error/story-shape
           (error-id #(rf.story/reg-story* :story.prog.badname {:variants {"a" {:setup []}}}))))
    (is (not (rf.story/registered? :story :story.prog.badname))))
  (testing "a desugared variant body is validated like any reg-variant* body"
    (let [data (shape-data #(rf.story/reg-story* :story.prog.badbody
                              {:variants {:a {:scripts [[:dispatch [:x]]]}}}))]
      (is (= :rf.error/variant-shape (:rf.error/id data)))
      (is (str/includes? (str (:reason data)) ":scripts (did you mean :script?)")))))

;; A literal outer body whose `:variants` is a symbol or an expression must
;; expand: walking it as a map fails with "Don't know how to create ISeq
;; from: clojure.lang.Symbol" or "nth not supported on this type: Symbol".
;; The rows expand at test time, so a broken expansion fails its own row.

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

(deftest reg-story-macro-registers-computed-variants-in-a-literal-body
  (doseq [[id form want]
          [[:story.g2k4.sym
            '(rf.story/reg-story :story.g2k4.sym {:doc "s" :variants formb-variants})
            formb-variants]
           [:story.g2k4.merge
            '(rf.story/reg-story :story.g2k4.merge
               {:doc "s" :variants (merge formb-variants {:c {:setup [[:init-c]]}})})
            (assoc formb-variants :c {:setup [[:init-c]]})]]]
    (testing (name id)
      (is (= id (eval-here form)))
      (let [story (rf.story/handler-meta :story id)]
        (is (= {:doc "s"} (dissoc story :source)))
        (doseq [[v-name v-body] want
                :let [v (rf.story/handler-meta :variant (rf.story.macros/variant-id-for id v-name))]]
          (is (= [v-body (:line (:source story))] [(dissoc v :source) (:line (:source v))])
              (str "variant " v-name)))))))

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
        counted '{:variants (do (swap! evaluations inc) formb-variants)}
        state   #(vector (rf.story/registered? :story :story.g2k4.gate-lit)
                         (rf.story/registered? :variant :story.g2k4.gate-lit/a)
                         (rf.story/registered? :variant :story.g2k4.gate-fwd/a)
                         @evaluations)]
    (reset! evaluations 0)
    (run false (expand :story.g2k4.gate-lit literal))
    (run false (expand :story.g2k4.gate-fwd counted))
    (is (= [false false false 0] (state))
        "gate off: nothing registers and the forwarded :variants is never evaluated")
    (run true (expand :story.g2k4.gate-lit literal))
    (run true (expand :story.g2k4.gate-fwd counted))
    (is (= [true true true 1] (state)) "control: gate on")))

;; ---- `variant-id-for` rejects a non-keyword at macro expansion ------------

(deftest variant-id-for-rejects-non-keyword-story-id
  (let [data (shape-data #(rf.story.macros/variant-id-for "story.foo" :a))]
    (is (= :rf.error/story-bad-id (:rf.error/id data)))
    (is (re-find #"story id must be a keyword" (:reason data)))))

(deftest variant-id-for-rejects-non-keyword-variant-name
  (let [data (shape-data #(rf.story.macros/variant-id-for :story.foo "a"))]
    (is (= :rf.error/story-bad-variant-name (:rf.error/id data)))
    (is (re-find #"variant-name in :variants map" (:reason data)))))

;; ---- configure! validates its keys ----------------------------------------

(deftest configure-bang-rejects-unknown-keys
  (testing "unknown keys fail loudly, all of them named in one pass"
    (let [e (try (rf.story/configure! {:rf.story/edtior :cursor :foo 1})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))
          msg (.getMessage ^Exception e)]
      (is (re-find #":rf.story/edtior" msg))
      (is (re-find #"\[:rf.error/unknown-story-config-key\]" msg)
          "the message ends with the machine-readable error token")
      (is (= {:rf.error/id :rf.error/unknown-story-config-key
              :where       'rf.story/configure!
              :recovery    :fix-call-site
              :unknown     #{:rf.story/edtior :foo}}
             (update (select-keys (ex-data e) [:rf.error/id :where :recovery :unknown])
                     :unknown set)))
      (is (contains? (:known (ex-data e)) :rf.story/editor)))))

(deftest configure-bang-accepts-every-known-key
  (is (nil? (rf.story/configure! {:rf.story/global-args       {:theme :light}
                                  :rf.story/global-decorators []
                                  :rf.story/editor            :vscode
                                  :rf.story/project-root      nil
                                  :rf.story/egress-profile    :rf.egress/local-redacted}))))
