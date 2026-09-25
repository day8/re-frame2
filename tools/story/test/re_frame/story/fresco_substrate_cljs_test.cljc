(ns re-frame.story.fresco-substrate-cljs-test
  "`:fresco` on Story's AUTHORING-LAYER axis, proved on the
  paths that carry a substrate keyword through data rather than through a
  render.

  ## What this namespace is the witness for

  `re-frame.story.schemas/SubstrateSet` is the ONE closed substrate enum
  in the repository, and it admits `:fresco`. Without that member a
  variant declaring `:substrates #{:fresco}` could not be REGISTERED, let
  alone rendered: `registrar/validate-shape!` would throw
  `:rf.error/variant-shape` before any renderer was consulted. The rows
  below say the member reaches the four places a substrate keyword has to
  survive to be worth anything.

  1. **Registration** — the closed shape accepts it, on the variant body
     and on the story body, and STILL refuses an unknown member. An enum
     that quietly opened would pass every other row here.
  2. **Plan compilation** — `rf.story.plan/variant-plan` folds it to
     `[:world :substrates]`, which is where `canonical/render-host-scope`
     reads the declared set.
  3. **The EDN / MCP read path** — `rf.story/variant->edn` is what the MCP
     `list-variants` / read tools relay to an agent, and a keyword that
     did not round-trip would strand the agent on a story it can see and
     cannot describe.
  4. **Snapshot identity** — two fresco views must be two baselines. This
     is the one that could silently collapse: `fingerprint.cljc` folds
     every FUNCTION to the `:rf/opaque-fn` sentinel, so were `:component`
     to accept a component VALUE, two distinct fresco views would hash
     identically. It is a keyword, and these rows are what says so.

  ## Both arms, deliberately

  `.cljc` with a `-cljs-test` ns, so the JVM runner (`clojure -M:test`
  from `tools/story`) and the shadow `:node-test` build (`npm run
  test:cljs`, whose `cljs-test$` regex matches) each run every row. Every
  claim here is about DATA — schema, plan, EDN, hash — so neither arm
  needs a renderer, and nothing here requires `re-frame.fresco`, so
  `tools/story/deps.edn` carries no fresco coordinate for it. The renderer
  itself is proved in
  `re-frame.story.ui.fresco-substrate-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.story :as rf.story]
            [re-frame.story.identity :as rf.story.identity]
            [re-frame.story.plan :as rf.story.plan]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

(use-fixtures :each (fn [t] (reset-all!) (t)))

;; ===========================================================================
;; 1 · the enum — admits :fresco, and is CLOSED
;; ===========================================================================

(deftest substrate-set-admits-fresco
  (testing "`#{:fresco}` is a legal substrate set — were the schema to
            reject it, no fresco variant could be registered at all."
    (is (m/validate rf.story.schemas/SubstrateSet #{:fresco}))
    (is (m/validate rf.story.schemas/SubstrateSet #{:reagent :fresco}))
    (is (m/validate rf.story.schemas/SubstrateSet #{:reagent :uix :fresco})))

  (testing "and :reagent, :uix and the empty set are legal beside it"
    (is (m/validate rf.story.schemas/SubstrateSet #{:reagent}))
    (is (m/validate rf.story.schemas/SubstrateSet #{:uix}))
    (is (m/validate rf.story.schemas/SubstrateSet #{})))

  (testing "the enum is CLOSED, which is the half a widening can lose
            with nothing going red to say so. `:reagent-slim` is the
            reserved member the docstring names as NOT YET admitted, and
            `:helix` is an authoring layer Story does not carry at all; if
            either row ever passes, the enum has become an opening and
            `SubstrateSet` validates nothing."
    (is (not (m/validate rf.story.schemas/SubstrateSet #{:reagent-slim})))
    (is (not (m/validate rf.story.schemas/SubstrateSet #{:helix})))
    (is (not (m/validate rf.story.schemas/SubstrateSet #{:reagent :helix})))
    (is (not (m/validate rf.story.schemas/SubstrateSet #{:fresco :typo})))
    (is (not (m/validate rf.story.schemas/SubstrateSet [:fresco]))
        "a VECTOR is not a set — the slot's shape is a set")))

;; ===========================================================================
;; 2 · registration — the closed body shapes take it, on both bodies
;; ===========================================================================

(deftest a-fresco-variant-registers
  (testing "`reg-variant*` validates the body against
            `VariantBody` and throws `:rf.error/variant-shape` on a miss
            (`re-frame.story.registrar/validate-shape!`); the registration
            landing is the user-visible half of the enum admitting
            `:fresco`."
    (rf.story/reg-story* :story.hic {:doc "fresco authoring-layer fixture"})
    (rf.story/reg-variant* :story.hic/card
      {:doc        "A variant whose subject is a fresco boundary."
       :component  :my.app.views/article-card
       :substrates #{:fresco}})
    (is (= #{:fresco} (:substrates (rf.story/variant->edn :story.hic/card)))))

  (testing "and the STORY body takes it too — `StoryBody` is closed
            independently of `VariantBody`, so a whole story can declare
            the authoring layer once. (That story-level
            declaration reaches the compiled plan too, so the canvas and
            `render-variant` read the same set; see the plan row below.)"
    (rf.story/reg-story* :story.hic-all
      {:doc        "story-level declaration"
       :component  :my.app.views/article-card
       :substrates #{:fresco}})
    (rf.story/reg-variant* :story.hic-all/v {:doc "child"})
    (is (= #{:fresco}
           (:substrates (rf.story.registrar/handler-meta :story :story.hic-all))))))

(deftest an-unknown-substrate-is-still-refused-at-registration
  (testing "the closed enum is enforced where it matters — at
            registration, with the catalogued error id, so an author's
            typo is a loud refusal rather than a variant that renders
            under whatever the shell defaulted to"
    (rf.story/reg-story* :story.hic-bad {:doc "fixture"})
    (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
          (rf.story/reg-variant* :story.hic-bad/typo
            {:doc "declares a substrate nobody defines"
             :substrates #{:hicaso}})))))

;; ===========================================================================
;; 3 · plan compilation — `[:world :substrates]` is where the host reads it
;; ===========================================================================
;;
;; `rf.story.plan/variant-plan` folds `:substrates` (and `:component`) from
;; the VARIANT, its `:extends` chain AND the parent story, with the canvas's
;; own variant-then-story precedence. So a substrate declared ONLY at story
;; level reaches `canonical/render-host-scope`, which reads
;; `[:world :substrates]`, exactly as it reaches the canvas through
;; `multi-substrate/resolve-substrate-set`; a story-blind fold would leave
;; the host on its `:reagent` default. The witness for that fold is
;; `re-frame.story.story-scope-world-keys-cljs-test`; the rows below stay
;; scoped to what a FRESCO declaration carries.

(deftest the-plan-carries-the-fresco-declaration
  (testing "`canonical/render-host-scope` reads the COMPILED PLAN's
            `[:world :substrates]`, not a literal `:reagent`, so that slot
            is the one a fresco variant has to reach. It is
            folded by `rf.story.plan/variant-plan`, already `:extends`-merged."
    (rf.story/reg-story* :story.hicplan {:doc "fixture"})
    (rf.story/reg-variant* :story.hicplan/v
      {:doc        "declares the native authoring layer"
       :component  :my.app.views/article-card
       :substrates #{:fresco}})
    (let [p (rf.story.plan/variant-plan :story.hicplan/v)]
      (is (= #{:fresco} (get-in p [:world :substrates])))
      (is (= :my.app.views/article-card (get-in p [:world :component]))
          "and the subject rides beside it — the two slots the renderer
           needs are both on the plan")))

  (testing "`:extends` inheritance carries it, so a fresco base story's
            children do not each re-declare the layer"
    (rf.story/reg-story* :story.hicext {:doc "fixture"})
    (rf.story/reg-variant* :story.hicext/base
      {:doc "base" :component :my.app.views/article-card
       :substrates #{:fresco}})
    (rf.story/reg-variant* :story.hicext/child
      {:doc "child" :extends :story.hicext/base})
    (is (= #{:fresco}
           (get-in (rf.story.plan/variant-plan :story.hicext/child)
                   [:world :substrates])))))

;; ===========================================================================
;; 4 · the EDN / MCP read path
;; ===========================================================================

(deftest the-substrate-keyword-round-trips-through-the-mcp-read-path
  (testing "`variant->edn` returns the registered body as serialisable EDN
            — the shape `re-frame.story-mcp`'s read tools relay to an
            agent. A keyword that did not survive here would leave an
            agent able to list a fresco story and unable to say what it
            renders under."
    (rf.story/reg-story* :story.hicedn {:doc "fixture"})
    (let [body {:doc        "a fresco variant"
                :component  :my.app.views/article-card
                :substrates #{:fresco}
                :args       {:label "one"}}]
      (rf.story/reg-variant* :story.hicedn/v body)
      (let [edn (rf.story/variant->edn :story.hicedn/v)]
        (is (= #{:fresco} (:substrates edn)))
        (is (= :my.app.views/article-card (:component edn))
            "and `:component` is a KEYWORD — a fresco view is registered
             fresco-side, never passed to `:component` as a value")
        (is (= body (select-keys edn (keys body)))
            "the body round-trips verbatim; `:source` is the registrar's
             own stamp and is the only addition")))))

;; ===========================================================================
;; 5 · snapshot identity — two fresco views are two baselines
;; ===========================================================================

(deftest two-fresco-view-ids-are-two-identities
  (testing "`:component` takes a keyword, never a component VALUE:
            `fingerprint.cljc` canonicalises every fn to the
            `:rf/opaque-fn` sentinel, so two distinct fresco heads passed
            as values would be INDISTINGUISHABLE to snapshot identity —
            one visual-regression baseline for two views. Naming them with
            keywords is what keeps them apart, and this row pins it."
    (rf.story/reg-story* :story.hicid {:doc "fixture"})
    (rf.story/reg-variant* :story.hicid/card
      {:doc "one" :component :my.app.views/article-card :substrates #{:fresco}})
    (rf.story/reg-variant* :story.hicid/panel
      {:doc "one" :component :my.app.views/side-panel :substrates #{:fresco}})
    (let [a (:content-hash (rf.story.identity/snapshot-identity :story.hicid/card))
          b (:content-hash (rf.story.identity/snapshot-identity :story.hicid/panel))]
      (is (string? a))
      (is (not= a b)
          "two fresco view ids, differing in NOTHING but `:component`,
           get distinct content hashes")))

  (testing "and the authoring layer is identity-bearing in its own right —
            the same view stories under two layers are two baselines,
            because the two renderers paint two trees"
    (rf.story/reg-story* :story.hiclayer {:doc "fixture"})
    (rf.story/reg-variant* :story.hiclayer/hic
      {:doc "x" :component :my.app.views/article-card :substrates #{:fresco}})
    (rf.story/reg-variant* :story.hiclayer/rea
      {:doc "x" :component :my.app.views/article-card :substrates #{:reagent}})
    (is (not= (:content-hash (rf.story.identity/snapshot-identity :story.hiclayer/hic))
              (:content-hash (rf.story.identity/snapshot-identity :story.hiclayer/rea)))))

  (testing "the hash is STABLE for one fresco variant across calls — the
            distinctions above are the tuple's, not run-to-run noise"
    (rf.story/reg-story* :story.hicstable {:doc "fixture"})
    (rf.story/reg-variant* :story.hicstable/v
      {:doc "x" :component :my.app.views/article-card :substrates #{:fresco}})
    (is (= (:content-hash (rf.story.identity/snapshot-identity :story.hicstable/v))
           (:content-hash (rf.story.identity/snapshot-identity :story.hicstable/v))))))
