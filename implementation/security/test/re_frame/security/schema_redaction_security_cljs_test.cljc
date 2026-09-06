(ns re-frame.security.schema-redaction-security-cljs-test
  "Adversarial-property security tier - schema-validation redaction
  boundary (rf2-3cfvt, surface 2; the rf2-g5auo class).

  ## The boundary

  When app-db validation fails at (or under) a `:sensitive?`-declared
  schema slot, the `:rf.error/schema-validation-failure` trace MUST NOT
  carry the failing value verbatim - `:value` / `:received` / `:explain`
  redact to `:rf/redacted`, and the event is stamped top-level
  `:sensitive? true`. rf2-g5auo was a real leak: a `:sensitive?` slot
  nested INSIDE a collection (`:vector` / `:map-of` / `:tuple` /
  `:sequential`) shipped the secret verbatim, because Malli's `:in` path
  carries collection indices the walker's index-free decl paths never
  match.

  ## Why property-style

  The pin-and-assert tests in `schemas_sensitive_test.clj` cover a fixed
  set of nesting shapes (vector, map-of, sequential, deep). This tier
  GENERATES arbitrary nestings: a recursive shape generator wraps a
  sensitive scalar slot in a random tower of collection + map combinators
  to an arbitrary depth, plants a unique unguessable SENTINEL secret at
  that slot, forces a type failure there, and asserts the sentinel string
  NEVER appears anywhere in the emitted trace (deep-walked). One escaped
  nesting shape = one leak.

  ## Net property (verify-by-revert)

  Reverting the `index-bearing-ops` / `align-in-path` alignment in
  `schemas/walker.cljc` (the rf2-g5auo fix) makes the generated nesting
  test go RED - the sentinel surfaces unredacted in the trace's
  `:explain` for collection-nested slots. Confirmed by temporary local
  revert + restore (see PR Quality gates)."
  (:require #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
               :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
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
;; No adapter needed - `validate-app-schema!` is called directly (not via
;; dispatch), so a clean app-schema slate is all the harness requires.
;; CLJC-clean: the same fixture runs on JVM and node.
;;
;; EP-0002 (rf2-gjq3ow): `reg-app-schema` + `validate-app-schema!` are
;; context-required frame-local (no `:rf/default` floor). The adapter-less
;; reset fixture establishes no ambient scope, so the outer fixture below
;; pins `:rf/default` as the carried scope for the test body — the
;; carried-invariant equivalent of `(with-frame :rf/default …)`. Without it
;; every `reg-app-schema` raises `:rf.error/no-frame-context`.
;;
;; Binding the dynamic var is sufficient (and necessary): `reg-app-schema`
;; writes only to the schemas side-table keyed by frame-id and resolves the
;; stamp through the scope reader, which reads the var — it needs neither a
;; registered frame nor a state container. We deliberately do NOT call
;; `ensure-default-frame!` here: it builds a container and so requires an
;; installed adapter, which this adapter-less validate-direct suite has not
;; got (it would raise `:rf.error/no-adapter-installed`).
(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true})
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
  wrapper over the shared `gen/contains-string?` (rf2-n5bkm7), which
  matches the sentinel as a substring."
  [x]
  (rf.security.gen/contains-string? x sentinel))

;; ---------------------------------------------------------------------------
;; Recursive generator - build [schema db] where a :sensitive? scalar slot
;; lives at an arbitrary collection/map nesting depth, with the sentinel
;; planted at that slot but a TYPE MISMATCH forcing a validation failure. The
;; recursive walk, the eleven wrapper arms, and the leaf are shared with the
;; validation-invariant suite via `gen/nested-sensitive-generator` (rf2-iu6fqv;
;; see that fn for each arm's rationale). This suite keeps its own sentinel and
;; passes its own wrapper-arm order + a 1..6 depth, so its generated shapes are
;; byte-identical to before (a failing draw still reproduces from its seed).
;; The `:tuple`-before-`:map-of-key` order below is this suite's historical draw
;; order, preserved deliberately - see the shared block comment on the drift.
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
  (testing "rf2-g5auo - a :sensitive? slot at ANY generated collection/map
            nesting depth redacts: the sentinel never appears in the trace"
    (let [result (rf.security.gen/for-all
                   gen-nested-sensitive 300 11
                   (fn [[schema db]]
                     (let [v (failure-trace schema db)]
                       (and (some? v)
                            ;; The trace fired AND is stamped sensitive AND
                            ;; carries no trace of the sentinel anywhere.
                            (true? (:sensitive? v))
                            (not (contains-sentinel? v))))))]
      (is (nil? result)
          (str "the sensitive sentinel leaked (or no redaction stamp) for a "
               "generated nesting: "
               (pr-str (when result (dissoc result :threw))))))))

;; ---------------------------------------------------------------------------
;; HOSTILE CORPUS - the exact rf2-g5auo shapes, pinned.
;; ---------------------------------------------------------------------------

(deftest hostile-nesting-corpus-all-redacted
  (testing "rf2-g5auo corpus - every named collection-nested sensitive shape
            redacts the sentinel"
    (doseq [[label schema db]
            [["vector-of-map"
              [:vector [:map [:token {:sensitive? true} :string]]]
              [{:token "ok"} {:token [sentinel]}]]
             ["map-of-value"
              [:map-of :string [:map [:secret {:sensitive? true} :string]]]
              {"a" {:secret [sentinel]}}]
             ;; rf2-gocef0 / rf2-6ijdgh — the sensitive :map-of KEY sibling
             ;; (the secret is the KEY, not the value). Both the key AND the
             ;; nested value carry the sentinel; both must redact.
             ["map-of-sensitive-key"
              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
              {sentinel {:age [sentinel]}}]
             ["sequential"
              [:sequential [:map [:pw {:sensitive? true} :string]]]
              [{:pw [sentinel]}]]
             ["deep-map-vector-map"
              [:map [:items [:vector [:map [:tok {:sensitive? true} :string]]]]]
              {:items [{:tok [sentinel]}]}]
             ["tuple-sensitive-element"
              [:tuple :int [:string {:sensitive? true}]]
              [0 [sentinel]]]
             ["scalar-sensitive-vector-element"
              [:vector [:string {:sensitive? true}]]
              ["ok" [sentinel]]]]]
      (let [v (failure-trace schema db)]
        (is (some? v) (str label ": a trace fired"))
        (is (true? (:sensitive? v)) (str label ": top-level :sensitive? stamp"))
        (is (not (contains-sentinel? v))
            (str label ": sentinel leaked into the trace: "
                 (pr-str (:tags v))))))))

;; ---------------------------------------------------------------------------
;; HOSTILE CORPUS - the rf2-ss06u.1 / rf2-ss06u.2 egress leaks, pinned.
;;   ss06u.1: a :set failure ships the failing ELEMENT VALUE in the
;;            structural :path tag (Malli reports the value, not an index).
;;   ss06u.2: a sensitive CONTAINER wrapped by :and/:or/:multi/:orn drops the
;;            consumed-ancestor sensitivity in align-in-path's fallback, so
;;            the failing value (and :explain) ride verbatim, unstamped.
;; The :set case asserts BOTH the value-bearing slots AND the :path tag carry
;; no sentinel; the wrapper cases assert the value-bearing slots are redacted
;; and stamped. One escaped shape = one egress leak.
;; ---------------------------------------------------------------------------

(deftest ss06u-set-path-tag-carries-no-secret
  (testing "rf2-ss06u.1 - a :set of sensitive maps must not ship the failing
            element value in ANY slot, including the structural :path tag"
    ;; The sentinel rides as a sibling :ssn (a plain string) so a leak would
    ;; surface it verbatim in :path even though the :token value is redacted.
    (let [v (failure-trace
              [:set [:map [:token {:sensitive? true} :string] [:ssn :string]]]
              #{{:token [sentinel] :ssn (str "ssn-" sentinel)}})]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v)) "top-level :sensitive? stamp")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The crux: NO sentinel anywhere, AND specifically not in :path.
      (is (not (contains-sentinel? (-> v :tags :path)))
          (str "the sentinel leaked into the :path tag: " (pr-str (-> v :tags :path))))
      (is (not (contains-sentinel? v))
          (str "the sentinel leaked somewhere in the trace: " (pr-str (:tags v)))))))

(deftest map-of-sensitive-key-path-tag-carries-no-secret
  (testing "rf2-gocef0 / rf2-6ijdgh - a :map-of with a :sensitive? KEY must not
            ship the failing key VALUE in ANY slot, including the structural
            :path tag; :path carries :rf/redacted for the key, not the secret.
            The one collection-navigation-segment sibling pinned for its
            neighbours (:set ss06u.1, :tuple, :cat/:catn) but not itself."
    (let [v (failure-trace
              [:map-of [:string {:sensitive? true}] [:map [:age :int]]]
              {sentinel {:age [sentinel]}})]
      (is (some? v) "a trace fired")
      (is (true? (:sensitive? v)) "top-level :sensitive? stamp")
      (is (= :rf/redacted (-> v :tags :value)) ":value redacted")
      (is (= :rf/redacted (-> v :tags :explain)) ":explain redacted")
      ;; The crux: the sensitive KEY is :rf/redacted in :path, not the secret;
      ;; the navigable inner :age key (a real :map key) survives.
      (is (= [:root :rf/redacted :age] (-> v :tags :path))
          (str "the sensitive :map-of key must be :rf/redacted in :path: "
               (pr-str (-> v :tags :path))))
      (is (not (contains-sentinel? (-> v :tags :path)))
          (str "the secret key leaked into the :path tag: " (pr-str (-> v :tags :path))))
      (is (not (contains-sentinel? (-> v :tags :reason)))
          (str "the secret key leaked into the :reason tag: " (pr-str (-> v :tags :reason))))
      (is (not (contains-sentinel? v))
          (str "the secret leaked somewhere in the trace: " (pr-str (:tags v)))))))

(deftest ss06u-ancestor-sensitive-wrapper-corpus-all-redacted
  (testing "rf2-ss06u.2 - a sensitive container whose failing leaf is under a
            transparent :and/:or/:multi/:orn wrapper redacts + stamps"
    (doseq [[label schema db]
            [["and-ancestor"
              [:map [:s {:sensitive? true} [:and [:map [:k :int]]]]]
              {:s {:k sentinel}}]
             ["or-ancestor"
              [:map [:s {:sensitive? true} [:or [:map [:k :int]]]]]
              {:s {:k sentinel}}]
             ["multi-ancestor"
              [:map [:s {:sensitive? true}
                     [:multi {:dispatch :t} [:a [:map [:t :keyword] [:k :int]]]]]]
              {:s {:t :a :k sentinel}}]
             ["orn-ancestor"
              [:map [:s {:sensitive? true} [:orn [:a [:map [:k :int]]]]]]
              {:s {:k sentinel}}]]]
      (let [v (failure-trace schema db)]
        (is (some? v) (str label ": a trace fired"))
        (is (true? (:sensitive? v)) (str label ": top-level :sensitive? stamp"))
        (is (= :rf/redacted (-> v :tags :value)) (str label ": :value redacted"))
        (is (not (contains-sentinel? v))
            (str label ": sentinel leaked into the trace: " (pr-str (:tags v))))))))

;; ---------------------------------------------------------------------------
;; NO OVER-REDACTION - a failure at a NON-sensitive slot must NOT be redacted
;; (the alignment must be precise, not a blanket scrub).
;; ---------------------------------------------------------------------------

(deftest non-sensitive-collection-failure-not-over-redacted
  (testing "rf2-g5auo - no regression: a collection failure where no slot is
            sensitive rides verbatim (the secret-detection is precise)"
    (let [v (failure-trace
              [:vector [:map [:name :string]]]
              [{:name 99}])]
      (is (some? v))
      (is (not (contains? v :sensitive?))
          "no :sensitive? stamp - nothing in the schema is sensitive")
      (is (not= :rf/redacted (-> v :tags :value))
          ":value rides verbatim - alignment did not over-redact"))))
