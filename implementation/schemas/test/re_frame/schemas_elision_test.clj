(ns re-frame.schemas-elision-test
  "JVM tests for the `:large?` schema-metadata walker (rf2-nwv63).

  Per Spec 009 §Size elision in traces, the schema-driven nomination
  path: any Malli slot carrying `:large? true` in its per-slot props
  (per Spec-Schemas §`:rf/app-schema-meta`) is registered into the
  frame's `[:rf/elision :declarations]` slot with `:source :schema`.

  This test file covers the walker mechanism in isolation:

    1. **Unit** — `extract-large-paths-from-schema` recognises every
       Malli shape `:large?` can legally live in (slot-level props,
       container-level props, nested `:map`, `:vector`, `:or`,
       `:multi`, etc.).
    2. **Integration** — `populate-elision-declarations` walks every
       schema in a frame and produces the unified registry shape, and
       is idempotent (repeat calls don't duplicate / corrupt).
    3. **Edge cases** — nested schemas, vector-of slots, `:or`/`:multi`
       combinators, the `:hint` propagation.

  Does NOT cover the actual app-db write path — that lives in
  `re-frame.elision` downstream (rf2-v9tw2). The seam between the two
  is the `:schemas/populate-elision-declarations` late-bind hook."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.schemas :as schemas]
            [re-frame.schemas.test-fixture :as tf]))

(use-fixtures :each tf/reset-runtime)

;; ---- extract-large-paths-from-schema --------------------------------------

(deftest extract-no-large-slots
  (testing "a schema with no :large? props produces no entries"
    (is (= {} (schemas/extract-large-paths-from-schema [:map [:name :string]] [])))
    (is (= {} (schemas/extract-large-paths-from-schema :string [])))
    (is (= {} (schemas/extract-large-paths-from-schema :int [:a :b])))))

(deftest extract-slot-level-large
  (testing "the slot's per-slot props carry :large? true"
    (let [schema [:map
                  [:name :string]
                  [:uploaded-pdf {:large? true :hint "Upload preview blob"} :string]]]
      (is (= {[:uploaded-pdf] {:large? true
                               :source :schema
                               :hint   "Upload preview blob"}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest extract-honours-base-path
  (testing "base-path is prepended to every discovered slot path"
    (let [schema [:map
                  [:uploaded-pdf {:large? true} :string]]]
      (is (= {[:user :uploaded-pdf] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema [:user]))))))

(deftest extract-container-level-large
  (testing "the schema's OWN props (not a parent slot's props) claim the base-path"
    ;; The user wrote `(reg-app-schema [:user :pdf] [:string {:large? true}])`
    ;; — the reg-app-schema path is where the marker fires; the schema's
    ;; own props claim it.
    (is (= {[:user :pdf] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:string {:large? true}] [:user :pdf])))))

(deftest extract-nested-map
  (testing "nested :map carries the path through every level"
    (let [schema [:map
                  [:user [:map
                          [:profile :string]
                          [:uploads [:map
                                     [:pdf {:large? true} :string]]]]]]]
      (is (= {[:user :uploads :pdf] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest extract-multiple-large-slots
  (testing "every :large? true slot is captured"
    (let [schema [:map
                  [:a {:large? true} :string]
                  [:b :string]
                  [:c {:large? true :hint "c-hint"} :string]
                  [:d [:map [:e {:large? true} :string]]]]]
      (is (= {[:a]    {:large? true :source :schema}
              [:c]    {:large? true :source :schema :hint "c-hint"}
              [:d :e] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest extract-vector-of
  (testing ":vector containers don't introduce a path segment, but inner :large? still claims the container's path"
    ;; A `:vector` of large strings — each element is large, but vectors
    ;; have no name slots, so the elision claim lands on the vector's
    ;; own path (where reg-app-schema landed).
    (is (= {[:uploads] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:vector [:string {:large? true}]] [:uploads])))))

(deftest extract-or-combinator
  (testing ":or descends into every branch at the same base-path"
    ;; Either branch declaring :large? claims the path.
    (is (= {[:user :payload] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:or :string [:string {:large? true}]]
             [:user :payload])))))

(deftest extract-maybe-passthrough
  (testing ":maybe descends to the inner schema"
    (is (= {[:user :pdf] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:maybe [:string {:large? true}]]
             [:user :pdf])))))

(deftest extract-multi-dispatch-branches
  (testing ":multi dispatch values are NOT path segments — branch schemas descend at the parent path"
    ;; The branch value (`:pdf`, `:img`) is a dispatch tag, not an
    ;; app-db key. A :map branch carries its own name slots; when the
    ;; dispatched value matches that branch, its :bytes slot IS a real
    ;; app-db sub-path of the :multi's path.
    (is (= {[:user :upload :bytes] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:multi {:dispatch :type}
              [:pdf [:map [:bytes {:large? true} :string]]]
              [:img :string]]
             [:user :upload])))))

(deftest extract-multi-branch-level-large
  (testing ":large? on a :multi branch's own props claims the parent :multi path"
    ;; When the :large? sits on the branch slot (not the inner schema),
    ;; the dispatched value as a whole is large — claims the :multi
    ;; path, not a sub-path. (Branch-slot props apply to the dispatched
    ;; value itself.)
    (is (= {[:user :upload] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:multi {:dispatch :type}
              [:pdf {:large? true} :string]
              [:img :string]]
             [:user :upload])))))

(deftest extract-hint-only-with-large
  (testing ":hint without :large? produces no declaration"
    ;; The reserved per-slot key vocabulary documents :hint as a sibling
    ;; of :large? — it is only meaningful when :large? rides alongside.
    (is (= {}
           (schemas/extract-large-paths-from-schema
             [:map [:foo {:hint "explains the slot"} :string]]
             [])))))

(deftest extract-explicit-false-skipped
  (testing ":large? false is NOT captured — only :large? true nominates"
    (is (= {}
           (schemas/extract-large-paths-from-schema
             [:map [:foo {:large? false} :string]]
             [])))))

(deftest extract-tuple-positions
  (testing ":tuple children descend at the same base-path"
    ;; Positional tuple positions don't get their own path segments;
    ;; the :large? claim lands on the tuple's path.
    (is (= {[:row] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:tuple :string [:string {:large? true}]]
             [:row])))))

;; ---- frame-elision-declarations -------------------------------------------

(deftest frame-merge-across-schemas
  (testing "every registered schema contributes its :large? slots"
    (rf/reg-app-schema [:user] [:map
                                [:name :string]
                                [:pdf {:large? true :hint "user pdf"} :string]])
    (rf/reg-app-schema [:downloads] [:map
                                     [:csv {:large? true} :string]])
    (let [merged (schemas/frame-elision-declarations)]
      (is (= {[:user :pdf]      {:large? true :source :schema :hint "user pdf"}
              [:downloads :csv] {:large? true :source :schema}}
             merged)))))

(deftest frame-empty-when-no-large-slots
  (testing "frames with no :large? slots produce an empty registry"
    (rf/reg-app-schema [:count] [:int])
    (rf/reg-app-schema [:user] [:map [:name :string]])
    (is (= {} (schemas/frame-elision-declarations)))))

(deftest frame-isolation
  (testing "schemas in one frame don't leak into another"
    (rf/reg-app-schema [:user]
                       [:map [:pdf {:large? true} :string]]
                       {:frame :frame-a})
    (rf/reg-app-schema [:user]
                       [:map [:img {:large? true} :string]]
                       {:frame :frame-b})
    (is (= {[:user :pdf] {:large? true :source :schema}}
           (schemas/frame-elision-declarations :frame-a)))
    (is (= {[:user :img] {:large? true :source :schema}}
           (schemas/frame-elision-declarations :frame-b)))))

;; ---- populate-elision-declarations ----------------------------------------

(deftest populate-on-empty-db
  (testing "populate hydrates [:rf/elision :declarations] from schemas"
    (rf/reg-app-schema [:user] [:map
                                [:pdf {:large? true :hint "blob"} :string]])
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-elision-declarations {} frame-id)]
      (is (= {:rf/elision
              {:declarations
               {[:user :pdf] {:large? true :source :schema :hint "blob"}}}}
             db')))))

(deftest populate-idempotent
  (testing "calling populate twice produces the same db both times"
    (rf/reg-app-schema [:user] [:map
                                [:pdf {:large? true} :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-elision-declarations {} frame-id)
          db-2     (schemas/populate-elision-declarations db-1 frame-id)]
      (is (= db-1 db-2)
          "second call is a no-op when the schema set is unchanged"))))

(deftest populate-replaces-prior-non-schema-source
  (testing "schema metadata is canonical for the populated registry slot"
    (rf/reg-app-schema [:user] [:map
                                [:pdf {:large? true :hint "schema-hint"} :string]])
    (let [frame-id  (frame/current-frame)
          db-with-prior
          {:rf/elision
           {:declarations
            {[:user :pdf] {:large? true
                           :source :legacy
                           :hint   "user-fx-hint"}}}}
          db'      (schemas/populate-elision-declarations
                     db-with-prior frame-id)]
      (is (= "schema-hint"
             (get-in db' [:rf/elision :declarations [:user :pdf] :hint]))
          "schema :hint replaces older registry state")
      (is (= :schema
             (get-in db' [:rf/elision :declarations [:user :pdf] :source]))
          "schema :source replaces older registry state"))))

(deftest populate-overwrites-prior-schema-entry
  (testing "a re-registered schema with a different :hint refreshes the schema-source slot"
    ;; Hot-reload semantics: the schema layer is the source of truth for
    ;; :source :schema entries. Re-registering a schema updates the
    ;; existing schema slot.
    (rf/reg-app-schema [:user] [:map
                                [:pdf {:large? true :hint "old"} :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-elision-declarations {} frame-id)]
      ;; Re-register with a new hint
      (rf/reg-app-schema [:user] [:map
                                  [:pdf {:large? true :hint "new"} :string]])
      (let [db-2 (schemas/populate-elision-declarations db-1 frame-id)]
        (is (= "new"
               (get-in db-2 [:rf/elision :declarations [:user :pdf] :hint])))
        (is (= :schema
               (get-in db-2 [:rf/elision :declarations [:user :pdf] :source])))))))

(deftest populate-noop-when-no-large-slots
  (testing "populate is a no-op when no schema declares :large?"
    (rf/reg-app-schema [:count] [:int])
    (let [frame-id (frame/current-frame)
          db-in    {:count 42}]
      (is (= db-in (schemas/populate-elision-declarations db-in frame-id))))))

(deftest populate-prunes-stale-schema-entry-on-flag-removal
  (testing "re-registering a schema without :large? prunes the prior :source :schema entry (rf2-kr3vp)"
    ;; Bug class: implementation drift from spec. Spec says
    ;; schema-derived declarations are authoritative after re-
    ;; registration; the prior shape only overwrote paths still
    ;; present in the new schema, so stale `:source :schema` entries
    ;; lingered after the flag was removed.
    (rf/reg-app-schema [:user]
                       [:map [:pdf {:large? true :hint "uploads"} :string]])
    (let [frame-id (frame/current-frame)
          db-1     (schemas/populate-elision-declarations {} frame-id)]
      (is (= {[:user :pdf] {:large? true :source :schema :hint "uploads"}}
             (get-in db-1 [:rf/elision :declarations]))
          "baseline: schema-derived entry present")
      ;; Re-register the same schema with `:large?` removed.
      (rf/reg-app-schema [:user]
                         [:map [:pdf :string]])
      (let [db-2 (schemas/populate-elision-declarations db-1 frame-id)]
        (is (nil? (:rf/elision db-2))
            "after flag removal — stale schema entry pruned and empty root removed")))))

(deftest populate-prunes-registry-slot
  (testing "pruning removes the schema-owned registry slot (rf2-kr3vp)"
    (rf/reg-app-schema [:user]
                       [:map [:pdf {:large? true} :string]])
    (let [frame-id (frame/current-frame)
          db-with-prior
          {:rf/elision
           {:declarations
            {[:secrets] {:large? true
                         :source :legacy
                         :hint   "user-fx"}}}}
          db-1 (schemas/populate-elision-declarations db-with-prior frame-id)]
      (is (= :schema
             (get-in db-1 [:rf/elision :declarations [:user :pdf] :source]))
          "schema entry replaces prior registry state")
      ;; Now wipe the schema set entirely (drop the registration).
      (reset! schemas/schemas-by-frame {})
      (let [db-2 (schemas/populate-elision-declarations db-1 frame-id)]
        (is (nil? (:rf/elision db-2))
            "schema entry pruned; empty registry root removed")))))

(deftest populate-replaces-legacy-source
  (testing "schema metadata replaces any legacy source in the declaration slot"
    (rf/reg-app-schema [:user]
                       [:map [:pdf {:large? true} :string]])
    (let [frame-id (frame/current-frame)
          db-with-legacy
          {:rf/elision
           {:declarations
            {[:user :pdf] {:large? true
                           :source :legacy}}}}
          db'      (schemas/populate-elision-declarations
                     db-with-legacy frame-id)]
      (is (= :schema
             (get-in db' [:rf/elision :declarations [:user :pdf] :source]))))))

;; ---- combined integration -------------------------------------------------

(deftest reg-app-schemas-bulk-walker
  (testing "bulk reg-app-schemas + populate produces the merged registry"
    (rf/reg-app-schemas
      {[:user]      [:map [:pdf {:large? true :hint "uploads"} :string]]
       [:downloads] [:map [:csv {:large? true} :string]]
       [:counter]   :int})
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-elision-declarations {} frame-id)]
      (is (= {[:user :pdf]      {:large? true :source :schema :hint "uploads"}
              [:downloads :csv] {:large? true :source :schema}}
             (get-in db' [:rf/elision :declarations]))))))

(deftest deeply-nested-large
  (testing "deeply nested :large? slots resolve to the full path"
    (rf/reg-app-schema
      [:root]
      [:map
       [:a [:map
            [:b [:map
                 [:c [:map
                      [:d {:large? true :hint "deep"} :string]]]]]]]])
    (let [frame-id (frame/current-frame)
          db'      (schemas/populate-elision-declarations {} frame-id)]
      (is (= {[:root :a :b :c :d] {:large? true :source :schema :hint "deep"}}
             (get-in db' [:rf/elision :declarations]))))))
