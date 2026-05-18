(ns re-frame.subs.tooling
  "Subs tooling sibling of `re-frame.subs` — carries the two tool-facing
  introspection fns (`sub-topology`, `sub-cache-snapshot`) that pair
  tools (Causa, re-frame2-pair-mcp, re-frame-10x) query but no production
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
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- static topology ------------------------------------------------------
;;
;; Per Spec 002 §The public registrar query API and Spec 006 §Subscription
;; topology vs subscription tracking. `sub-topology` is the static ":<- chain"
;; you can derive from registrations alone — pure data over the registrar,
;; no app-db, no reactive runtime, no per-frame cache. JVM-runnable.
;;
;; Shape (per Spec 002 §The public registrar query API row): a map of
;;   sub-id → {:inputs [<input-sub-ids>] :doc <str?> :ns sym :line int :file str}
;; with :inputs always present (empty vector for layer-1 / direct-app-db subs)
;; and the source-coord / :doc keys included only when the registration
;; carries them.

(defn sub-topology
  "Return the static dependency graph of every registered subscription.

  Pure data over the registrar — no app-db, no per-frame cache, no
  reactive runtime. Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  Shape: `{sub-id {:inputs [<input-sub-ids>] :doc ... :ns ... :line ... :file ...}}`.

  - `:inputs` is the vector of upstream sub-ids declared via `:<-` at
    registration time. It is always present; layer-1 subs (which read
    `app-db` directly) report `:inputs []`. The order matches the
    declaration order so that downstream tools can reconstruct the
    chain shape the body fn expects.
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
        (let [inputs (mapv first (:input-signals meta))
              entry  (cond-> {:inputs inputs}
                       (contains? meta :doc)  (assoc :doc  (:doc  meta))
                       (contains? meta :ns)   (assoc :ns   (:ns   meta))
                       (contains? meta :line) (assoc :line (:line meta))
                       (contains? meta :file) (assoc :file (:file meta)))]
          (assoc acc sub-id entry)))
      {}
      subs-meta)))

(defn sub-cache-snapshot
  "Public read-only snapshot of a frame's sub-cache, projected to a
  Tool-Pair-friendly shape: `{query-v {:value v :ref-count n}}`.

  CLJS-only — on the JVM the cache exists for ref-counting purposes but
  the cached reactions are not deref-able, so this fn returns `nil`. Per
  Spec 002 §The public registrar query API and Tool-Pair §How AI tools
  attach.

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
         (reduce-kv
           (fn [acc query-v entry]
             (assoc acc query-v
                    {:value     (when-let [r (:reaction entry)]
                                  (try @r (catch :default _ nil)))
                     :ref-count (or (:ref-count entry) 0)}))
           {}
           @cache)))
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
