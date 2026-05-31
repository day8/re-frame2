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
  (:require [cljs.test :refer-macros [deftest is async]]
            [cljs.reader]
            [clojure.string :as str]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.tools.dispatch :as dispatch]))

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
  "Install a stub `cljs-eval-value` that records the form string into
  `captured*` and resolves to `canned-value`. Restore in `.finally`."
  [captured* canned-value body-fn]
  (let [orig nrepl/cljs-eval-value
        stub (fn
               ([_conn _build-id form-str]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value))
               ([_conn _build-id form-str _opts]
                (reset! captured* form-str)
                (js/Promise.resolve canned-value)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

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
  ;; inside the runtime call.
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:dispatched? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [form @captured]
                     (is (string? form))
                     ;; The runtime call is `(rt/pair-dispatch! [:cart/checkout] {})`
                     ;; (or `pair-dispatch-sync!` / `dispatch-and-collect` for
                     ;; sync / trace modes). The event vector rides as an EDN
                     ;; literal — pinned via the `pr-str` shape.
                     (is (re-find #"pair-dispatch!" form))
                     (is (re-find #"\[:cart/checkout\]" form))
                     ;; And critically — NO host-form splice. The form is
                     ;; standalone CLJS that the runtime can read back as
                     ;; data. We can round-trip the outer call as EDN.
                     (let [parsed (cljs.reader/read-string form)]
                       (is (= 're-frame2-pair.runtime/pair-dispatch! (first parsed))
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

(deftest sync-mode-routes-to-pair-dispatch-sync
  (async done
    (let [captured (atom nil)]
      (-> (with-captured-eval! captured {:dispatched? true}
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]" :sync true})))
          (.then (fn [_]
                   (is (re-find #"pair-dispatch-sync!" @captured))
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
                     (is (= 're-frame2-pair.runtime/pair-dispatch-sync! (first parsed)))
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
  ;; Queued dispatch may return BEFORE the cascade drains. The runtime
  ;; reports `:cascade-summary-pending? true` and `:before-epoch-id`
  ;; in that case; the tool surfaces them verbatim so callers can poll
  ;; watch-epochs from the recorded pre-dispatch head.
  (async done
    (let [runtime-result {:ok? true :queued? true
                          :frame :rf/default
                          :cascade-summary-pending? true
                          :before-epoch-id 12
                          :hint "..."}]
      (-> (with-captured-eval! (atom nil) runtime-result
            (fn []
              (dispatch/dispatch-tool (fresh-conn)
                                      #js {:event "[:cart/checkout]"})))
          (.then (fn [r]
                   (is (not (err? r)))
                   (let [edn (read-result-text r)]
                     (is (true? (:ok? edn)))
                     (is (= :queued (:mode edn)))
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
  (let [orig nrepl/cljs-eval-value
        respond (fn [form-str]
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
    (-> (js/Promise.resolve nil)
        (.then (fn [_] (body-fn)))
        (.finally (fn [] (set! nrepl/cljs-eval-value orig))))))

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
                     ;; commit before the render can settle).
                     (is (str/includes? form "pair-dispatch-sync!")
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
