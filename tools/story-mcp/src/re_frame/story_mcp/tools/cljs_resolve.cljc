(ns re-frame.story-mcp.tools.cljs-resolve
  "The Story-MCP host-capability / provider BOUNDARY for browser-only
  reads, plus the low-level CLJS-var resolver it is built on.

  ## The two browser-only surfaces

  Two Story surfaces are reachable only from a CLJS runtime hosting the
  live browser state: the substrate REGISTRY (`register-substrate!`) and
  the a11y-panel VIOLATIONS atom (`re-frame.story.ui.a11y/
  violations-by-frame`). Both are `def`s behind a `#?(:cljs …)` reader
  conditional, so no JVM Var exists for them. The shipped Story-MCP entry
  point is a JVM stdio subprocess with no bridge to a browser heap, so on
  the JVM neither provider is reachable.

  ## Availability is NOT emptiness (rf2-3fc89f.21)

  The bug this ns closes: the old code let provider ABSENCE masquerade as
  a successful EMPTY answer — `list-substrates` returned `{:substrates []}`
  and `read-a11y-violations` returned `{:violations []}` on the JVM,
  indistinguishable from a reached provider that genuinely holds nothing.
  An agent could infer 'no substrates registered' or 'zero accessibility
  violations' from a host that never looked, or believe a requested
  render substrate was honoured when it was silently dropped to nil.

  This boundary represents AVAILABILITY separately from the returned
  collection. Each browser-only surface has a PROVIDER SEAM: a zero-arg fn
  held in a dynamic var. `nil` means 'no provider reachable' (the JVM
  stdio default). A bound provider's `[]`/`#{}`/`{}` means 'reached and
  empty', which is DISTINCT from the var being nil. A future browser
  bridge (or a test) supplies a provider by binding the seam; the
  transport itself is OUT OF SCOPE here (a later bead owns it — this ns
  only distinguishes 'reached + empty' from 'cannot answer').

  The two browser-only read handlers (`dev/tool-list-substrates`,
  `testing/tool-read-a11y-violations`) and the `:substrate` validation in
  `args` gate on the availability predicates below, routing an absent
  provider through `result/capability-unavailable-result` rather than
  returning a false-empty success.

  ## The low-level resolver

  `resolve-cljs-var` probes for a CLJS-only `def` via `clojure.core/
  resolve` on the JVM (nil on miss); it is the mechanism the two
  platform-default providers are wired from."
  (:require [re-frame.story :as story]))

(defn resolve-cljs-var
  "Resolve a fully-qualified symbol (`fully.qualified.ns/sym`) to the
  underlying var on the JVM, returning `nil` on miss. Wraps
  `clojure.core/resolve` in a try/catch so a CLJS-only `def` (which has
  no JVM Var) yields nil rather than blowing up.

  Callers MUST pass the FULLY-QUALIFIED ns (`re-frame.story/…`), not an
  alias-qualified symbol — even though `resolve` does honour the calling
  ns's aliases, passing the real ns keeps the contract self-describing.
  The two default providers below wire from this resolver against the
  substrate registry + the a11y violations atom."
  [sym]
  (try (resolve sym) (catch Throwable _ nil)))

;; ---------------------------------------------------------------------------
;; Resolved CLJS-only vars — the platform-default provider sources.
;;
;; Each is probed ONCE at ns-load. On a JVM host the CLJS-only Var does not
;; exist, so the probe is nil and the surface is UNAVAILABLE (NOT empty).
;; The `:cljs` accessor branches below read the live surface directly when
;; these namespaces are hosted in a CLJS runtime.
;; ---------------------------------------------------------------------------

#?(:clj
   (defonce ^:private registered-substrates-var
     (resolve-cljs-var 're-frame.story/registered-substrates)))

#?(:clj
   ;; `re-frame.story.ui.a11y/violations-by-frame` is the CLJS-side panel
   ;; atom — a JVM process cannot dereference a CLJS atom in a browser heap,
   ;; so this resolves nil on the stdio host. Probed once, here, so the a11y
   ;; provider seam has a single resolution site (co-located with the
   ;; substrate one) rather than a var buried in `testing`.
   (defonce ^:private violations-by-frame-var
     (resolve-cljs-var 're-frame.story.ui.a11y/violations-by-frame)))

;; ---------------------------------------------------------------------------
;; Substrate-registry provider seam
;; ---------------------------------------------------------------------------

(def ^:dynamic *substrate-provider*
  "Provider seam for the substrate-REGISTRY browser-only read. A provider
  is a zero-arg fn returning the registered-substrate id collection;
  `nil` means NO provider is reachable — the JVM stdio default (no bridge
  to the CLJS `register-substrate!` registry). A bound provider's `[]`
  means 'reached and empty', DISTINCT from this var being nil ('cannot
  answer'). Rebind in tests / a future browser bridge to supply one."
  #?(:clj  (when registered-substrates-var
             (fn [] (registered-substrates-var)))
     :cljs (fn [] (story/registered-substrates))))

(defn substrate-provider-available?
  "True iff a substrate-registry provider is reachable. The availability
  bit is represented SEPARATELY from the returned collection — an
  unreachable provider is NOT an empty registry. Callers that would read
  the collection gate on this first, routing absence through
  `result/capability-unavailable-result`."
  []
  (some? *substrate-provider*))

(defn registered-substrates
  "Sorted vec of the reached provider's registered substrate ids (`[]`
  when a provider was reached and holds nothing, OR when unreachable —
  callers MUST gate on `substrate-provider-available?` so an unreachable
  read never surfaces as a false-empty success). A throw from a bound
  provider propagates (a real error is not silently flattened to `[]`)."
  []
  (if-let [p *substrate-provider*]
    (sort (vec (p)))
    []))

(defn registered-substrates-set
  "Set form of the reached provider's substrate ids — `args/read-run-opts`'s
  bounded `safe-keyword` allowlist. `#{}` when reached-empty or
  unreachable; `args/substrate-arg-error` gates on
  `substrate-provider-available?` so an explicit `:substrate` against an
  unreachable provider REJECTS rather than silently coercing to nil."
  []
  (if-let [p *substrate-provider*]
    (set (p))
    #{}))

;; ---------------------------------------------------------------------------
;; a11y-panel-state provider seam
;; ---------------------------------------------------------------------------

(def ^:dynamic *a11y-provider*
  "Provider seam for the a11y-panel VIOLATIONS browser-only read. A
  provider is a zero-arg fn returning the by-frame violations map
  (`{variant-kw [violation …]}`); `nil` means NO provider is reachable —
  the JVM stdio default (no bridge to the CLJS `violations-by-frame`
  atom). A bound provider that returns `{}` (or lacks an entry for a
  frame) means 'reached and empty', DISTINCT from this var being nil.
  Rebind in tests / a future browser bridge to supply one.

  The `:clj` default derefs the resolved var-of-atom (`@var` → the atom,
  `(deref …)` → the by-frame map), matching the shape a browser-local
  consumer would supply."
  #?(:clj  (when violations-by-frame-var
             (fn [] (deref @violations-by-frame-var)))
     :cljs nil))

(defn a11y-provider-available?
  "True iff an a11y-panel-state provider is reachable. Availability is
  represented SEPARATELY from the returned collection — an unreachable
  provider is NOT a clean (zero-violation) result. `testing/
  tool-read-a11y-violations` gates on this, routing absence through
  `result/capability-unavailable-result`."
  []
  (some? *a11y-provider*))

(defn a11y-violations-by-frame
  "The reached a11y provider's by-frame violations map. Call ONLY after
  `a11y-provider-available?` — an unreachable provider must route through
  the capability-unavailable result, not read as an empty violations map
  here."
  []
  (when-let [p *a11y-provider*]
    (p)))
