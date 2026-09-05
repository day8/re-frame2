(ns re-frame2-pair-mcp.handler-meta-test
  "Unit tests for the `handler-meta` + `list-handlers` MCP tools.

  Both tools build a CLJS form that calls into the preloaded runtime,
  and into NOTHING ELSE: `re-frame2-pair.runtime/registrar-describe` /
  `registrar-list` for the fourteen registrar kinds,
  `frame-registrar-describe` / `frame-registrar-list` for the
  frame-targeted reads, and `machine-describe` / `machines-list` for the
  virtual `:machine` kind. Live end-to-end coverage runs against a
  shadow-cljs runtime; these tests pin:

    1. The descriptor wire-up — both tools surface on `tool-descriptors`
       and `tool-descriptors-js` with the right shape (required args,
       enum vocab, typicalTokens).
    2. The kind / id parsers — recognised kinds map to keywords;
       unknown / malformed values are rejected with structured envelopes;
       EDN-encoded ids round-trip cleanly.
    3. The form composition — given a valid (kind, id) pair the right
       runtime fn is called (`registrar-describe` for the registrar
       kinds; `machine-describe` for `:machine`).
    4. Error envelopes — missing / invalid kind / id arguments surface
       structured `:reason` slots an agent can read.
    5. THE RUNTIME DOOR (rf2-kuky.29) — every symbol an emitted form
       names is a public top-level `defn` in the preload's own source,
       and no emitted form names a framework var at all.

  ## Why (5) reads another artefact's source

  The coupling between these tools and the runtime they call is a
  STRING interpolated into CLJS source and shipped over nREPL. There is
  no `:require`, no classpath edge, and therefore no compiler error and
  no static check in this build that can see it. Points (1)–(4) are the
  emitter tested against itself: they stay green while the form names a
  var that does not exist anywhere, which is exactly what happened — the
  `:machine` branches named a `machines` and a `machine-meta` var on the
  FACADE, where the machine query surface has never lived (it is
  `re-frame.machines`, per spec/API.md's front-porch boundary), and both
  `:machine` reads returned an eval error against every running app
  while this suite passed. Point (5) is the both-sides witness that
  closes it, in the shape `hicasso_wire_test.cljs` established for the
  same class of string coupling."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [applied-science.js-interop :as j]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.handler-meta :as hm]))

;; ---------------------------------------------------------------------------
;; Helpers.
;;
;; The wire-envelope extractors live in `test-utils` (shared across
;; suites). `args-js` is aliased to the shared `args->js`.
;;
;; ## Stub lifetime — fixture-scoped, not Promise-chain-scoped
;;
;; `with-canned-eval!` / `with-form-capture!` install a `cljs-eval-value`
;; stub via a bare `set!` and intentionally do NOT restore it in a
;; per-call `.finally`; a `use-fixtures :each :after` step unconditionally
;; restores the pristine original captured at ns-load. A `.finally`-scoped
;; restore would fire AFTER cljs.test's `done` has advanced to the next
;; test, which in the full cross-namespace suite would let a neighbour's
;; late restore clobber another test's freshly-installed stub mid-eval —
;; surfacing as a probe reaching the real socket fn. The fixture boundary
;; closes that race; orient_test / invoke_test follow the same pattern.
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
;; Each test wraps its `.then` assertions in `(async done ...)` so the
;; assertions actually run before the test completes — a synchronous
;; deftest body would return before the Promise resolved, letting
;; cljs.test record the test as passed with ZERO assertions. The
;; error-envelope contract for both tools (a flipped `:invalid-kind`
;; reason or an NPE on the nil conn) is therefore genuinely exercised,
;; mirroring `dispatch_test` / `probe_test`.
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
;; Regression — handler-meta returns :ok? true with the real data as
;; top-level keys, never :ok? false :reason :unexpected-shape with the
;; actual map embedded as an EDN string.
;;
;; The hazard: a runtime meta map containing `:handler-fn <Function>`
;; renders via `pr-str` as `#object[Function ...]`, which nrepl's
;; `read-edn-safe` cannot parse back; a naive tool body would then fall
;; into `(not (map? v))` and stuff the raw string under `:value`. Two
;; complementary defences guard against that:
;;
;;   - Runtime side (skills/re-frame2-pair/preload/...): dissocs
;;     :handler-fn before returning. Pinned structurally by the
;;     babashka test `registrar_describe_test.clj`.
;;
;;   - MCP tool side (here): defensive re-parse so a runtime slip
;;     emitting a stringified map still surfaces as :ok? true.
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
    ;; restores the pristine value (see the ns header).
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
                                   "the bug shape stuffed the map under :value as a string — must not recur")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest handler-meta-unserializable-surfaces-structured
  (testing "rf2-qobqy: a runtime meta map that can't round-trip as EDN now rides back as a tagged :unserializable envelope — NOT a meta map smuggled as a STRING"
    ;; The typed result codec means the RUNTIME classifies an
    ;; unserializable meta map (a `#object` Function slot, a `#js {…}`)
    ;; into a tagged `:rf.mcp/result :unserializable` envelope with a
    ;; `:preview`. The tool surfaces the STRUCTURED error stamped with
    ;; the requested kind/id — never the meta-map-as-string a
    ;; :unexpected-shape path would carry.
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
                                   "the preview shows WHAT couldn't serialize")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                               (is (= :not-registered (:reason edn)))))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                           ;; rf2-acckgr regression: this tool-built
                           ;; :unexpected-shape map lacked the codec's
                           ;; ::codec-error meta, so it silently rode
                           ;; back as ok-text (isError: false) despite
                           ;; carrying :ok? false — masking the defect
                           ;; as a success. Sibling test
                           ;; `handler-meta-unserializable-surfaces-structured`
                           ;; already asserts this; it was missing here.
                           (is (is-error? result)
                               "an :unexpected-shape defect MUST be isError: true")
                           (let [edn (extract-edn result)]
                             (is (false? (:ok? edn)))
                             (is (= :unexpected-shape (:reason edn)))
                             (is (= 42 (:value edn))
                                 "the offending value rides on :value for forensics")))))))
          (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
          (.then (fn [_] (done)))))))

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
    ;; restores the pristine value (see the ns header).
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
                                   "default response carries NO :frame key (byte-identical)")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                               (is (not (contains? edn :frame)))))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; EP-0016 resource kinds — happy-path EXECUTION coverage.
;;
;; The descriptor tests above pin the resources kinds in the enum vocab.
;; These tests prove handler-meta / list-handlers actually accept
;; "resource", "mutation", and "resource-scope", route them through the
;; registrar (NOT the :machine wrapper or a wrong-kind path), and stamp
;; the requested :kind / :id back onto the response. A renamed id, a kind
;; dropped from `registrar-kinds`, or a kind silently mis-routed through
;; `machine-form` would fail here. Each EP-0016 kind is driven through
;; the tool with a canned runtime response, asserting (a) the kind is
;; accepted (not :invalid-kind), (b) the emitted form routes through the
;; registrar-describe / registrar-list path (never machine-describe /
;; machines-list), and (c) the requested kind/id ride back stamped on the
;; response.
;;
;; One focused test per (tool, kind) — the file's one-concern style.
;; Each drives a single canned eval; no multi-case reduce/async
;; interplay. The stubs restore via the fixture, not a per-call .finally.
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
                               "registrar metadata surfaces (routed through registrar-describe)")))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))

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
  (NOT machine-describe) carrying the kind keyword `kw-str`."
  [kind-str kw-str done]
  (let [form   (atom nil)
        canned {:ns 'app.x :line 1 :handler-fn-hash 7}]
    (-> (with-form-capture! form canned
          (fn []
            (-> (hm/handler-meta-tool nil (args-js {:kind kind-str :id ":x/y"}))
                (.then (fn [_]
                         (is (str/includes? @form "registrar-describe")
                             (str kind-str " routes through registrar-describe"))
                         (is (not (str/includes? @form "machine-describe"))
                             (str kind-str " is NOT mis-routed through the machine door"))
                         (is (str/includes? @form kw-str)
                             (str "the form carries the " kw-str " kind keyword")))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))

(deftest handler-meta-resource-routes-through-registrar
  (testing "kind \"resource\" emits a registrar-describe form, never machine-describe"
    (async done (handler-meta-routes-through-registrar-test "resource" ":resource" done))))

(deftest handler-meta-mutation-routes-through-registrar
  (testing "kind \"mutation\" emits a registrar-describe form, never machine-describe"
    (async done (handler-meta-routes-through-registrar-test "mutation" ":mutation" done))))

(deftest handler-meta-resource-scope-routes-through-registrar
  (testing "kind \"resource-scope\" emits a registrar-describe form, never machine-describe"
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
                                   "the whole-db cost flag rides through")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                             "count matches the id vector")))))))
      (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
      (.then (fn [_] (done)))))

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
                         (is (not (str/includes? @form "machines-list"))
                             (str kind-str " is NOT mis-routed through the machines enumerator"))
                         (is (str/includes? @form kw-str)
                             (str "the form carries the " kw-str " kind keyword")))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))

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
;; Frame-targeting — the EP-0023 forward direction.
;;
;; The OPTIONAL `:frame` arg re-keys the lookup through THAT frame's running
;; image generation — routing through the per-frame runtime fns
;; `frame-registrar-describe` / `frame-registrar-list` (which consume the
;; PUBLIC `(rf/handler-meta {:frame f …})` / `(rf/registrations {:frame f …})`
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
                                   "the provenance coordinate rides through")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                                   "default-path response carries NO :frame key (byte-identical)")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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
                                   "the resolved frame is stamped on the response")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

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

;; ---------------------------------------------------------------------------
;; The :machine kind — POSITIVE coverage for both tools (rf2-kuky.29).
;;
;; Every test above the machine kind was NEGATIVE ("this other kind is not
;; mis-routed through the machine branch"), which is why the machine branch
;; could name two vars that do not exist for as long as it did: nothing ever
;; asserted what it DOES emit. These pin the door it routes through, the id it
;; threads, and the miss envelope it hands back.
;; ---------------------------------------------------------------------------

(deftest handler-meta-machine-routes-through-the-runtime-door
  (testing "kind \"machine\" emits (re-frame2-pair.runtime/machine-describe id) and names no framework var"
    (async done
      (let [form   (atom nil)
            canned {:initial :idle :states {:idle {}} :guards {:can? :rf/fn}}]
        (-> (with-form-capture! form canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "machine"
                                                        :id   ":auth/session"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "re-frame2-pair.runtime/machine-describe")
                                   "the machine drill routes through the preload's machine door")
                               (is (str/includes? @form ":auth/session")
                                   "the requested id is threaded into the form")
                               (is (not (str/includes? @form "re-frame.core/"))
                                   "no facade var is named on this side of the wire")
                               (is (not (str/includes? @form "re-frame.machines/"))
                                   "no artefact var either — the preload is the only place one is spelled")
                               (is (true? (:ok? edn)))
                               (is (= :machine (:kind edn)))
                               (is (= :auth/session (:id edn)))
                               (is (= {:can? :rf/fn} (:guards edn))
                                   "the door's stripped :guards ride through as readable EDN")))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest handler-meta-machine-miss-is-the-uniform-not-registered-envelope
  (testing "the door's :not-a-machine is renamed to the one miss vocabulary the tool speaks"
    (async done
      ;; What `machine-describe` actually returns on a miss — the preload's
      ;; own vocabulary, which `ops.md` documents and other consumers read.
      (let [canned {:ok? false :reason :not-a-machine :id :nope/nothing}]
        (-> (with-canned-eval! canned
              (fn []
                (-> (hm/handler-meta-tool nil (args-js {:kind "machine"
                                                        :id   ":nope/nothing"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (false? (:ok? edn)))
                               (is (= :not-registered (:reason edn))
                                   "an agent branches on ONE miss reason across every kind")
                               (is (= :machine (:kind edn)))
                               (is (= :nope/nothing (:id edn)))))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

(deftest list-handlers-machine-routes-through-the-runtime-door
  (testing "kind \"machine\" emits (re-frame2-pair.runtime/machines-list) and names no framework var"
    (async done
      (let [form (atom nil)
            ids  [:auth/session :cart/checkout]]
        (-> (with-form-capture! form ids
              (fn []
                (-> (hm/list-handlers-tool nil (args-js {:kind "machine"}))
                    (.then (fn [result]
                             (let [edn (extract-edn result)]
                               (is (str/includes? @form "re-frame2-pair.runtime/machines-list")
                                   "the machine enumeration routes through the preload's list door")
                               (is (not (str/includes? @form "re-frame.core/"))
                                   "no facade var is named on this side of the wire")
                               (is (not (str/includes? @form "re-frame.machines/"))
                                   "no artefact var either")
                               (is (true? (:ok? edn)))
                               (is (= :machine (:kind edn)))
                               (is (= ids (:ids edn)))
                               (is (= 2 (:count edn)))))))))
            (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
            (.then (fn [_] (done))))))))

;; ---------------------------------------------------------------------------
;; THE RUNTIME DOOR — the both-sides witness (rf2-kuky.29).
;;
;; See the ns docstring for why this reads another artefact's source. In one
;; line: the coupling is a string, so nothing in this build can see it, and a
;; suite that only reads the emitter back to itself stays green over a var that
;; exists nowhere. `hicasso_wire_test.cljs` established the shape for the same
;; class of coupling; this is its registry-introspection twin.
;;
;; The symbols are extracted from ACTUAL EMITTED FORMS rather than from a
;; hand-written vector — a vector would prove only that two lists agree, and it
;; is the string on the wire that has to resolve.
;; ---------------------------------------------------------------------------

(def ^:private fs (js/require "fs"))
(def ^:private path (js/require "path"))

(defn- repo-root
  "Walk upward from the test process's cwd to the repository root — the first
  directory holding both this artefact and the preload the emitted forms call.
  Robust against the runner's working directory (`tools/re-frame2-pair-mcp` vs
  repo root)."
  []
  (loop [d (.cwd js/process)]
    (cond
      (and (.existsSync fs (.join path d "tools/re-frame2-pair-mcp/src"))
           (.existsSync fs (.join path d "skills/re-frame2-pair/preload")))
      d

      (= d (.dirname path d))
      (throw (ex-info (str "Could not locate the repository root from cwd — the runtime-door "
                           "witness needs both tools/re-frame2-pair-mcp/src and "
                           "skills/re-frame2-pair/preload to compare the two sides.")
                      {:cwd (.cwd js/process)}))

      :else (recur (.dirname path d)))))

(def ^:private preload-src
  ;; CR stripped: the `\n(defn ` anchor below is line-start-anchored, and a
  ;; CRLF checkout would otherwise fail every assertion for a reason that has
  ;; nothing to do with the door.
  (delay
    (let [rel  "skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs"
          full (.join path (repo-root) rel)]
      (when-not (.existsSync fs full)
        (throw (ex-info (str "the runtime preload is missing: " rel
                             " — every form these tools ship targets it by string, so its "
                             "absence is the failure this witness exists to report.")
                        {:path full})))
      (str/replace (.toString (.readFileSync fs full)) "\r" ""))))

(def ^:private door-cases
  "Every branch of the two tools' form builders that reaches a runtime call.
  `:machine` with a `:frame` is refused before a form is built (machines are
  not in the image generation resolver), so it is not a case here.

  `:canned` is the value the stubbed eval resolves with — a metadata map for
  `handler-meta`, an id vector for `list-handlers`, so each tool's own
  post-processing runs rather than throwing on the way to the assertion."
  [{:tool hm/handler-meta-tool  :args {:kind "event"   :id ":a/b"}                      :canned {:ns 'a.b :line 1}}
   {:tool hm/handler-meta-tool  :args {:kind "event"   :id ":a/b" :frame ":blue/main"}  :canned {:ns 'a.b :line 1}}
   {:tool hm/handler-meta-tool  :args {:kind "machine" :id ":auth/session"}             :canned {:initial :idle}}
   {:tool hm/list-handlers-tool :args {:kind "event"}                                   :canned [:a/b]}
   {:tool hm/list-handlers-tool :args {:kind "event"   :frame ":blue/main"}             :canned [:a/b]}
   {:tool hm/list-handlers-tool :args {:kind "machine"}                                 :canned [:auth/session]}])

(defn- capture-emitted-forms
  "Drive every `door-cases` entry through its tool with a capturing eval stub,
  resolving with the vector of form strings the tools actually shipped. Serial
  rather than parallel: the stub is installed by a bare `set!` on one var, so
  two in flight would capture each other's forms."
  []
  (reduce
    (fn [p {:keys [tool args canned]}]
      (.then p (fn [acc]
                 (let [form (atom nil)]
                   (-> (with-form-capture! form canned
                         (fn [] (tool nil (args-js args))))
                       (.then (fn [_] (conj acc @form))))))))
    (js/Promise.resolve [])
    door-cases))

(defn- runtime-symbols
  "Every fn name a form resolves off the runtime preload, as a set. Parsed out
  of the emitted source rather than read off the DSL, because the source string
  is what crosses the wire."
  [form]
  (into #{}
        (map second)
        (re-seq (re-pattern (str "\\(" (str/replace ef/runtime-ns "." "\\.")
                                 "/([^\\s)]+)"))
                form)))

(deftest every-emitted-runtime-symbol-is-published-by-the-preload
  (async done
    (-> (capture-emitted-forms)
        (.then (fn [forms]
                 (let [src  @preload-src
                       syms (into #{} (mapcat runtime-symbols) forms)]
                   (is (= (count door-cases) (count forms))
                       "every door case emitted a form to check")
                   (is (contains? syms "machine-describe")
                       "the machine drill is among the captured symbols")
                   (is (contains? syms "machines-list")
                       "the machine enumeration is among the captured symbols")
                   (doseq [s (sort syms)]
                     ;; A PUBLIC `defn` at column 0. `defn-` would not match,
                     ;; and must not: a private fn is unreachable from an eval
                     ;; form even though the name is spelled identically.
                     (is (str/includes? src (str "\n(defn " s "\n"))
                         (str "re-frame2-pair.runtime must publish " s
                              " — an emitted form calls it by name across a process "
                              "boundary, so a rename on the preload is a runtime failure "
                              "here and nowhere else"))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))

(deftest no-emitted-form-names-a-framework-var
  ;; The other half of the contract, and the one rf2-kuky.29 broke: the preload
  ;; is the SINGLE place a framework symbol is spelled. A form that reaches
  ;; past it compiles, passes every emitter test, and fails only in someone
  ;; else's process — which is exactly what two hand-built `:machine` strings
  ;; did for the whole life of that kind.
  (async done
    (-> (capture-emitted-forms)
        (.then (fn [forms]
                 (doseq [form forms]
                   (doseq [ns-prefix ["re-frame.core/" "re-frame.machines/"
                                      "re-frame.schemas/" "re-frame.routing/"
                                      "re-frame.flows/"]]
                     (is (not (str/includes? form ns-prefix))
                         (str "an emitted form names " ns-prefix
                              " directly — route it through the preload instead: "
                              form))))))
        (.catch (fn [e] (is false (str "rejected: " (.-message e))) nil))
        (.then (fn [_] (done))))))
