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
            [applied-science.js-interop :as j]
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

(defn- read-result-text
  "Extract the EDN text from a `wire/ok-text` / `wire/err-text` JS
  envelope and read it back as CLJS data."
  [result-js]
  (let [content (j/get result-js :content)
        item    (when (array? content) (aget content 0))
        text    (when item (j/get item :text))]
    (cljs.reader/read-string text)))

(defn- err? [result-js]
  (true? (j/get result-js :isError)))

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
