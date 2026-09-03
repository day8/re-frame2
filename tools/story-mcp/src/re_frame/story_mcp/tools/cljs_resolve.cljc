(ns re-frame.story-mcp.tools.cljs-resolve
  "The Story-MCP host-capability / provider BOUNDARY for browser-only
  reads.

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
  held in a dynamic var. `nil` means 'no provider reachable' — the default
  on EVERY platform (see the next section). A bound provider's
  `[]`/`#{}`/`{}` means 'reached and empty', which is DISTINCT from the var
  being nil. A future browser bridge (or a test) supplies a provider by
  binding the seam; the transport itself is OUT OF SCOPE here (a later bead
  owns it — this ns only distinguishes 'reached + empty' from 'cannot
  answer').

  ## Neither seam auto-wires — the browser bridge binds BOTH (rf2-jyjadg)

  BOTH provider seams default to `nil` on EVERY platform, JVM and CLJS
  alike. Neither `*substrate-provider*` nor `*a11y-provider*` is
  auto-wired: a future browser-local host (or a test) MUST bind BOTH for
  either browser-only read to answer. `substrate-provider-available?` and
  `a11y-provider-available?` therefore report the SAME posture out of the
  box (both false), and `list-substrates` / `read-a11y-violations` both
  degrade to `capability-unavailable` until their seam is bound.

  This symmetry is deliberate. The substrate registry
  (`re-frame.story/registered-substrates`) lives in the already-loaded
  Story CORE ns, so a CLJS default COULD cheaply dereference it in-process
  — but the a11y violations atom (`re-frame.story.ui.a11y/
  violations-by-frame`) lives in the Story UI ns, which this helper must
  NOT require (bundle isolation — the MCP classpath may not drag in the
  Story UI; see `deps.edn`). Auto-wiring ONLY the reachable seam would
  leave a HALF-WORKING default: `list-substrates` answering out of the box
  while `read-a11y-violations` silently reports capability-unavailable in
  the SAME live process — and would invite a bridge author to assume a11y
  'just works' because substrate does. So the seams are held CONSISTENT:
  the bridge wires both, together, and there is no silent asymmetry.

  The two browser-only read handlers (`dev/tool-list-substrates`,
  `testing/tool-read-a11y-violations`) and the `:substrate` validation in
  `args` gate on the availability predicates below, routing an absent
  provider through `result/capability-unavailable-result` rather than
  returning a false-empty success.

  ## No var probing, on either platform (rf2-6r9j.119)

  This ns holds NOTHING but the two seams and their accessors. Earlier
  revisions carried a `clojure.core/resolve` probe that tried to
  auto-discover the two providers from the JVM; it could never succeed —
  both sources are CLJS-only `def`s with no JVM Var, and the shipped
  Story-MCP entry point is a JVM stdio subprocess with no bridge to a
  browser heap — so it only obscured the explicit-provider contract above
  (and dragged the whole Story facade onto the load path to do it).")

;; ---------------------------------------------------------------------------
;; Substrate-registry provider seam
;; ---------------------------------------------------------------------------

(def ^:dynamic *substrate-provider*
  "Provider seam for the substrate-REGISTRY browser-only read. A provider
  is a zero-arg fn returning the registered-substrate id collection;
  `nil` means NO provider is reachable — the default on EVERY platform
  (JVM stdio AND CLJS), no auto-wire. A bound provider's `[]` means
  'reached and empty', DISTINCT from this var being nil ('cannot answer').
  Rebind in tests / a future browser bridge to supply one.

  NOTE (rf2-jyjadg): the CLJS default is nil even though the registry
  (`re-frame.story/registered-substrates`) is reachable in-process — it is
  held nil to stay SYMMETRIC with `*a11y-provider*`, whose UI-ns source is
  bundle-isolated out of this helper and so CANNOT auto-wire. A browser
  bridge binds BOTH seams together; see the ns docstring."
  nil)

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
  the default on EVERY platform (JVM stdio AND CLJS). A bound provider
  that returns `{}` (or lacks an entry for a frame) means 'reached and
  empty', DISTINCT from this var being nil. Rebind in tests / a future
  browser bridge to supply one.

  The CLJS default is nil because the source atom
  (`re-frame.story.ui.a11y/violations-by-frame`) lives in the Story UI ns
  this helper must NOT require (bundle isolation). It is held SYMMETRIC
  with `*substrate-provider*` (also nil on CLJS) so the browser bridge
  wires BOTH seams together — no silent half-working default (rf2-jyjadg;
  see the ns docstring)."
  nil)

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
