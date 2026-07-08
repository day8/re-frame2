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
            [re-frame.frame :as frame]
            ;; Publishes the Malli late-bind validate/explain hooks; without
            ;; it the default validator soft-passes and no failure fires.
            [re-frame.schemas.malli]
            [re-frame.schemas :as schemas]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.security.gen :as gen]))

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
  (test-support/make-reset-runtime-fixture
    {:clear-app-schemas? true})
  (fn [test-fn]
    (binding [frame/*current-frame* :rf/default]
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
  wrapper over the shared `gen/contains-string?` (rf2-n5bkm7); the sentinel
  is matched as a substring (`exact? false`)."
  [x]
  (gen/contains-string? x sentinel false))

;; ---------------------------------------------------------------------------
;; Recursive generator - build [schema db] where a :sensitive? scalar slot
;; lives at an arbitrary collection/map nesting depth, with the sentinel
;; planted at that slot but a TYPE MISMATCH forcing a validation failure.
;;
;; Each layer wraps the inner [schema value] in one of:
;;   :map     - name the slot with a random key
;;   :vector  - element schema (value becomes a 1-or-2 element vector)
;;   :sequential
;;   :map-of  - string-keyed
;;   :tuple   - sensitive slot at a random tuple index
;; The leaf is a :sensitive? :string slot whose value is the sentinel as a
;; NON-string (a vector wrapping it) so :string fails and the redaction path
;; fires.
;; ---------------------------------------------------------------------------

(def ^:private gen-key
  (gen/gen-elem [:auth :token :secret :pw :ssn :payload :inner :node :a :b]))

(defn- gen-shape
  "Generator returning `[schema db-value]`. `depth` bounds recursion."
  [depth]
  (if (<= depth 0)
    ;; Leaf: a sensitive :string slot whose value is the WRONG type (the
    ;; sentinel wrapped in a vector - fails :string, so the failure trace
    ;; would carry the sentinel verbatim absent redaction).
    (fn [rng]
      [[[:string {:sensitive? true}] [sentinel]] rng])
    (fn [rng]
      (let [[wrap rng1] (gen/rand-nth rng [:map :vector :sequential :map-of :tuple
                                           ;; rf2-ss06u.1 / rf2-ss06u.2 — the
                                           ;; set-element-value `:path` leak
                                           ;; and the consumed-ancestor
                                           ;; transparent-wrapper leaks. These
                                           ;; reach align-in-path's :else
                                           ;; fallback (or carry the element
                                           ;; value into :path for :set).
                                           :set :and :or :multi :orn])
            [[inner-schema inner-val] rng2] ((gen-shape (dec depth)) rng1)]
        (case wrap
          :map
          (let [[k rng3] (gen-key rng2)]
            [[[:map [k inner-schema]] {k inner-val}] rng3])

          :vector
          [[[:vector inner-schema] [inner-val]] rng2]

          :sequential
          [[[:sequential inner-schema] [inner-val]] rng2]

          :map-of
          [[[:map-of :string inner-schema] {"k" inner-val}] rng2]

          :tuple
          ;; sensitive slot is element 1; element 0 is an int filler.
          [[[:tuple :int inner-schema] [0 inner-val]] rng2]

          ;; rf2-ss06u.1 — a :set wraps a MAP carrying the sensitive inner
          ;; slot (so the set element is a navigable map, mirroring the bead
          ;; repro) under a random key. Malli reports the element VALUE as the
          ;; :in segment, so the failing element (incl. the sentinel) would
          ;; ride verbatim in :path absent the sanitiser.
          :set
          (let [[k rng3] (gen-key rng2)]
            [[[:set [:map [k inner-schema]]] #{{k inner-val}}] rng3])

          ;; rf2-ss06u.2 — transparent single-child wrappers that
          ;; align-in-path cannot resolve. We make the WRAPPER slot the
          ;; sensitive container (props on the named :map slot) so the
          ;; sensitivity is on a CONSUMED ANCESTOR; the failing leaf lives
          ;; under the :and/:or/:multi/:orn. Absent the prefix-carry fix the
          ;; leftover subtree shows no sensitivity and the value leaks.
          :and
          (let [[k rng3] (gen-key rng2)]
            [[[:map [k {:sensitive? true} [:and inner-schema]]] {k inner-val}] rng3])

          :or
          (let [[k rng3] (gen-key rng2)]
            [[[:map [k {:sensitive? true} [:or inner-schema]]] {k inner-val}] rng3])

          :multi
          (let [[k rng3] (gen-key rng2)]
            ;; :multi needs a dispatch; wrap the inner under a single branch
            ;; keyed by a fixed dispatch value carried in the value map.
            [[[:map [k {:sensitive? true}
                     [:multi {:dispatch :rf2/d}
                      [:x [:map [:rf2/d :keyword] [:v inner-schema]]]]]]
              {k {:rf2/d :x :v inner-val}}] rng3])

          :orn
          (let [[k rng3] (gen-key rng2)]
            [[[:map [k {:sensitive? true} [:orn [:x inner-schema]]]] {k inner-val}] rng3]))))))

(def ^:private gen-nested-sensitive
  "Draw a `[schema db-value]` with a sentinel-bearing :sensitive? slot at a
  random 1..6-deep collection/map nesting."
  (fn [rng]
    (let [[depth rng1] (gen/next-int rng 6)]
      ((gen-shape (inc depth)) rng1))))

;; ---------------------------------------------------------------------------
;; End-to-end harness - register the schema, validate the failing db,
;; capture the single schema-validation-failure trace.
;; ---------------------------------------------------------------------------

(defn- failure-trace
  [schema db]
  (rf/reg-app-schema [:root] schema)
  (let [traces (atom [])
        kw     (keyword "rf2-3cfvt" (name (gensym "listen")))]
    (trace-tooling/register-listener! kw (fn [ev] (swap! traces conj ev)))
    (schemas/validate-app-schema! {:root db} :root/bad)
    (trace-tooling/unregister-listener! kw)
    #?(:clj (schemas/clear-sensitive-paths-cache!))
    (first (filter #(= :rf.error/schema-validation-failure (:operation %))
                   @traces))))

;; ---------------------------------------------------------------------------
;; PROPERTY - across arbitrary nestings, the sentinel never leaks.
;; ---------------------------------------------------------------------------

(deftest sensitive-sentinel-never-leaks-at-arbitrary-nesting
  (testing "rf2-g5auo - a :sensitive? slot at ANY generated collection/map
            nesting depth redacts: the sentinel never appears in the trace"
    (let [result (gen/for-all
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
