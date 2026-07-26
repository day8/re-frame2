(ns re-frame2-pair-mcp.error-boundary-test
  "What a consumer's AI agent actually READS when a pair-mcp tool throws.

  A bare-keyword ex-message is tolerable inside a tool's internals. It is
  not tolerable once it is relayed onto the MCP tool surface, because the
  reader there is an agent trying to ACT on it: `:rf.error/pair-mcp-…`
  strands every actionable word somewhere the agent never sees. PR #7036
  converted this surface's throws to the canonical Spec 009 shape — a
  human sentence plus a trailing `[:rf.error/<id>]` greppability token —
  and `tools/` is bundle-isolated from `re-frame.error`, so those
  messages are hand-rolled at each site and no shared builder can keep
  them honest. Until this namespace nothing stood behind them but a
  docstring.

  ## Two relays, not one — the finding this bead turned up

  `tools/re-frame2-pair-mcp/src` has exactly four `throw` sites, and a
  throw reaches the agent by one of two DIFFERENT relays depending on
  WHERE in a tool body it fires. The two relays keep opposite halves of
  the exception:

  - `server.cljs` `invoke-and-guard` → `{:reason :handler-threw :message
    (.-message err)}`. Keeps the MESSAGE, drops the ex-data. Reached by
    anything thrown while the tool body builds its request — before the
    nREPL round-trip.

  - `tools/probe.cljs` `err->result` → `(merge {:ok? false} (ex-data
    err))`. Keeps the EX-DATA, drops the message entirely. Reached by
    anything thrown from the `on-value` callback of
    `eval-after-runtime!` / `eval-after-runtime-signalled!` — i.e. all
    response shaping, after the round-trip.

  Which relay a given throw meets decides which half of the Spec 009
  shape the agent can read, so each is pinned here at its own boundary:

  - **rt-let binding shape** (`tools/eval-form` `emit-name`) fires during
    synchronous form construction, so it meets `invoke-and-guard`. Its
    message IS the consumer contract, and the first test pins the human
    sentence and the token end to end.

  - **unknown wire `:kind`** (`tools/wire-pipeline` `run-wire-pipeline`)
    fires only from response shaping — all five call sites across
    `snapshot`, `get-path`, `read-sub`, `trace-window` and `watch-epochs`
    sit inside an `on-value` callback — so it meets `err->result`, whose
    ex-data half carries the agent's sentence in `:reason` and its
    discriminator in `:rf.error/id`. That is what the second test pins.
    Its ex-MESSAGE is unreachable by any consumer; see rf2-6tzm5.

  The other two throws (`server.cljs`'s `:rf.error/pair-mcp-ambiguous-shadow`
  and `:rf.error/pair-mcp-nrepl-port-not-found`) are raised inside discovery
  and caught by `handle-call*`'s own arm, which rebuilds a payload from
  `:rf.error/id` and never reads the message. Their bare-keyword messages
  are genuinely tool-internal and stay that way.

  The rf2-jquiy audit also named a diff-encode validation family. It is
  not reachable from this surface at all: mcp-base's grammar gate
  resolves `malli.core/validate` at runtime and soft-passes when Malli is
  absent — and Malli is deliberately absent from pair-mcp's CLJS
  classpath (`tools/mcp-base/deps.edn`) — while the shipped `:server`
  build additionally DCEs the gate via `:closure-defines
  {re-frame.mcp-base.diff-encode/validate-patches? false}`. A row for it
  could not fail in either build, and a boundary row that cannot fail is
  decoration.

  ## What is real here, and what is forced

  Both covered throws are programmer-typo guards: every `rt-let` call
  site passes literal quoted symbols and every `run-wire-pipeline` call
  site passes a literal `:kind`, so no tool ARGUMENT reaches either. That
  is exactly why they were unpinned, and exactly why a future edit
  reverting one of their messages is invisible to every other test.

  So the seam corrupts only the INPUT, at the production call site, and
  nothing else: the real `ef/rt-let` builds the malformed binding vector,
  the real `ef/emit` walks it, the real `emit-name` composes the message,
  and the real `run-wire-pipeline` rejects the real out-of-vocabulary
  `:kind`. Downstream of the throw everything is the shipped path — real
  `handle-call` → real `ensure-connection!` (over a seeded conn) → real
  `tools/invoke` → real relay → real envelope. `trace-window` is the
  carrier for both because one tool body reaches both relays: it builds
  its form with `ef/rt-let` and shapes its response with
  `run-wire-pipeline`."
  (:require [cljs.test :refer-macros [deftest is testing async use-fixtures]]
            [clojure.string :as str]
            [re-frame2-pair-mcp.nrepl :as nrepl]
            [re-frame2-pair-mcp.server :as server]
            [re-frame2-pair-mcp.test-utils :as tu]
            [re-frame2-pair-mcp.tools.eval-form :as ef]
            [re-frame2-pair-mcp.tools.wire-pipeline :as wp]))

(use-fixtures :each
  {:before (fn [] (server/reset-session-state-for-tests!))
   :after  (fn [] (server/reset-session-state-for-tests!))})

;; ---------------------------------------------------------------------------
;; The real boundary.
;; ---------------------------------------------------------------------------

(def ^:private trace-canned
  "What `trace-window`'s eval form returns after server-side slicing. An
  empty page: the rows assert on the ERROR path, so the payload only has
  to let the happy path reach the response-shaping step."
  {:epochs [] :id-aged-out? false :requested-id nil
   :head-id nil :next-id nil :history-count 0 :remaining 0})

(defn- drive
  "Drive the REAL `tools/call` path for `trace-window` and return a
  Promise of the MCP result envelope the SDK ships.

  Seeding the conn via `mark-discovered-for-tests!` (no port-file, so
  `ensure-connection!` takes its cached fast path) is what routes the
  drive through `handle-call*` → `tools/invoke` rather than the
  discovery-error arm — the distinction every assertion below rests on.
  The eval stub answers the runtime-preload probe `true` and every other
  form with `trace-canned`, mirroring `trace_window_test`."
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
  envelope to `check`, then restore. `install!` / `restore!` are 1-arity
  fns over the seam because CLJS `set!` needs the var literal at the call
  site. Restore is identity-guarded the same way `tu/restore-eval!` is,
  so a late `.finally` cannot clobber a neighbouring test's seam."
  [install! restore! seam check done]
  (install! seam)
  (-> (drive)
      (.then (fn [result] (check result)))
      (.catch (fn [e]
                (is false (str "the boundary drive rejected: " (.-message e)))))
      (.finally (fn [] (restore! seam) (done)))))

;; ---------------------------------------------------------------------------
;; Relay 1 — `invoke-and-guard`: the MESSAGE is the consumer contract.
;; ---------------------------------------------------------------------------

(deftest rt-let-binding-shape-reaches-the-agent-as-a-readable-message
  ;; Reverting `emit-name`'s message to a bare `(str error-kw)` reds the
  ;; sentence assertion; dropping the trailing token reds the token
  ;; assertion; a regression that turns the tool error into a rejected
  ;; promise reds `tu/error?` and takes the whole row with it.
  (async done
    (let [orig ef/rt-let
          ;; The REAL constructor, handed a binding name that is not a
          ;; symbol. `trace-window` then emits this form itself, and the
          ;; real `emit-name` composes the message under test.
          seam (fn [bindings & body-forms]
                 (apply orig (assoc (vec bindings) 0 "not-a-symbol") body-forms))]
      (drive-with-seam
        (fn [s] (set! ef/rt-let s))
        (fn [s] (when (identical? ef/rt-let s) (set! ef/rt-let orig)))
        seam
        (fn [result]
          (is (tu/error? result)
              "a handler throw is an MCP tool error, not a rejected promise")
          (let [edn (tu/extract-edn result)
                msg (:message edn)]
            (is (= :handler-threw (:reason edn))
                "form construction throws BEFORE the round-trip, so the
                 drive really did meet invoke-and-guard's relay")
            (is (re-find #"rt-let binding name must be a symbol" msg)
                (str "the agent reads a human sentence, not a keyword\n  got: "
                     (pr-str msg)))
            (is (str/includes? msg "[:rf.error/pair-mcp-rt-let-binding-bad-shape]")
                (str "the canonical [:rf.error/…] token rides the message\n  got: "
                     (pr-str msg)))))
        done))))

(deftest invoke-and-guard-relays-the-producers-message-verbatim
  ;; The control, and the reason the row above is a BOUNDARY row rather
  ;; than a unit test in disguise: `invoke-and-guard` adds nothing and
  ;; rewrites nothing. Whatever the producer put in the message is what
  ;; the agent reads — so a producer-side message regression is, with no
  ;; intermediate to absorb it, a consumer-side regression.
  (async done
    (let [orig ef/rt-let
          seam (fn [& _] (throw (ex-info "a plain sentence, no token" {:x 1})))]
      (drive-with-seam
        (fn [s] (set! ef/rt-let s))
        (fn [s] (when (identical? ef/rt-let s) (set! ef/rt-let orig)))
        seam
        (fn [result]
          (is (tu/error? result))
          (let [edn (tu/extract-edn result)]
            (is (= :handler-threw (:reason edn)))
            (is (= "a plain sentence, no token" (:message edn))
                "the relay neither trims nor decorates the producer's message")))
        done))))

;; ---------------------------------------------------------------------------
;; Relay 2 — `probe/err->result`: the EX-DATA is the consumer contract.
;; ---------------------------------------------------------------------------

(deftest unknown-wire-pipeline-kind-reaches-the-agent-as-readable-ex-data
  ;; Reverting the ex-data's `:reason` to a bare keyword reds the sentence
  ;; assertion; dropping `:rf.error/id` reds the discriminator assertion.
  (async done
    (let [orig wp/run-wire-pipeline
          ;; The REAL pipeline, handed a `:kind` outside its closed
          ;; three-case dispatch — the shape a contributor adding a new
          ;; payload kind, or typing an existing one, actually produces.
          seam (fn [payload opts] (orig payload (assoc opts :kind :not-a-wire-kind)))]
      (drive-with-seam
        (fn [s] (set! wp/run-wire-pipeline s))
        (fn [s] (when (identical? wp/run-wire-pipeline s)
                  (set! wp/run-wire-pipeline orig)))
        seam
        (fn [result]
          (is (tu/error? result)
              "a response-shaping throw is a tool error, not a rejected promise")
          (let [edn (tu/extract-edn result)]
            (is (= false (:ok? edn))
                "response shaping throws AFTER the round-trip, so the drive
                 really did meet err->result's relay")
            (is (= :rf.error/pair-mcp-unknown-wire-pipeline-kind
                   (:rf.error/id edn))
                "the machine discriminator the agent BRANCHES on rides the
                 wire, namespace intact")
            (is (re-find #"unknown :kind" (str (:reason edn)))
                (str "and the human sentence the agent READS rides with it\n  got: "
                     (pr-str (:reason edn))))
            (is (= :not-a-wire-kind (:kind edn))
                "along with the actionable slot — WHICH kind was unknown")
            ;; The finding, made durable. `err->result` relays ex-data and
            ;; DROPS `(ex-message err)`, so `run-wire-pipeline`'s carefully
            ;; composed message — and the `[:rf.error/…]` token in it —
            ;; reaches nobody. If this ever goes non-nil, that is the
            ;; improvement rf2-6tzm5 asks for: assert the message here and
            ;; correct the claim in `wire_pipeline.cljs`'s throw comment,
            ;; which today describes relay 1's behaviour at a relay-2 site.
            (is (nil? (:message edn))
                "the ex-MESSAGE does not reach a consumer at this relay")))
        done))))
