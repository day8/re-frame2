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
       surface is the documented adapter API per spec/API.md §UIx
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

  This EXISTENCE-only rule is the `reconcile` fn's — it covers the
  `:cljs-only` ADAPTER/Xray surfaces, which bind fn-valued value-defs the
  analyzer cannot tell from a plain `:var`. The re-frame.ui.test host-signature
  reconciler below (`signature-problems`) DOES reconcile `:kind` (rf2-d7sso):
  that surface is real `defn`s + `defmacro`s (no value-defs), so the analyzer
  classifies it reliably, and the sidecar's declared kind is VALIDATED against
  the live analyzer kind rather than trusted — closing the seam where the JVM
  lane once ignored the sidecar kind while this lane trusted it to select checks.

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
  "Pure host-signature reconciler for the CLJS (:cljs) lane (rf2-5bcdi; made
   KIND-AWARE + EXACT — rf2-d7sso). Reconciles the sidecar signature contract
   against the live CLJS analyzer surface. Returns a sorted problems vector;
   empty ⇒ in sync.

   `contract` — the sidecar `:vars` map
                `{var {:kind :fn|:macro :clj #{..} :cljs #{..}}}`.
   `surface`  — `{var {:kind kw :arities (#{arity} | nil)}}` from
                `cljs-publics/emit-ns-surface` — the live CLJS classification
                (`:kind`) + host arities.

   The sidecar's declared `:kind` is NOT trusted to SELECT checks (the
   rf2-d7sso seam): it is RECONCILED against the live analyzer kind and a
   disagreement is REJECTED (`:kind-mismatch`) — so a sidecar kind flipped
   `:fn`→`:macro` reddens this lane instead of silently skipping the entry.
   Arity checks are then selected by the AUTHORITATIVE live kind:

     - FUNCTIONS are arity-checked against `:cljs` (a re-frame.ui.test function
       can carry a host-specific runtime arity, so a CLJS-only reshape goes RED
       here; the reader-conditional `:clj`/`:cljs` difference is represented
       intentionally, never forced equal). A function the analyzer surfaces no
       arity for is itself drift (`:arity-unobserved`).
     - MACROS are host-invariant (one `.cljc` definition expanded on both
       hosts) — their call grammar is pinned once on the JVM lane, against the
       live JVM `:arglists`, and the analyzer does not reliably surface macro
       arglists so it is never treated as authority here. What IS checked is
       that the sidecar's two halves AGREE: a macro's `:cljs` grammar must
       EQUAL its `:clj` grammar (`:macro-host-variance`).

   MACRO HOST-INVARIANCE (rf2-qw31o). The `:cljs` half of a macro row used to
   be read by nothing at all: this lane skipped macros outright and the JVM
   lane reads only `:clj`, so an arbitrary mutation to `render`'s or
   `with-root`'s `:cljs` grammar stayed green on BOTH lanes — a duplicated
   field carrying misleading contract authority. Requiring equality is what
   gives the stored `:cljs` meaning: `:clj` is pinned to the live JVM macro
   arglists on the other lane, so equality transitively pins `:cljs` to that
   same live authority WITHOUT consulting the CLJS analyzer. Functions are
   untouched — `flush!`'s 0-arity JVM vs 0/1-arity CLJS difference is real and
   stays independently exact on each lane.

   Names are reconciled exactly: a contract var the live surface does not
   expose → `:var-absent`; a live blessed var with no contract entry →
   `:uncontracted-var` (so a classified CLJS-only function with no host-arity
   contract, or an omitted entry, goes RED)."
  [contract surface]
  (->>
   (concat
    (mapcat
     (fn [[var {:keys [kind clj cljs]}]]
       (if-let [{live-kind :kind live-arities :arities} (get surface var)]
         (concat
          (when (not= kind live-kind)
            [{:kind :kind-mismatch :var var :declared kind :live-kind live-kind}])
          ;; Both branches are selected by the AUTHORITATIVE live kind.
          (when (= live-kind :fn)
            (cond
              (nil? live-arities)      [{:kind :arity-unobserved :var var :expected cljs}]
              (not= cljs live-arities) [{:kind :arity-mismatch :var var :expected cljs :got live-arities}]))
          (when (and (= live-kind :macro) (not= clj cljs))
            [{:kind :macro-host-variance :var var :expected clj :got cljs}]))
         [{:kind :var-absent :var var :expected cljs}]))
     contract)
    (keep (fn [[var _]]
            (when-not (contains? contract var)
              {:kind :uncontracted-var :var var}))
          surface))
   (sort-by :var)
   vec))

(defn signature-report
  "Render a `signature-problems` seq to an actionable multi-line string — the
   message the probe's failing signature assertion prints."
  [problems]
  (str/join
   "\n"
   (concat
    ["CLJS ui.test host-signature DRIFT (rf2-5bcdi/rf2-d7sso): a re-frame.ui.test"
     "public var's live CLJS classification/arity no longer matches the signature"
     "contract in spec/api-manifest-metadata.edn (:ui-test-signatures). The"
     "sidecar's declared :kind is reconciled against the live analyzer kind, so a"
     "stale/flipped kind is rejected here. Reshape the source or reconcile the"
     "contract until this is green. Problems:"]
    (map (fn [{:keys [kind var expected got declared live-kind]}]
           (case kind
             :kind-mismatch
             (str "    " var ": sidecar declares " (pr-str declared)
                  " ; live CLJS analyzer is " (pr-str live-kind))
             :arity-mismatch
             (str "    " var ": live CLJS " (pr-str got)
                  " ; contract :cljs " (pr-str expected))
             :macro-host-variance
             (str "    " var ": MACRO contract :clj " (pr-str expected)
                  " but :cljs " (pr-str got)
                  " — a ui.test macro is ONE .cljc defmacro expanded on both"
                  " hosts, so its call grammar cannot differ by host. Set :cljs"
                  " equal to :clj (" (pr-str expected) "); if the grammar really"
                  " changed, change BOTH.")
             :arity-unobserved
             (str "    " var ": the analyzer surfaced NO arity (expected :cljs "
                  (pr-str expected) ") — a function must be observable")
             :var-absent
             (str "    " var ": the contract names it but the live CLJS surface "
                  "does not expose it")
             :uncontracted-var
             (str "    " var ": live CLJS public var with no :ui-test-signatures "
                  "entry")))
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
