(ns re-frame.api-manifest.cljs-manifest-probe-cljs-test
  "CLJS-side public-var enumeration probe for the API manifest (rf2-2mtte;
  follow-on to the rf2-3nbl5.2 keystone).

  The JVM manifest generator (`re-frame.api-manifest.gen`) runtime-verifies
  every JVM-loadable public namespace via `ns-publics`. The Reagent / UIx
  adapter namespaces and the Xray `mount` host-embed surface are
  ClojureScript-ONLY — they cannot be `require`d on the JVM — so their rows
  live in `spec/api-manifest-metadata.edn` under `:cljs-only`. This probe is
  their runtime verifier: it enumerates each namespace's LIVE public vars at
  compile time (via the analyzer, `cljs-publics/emit-ns-publics`) and
  reconciles them against the curated rows. A var added / removed / renamed
  in any covered surface turns this test RED — exactly the way the JVM
  drift-check fails for the JVM-loadable surfaces.

  ## Coverage

  - `re-frame.adapter.reagent` / `.uix` — fully-rowed: their
    entire public surface IS the documented adapter API (spec/API.md
    §UIx adapter), so BOTH directions are checked (a var added
    without a row, or a row with no live var, → RED).
  - `re-frame.hicasso` (rf2-phm7g) — fully-rowed, and the one covered
    namespace that is NOT CLJS-only. The door is a SPLIT-HOST `.cljc`:
    its `#?(:clj …)` arm is three authoring macros the JVM generator
    introspects and rows under `:classification`, and its `#?(:cljs …)`
    arm is eleven runtime aliases the JVM cannot see, rowed under
    `:cljs-only`. Both arms land on ONE live analyzer surface, because
    the macros are `:require-macros`-referred by the door's own
    ClojureScript arm — so `reconcile-rows` carries both row sets and
    the probe holds the whole door to full completeness. This is the
    case `:jvm-only-classification` mirrors from the other side, and
    the reason that key needs no entry here.
  - `day8.re-frame2-xray.core` (rf2-ar67) — fully-rowed, and the ONLY
    Xray namespace held to completeness. It is the Xray FAÇADE, the third
    of the three spec/Conventions.md §Facade policy names, and all
    sixteen of its exports are enumerated by name in
    tools/xray/spec/API.md §Wider public surface — so an export arriving
    there unclassified is the accretion that policy exists to stop, and
    BOTH directions are checked (an unrowed addition, or a row with no
    live var, → RED). The rest of Xray stays a curated subset for the
    reason below; they are ordinary namespaces that publish a few
    documented vars, not enumerated surfaces.
  - The rest of the Xray public surface (rf2-jhn46) — curated subsets
    (direction 1
    only). spec/API.md tiers the Xray surfaces `internal-public` (the
    `mount` shell lifecycle + the `panels` `mount-<panel>!` family — the
    supported host-embed surface), `implementation` (the panel-leaf `Panel`
    reg-views, exported only so the shell composes them — NOT host-facing;
    rf2-oekz6s) and `tooling` (the `config` published constants, the
    `open-in-editor` chip), with the rest
    \"otherwise unrowed-internal\". The probe checks EXISTENCE, not tier, so
    these tier reclassifications do not affect it. So only the rowed
    vars are verified to still resolve (a var renamed / removed under a
    rowed name → RED); the surfaces are NOT held to full completeness.
    Covered namespaces:
      - `day8.re-frame2-xray.mount` — `mounted?` read.
      - `day8.re-frame2-xray.panels` — the `mount-<panel>!` aggregators.
      - `day8.re-frame2-xray.panels.{epoch-panel,app-db-diff,reactive-panel,
        trace,machine-inspector,routing}` — the six Dynamic `Panel`s.
      - `day8.re-frame2-xray.static.{flows,interceptors,routes,schemas}.panel`
        — four Static `Panel`s — and `static.machines.panel` (lowercase
        `panel`), the five Static reg-views.
      - `day8.re-frame2-xray.config` — the published layout-host constants.
      - `day8.re-frame2-xray.open-in-editor` — the editor-URI chip.

  The pair-MCP server (`re-frame2-pair-mcp.server`) is the third
  CLJS-only surface the keystone names. It compiles under the pair-MCP
  artefact's OWN shadow-cljs build (`tools/re-frame2-pair-mcp`,
  `:server-test`) — it is not on the consolidated `:node-test`
  classpath and pulls the npm MCP SDK — so its probe rides that build;
  the shared `cljs-publics` + `cljs-probe` namespaces are the reusable
  mechanism for it (see rf2-2mtte PR notes)."
  (:require-macros [re-frame.api-manifest.cljs-publics
                    :refer [emit-ns-publics emit-cljs-only-rows
                            emit-classification-rows]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.api-manifest.cljs-probe :as probe]
            ;; The covered CLJS-only namespaces. The `:require` forces the
            ;; analyzer to analyse each before `emit-ns-publics` expands,
            ;; so the macro reads a real surface. The aliases are unused at
            ;; runtime (the surface is read at compile time) but the
            ;; requires are load-bearing.
            [re-frame.adapter.reagent]
            [re-frame.adapter.uix]
            [re-frame.hicasso]
            [day8.re-frame2-xray.core]
            [day8.re-frame2-xray.mount]
            [day8.re-frame2-xray.panels]
            [day8.re-frame2-xray.panels.epoch-panel]
            [day8.re-frame2-xray.panels.app-db-diff]
            [day8.re-frame2-xray.panels.reactive-panel]
            [day8.re-frame2-xray.panels.trace]
            [day8.re-frame2-xray.panels.machine-inspector]
            [day8.re-frame2-xray.panels.routing]
            [day8.re-frame2-xray.panels.resources]
            [day8.re-frame2-xray.static.flows.panel]
            [day8.re-frame2-xray.static.interceptors.panel]
            [day8.re-frame2-xray.static.machines.panel]
            [day8.re-frame2-xray.static.routes.panel]
            [day8.re-frame2-xray.static.schemas.panel]
            [day8.re-frame2-xray.config]
            [day8.re-frame2-xray.open-in-editor]))

;; ---------------------------------------------------------------------------
;; The live CLJS public surface, captured at compile time.
;; ---------------------------------------------------------------------------

(def live-publics
  "`{ns-string [[var-string kind-kw] ...]}` for every covered CLJS-only
   namespace, enumerated off the analyzer at compile time."
  {"re-frame.adapter.reagent"  (emit-ns-publics re-frame.adapter.reagent)
   "re-frame.adapter.uix"      (emit-ns-publics re-frame.adapter.uix)
   ;; The Hicasso door (rf2-phm7g) — a SPLIT-HOST `.cljc`, and the first
   ;; namespace the probe covers that the JVM generator ALSO owns. The
   ;; analyzer surface here is the door's `#?(:cljs …)` arm (the runtime
   ;; aliases, rowed under `:cljs-only`) plus the three `:require-macros`-
   ;; referred authoring macros (rowed under `:classification`, because the
   ;; JVM introspects them). `reconcile-rows` below carries both sets, so
   ;; the two arms reconcile against one live surface.
   "re-frame.hicasso"          (emit-ns-publics re-frame.hicasso)
   ;; The Xray FAÇADE (rf2-ar67) — fully-rowed, unlike every other Xray
   ;; namespace here. See `fully-rowed` below for why the postures differ.
   "day8.re-frame2-xray.core"  (emit-ns-publics day8.re-frame2-xray.core)
   "day8.re-frame2-xray.mount" (emit-ns-publics day8.re-frame2-xray.mount)
   ;; The rest of the Xray public surface (rf2-jhn46) — curated subsets
   ;; (direction 1).
   "day8.re-frame2-xray.panels"                      (emit-ns-publics day8.re-frame2-xray.panels)
   "day8.re-frame2-xray.panels.epoch-panel"          (emit-ns-publics day8.re-frame2-xray.panels.epoch-panel)
   "day8.re-frame2-xray.panels.app-db-diff"          (emit-ns-publics day8.re-frame2-xray.panels.app-db-diff)
   "day8.re-frame2-xray.panels.reactive-panel"       (emit-ns-publics day8.re-frame2-xray.panels.reactive-panel)
   "day8.re-frame2-xray.panels.trace"                (emit-ns-publics day8.re-frame2-xray.panels.trace)
   "day8.re-frame2-xray.panels.machine-inspector"    (emit-ns-publics day8.re-frame2-xray.panels.machine-inspector)
   "day8.re-frame2-xray.panels.routing"              (emit-ns-publics day8.re-frame2-xray.panels.routing)
   "day8.re-frame2-xray.panels.resources"            (emit-ns-publics day8.re-frame2-xray.panels.resources)
   "day8.re-frame2-xray.static.flows.panel"          (emit-ns-publics day8.re-frame2-xray.static.flows.panel)
   "day8.re-frame2-xray.static.interceptors.panel"   (emit-ns-publics day8.re-frame2-xray.static.interceptors.panel)
   "day8.re-frame2-xray.static.machines.panel"       (emit-ns-publics day8.re-frame2-xray.static.machines.panel)
   "day8.re-frame2-xray.static.routes.panel"         (emit-ns-publics day8.re-frame2-xray.static.routes.panel)
   "day8.re-frame2-xray.static.schemas.panel"        (emit-ns-publics day8.re-frame2-xray.static.schemas.panel)
   "day8.re-frame2-xray.config"                      (emit-ns-publics day8.re-frame2-xray.config)
   "day8.re-frame2-xray.open-in-editor"              (emit-ns-publics day8.re-frame2-xray.open-in-editor)})

(def fully-rowed
  "Namespaces whose ENTIRE public surface must be rowed (direction 2).
   The two adapter namespaces — their public surface IS the documented
   adapter API — and the Hicasso door, which is likewise its whole
   documented authoring surface (`re-frame.hicasso.impl.*` is where the
   non-surface lives, and the door re-exports none of it).

   `day8.re-frame2-xray.core` joins them (rf2-ar67) on the same test, and
   the test is what separates it from its own siblings: a namespace belongs
   here when its ENTIRE public surface is the documented API, and the Xray
   façade's is — tools/xray/spec/API.md §Wider public surface enumerates all
   sixteen exports by name, and the façade re-exports no internals (the
   per-concern namespaces `mount` / `config` / `focus` / `registry` are the
   seams Xray's own code reads). Holding it in BOTH directions is what makes
   the manifest's sixteen `:facade? true` rows a real inventory rather than a
   snapshot: a seventeenth export cannot arrive without a row carrying the
   `:justification` and `:action` spec/Conventions.md §Facade policy demands
   at diff time.

   The OTHER Xray namespaces stay curated subsets (direction 1 only) and are
   deliberately absent: `mount`, `panels`, `config` and the panel leaves are
   implementation namespaces that publish a few documented vars, so full
   completeness would oblige rows for their internals."
  #{"re-frame.adapter.reagent"
    "re-frame.adapter.uix"
    "re-frame.hicasso"
    "day8.re-frame2-xray.core"})

(def cljs-only-rows
  "The `:cljs-only` rows from spec/api-manifest-metadata.edn, embedded at
   compile time (no runtime filesystem)."
  (emit-cljs-only-rows))

(def hicasso-classification-rows
  "The `re-frame.hicasso` `:classification` rows — the door's three
   authoring macros (rf2-phm7g).

   Every other namespace this probe covers is CLJS-only, so all its rows
   live under `:cljs-only`. The Hicasso door does not: it is a `.cljc` the
   JVM generator owns, so its macros are curated under `:classification`
   and their `:kind`/`:tier` are the generator's. They still need
   reconciling HERE, because they are `:require-macros`-referred into the
   door's own ClojureScript arm and so appear on the live analyzer surface
   — and without them the `fully-rowed` completeness check above would
   report three live publics with no row."
  (emit-classification-rows "re-frame.hicasso"))

(def reconcile-rows
  "Every row the probe reconciles: the `:cljs-only` surfaces, plus the
   Hicasso door's JVM-owned `:classification` rows."
  (into (vec cljs-only-rows) hicasso-classification-rows))

;; ---------------------------------------------------------------------------
;; The probe.
;; ---------------------------------------------------------------------------

(deftest cljs-only-surface-in-sync
  (testing "live CLJS public surface reconciles with the :cljs-only rows"
    (let [result (probe/reconcile live-publics reconcile-rows fully-rowed)]
      (is (probe/in-sync? result)
          (probe/report result)))))

(deftest every-covered-namespace-was-analysed
  (testing "each covered namespace enumerates at least one public var"
    ;; Guards against a silent empty enumeration (e.g. a require dropped,
    ;; so the macro reads an un-analysed ns and emits []). An empty surface
    ;; would make the reconciliation vacuously green.
    (doseq [[ns-str pairs] live-publics]
      (is (seq pairs)
          (str ns-str " enumerated no public vars — the namespace was "
               "probably not analysed (check the :require) or the analyzer "
               "env was unavailable.")))))

(deftest fully-rowed-namespaces-are-covered
  (testing "every fully-rowed namespace is actually loaded by the probe"
    (doseq [ns-str fully-rowed]
      (is (contains? live-publics ns-str)
          (str ns-str " is marked :fully-rowed but is not in live-publics "
               "— add its :require + emit-ns-publics entry.")))))

(deftest runtime-verified-rows-are-actually-covered
  (testing ":runtime-verified? true means the probe really covers that namespace"
    ;; NON-VACUITY, and generic (rf2-phm7g). Every check above is conditional
    ;; on a namespace being IN `live-publics`: `reconcile` filters its rows to
    ;; the covered set, so deleting a namespace's `emit-ns-publics` entry
    ;; removes its rows from the reconciliation and the probe goes GREEN over a
    ;; surface it no longer looks at — the exact fail-open shape that left the
    ;; Hicasso door uninventoried on both hosts.
    ;;
    ;; The sidecar already states the intended invariant, in a field: a
    ;; `:cljs-only` row is `:runtime-verified? true` "once the probe covers its
    ;; namespace". That was a hand-maintained boolean nothing executed. Here it
    ;; is executed. Uncovered-by-design rows (`re-frame.core`'s reader-
    ;; conditional pair, which the JVM generator owns) carry `false` and are
    ;; unaffected, so this asserts the flag rather than the roster and needs no
    ;; edit when a namespace is legitimately added or dropped.
    (doseq [ns-str (->> cljs-only-rows
                        (filter :runtime-verified?)
                        (map :namespace)
                        distinct
                        sort)]
      (is (contains? live-publics ns-str)
          (str ns-str " has :cljs-only rows marked :runtime-verified? true, "
               "but the probe does not enumerate it — either add its :require "
               "+ emit-ns-publics entry, or set those rows "
               ":runtime-verified? false in spec/api-manifest-metadata.edn.")))))
