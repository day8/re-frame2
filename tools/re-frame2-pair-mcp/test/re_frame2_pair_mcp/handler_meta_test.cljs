(ns re-frame2-pair-mcp.handler-meta-test
  "Unit tests for the `handler-meta` + `list-handlers` MCP tools (rf2-pctf8).

  Both tools build a CLJS form that calls into the preloaded runtime
  (`re-frame2-pair.runtime/registrar-describe` / `registrar-list` for
  the ten registrar kinds; `re-frame.core/machine-meta` /
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
  (:require [cljs.test :refer-macros [deftest is testing async]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.handler-meta :as hm]))

;; ---------------------------------------------------------------------------
;; Helpers.
;;
;; The wire-envelope extractors live in `test-utils` (shared across
;; suites, rf2-wnrpi). `args-js` is aliased to the shared `args->js`.
;; ---------------------------------------------------------------------------

(def ^:private args-js tu/args->js)
(def ^:private extract-edn tu/extract-edn)
(def ^:private is-error? tu/error?)

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
;;
;; rf2-wnrpi: these six tests used to call `(.then p (fn [r] (is ...)))`
;; from a SYNCHRONOUS deftest body — the deftest returned before the
;; Promise resolved, so cljs.test recorded each as passed with ZERO
;; assertions. The error-envelope contract for both tools was therefore
;; effectively untested (a flipped `:invalid-kind` reason or an NPE on
;; the nil conn would have passed green). Each now wraps the `.then` in
;; `(async done ...)` so the assertions actually run before the test
;; completes — mirroring `dispatch_test` / `probe_test`.
;; ---------------------------------------------------------------------------

(deftest handler-meta-rejects-missing-kind
  (testing "handler-meta with no :kind surfaces :invalid-kind"
    (async done
      (-> (hm/handler-meta-tool nil (args-js {:id ":user/login"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :invalid-kind (:reason edn))))
                   (done)))))))

(deftest handler-meta-rejects-unknown-kind
  (testing "handler-meta with an out-of-vocab :kind surfaces :invalid-kind"
    (async done
      (-> (hm/handler-meta-tool nil (args-js {:kind "not-a-kind"
                                              :id   ":user/login"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :invalid-kind (:reason edn)))
                     (is (= "not-a-kind" (:kind edn))))
                   (done)))))))

(deftest handler-meta-rejects-missing-id
  (testing "handler-meta with kind but no :id surfaces :missing-id"
    (async done
      (-> (hm/handler-meta-tool nil (args-js {:kind "event"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :missing-id (:reason edn))))
                   (done)))))))

(deftest handler-meta-rejects-invalid-id-edn
  (testing "handler-meta with unreadable :id surfaces :invalid-id-edn"
    (async done
      (-> (hm/handler-meta-tool nil (args-js {:kind "event"
                                              :id   "{:unclosed"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :invalid-id-edn (:reason edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; list-handlers-tool — error envelopes.
;; ---------------------------------------------------------------------------

(deftest list-handlers-rejects-missing-kind
  (testing "list-handlers with no :kind surfaces :invalid-kind"
    (async done
      (-> (hm/list-handlers-tool nil (args-js {}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :invalid-kind (:reason edn))))
                   (done)))))))

(deftest list-handlers-rejects-unknown-kind
  (testing "list-handlers with an out-of-vocab :kind surfaces :invalid-kind"
    (async done
      (-> (hm/list-handlers-tool nil (args-js {:kind "ghost"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :invalid-kind (:reason edn))))
                   (done)))))))

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

;; ---------------------------------------------------------------------------
;; rf2-l7vnd regression — handler-meta returns :ok? true with the real
;; data as top-level keys; the bug shape was :ok? false :reason
;; :unexpected-shape with the actual map embedded as an EDN string.
;;
;; Pre-rf2-l7vnd path the runtime emitted a map containing
;; `:handler-fn <Function>`; `pr-str` rendered that as
;; `#object[Function ...]`; nrepl's `read-edn-safe` couldn't parse it
;; back; the tool body fell into `(not (map? v))` and stuffed the raw
;; string under `:value`. Two complementary defences pin the fix:
;;
;;   - Runtime side (skills/re-frame2-pair/preload/...): dissoc'd
;;     :handler-fn before returning. Pinned structurally by the
;;     babashka test `registrar_describe_test.clj`.
;;
;;   - MCP tool side (here): defensive re-parse so a future runtime
;;     slip emitting a stringified map still surfaces as :ok? true.
;; ---------------------------------------------------------------------------

(defn- with-canned-eval!
  "Stub `nrepl/cljs-eval-value` to resolve every call with `v`. The
  probe call (first eval, `__re_frame2_pair_runtime` form) gets the
  same response so we MUST hand back `true` from the first call. The
  trick: gate by call-count, so call 1 returns true (probe), call 2
  returns the canned value (actual handler-meta eval)."
  [canned-handler-value body-fn]
  (let [orig nrepl/cljs-eval-value
        ;; conn cache makes the probe round-trip skipped after the
        ;; first call. To be safe across tests, we always return
        ;; `true` for forms that contain the probe sentinel and the
        ;; canned value otherwise.
        stub (fn
               ([_conn _build-id form-str]
                (js/Promise.resolve
                  (if (re-find #"__re_frame2_pair_runtime" form-str)
                    true
                    canned-handler-value)))
               ([_conn _build-id form-str _opts]
                (js/Promise.resolve
                  (if (re-find #"__re_frame2_pair_runtime" form-str)
                    true
                    canned-handler-value))))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

(deftest handler-meta-returns-ok-true-with-real-keys
  (testing "registered handler → :ok? true with the metadata as top-level keys"
    (async done
      (let [canned {:ns 'testdeck.counter
                    :file "testdeck/counter.cljs"
                    :line 82
                    :handler-fn-hash 1140207590}]
        (-> (with-canned-eval! canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":counter/inc"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (not (is-error? result))
                                   "the response MUST NOT carry :isError true — bug shape")
                               (is (true? (:ok? edn))
                                   "the response MUST carry :ok? true, not :ok? false :reason :unexpected-shape")
                               (is (= :event (:kind edn)))
                               (is (= :counter/inc (:id edn)))
                               (is (= 'testdeck.counter (:ns edn))
                                   "real metadata keys must appear at the top level, not buried under :value")
                               (is (= 82 (:line edn)))
                               (is (= 1140207590 (:handler-fn-hash edn))
                                   "handler-fn-hash is the wire-friendly substitute for :handler-fn")
                               (is (not (contains? edn :value))
                                   "the bug shape stuffed the map under :value as a string — must not recur"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest handler-meta-unserializable-surfaces-structured
  (testing "rf2-qobqy: a runtime meta map that can't round-trip as EDN now rides back as a tagged :unserializable envelope — NOT a meta map smuggled as a STRING"
    ;; The pre-rf2-qobqy path defensively re-parsed a stringified map.
    ;; The typed result codec (rf2-qobqy) makes that obsolete: the
    ;; RUNTIME classifies an unserializable meta map (a `#object`
    ;; Function slot, a `#js {…}`) into a tagged `:rf.mcp/result
    ;; :unserializable` envelope with a `:preview`. The tool surfaces
    ;; the STRUCTURED error stamped with the requested kind/id — never
    ;; the meta-map-as-string the old :unexpected-shape path carried.
    (async done
      (let [tagged {:rf.mcp/result :unserializable
                    :type "object"
                    :preview "{:ns testdeck.counter :handler-fn #object[Function]}"}]
        (-> (with-canned-eval! tagged
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":counter/inc"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (is-error? result)
                                   "an unserializable meta map is an :isError envelope")
                               (is (false? (:ok? edn)))
                               (is (= :rf.error/unserializable (:reason edn)))
                               (is (= :event (:kind edn)) "kind stamped on the error")
                               (is (= :counter/inc (:id edn)) "id stamped on the error")
                               (is (str/includes? (:preview edn) "#object")
                                   "the preview shows WHAT couldn't serialize"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest handler-meta-not-registered-passes-through
  (testing "the runtime's :not-registered envelope still passes through unchanged"
    (async done
      (let [canned {:ok? false :reason :not-registered :kind :event :id :no/such}]
        (-> (with-canned-eval! canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":no/such"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (false? (:ok? edn)))
                               (is (= :not-registered (:reason edn))))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest handler-meta-genuinely-unparseable-still-fails
  (testing "a non-map non-recoverable value still surfaces :unexpected-shape"
    (async done
      ;; A plain integer back from the runtime is genuinely the wrong
      ;; shape — not a map, not a stringified map. The tool MUST still
      ;; surface :unexpected-shape so the bug envelope keeps doing its job
      ;; for actual shape errors.
      (-> (with-canned-eval! 42
            (fn []
              (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":anything"}))
                  (.then (fn [result]
                           (let [edn (extract-edn result)]
                             (is (false? (:ok? edn)))
                             (is (= :unexpected-shape (:reason edn)))
                             (is (= 42 (:value edn))
                                 "the offending value rides on :value for forensics"))
                           (done))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done)))))))
