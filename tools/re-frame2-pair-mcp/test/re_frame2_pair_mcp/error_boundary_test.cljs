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
  WHERE in a tool body it fires. The two relays used to keep OPPOSITE
  halves of the exception; both now carry the whole of it, and differ
  only in the precedence they give the two `:reason` slots:

  - `server.cljs` `invoke-and-guard` → the ex-data merged UNDER
    `{:reason :handler-threw :message (.-message err)}`. Carries both
    halves since rf2-qoih4. Reached by anything thrown while the tool
    body builds its request — before the nREPL round-trip. `:reason`
    is the ENVELOPE's discriminator here, so it outranks a site's own;
    the site's rides in `:rf.error/id`.

  - `tools/probe.cljs` `err->result` → the ex-data merged OVER
    `{:ok? false :message (ex-message err)}`. Carries both halves since
    rf2-6tzm5. Reached by anything thrown from the `on-value` callback
    of `eval-after-runtime!` / `eval-after-runtime-signalled!` — i.e.
    all response shaping, after the round-trip. Here the ex-data's
    `:reason` IS the payload's discriminator, so it wins.

  Both relays put ex-data on the wire, which makes a relayed throw's
  ex-data WIRE DATA: every value in it must be EDN-round-trippable,
  because the envelope's canonical slot is `(pr-str v)` and one
  unreadable value reds the consumer's read of the WHOLE envelope
  rather than merely going missing.

  Each relay is pinned here at its own boundary:

  - **rt-let binding shape** (`tools/eval-form` `emit-name`) fires during
    synchronous form construction, so it meets `invoke-and-guard`. The
    first test pins both halves: the human sentence and the token in the
    message, and `:rf.error/id` / `:where` / `:recovery` / `:name` as
    ex-data slots — plus the precedence rule, since `emit-name`'s
    ex-data carries a `:reason` of its own that must NOT displace
    `:handler-threw`.

  - **unknown wire `:kind`** (`tools/wire-pipeline` `run-wire-pipeline`)
    fires only from response shaping — all five call sites across
    `snapshot`, `get-path`, `read-sub`, `trace-window` and `watch-epochs`
    sit inside an `on-value` callback — so it meets `err->result`. The
    second test pins BOTH halves it now relays: the ex-data's `:reason`
    sentence and `:rf.error/id` discriminator, AND the ex-message's own
    sentence plus trailing token. Before rf2-6tzm5 that message reached
    no consumer at all, which is precisely why it needs a tripwire: a
    relay that silently discards a canonical message is invisible to
    every test that only reads ex-data.

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
  (:require [cljs.test :refer-macros [deftest is async use-fixtures]]
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
  ;; assertion; reverting `invoke-and-guard` to `{:reason :handler-threw
  ;; :message …}` — the shape that made this ex-data unreachable — reds the
  ;; four ex-data rows; a regression that turns the tool error into a
  ;; rejected promise reds `tu/error?` and takes the whole row with it.
  ;;
  ;; And the row is a tripwire for the hazard that promotion introduces, at
  ;; no extra cost: `tu/extract-edn` IS the consumer's EDN reader, so a
  ;; single non-EDN value anywhere in a relayed ex-data reds EVERY assertion
  ;; below at once with `No reader function for tag object`. `emit-name`
  ;; carried one (`:type (type n)`, a JS constructor) harmlessly for as long
  ;; as relay 1 dropped ex-data; putting it back demonstrates the failure.
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
                     (pr-str msg)))
            ;; The other half, closed by rf2-qoih4. Relay 1 used to relay the
            ;; message and DROP `(ex-data err)`, so the discriminator an agent
            ;; BRANCHES on arrived only as a token embedded in prose — it had
            ;; to regex it back out — and the actionable slots beside it did
            ;; not arrive at all. Nothing else on this surface can notice
            ;; that: every other assertion here reads the message, which the
            ;; old relay preserved. These rows are the only thing standing
            ;; between a future `{:reason :handler-threw :message …}` and a
            ;; second round of ex-data that reaches nobody.
            (is (= :rf.error/pair-mcp-rt-let-binding-bad-shape
                   (:rf.error/id edn))
                "the machine discriminator rides as a SLOT, namespace intact")
            (is (= 're-frame2-pair-mcp/rt-let (:where edn))
                "along with the rest of the site's actionable ex-data")
            (is (= :no-recovery (:recovery edn)))
            (is (= (pr-str "not-a-symbol") (:name edn))
                "and the offending value, in its total pr-str rendering")
            ;; Precedence, and it is the OPPOSITE of relay 2's. `emit-name`'s
            ;; ex-data carries its own `:reason` string; the `:handler-threw`
            ;; row above passes only because the relay's `:reason` wins over
            ;; it. Merge the ex-data OVER instead and that row reds, because
            ;; the agent loses the one key telling it the tool body threw
            ;; before the runtime was ever reached.
            (is (= :handler-threw (:reason edn))
                "the envelope discriminator survives an ex-data :reason")))
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
;; Relay 2 — `probe/err->result`: the EX-DATA *and* the MESSAGE are the
;; consumer contract (rf2-6tzm5 — the message half used to be dropped).
;; ---------------------------------------------------------------------------

(deftest unknown-wire-pipeline-kind-reaches-the-agent-as-readable-ex-data
  ;; Reverting the ex-data's `:reason` to a bare keyword reds the sentence
  ;; assertion; dropping `:rf.error/id` reds the discriminator assertion;
  ;; reverting `err->result` to `(merge {:ok? false} data)` — the shape that
  ;; made this message dead prose — reds the two ex-message assertions.
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
            ;; The tripwire the finding earned (rf2-6tzm5). `err->result`
            ;; used to relay ex-data and DROP `(ex-message err)`, so
            ;; `run-wire-pipeline`'s carefully composed message — and the
            ;; `[:rf.error/…]` token in it — reached nobody. Nothing else on
            ;; this surface can notice that: every other assertion here reads
            ;; ex-data, which the old relay preserved. These two rows are the
            ;; only thing standing between a future `(merge {:ok? false}
            ;; data)` and a second round of silently dead prose.
            (let [msg (:message edn)]
              (is (re-find #"unknown :kind" (str msg))
                  (str "the ex-MESSAGE reaches the agent at this relay too\n  got: "
                       (pr-str msg)))
              (is (str/includes?
                    (str msg)
                    "[:rf.error/pair-mcp-unknown-wire-pipeline-kind]")
                  (str "carrying the canonical [:rf.error/…] token\n  got: "
                       (pr-str msg))))))
        done))))
