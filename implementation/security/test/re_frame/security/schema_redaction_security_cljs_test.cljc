(ns re-frame.security.schema-redaction-security-cljs-test
  "Adversarial-property security tier - schema-validation redaction
  boundary.

  ## The boundary

  When app-db validation fails at (or under) a `:sensitive?`-declared
  schema slot, the `:rf.error/schema-validation-failure` trace MUST NOT
  carry the failing value verbatim - `:value` / `:received` / `:explain`
  redact to `:rf/redacted`, and the event is stamped top-level
  `:sensitive? true`. The hard case is a `:sensitive?` slot nested INSIDE
  a collection (`:vector` / `:map-of` / `:tuple` / `:sequential`):
  Malli's `:in` path carries collection indices that index-free decl
  paths never match, so without index alignment the secret would ship
  verbatim.

  ## Why property-style

  The pin-and-assert tests in `schemas_sensitive_test.clj` cover a fixed
  set of nesting shapes (vector, map-of, sequential, deep). This tier
  GENERATES arbitrary nestings: a recursive shape generator wraps a
  sensitive scalar slot in a random tower of collection + map combinators
  to an arbitrary depth, plants a unique unguessable SENTINEL secret at
  that slot, forces a type failure there, and asserts the sentinel string
  NEVER appears anywhere in the emitted trace (deep-walked). One escaped
  nesting shape = one leak.

  ## Net property

  Without the `index-bearing-ops` / `align-in-path` alignment in
  `schemas/walker.cljc`, the generated nesting test goes RED - the
  sentinel surfaces unredacted in the trace's `:explain` for
  collection-nested slots."
  (:require #?(:clj  [clojure.test :refer [deftest is use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is use-fixtures]])
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and no failure fires.
            [re-frame.schemas.malli]
            [re-frame.schemas :as rf.schemas]
            #?(:clj  [re-frame.test-support :as rf.test-support :refer [with-trace-recorder!]]
               :cljs [re-frame.test-support :as rf.test-support :refer-macros [with-trace-recorder!]])
            [re-frame.security.gen :as rf.security.gen]))

;; Reset per-test so app-schema registrations don't bleed across cases.
;; App schemas are frame-local, so the body runs with `:rf/default` bound as
;; the carried scope; `validate-app-schema!` is called directly, so no adapter
;; or registered frame is needed.
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture)
  (fn [test-fn]
    (binding [rf.frame/*current-frame* :rf/default]
      (test-fn))))

;; ---------------------------------------------------------------------------
;; Sentinel - a value that must NEVER appear unredacted in any trace slot.
;; Distinctive so a deep-walk substring scan is unambiguous.
;; ---------------------------------------------------------------------------

(def ^:private sentinel "S3CR3T-rf2-3cfvt-DO-NOT-LEAK")

(defn- contains-sentinel?
  "Deep-walk `x`; true when the sentinel string appears anywhere (as a
  value - matched as a SUBSTRING - inside a collection, or inside a
  stringified form, e.g. a keyword or symbol form built from it). Thin
  wrapper over the shared `gen/contains-string?`, which
  matches the sentinel as a substring."
  [x]
  (rf.security.gen/contains-string? x sentinel))

;; ---------------------------------------------------------------------------
;; Recursive generator - build [schema db] where a :sensitive? scalar slot
;; lives at an arbitrary collection/map nesting depth, with the sentinel
;; planted at that slot but a TYPE MISMATCH forcing a validation failure. The
;; recursive walk, the eleven wrapper arms, and the leaf are shared with the
;; validation-invariant suite via `gen/nested-sensitive-generator` (see that
;; fn for each arm's rationale). This suite passes its own sentinel, its own
;; wrapper-arm order and a 1..6 depth, which fix its generated shapes (a
;; failing draw reproduces from its seed). The `:tuple`-before-`:map-of-key`
;; order below is this suite's own draw order - see the shared block comment
;; on how the callers differ.
;; ---------------------------------------------------------------------------

(def ^:private gen-nested-sensitive
  "Draw a `[schema db-value]` with a sentinel-bearing :sensitive? slot at a
  random 1..6-deep collection/map nesting."
  (rf.security.gen/nested-sensitive-generator
    sentinel
    [:map :vector :sequential :map-of :tuple :map-of-key :set :and :or :multi :orn]
    6))

;; ---------------------------------------------------------------------------
;; End-to-end harness - register the schema, validate the failing db,
;; capture the single schema-validation-failure trace.
;; ---------------------------------------------------------------------------

(defn- failure-trace
  [schema db]
  (rf/reg-app-schema [:root] schema)
  (with-trace-recorder! [traces]
    (rf.schemas/validate-app-schema! {:root db} :root/bad)
    #?(:clj (rf.schemas/clear-sensitive-paths-cache!))
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

;; ---------------------------------------------------------------------------
;; PROPERTY - across arbitrary nestings, the sentinel never leaks.
;; ---------------------------------------------------------------------------

(deftest sensitive-sentinel-never-leaks-at-arbitrary-nesting
  (let [result (rf.security.gen/for-all
                 gen-nested-sensitive 300 11
                 (fn [[schema db]]
                   (let [v (failure-trace schema db)]
                     (and (true? (:sensitive? v))
                          (not (contains-sentinel? v))))))]
    (is (nil? result)
        (str "the sensitive sentinel leaked (or no redaction stamp) for a "
             "generated nesting: "
             (pr-str (when result (dissoc result :threw)))))))

(defn- stamp-redaction-and-leak
  "`[:sensitive? :value :explain sentinel-present?]` read off a failure trace."
  [v]
  [(:sensitive? v) (-> v :tags :value) (-> v :tags :explain) (contains-sentinel? v)])

;; ---------------------------------------------------------------------------
;; Collection navigation segments. Malli reports a failing :set element or a
;; failing :map-of key by VALUE in the `:in` path, so the structural `:path`
;; tag would ship it verbatim absent the sanitiser. The :set case carries the
;; secret in a NON-sensitive sibling of the element too, which the generated
;; shapes above never do.
;; ---------------------------------------------------------------------------

(deftest set-path-tag-carries-no-secret
  (let [v (failure-trace
            [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
            #{{:token [sentinel] :ssn (str "ssn-" sentinel)}})]
    (is (= [true :rf/redacted :rf/redacted false] (stamp-redaction-and-leak v))
        (pr-str (:tags v)))))

(deftest map-of-sensitive-key-path-tag-carries-no-secret
  ;; The sensitive key becomes :rf/redacted in :path; the navigable inner key
  ;; survives.
  (let [v (failure-trace
            [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
            {sentinel {:age [sentinel]}})]
    (is (= [true :rf/redacted :rf/redacted false] (stamp-redaction-and-leak v))
        (pr-str (:tags v)))
    (is (= [:root :rf/redacted :age] (-> v :tags :path)))))

(deftest non-sensitive-collection-failure-not-over-redacted
  (let [v (failure-trace [:vector [:map [:name :string]]] [{:name 99}])]
    (is (= [false 99] [(contains? v :sensitive?) (-> v :tags :value)])
        "no stamp, and :value rides verbatim")))
