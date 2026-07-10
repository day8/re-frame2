(ns re-frame.mcp-base.descriptor-manifest-test
  "Tests for the shared MCP tool-descriptor manifest serialiser +
  drift-check. The serialiser is consumed by BOTH MCP
  servers' generators; this corpus pins its determinism + diff
  semantics on the JVM (the algorithm is platform-agnostic `.cljc`)."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [re-frame.mcp-base.descriptor-manifest :as dm]))

(def ^:private sample-descriptors
  "Two descriptors in the on-the-registry shape both servers emit —
  story-mcp lifts :outputSchema/:annotations via cond->, pair-mcp
  declares them per-tool, but the slot SHAPE is identical. Both carry
  `:required` (inside :inputSchema) + `:typicalTokens` so
  the corpus exercises every governed slot."
  [{:name        "beta"
    :description "Second tool."
    :inputSchema {:type "object"
                  :properties {:limit {:type "integer"}
                               :cursor {:type "string"}}}
    :outputSchema {:type "object"}
    :annotations  {:readOnlyHint true}
    :typicalTokens 600}
   {:name        "alpha"
    :description "First tool."
    :inputSchema {:type "object"
                  :properties {:event {:type "string"}}
                  :required ["event"]}
    :annotations  {:destructiveHint true :openWorldHint true}
    :typicalTokens 300}])

;; ---------------------------------------------------------------------------
;; Row projection
;; ---------------------------------------------------------------------------

(deftest descriptor->row-projects-stable-shape
  (let [row (dm/descriptor->row (first sample-descriptors))]
    (is (= "beta" (:name row)))
    (is (= "Second tool." (:description row)))
    (is (= ["cursor" "limit"] (:input-keys row)) "input-keys sorted, stringified")
    (is (= [] (:gated-input-keys row)) "1-arity gates nothing → empty")
    (is (= [] (:required row)) "no :required → empty")
    (is (true? (:output? row)))
    (is (= ["readOnlyHint"] (:annotations row)))
    (is (= 600 (:typicalTokens row)) "typicalTokens passed through verbatim")))

(deftest descriptor->row-projects-required-and-typical-tokens
  ;; The two live API-semantics facets: :required + :typicalTokens.
  (let [row (dm/descriptor->row (second sample-descriptors))] ; alpha
    (is (= ["event"] (:input-keys row)))
    (is (= ["event"] (:required row)) ":required projects the mandatory input subset")
    (is (= 300 (:typicalTokens row)))))

(deftest descriptor->row-required-is-sorted-stringified
  ;; :required is sorted + stringified the same way :input-keys is, so a
  ;; keyword-shaped or out-of-order entry still renders byte-stably.
  (let [row (dm/descriptor->row {:name "x" :description "d"
                                 :inputSchema {:type "object"
                                               :properties {:b {} :a {}}
                                               :required [:b "a"]}})]
    (is (= ["a" "b"] (:required row)) "sorted + stringified")))

(deftest descriptor->row-rejects-required-not-subset-of-input-keys
  ;; manifest-contract invariant: :required is a SORTED SUBSET of
  ;; :input-keys. The two slots are read from independent descriptor
  ;; sources (:inputSchema :properties vs :inputSchema :required), so a
  ;; descriptor can name a required argument absent from its advertised
  ;; properties. Emitting that inconsistent row (input-keys ["known"]
  ;; with required ["missing"]) blesses a mandatory argument the input
  ;; surface never declares. descriptor->row must REJECT it.
  (testing "a required key missing from :properties throws an ex-info naming the tool + missing keys"
    (let [bad  {:name "broken"
                :description "Names a required arg it does not declare."
                :inputSchema {:type "object"
                              :properties {:known {:type "string"}}
                              :required ["missing"]}}
          data (ex-data (is (thrown? clojure.lang.ExceptionInfo (dm/descriptor->row bad))))]
      (is (= "broken" (:tool data)) "the ex-data names the offending tool")
      (is (= ["missing"] (:missing data)) "and the keys absent from :properties")
      (is (= ["known"] (:input-keys data)) "and the actual advertised input keys")))
  (testing "a partially-valid required set still rejects on the missing member"
    (let [bad {:name "partial"
               :description "One valid, one missing required arg."
               :inputSchema {:type "object"
                             :properties {:event {:type "string"}}
                             :required ["event" "absent"]}}]
      (is (thrown? clojure.lang.ExceptionInfo (dm/descriptor->row bad)))))
  (testing "the same rejection holds via build-manifest (the consumer entry-point)"
    (let [bad {:name "broken" :description "d"
               :inputSchema {:type "object"
                             :properties {:known {:type "string"}}
                             :required ["missing"]}}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (dm/build-manifest :test [bad])))))
  (testing "a valid subset (and the empty-required case) still projects cleanly"
    ;; the happy path the original gate covered must keep passing.
    (is (= ["event"] (:required (dm/descriptor->row (second sample-descriptors)))))
    (is (= [] (:required (dm/descriptor->row (first sample-descriptors)))))))

(deftest descriptor->row-handles-missing-optional-slots
  (let [row (dm/descriptor->row {:name "x" :description "d"
                                 :inputSchema {:type "object"}})]
    (is (= [] (:input-keys row)) "no :properties → empty input-keys")
    (is (= [] (:gated-input-keys row)) "no gated keys → empty")
    (is (= [] (:required row)) "no :required → empty")
    (is (false? (:output? row)) "no :outputSchema → output? false")
    (is (= [] (:annotations row)) "no :annotations → empty")
    (is (nil? (:typicalTokens row)) "no :typicalTokens → nil (forward-compatible)")))

(deftest build-rows-sorts-by-name
  (let [rows (dm/build-rows sample-descriptors)]
    (is (= ["alpha" "beta"] (mapv :name rows))
        "rows sorted by name regardless of input order")))

;; ---------------------------------------------------------------------------
;; Gated-input profiles
;;
;; A descriptor input key can sit on a PRIVILEGED (gate-open) `tools/list`
;; profile yet be stripped from the DEFAULT one by an operator-only gate
;; (story-mcp's `:include-sensitive`). The row models that with
;; `:gated-input-keys` — the sorted subset of `:input-keys` the default
;; profile gates off — so the manifest no longer claims a gated input is
;; on the default surface. These tests pin that two-profile contract at
;; the base boundary the two server generators consume.
;; ---------------------------------------------------------------------------

(def ^:private gated-descriptor
  "A story-mcp-shaped value-surfacing tool: the raw descriptor carries
  `:include-sensitive` (the FULL gate-open surface), which the default
  `tools/list` profile strips behind `--allow-sensitive-reads`."
  {:name        "preview-variant"
   :description "Surfaces a live app-db slice."
   :inputSchema {:type "object"
                 :properties {:variant-id       {:type "string"}
                              :max-tokens       {:type "integer"}
                              :include-sensitive {:type "boolean"}}
                 :required ["variant-id"]}
   :outputSchema {:type "object"}
   :annotations  {:readOnlyHint true}
   :typicalTokens 2000})

(deftest gated-keys-marks-the-gated-subset
  (let [row (dm/descriptor->row gated-descriptor #{"include-sensitive"})]
    (is (= ["include-sensitive" "max-tokens" "variant-id"] (:input-keys row))
        ":input-keys stays the FULL union — the gate-open surface")
    (is (= ["include-sensitive"] (:gated-input-keys row))
        ":gated-input-keys names the default-stripped subset")))

(deftest default-vs-gate-open-surfaces-are-derivable
  ;; The default profile excludes include-sensitive; the gate-open
  ;; profile includes it. Both are derivable from one byte-stable row.
  (let [row          (dm/descriptor->row gated-descriptor #{"include-sensitive"})
        gate-open    (:input-keys row)
        default-surf (remove (set (:gated-input-keys row)) gate-open)]
    (is (some #{"include-sensitive"} gate-open)
        "gate-open surface (= :input-keys) INCLUDES include-sensitive")
    (is (not (some #{"include-sensitive"} default-surf))
        "default surface (:input-keys minus :gated-input-keys) EXCLUDES include-sensitive")
    (is (= ["max-tokens" "variant-id"] (vec default-surf))
        "the non-gated inputs survive on the default surface")))

(deftest gated-keys-intersect-only-present-keys
  ;; A gated key the descriptor does not actually carry is NOT invented
  ;; into the row — the gated subset is the intersection with the real
  ;; input keys, so a non-sensitive tool under the same set stays clean.
  (let [row (dm/descriptor->row (second sample-descriptors) ; alpha — no include-sensitive
                                #{"include-sensitive"})]
    (is (= ["event"] (:input-keys row)))
    (is (= [] (:gated-input-keys row))
        "alpha gates no input even under the include-sensitive set")))

(deftest one-arity-and-empty-set-gate-nothing
  ;; pair-mcp's path: no gated-key set → every row carries an empty slot.
  (let [r1 (dm/descriptor->row gated-descriptor)
        r2 (dm/descriptor->row gated-descriptor #{})
        r3 (dm/descriptor->row gated-descriptor nil)]
    (doseq [r [r1 r2 r3]]
      (is (= [] (:gated-input-keys r))
          "no gated set → :gated-input-keys [] (include-sensitive stays on :input-keys)")
      (is (some #{"include-sensitive"} (:input-keys r))))))

(deftest build-manifest-threads-gated-keys
  (let [m       (dm/build-manifest :story-mcp
                                   [gated-descriptor (second sample-descriptors)]
                                   #{"include-sensitive"})
        by-name (into {} (map (juxt :name identity)) (:tools m))]
    (is (= ["include-sensitive"] (:gated-input-keys (by-name "preview-variant")))
        "the gated tool's row records the gated subset")
    (is (= [] (:gated-input-keys (by-name "alpha")))
        "a non-gated tool in the same manifest stays clean")))

(deftest check-detects-gated-input-key-drift
  ;; A tool newly gating an input (or a gate lifted) must trip the drift
  ;; gate as a :changed row — the governance point of the slot.
  (let [committed-m   (dm/build-manifest :story-mcp [gated-descriptor] #{"include-sensitive"})
        committed-edn (dm/render-edn committed-m)
        ;; Same registry, but the gate is LIFTED (include-sensitive no
        ;; longer gated → it moves onto the default surface).
        gen-m         (dm/build-manifest :story-mcp [gated-descriptor] #{})
        gen-edn       (dm/render-edn gen-m)
        res           (dm/check gen-m gen-edn committed-edn)]
    (is (false? (:ok? res)))
    (is (= [] (:added res)))
    (is (= [] (:removed res)))
    (is (= ["preview-variant"] (mapv :name (:changed res)))
        "the gated-input drift is a row-level :changed, not added/removed")
    (let [{:keys [old new]} (first (:changed res))]
      (is (= ["include-sensitive"] (:gated-input-keys old)))
      (is (= [] (:gated-input-keys new))
          "the lifted gate is visible in the row delta"))))

;; ---------------------------------------------------------------------------
;; Deterministic emission
;; ---------------------------------------------------------------------------

(deftest render-edn-is-byte-stable
  (let [m (dm/build-manifest :test sample-descriptors)
        a (dm/render-edn m)
        b (dm/render-edn m)]
    (is (= a b) "same input → byte-identical output")
    (is (not (re-find #"\r\n" a)) "no CRLF — LF-pinned")
    (is (re-find #"GENERATED" a) "carries the do-not-hand-edit banner")))

(deftest render-edn-round-trips-as-data
  (let [m      (dm/build-manifest :test sample-descriptors)
        parsed (edn/read-string (dm/render-edn m))
        by-name (into {} (map (juxt :name identity)) (:tools parsed))]
    (is (= :test (-> parsed :meta :server)))
    (is (= 2 (-> parsed :meta :tool-count)))
    (is (= ["alpha" "beta"] (mapv :name (:tools parsed))))
    ;; The :required + :typicalTokens facets survive the EDN round-trip.
    (is (= ["event"] (:required (by-name "alpha"))))
    (is (= [] (:required (by-name "beta"))))
    (is (= 300 (:typicalTokens (by-name "alpha"))))
    (is (= 600 (:typicalTokens (by-name "beta"))))
    ;; The gated-input slot survives + renders uniformly.
    (is (= [] (:gated-input-keys (by-name "alpha"))) "empty gated slot round-trips")
    (is (= [] (:gated-input-keys (by-name "beta"))))))

(deftest render-edn-round-trips-nil-typical-tokens
  ;; A tool that declares no :typicalTokens renders `nil` and reads back
  ;; as nil (not the symbol nil) — the forward-compatible slot is uniform.
  (let [m      (dm/build-manifest :test [{:name "x" :description "d"
                                          :inputSchema {:type "object"}}])
        parsed (edn/read-string (dm/render-edn m))
        row    (first (:tools parsed))]
    (is (contains? row :typicalTokens) "the slot always renders")
    (is (nil? (:typicalTokens row)) "nil round-trips as nil")))

(deftest render-edn-one-row-per-line
  (let [m     (dm/build-manifest :test sample-descriptors)
        lines (str/split-lines (dm/render-edn m))
        rows  (filter #(str/includes? % "{:name ") lines)]
    (is (= 2 (count rows)) "one tool row per line — surgical diffs")))

;; ---------------------------------------------------------------------------
;; Drift-check
;; ---------------------------------------------------------------------------

(deftest check-passes-when-in-sync
  (let [m   (dm/build-manifest :test sample-descriptors)
        edn (dm/render-edn m)
        res (dm/check m edn edn)]
    (is (true? (:ok? res)))
    (is (empty? (:added res)))
    (is (empty? (:removed res)))
    (is (empty? (:changed res)) "an in-sync manifest reports no changed rows")))

(deftest check-detects-missing-file
  (let [m   (dm/build-manifest :test sample-descriptors)
        edn (dm/render-edn m)
        res (dm/check m edn nil)]
    (is (false? (:ok? res)))
    (is (true? (:missing-file? res)))
    (is (= ["alpha" "beta"] (:added res)))))

(deftest check-detects-added-tool
  (testing "a tool present in the generated manifest but not the committed file is :added"
    (let [committed-m   (dm/build-manifest :test [(second sample-descriptors)]) ; alpha only
          committed-edn (dm/render-edn committed-m)
          gen-m         (dm/build-manifest :test sample-descriptors)            ; alpha + beta
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (= ["beta"] (:added res)) "beta is new vs the committed file")
      (is (empty? (:removed res))))))

(deftest check-detects-removed-tool
  (testing "a tool in the committed file the generator no longer produces is :removed"
    (let [committed-m   (dm/build-manifest :test sample-descriptors)            ; alpha + beta
          committed-edn (dm/render-edn committed-m)
          gen-m         (dm/build-manifest :test [(second sample-descriptors)]) ; alpha only
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (empty? (:added res)))
      (is (= ["beta"] (:removed res)) "beta was dropped"))))

(deftest check-detects-changed-existing-tool
  ;; When an EXISTING tool's catalogue row drifts (here alpha gains an
  ;; input key) the identity sets stay empty — neither :added nor
  ;; :removed names it. Without a row-level diff the CI guard would go
  ;; red with no identity of WHAT changed, forcing a manual
  ;; whole-manifest diff. `:changed` names the drifted tool and carries
  ;; old/new rows.
  (testing "an existing tool that gains an input key is :changed, not :added/:removed"
    (let [committed-m   (dm/build-manifest :test sample-descriptors)
          committed-edn (dm/render-edn committed-m)
          ;; alpha gains a :force input property; beta is untouched.
          alpha+        (assoc-in (second sample-descriptors)
                                  [:inputSchema :properties :force]
                                  {:type "boolean"})
          gen-m         (dm/build-manifest :test [(first sample-descriptors) alpha+])
          gen-edn       (dm/render-edn gen-m)
          res           (dm/check gen-m gen-edn committed-edn)]
      (is (false? (:ok? res)))
      (is (= [] (:added res)) "no tool entered the catalogue")
      (is (= [] (:removed res)) "no tool left the catalogue")
      (is (= ["alpha"] (mapv :name (:changed res)))
          "alpha's row drifted; it is named in :changed")
      (let [{:keys [old new]} (first (:changed res))]
        (is (= ["event"] (:input-keys old)) "old row carries the committed input-keys")
        (is (= ["event" "force"] (:input-keys new))
            "new row carries the regenerated input-keys — the maintainer sees the delta")))))

(deftest check-changed-detects-each-drifting-row-shape
  ;; Every catalogue-surface slot the manifest governs trips :changed in
  ;; isolation (description / output? / annotations / required /
  ;; typicalTokens, plus the input-keys case above). One row mutated per
  ;; case; beta untouched. The :required + :typicalTokens cases cover
  ;; the live API-semantics facets alongside the others.
  (let [base (second sample-descriptors)] ; alpha
    (doseq [[label mutate] [["description"   #(assoc % :description "Changed prose.")]
                            ["output?"       #(assoc % :outputSchema {:type "object"})]
                            ["annotations"   #(assoc-in % [:annotations :readOnlyHint] true)]
                            ;; flip :event from required to optional (drop :required)
                            ["required"      #(update % :inputSchema dissoc :required)]
                            ["typicalTokens" #(assoc % :typicalTokens 999)]]]
      (testing (str "a changed :" label " trips :changed")
        (let [committed-m   (dm/build-manifest :test sample-descriptors)
              committed-edn (dm/render-edn committed-m)
              gen-m         (dm/build-manifest :test [(first sample-descriptors) (mutate base)])
              gen-edn       (dm/render-edn gen-m)
              res           (dm/check gen-m gen-edn committed-edn)]
          (is (false? (:ok? res)))
          (is (= [] (:added res)))
          (is (= [] (:removed res)))
          (is (= ["alpha"] (mapv :name (:changed res)))))))))

(deftest check-tolerates-crlf-committed
  (testing "a CRLF working-tree checkout does not trip a spurious drift"
    (let [m       (dm/build-manifest :test sample-descriptors)
          lf-edn  (dm/render-edn m)
          crlf    (str/replace lf-edn "\n" "\r\n")
          res     (dm/check m lf-edn crlf)]
      (is (true? (:ok? res)) "LF-normalised comparison ignores line-ending style"))))

;; ---------------------------------------------------------------------------
;; Drift-report formatting — the shared, pure diagnostic body both server
;; generators print when a drift-check trips (rf2-cbgc40). Previously each
;; generator carried a byte-identical copy of the governed-slot vector +
;; the added / removed / changed / malformed traversal; centralising it
;; here pins the ONE report surface both servers share, with the two
;; per-server strings (regenerate command + missing-file header) injected.
;; ---------------------------------------------------------------------------

(def ^:private wording
  {:regenerate-line   "Regenerate with: <server command>"
   :missing-file-line "DRIFT: <file> does not exist. Run: <server command>"})

(deftest governed-slots-is-every-row-slot-except-name
  ;; The governed-slot vector must stay in lockstep with the row shape:
  ;; every slot descriptor->row emits EXCEPT :name (whose add/remove is
  ;; the :added/:removed diff). A slot added to the row but not here would
  ;; silently escape :changed reporting.
  (let [row-slots (set (keys (dm/descriptor->row (first sample-descriptors))))]
    (is (= (disj row-slots :name) (set dm/governed-slots))
        "governed-slots = row slots minus :name")
    (is (= [:description :input-keys :gated-input-keys :required :output? :annotations :typicalTokens]
           dm/governed-slots)
        "canonical report order is stable")))

(deftest row-slot-deltas-names-only-drifting-slots-in-canonical-order
  (let [old {:description "d" :input-keys ["event"] :gated-input-keys []
             :required ["event"] :output? false :annotations [] :typicalTokens 300}
        new (assoc old :input-keys ["event" "force"] :typicalTokens 999)]
    (is (= [[:input-keys ["event"] ["event" "force"]]
            [:typicalTokens 300 999]]
           (dm/row-slot-deltas old new))
        "only the two drifting slots, in governed-slots order")
    (is (= [] (dm/row-slot-deltas old old))
        "identical rows → no deltas")))

(deftest drift-report-lines-missing-file
  ;; A missing committed file: check reports EVERY generated tool as
  ;; :added; the report leads with the consumer's missing-file header,
  ;; then the regenerate line, then the full tool list.
  (let [res   {:ok? false :added ["alpha" "beta"] :removed [] :changed [] :missing-file? true}
        lines (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: <file> does not exist. Run: <server command>"
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + alpha"
            "    + beta"]
           lines))))

(deftest drift-report-lines-added
  (let [res   {:ok? false :added ["beta"] :removed [] :changed []}
        lines (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + beta"]
           lines)
        "an existing (non-missing) file uses the shared differs header")))

(deftest drift-report-lines-removed
  (let [res   {:ok? false :added [] :removed ["beta"] :changed []}
        lines (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools in the committed file the registry no longer has (removed/renamed tool):"
            "    - beta"]
           lines))))

(deftest drift-report-lines-changed-per-slot
  ;; A changed row prints the tool name, then one line per drifting
  ;; governed slot as `<slot>: <pr-str old> -> <pr-str new>`.
  (let [old   {:description "d" :input-keys ["event"] :gated-input-keys []
               :required ["event"] :output? false :annotations [] :typicalTokens 300}
        new   (assoc old :input-keys ["event" "force"] :typicalTokens 999)
        res   {:ok? false :added [] :removed []
               :changed [{:name "alpha" :old old :new new}]}
        lines (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):"
            "    ~ alpha"
            "        input-keys: [\"event\"] -> [\"event\" \"force\"]"
            "        typicalTokens: 300 -> 999"]
           lines))))

(deftest drift-report-lines-structurally-broken-committed
  ;; An unparseable committed file: check cannot read its rows, so every
  ;; generated tool is reported as :added (whole catalogue absent) with no
  ;; :changed — the report lists them all under the differs header.
  (let [m     (dm/build-manifest :test sample-descriptors)
        edn   (dm/render-edn m)
        res   (dm/check m edn "this is not { valid edn")
        lines (dm/drift-report-lines res wording)]
    (is (false? (:ok? res)))
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools the registry has that the committed file lacks (new/renamed tool):"
            "    + alpha"
            "    + beta"]
           lines))))

(deftest drift-report-lines-malformed-fallback
  ;; Tool set identical but the byte comparison failed (banner / meta
  ;; drift): added / removed / changed are all empty, so the fallback
  ;; hint fires instead of an empty body.
  (let [res   {:ok? false :added [] :removed [] :changed []}
        lines (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  (tool set identical; the committed file is structurally broken or its provenance banner/meta drifted — regenerate)"]
           lines))))

(deftest drift-report-lines-end-to-end-from-check
  ;; The formatter consumes a real `check` result unchanged: alpha gains
  ;; an input key, beta is dropped → one :changed + one :removed reported
  ;; together in canonical block order (added, removed, changed).
  (let [committed-m   (dm/build-manifest :test sample-descriptors)
        committed-edn (dm/render-edn committed-m)
        alpha+        (assoc-in (second sample-descriptors)
                                [:inputSchema :properties :force] {:type "boolean"})
        gen-m         (dm/build-manifest :test [alpha+]) ; alpha changed, beta removed
        gen-edn       (dm/render-edn gen-m)
        res           (dm/check gen-m gen-edn committed-edn)
        lines         (dm/drift-report-lines res wording)]
    (is (= ["DRIFT: generated manifest differs from tool-descriptors.edn."
            "Regenerate with: <server command>"
            "  Tools in the committed file the registry no longer has (removed/renamed tool):"
            "    - beta"
            "  Existing tools whose descriptor catalogue row changed (input-keys / gated-input-keys / required / output? / annotations / description / typicalTokens):"
            "    ~ alpha"
            "        input-keys: [\"event\"] -> [\"event\" \"force\"]"]
           lines))))
