(ns re-frame2-pair-mcp.dispatch-test
  "Unit tests for the dispatch tool's event-arg parsing (rf2-vflrg).

  The dispatch surface is intentionally narrower than `eval-cljs`:
  the contract is an EDN event vector, nothing else. These tests pin
  that boundary at the arg-parse step — an unreadable string, a
  non-vector EDN value, or a host-form CLJS source string must NOT
  reach the runtime; they MUST return a structured error envelope.

  The eval-form composition (`rt-call fn-sym event-vec opts-form`) is
  exercised indirectly via the `rt-call` arg-emit path (covered in
  `re-frame2-pair-mcp.eval-form-test`). The data flow we pin here:

      MCP arg (string) → read-string → vector check → rt-call data arg
                              ↑
                              the security gate (rf2-vflrg)"
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]))

;; ## Stub lifetime — fixture-scoped, not Promise-chain-scoped (rf2-wb06a)
;;
;; `with-captured-eval!` installs its `cljs-eval-value` stub via a bare
;; `set!` (no per-test `.finally`); the `:after` fixture below restores
;; the pristine original captured at ns-load. A `.finally`-scoped restore
;; fires AFTER cljs.test's `done` has advanced to the NEXT test (often the
;; next NAMESPACE, e.g. orient-test), where it clobbers that neighbour's
;; freshly-installed stub mid-eval — surfacing as a `captured = nil`
;; (the orient-test failure) or a real-socket `EADDRNOTAVAIL`. The fixture
;; boundary closes that race (the same fix orient_test / invoke_test carry).
;;
;; The `--allow-sensitive-reads` boot gate is also module-level state: the
;; rf2-olvr5 finding-1 tests set it SYNCHRONOUSLY inside each body-fn
;; (immediately before the synchronous form build, no intervening await)
;; and the `:after` fixture resets it to the published default (OFF) so a
;; gate-ON test can't leak its posture into a neighbour.

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/set-allow-raw-state! false)
            ;; rf2-8fin7.3 — dispatch now issues `signal-runtime!` (the
            ;; boot-gate posture push) before its eval, which records an
            ;; in-flight entry in the per-build `runtime-signalling` map.
            ;; Clear it between tests so a build-id can't leak a stale
            ;; in-flight Promise into a neighbour.
            (raw-state/reset-runtime-signal-cache!))})

;; ---------------------------------------------------------------------------
;; Stub harness — capture the form string the dispatch tool would have
;; sent over nREPL, without opening a socket.
;; ---------------------------------------------------------------------------

(defn- fresh-conn []
  (let [conn (nrepl/make-conn 0 "127.0.0.1")]
    ;; Pretend the runtime preload is already confirmed so probe
    ;; resolves synchronously and we exercise the form-building path.
    (swap! conn assoc :probed-builds #{:app})
    conn))

(defn- with-captured-eval!
  "Install a stub `cljs-eval-value` that records the DISPATCH form string
  into `captured*` and resolves to `canned-value`. Cleanup is the
  `:after` fixture's job (rf2-wb06a) — NOT a per-call `.finally`, which
  fires after `done` and races a neighbour's stub.

  rf2-8fin7.3 — dispatch now issues `configure-raw-state!`
  (`signal-runtime!`) BEFORE the dispatch eval. That signal form is
  resolved to nil (swallowed by signal-runtime!) and is NOT recorded
  into `captured*`, so the single-capture tests below still see the
  dispatch form (the one that mentions a dispatch runtime fn / the
  await wrapper). The per-build signal cache is reset so the signal
  always fires its eval."
  [captured* canned-value body-fn]
  (let [run (fn [form-str]
              ;; The boot-gate signal resolves to nil and is not captured —
              ;; the dispatch form is what the single-capture tests assert.
              (if (str/includes? form-str "configure-raw-state!")
                (js/Promise.resolve nil)
                (do (reset! captured* form-str)
                    (js/Promise.resolve canned-value))))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

(defn- with-captured-forms!
  "Like `with-captured-eval!` but records EVERY form string (the
  `configure-raw-state!` signal AND the dispatch eval) into `forms*` in
  order, so a test can assert their RELATIVE ordering (the rf2-8fin7.3
  path-3 invariant: signal-runtime! fires BEFORE the dispatch eval). The
  configure form resolves to nil; every other form resolves to
  `canned-value`."
  [forms* canned-value body-fn]
  (let [run (fn [form-str]
              (swap! forms* conj form-str)
              (js/Promise.resolve
                (if (str/includes? form-str "configure-raw-state!")
                  nil
                  canned-value)))
        stub (fn
               ([_conn _build-id form-str] (run form-str))
               ([_conn _build-id form-str _opts] (run form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

;; `read-result-text` (EDN read of the wire envelope) and `err?` are the
;; shared extractors (rf2-wnrpi) — aliased from `test-utils`.
(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

;; ---------------------------------------------------------------------------
;; Rejection arms — the security gate.
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source
  ;; The headline rf2-vflrg case: an attacker / a prompt-injected
  ;; agent supplies host-form source instead of an event vector. The
  ;; pre-fix shape inlined this via `rt-raw`, so `(println :pwned)`
  ;; would have run inside the runtime eval. Post-fix: the parser
  ;; reads it as a list, the vector-check fails, the runtime is
  ;; never contacted.
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {:event "(println :pwned)"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-an-event-vector (:reason edn)))
                   (is (= :list (:parsed-type edn))))
                 (done))))))

(deftest rejects-bare-keyword
  ;; `:cart/checkout` is valid EDN but not a vector — agents that
  ;; forget the brackets get a corrective error.
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {:event ":cart/checkout"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-an-event-vector (:reason edn)))
                   (is (= :keyword (:parsed-type edn))))
                 (done))))))

(deftest rejects-map
  ;; A map is valid EDN but the wrong shape.
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {:event "{:id :foo}"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :not-an-event-vector (:reason edn)))
                   (is (= :map (:parsed-type edn))))
                 (done))))))

(deftest rejects-unreadable-edn
  ;; Mismatched brackets / a lone `#` / any reader failure surfaces as
  ;; `:invalid-event-edn` so the caller can distinguish "didn't parse"
  ;; from "wrong shape".
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {:event "[:foo"})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :invalid-event-edn (:reason edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; rf2-wz66k7 — `:timeout-ms` (the `:await-render` deadline) is validated
;; as a positive-millisecond integer BEFORE the dispatch eval. A
;; non-numeric value (`"bogus"`) made `await-promise/poll-mailbox!`'s
;; `(>= elapsed timeout-ms)` deadline `(>= n NaN)` — never true — so the
;; render-settle mailbox poll loop ran FOREVER. The tool now short-circuits
;; to an honest validation error WITHOUT touching nREPL (the validation is
;; the first `cond` branch, ahead of the event-parse + eval).
;; ---------------------------------------------------------------------------

(deftest bogus-timeout-ms-rejected-before-touching-nrepl
  ;; A VALID event with a bogus :timeout-ms must surface the numeric-arg
  ;; error, not reach the (never-stubbed) eval. The fresh-conn has no live
  ;; socket; if the validation didn't short-circuit, this would fail
  ;; differently (a probe/eval failure), so the assertion is load-bearing.
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn)
                                #js {:event "[:cart/add]" :await-render true
                                     :timeout-ms "bogus"})
        (.then (fn [r]
                 (is (err? r) "malformed :timeout-ms surfaces as :isError true")
                 (let [edn (read-result-text r)]
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "timeout-ms" (:arg edn))))
                 (done))))))

(deftest negative-timeout-ms-rejected
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn)
                                #js {:event "[:cart/add]" :await-render true
                                     :timeout-ms -50})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :invalid-numeric-arg (:reason edn)))
                   (is (= "timeout-ms" (:arg edn))))
                 (done))))))

(deftest rejects-blank-event
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {:event "   "})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :missing-event (:reason edn))))
                 (done))))))

(deftest rejects-missing-event
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn) #js {})
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :missing-event (:reason edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Acceptance arm — the EDN vector reaches the runtime as data.
;; ---------------------------------------------------------------------------

(deftest accepts-edn-vector-and-emits-data-arg
  ;; Happy path: `[:cart/checkout]` reads as a vector, flows into
  ;; `rt-call` as a normal data arg, and emits as a pr-str'd literal
  ;; inside the runtime call. rf2-3bu3d.2 — the DEFAULT now routes
  ;; through `dispatch-consequence!` (validate + echo + consequence).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7 :db-changed? false
                                         :changed-paths [] :effects-fired [] :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [form @captured]
                     (is (string? form))
                     ;; The default runtime call is now
                     ;; `(rt/dispatch-consequence! [:cart/checkout] {})`.
                     ;; The event vector rides as an EDN literal — pinned
                     ;; via the `pr-str` shape.
                     (is (re-find #"dispatch-consequence!" form))
                     (is (re-find #"\[:cart/checkout\]" form))
                     ;; And critically — NO host-form splice. The form is
                     ;; standalone CLJS that the runtime can read back as
                     ;; data. We can round-trip the outer call as EDN.
                     (let [parsed (cljs.reader/read-string form)]
                       (is (= 're-frame2-pair.runtime/dispatch-consequence! (first parsed))
                           "first arg is the qualified fn symbol")
                       (is (= [:cart/checkout] (second parsed))
                           "second arg is the event vector — DATA, not source")))
                   (done)))))))

(deftest accepts-event-with-args
  ;; A two-element event: `[:cart/add {:sku "abc"}]`. The map rides
  ;; as a literal inside the vector.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:dispatched? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/add {:sku \"abc\"}]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [parsed (cljs.reader/read-string @captured)]
                     (is (= [:cart/add {:sku "abc"}] (second parsed))))
                   (done)))))))

(deftest sync-mode-routes-to-dispatch-consequence
  ;; rf2-3bu3d.2 — `:sync true` (like the default) routes through
  ;; `dispatch-consequence!`, the validate+echo+consequence surface.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 1}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]" :sync true})))
          (.then (fn [_]
                   (is (re-find #"dispatch-consequence!" @captured))
                   (done)))))))

(deftest trace-mode-routes-to-dispatch-and-collect
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:dispatched? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]" :trace true})))
          (.then (fn [_]
                   (is (re-find #"dispatch-and-collect" @captured))
                   (done)))))))

(deftest settle-mode-routes-to-dispatch-and-settle
  ;; rf2-vk79g — `:settle true` routes to the SYNCHRONOUS runtime
  ;; `dispatch-and-settle!` (dispatch-sync → flush-render! → re-read the
  ;; settled epoch). Unlike `:await-render`, the runtime fn returns a map
  ;; directly, so the emitted form is the ordinary `rt-call` (NOT the
  ;; await-promise mailbox wrapper).
  ;;
  ;; rf2-m9duxl — gate ON + `:include-sensitive true` does NOT bypass the
  ;; epoch projection. The settle form STILL wraps the runtime call in
  ;; `projected-record`, threading `{:include-sensitive? true}` as the
  ;; egress opt (app-db sensitive axis ONLY). The inner runtime fn is still
  ;; `dispatch-and-settle!` (no mailbox wrapper — synchronous). The
  ;; default-gate projection is pinned by
  ;; `settle-projects-epoch-by-default-when-gate-off`.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 11 :settled? true
                                         :render-events [] :cascade-summary {:renders 1}}
            (fn []
              ;; Set the gate ON immediately before the synchronous
              ;; form-build so there's no async-fixture window where it
              ;; could be flipped back (the gate is global mutable state).
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :settle true
                                           :include-sensitive true})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [form @captured]
                     (is (re-find #"dispatch-and-settle!" form)
                         ":settle routes to the synchronous dispatch-and-settle!")
                     (is (not (str/includes? form "__rf2pair_await__"))
                         "NO mailbox wrapper — dispatch-and-settle! is synchronous")
                     (is (str/includes? form "projected-record")
                         "include-sensitive STILL projects — never a raw bypass (rf2-m9duxl)")
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                         "rf2-nmjcll — the :epoch projects under the off-box-tool boundary even on the sensitive opt-in path")
                     (is (str/includes? form ":include-sensitive? true")
                         "the app-db sensitive axis is threaded INTO the projection (over the off-box-tool floor)")
                     (is (not (str/includes? form ":include-fx-args?"))
                         "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                     (is (not (str/includes? form ":include-runtime-db?"))
                         "runtime-db axis is NOT lifted by include-sensitive (orthogonal)"))
                   (let [edn (read-result-text r)]
                     (is (= :settle (:mode edn)) "mode is :settle")
                     (is (true? (:settled? edn)) "the settled flag rides through"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-olvr5 finding 1 — epoch-bearing dispatch modes project before egress.
;;
;; `:trace` (dispatch-and-collect) and `:settle` (dispatch-and-settle!)
;; return RAW `:epoch` records (db-before / db-after / trigger-event /
;; trace-events) plus, for settle, `:render-events`. With the
;; `--allow-sensitive-reads` gate OFF (the published default) the emitted
;; form MUST route the result's epoch slots through
;; `re-frame.core/projected-record` APP-SIDE before crossing the wire —
;; mirroring the pull-mode trace-window / watch-epochs egress (rf2-6wvh5).
;; The default sync / queued consequence shapes carry no raw app-db, so
;; they stay un-wrapped.
;; ---------------------------------------------------------------------------

(deftest settle-projects-epoch-by-default-when-gate-off
  ;; Gate OFF (default) ⇒ the settle form wraps the runtime call so the
  ;; result's `:epoch` is projected via `projected-record` and
  ;; `:render-events` is re-derived from the projected (elided) epoch's
  ;; trace-events. The runtime fn is still invoked (substring intact).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 11 :settled? true}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :settle true})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-and-settle!")
                         "the runtime settle fn is still the inner call")
                     (is (str/includes? form "re-frame.core/projected-record")
                         "gate OFF ⇒ :epoch routes through the framework's off-box projection")
                     (is (str/includes? form ":render-events")
                         "the settle form re-derives :render-events from the projected epoch"))
                   (done)))))))

(deftest trace-projects-epoch-by-default-when-gate-off
  ;; Gate OFF (default) ⇒ the trace form (dispatch-and-collect) wraps the
  ;; runtime call so the returned `:epoch` is projected before egress.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7 :epoch {:frame :rf/default}}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :trace true})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-and-collect")
                         "the runtime trace fn is still the inner call")
                     (is (str/includes? form "re-frame.core/projected-record")
                         "gate OFF ⇒ :epoch routes through projected-record before egress"))
                   (done)))))))

(deftest trace-include-sensitive-routes-through-projection-when-gate-on
  ;; rf2-m9duxl — gate ON (--allow-sensitive-reads) AND explicit
  ;; `:include-sensitive true` does NOT bypass the epoch projection. The
  ;; trace form STILL wraps the runtime call in `projected-record`,
  ;; threading `{:include-sensitive? true}` (app-db sensitive axis ONLY).
  ;; fx-args / runtime-db / large slots stay fail-closed.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :trace true
                                           :include-sensitive true})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-and-collect"))
                     (is (str/includes? form "projected-record")
                         "include-sensitive STILL projects — never a raw bypass (rf2-m9duxl)")
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                         "rf2-nmjcll — the :epoch projects under the off-box-tool boundary even on the sensitive opt-in path")
                     (is (str/includes? form ":include-sensitive? true")
                         "the app-db sensitive axis is threaded INTO the projection (over the off-box-tool floor)")
                     (is (not (str/includes? form ":include-fx-args?"))
                         "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                     (is (not (str/includes? form ":include-runtime-db?"))
                         "runtime-db axis is NOT lifted by include-sensitive (orthogonal)"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

(deftest sync-mode-does-not-project
  ;; The default sync consequence (dispatch-consequence!) carries no raw
  ;; app-db — it returns :db-changed? / :changed-paths / :effects-fired,
  ;; not :db-before/:db-after. It must NOT be wrapped in the projection
  ;; (the wrap is for the epoch-bearing modes only).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7 :db-changed? false}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :sync true})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-consequence!"))
                     (is (not (str/includes? form "projected-record"))
                         "sync consequence carries no raw app-db — no projection wrap"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; rf2-8fin7.3 — boot-gate signal (path 3): the DEFAULT cascade-summary
;; `:event-vector` redaction.
;;
;; `redact-sensitive-event-vector` (runtime) redacts the cascade-summary's
;; `:event-vector` (the raw `:trigger-event`) ONLY when the runtime's
;; `raw-state-config` is at `{:allow-raw-state? false}`. That config
;; DEFAULTS to `:allow-raw-state? true` and flips to the server's gate
;; state ONLY when a tool calls `configure-raw-state!` via
;; `raw-state/signal-runtime!`. Before this fix `dispatch` never signalled,
;; so a FIRST-in-session sensitive dispatch ran with the runtime still
;; permissive and shipped the raw event vector under the default OFF gate.
;;
;; These tests assert the WIRE BOUNDARY: that `dispatch` emits the
;; `configure-raw-state!` signal BEFORE its dispatch eval, carrying the
;; server's gate posture. The runtime-side redaction itself is exercised in
;; the live preload runtime tests (skills/re-frame2-pair/tests/runtime/).
;; ---------------------------------------------------------------------------

(deftest default-dispatch-signals-configure-raw-state-before-eval
  ;; Path 3, gate OFF (the published default): the default sync dispatch
  ;; (no trace / settle) MUST signal `configure-raw-state!` with
  ;; `:allow-raw-state? false` BEFORE the dispatch-consequence! eval — so
  ;; the runtime flips out of its permissive default and the
  ;; cascade-summary `:event-vector` redacts for a sensitive epoch. This is
  ;; the rf2-8fin7.3 broadening: the leak is NOT trace-mode-only.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-forms! forms {:ok? true :epoch-id 7 :db-changed? false}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:auth/sign-in {:password \"hunter2\"}]"})))
          (.then (fn [_]
                   (let [all      @forms
                         cfg-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         disp-idx (first (keep-indexed (fn [i f] (when (str/includes? f "dispatch-consequence!") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! is signalled on the DEFAULT path")
                     (is (some? disp-idx) "the dispatch eval ran")
                     (is (< cfg-idx disp-idx)
                         "configure-raw-state! is signalled BEFORE the dispatch eval — the cascade-summary redaction depends on it")
                     (is (str/includes? (nth all cfg-idx) ":allow-raw-state? false")
                         "the gate-OFF posture is pushed to the runtime so the :event-vector redacts"))
                   (done)))))))

(deftest gate-on-dispatch-signals-allow-raw-state-true
  ;; Gate ON (--allow-sensitive-reads): the signal still fires, but pushes
  ;; `:allow-raw-state? true` — the operator opted into raw reads, so the
  ;; runtime leaves the cascade-summary `:event-vector` verbatim.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-forms! forms {:ok? true :epoch-id 7 :db-changed? false}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:auth/sign-in {:password \"hunter2\"}]"})))
          (.then (fn [_]
                   (let [all     @forms
                         cfg     (some #(when (str/includes? % "configure-raw-state!") %) all)]
                     (is (some? cfg) "configure-raw-state! is signalled under gate ON too")
                     (is (str/includes? cfg ":allow-raw-state? true")
                         "gate ON pushes :allow-raw-state? true — the operator opted into raw reads"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

(deftest settle-signals-configure-raw-state-before-eval
  ;; Path 3 holds on the settle path too: `:settle` issues the signal
  ;; before the dispatch-and-settle! eval (in addition to projecting the
  ;; :epoch slots — path 2). Gate OFF.
  (async done
    (let [forms (atom [])]
      (-> (with-captured-forms! forms {:ok? true :epoch-id 11 :settled? true}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:auth/sign-in {:password \"hunter2\"}]"
                                           :settle true})))
          (.then (fn [_]
                   (let [all      @forms
                         cfg-idx  (first (keep-indexed (fn [i f] (when (str/includes? f "configure-raw-state!") i)) all))
                         disp-idx (first (keep-indexed (fn [i f] (when (str/includes? f "dispatch-and-settle!") i)) all))]
                     (is (some? cfg-idx) "configure-raw-state! is signalled on the settle path")
                     (is (some? disp-idx) "dispatch-and-settle! ran")
                     (is (< cfg-idx disp-idx)
                         "the signal precedes the settle eval"))
                   (done)))))))

(deftest await-render-signals-configure-raw-state-before-eval
  ;; Path 3 holds on the await-render path: the signal fires before the
  ;; await-promise wrap form is eval'd, so a render-settle of a sensitive
  ;; event redacts the cascade-summary :event-vector too.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)
          signalled?  (atom false)]
      ;; Thin staged-mailbox stub. The await/mailbox predicates are
      ;; inlined (rather than the later-defined `await-wrap-form?` /
      ;; `mailbox-read-form?` helpers) so this path-3 test stays
      ;; co-located with the boot-gate cluster without a forward
      ;; reference. It records whether the configure signal was emitted
      ;; before the wrap form.
      (let [await-wrap? (fn [f] (and (str/includes? f "__rf2pair_await__")
                                     (str/includes? f ":rf.mcp/await-mailbox")))
            mailbox-read? (fn [f] (and (str/includes? f "__rf2pair_await__")
                                       (str/includes? f "cljs.reader/read-string")))
            respond (fn [form-str]
                      (cond
                        (str/includes? form-str "configure-raw-state!")
                        (do (reset! signalled? true)
                            (js/Promise.resolve nil))

                        (await-wrap? form-str)
                        (do (is (true? @signalled?)
                                "configure-raw-state! fired BEFORE the await-render wrap form")
                            (reset! wrap-form* form-str)
                            (js/Promise.resolve {:rf.mcp/await-mailbox "settle-mbx"}))

                        (mailbox-read? form-str)
                        (let [n (swap! read-count* inc)]
                          (js/Promise.resolve
                            (if (<= n 0)
                              {:status :pending}
                              {:status :resolved
                               :value  {:ok? true :epoch-id 9 :frame :rf/default
                                        :settled? true :cascade-summary {:renders 1}}})))

                        :else (js/Promise.resolve nil)))
            stub (fn
                   ([_conn _build-id form-str] (respond form-str))
                   ([_conn _build-id form-str _opts] (respond form-str)))]
        (set! nrepl/cljs-eval-value stub)
        (raw-state/reset-runtime-signal-cache!)
        (raw-state/set-allow-raw-state! false)
        (-> (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:auth/sign-in {:password \"hunter2\"}]"
                                         :await-render true})
            (.then (fn [_]
                     (is (true? @signalled?) "configure-raw-state! was signalled on the await-render path")
                     (is (string? @wrap-form*) "the await wrap form was eval'd after the signal")
                     (done))))))))

(deftest settle-wins-over-other-mode-flags
  ;; `:settle` is the most complete single-call shape — it wins over
  ;; await-render / trace / queued when set together.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 11 :settled? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :settle true :trace true :queued true
                                           :await-render true})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (re-find #"dispatch-and-settle!" form)
                         ":settle wins — routes to dispatch-and-settle! despite trace/queued/await-render")
                     (is (not (str/includes? form "__rf2pair_await__"))
                         "no await-render mailbox path — settle is synchronous"))
                   (done)))))))

(deftest settle-runtime-failure-surfaces-as-error
  ;; A frame-untargetable settle (the runtime's pair-dispatch-sync!
  ;; :ok? false rides through dispatch-and-settle! verbatim) must surface
  ;; as an :isError envelope WITHOUT a :mode slot — the rf2-ldfnx
  ;; invariant holds through the settle path too.
  (async done
    (let [runtime-result {:ok?    false
                          :reason :no-epoch-recorded
                          :event  [:list/toggle]
                          :frame  :rf/gone
                          :hint   "epoch-history is empty after dispatch."}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :frame ":rf/gone"
                                           :settle true})))
          (.then (fn [r]
                   (is (err? r) "runtime :ok? false ⇒ :isError even on the settle path")
                   (let [edn (read-result-text r)]
                     (is (= :no-epoch-recorded (:reason edn)))
                     (is (not (contains? edn :mode))
                         "NO :mode slot — the dispatch did not settle"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Dispatch CONSEQUENCE (rf2-3bu3d.2) + echo/validate (rf2-3bu3d.3) — the
;; DEFAULT now returns the re-frame2 consequence, not a transport ack. A
;; no-op is VISIBLE; an unknown event-id is VALIDATED at the boundary and
;; returns a structured error with nearest matches, never a silent
;; success; the resolved event is ECHOed back.
;; ---------------------------------------------------------------------------

(deftest default-returns-consequence-shape
  ;; The runtime's dispatch-consequence! return (the consequence slots)
  ;; rides through to the wire envelope. :db-changed? / :changed-paths /
  ;; :effects-fired / :no-op? / :resolved all surface.
  (async done
    (let [runtime-result {:ok? true :epoch-id 7
                          :db-changed? true :changed-paths [[:counter]]
                          :effects-fired [:db] :no-op? false
                          :resolved [:counter/inc]}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "[:counter/inc]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= :sync (:mode edn)) "default mode is the sync consequence")
                     (is (true? (:db-changed? edn)))
                     (is (= [[:counter]] (:changed-paths edn)))
                     (is (= [:db] (:effects-fired edn)))
                     (is (false? (:no-op? edn)))
                     (is (= [:counter/inc] (:resolved edn))
                         "the resolved event is echoed back (rf2-3bu3d.3)"))
                   (done)))))))

(deftest no-op-is-visible
  ;; The headline rf2-3bu3d.2 fix: a dispatch that changed no app-db path
  ;; and fired no effect returns :db-changed? false :effects-fired []
  ;; :no-op? true — NOT a fake {:mode :sync} ack.
  (async done
    (let [runtime-result {:ok? true :epoch-id 8
                          :db-changed? false :changed-paths []
                          :effects-fired [] :no-op? true
                          :resolved [:noop/event]}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "[:noop/event]"})))
          (.then (fn [r]
                   (is (not (err? r)) "a no-op is still a successful dispatch, just visible")
                   (let [edn (read-result-text r)]
                     (is (false? (:db-changed? edn))
                         "no-op VISIBLY reports :db-changed? false")
                     (is (= [] (:effects-fired edn))
                         "no-op VISIBLY reports :effects-fired []")
                     (is (true? (:no-op? edn))))
                   (done)))))))

(deftest unknown-event-id-validated-not-dispatched
  ;; rf2-3bu3d.3 — the runtime dispatch-consequence! short-circuits on a
  ;; validation miss, returning :reason :unknown-id with :nearest matches.
  ;; The tool surfaces it as an :isError envelope (no silent success).
  (async done
    (let [runtime-result {:ok? false :reason :unknown-id :kind :event
                          :id :rf/xrayy :event [:rf/xrayy]
                          :nearest [:rf/xray :rf/default]
                          :resolved [:rf/xrayy] :dispatched? false
                          :hint "unknown :event :rf/xrayy; did you mean :rf/xray, :rf/default?"}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "[:rf/xrayy]"})))
          (.then (fn [r]
                   (is (err? r) "unknown id ⇒ :isError, never a silent success")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :unknown-id (:reason edn)))
                     (is (= [:rf/xray :rf/default] (:nearest edn))
                         "nearest matches carried for a corrective retry")
                     (is (= [:rf/xrayy] (:resolved edn))
                         "the resolved (parsed) event is echoed even on the miss")
                     (is (not (contains? edn :mode))
                         "no :mode slot — the dispatch did NOT land"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Cascade summary (rf2-6yqdl) — the runtime's `:cascade-summary` slot
;; rides through the dispatch tool unchanged.
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through-on-sync-mode
  ;; The runtime now returns a structured envelope including
  ;; `:cascade-summary`. The dispatch tool's merge path
  ;; `(merge {:mode mode} (when (map? v) v))` must thread the slot
  ;; through to the wire envelope unchanged.
  (async done
    (let [canned-cascade {:epoch-id 7
                          :event-id :cart/checkout
                          :event-vector [:cart/checkout]
                          :frame :rf/default
                          :outcome :ok
                          :db-diff {:changed-paths [[:cart]]
                                    :added-paths [] :removed-paths []}
                          :fx-fired [:dispatch]
                          :subs-recomputed 3
                          :renders 1
                          :elapsed-ms 4}
          runtime-result {:ok? true
                          :epoch-id 7
                          :event [:cart/checkout]
                          :frame :rf/default
                          :cascade-summary canned-cascade}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]" :sync true})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= :sync (:mode edn)))
                     (is (map? (:cascade-summary edn))
                         "cascade-summary slot rides through")
                     (is (= canned-cascade (:cascade-summary edn))
                         "cascade-summary contents unchanged"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Frame targeting (rf2-ldfnx) — the colon-prefixed `frame` arg must route
;; to the named frame, NOT the malformed `::rf/xray` the raw `(keyword ...)`
;; coercion minted. Reproduces the live silent-wrong-success: `frame
;; ":rf/xray"` reported `{:mode :sync}` while no-op'ing on the named frame.
;; ---------------------------------------------------------------------------

(deftest colon-prefixed-frame-routes-to-named-frame
  ;; The documented `frame` arg form is colon-prefixed (`":rf/xray"` —
  ;; Tool-Catalogue §Id representation, rf2-cg37y). Pre-fix the tool
  ;; coerced it with raw `(keyword ":rf/xray")`, which mints the
  ;; MALFORMED `::rf/xray` (namespace literally `":rf"`) — a frame the
  ;; runtime never registered, so dispatch silently no-op'd. The emitted
  ;; opts map MUST carry the well-formed `:rf/xray`.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:rf.xray/focus-cascade 85]"
                                           :frame ":rf/xray"
                                           :sync true})))
          (.then (fn [_]
                   (let [parsed (cljs.reader/read-string @captured)
                         opts   (nth parsed 2)]
                     (is (= 're-frame2-pair.runtime/dispatch-consequence! (first parsed)))
                     (is (= [:rf.xray/focus-cascade 85] (second parsed)))
                     (is (= :rf/xray (:frame opts))
                         "frame routes to the well-formed :rf/xray keyword")
                     (is (not= ::malformed (:frame opts)))
                     ;; The malformed `::rf/xray` keyword carries namespace
                     ;; ":rf" — assert it never reaches the runtime call.
                     (is (= "rf" (namespace (:frame opts)))
                         "namespace is the clean `rf`, not `:rf`")
                     (is (not (re-find #"::rf/xray" @captured))
                         "no malformed double-colon keyword in the emitted form"))
                   (done)))))))

(deftest bare-name-frame-also-routes
  ;; The bare-name form (`"rf/xray"`, no leading colon) must coerce to
  ;; the same `:rf/xray` — the `->frame-keyword` contract accepts both.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"
                                           :frame "rf/xray"
                                           :sync true})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (= :rf/xray (:frame opts))))
                   (done)))))))

(deftest no-frame-arg-omits-frame-opt
  ;; Absent `frame` arg ⇒ no `:frame` key in the opts map (the runtime
  ;; resolves the operating frame itself). Guards against a stray
  ;; nil-frame slot.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :queued? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (not (contains? opts :frame))
                         "no :frame opt when the arg is absent"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Success-vs-error contract (rf2-ldfnx) — a runtime `{:ok? false ...}`
;; (the frame-untargetable / no-epoch result) MUST surface as an :isError
;; envelope WITHOUT a `:mode` slot. The pre-fix shape merged `{:mode :sync}`
;; over the failure and emitted a success envelope — the silent
;; wrong-success the bead targets.
;; ---------------------------------------------------------------------------

(deftest runtime-failure-surfaces-as-error-not-mode-success
  ;; Frame couldn't be targeted (head didn't advance) — the runtime
  ;; reports {:ok? false :reason :no-new-epoch}. The tool MUST NOT
  ;; report {:mode :sync}; it must surface the structured failure as an
  ;; error envelope.
  (async done
    (let [runtime-result {:ok?    false
                          :reason :no-new-epoch
                          :event  [:rf.xray/focus-cascade 85]
                          :frame  :rf/xray
                          :hint   "dispatch-sync returned, but epoch-history head did not advance."}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:rf.xray/focus-cascade 85]"
                                           :frame ":rf/xray"
                                           :sync true})))
          (.then (fn [r]
                   (is (err? r) "runtime :ok? false ⇒ :isError envelope")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :no-new-epoch (:reason edn)))
                     (is (not (contains? edn :mode))
                         "NO :mode slot — the dispatch did not land")
                     (is (= :rf/xray (:frame edn))
                         "structured failure carries the targeted frame"))
                   (done)))))))

(deftest runtime-no-epoch-recorded-surfaces-as-error
  ;; The other untargetable-frame failure mode: epoch-history empty
  ;; (frame destroyed / recording disabled). Same contract — error
  ;; envelope, no :mode.
  (async done
    (let [runtime-result {:ok?    false
                          :reason :no-epoch-recorded
                          :event  [:counter/inc]
                          :frame  :rf/gone
                          :hint   "epoch-history is empty after dispatch."}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"
                                           :frame ":rf/gone"
                                           :sync true})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :no-epoch-recorded (:reason edn)))
                     (is (not (contains? edn :mode))))
                   (done)))))))

(deftest cascade-summary-pending-passes-through-on-queued-mode
  ;; Queued dispatch (`:queued true`, rf2-3bu3d.2) may return BEFORE the
  ;; cascade drains. The runtime reports `:cascade-summary-pending? true`
  ;; and `:before-epoch-id`; the tool surfaces them verbatim PLUS
  ;; `:settled? false` so callers poll watch-epochs from the recorded
  ;; pre-dispatch head.
  (async done
    (let [captured (atom nil)
          runtime-result {:ok? true :queued? true
                          :frame :rf/default
                          :cascade-summary-pending? true
                          :before-epoch-id 12
                          :hint "..."}]
      (-> (with-captured-eval! captured runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]" :queued true})))
          (.then (fn [r]
                   (is (not (err? r)))
                   ;; `:queued true` routes to `pair-dispatch!`.
                   (is (re-find #"pair-dispatch!" @captured)
                       ":queued routes to the async pair-dispatch!")
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= :queued (:mode edn)))
                     (is (false? (:settled? edn))
                         "an undrained queued dispatch reports :settled? false")
                     (is (true? (:cascade-summary-pending? edn)))
                     (is (= 12 (:before-epoch-id edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Render-settle — `:await-render` (rf2-gfu33).
;;
;; `dispatch :await-render true` resolves only AFTER the substrate has
;; flushed the new state to the DOM and the next paint is scheduled, so
;; `dispatch -> observe` is one deterministic step. Two invariants we
;; pin here WITHOUT a sleep:
;;
;;   1. SHAPE: the emitted settle form routes the flush through the
;;      substrate-agnostic adapter primitive `re-frame.interop/after-render`
;;      and the paint boundary `js/requestAnimationFrame` — NOT a Reagent
;;      API and NOT a `setTimeout` sleep. (form-substring assertions)
;;   2. TIMING: the server awaits the render-settle Promise via the
;;      shared mailbox — it POLLS until the mailbox flips off `:pending`,
;;      so a settle that takes N polls resolves only once the flush has
;;      reported done. We drive a stub mailbox that stays `:pending` for
;;      the first few polls, then flips to `:resolved`, and assert the
;;      tool waited (multiple poll reads) before resolving.
;; ---------------------------------------------------------------------------

(defn- await-wrap-form?
  "True when the emitted form is the await-promise wrapper (the settle
  Promise wrapped for the mailbox dance)."
  [form-str]
  (and (string? form-str)
       (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str ":rf.mcp/await-mailbox")))

(defn- mailbox-read-form?
  "True when the emitted form is the mailbox-read poll form."
  [form-str]
  (and (string? form-str)
       (str/includes? form-str "__rf2pair_await__")
       (str/includes? form-str "cljs.reader/read-string")))

(defn- with-staged-mailbox-eval!
  "Install a stub `cljs-eval-value` that plays the browser:

    - the wrap-form eval records the form into `wrap-form*` and returns
      the mailbox sentinel `{:rf.mcp/await-mailbox <id>}`;
    - the mailbox-read polls return `{:status :pending}` for the first
      `pending-polls` reads, then `{:status :resolved :value resolved}`.

  `read-count*` records how many poll reads happened — the deterministic
  proof the server waited for the flush rather than resolving eagerly."
  [{:keys [wrap-form* read-count* pending-polls resolved]} body-fn]
  (let [respond (fn [form-str]
                  (cond
                    (await-wrap-form? form-str)
                    (do (reset! wrap-form* form-str)
                        (js/Promise.resolve {:rf.mcp/await-mailbox "settle-mbx"}))

                    (mailbox-read-form? form-str)
                    (let [n (swap! read-count* inc)]
                      (js/Promise.resolve
                        (if (<= n pending-polls)
                          {:status :pending}
                          {:status :resolved :value resolved})))

                    :else
                    (js/Promise.resolve nil)))
        stub (fn
               ([_conn _build-id form-str] (respond form-str))
               ([_conn _build-id form-str _opts] (respond form-str)))]
    (set! nrepl/cljs-eval-value stub)
    ;; rf2-8fin7.3 — the configure-raw-state! signal hits the `:else`
    ;; branch (it is neither the await wrapper nor a mailbox read) and
    ;; resolves to nil; reset the signal cache so it fires each test.
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

(deftest await-render-emits-substrate-agnostic-flush-form
  ;; SHAPE invariant: the settle form must flush via the adapter
  ;; primitive `re-frame.interop/after-render` and pin resolution to the
  ;; paint boundary with `js/requestAnimationFrame`. It must NOT name a
  ;; substrate API (reagent/*) and must NOT sleep via setTimeout.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? true :epoch-id 9 :frame :rf/default :settled? true
                        :cascade-summary {:renders 1}}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :await-render true})))
          (.then (fn [_]
                   (let [form @wrap-form*]
                     (is (string? form))
                     (is (str/includes? form "re-frame.interop/after-render")
                         "flush routes through the substrate-agnostic adapter primitive")
                     (is (str/includes? form "requestAnimationFrame")
                         "resolution pinned to the paint boundary")
                     (is (not (str/includes? form "reagent"))
                         "no hardcoded Reagent API — substrate-agnostic")
                     (is (not (str/includes? form "setTimeout"))
                         "no sleep — deterministic settle, not a timer")
                     ;; await-render forces sync dispatch (cascade must
                     ;; commit before the render can settle) — routed
                     ;; through the consequence surface (rf2-3bu3d.2).
                     (is (str/includes? form "dispatch-consequence!")
                         "await-render forces synchronous dispatch")
                     (is (str/includes? form ":settled? true")
                         "settle form merges :settled? true into the result"))
                   (done)))))))

(deftest await-render-waits-for-flush-then-resolves
  ;; TIMING invariant: the server polls the mailbox until the flush
  ;; reports done. We hold the mailbox `:pending` for 3 polls; the tool
  ;; must NOT resolve until the flush flips it to `:resolved`. The proof
  ;; the wait was real (not a sleep): >= 4 poll reads occurred and the
  ;; final envelope carries the post-settle value with :settled? true.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 3
             :resolved {:ok? true :epoch-id 9 :frame :rf/default :settled? true
                        :cascade-summary {:renders 1 :event-id :counter/inc}}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :await-render true})))
          (.then (fn [r]
                   (is (not (err? r)) "settle resolves to a success envelope")
                   (is (>= @read-count* 4)
                       "server polled past the pending phase — waited for the flush")
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= :sync (:mode edn))
                         "await-render reports :sync mode (forced)")
                     (is (true? (:settled? edn))
                         "result confirms the render settled")
                     (is (= :counter/inc (get-in edn [:cascade-summary :event-id]))
                         "the dispatch cascade-summary rides through after settle"))
                   (done)))))))

(deftest await-render-runtime-failure-surfaces-as-error
  ;; If the dispatch itself no-op'd (frame untargetable), the settle form
  ;; still resolves — but to the runtime's {:ok? false ...} envelope. The
  ;; tool MUST surface that as an :isError (the rf2-ldfnx invariant holds
  ;; through the settle path), never a {:mode :sync :settled? true}
  ;; success.
  (async done
    (let [read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* (atom nil) :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? false :reason :no-new-epoch :settled? true
                        :frame :rf/gone
                        :hint "head did not advance."}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"
                                           :frame ":rf/gone"
                                           :await-render true})))
          (.then (fn [r]
                   (is (err? r) "runtime :ok? false ⇒ :isError even on the settle path")
                   (let [edn (read-result-text r)]
                     (is (false? (:ok? edn)))
                     (is (= :no-new-epoch (:reason edn)))
                     (is (not (contains? edn :mode))
                         "NO :mode slot — the dispatch did not land"))
                   (done)))))))

(deftest await-render-timeout-surfaces-structured-error
  ;; If the mailbox never flips off :pending within timeout-ms, the tool
  ;; returns a structured :rf.error/dispatch-await-render-timeout — not a
  ;; hang, not a false success. We use a tiny timeout-ms so the test is
  ;; fast and deterministic (the mailbox stays pending forever).
  (async done
    (let [read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* (atom nil) :read-count* read-count*
             ;; never resolves — pending-polls larger than any poll count
             ;; reachable inside the short timeout window
             :pending-polls 1000000
             :resolved {:ok? true}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"
                                           :await-render true
                                           :timeout-ms 60})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :rf.error/dispatch-await-render-timeout (:reason edn)))
                     (is (= 60 (:timeout-ms edn))))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; fx-overrides wire shape (rf2-hf7m9j) — over JSON-MCP a caller can only
;; send a JSON object, so an override TARGET arrives as a string
;; (`{":http": ":stub-http"}` ⇒ value `":stub-http"`). The pre-fix parse
;; (`js->clj :keywordize-keys true`) keywordized object KEYS only, leaving
;; the value the string `":stub-http"`; core's `resolve-fx-with-overrides`
;; then SILENTLY fell that string through to the original fx (the real
;; http/navigate effect fires despite the recipe saying it was stubbed).
;; The tool now coerces the documented colon-prefixed string target to a
;; keyword and rejects any other value.
;; ---------------------------------------------------------------------------

(deftest fx-overrides-colon-string-target-coerces-to-keyword
  ;; The documented wire shape `{":http": ":stub-http"}` must resolve to a
  ;; KEYWORD redirect target in the emitted opts — never a string that
  ;; would fall through to the real fx in core's resolver.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 1 :db-changed? false
                                         :changed-paths [] :effects-fired [] :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]"
                                           :fx-overrides #js {":http" ":stub-http"}})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [opts (nth (cljs.reader/read-string @captured) 2)
                         overrides (:fx-overrides opts)]
                     (is (= {:http :stub-http} overrides)
                         "override target is the KEYWORD :stub-http, not the string \":stub-http\"")
                     (is (keyword? (:http overrides))
                         "the redirect target keywordizes so core honours it as an id-redirect"))
                   (done)))))))

(deftest fx-overrides-bare-string-target-rejected
  ;; A non-colon-prefixed string (`"stub-http"`) is NOT a valid id-redirect
  ;; over the wire — core would silently fall it through. Reject with an
  ;; :isError envelope rather than fire the real effect.
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:cart/checkout]"
                                         :fx-overrides #js {":http" "stub-http"}})))
        (.then (fn [r]
                 (is (err? r)
                     "a bare (non-colon) string target ⇒ :isError, never a silent fall-through")
                 (done))))))

(deftest fx-overrides-non-string-target-rejected
  ;; A number / boolean / nested value is not a valid override target over
  ;; the wire — reject it rather than fall through to the real fx.
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:cart/checkout]"
                                         :fx-overrides #js {":http" 42}})))
        (.then (fn [r]
                 (is (err? r)
                     "a non-string override target ⇒ :isError")
                 (done))))))

;; ---------------------------------------------------------------------------
;; cofx wire shape (rf2-q6s1nb / EP-0010 + EP-0017) — a scripted recordable-
;; coeffect map is parsed as EDN data and threaded into the dispatch opts
;; under the flat `:rf.cofx` key the router reads (which preserves a
;; caller-supplied map verbatim). A dispatched event then carries the agent's
;; exact `:rf/time-ms` / owner-qualified recordable facts, so the resulting
;; state is REPRODUCIBLE — the agent-replay-determinism affordance the EP
;; calls for. EP-0017 renamed the MCP arg `world-inputs` → `cofx`, the opts
;; key `:rf.world/inputs` → `:rf.cofx`, and the time fact `:time-ms` →
;; `:rf/time-ms`. A malformed value short-circuits to an :isError envelope
;; before the eval rather than threading a value the runtime's
;; :rf/dispatch-opts validation would reject.
;; ---------------------------------------------------------------------------

(deftest cofx-threaded-into-opts-under-flat-key
  ;; The headline rf2-q6s1nb case: `cofx "{:rf/time-ms 1781078400123}"`
  ;; must appear in the emitted runtime opts map under the flat `:rf.cofx`
  ;; key, as DATA (the exact integer time), so the router preserves it
  ;; verbatim and the dispatch is reproducible.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7 :db-changed? true
                                         :changed-paths [[:todo]] :effects-fired [:db] :no-op? false}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:todo/add {:text \"buy milk\"}]"
                                           :cofx "{:rf/time-ms 1781078400123}"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [parsed (cljs.reader/read-string @captured)
                         opts   (nth parsed 2)]
                     (is (= [:todo/add {:text "buy milk"}] (second parsed)))
                     (is (= {:rf/time-ms 1781078400123} (:rf.cofx opts))
                         "cofx is threaded under the :rf.cofx opts key the router reads")
                     (is (int? (get-in opts [:rf.cofx :rf/time-ms]))
                         ":rf/time-ms rides as an integer — DATA, not source")
                     (is (re-find #":rf\.cofx" @captured)
                         "the flat key is emitted in the runtime form"))
                   (done)))))))

(deftest cofx-supports-owner-qualified-recordable-facts
  ;; Owner-qualified recordable facts (the app's :counter/delta, a
  ;; subsystem's :rf.route/location) ride through verbatim so an agent can
  ;; script the recorded causal token too.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 8}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:todo/add {:text \"x\"}]"
                                           :sync true
                                           :cofx "{:rf/time-ms 1700000000000 :counter/delta 4 :rf.route/location {:path \"/todos\"}}"})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)
                         cofx (:rf.cofx opts)]
                     (is (= 1700000000000 (:rf/time-ms cofx)))
                     (is (= 4 (:counter/delta cofx))
                         "an app-owned recordable fact rides through verbatim")
                     (is (= {:path "/todos"} (:rf.route/location cofx))
                         "a subsystem recordable fact rides through verbatim"))
                   (done)))))))

(deftest no-cofx-arg-omits-the-opts-key
  ;; Absent `cofx` ⇒ no `:rf.cofx` key in the emitted opts (the ordinary
  ;; live path — the runtime stamps :rf/time-ms itself). Guards against a
  ;; stray nil-valued slot that would defeat the router's stamp.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :sync true})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (not (contains? opts :rf.cofx))
                         "no :rf.cofx opt when the arg is absent"))
                   (done)))))))

(deftest cofx-non-map-rejected
  ;; A vector / scalar is valid EDN but the wrong shape — reject with
  ;; :invalid-cofx rather than thread it into the opts.
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:counter/inc]"
                                         :cofx "[:not :a :map]"})))
        (.then (fn [r]
                 (is (err? r) "a non-map cofx ⇒ :isError")
                 (let [edn (read-result-text r)]
                   (is (= :invalid-cofx (:reason edn))))
                 (done))))))

(deftest cofx-unreadable-rejected
  ;; Unreadable EDN (mismatched brackets) ⇒ :invalid-cofx.
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:counter/inc]"
                                         :cofx "{:rf/time-ms 1"})))
        (.then (fn [r]
                 (is (err? r))
                 (let [edn (read-result-text r)]
                   (is (= :invalid-cofx (:reason edn))))
                 (done))))))

(deftest cofx-non-integer-time-ms-rejected
  ;; :rf/time-ms must be an integer (epoch ms). A string / float ⇒
  ;; :invalid-cofx-time-ms, short-circuited before the eval.
  (async done
    (-> (with-captured-eval! (atom nil) {:ok? true}
          (fn []
            (dispatch/dispatch-tool (fresh-conn)
                                    #js {:event "[:counter/inc]"
                                         :cofx "{:rf/time-ms \"now\"}"})))
        (.then (fn [r]
                 (is (err? r) "a non-integer :rf/time-ms ⇒ :isError")
                 (let [edn (read-result-text r)]
                   (is (= :invalid-cofx-time-ms (:reason edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; Strict replay (rf2-v52xsr · EP-0017 §6 / Tool-Pair §Replay). Replay
;; re-drives a recorded event through the app's own handlers by re-presenting
;; the recorded `:rf.cofx` UNDER `:rf.cofx/mint-policy :strict`, so a recorded
;; fact MISSING from the token fails LOUDLY (`:rf.error/missing-required-cofx`)
;; instead of being silently re-minted — the exact divergence the recording
;; discipline exists to kill. Ordinary live `cofx` dispatch stays `:live`
;; (the router default) unless the named `replay` affordance is selected.
;; ---------------------------------------------------------------------------

(deftest replay-threads-strict-mint-policy-with-cofx
  ;; The headline rf2-v52xsr case: `replay true` alongside a recorded `cofx`
  ;; must emit BOTH the recorded `:rf.cofx` AND `:rf.cofx/mint-policy :strict`
  ;; in the runtime opts — the per-call replay lever wins over the frame's
  ;; (default :live) config, so an incomplete record halts rather than mints.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 9 :db-changed? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:todo/add {:text \"buy milk\"}]"
                                           :replay true
                                           :cofx "{:rf/time-ms 1781078400123 :counter/delta 4}"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (= {:rf/time-ms 1781078400123 :counter/delta 4} (:rf.cofx opts))
                         "the recorded :rf.cofx token rides verbatim")
                     (is (= :strict (:rf.cofx/mint-policy opts))
                         "replay hard-wires :rf.cofx/mint-policy :strict")
                     (is (re-find #":rf\.cofx/mint-policy" @captured)
                         "the strict opt is emitted in the runtime form"))
                   (done)))))))

(deftest replay-without-cofx-still-strict
  ;; Replay is strict EVEN WITHOUT a `cofx` token — a record with no scripted
  ;; facts still re-drives under :strict so any declared-but-absent recordable
  ;; fact fails loudly (no generator, no host read). The opts carry
  ;; :rf.cofx/mint-policy :strict and NO :rf.cofx key.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 9}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :replay true})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (= :strict (:rf.cofx/mint-policy opts))
                         "replay is strict regardless of a supplied cofx token")
                     (is (not (contains? opts :rf.cofx))
                         "no :rf.cofx key when no token was scripted"))
                   (done)))))))

(deftest live-cofx-dispatch-is-not-strict
  ;; The control: ordinary `cofx` dispatch WITHOUT `replay` stays live — no
  ;; :rf.cofx/mint-policy in the opts, so the router's :live default applies
  ;; and a generator-backed fact absent from the token is freshly minted (the
  ;; scripted-live path, distinct from replay).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 9}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:todo/add {:text \"x\"}]"
                                           :cofx "{:rf/time-ms 1781078400123}"})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (= {:rf/time-ms 1781078400123} (:rf.cofx opts))
                         "the scripted token still rides")
                     (is (not (contains? opts :rf.cofx/mint-policy))
                         "live cofx dispatch carries NO strict opt — stays :live"))
                   (done)))))))

(deftest no-replay-no-strict-opt
  ;; An ordinary live dispatch with no replay and no cofx carries neither the
  ;; :rf.cofx nor the :rf.cofx/mint-policy opt — the runtime stamps :rf/time-ms
  ;; and the router default :live applies. Guards against a stray strict opt.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 9}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :sync true})))
          (.then (fn [_]
                   (let [opts (nth (cljs.reader/read-string @captured) 2)]
                     (is (not (contains? opts :rf.cofx/mint-policy))
                         "no strict opt without the replay affordance")
                     (is (not (contains? opts :rf.cofx))))
                   (done)))))))
