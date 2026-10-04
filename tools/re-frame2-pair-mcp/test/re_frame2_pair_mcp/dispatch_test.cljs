(ns re-frame2-pair-mcp.dispatch-test
  "Unit tests for the dispatch tool's event-arg parsing.

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
                              the security gate"
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.raw-state :as raw-state]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]))

;; ## Stub lifetime — fixture-scoped, not Promise-chain-scoped
;;
;; `with-captured-eval!` installs its `cljs-eval-value` stub via a bare
;; `set!` (no per-test `.finally`); the `:after` fixture below restores
;; the pristine original captured at ns-load. A `.finally`-scoped restore
;; would fire AFTER cljs.test's `done` has advanced to the NEXT test
;; (often the next NAMESPACE, e.g. orient-test), where it would clobber
;; that neighbour's freshly-installed stub mid-eval — surfacing as a
;; `captured = nil` or a real-socket `EADDRNOTAVAIL`. The fixture boundary
;; closes that race (the same shape orient_test / invoke_test carry).
;;
;; The `--allow-sensitive-reads` boot gate is also module-level state: the
;; gate-ON tests set it SYNCHRONOUSLY inside each body-fn (immediately
;; before the synchronous form build, no intervening await) and the
;; `:after` fixture resets it to the published default (OFF) so a gate-ON
;; test can't leak its posture into a neighbour.

(def ^:private pristine-eval nrepl/cljs-eval-value)

(use-fixtures :each
  {:after (fn []
            (set! nrepl/cljs-eval-value pristine-eval)
            (raw-state/set-allow-raw-state! false)
            ;; Dispatch issues `signal-runtime!` (the boot-gate posture
            ;; push) before its eval, which records an in-flight entry in
            ;; the per-build `runtime-signalling` map. Clear it between
            ;; tests so a build-id can't leak a stale in-flight Promise
            ;; into a neighbour.
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
  `:after` fixture's job — NOT a per-call `.finally`, which fires after
  `done` and races a neighbour's stub.

  Dispatch issues `configure-raw-state!` (`signal-runtime!`) BEFORE the
  dispatch eval. That signal form resolves to nil (swallowed by
  signal-runtime!) and is NOT recorded into `captured*`, so the
  single-capture tests below still see the dispatch form (the one that
  mentions a dispatch runtime fn / the await wrapper). The per-build
  signal cache is reset so the signal always fires its eval."
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
  order, so a test can assert their RELATIVE ordering (the invariant that
  signal-runtime! fires BEFORE the dispatch eval). The configure form
  resolves to nil; every other form resolves to `canned-value`."
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
;; shared extractors — aliased from `test-utils`.
(def ^:private read-result-text tu/extract-edn)
(def ^:private err? tu/error?)

(defn- quoted-datum
  "The datum a `(quote <datum>)` form evaluates to, or `::not-quoted` for
  anything else — an unquoted list is a call, an unquoted symbol is a name
  lookup, and neither yields the datum it was printed from."
  [form]
  (if (and (seq? form) (= 'quote (first form)) (= 2 (count form)))
    (second form)
    ::not-quoted))

(defn- event-arg
  "The event argument of the captured runtime call, as a read form."
  [captured]
  (second (cljs.reader/read-string @captured)))

(defn- opts-arg
  "The opts map the captured runtime call carries.

  The data-only opts map rides QUOTED, like the event beside it, because
  three of its slots (`:rf.cofx`, `:fx-overrides`,
  `:interceptor-overrides`) are EDN the caller supplied and printing
  would render them as source. Unwrapping here keeps every opts assertion
  below about the VALUE the runtime receives."
  [captured]
  (quoted-datum (nth (cljs.reader/read-string @captured) 2)))

;; ---------------------------------------------------------------------------
;; Narrow integration arms — the exhaustive event-parse matrix (nil / blank
;; / unreadable / map / keyword / list / symbol / scalar / vector, with the
;; parsed-type classification) lives in `args_test` against the shared
;; `args/parse-event-arg` seam. Here we pin only the two
;; tool-level invariants:
;;   1. no-eval-on-error — a bad event short-circuits to an :isError WITHOUT
;;      contacting the runtime (host-form source never rides into the eval);
;;   2. the DISTINCT dispatch missing-event hint.
;; ---------------------------------------------------------------------------

(deftest rejects-arbitrary-cljs-source-without-eval
  ;; The headline security case: an attacker / a prompt-injected agent
  ;; supplies host-form source instead of an event vector. It reads as a
  ;; list, the vector-check fails, and the runtime is NEVER contacted —
  ;; `(println :pwned)` never rides into the eval. We PROVE the no-eval by
  ;; capturing every form the tool would send and asserting none fired
  ;; (neither the configure-raw-state! signal nor the dispatch eval).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {:event "(println :pwned)"})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :not-an-event-vector (:reason edn)))
                     (is (= :list (:parsed-type edn))))
                   (is (nil? @captured)
                       "no-eval-on-error — the runtime was never contacted; no signal / dispatch form fired")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; `:timeout-ms` (the `:await-render` deadline) is validated as a
;; positive-millisecond integer BEFORE the dispatch eval. A non-numeric
;; value (`"bogus"`) would make `await-promise/poll-mailbox!`'s
;; `(>= elapsed timeout-ms)` deadline `(>= n NaN)` — never true — so the
;; render-settle mailbox poll loop would run FOREVER. The tool
;; short-circuits to an honest validation error WITHOUT touching nREPL
;; (the validation is the first `cond` branch, ahead of the event-parse
;; + eval).
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

(deftest rejects-missing-event-with-dispatch-hint
  ;; The DISTINCT-hint invariant: a missing event surfaces :missing-event
  ;; carrying DISPATCH's own usage string (its opt set — sync / trace /
  ;; interceptor-overrides), NOT dispatch-dry-run's. Same shared parser,
  ;; distinct caller data. Also confirms no-eval on the missing path — the
  ;; error returns without the runtime being contacted.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn) #js {})))
          (.then (fn [r]
                   (is (err? r))
                   (let [edn (read-result-text r)]
                     (is (= :missing-event (:reason edn)))
                     (is (str/includes? (:hint edn) "dispatch {")
                         "the missing-event hint is dispatch's own usage string")
                     (is (str/includes? (:hint edn) "interceptor-overrides")
                         "carries the dispatch-specific opts — distinct from dry-run's hint"))
                   (is (nil? @captured) "no eval on the missing-event path")
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Acceptance arm — the EDN vector reaches the runtime as data.
;; ---------------------------------------------------------------------------

(deftest accepts-edn-vector-and-emits-data-arg
  ;; Happy path: `[:cart/checkout]` reads as a vector, flows into
  ;; `rt-call` as a normal data arg, and emits as a pr-str'd literal
  ;; inside the runtime call. The DEFAULT routes through
  ;; `dispatch-consequence!` (validate + echo + consequence).
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
                     ;; The default runtime call is
                     ;; `(rt/dispatch-consequence! (quote [:cart/checkout]) {})`.
                     ;; The event vector rides as a QUOTED literal
                     ;; so it evaluates to the datum the caller sent.
                     (is (re-find #"dispatch-consequence!" form))
                     (is (re-find #"\[:cart/checkout\]" form))
                     ;; And critically — NO host-form splice. The form is
                     ;; standalone CLJS that the runtime can read back as
                     ;; data. We can round-trip the outer call as EDN.
                     (let [parsed (cljs.reader/read-string form)]
                       (is (= 're-frame2-pair.runtime/dispatch-consequence! (first parsed))
                           "first arg is the qualified fn symbol")
                       (is (= [:cart/checkout] (quoted-datum (second parsed)))
                           "second arg evaluates to the event vector — DATA, not source")))
                   (done)))))))

(deftest settle-mode-routes-to-dispatch-and-settle
  ;; `:settle true` routes to the SYNCHRONOUS runtime
  ;; `dispatch-and-settle!` (dispatch-sync → flush-render! → re-read the
  ;; settled epoch). Unlike `:await-render`, the runtime fn returns a map
  ;; directly, so the emitted form is the ordinary `rt-call` (NOT the
  ;; await-promise mailbox wrapper).
  ;;
  ;; Gate ON + `:include-sensitive true` does NOT bypass the epoch
  ;; projection. The settle form STILL wraps the runtime call in
  ;; `project-egress`, threading `{:rf.egress/include-sensitive? true}` as the
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
                     (is (str/includes? form "project-egress")
                         "include-sensitive STILL projects — never a raw bypass")
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                         "the :epoch projects under the off-box-tool boundary even on the sensitive opt-in path")
                     (is (str/includes? form ":rf.egress/include-sensitive? true")
                         "the app-db sensitive axis is threaded INTO the projection (over the off-box-tool floor)")
                     (is (not (str/includes? form ":rf.egress/include-fx-args?"))
                         "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                     (is (not (str/includes? form ":rf.egress/include-runtime-db?"))
                         "runtime-db axis is NOT lifted by include-sensitive (orthogonal)"))
                   (let [edn (read-result-text r)]
                     (is (= :settle (:mode edn)) "mode is :settle")
                     (is (true? (:settled? edn)) "the settled flag rides through"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Epoch-bearing dispatch modes project before egress.
;;
;; `:trace` (dispatch-and-collect) and `:settle` (dispatch-and-settle!)
;; return RAW `:epoch` records (db-before / db-after / trigger-event /
;; trace-events) plus, for settle, `:render-events`. With the
;; `--allow-sensitive-reads` gate OFF (the published default) the emitted
;; form MUST route the result's epoch slots through
;; `re-frame.core/project-egress` APP-SIDE before crossing the wire —
;; mirroring the pull-mode trace-window / watch-epochs egress. The default
;; sync / queued consequence shapes carry no raw app-db, so they stay
;; un-wrapped.
;; ---------------------------------------------------------------------------

(deftest settle-projects-epoch-by-default-when-gate-off
  ;; Gate OFF (default) ⇒ the settle form wraps the runtime call so the
  ;; result's `:epoch` is projected via `project-egress` and
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
                     (is (str/includes? form "re-frame.core/project-egress")
                         "gate OFF ⇒ :epoch routes through the framework's off-box projection")
                     (is (str/includes? form ":render-events")
                         "the settle form re-derives :render-events from the projected epoch"))
                   (done)))))))

(deftest trace-include-sensitive-string-false-stays-false-no-leak
  ;; The include-sensitive arg MUST parse through the
  ;; safe `args/parse-bool-arg`, not a raw `(boolean (wire/arg …))`
  ;; coercion. Over the JSON-MCP wire the value can arrive as the STRING
  ;; "false", which is TRUTHY in CLJS — a bare `boolean` would coerce a
  ;; caller's explicit decline to TRUE under --allow-sensitive-reads,
  ;; threading `:rf.egress/include-sensitive? true` into project-egress and
  ;; lifting the app-db sensitive axis the operator just declined. Parsed
  ;; safely, "false" stays false ⇒ the projection runs (epoch still routes
  ;; through project-egress) but WITHOUT the sensitive opt — sensitive
  ;; app-db leaves stay :rf/redacted.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              ;; Gate ON so the per-call arg is consulted at all.
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :trace true
                                           :include-sensitive "false"})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form "dispatch-and-collect"))
                     (is (str/includes? form "project-egress")
                         "the epoch STILL routes through project-egress (never a raw bypass)")
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                         "the off-box-tool boundary is named")
                     (is (not (str/includes? form ":rf.egress/include-sensitive? true"))
                         "string \"false\" stays FALSE — the sensitive axis is NOT lifted (no raw boolean fail-open)"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

(deftest trace-include-sensitive-string-true-lifts-axis
  ;; The positive control: the genuine truthy STRING "true" DOES thread
  ;; the sensitive opt — `parse-bool-arg` reads it as true. Pins that the
  ;; safe parse isn't over-strict (it still honours the documented
  ;; string-true accept shape, the same one dispatch-dry-run honours).
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 7}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]" :trace true
                                           :include-sensitive "true"})))
          (.then (fn [_]
                   (let [form @captured]
                     (is (str/includes? form ":rf.egress/include-sensitive? true")
                         "string \"true\" lifts the app-db sensitive axis (accept-shape parity)"))
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
                     (is (not (str/includes? form "project-egress"))
                         "sync consequence carries no raw app-db — no projection wrap"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Boot-gate signal: the DEFAULT cascade-summary `:event-vector`
;; redaction.
;;
;; `redact-sensitive-event-vector` (runtime) redacts the cascade-summary's
;; `:event-vector` (the raw `:trigger-event`) ONLY when the runtime's
;; `raw-state-config` is at `{:allow-raw-state? false}`. That config
;; DEFAULTS to `:allow-raw-state? true` and flips to the server's gate
;; state ONLY when a tool calls `configure-raw-state!` via
;; `raw-state/signal-runtime!`. So `dispatch` MUST signal before its eval;
;; otherwise a FIRST-in-session sensitive dispatch would run with the
;; runtime still permissive and ship the raw event vector under the
;; default OFF gate.
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
  ;; cascade-summary `:event-vector` redacts for a sensitive epoch. The
  ;; redaction covers the default path, not trace-mode only.
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

;; ---------------------------------------------------------------------------
;; Cascade summary — the runtime's `:cascade-summary` slot rides through
;; the dispatch tool unchanged.
;; ---------------------------------------------------------------------------

(deftest cascade-summary-passes-through-on-sync-mode
  ;; The runtime returns a structured envelope including
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
;; Opts composition — an absent optional arg emits no slot. The
;; colon-prefixed `frame` arg routing to the well-formed `:rf/xray` (never
;; the malformed `::rf/xray` a raw `(keyword ...)` would mint) is pinned
;; by the `:dispatch/frame-targeted-routes` corpus fixture.
;; ---------------------------------------------------------------------------

(deftest absent-optional-args-emit-an-empty-opts-map
  ;; Absent `frame` arg ⇒ no `:frame` key (the runtime resolves the
  ;; operating frame itself); no replay ⇒ no strict mint policy; no
  ;; interceptor-overrides / fx-overrides / cofx arg ⇒ no such key. One
  ;; equality guards every stray slot, a nil-frame one included.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :queued? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]"})))
          (.then (fn [_]
                   (is (= {} (opts-arg captured))
                       "no optional arg ⇒ no opts slot")
                   (done)))))))


;; ---------------------------------------------------------------------------
;; Render-settle — `:await-render`.
;;
;; `dispatch :await-render true` resolves only AFTER the substrate has
;; flushed the new state to the DOM and the next paint is scheduled, so
;; `dispatch -> observe` is one deterministic step. Two invariants, pinned
;; WITHOUT a sleep:
;;
;;   1. SHAPE: the emitted settle form routes the flush through the
;;      substrate-agnostic adapter primitive `re-frame.interop/after-render`
;;      and the paint boundary `js/requestAnimationFrame` — NOT a Reagent
;;      API and NOT a `setTimeout` sleep. (the corpus fixture
;;      `:dispatch/await-render-settles`, whose must-not-contain check covers
;;      every form sent)
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
    ;; The configure-raw-state! signal hits the `:else` branch (it is
    ;; neither the await wrapper nor a mailbox read) and resolves to nil;
    ;; reset the signal cache so it fires each test.
    (raw-state/reset-runtime-signal-cache!)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn))))))

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
  ;; tool MUST surface that as an :isError (the failure-envelope invariant
  ;; holds through the settle path), never a {:mode :sync :settled? true}
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
;; The :await-render + :trace path MUST project the epoch off-box, exactly
;; as the non-await :trace / :settle path does. Under await-render an
;; explicit :trace still resolves to dispatch-and-collect (the RAW
;; :epoch); the render-settle Promise wraps the runtime call, and a wrap
;; emitting the BARE runtime call would ship the raw epoch off-box
;; un-projected (the leak). The await-render result routes through
;; `project-egress` (egress/project-dispatch-result-src) INSIDE the settle
;; form, the same redaction the non-await path applies.
;; ---------------------------------------------------------------------------

(deftest await-render-trace-projects-epoch-off-box-when-gate-off
  ;; THE leak. Gate OFF (the published default) + :await-render :trace ⇒
  ;; the await wrap form MUST route the dispatch-and-collect :epoch through
  ;; the framework's off-box projection before it crosses the wire. A wrap
  ;; form that was the bare `(rt/dispatch-and-collect …)` call with NO
  ;; projection would ship the raw db-before/db-after/trigger-event
  ;; off-box.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? true :epoch-id 9 :frame :rf/default :settled? true
                        :epoch {:frame :rf/default}}}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :await-render true :trace true})))
          (.then (fn [_]
                   (let [form @wrap-form*]
                     (is (string? form))
                     (is (str/includes? form "dispatch-and-collect")
                         "await-render :trace still routes to the raw-epoch dispatch-and-collect")
                     (is (str/includes? form "re-frame.core/project-egress")
                         "gate OFF ⇒ the await-render :epoch routes through project-egress")
                     (is (str/includes? form ":rf.egress/profile :rf.egress/off-box-tool")
                         "the await-render epoch projects under the off-box-tool boundary")
                     (is (str/includes? form "re-frame.interop/after-render")
                         "the render-settle flush is intact — projection composes with the settle")
                     (is (str/includes? form ":settled? true")
                         "the settle form still merges :settled? true (over the projected envelope)"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

(deftest await-render-trace-include-sensitive-routes-through-projection
  ;; Gate ON + :include-sensitive true on the await-render :trace path does
  ;; NOT bypass the projection — it threads `{:rf.egress/include-sensitive? true}`
  ;; INTO project-egress (app-db sensitive axis only), exactly like the
  ;; non-await path. fx-args / runtime-db stay fail-closed.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? true :epoch-id 9 :frame :rf/default :settled? true
                        :epoch {:frame :rf/default}}}
            (fn []
              (raw-state/set-allow-raw-state! true)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:list/toggle]"
                                           :await-render true :trace true
                                           :include-sensitive true})))
          (.then (fn [_]
                   (let [form @wrap-form*]
                     (is (str/includes? form "project-egress")
                         "include-sensitive STILL projects — never a raw bypass on the await path")
                     (is (str/includes? form ":rf.egress/include-sensitive? true")
                         "the app-db sensitive axis is threaded INTO the projection")
                     (is (not (str/includes? form ":rf.egress/include-fx-args?"))
                         "fx-args axis is NOT lifted by include-sensitive (orthogonal)")
                     (is (not (str/includes? form ":rf.egress/include-runtime-db?"))
                         "runtime-db axis is NOT lifted by include-sensitive (orthogonal)"))
                   (raw-state/set-allow-raw-state! false)
                   (done)))))))

(deftest await-render-plain-does-not-project
  ;; The control: a plain :await-render (no :trace) routes to
  ;; dispatch-consequence!, which carries no raw app-db — so its wrap form
  ;; must NOT wrap the call in project-egress (the projection is for the
  ;; epoch-bearing :trace path only).
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? true :epoch-id 9 :frame :rf/default :settled? true}}
            (fn []
              (raw-state/set-allow-raw-state! false)
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:counter/inc]" :await-render true})))
          (.then (fn [_]
                   (let [form @wrap-form*]
                     (is (str/includes? form "dispatch-consequence!")
                         "plain await-render forces the sync consequence surface")
                     (is (not (str/includes? form "project-egress"))
                         "the consequence carries no raw app-db — no projection wrap"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; cofx wire shape (EP-0010 + EP-0017) — a scripted recordable-coeffect
;; map is parsed as EDN data and threaded into the dispatch opts under the
;; flat `:rf.cofx` key the router reads (which preserves a caller-supplied
;; map verbatim). A dispatched event then carries the agent's exact
;; `:rf/time-ms` / owner-qualified recordable facts, so the resulting
;; state is REPRODUCIBLE — the agent-replay-determinism affordance the EP
;; calls for. The MCP arg is `cofx`, the opts key `:rf.cofx`, and the time
;; fact `:rf/time-ms`. A malformed value short-circuits to an :isError
;; envelope before the eval rather than threading a value the runtime's
;; :rf/dispatch-opts validation would reject.
;; ---------------------------------------------------------------------------

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
;; Strict replay (EP-0017 §6 / Tool-Pair §Replay). Replay
;; re-drives a recorded event through the app's own handlers by re-presenting
;; the recorded `:rf.cofx` UNDER `:rf.cofx/mint-policy :strict`, so a recorded
;; fact MISSING from the token fails LOUDLY (`:rf.error/missing-required-cofx`)
;; instead of being silently re-minted — the exact divergence the recording
;; discipline exists to kill. Ordinary live `cofx` dispatch stays `:live`
;; (the router default) unless the named `replay` affordance is selected.
;; ---------------------------------------------------------------------------

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
                   (let [opts (opts-arg captured)]
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
                   (let [opts (opts-arg captured)]
                     (is (= {:rf/time-ms 1781078400123} (:rf.cofx opts))
                         "the scripted token still rides")
                     (is (not (contains? opts :rf.cofx/mint-policy))
                         "live cofx dispatch carries NO strict opt — stays :live"))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; Tool-Pair pair-mcp re-supply: `:interceptor-overrides` wire arg +
;; strict-replay re-supply of the recorded envelope overrides
;; (`:fx-overrides` / `:interceptor-overrides`) alongside `:rf.cofx` +
;; `:rf.cofx/mint-policy :strict`. `:rf/epoch-record` carries
;; `:fx-overrides` / `:interceptor-overrides` as bare slots, and the
;; Tool-Pair §Replay re-supply rule says a replay re-supplies them. These
;; tests pin the ACTUAL pair-mcp dispatch-tool wiring for that rule — the
;; core+epoch capture is covered by those artefacts' own tests.
;;
;; Without an `:interceptor-overrides` wire arg the dispatch tool would
;; silently ignore it (an unrecognised key on the JS args object), so a
;; replay of a recorded epoch with an active `:interceptor-overrides`
;; would replay under a DIFFERENT effective chain than the original run —
;; the exact silent divergence Tool-Pair §Replay forbids. The headline
;; regression test below goes RED if the emitted opts map is missing
;; `:interceptor-overrides`.
;; ---------------------------------------------------------------------------

(deftest interceptor-overrides-threaded-into-opts
  ;; The headline wire-shape case: a bare colon-tolerant keyword ref key/
  ;; value pair threads into the emitted opts under `:interceptor-overrides`
  ;; as coerced keyword refs — the `:interceptor-overrides` sibling of the
  ;; `:fx-overrides` wire contract.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 1 :db-changed? false
                                         :changed-paths [] :effects-fired [] :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]"
                                           :interceptor-overrides #js {":auth/required" ":story/skip-auth"}})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [opts (opts-arg captured)]
                     (is (= {:auth/required :story/skip-auth} (:interceptor-overrides opts))
                         "the ref key/value coerce to keyword refs in the emitted opts"))
                   (done)))))))

(deftest replay-resupplies-recorded-fx-and-interceptor-overrides
  ;; THE HEADLINE REGRESSION TEST. A strict replay is
  ;; faithful only when the recorded envelope's OWN `:fx-overrides` /
  ;; `:interceptor-overrides` ride along with `:rf.cofx` +
  ;; `:rf.cofx/mint-policy :strict` — per Tool-Pair §Replay's
  ;;
  ;;   (merge {:rf.cofx (:rf.cofx record) :rf.cofx/mint-policy :strict}
  ;;          (select-keys record [:fx-overrides :interceptor-overrides]))
  ;;
  ;; splat. This simulates a caller re-supplying a recorded epoch's own
  ;; `:fx-overrides` / `:interceptor-overrides` (read off the
  ;; `:rf/epoch-record`) as the `fx-overrides` / `interceptor-overrides`
  ;; wire args alongside `replay true` + the recorded `cofx` token, and
  ;; asserts ALL FOUR re-supplied facts land in the SAME emitted opts map —
  ;; never a partial re-supply that leaves the replay running under a
  ;; different effective fx / interceptor chain than the original run.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :epoch-id 12 :db-changed? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:todo/add {:text \"buy milk\"}]"
                                           :replay true
                                           :cofx "{:rf/time-ms 1781078400123 :counter/delta 4}"
                                           :fx-overrides #js {":http" ":stub-http"}
                                           :interceptor-overrides #js {":audit/record-event" nil}})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [opts (opts-arg captured)]
                     (is (= {:rf/time-ms 1781078400123 :counter/delta 4} (:rf.cofx opts))
                         "the recorded :rf.cofx token rides verbatim")
                     (is (= :strict (:rf.cofx/mint-policy opts))
                         "replay hard-wires :rf.cofx/mint-policy :strict")
                     (is (= {:http :stub-http} (:fx-overrides opts))
                         "the recorded :fx-overrides re-supplies alongside :rf.cofx")
                     (is (= {:audit/record-event nil} (:interceptor-overrides opts))
                         (str "the recorded :interceptor-overrides re-supplies alongside :rf.cofx — "
                              "without it the replay would run under a DIFFERENT "
                              "effective interceptor chain than the original run")))
                   (done)))))))

;; ---------------------------------------------------------------------------
;; `:rf/fn-override` sentinel fail-loud (Tool-Pair §Replay). A
;; recorded `:fx-overrides` entry carrying the opaque marker (a fn-valued
;; override the router could not serialize) makes the run UNREPLAYABLE
;; under :strict. The dispatch tool MUST refuse rather than re-supplying
;; the literal sentinel as if it were a real fx redirect.
;; ---------------------------------------------------------------------------

(deftest replay-with-fn-override-sentinel-fails-loud-before-touching-nrepl
  (async done
    (-> (dispatch/dispatch-tool (fresh-conn)
                                #js {:event "[:cart/checkout]"
                                     :replay true
                                     :cofx "{:rf/time-ms 1781078400123}"
                                     :fx-overrides #js {":http" ":rf/fn-override"}})
        (.then (fn [r]
                 (is (err? r)
                     (str "the opaque :rf/fn-override marker makes the record UNREPLAYABLE — "
                          "the tool must refuse, never silently redirect to a non-existent fx"))
                 (let [edn (read-result-text r)]
                   (is (= :rf.error/unreplayable-fx-override (:reason edn)))
                   (is (= :http (:target edn))))
                 (done))))))

;; ---------------------------------------------------------------------------
;; The parsed event reaches the runtime as DATA.
;;
;; `parse-event-arg`'s vector check stops a whole host form at the door, and
;; its docstring says why: the payload must be data, never source. But the
;; check is on the OUTER shape only, so what gets past it must be quoted
;; rather than emitted with `pr-str` — which renders a value as source.
;; Printed, an event whose payload contained a list or a symbol would change
;; meaning on the way to the handler, and one shaped like the emitter's own
;; IR would be spliced in as raw source outright. Both would happen while
;; the runtime call is being CONSTRUCTED, ahead of anything the dispatch fn
;; validates, and both would be reachable with `eval-cljs` disabled — the
;; narrow explicit boundary the parser's own docstring draws.
;;
;; These deftests read the emitted argument through `quoted-datum`, which
;; asks what the form EVALUATES to rather than what it prints as. A
;; read-back-as-EDN assertion cannot see the defect at all: `pr-str`'d
;; source and quoted data read back identically as EDN, and only evaluation
;; tells them apart.
;; ---------------------------------------------------------------------------

(deftest event-payload-lists-are-not-evaluated-before-dispatch
  ;; `(inc 41)` inside the payload is ordinary data. Unquoted it evaluates
  ;; to 42 and the handler is handed a number where the caller sent a list.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/add (inc 41)]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (is (= [:cart/add '(inc 41)] (quoted-datum (event-arg captured)))
                       "the nested list reaches the runtime as a list")
                   (is (not (str/includes? @captured "dispatch-consequence! [:cart/add"))
                       "the event does not ride as a bare unquoted literal")
                   (done)))))))

;; The OPTS map beside the event carries caller EDN too, so it is quoted
;; like the event.
;;
;; The opts map is where the scripted coeffects ride. Printed rather than
;; quoted, a `cofx "{:review/fact (inc 41)}"` would reach the router as
;; `{:review/fact 42}` — the replay would run on a DIFFERENT causal fact
;; from the one scripted, which is precisely the determinism a recorded
;; cofx exists to provide. Same mechanism for a parameterised
;; `:interceptor-overrides` value.

(deftest cofx-fact-lists-are-not-evaluated-before-dispatch
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:ok? true :no-op? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:review/event]"
                                           :cofx "{:review/fact (inc 41)}"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (is (= '(inc 41) (get-in (opts-arg captured) [:rf.cofx :review/fact]))
                       "the scripted fact reaches the router as the datum it was")
                   (is (not (str/includes? @captured ":review/fact 42"))
                       "and was not evaluated while the call was constructed")
                   (done)))))))

(deftest await-render-event-payload-is-quoted-too
  ;; `:await-render` composes the runtime call through `render-settle-form`
  ;; rather than the plain emit path, so it needs its own arm — the same
  ;; parsed EDN travels a second route to the same runtime fn.
  (async done
    (let [wrap-form*  (atom nil)
          read-count* (atom 0)]
      (-> (with-staged-mailbox-eval!
            {:wrap-form* wrap-form* :read-count* read-count*
             :pending-polls 0
             :resolved {:ok? true :epoch-id 9 :settled? true}}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/add (inc 41)]"
                                           :await-render true})))
          (.then (fn [_]
                   (let [form @wrap-form*]
                     (is (str/includes? form "(quote [:cart/add (inc 41)])")
                         "the settle form carries the event as quoted data")
                     (is (not (str/includes? form "dispatch-consequence! [:cart/add"))
                         "and not as a bare unquoted literal"))
                   (done)))))))
