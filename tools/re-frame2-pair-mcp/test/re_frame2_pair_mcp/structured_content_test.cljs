(ns re-frame2-pair-mcp.structured-content-test
  "Every MCP result envelope MUST carry both the
  wire-canonical `:content [{:type \"text\" :text ...}]` slot and a
  `:structuredContent` slot whose value is the JS-coerced projection
  of the same payload.

  The dual-slot rule is enforced at one site, in `tools.wire/ok-text`
  + `err-text`; the test corpus pins the shape on the four canonical
  paths:

    - Success envelope (`ok-text`).
    - Error envelope (`err-text`).
    - Cache-hit marker (`cache/cache-hit-result`).
    - Overflow marker (`tools.cap/result-io` via `apply-cap`)."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.tools.wire :as wire]
            [re-frame2-pair-mcp.cache :as cache]))

;; ---------------------------------------------------------------------------
;; Helpers.
;; ---------------------------------------------------------------------------

(defn- content-text [result-js]
  (let [c (j/get result-js :content)
        item (when (array? c) (aget c 0))]
    (when item (j/get item :text))))

;; ---------------------------------------------------------------------------
;; Success envelope.
;; ---------------------------------------------------------------------------

(deftest ok-text-emits-both-slots
  (testing "wire/ok-text carries :content (text) AND :structuredContent"
    (let [payload {:ok? true :value 42 :tag :sample}
          result  (wire/ok-text payload)]
      (is (some? (j/get result :content))
          "the wire-canonical :content slot is present")
      (is (= (pr-str payload) (content-text result))
          ":content[0].text is the pr-str EDN of the payload")
      (is (some? (j/get result :structuredContent))
          "the :structuredContent slot is present (rf2-hj3pi)")
      ;; The structured slot should be a JS object whose shape mirrors
      ;; the input. Keywords lose their `:` prefix in the JSON-coercible
      ;; projection but KEEP their namespace (`:rf/x` → "rf/x"); plain
      ;; keys like `:ok?` become the bare "ok?".
      (is (object? (j/get result :structuredContent))
          ":structuredContent is a JS object")
      (is (= true (j/get-in result [:structuredContent :ok?]))
          ":ok? round-trips through clj->js")
      (is (= 42 (j/get-in result [:structuredContent :value]))
          ":value round-trips")
      ;; No :isError on success.
      (is (not (true? (j/get result :isError)))
          "success envelopes do not set :isError"))))

;; ---------------------------------------------------------------------------
;; Error envelope.
;; ---------------------------------------------------------------------------

(deftest err-text-emits-both-slots-plus-isError
  (testing "wire/err-text carries :isError, :content, AND :structuredContent"
    (let [payload {:ok? false :reason :sample-error :hint "..."}
          result  (wire/err-text payload)]
      (is (true? (j/get result :isError))
          ":isError true is set on error envelopes")
      (is (= (pr-str payload) (content-text result))
          ":content[0].text is the pr-str EDN of the error payload")
      (is (some? (j/get result :structuredContent))
          ":structuredContent slot is present on error envelopes too (rf2-hj3pi)")
      (is (= false (j/get-in result [:structuredContent :ok?]))
          ":ok? false round-trips"))))

;; ---------------------------------------------------------------------------
;; Cache-hit marker.
;; ---------------------------------------------------------------------------

(deftest cache-hit-result-emits-both-slots
  (testing "cache-hit-result carries both wire slots (rf2-hj3pi)"
    (let [entry  {:hash 12345 :unchanged-since 1700000000000}
          result (cache/cache-hit-result entry "snapshot" :result-hash)
          text   (content-text result)]
      (is (string? text)
          ":content[0].text is present")
      (is (some? (j/get result :structuredContent))
          ":structuredContent is present on cache-hit envelopes")
      ;; Cache-hit envelope wraps the payload in the
      ;; `:rf.mcp/cache-hit` marker key. Sanity: the structured slot
      ;; is a JS object (the precise shape is pinned in the cache test
      ;; corpus elsewhere).
      (is (object? (j/get result :structuredContent))))))

(deftest cache-hit-marker-key-keeps-namespace-in-structured-slot
  ;; The cache-hit marker is built OUTSIDE the per-tool callbacks. It
  ;; routes through `wire/result` so SDK-friendly hosts reading
  ;; structuredContent see the fully-qualified `"rf.mcp/cache-hit"`
  ;; token rather than a namespace-truncated `"cache-hit"` key.
  (testing "the :rf.mcp/cache-hit marker KEY survives namespace-faithfully (rf2-or8s29)"
    (let [entry  {:hash 12345 :unchanged-since 1700000000000}
          result (cache/cache-hit-result entry "snapshot" :result-hash)]
      (is (some? (j/get-in result [:structuredContent "rf.mcp/cache-hit"]))
          "the marker serialises to the fully-qualified \"rf.mcp/cache-hit\" key")
      (is (nil? (j/get-in result [:structuredContent "cache-hit"]))
          "the namespace-truncated \"cache-hit\" key must NOT appear (the lossy old shape)"))))

;; ---------------------------------------------------------------------------
;; structuredContent is NEVER null.
;;
;; Every tool descriptor declares a map-shaped `:outputSchema`
;; (`{:type "object"}`). The npm MCP SDK validates `structuredContent`
;; against it, and a `null` is not a record — the SDK rejects the whole
;; `tools/call` at the transport layer with
;; `Invalid tools/call result: expected record at structuredContent,
;; received null`. read-dom can produce this when its browser eval
;; comes back blank (`cljs-eval-value` → nil → `(wire/ok-text nil)` →
;; `(clj->js nil)` → null). `ok-text` / `err-text` make the slot a
;; total non-null record.
;; ---------------------------------------------------------------------------

(deftest ok-text-nil-payload-never-emits-null-structured-content
  (testing "wire/ok-text with a nil payload emits a non-null structured record (rf2-r5erl)"
    (let [result (wire/ok-text nil)]
      (is (some? (j/get result :structuredContent))
          ":structuredContent must NOT be null — the SDK outputSchema check rejects null")
      (is (object? (j/get result :structuredContent))
          ":structuredContent must be a record (object), not a primitive")
      (is (true? (j/get-in result [:structuredContent "rf.mcp/null"]))
          "the nil payload projects to the :rf.mcp/null sentinel object")
      (is (= "nil" (content-text result))
          "the EDN text slot still carries the verbatim nil for the cljs round-trip"))))

(deftest err-text-nil-payload-never-emits-null-structured-content
  (testing "wire/err-text with a nil payload emits a non-null structured record (rf2-r5erl)"
    (let [result (wire/err-text nil)]
      (is (true? (j/get result :isError)))
      (is (some? (j/get result :structuredContent)))
      (is (object? (j/get result :structuredContent))
          ":structuredContent must be a record even on the error path"))))

(deftest ok-text-scalar-payload-wraps-to-a-record
  (testing "a non-map scalar payload still projects to an object structuredContent (rf2-r5erl)"
    ;; clj->js of a bare scalar (number / string / bool) is a JS
    ;; primitive — also not a record. The total-function backstop wraps
    ;; it so the slot is always object-typed.
    (doseq [v [42 "hi" true]]
      (let [result (wire/ok-text v)]
        (is (object? (j/get result :structuredContent))
            (str "scalar " (pr-str v) " must wrap to an object structuredContent"))
        (is (= (pr-str v) (content-text result))
            "the EDN text slot still carries the verbatim scalar")))))

;; ---------------------------------------------------------------------------
;; Namespaced keywords keep their namespace over the wire.
;;
;; A bare `(clj->js v)` is namespace-lossy: the default `:keyword-fn` for
;; map KEYS is `name` (`:rf/runtime` → `"runtime"`), and keyword VALUES
;; always go through `name` too (`:door/main` → `"main"`). Both drop the
;; namespace AND the colon, so an agent reading the structured slot then
;; threading the name-only key back through `get-path` hits `nil`. The
;; wire layer stringifies every keyword to its colon-less fully-qualified
;; token (`"rf/runtime"`) before `clj->js`. The EDN text slot stays the
;; namespace-faithful, fully-typed canonical round-trip.
;; ---------------------------------------------------------------------------

(deftest namespaced-keyword-map-key-keeps-namespace-in-structured-slot
  (testing "a namespaced map KEY survives to the structured slot as ns/name (rf2-t2n04)"
    (let [payload {:rf/runtime {:loaded? true} :step 3}
          result  (wire/ok-text payload)]
      ;; The structured slot key must be the fully-qualified token, NOT
      ;; a name-only "runtime".
      (is (some? (j/get-in result [:structuredContent "rf/runtime"]))
          ":rf/runtime serialises to the \"rf/runtime\" key, not \"runtime\"")
      (is (nil? (j/get-in result [:structuredContent "runtime"]))
          "the name-only \"runtime\" key must NOT appear (the lossy old shape)")
      (is (= true (j/get-in result [:structuredContent "rf/runtime" "loaded?"]))
          "nested values under the namespaced key still round-trip")
      ;; A plain (un-namespaced) key keeps its bare name.
      (is (= 3 (j/get-in result [:structuredContent "step"]))
          "plain keys remain bare-named")
      ;; The EDN text slot is the canonical fully-typed form, unchanged.
      (is (= (pr-str payload) (content-text result))
          "the EDN text slot carries the verbatim namespace-faithful EDN"))))

(deftest namespaced-keyword-values-keep-namespace-in-structured-slot
  (testing "namespaced keyword VALUES (e.g. machine-ids) keep their namespace (rf2-t2n04)"
    ;; clj->js's :keyword-fn applies to KEYS only; keyword values go
    ;; through `name`. This pins the value path too.
    (let [payload {:machine-ids [:door/main :traffic/light :quiz/scorer]
                   :current     :door/open}
          result  (wire/ok-text payload)
          ids     (js->clj (j/get-in result [:structuredContent "machine-ids"]))]
      (is (= ["door/main" "traffic/light" "quiz/scorer"] ids)
          "namespaced keyword values serialise as ns/name, not the truncated name")
      (is (= "door/open" (j/get-in result [:structuredContent "current"]))
          "a scalar namespaced keyword value keeps its namespace")
      (is (= (pr-str payload) (content-text result))
          "EDN text slot unchanged — the canonical round-trip"))))

(deftest namespaced-keyword-round-trips-through-the-structured-slot
  (testing "reading the structured-slot token back yields the original keyword (rf2-t2n04)"
    ;; The whole point: an agent that reads the structured slot can
    ;; reconstruct the exact key it must thread back into get-path.
    (doseq [kw [:rf/runtime :door/open :examples/step-deck]]
      (let [payload {kw 1}
            result  (wire/ok-text payload)
            ;; The structured key is the colon-less fully-qualified token.
            token   (-> (j/get result :structuredContent)
                        js/Object.keys
                        (aget 0))]
        (is (= (str (symbol kw)) token)
            (str kw " serialises to its colon-less fully-qualified token"))
        ;; Round-trip: prefixing a colon reads back to the original kw.
        (is (= kw (keyword token))
            (str "(keyword \"" token "\") reconstructs " kw))))))
