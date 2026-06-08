(ns re-frame.subs.tooling
  "Subs tooling sibling of `re-frame.subs` — carries the two tool-facing
  introspection fns (`sub-topology`, `sub-cache-snapshot`) that pair
  tools (Xray, re-frame2-pair-mcp, re-frame-10x) query but no production
  application code reads.

  Per rf2-bmzq0 (audit rf2-53tcf §Part 4 P2): keeping these fns in
  `re-frame.subs` paid for their bodies in every CLJS bundle that
  loaded the subs ns, even though no production code path reaches
  them. Splitting them off lets `:advanced` + `goog.DEBUG=false` DCE
  the bodies wholesale (the sibling ns is loaded only when a test
  fixture, tool, or dev preload requires it).

  Mirrors the rf2-qwm0a `re-frame.trace.tooling` split:
    - CLJS consumers needing the surface call
      `re-frame.subs.tooling/<name>` directly. Production counter
      bundles never load this ns and DCE the bodies wholesale.
    - JVM consumers keep the legacy `re-frame.subs/<name>` /
      `rf/sub-topology` / `rf/sub-cache` shape via the convenience
      aliases in `re-frame.subs` and `re-frame.core` (gated under
      `#?(:clj ...)`). JVM has no bundle to protect; the aliases
      cost nothing.

  Per Spec 002 §The public registrar query API and Spec 006
  §Subscription topology vs subscription tracking."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- static topology ------------------------------------------------------
;;
;; Per Spec 002 §The public registrar query API and Spec 006 §Subscription
;; topology vs subscription tracking. `sub-topology` is the static dependency
;; graph you can derive from registrations alone — pure data over the
;; registrar, no app-db, no reactive runtime, no per-frame cache.
;; JVM-runnable.
;;
;; Shape (per Spec 002 §The public registrar query API row): a map of
;;   sub-id → {:input-kind <kind> :inputs <inputs>
;;             :doc <str?> :ns sym :line int :file str}
;; where `:input-kind` discriminates the input producer (Spec 006
;; §Subscription input producers) and `:inputs` carries the kind-specific
;; static edge set:
;;   :db          layer-1 / direct-app-db reader  →  :inputs []
;;   :static      literal `:<-` query-vectors      →  :inputs [[:q1] [:q2 a] ...]
;;   :parametric  an `input-fn` (query-parametric) →  :inputs :parametric
;; The parametric edge set is NOT statically enumerable — it depends on the
;; concrete outer query vector, so the static surface reports the
;; `:parametric` sentinel rather than fabricating un-materialized edges.
;; Realized parametric edges per concrete query vector are runtime cache
;; state, surfaced by `sub-cache-snapshot`'s `:realized-inputs` slot.
;; The source-coord / :doc keys are included only when the registration
;; carries them.

(defn sub-topology
  "Return the static dependency graph of every registered subscription.

  Pure data over the registrar — no app-db, no per-frame cache, no
  reactive runtime. Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  Shape: `{sub-id {:input-kind <kind> :inputs <inputs>
                   :doc ... :ns ... :line ... :file ...}}`.

  - `:input-kind` discriminates the sub's input producer (Spec 006
    §Subscription input producers): `:db` (layer-1 app-db reader),
    `:static` (literal `:<-` chain), or `:parametric` (an `input-fn`
    that computes its input query-vectors from the outer query-v). It
    is always present.
  - `:inputs` is the kind-specific static edge set, always present:
      - `:db`         → `[]` (reads `app-db` directly; no upstream subs).
      - `:static`     → the vector of literal `:<-` input query-vectors
                        (`[[:items] [:filter]]`) in declaration order, so
                        downstream tools can reconstruct the chain shape
                        the body fn expects. The args are preserved
                        (`:<- [:upstream :arg]` → `[:upstream :arg]`).
      - `:parametric` → the keyword `:parametric`. The realized edge set
                        depends on the concrete outer query vector and is
                        therefore NOT statically enumerable. Realized
                        parametric edges per concrete query vector live in
                        the runtime cache — see `sub-cache-snapshot`'s
                        `:realized-inputs` slot.
  - `:doc`, `:ns`, `:line`, `:file` are present when the registration
    carried them (`:ns` / `:line` / `:file` are auto-captured by the
    `reg-sub` macro per Spec 001 §Source-coordinate capture; `:doc`
    is user-supplied via the meta-map first arg).

  Returns `{}` when no subs are registered.

  JVM-runnable. The runtime cache state (`sub-cache`) is the dynamic
  counterpart and is CLJS-only."
  []
  (let [subs-meta (registrar/registrations :sub)]
    (reduce-kv
      (fn [acc sub-id meta]
        (let [input-kind (:input-kind meta)
              ;; `:static` reports the literal `:<-` input query-vectors
              ;; (args preserved); `:parametric` reports the `:parametric`
              ;; sentinel (the realized edge set is per-concrete-query-v
              ;; runtime state, not statically enumerable); `:db` (and any
              ;; legacy registration without a discriminator) reports `[]`.
              inputs     (case input-kind
                           :parametric :parametric
                           :static     (vec (:input-signals meta))
                           [])
              entry  (cond-> {:input-kind (or input-kind :db)
                              :inputs     inputs}
                       (contains? meta :doc)  (assoc :doc  (:doc  meta))
                       (contains? meta :ns)   (assoc :ns   (:ns   meta))
                       (contains? meta :line) (assoc :line (:line meta))
                       (contains? meta :file) (assoc :file (:file meta)))]
          (assoc acc sub-id entry)))
      {}
      subs-meta)))

(defn sub-cache-snapshot
  "Public read-only snapshot of a frame's sub-cache, projected to a
  Tool-Pair-friendly shape:
  `{query-v {:value v :ref-count n :input-kind k :realized-inputs [...]}}`.

  CLJS-only — on the JVM the cache exists for ref-counting purposes but
  the cached reactions are not deref-able, so this fn returns `nil`. Per
  Spec 002 §The public registrar query API and Tool-Pair §How AI tools
  attach.

  Per-entry slots:

  - `:value`           — the current deref of the cached reaction (`nil`
                         when the reaction throws on deref).
  - `:ref-count`       — the live consumer ref-count for the entry.
  - `:input-kind`      — the input-producer discriminator the sub was
                         registered with (`:db` / `:static` /
                         `:parametric`), looked up from the registrar.
                         Layer-1 / direct-app-db readers are `:db`.
  - `:realized-inputs` — the REALIZED input query-vectors for THIS
                         concrete cache entry — the literal `:<-` list for
                         `:static`, and the `(input-fn query-v)` result for
                         `:parametric` (Spec 006 §Subscription input
                         producers; the EP §Tooling live cache-entry view).
                         This is the runtime counterpart to the static
                         `sub-topology` `:inputs :parametric` sentinel: it
                         surfaces the concrete edges that the static surface
                         cannot enumerate. `[]` for a `:db` reader. Stored
                         on the entry at materialization, so its topology is
                         fixed for the entry's lifetime.

  Dev-only on CLJS too — the body is gated on `interop/debug-enabled?`
  (the `goog.DEBUG` mirror) so production builds elide both the cache
  walk and the deref-and-collect machinery. Pair tools that attach in
  production explicitly opt in by toggling the gate.

  Returns `nil` for missing or destroyed frames, and `nil` in
  production builds."
  [frame-id]
  #?(:cljs
     (when interop/debug-enabled?
       (when-let [cache (:sub-cache (frame/frame frame-id))]
         (let [subs-meta (registrar/registrations :sub)]
           (reduce-kv
             (fn [acc query-v entry]
               (let [sub-id     (when (vector? query-v) (first query-v))
                     meta       (get subs-meta sub-id)
                     ;; The cache entry's `:inputs` slot holds the realized
                     ;; input query-vectors for this concrete outer query-v
                     ;; (the literal `:<-` list for `:static`, the
                     ;; `(input-fn query-v)` result for `:parametric`) — see
                     ;; `re-frame.subs/compute-and-cache!`. Layer-1 readers
                     ;; carry `[]`.
                     realized   (vec (:inputs entry))]
                 (assoc acc query-v
                        {:value           (when-let [r (:reaction entry)]
                                            (try @r (catch :default _ nil)))
                         :ref-count       (or (:ref-count entry) 0)
                         :input-kind      (or (:input-kind meta) :db)
                         :realized-inputs realized})))
             {}
             @cache))))
     :clj
     nil))

;; ---- bundle-isolation sentinel ------------------------------------------
;;
;; Per rf2-bmzq0: `implementation/scripts/check-bundle-isolation.cjs`
;; greps the counter bundle for this exact string. The string lives
;; ONLY in this file's source body — no other namespace, no docstring,
;; no test fixture references it — so its presence in the production
;; counter bundle proves that the tooling sibling's body got pulled
;; in (most likely via a stray `:require` from a core/* ns). The
;; sentinel survives `:advanced` because string literals are not
;; renamed; it sits outside any `interop/debug-enabled?` gate so DCE
;; cannot drop the literal independently of the surrounding ns body.

(defonce ^:private bundle-isolation-sentinel
  "rf.subs.tooling/sentinel:rf2-bmzq0-2026-05-16:do-not-rename")
