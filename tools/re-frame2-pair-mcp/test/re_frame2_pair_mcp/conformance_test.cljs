(ns re-frame2-pair-mcp.conformance-test
  "The pair-mcp wire corpus: each fixture drives one MCP tool through
  `tools/invoke` with canned runtime answers and checks the envelope it
  produces. Each unit suite knows one tool's vocabulary; this is the
  cross-tool ratchet that catches a renamed `:reason` or a flipped result
  shape on the wire an agent host consumes. It pins wire SHAPE; the
  framework's own corpora pin runtime semantics.

  `nrepl/cljs-eval-value` is stubbed by `set!` — `with-redefs` would
  restore before the Promise chain settles — and answers each emitted form
  from the fixture's `:fixture/eval-script`."
  (:require [cljs.test :refer-macros [deftest is async]]
            [clojure.string :as str]
            [clojure.set :as set]
            [re-frame2-pair-mcp.cache :as cache]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools :as tools]
            [re-frame2-pair-mcp.tools.eval-cljs :as eval-cljs]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.registry :as registry]
            [re-frame2-pair-mcp.tools.writes :as writes]))

(defn- run-eval-script
  "The canned answer for `form-str`: that of the first `[match canned]`
  entry whose `match` is a substring of it or `:default`. No match throws
  — the script does not cover a form the tool emits."
  [eval-script form-str]
  (loop [entries eval-script]
    (when (empty? entries)
      (throw (ex-info "eval-script did not match emitted form"
                      {:form    form-str
                       :script  (mapv first eval-script)})))
    (let [[match canned] (first entries)]
      (cond
        (= match :default)            canned
        (and (string? match)
             (str/includes? form-str match))
        canned
        :else (recur (rest entries))))))

(defn- with-stubbed-eval!
  "Stub `nrepl/cljs-eval-value` to answer from `eval-script`, recording
  each form it is sent in `forms-seen`, and `nrepl/jvm-eval` to report a
  reachable JVM running `:app`, so a runtime probe scripted `false` lands
  on `:runtime-loaded-but-preload-missing`. Runs `body-fn` and restores
  both in `.finally`."
  [eval-script forms-seen body-fn]
  (let [orig-cljs  nrepl/cljs-eval-value
        orig-jvm   nrepl/jvm-eval
        cljs-stub  (fn
                     ([_conn _build-id form-str]
                      (swap! forms-seen conj form-str)
                      (js/Promise.resolve (run-eval-script eval-script form-str)))
                     ([_conn _build-id form-str _opts]
                      (swap! forms-seen conj form-str)
                      (js/Promise.resolve (run-eval-script eval-script form-str))))
        jvm-script [["active-builds" {:value "[:app]"}]
                    [:default        {:value "1"}]]
        jvm-stub   (fn
                     ([_conn form-str]
                      (js/Promise.resolve (run-eval-script jvm-script form-str)))
                     ([_conn form-str _opts]
                      (js/Promise.resolve (run-eval-script jvm-script form-str))))]
    (set! nrepl/cljs-eval-value cljs-stub)
    (set! nrepl/jvm-eval         jvm-stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn []
                    (tu/restore-eval! cljs-stub orig-cljs)
                    (tu/restore-jvm-eval! jvm-stub orig-jvm))))))

(defn- submap?
  "True if every k/v in `expected` appears in `actual` with a matching
  value. Recurses into nested maps."
  [expected actual]
  (cond
    (and (map? expected) (map? actual))
    (every? (fn [[k v]]
              (let [a (get actual k)]
                (if (and (map? v) (map? a))
                  (submap? v a)
                  (= v a))))
            expected)
    :else (= expected actual)))

(def ^:private expect-keys #{:isError? :reason :edn-submap :edn-contains-keys})

(defn- check-fixture-result
  "`[passed? failure-msg]` for `result` against `:fixture/expect`, whose
  keys (any subset of `expect-keys`) are `:isError?`, `:reason`,
  `:edn-submap` (a recursive submap of the parsed EDN) and
  `:edn-contains-keys` (required top-level keys). An unknown key fails
  rather than going unchecked."
  [result expect]
  (let [edn     (tu/extract-edn result)
        unknown (remove expect-keys (keys expect))
        missing (remove #(contains? edn %) (:edn-contains-keys expect))]
    (cond
      (seq unknown)
      [false (str "unknown :fixture/expect key(s): " (pr-str unknown))]

      (and (contains? expect :isError?)
           (not= (:isError? expect) (tu/error? result)))
      [false (str "isError mismatch — expected " (:isError? expect)
                  ", actual " (tu/error? result))]

      (and (contains? expect :reason)
           (not= (:reason expect) (:reason edn)))
      [false (str ":reason mismatch — expected " (:reason expect)
                  ", actual " (:reason edn))]

      (and (contains? expect :edn-submap)
           (not (submap? (:edn-submap expect) edn)))
      [false (str ":edn-submap mismatch — expected " (pr-str (:edn-submap expect))
                  ", actual " (pr-str edn))]

      (seq missing)
      [false (str ":edn-contains-keys missing: " (pr-str missing))]

      :else [true nil])))

(def corpus
  "One map per fixture: `:fixture/id`, `:fixture/doc`, `:fixture/tool`,
  `:fixture/args` (a CLJS map, converted to the `#js` object tools read),
  `:fixture/eval-script` (see `run-eval-script`) and `:fixture/expect` (see
  `check-fixture-result`), plus the optional launch gates and emitted-form
  substrings `run-one-fixture` reads."
  [;; ---------- discover-app ----------------------------------------------
   ;; discover-app routes the runtime health map through several
   ;; precondition gates (`:debug-enabled?`, `:frames`,
   ;; `:ambiguous-frame?`, `:coord-annotation-enabled?`). The happy
   ;; path requires all of them to land in the documented shape, so
   ;; the canned health here mirrors the actual runtime contract.
   {:fixture/id    :discover-app/happy
    :fixture/doc   "discover-app on a healthy runtime adds :ok? true + :build-id to the health map."
    :fixture/tool  "discover-app"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["health"                    {:ok?                       true
                                   :debug-enabled?            true
                                   :coord-annotation-enabled? true
                                   :frames                    [:rf/default]
                                   :version                   "test"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :build-id :app}}}

   {:fixture/id    :discover-app/preload-missing
    :fixture/doc   "discover-app surfaces :runtime-loaded-but-preload-missing when the marker is absent (the specific rung of the diagnostic ladder)."
    :fixture/tool  "discover-app"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; A `:port` that maps to no build short-circuits BEFORE any
   ;; cljs-eval-value round-trip: `resolve-build-by-port` reads the
   ;; `:dev-http` map via `jvm-eval`; the corpus's default JVM stub answers
   ;; the non-`active-builds` lookup form with `{:value "1"}`, which
   ;; `read-string`s to `1` — not a keyword build-id — so the resolver
   ;; returns nil and discover-app emits `:port-unresolved`. This fixture
   ;; pins that it rides as an `isError` result (a known-tool `:ok? false`
   ;; failure), NOT a success-shaped ok-text envelope that the response
   ;; cache could retain and mask a later valid port→build mapping.
   {:fixture/id    :discover-app/port-unresolved
    :fixture/doc   "discover-app with a :port that maps to no build rides as isError carrying :port-unresolved (every :ok? false is isError; never a cacheable ok-text)."
    :fixture/tool  "discover-app"
    :fixture/args  {:port 9999}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :port-unresolved :port 9999}}}

   ;; ---------- eval-cljs --------------------------------------------------
   ;; The launch-flag gate ships DEFAULT-ON in published builds — the
   ;; operator passes `--no-eval` to opt OUT. Fixtures that drive the
   ;; post-gate logical paths (`:happy`, `:missing-form`,
   ;; `:no-runtime-for-build`) rely on the default ON state; the
   ;; `:disabled-via-no-eval` fixture pins the opt-out envelope via
   ;; `:fixture/eval-allowed? false`.
   {:fixture/id    :eval-cljs/disabled-via-no-eval
    :fixture/doc   "eval-cljs with --no-eval (gate flipped OFF) returns :rf.error/eval-cljs-disabled."
    :fixture/tool  "eval-cljs"
    :fixture/eval-allowed? false
    :fixture/args  {:form "(+ 1 2)"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/eval-cljs-disabled}}

   {:fixture/id    :eval-cljs/happy
    :fixture/doc   "eval-cljs (gate at default ON) with explicit :build returns {:ok? true :value v} on a successful runtime eval."
    :fixture/tool  "eval-cljs"
    ;; Explicit :build short-circuits the auto-detect (which would
    ;; jvm-eval `active-builds` — not stubbed by this cljs-eval-only
    ;; harness). The runtime-sentinel preflight still runs.
    :fixture/args  {:form "(+ 1 2)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(+ 1 2)"                   3]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; The typed result envelope distinguishes a GENUINE nil, an
   ;; eval-error, and an unserializable value rather than collapsing all
   ;; three to a bare null. The runtime classifies the result into a
   ;; tagged `:rf.mcp/result` map; the server projects it.
   {:fixture/id    :eval-cljs/typed-nil
    :fixture/doc   "eval-cljs of a form that genuinely returns nil rides back as :ok? true :value nil — a tagged :rf.mcp/result :nil, NOT a collapsed null."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(get {} :missing)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(get {} :missing)"         {:rf.mcp/result :nil}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value nil :build :app}}}

   {:fixture/id    :eval-cljs/typed-eval-error
    :fixture/doc   "eval-cljs of a form that throws (e.g. an unresolved symbol) surfaces a structured :rf.error/eval-cljs-threw, NOT a silent nil."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(undefined-symbol)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(undefined-symbol)"        {:rf.mcp/result :eval-error
                                   :reason :rf.error/eval-cljs-threw
                                   :ex "#error {:message \"undefined-symbol is not defined\"}"
                                   :message "undefined-symbol is not defined"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/eval-cljs-threw :build :app}}}

   {:fixture/id    :eval-cljs/typed-unserializable
    :fixture/doc   "eval-cljs of a form returning a #object/#js/Function surfaces :rf.error/unserializable with a :preview, NOT a collapsed null."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "js/console" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["js/console"                {:rf.mcp/result :unserializable
                                   :type "object"
                                   :preview "#object[Object [object console]]"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/unserializable
                  :type "object" :build :app}}}

   {:fixture/id    :eval-cljs/no-runtime-for-build
    :fixture/doc   "eval-cljs against a build with no live runtime fails loud (:no-runtime-for-build), never :ok? true :value nil. A preflight rejection routes through probe/err->result, so it surfaces as isError: true per the known-tool-failure contract (API §Result shape)."
    :fixture/tool  "eval-cljs"
    ;; Explicit :build so the path is deterministic against the
    ;; cljs-eval-only stub; sentinel probe returns false → fail loud.
    :fixture/args  {:form "(count [1 2 3])" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-runtime-for-build}}}

   {:fixture/id    :eval-cljs/missing-form
    :fixture/doc   "eval-cljs without :form surfaces :missing-form."
    :fixture/tool  "eval-cljs"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-form}}

   ;; ---- await opt-in ----
   ;; :await false (the default) sends the form verbatim — no await
   ;; wrapper. Pin that via :fixture/eval-form-must-not-contain.
   {:fixture/id    :eval-cljs/await-default-off-no-wrap
    :fixture/doc   "eval-cljs without :await sends the form verbatim — no await wrapper, no mailbox slot (default :await false)."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(+ 1 2)" :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["(+ 1 2)"                   3]
     [:default                    nil]]
    :fixture/eval-form-must-not-contain ["__rf2pair_await__"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; Await + non-thenable: the wrapper's synchronous arm reports
   ;; `:rf.mcp/await-direct v`; the server short-circuits with v as
   ;; the :value. Same shape as the no-await path.
   {:fixture/id    :eval-cljs/await-direct-passthrough
    :fixture/doc   "eval-cljs :await true on a non-thenable form returns the value via the wrapper's synchronous fast path."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(+ 1 2)" :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"           true]
     ;; The await wrapper for a non-thenable returns the
     ;; `:rf.mcp/await-direct` sentinel; the server pulls v out of it.
     [":rf.mcp/await-mailbox"  {:rf.mcp/await-direct 3}]
     [:default                  nil]]
    :fixture/eval-form-must-contain ["__rf2pair_await__"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 3 :build :app}}}

   ;; Await + resolved thenable: wrapper returns the mailbox sentinel;
   ;; the server polls the read-mailbox form which resolves immediately.
   ;;
   ;; Substring-match ordering: the wrap form contains
   ;; `:rf.mcp/await-mailbox` (the keyword it emits as part of the
   ;; sentinel literal) AND `\"resolved\"` (in the `.then` handler that
   ;; writes the resolved status into the mailbox). The mailbox-read
   ;; form contains `cljs.reader/read-string` (the EDN re-read of the
   ;; mailbox value) — uniquely identifying it without false-matching
   ;; the wrap form. The script orders wrap-match first to keep
   ;; first-match-wins deterministic.
   {:fixture/id    :eval-cljs/await-resolved
    :fixture/doc   "eval-cljs :await true on a thenable that resolves returns {:ok? true :value <resolved>} after polling the mailbox."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(-> (js/Promise.resolve {:hello \"world\"}) (.then identity))"
                    :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"   true]
     [":rf.mcp/await-mailbox"      {:rf.mcp/await-mailbox "fixture-mbx"}]
     ["cljs.reader/read-string"    {:status :resolved :value {:hello "world"}}]
     [:default                     nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value {:hello "world"} :build :app}}}

   ;; Await + rejected thenable: the mailbox-read returns :rejected;
   ;; the server surfaces :rf.error/eval-cljs-rejected. This is a
   ;; known-tool failure and MUST be isError: true per spec/003's
   ;; universal isError rule — riding back as ok-text (isError: false)
   ;; would mask the rejection as a success.
   {:fixture/id    :eval-cljs/await-rejected
    :fixture/doc   "eval-cljs :await true on a thenable that rejects returns {:ok? false :reason :rf.error/eval-cljs-rejected :rejection <pr-str>} as an isError envelope."
    :fixture/tool  "eval-cljs"
    :fixture/args  {:form "(js/Promise.reject (ex-info \"nope\" {}))"
                    :await true :build "app"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"   true]
     [":rf.mcp/await-mailbox"      {:rf.mcp/await-mailbox "fixture-mbx"}]
     ["cljs.reader/read-string"    {:status :rejected :rejection "#error {:message \"nope\"}"}]
     [:default                     nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false
                  :reason :rf.error/eval-cljs-rejected
                  :rejection "#error {:message \"nope\"}"
                  :build :app}}}

   ;; ---------- dispatch ---------------------------------------------------
   ;; The DEFAULT dispatch returns the CONSEQUENCE via
   ;; `dispatch-consequence!`: `:db-changed? :changed-paths :effects-fired
   ;; :no-op?` so a no-op is VISIBLE, plus `:resolved` echoing the parsed
   ;; event. The runtime fn is `dispatch-consequence!`, distinct from the
   ;; `pair-dispatch!` transport ack used by the :queued path.
   {:fixture/id    :dispatch/happy
    :fixture/doc   "default dispatch returns the consequence via dispatch-consequence!."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 7
                                   :db-changed? true :changed-paths [[:counter]]
                                   :effects-fired [:db] :no-op? false
                                   :resolved [:counter/inc]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["dispatch-consequence!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :db-changed? true :no-op? false
                  :resolved [:counter/inc]}}}

   ;; A genuine NO-OP is VISIBLE: `:db-changed? false :effects-fired []
   ;; :no-op? true` rather than a bare success ack.
   {:fixture/id    :dispatch/no-op-visible
    :fixture/doc   "a no-op dispatch returns :db-changed? false :effects-fired [] :no-op? true."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:noop/event]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 8
                                   :db-changed? false :changed-paths []
                                   :effects-fired [] :no-op? true
                                   :resolved [:noop/event]}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :db-changed? false
                  :effects-fired [] :no-op? true}}}

   ;; An unknown event-id is VALIDATED at the wire boundary and returns a
   ;; structured :unknown-id error with :nearest matches, WITHOUT
   ;; dispatching (the runtime `dispatch-consequence!` short-circuits on
   ;; the validation miss). No silent no-op success.
   {:fixture/id    :dispatch/unknown-id-validated
    :fixture/doc   "dispatch of an unregistered event-id surfaces :reason :unknown-id + :nearest, never a silent success."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf/xrayy]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? false :reason :unknown-id :kind :event
                                   :id :rf/xrayy :event [:rf/xrayy]
                                   :nearest [:rf/xray :rf/default]
                                   :resolved [:rf/xrayy] :dispatched? false
                                   :hint "unknown :event :rf/xrayy; did you mean :rf/xray, :rf/default?"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :unknown-id :id :rf/xrayy
                  :nearest [:rf/xray :rf/default] :resolved [:rf/xrayy]}}}

   ;; `:queued true` opts into the async transport-ack: the runtime
   ;; `pair-dispatch!` return rides back with `:mode :queued` and
   ;; `:settled? false` when the cascade hasn't drained.
   {:fixture/id    :dispatch/queued-settled-false
    :fixture/doc   "dispatch :queued true returns the async ack (:mode :queued :settled? false)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]" :queued true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["pair-dispatch!"            {:ok? true :queued? true
                                   :cascade-summary-pending? true
                                   :before-epoch-id 12}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["pair-dispatch!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :queued :settled? false :cascade-summary-pending? true}}}

   {:fixture/id    :dispatch/missing-event
    :fixture/doc   "dispatch without :event surfaces :missing-event."
    :fixture/tool  "dispatch"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-event}}

   {:fixture/id    :dispatch/preload-missing
    :fixture/doc   "dispatch surfaces :runtime-loaded-but-preload-missing when the marker is absent (diagnostic ladder)."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; Frame-targeted dispatch routes to the named frame. The documented
   ;; `frame` arg is the colon-prefixed id (":rf/xray"; Tool-Catalogue
   ;; §Id representation). The emitted runtime call MUST carry the
   ;; well-formed `:frame :rf/xray` opt and MUST NOT mint the malformed
   ;; `::rf/xray` (namespace ":rf") that raw `(keyword ...)` would produce
   ;; — which would be a silent no-op.
   {:fixture/id    :dispatch/frame-targeted-routes
    :fixture/doc   "dispatch frame ':rf/xray' emits {:frame :rf/xray} in the runtime opts — NOT the malformed ::rf/xray. Routes through dispatch-consequence!."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf.xray/focus-event 85]" :frame ":rf/xray" :sync true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? true :epoch-id 7 :frame :rf/xray
                                   :resolved [:rf.xray/focus-event 85]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["dispatch-consequence!" ":frame :rf/xray"]
    :fixture/eval-form-must-not-contain
    ["::rf/xray"]
    :fixture/expect
    {:isError? false
     :edn-submap {:mode :sync :ok? true :frame :rf/xray}}}

   ;; When the named frame cannot be targeted (head did not advance), the
   ;; runtime returns {:ok? false :reason :no-new-epoch}. The tool MUST
   ;; surface a structured ERROR envelope, never a {:mode :sync} success
   ;; merged over the failure (which would be a silent wrong-success).
   {:fixture/id    :dispatch/frame-untargetable-error
    :fixture/doc   "dispatch surfaces the runtime's {:ok? false :reason :no-new-epoch} as an :isError envelope with NO :mode slot."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:rf.xray/focus-event 85]" :frame ":rf/xray" :sync true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-consequence!"     {:ok? false :reason :no-new-epoch
                                   :event [:rf.xray/focus-event 85]
                                   :frame :rf/xray
                                   :resolved [:rf.xray/focus-event 85]
                                   :hint "dispatch-sync returned, but epoch-history head did not advance."}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-new-epoch :frame :rf/xray}}}

   ;; Render-settle. `:await-render true` wraps the settle Promise in the
   ;; await mailbox; the wrap-form eval returns the mailbox sentinel and
   ;; the poll read resolves to the dispatch envelope with
   ;; `:settled? true`. The emitted settle form MUST route
   ;; the flush through the substrate-agnostic adapter primitive
   ;; (`re-frame.interop/after-render`) + the paint boundary
   ;; (`requestAnimationFrame`) and force synchronous dispatch.
   {:fixture/id    :dispatch/await-render-settles
    :fixture/doc   "dispatch :await-render true resolves to the dispatch envelope with :settled? true after the render flush, routed through the adapter contract."
    :fixture/tool  "dispatch"
    :fixture/args  {:event "[:counter/inc]" :await-render true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The wrap form (contains the await-mailbox sentinel literal)
     ;; returns the mailbox sentinel; first-match-wins orders it before
     ;; the read form.
     [":rf.mcp/await-mailbox"     {:rf.mcp/await-mailbox "settle-mbx"}]
     ;; The poll read resolves immediately to the settled dispatch
     ;; envelope.
     ["cljs.reader/read-string"   {:status :resolved
                                   :value {:ok? true :epoch-id 9
                                           :frame :rf/default
                                           :settled? true
                                           :cascade-summary {:event-id :counter/inc
                                                             :renders 1}}}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.interop/after-render"
     "requestAnimationFrame"
     "dispatch-consequence!"
     ":settled? true"]
    :fixture/eval-form-must-not-contain
    ["reagent" "setTimeout"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :mode :sync :settled? true :epoch-id 9}}}

   ;; ---------- dispatch-dry-run ------------------------------------------
   ;; Dry-run is NOT --allow-writes-gated: the framework effect sink
   ;; records and skips every declared fx, and `replace-frame-state!`
   ;; restores the pre-call frame-state. No gate needed because the
   ;; contract IS "no state change".
   {:fixture/id    :dispatch-dry-run/happy
    :fixture/doc   "dispatch-dry-run wraps the runtime's dispatch-dry-run envelope through unchanged — :cascade-summary + :would-fire-effects + :db-state-after-simulation."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "[:cart/checkout]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dispatch-dry-run"          {:ok? true :dry-run? true :rolled-back? true
                                   :event [:cart/checkout]
                                   :frame :rf/default
                                   :before-epoch-id 41
                                   :cascade-summary {:epoch-id 42
                                                     :event-id :cart/checkout
                                                     :event-vector [:cart/checkout]
                                                     :frame :rf/default
                                                     :outcome :ok
                                                     :db-diff {:changed-paths [[:cart]]
                                                               :added-paths []
                                                               :removed-paths []}
                                                     :fx-fired [:http]
                                                     :subs-recomputed 2
                                                     :renders 1}
                                   :would-fire-effects [{:fx-id :http :args {:url "/checkout"}}]
                                   :db-state-after-simulation {:cart {} :order {:id 1}}}]
     [:default                    nil]]
    ;; The parsed event rides as a QUOTED literal, so it
    ;; evaluates to the datum the caller sent rather than to whatever its
    ;; printed form means as source.
    :fixture/eval-form-must-contain
    ["dispatch-dry-run (quote [:cart/checkout])"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true :rolled-back? true}}}

   {:fixture/id    :dispatch-dry-run/missing-event
    :fixture/doc   "dispatch-dry-run without :event surfaces :missing-event (mirrors dispatch's arg-parse gate)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-event}}

   {:fixture/id    :dispatch-dry-run/rejects-host-form
    :fixture/doc   "dispatch-dry-run rejects host-form source (the data-not-source posture shared with dispatch)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "(println :pwn)"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :not-an-event-vector}}

   ;; Dry-run is an AI-facing READ surface. Gate OFF (the default
   ;; published posture) MUST run the app-db-rooted egress slot
   ;; (:db-state-after-simulation) through the elision walker server-side,
   ;; with sensitive slots forced to redact. The caller's `:elision false`
   ;; is the size override and is honoured on every launch: an
   ;; `include-large? true` overlay on the off-box-tool
   ;; floor, never the local-raw boundary, and echoed as `:elision false`.
   ;; The :would-fire-effects[*].args slot is NOT app-db-rooted, so
   ;; it FAILS CLOSED (assoc :rf/redacted) rather than running through the
   ;; walker. The eval form must carry the walker call
   ;; (for the db slot) + the safe walker opts + the fx-args redaction
   ;; (touching :would-fire-effects), and must signal the raw-state tap
   ;; posture (configure-raw-state!) before the dispatch.
   {:fixture/id    :dispatch-dry-run/gate-off-redacts-egress
    :fixture/doc   "dispatch-dry-run with --allow-sensitive-reads OFF (default): the form runs :db-state-after-simulation through project-egress under the :rf.egress/off-box-tool boundary, and FAILS CLOSED the :would-fire-effects[*].args to :rf/redacted."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? false
    :fixture/args  {:event "[:auth/login]"
                    ;; the size override is honoured on every launch;
                    ;; the sensitive + fx-args opt-ins are dropped by
                    ;; the gate.
                    :elision false
                    :include-sensitive true
                    :include-fx-args true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :rolled-back? true
                                           :would-fire-effects [{:fx-id :http :args :rf/redacted}]
                                           :db-state-after-simulation {:user {:token :rf/redacted}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":db-state-after-simulation"
     ":would-fire-effects"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"
     "configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true :elision false}}}

   ;; Fail-CLOSED. Gate ON + a BARE `:elision false` (no
   ;; `:include-sensitive true`) MUST still walk the app-db-rooted
   ;; `:db-state-after-simulation` slot: large content passes
   ;; (`include-large? true`) but a declared-sensitive db slot redacts.
   {:fixture/id    :dispatch-dry-run/gate-on-bare-elision-false-still-walks
    :fixture/doc   "dispatch-dry-run ON + :elision false (no sensitive opt-in) ⇒ form STILL runs :db-state-after-simulation through project-egress under :rf.egress/off-box-tool with a :rf.egress/include-large? true overlay."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:cart/checkout]" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :db-state-after-simulation {:user {:token :rf/redacted}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; The deliberate full-raw opt-in (`:elision false` AND
   ;; `:include-sensitive true`) NAMES `:rf.egress/local-raw`, under which
   ;; the projection is the identity so the db slot ships raw (the door
   ;; is still called). With `:include-fx-args` unset the fx
   ;; args still fail closed, so the form still touches
   ;; `:would-fire-effects`.
   {:fixture/id    :dispatch-dry-run/gate-on-full-raw-opt-out
    :fixture/doc   "dispatch-dry-run ON + :elision false + :include-sensitive true ships the raw simulation details — the db slot names :rf.egress/local-raw, under which the projection is the identity."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:cart/checkout]" :elision false :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :db-state-after-simulation {:user {:token "raw"}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/local-raw"]
    :fixture/eval-form-must-not-contain
    ["elide-wire-value"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; The :would-fire-effects[*].args slot carries RAW fx-handler
   ;; arguments (HTTP bodies, dispatched event vectors, payment maps) NOT
   ;; rooted at app-db, so the schema-path walker cannot prove
   ;; them safe. Off-box egress FAILS CLOSED: :args redacts to :rf/redacted
   ;; for every fx by default, while :fx-id rides through. The emitted form
   ;; carries the fail-close (assoc :args :rf/redacted on :would-fire-effects).
   ;; Gate ON (--allow-sensitive-reads) + :include-fx-args true is the
   ;; trusted-local opt-in that keeps the raw args — the form then does NOT
   ;; touch the :args slot.
   {:fixture/id    :dispatch-dry-run/gate-on-include-fx-args-reveals
    :fixture/doc   "dispatch-dry-run with --allow-sensitive-reads ON + :include-fx-args true keeps the raw :would-fire-effects[*].args — the emitted form does NOT assoc :rf/redacted onto the args."
    :fixture/tool  "dispatch-dry-run"
    :fixture/allow-raw-state? true
    :fixture/args  {:event "[:auth/login]" :include-fx-args true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? true :dry-run? true :rolled-back? true
                                           :would-fire-effects [{:fx-id :http :args {:headers {:authorization "Bearer raw-token"}}}]
                                           :db-state-after-simulation {:user {:token "raw"}}}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/eval-form-must-not-contain
    ["assoc e :args :rf/redacted"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :dry-run? true
                  :would-fire-effects [{:fx-id :http :args {:headers {:authorization "Bearer raw-token"}}}]}}}

   {:fixture/id    :dispatch-dry-run/no-new-epoch
    :fixture/doc   "dispatch-dry-run surfaces the runtime's {:ok? false :reason :no-new-epoch} as an isError envelope (a non-landed dry-run is never a silent success; parity with dispatch/no-new-epoch)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "[:noop]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? false :reason :no-new-epoch
                                           :event [:noop] :frame :rf/default}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-new-epoch}}}

   ;; SAFETY: the simulation LANDED but the rollback
   ;; (`replace-frame-state!`) was rejected, so the simulated state can
   ;; still be live. The runtime reports the documented
   ;; {:ok? false :reason :rollback-failed :rolled-back? false} shape — an
   ;; :ok? true shape would read GREEN over a mutated db. A dry-run that
   ;; silently mutated the live app must ride as isError — the tool routes
   ;; it red on :ok? false AND on the belt-and-braces :rolled-back? false
   ;; boundary guard.
   {:fixture/id    :dispatch-dry-run/rollback-failed
    :fixture/doc   "dispatch-dry-run surfaces the runtime's {:ok? false :reason :rollback-failed :rolled-back? false} as an isError envelope (a failed rollback leaves the live db MUTATED; it must never read as a silent green success)."
    :fixture/tool  "dispatch-dry-run"
    :fixture/args  {:event "[:cart/checkout]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["dispatch-dry-run"          {:value {:ok? false :reason :rollback-failed
                                           :rolled-back? false
                                           :event [:cart/checkout] :frame :rf/default
                                           :before-epoch-id nil}
                                   :elided-count 0}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rollback-failed :rolled-back? false}}}

   ;; ---------- restore-epoch (gated write) -------------------------------
   ;; The --allow-writes gate ships DEFAULT-OFF. The disabled fixture pins
   ;; the gate-closed envelope (no nREPL round-trip); the happy fixture
   ;; flips :fixture/allow-writes? and pins the success shape. epoch-id is
   ;; parsed as EDN so the integer id "7" reads as the number 7.
   {:fixture/id    :restore-epoch/disabled-default
    :fixture/doc   "restore-epoch with --allow-writes OFF returns :rf.error/writes-disabled without touching the runtime."
    :fixture/tool  "restore-epoch"
    :fixture/args  {:epoch-id "7"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/writes-disabled}}

   {:fixture/id    :restore-epoch/happy
    :fixture/doc   "restore-epoch with --allow-writes ON wraps the runtime's true into {:ok? true :restored? true :epoch-id <int>}."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {:epoch-id "7"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["restore-epoch"             true]
     [:default                    nil]]
    ;; The caller's epoch-id is EDN and rides as quoted
    ;; literal data, like every other caller-supplied argument.
    :fixture/eval-form-must-contain
    ["restore-epoch (quote 7)"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :restored? true :epoch-id 7}}}

   {:fixture/id    :restore-epoch/missing-id
    :fixture/doc   "restore-epoch with --allow-writes ON but no :epoch-id surfaces :missing-epoch-id."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-epoch-id}}

   ;; A restore that the runtime REJECTS (a bare `false`: aged-out id,
   ;; drain-in-flight) means the write did not land. It MUST ride as
   ;; isError carrying :restore-rejected, NOT a success-shaped envelope
   ;; the host reads as a landed write.
   {:fixture/id    :restore-epoch/rejected-false
    :fixture/doc   "restore-epoch with --allow-writes ON whose runtime returns false (rejected restore) rides as isError :restore-rejected."
    :fixture/tool  "restore-epoch"
    :fixture/allow-writes? true
    :fixture/args  {:epoch-id "999"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["restore-epoch"             false]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :restored? false :reason :restore-rejected :epoch-id 999}}}

   ;; ---------- replay-epoch (dispatch authority; NOT writes-gated) --------
   ;; Strict replay of a retained epoch in ONE call. The tool
   ;; sends only the id; the runtime resolves the raw record in-process.
   ;; NOT behind --allow-writes (it drives the app's own handlers, exactly
   ;; like dispatch), so the happy fixture leaves the gate at its default
   ;; OFF and still reaches the runtime.
   {:fixture/id    :replay-epoch/happy
    :fixture/doc   "replay-epoch with the writes gate at its default OFF reaches the runtime and passes the dispatch-shaped consequence through."
    :fixture/tool  "replay-epoch"
    :fixture/args  {:epoch-id "7"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["replay-epoch"              {:ok? true :replayed? true :source-epoch-id 7 :epoch-id 12
                                   :event-id :cart/checkout :frame :rf/default
                                   :db-changed? true :changed-paths [[:cart]]
                                   :effects-fired [:http] :no-op? false}]
     [:default                    nil]]
    ;; Caller EDN rides quoted (see the restore-epoch row).
    :fixture/eval-form-must-contain
    ["replay-epoch (quote 7)"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :replayed? true :source-epoch-id 7 :epoch-id 12}}}

   {:fixture/id    :replay-epoch/missing-id
    :fixture/doc   "replay-epoch with no :epoch-id surfaces :missing-epoch-id without a runtime round-trip."
    :fixture/tool  "replay-epoch"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-epoch-id}}

   ;; A refusal decided BEFORE dispatch (unknown / aged-out id) means the
   ;; replay did not land. It MUST ride as isError carrying the framework's
   ;; own reason, never a success-shaped envelope.
   {:fixture/id    :replay-epoch/refused-unknown-epoch
    :fixture/doc   "replay-epoch whose runtime refuses BEFORE dispatch (unknown / aged-out id) rides as isError carrying the framework reason."
    :fixture/tool  "replay-epoch"
    :fixture/args  {:epoch-id "999"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["replay-epoch"              {:ok? false :reason :rf.epoch/replay-unknown-epoch
                                   :frame :rf/default :epoch-id 999 :history-size 50}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.epoch/replay-unknown-epoch :epoch-id 999}}}

   ;; ---------- replace-app-db (gated write) ------------------------------
   {:fixture/id    :replace-app-db/disabled-default
    :fixture/doc   "replace-app-db with --allow-writes OFF returns :rf.error/writes-disabled without touching the runtime."
    :fixture/tool  "replace-app-db"
    :fixture/args  {:db "{:k :v}"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/writes-disabled}}

   {:fixture/id    :replace-app-db/happy
    :fixture/doc   "replace-app-db with --allow-writes ON passes the runtime's app-db-reset! envelope through; db rides as EDN data. Signals configure-raw-state! before app-db-reset! (raw-state tap posture)."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/allow-raw-state? false
    :fixture/args  {:db "{:counter 0}"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["app-db-reset!"             {:ok? true :frame :rf/default}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ;; The `db` value is external EDN, so it rides the
    ;; literal-data emission `(quote <datum>)`, not the print path.
    ["app-db-reset! (quote {:counter 0})"
     ;; The raw-state tap posture is signalled (the unit suite pins it
     ;; lands BEFORE app-db-reset!; the corpus pins it IS emitted on the
     ;; write path with the gate-OFF posture).
     "configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :frame :rf/default}}}

   {:fixture/id    :replace-app-db/missing-db
    :fixture/doc   "replace-app-db with --allow-writes ON but no :db surfaces :missing-db."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-db}}

   ;; A replace the runtime REJECTS
   ;; ({:ok? false :reason :reset-rejected ...}: no-such-frame,
   ;; replace-during-drain, schema-mismatch) means the injection did NOT
   ;; land. It MUST ride as isError carrying the reason, NOT a
   ;; success-shaped envelope.
   {:fixture/id    :replace-app-db/reset-rejected
    :fixture/doc   "replace-app-db with --allow-writes ON whose runtime returns {:ok? false :reason :reset-rejected} rides as isError."
    :fixture/tool  "replace-app-db"
    :fixture/allow-writes? true
    :fixture/allow-raw-state? false
    :fixture/args  {:db "{:bad :shape}"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["app-db-reset!"             {:ok? false :reason :reset-rejected :frame :rf/default}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :reset-rejected :frame :rf/default}}}

   ;; ---------- trace-window -----------------------------------------------
   {:fixture/id    :trace-window/happy
    :fixture/doc   "trace-window carries the runtime's epoch page through the wire pipeline: one epoch in, :count 1 out."
    :fixture/tool  "trace-window"
    :fixture/args  {:ms 500}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["epoch-history"             {:epochs        [{:epoch-id 7 :event [:counter/inc]}]
                                   :history-count 1
                                   :remaining     0}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-contains-keys #{:epochs}
     :edn-submap {:ok? true :count 1}}}

   ;; ---------- watch-epochs -----------------------------------------------
   ;; watch-epochs wraps the runtime's `epochs-since` result into a
   ;; paged/cursor envelope: `:matches` (rather than raw `:epochs`),
   ;; `:has-more?`, `:next-cursor`, `:limit`, `:count`, `:head-id`,
   ;; `:dedup`, `:epochs-mode`, `:id-aged-out?`. Pin the envelope's
   ;; documented keys so accidental renames break the test.
   {:fixture/id    :watch-epochs/empty
    :fixture/doc   "watch-epochs surfaces the empty-window cursor envelope when the ring is empty."
    :fixture/tool  "watch-epochs"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:matches      []
                                   :id-aged-out? false
                                   :requested-id nil
                                   :head-id      nil
                                   :next-id      nil
                                   :remaining    0}]]
    :fixture/expect
    {:isError? false
     :edn-contains-keys #{:matches :has-more? :limit :count}
     :edn-submap        {:ok? true :has-more? false :count 0 :id-aged-out? false}}}

   ;; ---------- tail-build -------------------------------------------------
   {:fixture/id    :tail-build/no-probe-soft-delay
    :fixture/doc   "tail-build with no probe resolves after its fixed soft delay, evaluating nothing."
    :fixture/tool  "tail-build"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :soft? true}}}

   ;; The `:probe` is arbitrary CLJS evaluated in the
   ;; runtime (the eval-cljs authority class), so `--no-eval` refuses it
   ;; with eval-cljs's own envelope, and the probe source never reaches an
   ;; nREPL eval. Beside `:eval-cljs/disabled-via-no-eval`.
   {:fixture/id    :tail-build/disabled-via-no-eval
    :fixture/doc   "tail-build with a :probe under --no-eval returns :rf.error/eval-cljs-disabled without evaluating the probe."
    :fixture/tool  "tail-build"
    :fixture/eval-allowed? false
    :fixture/args  {:probe "(+ 1 2)" :baseline "3"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :rf.error/eval-cljs-disabled}
    :fixture/eval-form-must-not-contain ["(+ 1 2)"]}

   ;; ---------- snapshot ---------------------------------------------------
   {:fixture/id    :snapshot/preload-missing
    :fixture/doc   "snapshot surfaces :runtime-loaded-but-preload-missing when the marker is absent (diagnostic ladder)."
    :fixture/tool  "snapshot"
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  false]
     [:default                    nil]]
    :fixture/expect
    {:edn-submap {:ok? false :reason :runtime-loaded-but-preload-missing}}}

   ;; ---------- get-path ---------------------------------------------------
   {:fixture/id    :get-path/happy
    :fixture/doc   "get-path returns {:ok? true :exists? true :path p :value v}."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:counter]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The eval form pre-counts elision markers; the envelope carries
     ;; `:elided-count` so the wire-pipeline reads the count from opts
     ;; instead of re-walking the scalar.
     [:default                    {:ok? true :exists? true :path [:counter]
                                   :value 42 :elided-count 0}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :exists? true :value 42}}}

   {:fixture/id    :get-path/missing-path
    :fixture/doc   "get-path without :path surfaces :missing-path."
    :fixture/tool  "get-path"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-path}}

   {:fixture/id    :get-path/path-not-found
    :fixture/doc   "get-path surfaces the runtime's :path-not-found failure as an isError envelope (a known-tool :ok? false is never a silent success)."
    :fixture/tool  "get-path"
    :fixture/args  {:path "[:no-such :key]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? false :reason :path-not-found
                                   :path [:no-such :key]
                                   :deepest-valid-prefix []}]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :path-not-found}}}

   ;; ---------- read-dom (raw DOM plane read) -----------------------------
   ;; The whole read runs browser-side in the preloaded runtime fn
   ;; (re-frame2-pair.runtime/dom-read — shared plumbing with read-ui);
   ;; the corpus stubs the form's canned envelope, matching on
   ;; the `dom-read` runtime call. Pins the outer wire shape: matched
   ;; :count + per-node {:tag :text :attrs}, the large-text elision marker,
   ;; the missing-arg gate, and the bad-selector error reason.
   {:fixture/id    :read-dom/happy
    :fixture/doc   "read-dom returns {:ok? true :count N :nodes [{:tag :text :attrs}]} from the browser-side dom-read runtime fn."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "#app .counter"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? true :selector "#app .counter"
                                   :count 1 :truncated? false
                                   :nodes [{:tag "div" :text "Count: 3"
                                            :attrs {"class" "counter" "data-count" "3"}}]}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame2-pair.runtime/dom-read" "#app .counter" ":selector"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :count 1 :truncated? false}
     :edn-contains-keys #{:nodes :selector}}}

   {:fixture/id    :read-dom/large-text-elided
    :fixture/doc   "read-dom replaces over-:max-text node text with the {:rf.size/large-elided {:type :dom-text ...}} marker (the size-elision convention)."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "pre" :max-text 100}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? true :selector "pre" :count 1 :truncated? false
                                   :nodes [{:tag "pre"
                                            :text {:rf.size/large-elided
                                                   {:type :dom-text :chars 54000 :preview "lorem..."}}
                                            :attrs {}}]}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :count 1}}}

   {:fixture/id    :read-dom/missing-selector
    :fixture/doc   "read-dom without :selector surfaces :missing-selector before any nREPL round-trip."
    :fixture/tool  "read-dom"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-selector}}

   {:fixture/id    :read-dom/bad-selector
    :fixture/doc   "read-dom forwards the browser-side :rf.error/read-dom-bad-selector envelope (querySelectorAll threw SyntaxError inside dom-read) as an :isError envelope — a thrown malformed-selector is a caller FAULT, so per spec/003-Tool-Catalogue.md §*Every :ok? false response is isError: true* map-result-or-blank branches its map arm on :ok?. Keeping it isError also keeps the transient failure OUT of the response cache (cache eligibility bypasses isError)."
    :fixture/tool  "read-dom"
    :fixture/args  {:selector "###"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["dom-read"                  {:ok? false :reason :rf.error/read-dom-bad-selector
                                   :selector "###" :message "bad selector"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :rf.error/read-dom-bad-selector}}}

   ;; ---------- list-subscriptions (reactive sub-cache) -------------------
   {:fixture/id    :list-subscriptions/empty
    :fixture/doc   "list-subscriptions on a frame with no live reactive subs returns an empty list envelope. GENUINE emptiness is the runtime's OWN `{:ok? true :subs []}` MAP (a real answer), not a blank eval — so it rides isError:false. (A blank/non-map eval is a DEGRADED read, not emptiness — see the /degraded-blank sibling.)"
    :fixture/tool  "list-subscriptions"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :frame :rf/default :count 0 :subs []}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :subs []}}}

   {:fixture/id    :list-subscriptions/degraded-blank
    :fixture/doc   "A BLANK/non-map eval (the runtime sentinel is present, but the browser tab closed/navigated in the race between the liveness re-check and the sub-cache drain) is a DEGRADED read — NOT an empty listing. It surfaces as :unexpected-shape (isError:true), never a fabricated `{:ok? true :subs []}` masking a dead read."
    :fixture/tool  "list-subscriptions"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :reason  :unexpected-shape}}

   ;; ---------- operating-frame trio --------------------------------------
   ;; The three Tool-Pair §Tool-surface-obligations ops. The runtime
   ;; surfaces them via `frames-list` (the {:frames :selected :operating}
   ;; triple) and `select-frame!`; the tools compose validate-then-pin /
   ;; clear-then-reread / read forms over those. The corpus pins the
   ;; outer wire shape + the escape contract: a SET in a multi-frame
   ;; session makes the next frame-consuming op resolve to the pinned
   ;; frame instead of refusing with :ambiguous-frame.
   {:fixture/id    :set-operating-frame/happy
    :fixture/doc   "set-operating-frame pins a registered frame and returns frames-list's map with :selected = the pinned frame (the tier-2 escape from :ambiguous-frame)."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":stories"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The runtime's answer depends on the form's shape. Pin-then-re-read
     ;; evaluates to frames-list's map, now :selected / :operating
     ;; :stories. A form ending at the bare pin evaluates to select-frame!'s
     ;; own value, which carries none of that map's keys.
     ["(let [_ (re-frame2-pair.runtime/select-frame! :stories)] (re-frame2-pair.runtime/frames-list))"
      {:ok? true
       :frames [:rf/default :stories]
       :app-frames [:rf/default :stories]
       :selected :stories
       :operating :stories}]
     ["select-frame!"             {:ok? true :frame :stories}]
     [:default                    nil]]
    ;; The emitted form MUST validate against the registered list AND
    ;; pin via select-frame! — the escape mechanism. The keyword rides
    ;; as a well-formed :stories, never the malformed ::stories.
    :fixture/eval-form-must-contain
    ["frames-list" "select-frame!" ":stories"]
    :fixture/eval-form-must-not-contain
    ["::stories"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :stories :operating :stories}
     :edn-contains-keys #{:frames :app-frames :selected :operating}}}

   {:fixture/id    :set-operating-frame/no-such-frame
    :fixture/doc   "set-operating-frame on an unregistered frame refuses with :no-such-frame as an isError envelope (Tool-Pair §Tool-surface obligations — the failed pin is not a silent success, so the invoke chokepoint won't flush the cache)."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":nope"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; :nope is not in frames-list → the form's else branch returns the
     ;; :no-such-frame envelope WITHOUT calling select-frame!.
     [:default                    {:ok? false :reason :no-such-frame
                                   :frame :nope
                                   :frames [:rf/default :stories]}]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-such-frame :frame :nope}}}

   {:fixture/id    :set-operating-frame/reserved-tool-frame
    :fixture/doc   "set-operating-frame refuses to pin a reserved :rf/* TOOL frame as the operating frame. Refused BEFORE any nREPL round-trip — a reserved frame is never the operating frame, so an omitted-:frame read can never resolve to it. :rf/default (an app frame) is allowed."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":rf/xray"}
    ;; No eval expected — the refusal short-circuits before the round-trip.
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :reserved-tool-frame :frame :rf/xray}}}

   {:fixture/id    :set-operating-frame/missing-frame
    :fixture/doc   "set-operating-frame without :frame surfaces :missing-frame before any nREPL round-trip."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-frame}}

   ;; EP-0023 §Specification — the public address is the FRAME id (a single
   ;; process-local frame-id space). The pin form pins the frame via
   ;; select-frame!.
   {:fixture/id    :set-operating-frame/frame-pin
    :fixture/doc   "set-operating-frame pins a FRAME (the public address, EP-0023); the emitted form pins via select-frame!."
    :fixture/tool  "set-operating-frame"
    :fixture/args  {:frame ":shop/cart"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The form validates :shop/cart against (:frames (frames-list)), pins
     ;; it via select-frame!, then re-reads frames-list, which reports it as
     ;; :selected / :operating. A bare pin would answer select-frame!'s own
     ;; value instead.
     ["(let [_ (re-frame2-pair.runtime/select-frame! :shop/cart)] (re-frame2-pair.runtime/frames-list))"
      {:ok? true
       :frames [:rf/default :shop/cart]
       :app-frames [:rf/default :shop/cart]
       :selected :shop/cart
       :operating :shop/cart}]
     ["select-frame!"             {:ok? true :frame :shop/cart}]
     [:default                    nil]]
    ;; The emitted form MUST pin the FRAME via select-frame!.
    :fixture/eval-form-must-contain
    ["select-frame!" ":frames" ":shop/cart"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :shop/cart :operating :shop/cart}
     :edn-contains-keys #{:frames :app-frames :selected :operating}}}

   {:fixture/id    :reset-operating-frame/clears-pin
    :fixture/doc   "reset-operating-frame clears the session frame pin (select-frame! nil), returning the post-reset map with :selected nil and :operating back at the tier-3/4 resolution."
    :fixture/tool  "reset-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ;; The clear-then-reread form: select-frame! nil, then frames-list reports
     ;; the pin cleared — :selected nil (multi-frame app → :operating nil, back
     ;; to tier-4 ambiguous). The substring match on "select-frame!" matches
     ;; the single clear-then-reread form.
     ["select-frame!"             {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected nil
                                   :operating nil}]
     [:default                    nil]]
    ;; The emitted reset form MUST clear the frame pin and re-read.
    :fixture/eval-form-must-contain
    ["select-frame! nil" "frames-list"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected nil :operating nil}
     :edn-contains-keys #{:frames :selected :operating}}}

   {:fixture/id    :get-operating-frame/reports-triple
    :fixture/doc   "get-operating-frame returns the normative {:frames :selected :operating} triple; :selected reflects a prior set."
    :fixture/tool  "get-operating-frame"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["frames-list"               {:ok? true
                                   :frames [:rf/default :stories]
                                   :selected :stories
                                   :operating :stories}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :selected :stories :operating :stories}
     :edn-contains-keys #{:frames :selected :operating}}}

   ;; ---------- get-re-frame2-pair-instructions ------------------------------------
   ;; Inline-text tool. No nREPL round-trip; the result is a
   ;; pure-data def in the bundle, so the eval-script is irrelevant.
   {:fixture/id    :get-re-frame2-pair-instructions/happy
    :fixture/doc   "get-re-frame2-pair-instructions returns {:ok? true :tool ... :text <prose>}."
    :fixture/tool  "get-re-frame2-pair-instructions"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :tool "get-re-frame2-pair-instructions"}}}

   ;; ---------- raw-state boot-gate ---------------------------------------
   ;; The default-OFF gate forces `:include-sensitive false` on every
   ;; snapshot / get-path call, regardless of the per-call arg; the
   ;; gate-ON path defers to it. `:elision` (the size override) is NOT
   ;; gated — a caller's `:elision false` overlays
   ;; `:rf.egress/include-large? true` on the off-box-tool floor on every
   ;; launch. The wire-key carries no trailing `?`; the namespaced
   ;; walker-option keyword `:rf.egress/include-sensitive?` retains it
   ;; (internal framework key, not on the wire).
   {:fixture/id    :raw-state/snapshot-gated-default-forces-redact
    :fixture/doc   "Gate OFF + caller passes :include-sensitive true ⇒ the dropped opt-in leaves the form on the :rf.egress/off-box-tool boundary with no large overlay."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.egress/profile :rf.egress/off-box-tool"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"
     ":rf.egress/include-large? true"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/snapshot-opt-in-honours-arg
    :fixture/doc   "Gate ON + caller passes :include-sensitive true ⇒ form must name the trusted-local :rf.egress/local-raw boundary."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.egress/profile :rf.egress/local-raw"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/snapshot-gated-default-honours-elision-false
    :fixture/doc   "Gate OFF + caller passes :elision false ⇒ the size override is honoured: the form still projects via project-egress under :rf.egress/off-box-tool, with a :rf.egress/include-large? true overlay, never local-raw."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; The get-path size override on a DEFAULT launch, singular and batch.
   ;; A gate that forced `:elision true` would leave the overlay absent
   ;; and echo `true`.
   {:fixture/id    :raw-state/get-path-gated-default-honours-elision-false
    :fixture/doc   "get-path: gate OFF + caller passes :elision false ⇒ the form names :rf.egress/off-box-tool with a :rf.egress/include-large? true overlay, never local-raw, and the envelope echoes :elision false."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? false
    :fixture/args  {:path "[:rows 0]" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :exists? true :path [:rows 0]
                                   :value {:id 0} :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   {:fixture/id    :raw-state/get-path-batch-gated-default-honours-elision-false
    :fixture/doc   "get-path batch: gate OFF + caller passes :elision false ⇒ same overlay on the batch form, echoed :elision false."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? false
    :fixture/args  {:paths "[[:rows 0] [:rows 1]]" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true
                                   :results {[:rows 0] {:exists? true :value {:id 0}}
                                             [:rows 1] {:exists? true :value {:id 1}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :elision false}}}

   ;; Fail-CLOSED. A BARE `:elision false` (no `:include-sensitive true`)
   ;; MUST still walk: large content passes (`include-large? true`) but a
   ;; declared-sensitive `:app-db` / `:sub-cache` slot redacts to
   ;; `:rf/redacted`.
   {:fixture/id    :raw-state/snapshot-bare-elision-false-still-walks
    :fixture/doc   "Gate ON + :elision false (no sensitive opt-in) ⇒ form must STILL call project-egress under :rf.egress/off-box-tool (sensitive redacts, large passes via the overlay)."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :elision false}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/off-box-tool"
     ":rf.egress/include-large? true"]
    :fixture/expect
    {:isError? false}}

   ;; The deliberate full-raw local opt-in (`:elision false` AND
   ;; `:include-sensitive true`) NAMES `:rf.egress/local-raw` — the door
   ;; is still called, and under that boundary the projection is the
   ;; identity so the slices ship raw.
   {:fixture/id    :raw-state/snapshot-full-raw-opt-in-names-local-raw
    :fixture/doc   "Gate ON + :elision false + :include-sensitive true ⇒ full-raw opt-in; the form names :rf.egress/local-raw and never the walker export."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? true
    :fixture/args  {:frames "all" :elision false :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["re-frame.core/project-egress"
     ":rf.egress/profile :rf.egress/local-raw"]
    :fixture/eval-form-must-not-contain
    ["re-frame.core/elide-wire-value"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/get-path-gated-default-forces-redact
    :fixture/doc   "get-path: gate OFF + caller passes :include-sensitive true ⇒ the dropped opt-in leaves the form on the :rf.egress/off-box-tool boundary with no large overlay."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? false
    :fixture/args  {:path "[:user :token]" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :exists? true :path [:user :token]
                                   :value :rf/redacted :elided-count 1}]]
    :fixture/eval-form-must-contain
    [":rf.egress/profile :rf.egress/off-box-tool"]
    :fixture/eval-form-must-not-contain
    [":rf.egress/local-raw"
     ":rf.egress/include-large? true"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/get-path-opt-in-honours-arg
    :fixture/doc   "get-path: gate ON + caller passes :include-sensitive true ⇒ form must name the trusted-local :rf.egress/local-raw boundary."
    :fixture/tool  "get-path"
    :fixture/allow-raw-state? true
    :fixture/args  {:path "[:user :token]" :include-sensitive true}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok? true :exists? true :path [:user :token]
                                   :value "raw" :elided-count 0}]]
    :fixture/eval-form-must-contain
    [":rf.egress/profile :rf.egress/local-raw"]
    :fixture/expect
    {:isError? false}}

   {:fixture/id    :raw-state/signal-runtime-fires-once-on-first-call
    :fixture/doc   "Boot-gate state is signalled to the runtime via configure-raw-state! before a state-emitting tool call's eval."
    :fixture/tool  "snapshot"
    :fixture/allow-raw-state? false
    :fixture/args  {:frames "all"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     [:default                    {:value {:rf/default {:app-db {:k :v}}}
                                   :elided-count 0}]]
    :fixture/eval-form-must-contain
    ["configure-raw-state!"
     ":allow-raw-state? false"]
    :fixture/expect
    {:isError? false}}

   ;; ---------- handler-meta ----------------------------------------------
   ;; These fixtures put handler-meta / list-handlers inside the "every
   ;; tool" cross-tool ratchet the corpus promises, pinning their
   ;; outer-wire shape (the unit suite covers their error paths).
   {:fixture/id    :handler-meta/happy
    :fixture/doc   "handler-meta on a registered (kind,id) merges :ok? true + the requested kind/id onto the runtime meta map."
    :fixture/tool  "handler-meta"
    :fixture/args  {:kind "event" :id ":user/login"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["registrar-describe"        {:ns "user.app" :line 12 :doc "login event"}]
     [:default                    nil]]
    :fixture/expect
    {:isError?          false
     :edn-submap        {:ok? true :kind :event :id :user/login}
     :edn-contains-keys #{:ns :line :doc}}}

   {:fixture/id    :handler-meta/missing-kind
    :fixture/doc   "handler-meta with no :kind short-circuits on :invalid-kind before any nREPL round-trip."
    :fixture/tool  "handler-meta"
    :fixture/args  {:id ":user/login"}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :invalid-kind}}

   ;; ---------- list-handlers ---------------------------------------------
   {:fixture/id    :list-handlers/happy
    :fixture/doc   "list-handlers returns the sorted id vector + :count for a kind, wrapped :ok? true."
    :fixture/tool  "list-handlers"
    :fixture/args  {:kind "event"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["registrar-list"            [:user/login :user/logout]]
     [:default                    nil]]
    :fixture/expect
    {:isError?   false
     :edn-submap {:ok? true :kind :event
                  :ids [:user/login :user/logout] :count 2}}}

   {:fixture/id    :list-handlers/missing-kind
    :fixture/doc   "list-handlers with no :kind short-circuits on :invalid-kind."
    :fixture/tool  "list-handlers"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :invalid-kind}}

   ;; ---------- orient ----------------------------------------------------
   ;; The first-contact app-shape summary. One nREPL round-trip
   ;; (`re-frame2-pair.runtime/orient`); the tool echoes the resolved
   ;; `:build` and degrades a blank/non-map eval to `:unexpected-shape`
   ;; (isError) rather than a null.
   {:fixture/id    :orient/happy
    :fixture/doc   "orient forwards the runtime's app-shape summary map with the resolved :build echoed."
    :fixture/tool  "orient"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    {:ok?    true
                                   :frames {:all [:rf/default] :app [:rf/default] :operating :rf/default}
                                   :registry {:counts {:event 3 :sub 2 :fx 1}
                                              :events [:counter/inc] :subs [:counter/value] :fx [:http]}}]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :build :app}
     :edn-contains-keys #{:frames :registry}}}

   {:fixture/id    :orient/blank-eval-unexpected-shape
    :fixture/doc   "orient on a blank/non-map eval (dropped WebSocket / navigated tab) rides :unexpected-shape (isError:true), never a null structuredContent."
    :fixture/tool  "orient"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :unexpected-shape}}}

   ;; ---------- read-sub --------------------------------------------------
   ;; Validated one-shot subscription read (signalled prelude — the
   ;; configure-raw-state! tap posture rides first, then read-sub!).
   {:fixture/id    :read-sub/happy
    :fixture/doc   "read-sub forwards the runtime read-sub! hit — :ok? true :value <elided> with :elision echoed."
    :fixture/tool  "read-sub"
    :fixture/allow-raw-state? false
    :fixture/args  {:sub "[:counter/value]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["read-sub!"                 {:ok? true :query-v [:counter/value] :frame :rf/default :value 42}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["read-sub!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :value 42}}}

   {:fixture/id    :read-sub/missing-sub
    :fixture/doc   "read-sub without :sub short-circuits on :missing-sub before any nREPL round-trip."
    :fixture/tool  "read-sub"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :missing-sub}}

   ;; ---------- read-ui ---------------------------------------------------
   ;; Typed ui/read — view-plane content + producing entity. The runtime
   ;; fn always returns a map, projected via map-result-or-blank.
   {:fixture/id    :read-ui/happy
    :fixture/doc   "read-ui forwards the runtime ui-read envelope (content + entity) with the resolved :build echoed."
    :fixture/tool  "read-ui"
    :fixture/args  {:selector "#app .counter"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["ui-read"                   {:ok? true :via :selector
                                   :entity {:view-id :my.app/counter :render-key 3 :subs-read [[:counter/value]]}
                                   :content {:tag "div" :text "Count: 3" :attrs {"class" "counter"}}}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame2-pair.runtime/ui-read"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :via :selector}
     :edn-contains-keys #{:entity :content}}}

   {:fixture/id    :read-ui/no-target-arg
    :fixture/doc   "read-ui with no entry point (view-id/point/selector) short-circuits on :no-target-arg before any nREPL round-trip."
    :fixture/tool  "read-ui"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :no-target-arg}}

   ;; ---------- the three re-frame.fresco.tool reads ---------------------
   ;; Each ships one self-describing form that RESOLVES re-frame.fresco.tool at
   ;; runtime — `find-ns-obj` on the door, `unchecked-get` on the munged read —
   ;; and resolves to an {:ok? ...} envelope projected via map-envelope-result
   ;; (every :ok? false is isError). The stub returns the envelope that form
   ;; would produce, so the corpus pins the happy passthrough (with :schema + the
   ;; four projection axes) AND the honest absent/inactive envelopes.
   ;;
   ;; THE STUB KEY IS THE WIRE STRING — `(cljs.core/munge "<read>")`, the one
   ;; place each read is named in the emitted form, which carries no
   ;; fully-qualified var reference. These fixtures therefore pin the emitter and
   ;; nothing else: a form naming a read the provider does not publish matches a
   ;; stub here just as happily as a real one. `fresco-wire-test` is what reads
   ;; the provider's own source and holds the other side of that, and the
   ;; door-absent rung is only witnessed live (test/live-fresco-wire.cjs) —
   ;; a stub cannot reject a form the way shadow's analyzer can.
   {:fixture/id    :read-mounted-boundaries/happy
    :fixture/doc   "read-mounted-boundaries forwards the versioned roster; the form resolves re-frame.fresco.tool at runtime and calls read-mounted-boundaries off it. A boundary is keyed by its READ SET — the runtime mints no boundary identity — and :views names the declared views that rendered that set, with the source coordinate defview captured."
    :fixture/tool  "read-mounted-boundaries"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-mounted-boundaries\")"    {:ok? true :schema :re-frame.fresco.evidence/v3
                                                          :producer :re-frame/fresco
                                                          :read :mounted-boundaries
                                                          :complete? true :loss nil
                                                          :boundaries [{:boundary {:parent nil
                                                                                   :key [[:app/main :todo [:todo 7]]]}
                                                                        :views [{:view "app.views/todo-row"
                                                                                 :source {:ns 'app.views
                                                                                          :file "/src/app/views.cljs"
                                                                                          :line 12 :column 1}}]
                                                                        :instances 3 :read-orders 1
                                                                        :frame :app/main
                                                                        :reads [{:sub-id :todo :query [:todo 7]
                                                                                 :frame-id :app/main :epoch 4}]}]
                                                          :generation 12}]
     [:default                                           nil]]
    :fixture/eval-form-must-contain
    ["(cljs.core/munge \"read-mounted-boundaries\")"
     "(cljs.core/find-ns-obj \"re-frame.fresco.tool\")"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :schema :re-frame.fresco.evidence/v3
                  :producer :re-frame/fresco :complete? true}
     :edn-contains-keys #{:boundaries :generation}}}

   {:fixture/id    :read-mounted-boundaries/empty-but-versioned
    :fixture/doc   "with nothing mounted, read-mounted-boundaries forwards {:ok? true :boundaries []} - an empty-but-versioned envelope, NOT an isError. The entry cache is authoritative about what holds a live read edge, so the empty IS complete; what it does not establish is that nothing is retained above, which the census cannot see."
    :fixture/tool  "read-mounted-boundaries"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-mounted-boundaries\")"    {:ok? true :schema :re-frame.fresco.evidence/v3
                                                          :read :mounted-boundaries
                                                          :complete? true :loss nil
                                                          :boundaries []}]
     [:default                                           nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :boundaries []}}}

   {:fixture/id    :read-mounted-boundaries/tier-unavailable-iserror
    :fixture/doc   "when re-frame.fresco.tool is not loaded - a Reagent/UIx app, or a Fresco app nothing pulled the door into (nothing in re-frame.fresco requires it, which is how a production build never loads it) - find-ns-obj answers nil and the form resolves to {:ok? false :reason :evidence-tier-unavailable}, surfaced as an isError envelope. Absent evidence tolerated explicitly, never a fabricated empty roster."
    :fixture/tool  "read-mounted-boundaries"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-mounted-boundaries\")"    {:ok? false :reason :evidence-tier-unavailable
                                                          :hint "the re-frame.fresco.tool evidence door is not loaded..."}]
     [:default                                           nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :evidence-tier-unavailable}}}

   {:fixture/id    :read-mounted-boundaries/schema-mismatch-iserror
    :fixture/doc   "a projection stamped an evidence schema this pair build was NOT written against is converted to a typed {:ok? false :reason :evidence-tier-version-mismatch} (isError) by the consumer-owned schema gate - Pair reaches an arbitrarily old/new app, so the producer's stamp does not define support. The superseded v2 is the honest fixture: re-frame.fresco.evidence states there is no acceptance path for a superseded version and no compatibility adapter, because a v2 parser handed a v3 envelope reads :views as absent and the scope axis as missing rather than failing."
    :fixture/tool  "read-mounted-boundaries"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-mounted-boundaries\")"    {:ok? true :schema :re-frame.fresco.evidence/v2
                                                          :boundaries []}]
     [:default                                           nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :evidence-tier-version-mismatch
                  :expected :re-frame.fresco.evidence/v3
                  :actual :re-frame.fresco.evidence/v2}}}

   {:fixture/id    :read-read-attribution/happy
    :fixture/doc   "read-read-attribution forwards the reverse edge exactly: per subscription its :sub-id, projected :query, :frame-id, :epoch, :fan-out (one slot per reading boundary) and the distinct :readers holding them - each keyed identically to read-mounted-boundaries so the two rosters join with no correlation step, and each carrying its :views."
    :fixture/tool  "read-read-attribution"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-read-attribution\")"      {:ok? true :schema :re-frame.fresco.evidence/v3
                                                          :producer :re-frame/fresco
                                                          :read :read-attribution
                                                          :complete? true :loss nil
                                                          :edges [{:sub-id :todo :query [:todo 7]
                                                                   :frame-id :app/main :epoch 4 :fan-out 3
                                                                   :readers [{:parent nil
                                                                              :key [[:app/main :todo [:todo 7]]]
                                                                              :views [{:view "app.views/todo-row"
                                                                                       :source {:ns 'app.views
                                                                                                :file "/src/app/views.cljs"
                                                                                                :line 12 :column 1}}]}]}]}]
     [:default                                           nil]]
    :fixture/eval-form-must-contain
    ["(cljs.core/munge \"read-read-attribution\")"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :complete? true :read :read-attribution}
     :edn-contains-keys #{:edges}}}

   {:fixture/id    :read-read-attribution/tier-inactive-iserror
    :fixture/doc   "a production build nil-gates the whole door - every read answers nil under :advanced with goog.DEBUG false - so the door RESOLVES and the form resolves to {:ok? false :reason :evidence-tier-inactive}, surfaced as isError. Distinguishable from :evidence-tier-unavailable, which is the door being absent rather than dev-gated."
    :fixture/tool  "read-read-attribution"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"read-read-attribution\")"      {:ok? false :reason :evidence-tier-inactive
                                                          :hint "the evidence door is DEV-ONLY..."}]
     [:default                                           nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :evidence-tier-inactive}}}

   {:fixture/id    :explain-render/happy
    :fixture/doc   "explain-render forwards the two halves unblended - PROVEN (:latest-reads at the boundary's :peak-epoch, and the :snapshot React itself compares) beside the LEADS (:candidates, with the row's own :loss saying the join is missing structurally, because the commit seam records no cascade id). It is a successful read whose OUTER :complete? is false on purpose."
    :fixture/tool  "explain-render"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"explain-render\")"             {:ok? true :schema :re-frame.fresco.evidence/v3
                                                          :producer :re-frame/fresco
                                                          :read :explain-render
                                                          :complete? false
                                                          :loss {:reason :uncorrelated :dropped :unknown}
                                                          :explanations [{:boundary {:parent nil
                                                                                     :key [[:app/main :todo [:todo 7]]]}
                                                                          :views [{:view "app.views/todo-row"
                                                                                   :source {:ns 'app.views
                                                                                            :file "/src/app/views.cljs"
                                                                                            :line 12 :column 1}}]
                                                                          :frame :app/main :instances 1
                                                                          :window {:frames [:app/main] :retained-runs 12}
                                                                          :snapshot 9 :peak-epoch 5
                                                                          :latest-reads [{:sub-id :todo
                                                                                          :query [:todo 7]
                                                                                          :frame-id :app/main}]
                                                                          :loss {:reason :uncorrelated
                                                                                 :dropped :unknown}
                                                                          :candidates [{:dispatch-id 41
                                                                                        :event-id :todo/toggle
                                                                                        :frame-id :app/main
                                                                                        :sub-id :todo}]}]
                                                          :window {:frames [:app/main] :retained-runs 12}}]
     [:default                                           nil]]
    :fixture/eval-form-must-contain
    ["(cljs.core/munge \"explain-render\")"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :complete? false
                  :loss {:reason :uncorrelated :dropped :unknown}}
     :edn-contains-keys #{:explanations :window}}}

   {:fixture/id    :explain-render/tier-error-iserror
    :fixture/doc   "a throwing read degrades to {:ok? false :reason :evidence-tier-error :message ...} rather than rejecting the eval - the whole emitted form is one try, so a provider defect is reported as a typed isError with the thrown message instead of surfacing as an nREPL failure the agent cannot interpret."
    :fixture/tool  "explain-render"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"                         true]
     ["(cljs.core/munge \"explain-render\")"             {:ok? false :reason :evidence-tier-error
                                                          :message "TypeError: cannot read .-epoch of null"}]
     [:default                                           nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :evidence-tier-error}}}

   ;; ---------- describe-image --------------------------------------------
   ;; EP-0023 forward read of a frame's resolved image generation. Routes
   ;; through map-envelope-result — the isError-contract ratchet.
   {:fixture/id    :describe-image/happy
    :fixture/doc   "describe-image forwards the runtime's frame-generation summary (:images/:kinds/:counts)."
    :fixture/tool  "describe-image"
    :fixture/args  {:frame ":rf/default"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["describe-image"            {:ok? true :frame :rf/default
                                   :images [:app] :kinds [:event :sub]
                                   :counts {:event 3 :sub 2}}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :frame :rf/default}
     :edn-contains-keys #{:images :kinds :counts}}}

   {:fixture/id    :describe-image/ambiguous-frame-iserror
    :fixture/doc   "describe-image surfaces the runtime's {:ok? false :reason :ambiguous-frame} as an isError envelope (map-envelope-result — every :ok? false is isError)."
    :fixture/tool  "describe-image"
    :fixture/args  {}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["describe-image"            {:ok? false :reason :ambiguous-frame
                                   :frames [:rf/default :stories]}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :ambiguous-frame}}}

   ;; ---------- record ----------------------------------------------------
   ;; Installs a background signal recorder (signalled prelude). Returns a
   ;; :recording-id immediately; routes through map-envelope-result.
   {:fixture/id    :record/happy
    :fixture/doc   "record installs the recorder and forwards the runtime's {:ok? true :recording-id …}."
    :fixture/tool  "record"
    :fixture/allow-raw-state? false
    :fixture/args  {:signals "[{:focus true}]"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["start-recording!"          {:ok? true :recording-id "rec-abc" :status :recording}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame2-pair.runtime/start-recording!"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true}
     :edn-contains-keys #{:recording-id}}}

   {:fixture/id    :record/no-signals
    :fixture/doc   "record without :signals short-circuits on :no-signals before any nREPL round-trip."
    :fixture/tool  "record"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :no-signals}}

   ;; ---------- read-recording --------------------------------------------
   ;; Reads back a recording's change-log; map-envelope-result routes the
   ;; :no-such-recording refusal as isError.
   {:fixture/id    :read-recording/happy
    :fixture/doc   "read-recording forwards the runtime change-log envelope (:ok? true :count :entries)."
    :fixture/tool  "read-recording"
    :fixture/args  {:recording-id "rec-abc"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["re-frame2-pair.runtime/read-recording"
      {:ok? true :recording-id "rec-abc" :status :stopped :count 2 :entries []}]
     [:default                    nil]]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :recording-id "rec-abc" :count 2}}}

   {:fixture/id    :read-recording/no-such-recording-iserror
    :fixture/doc   "read-recording of an unknown/expired id surfaces {:ok? false :reason :no-such-recording} as isError (never a green result hiding a buried :ok? false)."
    :fixture/tool  "read-recording"
    :fixture/args  {:recording-id "rec-gone"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["re-frame2-pair.runtime/read-recording"
      {:ok? false :reason :no-such-recording :recording-id "rec-gone"}]
     [:default                    nil]]
    :fixture/expect
    {:isError? true
     :edn-submap {:ok? false :reason :no-such-recording :recording-id "rec-gone"}}}

   ;; ---------- watch-until -----------------------------------------------
   ;; Server-polls a compiled predicate until it holds or times out. The
   ;; happy fixture makes the FIRST poll hold so it resolves immediately
   ;; (no setTimeout, no real wait).
   {:fixture/id    :watch-until/held-on-first-poll
    :fixture/doc   "watch-until resolves {:ok? true :held? true …} on the first poll where the predicate holds."
    :fixture/tool  "watch-until"
    :fixture/allow-raw-state? false
    :fixture/args  {:signals "[{:app-db [:upload :status]}]"
                    :pred "{:signal 0 :equals :done}"}
    :fixture/eval-script
    [["__re_frame2_pair_runtime"  true]
     ["configure-raw-state!"      nil]
     ["sample-signals"            {:held? true :sample {0 :done} :t 5}]
     [:default                    nil]]
    :fixture/eval-form-must-contain
    ["re-frame2-pair.runtime/sample-signals"]
    :fixture/expect
    {:isError? false
     :edn-submap {:ok? true :held? true}}}

   {:fixture/id    :watch-until/no-signals
    :fixture/doc   "watch-until without :signals short-circuits on :no-signals before any nREPL round-trip."
    :fixture/tool  "watch-until"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :no-signals}}

   ;; ---------- pipeline: unknown tool ------------------------------------
   {:fixture/id    :pipeline/unknown-tool
    :fixture/doc   "invoke against a name not in the registry returns :unknown-tool error carrying a recovery :hint + the :available-tools catalogue."
    :fixture/tool  "no-such-tool"
    :fixture/args  {}
    :fixture/eval-script
    [[:default nil]]
    :fixture/expect
    {:isError? true
     :reason :unknown-tool
     :edn-contains-keys #{:hint :available-tools}}}])

(defn- run-one-fixture
  "Drive one fixture through `tools/invoke` from an empty response cache,
  with the launch gates as it asks — `:fixture/eval-allowed?` defaults on,
  `:fixture/allow-raw-state?` and `:fixture/allow-writes?` off, as
  published — then check the result and the
  `:fixture/eval-form-must-contain` / `:fixture/eval-form-must-not-contain`
  substrings against every form sent. Resolves to `{:fixture-id :passed?
  :failure :result-edn}`."
  [{:fixture/keys [id tool args eval-script expect eval-allowed?
                    allow-raw-state? allow-writes?
                    eval-form-must-contain eval-form-must-not-contain]}]
  (cache/clear!)
  (let [forms-seen      (atom [])
        prev-eval-gate  (eval-cljs/eval-allowed-enabled?)
        prev-raw-gate   (raw-state/raw-state-allowed?)
        prev-write-gate (writes/allow-writes-enabled?)]
    (eval-cljs/set-eval-allowed! (if (nil? eval-allowed?) true (boolean eval-allowed?)))
    (raw-state/set-allow-raw-state! (boolean allow-raw-state?))
    (writes/set-allow-writes! (boolean allow-writes?))
    ;; `signal-runtime!` awaits a per-build in-flight signal; clear it so
    ;; no fixture waits on another's.
    (raw-state/reset-runtime-signal-cache!)
    (with-stubbed-eval! eval-script forms-seen
      (fn []
        (-> (tools/invoke nil tool (tu/args->js args) nil)
            (.then (fn [result]
                     (let [seen?        (fn [needle] (some #(str/includes? % needle) @forms-seen))
                           [ok? msg]    (check-fixture-result result expect)
                           missing      (remove seen? eval-form-must-contain)
                           contains-bad (filter seen? eval-form-must-not-contain)
                           [passed? failure]
                           (cond
                             (not ok?)          [false msg]
                             (seq missing)      [false (str "eval-form-must-contain — missing from every observed form: "
                                                            (pr-str missing))]
                             (seq contains-bad) [false (str "eval-form-must-not-contain — appeared in an observed form: "
                                                            (pr-str contains-bad))]
                             :else              [true nil])]
                       {:fixture-id id
                        :passed?    passed?
                        :failure    failure
                        :result-edn (try (tu/extract-edn result)
                                         (catch :default _ :unparseable))})))
            (.catch (fn [err]
                      {:fixture-id id
                       :passed?    false
                       :failure    (str "invoke threw: " (.-message err))}))
            (.finally (fn []
                        (eval-cljs/set-eval-allowed! prev-eval-gate)
                        (raw-state/set-allow-raw-state! prev-raw-gate)
                        (writes/set-allow-writes! prev-write-gate))))))))

(deftest run-re-frame2-pair-mcp-conformance-corpus
  ;; One deftest walking the corpus serially, like the framework-side
  ;; corpus runners; a failure names its fixture.
  (async done
    (-> (reduce (fn [chain fixture]
                  (.then chain (fn [results]
                                 (.then (run-one-fixture fixture) #(conj results %)))))
                (js/Promise.resolve [])
                corpus)
        (.then (fn [results]
                 (let [failed (remove :passed? results)]
                   (doseq [{:keys [fixture-id failure result-edn]} failed]
                     (println "  " fixture-id "-" failure)
                     (println "     parsed-result:" (pr-str result-edn)))
                   (is (empty? failed)
                       (str (count failed) " of " (count results)
                            " re-frame2-pair-mcp conformance fixtures failed.")))
                 (cache/clear!)
                 (done))))))

(deftest conformance-corpus-covers-every-registered-tool
  ;; The ratchet only reaches tools that have a fixture. The reverse is not
  ;; checked: `:pipeline/unknown-tool` names a tool that must not exist.
  (let [missing (set/difference (set registry/tool-names) (set (map :fixture/tool corpus)))]
    (is (empty? missing)
        (str "registered tools with no conformance fixture: " (sort missing)
             " — add a happy-path and an error fixture for each."))))
