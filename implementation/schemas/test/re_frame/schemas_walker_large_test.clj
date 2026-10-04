(ns re-frame.schemas-walker-large-test
  "JVM tests pinning the `:large?` arm of the per-slot flag walker, plus
  the `:hint` propagation contract.

  The walker (`re-frame.schemas.walker/walk-flagged-schema`) is
  parameterised on the per-slot flag key and serves both `:sensitive?`
  and `:large?` (Spec 009 §Size elision in traces / Spec 010
  §`:large?`). The `:sensitive?` arm is exhaustively unit-tested across
  every operator family by `schemas_walker_operators_test` and the
  `extract-*` tests in `schemas_sensitive_test`, but the
  `:large?` public entry point — `extract-large-paths-from-schema`,
  re-exported from `re-frame.schemas` and published as the
  `:schemas/extract-large-paths-from-schema` late-bind hook that the
  machines / resources / http artefacts consume (durable egress
  classification is frame-owned, per EP-0015 §8) — is a separate public
  entry point and needs its own unit coverage.

  A regression in the `:large?` wiring (a wrong flag-key threaded
  through the shared walker, or a structural-recognition break that the
  `:sensitive?` tests don't reach because they pin the other flag)
  would slip past the suite. These tests pin the `:large?` entry point
  directly against the same structural matrix the `:sensitive?` arm
  pins — both flags share one traversal, so locking both ends pins the
  parameterisation contract.

  `:hint` propagation (declaration-from-properties): the per-slot props may carry an
  optional `:hint` that the walker propagates verbatim into the
  declaration and omits when absent (Spec 009 §Size elision marker
  shape). These pin the propagate / omit behaviour at the walker
  level for both flags."
  (:require [clojure.test :refer [are deftest is testing]]
            [re-frame.schemas :as rf.schemas]))

;; ---- :large? structural recognition --------------------------------------
;;
;; Mirror the `schemas_walker_operators_test` matrix on the OTHER flag —
;; the walker is parameterised on flag-key, so pinning `:large?` across
;; the structural classes locks the parameterisation contract end-to-end.

(deftest large-walker-claims-each-structural-class
  (testing "the `:large?` arm claims the same paths the `:sensitive?` arm
            does across the structural classes"
    (are [schema base-path expected]
         (= expected (rf.schemas/extract-large-paths-from-schema schema base-path))
      ;; slot-level: the slot's per-slot props claim (conj base k)
      [:map [:id :int] [:blob {:large? true} :string]]
      []
      {[:blob] {:large? true :source :schema}}
      ;; container-level: the schema's OWN props claim the base-path, as
      ;; `(reg-app-schema [:user :pdf] [:string {:large? true}])` does
      [:string {:large? true}]
      [:user :pdf]
      {[:user :pdf] {:large? true :source :schema}}
      ;; nested :map carries the path through every level
      [:map [:doc [:map [:attachment [:map [:payload {:large? true} :string]]]]]]
      []
      {[:doc :attachment :payload] {:large? true :source :schema}}
      ;; :vector descends at the same base-path: the inner type's container
      ;; props claim the :vector's path
      [:vector [:string {:large? true}]]
      [:frames]
      {[:frames] {:large? true :source :schema}}
      ;; :multi branch slot-props claim the PARENT path (dispatch values are
      ;; not path segments), as the :sensitive? :orn / :altn rows in
      ;; walker_operators_test do
      [:multi {:dispatch :kind}
       [:photo {:large? true} [:map [:kind :string]]]
       [:icon  [:map [:kind :string]]]]
      [:asset]
      {[:asset] {:large? true :source :schema}})))

(deftest large-and-sensitive-are-independent-flags
  (testing "the two flag entry points read INDEPENDENT slots — a slot
            flagged :large? is invisible to the :sensitive? walker and
            vice versa; this is the parameterisation contract (one
            traversal, distinct flag-key per call)"
    (let [schema [:map
                  [:blob   {:large? true} :string]
                  [:secret {:sensitive? true} :string]]]
      (is (= {[:blob] {:large? true :source :schema}}
             (rf.schemas/extract-large-paths-from-schema schema []))
          ":large? walker sees only the :large? slot, not the :sensitive? one")
      (is (= {[:secret] {:sensitive? true :source :schema}}
             (rf.schemas/extract-sensitive-paths-from-schema schema []))
          ":sensitive? walker sees only the :sensitive? slot, not the :large? one"))))

;; ---- :hint propagation (declaration-from-properties) ---------------------
;;
;; `declaration-from-properties` propagates an optional `:hint` verbatim into the
;; declaration and OMITS the key entirely when absent so the marker
;; shape stays minimal (Spec 009 §Size elision marker shape). Pinned
;; here at the walker level for BOTH flags.

(deftest hint-rides-verbatim-only-beside-a-true-flag
  (testing "a slot's :hint surfaces verbatim in the declaration for either
            flag, and is inert without its flag set to true
            (declaration-from-properties gates on the flag-key, not on :hint)"
    (are [extract schema expected] (= expected (extract schema []))
      ;; :large? slot carrying :hint
      rf.schemas/extract-large-paths-from-schema
      [:map [:upload {:large? true :hint "video-blob"} :string]]
      {[:upload] {:large? true :source :schema :hint "video-blob"}}
      ;; :hint composes with :sensitive? too
      rf.schemas/extract-sensitive-paths-from-schema
      [:map [:password {:sensitive? true :hint "argon2id"} :string]]
      {[:password] {:sensitive? true :source :schema :hint "argon2id"}}
      ;; :hint without :large? true -> no declaration
      rf.schemas/extract-large-paths-from-schema
      [:map [:blob {:hint "orphan-hint"} :string]]
      {}
      ;; :large? false (not true) -> no declaration even with a :hint
      rf.schemas/extract-large-paths-from-schema
      [:map [:blob {:large? false :hint "x"} :string]]
      {})))
