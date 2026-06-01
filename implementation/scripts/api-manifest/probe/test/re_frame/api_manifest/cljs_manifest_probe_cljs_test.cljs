(ns re-frame.api-manifest.cljs-manifest-probe-cljs-test
  "CLJS-side public-var enumeration probe for the API manifest (rf2-2mtte;
  follow-on to the rf2-3nbl5.2 keystone).

  The JVM manifest generator (`re-frame.api-manifest.gen`) runtime-verifies
  every JVM-loadable public namespace via `ns-publics`. The Reagent / UIx /
  Helix adapter namespaces and the Xray `mount` host-embed surface are
  ClojureScript-ONLY — they cannot be `require`d on the JVM — so their rows
  live in `spec/api-manifest-metadata.edn` under `:cljs-only`. This probe is
  their runtime verifier: it enumerates each namespace's LIVE public vars at
  compile time (via the analyzer, `cljs-publics/emit-ns-publics`) and
  reconciles them against the curated rows. A var added / removed / renamed
  in any covered surface turns this test RED — exactly the way the JVM
  drift-check fails for the JVM-loadable surfaces.

  ## Coverage

  - `re-frame.adapter.reagent` / `.uix` / `.helix` — fully-rowed: their
    entire public surface IS the documented adapter API (spec/API.md
    §UIx/Helix adapter), so BOTH directions are checked (a var added
    without a row, or a row with no live var, → RED).
  - The Xray public surface (rf2-jhn46) — curated subsets (direction 1
    only). spec/API.md tiers the Xray surfaces `internal-public` (the
    `mount` shell lifecycle, the `panels` `mount-<panel>!` family, the
    panel `Panel` reg-views) and `tooling` (the `config` published
    constants, the `open-in-editor` chip, the `runtime` Xray↔MCP read
    seam), with the rest \"otherwise unrowed-internal\". So only the rowed
    vars are verified to still resolve (a var renamed / removed under a
    rowed name → RED); the surfaces are NOT held to full completeness.
    Covered namespaces:
      - `day8.re-frame2-xray.mount` — `mounted?` read.
      - `day8.re-frame2-xray.panels` — the `mount-<panel>!` aggregators.
      - `day8.re-frame2-xray.panels.{epoch-panel,app-db-diff,reactive-panel,
        trace,machine-inspector,routing}` — the six Dynamic `Panel`s.
      - `day8.re-frame2-xray.static.{flows,interceptors,routes,schemas}.panel`
        — the four Static `Panel`s.
      - `day8.re-frame2-xray.config` — the published layout-host constants.
      - `day8.re-frame2-xray.open-in-editor` — the editor-URI chip.
      - `day8.re-frame2-xray.runtime` — the discovery sentinel + safe-egress
        entry points.

  The pair-MCP server (`re-frame2-pair-mcp.server`) is the third
  CLJS-only surface the keystone names. It compiles under the pair-MCP
  artefact's OWN shadow-cljs build (`tools/re-frame2-pair-mcp`,
  `:server-test`) — it is not on the consolidated `:node-test`
  classpath and pulls the npm MCP SDK — so its probe rides that build;
  the shared `cljs-publics` + `cljs-probe` namespaces are the reusable
  mechanism for it (see rf2-2mtte PR notes)."
  (:require-macros [re-frame.api-manifest.cljs-publics
                    :refer [emit-ns-publics emit-cljs-only-rows]])
  (:require [cljs.test :refer-macros [deftest is testing]]
            [re-frame.api-manifest.cljs-probe :as probe]
            ;; The covered CLJS-only namespaces. The `:require` forces the
            ;; analyzer to analyse each before `emit-ns-publics` expands,
            ;; so the macro reads a real surface. The aliases are unused at
            ;; runtime (the surface is read at compile time) but the
            ;; requires are load-bearing.
            [re-frame.adapter.reagent]
            [re-frame.adapter.uix]
            [re-frame.adapter.helix]
            [day8.re-frame2-xray.mount]
            [day8.re-frame2-xray.panels]
            [day8.re-frame2-xray.panels.epoch-panel]
            [day8.re-frame2-xray.panels.app-db-diff]
            [day8.re-frame2-xray.panels.reactive-panel]
            [day8.re-frame2-xray.panels.trace]
            [day8.re-frame2-xray.panels.machine-inspector]
            [day8.re-frame2-xray.panels.routing]
            [day8.re-frame2-xray.static.flows.panel]
            [day8.re-frame2-xray.static.interceptors.panel]
            [day8.re-frame2-xray.static.routes.panel]
            [day8.re-frame2-xray.static.schemas.panel]
            [day8.re-frame2-xray.config]
            [day8.re-frame2-xray.open-in-editor]
            [day8.re-frame2-xray.runtime]))

;; ---------------------------------------------------------------------------
;; The live CLJS public surface, captured at compile time.
;; ---------------------------------------------------------------------------

(def live-publics
  "`{ns-string [[var-string kind-kw] ...]}` for every covered CLJS-only
   namespace, enumerated off the analyzer at compile time."
  {"re-frame.adapter.reagent"  (emit-ns-publics re-frame.adapter.reagent)
   "re-frame.adapter.uix"      (emit-ns-publics re-frame.adapter.uix)
   "re-frame.adapter.helix"    (emit-ns-publics re-frame.adapter.helix)
   "day8.re-frame2-xray.mount" (emit-ns-publics day8.re-frame2-xray.mount)
   ;; The Xray public surface (rf2-jhn46) — curated subsets (direction 1).
   "day8.re-frame2-xray.panels"                      (emit-ns-publics day8.re-frame2-xray.panels)
   "day8.re-frame2-xray.panels.epoch-panel"          (emit-ns-publics day8.re-frame2-xray.panels.epoch-panel)
   "day8.re-frame2-xray.panels.app-db-diff"          (emit-ns-publics day8.re-frame2-xray.panels.app-db-diff)
   "day8.re-frame2-xray.panels.reactive-panel"       (emit-ns-publics day8.re-frame2-xray.panels.reactive-panel)
   "day8.re-frame2-xray.panels.trace"                (emit-ns-publics day8.re-frame2-xray.panels.trace)
   "day8.re-frame2-xray.panels.machine-inspector"    (emit-ns-publics day8.re-frame2-xray.panels.machine-inspector)
   "day8.re-frame2-xray.panels.routing"              (emit-ns-publics day8.re-frame2-xray.panels.routing)
   "day8.re-frame2-xray.static.flows.panel"          (emit-ns-publics day8.re-frame2-xray.static.flows.panel)
   "day8.re-frame2-xray.static.interceptors.panel"   (emit-ns-publics day8.re-frame2-xray.static.interceptors.panel)
   "day8.re-frame2-xray.static.routes.panel"         (emit-ns-publics day8.re-frame2-xray.static.routes.panel)
   "day8.re-frame2-xray.static.schemas.panel"        (emit-ns-publics day8.re-frame2-xray.static.schemas.panel)
   "day8.re-frame2-xray.config"                      (emit-ns-publics day8.re-frame2-xray.config)
   "day8.re-frame2-xray.open-in-editor"              (emit-ns-publics day8.re-frame2-xray.open-in-editor)
   "day8.re-frame2-xray.runtime"                     (emit-ns-publics day8.re-frame2-xray.runtime)})

(def fully-rowed
  "Namespaces whose ENTIRE public surface must be rowed (direction 2).
   The three adapter namespaces — their public surface IS the documented
   adapter API. The Xray mount surface is a curated subset (direction 1
   only), so it is deliberately absent here."
  #{"re-frame.adapter.reagent"
    "re-frame.adapter.uix"
    "re-frame.adapter.helix"})

(def cljs-only-rows
  "The `:cljs-only` rows from spec/api-manifest-metadata.edn, embedded at
   compile time (no runtime filesystem)."
  (emit-cljs-only-rows))

;; ---------------------------------------------------------------------------
;; The probe.
;; ---------------------------------------------------------------------------

(deftest cljs-only-surface-in-sync
  (testing "live CLJS public surface reconciles with the :cljs-only rows"
    (let [result (probe/reconcile live-publics cljs-only-rows fully-rowed)]
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
