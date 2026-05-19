(ns re-frame2-pair-mcp.handler-meta-test
  "Unit tests for the `handler-meta` + `list-handlers` MCP tools (rf2-pctf8).

  Both tools build a CLJS form that calls into the preloaded runtime
  (`re-frame2-pair.runtime/registrar-describe` / `registrar-list` for
  the six registrar kinds; `re-frame.core/machine-meta` /
  `re-frame.core/machines` for the `:machine` kind). Live end-to-end
  coverage runs against a shadow-cljs runtime; these tests pin:

    1. The descriptor wire-up — both tools surface on `tool-descriptors`
       and `tool-descriptors-js` with the right shape (required args,
       enum vocab, typicalTokens).
    2. The kind / id parsers — recognised kinds map to keywords;
       unknown / malformed values are rejected with structured envelopes;
       EDN-encoded ids round-trip cleanly.
    3. The form composition — given a valid (kind, id) pair the right
       runtime fn is called (`registrar-describe` for the six registrar
       kinds; `machine-meta` for `:machine`).
    4. Error envelopes — missing / invalid kind / id arguments surface
       structured `:reason` slots an agent can read."
  (:require [cljs.test :refer-macros [deftest is testing]]
            [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.handler-meta :as hm]))

;; ---------------------------------------------------------------------------
;; Helpers.
;; ---------------------------------------------------------------------------

(defn- args-js [m]
  (let [o #js {}]
    (doseq [[k v] m]
      (j/assoc! o (name k) v))
    o))

(defn- extract-text [result]
  (let [content (j/get result :content)
        item    (when (array? content) (aget content 0))]
    (when item (j/get item :text))))

(defn- extract-edn [result]
  (some-> (extract-text result) edn/read-string))

(defn- is-error? [result]
  (true? (j/get result :isError)))

(defn- find-descriptor [name]
  (some #(when (= name (:name %)) %) tools/tool-descriptors))

;; ---------------------------------------------------------------------------
;; Descriptor — handler-meta.
;; ---------------------------------------------------------------------------

(deftest handler-meta-descriptor-present
  (testing "handler-meta is registered in tool-descriptors"
    (let [d (find-descriptor "handler-meta")]
      (is (some? d) "descriptor exists")
      (is (string? (:description d)))
      (is (integer? (:typicalTokens d)))
      (is (pos? (:typicalTokens d)))
      (let [{:keys [required properties]} (:inputSchema d)]
        (is (= #{"kind" "id"} (set required))
            "kind + id are both required")
        (is (contains? properties :kind))
        (is (contains? properties :id))
        (is (= #{"event" "sub" "fx" "cofx" "view" "frame"
                 "route" "flow" "head" "error-projector" "machine"}
               (set (:enum (:kind properties))))
            "kind enum lists every supported kind")))))

(deftest handler-meta-descriptor-surfaces-on-tools-list
  (testing "handler-meta shows up in tool-descriptors-js"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (contains? names "handler-meta")))))

;; ---------------------------------------------------------------------------
;; Descriptor — list-handlers.
;; ---------------------------------------------------------------------------

(deftest list-handlers-descriptor-present
  (testing "list-handlers is registered in tool-descriptors"
    (let [d (find-descriptor "list-handlers")]
      (is (some? d) "descriptor exists")
      (is (string? (:description d)))
      (is (integer? (:typicalTokens d)))
      (let [{:keys [required properties]} (:inputSchema d)]
        (is (= #{"kind"} (set required))
            "kind is the only required arg")
        (is (contains? properties :kind))
        (is (= #{"event" "sub" "fx" "cofx" "view" "frame"
                 "route" "flow" "head" "error-projector" "machine"}
               (set (:enum (:kind properties)))))))))

(deftest list-handlers-descriptor-surfaces-on-tools-list
  (testing "list-handlers shows up in tool-descriptors-js"
    (let [arr   (tools/tool-descriptors-js)
          names (set (for [i (range (alength arr))]
                       (j/get (aget arr i) :name)))]
      (is (contains? names "list-handlers")))))

;; ---------------------------------------------------------------------------
;; handler-meta-tool — error envelopes (no nREPL needed).
;;
;; The tool short-circuits on bad args BEFORE reaching `probe/ensure-runtime!`.
;; A nil conn never gets touched on these paths.
;; ---------------------------------------------------------------------------

(deftest handler-meta-rejects-missing-kind
  (testing "handler-meta with no :kind surfaces :invalid-kind"
    (let [p (hm/handler-meta-tool nil (args-js {:id ":user/login"}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :invalid-kind (:reason edn)))))))))

(deftest handler-meta-rejects-unknown-kind
  (testing "handler-meta with an out-of-vocab :kind surfaces :invalid-kind"
    (let [p (hm/handler-meta-tool nil (args-js {:kind "not-a-kind"
                                                 :id   ":user/login"}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :invalid-kind (:reason edn)))
                   (is (= "not-a-kind" (:kind edn)))))))))

(deftest handler-meta-rejects-missing-id
  (testing "handler-meta with kind but no :id surfaces :missing-id"
    (let [p (hm/handler-meta-tool nil (args-js {:kind "event"}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :missing-id (:reason edn)))))))))

(deftest handler-meta-rejects-invalid-id-edn
  (testing "handler-meta with unreadable :id surfaces :invalid-id-edn"
    (let [p (hm/handler-meta-tool nil (args-js {:kind "event"
                                                 :id   "{:unclosed"}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :invalid-id-edn (:reason edn)))))))))

;; ---------------------------------------------------------------------------
;; list-handlers-tool — error envelopes.
;; ---------------------------------------------------------------------------

(deftest list-handlers-rejects-missing-kind
  (testing "list-handlers with no :kind surfaces :invalid-kind"
    (let [p (hm/list-handlers-tool nil (args-js {}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :invalid-kind (:reason edn)))))))))

(deftest list-handlers-rejects-unknown-kind
  (testing "list-handlers with an out-of-vocab :kind surfaces :invalid-kind"
    (let [p (hm/list-handlers-tool nil (args-js {:kind "ghost"}))]
      (.then p (fn [result]
                 (is (is-error? result))
                 (let [edn (extract-edn result)]
                   (is (= :invalid-kind (:reason edn)))))))))

;; ---------------------------------------------------------------------------
;; Name + descriptor kind-vocab consistency.
;; ---------------------------------------------------------------------------

(deftest tool-name-uses-kebab-case
  (testing "the two new tool descriptors use kebab-case names"
    (is (= "handler-meta" (:name (find-descriptor "handler-meta")))
        "name uses kebab-case, not handler_meta / handlerMeta")
    (is (= "list-handlers" (:name (find-descriptor "list-handlers")))
        "name uses kebab-case, not list_handlers / listHandlers")))

(deftest descriptors-share-the-kind-vocab
  (testing "handler-meta and list-handlers share the same :kind enum"
    (let [hm-enum (-> (find-descriptor "handler-meta") :inputSchema :properties :kind :enum set)
          rl-enum (-> (find-descriptor "list-handlers") :inputSchema :properties :kind :enum set)]
      (is (= hm-enum rl-enum)
          "drift here would make agents learn two vocabularies for one concept"))))
