(ns re-frame2-pair-mcp.error-boundary-test
  "What a consumer's AI agent actually READS when a pair-mcp tool throws.

  A throw that escapes a tool body reaches the agent by one of two relays,
  depending on WHERE it fires. Both carry the message AND the ex-data, and
  differ only in which `:reason` wins:

  - `server.cljs` `invoke-and-guard` — anything thrown while the tool body
    builds its request, before the nREPL round-trip. The ex-data merges
    UNDER `{:reason :handler-threw :message …}`: `:reason` is the
    envelope's discriminator, and the site's rides in `:rf.error/id`.
  - `tools/probe.cljs` `err->result` — anything thrown while shaping the
    response. The ex-data merges OVER `{:ok? false :message …}`: its
    `:reason` is the payload's own discriminator.

  The relayed ex-data is WIRE DATA: `tu/extract-edn` is the consumer's EDN
  reader, so one unreadable value reds every assertion here at once.

  Both covered throws are programmer-typo guards no tool argument reaches,
  which is why no other test reaches them. The seam corrupts only the
  INPUT at the production call site; downstream of the throw everything is
  the shipped path — real `handle-call` → `ensure-connection!` (over a
  seeded conn) → `tools/invoke` → relay → envelope. `trace-window` carries
  both: it builds its form with `ef/rt-let` and shapes its response with
  `run-wire-pipeline`."
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

(def ^:private trace-canned
  "An empty `trace-window` page: enough for the happy path to reach the
  response-shaping step."
  {:epochs [] :id-aged-out? false :requested-id nil
   :head-id nil :next-id nil :history-count 0 :remaining 0})

(defn- drive
  "Drive the REAL `tools/call` path for `trace-window`. Seeding the conn
  (no port-file) routes it through `handle-call*` → `tools/invoke` rather
  than the discovery-error arm."
  []
  (server/reset-session-state-for-tests!)
  (server/mark-discovered-for-tests! (nrepl/make-conn 0 "127.0.0.1"))
  (let [orig nrepl/cljs-eval-value
        answer (fn [form-str]
                 (js/Promise.resolve
                   (if (re-find #"__re_frame2_pair_runtime" form-str)
                     true
                     trace-canned)))
        stub (fn
               ([_conn _build-id form-str] (answer form-str))
               ([_conn _build-id form-str _opts] (answer form-str)))]
    (set! nrepl/cljs-eval-value stub)
    (-> (server/handle-call-for-tests {} "trace-window"
                                      (tu/args->js {:ms 1000}) nil)
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- drive-with-seam
  "Install `seam` over its production var, drive the boundary, hand the
  envelope to `check`, then restore (identity-guarded, like
  `tu/restore-eval!`)."
  [install! restore! seam check done]
  (install! seam)
  (-> (drive)
      (.then (fn [result] (check result)))
      (.catch (fn [e]
                (is false (str "the boundary drive rejected: " (.-message e)))))
      (.finally (fn [] (restore! seam) (done)))))

(deftest rt-let-binding-shape-reaches-the-agent-as-a-readable-message
  ;; Relay 1. The REAL `ef/rt-let`, handed a binding name that is not a
  ;; symbol: `trace-window` emits the form and the real `emit-name` composes
  ;; the message — a human sentence plus the trailing [:rf.error/…] token.
  (async done
    (let [orig ef/rt-let
          seam (fn [bindings & body-forms]
                 (apply orig (assoc (vec bindings) 0 "not-a-symbol") body-forms))]
      (drive-with-seam
        (fn [s] (set! ef/rt-let s))
        (fn [s] (when (identical? ef/rt-let s) (set! ef/rt-let orig)))
        seam
        (fn [result]
          (let [edn (tu/extract-edn result)]
            (is (tu/error? result) "a handler throw is an MCP tool error, not a rejected promise")
            (is (re-find #"rt-let binding name must be a symbol.*\[:rf\.error/pair-mcp-rt-let-binding-bad-shape\]"
                         (str (:message edn)))
                (str "got: " (pr-str (:message edn))))
            ;; `emit-name`'s ex-data carries its own `:reason`; the relay's
            ;; :handler-threw must win, and the site's slots ride beside it.
            (is (= {:reason      :handler-threw
                    :rf.error/id :rf.error/pair-mcp-rt-let-binding-bad-shape
                    :where       're-frame2-pair-mcp/rt-let
                    :recovery    :no-recovery
                    :name        (pr-str "not-a-symbol")}
                   (select-keys edn [:reason :rf.error/id :where :recovery :name])))))
        done))))

(deftest unknown-wire-pipeline-kind-reaches-the-agent-as-readable-ex-data
  ;; Relay 2. The REAL pipeline, handed a `:kind` outside its closed
  ;; three-case dispatch. Both halves must arrive: the ex-data the agent
  ;; branches on, and the ex-message with its token.
  (async done
    (let [orig wp/run-wire-pipeline
          seam (fn [payload opts] (orig payload (assoc opts :kind :not-a-wire-kind)))]
      (drive-with-seam
        (fn [s] (set! wp/run-wire-pipeline s))
        (fn [s] (when (identical? wp/run-wire-pipeline s)
                  (set! wp/run-wire-pipeline orig)))
        seam
        (fn [result]
          (let [edn (tu/extract-edn result)]
            (is (tu/error? result) "a response-shaping throw is a tool error, not a rejected promise")
            (is (= {:ok?         false
                    :rf.error/id :rf.error/pair-mcp-unknown-wire-pipeline-kind
                    :kind        :not-a-wire-kind}
                   (select-keys edn [:ok? :rf.error/id :kind])))
            (is (re-find #"unknown :kind" (str (:reason edn)))
                (str "got: " (pr-str (:reason edn))))
            (is (re-find #"unknown :kind.*\[:rf\.error/pair-mcp-unknown-wire-pipeline-kind\]"
                         (str (:message edn)))
                (str "got: " (pr-str (:message edn))))))
        done))))
