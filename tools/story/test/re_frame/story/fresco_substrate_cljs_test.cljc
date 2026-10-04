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
  below say the member is accepted where a substrate keyword enters:

  1. **Registration** — the closed shape accepts it, on the variant body
     and on the story body, and STILL refuses an unknown member. An enum
     that quietly opened would pass every other row here.
  2. **The EDN / MCP read path** — `rf.story/variant->edn` is what the MCP
     `list-variants` / read tools relay to an agent, and the registration
     row reads the declared set back through it.

  Plan compilation folds the set to `[:world :substrates]`, where
  `canonical/render-host-scope` reads it; the witness for that fold is
  `re-frame.story.story-scope-world-keys-cljs-test`.

  ## Both arms, deliberately

  `.cljc` with a `-cljs-test` ns, so the JVM runner (`clojure -M:test`
  from `tools/story`) and the shadow `:node-test` build (`npm run
  test:cljs`, whose `cljs-test$` regex matches) each run every row. Every
  claim here is about DATA — schema and EDN — so neither arm
  needs a renderer, and nothing here requires `re-frame.fresco`, so
  `tools/story/deps.edn` carries no fresco coordinate for it. The renderer
  itself is proved in
  `re-frame.story.ui.fresco-substrate-dom-cljs-test`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.story :as rf.story]
            [re-frame.story.registrar :as rf.story.registrar]
            [re-frame.story.schemas :as rf.story.schemas]))

;; ---- fixture --------------------------------------------------------------

(defn- reset-all! []
  (rf.story/clear-all!)
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  ;; Registration and the default frame build state containers through the
  ;; installed substrate adapter, so the namespace installs its own rather
  ;; than relying on one an earlier namespace left behind.
  (try (rf/init! rf.substrate.plain-atom/adapter)
       (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) _ nil))
  (rf.story/install-canonical-vocabulary!)
  (rf.frame/ensure-default-frame!))

;; The adapter slot is handed back as found: one this fixture seated is
;; disposed afterwards rather than left for a later namespace to lean on.
(use-fixtures :each
  (fn [t]
    (let [seated? (some? (rf/current-adapter))]
      (reset-all!)
      (try (t)
           (finally (when-not seated? (rf/destroy-adapter!)))))))

;; ===========================================================================
;; 1 · the enum — admits :fresco, and is CLOSED
;; ===========================================================================

(deftest substrate-set-admits-fresco
  (testing "`#{:fresco}` is a legal substrate set — were the schema to
            reject it, no fresco variant could be registered at all."
    (is (m/validate rf.story.schemas/SubstrateSet #{:fresco})))

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
            `render-variant` read the same set; see
            `re-frame.story.story-scope-world-keys-cljs-test`.)"
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
    (let [e (try (rf.story/reg-variant* :story.hic-bad/typo
                   {:doc "declares a substrate nobody defines"
                    :substrates #{:hicaso}})
                 nil
                 (catch #?(:clj clojure.lang.ExceptionInfo :cljs :default) e e))]
      (is (= :rf.error/variant-shape (:rf.error/id (ex-data e)))))))
