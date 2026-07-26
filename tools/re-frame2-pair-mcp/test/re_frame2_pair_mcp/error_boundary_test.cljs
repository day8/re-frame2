(ns re-frame2-pair-mcp.error-boundary-test
  "The message a consumer's AI agent READS when a tool handler throws.

  `server.cljs`'s `invoke-and-guard` catches ANY throw out of a tool
  handler and puts `(.-message err)` — verbatim, un-rewritten — into the
  `:handler-threw` envelope the MCP client receives. That makes the
  ex-message a CONSUMER contract, not tool-internal prose: the reader on
  the other end is an agent trying to act on it. A bare `(str error-kw)`
  ships that agent `:rf.error/pair-mcp-rt-let-binding-bad-shape` and
  strands every actionable word in ex-data, which the relay does not send.

  Every consumer-reachable throw here must therefore carry the canonical
  Spec 009 shape: a human sentence PLUS a trailing `[:rf.error/<id>]`
  greppability token. `tools/` is bundle-isolated and MUST NOT
  `:require re-frame.error`, so these messages are hand-rolled at each
  throw site and no shared builder can enforce them — only a boundary
  assertion can. PR #7036 landed the conversions; until this namespace
  nothing stood behind them but a docstring, and reverting any of them to
  a bare keyword was a green change.

  ## Which throws this covers, and why not the others

  `tools/re-frame2-pair-mcp/src` has exactly four `throw` sites. Only two
  are relayed to a consumer as a MESSAGE:

    - `tools/eval-form`     `emit-name`         — rt-let binding shape.
    - `tools/wire-pipeline` `run-wire-pipeline` — unknown payload `:kind`.

  The other two (`server.cljs`'s `:rf.error/pair-mcp-ambiguous-shadow`
  and `:rf.error/pair-mcp-nrepl-port-not-found`) are raised INSIDE
  discovery and caught by `handle-call*`'s own `.catch`, which rebuilds a
  structured payload from `:rf.error/id` and never reads the message.
  Their bare-keyword messages are tool-internal and stay that way.

  The rf2-jquiy audit also named a diff-encode validation family. It is
  not reachable from this surface: `mcp-base`'s grammar gate resolves
  `malli.core/validate` at runtime and soft-passes when Malli is absent,
  and Malli is deliberately absent from pair-mcp's CLJS classpath (see
  `tools/mcp-base/deps.edn`) — while the SHIPPED `:server` build
  additionally DCEs the gate outright via `:closure-defines
  {re-frame.mcp-base.diff-encode/validate-patches? false}`. A row for it
  could not fail in either build, and a boundary row that cannot fail is
  decoration.

  ## What is real here and what is forced

  Both covered throws are programmer-typo guards: all twelve `rt-let`
  call sites pass literal quoted symbols, and every `run-wire-pipeline`
  call site passes a literal `:kind`. No tool ARGUMENT reaches either, so
  no MCP request can trigger them from outside — which is exactly why
  they were unpinned, and exactly why the regression they guard against
  (a future edit reverting the message) is invisible to every other test.

  So the exception is produced by the REAL producer — `ef/emit` over a
  real malformed `ef/rt-let`, and a real `wp/run-wire-pipeline` call with
  an out-of-vocabulary `:kind`, both public fns, no seam — and then made
  to escape a real tool handler by seaming the one thing the handler
  trusts, `nrepl/cljs-eval-value`. Everything downstream of the throw is
  the shipped path: real `handle-call` → real `ensure-connection!` (over
  a seeded conn) → real `tools/invoke` → real `invoke-and-guard` → real
  `wire/result`. The trigger is forced; the message, the relay and the
  envelope are not.

  `handler-threw-relays-the-producers-message-verbatim` is the control
  that closes the causal loop: the relay is proven to be a pass-through,
  so a message regression AT THE PRODUCER is a message regression AT THE
  CONSUMER."
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

(defn- relay
  "Drive the REAL `tools/call` path with `thrown` escaping a real tool
  handler, and return a Promise of the MCP result envelope the SDK ships.

  Seeding the conn via `mark-discovered-for-tests!` (no port-file, so
  `ensure-connection!` takes its cached fast path) is what puts the drive
  through `handle-call*` → `invoke-and-guard` rather than the discovery-
  error arm — the distinction the `:handler-threw` assertions rest on."
  [thrown]
  (server/reset-session-state-for-tests!)
  (server/mark-discovered-for-tests! (nrepl/make-conn 0 "127.0.0.1"))
  (let [orig nrepl/cljs-eval-value
        stub (fn [& _] (js/Promise.reject thrown))]
    (set! nrepl/cljs-eval-value stub)
    (-> (server/handle-call-for-tests {} "snapshot" #js {} nil)
        (.finally (fn [] (tu/restore-eval! stub orig))))))

(defn- threw
  "Return the exception `f` throws. Fails loudly when it does not — a row
  whose producer stopped throwing would otherwise assert nothing at all."
  [label f]
  (let [e (try (f) nil (catch :default e e))]
    (is (some? e)
        (str label ": the production producer must still throw"))
    e))

;; ---------------------------------------------------------------------------
;; The table. One row per consumer-relayed throw family.
;; ---------------------------------------------------------------------------

(def ^:private families
  [{:label   "an rt-let binding name that is not a symbol"
    :produce #(ef/emit (ef/rt-let ["snap" (ef/rt-call 'snapshot)]
                                  (ef/rt-raw "snap")))
    :human   #"rt-let binding name must be a symbol"
    :token   "[:rf.error/pair-mcp-rt-let-binding-bad-shape]"}

   {:label   "a wire-pipeline :kind outside the closed dispatch"
    :produce #(wp/run-wire-pipeline {} {:kind :snapshot-mapp})
    :human   #"expected one of :snapshot-map, :epoch-vector, :scalar-value"
    :token   "[:rf.error/pair-mcp-unknown-wire-pipeline-kind]"}])

(deftest thrown-error-shape-reaches-the-consumer-facing-surface
  ;; Reverting either message to a bare `(str error-kw)` reds the `human`
  ;; row; dropping the trailing token reds the `token` row; a regression
  ;; that turns the relay into a rejected promise reds `tool-error?` on
  ;; every row at once.
  (async done
    (-> (reduce
          (fn [p {:keys [label produce human token]}]
            (.then
              p
              (fn [_]
                (let [e   (threw label produce)
                      msg (ex-message e)]
                  (testing (str label " — at the producer")
                    (is (re-find human msg)
                        (str label ": the message carries the human sentence,"
                             " not just a keyword\n  got: " (pr-str msg)))
                    (is (str/includes? msg token)
                        (str label ": the canonical [:rf.error/…] token rides"
                             " the message\n  got: " (pr-str msg))))
                  (-> (relay e)
                      (.then
                        (fn [result]
                          (testing (str label " — at the consumer surface")
                            (is (tu/error? result)
                                (str label ": a handler throw is an MCP tool"
                                     " error, not a rejected promise"))
                            (let [edn (tu/extract-edn result)]
                              (is (= :handler-threw (:reason edn))
                                  (str label ": the drive really went through"
                                       " invoke-and-guard's relay"))
                              (is (re-find human (:message edn))
                                  (str label ": the human sentence survives the"
                                       " relay to the agent\n  got: "
                                       (pr-str (:message edn))))
                              (is (str/includes? (:message edn) token)
                                  (str label ": the token survives the relay to"
                                       " the agent\n  got: "
                                       (pr-str (:message edn)))))))))))))
          (js/Promise.resolve nil)
          families)
        (.then (fn [_] (done)))
        (.catch (fn [e]
                  (is false (str "the boundary drive rejected: " (.-message e)))
                  (done))))))

(deftest handler-threw-relays-the-producers-message-verbatim
  ;; The control, and the reason the producer-side assertions above are
  ;; load-bearing rather than a unit test in disguise: `invoke-and-guard`
  ;; adds nothing and rewrites nothing. Whatever the producer put in the
  ;; message is what the agent reads — so a producer-side regression is a
  ;; consumer-side regression, with no intermediate to absorb it.
  (async done
    (-> (relay (ex-info "a plain sentence with no token at all" {:x 1}))
        (.then (fn [result]
                 (is (tu/error? result))
                 (let [edn (tu/extract-edn result)]
                   (is (= :handler-threw (:reason edn)))
                   (is (= "a plain sentence with no token at all"
                          (:message edn))
                       "the relay is a pass-through — it neither trims nor
                        decorates the producer's message"))))
        (.catch (fn [e]
                  (is false (str "the boundary drive rejected: " (.-message e)))))
        (.finally (fn [] (done))))))
