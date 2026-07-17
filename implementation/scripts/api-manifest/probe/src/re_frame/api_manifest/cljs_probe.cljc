(ns re-frame.api-manifest.cljs-probe
  "Reconciliation between the live ClojureScript public surface and the
  curated `:cljs-only` rows of `spec/api-manifest-metadata.edn`
  (rf2-2mtte). Pure data — no I/O, no analyzer, no React — so the same
  logic runs under any shadow-cljs build (the consolidated `:node-test`
  build for the adapter / Xray surfaces; a tool artefact's own
  `:server-test` build for the pair-MCP surface) and can be unit-tested
  on the JVM.

  ## The contract (mirrors the JVM drift-check)

  The JVM generator's drift-check goes RED when a public var is added,
  removed, or renamed in a JVM-loadable namespace. This is the CLJS-side
  equivalent for the namespaces that cannot be `require`d on the JVM.
  Two directions, exactly as the JVM `build-manifest` does
  (`missing` / `stale`):

    1. STALE ROW (removed / renamed → RED). Every `:cljs-only` row whose
       namespace the probe covers MUST resolve to a live public var of
       that namespace. A var renamed or removed in an adapter / Xray
       surface leaves a row with no live var → RED (the analogue of the
       generator's stale-sidecar-entries check).

    2. MISSING ROW (added → RED). For namespaces the probe marks
       `:fully-rowed` (the adapter namespaces, whose entire public
       surface is the documented adapter API per spec/API.md §UIx/Helix
       adapter), every live public var MUST have a `:cljs-only` row. A
       var ADDED to an adapter without a row → RED (the analogue of the
       generator's missing-classification check).

  ## Why `:kind` is NOT reconciled

  The manifest's `:kind` axis (`:macro` / `:fn` / `:var`) is, on the JVM
  side, derived at RUNTIME from `(fn? @v)`. The CLJS analyzer runs at
  COMPILE time and cannot know a `def`'s value is a fn: the adapter
  surfaces bind their hooks/seams as fn-VALUED `def`s
  (`(def use-subscribe (:use-subscribe spine-fns))`), which the analyzer
  reports as `:var` (no `:arglists`) even though they are callable. The
  curated `:kind :fn` on those rows is the correct human assertion; the
  analyzer simply cannot reproduce it. Reconciling `:kind` here would
  fail on an analyzer limitation, not a real drift — so the probe checks
  EXISTENCE (added / removed / renamed),
  not `:kind`. The JVM-loadable kind derivation stays authoritative on
  the JVM side; for CLJS-only value-defs the sidecar's curated `:kind`
  stands.

  ## Why the adapters are `:fully-rowed` but the Xray mount surface is not

  spec/API.md tiers the Xray `mount-*!` family `internal-public` (the
  supported host-embed surface), the panel-leaf `Panel` reg-views
  `implementation` (rf2-oekz6s — exported only so the shell composes them),
  and declares the panel-helper functions beneath them \"otherwise
  unrowed-internal\" (§Tiering of cross-tool surfaces). So the Xray mount
  surface is a CURATED subset by design — only the rowed reads
  (`mounted?`) are manifest rows; the open/close/teardown host-embed
  machinery is intentionally unrowed. (The probe checks existence, not
  tier, so these classifications do not change what it verifies.) The probe therefore verifies
  direction 1 (the curated rows still resolve) for the Xray surface but
  not direction 2 (full completeness), matching the spec's intent. The
  three adapter namespaces ARE their full documented public API, so they
  earn the stricter bidirectional check."
  (:require [clojure.string :as str]))

(defn reconcile
  "Reconcile the live CLJS public surface against the curated `:cljs-only`
   sidecar rows.

   Args:
   - `live`  — `{ns-string [[var-string kind-kw] ...]}`, the live publics
               (minus `^:no-doc`) of every namespace the probe covers,
               as produced by `cljs-publics/emit-ns-publics`.
   - `rows`  — the `:cljs-only` rows from the sidecar (each a full
               manifest row map with `:namespace` / `:var` / `:kind`).
   - `fully-rowed` — set of namespace strings whose ENTIRE public surface
               must be rowed (direction 2). Namespaces in `live` but NOT
               here are checked direction-1 only (curated subsets).

   Returns `{:stale [...] :missing [...]}` — each a sorted vector of
   human-readable problem maps. Empty everywhere ⇒ in sync (green)."
  [live rows fully-rowed]
  (let [covered      (set (keys live))
        ;; Only reconcile rows for namespaces the probe actually loaded
        ;; — a `:cljs-only` row for a namespace not in `live` (e.g. the
        ;; `re-frame.core` `frame-provider` reader-conditional row, which
        ;; the JVM side owns) is out of this probe's scope.
        covered-rows (filter #(contains? covered (:namespace %)) rows)
        rows-by-ns   (group-by :namespace covered-rows)
        live-vars    (into {} (map (fn [[ns pairs]]
                                     [ns (set (map first pairs))]))
                           live)
        stale        (for [{:keys [namespace var]} covered-rows
                           :when (not (contains? (get live-vars namespace) var))]
                       {:namespace namespace :var var})
        missing      (for [ns-str fully-rowed
                           :when  (contains? covered ns-str)
                           :let   [rowed (set (map :var (get rows-by-ns ns-str)))]
                           [v k]  (get live ns-str)
                           :when  (not (contains? rowed v))]
                       {:namespace ns-str :var v :live-kind k})]
    {:stale   (vec (sort-by (juxt :namespace :var) stale))
     :missing (vec (sort-by (juxt :namespace :var) missing))}))

(defn in-sync?
  "True when a `reconcile` result has no problems in any bucket."
  [result]
  (every? empty? (vals result)))

;; ---------------------------------------------------------------------------
;; Host-arity reconciliation (rf2-5bcdi — the CLJS lane of the ui.test
;; host-arity guard; the JVM lane lives in api-md-check).
;;
;; The manifest carries name + :kind but NO arity, so a re-frame.ui.test
;; FUNCTION can reshape a supported arity and stay green — and its contract is
;; host-specific (`flush!` is 0-arity on the JVM, 0/1-arity on CLJS). This
;; reconciles the live CLJS analyzer arities against the `:cljs` half of the
;; sidecar signature authority.
;; ---------------------------------------------------------------------------

(defn signature-problems
  "Pure host-arity reconciler for the CLJS (:cljs) lane (rf2-5bcdi). Compares
   the declared `:cljs` signature contract against the live CLJS analyzer
   arities. Returns a sorted problems vector; empty ⇒ in sync.

   `contract` — the `:vars` map `{var {:kind :fn|:macro :clj #{..} :cljs #{..}}}`.
   `live`     — `{var (#{arity} | nil)}` from `cljs-publics/emit-ns-signatures`.

   Only FUNCTION vars are reconciled: a re-frame.ui.test function can carry a
   host-specific runtime arity, so the CLJS surface is where a CLJS-only arity
   reshape must go RED (the reader-conditional `:clj`/`:cljs` difference is
   represented intentionally, never forced equal). MACROS are host-invariant
   (one `.cljc` definition expanded on both hosts), so their programmer-visible
   call grammar is pinned once on the JVM lane, and the analyzer does not
   reliably surface their arglists anyway. A FUNCTION the analyzer surfaces no
   arity for is itself drift (`:arity-unobserved`)."
  [contract live]
  (->> contract
       (keep (fn [[var {:keys [kind cljs]}]]
               (when (= kind :fn)
                 (let [got (get live var)]
                   (cond
                     (nil? got)      {:kind :arity-unobserved :var var :expected cljs}
                     (not= cljs got) {:kind :arity-mismatch   :var var :expected cljs :got got})))))
       (sort-by :var)
       vec))

(defn signature-report
  "Render a `signature-problems` seq to an actionable multi-line string — the
   message the probe's failing arity assertion prints."
  [problems]
  (str/join
   "\n"
   (concat
    ["CLJS ui.test host-arity DRIFT (rf2-5bcdi): a re-frame.ui.test FUNCTION's"
     "live CLJS arity no longer matches the :cljs signature contract in"
     "spec/api-manifest-metadata.edn (:ui-test-signatures). Reshape the source"
     "or reconcile the contract until this is green. Problems:"]
    (map (fn [{:keys [kind var expected got]}]
           (case kind
             :arity-mismatch
             (str "    " var ": live CLJS " (pr-str got)
                  " ; contract :cljs " (pr-str expected))
             :arity-unobserved
             (str "    " var ": the analyzer surfaced NO arity (expected :cljs "
                  (pr-str expected) ") — a function must be observable")))
         problems))))

(defn report
  "Render a `reconcile` result to an actionable multi-line string —
   the message the probe's failing assertion prints. Mirrors the tone of
   the JVM generator's drift report."
  [{:keys [stale missing]}]
  (str/join
   "\n"
   (concat
    ["CLJS manifest probe DRIFT: the live ClojureScript public surface no"
     "longer matches the :cljs-only rows in spec/api-manifest-metadata.edn."
     "Reconcile the sidecar (+ regenerate spec/api-manifest.edn via"
     "clojure -M -m re-frame.api-manifest.gen) until this is green."]
    (when (seq stale)
      (cons "  Rows with NO live public var (removed / renamed in source):"
            (map (fn [{:keys [namespace var]}]
                   (str "    - " namespace "/" var))
                 stale)))
    (when (seq missing)
      (cons "  Live public vars with NO sidecar row (added to a fully-rowed ns):"
            (map (fn [{:keys [namespace var live-kind]}]
                   (str "    + " namespace "/" var " (" live-kind ")"))
                 missing))))))
