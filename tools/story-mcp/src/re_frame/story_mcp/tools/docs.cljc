(ns re-frame.story-mcp.tools.docs
  "Docs-category tool handlers — introspection over the Story
  registry. Per IMPL-SPEC §7.2 these are the pure-read surfaces:
  `list-stories`, `get-story`, `get-variant`, `list-tags`,
  `list-modes`, `list-assertions`, `variant->edn`."
  (:require [clojure.set :as set]
            [re-frame.mcp-base.args :as args]
            [re-frame.story :as story]
            [re-frame.story-mcp.tools.helpers :as h]
            [re-frame.story-mcp.tools.schemas :as s]))

(defn tool-list-stories
  "Docs: all registered stories (with optional tag filters).

  `args`:
    :tags — vector of tag ids (strings or `:keyword` forms); narrows
            the result to stories whose `:tags` set intersects this."
  [args]
  (let [stories (story/handlers :story)
        tags    (when-let [ts (:tags args)]
                  (set (map args/parse-keyword ts)))
        filtered (if (seq tags)
                   (into {}
                         (filter (fn [[_id body]]
                                   (seq (set/intersection tags (set (:tags body))))))
                         stories)
                   stories)
        payload  {:stories (vec (for [[sid body] filtered]
                                  {:id   sid
                                   :doc  (:doc body)
                                   :tags (vec (:tags body))
                                   :variants (sort (story/variants-of sid))}))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-get-story
  "Docs: one story's full body."
  [args]
  (let [[sid err] (h/required-arg args :story-id)]
    (if err err
      (let [sk   (args/parse-keyword sid)
            body (story/handler-meta :story sk)]
        (if (nil? body)
          (h/error-result (str "Story not found: " (pr-str sk)))
          (let [payload {:id sk :body body :variants (sort (story/variants-of sk))}]
            (h/text-result (h/pr-edn payload) payload)))))))

(defn tool-get-variant
  "Docs: one variant's full body (`handler-meta :variant id`)."
  [args]
  (h/with-variant args
    (fn [vk body]
      (let [payload {:id vk :body body}]
        (h/text-result (h/pr-edn payload) payload)))))

(defn tool-list-tags
  "Docs: canonical tags + custom tags."
  [_args]
  (let [registered (story/list-tags)
        canonical  story/canonical-tags
        custom     (set/difference (set registered) (set canonical))
        payload    {:canonical (vec (sort-by str canonical))
                    :custom    (vec (sort-by str custom))
                    :all       (vec (sort-by str registered))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-list-modes
  "Docs: registered modes (from `reg-mode`). Returns each mode's id +
  body so agents can see the `:args` saved tuple."
  [_args]
  (let [modes   (story/handlers :mode)
        payload {:modes (vec (for [[mid body] modes]
                               {:id mid :doc (:doc body) :args (:args body)}))}]
    (h/text-result (h/pr-edn payload) payload)))

(def canonical-assertion-docs
  "Per spec/007 line 304 + IMPL-SPEC §3.5 the canonical seven
  assertions' arities."
  [{:id :rf.assert/path-equals
    :payload "[path expected]"
    :semantics "(= (get-in @app-db path) expected)"}
   {:id :rf.assert/path-matches
    :payload "[path malli-schema]"
    :semantics "Malli validate at path"}
   {:id :rf.assert/sub-equals
    :payload "[sub-vec expected]"
    :semantics "(= @(subscribe sub-vec) expected)"}
   {:id :rf.assert/dispatched?
    :payload "[event-or-pred]"
    :semantics "Was this event dispatched during play?"}
   {:id :rf.assert/state-is
    :payload "[machine-id state]"
    :semantics "Machine in state?"}
   {:id :rf.assert/no-warnings
    :payload "[]"
    :semantics "No :warning trace events since play start"}
   {:id :rf.assert/effect-emitted
    :payload "[fx-id (optional pred)]"
    :semantics "fx-id emitted during play?"}])

(defn tool-list-assertions
  "Docs: the `:rf.assert/*` canonical vocabulary + arity docs."
  [_args]
  (let [registered (story/canonical-assertion-ids)
        payload    {:canonical canonical-assertion-docs
                    :registered (vec (sort-by str registered))}]
    (h/text-result (h/pr-edn payload) payload)))

(defn tool-variant->edn
  "Docs: round-trippable EDN of a registered variant. Identical payload
  to `get-variant` but framed as EDN-only — the `structuredContent`
  slot is omitted so agents that want strict EDN parse the text."
  [args]
  (h/with-variant args
    (fn [_vk body]
      (h/text-result (h/pr-edn body)))))

;; ---------------------------------------------------------------------------
;; Registry descriptors (assembled in `tools.registry/tool-registry`)
;; ---------------------------------------------------------------------------

(def descriptors
  "Docs-category descriptors, in IMPL-SPEC §7.2 order."
  [{:name           "list-stories"
    :category       :docs
    :description    "All registered stories, optionally filtered by tags. Each entry carries id, doc, tags, and child variant ids."
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens
                                {:tags {:type "array" :items s/kw-or-string
                                        :description "Optional tag filter; story `:tags` set must intersect."}})
                  :additionalProperties false}
    :handler     tool-list-stories}

   {:name           "get-story"
    :category       :docs
    :description    "Return one story's full body (`:doc`, `:component`, `:decorators`, `:args`, ... + its variant ids)."
    :typicalTokens  1500
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:story-id s/kw-or-string})
                  :required ["story-id"]
                  :additionalProperties false}
    :handler     tool-get-story}

   {:name           "get-variant"
    :category       :docs
    :description    "Return one variant's full body (the resolved EDN, with `:extends` already applied at registration time)."
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-get-variant}

   {:name           "list-tags"
    :category       :docs
    :description    "Canonical tags (`:dev :docs :test :screenshot :experimental :internal :agent`) + any custom tags registered by the project."
    :typicalTokens  100
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-tags}

   {:name           "list-modes"
    :category       :docs
    :description    "Registered modes (Chromatic-style saved tuples of args). Each entry is `{:id :doc :args}`."
    :typicalTokens  200
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-modes}

   {:name           "list-assertions"
    :category       :docs
    :description    "The seven canonical `:rf.assert/*` events with payload arity + semantics, plus any project-registered assertion ids."
    :typicalTokens  500
    :inputSchema    {:type "object" :properties (s/with-max-tokens {}) :additionalProperties false}
    :handler        tool-list-assertions}

   {:name           "variant->edn"
    :category       :docs
    :description    "Round-trippable EDN of a registered variant. Text result only (no structuredContent); use this when you want byte-stable EDN."
    :typicalTokens  1000
    :inputSchema {:type "object"
                  :properties (s/with-max-tokens {:variant-id s/kw-or-string})
                  :required ["variant-id"]
                  :additionalProperties false}
    :handler     tool-variant->edn}])
