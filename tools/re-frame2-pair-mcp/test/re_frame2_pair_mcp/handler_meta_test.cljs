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
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
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
;;
;; ## Stub lifetime — fixture-scoped, not Promise-chain-scoped (rf2-wb06a)
;;
;; `with-canned-eval!` / `with-form-capture!` install a `cljs-eval-value`
;; stub via a bare `set!` and DO NOT restore it in a per-call `.finally`;
;; a `use-fixtures :each :after` step unconditionally restores the
;; pristine original captured at ns-load. A `.finally`-scoped restore
;; fires AFTER cljs.test's `done` has advanced to the next test, which in
;; the full cross-namespace suite let a neighbour's late restore clobber
;; another test's freshly-installed stub mid-eval — surfacing as a probe
;; reaching the real socket fn (`first-positive-probe-runs-one-eval`
;; flaking on `@calls = 0`). The fixture boundary closes that race — the
;; same fix orient_test / invoke_test carry.
;; ---------------------------------------------------------------------------

(def ^:private args-js tu/args->js)
(def ^:private extract-edn tu/extract-edn)
(def ^:private is-error? tu/error?)

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn [] (set! nrepl/cljs-eval-value pristine-eval))})

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
        (is (= #{"event" "sub" "fx" "cofx" "interceptor" "view" "frame"
                 "route" "flow" "head" "error-projector"
                 "resource" "mutation" "resource-scope" "machine"}
               (set (:enum (:kind properties))))
            "kind enum lists every supported kind (incl. the EP-0016 resources kinds + the EP-0022 :interceptor kind)")))))

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
        (is (= #{"event" "sub" "fx" "cofx" "interceptor" "view" "frame"
                 "route" "flow" "head" "error-projector"
                 "resource" "mutation" "resource-scope" "machine"}
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
  (let [;; conn cache makes the probe round-trip skipped after the
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
    ;; Bare set!, NO per-call .finally restore — the :after fixture
    ;; restores the pristine value (rf2-wb06a; see the ns header).
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

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

;; ---------------------------------------------------------------------------
;; Eval-form capture helper — used by the frame-targeting tests below to assert
;; on the exact eval form the tool ships (the frame-targeted vs default shape).
;; ---------------------------------------------------------------------------

(defn- with-form-capture!
  "Stub `nrepl/cljs-eval-value` to CAPTURE the non-probe eval form string
  into `form-atom` and resolve with `canned`. Lets a test assert on the
  exact form the tool ships (the frame-targeted vs default shape)."
  [form-atom canned body-fn]
  (let [stub (fn
               ([_conn _build-id form-str]
                (if (re-find #"__re_frame2_pair_runtime" form-str)
                  (js/Promise.resolve true)
                  (do (reset! form-atom form-str) (js/Promise.resolve canned))))
               ([_conn _build-id form-str _opts]
                (if (re-find #"__re_frame2_pair_runtime" form-str)
                  (js/Promise.resolve true)
                  (do (reset! form-atom form-str) (js/Promise.resolve canned)))))]
    ;; Bare set!, NO per-call .finally restore — the :after fixture
    ;; restores the pristine value (rf2-wb06a; see the ns header).
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

(deftest handler-meta-default-form-is-byte-identical
  (testing "no :frame ⇒ the eval form routes through the runtime registrar-describe (unchanged)"
    (async done
      (let [form (atom nil)
            canned {:ns 'app.x :line 1 :handler-fn-hash 7}]
        (-> (with-form-capture! form canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":counter/inc"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "registrar-describe")
                                   "default path still uses the runtime registrar-describe fn")
                               (is (not (contains? edn :frame))
                                   "default response carries NO :frame key (byte-identical)"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest list-handlers-default-form-is-byte-identical
  (testing "list-handlers with no :frame ⇒ runtime registrar-list (unchanged), no :frame key"
    (async done
      (let [form (atom nil)]
        (-> (with-form-capture! form [:a :b]
              (fn []
                (-> (hm/list-handlers-tool nil (args-js {:kind "event"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "registrar-list"))
                               (is (not (contains? edn :frame))))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

;; ---------------------------------------------------------------------------
;; EP-0016 resource kinds — happy-path EXECUTION coverage (rf2-uydhif).
;;
;; The descriptor tests above pin the resources kinds in the enum vocab.
;; But every happy-path EXECUTION fixture used kind "event" — nothing
;; proved that handler-meta / list-handlers actually accept "resource",
;; "mutation", and "resource-scope", route them through the registrar
;; (NOT the :machine wrapper or a wrong-kind path), and stamp the
;; requested :kind / :id back onto the response. A renamed id, a kind
;; dropped from `registrar-kinds`, or a kind silently mis-routed through
;; `machine-form` would have passed green. These close that gap by
;; driving each EP-0016 kind through the tool with a canned runtime
;; response and asserting (a) the kind is accepted (not :invalid-kind),
;; (b) the emitted form routes through the registrar-describe /
;; registrar-list path (never machine-meta / machines), and (c) the
;; requested kind/id ride back stamped on the response.
;;
;; One focused test per (tool, kind) — the existing file's one-concern
;; style. Each drives a single canned eval; no multi-case reduce/async
;; interplay. The stubs restore via the fixture (rf2-wb06a), not a
;; per-call .finally.
;; ---------------------------------------------------------------------------

(defn- handler-meta-accepts-kind-test
  "Run handler-meta for `kind-str`/`id-str`, asserting the kind is
  accepted, :ok? true, and the requested kind/id ride back stamped
  alongside the canned registrar metadata. `done` is the cljs.test
  async callback; `expect-k`/`expect-i` are the parsed kind/id keywords."
  [kind-str id-str expect-k expect-i done]
  (let [canned {:ns 'app.articles :line 12 :handler-fn-hash 99}]
    (-> (with-canned-eval! canned
          (fn []
            (-> (hm/handler-meta-tool nil (args-js {:kind kind-str :id id-str}))
                (.then (fn [result]
                         (let [edn (extract-edn result)]
                           (is (not (is-error? result))
                               (str kind-str " is accepted, not :invalid-kind"))
                           (is (true? (:ok? edn)))
                           (is (= expect-k (:kind edn))
                               (str "the requested kind " kind-str " rides back stamped"))
                           (is (= expect-i (:id edn))
                               "the requested id rides back stamped")
                           (is (= 'app.articles (:ns edn))
                               "registrar metadata surfaces (routed through registrar-describe)"))
                         (done))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))

(deftest handler-meta-accepts-resource-kind
  (testing "handler-meta accepts kind \"resource\" and stamps kind+id"
    (async done (handler-meta-accepts-kind-test "resource" ":article/by-slug"
                                                :resource :article/by-slug done))))

(deftest handler-meta-accepts-mutation-kind
  (testing "handler-meta accepts kind \"mutation\" and stamps kind+id"
    (async done (handler-meta-accepts-kind-test "mutation" ":article/save"
                                                :mutation :article/save done))))

(deftest handler-meta-accepts-resource-scope-kind
  (testing "handler-meta accepts kind \"resource-scope\" and stamps kind+id"
    (async done (handler-meta-accepts-kind-test "resource-scope" ":realworld/session"
                                                :resource-scope :realworld/session done))))

(defn- handler-meta-routes-through-registrar-test
  "Assert handler-meta for `kind-str` emits a registrar-describe form
  (NOT machine-meta) carrying the kind keyword `kw-str`."
  [kind-str kw-str done]
  (let [form   (atom nil)
        canned {:ns 'app.x :line 1 :handler-fn-hash 7}]
    (-> (with-form-capture! form canned
          (fn []
            (-> (hm/handler-meta-tool nil (args-js {:kind kind-str :id ":x/y"}))
                (.then (fn [_]
                         (is (str/includes? @form "registrar-describe")
                             (str kind-str " routes through registrar-describe"))
                         (is (not (str/includes? @form "machine-meta"))
                             (str kind-str " is NOT mis-routed through the machine wrapper"))
                         (is (str/includes? @form kw-str)
                             (str "the form carries the " kw-str " kind keyword"))
                         (done))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))

(deftest handler-meta-resource-routes-through-registrar
  (testing "kind \"resource\" emits a registrar-describe form, never machine-meta"
    (async done (handler-meta-routes-through-registrar-test "resource" ":resource" done))))

(deftest handler-meta-mutation-routes-through-registrar
  (testing "kind \"mutation\" emits a registrar-describe form, never machine-meta"
    (async done (handler-meta-routes-through-registrar-test "mutation" ":mutation" done))))

(deftest handler-meta-resource-scope-routes-through-registrar
  (testing "kind \"resource-scope\" emits a registrar-describe form, never machine-meta"
    (async done (handler-meta-routes-through-registrar-test "resource-scope" ":resource-scope" done))))

(deftest handler-meta-resource-scope-surfaces-inputs-and-cost
  (testing "a resource-scope drill surfaces the resolver's declared :inputs + :whole-db? cost (EP-0016 inspectability promise)"
    (async done
      ;; The runtime registrar-describe for a :resource-scope returns the
      ;; resolver's static declaration — its :inputs map and the whole-db
      ;; cost flag — with the :resolve fn stripped off the wire.
      (let [canned {:ns 'realworld.scope :line 8 :handler-fn-hash 41
                    :inputs {:username [:db [:auth :user :username]]}
                    :whole-db? false}]
        (-> (with-canned-eval! canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "resource-scope"
                                                        :id   ":realworld/session"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (true? (:ok? edn)))
                               (is (= :resource-scope (:kind edn)))
                               (is (= :realworld/session (:id edn)))
                               (is (= {:username [:db [:auth :user :username]]} (:inputs edn))
                                   "the resolver's declared :inputs ride through")
                               (is (false? (:whole-db? edn))
                                   "the whole-db cost flag rides through"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(defn- list-handlers-accepts-kind-test
  "Run list-handlers for `kind-str` with a canned id vector `ids`,
  asserting the kind is accepted, stamped, and the ids/count ride
  through. A kind dropped from `registrar-kinds` would surface
  :invalid-kind here. `expect-k` is the parsed kind keyword."
  [kind-str expect-k ids done]
  (-> (with-canned-eval! ids
        (fn []
          (-> (hm/list-handlers-tool nil (args-js {:kind kind-str}))
              (.then (fn [result]
                       (let [edn (extract-edn result)]
                         (is (not (is-error? result))
                             (str kind-str " is accepted, not :invalid-kind"))
                         (is (true? (:ok? edn)))
                         (is (= expect-k (:kind edn))
                             (str kind-str " rides back stamped as the kind"))
                         (is (= ids (:ids edn))
                             "the runtime id vector rides through")
                         (is (= (count ids) (:count edn))
                             "count matches the id vector"))
                       (done))))))
      (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done)))))

(deftest list-handlers-accepts-resource-kind
  (testing "list-handlers accepts \"resource\", stamps the kind, returns ids + count"
    (async done (list-handlers-accepts-kind-test
                  "resource" :resource [:article/by-slug :article/list] done))))

(deftest list-handlers-accepts-mutation-kind
  (testing "list-handlers accepts \"mutation\", stamps the kind, returns ids + count"
    (async done (list-handlers-accepts-kind-test
                  "mutation" :mutation [:article/save] done))))

(deftest list-handlers-accepts-resource-scope-kind
  (testing "list-handlers accepts \"resource-scope\", stamps the kind, returns ids + count"
    (async done (list-handlers-accepts-kind-test
                  "resource-scope" :resource-scope [:realworld/session] done))))

(defn- list-handlers-routes-through-registrar-list-test
  "Assert list-handlers for `kind-str` emits a registrar-list form (NOT
  the machines enumerator) carrying the kind keyword `kw-str`."
  [kind-str kw-str done]
  (let [form (atom nil)]
    (-> (with-form-capture! form [:a :b]
          (fn []
            (-> (hm/list-handlers-tool nil (args-js {:kind kind-str}))
                (.then (fn [_]
                         (is (str/includes? @form "registrar-list")
                             (str kind-str " routes through registrar-list"))
                         (is (not (str/includes? @form "re-frame.core/machines"))
                             (str kind-str " is NOT mis-routed through the machines enumerator"))
                         (is (str/includes? @form kw-str)
                             (str "the form carries the " kw-str " kind keyword"))
                         (done))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))

(deftest list-handlers-resource-routes-through-registrar-list
  (testing "kind \"resource\" emits a registrar-list form, never the machines enumerator"
    (async done (list-handlers-routes-through-registrar-list-test "resource" ":resource" done))))

(deftest list-handlers-mutation-routes-through-registrar-list
  (testing "kind \"mutation\" emits a registrar-list form, never the machines enumerator"
    (async done (list-handlers-routes-through-registrar-list-test "mutation" ":mutation" done))))

(deftest list-handlers-resource-scope-routes-through-registrar-list
  (testing "kind \"resource-scope\" emits a registrar-list form, never the machines enumerator"
    (async done (list-handlers-routes-through-registrar-list-test "resource-scope" ":resource-scope" done))))

;; ---------------------------------------------------------------------------
;; Frame-targeting — the EP-0023 forward direction (rf2-srobm0).
;;
;; The OPTIONAL `:frame` arg re-keys the lookup through THAT frame's running
;; image generation — routing through the per-frame runtime fns
;; `frame-registrar-describe` / `frame-registrar-list` (which consume the
;; PUBLIC `(rf/handler-meta {:frame f …})` / `(rf/handler-ids {:frame f …})`
;; facade reads). ABSENT ⇒ the byte-identical default path. PRESENT ⇒ the
;; frame-id threaded into the per-frame form + stamped on the response.
;; `:frame` + machine is rejected (machines are not in the resolver).
;; ---------------------------------------------------------------------------

(deftest handler-meta-frame-routes-through-frame-registrar-describe
  (testing ":frame ⇒ the form uses the per-frame frame-registrar-describe runtime fn and stamps :frame"
    (async done
      (let [form (atom nil)
            canned {:ns 'blue.core :line 1 :handler-fn-hash 7
                    :rf.image/coordinate {:source :registered :ns "blue.core"}}]
        (-> (with-form-capture! form canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind  "event"
                                                        :id    ":counter/inc"
                                                        :frame ":blue/main"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "frame-registrar-describe")
                                   "frame path routes through the per-frame runtime fn")
                               (is (str/includes? @form ":blue/main")
                                   "the frame-id is threaded into the form")
                               (is (not (str/includes? @form "registrar-describe)"))
                                   "NOT the default registrar-describe path")
                               (is (true? (:ok? edn)))
                               (is (= :blue/main (:frame edn))
                                   "the resolved frame is stamped on the response")
                               (is (= {:source :registered :ns "blue.core"}
                                      (:rf.image/coordinate edn))
                                   "the provenance coordinate rides through"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest handler-meta-default-path-has-no-frame
  (testing "no :frame ⇒ the default registrar-describe path, no :frame key on the response"
    (async done
      (let [form (atom nil)
            canned {:ns 'app.x :line 1 :handler-fn-hash 7}]
        (-> (with-form-capture! form canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "event" :id ":counter/inc"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (not (str/includes? @form "frame-registrar-describe")))
                               (is (not (contains? edn :frame))
                                   "default-path response carries NO :frame key (byte-identical)"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest handler-meta-rejects-frame-with-machine
  (testing ":frame + kind=machine ⇒ structured :frame-unsupported-for-machine"
    (async done
      (-> (hm/handler-meta-tool nil (args-js {:kind  "machine"
                                              :id    ":auth/session"
                                              :frame ":blue/main"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :frame-unsupported-for-machine (:reason edn)))
                     (is (= :blue/main (:frame edn))))
                   (done)))))))

(deftest list-handlers-frame-routes-through-frame-registrar-list
  (testing "list-handlers :frame ⇒ the per-frame frame-registrar-list runtime fn + stamps :frame"
    (async done
      (let [form (atom nil)]
        (-> (with-form-capture! form [:counter/inc]
              (fn []
                (-> (hm/list-handlers-tool nil (args-js {:kind "event" :frame ":blue/main"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "frame-registrar-list")
                                   "frame path routes through the per-frame runtime fn")
                               (is (str/includes? @form ":blue/main"))
                               (is (true? (:ok? edn)))
                               (is (= :blue/main (:frame edn))
                                   "the resolved frame is stamped on the response"))
                             (done))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) (done))))))))

(deftest list-handlers-rejects-frame-with-machine
  (testing "list-handlers :frame + kind=machine ⇒ :frame-unsupported-for-machine"
    (async done
      (-> (hm/list-handlers-tool nil (args-js {:kind "machine" :frame ":blue/main"}))
          (.then (fn [result]
                   (is (is-error? result))
                   (let [edn (extract-edn result)]
                     (is (= :frame-unsupported-for-machine (:reason edn))))
                   (done)))))))

(deftest frame-arg-is-optional-in-descriptors
  (testing "the :frame property is present but NOT required on both tools"
    (doseq [tool-name ["handler-meta" "list-handlers"]]
      (let [{:keys [required properties]} (:inputSchema (find-descriptor tool-name))]
        (is (contains? properties :frame)
            (str tool-name " exposes the optional :frame property"))
        (is (not (contains? (set required) "frame"))
            (str tool-name " does not require :frame (default registrar is the common case)"))))))
