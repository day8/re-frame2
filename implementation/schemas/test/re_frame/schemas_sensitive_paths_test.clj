(ns re-frame.schemas-sensitive-paths-test
  "JVM tests for the rf2-c1l4d schema-`:sensitive?` registry feeder —
  the parallel of rf2-nwv63's `:large?` registry feeder.

  rf2-kj51z (PR #673) already ships the walker that recognises
  `:sensitive?` slot-level + container-level props inside a Malli EDN
  schema (`extract-sensitive-paths-from-schema` and
  `schema-has-sensitive?`). rf2-c1l4d extends the contract one step
  further: aggregate every registered frame's sensitive-path
  declarations and write them into a sibling slot in the unified
  elision registry (`[:rf/elision :sensitive-declarations]`) at boot /
  on `reg-app-schema` re-registration. Same shape, same idempotency,
  same conflict-resolution rule as the `:large?` registry.

  Test surfaces this file covers:

    1. **Walker per-entry shape** — every entry the walker emits
       carries `:source :schema` (parallel to the `:large?` walker)
       so the unified registry can route on provenance.
    2. **Hint propagation** — when the slot's props declare `:hint`,
       it rides into the registry entry verbatim.
    3. **`frame-sensitive-declarations`** — aggregates across every
       schema registered in a frame; isolated per frame.
    4. **`populate-sensitive-declarations`** — idempotent hydration of
       `[:rf/elision :sensitive-declarations]`; preserves `:source
       :declared` entries; refreshes `:source :schema` entries on
       hot-reload.
    5. **Composition** — a slot flagged BOTH `:sensitive?` AND
       `:large?` lands in BOTH sibling registries (the two flags
       compose orthogonally on the registry side; the validation
       emit-site resolves the sensitive-wins precedence at trace
       time, covered in `schemas_sensitive_test.clj`).

  Does NOT cover the actual app-db write path at boot — that lives in
  `re-frame.elision` / `re-frame.core` downstream (the rf2-c1l4d
  follow-on integration). The seam between the two is the
  `:schemas/populate-sensitive-declarations` late-bind hook."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- walker per-entry shape ----------------------------------------------

(deftest walker-stamps-source-schema
  (testing "every emitted entry carries :source :schema so the unified
            registry can apply provenance-based conflict resolution"
    (let [schema [:map [:password {:sensitive? true} :string]]
          decls  (schemas/extract-sensitive-paths-from-schema schema [])]
      (is (= {[:password] {:sensitive? true :source :schema}}
             decls)
          "single-slot — :source :schema present"))))

(deftest walker-propagates-hint
  (testing "the :hint string on a sensitive slot's props rides into the
            registry entry verbatim — mirrors the :large? walker"
    (let [schema [:map
                  [:password {:sensitive? true :hint "User password — argon2id"}
                   :string]]]
      (is (= {[:password] {:sensitive? true
                           :source     :schema
                           :hint       "User password — argon2id"}}
             (schemas/extract-sensitive-paths-from-schema schema []))))))

(deftest walker-hint-only-without-sensitive
  (testing ":hint without :sensitive? produces no declaration"
    ;; Mirrors the :large? walker — :hint alone is not a nomination.
    (is (= {}
           (schemas/extract-sensitive-paths-from-schema
             [:map [:foo {:hint "just a description"} :string]]
             [])))))

(deftest walker-explicit-false-skipped
  (testing ":sensitive? false is NOT captured — only :sensitive? true nominates"
    (is (= {}
           (schemas/extract-sensitive-paths-from-schema
             [:map [:foo {:sensitive? false} :string]]
             [])))))

;; ---- frame-sensitive-declarations ----------------------------------------

(deftest frame-merge-across-schemas
  (testing "every registered schema contributes its :sensitive? slots
            to the frame's merged declarations map"
    (rf/reg-app-schema [:user]
                       [:map
                        [:name :string]
                        [:password {:sensitive? true :hint "argon2id"} :string]])
    (rf/reg-app-schema [:auth]
                       [:map
                        [:token {:sensitive? true} :string]])
    (let [merged (schemas/frame-sensitive-declarations)]
      (is (= {[:user :password] {:sensitive? true :source :schema
                                 :hint "argon2id"}
              [:auth :token]    {:sensitive? true :source :schema}}
             merged)))))

(deftest frame-empty-when-no-sensitive-slots
  (testing "frames with no :sensitive? slots produce an empty registry"
    (rf/reg-app-schema [:count] [:int])
    (rf/reg-app-schema [:user] [:map [:name :string]])
    (is (= {} (schemas/frame-sensitive-declarations)))))

(deftest frame-isolation
  (testing "schemas in one frame don't leak into another — per-frame
            isolation parallel to the :large? walker"
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true} :string]]
                       {:frame :frame-a})
    (rf/reg-app-schema [:user]
                       [:map [:token {:sensitive? true} :string]]
                       {:frame :frame-b})
    (is (= {[:user :password] {:sensitive? true :source :schema}}
           (schemas/frame-sensitive-declarations :frame-a)))
    (is (= {[:user :token] {:sensitive? true :source :schema}}
           (schemas/frame-sensitive-declarations :frame-b)))))

;; ---- populate-sensitive-declarations -------------------------------------

(deftest populate-on-empty-db
  (testing "populate hydrates [:rf/elision :sensitive-declarations] from schemas"
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true :hint "argon2id"}
                              :string]])
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-sensitive-declarations {} frame-id)]
      (is (= {:rf/elision
              {:sensitive-declarations
               {[:user :password] {:sensitive? true :source :schema
                                   :hint "argon2id"}}}}
             db')))))

(deftest populate-idempotent
  (testing "calling populate twice produces the same db both times"
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true} :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-sensitive-declarations {} frame-id)
          db-2     (schemas/populate-sensitive-declarations db-1 frame-id)]
      (is (= db-1 db-2)
          "second call is a no-op when the schema set is unchanged"))))

(deftest populate-preserves-declared-source
  (testing "existing :source :declared entries are NEVER overwritten by
            schema entries — conflict rule: declared beats schema"
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true :hint "schema-hint"}
                              :string]])
    (let [frame-id  (frame/current-frame)
          db-with-declared
          {:rf/elision
           {:sensitive-declarations
            {[:user :password] {:sensitive? true
                                :source     :declared
                                :hint       "user-fx-hint"}}}}
          db' (schemas/populate-sensitive-declarations
                db-with-declared frame-id)]
      (is (= "user-fx-hint"
             (get-in db' [:rf/elision :sensitive-declarations
                          [:user :password] :hint]))
          "declared :hint preserved")
      (is (= :declared
             (get-in db' [:rf/elision :sensitive-declarations
                          [:user :password] :source]))
          "declared :source preserved"))))

(deftest populate-overwrites-prior-schema-entry
  (testing "a re-registered schema with a different :hint refreshes the
            schema-source slot — hot-reload semantics"
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true :hint "old"}
                              :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-sensitive-declarations {} frame-id)]
      (rf/reg-app-schema [:user]
                         [:map [:password {:sensitive? true :hint "new"}
                                :string]])
      (let [db-2 (schemas/populate-sensitive-declarations db-1 frame-id)]
        (is (= "new"
               (get-in db-2 [:rf/elision :sensitive-declarations
                             [:user :password] :hint])))
        (is (= :schema
               (get-in db-2 [:rf/elision :sensitive-declarations
                             [:user :password] :source])))))))

(deftest populate-noop-when-no-sensitive-slots
  (testing "populate is a no-op when no schema declares :sensitive?"
    (rf/reg-app-schema [:count] [:int])
    (let [frame-id (frame/current-frame)
          db-in    {:count 42}]
      (is (= db-in (schemas/populate-sensitive-declarations db-in frame-id))))))

(deftest populate-prunes-stale-schema-entry-on-flag-removal
  (testing "re-registering a schema without :sensitive? prunes the prior :source :schema entry (rf2-kr3vp)"
    ;; Parallel to the :large? prune test in schemas_elision_test.clj —
    ;; the parameterised feeder serves both flags so both paths share
    ;; the prune-on-re-registration contract.
    (rf/reg-app-schema [:user]
                       [:map [:password {:sensitive? true :hint "argon2id"} :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-sensitive-declarations {} frame-id)]
      (is (= {[:user :password] {:sensitive? true :source :schema :hint "argon2id"}}
             (get-in db-1 [:rf/elision :sensitive-declarations]))
          "baseline: schema-derived entry present")
      ;; Re-register the same schema with `:sensitive?` removed.
      (rf/reg-app-schema [:user]
                         [:map [:password :string]])
      (let [db-2 (schemas/populate-sensitive-declarations db-1 frame-id)]
        (is (= {} (get-in db-2 [:rf/elision :sensitive-declarations]))
            "after flag removal — stale schema entry pruned")))))

;; ---- nested + edge-case coverage ----------------------------------------

(deftest deeply-nested-sensitive
  (testing "deeply nested :sensitive? slots resolve to the full path"
    (rf/reg-app-schema
      [:root]
      [:map
       [:a [:map
            [:b [:map
                 [:c [:map
                      [:d {:sensitive? true :hint "deep"} :string]]]]]]]])
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-sensitive-declarations {} frame-id)]
      (is (= {[:root :a :b :c :d] {:sensitive? true :source :schema
                                   :hint "deep"}}
             (get-in db' [:rf/elision :sensitive-declarations]))))))

(deftest vector-of-sensitive
  (testing ":vector container's inner :sensitive? claims the container's path"
    (rf/reg-app-schema [:tokens]
                       [:vector [:string {:sensitive? true}]])
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-sensitive-declarations {} frame-id)]
      (is (= {[:tokens] {:sensitive? true :source :schema}}
             (get-in db' [:rf/elision :sensitive-declarations]))))))

(deftest reg-app-schemas-bulk
  (testing "bulk reg-app-schemas + populate produces the merged registry"
    (rf/reg-app-schemas
      {[:user]    [:map [:password {:sensitive? true :hint "argon2id"} :string]]
       [:auth]    [:map [:token {:sensitive? true} :string]]
       [:counter] :int})
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-sensitive-declarations {} frame-id)]
      (is (= {[:user :password] {:sensitive? true :source :schema
                                 :hint "argon2id"}
              [:auth :token]    {:sensitive? true :source :schema}}
             (get-in db' [:rf/elision :sensitive-declarations]))))))

;; ---- composition with :large? -------------------------------------------

(deftest both-flags-on-same-slot-populate-both-registries
  (testing "Per Spec 010 §`:sensitive?` + Spec 009 §Unified wire-elision
            surface — a slot carrying BOTH `:sensitive? true` and
            `:large? true` lands in BOTH sibling registries
            (`:declarations` for `:large?`, `:sensitive-declarations`
            for `:sensitive?`).

            The validation emit-site's composition rule (sensitive
            wins on the schema-validation trace) is covered by
            `schemas_sensitive_test.clj`; this test pins the
            registry-side composition: both registries are populated,
            and a downstream consumer can join them path-by-path to
            decide elision vs redaction."
    (rf/reg-app-schema [:user :secret-pdf]
                       [:string {:sensitive? true :large? true
                                 :hint "encrypted-pdf"}])
    (let [frame-id (frame/current-frame)
          db'      (-> {}
                       (schemas/populate-elision-declarations frame-id)
                       (schemas/populate-sensitive-declarations frame-id))]
      ;; :large? registry — the slot is large.
      (is (= {[:user :secret-pdf]
              {:large? true :source :schema :hint "encrypted-pdf"}}
             (get-in db' [:rf/elision :declarations]))
          ":large? entry in :declarations")
      ;; :sensitive? registry — the slot is sensitive.
      (is (= {[:user :secret-pdf]
              {:sensitive? true :source :schema :hint "encrypted-pdf"}}
             (get-in db' [:rf/elision :sensitive-declarations]))
          ":sensitive? entry in :sensitive-declarations")
      ;; The walker's composition rule (sensitive wins) is enforced at
      ;; the emit site (schemas_sensitive_test.clj §sensitive-overrides-
      ;; large-on-same-slot); this test pins the registry-side
      ;; orthogonal composition.
      (is (true? (schemas/schema-has-sensitive?
                   [:string {:sensitive? true :large? true}]))))))

(deftest one-slot-large-other-sensitive
  (testing "two sibling slots — one :large?, one :sensitive? — populate
            disjoint sibling registries"
    (rf/reg-app-schema [:user]
                       [:map
                        [:pdf {:large? true :hint "big blob"} :string]
                        [:password {:sensitive? true} :string]])
    (let [frame-id (frame/current-frame)
          db'      (-> {}
                       (schemas/populate-elision-declarations frame-id)
                       (schemas/populate-sensitive-declarations frame-id))]
      (is (= {[:user :pdf] {:large? true :source :schema :hint "big blob"}}
             (get-in db' [:rf/elision :declarations])))
      (is (= {[:user :password] {:sensitive? true :source :schema}}
             (get-in db' [:rf/elision :sensitive-declarations]))))))
