(ns re-frame.schemas-walker-large-test
  "JVM tests pinning the `:large?` arm of the per-slot flag walker, plus
  the `:hint` propagation contract (rf2-ynjts.12 coverage pass).

  The walker (`re-frame.schemas.walker/walk-flagged-schema`) is
  parameterised on the per-slot flag key and serves both `:sensitive?`
  and `:large?` (Spec 009 §Size elision in traces / Spec 010
  §`:large?`). The `:sensitive?` arm is exhaustively unit-tested across
  every operator family by `schemas_walker_operators_test`, but the
  `:large?` public entry point — `extract-large-paths-from-schema`,
  re-exported from `re-frame.schemas` and published as the
  `:schemas/extract-large-paths-from-schema` late-bind hook that the
  machines / resources / http artefacts consume (EP-0015 §8 removed the
  former `re-frame.elision` registry-population feed; durable egress
  classification is now frame-owned) — had no direct slice-local unit
  coverage.

  A regression in the `:large?` wiring (a wrong flag-key threaded
  through the shared walker, or a structural-recognition break that the
  `:sensitive?` tests don't reach because they pin the other flag)
  would slip past the suite. These tests pin the `:large?` entry point
  directly against the same structural matrix the `:sensitive?` arm
  pins — both flags share one traversal, so locking both ends pins the
  parameterisation contract.

  `:hint` propagation (decl-from-props): the per-slot props may carry an
  optional `:hint` that the walker propagates verbatim into the
  declaration and omits when absent (Spec 009 §Size elision marker
  shape). Only one integration test touched `:hint` (and only for
  `:large?`); these pin the propagate / omit behaviour at the walker
  level for both flags."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.schemas :as schemas]))

;; ---- :large? structural recognition --------------------------------------
;;
;; Mirror the `schemas_walker_operators_test` matrix on the OTHER flag —
;; the walker is parameterised on flag-key, so pinning `:large?` across
;; the structural classes locks the parameterisation contract end-to-end.

(deftest large-no-slots
  (testing "a schema with no :large? props produces no entries"
    (is (= {} (schemas/extract-large-paths-from-schema
                [:map [:name :string]] [])))
    (is (= {} (schemas/extract-large-paths-from-schema :string [])))
    (is (= {} (schemas/extract-large-paths-from-schema :int [:a :b])))))

(deftest large-slot-level
  (testing "the slot's per-slot props carry :large? true — claims (conj base k)"
    (let [schema [:map
                  [:id    :int]
                  [:blob  {:large? true} :string]]]
      (is (= {[:blob] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest large-honours-base-path
  (testing "base-path is prepended to every discovered :large? slot path"
    (let [schema [:map [:blob {:large? true} :string]]]
      (is (= {[:user :blob] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema [:user]))))))

(deftest large-container-level
  (testing "the schema's OWN props (container-level) claim the base-path —
            `(reg-app-schema [:user :pdf] [:string {:large? true}])`"
    (is (= {[:user :pdf] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:string {:large? true}] [:user :pdf])))))

(deftest large-nested-map
  (testing "nested :map carries the path through every level for :large?"
    (let [schema [:map
                  [:doc
                   [:map
                    [:attachment
                     [:map
                      [:payload {:large? true} :string]]]]]]]
      (is (= {[:doc :attachment :payload] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest large-multiple-slots
  (testing "multiple :large? slots in one schema produce one entry each"
    (let [schema [:map
                  [:thumbnail :string]
                  [:hi-res   {:large? true} :string]
                  [:raw      {:large? true} :string]
                  [:caption  :string]]]
      (is (= {[:hi-res] {:large? true :source :schema}
              [:raw]    {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))))))

(deftest large-positional-combinator-descends
  (testing ":vector descends at the same base-path for :large? — the
            inner type's container props claim the :vector's path"
    (is (= {[:frames] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:vector [:string {:large? true}]] [:frames])))))

(deftest large-dispatch-bearing-claims-parent-path
  (testing ":multi branch slot-props claim the PARENT path for :large?
            (dispatch values are not path segments) — symmetric with the
            :sensitive? :orn/:catn/:altn cases in walker_operators_test"
    (is (= {[:asset] {:large? true :source :schema}}
           (schemas/extract-large-paths-from-schema
             [:multi {:dispatch :kind}
              [:photo {:large? true} [:map [:kind :string]]]
              [:icon  [:map [:kind :string]]]]
             [:asset])))))

(deftest large-and-sensitive-are-independent-flags
  (testing "the two flag entry points read INDEPENDENT slots — a slot
            flagged :large? is invisible to the :sensitive? walker and
            vice versa; this is the parameterisation contract (one
            traversal, distinct flag-key per call)"
    (let [schema [:map
                  [:blob   {:large? true} :string]
                  [:secret {:sensitive? true} :string]]]
      (is (= {[:blob] {:large? true :source :schema}}
             (schemas/extract-large-paths-from-schema schema []))
          ":large? walker sees only the :large? slot, not the :sensitive? one")
      (is (= {[:secret] {:sensitive? true :source :schema}}
             (schemas/extract-sensitive-paths-from-schema schema []))
          ":sensitive? walker sees only the :sensitive? slot, not the :large? one"))))

(deftest large-opaque-and-degenerate-forms-tolerated
  (testing "opaque / degenerate forms yield no :large? declarations —
            the walker treats them as non-introspectable leaves rather
            than blowing up (matches the :sensitive? defensive contract)"
    (is (= {} (schemas/extract-large-paths-from-schema 'malli/Opaque [])))
    (is (= {} (schemas/extract-large-paths-from-schema (fn [_] true) [])))
    (is (= {} (schemas/extract-large-paths-from-schema [] [])))
    (is (= {} (schemas/extract-large-paths-from-schema [:string] [])))))

;; ---- :hint propagation (decl-from-props) ---------------------------------
;;
;; `decl-from-props` propagates an optional `:hint` verbatim into the
;; declaration and OMITS the key entirely when absent so the marker
;; shape stays minimal (Spec 009 §Size elision marker shape). Pinned
;; here at the walker level for BOTH flags.

(deftest hint-propagated-verbatim-for-large
  (testing "a :large? slot carrying :hint surfaces the hint verbatim in
            the declaration map"
    (is (= {[:upload] {:large? true :source :schema :hint "video-blob"}}
           (schemas/extract-large-paths-from-schema
             [:map [:upload {:large? true :hint "video-blob"} :string]]
             [])))))

(deftest hint-propagated-verbatim-for-sensitive
  (testing "a :sensitive? slot carrying :hint surfaces the hint verbatim —
            :hint composes with either flag"
    (is (= {[:password] {:sensitive? true :source :schema :hint "argon2id"}}
           (schemas/extract-sensitive-paths-from-schema
             [:map [:password {:sensitive? true :hint "argon2id"} :string]]
             [])))))

(deftest hint-omitted-when-absent
  (testing "with no :hint prop the declaration map carries exactly
            {flag true :source :schema} — the :hint key is absent, not
            present-with-nil (minimal marker shape)"
    (let [large (get (schemas/extract-large-paths-from-schema
                       [:map [:blob {:large? true} :string]] [])
                     [:blob])
          sens  (get (schemas/extract-sensitive-paths-from-schema
                       [:map [:tok {:sensitive? true} :string]] [])
                     [:tok])]
      (is (= {:large? true :source :schema} large))
      (is (not (contains? large :hint))
          ":hint key is absent (not nil) when the prop carries no hint")
      (is (= {:sensitive? true :source :schema} sens))
      (is (not (contains? sens :hint))))))

(deftest hint-only-rides-when-flag-is-true
  (testing "a slot carrying :hint but NOT the flag set to true produces
            no declaration at all — the :hint is inert without its flag
            (decl-from-props gates on the flag-key, not on :hint)"
    (is (= {} (schemas/extract-large-paths-from-schema
                [:map [:blob {:hint "orphan-hint"} :string]] []))
        ":hint without :large? true → no declaration")
    (is (= {} (schemas/extract-large-paths-from-schema
                [:map [:blob {:large? false :hint "x"} :string]] []))
        ":large? false (not true) → no declaration even with a :hint")))
